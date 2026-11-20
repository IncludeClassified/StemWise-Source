"""Timing accounts for real unittest fixture boundaries and keeps failures/skips visible."""
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

PC = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PC))
from test_timing import run_timed


class TestTimingTest(unittest.TestCase):
    def test_body_and_fixture_accounting_use_executed_unittest_boundaries(self):
        clock = [0.0]

        class Fixture(unittest.TestCase):
            @classmethod
            def setUpClass(cls):
                clock[0] += 11

            def setUp(self):
                clock[0] += 3

            def test_body(self):
                clock[0] += 7

            def tearDown(self):
                clock[0] += 2

        stream = io.StringIO()
        test = Fixture('test_body')
        with patch('test_timing.time.perf_counter', side_effect=lambda: clock[0]):
            result = run_timed(unittest.TestSuite([test]), load_seconds=4, stream=stream)
        summary = json.loads(stream.getvalue().split('TEST_TIMING ')[1])
        self.assertTrue(result.wasSuccessful())
        self.assertEqual((4, 23, 7, 5, 11), tuple(summary[key] for key in
            ('loadSeconds', 'runSeconds', 'bodySeconds', 'testFixtureSeconds', 'suiteFixtureAndRunnerSeconds')))
        self.assertEqual(summary['slowestMethods'][0]['method'], test.id())
        self.assertNotIn('_callTestMethod', test.__dict__)

    def test_real_runner_reports_failure_and_skip_without_false_qualification(self):
        with tempfile.TemporaryDirectory(dir=PC.parent/'work') as name:
            directory = Path(name)
            (directory/'test_fixture.py').write_text('''import unittest
class Check(unittest.TestCase):
 def test_failure(self): self.fail('injected timing failure')
 @unittest.skip('injected skip')
 def test_skip(self): raise AssertionError('must not execute')
''', encoding='utf-8')
            result = subprocess.run([sys.executable, '-B', str(PC/'run_tests.py'), '--directory',
                str(directory), 'test_fixture'], capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 1, result.stdout+result.stderr)
            self.assertIn('injected timing failure', result.stderr)
            self.assertIn('test_fixture.py', result.stderr)
            self.assertIn('1 executed, 1 skipped', result.stderr)
            summary = json.loads(next(line.removeprefix('TEST_TIMING ') for line in result.stderr.splitlines()
                                     if line.startswith('TEST_TIMING ')))
            rows = {row['method']: row for row in summary['slowestMethods']}
            self.assertEqual(set(rows), {'test_fixture.Check.test_failure', 'test_fixture.Check.test_skip'})
            self.assertEqual(rows['test_fixture.Check.test_skip']['bodySeconds'], 0)
            self.assertGreater(summary['runSeconds'], 0)
