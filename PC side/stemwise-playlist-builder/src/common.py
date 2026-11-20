"""Shared filesystem, JSON, and configuration helpers for Stemwise PC."""

from __future__ import annotations

import errno
import base64
import hashlib
import json
import math
import os
import re
import signal
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import uuid
from contextlib import contextmanager, ExitStack
from contextvars import ContextVar
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


VIDEO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{11}$")


def source_duration_ms(frames: int, sample_rate: int) -> int:
    if any(type(value) is not int or value <= 0 for value in (frames, sample_rate)):
        raise ValueError('Audio source timeline is invalid')
    return (frames * 2000 + sample_rate) // (sample_rate * 2)


class YouTubeAudioReplacedError(ValueError):
    """The owner's selected FLAC supersedes this YouTube recording."""


class WorkerRetirementError(RuntimeError):
    """The owned process boundary could not establish worker retirement."""


WORKER_RETIREMENT_FAILURE_MARKER = 'STEMWISE_WORKER_RETIREMENT_FAILED'


def require_youtube_audio_allowed(video_id: str, *, root: Path) -> None:
    validate_video_id(video_id)
    metadata = root / 'audio/youtube' / video_id / 'download.json'
    selected = root / 'comparison-results/library-files.json'
    library = read_bounded_json(selected, max_bytes=16*1024*1024, root=root) if selected.exists() else {}
    retained = any(row['videoId'] == video_id for row in library.get('youtube', []))
    if (not retained and any(row['videoId'] == video_id and row['source'] == 'qobuz'
                            for row in library.get('playlist', []))
            or metadata.exists() and read_bounded_json(metadata, max_bytes=16*1024*1024,
                root=root).get('status') == 'replaced_by_flac'):
        raise YouTubeAudioReplacedError('Approved FLAC replaces this YouTube audio; use its recording key. YouTube metadata remains available.')


GENERATED_MANIFEST_NAME = ".stemwise-generated.json"
_FILE_ATTRIBUTE_REPARSE_POINT = 0x400
_PROCESS_LOCKS: ContextVar[tuple[tuple[Path, int], ...]] = ContextVar("stemwise_process_locks", default=())
_PROCESS_LOCK_LEASES_ENV = "STEMWISE_PROCESS_LOCK_LEASES"
_IDLE_WORKER_RETIREMENT: ContextVar[Any] = ContextVar('stemwise_idle_worker_retirement', default=None)
WORKER_DIRECTORY_LOCK = '.stemwise-worker.lock'


@contextmanager
def worker_directory_cleanup(path: Path):
    """A surviving inherited lease fences cleanup, including removal of a parent."""
    with ExitStack() as leases:
        if path.is_dir():
            for current, _, files in os.walk(path, followlinks=False):
                if WORKER_DIRECTORY_LOCK in files:
                    _retire_idle_workers()
                    lock = Path(current) / WORKER_DIRECTORY_LOCK
                    if process_lock_descriptor(lock) is None:
                        leases.enter_context(exclusive_process_lock(lock, wait_seconds=0))
        yield


class WorkerTemporaryDirectory:
    """Temporary files remain owned until every inherited worker lease retires."""
    def __init__(self, *, prefix, dir):
        self.name = tempfile.mkdtemp(prefix=prefix, dir=dir)
        self._lease = exclusive_process_lock(Path(self.name) / WORKER_DIRECTORY_LOCK)
        try:
            self._lease.__enter__()
        except BaseException:
            shutil.rmtree(self.name)
            raise
        self._closed = False

    def __enter__(self):
        return self.name

    def __exit__(self, kind, error, traceback):
        self.cleanup(preserve=isinstance(error, WorkerRetirementError))

    def cleanup(self, *, preserve=False):
        if self._closed:
            return
        self._closed = True
        self._lease.__exit__(None, None, None)
        if not preserve and not isinstance(sys.exc_info()[1], WorkerRetirementError):
            with worker_directory_cleanup(Path(self.name)):
                shutil.rmtree(self.name)


def _retire_idle_workers() -> None:
    retire = _IDLE_WORKER_RETIREMENT.get()
    if retire is not None:
        retire()

# Stemwise intentionally stores every Arabic regional/dialect label under the
# single app language ``ar``.  These ISO 639 subtags are commonly used for
# individual Arabic varieties; the list is routing policy, not a claim that the
# varieties are linguistically interchangeable.
ARABIC_LANGUAGE_SUBTAGS = frozenset(
    {
        "ar",
        "aao",
        "abh",
        "abv",
        "acm",
        "acq",
        "acw",
        "acx",
        "acy",
        "adf",
        "aeb",
        "aec",
        "afb",
        "ajp",
        "apc",
        "apd",
        "arb",
        "arq",
        "ars",
        "ary",
        "arz",
        "auz",
        "avl",
        "ayh",
        "ayl",
        "ayn",
        "ayp",
        "pga",
        "shu",
        "ssh",
    }
)


def utc_now() -> str:
    """Return the contract's RFC 3339 UTC timestamp form."""
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def json_bytes(value: Any, *, pretty: bool = False) -> bytes:
    options: dict[str, Any] = {"ensure_ascii": False, "allow_nan": False}
    if pretty:
        options.update(indent=2)
    else:
        options.update(separators=(",", ":"))
    return (json.dumps(value, **options) + "\n").encode("utf-8")


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_strict_json_bytes(payload: bytes) -> Any:
    """Parse canonical external JSON: UTF-8, no BOM, duplicates, or NaN/Infinity."""

    if payload.startswith(b"\xef\xbb\xbf"):
        raise ValueError("JSON must not contain a UTF-8 byte-order mark")

    def object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            if key in value:
                raise ValueError(f"duplicate JSON property name {key!r}")
            value[key] = item
        return value

    def reject_constant(value: str) -> None:
        raise ValueError(f"non-finite JSON constant {value}")

    def finite_float(text: str) -> float:
        number = float(text)
        if not math.isfinite(number):
            raise ValueError('JSON numbers must be finite')
        return number

    try:
        text = payload.decode('utf-8', errors='strict')
        depth, in_string, escaped = 0, False, False
        for character in text:
            if in_string:
                if escaped:
                    escaped = False
                elif character == '\\':
                    escaped = True
                elif character == '"':
                    in_string = False
            elif character == '"':
                in_string = True
            elif character in '[{':
                depth += 1
                if depth > 64:
                    raise ValueError('JSON nesting exceeds 64 containers')
            elif character in ']}':
                depth -= 1
        value = json.loads(text, object_pairs_hook=object_without_duplicates,
                           parse_constant=reject_constant, parse_float=finite_float)
        pending = [value]
        nodes = 0
        while pending:
            item = pending.pop()
            nodes += 1
            if nodes > 1_000_000:
                raise ValueError('JSON structure exceeds the node budget')
            if isinstance(item, dict):
                pending.extend(item.keys())
                pending.extend(item.values())
            elif isinstance(item, list):
                pending.extend(item)
            elif isinstance(item, str):
                item.encode('utf-8', errors='strict')
        return value
    except (UnicodeError, RecursionError, OverflowError) as error:
        raise ValueError('JSON contains invalid Unicode, nesting or numbers') from error


def read_bounded_regular_bytes(
    path: Path,
    *,
    max_bytes: int,
    root: Path | None = None,
) -> bytes:
    """Read one bounded regular file without following its final symlink."""
    if type(max_bytes) is not int or max_bytes <= 0:
        raise ValueError('max_bytes must be a positive integer')
    conflict = path.with_name('.'+path.name+'.publication-conflict.json')
    if conflict.exists():
        if root is not None and not path.resolve().is_relative_to(root.resolve()):
            raise ValueError(f'Input file escapes its allowed directory: {path.name}')
        from stemwise_engine.worker_publication import recover_owner_publication
        recover_owner_publication(path, max_bytes=max_bytes)
    return _read_bounded_regular_bytes(path, max_bytes=max_bytes, root=root)


def _read_bounded_regular_bytes(path: Path, *, max_bytes: int, root: Path | None = None) -> bytes:
    if type(max_bytes) is not int or max_bytes <= 0:
        raise ValueError("max_bytes must be a positive integer")
    try:
        metadata = path.lstat()
        if stat.S_ISLNK(metadata.st_mode) or (
            getattr(metadata, "st_file_attributes", 0) & _FILE_ATTRIBUTE_REPARSE_POINT
        ):
            raise ValueError(f"Input file must not be a symlink or reparse point: {path.name}")
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError(f"Input is not a regular file: {path.name}")
    except FileNotFoundError as error:
        raise ValueError(f"Input file is unavailable: {path.name}") from error
    if root is not None:
        resolved_root = root.resolve()
        try:
            resolved_path = path.resolve(strict=True)
        except OSError as error:
            raise ValueError(f"Input file is unavailable: {path.name}") from error
        if not resolved_path.is_relative_to(resolved_root):
            raise ValueError(f"Input file escapes its allowed directory: {path.name}")

    # A file replaced by a FIFO after lstat must not block before fstat can reject it.
    flags = (os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
             | getattr(os, "O_NONBLOCK", 0))
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise ValueError(f"Input file is unavailable or is a symlink: {path.name}") from error
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError(f"Input is not a regular file: {path.name}")
        if metadata.st_size > max_bytes:
            raise ValueError(f"Input file exceeds the {max_bytes}-byte limit: {path.name}")
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            payload = handle.read(max_bytes + 1)
        if len(payload) > max_bytes:
            raise ValueError(f"Input file exceeds the {max_bytes}-byte limit: {path.name}")
        return payload
    finally:
        os.close(descriptor)


def read_bounded_json(path: Path, *, max_bytes: int, root: Path | None = None) -> Any:
    payload = read_bounded_regular_bytes(path, max_bytes=max_bytes, root=root)
    return parse_strict_json_bytes(payload)


def _absolute_path(path: Path) -> Path:
    """Return an absolute path without following links or reparse points."""

    return Path(os.path.abspath(os.fspath(path)))


def _reject_reparse(path: Path) -> os.stat_result | None:
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return None
    if stat.S_ISLNK(metadata.st_mode) or (
        getattr(metadata, "st_file_attributes", 0) & _FILE_ATTRIBUTE_REPARSE_POINT
    ):
        raise ValueError(f"Generated path must not contain a link or reparse point: {path}")
    return metadata


def validate_generated_path(
    root: Path,
    path: Path,
    *,
    workspace_root: Path | None = None,
) -> Path:
    """Contain one generated path and fail closed on links or mount changes."""

    root = _absolute_path(root)
    path = _absolute_path(path)
    if not path.is_relative_to(root):
        raise ValueError("Generated path escapes its dedicated root")
    inspection_root = root
    if workspace_root is not None:
        workspace_root = _absolute_path(workspace_root)
        if not root.is_relative_to(workspace_root):
            raise ValueError("Generated root must stay inside the StemWise workspace")
        inspection_root = workspace_root

    root_metadata = _reject_reparse(inspection_root)
    device = root_metadata.st_dev if root_metadata is not None else None
    current = inspection_root
    for part in path.relative_to(inspection_root).parts:
        current /= part
        metadata = _reject_reparse(current)
        if metadata is None:
            continue
        if device is None:
            device = metadata.st_dev
        elif metadata.st_dev != device:
            raise ValueError("Generated path crosses an unexpected filesystem boundary")
    return path


def create_manifested_directory(
    root: Path,
    path: Path,
    *,
    owner: dict[str, Any],
    workspace_root: Path | None = None,
) -> Path:
    """Create one generated tree whose exact root is authorized for cleanup."""

    path = validate_generated_path(root, path, workspace_root=workspace_root)
    if path.exists() or path.is_symlink():
        raise FileExistsError(path)
    path.mkdir(parents=True, exist_ok=False)
    path = validate_generated_path(root, path, workspace_root=workspace_root)
    try:
        atomic_write_json(
            path / GENERATED_MANIFEST_NAME,
            {"manifestVersion": 1, "owner": owner, "generatedPaths": ["."]},
            root=path, workspace_root=workspace_root)
    except BaseException:
        try:
            path.rmdir()  # Remove only our empty creation; preserve any intervening content.
        except OSError:
            pass
        raise
    return path


def _recycle_disposable_path(path: Path) -> None:
    """Use the host Windows Recycle Bin, including for project files under WSL."""
    powershell = shutil.which('powershell.exe')
    if powershell is None:
        raise OSError('Windows Recycle Bin is unavailable')
    native = str(path)
    if os.name != 'nt':
        native = run_process_tree(['wslpath', '-w', native], capture_output=True,
                                  text=True, check=True, timeout=10).stdout.strip()
    script = """
$ErrorActionPreference = 'Stop'
$taskPath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__STEMWISE_PATH__'))
Add-Type -AssemblyName Microsoft.VisualBasic
if ([IO.Directory]::Exists($taskPath)) {
    [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($taskPath,
        [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
        [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin,
        [Microsoft.VisualBasic.FileIO.UICancelOption]::ThrowException)
} elseif ([IO.File]::Exists($taskPath)) {
    [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($taskPath,
        [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
        [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin,
        [Microsoft.VisualBasic.FileIO.UICancelOption]::ThrowException)
}
if (Test-Path -LiteralPath $taskPath) { throw 'Recycle Bin did not remove the original path' }
"""
    # Only base64 characters enter the command; filenames remain literal data.
    script = script.replace('__STEMWISE_PATH__', base64.b64encode(native.encode('utf-8')).decode('ascii'))
    run_process_tree([powershell, '-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', script],
        capture_output=True,
        text=True, check=True, timeout=30)


def remove_disposable_path(root: Path, path: Path, *, workspace_root: Path | None = None) -> bool:
    """Delete scoped disposable data now; recycle blocked targets without a holding folder."""
    path = validate_generated_path(root, path, workspace_root=workspace_root)
    if path == root.resolve():
        raise ValueError('Cleanup cannot remove its scope root')
    if not path.exists():
        return False
    with worker_directory_cleanup(path):
        try:
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()
        except OSError:
            try:
                _recycle_disposable_path(path)
            except (OSError, subprocess.SubprocessError) as error:
                raise OSError(f'Could not delete or recycle disposable path: {path}') from error
    if path.exists() or path.is_symlink():
        raise OSError(f'Disposable path still exists after cleanup: {path}')
    return True


def manifested_cleanup_receipt(path: Path) -> Path:
    return path.with_name(f'.{path.name}.cleanup.json')


def remove_manifested_directory(
    root: Path,
    path: Path,
    *,
    owner: dict[str, Any],
    workspace_root: Path | None = None,
    before_remove: Callable[[], None] | None = None,
) -> bool:
    """Remove only an identity-bound generated tree listed by its manifest."""

    path = validate_generated_path(root, path, workspace_root=workspace_root)
    if path == root.resolve():
        raise ValueError('Cleanup cannot remove its scope root')
    receipt = manifested_cleanup_receipt(path)
    expected = {'manifestVersion': 1, 'owner': owner, 'generatedPaths': ['.']}
    pending = (read_bounded_json(receipt, max_bytes=16 * 1024, root=path.parent)
               if receipt.exists() or receipt.is_symlink() else None)
    if pending is not None and (not isinstance(pending, dict)
            or set(pending) != {'manifest', 'path', 'device', 'inode'}
            or pending['manifest'] != expected or pending['path'] != str(path)):
        raise ValueError('Generated cleanup receipt identity does not match cleanup target')
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        if before_remove is not None:
            before_remove()
        if pending is not None:
            receipt.unlink()
            _fsync_directory(path.parent)
        return False
    if not stat.S_ISDIR(metadata.st_mode):
        raise ValueError("Manifest-scoped cleanup target is not a directory")
    if pending is not None and (pending['device'], pending['inode']) != (metadata.st_dev, metadata.st_ino):
        raise ValueError('Generated cleanup directory was replaced')
    manifest = path / GENERATED_MANIFEST_NAME
    if manifest.exists() or manifest.is_symlink() or pending is None:
        if read_bounded_json(manifest, max_bytes=16 * 1024, root=path) != expected:
            raise ValueError("Generated-file manifest identity does not match cleanup target")
    for current, directories, files in os.walk(path, topdown=True, followlinks=False):
        current_path = Path(current)
        for name in [*directories, *files]:
            child_metadata = _reject_reparse(current_path / name)
            if child_metadata is not None and child_metadata.st_dev != metadata.st_dev:
                raise ValueError(
                    "Generated cleanup tree crosses an unexpected filesystem boundary"
                )
    validate_generated_path(root, path, workspace_root=workspace_root)
    with worker_directory_cleanup(path):
        if before_remove is not None:
            before_remove()
        if pending is None:
            atomic_write_json(receipt, {'manifest': expected, 'path': str(path),
                'device': metadata.st_dev, 'inode': metadata.st_ino}, root=path.parent,
                workspace_root=workspace_root)
        remove_disposable_path(root, path, workspace_root=workspace_root)
    receipt.unlink()
    _fsync_directory(path.parent)
    return True


@contextmanager
def manifested_temporary_directory(
    root: Path,
    *,
    prefix: str,
    owner: dict[str, Any],
    workspace_root: Path | None = None,
):
    root = validate_generated_path(root, root, workspace_root=workspace_root)
    root.mkdir(parents=True, exist_ok=True)
    path = root / f"{prefix}{uuid.uuid4().hex}"
    create_manifested_directory(
        root, path, owner=owner, workspace_root=workspace_root
    )
    try:
        with exclusive_process_lock(path / WORKER_DIRECTORY_LOCK):
            yield path
    finally:
        failure = sys.exc_info()[1]
        if not isinstance(failure, WorkerRetirementError):
            try:
                remove_manifested_directory(
                    root, path, owner=owner, workspace_root=workspace_root
                )
            except BaseException as cleanup_error:
                raise_with_cleanup_failure(failure, cleanup_error)


def raise_with_cleanup_failure(primary, cleanup_error):
    """Keep failed retirement typed so outer owners preserve still-owned resources."""
    if isinstance(cleanup_error, WorkerRetirementError) and not isinstance(primary, WorkerRetirementError):
        raise cleanup_error
    if primary is not None:
        raise primary
    raise cleanup_error


def preflight_staging_space(
    destination: Path,
    staging: Path,
    *,
    candidate_bytes: int,
    rollback_bytes: int = 0,
) -> None:
    """Require same-volume staging and enough free space for peak copies."""

    if candidate_bytes < 0 or rollback_bytes < 0:
        raise ValueError("Staging byte counts must be nonnegative")
    destination_parent = destination.parent
    staging_parent = staging.parent
    if destination_parent.stat().st_dev != staging_parent.stat().st_dev:
        raise ValueError("Package staging must use the destination volume")
    required = candidate_bytes + rollback_bytes
    if shutil.disk_usage(staging_parent).free < required:
        raise OSError("Insufficient disk space for package staging and rollback")


def _fsync_directory(path: Path) -> None:
    """Persist a completed rename when the host filesystem supports directory fsync."""

    if os.name == "nt":
        return
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        try:
            os.fsync(descriptor)
        except OSError as error:
            if error.errno not in {errno.EINVAL, errno.ENOTSUP, errno.EOPNOTSUPP}:
                raise
    finally:
        os.close(descriptor)


def durable_replace(staged: Path, destination: Path, *, staged_is_synced: bool = False) -> None:
    """Replace a file atomically and persist both its bytes and directory entry."""

    if not staged_is_synced:
        # Windows rejects fsync on a read-only descriptor even when the file is valid.
        with staged.open("rb+") as handle:
            os.fsync(handle.fileno())
    os.replace(staged, destination)
    _fsync_directory(destination.parent)


def atomic_write_bytes(
    path: Path,
    payload: bytes,
    *,
    root: Path | None = None,
    workspace_root: Path | None = None,
) -> None:
    if root is not None:
        path = validate_generated_path(root, path, workspace_root=workspace_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    if root is not None:
        path = validate_generated_path(root, path, workspace_root=workspace_root)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        if root is not None:
            validate_generated_path(root, temporary, workspace_root=workspace_root)
            validate_generated_path(root, path, workspace_root=workspace_root)
        durable_replace(temporary, path, staged_is_synced=True)
    finally:
        if root is not None:
            validate_generated_path(
                root, temporary, workspace_root=workspace_root
            ).unlink(missing_ok=True)
        else:
            temporary.unlink(missing_ok=True)


def atomic_write_json(
    path: Path,
    value: Any,
    *,
    pretty: bool = True,
    root: Path | None = None,
    workspace_root: Path | None = None,
) -> None:
    atomic_write_bytes(
        path,
        json_bytes(value, pretty=pretty),
        root=root,
        workspace_root=workspace_root,
    )


def _linux_process_identity(pid: int) -> tuple[int, int] | None:
    try:
        fields = Path(f'/proc/{pid}/stat').read_bytes().rsplit(b')', 1)[1].split()
        return int(fields[1]), int(fields[19])
    except (FileNotFoundError, ProcessLookupError):
        return None


def _linux_pidfd_call(name: str, *args: int | None) -> int:
    import ctypes
    call = getattr(ctypes.CDLL(None, use_errno=True), name)
    result = call(*args)
    if result < 0:
        raise OSError(ctypes.get_errno(), f'{name} failed')
    return result


def _owned_descendants() -> dict[int, tuple[int, int]]:
    processes = {}
    for entry in Path('/proc').iterdir():
        if entry.name.isdecimal():
            identity = _linux_process_identity(int(entry.name))
            if identity is not None:
                processes[int(entry.name)] = identity
    parents = {os.getpid()}
    owned = {}
    while True:
        added = {pid: identity for pid, identity in processes.items()
                 if identity[0] in parents and pid not in owned}
        if not added:
            return owned
        owned.update(added)
        parents.update(added)


def _retire_owned_descendants() -> None:
    # The subreaper retains double-forked and new-session workers as children.
    deadline = time.monotonic() + 5
    while True:
        owned = _owned_descendants()
        for pid, identity in owned.items():
            try:
                descriptor = _linux_pidfd_call('pidfd_open', pid, 0)
            except ProcessLookupError:
                continue
            try:
                if _linux_process_identity(pid) == identity:
                    _linux_pidfd_call('pidfd_send_signal', descriptor, signal.SIGKILL, None, 0)
            except ProcessLookupError:
                pass
            finally:
                os.close(descriptor)
        while True:
            try:
                pid, _ = os.waitpid(-1, os.WNOHANG)
                if pid == 0:
                    break
            except ChildProcessError:
                return
        if time.monotonic() >= deadline:
            raise RuntimeError('Owned command workers did not terminate')
        time.sleep(.01)


def _reap_exited_adopted_children(command: subprocess.Popen[Any]) -> None:
    # Peek before consuming status: Popen alone owns the primary command's exit.
    # Bound each pass so descendant churn cannot delay cancellation polling.
    for _ in range(64):
        try:
            exited = os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        except ChildProcessError:
            return
        if exited is None:
            return
        if exited.si_pid == command.pid:
            command.poll()
            return
        os.waitpid(exited.si_pid, os.WNOHANG)


def _supervise_command(parent_pid: int, parent_start: int, status_descriptor: int, descriptors: tuple[int, ...],
                       args: list[str]) -> int:
    import ctypes
    import select
    library = ctypes.CDLL(None, use_errno=True)
    if library.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
        raise OSError(ctypes.get_errno(), 'Cannot own command descendants')
    try:
        owner = _linux_pidfd_call('pidfd_open', parent_pid, 0)
    except ProcessLookupError:
        return 1
    stopped = False

    def stop(*_):
        nonlocal stopped
        stopped = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    command_descriptor = None
    try:
        identity = _linux_process_identity(parent_pid)
        if identity is None or identity[1] != parent_start or select.select([owner], [], [], 0)[0]:
            return 1
        # No command can exist before this byte; cancellation may retire startup directly.
        os.write(status_descriptor, b'R')
        if stopped:
            return -signal.SIGTERM
        command = subprocess.Popen(args, pass_fds=descriptors)
        command_descriptor = _linux_pidfd_call('pidfd_open', command.pid, 0)
        while command.poll() is None and not stopped:
            _reap_exited_adopted_children(command)
            if owner in select.select([owner, command_descriptor], [], [], .05)[0]:
                stopped = True
        returncode = command.returncode if not stopped else -signal.SIGTERM
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        try:
            _retire_owned_descendants()
            os.write(status_descriptor, b'1')
        except BaseException:
            # A distinct private guardian failure must never admit partial model output.
            os._exit(125)
        finally:
            os.close(status_descriptor)
            if command_descriptor is not None:
                os.close(command_descriptor)
            os.close(owner)
    if returncode < 0:
        if -returncode not in {signal.SIGKILL, signal.SIGSTOP}:
            signal.signal(-returncode, signal.SIG_DFL)
        os.kill(os.getpid(), -returncode)
    return returncode


if __name__ == '__main__' and sys.argv[1:2] == ['--supervise-command']:
    raise SystemExit(_supervise_command(int(sys.argv[2]), int(sys.argv[3]),
                     int(sys.argv[4]), tuple(json.loads(sys.argv[5])), sys.argv[6:]))


def _terminate_process_tree(process: subprocess.Popen[Any]) -> None:
    if os.name == "nt":
        # Production uses WSL process groups. taskkill is the best available
        # host-side fallback when unit utilities invoke this helper on Windows.
        if process.poll() is not None:
            return
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if process.poll() is None:
            process.kill()
            process.wait()
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    try:
        # The direct child may exit before one of its descendants. Kill any
        # survivor still attached to the private process group.
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    if process.poll() is None:
        process.wait()


@contextmanager
def owned_process_tree(args: list[str], *, startup_check: Callable[[], float | None] | None = None, **kwargs: Any):
    """Own one synchronous Popen lifetime, including escaped command descendants."""
    from stemwise_engine.runtime_environment import command_environment
    with command_environment(args, kwargs) as kwargs:
        _retire_idle_workers()
        if os.name == "nt":
            kwargs["creationflags"] = int(kwargs.get("creationflags", 0)) | subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            kwargs["start_new_session"] = True
            held = {descriptor: path for path, descriptor in _PROCESS_LOCKS.get()}
            leases = set(held)
            if leases:
                kwargs["pass_fds"] = tuple(sorted(leases | set(kwargs.get("pass_fds", ()))))
                environment = kwargs.get("env")
                kwargs["env"] = {**(os.environ if environment is None else environment),
                                 _PROCESS_LOCK_LEASES_ENV: json.dumps(
                                     [[str(path), descriptor] for descriptor, path in sorted(held.items())])}
        command = args
        status_reader = status_writer = None
        if sys.platform == 'linux':
            # A separate guardian remains alive if the caller is killed. pidfds avoid
            # inherited pipe writers hiding parent death in forked model workers.
            identity = _linux_process_identity(os.getpid())
            if identity is None:
                raise RuntimeError('Command owner process identity is unavailable')
            status_reader, status_writer = os.pipe()
            descriptors = tuple(kwargs.get('pass_fds', ()))
            kwargs['pass_fds'] = (*descriptors, status_writer)
            interpreter = '/usr/bin/python3' if Path('/usr/bin/python3').is_file() else sys.executable
            command = [interpreter, '-I', '-S', '-B', str(Path(__file__).resolve()), '--supervise-command',
                       str(os.getpid()), str(identity[1]), str(status_writer), json.dumps(descriptors), *args]
        try:
            process = subprocess.Popen(command, **kwargs)
        except BaseException:
            if status_reader is not None:
                os.close(status_reader)
            raise
        finally:
            if status_writer is not None:
                os.close(status_writer)
        receipt = b''
        try:
            if status_reader is not None:
                import select
                startup_deadline = time.monotonic() + 5
                while True:
                    remaining = startup_deadline - time.monotonic()
                    if startup_check is not None:
                        caller_remaining = startup_check()
                        if caller_remaining is not None:
                            remaining = min(remaining, caller_remaining)
                    if remaining <= 0:
                        raise WorkerRetirementError('Owned command supervisor startup timed out')
                    if select.select([status_reader], [], [], min(.05, remaining))[0]:
                        receipt = os.read(status_reader, 1)
                        if receipt != b'R':
                            raise WorkerRetirementError('Owned command supervisor startup failed')
                        break
            yield process
        finally:
            if process.poll() is None or sys.platform != 'linux':
                _terminate_process_tree(process)
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream is not None:
                    stream.close()
            if status_reader is not None:
                try:
                    receipt += os.read(status_reader, 2)
                    # R is written before child creation. Once observed it always requires final1.
                    if receipt not in (b'', b'1', b'R1'):
                        raise WorkerRetirementError('Owned command workers could not be retired')
                finally:
                    os.close(status_reader)


def run_process_tree(
    args: list[str],
    *,
    timeout: int | float | None,
    cancelled: Callable[[], bool] | None = None,
    **kwargs: Any,
) -> subprocess.CompletedProcess[Any]:
    """Run a bounded external command and tear down its process group on exit."""

    ledger_path = os.environ.get('STEMWISE_SONG_LEDGER')
    if ledger_path is not None and kwargs.get('env') is not None:
        kwargs['env'] = {**kwargs['env'], 'STEMWISE_SONG_LEDGER': ledger_path}

    check = bool(kwargs.pop("check", False))
    capture_output = bool(kwargs.pop("capture_output", False))
    input_data = kwargs.pop("input", None)
    if input_data is not None:
        if kwargs.get("stdin") is not None:
            raise ValueError("stdin and input may not both be supplied")
        kwargs["stdin"] = subprocess.PIPE
    if capture_output:
        if kwargs.get("stdout") is not None or kwargs.get("stderr") is not None:
            raise ValueError("stdout and stderr may not be used with capture_output")
        kwargs["stdout"] = subprocess.PIPE
        kwargs["stderr"] = subprocess.PIPE
    if cancelled is not None and cancelled():
        raise InterruptedError("Command cancelled")
    deadline = time.monotonic() + timeout if timeout is not None else None
    def check_startup():
        if cancelled is not None and cancelled():
            raise InterruptedError("Command cancelled")
        remaining = None if deadline is None else deadline - time.monotonic()
        if remaining is not None and remaining <= 0:
            raise subprocess.TimeoutExpired(args, timeout)
        return remaining
    with owned_process_tree(args, startup_check=check_startup, **kwargs) as process:
        try:
            while True:
                if cancelled is not None and cancelled():
                    raise InterruptedError("Command cancelled")
                remaining = None if deadline is None else max(0.0, deadline - time.monotonic())
                try:
                    stdout, stderr = process.communicate(input=input_data,
                        timeout=(min(.1, remaining) if remaining is not None else .1)
                        if cancelled is not None else remaining)
                    break
                except subprocess.TimeoutExpired:
                    input_data = None
                    if deadline is not None and time.monotonic() >= deadline:
                        raise
            if cancelled is not None and cancelled():
                raise InterruptedError("Command cancelled")
        except subprocess.TimeoutExpired as error:
            error.timeout = timeout
            _terminate_process_tree(process)
            error.output, error.stderr = process.communicate()
            raise
        except BaseException:
            _terminate_process_tree(process)
            process.communicate()
            raise
    completed = subprocess.CompletedProcess(args, process.returncode, stdout, stderr)
    if check:
        completed.check_returncode()
    return completed


class ProcessLockBusyError(RuntimeError):
    """Raised when a bounded Stemwise advisory-lock acquisition cannot proceed."""


def process_lock_descriptor(path: Path) -> int | None:
    """Find the lease held in this context so a worker can inherit its lifetime."""
    resolved = path.resolve()
    return next((descriptor for held, descriptor in reversed(_PROCESS_LOCKS.get())
                 if held == resolved), None)


@contextmanager
def inherited_process_lock(path: Path, descriptor: int):
    """Keep a passed flock alive without unlocking the supervisor's shared lease."""
    path = _verify_process_lock_descriptor(path, descriptor)
    token = _PROCESS_LOCKS.set((*_PROCESS_LOCKS.get(), (path, descriptor)))
    try:
        yield
    finally:
        _PROCESS_LOCKS.reset(token)
        _PROCESS_LOCKS.set(tuple(record for record in _PROCESS_LOCKS.get() if record[1] != descriptor))
        # LOCK_UN would also release the parent's lock: both processes share the
        # same open file description. Closing releases it only after the last owner.
        os.close(descriptor)


def _verify_process_lock_descriptor(path: Path, descriptor: int) -> Path:
    """An environment label alone never establishes ownership of a lease."""
    import fcntl
    actual = os.fstat(descriptor)
    expected = path.stat()
    if not stat.S_ISREG(actual.st_mode) or (actual.st_dev, actual.st_ino) != (expected.st_dev, expected.st_ino):
        raise ValueError("Inherited worker lease differs from the heavy lock")
    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    return path.resolve()


def _adopt_inherited_process_locks():
    payload = os.environ.pop(_PROCESS_LOCK_LEASES_ENV, None)
    if payload is None:
        return
    records = json.loads(payload)
    if not isinstance(records, list) or len(records) > 32:
        raise ValueError("Invalid inherited process leases")
    held = []
    for record in records:
        if (not isinstance(record, list) or len(record) != 2 or not isinstance(record[0], str)
                or not Path(record[0]).is_absolute() or type(record[1]) is not int or record[1] < 0):
            raise ValueError("Invalid inherited process lease")
        held.append((_verify_process_lock_descriptor(Path(record[0]), record[1]), record[1]))
    _PROCESS_LOCKS.set(tuple(held))


_adopt_inherited_process_locks()


def heavy_worker_lock_path(config: dict[str, Any]) -> Path:
    """Return the one lock shared by every PC analysis/alignment entry point."""

    project_value = config.get("projectDir")
    if not isinstance(project_value, str) or not project_value.strip():
        raise ValueError("Stemwise config requires an absolute projectDir")
    project = Path(project_value)
    if not project.is_absolute():
        raise ValueError("Stemwise config requires an absolute projectDir")
    project = project.resolve()
    return project / "stemwise-heavy-worker.lock"


@contextmanager
def heavy_worker_stage(config: dict[str, Any], *, wait_seconds: float | None = None):
    """Lease one model stage; callers retire model workers before leaving it."""
    _retire_idle_workers()
    path = heavy_worker_lock_path(config)
    descriptor = process_lock_descriptor(path)
    if descriptor is not None:
        _verify_process_lock_descriptor(path, descriptor)
        yield
    else:
        from stemwise_engine.alignment_attempt import current_song_ledger
        ledger = current_song_ledger()
        if ledger is None:
            with exclusive_process_lock(path, wait_seconds=wait_seconds):
                yield
        else:
            with ExitStack() as ownership:
                with ledger.lease_wait():
                    ownership.enter_context(exclusive_process_lock(path, wait_seconds=wait_seconds))
                yield


@contextmanager
def exclusive_process_lock(path: Path, *, wait_seconds: float | None = None):
    """Hold an advisory lock for one WSL-side builder process.

    The builder is launched inside WSL, so ``flock`` is released automatically
    once this process and its leased workers close their descriptors. The small
    persistent file is diagnostic only;
    lock ownership belongs to the open descriptor, not to its saved PID text.
    The shared heavy-resource lock waits for admission without a job deadline;
    per-job locks remain nonblocking. Explicit wait_seconds bounds either.
    """

    try:
        import fcntl
    except ImportError as error:  # pragma: no cover - the supported runtime is WSL
        raise RuntimeError("The Stemwise process lock requires the WSL/Linux runtime") from error

    if wait_seconds is None and path.name != 'stemwise-heavy-worker.lock':
        wait_seconds = 0
    if wait_seconds is not None and (isinstance(wait_seconds, bool) or not isinstance(wait_seconds, (int, float))):
        raise TypeError("wait_seconds must be a nonnegative number")
    if wait_seconds is not None and wait_seconds < 0:
        raise ValueError("wait_seconds must be nonnegative")

    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    with os.fdopen(descriptor, "r+", encoding="ascii") as handle:
        def require_owned_lock():
            opened, named = os.fstat(handle.fileno()), path.lstat()
            if (not stat.S_ISREG(opened.st_mode) or opened.st_nlink != 1
                    or not os.path.samestat(opened, named)):
                raise ValueError('Process lock must be a single-link regular file at its exact path')
        require_owned_lock()
        deadline = None if wait_seconds is None else time.monotonic() + float(wait_seconds)
        while True:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError as error:
                remaining = None if deadline is None else deadline - time.monotonic()
                if remaining is not None and remaining <= 0:
                    raise ProcessLockBusyError(
                        "Another Stemwise inventory, analysis, or bulk process is already active."
                    ) from error
                time.sleep(0.1 if remaining is None else min(0.1, remaining))
        require_owned_lock()
        handle.seek(0)
        handle.truncate()
        handle.write(f"{os.getpid()}\n")
        handle.flush()
        os.fsync(handle.fileno())
        _PROCESS_LOCKS.set((*_PROCESS_LOCKS.get(), (path.resolve(), handle.fileno())))
        try:
            yield
        finally:
            _PROCESS_LOCKS.set(tuple(record for record in _PROCESS_LOCKS.get()
                                     if record[1] != handle.fileno()))
        # Closing the handle at context exit preserves surviving child leases.
        # Explicit LOCK_UN would release their shared open file description too.




def resolve_path(value: str, *, base: Path) -> Path:
    """Resolve config paths under Windows or WSL without shell interpolation."""
    expanded = os.path.expandvars(value)
    if os.name != "nt" and re.match(r"^[A-Za-z]:[\\/]", expanded):
        completed = subprocess.run(
            ["wslpath", "-u", expanded],
            text=True,
            capture_output=True,
            check=False,
        )
        if completed.returncode != 0 or not completed.stdout.strip():
            raise RuntimeError(f"Could not translate Windows path with wslpath: {value}")
        return Path(completed.stdout.strip()).resolve()
    path = Path(expanded)
    if not path.is_absolute():
        path = base / path
    return path.resolve()


def load_config(path: Path) -> dict[str, Any]:
    raw = read_json(path)
    if not isinstance(raw, dict):
        raise ValueError("config.json must contain a JSON object")
    required = ("playlistUrl", "outputDirWindows")
    for name in required:
        if not isinstance(raw.get(name), str) or not raw[name].strip():
            raise ValueError(f"config.json requires a nonblank {name}")
    config = dict(raw)
    from stemwise_engine.enrichment_constants import GENERATOR_VERSION
    config['generatorVersion'] = GENERATOR_VERSION
    config["projectDir"] = str(path.parent.resolve())
    config["outputDir"] = str(resolve_path(config["outputDirWindows"], base=path.parent))
    configured_temp = (
        config.get("tempDirWindows")
        or config.get("tempDirWsl")
        or str(path.parent.parent / "temporary-analysis")
    )
    temp_root = resolve_path(configured_temp, base=path.parent)
    configured_pc_side_root = config.get("pcSideRootWindows")
    if configured_pc_side_root:
        pc_side_root = resolve_path(configured_pc_side_root, base=path.parent)
        if not temp_root.is_relative_to(pc_side_root):
            raise ValueError(
                "config.json temporary analysis directory must stay inside PC side"
            )
    config["tempDir"] = str(temp_root)
    config.setdefault("playlistName", "playlist")

    # These are external contract ceilings, not tuning knobs. Smaller
    # local limits are allowed, but a config file must never make a package that
    # the Android importer is required to reject.
    from stemwise_engine.contract import (  # local import keeps this helper lightweight at module import
        MAX_COVER_BYTES,
        MAX_COVER_PIXELS_PER_SIDE,
        MAX_JSON_BYTES,
        MAX_SINGLE_ZIP_BYTES,
    )

    frozen_limits = {
        "artworkMaxPixels": MAX_COVER_PIXELS_PER_SIDE,
        "jsonMaxBytes": MAX_JSON_BYTES,
        "pngMaxBytes": MAX_COVER_BYTES,
        "childZipMaxBytes": MAX_SINGLE_ZIP_BYTES,
    }
    for name, maximum in frozen_limits.items():
        value = config.setdefault(name, maximum)
        if type(value) is not int or value <= 0:
            raise ValueError(f"config.json {name} must be a positive integer")
        if value > maximum:
            raise ValueError(
                f"config.json {name} cannot exceed the external contract maximum {maximum}"
            )
    config.setdefault("analysisTimeoutMinutes", 45)
    config.setdefault("keepFailedAudio", False)
    if type(config.get("analysisTimeoutMinutes")) is not int or config["analysisTimeoutMinutes"] <= 0:
        raise ValueError("config.json analysisTimeoutMinutes must be a positive integer")
    if type(config.get("keepFailedAudio")) is not bool:
        raise ValueError("config.json keepFailedAudio must be a Boolean")
    return config


def validate_video_id(value: str) -> str:
    if not VIDEO_ID_RE.fullmatch(value):
        raise ValueError(f"Invalid YouTube video ID: {value!r}")
    return value


def extract_youtube_video_id(value: str) -> str:
    """One supported song route and one unambiguous recording identity."""
    from urllib.parse import parse_qs, urlsplit
    value = value.strip()
    if re.search(r'[\x00-\x1f\x7f]', value):
        raise ValueError('YouTube song URLs cannot contain embedded control characters')
    if VIDEO_ID_RE.fullmatch(value):
        return validate_video_id(value)
    url = urlsplit(value)
    if url.scheme != 'https' or url.username or url.password or url.port:
        raise ValueError('Use an exact HTTPS YouTube song URL or video ID')
    values = parse_qs(url.query, keep_blank_values=True).get('v', [])
    if len(values) > 1:
        raise ValueError('The URL contains duplicate video IDs')
    identity = None
    if url.hostname == 'youtu.be' and len(url.path.split('/')) == 2:
        identity = url.path[1:]
    if url.hostname in ('youtube.com', 'www.youtube.com', 'music.youtube.com', 'm.youtube.com'):
        if url.path == '/watch' and values:
            identity = values[0]
        parts = url.path.strip('/').split('/')
        if len(parts) == 2 and parts[0] in ('shorts', 'embed', 'live'):
            identity = parts[1]
    if identity is None:
        raise ValueError('Use one exact YouTube song, not a playlist or another host')
    if values and values[0] != identity:
        raise ValueError('The URL contains conflicting video IDs')
    return validate_video_id(identity)


def canonical_lyrics_language(value: str) -> str:
    """Collapse every supported Arabic variety tag to the app's ``ar`` tag."""

    primary = value.split("-", 1)[0].lower()
    return "ar" if primary in ARABIC_LANGUAGE_SUBTAGS else value


def compact_error(error: BaseException, limit: int = 500) -> str:
    text = " ".join(str(error).split()) or error.__class__.__name__
    # Tool messages often include temporary files or signed CDN URLs.  Reasons
    # are imported by the phone, so keep those details only in PC diagnostics.
    text = re.sub(r"[A-Za-z]:[\\/][^\s,;]+", "[local path]", text)
    text = re.sub(r"(?<![A-Za-z0-9])/(?:mnt|home|tmp|var|opt)/[^\s,;]+", "[local path]", text)
    text = re.sub(r"https?://[^\s]+", "[url]", text)
    text = re.sub(r"<\s*/?\s*[A-Za-z][^>]*>", "[details]", text)
    return text[:limit]


def diagnostic_code(error: BaseException) -> str:
    """Return a body-, URL-, and path-free exception class code."""

    value = re.sub(r"[^A-Za-z0-9_.-]+", "-", type(error).__name__).strip("-.")
    return value[:120] or "Exception"
