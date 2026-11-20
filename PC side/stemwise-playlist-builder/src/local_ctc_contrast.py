"""Experimental local CTC comparisons; no lyric or timing admission decision."""
import argparse
from contextlib import redirect_stdout
from hashlib import sha256
import json
import os
from pathlib import Path
import sys
import traceback

import numpy as np

from stemwise_engine.common import (WorkerTemporaryDirectory, atomic_write_json, heavy_worker_stage,
    json_bytes, read_bounded_json, run_process_tree, sha256_file)
from stemwise_engine.endpoint_ctc_evidence import _ctc_sequence_scores, _validated_ctc_emissions
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.worker_protocol import (fatal_model_failure, parse_worker_json,
    require_worker_success, run_worker_main)

KIND = 'experimental-local-ctc-contrast'
ROLES = {'candidate', 'positive-control', 'negative-control'}


def producer_identity():
    directory = Path(__file__).parent
    return {name: semantic_source_identity(directory/name, portable=True) for name in (
        'local_ctc_contrast.py', 'endpoint_mms_prediction.py', 'mms_forced_alignment.py',
        'endpoint_ctc_evidence.py', 'ctc_numerics.py', 'ctc_worker.py', 'acoustic_inputs.py',
        'worker_runtime_probe.py', 'model_registry.py', 'model-route-registry.json')}


def compare(logp, alternatives, *, blank):
    """Report complete-path and reverse-order controls without picking a winner."""
    if (not isinstance(alternatives, list) or len(alternatives) < 2
            or any(not isinstance(row, dict) or set(row) != {'id', 'text', 'role', 'tokens'}
                or not isinstance(row['id'], str) or not row['id']
                or not isinstance(row['text'], str) or not row['text'].strip()
                or row['role'] not in ROLES for row in alternatives)
            or len({row['id'] for row in alternatives}) != len(alternatives)):
        raise ValueError('Local comparison alternatives are invalid')
    targets = [row['tokens'] for row in alternatives]
    scores = _ctc_sequence_scores(logp, [*targets, *(list(reversed(t)) for t in targets)],
        blank, allow_edge_audio=True)
    if not np.isfinite(scores).all():
        raise ValueError('Local comparison has no finite complete target path')
    count = len(alternatives)
    return {'kind': KIND, 'decision': 'unclear', 'contentAuthority': False,
        'timingAuthority': False, 'publicationAuthority': False,
        'alternatives': [{**row, 'logLikelihood': scores[i], 'reverseLogLikelihood': scores[count+i],
            'orderContrast': scores[i]-scores[count+i]} for i, row in enumerate(alternatives)]}


def _runtime_identity():
    import torch
    from stemwise_engine import mms_forced_alignment as worker
    from stemwise_engine.model_registry import runtime_profile
    from stemwise_engine.worker_runtime_probe import torch_runtime_identity
    pins = runtime_profile('main')
    actual = worker.runtime_info('cuda')
    if (actual['python'] != pins['python']
            or any(actual[key] != pins['packages'][key] for key in ('torch', 'torchaudio', 'demucs', 'uroman'))
            or torch.cuda.current_device() != 0):
        raise ValueError('Local MMS comparison runtime or selected device differs')
    return {'mms': actual, 'torchProducer': torch_runtime_identity(), 'deviceIndex': 0}


def _audio_identity(path):
    import soundfile as sf
    info = sf.info(path)
    if info.format not in {'WAV', 'WAVEX', 'RF64'} or info.channels not in (1, 2) or info.frames <= 0:
        raise ValueError('Local comparison requires retained Original PCM')
    return {'sha256': sha256_file(path), 'byteCount': path.stat().st_size,
        'sampleRate': info.samplerate, 'channels': info.channels, 'frames': info.frames}


def _cases(cases, duration_ms):
    if not isinstance(cases, list) or not cases:
        raise ValueError('Local comparison needs declared cases')
    identifiers = set()
    for case in cases:
        if (not isinstance(case, dict) or set(case) != {'id', 'startMs', 'endMs', 'alternatives'}
                or not isinstance(case['id'], str) or not case['id'] or case['id'] in identifiers
                or any(type(case[key]) is not int for key in ('startMs', 'endMs'))
                or not 0 <= case['startMs'] < case['endMs'] <= duration_ms
                or not isinstance(case['alternatives'], list) or len(case['alternatives']) < 2):
            raise ValueError('Local comparison crop or identity is invalid')
        identifiers.add(case['id'])
        rows = case['alternatives']
        if (any(not isinstance(row, dict) or set(row) != {'id', 'text', 'role'}
                or not isinstance(row['id'], str) or not row['id']
                or not isinstance(row['text'], str) or not row['text'].strip()
                or row['role'] not in ROLES for row in rows)
                or len({row['id'] for row in rows}) != len(rows)):
            raise ValueError('Local comparison text alternatives are invalid')
    return cases


def _tokens(rows, vocab):
    from stemwise_engine.mms_forced_alignment import _romanized_words
    result = []
    for row in rows:
        text = ''.join(_romanized_words(row['text']))
        if not text or any(char not in vocab or vocab[char] == 0 for char in text):
            raise ValueError('Local comparison cannot represent the complete alternative')
        result.append({**row, 'tokens': [vocab[char] for char in text]})
    return result


def _emission_cache(path, binding, produce):
    """One atomic target-independent table, with the producer's exact time edges."""
    try:
        with np.load(path, allow_pickle=False) as archive:
            if set(archive.files) != {'binding', 'logp', 'edges'} or archive['binding'].tobytes() != json_bytes(binding):
                raise ValueError('Local emission cache binding changed')
            logp, edges = archive['logp'], archive['edges']
            _validate_emissions(logp, edges, binding)
            return logp, edges, True
    except (OSError, ValueError, KeyError, EOFError):
        pass
    logp, edges = produce()
    _validate_emissions(logp, edges, binding)
    return logp, edges, False


def _validate_emissions(logp, edges, binding):
    _validated_ctc_emissions(logp, 0)
    if (logp.shape[1] != len(binding['vocabulary']) or edges.shape != (len(logp)+1,)
            or not np.isfinite(edges).all() or np.any(np.diff(edges) <= 0)
            or edges[0] != binding['crop']['startMs'] or edges[-1] != binding['crop']['endMs']):
        raise ValueError('Local emission geometry changed')


def _publish_emissions(path, binding, logp, edges):
    # Temporary ownership includes the write and atomic replacement.
    with WorkerTemporaryDirectory(prefix='local-emissions-', dir=path.parent) as directory:
        temporary = Path(directory)/'emissions.npz'
        np.savez(temporary, binding=np.frombuffer(json_bytes(binding), dtype=np.uint8), logp=logp, edges=edges)
        os.replace(temporary, path)


def run_request(path, expected_sha):
    from stemwise_engine import endpoint_mms_prediction as mms
    from stemwise_engine.acoustic_inputs import StreamedMonoAudio
    from stemwise_engine.ctc_worker import execution_deadline, retire_model
    import torchaudio
    if sha256_file(path) != expected_sha:
        raise ValueError('Local comparison request changed')
    request = read_bounded_json(path, max_bytes=16*1024*1024, root=path.parent)
    if (set(request) != {'project', 'audio', 'audioIdentity', 'producer', 'runtime', 'cases', 'timeoutSeconds', 'cacheDir'}
            or type(request['timeoutSeconds']) is not int or request['timeoutSeconds'] <= 0
            or request['producer'] != producer_identity()):
        raise ValueError('Local comparison request or producer is invalid')
    project, audio, cache_dir = (Path(request[key]) for key in ('project', 'audio', 'cacheDir'))
    if not all(p.is_absolute() for p in (project, audio, cache_dir)):
        raise ValueError('Local comparison paths must be absolute')
    if _audio_identity(audio) != request['audioIdentity'] or _runtime_identity() != request['runtime']:
        raise ValueError('Local comparison input or runtime changed')
    identity = request['audioIdentity']
    cases = _cases(request['cases'], identity['frames']*1000//identity['sampleRate'])
    checkpoint = project.parent/'model-cache/torch/hub/checkpoints/model.pt'
    if sha256_file(checkpoint) != mms.MODEL['modelSha256']:
        raise ValueError('Local MMS checkpoint changed')
    vocab = torchaudio.pipelines.MMS_FA.get_dict(star=None)
    targets = [_tokens(case['alternatives'], vocab) for case in cases]
    reader = StreamedMonoAudio(audio, mms.RATE)
    state, model, results, pending = {}, None, [], []
    try:
        with execution_deadline(request['timeoutSeconds']):
            for case, alternatives in zip(cases, targets):
                binding = {'audio': identity, 'crop': {key: case[key] for key in ('startMs', 'endMs')},
                    'representation': reader.representation, 'model': mms.MODEL, 'runtime': request['runtime'],
                    'producer': request['producer'], 'vocabulary': vocab, 'sampleRate': mms.RATE}
                cache = cache_dir/('local-emissions-'+sha256(json_bytes(binding)).hexdigest()+'.npz')
                def produce():
                    nonlocal model
                    if model is None:
                        # Set ownership before loading so failed CUDA construction still retires.
                        state['cuda_model'] = True
                        model = mms.load_model(project)
                        state['model'] = model
                        if model.vocab != vocab:
                            raise ValueError('Local MMS vocabulary changed')
                    signal = reader.read(case['startMs']*16, case['endMs']*16)[0].numpy()
                    return mms.prediction_emissions(model, signal, case['startMs'])
                logp, edges, reused = _emission_cache(cache, binding, produce)
                result = compare(logp, alternatives, blank=0)
                results.append({'case': case, 'emissionsSha256': sha256(logp.tobytes()+edges.tobytes()).hexdigest(),
                    'cachePath': str(cache), 'cacheReused': reused, 'binding': binding, 'result': result})
                if not reused:
                    pending.append((cache, binding, logp, edges))
    except BaseException as error:
        traceback.clear_frames(error.__traceback__)
        raise
    finally:
        model = None
        if state:
            try:
                with execution_deadline(30):
                    retire_model(state)
            except BaseException:
                fatal_model_failure('local-ctc-model-retirement-failed')
    if (_audio_identity(audio) != identity or _runtime_identity() != request['runtime']
            or producer_identity() != request['producer'] or sha256_file(path) != expected_sha
            or sha256_file(checkpoint) != mms.MODEL['modelSha256']):
        raise ValueError('Local comparison inputs changed before publication')
    for cache, binding, logp, edges in pending:
        _publish_emissions(cache, binding, logp, edges)
    return {'kind': KIND, 'requestSha256': expected_sha, 'decision': 'unclear',
        'contentAuthority': False, 'timingAuthority': False, 'publicationAuthority': False, 'cases': results}


def run_experiment(*, project, audio, cases, directory, timeout_seconds):
    """Own the main environment, heavy lease, supervised child and publication."""
    project, audio, directory = Path(project).resolve(), Path(audio).resolve(strict=True), Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    identity = _audio_identity(audio)
    _cases(cases, identity['frames']*1000//identity['sampleRate'])
    if type(timeout_seconds) is not int or timeout_seconds <= 0:
        raise ValueError('Local comparison needs a positive execution deadline')
    environment = {**os.environ, 'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1'}
    command = [str(environment_python('main', project)), '-B', '-m', 'stemwise_engine.local_ctc_contrast']
    with environment_lease('main', builder=project), heavy_worker_stage({'projectDir': str(project)}):
        probe = run_process_tree([*command, '--runtime-info'], capture_output=True, text=True,
            check=False, timeout=timeout_seconds, env=environment, cwd=project)
        require_worker_success(probe, code='local-ctc-runtime-failed', diagnostic_path=directory/'runtime-error.private.txt')
        request = {'project': str(project), 'audio': str(audio), 'audioIdentity': identity,
            'producer': producer_identity(), 'runtime': parse_worker_json(probe.stdout),
            'cases': cases, 'timeoutSeconds': timeout_seconds, 'cacheDir': str(directory)}
        with WorkerTemporaryDirectory(prefix='local-contrast-', dir=directory) as temporary:
            path = Path(temporary)/'request.json'
            atomic_write_json(path, request)
            digest = sha256_file(path)
            completed = run_process_tree([*command, '--request', str(path), '--request-sha256', digest],
                capture_output=True, text=True, check=False, timeout=timeout_seconds+30, env=environment, cwd=project)
            require_worker_success(completed, code='local-ctc-worker-failed', diagnostic_path=directory/'worker-error.private.txt')
            result = parse_worker_json(completed.stdout)
            if (result.get('requestSha256') != digest or result.get('kind') != KIND
                    or result.get('decision') != 'unclear' or any(result.get(key) is not False for key in
                        ('contentAuthority', 'timingAuthority', 'publicationAuthority'))
                    or [row['case'] for row in result['cases']] != cases
                    or _audio_identity(audio) != identity or producer_identity() != request['producer']):
                raise ValueError('Local comparison output or current input changed')
            atomic_write_json(directory/'comparison.private.json', result)
            return read_bounded_json(directory/'comparison.private.json', max_bytes=16*1024*1024, root=directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--runtime-info', action='store_true')
    mode.add_argument('--request', type=Path)
    parser.add_argument('--request-sha256')
    args = parser.parse_args()
    with redirect_stdout(sys.stderr):
        result = _runtime_identity() if args.runtime_info else run_request(args.request, args.request_sha256)
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))


if __name__ == '__main__':
    raise SystemExit(run_worker_main(main))
