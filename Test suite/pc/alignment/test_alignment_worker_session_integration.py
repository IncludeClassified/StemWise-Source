"""Real parent calls share one bounded numerical lifetime without caching runtime replies."""
import argparse
import json
import importlib
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.alignment_models as alignment_models
import stemwise_engine.lyrics_align_pc as alignment
import stemwise_engine.alignment_worker_session as sessions
from stemwise_engine.common import _linux_process_identity


class AlignmentWorkerSessionIntegrationTests(unittest.TestCase):
    def test_runtime_probes_share_process_only_inside_current_operation(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "Test suite/work") as directory:
            source = Path(directory) / "stemwise_engine"
            source.mkdir()
            for dependency in ("__init__.py", "common.py", "runtime_environment.py", "alignment_worker_session.py",
                               "acoustic_inputs.py", "ctc_worker.py", "worker_protocol.py",
                               "worker_publication.py", "private_diagnostics.py"):
                (source / dependency).write_bytes((Path(sessions.__file__).parent / dependency).read_bytes())
            helper = source / "mms_forced_alignment.py"
            helper.write_text("import json,os\ndef main():\n"
                " value={'runtimeVersion':1,'bundle':'torchaudio.pipelines.MMS_FA',"
                "'requestedDevice':'cpu','resolvedDevice':'cpu','supportedRequestVersions':[5],"
                "'workerPid':os.getpid()}\n json.dump(value,__import__('sys').stdout)\n return 0\n"
                "if __name__=='__main__': raise SystemExit(main())\n", encoding="utf-8")
            (source / "wav2vec2_forced_alignment.py").write_bytes(helper.read_bytes())
            import stemwise_engine
            canonical = 'stemwise_engine.mms_forced_alignment'
            cached = sys.modules.pop(canonical, None)
            if cached is not None:
                self.addCleanup(sys.modules.__setitem__, canonical, cached)
            importlib.invalidate_caches()
            with patch.object(stemwise_engine, '__path__', [str(source)]), \
                    patch.dict(os.environ, {'PYTHONPATH': directory}), \
                    patch.object(alignment_models, "mms_runtime_pins", return_value={}):
                with sessions.alignment_worker_session():
                    first = alignment._mms_runtime_provenance(Path(sys.executable), helper,
                        device="cpu", timeout_seconds=10)
                    second = alignment._mms_runtime_provenance(Path(sys.executable), helper,
                        device="cpu", timeout_seconds=10)
                    self.assertEqual(first["workerPid"], second["workerPid"],
                        "Uncached runtime probes in one operation must reuse the owned worker")
                    identity = _linux_process_identity(first["workerPid"])
                    self.assertIsNotNone(identity)
                self.assertNotEqual(_linux_process_identity(first["workerPid"]), identity,
                    "Leaving the operation must retire its numerical process")
                with sessions.alignment_worker_session():
                    following = alignment._mms_runtime_provenance(Path(sys.executable), helper,
                        device="cpu", timeout_seconds=10)
                    self.assertNotEqual(following["workerPid"], first["workerPid"],
                        "Separate operations cannot share a numerical daemon")

    def test_alignment_scope_closes_when_setup_fails(self):
        self.assertIsNone(sessions.active_alignment_session())
        def fail_setup(_config):
            self.assertIsNotNone(sessions.active_alignment_session(),
                "Alignment must enter its owned numerical scope before setup")
            raise ValueError("controlled setup failure")
        args = argparse.Namespace(from_position=0, to_position=0, device='cpu', compute_type='float32')
        with patch.object(alignment, "_paths", side_effect=fail_setup):
            with self.assertRaisesRegex(ValueError, "controlled setup failure"):
                alignment._align({}, args)
        self.assertIsNone(sessions.active_alignment_session())


if __name__ == "__main__":
    unittest.main()
