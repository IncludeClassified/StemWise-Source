"""Operation-owned acoustic admission; callers still own their durable song state."""
import fcntl
import errno
import hashlib
import math
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import uuid
from pathlib import Path

from stemwise_engine.common import (ProcessLockBusyError, WorkerRetirementError, WorkerTemporaryDirectory, atomic_write_json, exclusive_process_lock,
                    heavy_worker_lock_path, json_bytes, read_bounded_json,
                    parse_strict_json_bytes, sha256_file, validate_generated_path, durable_replace, atomic_write_bytes,
                    diagnostic_code)


MAX_RESPONSE_BYTES = 16 * 1024 * 1024


class AnalysisInputNotStarted(RuntimeError):
    """A retired serial worker never entered this still-pending request."""


def dispose_scratch(path, *, directory=False):
    """Disposal cannot revoke a result; retained request owners are scavenged on admission."""
    if isinstance(sys.exc_info()[1], WorkerRetirementError):
        return
    try:
        from stemwise_engine.common import worker_directory_cleanup
        with worker_directory_cleanup(path):
            shutil.rmtree(path) if directory else path.unlink(missing_ok=True)
    except (OSError, ProcessLockBusyError) as error:
        try:
            print(f'acoustic-disposal-pending:{path}:{diagnostic_code(error)}', file=sys.stderr)
        except (OSError, ValueError):
            pass


def _address(token):
    return '\0stemwise-acoustic-' + token


def _peer_has_model_lease(pid, lock_path):
    """The kernel's flock owner, never the editable PID text, authenticates replies."""
    try:
        expected = lock_path.stat()
        for descriptor in Path(f'/proc/{pid}/fd').iterdir():
            try:
                actual = descriptor.stat()
                if (actual.st_dev, actual.st_ino) != (expected.st_dev, expected.st_ino):
                    continue
                info = Path(f'/proc/{pid}/fdinfo/{descriptor.name}').read_text()
                for line in info.splitlines():
                    fields = line.split()
                    if (len(fields) == 9 and fields[0] == 'lock:'
                            and fields[2] in {'FLOCK', 'POSIX'}
                            and fields[3:6] == ['ADVISORY', 'WRITE', str(pid)]
                            and fields[-2:] == ['0', 'EOF']):
                        major, minor, inode = fields[6].split(':')
                        if (int(major, 16), int(minor, 16), int(inode)) == (
                                os.major(expected.st_dev), os.minor(expected.st_dev), expected.st_ino):
                            return _owner(pid) is not None
            except (OSError, ValueError):
                continue
    except OSError:
        pass
    return False


def _read_exact(connection, length, *, deadline=None):
    data = bytearray()
    while len(data) < length:
        if deadline is not None:
            remaining = deadline-time.monotonic()
            if remaining <= 0:
                raise TimeoutError('acoustic-caller-deadline')
            connection.settimeout(min(5, remaining))
        part = connection.recv(min(65536, length - len(data)))
        if not part:
            raise RuntimeError('Acoustic response was interrupted')
        data.extend(part)
    return bytes(data)


def _validate_response(response, request_hash):
    if (not isinstance(response, dict) or response.get('requestSha256') != request_hash
            or set(response) not in ({'requestSha256', 'analysis'}, {'requestSha256', 'error'},
                                    {'requestSha256', 'error', 'workerFailure'})):
        raise RuntimeError('Acoustic response belongs to another request')
    if 'workerFailure' in response:
        failure = response['workerFailure']
        if (not isinstance(failure, dict) or set(failure) != {'exitCode', 'reason', 'stderr'}
                or (failure['exitCode'] is not None and type(failure['exitCode']) is not int)
                or not isinstance(failure['reason'], str)
                or re.fullmatch(r'[a-z0-9-]{1,120}', failure['reason']) is None
                or not isinstance(failure['stderr'], str) or len(failure['stderr'].encode('utf-8')) > 12000):
            raise RuntimeError('Acoustic failure diagnostic is invalid')
    return response


def _preparation(value, project):
    if value is None:
        return None
    if (not isinstance(value, dict) or (set(value) != {
            'mode', 'family', 'compatibilitySha256', 'cacheKey', 'cacheRoot', 'cacheOutput'}
            and set(value) != {'mode', 'family', 'compatibilitySha256', 'cacheKey', 'cacheRoot', 'cacheOutput', 'diagnosticPath'})
            or value['mode'] != 'alignment-emissions' or value['family'] not in {'mms', 'wav2vec2'}
            or any(not isinstance(value[key], str) or re.fullmatch(r'[0-9a-f]{64}', value[key]) is None
                   for key in ('compatibilitySha256', 'cacheKey'))
            or any(not isinstance(value[key], str) or not Path(value[key]).is_absolute()
                   for key in ('cacheRoot', 'cacheOutput'))):
        raise ValueError('Unknown or malformed queued preparation')
    root = validate_generated_path(project.parent.parent, Path(value['cacheRoot']))
    output = validate_generated_path(root, Path(value['cacheOutput']), workspace_root=project.parent.parent)
    if output.name != f"emissions-{value['cacheKey']}.pt" or output == root:
        raise ValueError('Queued emission destination differs from its cache key')
    if 'diagnosticPath' in value:
        diagnostic = value['diagnosticPath']
        if not isinstance(diagnostic, str) or not Path(diagnostic).is_absolute() or not Path(diagnostic).name.endswith('.prepare.failure.private.txt'):
            raise ValueError('Queued emission diagnostic path is malformed')
        validate_generated_path(project.parent.parent, Path(diagnostic))
    return {**value, 'cacheRoot': str(root), 'cacheOutput': str(output)}


def _prepared_result(request, result):
    preparation = request.get('preparation')
    if preparation is None:
        return
    if (isinstance(result, dict) and set(result) == {'durationMs', 'deferToAlignmentWorker'}
            and type(result['durationMs']) is int and result['durationMs'] == request['durationMs']
            and result['deferToAlignmentWorker'] is True):
        return
    if (not isinstance(result, dict) or set(result) != {'durationMs', 'cacheKey', 'cacheSha256', 'cacheByteCount'}
            or result['durationMs'] != request['durationMs'] or result['cacheKey'] != preparation['cacheKey']
            or type(result['cacheByteCount']) is not int or result['cacheByteCount'] <= 0
            or not isinstance(result['cacheSha256'], str) or re.fullmatch(r'[0-9a-f]{64}', result['cacheSha256']) is None):
        raise ValueError('Queued emission reply differs from its request')
    path = validate_generated_path(Path(preparation['cacheRoot']), Path(preparation['cacheOutput']))
    if path.stat().st_size != result['cacheByteCount'] or sha256_file(path) != result['cacheSha256']:
        raise ValueError('Queued emission cache differs from its reply')


def _install_prepared(staged, request, result):
    preparation = request['preparation']
    if (not isinstance(result, dict) or set(result) != {'durationMs', 'cacheKey', 'cachePath', 'cacheSha256', 'cacheByteCount'}
            or result['cacheKey'] != preparation['cacheKey'] or result['durationMs'] != request['durationMs']):
        raise ValueError('Queued emission result differs from its context')
    source = validate_generated_path(staged, Path(result['cachePath']))
    if (source.name != f"emissions-{preparation['cacheKey']}.pt"
            or type(result['cacheByteCount']) is not int or result['cacheByteCount'] <= 0
            or source.stat().st_size != result['cacheByteCount'] or sha256_file(source) != result['cacheSha256']):
        raise ValueError('Queued emission artifact is missing or stale')
    destination = validate_generated_path(Path(preparation['cacheRoot']), Path(preparation['cacheOutput']))
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f'.{destination.name}.{uuid.uuid4().hex}.tmp')
    try:
        try:
            os.link(source, temporary)
        except OSError:
            shutil.copyfile(source, temporary)
        if sha256_file(temporary) != result['cacheSha256']:
            raise ValueError('Queued emission artifact changed during publication')
        durable_replace(temporary, destination)
    finally:
        dispose_scratch(temporary)
    published = {key: value for key, value in result.items() if key != 'cachePath'}
    _prepared_result(request, published)
    return published


def _retire_request(directory, request, response):
    request_hash = hashlib.sha256(json_bytes(request)).hexdigest()
    _validate_response(response, request_hash)
    if 'analysis' in response:
        try:
            unchanged = sha256_file(Path(request['audio'])) == request['audioSha256']
        except OSError:
            unchanged = False
        if not unchanged:
            response = {'requestSha256': request_hash, 'error': 'queued acoustic input changed'}
        else:
            try:
                if request.get('preparation') is not None and 'cachePath' in response['analysis']:
                    prepared = _install_prepared(directory.parent, request, response['analysis'])
                    response = {'requestSha256': request_hash, 'analysis': prepared}
                _prepared_result(request, response['analysis'])
            except (OSError, ValueError, TypeError, KeyError):
                response = {'requestSha256': request_hash, 'error': 'queued emission artifact is missing or stale'}
    durable_replace(directory / 'request.json', directory / 'retired.json')
    return response


def _receive(listener, lock_path, directory, request):
    try:
        connection, _ = listener.accept()
    except BlockingIOError:
        return None
    with connection:
        pid, uid, _ = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        if uid != os.getuid() or not _peer_has_model_lease(pid, lock_path):
            return None
        connection.settimeout(5)
        connection.sendall(b'1')
        length = struct.unpack('!Q', _read_exact(connection, 8, deadline=request.get('deadline')))[0]
        if not 0 < length <= MAX_RESPONSE_BYTES:
            raise RuntimeError('Acoustic response exceeds its bound')
        response = _retire_request(directory, request, parse_strict_json_bytes(
            _read_exact(connection, length, deadline=request.get('deadline'))))
        try:
            connection.sendall(b'1')
        except OSError:
            # Retirement already committed under the authenticated request owner.
            pass
        return response


def _send(request, response):
    raw = json_bytes(response)
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(5)
        connection.connect(_address(request['token']))
        if _read_exact(connection, 1) != b'1':
            raise RuntimeError('Acoustic receiver rejected the model owner')
        connection.sendall(struct.pack('!Q', len(raw)) + raw)
        # Queue age is not execution time: the admitted stage budget also bounds retirement.
        connection.settimeout(request['timeout'])
        if _read_exact(connection, 1) != b'1':
            raise RuntimeError('Acoustic receiver did not accept its response')


def _owner(pid):
    try:
        fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        return None if fields[0] == 'Z' else fields[19]
    except (OSError, IndexError):
        return None


def _live(directory, request):
    if _owner(request['pid']) != request['start']:
        return False
    try:
        owner = validate_generated_path(directory, directory / 'owner.lock').open('r+')
    except FileNotFoundError:
        return False
    with owner:
        try:
            fcntl.flock(owner, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
    return False


def _pending(root, producer, timeout):
    pending = []
    for directory in sorted(root.iterdir()):
        if not directory.is_dir() or len(directory.name) != 32:
            continue
        try:
            uuid.UUID(hex=directory.name)
            validate_generated_path(root, directory)
            retired = (directory / 'retired.json').is_file()
            request = read_bounded_json(directory / ('retired.json' if retired else 'request.json'),
                                        max_bytes=16384, root=root)
            fields = {'token', 'pid', 'start', 'created', 'producer', 'audio', 'audioSha256', 'durationMs', 'timeout'}
            if (not isinstance(request, dict) or not fields <= set(request)
                    or set(request) - fields - {'preparation', 'deadline'}
                    or request.get('token') != directory.name
                    or type(request.get('pid')) is not int or request['pid'] <= 0
                    or not isinstance(request.get('start'), str)
                    or type(request.get('created')) is not int
                    or not isinstance(request.get('producer'), str)):
                continue
            if 'preparation' in request:
                if request['preparation'] is None:
                    continue
                _preparation(request['preparation'], root.parents[1])
            if 'deadline' in request and (type(request['deadline']) not in (int, float)
                    or not math.isfinite(request['deadline'])):
                continue
            if not _live(directory, request):
                dispose_scratch(directory, directory=True)
                continue
            if 'deadline' in request and request['deadline'] <= time.monotonic():
                continue
            if not retired and request['producer'] == producer and request.get('timeout') == timeout:
                pending.append((directory, request))
        except (OSError, ValueError, KeyError):
            # Unrecognized or damaged requests never acquire model/result authority.
            continue
    return sorted(pending, key=lambda item: (item[1]['created'], item[0].name))[:2]


def _preparation_failure(request, error, workspace):
    preparation = request.get('preparation')
    if preparation is None:
        return ('native-model-retirement-failed' if isinstance(error, WorkerRetirementError)
                else 'acoustic song analysis failed')
    if 'diagnosticPath' in preparation:
        cause = f'{type(error).__name__}: {error}'.encode('utf-8', errors='replace')
        stderr = getattr(error, 'stderr', None)
        if isinstance(stderr, str):
            stderr = stderr.encode('utf-8', errors='replace')
        if isinstance(stderr, bytes):
            cause += b'\nChild stderr:\n' + stderr
        from stemwise_engine.private_diagnostics import bounded_utf8_tail
        try:
            path = validate_generated_path(workspace, Path(preparation['diagnosticPath']))
            atomic_write_bytes(path, bounded_utf8_tail(cause), root=workspace, workspace_root=workspace)
        except OSError:
            if isinstance(error, BaseException):
                error.stderr = bounded_utf8_tail(cause).decode('utf-8')
    return ('native-model-retirement-failed' if isinstance(error, WorkerRetirementError) or getattr(error, 'returncode', None) == 70
            else 'native-emission-preparation-failed')


def _dispatch(root, directory, project, producer, timeout, backend, *, local_request):
    selected = _pending(root, producer, timeout)
    if not selected:
        return
    workspace = project.parent.parent
    staged_owner = WorkerTemporaryDirectory(prefix='batch-', dir=directory)
    staged = Path(staged_owner.name)
    retirement_failure = None
    rows = []
    local_response = None
    local_token = directory.name
    def publish(target, request, response):
        nonlocal local_response
        try:
            if len(json_bytes(response)) > MAX_RESPONSE_BYTES:
                raise ValueError('Acoustic result exceeds its bound')
        except (ValueError, TypeError):
            response = {'requestSha256': response['requestSha256'], 'error': 'Acoustic result is invalid or exceeds its bound'}
        if request['token'] == local_token:
            local_response = _retire_request(target, local_request, response)
        elif _live(target, request):
            try:
                _send(request, response)
            except (OSError, RuntimeError):
                # A cancelled recipient owns its cleanup; it cannot complete from a file.
                pass
    try:
        for index, (directory, request) in enumerate(selected):
            try:
                audio = validate_generated_path(workspace, Path(request['audio']))
                if (type(request['durationMs']) is not int or request['durationMs'] <= 0
                        or type(request['timeout']) is not int or request['timeout'] <= 0
                        or sha256_file(audio) != request['audioSha256']):
                    raise ValueError('queued acoustic input changed')
                alias = staged / f'{index}.wav'
                try:
                    os.link(audio, alias)
                except OSError as error:
                    if error.errno not in {errno.EXDEV, errno.EPERM, errno.EOPNOTSUPP, errno.ENOSYS, errno.EMLINK}:
                        raise
                    shutil.copyfile(audio, alias)
                if sha256_file(alias) != request['audioSha256']:
                    raise ValueError('queued acoustic input changed')
                rows.append((directory, request, alias))
            except (OSError, ValueError, KeyError) as error:
                publish(directory, request, {
                    'requestSha256': hashlib.sha256(json_bytes(request)).hexdigest(),
                    'error': 'queued-acoustic-staging-io-failed' if isinstance(error, OSError) else 'queued acoustic input changed'})
        if not rows:
            return local_response
        try:
            unique = {}
            preparing = rows[0][1].get('preparation') is not None
            def identity(request):
                return (request['audioSha256'], request['durationMs'],
                        request.get('preparation', {}).get('cacheKey'))
            for index, (_, request, alias) in enumerate(rows):
                if preparing:
                    context = staged / str(index)
                    context.mkdir()
                    key = request['preparation']['cacheKey']
                    value = {'contextId': str(index), 'audio': str(alias), 'durationMs': request['durationMs'],
                             'audioSha256': request['audioSha256'], 'emissionCacheKey': key,
                             'cacheOutput': str(context / f'emissions-{key}.pt')}
                else:
                    value = (alias, request['durationMs'])
                unique.setdefault(identity(request), value)
            if preparing and len(rows) == 1:
                # One requested input needs no extra preparation process/model startup.
                results = [{'durationMs': rows[0][1]['durationMs'], 'deferToAlignmentWorker': True}]
            else:
                deadlines = [request['deadline']-time.monotonic()
                    for _, request, _ in rows if 'deadline' in request]
                remaining = min(timeout, min(deadlines)/len(unique)) if deadlines else timeout
                if remaining <= 0:
                    raise TimeoutError('acoustic-caller-deadline')
                results = backend(list(unique.values()), project, remaining)
            if not isinstance(results, list) or len(results) != len(unique):
                raise ValueError('acoustic batch result count differs')
            by_input = dict(zip(unique, results))
            results = [by_input[identity(request)] for _, request, _ in rows]
        except Exception as error:
            results = [error] * len(rows)
        retirement_failure = next((result for result in results if isinstance(result, WorkerRetirementError)), None)
        for (directory, request, alias), result in zip(rows, results):
            if not _live(directory, request):
                continue
            if isinstance(result, AnalysisInputNotStarted):
                continue
            response = {'requestSha256': hashlib.sha256(json_bytes(request)).hexdigest()}
            try:
                unchanged = (sha256_file(alias) == request['audioSha256']
                             and sha256_file(Path(request['audio'])) == request['audioSha256'])
            except OSError:
                unchanged = False
            if not unchanged:
                response['error'] = 'queued acoustic input changed'
            elif isinstance(result, (TimeoutError, subprocess.TimeoutExpired)):
                _preparation_failure(request, result, workspace)
                response['error'] = ('native-emission-preparation-timeout'
                                     if request.get('preparation') is not None else 'acoustic-batch-timeout')
            elif (not isinstance(result, dict) or type(result.get('durationMs')) is not int
                  or result.get('durationMs') != request['durationMs']):
                response['error'] = _preparation_failure(request, result, workspace)
            else:
                response['analysis'] = result
            if 'error' in response and isinstance(result, BaseException) and (
                    hasattr(result, 'returncode') or hasattr(result, 'stderr') or isinstance(result, subprocess.TimeoutExpired)):
                stderr = getattr(result, 'stderr', None) or ''
                raw = stderr if isinstance(stderr, bytes) else str(stderr).encode('utf-8', errors='replace')
                response['workerFailure'] = {'exitCode': getattr(result, 'returncode', None),
                    'reason': getattr(result, 'worker_reason', 'worker-timeout' if isinstance(result, subprocess.TimeoutExpired) else 'worker-exit'),
                    'stderr': raw[-12000:].decode('utf-8', errors='ignore')}
            publish(directory, request, response)
        if retirement_failure is not None:
            raise retirement_failure
        return local_response
    finally:
        staged_owner.cleanup(preserve=True)
        if retirement_failure is None:
            dispose_scratch(staged, directory=True)


def analyze(audio, duration, project, timeout, backend, *, preparation=None, deadline=None, cancelled=None):
    """Run one input immediately, or consume up to two already-pending inputs."""
    if type(duration) is not int or duration <= 0 or type(timeout) is not int or timeout <= 0:
        raise ValueError('Acoustic duration and timeout must be positive integers')
    if deadline is not None and (type(deadline) not in (int, float) or not math.isfinite(deadline)):
        raise ValueError('Caller deadline must be a finite monotonic time')
    def check_caller():
        if cancelled is not None and cancelled():
            from stemwise_engine.provider_operations import ProviderCancelled
            raise ProviderCancelled('acoustic-caller-cancelled')
        if deadline is not None and time.monotonic() >= deadline:
            raise TimeoutError('acoustic-queue-admission-deadline')
    project, audio = project.resolve(), audio.resolve()
    workspace = project.parent.parent
    preparation = _preparation(preparation, project)
    validate_generated_path(workspace, audio)
    root = validate_generated_path(workspace, project / 'runtime/acoustic-queue')
    root.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex
    directory = root / token
    directory.mkdir()
    try:
        source = Path(__file__).parent
        producer = hashlib.sha256(json_bytes({
            'sources': {name: sha256_file(source / name) for name in
                        ('analysis_queue.py', 'pipeline.py', 'analyzers.py')},
            'python': {'entry': str(Path(sys.executable).absolute()),
                       'resolved': str(Path(sys.executable).resolve()),
                       'prefix': sys.prefix, 'basePrefix': sys.base_prefix},
            'preparation': None if preparation is None else {key: preparation[key] for key in
                ('mode', 'family', 'compatibilitySha256')},
            'deadline': deadline,
            'environment': {name: os.environ.get(name) for name in
                            ('STEMWISE_ALLIN1_PYTHON', 'TORCH_HOME', 'HF_HOME', 'CUDA_VISIBLE_DEVICES',
                             'OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS')},
        })).hexdigest()
        start = _owner(os.getpid())
        if start is None:
            raise RuntimeError('Acoustic request owner cannot be established')
        request = {'token': token, 'pid': os.getpid(), 'start': start,
                   'created': time.monotonic_ns(), 'producer': producer,
                   'audio': str(audio), 'audioSha256': sha256_file(audio),
                   'durationMs': duration, 'timeout': timeout}
        if preparation is not None:
            request['preparation'] = preparation
        if deadline is not None:
            request['deadline'] = deadline
        request_hash = hashlib.sha256(json_bytes(request)).hexdigest()
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener, exclusive_process_lock(directory / 'owner.lock'):
            listener.bind(_address(token))
            listener.listen(8)
            listener.setblocking(False)
            atomic_write_json(directory / 'request.json', request)
            lock_path = heavy_worker_lock_path({'projectDir': str(project)})
            response = None
            while True:
                check_caller()
                if response is None:
                    response = _receive(listener, lock_path, directory, request)
                if response is not None:
                    _validate_response(response, request_hash)
                    if 'error' in response:
                        if response['error'] == 'native-model-retirement-failed':
                            error = WorkerRetirementError('Owned model workers could not be retired')
                        elif response['error'] in {'acoustic-batch-timeout', 'native-emission-preparation-timeout'}:
                            command = 'native-emission-preparation' if preparation is not None else 'acoustic-analysis'
                            error = subprocess.TimeoutExpired(command, timeout)
                        else:
                            error = RuntimeError(response['error'])
                        if 'workerFailure' in response:
                            failure = response['workerFailure']
                            error.returncode = failure['exitCode']
                            error.stderr = failure['stderr']
                            error.worker_reason = failure['reason']
                        raise error
                    if sha256_file(audio) != request['audioSha256']:
                        raise RuntimeError('queued acoustic input changed')
                    _prepared_result(request, response['analysis'])
                    return response['analysis']
                try:
                    with exclusive_process_lock(root / 'dispatch.lock', wait_seconds=0):
                        with exclusive_process_lock(lock_path, wait_seconds=0):
                            response = _dispatch(root, directory, project, producer, timeout, backend, local_request=request)
                except ProcessLockBusyError:
                    time.sleep(.1)
    finally:
        dispose_scratch(directory, directory=True)
