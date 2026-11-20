"""Signed, staged downloader updates; a running Python process keeps its own version."""
from __future__ import annotations

from stemwise_engine.common import WorkerTemporaryDirectory

from stemwise_engine.common import WorkerRetirementError

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path

from stemwise_engine.common import atomic_write_json, exclusive_process_lock, read_bounded_json, run_process_tree, remove_disposable_path
from stemwise_engine.provider_operations import provider_operation, ProviderStageDeadline, ProviderDeadline, ProviderCancelled, ProviderError

PC_ROOT = Path(__file__).resolve().parents[2]
RUNTIME = PC_ROOT / "runtime" / "yt-dlp"
KEY = PC_ROOT.parent / "StemWise/app/src/main/res/raw/yt_dlp_release_signing_public_key.asc"
KEY_SHA256 = "45d6b415928b5f3e228b461fa9e6d7eb56a824931c785ece00279a06c7a6d6e5"
FINGERPRINT = "AC0CBBE6848D6A873464AF4E57CF65933B5A7581"
MINIMUM_VERSION = "2026.08.19"
INTERVAL_SECONDS = 86400
FAILURE_RETRY_SECONDS = 900
MAX_BINARY_BYTES = 16 * 1024 * 1024
PROBE_URL = "https://www.youtube.com/watch?v=YE7VzlLtp-4"
VERSION = re.compile(r"20\d{2}\.\d{2}\.\d{2}(?:\.[1-9]\d{0,3})?\Z")
WATCH_URL = re.compile(r"https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})\Z")


def version_key(value: str) -> tuple[int, ...]:
    if not VERSION.fullmatch(value):
        raise ValueError("Invalid yt-dlp stable version")
    import datetime
    parts = tuple(map(int, value.split(".")))
    datetime.date(*parts[:3])
    return parts[:3] + (parts[3] if len(parts) == 4 else 0,)


def verify_release(directory: Path, version: str) -> Path:
    """Authenticate the retained release too; an editable success flag is insufficient."""
    version_key(version)
    if version_key(version) < version_key(MINIMUM_VERSION):
        raise ValueError("yt-dlp release is below the safe baseline")
    if hashlib.sha256(KEY.read_bytes()).hexdigest() != KEY_SHA256:
        raise ValueError("yt-dlp signing key changed")
    for name, limit in (("yt-dlp", MAX_BINARY_BYTES), ("SHA2-256SUMS", 65536), ("SHA2-256SUMS.sig", 8192)):
        path = directory / name
        if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
            raise ValueError("Unsafe yt-dlp release file")
    keyring = RUNTIME / "signing-key.gpg"
    if not keyring.exists():
        RUNTIME.mkdir(parents=True, exist_ok=True)
        result = run_process_tree(
            ["gpg", "--no-options", "--batch", "--dearmor", "--output", "-", str(KEY)],
            capture_output=True, check=True, timeout=15,
        )
        from stemwise_engine.common import atomic_write_bytes
        atomic_write_bytes(keyring, result.stdout, root=RUNTIME)
    checked = run_process_tree(
        ["gpgv", "--keyring", str(keyring.resolve()), "--status-fd", "1",
         str(directory / "SHA2-256SUMS.sig"), str(directory / "SHA2-256SUMS")],
        capture_output=True, text=True, check=True, timeout=15,
    )
    signatures = [line.split() for line in checked.stdout.splitlines()
                  if line.startswith("[GNUPG:] VALIDSIG ")]
    if len(signatures) != 1 or FINGERPRINT not in (signatures[0][2], signatures[0][-1]):
        raise ValueError("Untrusted yt-dlp signing key")
    if signatures[0][9] not in ("8", "9", "10"):
        raise ValueError("Weak yt-dlp signature digest")
    sums = (directory / "SHA2-256SUMS").read_bytes()
    if not 0 < len(sums) <= 64 * 1024:
        raise ValueError("Invalid yt-dlp checksums")
    matches = re.findall(r"(?m)^([0-9a-f]{64}) [ *]yt-dlp$", sums.decode("utf-8"))
    binary = directory / "yt-dlp"
    if len(matches) != 1 or not 0 < binary.stat().st_size <= MAX_BINARY_BYTES:
        raise ValueError("Invalid yt-dlp binary")
    if hashlib.sha256(binary.read_bytes()).hexdigest() != matches[0]:
        raise ValueError("yt-dlp binary checksum mismatch")
    with zipfile.ZipFile(binary) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == "yt_dlp/version.py"]
        if len(entries) != 1 or not 0 < entries[0].file_size <= 4096:
            raise ValueError("Invalid yt-dlp embedded version")
        text = archive.read(entries[0]).decode("utf-8")
        if (re.findall(r"(?m)^__version__ = '([^']+)'$", text) != [version]
                or "CHANNEL = 'stable'" not in text or "ORIGIN = 'yt-dlp/yt-dlp'" not in text):
            raise ValueError("yt-dlp release identity mismatch")
    return binary


def activate_installed() -> str | None:
    """Select before the first import, never replace modules halfway through a job."""
    pinned = os.environ.get("STEMWISE_YTDLP_VERSION")
    if "yt_dlp" in sys.modules or pinned == "base":
        return None
    if pinned is None and not (RUNTIME / "active.json").exists():
        return None
    version = pinned or read_bounded_json(RUNTIME / "active.json", max_bytes=4096, root=RUNTIME)["version"]
    version_key(version)
    directory = RUNTIME / version
    if directory.is_symlink() or directory.resolve().parent != RUNTIME.resolve():
        raise ValueError("Unsafe yt-dlp release directory")
    binary = verify_release(directory, version)
    if str(binary) not in sys.path:
        sys.path.insert(0, str(binary))
    os.environ["STEMWISE_YTDLP_VERSION"] = version
    return version


def download_asset(url: str, limit: int) -> tuple[bytes, str]:
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "StemWise"}),
                                timeout=20) as response:
        final = response.url
        if not final.startswith("https://"):
            raise ValueError("Insecure yt-dlp release redirect")
        data = response.read(limit + 1)
        if not 0 < len(data) <= limit:
            raise ValueError("yt-dlp release response exceeds its limit")
        return data, final


def probe_download(binary: Path, deno: Path, directory: Path, url: str = PROBE_URL) -> None:
    """Download a bounded audio sample, not merely a list of installed extractors."""
    output = directory / "probe.%(ext)s"
    match = WATCH_URL.fullmatch(url)
    if match is None:
        raise ValueError("Invalid yt-dlp probe URL")
    result = run_process_tree(
        [sys.executable, str(binary), "--ignore-config", "--no-plugin-dirs", "--no-playlist",
         "--js-runtimes", f"deno:{deno}", "--test", "--quiet", "--no-warnings", "--no-part", "--no-cache-dir",
         "--retries", "0", "--fragment-retries", "0", "--socket-timeout", "10",
         "--format", "bestaudio/best", "--output", str(output),
         "--print", "after_video:%(id)s", url],
        capture_output=True, text=True, check=True, timeout=60,
    )
    samples = [p for p in directory.glob("probe.*") if p.is_file() and not p.is_symlink()]
    if result.stdout.strip() != match[1] or len(samples) != 1 or not 0 < samples[0].stat().st_size <= 1024 * 1024:
        raise ValueError("yt-dlp real audio download probe failed")


def maybe_update_ytdlp(deno: Path, *, probe_url: str = PROBE_URL) -> None:
    """An optional bounded phase leaves time for the installed downloader."""
    with provider_operation() as budget:
        try:
            RUNTIME.mkdir(parents=True, exist_ok=True)
            with WorkerTemporaryDirectory(prefix='update-operation-', dir=RUNTIME) as scratch:
                budget.run(['--ytdlp-update', json.dumps({'deno': str(deno), 'probeUrl': probe_url,
                    'scratchParent': scratch})], phase_seconds=min(15, budget.remaining() / 4))
        except ProviderStageDeadline:
            budget.remaining()  # The whole operation deadline remains fatal.
            print('yt-dlp optional update deadline; using installed downloader', file=sys.stderr)
        except (ProviderDeadline, ProviderCancelled):
            raise
        except (ProviderError, OSError) as error:
            budget.remaining()
            print(f'yt-dlp update deferred: {type(error).__name__}', file=sys.stderr)


def _update_ytdlp(deno: Path, *, probe_url: str = PROBE_URL, scratch_parent: Path | None = None) -> None:
    """Best effort before acquisition; rejected candidates never change active state."""
    now = time.time()
    scratch: Path | None = None
    try:
        RUNTIME.mkdir(parents=True, exist_ok=True)
        with exclusive_process_lock(RUNTIME / "update.lock"):
            state_path = RUNTIME / "check.json"
            state = read_bounded_json(state_path, max_bytes=4096, root=RUNTIME) if state_path.exists() else {}
            last = state.get("checkedAt", 0)
            interval = FAILURE_RETRY_SECONDS if state.get("failed") else INTERVAL_SECONDS
            if 0 <= now - last < interval:
                return
            atomic_write_json(state_path, {"checkedAt": now, "failed": True}, root=RUNTIME)
            release_bytes, _ = download_asset("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest", 256 * 1024)
            release = json.loads(release_bytes)
            if not isinstance(release, dict) or release.get("draft") is not False or release.get("prerelease") is not False:
                raise ValueError("Invalid yt-dlp stable release")
            version = release["tag_name"]
            active_path = RUNTIME / "active.json"
            active = read_bounded_json(active_path, max_bytes=4096, root=RUNTIME) if active_path.exists() else {"version": MINIMUM_VERSION}
            if version_key(version) < version_key(active["version"]):
                raise ValueError("yt-dlp rollback rejected")
            if version_key(version) == version_key(active["version"]):
                atomic_write_json(state_path, {"checkedAt": now, "failed": False}, root=RUNTIME)
                return
            scratch = Path(tempfile.mkdtemp(prefix="candidate-", dir=scratch_parent or RUNTIME))
            release_directory = scratch / "release"
            release_directory.mkdir()
            base = f"https://github.com/yt-dlp/yt-dlp/releases/download/{version}/"
            for name, limit in (("SHA2-256SUMS", 65536), ("SHA2-256SUMS.sig", 8192), ("yt-dlp", MAX_BINARY_BYTES)):
                data, _ = download_asset(base + name, limit)
                (release_directory / name).write_bytes(data)
            binary = verify_release(release_directory, version)
            probe_download(binary, deno, scratch, probe_url)
            target = RUNTIME / version
            if target.is_symlink() or target.resolve().parent != RUNTIME.resolve():
                raise ValueError("Unsafe yt-dlp release directory")
            if target.exists():
                existing = verify_release(target, version)
                if hashlib.sha256(existing.read_bytes()).digest() != hashlib.sha256(binary.read_bytes()).digest():
                    raise ValueError("yt-dlp immutable release bytes changed")
            else:
                # One same-volume rename cannot leave a half-populated installed release.
                release_directory.rename(target)
            atomic_write_json(active_path, {"version": version}, root=RUNTIME)
            atomic_write_json(state_path, {"checkedAt": now, "failed": False}, root=RUNTIME)
    except WorkerRetirementError:
        raise
    except Exception as error:
        from stemwise_engine.common import ProcessLockBusyError
        if not isinstance(error, ProcessLockBusyError):
            print(f"yt-dlp update deferred: {type(error).__name__}", file=sys.stderr)
    finally:
        if scratch is not None and scratch.exists() and not isinstance(sys.exc_info()[1], WorkerRetirementError):
            try:
                if scratch.resolve().parent != (scratch_parent or RUNTIME).resolve():
                    raise ValueError("Unsafe yt-dlp scratch cleanup")
                remove_disposable_path(RUNTIME, scratch, workspace_root=PC_ROOT.parent)
            except (OSError, ValueError) as error:
                print(f"yt-dlp scratch cleanup deferred at {scratch}: {type(error).__name__}", file=sys.stderr)
