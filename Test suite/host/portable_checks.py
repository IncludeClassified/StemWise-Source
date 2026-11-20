"""Credential-free repository checks; excludes signing, devices, providers and models."""
import ast
import argparse
from pathlib import Path
import subprocess
import sys
import time
import unittest
import warnings

ROOT = Path(__file__).resolve().parents[2]
SUITE = ROOT/'Test suite'
PORTABLE_TEST_MODULES = (
    'test_ci_changes', 'test_source_test_inventory', 'test_device_inventory', 'test_playback_performance',
    'test_document_contracts', 'test_vendor_sources', 'test_environment_locks',
    'test_runtime_layout', 'test_workflow_reports', 'test_native_device_merger')
PORTABLE_TEST_GROUPS = {'test_runtime_layout': 'runtimeOnly'}


def portable_test_selections(pc=SUITE/'pc'):
    from test_plan import classifications
    rules = classifications(pc)
    selections = []
    for module in PORTABLE_TEST_MODULES:
        group = PORTABLE_TEST_GROUPS.get(module)
        if group is None:
            selections.append(module)
            continue
        methods = rules[f'unit/{module}'].get(group)
        if not isinstance(methods, list) or not methods:
            raise ValueError(f'Missing portable PC test group: {module}.{group}')
        selections.extend(f'{module}.{method}' for method in methods)
    return selections


def validate_python_names(source, *, filename):
    try:
        from pyflakes.checker import Checker
        from pyflakes.messages import UndefinedName, UndefinedLocal, UndefinedExport
    except ImportError as error:
        raise RuntimeError('Install Test suite/python-checks-requirements.txt with --require-hashes before portable checks') from error
    issues = [message for message in Checker(ast.parse(source, filename=filename), filename=filename).messages
              if isinstance(message, (UndefinedName, UndefinedLocal, UndefinedExport))]
    if issues:
        raise ValueError('\n'.join(str(message) for message in issues))


def check_python_sources(root=ROOT):
    current = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard',
                                      '-z', '--', '*.py'], cwd=root, timeout=15).decode('utf-8').split('\0')
    files = sorted({relative for relative in current if relative and (root/relative).is_file()})
    with warnings.catch_warnings():
        warnings.simplefilter('error',SyntaxWarning)
        for relative in files:
            source = (root/relative).read_text(encoding='utf-8-sig')
            ast.parse(source, filename=relative)
            if not relative.startswith('StemWise/third_party/'):
                validate_python_names(source, filename=relative)
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-only', action='store_true')
    parser.add_argument('--covered-pc-modules', nargs='+', default=[])
    args = parser.parse_args()
    if args.source_only and not set(PORTABLE_TEST_MODULES) <= {
            name.rsplit('/', 1)[-1] for name in args.covered_pc_modules}:
        parser.error('--source-only requires the complete portable module selection in the PC phase')
    (SUITE/'work').mkdir(exist_ok=True)
    subprocess.run([sys.executable, '-B', str(SUITE/'host/merger_math.py'), '--check'],
                   cwd=ROOT, check=True, timeout=60)
    check_python_sources()
    if args.source_only:
        return 0
    sys.path.insert(0,str(SUITE/'host'))
    sys.path.insert(0,str(SUITE/'pc/unit'))
    sys.path.insert(0,str(SUITE/'pc'))
    from test_timing import run_timed
    started = time.perf_counter()
    tests = unittest.defaultTestLoader.loadTestsFromNames(portable_test_selections())
    result = run_timed(tests, load_seconds=time.perf_counter() - started)
    return 0 if result.wasSuccessful() and result.testsRun > 0 and not result.skipped else 1


if __name__ == '__main__':
    raise SystemExit(main())
