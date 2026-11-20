from __future__ import annotations

import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SRC = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder" / "src"

import stemwise_engine.doctor as doctor


class DoctorTests(unittest.TestCase):
    def test_hanging_malformed_and_failed_probes_return_independent_capability_errors(self):
        import time
        before = time.monotonic()
        for program in ('import time; time.sleep(30)', 'print("invalid JSON")', 'raise RuntimeError("private")'):
            result = doctor.json_probe([sys.executable, '-c', program], timeout=.15)
            self.assertEqual(result['ok'], False)
            self.assertIn('error', result)
            self.assertNotIn('private', result['error'])
        self.assertLess(time.monotonic()-before, 3)
        self.assertEqual(doctor.json_probe([sys.executable, '-c', 'print("{\\"capability\\":true}")']), {'capability': True})

    def test_cli_doctor_forwards_the_resolved_selected_config(self):
        import stemwise_engine.stemwise_pc as stemwise_pc
        config = SRC.parent / 'alternate.json'
        with mock.patch.object(stemwise_pc, 'load_config', return_value={}) as load, \
                mock.patch.object(stemwise_pc.subprocess, 'run',
                    return_value=subprocess.CompletedProcess([], 0)) as child:
            self.assertEqual(stemwise_pc.main(['--config', str(config), 'doctor']), 0)
        load.assert_called_once_with(config.resolve())
        argv = child.call_args.args[0]
        self.assertIn('--config', argv)
        self.assertEqual(argv[argv.index('--config') + 1], str(config.resolve()))

    def test_deno_identity_accepts_the_pinned_release_output(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            executable = Path(name) / "deno"
            executable.touch()
            completed = subprocess.CompletedProcess(
                [str(executable), "--version"],
                0,
                stdout=f"deno {doctor.EXPECTED_DENO_VERSION} (stable, release)\n",
                stderr="",
            )
            with mock.patch('stemwise_engine.doctor.subprocess.run', return_value=completed), mock.patch(
                'stemwise_engine.doctor.sha256_file', return_value=doctor.EXPECTED_DENO_SHA256
            ):
                identity = doctor.deno_identity(executable)

        self.assertTrue(identity["verified"])
        self.assertEqual(doctor.EXPECTED_DENO_VERSION, identity["version"])

    def test_deno_identity_fails_closed_when_runtime_is_absent(self) -> None:
        identity = doctor.deno_identity(None)

        self.assertFalse(identity["available"])
        self.assertNotIn("verified", identity)

    def test_wav2vec2_model_identity_requires_the_exact_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            snapshot = Path(name)
            payload = b"pinned model fixture"
            expected = {"model.bin": (len(payload), hashlib.sha256(payload).hexdigest())}
            (snapshot / "model.bin").write_bytes(payload)
            with mock.patch.object(doctor, "EXPECTED_WAV2VEC2_FILES", expected):
                self.assertTrue(doctor.wav2vec2_model_identity(snapshot)["verified"])
                (snapshot / "model.bin").write_bytes(payload + b"changed")
                self.assertFalse(doctor.wav2vec2_model_identity(snapshot)["verified"])

    def test_universal_wav2vec2_model_identity_requires_the_exact_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            snapshot = Path(name)
            payload = b"pinned universal model fixture"
            expected = {"model.bin": (len(payload), hashlib.sha256(payload).hexdigest())}
            (snapshot / "model.bin").write_bytes(payload)
            with mock.patch.object(
                doctor, "EXPECTED_UNIVERSAL_WAV2VEC2_FILES", expected
            ):
                self.assertTrue(
                    doctor.universal_wav2vec2_model_identity(snapshot)["verified"]
                )
                (snapshot / "model.bin").write_bytes(payload + b"changed")
                self.assertFalse(
                    doctor.universal_wav2vec2_model_identity(snapshot)["verified"]
                )

    def test_future_song_policy_preflight_is_read_only_and_fails_closed(self) -> None:
        from stemwise_engine.future_song import FutureSongError, FutureSongWorkflow

        with tempfile.TemporaryDirectory() as name:
            workspace = Path(name)
            config_path = workspace / "PC side" / "builder" / "config.json"
            config_path.parent.mkdir(parents=True)
            config_path.touch()
            config = {
                "generatorVersion": "2.1.0",
                "pcSideRootWindows": str(workspace / "PC side"),
                "futureRootWindows": str(config_path.parent / "future-songs"),
            }

            def valid_policy(workflow: FutureSongWorkflow) -> dict[str, object]:
                self.assertFalse(hasattr(workflow, 'campaign_recovery_authorization_path'))
                return {"policyVersion": "stemwise-future-song/test"}

            with mock.patch('stemwise_engine.doctor.load_config', return_value=config), mock.patch.object(
                FutureSongWorkflow, "_validate_policy", autospec=True, side_effect=valid_policy
            ), mock.patch.object(Path, "mkdir", side_effect=AssertionError("must stay read-only")):
                ready = doctor.future_song_policy_identity(config_path)

            self.assertTrue(ready["ready"])
            self.assertTrue(ready["deliveryAllowed"])
            self.assertFalse(ready["qualificationClaimsAllowed"])

            with mock.patch('stemwise_engine.doctor.load_config', return_value=config), mock.patch.object(
                FutureSongWorkflow,
                "_validate_policy",
                side_effect=FutureSongError("stale policy binding"),
            ):
                stale = doctor.future_song_policy_identity(config_path)

            self.assertEqual(stale, {"ready": False, "error": "FutureSongError"})


if __name__ == "__main__":
    unittest.main()
