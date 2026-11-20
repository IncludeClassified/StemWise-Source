"""Dependency-light, finite JSON publication for isolated numerical runtimes."""
import json
import os
from pathlib import Path
import tempfile
import hashlib
import re


def recover_owner_publication(destination: Path, *, max_bytes: int):
    from stemwise_engine.common import exclusive_process_lock
    with exclusive_process_lock(destination.with_name('.'+destination.name+'.publication.lock')):
        return _recover_owner_publication(destination, max_bytes=max_bytes)


def _recover_owner_publication(destination: Path, *, max_bytes: int):
    from stemwise_engine.common import _read_bounded_regular_bytes, parse_strict_json_bytes, _fsync_directory
    marker = destination.with_name('.'+destination.name+'.publication-conflict.json')
    if not marker.exists():
        return None
    record = parse_strict_json_bytes(_read_bounded_regular_bytes(marker, max_bytes=4096, root=destination.parent))
    if (not isinstance(record, dict) or set(record) != {'formatVersion', 'phase', 'ownerFile',
            'stagedFile', 'displacedFile', 'expectedSha256', 'candidateSha256'}
            or record['formatVersion'] != 1 or record['phase'] not in {'pending', 'exchanged'}
            or record['ownerFile'] != destination.name
            or not isinstance(record['stagedFile'], str)
            or re.fullmatch(r'\.'+re.escape(destination.name)+r'\.[0-9a-f]{32}\.pending', record['stagedFile']) is None
            or not isinstance(record['displacedFile'], str)
            or record['displacedFile'] not in {record['stagedFile'], Path(record['stagedFile']).with_suffix('.conflict').name}
            or any(not isinstance(record[key], str) or Path(record[key]).name != record[key]
                   or record[key] in {'', '.', '..', destination.name, marker.name}
                   for key in ('stagedFile', 'displacedFile'))
            or any(not isinstance(record[key], str) or re.fullmatch('[0-9a-f]{64}', record[key]) is None
                   for key in ('expectedSha256', 'candidateSha256'))):
        raise ValueError('Owner publication recovery record is invalid')
    staged = destination.parent/record['stagedFile']
    displaced = destination.parent/record['displacedFile']
    def digest(path):
        return (hashlib.sha256(_read_bounded_regular_bytes(path, max_bytes=max_bytes,
                    root=destination.parent)).hexdigest() if path.exists() else None)
    current = digest(destination)
    expected, candidate = record['expectedSha256'], record['candidateSha256']
    if current is None and digest(displaced) == expected:
        os.link(displaced, destination)
        _fsync_directory(destination.parent)
        current = expected
    if current == candidate:
        for path in {staged, displaced}:
            retained = digest(path)
            if retained not in {None, expected, candidate}:
                raise ValueError(f'Owner publication conflict requires recovery: {marker}; owner bytes: {path}')
        for path in {staged, displaced}:
            path.unlink(missing_ok=True)
        completed = True
    elif current == expected:
        if digest(staged) not in {None, candidate} or digest(displaced) not in {None, expected}:
            raise ValueError(f'Owner publication conflict requires recovery: {marker}')
        if displaced != staged:
            displaced.unlink(missing_ok=True)
        completed = False
    else:
        raise ValueError(f'Owner publication conflict requires recovery: {marker}')
    marker.unlink()
    _fsync_directory(destination.parent)
    return {**record, 'completed': completed}


def publish_owner_file(destination: Path, replacement: Path, expected: bytes, *, max_bytes: int) -> None:
    from stemwise_engine.common import (atomic_write_json, exclusive_process_lock, read_bounded_regular_bytes,
                        _fsync_directory)
    marker = destination.with_name('.'+destination.name+'.publication-conflict.json')
    previous = replacement.with_suffix('.conflict')
    if (replacement.parent.resolve() != destination.parent.resolve()
            or re.fullmatch(r'\.'+re.escape(destination.name)+r'\.[0-9a-f]{32}\.pending', replacement.name) is None
            or replacement.name in {destination.name, marker.name}
            or previous.name == destination.name):
        raise ValueError('Owner publication candidate must be a separate file in the owner directory')
    with exclusive_process_lock(destination.with_name('.'+destination.name+'.publication.lock')):
        recovered = _recover_owner_publication(destination, max_bytes=max_bytes)
        if (recovered and recovered['completed']
                and recovered['expectedSha256'] == hashlib.sha256(expected).hexdigest()
                and (not replacement.exists() or hashlib.sha256(replacement.read_bytes()).hexdigest()
                     == recovered['candidateSha256'])):
            return
        if read_bounded_regular_bytes(destination, max_bytes=max_bytes, root=destination.parent) != expected:
            raise ValueError('Owner sidecar changed before publication; candidate remains private')
        record = {'formatVersion': 1, 'phase': 'pending', 'ownerFile': destination.name,
                  'stagedFile': replacement.name, 'displacedFile': previous.name,
                  'expectedSha256': hashlib.sha256(expected).hexdigest(),
                  'candidateSha256': hashlib.sha256(replacement.read_bytes()).hexdigest()}
        atomic_write_json(marker, record, root=destination.parent)
        displaced = replace_preserving_previous(destination, replacement, previous)
        record.update(phase='exchanged', displacedFile=displaced.name)
        atomic_write_json(marker, record, root=destination.parent)
        if read_bounded_regular_bytes(displaced, max_bytes=max_bytes, root=destination.parent) != expected:
            raise ValueError(f'Owner publication conflict requires recovery: {marker}; owner bytes: {displaced}')
        _fsync_directory(destination.parent)
        displaced.unlink()
        marker.unlink()
        _fsync_directory(destination.parent)


def replace_preserving_previous(destination: Path, replacement: Path, previous: Path) -> Path:
    """Atomically replace a file while retaining the actual displaced directory entry."""
    import ctypes
    if os.name == 'nt':
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        replace = kernel.ReplaceFileW
        replace.argtypes = [ctypes.c_wchar_p] * 3 + [ctypes.c_uint32, ctypes.c_void_p, ctypes.c_void_p]
        replace.restype = ctypes.c_int
        if not replace(str(destination), str(replacement), str(previous), 0, None, None):
            raise ctypes.WinError(ctypes.get_last_error())
        return previous
    else:
        libc = ctypes.CDLL(None, use_errno=True)
        exchange = libc.renameat2
        exchange.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        exchange.restype = ctypes.c_int
        if exchange(-100, os.fsencode(destination), -100, os.fsencode(replacement), 2):
            import errno
            error = ctypes.get_errno()
            if error not in (errno.EINVAL, errno.ENOSYS, errno.EOPNOTSUPP):
                raise OSError(error, 'Atomic sidecar exchange failed')
            # DrvFS has no exchange syscall. Move the actual old entry, then publish
            # without clobbering any editor that creates a replacement in the gap.
            os.rename(destination, previous)
            try:
                os.link(replacement, destination)
            except BaseException as cause:
                try:
                    os.link(previous, destination)
                except FileExistsError:
                    pass
                raise RuntimeError(f'Sidecar publication conflict; previous bytes retained at {previous}') from cause
            replacement.unlink()
            return previous
        return replacement


def publish_json(path: Path, value, *, durable: bool = True, ensure_ascii: bool = False,
                 indent=None, sort_keys: bool = False, separators=None) -> None:
    # Serialize before creating any file; invalid numerical output never displaces good state.
    raw = (json.dumps(value, ensure_ascii=ensure_ascii, indent=indent,
                      sort_keys=sort_keys, separators=separators, allow_nan=False) + '\n').encode('utf-8')
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=f'.{path.name}.', suffix='.tmp', dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(raw)
            handle.flush()
            if durable:
                os.fsync(handle.fileno())
        if durable:
            from stemwise_engine.common import durable_replace
            durable_replace(temporary, path, staged_is_synced=True)
        else:
            os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
