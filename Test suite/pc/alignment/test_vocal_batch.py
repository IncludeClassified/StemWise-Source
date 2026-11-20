"""Batch preparation preserves independent crops, deadlines and recovery."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout
import stemwise_engine.alignment_endpoints as endpoints
import stemwise_engine.endpoint_ctc_evidence as ctc
import stemwise_engine.endpoint_vocal_evidence as vocal


class VocalBatchTests(unittest.TestCase):
    def setUp(self):
        self.runtime = {'torch': 'fixture', 'cudaRuntime': '12.8', 'cudnn': 90000,
            'cudaDevice': {'name': 'GPU A', 'capability': [8, 9], 'totalMemoryBytes': 16*1024**3, 'uuid': 'a'*32}}
        active = patch('stemwise_engine.worker_runtime_probe.torch_runtime_identity', return_value=self.runtime)
        active.start()
        self.addCleanup(active.stop)
    def test_pcm_equivalent_reuse_rebinds_real_provenance_and_survives_prepared_interruption(self):
        import numpy as np
        import soundfile as sf
        import struct
        import torch
        import stemwise_engine.alignment_models as alignment_models
        import stemwise_engine.alignment_audio as alignment_audio
        import stemwise_engine.lyrics_align_pc as align
        import stemwise_engine.roformer_separation as roformer
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            job = Path(name)/'job'
            old = job/'endpoint-ctc-evidence/old/context-000'
            old.mkdir(parents=True)
            clip = old/'source.wav'
            samples = np.column_stack((np.sin(np.arange(4800)/20), np.cos(np.arange(4800)/17))).astype('float32')*.1
            sf.write(clip, samples, 48000, subtype='FLOAT')
            workspace = AlignmentWorkspace(old/'research', workspace_root=old)
            paths = {vocal.ROUTES[0]: old/'demucs/htdemucs/source/vocals.wav',
                     **{route: old/route/(route+'.wav') for route in vocal.ROUTES[1:]}}
            for path in paths.values():
                path.parent.mkdir(parents=True, exist_ok=True)
                sf.write(path, samples*.5, 48000, subtype='FLOAT')
            provenance = alignment_audio.write_audio_provenance(workspace, 'vocal-probe', duration_ms=100,
                mix_audio=clip, vocals_audio=paths[vocal.ROUTES[0]], demucs_model_sha256=alignment_models.PINNED_DEMUCS_SHA256,
                producer_runtime=self.runtime)
            pins = alignment_models.roformer_runtime_pins(Path(align.__file__).resolve().parent.parent)
            package = {'version': alignment_models.ROFORMER_PACKAGE_VERSION, 'wheelSha256': alignment_models.ROFORMER_PACKAGE_WHEEL_SHA256,
                       'sourceManifestSha256': alignment_models.ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256,
                       'sourceCommit': alignment_models.ROFORMER_PACKAGE_SOURCE_COMMIT}
            runtime = {'runtimeVersion': alignment_audio.ROFORMER_RESULT_VERSION, 'supportedResultVersions': [alignment_audio.ROFORMER_RESULT_VERSION],
                **pins, 'package': package, 'implementation': 'CPython', 'platform': 'Linux', 'machine': 'x86_64',
                'requestedDevice': 'cpu', 'resolvedDevice': 'cpu', 'cudaAvailable': False,
                'cudaDeviceName': None, 'cudaRuntime': None, 'cudnn': None}
            for route in vocal.ROUTES[1:]:
                definition = endpoints.ROFORMER_ROUTES[route]
                source = provenance['mix'] if definition['sourceViewId'] == 'mix' else provenance['audioViews'][definition['sourceViewId']]
                output = alignment_audio.file_identity(paths[route])
                reconstruction = roformer._chunk_reconstruction(SimpleNamespace(inference=SimpleNamespace(chunk_size=4800, num_overlap=2)), 4800)
                result = {'resultVersion': alignment_audio.ROFORMER_RESULT_VERSION, 'routeId': route,
                    'targetInstrument': 'vocals' if route == vocal.ROUTES[1] else 'dry', 'package': package,
                    'model': {key: definition[key] for key in ('checkpointSha256', 'configSha256')},
                    'input': {key: source[key] for key in ('sha256', 'byteCount', 'stream')},
                    'output': output, 'runtime': runtime, 'device': 'cpu',
                    'parameters': {'deterministicAlgorithms': True, 'cudnnBenchmark': False,
                        'cudnnDeterministic': True, 'modelSampleRate': 48000, **reconstruction},
                    'stageMetrics': dict.fromkeys(('preprocessingRuntimeMs', 'modelLoadRuntimeMs', 'inferenceRuntimeMs', 'writeRuntimeMs', 'runtimeMs'), 0),
                    'canonicalTimeTransform': roformer._time_transform(source['stream'], output['stream'], reconstruction)}
                provenance = alignment_audio.write_roformer_audio_provenance(workspace, 'vocal-probe', duration_ms=100,
                    expected_mix_sha256=align._sha256_file(clip), route_id=route, output_audio=paths[route],
                    result=result, helper_sha256=align.semantic_source_identity(Path(roformer.__file__)), producer_runtime=self.runtime)
            old_records = {route: {'path': str(paths[route].relative_to(old)), 'provenance': provenance['audioViews'][route]}
                           for route in vocal.ROUTES}
            (old/'prepared.json').write_text(json.dumps({'views': old_records}), encoding='utf-8')
            vocal._index_separation(clip, old)
            preserved = {path: path.read_bytes() for path in old.rglob('*') if path.is_file()}
            scratch = job/'endpoint-owsm-ctc/new/context-000'
            scratch.mkdir(parents=True)
            current = scratch/'source.wav'
            raw = bytearray(clip.read_bytes())
            peak = raw.find(b'PEAK', 0, 512)
            self.assertGreaterEqual(peak, 0)
            stamp = struct.unpack_from('<I', raw, peak+12)[0]
            struct.pack_into('<I', raw, peak+12, (stamp+1)&0xffffffff)
            current.write_bytes(raw)
            current_sha = align._sha256_file(current)
            self.assertNotEqual(current_sha, align._sha256_file(clip))
            with patch.object(alignment_models, 'require_pinned_file'), patch.dict(sys.modules, {'demucs': SimpleNamespace(separate=object())}), \
                    patch.object(roformer, 'run', side_effect=AssertionError('same PCM must not repeat models')):
                for _ in range(2):
                    result = vocal._separate_many_local({'projectDir': str(ROOT/'PC side/stemwise-playlist-builder')}, [(current, scratch)], 60)[0]
                    checked = alignment_audio.read_audio_provenance(AlignmentWorkspace(scratch/'research', workspace_root=scratch),
                        'vocal-probe', 100, expected_mix_sha256=current_sha)
                    self.assertIsNotNone(checked)
                    self.assertTrue((scratch/'vocal-worker.json').is_file())
                    self.assertFalse((scratch/'prepared.json').exists())
                    for route, (_, identity) in result.items():
                        source_sha = current_sha if identity['sourceViewId'] == 'mix' else result[identity['sourceViewId']][1]['sha256']
                        self.assertEqual(identity['sourceAudioSha256'], source_sha)
            self.assertEqual({path: path.read_bytes() for path in preserved}, preserved)
            import stemwise_engine.worker_runtime_probe as probe
            alternate = {**self.runtime, 'cudaDevice': {**self.runtime['cudaDevice'], 'uuid': 'b'*32}}
            with patch.object(probe, 'torch_runtime_identity', return_value=alternate):
                fresh = job/'endpoint-owsm-ctc/switched/context-000'
                fresh.mkdir(parents=True)
                fresh_source = fresh/'source.wav'
                fresh_source.write_bytes(current.read_bytes())
                self.assertIsNone(vocal._reuse_separation(fresh_source, fresh))
                self.assertEqual(list(fresh.iterdir()), [fresh_source])
                self.assertIsNone(alignment_audio.read_audio_provenance(workspace, 'vocal-probe', 100,
                    expected_producer_runtime=alternate))
                partial_path = AlignmentWorkspace(scratch/'research', workspace_root=scratch).audio_provenance_path('vocal-probe')
                partial = json.loads(partial_path.read_bytes())
                partial['audioViews'].pop(vocal.ROUTES[-1])
                partial_path.write_text(json.dumps(partial), encoding='utf-8')
                partial_bytes = partial_path.read_bytes()
                with patch.object(alignment_models, 'require_pinned_file'), \
                        patch.object(roformer, 'run', side_effect=AssertionError('Old-device partial recovery ran RoFormer')), patch.dict(sys.modules,
                        {'demucs': SimpleNamespace(separate=SimpleNamespace(
                            get_parser=lambda: SimpleNamespace(parse_args=lambda args: object()),
                            get_model_from_args=lambda args: (_ for _ in ()).throw(RuntimeError('new device inference'))))}):
                    with self.assertRaisesRegex(RuntimeError, 'new device inference'):
                        vocal._separate_many_local({'projectDir': str(ROOT/'PC side/stemwise-playlist-builder')},
                            [(current, scratch)], 60)
                self.assertEqual(partial_path.read_bytes(), partial_bytes)
            self.assertEqual({path: path.read_bytes() for path in preserved}, preserved)
            with patch.object(probe, 'torch_runtime_identity', side_effect=AssertionError('stored inspection initialized GPU')):
                self.assertIsNotNone(alignment_audio.read_audio_provenance(workspace, 'vocal-probe', 100))
            provenance_path = workspace.audio_provenance_path('vocal-probe')
            original_provenance = provenance_path.read_bytes()
            for malformed in (None, [], 'invalid'):
                packet = json.loads(original_provenance)
                packet['audioViews'][vocal.ROUTES[0]] = malformed
                provenance_path.write_text(json.dumps(packet), encoding='utf-8')
                self.assertIsNone(alignment_audio.read_audio_provenance(workspace, 'vocal-probe', 100,
                    expected_producer_runtime=self.runtime))
            packet = json.loads(original_provenance)
            for view in packet['audioViews'].values():
                view.pop('producerRuntime')
            provenance_path.write_text(json.dumps(packet), encoding='utf-8')
            self.assertIsNone(alignment_audio.read_audio_provenance(workspace, 'vocal-probe', 100,
                expected_producer_runtime=self.runtime))
            provenance_path.write_bytes(original_provenance)

    def test_single_context_uses_the_shared_main_worker_and_validates_real_outputs(self):
        import numpy as np
        import soundfile as sf
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            project = Path(name) / 'builder'
            prepare_runtime_layout(project)
            scratch = Path(name)
            clip = scratch / 'source.wav'
            samples = np.column_stack((np.arange(480), -np.arange(480))) / 1000
            sf.write(clip, samples, 48000, subtype='FLOAT')
            original = clip.read_bytes()
            records = {}
            for index, route in enumerate(vocal.ROUTES):
                path = scratch / f'{index}.wav'
                sf.write(path, samples * .5, 48000, subtype='FLOAT')
                records[route] = {'path': path.name, 'provenance': {
                    'sha256': vocal.sha256_file(path), 'stream': {'sampleRate': 48000},
                    'canonicalTimeTransform': {**dict.fromkeys(
                        ('offsetFrames', 'trimFrames', 'padFrames', 'separatorLatencyFrames'), 0),
                        'measuredResidualMs': 0}}}
            calls = []
            def completed(command, **kwargs):
                from stemwise_engine.common import heavy_worker_lock_path, process_lock_descriptor
                self.assertIsNotNone(process_lock_descriptor(heavy_worker_lock_path({'projectDir': str(project)})))
                self.assertEqual(Path(command[0]), project / '.venv/bin/python')
                self.assertIn('--separate-many', command)
                self.assertEqual(json.loads(kwargs['input']), [[str(clip), str(scratch)]])
                self.assertEqual(kwargs['timeout'], 120)
                calls.append(command)
                (scratch / 'vocal-worker.json').write_text(json.dumps(records))
                return SimpleNamespace(returncode=0, stdout='', stderr='')
            with patch.object(vocal, 'run_process_tree', side_effect=completed):
                outputs = vocal._separate({'projectDir': str(project), 'analysisTimeoutMinutes': 2}, clip, scratch)
            self.assertEqual(len(calls), 1)
            self.assertEqual(set(outputs), set(vocal.ROUTES))
            self.assertEqual(clip.read_bytes(), original)
            for path, _ in outputs.values():
                actual, rate = sf.read(path, always_2d=True)
                self.assertEqual(rate, 48000)
                np.testing.assert_array_equal(actual, samples.astype('float32') * .5)

    def test_single_ctc_preparation_uses_the_same_batch_admission(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            scratch = root / 'context'
            scratch.mkdir()
            (scratch / 'request.private.json').write_text('{"language":"en"}')
            model = {'snapshot': 'current'}
            selector = SimpleNamespace(_wav2vec2_model_assets=lambda *_: (root / 'model', model),
                PINNED_DEMUCS_SHA256='a'*64, _require_pinned_file=lambda *_: None,
                _read_audio_provenance=lambda *_args, **_kwargs: None)
            config = {'analysisTimeoutMinutes': 2, 'projectDir': str(root)}
            views = {r: (scratch / (r + '.wav'), {'sha256': r}) for r in vocal.ROUTES}
            with patch.dict(sys.modules, {'stemwise_engine.lyrics_align_pc': selector}), patch('stemwise_engine.alignment_models.require_pinned_file'), patch.object(
                    ctc, 'load_config', return_value=config), patch.object(
                    vocal, '_separate_many', return_value=[views]) as separate:
                ctc._prepare(root, scratch)
            separate.assert_called_once_with(config, [(scratch / 'source.wav', scratch)])
            self.assertEqual(json.loads((scratch / 'prepared.json').read_text()), {
                'modelPath': str(root / 'model'), 'model': model,
                'views': {r: {'path': r + '.wav', 'provenance': {'sha256': r}} for r in vocal.ROUTES}})

    def test_orchestration_validates_native_stems_without_resampling(self):
        import numpy as np
        import soundfile as sf
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            scratch = Path(name)
            project = scratch/'builder'
            prepare_runtime_layout(project)
            clip = scratch / 'source.wav'
            sf.write(clip, np.ones((480, 2)) * .1, 48000, subtype='FLOAT')
            records = {}
            for i, route in enumerate(vocal.ROUTES):
                path = scratch / f'{i}.wav'
                sf.write(path, np.ones((441, 2)) * .05, 44100, subtype='FLOAT')
                records[route] = {'path': path.name, 'provenance': {'sha256': vocal.sha256_file(path),
                    'stream': {'sampleRate': 44100}, 'canonicalTimeTransform': {
                        **dict.fromkeys(('offsetFrames','trimFrames','padFrames','separatorLatencyFrames'), 0), 'measuredResidualMs': 0}}}
            def completed(*_args, **_kw):
                (scratch / 'vocal-worker.json').write_text(json.dumps(records))
                return SimpleNamespace(returncode=0, stdout='', stderr='')
            guard = SimpleNamespace(_roformer_offline_env=lambda _: {})
            with patch.dict(sys.modules, {'stemwise_engine.lyrics_align_pc': guard}), patch.object(vocal, 'run_process_tree', side_effect=completed), \
                    patch('scipy.signal.resample_poly', side_effect=AssertionError('orchestration resampled PCM')):
                outputs = vocal._separate_many({'projectDir': str(project), 'analysisTimeoutMinutes': 1}, [(clip, scratch)])
            from stemwise_engine.common import WorkerRetirementError
            from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
            prior = {path: path.read_bytes() for path, _ in outputs[0].values()}
            for disposition, error in (('cancelled', ProviderCancelled), ('parent-deadline', ProviderDeadline),
                                       ('retirement-failed', WorkerRetirementError)):
                def stopped(*args, **kwargs):
                    completed(*args, **kwargs)
                    marker = 'STEMWISE_WORKER_FAILURE='+json.dumps({'formatVersion': 1,
                        'code': 'nested-stop', 'disposition': disposition})
                    return SimpleNamespace(returncode=0, stdout='', stderr=marker)
                with self.subTest(disposition=disposition), patch.object(vocal, 'run_process_tree', side_effect=stopped), \
                        self.assertRaises(error):
                    vocal._separate_many({'projectDir': str(project), 'analysisTimeoutMinutes': 1}, [(clip, scratch)])
                self.assertEqual({path: path.read_bytes() for path in prior}, prior)
            self.assertEqual(set(outputs[0]), set(vocal.ROUTES))
            self.assertEqual([sf.info(p).samplerate for p, _ in outputs[0].values()], [44100]*3)

    def test_success_exit_without_fresh_outputs_cannot_reuse_stale_result(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            scratch = Path(name)
            project = scratch/'builder'
            prepare_runtime_layout(project)
            clip = scratch / 'source.wav'
            clip.write_bytes(b'original crop')
            (scratch / 'vocal-worker.json').write_text('{}')
            guard = SimpleNamespace(_roformer_offline_env=lambda _: {})
            with patch.dict(sys.modules, {'stemwise_engine.lyrics_align_pc': guard}), patch.object(vocal, 'run_process_tree',
                    return_value=SimpleNamespace(returncode=0, stdout='', stderr='')):
                with self.assertRaises(ValueError):
                    vocal._separate_many({'projectDir': str(project), 'analysisTimeoutMinutes': 1}, [(clip, scratch)])
            self.assertEqual(clip.read_bytes(), b'original crop')
            self.assertFalse((scratch / 'vocal-worker.json').exists())

    def test_multilingual_contexts_batch_and_bind_every_crop_independently(self):
        import numpy as np
        import soundfile as sf
        import stemwise_engine.endpoint_owsm_ctc as owsm
        for incomplete in (False, True):
            with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
                root = Path(name)
                audio = root / 'source.wav'
                sf.write(audio, np.zeros((960, 2)), 48000, subtype='FLOAT')
                attempt = root / 'endpoint-review/attempt'
                attempt.mkdir(parents=True)
                plans = [{'language': 'ar', 'occurrenceId': str(i), 'startMs': i*10, 'endMs': i*10+10} for i in range(2)]
                seen = []
                def separate(_config, pairs):
                    seen.extend(pairs)
                    outputs = []
                    for clip, scratch in pairs[:1 if incomplete else 2]:
                        views = {}
                        for route in vocal.ROUTES:
                            output = scratch / (route + '.wav')
                            output.write_bytes(clip.read_bytes())
                            views[route] = (output, {'sha256': vocal.sha256_file(output),
                                                    'byteCount': output.stat().st_size})
                        outputs.append(views)
                    return outputs
                with patch.object(vocal, '_separate_many', side_effect=separate):
                    if incomplete:
                        with self.assertRaisesRegex(ValueError, 'incomplete'):
                            owsm._contexts(attempt, plans, [['ar'], ['ar']], audio, {})
                        self.assertFalse(list(root.glob('endpoint-owsm-ctc/*/context-*/prepared.json')))
                        continue
                    contexts = owsm._contexts(attempt, plans, [['ar'], ['ar']], audio, {})
                self.assertEqual(len(seen), 2)
                self.assertEqual([c['index'] for c in contexts], [0, 1])
                self.assertNotEqual(contexts[0]['inputs']['request.private.json'], contexts[1]['inputs']['request.private.json'])
                for context in contexts:
                    path = Path(context['path'])
                    self.assertEqual(context['inputs'], {n: vocal.sha256_file(path / n) for n in
                                                       ('source.wav', 'prepared.json', 'request.private.json')})

    def test_preparation_revalidates_retained_contexts_and_refreshes_model(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            contexts = [root / str(i) for i in range(3)]
            for i, context in enumerate(contexts):
                context.mkdir()
                (context / 'request.private.json').write_text(json.dumps({'language': 'en' if i != 1 else 'ar'}))
                (context / 'source.wav').write_bytes(bytes([i]))
            views = {r: {'path': r + '.wav', 'provenance': {'sha256': 'retained'}} for r in vocal.ROUTES}
            (contexts[0] / 'prepared.json').write_text(json.dumps({'views': views, 'modelPath': 'old'}))
            seen = []
            def separate(config, pairs):
                seen.extend(pairs)
                return [{r: (p / (r + '.wav'), {'sha256': str(i)}) for r in vocal.ROUTES}
                        for i, (_, p) in enumerate(pairs)]
            assets = SimpleNamespace(_wav2vec2_model_assets=lambda config, language:
                                     (root / language, {'language': language}))
            with patch.dict(sys.modules, {'stemwise_engine.lyrics_align_pc': assets}), patch.object(ctc, 'load_config', return_value={}), \
                    patch.object(vocal, '_separate_many', side_effect=separate):
                ctc._prepare_many(root, contexts)
            self.assertEqual(seen, [(p / 'source.wav', p) for p in contexts])
            records = [json.loads((p / 'prepared.json').read_bytes()) for p in contexts]
            self.assertEqual([r['modelPath'] for r in records], [str(root / v) for v in ('en', 'ar', 'en')])
            self.assertEqual([r['views'][vocal.ROUTES[0]]['provenance']['sha256'] for r in records], ['0', '1', '2'])

    def test_preparation_failure_cannot_publish_partial_prepared_state(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            context = root / 'context-000'
            context.mkdir()
            (context / 'request.private.json').write_text('{"language":"en"}')
            review = root / 'review.json'
            review.write_bytes(b'pending')
            assets = SimpleNamespace(_wav2vec2_model_assets=lambda *_: (root, {}))
            for outputs in ([], [{}]):
                with patch.dict(sys.modules, {'stemwise_engine.lyrics_align_pc': assets}), patch.object(ctc, 'load_config', return_value={}), \
                        patch.object(vocal, '_separate_many', return_value=outputs):
                    with self.assertRaises(ValueError):
                        ctc._prepare_many(root, [context])
                self.assertFalse((context / 'prepared.json').exists())
                self.assertEqual(review.read_bytes(), b'pending')

    def test_batch_rejects_duplicate_contexts_and_invalid_deadline(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            p = Path(name)
            for contexts, timeout in (([], 1), ([p, p], 1), ([p], 0), ([p], True)):
                with self.assertRaises(ValueError):
                    vocal._separate_many_local({'projectDir': name}, [(c / 'source.wav', c) for c in contexts], timeout)

    def test_worker_preserves_separator_inputs_and_recovers_completed_stages(self):
        import numpy as np
        import soundfile as sf
        import signal
        import torch
        import stemwise_engine.roformer_separation as roformer_separation
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            pairs = [(root / str(i) / 'source.wav', root / str(i)) for i in range(2)]
            for i, (clip, scratch) in enumerate(pairs):
                scratch.mkdir()
                sf.write(clip, np.column_stack((np.sin(np.arange(480)/20), np.cos(np.arange(480)/17))) * (.1+i*.1),
                         48000, subtype='FLOAT')
            def identity(path):
                info = sf.info(path)
                return {'sha256': vocal.sha256_file(path), 'byteCount': path.stat().st_size,
                    'stream': {'sampleRate': info.samplerate, 'channelCount': info.channels, 'frameCount': info.frames, 'durationMs': 10},
                    'canonicalTimeTransform': {**dict.fromkeys(('offsetFrames','trimFrames','padFrames','separatorLatencyFrames'), 0), 'measuredResidualMs': 0}}
            def read(workspace, *_args, **_kwargs):
                path = workspace.audio_provenance_path('vocal-probe')
                return json.loads(path.read_bytes()) if path.exists() else None
            def write(workspace, *_args, **kw):
                value = {'mix': identity(kw['mix_audio']), 'audioViews': {vocal.ROUTES[0]: identity(kw['vocals_audio'])}}
                value['audioViews'][vocal.ROUTES[0]]['demucsModelSha256'] = 'pinned'
                workspace.audio_provenance_path('vocal-probe').write_text(json.dumps(value))
                return value
            calls, loads = [], []
            def write_roformer(workspace, *_args, **kw):
                value = read(workspace)
                value['audioViews'][kw['route_id']] = identity(kw['output_audio'])
                workspace.audio_provenance_path('vocal-probe').write_text(json.dumps(value))
                return value
            guard = SimpleNamespace(PINNED_DEMUCS_SHA256='pinned', _require_pinned_file=lambda *_: None,
                _audio_stream=lambda p: identity(p)['stream'], _read_audio_provenance=read,
                _write_audio_provenance=write, _write_roformer_audio_provenance=write_roformer,
                _roformer_assets=lambda *_: {'checkpoint': root, 'config': root, 'checkpointSha256': 'a'*64, 'configSha256': 'b'*64})
            model = SimpleNamespace(audio_channels=2, samplerate=48000, sources=['vocals'])
            model.cpu = model.eval = lambda: model
            def load(*_):
                loads.append('htdemucs')
                return model
            demucs = SimpleNamespace(get_parser=__import__('argparse').ArgumentParser(), get_model_from_args=load,
                load_track=lambda p, *_: torch.from_numpy(sf.read(p, dtype='float32', always_2d=True)[0].T.copy()),
                apply_model=lambda _model, wav, **_kw: wav[:, None] * .5,
                save_audio=lambda samples, path, **_kw: sf.write(path, samples.numpy().T, 48000, subtype='PCM_16'))
            demucs.get_parser = lambda: SimpleNamespace(parse_args=lambda _: SimpleNamespace(
                device='cuda', shifts=0, split=True, overlap=.25, jobs=0, segment=None,
                mp3_bitrate=320, mp3_preset=2, clip_mode='rescale', float32=False, int24=False))
            fail = [True]
            def run(arguments, *, model_cache):
                if not model_cache:
                    loads.append(arguments.route)
                    model_cache[arguments.route] = object()
                calls.append((arguments.route, arguments.audio))
                if arguments.route == vocal.ROUTES[2] and arguments.output.is_relative_to(pairs[1][1]) and fail[0]:
                    raise TimeoutError('context deadline')
                samples, rate = sf.read(arguments.audio, always_2d=True)
                sf.write(arguments.output, samples * .5, rate, subtype='FLOAT')
                arguments.result.write_text('{}')
            handler = signal.getsignal(signal.SIGALRM)
            with patch.object(torch.cuda, 'empty_cache'), \
                    patch.dict(sys.modules, {'demucs': SimpleNamespace(separate=demucs)}), \
                    patch('stemwise_engine.alignment_models.PINNED_DEMUCS_SHA256', 'pinned'), \
                    patch('stemwise_engine.alignment_models.require_pinned_file'), \
                    patch('stemwise_engine.alignment_models.roformer_assets', side_effect=guard._roformer_assets), \
                    patch('stemwise_engine.alignment_audio.audio_stream', side_effect=guard._audio_stream), \
                    patch('stemwise_engine.alignment_audio.read_audio_provenance', side_effect=read), \
                    patch('stemwise_engine.alignment_audio.write_audio_provenance', side_effect=write), \
                    patch('stemwise_engine.alignment_audio.write_roformer_audio_provenance', side_effect=write_roformer), \
                    patch.object(vocal, '_reuse_separation', return_value=None), patch.object(roformer_separation, 'run', side_effect=run):
                with self.assertRaisesRegex(TimeoutError, 'context deadline'):
                    vocal._separate_many_local({'projectDir': str(root)}, pairs, 60)
                self.assertEqual(signal.getsignal(signal.SIGALRM), handler)
                self.assertTrue((pairs[0][1] / 'vocal-worker.json').exists())
                self.assertFalse((pairs[1][1] / 'vocal-worker.json').exists())
                prior = (pairs[0][1] / vocal.ROUTES[2] / (vocal.ROUTES[2]+'.wav')).read_bytes()
                self.assertEqual(loads, ['htdemucs', vocal.ROUTES[1], vocal.ROUTES[2]])
                fail[0] = False
                output = vocal._separate_many_local({'projectDir': str(root)}, pairs, 60)
                self.assertEqual(loads, ['htdemucs', vocal.ROUTES[1], vocal.ROUTES[2], vocal.ROUTES[2]])
                fresh = [(root / f'fresh-{i}' / 'source.wav', root / f'fresh-{i}') for i in range(2)]
                for (clip, scratch), (old_clip, _) in zip(fresh, pairs):
                    scratch.mkdir()
                    clip.write_bytes(old_clip.read_bytes())
                before = len(calls)
                clock = iter(range(0, 1000, 5))
                with patch('time.monotonic', side_effect=lambda: next(clock)):
                    with self.assertRaisesRegex(TimeoutError, 'exceeded its deadline'):
                        vocal._separate_many_local({'projectDir': str(root)}, fresh, 9)
                # Loading plus the first stage consumed the first crop's budget;
                # Kim cannot receive a fresh deadline or emit any result.
                self.assertEqual(len(calls), before)
                self.assertEqual(signal.getsignal(signal.SIGALRM), handler)
                self.assertFalse(any((s / 'vocal-worker.json').exists() for _, s in fresh))
            self.assertEqual([path for route, path in calls if route == vocal.ROUTES[1]], [p for p, _ in pairs])
            self.assertTrue(all(path.name == vocal.ROUTES[1]+'.wav' for route, path in calls if route == vocal.ROUTES[2]))
            self.assertEqual(output[0][vocal.ROUTES[2]][0].read_bytes(), prior)
            for (clip, _), views in zip(pairs, output):
                original, rate = sf.read(clip, always_2d=True)
                actual, ar = sf.read(views[vocal.ROUTES[2]][0], always_2d=True)
                self.assertEqual(ar, rate)
                np.testing.assert_array_equal(actual, original * .25)
                demucs_samples, _ = sf.read(views[vocal.ROUTES[0]][0], always_2d=True)
                expected = np.floor((original * .5 + original.mean() * .5) * 32768) / 32768
                np.testing.assert_array_equal(demucs_samples, expected)



if __name__ == '__main__':
    unittest.main()
