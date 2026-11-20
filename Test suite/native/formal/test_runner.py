"""Exercise runner control flow with a substituted verifier, not formal qualification."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('formal_verify', Path(__file__).with_name('verify.py'))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class FormalRunnerTest(unittest.TestCase):
    def setUp(self):
        if sys.platform != 'win32':
            return
        # These verifier substitutes launch no workers that need the Linux directory lease.
        scratch = patch.object(verify, 'WorkerTemporaryDirectory', tempfile.TemporaryDirectory)
        scratch.start()
        self.addCleanup(scratch.stop)

    def test_qualified_version_reaches_compilation_and_all_six_model_cases(self):
        calls = []

        def run(command, **options):
            calls.append((command, options))
            if '--version' in command:
                output = 'ESBMC version 8.5.0'
            elif command[0] == 'g++':
                output = ''
            elif command[command.index('-I') + 1] != str(verify.SOURCE):
                name = Path(command[command.index('-I') + 1]).name
                assertion = verify.MUTATIONS[next(key for key in verify.MUTATIONS if name.startswith(key))][3]
                return subprocess.CompletedProcess(command, 1,
                    'Violated property:\n assertion ' + assertion + '\n** 1 of 69 properties failed', '')
            else:
                output = '** 0 of 17 properties failed, 17 passed\nVERIFICATION SUCCESSFUL'
            return subprocess.CompletedProcess(command, 0, output, '')

        with patch.object(verify, 'run', run), patch.object(sys, 'argv', ['verify.py']), contextlib.redirect_stdout(io.StringIO()):
            verify.main()
        self.assertEqual(10, len(calls))
        self.assertEqual('g++', calls[1][0][0])
        self.assertEqual({'timeout': 30, 'check': True}, calls[1][1])
        models = calls[2:8]
        self.assertEqual([function for _, function, _ in verify.CASES],
                         [command[command.index('--function') + 1] for command, _ in models])
        self.assertEqual([135, 135, 135, 135, 135, 615],
                         [options['timeout'] for _, options in models])
        self.assertEqual(['120s', '120s', '120s', '120s', '120s', '600s'],
                         [command[command.index('--timeout') + 1] for command, _ in models])
        self.assertTrue(all(command[command.index('--unwind') + 1] == '3' for command, _ in models))
        self.assertTrue(all(command[command.index('--unwindsetname') + 1] ==
            'N@stemwise@N@audio@S@GuardedAtomicSnapshot@read<:0:9' for command, _ in models))

    def test_model_failure_stops_without_reporting_a_pass(self):
        responses = [subprocess.CompletedProcess([], 0, '8.5.0', ''),
                     subprocess.CompletedProcess([], 0, '', ''),
                     subprocess.CompletedProcess([], 1, 'VERIFICATION FAILED', '')]
        output = io.StringIO()
        with patch.object(verify, 'run', side_effect=responses) as run, patch.object(sys, 'argv', ['verify.py']), contextlib.redirect_stdout(output):
            with self.assertRaisesRegex(SystemExit, 'FAIL: immediate'):
                verify.main()
        self.assertEqual(3, run.call_count)
        self.assertNotIn('PASS:', output.getvalue())

    def test_negative_control_rejects_an_unrelated_solver_error(self):
        responses = [subprocess.CompletedProcess([], 0, '8.5.0', ''),
                     subprocess.CompletedProcess([], 0, '', ''),
                     subprocess.CompletedProcess([], 1, 'compiler error; not a counterexample', '')]
        with patch.object(verify, 'run', side_effect=responses), patch.object(sys, 'argv',
                ['verify.py', '--case', 'reject-early-ack']), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(SystemExit, 'counterexample absent'):
                verify.main()

    def test_negative_control_rejects_a_signal_or_wrong_exit_after_expected_property_output(self):
        assertion = verify.MUTATIONS['reject-early-ack'][3]
        for exit_code in (-11, 10, 124):
            responses = [subprocess.CompletedProcess([], 0, '8.5.0', ''),
                         subprocess.CompletedProcess([], 0, '', ''),
                         subprocess.CompletedProcess([], exit_code,
                             'Violated property:\n assertion ' + assertion + '\n** 1 of 69 properties failed', '')]
            with self.subTest(exit_code=exit_code), patch.object(verify, 'run', side_effect=responses), \
                    patch.object(sys, 'argv', ['verify.py', '--case', 'reject-early-ack']), \
                    contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(SystemExit, 'counterexample absent'):
                    verify.main()

    def test_only_the_assertion_inside_an_actual_violation_block_counts(self):
        assertion = verify.MUTATIONS['reject-early-ack'][3]
        for boundary in ('** Results:', 'State 69 file trace.cpp line 55 function check_protocol thread 0'):
            output = ('Violated property:\n  file trace.cpp line 54 function check_protocol\n'
                      '  assertion wrong_owner\n' + boundary + '\n  assertion ' + assertion +
                      '\n** 1 of 69 properties failed')
            self.assertFalse(verify.violates_assertion(output, assertion))
            responses = [subprocess.CompletedProcess([], 0, '8.5.0', ''),
                         subprocess.CompletedProcess([], 0, '', ''),
                         subprocess.CompletedProcess([], 1, output, '')]
            with self.subTest(boundary=boundary), patch.object(verify, 'run', side_effect=responses), \
                    patch.object(sys, 'argv', ['verify.py', '--case', 'reject-early-ack']), \
                    contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(SystemExit, 'counterexample absent'):
                    verify.main()

    def test_report_retains_actual_failed_property_output_and_source_change(self):
        responses = [subprocess.CompletedProcess([], 0, '8.5.0', ''),
                     subprocess.CompletedProcess([], 0, 'g++ test version', ''),
                     subprocess.CompletedProcess([], 0, '', ''),
                     subprocess.CompletedProcess([], 1, 'compiler error; not a counterexample', '')]
        with tempfile.TemporaryDirectory(dir=verify.ROOT / 'Test suite/work') as directory:
            report = Path(directory) / 'protocol.json'
            with patch.object(verify, 'run', side_effect=responses), \
                    patch.object(verify.shutil, 'which', return_value='/usr/bin/g++'), \
                    patch.object(verify.audio_evidence, 'digest', return_value='tool-hash'), \
                    patch.object(verify.audio_evidence, 'source_identity', side_effect=['before', 'after']), \
                    patch.object(sys, 'argv', ['verify.py', '--case', 'reject-early-ack', '--report', str(report)]), \
                    contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(SystemExit, 'counterexample absent'):
                    verify.main()
            actual = json.loads(report.read_text(encoding='utf-8'))
            self.assertEqual([['reject-early-ack', 'failed']], actual['records'])
            self.assertEqual('compiler error; not a counterexample', actual['cases'][0]['stdout'])
            self.assertEqual(('before', 'after'), (actual['sourceSha256'], actual['sourceEndSha256']))


if __name__ == '__main__':
    unittest.main()
