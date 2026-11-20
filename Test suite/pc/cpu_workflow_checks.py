"""Deterministic production workflow checks; model execution is substituted, never qualified."""
from pathlib import Path
import sys
import subprocess
import tempfile
import time
import unittest
from test_plan import cpu_selections
from test_timing import TimedTestResult, run_timed

SUITE = Path(__file__).resolve().parents[1]
for directory in ('unit', 'alignment'):
    sys.path.insert(0, str(SUITE/'pc'/directory))


class WorkflowTestResult(TimedTestResult):
    def addError(self, test, error):
        failure = error[1]
        if isinstance(failure, subprocess.CalledProcessError) and failure.stderr:
            self.stream.writeln(f'\nChild stderr:\n{failure.stderr[-12000:]}')
        super().addError(test, error)


def main():
    (SUITE/'work').mkdir(exist_ok=True)
    tempfile.tempdir = str(SUITE/'work')
    subprocess.run([sys.executable, '-B', str(SUITE.parent/'StemWise/tools/regenerate-golden-waveform.py'), '--check'], check=True, timeout=30)
    started = time.perf_counter()
    tests = unittest.defaultTestLoader.loadTestsFromNames(cpu_selections())
    result = run_timed(tests, resultclass=WorkflowTestResult, verbosity=2, failfast=True,
                       load_seconds=time.perf_counter() - started)
    return 0 if result.wasSuccessful() and result.testsRun > 0 and not result.skipped else 1


if __name__ == '__main__':
    raise SystemExit(main())
