"""Compare three fresh cached decoders with one owned operation-scoped session.

The input fixture binds real audio, a retained request/result, and prepared model
emissions. No model inference is permitted during the measured comparison.
"""
from __future__ import annotations

import argparse
import ast
import contextlib
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT / 'PC side/stemwise-playlist-builder'


def digest_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def project_path(value):
    path = Path(value)
    path = (path if path.is_absolute() else ROOT / path).resolve(strict=True)
    path.relative_to(ROOT)
    return path


def cache_only_overlay(source):
    """Keep numerical functions intact; a missing cache becomes a hard failure."""
    tree = ast.parse(source)
    cached = next(node for node in tree.body
                  if isinstance(node, ast.FunctionDef) and node.name == 'cached_emissions')
    if cached.args.args[4].arg != 'produce':
        raise ValueError('The emission cache producer interface changed')
    guard = "\n_benchmark_original_cached_emissions = cached_emissions\ndef _benchmark_cached_emissions(*args, **kwargs):\n    from stemwise_engine.common import _linux_process_identity\n    def forbidden():\n        raise RuntimeError('Benchmark emission cache unavailable; inference is disabled')\n    arguments = list(args)\n    arguments[4] = forbidden\n    result = _benchmark_original_cached_emissions(*arguments, **kwargs)\n    receipt = {'pid': os.getpid(), 'identity': _linux_process_identity(os.getpid())}\n    Path(arguments[0]).with_suffix('.process.json').write_text(json.dumps(receipt))\n    return result\ncached_emissions = _benchmark_cached_emissions\n\n"
    modified = source + guard
    functions = lambda text: {node.name: ast.dump(node, include_attributes=False)
                              for node in ast.parse(text).body if isinstance(node, ast.FunctionDef)}
    before, after = functions(source), functions(modified)
    if any(after.get(name) != value for name, value in before.items()):
        raise ValueError('Benchmark overlay changed a numerical function')
    return modified


def require_production_cache(fixture: dict, retained: dict, emissions: Path, sources: Path) -> None:
    if digest_file(emissions) != fixture['emissionsSha256']:
        raise ValueError('Benchmark emissionsSha256 input changed')
    cache_key = fixture['emissionCacheKey']
    if not re.fullmatch(r'[0-9a-f]{64}', cache_key) or emissions.name != f'emissions-{cache_key}.pt':
        raise ValueError('Emission cache filename/key differs')
    from stemwise_engine.ctc_worker import helper_source_identity
    from stemwise_engine.lyrics_align_pc import _emission_cache_key
    helper_names = (('mms_forced_alignment.py',) if fixture['worker'] == 'mms' else
                    ('mms_forced_alignment.py', 'wav2vec2_forced_alignment.py'))
    producers = {name: helper_source_identity(sources / name) for name in helper_names}
    if producers != fixture['producerSha256s']:
        raise ValueError('The prepared emissions belong to another numerical producer')
    expected_key = _emission_cache_key(retained['provenance'], producers, fixture['emissionDevice'])
    if cache_key != expected_key:
        raise ValueError('Emission tensor key does not bind the retained audio/model recipe')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', required=True, type=Path,
                        help='Owned functional fixture with audio/request/emission hashes.')
    parser.add_argument('--candidate-src', type=Path, default=PROJECT / 'src')
    parser.add_argument('--work', required=True, type=Path,
                        help='New disposable directory beneath Test suite/work; removed on exit.')
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--repair-segments', action='store_true',
                        help='Measure current guarded segment repair against the same retained emissions.')
    args = parser.parse_args()
    if sys.platform != 'linux':
        parser.error('Run with the project main Python environment in WSL')
    fixture = json.loads(project_path(args.fixture).read_bytes())
    if fixture.get('formatVersion') != 1 or fixture.get('worker') not in {'mms', 'wav2vec2'}:
        raise ValueError('Unprepared or unsupported benchmark fixture')
    audio, request, emissions, bound_result = (
        project_path(fixture[name]) for name in ('audio', 'request', 'emissions', 'sourceCache'))
    for path, key in ((audio, 'audioSha256'), (emissions, 'emissionsSha256')):
        if digest_file(path) != fixture[key]:
            raise ValueError(f'Benchmark {key} input changed')
    request_value = json.loads(request.read_bytes())
    request_sha = hashlib.sha256(json.dumps(request_value, ensure_ascii=False, sort_keys=True,
                                           separators=(',', ':')).encode()).hexdigest()
    retained = json.loads(bound_result.read_bytes())
    if (request_sha != fixture['requestSha256'] or request_sha != retained['requestSha256']
            or retained['provenance']['audio']['sha256'] != fixture['audioSha256']
            or request_value['durationMs'] != fixture['durationMs']
            or retained['durationMs'] != fixture['durationMs']):
        raise ValueError('Fixture request or retained recording binding changed')
    sources = project_path(args.candidate_src)
    require_production_cache(fixture, retained, emissions, sources)
    from stemwise_engine.common import (_linux_process_identity, run_process_tree,
        WorkerRetirementError, exclusive_process_lock, WORKER_DIRECTORY_LOCK, worker_directory_cleanup)
    from stemwise_engine.alignment_worker_session import alignment_worker_session, run_alignment_process
    from stemwise_engine.lyrics_align_pc import _read_alignment_batch_reply
    work = args.work.resolve()
    work.relative_to((ROOT / 'Test suite/work').resolve())
    if args.timeout <= 0:
        raise ValueError('Positive per-request timeout required')
    work.mkdir()
    lease = exclusive_process_lock(work / WORKER_DIRECTORY_LOCK)
    lease.__enter__()
    import stemwise_engine
    previous_path = stemwise_engine.__path__
    helper_modules = tuple('stemwise_engine.' + name for name in
                           ('mms_forced_alignment', 'wav2vec2_forced_alignment'))
    previous_modules = {name: sys.modules.pop(name) for name in helper_modules if name in sys.modules}
    try:
        overlay_root = work / 'src'
        overlay = overlay_root / 'stemwise_engine'
        overlay.mkdir(parents=True)
        (overlay / '__init__.py').write_text(f'__path__.append({str(sources)!r})\n', encoding='utf-8')
        for name in ('common.py', 'runtime_environment.py', 'alignment_worker_session.py',
                     'mms_forced_alignment.py', 'wav2vec2_forced_alignment.py',
                     'ctc_worker.py', 'acoustic_inputs.py', 'worker_protocol.py',
                     'worker_publication.py', 'private_diagnostics.py'):
            shutil.copyfile(sources / name, overlay / name)
        shared = overlay / 'ctc_worker.py'
        shared.write_text(cache_only_overlay(shared.read_text('utf-8')), encoding='utf-8')
        helper = 'stemwise_engine.' + ('mms_forced_alignment' if fixture['worker'] == 'mms'
                                      else 'wav2vec2_forced_alignment')
        stemwise_engine.__path__ = [str(overlay), *previous_path]
        importlib.invalidate_caches()
        extra = ([] if fixture['worker'] == 'mms' else
                 ['--model', str(project_path(fixture['model'])), '--language',
                  fixture.get('modelIdentity', {}).get('phonemizerLanguage') or fixture['language']])
        environment = {**os.environ, 'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1',
                       'PYTHONPATH': os.pathsep.join((str(overlay_root), str(sources))),
                       'HF_HUB_DISABLE_TELEMETRY': '1', 'PYTHONDONTWRITEBYTECODE': '1'}
        reference = None
        measurements = []
        for round_index, mode in enumerate(('cold', 'session', 'session', 'cold')):
            group = work / str(round_index)
            group.mkdir()
            completed_outputs, receipts, repairs = [], [], []
            started = time.monotonic()
            scope = alignment_worker_session() if mode == 'session' else contextlib.nullcontext()
            with scope:
                for index in range(3):
                    call = group / str(index)
                    call.mkdir()
                    current_request, output = call / 'request.json', call / 'output.json'
                    current_request.write_bytes(request.read_bytes())
                    os.link(emissions, call / emissions.name)
                    batch, reply_path = call / 'batch.json', call / 'reply.json'
                    manifest = {'formatVersion': 1, 'timeoutSeconds': args.timeout,
                        'repairSegments': args.repair_segments, 'contexts': [{'contextId': '0',
                            'audio': str(audio), 'audioSha256': fixture['audioSha256'],
                            'request': str(current_request), 'requestSha256': request_sha,
                            'output': str(output), 'emissionCacheKey': fixture['emissionCacheKey']}]}
                    batch.write_text(json.dumps(manifest), encoding='utf-8')
                    command = [sys.executable, '-m', helper,
                        '--batch', str(batch), '--output', str(reply_path),
                        '--project', str(PROJECT), '--device', 'cpu', *extra]
                    run = run_alignment_process if mode == 'session' else run_process_tree
                    completed = run(command, timeout=args.timeout, env=environment,
                                    capture_output=True, text=True, check=False)
                    if completed.returncode != 0 or not output.is_file():
                        sys.stderr.write((completed.stderr or '')[-2000:])
                        raise RuntimeError(f'{mode} cached decoder failed: {completed.returncode}')
                    resolved, recovery = _read_alignment_batch_reply(manifest,
                        json.loads(reply_path.read_bytes()), request_value)
                    completed_outputs.append({'request': resolved, 'result': json.loads(output.read_bytes())})
                    repairs.append(len(recovery['mergeSteps']) if recovery else 0)
                    receipts.append(json.loads(current_request.with_suffix('.process.json').read_bytes()))
            seconds = time.monotonic() - started
            pids = {row['pid'] for row in receipts}
            if len(pids) != (1 if mode == 'session' else 3):
                raise RuntimeError('Measured worker ownership differs from requested mode')
            if any(_linux_process_identity(row['pid']) == tuple(row['identity']) for row in receipts):
                raise RuntimeError('A benchmark numerical process survived operation retirement')
            if reference is None:
                reference = completed_outputs
            elif completed_outputs != reference:
                raise RuntimeError('Complete numerical results differ between worker lifetimes')
            row = {'mode': mode, 'seconds': seconds, 'calls': 3, 'numericalProcesses': len(pids),
                   'allWorkersRetired': True, 'segmentRepairs': sum(repairs)}
            measurements.append(row)
            print(json.dumps(row), flush=True)
        average = lambda mode: sum(row['seconds'] for row in measurements if row['mode'] == mode) / 2
        print(json.dumps({'coldSeconds': average('cold'), 'sessionSeconds': average('session'),
            'speedup': average('cold') / average('session'), 'completeOutputsEqual': True,
            'durationMs': fixture['durationMs'], 'scope': 'Three cached-emission decodes, startup and retirement included; excludes inference and queue wait',
            'repairSegments': args.repair_segments,
            'substitute': 'Identical isolated worker source overlays forbid inference and record process identity; numerical function ASTs unchanged'}))
    finally:
        stemwise_engine.__path__ = previous_path
        for name in helper_modules:
            sys.modules.pop(name, None)
        sys.modules.update(previous_modules)
        importlib.invalidate_caches()
        lease.__exit__(None, None, None)
        if not isinstance(sys.exc_info()[1], WorkerRetirementError):
            with worker_directory_cleanup(work):
                shutil.rmtree(work)


if __name__ == '__main__':
    main()
