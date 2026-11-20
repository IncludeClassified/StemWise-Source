"""Offline transport substitutes; admission, hashes, version and download checks are real."""
from __future__ import annotations

import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import stemwise_engine.ytdlp_update as update


def candidate(version: str, mode: str) -> bytes:
    target = io.BytesIO()
    with zipfile.ZipFile(target, "w") as archive:
        # Fixed ZIP timestamps keep a retry byte-identical to its interrupted candidate.
        archive.writestr(zipfile.ZipInfo("yt_dlp/version.py"), f"__version__ = '{version}'\nCHANNEL = 'stable'\nORIGIN = 'yt-dlp/yt-dlp'\n")
        archive.writestr(zipfile.ZipInfo("__main__.py"), f"""
import pathlib,sys
out=pathlib.Path(sys.argv[sys.argv.index('--output')+1].replace('%(ext)s','webm'))
mode={mode!r}
if mode != 'metadata-only': out.write_bytes(b'audio' if mode != 'empty' else b'')
print('YE7VzlLtp-4' if mode != 'wrong-video' else 'other-video')
""")
    return target.getvalue()


class YtDlpUpdateTests(unittest.TestCase):
    def test_unretired_candidate_probe_escapes_and_preserves_downloaded_release(self):
        from stemwise_engine.common import WorkerRetirementError
        fatal = WorkerRetirementError('updater probe still owns candidate')
        with mock.patch.object(update, 'probe_download', side_effect=fatal):
            with self.assertRaises(WorkerRetirementError) as raised:
                self.run_update()
        self.assertIs(raised.exception, fatal)
        candidates = list(self.runtime.glob('candidate-*'))
        self.assertEqual(len(candidates), 1)
        self.assertTrue((candidates[0]/'release/yt-dlp').is_file())
        self.assertFalse((self.runtime/'active.json').exists())

    def test_killed_update_attempt_retains_cooldown_before_network_and_then_recovers(self):
        code = "\nimport os,sys\nfrom pathlib import Path\nsys.path.insert(0,sys.argv[1])\nimport stemwise_engine.ytdlp_update as update\nupdate.RUNTIME=Path(sys.argv[2])\ndef fetch(*args): os._exit(23)\nupdate.download_asset=fetch\nupdate._update_ytdlp(Path('deno'))\n"
        child = subprocess.run([sys.executable, '-B', '-c', code, str(Path(update.__file__).parent),
            str(self.runtime)], capture_output=True, timeout=10)
        self.assertEqual(child.returncode, 23)
        state = json.loads((self.runtime/'check.json').read_bytes())
        self.assertIs(state['failed'], True)
        with mock.patch.object(update, 'download_asset', side_effect=AssertionError('cooldown repeated network')) as fetch:
            update._update_ytdlp(self.root/'deno')
        fetch.assert_not_called()
        with mock.patch.object(update.time, 'time', return_value=state['checkedAt']+update.FAILURE_RETRY_SECONDS+1):
            self.run_update()
        self.assertIs(json.loads((self.runtime/'check.json').read_bytes())['failed'], False)
        self.assertEqual(json.loads((self.runtime/'active.json').read_bytes())['version'], '2026.08.20')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/"Test suite/work")
        self.root = Path(self.temporary.name)
        self.runtime = self.root / "PC side/runtime/yt-dlp"
        self.key = self.root / "key.asc"
        self.key.write_bytes(b"fixture key")
        self.patches = [
            mock.patch.object(update, "RUNTIME", self.runtime),
            mock.patch.object(update, "PC_ROOT", self.root / "PC side"),
            mock.patch.object(update, "KEY", self.key),
            mock.patch.object(update, "KEY_SHA256", hashlib.sha256(self.key.read_bytes()).hexdigest()),
            mock.patch.dict(os.environ, {}, clear=False),
        ]
        for patch in self.patches: patch.start()
        os.environ.pop("STEMWISE_YTDLP_VERSION", None)
        self.addCleanup(self.temporary.cleanup)
        for patch in self.patches: self.addCleanup(patch.stop)

    def run_update(self, mode="valid", version="2026.08.20", signature_valid=True, tamper=False):
        binary = candidate(version, mode)
        sums = f"{hashlib.sha256(binary).hexdigest()}  yt-dlp\n".encode()
        def fetch(url, limit):
            if url.endswith("/releases/latest"):
                return json.dumps({"tag_name": version, "draft": False, "prerelease": False}).encode(), url
            if url.endswith("SHA2-256SUMS"):
                return sums, f"https://github.com/yt-dlp/yt-dlp/releases/download/{version}/SHA2-256SUMS"
            return (b"signature" if url.endswith(".sig") else binary + (b"tampered" if tamper else b"")), url
        def run(command, **kwargs):
            if command[0] == "gpg": return SimpleNamespace(stdout=b"fixture keyring")
            if command[0] == "gpgv":
                if not signature_valid: raise subprocess.CalledProcessError(1, command)
                return SimpleNamespace(stdout=f"[GNUPG:] VALIDSIG {update.FINGERPRINT} 2026-08-20 1 0 4 0 1 8 00 {update.FINGERPRINT}\n")
            return subprocess.run(command, **kwargs)
        with mock.patch.object(update, "download_asset", side_effect=fetch), mock.patch.object(update, "run_process_tree", side_effect=run):
            update._update_ytdlp(self.root / "deno")

    def test_success_promotes_only_after_real_probe_produces_the_expected_sample(self):
        self.run_update()
        self.assertEqual('{\n  "version": "2026.08.20"\n}\n', (self.runtime / "active.json").read_text())
        self.assertTrue((self.runtime / "2026.08.20/yt-dlp").is_file())
        self.assertFalse(list(self.runtime.glob("candidate-*")))
        self.assertFalse((self.root / "Pending deletion").exists())

    def test_metadata_only_empty_and_wrong_video_cannot_promote_or_replace_existing_state(self):
        for mode in ("metadata-only", "empty", "wrong-video"):
            with self.subTest(mode=mode):
                self.runtime.mkdir(parents=True, exist_ok=True)
                prior = b'{"version":"2026.08.19"}\n'
                (self.runtime / "active.json").write_bytes(prior)
                (self.runtime / "check.json").unlink(missing_ok=True)
                self.run_update(mode)
                self.assertEqual(prior, (self.runtime / "active.json").read_bytes())
                self.assertFalse((self.runtime / "2026.08.20").exists())

    def test_bad_signature_and_older_release_leave_active_state_unchanged(self):
        self.runtime.mkdir(parents=True)
        prior = b'{"version":"2026.08.19"}\n'
        (self.runtime / "active.json").write_bytes(prior)
        self.run_update(signature_valid=False)
        self.assertEqual(prior, (self.runtime / "active.json").read_bytes())
        (self.runtime / "check.json").unlink()
        self.run_update(version="2026.08.18")
        self.assertEqual(prior, (self.runtime / "active.json").read_bytes())

    def test_signed_checksum_mismatch_cannot_promote(self):
        self.runtime.mkdir(parents=True)
        prior = b'{"version":"2026.08.19"}\n'
        (self.runtime / "active.json").write_bytes(prior)
        self.run_update(tamper=True)
        self.assertEqual(prior, (self.runtime / "active.json").read_bytes())
        self.assertFalse((self.runtime / "2026.08.20").exists())

    def test_daily_and_failed_check_cadences_do_not_recontact_the_provider(self):
        self.runtime.mkdir(parents=True)
        for failed, interval in ((False, 86400), (True, 900)):
            update.atomic_write_json(self.runtime / "check.json", {"checkedAt": 10000, "failed": failed})
            with mock.patch.object(update.time, "time", return_value=10000 + interval - 1), mock.patch.object(update, "download_asset") as fetch:
                update._update_ytdlp(self.root / "deno")
                fetch.assert_not_called()

    def test_corrupt_sample_and_state_do_not_load_an_unverified_executable(self):
        self.runtime.mkdir(parents=True)
        (self.runtime / "active.json").write_text('{"version":"../escape"}')
        with mock.patch.dict(sys.modules), self.assertRaisesRegex(ValueError, "Invalid yt-dlp stable version"):
            for name in tuple(sys.modules):
                if name == 'yt_dlp' or name.startswith('yt_dlp.'):
                    sys.modules.pop(name)
            update.activate_installed()

    def test_worker_pin_survives_a_new_active_pointer(self):
        self.run_update()
        (self.runtime / "active.json").write_text('{"version":"2026.08.21"}')
        signature = SimpleNamespace(stdout=f"[GNUPG:] VALIDSIG {update.FINGERPRINT} 2026-08-20 1 0 4 0 1 8 00 {update.FINGERPRINT}\n")
        prior_path = list(sys.path)
        try:
            with mock.patch.dict(sys.modules), mock.patch.dict(os.environ, {"STEMWISE_YTDLP_VERSION": "2026.08.20"}), mock.patch.object(update, "run_process_tree", return_value=signature):
                sys.modules.pop("yt_dlp", None)
                self.assertEqual("2026.08.20", update.activate_installed())
                self.assertEqual(str(self.runtime / "2026.08.20/yt-dlp"), sys.path[0])
        finally:
            sys.path[:] = prior_path

    def test_provenance_records_the_imported_release_instead_of_the_bootstrap_wheel(self):
        from stemwise_engine.provenance import package_identity
        module = SimpleNamespace(__file__=str(self.runtime / "2026.08.20/yt-dlp/yt_dlp/__init__.py"),
                                 version=SimpleNamespace(__version__="2026.08.20"))
        with mock.patch.dict(sys.modules, {"yt_dlp": module}):
            self.assertEqual("2026.08.20", package_identity("yt-dlp")["version"])

    def test_interruption_before_pointer_commit_preserves_current_version_and_can_retry(self):
        self.runtime.mkdir(parents=True)
        prior = b'{"version":"2026.08.19"}\n'
        (self.runtime / "active.json").write_bytes(prior)
        write = update.atomic_write_json
        def interrupted(path, value, **kwargs):
            if path.name == "active.json": raise OSError("commit interrupted")
            return write(path, value, **kwargs)
        with mock.patch.object(update, "atomic_write_json", side_effect=interrupted):
            self.run_update()
        self.assertEqual(prior, (self.runtime / "active.json").read_bytes())
        self.assertEqual({"yt-dlp", "SHA2-256SUMS", "SHA2-256SUMS.sig"},
                         {p.name for p in (self.runtime / "2026.08.20").iterdir()})
        (self.runtime / "check.json").unlink()
        self.run_update()
        self.assertIn('"2026.08.20"', (self.runtime / "active.json").read_text())

    def test_locked_disposable_scratch_does_not_cancel_a_successful_update(self):
        with mock.patch.object(update, "remove_disposable_path", side_effect=PermissionError("locked")):
            self.run_update()
        self.assertIn('"2026.08.20"', (self.runtime / "active.json").read_text())
        self.assertEqual(1, len(list(self.runtime.glob("candidate-*"))))


if __name__ == "__main__": unittest.main()
