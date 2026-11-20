"""Run required PC checks; empty selections and skipped checks cannot qualify."""
import argparse
from pathlib import Path
import sys
import tempfile
import time
import unittest
from test_timing import run_timed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--discover', action='store_true')
    parser.add_argument('modules', nargs='*')
    args = parser.parse_args()
    if args.discover == bool(args.modules):
        parser.error('Select either discovery or named modules')
    work = Path(__file__).resolve().parents[1] / 'work'
    work.mkdir(parents=True, exist_ok=True)
    tempfile.tempdir = str(work)
    sys.path.insert(0, str(Path.cwd()))
    sys.path.insert(0, str(args.directory.resolve()))
    started = time.perf_counter()
    loader = unittest.defaultTestLoader
    suite = (loader.discover(str(args.directory), pattern='test_*.py') if args.discover
             else loader.loadTestsFromNames(args.modules))
    result = run_timed(suite, verbosity=2, load_seconds=time.perf_counter() - started)
    executed = result.testsRun - len(result.skipped)
    if executed == 0 or result.skipped:
        print(f'PC qualification rejected: {executed} executed, {len(result.skipped)} skipped.',
              file=sys.stderr)
        return 1
    return int(not result.wasSuccessful())


if __name__ == '__main__':
    sys.exit(main())
