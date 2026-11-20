"""Acoustic process entry: inherit the exact model lease and publish bounded rows."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import signal
from stemwise_engine.common import inherited_process_lock, WorkerRetirementError, WORKER_RETIREMENT_FAILURE_MARKER


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest='mode', required=True)
    single = modes.add_parser('single')
    single.add_argument('audio', type=Path)
    single.add_argument('duration', type=int)
    single.add_argument('project', type=Path)
    single.add_argument('timeout', type=int)
    single.add_argument('--repair-from', type=Path)
    batch = modes.add_parser('batch')
    batch.add_argument('inputs')
    batch.add_argument('project', type=Path)
    batch.add_argument('timeout', type=int)
    for command in (single, batch):
        command.add_argument('lock', type=Path)
        command.add_argument('descriptor', type=int)
    args = parser.parse_args(argv)

    def terminate(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, terminate)
    if args.mode == 'single':
        from stemwise_engine.analysis_numerics import _analysis_worker
        with inherited_process_lock(args.lock, args.descriptor):
            try:
                if args.repair_from is None:
                    result = _analysis_worker(args.audio, args.duration, args.project, args.timeout)
                else:
                    from stemwise_engine.analysis_numerics import _repair_analysis_worker
                    from stemwise_engine.common import read_bounded_json
                    from stemwise_engine.contract import MAX_JSON_BYTES
                    previous = read_bounded_json(args.repair_from, max_bytes=MAX_JSON_BYTES)
                    result = _repair_analysis_worker(args.audio, args.duration, args.project, args.timeout, previous)
            except WorkerRetirementError:
                print(WORKER_RETIREMENT_FAILURE_MARKER, flush=True)
                raise
        print('STEMWISE_ANALYSIS_RESULT=' + json.dumps(result, separators=(',', ':'), allow_nan=False))
    else:
        from stemwise_engine.analysis_numerics import _analysis_worker_many
        inputs = [(Path(path), duration) for path, duration in json.loads(args.inputs)]

        def publish(index: int, analysis: dict) -> None:
            print('STEMWISE_ACOUSTIC_ROW=' + json.dumps({'index': index, 'analysis': analysis},
                  separators=(',', ':'), allow_nan=False), flush=True)
        with inherited_process_lock(args.lock, args.descriptor):
            try:
                _analysis_worker_many(inputs, args.project, args.timeout, on_completed=publish)
            except WorkerRetirementError:
                print(WORKER_RETIREMENT_FAILURE_MARKER, flush=True)
                raise
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
