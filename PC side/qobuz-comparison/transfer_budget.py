"""One elapsed deadline and byte budget across a current acquisition operation."""
from dataclasses import dataclass
from typing import Callable
import shutil
import time
import hashlib
import io
import math


@dataclass(frozen=True)
class TransferBudget:
    deadline: float
    max_bytes: int = 1024**3
    cancelled: Callable[[], bool] = lambda: False

    def remaining(self):
        if self.cancelled():
            from stemwise_engine.provider_operations import ProviderCancelled
            raise ProviderCancelled('provider-operation-cancelled')
        seconds = self.deadline-time.monotonic()
        if seconds <= 0:
            raise TimeoutError('Acquisition elapsed deadline exceeded; retained bytes can be resumed')
        return seconds

    def require_size(self, size):
        self.remaining()
        if size < 0 or size > self.max_bytes:
            raise ValueError('Acquisition exceeds its byte budget')


def copy_bounded(source, target, budget, *, offset=0, committed=None):
    total = offset
    reader = getattr(source, 'read1', source.read)
    while True:
        budget.remaining()
        chunk = reader(min(64*1024, budget.max_bytes-total+1))
        budget.remaining()
        if not chunk:
            break
        total += len(chunk)
        budget.require_size(total)
        written = target.write(chunk)
        if written != len(chunk):
            raise OSError('HTTP prefix write was incomplete')
        if committed is not None:
            committed(chunk, total)
    return total


def read_bounded(source, budget, *, max_bytes):
    target = io.BytesIO()
    copy_bounded(source, target, TransferBudget(budget.deadline, max_bytes, budget.cancelled))
    return target.getvalue()


def hash_bounded(path, budget, *, length=None):
    size = path.stat().st_size
    budget.require_size(size)
    remaining = size if length is None else length
    if type(remaining) is not int or not 0 <= remaining <= size:
        raise ValueError('Retained prefix length is invalid')
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while remaining and (chunk := source.read(min(1024*1024, remaining))):
            budget.remaining()
            digest.update(chunk)
            remaining -= len(chunk)
    if remaining:
        raise ValueError('Retained prefix changed during readback')
    budget.remaining()
    return digest.hexdigest()


def validate_archive(archive, budget, *, max_expanded_bytes=8*1024**3):
    """Validate archive structure independently of any selected copy operation."""
    budget.remaining()
    entries = archive.infolist()
    if len(entries) > 4096 or len({entry.filename for entry in entries}) != len(entries):
        raise ValueError('Album archive entry count or identity is invalid')
    expanded = sum(entry.file_size for entry in entries)
    if expanded > max_expanded_bytes or any(entry.file_size > 1024**3 for entry in entries):
        raise ValueError('Album archive exceeds its expansion budget')
    return entries


def require_free_space(directory, additional_bytes):
    """Admit only the next actual allocation on its destination filesystem."""
    if type(additional_bytes) is not int or additional_bytes < 0:
        raise ValueError('Album allocation size is invalid')
    if shutil.disk_usage(directory).free < additional_bytes + 64*1024*1024:
        raise ValueError('Insufficient free space for bounded album staging')


def http_request(url, budget, *, headers, partial=None, max_bytes=1024*1024):
    """The existing provider supervisor owns all blocking network work and retirement."""
    import json
    from pathlib import Path
    from stemwise_engine.provider_operations import provider_operation, current_budget
    request = {'url': url, 'headers': headers, 'partial': str(Path(partial).absolute()) if partial is not None else None,
               'maxBytes': budget.max_bytes if partial is not None else max_bytes, 'seconds': budget.remaining()}
    with provider_operation(budget.remaining(), cancelled=budget.cancelled):
        result = current_budget().run(['--qobuz-http', json.dumps(request)], phase_seconds=budget.remaining())
    budget.remaining()
    return result


def http_worker(value):
    import os
    import re
    import urllib.request
    from pathlib import Path
    from urllib.error import HTTPError
    from stemwise_engine.common import validate_generated_path, atomic_write_json, read_bounded_json
    import tempfile
    if (set(value) != {'url', 'headers', 'partial', 'maxBytes', 'seconds'}
            or not isinstance(value['url'], str) or not isinstance(value['headers'], dict)
            or type(value['maxBytes']) is not int or not 0 < value['maxBytes'] <= 4*1024**3
            or value['partial'] is None and value['maxBytes'] > 3*1024*1024
            or type(value['seconds']) not in (int, float) or not math.isfinite(value['seconds'])
            or value['seconds'] <= 0):
        raise ValueError('Invalid Qobuz HTTP operation')
    budget = TransferBudget(time.monotonic()+value['seconds'], value['maxBytes'])
    partial = value['partial']
    if partial is None:
        with urllib.request.urlopen(urllib.request.Request(value['url'], headers=value['headers']),
                timeout=min(60, budget.remaining())) as response:
            return read_bounded(response, budget, max_bytes=value['maxBytes'])
    project = Path(__file__).resolve().parents[2]
    partial = validate_generated_path(project, Path(partial))
    for attempt in range(3):
        retained = partial.stat().st_size if partial.exists() else 0
        budget.require_size(retained)
        identity_path = partial.with_name(partial.name+'.http.json')
        identity = read_bounded_json(identity_path, max_bytes=16*1024, root=partial.parent) if identity_path.exists() else None
        resumable = (isinstance(identity, dict) and set(identity) == {'url','etag','length','bytes','sha256'}
            and identity['url'] == value['url'] and isinstance(identity['etag'], str)
            and re.fullmatch(r'"[^"\r\n]+"', identity['etag']) is not None
            and type(identity['length']) is int
            and type(identity['bytes']) is int and isinstance(identity['sha256'], str)
            and re.fullmatch('[0-9a-f]{64}', identity['sha256']) is not None
            and identity['length'] >= identity['bytes'] > 0 and identity['bytes'] <= retained
            and identity['sha256'] == hash_bounded(partial, budget, length=identity['bytes']))
        if resumable and retained != identity['bytes']:
            with partial.open('rb+') as output:
                output.truncate(identity['bytes'])
                os.fsync(output.fileno())
            retained = identity['bytes']
        offset = retained if resumable else 0
        headers = value['headers'] | ({'Range': f'bytes={offset}-', 'If-Range':identity['etag']} if offset else {})
        try:
            with urllib.request.urlopen(urllib.request.Request(value['url'], headers=headers),
                    timeout=min(90, budget.remaining())) as response:
                length = int(response.headers.get('Content-Length', '0'))
                restart = retained > 0 and (offset == 0 or response.status == 200)
                etag = response.headers.get('ETag')
                strong_etag = isinstance(etag, str) and re.fullmatch(r'"[^"\r\n]+"', etag) is not None
                if response.status == 206:
                    match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', response.headers.get('Content-Range', ''))
                    if (not match or int(match[1]) != offset or int(match[2]) < offset
                            or int(match[2])+1 != int(match[3])
                            or length and length != int(match[3])-offset
                            or offset and (etag != identity['etag'] or int(match[3]) != identity['length'])):
                        raise ValueError('Server cannot safely resume this retained transfer')
                    expected = int(match[3])
                else:
                    if response.status != 200:
                        raise ValueError('Server did not return a complete file response')
                    expected = length
                budget.require_size(expected)
                if restart:
                    from stemwise_engine.common import durable_replace
                    require_free_space(partial.parent, expected or budget.max_bytes)
                    descriptor, name = tempfile.mkstemp(prefix='.'+partial.name+'.', suffix='.replacement', dir=partial.parent)
                    replacement = Path(name)
                    try:
                        with os.fdopen(descriptor, 'wb', buffering=0) as output:
                            size = copy_bounded(response, output, budget)
                            os.fsync(output.fileno())
                        if not size or replacement.stat().st_size != size or expected and size != expected:
                            raise ValueError('Incomplete replacement file transfer')
                        budget.remaining()
                        durable_replace(replacement, partial)
                        if strong_etag and expected:
                            atomic_write_json(identity_path, {'url':value['url'],'etag':etag,'length':expected,
                                'bytes':partial.stat().st_size,'sha256':hash_bounded(partial,budget)})
                        return b''
                    finally:
                        replacement.unlink(missing_ok=True)
                # Only fsynced, receipted prefixes can survive process death as resumable authority.
                with partial.open('ab' if partial.exists() else 'xb', buffering=0) as output:
                    digest = hashlib.sha256()
                    if offset:
                        with partial.open('rb') as prefix:
                            while chunk := prefix.read(1024*1024):
                                budget.remaining()
                                digest.update(chunk)
                    received, committed_bytes = offset, offset
                    def commit(total):
                        nonlocal committed_bytes
                        os.fsync(output.fileno())
                        atomic_write_json(identity_path, {'url':value['url'], 'etag':etag, 'length':expected,
                            'bytes':total, 'sha256':digest.hexdigest()})
                        committed_bytes = total
                    def checkpoint(chunk, total):
                        nonlocal received
                        digest.update(chunk)
                        received = total
                        if total - committed_bytes >= 64*1024:
                            commit(total)
                    if strong_etag and expected:
                        commit(offset)
                    try:
                        copy_bounded(response, output, budget, offset=offset,
                            committed=checkpoint if strong_etag and expected else None)
                    finally:
                        if strong_etag and expected and received != committed_bytes:
                            commit(received)
                    os.fsync(output.fileno())
                if strong_etag and expected:
                    atomic_write_json(identity_path, {'url':value['url'],'etag':etag,'length':expected,
                        'bytes':partial.stat().st_size,'sha256':hash_bounded(partial,budget)})
            budget.remaining()
            if not partial.stat().st_size or expected and partial.stat().st_size != expected:
                raise ValueError('Incomplete file transfer')
            return b''
        except HTTPError as error:
            budget.remaining()
            if (offset > 0 and error.code == 416 and identity['length'] == offset
                    and error.headers.get('ETag') == identity['etag']
                    and error.headers.get('Content-Range') == f'bytes */{offset}'
                    and partial.is_file() and not partial.is_symlink() and partial.stat().st_size == offset):
                return b''
            if attempt == 2: raise
        except Exception:
            budget.remaining()
            if attempt == 2: raise
