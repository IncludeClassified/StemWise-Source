"""Alternate two render benchmark binaries, checking exact PCM and recording repeatable timing."""
import argparse
from datetime import datetime, timezone
import hashlib
from fractions import Fraction
import json
import math
from pathlib import Path
import platform
import statistics
import subprocess


def parse_output(text):
    records = {}
    for line in text.splitlines():
        if not line.startswith('mode='):
            continue
        fields = dict(part.split('=', 1) for part in line.split())
        mode = fields.pop('mode')
        if mode in records:
            raise ValueError('Duplicate benchmark mode: ' + mode)
        row = {key: int(value) for key, value in fields.items()}
        if not {'block', 'frames', 'render_ns', 'pcm_hash', 'allocations', 'p50_ns', 'p95_ns'} <= row.keys():
            raise ValueError('Incomplete benchmark evidence')
        if row['allocations'] != 0 or min(row['block'], row['frames'], row['render_ns']) <= 0:
            raise ValueError('Allocation or invalid timing gate failed')
        if row['p50_ns'] <= 0 or row['p95_ns'] < row['p50_ns']:
            raise ValueError('Timing percentiles need positive measurements; use finer or longer benchmark runs')
        records[mode] = row
    if set(records) != {'direct', 'tuned', 'returned-neutral'}:
        raise ValueError('Missing required render scenarios')
    return records


def compare_rows(before, after):
    for mode in before:
        for field in ('block', 'frames', 'pcm_hash', 'allocations'):
            if before[mode][field] != after[mode][field]:
                raise ValueError(f'{mode}: {field} changed; timing comparison rejected')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', required=True)
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--runs', type=int, default=5)
    parser.add_argument('--output', required=True)
    parser.add_argument('--max-regression-percent', type=float, default=10.0)
    args = parser.parse_args()
    if args.runs < 3 or not math.isfinite(args.max_regression_percent) or args.max_regression_percent < 0:
        parser.error('At least three pairs and a finite nonnegative timing budget are required.')
    destination = Path(args.output)
    if destination.exists():
        parser.error('Use a new output file to preserve previous measurements.')
    binaries = {role: Path(getattr(args, role)).resolve() for role in ('baseline', 'candidate')}
    report = {'status': 'FAIL', 'scope': 'HOST_RENDER_ONLY', 'deviceAudioFaults': None,
              'startedUtc': datetime.now(timezone.utc).isoformat(), 'host': platform.platform(),
              'timingBudgetPercent': args.max_regression_percent,
              'binaries': {role: {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                           for role, path in binaries.items()}, 'pairs': []}
    destination.parent.mkdir(parents=True, exist_ok=True)

    def run(role):
        completed = subprocess.run([str(binaries[role]), '--benchmark'], capture_output=True,
                                   text=True, timeout=180)
        if completed.returncode:
            raise RuntimeError(f'{role} exited {completed.returncode}: {completed.stderr[-1000:]}')
        return parse_output(completed.stdout)

    try:
        # Warm each binary once; alternate first/second to reduce consistent order bias.
        warm = {role: run(role) for role in binaries}
        compare_rows(warm['baseline'], warm['candidate'])
        for index in range(args.runs):
            order = ['baseline', 'candidate'] if index % 2 == 0 else ['candidate', 'baseline']
            pair = {'order': order}
            report['pairs'].append(pair)
            for role in order:
                pair[role] = run(role)
                compare_rows(warm[role], pair[role])
            compare_rows(pair['baseline'], pair['candidate'])
        summary = {}
        for mode in warm['baseline']:
            summary[mode] = {}
            for metric in ('render_ns', 'p50_ns', 'p95_ns'):
                before = statistics.median(pair['baseline'][mode][metric] for pair in report['pairs'])
                after = statistics.median(pair['candidate'][mode][metric] for pair in report['pairs'])
                summary[mode][metric] = {'baselineMedian': before, 'candidateMedian': after,
                                        'changePercent': round((after - before) * 100 / before, 9) if before else None}
        report['summary'] = summary
        report['timingRegressions'] = [f'{mode}/{metric}' for mode, metrics in summary.items()
                                      for metric, value in metrics.items()
                                      if (Fraction(value['candidateMedian']) - Fraction(value['baselineMedian'])) * 100 >
                                      Fraction(value['baselineMedian']) * Fraction(str(args.max_regression_percent))]
        report['status'] = 'TIMING_REVIEW_REQUIRED' if report['timingRegressions'] else 'PASS'
        print(json.dumps({'status': report['status'], 'summary': summary}, indent=2))
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        report['finishedUtc'] = datetime.now(timezone.utc).isoformat()
        destination.write_text(json.dumps(report, indent=2), encoding='utf-8')
    return 2 if report['timingRegressions'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
