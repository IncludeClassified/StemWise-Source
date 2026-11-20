"""Coalesce only already-requested native emissions; each caller owns CPU decoding."""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

from stemwise_engine.common import (WorkerRetirementError, atomic_write_json, heavy_worker_lock_path, heavy_worker_stage,
                    process_lock_descriptor, read_bounded_json,
                    run_process_tree, sha256_file, validate_generated_path)
import stemwise_engine.analysis_queue as analysis_queue


def _canonical_sha256(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def prepare_emissions(*, audio: Path, duration_ms: int, config: dict, timeout_seconds: int,
                      python: Path, helper: Path, device: str, cache_key: str, cache_path: Path,
                      producer: dict, model: Path | None = None, language: str | None = None,
                      env: dict | None = None, diagnostic_path: Path | None = None) -> None:
    """Prepare a missing cache; existing-cache numerical validation stays in the worker."""
    project = Path(config['projectDir']).resolve()
    cache_root = validate_generated_path(project.parent.parent, Path(config['tempDir']))
    cache_path = validate_generated_path(cache_root, cache_path, workspace_root=project.parent.parent)
    families = {'mms_forced_alignment.py': 'mms', 'wav2vec2_forced_alignment.py': 'wav2vec2'}
    if (helper.name not in families or not isinstance(cache_key, str)
            or re.fullmatch(r'[0-9a-f]{64}', cache_key) is None
            or cache_path.name != f'emissions-{cache_key}.pt'
            or device not in {'cpu', 'cuda', 'auto'}
            or (families[helper.name] == 'wav2vec2') != (model is not None and isinstance(language, str) and bool(language))
            or not isinstance(producer, dict)
            or set(producer) & {'audio', 'durationMs', 'audioViewId', 'audioFamilyId',
                                'videoId', 'recordingKey', 'mappingIdentity', 'requestSha256'}):
        raise ValueError('Native emission preparation binding is malformed')
    if cache_path.is_file():
        return
    environment = os.environ.copy() if env is None else dict(env)
    sources = {'worker': sha256_file(helper),
               'sharedWorker': sha256_file(helper.with_name('ctc_worker.py')),
               'acousticInputs': sha256_file(helper.with_name('acoustic_inputs.py')),
               'protocol': sha256_file(helper.with_name('worker_protocol.py')),
               'publication': sha256_file(helper.with_name('worker_publication.py'))}
    compatibility = _canonical_sha256({
        'family': families[helper.name], 'python': str(python.absolute()),
        'resolvedPython': str(python.resolve()), 'worker': str(helper.resolve()), 'sources': sources,
        'device': device, 'model': str(model.resolve()) if model is not None else None,
        'language': language, 'producer': producer,
        'environment': {name: environment.get(name) for name in (
            'HF_HOME', 'HF_HUB_CACHE', 'HUGGINGFACE_HUB_CACHE', 'TRANSFORMERS_CACHE',
            'TORCH_HOME', 'HF_HUB_OFFLINE', 'TRANSFORMERS_OFFLINE', 'HF_DATASETS_OFFLINE',
            'CUDA_VISIBLE_DEVICES', 'OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS')},
    })

    def backend(contexts, selected_project, timeout):
        directory = Path(contexts[0]['cacheOutput']).parent
        directory.mkdir(parents=True, exist_ok=True)
        manifest = {'formatVersion': 1, 'contexts': contexts, 'timeoutSeconds': timeout}
        request, output = directory / 'prepare.request.json', directory / 'prepare.output.json'
        atomic_write_json(request, manifest)
        output.unlink(missing_ok=True)
        command = [str(python), "-m", "stemwise_engine." + helper.stem, '--prepare-batch', str(request), '--output', str(output),
                   '--project', str(selected_project), '--device', device]
        if model is not None:
            command.extend(('--model', str(model), '--language', str(language)))
        # Queue acquisition has already completed; this deadline covers actual work and retirement.
        try:
            failure = None
            completed = None
            try:
                completed = run_process_tree(command, capture_output=True, text=True, check=False,
                                             timeout=timeout * len(contexts) + 30, env=environment)
                if completed.returncode != 0:
                    failure = subprocess.CalledProcessError(completed.returncode, command, output=completed.stdout, stderr=completed.stderr)
                    if completed.returncode == 70:
                        fatal = WorkerRetirementError('Native emission workers could not be retired')
                        fatal.returncode, fatal.stderr = completed.returncode, completed.stderr
                        raise fatal from failure
            except subprocess.TimeoutExpired as error:
                failure = error
            if not output.is_file():
                raise failure or RuntimeError('Native emission preparation worker returned no result')
            try:
                reply = read_bounded_json(output, max_bytes=16384, root=directory)
                if (not isinstance(reply, dict) or set(reply) != {'formatVersion', 'batchSha256', 'contexts'}
                        or type(reply['formatVersion']) is not int or reply['formatVersion'] != 1
                        or reply['batchSha256'] != _canonical_sha256(manifest)
                        or not isinstance(reply['contexts'], list) or len(reply['contexts']) > len(contexts)
                        or failure is None and len(reply['contexts']) != len(contexts)):
                    raise ValueError('Native emission preparation reply is incomplete or stale')
                results = []
                for context, row in zip(contexts, reply['contexts']):
                    if (not isinstance(row, dict) or set(row) != {'contextId', 'audioSha256', 'durationMs',
                            'emissionCacheKey', 'cacheSha256', 'cacheByteCount'}
                            or any(row.get(key) != context[key] for key in
                                   ('contextId', 'audioSha256', 'durationMs', 'emissionCacheKey'))
                            or type(row['cacheByteCount']) is not int or row['cacheByteCount'] <= 0
                            or not isinstance(row['cacheSha256'], str) or re.fullmatch(r'[0-9a-f]{64}', row['cacheSha256']) is None):
                        raise ValueError('Native emission preparation context differs from its request')
                    artifact = Path(context['cacheOutput'])
                    try:
                        intact = artifact.stat().st_size == row['cacheByteCount'] and sha256_file(artifact) == row['cacheSha256']
                    except OSError:
                        intact = False
                    if not intact:
                        error = ValueError('Native emission preparation artifact is missing or stale')
                        error.stderr = completed.stderr if completed is not None else getattr(failure, 'stderr', None)
                        results.append(error)
                        continue
                    results.append({'durationMs': row['durationMs'], 'cacheKey': row['emissionCacheKey'],
                                    'cachePath': str(artifact), 'cacheSha256': row['cacheSha256'],
                                    'cacheByteCount': row['cacheByteCount']})
                results.extend([failure] * (len(contexts) - len(results)))
                return results
            except Exception as error:
                error.stderr = completed.stderr if completed is not None else getattr(failure, 'stderr', None)
                raise
        finally:
            analysis_queue.dispose_scratch(request)
            analysis_queue.dispose_scratch(output)

    if process_lock_descriptor(heavy_worker_lock_path(config)) is not None:
        # The caller's numerical worker can prepare and decode under this same lease.
        with heavy_worker_stage(config):
            return
    preparation = {'mode': 'alignment-emissions', 'family': families[helper.name],
                   'compatibilitySha256': compatibility, 'cacheKey': cache_key,
                   'cacheRoot': str(cache_root), 'cacheOutput': str(cache_path)}
    if diagnostic_path is not None:
        preparation['diagnosticPath'] = str(diagnostic_path)
    analysis_queue.analyze(audio, duration_ms, project, timeout_seconds, backend, preparation=preparation)
