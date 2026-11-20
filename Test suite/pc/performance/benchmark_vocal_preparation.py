"""Paired real-separator benchmark; crops only, no lyric decisions or delivery."""
import argparse
import json
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT / 'PC side/stemwise-playlist-builder'
from stemwise_engine.common import exclusive_process_lock, heavy_worker_lock_path, run_process_tree, load_config, sha256_file
from stemwise_engine.runtime_environment import environment_python, environment_lease


def _serial_separate_local(config: dict, clip: Path, scratch: Path) -> dict[str, tuple[Path, dict]]:
    """Reuse pinned workers in an isolated scratch workspace, never the job's."""
    from stemwise_engine.endpoint_vocal_evidence import ROUTES, _reuse_separation
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace, separate_vocals_demucs
    from stemwise_engine.lyrics_align_pc import _run_roformer_separation
    from stemwise_engine.alignment_models import PINNED_DEMUCS_SHA256
    from stemwise_engine.alignment_audio import write_audio_provenance, audio_stream
    from stemwise_engine.alignment_models import require_pinned_file
    project = Path(config["projectDir"])
    checkpoint = project.parent / "model-cache/torch/hub/checkpoints/955717e8-8726e21a.th"
    require_pinned_file(checkpoint, PINNED_DEMUCS_SHA256, "HTDemucs checkpoint")
    reused = _reuse_separation(clip, scratch)
    if reused is not None:
        return reused
    workspace = AlignmentWorkspace(scratch / "research", workspace_root=scratch)
    video_id = "vocal-probe"  # Valid private scratch ID; never a production job.
    workspace.job_dir(video_id).mkdir(parents=True, exist_ok=True)
    duration = audio_stream(clip)["durationMs"]
    source_sha = sha256_file(clip)
    timeout = int(config["analysisTimeoutMinutes"]) * 60
    vocals = separate_vocals_demucs(clip, scratch / "demucs", project_dir=project,
                                    device="cuda", timeout_seconds=timeout)
    provenance = write_audio_provenance(workspace, video_id, duration_ms=duration,
        mix_audio=clip, vocals_audio=vocals, demucs_model_sha256=PINNED_DEMUCS_SHA256,
        expected_mix_sha256=source_sha)
    result = {ROUTES[0]: (vocals, provenance["audioViews"][ROUTES[0]])}
    source, identity = clip, provenance["mix"]
    runtime_cache = {}
    for route in ROUTES[1:]:
        output, view, _ = _run_roformer_separation(config=config, video_id=video_id, route_id=route,
            input_audio=source, input_identity=identity, workspace=workspace, duration_ms=duration,
            expected_mix_sha256=source_sha, temp_dir=scratch / route, device="cuda", timeout_seconds=timeout,
            runtime_cache=runtime_cache)
        result[route] = (output, view)
        source, identity = output, view
    return result


def verify_recovery(work, contexts):
    from stemwise_engine.alignment_audio import read_audio_provenance
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace
    from stemwise_engine.endpoint_vocal_evidence import ROUTES
    for i in range(contexts):
        scratch = work / f'context-{i:03d}'
        workspace = AlignmentWorkspace(scratch / 'research', workspace_root=scratch)
        path = workspace.audio_provenance_path('vocal-probe')
        original = path.read_bytes()
        try:
            value = json.loads(original)
            assert read_audio_provenance(workspace, 'vocal-probe', 8000,
                expected_mix_sha256=value['mix']['sha256']) is not None
            value['audioViews'][ROUTES[1]]['helperSha256'] = '0' * 64
            path.write_text(json.dumps(value))
            assert read_audio_provenance(workspace, 'vocal-probe', 8000) is None
            value = json.loads(original)
            value['audioViews'][ROUTES[2]]['sourceAudioSha256'] = '0' * 64
            path.write_text(json.dumps(value))
            assert read_audio_provenance(workspace, 'vocal-probe', 8000) is None
        finally:
            path.write_bytes(original)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--contexts', type=int, default=2)
    parser.add_argument('--child', choices=('serial', 'batch'))
    args = parser.parse_args()
    work = args.work.resolve()
    if not work.is_relative_to(ROOT / 'Test suite/work') or not 1 <= args.contexts <= 8:
        parser.error('Use contained test work and one to eight contexts')
    from stemwise_engine.endpoint_vocal_evidence import _separate_many_local, ROUTES
    import soundfile as sf
    pairs = [(work / f'context-{i:03d}/source.wav', work / f'context-{i:03d}') for i in range(args.contexts)]
    config = load_config(PROJECT / 'config.json')
    if args.child:
        loads = {}
        if args.child == 'batch':
            import demucs.separate as demucs
            import mel_band_roformer as mel
            original_demucs, original_mel = demucs.get_model_from_args, mel.get_model_from_config
            def load_demucs(*a, **k):
                loads['htdemucs'] = loads.get('htdemucs', 0) + 1
                return original_demucs(*a, **k)
            def load_mel(*a, **k):
                loads['roformer'] = loads.get('roformer', 0) + 1
                return original_mel(*a, **k)
            demucs.get_model_from_args, mel.get_model_from_config = load_demucs, load_mel
            import io
            import runpy
            import stemwise_engine.endpoint_vocal_evidence as vocal
            sys.argv = [vocal.__file__, '--separate-many', str(PROJECT), '1200']
            sys.stdin = io.StringIO(json.dumps([[str(c), str(s)] for c, s in pairs]))
            try:
                runpy.run_path(vocal.__file__, run_name='__main__')
            except SystemExit as error:
                if error.code:
                    raise
            outputs = [{r: (s / v['path'], v['provenance']) for r, v in
                        json.loads((s / 'vocal-worker.json').read_bytes()).items()} for _, s in pairs]
            if loads != {'htdemucs': 1, 'roformer': 2}:
                raise RuntimeError('Batch did not reuse exactly one model per separator')
        else:
            outputs = [_serial_separate_local(config, clip, scratch) for clip, scratch in pairs]
        print(json.dumps({'loads': loads, 'outputs': [{r: str(p) for r, (p, _) in v.items()} for v in outputs]}))
        return
    from stemwise_engine.alignment_models import roformer_offline_env
    results = {}
    with exclusive_process_lock(heavy_worker_lock_path(config)):
        for mode in ('serial', 'batch'):
            mode_work = work / mode
            with sf.SoundFile(args.input.resolve(strict=True)) as source:
                for i in range(args.contexts):
                    scratch = mode_work / f'context-{i:03d}'
                    scratch.mkdir(parents=True)
                    source.seek(i * 8 * source.samplerate)
                    samples = source.read(8 * source.samplerate, dtype='float32', always_2d=True)
                    if len(samples) != 8 * source.samplerate or samples.shape[1] != 2:
                        raise ValueError('Input must supply complete stereo eight-second crops')
                    if source.samplerate != 48000:
                        from scipy.signal import resample_poly
                        from math import gcd
                        factor = gcd(source.samplerate, 48000)
                        samples = resample_poly(samples, 48000 // factor, source.samplerate // factor, axis=0)
                    sf.write(scratch / 'source.wav', samples, 48000, subtype='FLOAT')
            profile = 'lyrics' if mode == 'serial' else 'main'
            python = environment_python(profile, PROJECT)
            start = time.perf_counter()
            with environment_lease(profile, builder=PROJECT):
                completed = run_process_tree([str(python), str(Path(__file__)), '--input', str(args.input),
                    '--work', str(mode_work), '--contexts', str(args.contexts), '--child', mode],
                    timeout=1200 * args.contexts, env=roformer_offline_env(PROJECT.parent),
                    capture_output=True, text=True, check=True)
            results[mode] = {'seconds': time.perf_counter() - start, **json.loads(completed.stdout.splitlines()[-1])}
            print(json.dumps({'mode': mode, 'seconds': results[mode]['seconds'], 'loads': results[mode]['loads']}), flush=True)
    import numpy as np
    for old, new in zip(results['serial']['outputs'], results['batch']['outputs']):
        if set(old) != set(ROUTES) or set(new) != set(ROUTES):
            raise ValueError('Missing vocal output')
        for route in ROUTES:
            a, ar = sf.read(old[route], dtype='float64', always_2d=True)
            b, br = sf.read(new[route], dtype='float64', always_2d=True)
            if ar != br or not np.array_equal(a, b):
                raise RuntimeError(f'Numerical output changed for {route}')
    summary = {'serialSeconds': results['serial']['seconds'], 'batchSeconds': results['batch']['seconds'],
               'contexts': args.contexts, 'exactPcmMatch': True, 'batchModelLoads': results['batch']['loads']}
    verify_recovery(work / 'batch', args.contexts)
    (work / 'result.json').write_text(json.dumps(summary))
    print(json.dumps(summary), flush=True)


if __name__ == '__main__':
    main()
