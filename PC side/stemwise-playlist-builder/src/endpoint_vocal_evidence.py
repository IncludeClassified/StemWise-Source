"""Shared vocal separation, exact retained PCM reuse and sample loading."""
from __future__ import annotations
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.runtime_environment import environment_python, environment_lease

import hashlib
import json
from pathlib import Path
from stemwise_engine.worker_protocol import require_worker_success
import sys

import numpy as np
from stemwise_engine.common import source_duration_ms

from stemwise_engine.common import (heavy_worker_stage, atomic_write_bytes, json_bytes, read_bounded_json,
                    sha256_file, validate_generated_path, run_process_tree, load_config)

ROUTES = ("htdemucs-vocals", "kimberley-jensen-roformer-vocals", "sucial-v2-roformer-dry-vocals")
RATE = 16000


class NoAcousticEvidence(ValueError):
    """Audio has no finite, varying signal suitable for separation."""


def normalize_demucs_input(wav):
    import torch
    from stemwise_engine.acoustic_inputs import mono_tensor
    if wav.ndim != 2 or wav.shape[0] not in (1, 2) or not torch.isfinite(wav).all():
        raise NoAcousticEvidence('vocal-input-invalid')
    try:
        reference = mono_tensor(wav)[0]
    except ValueError as error:
        raise NoAcousticEvidence('vocal-input-uninformative') from error
    offset, scale = reference.mean(), reference.std()
    if not torch.isfinite(scale) or scale <= 1e-8:
        raise NoAcousticEvidence('vocal-input-uninformative')
    return (wav-offset)/scale, offset, scale


def _identity(path: Path) -> dict:
    return {"sha256": sha256_file(path), "byteCount": path.stat().st_size}


def _separate(config: dict, clip: Path, scratch: Path) -> dict[str, tuple[Path, dict]]:
    return _separate_many(config, [(clip, scratch)])[0]


def _separate_many(config: dict, pairs: list[tuple[Path, Path]]) -> list[dict]:
    import soundfile as sf
    project = Path(config['projectDir'])
    timeout = int(config['analysisTimeoutMinutes']) * 60
    for _, scratch in pairs:
        validate_generated_path(scratch, scratch / 'vocal-worker.json').unlink(missing_ok=True)
    from stemwise_engine.alignment_models import roformer_offline_env
    with heavy_worker_stage(config), environment_lease('main', builder=project):
        result = run_process_tree([str(environment_python('main', project)), '-m', 'stemwise_engine.endpoint_vocal_evidence',
            '--separate-many', str(project), str(timeout)], input=json.dumps([[str(c), str(s)] for c, s in pairs]),
            capture_output=True, text=True, timeout=timeout * len(pairs), check=False,
            env=roformer_offline_env(project.parent))
    require_worker_success(result, code='vocal-worker-failed',
        diagnostic_path=pairs[0][1].parent/'vocal-worker.failure.private.txt')
    outputs = []
    for clip, scratch in pairs:
        records = read_bounded_json(scratch / 'vocal-worker.json', max_bytes=1024*1024, root=scratch)
        if not isinstance(records, dict) or set(records) != set(ROUTES):
            raise ValueError('invalid vocal worker result')
        info = sf.info(clip)
        duration = source_duration_ms(info.frames, info.samplerate)
        views = {}
        for route, record in records.items():
            path = validate_generated_path(scratch, scratch / record['path'])
            _vocal_samples(path, record['provenance'], duration,
                           sample_rate=record['provenance']['stream']['sampleRate'])
            views[route] = (path, record['provenance'])
        outputs.append(views)
    return outputs


def _separate_many_local(config: dict, pairs: list[tuple[Path, Path]], timeout_seconds: int) -> list[dict]:
    """One pinned separator at a time, with independently recoverable contexts."""
    if (type(timeout_seconds) is not int or timeout_seconds <= 0 or not pairs
            or len({s.resolve() for _, s in pairs}) != len(pairs)):
        raise ValueError('Vocal batch requires distinct contexts and a positive deadline')
    import gc
    import signal
    import time
    import torch
    from demucs import separate as demucs
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace
    from stemwise_engine.alignment_models import PINNED_DEMUCS_SHA256
    from stemwise_engine.alignment_audio import audio_stream, read_audio_provenance, write_audio_provenance, write_roformer_audio_provenance
    from stemwise_engine.alignment_models import require_pinned_file, roformer_assets
    import stemwise_engine.roformer_separation as roformer
    from stemwise_engine.worker_runtime_probe import torch_runtime_identity
    producer_runtime = torch_runtime_identity()
    project = Path(config['projectDir'])
    require_pinned_file(project.parent / 'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th',
                         PINNED_DEMUCS_SHA256, 'HTDemucs checkpoint')
    contexts = []
    for clip, scratch in pairs:
        validate_generated_path(scratch, clip)
        stream = audio_stream(clip)
        if stream['sampleRate'] != 48000 or stream['channelCount'] != 2 or stream['frameCount'] <= 0:
            raise ValueError('Vocal batch requires canonical stereo PCM')
        workspace = AlignmentWorkspace(scratch / 'research', workspace_root=scratch)
        workspace.job_dir('vocal-probe').mkdir(parents=True, exist_ok=True)
        duration = stream['durationMs']
        reused = _reuse_separation(clip, scratch)
        source_sha = sha256_file(clip)
        if reused is not None:
            provenance = write_audio_provenance(workspace, 'vocal-probe', duration_ms=duration,
                mix_audio=clip, vocals_audio=reused[ROUTES[0]][0], demucs_model_sha256=PINNED_DEMUCS_SHA256,
                expected_mix_sha256=source_sha, producer_runtime=producer_runtime)
            provenance['audioViews'] = {r: v for r, (_, v) in reused.items()}
            atomic_write_bytes(workspace.audio_provenance_path('vocal-probe'), json_bytes(provenance), root=scratch)
        prior = read_audio_provenance(workspace, 'vocal-probe', duration, expected_mix_sha256=source_sha,
            expected_producer_runtime=producer_runtime)
        if reused is not None and (prior is None or prior['audioViews'] != provenance['audioViews']):
            raise ValueError('Reused vocal provenance failed current-mix readback')
        views = dict(reused or {})
        paths = {ROUTES[0]: scratch / 'demucs/htdemucs' / clip.stem / 'vocals.wav',
                 **{r: scratch / r / (r + '.wav') for r in ROUTES[1:]}}
        for route in ROUTES:
            if route in views or not prior or route not in prior['audioViews']:
                continue
            identity = prior['audioViews'][route]
            try:
                if route == ROUTES[0] and identity.get('demucsModelSha256') != PINNED_DEMUCS_SHA256:
                    raise ValueError('Retained HTDemucs checkpoint differs')
                if _identity(paths[route]) != {k: identity[k] for k in ('sha256', 'byteCount')}:
                    raise ValueError('Retained vocal output identity differs')
                _vocal_samples(paths[route], identity, duration, sample_rate=identity['stream']['sampleRate'])
            except (ValueError, OSError):
                break  # A damaged upstream view invalidates its downstream recovery.
            views[route] = (paths[route], identity)
        contexts.append({'clip': clip, 'scratch': scratch, 'workspace': workspace,
            'duration': duration, 'sha256': source_sha, 'views': views, 'paths': paths, 'remaining': float(timeout_seconds)})
    def expired(*_):
        raise TimeoutError('Vocal preparation context exceeded its deadline')
    previous_handler = signal.signal(signal.SIGALRM, expired)
    def timed(context, action):
        if context['remaining'] <= 0:
            expired()
        start = time.monotonic()
        signal.setitimer(signal.ITIMER_REAL, context['remaining'])
        try:
            return action()
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            context['remaining'] -= time.monotonic() - start
    model, cache = None, {}
    try:
        pending = [c for c in contexts if ROUTES[0] not in c['views']]
        if pending:
            options = demucs.get_parser().parse_args(['--two-stems', 'vocals', '--name', 'htdemucs',
                '--shifts', '0', '--device', 'cuda', str(pending[0]['clip'])])
            model = timed(pending[0], lambda: demucs.get_model_from_args(options))
            model.cpu().eval()
            def isolate(context):
                # Keep the pinned demucs.separate normalization, inference and PCM16 writer.
                wav = demucs.load_track(context['clip'], model.audio_channels, model.samplerate)
                wav, offset, scale = normalize_demucs_input(wav)
                sources = demucs.apply_model(model, wav[None], device=options.device, shifts=options.shifts,
                    split=options.split, overlap=options.overlap, progress=True,
                    num_workers=options.jobs, segment=options.segment)[0]
                sources *= scale
                sources += offset
                output = context['paths'][ROUTES[0]]
                output.parent.mkdir(parents=True, exist_ok=True)
                demucs.save_audio(sources[model.sources.index('vocals')], str(output), samplerate=model.samplerate,
                    bitrate=options.mp3_bitrate, preset=options.mp3_preset, clip=options.clip_mode,
                    as_float=options.float32, bits_per_sample=24 if options.int24 else 16)
                provenance = write_audio_provenance(context['workspace'], 'vocal-probe',
                    duration_ms=context['duration'], mix_audio=context['clip'], vocals_audio=output,
                    demucs_model_sha256=PINNED_DEMUCS_SHA256, expected_mix_sha256=context['sha256'],
                    producer_runtime=producer_runtime)
                context['views'][ROUTES[0]] = (output, provenance['audioViews'][ROUTES[0]])
            for context in pending:
                timed(context, lambda: isolate(context))
            model = None
            gc.collect()
            torch.cuda.empty_cache()
        for route in ROUTES[1:]:
            pending = [c for c in contexts if route not in c['views']]
            if not pending:
                continue
            assets = roformer_assets(project.parent, route)
            for context in pending:
                source = context['clip'] if route == ROUTES[1] else context['views'][ROUTES[1]][0]
                output = context['paths'][route]
                output.parent.mkdir(parents=True, exist_ok=True)
                result_path = output.with_suffix('.result.private.json')
                result_path.unlink(missing_ok=True)
                arguments = roformer.parser().parse_args(['--route', route, '--audio', str(source),
                    '--input-sha256', sha256_file(source), '--checkpoint', str(assets['checkpoint']),
                    '--checkpoint-sha256', assets['checkpointSha256'], '--config', str(assets['config']),
                    '--config-sha256', assets['configSha256'], '--output', str(output), '--result', str(result_path),
                    '--device', 'cuda'])
                timed(context, lambda: roformer.run(arguments, model_cache=cache))
                result = read_bounded_json(result_path, max_bytes=1024*1024, root=output.parent)
                provenance = write_roformer_audio_provenance(context['workspace'], 'vocal-probe',
                    duration_ms=context['duration'], expected_mix_sha256=context['sha256'], route_id=route,
                    output_audio=output, result=result, helper_sha256=semantic_source_identity(Path(roformer.__file__)),
                    producer_runtime=producer_runtime)
                context['views'][route] = (output, provenance['audioViews'][route])
            cache.clear()
            gc.collect()
            torch.cuda.empty_cache()
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)
        model = None
        cache.clear()
        gc.collect()
        torch.cuda.empty_cache()
        # Each completed context survives a later context's failure. Incomplete
        # stages retain only their existing hash-bound separation recovery.
        for context in contexts:
            if set(context['views']) == set(ROUTES):
                if read_audio_provenance(context['workspace'], 'vocal-probe', context['duration'],
                        expected_mix_sha256=context['sha256'], expected_producer_runtime=producer_runtime) is None:
                    raise ValueError('Completed vocal provenance failed readback')
                atomic_write_bytes(context['scratch'] / 'vocal-worker.json', json_bytes({
                    r: {'path': str(p.relative_to(context['scratch'])), 'provenance': v}
                    for r, (p, v) in context['views'].items()}), root=context['scratch'])
                _index_separation(context['clip'], context['scratch'])
    return [c['views'] for c in contexts]


def _separation_identity():
    from stemwise_engine.alignment_models import roformer_runtime_pins
    from stemwise_engine.model_registry import model_assets, runtime_profile
    from stemwise_engine.provenance import semantic_source_identity
    from stemwise_engine.worker_runtime_probe import torch_runtime_identity
    import stemwise_engine.roformer_separation as roformer
    source = Path(__file__).resolve().parent
    assets = model_assets('lyrics_align_pc')
    return {
        'source': {
            'separation': semantic_source_identity(Path(__file__),
                ('normalize_demucs_input', '_separate_many_local')),
            'roformer': semantic_source_identity(Path(roformer.__file__)),
            'mono': semantic_source_identity(source / 'acoustic_inputs.py',
                ('informative_views', 'mono_representation', 'mono_samples', 'mono_tensor')),
        },
        'models': {'demucs': assets['PINNED_DEMUCS_SHA256'],
                   'roformer': assets['ROFORMER_ROUTES']},
        'runtime': {**roformer_runtime_pins(source.parent),
                    'demucs': runtime_profile('main')['packages']['demucs']},
        'producerRuntime': torch_runtime_identity(),
    }


def _separation_key(clip):
    import soundfile as sf
    digest = hashlib.sha256(json_bytes(_separation_identity()))
    with sf.SoundFile(clip) as source:
        if source.samplerate != 48000 or source.channels != 2 or not len(source):
            raise ValueError('Vocal reuse needs canonical stereo PCM')
        digest.update(json_bytes([source.samplerate, source.channels, len(source)]))
        for block in source.blocks(blocksize=65536, dtype='float32', always_2d=True):
            if not np.isfinite(block).all():
                raise ValueError('Vocal reuse source contains nonfinite samples')
            digest.update(block.astype('<f4', copy=False).tobytes())
        return digest.hexdigest(), source_duration_ms(len(source), source.samplerate), len(source)


def _index_separation(clip, scratch):
    if not scratch.name.startswith('context-') or scratch.parent.parent.name not in {
            'endpoint-ctc-evidence', 'endpoint-owsm-ctc'}:
        return
    job = scratch.parents[2]
    path = job / 'vocal-context-index.json'
    key, _, _ = _separation_key(clip)
    atomic_write_bytes(scratch / 'vocal-separation.json',
        json_bytes({'schemaVersion': 1, 'key': key}), root=scratch)
    try:
        index = read_bounded_json(path, max_bytes=1024*1024, root=job)
        if not isinstance(index, dict): raise ValueError('Vocal context index is malformed')
    except (OSError, ValueError, RuntimeError):
        index = {}
    index[key] = str(scratch.relative_to(job))
    atomic_write_bytes(path, json_bytes(index), root=job)


def _separation_candidates(job, key):
    try:
        index = read_bounded_json(job / 'vocal-context-index.json', max_bytes=1024*1024, root=job)
        indexed = validate_generated_path(job, job / index[key])
    except (OSError, ValueError, RuntimeError, KeyError, TypeError):
        indexed = None
    if indexed is not None:
        yield indexed
    for owner in ('endpoint-ctc-evidence', 'endpoint-owsm-ctc'):
        for record in (job / owner).glob('*/context-*/vocal-separation.json'):
            try:
                identity = read_bounded_json(record, max_bytes=1024, root=job)
                if identity == {'schemaVersion': 1, 'key': key}:
                    candidate = validate_generated_path(job, record.parent)
                    if candidate != indexed:
                        yield candidate
            except (OSError, ValueError, RuntimeError):
                continue


def _reuse_separation(clip: Path, scratch: Path):
    """Reuse same-job stems for identical PCM, independent of WAV timestamps."""
    import shutil
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace
    from stemwise_engine.alignment_models import PINNED_DEMUCS_SHA256
    from stemwise_engine.alignment_audio import read_audio_provenance
    from stemwise_engine.worker_runtime_probe import torch_runtime_identity
    if (not scratch.name.startswith("context-") or scratch.parent.parent.name
            not in {"endpoint-ctc-evidence", "endpoint-owsm-ctc"}
            or (scratch / "prepared.json").exists()):
        return None
    job = scratch.parents[2]
    try:
        key, duration, frames = _separation_key(clip)
        candidates = _separation_candidates(job, key)
    except (OSError, ValueError, RuntimeError, KeyError, TypeError):
        return None
    for cached in candidates:
        if cached == scratch:
            continue
        for prepared_path in (cached / 'prepared.json', cached / 'vocal-worker.json'):
            if not prepared_path.exists():
                continue
            try:
                identity = read_bounded_json(cached / 'vocal-separation.json', max_bytes=1024, root=cached)
                if identity != {'schemaVersion': 1, 'key': key}:
                    continue
                old_clip = validate_generated_path(cached, cached / 'source.wav')
                if _separation_key(old_clip)[0] != key:
                    continue
                provenance = read_audio_provenance(
                    AlignmentWorkspace(cached / "research", workspace_root=cached), "vocal-probe",
                    duration, expected_mix_sha256=sha256_file(old_clip),
                    expected_producer_runtime=torch_runtime_identity())
                retained = read_bounded_json(prepared_path, max_bytes=1024*1024, root=cached)
                views = retained['views'] if prepared_path.name == 'prepared.json' else retained
                if (not provenance or set(views) != set(ROUTES) or set(provenance["audioViews"]) != set(ROUTES)
                        or provenance["audioViews"][ROUTES[0]]["demucsModelSha256"] != PINNED_DEMUCS_SHA256
                        or provenance["mix"]["byteCount"] != old_clip.stat().st_size):
                    continue
                stream = provenance["mix"]["stream"]
                if stream != {"sampleRate": 48000, "channelCount": 2,
                              "frameCount": frames, "durationMs": duration}:
                    continue
                copies = []
                for route in ROUTES:
                    record = views[route]
                    if record["provenance"] != provenance["audioViews"][route]:
                        raise ValueError("cached stem metadata changed")
                    src = validate_generated_path(cached, cached / record["path"])
                    dst = validate_generated_path(scratch, scratch / record["path"])
                    if dst.exists() or src.stat().st_size != record["provenance"]["byteCount"]:
                        raise ValueError("cached stem path or size changed")
                    _vocal_samples(src, record["provenance"], duration,
                                   sample_rate=record["provenance"]["stream"]["sampleRate"])
                    copies.append((route, src, dst, record["provenance"]))
            except (OSError, ValueError, RuntimeError, KeyError, TypeError):
                continue
            # Validate every source before changing this unprepared context. Copies
            # stay independent so later cleanup or damage cannot alter old recovery.
            _index_separation(old_clip, cached)
            for _, src, dst, _ in copies:
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src, dst)
            current_sha = sha256_file(clip)
            return {route: (dst, {**record, 'sourceAudioSha256': current_sha}
                           if record.get('sourceViewId') == 'mix' else dict(record))
                    for route, _, dst, record in copies}
    try:
        path = job / 'vocal-context-index.json'
        index = read_bounded_json(path, max_bytes=1024*1024, root=job)
        if isinstance(index, dict) and key in index:
            del index[key]
            atomic_write_bytes(path, json_bytes(index), root=job)
    except (OSError, ValueError, RuntimeError):
        pass
    return None


def _vocal_samples(path: Path, provenance: dict, duration: int, *, sample_rate: int = RATE) -> np.ndarray:
    import soundfile as sf
    if sha256_file(path) != provenance["sha256"]:
        raise ValueError("vocal artifact differs from separation output")
    transform = provenance["canonicalTimeTransform"]
    if any(transform.get(k) != 0 for k in ("offsetFrames", "trimFrames", "padFrames", "separatorLatencyFrames")) or abs(transform.get("measuredResidualMs", 999)) > 1:
        raise ValueError("vocal source timeline is not aligned")
    x, rate = sf.read(path, dtype="float64", always_2d=True)
    if not np.isfinite(x).all() or x.shape[1] not in (1, 2):
        raise ValueError("invalid vocal PCM")
    if type(sample_rate) is not int or sample_rate <= 0:
        raise ValueError("invalid vocal sample rate")
    if rate != sample_rate:
        from scipy.signal import resample_poly
        from math import gcd
        factor = gcd(rate, sample_rate)
        x = resample_poly(x, sample_rate//factor, rate//factor, axis=0)
    expected = round(duration*sample_rate/1000)
    if abs(len(x)-expected) > int(np.ceil(sample_rate/1000)):
        raise ValueError("vocal crop length changed")
    # Reconcile only the already validated <=1 ms trailing separator residual.
    return np.pad(x[:expected], ((0, max(0, expected-len(x))), (0, 0)))


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == '--separate-many':
        raw = sys.stdin.read(16*1024*1024+1)
        pairs = json.loads(raw) if len(raw.encode()) <= 16*1024*1024 else None
        if (not isinstance(pairs, list) or not pairs or any(not isinstance(p, list) or len(p) != 2
                or any(not isinstance(v, str) for v in p) for p in pairs)):
            raise ValueError('invalid vocal context batch')
        project = Path(sys.argv[2])
        _separate_many_local(load_config(project / 'config.json'), [(Path(c), Path(s)) for c, s in pairs], int(sys.argv[3]))
        raise SystemExit(0)
    if len(sys.argv) != 5 or sys.argv[1] != "--separate":
        raise SystemExit("private vocal worker requires project, clip, and scratch paths")
    project, clip, scratch = map(Path, sys.argv[2:])
    validate_generated_path(scratch, clip)
    outputs = _separate(load_config(project / "config.json"), clip, scratch)
    atomic_write_bytes(scratch / "vocal-worker.json", json_bytes({route: {
        "path": str(path.relative_to(scratch)), "provenance": provenance}
        for route, (path, provenance) in outputs.items()}), root=scratch)
