"""Fake only expensive model APIs while running the current worker entry points."""
import os
from pathlib import Path


def worker_fixture(root: Path, stage: str, *, native_seconds=3):
    source = Path(__file__).resolve().parents[3] / 'PC side/stemwise-playlist-builder/src'
    fake = root / 'fake-models'
    files = {
        'allin1/__init__.py': '''
def analyze(paths, **kwargs):
    from importlib import import_module
    implementation = import_module('allin1.analyze')
    demixed = implementation.demix(paths, kwargs['demix_dir'], kwargs['device'])
    specs = implementation.extract_spectrograms(demixed, kwargs['spec_dir'], False)
    model = implementation.load_pretrained_model()
    return [implementation.run_inference(path=path, spec_path=spec, model=model)
            for path, spec in zip(paths, specs)]
''',
        'allin1/analyze.py': '''
import ctypes, os, time
from pathlib import Path
from types import SimpleNamespace
def extract_spectrograms(paths, directory, multiprocess):
    directory.mkdir(parents=True, exist_ok=True)
    outputs = []
    for path in paths:
        output = directory/(path.name+'.npy')
        if not output.exists():
            with open(os.environ['FIXTURE_EVENTS'], 'a', encoding='utf-8') as events:
                events.write('spec '+path.name+'\\n')
            output.write_bytes(b'complete spectrogram')
        outputs.append(output)
    return outputs
def load_pretrained_model():
    return object()
def run_inference(**kwargs):
    with open(os.environ['FIXTURE_EVENTS'], 'a', encoding='utf-8') as events:
        events.write('inference '+kwargs['path'].stem+'\\n')
    if os.environ['FIXTURE_STAGE'] in {'inference', 'first-inference', 'last-inference'} and (
            os.environ['FIXTURE_STAGE'] == 'inference' or
            ('000001' if os.environ['FIXTURE_STAGE'].startswith('last') else '000000') in str(kwargs['path'])):
        print('STAGE_STARTED='+str(time.monotonic()), flush=True)
        ctypes.CDLL(None).system(('echo NATIVE_CHILD_PID=$$; exec sleep '+os.environ['FIXTURE_NATIVE_SECONDS']).encode())
    return SimpleNamespace(path=kwargs['path'], bpm=120, beats=[0, .5], downbeats=[0], segments=[
        SimpleNamespace(start=0, end=1, label='verse')])
''',
        'allin1/typings.py': 'from types import SimpleNamespace as AnalysisResult\n',
        'allin1/models/__init__.py': '',
        'allin1/models/loaders.py': 'from huggingface_hub import hf_hub_download\n',
        'huggingface_hub.py': '''
import ctypes, os, time
from pathlib import Path
def hf_hub_download(repo_id, filename, revision):
    if os.environ['FIXTURE_STAGE'] == 'setup':
        print('STAGE_STARTED='+str(time.monotonic()), flush=True)
        ctypes.CDLL(None).system(b'exec sleep 3')
    destination = Path(os.environ['FIXTURE_MODELS']) / repo_id / 'snapshots' / revision / filename
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(b'fixture model')
    return str(destination)
''',
        'demucs/__init__.py': '''
import ctypes, os, time
from pathlib import Path
from types import SimpleNamespace
def main(args):
    with open(os.environ['FIXTURE_EVENTS'], 'a', encoding='utf-8') as events:
        events.write('demix '+Path(args[-1]).stem+'\\n')
    if os.environ['FIXTURE_STAGE'] in {'demix', 'first-demix', 'last-demix'} and (
            os.environ['FIXTURE_STAGE'] == 'demix' or
            ('000001' if os.environ['FIXTURE_STAGE'].startswith('last') else '000000') in args[-1]):
        print('STAGE_STARTED='+str(time.monotonic()), flush=True)
        ctypes.CDLL(None).system(('echo NATIVE_CHILD_PID=$$; exec sleep '+os.environ['FIXTURE_NATIVE_SECONDS']).encode())
    destination = Path(args[1]) / 'htdemucs' / Path(args[-1]).stem
    destination.mkdir(parents=True, exist_ok=True)
    for stem in ('bass', 'drums', 'other', 'vocals'):
        (destination / (stem+'.wav')).write_bytes(b'pcm')
separate = SimpleNamespace(Separator=lambda **kwargs: object(), main=main)
''',
    }
    files['allin1/analyze.py'] += files['allin1/__init__.py']
    files['allin1/__init__.py'] = 'from .analyze import analyze\n'
    for name, body in files.items():
        path = fake / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    driver = root / 'worker-entry.py'
    driver.write_text("\nimport hashlib, sys\nimport importlib.metadata\nimport stemwise_engine.model_registry as model_registry\nimport stemwise_engine.allin1_worker as allin1_worker\nimport allin1, allin1.typings, allin1.models.loaders, demucs\ndigest = hashlib.sha256(b'fixture model').hexdigest()\npins = {'ALLIN1_MODEL_REVISION': 'exact-allin1', 'ALLIN1_MODEL_SHA256': {'model.bin': digest},\n        'HTDEMUCS_MODEL_REVISION': 'exact-demucs', 'HTDEMUCS_MODEL_SHA256': {'model.bin': digest}}\nmodel_registry.model_assets = lambda _: pins\nimportlib.metadata.version = lambda _: 'fixture'\nraise SystemExit(allin1_worker.main(sys.argv[1:]))\n", encoding='utf-8')
    return driver, {**os.environ, 'PYTHONPATH': os.pathsep.join((str(fake), str(source))),
                    'FIXTURE_STAGE': stage, 'FIXTURE_MODELS': str(root / 'models'),
                    'FIXTURE_NATIVE_SECONDS': str(native_seconds),
                    'FIXTURE_EVENTS': str(root/'phase-events.txt'),
                    'PYTHONDONTWRITEBYTECODE': '1'}
