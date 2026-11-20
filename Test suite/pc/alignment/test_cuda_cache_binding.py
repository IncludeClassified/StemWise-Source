"""Disk cache admission follows observed producers, with native inference substituted."""
import itertools
import json
import sys
import wave
from dataclasses import asdict, dataclass
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import stemwise_engine.alignment_audio as audio_store
import stemwise_engine.lyrics_align_pc as align
import stemwise_engine.worker_runtime_probe as probe
from stemwise_engine.lyrics_alignment import AlignmentWorkspace
import stemwise_engine.lyrics_alignment as lyrics_alignment

ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT/'PC side/stemwise-playlist-builder'


@dataclass(frozen=True)
class FixtureWord:
    word: str
    raw: str
    start: float = 0.
    end: float = .2

    def to_dict(self):
        return asdict(self)

    @classmethod
    def from_dict(cls, value):
        return cls(**value)


class CudaCacheBindingTests(unittest.TestCase):
    def test_torch_observation_uses_native_device_and_driver_without_child_processes(self):
        properties = SimpleNamespace(uuid='physical-device', name='Observed GPU', total_memory=16*1024**3)
        torch = SimpleNamespace(__version__='2.7.0', version=SimpleNamespace(cuda='12.8'),
            backends=SimpleNamespace(cudnn=SimpleNamespace(version=lambda: 90000)),
            cuda=SimpleNamespace(is_available=lambda: True, current_device=lambda: 3,
                get_device_properties=Mock(return_value=properties),
                get_device_capability=Mock(return_value=(8, 9))))
        def driver_version(pointer):
            pointer._obj.value = 13030
            return 0
        driver = SimpleNamespace(cuDriverGetVersion=driver_version)
        with patch.dict(sys.modules, {'torch': torch}), patch.object(probe.ctypes, 'CDLL', return_value=driver), \
                patch('stemwise_engine.common.run_process_tree', side_effect=AssertionError('Spawned numerical probe child')), \
                patch('stemwise_engine.provenance.run_process_tree', side_effect=AssertionError('Spawned diagnostic child')):
            runtime = probe.torch_runtime_identity()
        self.assertEqual(runtime, {'device': 'cuda', 'torch': '2.7.0', 'cudaDriver': 13030,
            'cudaRuntime': '12.8', 'cudnn': '90000', 'uuid': 'GPU-physical-device',
            'name': 'Observed GPU', 'capability': [8, 9], 'totalMemoryBytes': 16*1024**3})
        torch.cuda.get_device_properties.assert_called_once_with(3)
        torch.cuda.get_device_capability.assert_called_once_with(3)

    def test_runtime_probe_retirement_failure_crosses_cli_and_parent_boundary(self):
        from stemwise_engine.common import run_process_tree, WorkerRetirementError, WORKER_RETIREMENT_FAILURE_MARKER
        script = '''
import runpy, sys
from types import SimpleNamespace
from unittest.mock import patch
from stemwise_engine.common import WorkerRetirementError
def available():
    raise WorkerRetirementError('Owned driver probe did not retire')
sys.argv = ['runtime-probe', '--torch-runtime']
with patch.dict(sys.modules, {'torch': SimpleNamespace(cuda=SimpleNamespace(is_available=available))}):
    runpy.run_module('stemwise_engine.worker_runtime_probe', run_name='__main__')
'''
        completed = run_process_tree([sys.executable, '-B', '-c', script], capture_output=True,
            text=True, check=False, timeout=30)
        self.assertNotEqual(completed.returncode, 0)
        self.assertEqual(completed.stdout, '')
        self.assertIn(WORKER_RETIREMENT_FAILURE_MARKER, completed.stderr.splitlines())
        self.assertIn('Owned driver probe did not retire', completed.stderr)
        with patch('stemwise_engine.common.run_process_tree', return_value=completed):
            with self.assertRaises(WorkerRetirementError) as raised:
                probe.selected_torch_runtime(PROJECT, 30)
        self.assertEqual(raised.exception.stderr, completed.stderr)
        for diagnostic in ('Ordinary probe failure', 'prefix-' + WORKER_RETIREMENT_FAILURE_MARKER):
            ordinary = SimpleNamespace(returncode=1, stdout='', stderr=diagnostic)
            with patch('stemwise_engine.common.run_process_tree', return_value=ordinary):
                with self.assertRaises(RuntimeError) as raised:
                    probe.selected_torch_runtime(PROJECT, 30)
            self.assertIs(type(raised.exception), RuntimeError)
        runtime = {'device': 'cuda', 'uuid': 'GPU-fixture'}
        success = SimpleNamespace(returncode=0, stdout=json.dumps(runtime), stderr=WORKER_RETIREMENT_FAILURE_MARKER)
        with patch('stemwise_engine.common.run_process_tree', return_value=success):
            self.assertEqual(probe.selected_torch_runtime(PROJECT, 30), runtime)

    def test_ctranslate2_observes_driver_ordinal_and_physical_identity(self):
        calls = []
        def native(name):
            def invoke(*args):
                calls.append(name)
                if name == 'cuDeviceGet':
                    self.assertEqual(args[1].value, 0)
                    args[0]._obj.value = 7
                elif name == 'cuDeviceGetName':
                    self.assertEqual(args[2].value, 7)
                    args[0].value = b'Observed GPU'
                elif name == 'cuDeviceGetUuid_v2':
                    args[0].raw = b'0123456789abcdef'
                elif name == 'cuDeviceGetAttribute':
                    args[0]._obj.value = 8 if args[1].value == 75 else 9
                elif name == 'cuDeviceTotalMem_v2':
                    args[0]._obj.value = 16*1024**3
                elif name == 'cuDriverGetVersion':
                    args[0]._obj.value = 12080
                return 0
            return invoke
        driver = SimpleNamespace(**{name: native(name) for name in ('cuInit', 'cuDeviceGet', 'cuDeviceGetName',
            'cuDeviceGetUuid_v2', 'cuDeviceGetAttribute', 'cuDeviceTotalMem_v2', 'cuDriverGetVersion')})
        with patch.object(probe.ctypes, 'CDLL', return_value=driver), \
                patch.dict(sys.modules, {'ctranslate2': SimpleNamespace(__version__='fixture', get_cuda_device_count=lambda: 1)}), \
                patch.object(probe, 'version', return_value='pinned'):
            value = probe.ctranslate2_runtime_identity('cuda')
            self.assertEqual(value['cudaDevice'], {'name': 'Observed GPU', 'capability': [8, 9],
                'totalMemoryBytes': 16*1024**3, 'uuid': b'0123456789abcdef'.hex()})
            self.assertEqual(value['cudaDriver'], 12080)
            self.assertIn('cuDeviceGetUuid_v2', calls)
            driver.cuDeviceGetUuid_v2 = lambda *_: 999
            with self.assertRaisesRegex(RuntimeError, 'observation failed'):
                probe.ctranslate2_runtime_identity('cuda')

    def test_every_whisper_variant_rejects_switched_and_unbound_producers(self):
        selected = {'cudaDevice': {'uuid': 'GPU-A'}}
        invocations = []
        def transcribe(*args, **options):
            invocations.append(selected['cudaDevice']['uuid'])
            raw = 'Alpha' if selected['cudaDevice']['uuid'] == 'GPU-A' else 'Beta'
            return [FixtureWord(raw.lower(), raw)], {'wordCount': 1}
        model = SimpleNamespace(transcribe=transcribe)
        original = {'id': 'original', 'kind': 'original', 'language': 'en', 'script': 'Latn',
            'format': 'plain', 'text': 'Approved text'}
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            audio = root/'source.wav'
            vocals = root/'vocals.wav'
            for path in (audio, vocals):
                with wave.open(str(path), 'wb') as output:
                    output.setparams((2, 2, 48000, 0, 'NONE', 'not compressed'))
                    output.writeframes(b'\0' * 48000 * 4)
            models = root/'models'
            checkpoint = models.parent/'torch/hub/checkpoints/955717e8-8726e21a.th'
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b'fixture checkpoint')
            with patch.object(probe, 'ctranslate2_runtime_identity', side_effect=lambda device: json.loads(json.dumps(selected))), \
                    patch.object(lyrics_alignment, 'WordTimestamp', FixtureWord), \
                    patch.object(align, '_whisper_recognition_cache_identity', return_value={'fixture': 'recognition-code-v1'}), \
                    patch.object(align, 'deduplicated_lyrics_prompt', return_value='Approved text'):
                for index, (separate, precision, prompted) in enumerate(itertools.product((False, True), repeat=3)):
                    with self.subTest(separate=separate, precision=precision, prompted=prompted):
                        selected['cudaDevice']['uuid'] = 'GPU-A'
                        workspace = AlignmentWorkspace(root/str(index))
                        arguments = dict(video_id='KbKqkR3Q6SA', original=original, workspace=workspace,
                            model_name='large-v3', model_cache=models, language='en', separate_vocals=separate,
                            project_dir=root, device='cuda', compute_type='float16', duration_ms=1000,
                            get_model=lambda: model, temp_root=root/'tmp', timeout_seconds=30, rerun=False,
                            precision=precision, use_approved_prompt=prompted, analysis_audio=audio,
                            vocals_provider=lambda: vocals)
                        first = align._load_or_transcribe(**arguments)
                        self.assertFalse(first[2])
                        before = len(invocations)
                        self.assertTrue(align._load_or_transcribe(**arguments)[2])
                        self.assertEqual(len(invocations), before)
                        selected['cudaDevice']['uuid'] = 'GPU-B'
                        changed = align._load_or_transcribe(**arguments)
                        self.assertFalse(changed[2])
                        self.assertNotEqual(changed[0], first[0])
                        self.assertTrue(align._load_or_transcribe(**arguments)[2])
                        cache = next(workspace.job_dir('KbKqkR3Q6SA').glob('transcript*.private.json'))
                        packet = json.loads(cache.read_bytes())
                        packet['provenance'].pop('producerRuntime')
                        cache.write_text(json.dumps(packet), encoding='utf-8')
                        self.assertFalse(align._load_or_transcribe(**arguments)[2])
                for route in ('kimberley-jensen-roformer-vocals', 'sucial-v2-roformer-dry-vocals'):
                    selected['cudaDevice']['uuid'] = 'GPU-A'
                    workspace = AlignmentWorkspace(root/route)
                    identity = audio_store.write_audio_provenance(workspace, 'KbKqkR3Q6SA', duration_ms=1000, mix_audio=audio)['mix']
                    stored = {'audioViews': {route: identity}}
                    arguments = dict(video_id='KbKqkR3Q6SA', original=original, route_id=route,
                        workspace=workspace, model_name='large-v3', model_cache=models, language='en', device='cuda',
                        compute_type='float16', duration_ms=1000, get_model=lambda: model,
                        get_audio=lambda: (audio, identity, {}), rerun=False, expected_mix_sha256=identity['sha256'])
                    with patch.object(audio_store, 'read_audio_provenance', return_value=stored):
                        first = align._load_or_transcribe_roformer(**arguments)
                        self.assertFalse(first[2])
                        self.assertTrue(align._load_or_transcribe_roformer(**arguments)[2])
                        selected['cudaDevice']['uuid'] = 'GPU-B'
                        changed = align._load_or_transcribe_roformer(**arguments)
                        self.assertFalse(changed[2])
                        self.assertNotEqual(changed[0], first[0])
                        self.assertTrue(align._load_or_transcribe_roformer(**arguments)[2])


if __name__ == '__main__':
    unittest.main()
