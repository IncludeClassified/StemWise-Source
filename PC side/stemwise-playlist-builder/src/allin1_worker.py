"""Pinned All-In-One model execution; the parent owns process retirement/results."""
from __future__ import annotations
import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import signal
import sys
import time
from typing import Any, TypedDict
from stemwise_engine.private_diagnostics import retain_allin1_failure


def publish_active(work, request_id, index, used_seconds):
    destination = work/'batch-active.json'
    temporary = destination.with_suffix('.tmp')
    temporary.write_text(json.dumps({'requestId': request_id, 'index': index,
        'usedSeconds': used_seconds}), encoding='utf-8')
    os.replace(temporary, destination)


class BatchInput(TypedDict):
    index: int
    source: str
    audio: str
    durationMs: int


class BatchRequest(TypedDict):
    requestId: str
    timeoutSeconds: int
    inputs: list[BatchInput]
    setupSeconds: float
    batchStartedMonotonic: float
    spentSeconds: dict[str, float]


@dataclass
class BatchState:
    request: BatchRequest
    work: Path
    limit: float
    items: dict[str, BatchInput]
    spent: dict[str, float]
    started: float
    todo: list[Path]


def prepare_models(revision: str, expected: dict[str, str], demucs_revision: str, demucs_expected: dict[str, str]):
    import dataclasses, hashlib, json, os, sys, importlib.metadata
    from pathlib import Path
    from huggingface_hub import hf_hub_download as hub_download
    import allin1
    import allin1.models.loaders as loaders

    def verify(path, expected_hash, label):
        digest = hashlib.sha256()
        with open(path, 'rb') as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b''):
                digest.update(chunk)
        actual = digest.hexdigest()
        if actual != expected_hash:
            raise RuntimeError(label + ' identity mismatch')
    for filename, expected_hash in expected.items():
        path = hub_download(repo_id='taejunkim/allinone', filename=filename, revision=revision)
        verify(path, expected_hash, 'All-In-One ' + filename)
    demucs_repo = None
    for filename, expected_hash in demucs_expected.items():
        path = hub_download(repo_id='adefossez/HTDemucs', filename=filename, revision=demucs_revision)
        verify(path, expected_hash, 'HTDemucs ' + filename)
        demucs_repo = Path(path).parents[2]
    if demucs_repo is None:
        raise RuntimeError('No HTDemucs model identities were configured')
    ref = demucs_repo / 'refs' / 'main'
    ref.parent.mkdir(parents=True, exist_ok=True)
    ref.write_text(demucs_revision + '\n', encoding='utf-8')
    os.environ['HF_HUB_OFFLINE'] = '1'
    original_download = loaders.hf_hub_download

    def pinned_download(*args, **kwargs):
        kwargs['revision'] = revision
        path = original_download(*args, **kwargs)
        filename = str(kwargs.get('filename') or (args[1] if len(args) > 1 else ''))
        if filename not in expected:
            raise RuntimeError('Unexpected All-In-One model file: ' + filename)
        verify(path, expected[filename], 'All-In-One ' + filename)
        return path
    loaders.hf_hub_download = pinned_download
    return allin1

def run_single(audio_path: str, allin1) -> None:
    import importlib.metadata
    r = allin1.analyze(audio_path, model='harmonix-all', device='cpu', keep_byproducts=False, multiprocess=False)

    def get(o, n, d=None):
        return getattr(o, n, d)
    payload = {'version': importlib.metadata.version('allin1'), 'bpm': get(r, 'bpm'), 'beats': list(get(r, 'beats', []) or []), 'downbeats': list(get(r, 'downbeats', []) or []), 'segments': [{'start': get(x, 'start'), 'end': get(x, 'end'), 'label': get(x, 'label')} for x in get(r, 'segments', []) or []]}
    print('STEMWISE_RESULT=' + json.dumps(payload, separators=(',', ':')))

def run_batch(request_path: Path, batch_started: float, allin1) -> None:
    import importlib, math, signal, subprocess, time
    from allin1.typings import AnalysisResult
    signal.setitimer(signal.ITIMER_REAL, 0)
    implementation = importlib.import_module('allin1.analyze')
    request = json.loads(request_path.read_text(encoding='utf-8'))
    work = request_path.parent
    limit = request['timeoutSeconds']
    items = {str(Path(item['audio']).resolve()): item for item in request['inputs']}
    spent = {path: float(request.get('spentSeconds', {}).get(str(item['index']), 0.)) for path, item in items.items()}
    inherited = sum(spent.values())
    started = batch_started
    state = BatchState(request, work, limit, items, spent, started, [])

    def publish(path, *, result=None, error=None):
        item = state.items[str(Path(path).resolve())]
        row = {'requestId': state.request['requestId'], 'index': item['index'], 'source': item['source'], 'durationMs': item['durationMs']}
        if error is None:
            if Path(result.path).resolve() != Path(path).resolve():
                raise RuntimeError('All-In-One changed result input identity')
            row['result'] = {'version': importlib.metadata.version('allin1'), 'bpm': result.bpm, 'beats': list(result.beats), 'downbeats': list(result.downbeats), 'segments': [{'start': segment.start, 'end': segment.end, 'label': segment.label} for segment in result.segments]}
        else:
            row['error'] = type(error).__name__
            retain_allin1_failure(error, state.work, item)
        destination = state.work / ('result-' + str(item['index']) + '.json')
        temporary = destination.with_suffix('.tmp')
        temporary.write_text(json.dumps(row, separators=(',', ':')), encoding='utf-8')
        os.replace(temporary, destination)
    signal.signal(signal.SIGALRM, signal.SIG_DFL)

    def remaining(path):
        setup = max(0.0, time.monotonic() - state.started - sum(state.spent.values()) + inherited)
        return state.limit - state.spent[str(Path(path).resolve())] - setup

    def bounded(path, call):
        budget = remaining(path)
        if budget <= 0:
            raise TimeoutError('All-In-One per-song deadline expired')
        publish_active(state.work, state.request['requestId'], state.items[str(Path(path).resolve())]['index'], {
            str(item['index']): max(0., state.limit-remaining(audio)) for audio, item in state.items.items()})
        signal.setitimer(signal.ITIMER_REAL, budget)
        began = time.monotonic()
        try:
            return call()
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            state.spent[str(Path(path).resolve())] += time.monotonic() - began

    def demix(paths, demix_dir, device):
        state.todo = paths
        state.request['setupSeconds'] = time.monotonic() - state.started
        state.request['batchStartedMonotonic'] = state.started
        state.request['spentSeconds'] = {str(item['index']): state.spent[audio] for audio, item in state.items.items()}
        def read_status(path):
            item = state.items[str(path.resolve())]
            row = json.loads((state.work/f'demix-{item["index"]}.json').read_text(encoding='utf-8'))
            if (any(row.get(name) != value for name, value in {'requestId': state.request['requestId'], **item}.items())
                    or type(row.get('ok')) is not bool or type(row.get('seconds')) not in (int, float)
                    or not math.isfinite(row['seconds']) or row['seconds'] < 0):
                raise ValueError('Malformed All-In-One demix status')
            return row
        def complete(path):
            destination = demix_dir/'htdemucs'/path.stem
            return all((destination/(stem+'.wav')).is_file() for stem in ('bass', 'drums', 'other', 'vocals'))
        reused = set()
        for path in paths:
            try:
                if read_status(path)['ok'] and complete(path):
                    reused.add(str(path.resolve()))
            except (OSError, ValueError, TypeError):
                pass
        pending = [state.items[str(path.resolve())] for path in paths if str(path.resolve()) not in reused]
        if pending:
            demix_request = state.work/'demix-request.json'
            demix_request.write_text(json.dumps({**state.request, 'inputs': pending}), encoding='utf-8')
            completed = subprocess.run([sys.executable, '-B', '-m', 'stemwise_engine.allin1_demix_worker', str(demix_request), str(demix_dir)], check=False)
            if completed.returncode == -signal.SIGALRM:
                # Retire this entire owned tree before the parent restarts independent inputs.
                os.kill(os.getpid(), signal.SIGALRM)
        good = []
        outputs = []
        for path in list(paths):
            item = state.items[str(path.resolve())]
            try:
                row = read_status(path)
                if str(path.resolve()) not in reused:
                    state.spent[str(path.resolve())] += row['seconds']
            except Exception:
                row = {'ok': False, 'error': 'RuntimeError'}
            destination = demix_dir / 'htdemucs' / path.stem
            if row['ok'] and complete(path):
                good.append(path)
                outputs.append(destination)
            else:
                publish(path, error=TimeoutError('All-In-One demix deadline expired') if row.get('error') == 'TimeoutError' else RuntimeError('All-In-One demix failed'))
        paths[:] = good
        if not good:
            raise RuntimeError('All-In-One batch has no demixed inputs')
        return outputs
    original_extract = implementation.extract_spectrograms

    def extract(demix_paths, spec_dir, multiprocess):
        outputs = []
        good = []
        for path, demixed in zip(list(state.todo), demix_paths):
            try:
                output = bounded(path, lambda: original_extract([demixed], spec_dir, multiprocess=False)[0])
                outputs.append(output)
                good.append(path)
            except Exception as error:
                publish(path, error=error)
        state.todo[:] = good
        return outputs
    original_inference = implementation.run_inference

    def inference(*args, **kwargs):
        path = kwargs['path']
        try:
            result = bounded(path, lambda: original_inference(*args, **kwargs))
            publish(path, result=result)
            return result
        except Exception as error:
            publish(path, error=error)
            return AnalysisResult(path=path, bpm=0, beats=[], downbeats=[], beat_positions=[], segments=[])
    original_load = implementation.load_pretrained_model

    def load(*args, **kwargs):
        if not state.todo:
            raise RuntimeError('All-In-One batch has no prepared inputs')
        budget = max((remaining(path) for path in state.todo))
        if budget <= 0:
            raise TimeoutError('All-In-One model setup deadline expired')
        signal.setitimer(signal.ITIMER_REAL, budget)
        try:
            return original_load(*args, **kwargs)
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
    implementation.demix = demix
    implementation.extract_spectrograms = extract
    implementation.run_inference = inference
    implementation.load_pretrained_model = load
    allin1.analyze([Path(item['audio']) for item in state.request['inputs']], model='harmonix-all', device='cpu', keep_byproducts=False, multiprocess=False, demix_dir=state.work / 'demix', spec_dir=state.work / 'spec')

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('single', 'batch'))
    parser.add_argument('--timeout-seconds', type=int, required=True)
    parser.add_argument('input', type=Path)
    args = parser.parse_args(argv)
    if args.timeout_seconds <= 0:
        parser.error('--timeout-seconds must be positive')
    signal.signal(signal.SIGALRM, signal.SIG_DFL)
    budget = args.timeout_seconds
    if args.mode == 'batch':
        request = json.loads(args.input.read_text(encoding='utf-8'))
        used = request.get('spentSeconds', {})
        budget -= min(float(used.get(str(item['index']), 0.)) for item in request['inputs'])
    if budget <= 0:
        raise TimeoutError('All-In-One setup has no remaining song budget')
    signal.setitimer(signal.ITIMER_REAL, budget)
    started = time.monotonic()
    try:
        from stemwise_engine.model_registry import model_assets
        pins = model_assets('analyzers')
        allin1 = prepare_models(pins['ALLIN1_MODEL_REVISION'], pins['ALLIN1_MODEL_SHA256'],
                                pins['HTDEMUCS_MODEL_REVISION'], pins['HTDEMUCS_MODEL_SHA256'])
        if args.mode == 'single':
            run_single(str(args.input), allin1)
        else:
            run_batch(args.input, started, allin1)
        return 0
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)


if __name__ == '__main__':
    raise SystemExit(main())
