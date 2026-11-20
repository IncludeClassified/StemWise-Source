"""Profiler startup failures still retire an admitted remote trace."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SUITE = Path(__file__).resolve().parents[2]


class ProfileStartupTest(unittest.TestCase):
    def test_identity_and_configuration_failures_retire_the_started_trace(self):
        for fault in ('identity', 'configuration'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=SUITE / 'work') as name:
                root = Path(name)
                spec = importlib.util.spec_from_file_location('profile_playback', SUITE / 'device/profile_playback.py')
                profile = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(profile)
                identity = ('12345', ('perfetto', '-o', 'fixture'))
                reads = 0
                removals = 0

                def read(*args):
                    nonlocal reads
                    reads += 1
                    if fault == 'identity' and reads == 1:
                        raise OSError('identity discovery failed')
                    return identity

                def run(command, **kwargs):
                    nonlocal removals
                    if 'rm' in command:
                        removals += 1
                        if fault == 'configuration' and removals == 1:
                            raise OSError('configuration cleanup failed')
                    return subprocess.CompletedProcess(command, 0, '123\n', '')

                args = ['profile_playback', '--serial', 'emulator-5554', '--device-profile', str(root / 'device.json'),
                        '--class', 'example.Test', '--trace-processor', str(root / 'processor.py'),
                        '--output', str(root / 'output')]
                with patch.object(profile, 'os', SimpleNamespace(name='nt')), patch.object(sys, 'argv', args), \
                        patch.object(profile, 'run_owned', side_effect=run), \
                        patch.object(profile, 'read_perfetto_identity', side_effect=read), \
                        patch.object(profile, 'stop_perfetto') as stop, \
                        patch.object(profile, 'run_selection') as selection:
                    with self.assertRaisesRegex(OSError, fault):
                        profile.main()
                stop.assert_called_once()
                self.assertEqual('123', stop.call_args.args[1])
                selection.assert_not_called()
                report = json.loads((root / 'output/invocation.json').read_text(encoding='utf-8'))
                self.assertEqual('FAIL', report['status'])
                self.assertIn(fault, report['failure'])
