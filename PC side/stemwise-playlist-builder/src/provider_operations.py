"""One elapsed budget across provider attempts; the existing supervisor owns I/O."""
from __future__ import annotations

from contextlib import contextmanager, redirect_stdout
from contextvars import ContextVar
from functools import wraps
import json
import math
import re
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request

from stemwise_engine.common import owned_process_tree, WorkerRetirementError


class ProviderDeadline(TimeoutError):
    pass


class ProviderStageDeadline(ProviderDeadline):
    """An optional phase expired while its containing operation remains usable."""


class ProviderCancelled(InterruptedError):
    pass


def raise_if_operation_stopped(error):
    if (isinstance(error, ProviderCancelled) or
            isinstance(error, ProviderDeadline) and not isinstance(error, ProviderStageDeadline)):
        raise error


class ProviderError(RuntimeError):
    def __init__(self, error_type, http_status=None):
        if (not isinstance(error_type, str) or re.fullmatch('[A-Za-z][A-Za-z0-9_]{0,119}', error_type) is None
                or http_status is not None and (type(http_status) is not int or not 100 <= http_status <= 599)):
            raise ValueError('Provider failure cause is invalid')
        self.cause = {'exceptionType': error_type, 'httpStatus': http_status,
                      'retryable': isinstance(self, ProviderTransientError)}
        super().__init__('provider-' + error_type + (f'-http-{http_status}' if http_status else ''))


class ProviderTransientError(ProviderError):
    pass


def provider_failure_cause(error):
    seen = set()
    while error is not None and id(error) not in seen:
        if isinstance(error, (ProviderError, ProviderDeadline, ProviderCancelled)):
            return error
        seen.add(id(error))
        error = error.__cause__
    return None


def _attach_provider_cause(error, stderr, stage):
    from stemwise_engine.private_diagnostics import bounded_utf8_tail
    error.stderr = bounded_utf8_tail(b'provider-stage: '+stage.encode('ascii')+b'\n'+stderr).decode('utf-8')
    error.provider_stage = stage


_CURRENT = ContextVar('provider_operation', default=None)


class ProviderBudget:
    def __init__(self, seconds=60, *, cancelled=lambda: False, clock=time.monotonic, sleeper=time.sleep):
        if type(seconds) not in (int, float) or not math.isfinite(seconds) or seconds <= 0:
            raise ValueError('Provider budget must be positive and finite')
        self.clock, self.sleeper, self.cancelled = clock, sleeper, cancelled
        self.deadline = clock() + seconds

    def remaining(self):
        if self.cancelled():
            raise ProviderCancelled('provider-operation-cancelled')
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise ProviderDeadline('provider-operation-deadline')
        return remaining

    def pause(self, seconds):
        end = self.clock() + seconds
        while True:
            remaining = end - self.clock()
            if remaining <= 0:
                break
            self.sleeper(min(.05, remaining, self.remaining()))
        self.remaining()

    def run(self, arguments, *, phase_seconds=None):
        remaining = self.remaining()
        if phase_seconds is not None and (type(phase_seconds) not in (int, float)
                or not math.isfinite(phase_seconds) or phase_seconds <= 0):
            raise ValueError('Provider phase budget must be positive and finite')
        phase_deadline = self.clock() + min(remaining, phase_seconds if phase_seconds is not None else remaining)
        def remaining_time():
            remaining = self.remaining()
            phase_remaining = phase_deadline - self.clock()
            if phase_remaining <= 0:
                raise ProviderStageDeadline('provider-phase-deadline')
            return min(remaining, phase_remaining)
        command = [sys.executable, '-B', '-m', 'stemwise_engine.provider_operations', *arguments]
        stderr = b''
        try:
            with owned_process_tree(command, startup_check=remaining_time, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
                while True:
                    try:
                        stdout, stderr = process.communicate(timeout=min(.1, remaining_time()))
                        break
                    except subprocess.TimeoutExpired as pending:
                        stderr = pending.stderr or b''
                        remaining_time()
                remaining_time()
        except (ProviderDeadline, ProviderCancelled) as error:
            _attach_provider_cause(error, stderr, arguments[0])
            raise
        if process.returncode:
            if process.returncode == 76:
                try:
                    cause = json.loads(stderr.splitlines()[-1].decode('utf-8'))
                    message = cause['message']
                    if (cause.get('exceptionType') != 'WorkerRetirementError'
                            or not isinstance(message, str) or not message):
                        raise ValueError('Provider retirement result is invalid')
                except (UnicodeError, ValueError, TypeError, KeyError, IndexError):
                    message = 'provider-worker-retirement-failed'
                error = WorkerRetirementError(message)
                _attach_provider_cause(error, stderr, arguments[0])
                raise error
            kind = ProviderTransientError if process.returncode == 75 else ProviderError
            try:
                cause = json.loads(stderr[-4096:].splitlines()[-1].decode('utf-8'))
                if (not isinstance(cause, dict) or set(cause) != {'exceptionType', 'httpStatus', 'retryable'}
                        or cause['retryable'] is not (kind is ProviderTransientError)):
                    raise ValueError('Provider failure result is invalid')
                error = kind(cause['exceptionType'], cause['httpStatus'])
            except (UnicodeError, ValueError, TypeError, KeyError, IndexError):
                error = ProviderError('ProviderProtocolError')
            _attach_provider_cause(error, stderr, arguments[0])
            raise error
        return stdout


class _OptionalStageBudget(ProviderBudget):
    def __init__(self, seconds, parent):
        super().__init__(seconds, **({'clock': parent.clock, 'sleeper': parent.sleeper} if parent else {}))
        self.parent = parent

    def remaining(self):
        outer = self.parent.remaining() if self.parent is not None else math.inf
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise ProviderStageDeadline('provider-stage-deadline')
        return min(remaining, outer)


@contextmanager
def optional_provider_stage(seconds=60):
    budget = _OptionalStageBudget(seconds, _CURRENT.get())
    token = _CURRENT.set(budget)
    try:
        budget.remaining()
        yield budget
        budget.remaining()
    finally:
        _CURRENT.reset(token)


@contextmanager
def provider_operation(seconds=60, **options):
    current = _CURRENT.get()
    if current is not None:
        current.remaining()
        yield current
        return
    budget = ProviderBudget(seconds, **options)
    token = _CURRENT.set(budget)
    try:
        yield budget
    finally:
        _CURRENT.reset(token)


def bounded_provider_operation(function):
    @wraps(function)
    def execute(*args, **kwargs):
        with provider_operation() as budget:
            result = function(*args, **kwargs)
            budget.remaining()
            return result
    return execute


def current_budget():
    budget = _CURRENT.get()
    if budget is None:
        raise RuntimeError('Provider call requires its operation budget')
    budget.remaining()
    return budget


def retry(call, attempts=3):
    if type(attempts) is not int or not 1 <= attempts <= 3:
        raise ValueError('Provider attempt count is invalid')
    with provider_operation() as budget:
        for index in range(attempts):
            budget.remaining()
            try:
                result = call()
                budget.remaining()
                return result
            except (ProviderDeadline, ProviderCancelled):
                raise
            except (ProviderTransientError, TimeoutError, ConnectionError, urllib.error.URLError) as error:
                if isinstance(error, urllib.error.HTTPError) and error.code not in {408, 429, 500, 502, 503, 504}:
                    raise
                if index == attempts - 1:
                    raise
                budget.pause(.1 * (2 ** index))


def read_response(response, max_bytes):
    """Injected opener boundary; real network reads run in the owned child."""
    budget = current_budget()
    output = bytearray()
    reader = getattr(response, 'read1', response.read)
    while True:
        budget.remaining()
        block = reader(min(65536, max_bytes + 1 - len(output)))
        budget.remaining()
        output.extend(block)
        if len(output) > max_bytes:
            raise ValueError('provider-response-size-limit')
        if not block:
            return bytes(output)


@bounded_provider_operation
def fetch_bytes(url, *, max_bytes, headers=None, before_attempt=None):
    request = json.dumps({'url': url, 'maxBytes': max_bytes, 'headers': headers or {}}, ensure_ascii=False)
    def attempt():
        if before_attempt is not None:
            before_attempt()
        return current_budget().run(['--fetch', request])
    return retry(attempt)


class LyricsProvider:
    def _call(self, method, args, kwargs):
        value = current_budget().run(['--lyrics', json.dumps({'method': method, 'args': args, 'kwargs': kwargs})])
        return json.loads(value.decode('utf-8'))

    def search(self, *args, **kwargs):
        return self._call('search', args, kwargs)

    def get_watch_playlist(self, *args, **kwargs):
        return self._call('get_watch_playlist', args, kwargs)

    def get_lyrics(self, *args, **kwargs):
        return self._call('get_lyrics', args, kwargs)


def _worker():
    mode, raw = sys.argv[1:]
    value = json.loads(raw)
    try:
        if mode == '--fetch':
            if (set(value) != {'url', 'maxBytes', 'headers'} or type(value['maxBytes']) is not int
                    or not 0 < value['maxBytes'] <= 50 * 1024 * 1024):
                raise ValueError('provider-fetch-invalid')
            request = urllib.request.Request(value['url'], headers=value['headers'])
            with urllib.request.urlopen(request, timeout=30) as response:
                declared = response.headers.get('Content-Length')
                if declared is not None and int(declared) > value['maxBytes']:
                    raise ValueError('provider-response-size-limit')
                payload = response.read(value['maxBytes'] + 1)
            if len(payload) > value['maxBytes']:
                raise ValueError('provider-response-size-limit')
        elif mode == '--qobuz-http':
            from stemwise_media.transfer_budget import http_worker
            payload = http_worker(value)
        elif mode == '--lyrics':
            if (set(value) != {'method', 'args', 'kwargs'}
                    or value['method'] not in {'search', 'get_watch_playlist', 'get_lyrics'}):
                raise ValueError('provider-lyrics-invalid')
            from ytmusicapi import YTMusic
            import requests
            class Session(requests.Session):
                def request(self, *args, **kwargs):
                    kwargs['timeout'] = (10, 30)
                    response = super().request(*args, **kwargs)
                    response.raise_for_status()
                    return response
            with Session() as session:
                result = getattr(YTMusic(requests_session=session), value['method'])(
                    *value['args'], **value['kwargs'])
            payload = json.dumps(result, ensure_ascii=False, allow_nan=False).encode('utf-8')
            if len(payload) > 8 * 1024 * 1024:
                raise ValueError('provider-response-size-limit')
        elif mode == '--youtube':
            if (set(value) != {'url', 'options', 'download'} or not isinstance(value['url'], str)
                    or not isinstance(value['options'], dict) or type(value['download']) is not bool):
                raise ValueError('provider-youtube-invalid')
            from stemwise_engine.youtube import _ydl
            with redirect_stdout(sys.stderr), _ydl(value['options'], check_update=False) as downloader:
                info = downloader.extract_info(value['url'], download=value['download'])
                sanitizer = getattr(downloader, 'sanitize_info', None)
                completed_file = downloader.prepare_filename(info) if value['download'] and not value['options'].get('skip_download') else None
                info = sanitizer(info) if callable(sanitizer) else info
                if completed_file is not None and isinstance(info, dict):
                    info['_filename'] = completed_file
            payload = json.dumps(info, ensure_ascii=False, allow_nan=False).encode('utf-8')
            if len(payload) > 16 * 1024 * 1024:
                raise ValueError('provider-response-size-limit')
        elif mode == '--ytdlp-update':
            if set(value) != {'deno', 'probeUrl', 'scratchParent'}:
                raise ValueError('provider-update-invalid')
            from stemwise_engine.ytdlp_update import _update_ytdlp
            with redirect_stdout(sys.stderr):
                _update_ytdlp(Path(value['deno']), probe_url=value['probeUrl'],
                              scratch_parent=Path(value['scratchParent']))
            payload = b'{}'
        else:
            raise ValueError('provider-mode-invalid')
        sys.stdout.buffer.write(payload)
        return 0
    except Exception as error:
        # Private child causes stay on the captured error channel; the public failure stays typed.
        from stemwise_engine.private_diagnostics import error_record
        sys.stderr.write(error_record(error)['body'])
        if isinstance(error, WorkerRetirementError):
            sys.stderr.write('\n' + json.dumps({'exceptionType':'WorkerRetirementError',
                'message':str(error)}, ensure_ascii=True) + '\n')
            return 76
        transient = isinstance(error, (TimeoutError, ConnectionError, urllib.error.URLError))
        status = getattr(error, 'code', None) or getattr(getattr(error, 'response', None), 'status_code', None)
        if status is not None:
            transient = status in {408, 429, 500, 502, 503, 504}
        if status is None and type(error).__module__.startswith('requests'):
            transient = type(error).__name__ in {'Timeout', 'ConnectTimeout', 'ReadTimeout', 'ConnectionError'}
        sys.stderr.write('\n' + json.dumps({'exceptionType': type(error).__name__,
            'httpStatus': status, 'retryable': transient}, sort_keys=True) + '\n')
        return 75 if transient else 1


if __name__ == '__main__':
    raise SystemExit(_worker())
