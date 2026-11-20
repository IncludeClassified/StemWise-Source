"""Measure real CTC startup/inference; mix crops substitute for separated views.

Synthetic targets isolate scheduling cost, never lyric accuracy or publication.
"""
import argparse
import json
import os
import struct
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT / 'PC side/stemwise-playlist-builder'
from stemwise_engine.common import atomic_write_json, exclusive_process_lock, heavy_worker_lock_path, run_process_tree, sha256_file
from stemwise_engine.runtime_environment import environment_python, environment_lease


def prepare_contexts(input_path, work, assets, count, language):
    import soundfile as sf
    from stemwise_engine.endpoint_vocal_evidence import ROUTES
    from stemwise_engine.endpoint_context_cache import context_binding
    work.mkdir(parents=True, exist_ok=False)
    scratches = []
    with sf.SoundFile(input_path.resolve(strict=True)) as source:
        for index in range(count):
            scratch = work/f'context-{index:03d}'
            scratch.mkdir()
            source.seek(index*8*source.samplerate)
            samples = source.read(8*source.samplerate, dtype='float32', always_2d=True)
            if len(samples) != 8*source.samplerate:
                raise ValueError('Input must supply all eight-second test contexts')
            wav = scratch/'source.wav'
            # libsndfile FLOAT headers include a PEAK timestamp; keep identical
            # numerical fixture inputs byte-identical across independent runs.
            data = samples.astype('<f4', copy=False).tobytes()
            block = samples.shape[1]*4
            fmt = struct.pack('<HHIIHH', 3, samples.shape[1], source.samplerate,
                              source.samplerate*block, block, 32)
            body = b'WAVEfmt '+struct.pack('<I', len(fmt))+fmt+b'data'+struct.pack('<I', len(data))+data
            wav.write_bytes(b'RIFF'+struct.pack('<I', len(body))+body)
            provenance = {'sha256': sha256_file(wav), 'byteCount': wav.stat().st_size,
                'canonicalTimeTransform': {'offsetFrames': 0, 'trimFrames': 0, 'padFrames': 0,
                    'separatorLatencyFrames': 0, 'measuredResidualMs': 0}}
            atomic_write_json(scratch/'prepared.json', {**assets, 'views': {
                route: {'path': wav.name, 'provenance': provenance} for route in ROUTES}})
            words = ('hello', 'world') if language == 'en' else ('سلام', 'عليكم')
            atomic_write_json(scratch/'request.private.json', {'language': language, 'startMs': 0, 'endMs': 8000,
                'targetLine': 0, 'request': {'lines': [{'lineId': 'fixture', 'sourcePhysicalIndex': 0,
                    'words': [{'wordId': 'a', 'acousticText': words[0]}, {'wordId': 'b', 'acousticText': words[1]}]}]},
                'candidateFamilies': {'a': {'startMs': 1000, 'endMs': 6000}, 'b': {'startMs': 1020, 'endMs': 6020}}})
            context_binding(scratch, assets['model'])
            scratches.append(scratch)
    return scratches


def require_serial_reference(result, reference):
    if (reference.get('mode') != 'serial' or reference.get('binding') != result['binding']
            or reference.get('results') != result['results']):
        raise ValueError('Batched acoustic calculations or bound inputs differ from the serial reference')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--mode', choices=('serial', 'batch'), required=True)
    parser.add_argument('--contexts', type=int, default=3)
    parser.add_argument('--language', choices=('en', 'ar'), default='en')
    parser.add_argument('--reference', type=Path, help='Required serial numerical result for a batch comparison')
    args = parser.parse_args()
    work = args.work.resolve()
    if not work.is_relative_to(ROOT / 'Test suite/work') or not 1 <= args.contexts <= 8:
        parser.error('Use contained test work and one to eight contexts')
    work.mkdir(parents=True, exist_ok=True)
    if args.mode == 'batch' and args.reference is None:
        parser.error('Batch timing requires --reference from an independent serial run')
    cache = PROJECT.parent / 'model-cache'
    env = {**os.environ, 'PYTHONPATH': str(PROJECT / 'src'), 'HF_HUB_OFFLINE': '1',
           'TRANSFORMERS_OFFLINE': '1', 'HF_HOME': str(cache / 'huggingface'), 'TORCH_HOME': str(cache / 'torch')}
    resolver = "\nimport json,sys\nfrom pathlib import Path\nfrom stemwise_engine.common import load_config\nfrom stemwise_engine.lyrics_align_pc import _wav2vec2_model_assets\npath,identity=_wav2vec2_model_assets(load_config(Path(sys.argv[1])/'config.json'),sys.argv[2])\nprint(json.dumps({'modelPath':str(path),'model':identity}))\n"
    with environment_lease('lyrics', builder=PROJECT):
        resolved = run_process_tree([str(environment_python('lyrics', PROJECT)), '-c', resolver, str(PROJECT), args.language],
                                   timeout=120, cwd=ROOT, env=env, capture_output=True, text=True, check=True)
    assets = json.loads(resolved.stdout)
    scratches = prepare_contexts(args.input, work/args.mode, assets, args.contexts, args.language)
    script = "\nimport json,sys,time\nfrom pathlib import Path\nimport transformers\nimport stemwise_engine.endpoint_ctc_evidence as ctc\nloads=[]\noriginal=transformers.AutoModelForCTC.from_pretrained\ndef load(*args,**kwargs):\n start=time.perf_counter();model=original(*args,**kwargs);loads.append(time.perf_counter()-start);return model\ntransformers.AutoModelForCTC.from_pretrained=load\nproject=Path(sys.argv[1]);scratches=[Path(s) for s in sys.argv[3:]]\nif sys.argv[2]=='batch':ctc._infer_many(project,scratches)\nelse:ctc._infer(project,scratches[0])\nprint(json.dumps({'modelLoads':len(loads),'modelLoadSeconds':sum(loads)}))\n"
    started = time.perf_counter()
    loads = 0
    load_seconds = 0
    with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(PROJECT)})):
        started = time.perf_counter()
        groups = [scratches] if args.mode == 'batch' else [[s] for s in scratches]
        for group in groups:
            with environment_lease('main', builder=PROJECT):
                completed = run_process_tree([str(environment_python('main', PROJECT)), '-c', script, str(PROJECT), args.mode, *map(str, group)],
                    timeout=1200, cwd=ROOT, env=env, capture_output=True, text=True, check=True)
            metrics = json.loads(completed.stdout.splitlines()[-1])
            loads += metrics['modelLoads']
            load_seconds += metrics['modelLoadSeconds']
    result = {'mode': args.mode, 'binding': {'inputSha256': sha256_file(args.input),
        'contexts': args.contexts, 'language': args.language, 'model': assets['model']},
        'seconds': time.perf_counter()-started, 'modelLoads': loads, 'modelLoadSeconds': load_seconds,
              'results': [json.loads((s / 'inference.json').read_bytes()) for s in scratches]}
    if loads != (1 if args.mode == 'batch' else args.contexts) or any(len(row.get('records', [])) != 9 for row in result['results']):
        raise ValueError('Benchmark did not run every required real-model calculation')
    if args.mode == 'batch':
        reference = json.loads(args.reference.read_bytes())
        require_serial_reference(result, reference)
    atomic_write_json(work / (args.mode + '.json'), result)
    print(json.dumps({k: v for k, v in result.items() if k != 'results'}))


if __name__ == '__main__':
    main()
