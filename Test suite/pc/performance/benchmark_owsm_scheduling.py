"""Measure repeated current endpoint jobs with exact numerical/input identity.

Uses an existing valid full-song request, optionally checked against a completed result.
Scratch outputs are disposable; no campaign job, media or approval is modified.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack, contextmanager
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT / 'PC side/stemwise-playlist-builder'
from stemwise_engine.common import heavy_worker_stage, owned_process_tree


@contextmanager
def benchmark_work():
    work = ROOT / 'Test suite/work/endpoint-scheduling-current'
    work.mkdir()
    try:
        yield work
    except BaseException:
        print(f'Unresolved benchmark outputs retained in {work}', file=sys.stderr)
        raise
    else:
        shutil.rmtree(work)


def numerical_results(rows, pcm_records):
    if len(rows) != len(pcm_records) or not rows or any(pcm != pcm_records[0] for pcm in pcm_records):
        raise ValueError('Prepared sample data differs between numerical requests')
    result=[]
    for row, pcm in zip(rows, pcm_records):
        value=json.loads(json.dumps(row))
        preparation=value['preprocessing']
        if (preparation['frameCount'] != pcm['frameCount']
                or preparation['sampleRate'] != pcm['sampleRate'] or pcm['channels'] != 1):
            raise ValueError('Prepared sample data does not match worker metadata')
        # FLOAT WAV PEAK chunks contain a wall-clock timestamp, outside audio samples.
        preparation['sha256']=pcm['sha256']
        result.append(value)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', type=Path, required=True)
    parser.add_argument('--reference-result', type=Path)
    parser.add_argument('--nfa', action='store_true', help='Measure NeMo NFA instead of OWSM.')
    parser.add_argument('--jobs-per-round', type=int, choices=(1, 2), default=2)
    parser.add_argument('--rounds', type=int, choices=range(1, 11), default=2)
    parser.add_argument('--source', type=Path, default=PROJECT / 'src')
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 3600:
        parser.error('--timeout must be between 1 and 3600 seconds')
    source = args.source.resolve(strict=True)
    source.relative_to(ROOT)
    request_path = args.request.resolve(strict=True)
    request_path.relative_to(ROOT)
    raw = request_path.read_bytes()
    request = json.loads(raw)
    digest = hashlib.sha256(raw).hexdigest()
    reference = reference_pcm = None
    if args.reference_result is not None:
        reference_path = args.reference_result.resolve(strict=True)
        reference_path.relative_to(ROOT)
        reference = json.loads(reference_path.read_bytes())
        if (reference['request'] != {'sha256': digest, 'byteCount': len(raw)}
                or reference['audio']['sha256'] != request['audio']['sha256']
                or reference['lineCount'] != len(request['lines'])):
            raise ValueError('Reference result does not bind this exact request')
        if args.nfa:
            reference_pcm = json.loads(reference_path.with_suffix('.pcm.json').read_bytes())
    runtime = 'nemo-speech-3.0.0' if args.nfa else 'espnet-202511-owsm'
    python = PROJECT.parent / 'runtime' / runtime / '.venv/bin/python'
    name = 'nemo_nfa_forced_alignment.py' if args.nfa else 'owsm_ctc_forced_alignment.py'
    sources = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in source.iterdir()
               if path.is_file() and path.suffix in ('.py', '.json')}
    with benchmark_work() as work:
        project = work / 'project'
        overlay = project / 'src'
        helpers = overlay / 'stemwise_engine'
        helpers.mkdir(parents=True)
        for filename in sources:
            shutil.copyfile(source / filename, helpers / filename)
        if not (helpers/'__init__.py').exists():
            (helpers/'__init__.py').write_text('', encoding='utf-8')
        helper = helpers / name
        request_path = work / 'request.json'
        request_path.write_bytes(raw)
        entry = work / 'controlled_worker.py'
        controls = ('import random,runpy,sys\nfrom pathlib import Path\n'
            'import numpy as np\nimport torch\n'
            'random.seed(0);np.random.seed(0);torch.manual_seed(0)\n'
            'torch.use_deterministic_algorithms(True)\n'
            'torch.backends.cudnn.benchmark=False\ntorch.backends.cudnn.deterministic=True\n')
        entry.write_text(controls + 'import hashlib,json\n'
            'sys.argv=sys.argv[1:]\n'
            f'nfa={args.nfa!r}\n'
            'if nfa:\n'
            ' namespace=runpy.run_path(sys.argv[0],run_name="stemwise_nfa_benchmark")\n'
            ' original=namespace["run"].__globals__["_prepare_mono_audio"]\n'
            ' def prepared(source,target):\n'
            '  value=original(source,target)\n'
            '  import soundfile\n'
            '  samples,rate=soundfile.read(target,dtype="float32",always_2d=True)\n'
            '  pcm={"sha256":hashlib.sha256(np.ascontiguousarray(samples,dtype="<f4").tobytes()).hexdigest(),\n'
            '   "frameCount":samples.shape[0],"sampleRate":rate,"channels":samples.shape[1]}\n'
            '  Path(sys.argv[sys.argv.index("--output")+1]).with_suffix(".pcm.json").write_text(json.dumps(pcm))\n'
            '  return value\n'
            ' namespace["run"].__globals__["_prepare_mono_audio"]=prepared\n'
            ' raise SystemExit(namespace["main"]())\n'
            'runpy.run_path(sys.argv[0],run_name="__main__")\n', encoding='utf-8')
        compile(entry.read_text(encoding='utf-8'), str(entry), 'exec')
        environment = {**os.environ, 'PYTHONPATH': str(overlay), 'HF_HUB_OFFLINE': '1',
            'TRANSFORMERS_OFFLINE': '1', 'HF_DATASETS_OFFLINE': '1', 'PYTHONDONTWRITEBYTECODE': '1',
            'CUBLAS_WORKSPACE_CONFIG': ':4096:8'}
        rows, pcm, measurements = [], [], []
        # The outer lease protects the installation; copied current workers use an isolated model lease.
        with heavy_worker_stage({'projectDir': str(PROJECT)}):
            for round_index in range(args.rounds):
                started = time.monotonic()
                with ExitStack() as owners:
                    children = []
                    for index in range(args.jobs_per_round):
                        output = work / f'{round_index}-{index}.json'
                        command = [str(python), '-B', str(entry), str(helper), '--request', str(request_path),
                            '--request-sha256', digest, '--output', str(output), '--timeout-seconds', str(args.timeout)]
                        child = owners.enter_context(owned_process_tree(command, stdout=-1, stderr=-1,
                            text=True, env=environment))
                        children.append((child, output))
                    for child, output in children:
                        _, stderr = child.communicate(timeout=max(1, args.timeout + 60 - (time.monotonic() - started)))
                        if child.returncode or not output.is_file():
                            raise RuntimeError(f'Current worker failed ({child.returncode}): {stderr[-2000:]}')
                        value = json.loads(output.read_bytes())
                        if (value['request'] != {'sha256': digest, 'byteCount': len(raw)}
                                or value['lineCount'] != len(request['lines'])
                                or value['audio']['sha256'] != request['audio']['sha256']):
                            raise ValueError('Current result does not bind the full request')
                        rows.append(value)
                        if args.nfa:
                            pcm.append(json.loads(output.with_suffix('.pcm.json').read_bytes()))
                measurement = {'round': round_index, 'seconds': time.monotonic() - started,
                    'jobs': args.jobs_per_round, 'workerSha256': sources[name], 'requestSha256': digest}
                measurements.append(measurement)
                print(json.dumps(measurement), flush=True)
        raw_identical = all(row == rows[0] for row in rows)
        if args.nfa:
            checked = numerical_results(rows, pcm)
            if reference is not None:
                expected, first = numerical_results([reference, rows[0]], [reference_pcm, pcm[0]])
                if expected != first:
                    raise ValueError('Current numerical result differs from the bound reference')
        else:
            checked = rows
            if reference is not None and reference != rows[0]:
                raise ValueError('Current numerical result differs from the bound reference')
        if any(row != checked[0] for row in checked):
            raise ValueError('Current numerical outputs differ between identical requests')
        if any(hashlib.sha256((source / filename).read_bytes()).hexdigest() != expected
               for filename, expected in sources.items()) or args.request.read_bytes() != raw:
            raise ValueError('Current benchmark inputs changed during measurement')
        print(json.dumps({'scope': 'Current full-song jobs: startup, inference, alignment and retirement; external queue wait excluded',
            'worker': 'NeMo NFA' if args.nfa else 'OWSM', 'workerSha256': sources[name],
            'requestSha256': digest, 'jobsPerRound': args.jobs_per_round, 'rounds': args.rounds,
            'meanSeconds': sum(row['seconds'] for row in measurements) / len(measurements),
            'identicalControlledResults': True, 'identicalRawResults': raw_identical,
            'boundReferenceCompared': reference is not None,
            'comparisonExclusions': ['preprocessing.sha256 (container hash; exact PCM compared)'] if args.nfa else [],
            'lineCountPerJob': len(request['lines']), 'audioDurationMs': request['durationMs'],
            'numericalControls': 'seed 0; deterministic Torch/cuDNN; CUBLAS :4096:8; benchmark only'}))


if __name__ == '__main__':
    main()
