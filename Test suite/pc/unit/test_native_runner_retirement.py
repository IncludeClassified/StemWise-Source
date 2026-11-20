"""Exercise real runner cleanup around controlled compiler/verifier failures."""
import importlib.util
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from stemwise_engine.common import WorkerRetirementError, process_lock_descriptor, WORKER_DIRECTORY_LOCK

ROOT = Path(__file__).resolve().parents[3]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@unittest.skipIf(os.name == 'nt', 'Native process leases require the WSL/Linux runtime')
class NativeRunnerRetirementTest(unittest.TestCase):
    def test_mutation_runner_preserves_inputs_and_live_header_until_retirement(self):
        runner = load('native_retirement_mutations', ROOT / 'Test suite/native/run_negative_controls.py')
        for stage in ('configure', 'mutant-compile', 'mutant-verifier'):
          for fatal in (True, False):
            with self.subTest(stage=stage, fatal=fatal), tempfile.TemporaryDirectory(
                    prefix='runner-retirement-test-', dir=ROOT / 'Test suite/work') as directory:
                root = Path(directory)
                error = WorkerRetirementError('worker still owns inputs') if fatal else RuntimeError('retired failure')
                builds = 0
                executions = 0

                def run(command, **kwargs):
                    nonlocal builds, executions
                    if '--version' in command:
                        return subprocess.CompletedProcess(command, 0, 'fixture tool', '')
                    work = next((root / 'Test suite/work').glob('audio-mutations-*'))
                    self.assertIsNotNone(process_lock_descriptor(work / WORKER_DIRECTORY_LOCK))
                    if '-S' in command:
                        at = 'configure'
                    elif '--build' in command:
                        builds += 1
                        at = 'mutant-compile' if builds == 2 else 'baseline-compile'
                    else:
                        executions += 1
                        at = 'mutant-verifier' if executions == 2 else 'baseline-verifier'
                    if at == stage:
                        raise error
                    return subprocess.CompletedProcess(command, 0, '', '')

                with patch.object(runner, 'ROOT', root), patch.object(runner, 'run', run), \
                        patch.object(runner, 'os', SimpleNamespace(name='posix', environ=os.environ)), \
                        patch.dict(os.environ), \
                        patch.object(runner.shutil, 'which', return_value=sys.executable), \
                        patch.object(runner.audio_evidence, 'source_identity', return_value='fixture'), \
                        patch.object(sys, 'argv', ['runner', '--case', 'stale-control']):
                    with self.assertRaises(type(error)) as caught:
                        runner.main()
                self.assertIs(error, caught.exception)
                retained = list((root / 'Test suite/work').glob('audio-mutations-*'))
                self.assertEqual(1 if fatal else 0, len(retained))
                if retained:
                    path, before, after, *_ = runner.CASES['stale-control']
                    text = (retained[0] / 'source' / path).read_text(encoding='utf-8')
                    self.assertEqual(stage == 'configure', before in text)
                    if stage != 'configure':
                        self.assertIn(after, text)

    def test_formal_mutation_retains_verifier_input_after_fatal_retirement(self):
        runner = load('native_retirement_formal', ROOT / 'Test suite/native/formal/verify.py')
        for fatal in (True, False):
            with self.subTest(fatal=fatal), tempfile.TemporaryDirectory(
                    prefix='verifier-retirement-test-', dir=ROOT / 'Test suite/work') as directory:
                root = Path(directory)
                (root / 'Test suite/work').mkdir(parents=True)
                error = WorkerRetirementError('verifier still owns header') if fatal else RuntimeError('retired verifier')

                def run(command, **kwargs):
                    if '--version' in command:
                        return subprocess.CompletedProcess(command, 0, 'ESBMC 8.5.0', '')
                    if '-fsyntax-only' in command:
                        return subprocess.CompletedProcess(command, 0, '', '')
                    work = next((root / 'Test suite/work').glob('reject-early-ack-*'))
                    self.assertIsNotNone(process_lock_descriptor(work / WORKER_DIRECTORY_LOCK))
                    raise error

                args = SimpleNamespace(esbmc=root / 'verifier', case='reject-early-ack', report=None)
                with patch.object(runner, 'ROOT', root), patch.object(runner, 'run', run):
                    with self.assertRaises(type(error)) as caught:
                        runner.verify(args, SimpleNamespace(), {'records': [], 'cases': []})
                self.assertIs(error, caught.exception)
                retained = list((root / 'Test suite/work').glob('reject-early-ack-*'))
                self.assertEqual(1 if fatal else 0, len(retained))
                if retained:
                    self.assertTrue((retained[0] / 'OutputRingRoleCoordinator.h').is_file())


if __name__ == '__main__':
    unittest.main()
