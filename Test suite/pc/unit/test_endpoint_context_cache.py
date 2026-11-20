"""Completed contexts survive interruption without repeating completed producers."""
import contextlib
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.endpoint_context_cache as cache
import stemwise_engine.endpoint_native_ctc as native
from stemwise_engine.endpoint_vocal_evidence import ROUTES


class EndpointContextCacheTests(unittest.TestCase):
    def setUp(self):
        active = patch('stemwise_engine.worker_runtime_probe.torch_runtime_identity', return_value={'cudaDevice': 'fixture'})
        active.start()
        self.addCleanup(active.stop)
    def test_benchmark_main_runs_fresh_serial_and_batch_ctc_with_equivalent_outputs(self):
        import importlib.util
        import io
        import subprocess
        import numpy as np
        import soundfile as sf
        import torch
        import transformers
        import stemwise_engine.endpoint_ctc_evidence as ctc
        spec = importlib.util.spec_from_file_location('benchmark_endpoint_loading',
            ROOT/'Test suite/pc/performance/benchmark_endpoint_loading.py')
        benchmark = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(benchmark)

        class Tokenizer:
            def __init__(self):
                self.encoder = {'<pad>': 0, '|': 1, '<unk>': 2,
                                **{c: i+3 for i, c in enumerate('HELOWRD')}}
                self.word_delimiter_token_id = 1
                self.unk_token_id = 2
                self.pad_token_id = 0
                self.all_special_ids = [0, 2]

            def get_vocab(self):
                return self.encoder

        class Model:
            config = SimpleNamespace(pad_token_id=0, vocab_size=10,
                                     conv_kernel=[320], conv_stride=[320])

            def to(self, device):
                self.assert_device = device
                return self

            def eval(self):
                return self

        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            project = root/'project'
            project.mkdir()
            from process_fixture import prepare_runtime_layout
            from stemwise_engine.runtime_environment import environment_python, environment_lease
            prepare_runtime_layout(project)
            layout_path = root/'runtime-layout.json'
            layout = json.loads(layout_path.read_bytes())
            layout['environments'].update(main='project/managed-main/bin/python3.10',
                lyrics='project/managed-lyrics/bin/python3.10')
            layout_path.write_text(json.dumps(layout), encoding='utf-8')
            model_path = root/'model'
            model_path.mkdir()
            audio = root/'audio.wav'
            signal = np.sin(np.arange(48000*24)*.017).astype(np.float32)
            sf.write(audio, np.column_stack((signal, signal*.7)), 48000, subtype='FLOAT')
            assets = {'modelPath': str(model_path), 'model': {'targetKind': 'grapheme', 'fixture': 'ctc'}}
            groups, model_loads, emissions = [], [], []

            def load_model(*args, **kwargs):
                model_loads.append(args[0])
                return Model()

            def model_emissions(waveform, extractor, model, device, dtype, stride):
                self.assertEqual(tuple(waveform.shape[:1]), (1,))
                self.assertEqual(str(device), 'cuda')
                self.assertEqual(stride, 320)
                emissions.append(int(waveform.shape[1]))
                return torch.full((400, 10), -np.log(10), dtype=torch.float64)

            def subprocess_boundary(argv, **kwargs):
                role = 'lyrics' if '_wav2vec2_model_assets' in argv[2] else 'main'
                self.assertEqual(Path(argv[0]), environment_python(role, project))
                with self.assertRaises(TimeoutError):
                    with environment_lease(role, builder=project, exclusive=True, wait_seconds=0):
                        pass
                if '_wav2vec2_model_assets' in argv[2]:
                    return subprocess.CompletedProcess(argv, 0, json.dumps(assets), '')
                groups.append((argv[4], tuple(argv[5:])))
                output = io.StringIO()
                # Execute the benchmark's actual worker program; substitute only
                # model assets/loading/emissions, retaining CTC and cache work.
                with patch.object(sys, 'argv', ['-c', *argv[3:]]), \
                        patch.object(transformers.AutoModelForCTC, 'from_pretrained', side_effect=load_model), \
                        patch.object(transformers.Wav2Vec2FeatureExtractor, 'from_pretrained',
                                     return_value=SimpleNamespace(sampling_rate=16000)), \
                        patch.object(transformers.Wav2Vec2CTCTokenizer, 'from_pretrained', side_effect=lambda *a, **k: Tokenizer()), \
                        patch.object(ctc, '_bounded_emissions', side_effect=model_emissions), \
                        contextlib.redirect_stdout(output):
                    exec(compile(argv[2], '<benchmark-worker>', 'exec'), {'__name__': '__main__'})
                return subprocess.CompletedProcess(argv, 0, output.getvalue(), '')

            def run(mode, work, reference=None):
                argv = ['benchmark', '--input', str(audio), '--work', str(work), '--mode', mode]
                if reference is not None:
                    argv += ['--reference', str(reference)]
                with patch.object(sys, 'argv', argv), patch.object(benchmark, 'PROJECT', project), \
                        patch.object(benchmark, 'run_process_tree', side_effect=subprocess_boundary), \
                        contextlib.redirect_stdout(io.StringIO()):
                    benchmark.main()

            run('serial', root/'runs')
            self.assertEqual([len(paths) for _, paths in groups], [1, 1, 1])
            self.assertEqual(len(model_loads), 3)
            self.assertEqual(len(emissions), 54)
            run('batch', root/'runs', root/'runs/serial.json')
            self.assertEqual([len(paths) for _, paths in groups], [1, 1, 1, 3])
            self.assertEqual(len(model_loads), 4)
            self.assertEqual(len(emissions), 108)
            serial = json.loads((root/'runs/serial.json').read_bytes())
            batch = json.loads((root/'runs/batch.json').read_bytes())
            self.assertEqual((serial['modelLoads'], batch['modelLoads']), (3, 1))
            self.assertEqual(serial['results'], batch['results'])
            self.assertEqual([len(row['records']) for row in batch['results']], [9, 9, 9])
            serial_paths, batch_paths = set(), set(groups[-1][1])
            for _, paths in groups[:3]:
                serial_paths.update(paths)
            self.assertTrue(serial_paths.isdisjoint(batch_paths))
            self.assertEqual(sum(len(list(Path(p).glob('inference-*.json'))) for p in serial_paths | batch_paths), 6)
            serial['results'][0]['targetCount'] += 1
            bad_reference = root/'bad-reference.json'
            bad_reference.write_text(json.dumps(serial), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'differ from the serial reference'):
                run('batch', root/'rejected', bad_reference)
            self.assertFalse((root/'rejected/batch.json').exists())

    def test_benchmark_contexts_bind_current_inputs_in_separate_fresh_directories(self):
        import importlib.util
        import numpy as np
        import soundfile as sf
        spec = importlib.util.spec_from_file_location('benchmark_endpoint_loading',
            ROOT/'Test suite/pc/performance/benchmark_endpoint_loading.py')
        benchmark = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(benchmark)
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            audio = root/'audio.wav'
            sf.write(audio, np.zeros((48000*8, 2), dtype=np.float32), 48000, subtype='FLOAT')
            model = {'fixture': 'numerical-reference'}
            serial = benchmark.prepare_contexts(audio, root/'serial', {'model': model}, 1, 'en')[0]
            batch = benchmark.prepare_contexts(audio, root/'batch', {'model': model}, 1, 'en')[0]
            self.assertEqual(cache.context_binding(serial, model), cache.context_binding(batch, model))
            self.assertNotEqual(serial, batch)
            with self.assertRaises(FileExistsError):
                benchmark.prepare_contexts(audio, root/'serial', {'model': model}, 1, 'en')
            serial_result = {'mode': 'serial', 'binding': {'model': model, 'inputSha256': 'a'*64},
                             'results': [{'records': [1, 2, 3]}]}
            result = {**serial_result, 'mode': 'batch'}
            benchmark.require_serial_reference(result, serial_result)
            for reference in ({**serial_result, 'mode': 'batch'},
                    {**serial_result, 'binding': {'inputSha256': 'b'*64}},
                    {**serial_result, 'results': [{'records': [3, 2, 1]}]}):
                with self.assertRaises(ValueError):
                    benchmark.require_serial_reference(result, reference)


    def test_owsm_prediction_identities_bind_devices_but_stored_validation_never_probes(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        device = {'name': 'Fixture CUDA accelerator', 'capability': [12, 0], 'totalMemoryBytes': 16*1024**3}
        with patch.object(owsm.worker, 'cuda_device_identity', return_value=device):
            first = owsm.numerical_model(owsm.MODEL)
        alternate = {**device, 'name': 'Another capable accelerator'}
        with patch.object(owsm.worker, 'cuda_device_identity', return_value=alternate):
            second = owsm.numerical_model(owsm.MODEL)
        self.assertNotEqual(first, second)
        with patch.object(owsm.worker, 'cuda_device_identity', side_effect=AssertionError('stored validation initialized CUDA')):
            self.assertEqual(owsm.validate_recorded_model(first, owsm.MODEL), first)
            self.assertEqual(owsm.validate_recorded_model(second, owsm.MODEL), second)
        with self.assertRaises(ValueError):
            owsm.validate_recorded_model(owsm.MODEL, owsm.MODEL)
        self.assertNotIn('cudaDevice', owsm.MODEL)
        self.assertNotIn('cudaDevice', owsm.PREDICTION_MODEL)
        self.assertNotIn('cudaDevice', owsm.UNCONDITIONED_PREDICTION_MODEL)
        with patch.object(owsm.worker, 'cuda_device_identity', side_effect=AssertionError('empty work initialized CUDA')):
            self.assertEqual(owsm._infer(Path('.'), [], 1), {})
            self.assertEqual(owsm.infer_contexts(Path('.'), []), {})

    def test_owsm_cache_does_not_relabel_another_device_and_preserves_retained_bytes(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        device = {'name': 'New capable accelerator', 'capability': [12, 0], 'totalMemoryBytes': 16*1024**3}
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            context = self.context(root, 0)
            cache.write_context_result(context, owsm.MODEL, {'model': owsm.MODEL, 'retained': 'old producer'})
            before = {path: path.read_bytes() for path in context.glob('inference-*.json')}
            model = {**owsm.MODEL, 'cudaDevice': device}
            result = {'0': {'model': model, 'retained': 'new producer'}}
            completed = SimpleNamespace(returncode=0, stdout=json.dumps(result))
            contexts = [{'index': 0, 'path': str(context), 'languages': ['en']}]
            with patch.object(owsm.worker, 'cuda_device_identity', return_value=device), \
                    patch.object(owsm, 'heavy_worker_stage', return_value=contextlib.nullcontext()), \
                    patch.object(owsm, 'run_process_tree', return_value=completed) as worker:
                self.assertEqual(owsm._infer(root, contexts, 1), result)
                worker.assert_called_once()
                cache.write_context_result(context, model, result['0'])
                worker.reset_mock()
                self.assertEqual(owsm._infer(root, contexts, 1), result)
                worker.assert_not_called()
                alternate = {**device, 'name': 'Another capable accelerator'}
                with patch.object(owsm.worker, 'cuda_device_identity', return_value=alternate):
                    with self.assertRaisesRegex(ValueError, 'device|model'):
                        owsm._infer(root, contexts, 1)
                self.assertEqual(worker.call_count, 1)
            self.assertEqual({path: path.read_bytes() for path in before}, before)



    def context(self, root, index):
        context = root / f'context-{index:03d}'
        context.mkdir()
        (context/'source.wav').write_bytes(b'exact crop')
        (context/'request.private.json').write_text(json.dumps({
            'language': 'en', 'startMs': 0, 'endMs': 1000, 'request': {'lines': []}}), encoding='utf-8')
        for route in ROUTES:
            (context/(route+'.wav')).write_bytes(b'exact retained stem')
        (context/'prepared.json').write_text(json.dumps({'views': {
            route: {'path': route+'.wav', 'provenance': {'sha256': hashlib.sha256(b'exact retained stem').hexdigest(),
                'byteCount': len(b'exact retained stem')}} for route in ROUTES}}), encoding='utf-8')
        return context

    def test_native_retry_binds_observed_device_and_only_dispatches_missing_contexts(self):
        device = {'name': 'Fixture CUDA accelerator', 'capability': [12, 0], 'totalMemoryBytes': 16*1024**3}
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name, patch.object(
                native.nfa, 'cuda_device_identity', return_value=device):
            root = Path(name)
            first, second = [self.context(root, i) for i in range(2)]
            model = native.numerical_model()
            first_result, second_result = [{'model': model, 'completed': i} for i in range(2)]
            cache.write_context_result(first, model, first_result)
            completed = SimpleNamespace(returncode=0, stdout=json.dumps([second_result]))
            with patch.object(native, 'heavy_worker_stage', return_value=contextlib.nullcontext()), \
                    patch.object(native, 'run_process_tree', return_value=completed) as worker:
                self.assertEqual(native._infer(root, [first, second], 1), [first_result, second_result])
                self.assertIn(str(second), worker.call_args.args[0])
                self.assertNotIn(str(first), worker.call_args.args[0])
                before = {path: path.read_bytes() for path in first.glob('inference-*.json')}
                alternate = {**device, 'name': 'Another capable accelerator'}
                with patch.object(native.nfa, 'cuda_device_identity', return_value=alternate):
                    # A result from the first worker cannot be relabelled for another GPU.
                    completed.stdout = json.dumps([first_result, second_result])
                    with self.assertRaisesRegex(ValueError, 'model or device'):
                        native._infer(root, [first, second], 1)
                self.assertEqual({path: path.read_bytes() for path in before}, before)
                (first/'source.wav').write_bytes(b'changed crop')
                completed.stdout = json.dumps([{**first_result, 'completed': 10}, second_result])
                self.assertEqual(native._infer(root, [first, second], 1)[0]['completed'], 10)
                self.assertIn(str(first), worker.call_args.args[0])
            self.assertIsNone(cache.read_context_result(first, model))

    def test_corrupt_selected_view_and_changed_preparation_cannot_relabel_completed_inference(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            context = self.context(Path(name), 0)
            before = cache.context_binding(context, native.MODEL)
            cache.write_context_result(context, native.MODEL, {'completed': True}, binding=before)
            self.assertEqual(cache.read_context_result(context, native.MODEL), {'completed': True})
            (context/(ROUTES[0]+'.wav')).write_bytes(b'changed stem')
            self.assertIsNone(cache.read_context_result(context, native.MODEL))
            with self.assertRaisesRegex(ValueError, 'missing or changed'):
                cache.context_binding(context, native.MODEL)
            (context/(ROUTES[0]+'.wav')).write_bytes(b'exact retained stem')
            (context/'source.wav').write_bytes(b'changed preparation during inference')
            with self.assertRaisesRegex(ValueError, 'inputs changed during inference'):
                cache.write_context_result(context, native.MODEL, {'completed': False}, binding=before)
            self.assertIsNone(cache.read_context_result(context, native.MODEL))
            model = {'runtime': {'version': 1}}
            binding = cache.context_binding(context, model)
            model['runtime']['version'] = 2
            with self.assertRaisesRegex(ValueError, 'inputs changed during inference'):
                cache.write_context_result(context, model, {'completed': True}, binding=binding)



if __name__ == '__main__':
    unittest.main()
