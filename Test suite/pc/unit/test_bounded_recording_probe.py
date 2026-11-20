"""A stuck recording probe cannot outlive its bounded inspection."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
import stemwise_media.compare as compare


class BoundedRecordingProbeTest(unittest.TestCase):
    def test_blocked_probe_is_retired_and_never_returns_a_partial_identity(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            executable = root / 'ffprobe'
            identity = root / 'probe.pid'
            executable.write_text('#!/usr/bin/env python3\nimport os,time\n'
                f'from pathlib import Path\nPath({str(identity)!r}).write_text(str(os.getpid()))\n'
                'time.sleep(10)\nprint("{}")\n', encoding='utf-8')
            executable.chmod(0o755)
            with patch.dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH']):
                with self.assertRaises(subprocess.TimeoutExpired):
                    compare.probe(root / 'recording.flac', timeout_seconds=1)
            self.assertTrue(identity.is_file(), 'Probe never reached the actual child')
            with self.assertRaises(ProcessLookupError):
                os.kill(int(identity.read_text()), 0)


if __name__ == '__main__':
    unittest.main()
