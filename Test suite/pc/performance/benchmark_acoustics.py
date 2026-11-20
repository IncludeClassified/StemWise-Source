"""Measure actual acoustic workers on fixed local inputs without publishing jobs."""
import argparse
import json
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT / 'PC side/stemwise-playlist-builder'
from stemwise_engine.common import atomic_write_json, exclusive_process_lock, heavy_worker_lock_path, run_process_tree
from stemwise_engine.pipeline import run_analysis_worker
from stemwise_engine.recording_analysis import require_analysis
from stemwise_engine.youtube import decode_analysis_audio, probe_duration_ms


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', action='append', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--result', type=Path, required=True)
    parser.add_argument('--reference', type=Path)
    parser.add_argument('--seed', type=int)
    args = parser.parse_args()
    work = args.work.resolve()
    if not work.is_relative_to(ROOT / 'Test suite/work'):
        parser.error('Benchmark work must stay under Test suite/work')
    if not args.result.resolve().is_relative_to(work):
        parser.error('Benchmark results must stay inside the selected work directory')
    if args.reference and args.result.resolve() == args.reference.resolve():
        parser.error('Results cannot overwrite the comparison reference')
    cache = PROJECT.parent / 'model-cache'
    os.environ.update(HF_HOME=str(cache / 'huggingface'), TORCH_HOME=str(cache / 'torch'),
                      XDG_CACHE_HOME=str(cache / 'xdg'), HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1')
    if args.seed is not None:
        hook = work / 'seed-hook'
        hook.mkdir(parents=True, exist_ok=True)
        (hook / 'sitecustomize.py').write_text(f'import random\nrandom.seed({args.seed!r})\n')
        os.environ['PYTHONPATH'] = os.pathsep.join([str(hook), os.environ.get('PYTHONPATH', '')])
    reference = json.loads(args.reference.read_bytes()) if args.reference else None
    if reference and len(reference['cases']) != len(args.input):
        parser.error('Reference and measured input counts must match')
    cases = []
    with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(PROJECT)})):
        for index, source in enumerate(args.input):
            directory = work / f'input-{index}'
            directory.mkdir(parents=True, exist_ok=True)
            wav = decode_analysis_audio(source.resolve(strict=True), directory, timeout_seconds=120)
            duration = probe_duration_ms(wav)
            started = time.perf_counter()
            analysis = run_analysis_worker(wav, duration, PROJECT, 1200)
            seconds = time.perf_counter() - started
            require_analysis(analysis, duration)
            case = {'source': source.name, 'durationMs': duration, 'seconds': seconds, 'analysis': analysis}
            cases.append(case)
            atomic_write_json(args.result, {'cases': cases})
            if reference:
                old = reference['cases'][index]
                if old['source'] != case['source'] or old['durationMs'] != duration:
                    raise ValueError('Benchmark inputs differ')
                if old['analysis'] != analysis:
                    raise ValueError('Parallel analysis differs from the serial result')
            print(json.dumps({'source': source.name, 'seconds': round(seconds, 3),
                              'sameResult': True if reference else None}), flush=True)
    total = sum(case['seconds'] for case in cases)
    print(json.dumps({'totalSeconds': round(total, 3), 'recordingsPerHour': round(len(cases)*3600/total, 3)}))


if __name__ == '__main__':
    main()
