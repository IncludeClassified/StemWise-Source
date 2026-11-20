"""Bounded SC handoff checks over the production header."""
import argparse
import json
from pathlib import Path
import re
import shutil
import sys
import time


ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "StemWise/audio-engine/src/main/cpp"
HARNESS = Path(__file__).with_name("output_handoff.cpp")
sys.path.insert(0, str(ROOT / 'Test suite/host'))
from owned_process import run
import audio_evidence
from stemwise_engine.common import WorkerTemporaryDirectory
CASES = (("immediate", "check_protocol", 0), ("drain", "check_protocol", 1),
         ("loop", "check_protocol", 2), ("concurrent-reuse", "check_concurrent_reuse", 0),
         ("concurrent-fence", "check_concurrent_fence", 0),
         ("concurrent-loop-writes", "check_concurrent_loop_writes", 0))
MUTATIONS = {
    'reject-early-ack': (
        'publicationDisposition(std::memory_order_acquire) !=\n                kPublicationSwapCommitted',
        'false', 0, 'roles.activeRing() == outgoing'),
    'reject-old-loop-writes': (
        'mActiveWritesAllowed.store(false, std::memory_order_relaxed);',
        'mActiveWritesAllowed.store(true, std::memory_order_relaxed);', 2,
        '!roles.writableRingForGeneration(owner).valid()'),
}
# The pinned ESBMC 8.5.0 reports a property counterexample with exit 1.
COUNTEREXAMPLE_EXIT_CODE = 1


def violates_assertion(output, assertion):
    for header in re.finditer(r'(?m)^[ \t]*Violated property:[ \t]*\r?$', output):
        block = re.split(r'(?m)^(?:State [0-9]+\b|\*\* Results:|\*\* [0-9]+\b|[ \t]*Violated property:)',
                         output[header.end():], maxsplit=1)[0]
        lines = [line.strip() for line in block.splitlines() if line.strip().startswith('assertion ')]
        if lines == ['assertion ' + assertion]:
            return True
    return False


def command_for(binary, function, mode, timeout, includes):
    return [str(binary), str(HARNESS), *[argument for path in includes for argument in ('-I', str(path))],
            '-D__ESBMC__', f'-DFORMAL_MODE={mode}', '--std', 'c++17', '--function', function,
            '--quiet', '--verbosity', '5', '--unwind', '3', '--unwindsetname',
            'N@stemwise@N@audio@S@GuardedAtomicSnapshot@read<:0:9',
            '--context-bound', '2', '--timeout', f'{timeout}s', '--z3']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--esbmc", type=Path, default=ROOT / "Test suite/work/tools/esbmc-8.5/release/bin/esbmc")
    parser.add_argument("--case", choices=[case[0] for case in CASES] + list(MUTATIONS))
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = {'schemaVersion': 1, 'lane': 'protocol',
              'sourceSha256': audio_evidence.source_identity() if args.report else None,
              'required': [args.case] if args.case else [case[0] for case in CASES] + list(MUTATIONS),
              'records': [], 'cases': [], 'tools': {}}
    try:
        verify(args, parser, report)
    finally:
        if args.report:
            report['sourceEndSha256'] = audio_evidence.source_identity()
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


def verify(args, parser, report):
    binary = args.esbmc.resolve()
    version = run([str(binary), "--version"], timeout=10, check=True).stdout.strip()
    if not re.search(r"\b8\.5\.0\b", version):
        parser.error("This harness requires the documented ESBMC 8.5.0 atomic model.")
    if args.report:
        compiler = Path(shutil.which('g++')).resolve()
        report['tools'] = {
            'esbmc': {'path': str(binary), 'sha256': audio_evidence.digest(binary), 'version': version},
            'compiler': {'path': str(compiler), 'sha256': audio_evidence.digest(compiler),
                         'version': run([str(compiler), '--version'], timeout=10, check=True).stdout.strip()},
        }
    # Real standard-library trait checks stay enabled here; the model lacks them.
    run(["g++", "-std=c++17", "-UNDEBUG", "-Wall", "-Wextra", "-Werror",
         "-fsyntax-only", "-I", str(SOURCE), str(HARNESS)], timeout=30, check=True)
    for name, function, mode in CASES:
        if args.case and name != args.case:
            continue
        # Eight snapshot retries multiply the concurrent loop-write interleavings.
        # Keep that full safety scope, with a separate bounded solver budget.
        timeout = 600 if name == "concurrent-loop-writes" else 120
        # Include all eight bounded snapshot attempts and their terminating condition.
        command = command_for(binary, function, mode, timeout, (SOURCE,))
        started = time.monotonic()
        result = run(command, timeout=timeout + 15)
        output = result.stdout + result.stderr
        summary = re.search(r"\*\* 0 of ([1-9][0-9]*) properties failed, \1 passed", output)
        passed = not result.returncode and bool(summary) and "VERIFICATION SUCCESSFUL" in output
        record(report, name, result, passed, 'verified')
        if not passed:
            print("\n".join(output.splitlines()[-100:]))
            raise SystemExit(f"FAIL: {name}; verifier exit {result.returncode}")
        print(f"PASS: {name}; {summary[1]} properties; {time.monotonic()-started:.2f}s", flush=True)
    header = SOURCE / 'OutputRingRoleCoordinator.h'
    for name, (before, after, mode, assertion) in MUTATIONS.items():
        if args.case and name != args.case:
            continue
        original = header.read_text(encoding='utf-8')
        if original.count(before) != 1:
            raise SystemExit('Negative-control mutation no longer matches the exact production guard: ' + name)
        with WorkerTemporaryDirectory(prefix=name + '-', dir=ROOT / 'Test suite/work') as directory:
            mutated = Path(directory)
            (mutated / header.name).write_text(original.replace(before, after), encoding='utf-8')
            result = run(command_for(binary, 'check_protocol', mode, 120, (mutated, SOURCE)), timeout=135)
            output = result.stdout + result.stderr
            passed = (result.returncode == COUNTEREXAMPLE_EXIT_CODE and bool(re.search(r'\*\* [1-9][0-9]* of [1-9][0-9]* properties failed', output)) and
                      violates_assertion(output, assertion))
            record(report, name, result, passed, 'counterexample', assertion)
            if not passed:
                print('\n'.join(output.splitlines()[-100:]))
                raise SystemExit('Required ownership counterexample absent: ' + name)
            print(f'PASS: {name}; actual production-header counterexample: {assertion}', flush=True)


def record(report, name, result, passed, expected, assertion=None):
    report['records'].append([name, 'passed' if passed else 'failed'])
    report['cases'].append({'id': name, 'expected': expected, 'assertion': assertion,
                            'returnCode': result.returncode, 'stdout': result.stdout,
                            'stderr': result.stderr})


if __name__ == "__main__":
    main()
