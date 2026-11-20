"""Resource admission uses real orchestration and substitutes only expensive producers."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.analysis_resources as resources
import stemwise_engine.analysis_numerics as analysis_numerics
import stemwise_engine.analyzers as analyzers


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class AnalysisResourcesTests(unittest.TestCase):
    def test_feasible_producers_still_run_on_both_sides_of_ten_minutes(self):
        for duration in (600000, 600001):
            with self.subTest(duration=duration), \
                    patch.object(resources, 'available_memory_bytes', return_value=64*1024**3), \
                    patch.object(analysis_numerics, 'analyze_beat_this', return_value=(
                        [0, 500, 1000], [0], {'name': 'beat-this-full', 'status': 'complete'})) as beat, \
                    patch.object(analysis_numerics, 'analyze_skey', return_value=(
                        {'status': 'uncertain', 'tonic': 'C', 'mode': 'major', 'reason': 'Uncalibrated'},
                        {'name': 's-key', 'status': 'complete'})) as key:
                result = analysis_numerics._gpu_analysis(Path('substituted-model.wav'), duration)
            beat.assert_called_once()
            key.assert_called_once()
            self.assertEqual(result[-1]['status'], 'complete')
            self.assertEqual(result[2]['tonic'], 'C')

    def test_failed_essentia_retry_does_not_invalidate_retained_values_after_another_repair(self):
        from test_packages import song_document
        from stemwise_engine.contract import _validate_analysis
        prior = song_document('abcdefghijk')['analysis']
        prior['tuning'] = {'status': 'uncertain', 'frequencyHz': 441., 'centsFromA440': 3.93,
            'reason': 'Intrinsic dispersion'}
        prior['toolRuns'] = [({'name': 'essentia', 'status': 'complete', 'version': 'fixture'}
            if row['name'] == 'essentia' else {'name': 's-key', 'status': 'failed', 'version': 'fixture', 'reason': 'Transient'}
            if row['name'] == 's-key' else row) for row in prior['toolRuns']]
        with patch.object(analysis_numerics, 'analyze_skey', return_value=(
                {'status': 'uncertain', 'tonic': 'C', 'mode': 'major', 'reason': 'Uncalibrated'},
                {'name': 's-key', 'status': 'complete', 'version': 'fixture'})), \
                patch.object(analysis_numerics, 'analyze_essentia', return_value=(
                    {'status': 'unavailable', 'reason': 'RuntimeError'}, {'status': 'unavailable', 'reason': 'RuntimeError'},
                    {'name': 'essentia', 'status': 'failed', 'version': 'fixture', 'reason': 'RuntimeError'})):
            result = analysis_numerics._repair_analysis_worker(Path('fixture.wav'), 1000, ROOT, 30, prior)
        self.assertEqual(result['tuning'], prior['tuning'])
        self.assertEqual(result['loudness'], prior['loudness'])
        self.assertEqual(next(row for row in result['toolRuns'] if row['name'] == 'essentia')['status'], 'complete')
        _validate_analysis(result, '$.analysis')

    def test_essentia_repair_targets_unavailable_features_and_preserves_independent_values(self):
        from test_packages import song_document
        for missing in (('tuning',), ('loudness',), ('tuning', 'loudness')):
            with self.subTest(missing=missing):
                prior = song_document('abcdefghijk')['analysis']
                prior['tuning'] = {'status': 'uncertain', 'frequencyHz': 441., 'centsFromA440': 3.93,
                    'reason': 'Estimate dispersion'}
                prior['loudness'] = {'status': 'complete', 'integratedLufs': -18., 'loudnessRangeLu': 2.}
                for name in missing:
                    prior[name] = {'status': 'unavailable', 'reason': 'RuntimeError'}
                for row in prior['toolRuns']:
                    if row['name'] == 'essentia':
                        row.update(status='complete', version='fixture')
                        row.pop('reason', None)
                self.assertEqual(analysis_numerics.repair_components(prior, 1000), {'essentia'})
                repaired = ({'status': 'complete', 'frequencyHz': 440., 'centsFromA440': 0.},
                    {'status': 'complete', 'integratedLufs': -20., 'loudnessRangeLu': 3.},
                    {'name': 'essentia', 'status': 'complete', 'version': 'fixture'})
                with patch.object(analysis_numerics, 'analyze_essentia', return_value=repaired):
                    result = analysis_numerics._repair_analysis_worker(Path('fixture.wav'), 1000, ROOT, 30, prior)
                for index, name in enumerate(('tuning', 'loudness')):
                    self.assertEqual(result[name], repaired[index] if name in missing else prior[name])
                self.assertEqual(analysis_numerics.repair_components(result, 1000), set())
        result['tuning'] = {'status': 'unavailable', 'reason': 'tuning-insufficient-pitched-frames'}
        self.assertEqual(analysis_numerics.repair_components(result, 1000), set())

    def test_windowed_essentia_both_feature_failures_have_failed_tool_status(self):
        from types import ModuleType, SimpleNamespace
        from test_packages import song_document
        import soundfile
        es, essentia = ModuleType('essentia.standard'), ModuleType('essentia')
        essentia.standard = es
        with patch.dict(sys.modules, {'essentia': essentia, 'essentia.standard': es}), \
                patch.object(soundfile, 'info', return_value=SimpleNamespace(frames=48000*601, samplerate=48000)), \
                patch.object(resources, 'require_analysis_capacity', return_value={'fullWindowAdmitted': False}), \
                patch.object(analyzers, '_stream_loudness', side_effect=ValueError('isolated loudness fault')):
            tuning, loudness, tool = analyzers.analyze_essentia(Path('fixture.wav'))
        self.assertEqual((tuning['status'], loudness['status'], tool['status']), ('unavailable', 'unavailable', 'failed'))
        prior = song_document('abcdefghijk')['analysis']
        prior.update(tuning=tuning, loudness=loudness)
        tool = json.loads(json.dumps(tool))
        prior['toolRuns'] = [tool if row['name'] == 'essentia' else row for row in prior['toolRuns']]
        self.assertEqual(analysis_numerics.repair_components(prior, 1000), {'essentia'})

    def test_repair_preserves_recovered_components_when_later_waveform_fails(self):
        import copy
        from test_packages import song_document
        from stemwise_engine.contract import _validate_analysis
        from stemwise_engine.private_diagnostics import analysis_diagnostics, harvest_analysis_causes
        prior = song_document('abcdefghijk')['analysis']
        prior['tuning'] = {'status': 'uncertain', 'frequencyHz': 440., 'centsFromA440': 0.,
            'reason': 'Intrinsic estimate dispersion'}
        prior['loudness'] = {'status': 'complete', 'integratedLufs': -18., 'loudnessRangeLu': 2.}
        prior['waveform'] = {'status': 'unavailable', 'reason': 'Transient read failure'}
        runs = {row['name']: row for row in prior['toolRuns']}
        runs['beat-this-full'] = {'name': 'beat-this-full', 'status': 'failed', 'version': '1', 'reason': 'transient'}
        runs['stemwise-waveform'] = {'name': 'stemwise-waveform', 'status': 'failed', 'version': '1', 'reason': 'transient'}
        runs['essentia'] = {'name': 'essentia', 'status': 'complete', 'version': '1'}
        prior['toolRuns'] = list(runs.values())
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            with patch.object(analysis_numerics, 'analyze_beat_this', return_value=(
                    [0, 400, 800], [0], {'name': 'beat-this-full', 'status': 'complete', 'version': '1'})), \
                    patch.object(analysis_numerics, 'build_waveform', side_effect=ValueError('later read failure')), \
                    patch.object(analysis_numerics, 'analyze_essentia', side_effect=AssertionError('uncertain estimate reran')):
                result = analysis_numerics._repair_analysis_worker(root/'song.wav', 1000, root, 30, prior)
            with analysis_diagnostics(root/'causes', workspace_root=root):
                harvest_analysis_causes(result)
            from stemwise_engine.common import json_bytes
            from stemwise_engine.worker_protocol import parse_worker_json
            _validate_analysis(parse_worker_json(json_bytes(result)), '$.analysis')
            self.assertEqual(result['rhythm']['bpm'], 150.)
            self.assertEqual(result['tuning'], prior['tuning'])
            self.assertEqual(result['waveform']['status'], 'unavailable')
        limited = copy.deepcopy(prior)
        limited['durationMs'] = 600001
        limited['toolRuns'] = [{'name': name, 'status': 'skipped',
            'reason': ('analysis-full-window-budget-exceeded' if name != 'stemwise-waveform' else 'Not requested')}
            if name != 'essentia' else runs['essentia'] for name in runs]
        with patch.object(resources, 'available_memory_bytes', return_value=64*1024**3):
            self.assertEqual(analysis_numerics.repair_components(limited, 600001),
                {'beat-this-full', 'stemwise-rhythm-normalizer', 's-key', 'all-in-one'})
            limited['durationMs'] = 1000
            self.assertEqual(analysis_numerics.repair_components(limited, 1000),
                {'beat-this-full', 'stemwise-rhythm-normalizer', 's-key', 'all-in-one'})

    def test_full_recording_admission_preserves_independent_results_without_model_calls(self):
        cpu = ({'status': 'complete', 'frequencyHz': 440.0, 'centsFromA440': 0.0},
               {'status': 'complete', 'integratedLufs': -18.0, 'loudnessRangeLu': 2.0},
               {'name': 'essentia', 'status': 'complete', 'version': 'fixture', 'profile': 'stemwise-v2-windowed'})
        with patch.object(resources, 'available_memory_bytes', return_value=1024**3), \
                patch.object(analysis_numerics, 'analyze_beat_this', side_effect=AssertionError('oversize Beat This')), \
                patch.object(analysis_numerics, 'analyze_skey', side_effect=AssertionError('oversize S-KEY')), \
                patch.object(analysis_numerics, 'analyze_essentia', return_value=cpu), \
                patch.object(analyzers, 'run_process_tree', side_effect=AssertionError('oversize All-In-One')), \
                patch.object(analysis_numerics, 'build_waveform', side_effect=ValueError('fixture has no waveform')):
            result = analysis_numerics._analysis_worker(Path('never-decoded.wav'), 7200000, ROOT, 30)
        self.assertEqual(result['status'], 'partial')
        self.assertEqual(result['tuning'], cpu[0])
        self.assertEqual(result['loudness'], cpu[1])
        self.assertEqual(result['rhythm']['reason'], 'analysis-full-window-budget-exceeded')
        self.assertEqual(result['structure']['reason'], 'analysis-full-window-budget-exceeded')
        import stemwise_engine.contract as contract
        from stemwise_engine.private_diagnostics import analysis_diagnostics, harvest_analysis_causes
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            with analysis_diagnostics(root/'causes', workspace_root=root):
                harvest_analysis_causes(result)
            from stemwise_engine.common import json_bytes
            from stemwise_engine.worker_protocol import parse_worker_json
            contract._validate_analysis(parse_worker_json(json_bytes(result)), '$.analysis')

    def test_long_essentia_uses_bounded_frames_and_global_ebu_instead_of_full_decoders(self):
        import numpy as np
        import soundfile as sf
        from types import ModuleType, SimpleNamespace
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            audio = Path(name)/'song.wav'
            t = np.arange(44100)/44100
            signal = (.2*(np.sin(2*np.pi*440*t)+.3*np.sin(2*np.pi*880*t))).astype('float32')
            sf.write(audio, np.column_stack((signal, signal)), 44100, subtype='FLOAT')
            info = type('Info', (), {'frames': 7200*48000, 'samplerate': 48000})()
            # Substitute optional DSP and external ffmpeg; keep bounded decoding and EBU routing real.
            es = ModuleType('essentia.standard')
            essentia = ModuleType('essentia')
            essentia.standard = es
            es.MonoLoader = Mock(side_effect=AssertionError('full mono decode'))
            es.AudioLoader = Mock(side_effect=AssertionError('full stereo decode'))
            es.FrameGenerator = Mock(return_value=[signal[:4096]]*8)
            es.Windowing = Mock(return_value=lambda frame: frame)
            es.Spectrum = Mock(return_value=lambda frame: frame)
            es.SpectralPeaks = Mock(return_value=lambda spectrum: ([440., 880.], [1., .3]))
            es.TuningFrequency = Mock(return_value=Mock(return_value=(440., 0.)))
            def ffmpeg(command, **kwargs):
                if '-filter:a' in command:
                    return SimpleNamespace(stderr='Summary:\nIntegrated loudness:\n I: -18.0 LUFS\nLoudness range:\n LRA: 2.0 LU\n')
                return SimpleNamespace(stdout=signal.tobytes())
            with patch.dict(sys.modules, {'essentia': essentia, 'essentia.standard': es}), \
                    patch.object(resources, 'available_memory_bytes', return_value=1024**3), \
                    patch.object(sf, 'info', return_value=info), \
                    patch.object(analyzers, 'run_process_tree', side_effect=ffmpeg) as processes:
                tuning, loudness, tool = analyzers.analyze_essentia(audio)
            commands = [call.args[0] for call in processes.call_args_list]
            decodes = [command for command in commands if '-t' in command]
            self.assertEqual(len(decodes), 240)
            self.assertTrue(all(float(command[command.index('-t')+1]) <= 30 for command in decodes))
            self.assertEqual(sum(float(command[command.index('-t')+1]) for command in decodes), 7200)
            self.assertEqual(decodes[0][decodes[0].index('-ss')+1], '0.0')
            self.assertEqual(decodes[-1][decodes[-1].index('-ss')+1], '7170.0')
            ebu = [command for command in commands if '-filter:a' in command]
            self.assertEqual(len(ebu), 1)
            self.assertEqual(ebu[0][ebu[0].index('-filter:a')+1], 'ebur128=framelog=verbose')
            es.MonoLoader.assert_not_called()
            es.AudioLoader.assert_not_called()
            self.assertEqual(tuning['status'], 'complete')
            self.assertAlmostEqual(tuning['frequencyHz'], 440, delta=2)
            self.assertEqual(loudness, {'status': 'complete', 'integratedLufs': -18., 'loudnessRangeLu': 2.})
            self.assertNotIn('descriptors', loudness)
            self.assertEqual(tool['profile'], 'stemwise-v2-windowed')

    def test_insufficient_policy_budget_rejects_before_native_decode_and_preserves_original(self):
        from types import SimpleNamespace
        from stemwise_engine.recording_source import RecordingSource
        from stemwise_engine.pipeline import StemwisePipeline
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            source = Path(name)/'original.flac'
            source.write_bytes(b'unchanged selected original')
            selected = SimpleNamespace(identity={'durationMs': 180000}, reference_ids=['AAAAAAAAAAA'], audio=source)
            pipeline = StemwisePipeline.__new__(StemwisePipeline)
            pipeline.config = {'localRecordingKey': 'qobuz:1'}
            pipeline.source = RecordingSource(pipeline.config, selected)
            with patch('stemwise_engine.recording_source.selection', return_value=selected), \
                    patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=1), \
                    patch('stemwise_engine.recording_source.decode_analysis_audio', side_effect=AssertionError('decoded before admission')) as decoder:
                with self.assertRaisesRegex(resources.AcousticBudgetExceeded, 'analysis-streaming-memory-unavailable'):
                    pipeline._acquire_analysis_audio('AAAAAAAAAAA', Path(name))
                decoder.assert_not_called()
            self.assertEqual(source.read_bytes(), b'unchanged selected original')

    def test_insufficient_budget_rejects_normal_pipeline_before_audio_acquisition(self):
        import stemwise_engine.pipeline as module
        from test_packages import package_config, song_document
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            config = package_config(root)
            config['projectDir'] = str(root/'PC side/stemwise-playlist-builder')
            owner = module.StemwisePipeline(config)
            owner.provenance_sha256 = 'a'*64
            video = 'AAAAAAAAAAA'
            metadata = song_document(video)['metadata']
            preserved = owner.lyrics_input_dir/'unrelated-owner-input.txt'
            preserved.parent.mkdir(parents=True)
            preserved.write_bytes(b'preserved input')
            with patch.object(owner, '_ensure_provenance'), \
                    patch.object(owner, '_song_info', return_value={'id': video, 'duration': 180.}), \
                    patch.object(owner, '_metadata', return_value=metadata), \
                    patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=1), \
                    patch.object(owner.source, 'acquire_analysis_audio', side_effect=AssertionError('acquired before capacity admission')) as download:
                self.assertEqual(owner.process_song({'videoId': video, 'title': 'Song'}), 'failed')
                download.assert_not_called()
            self.assertEqual(preserved.read_bytes(), b'preserved input')
            checkpoint = owner.load_checkpoint(video)
            self.assertEqual(checkpoint['status'], 'failed')
            self.assertEqual(checkpoint['error'], 'AcousticBudgetExceeded')
            self.assertFalse(owner.child_path(video).exists())

    def test_normal_essentia_releases_mono_pcm_before_stereo_decode(self):
        import gc
        import weakref
        import numpy as np
        import soundfile as sf
        from types import ModuleType, SimpleNamespace
        from unittest.mock import Mock
        es, essentia = ModuleType('essentia.standard'), ModuleType('essentia')
        essentia.standard = es
        references = []
        def mono():
            samples = np.ones(16384, dtype='float32')
            references.append(weakref.ref(samples))
            return samples
        def stereo():
            gc.collect()
            self.assertIsNone(references[0](), 'mono PCM overlaps the stereo allocation')
            return np.ones((16384, 2), dtype='float32'), 44100
        es.MonoLoader = Mock(return_value=mono)
        es.FrameGenerator = lambda samples, **kwargs: (samples[:4096] for _ in range(8))
        es.Windowing = Mock(return_value=lambda frame: frame)
        es.Spectrum = Mock(return_value=lambda frame: frame)
        es.SpectralPeaks = Mock(return_value=lambda frame: ([440., 880.], [1., .3]))
        es.TuningFrequency = Mock(return_value=lambda *args: (440., 0.))
        es.DynamicComplexity = Mock(return_value=lambda samples: (1., 0.))
        es.AudioLoader = Mock(return_value=stereo)
        es.LoudnessEBUR128 = Mock(return_value=lambda samples: ([], [], -18., 2.))
        with patch.dict(sys.modules, {'essentia': essentia, 'essentia.standard': es}), \
                patch.object(sf, 'info', return_value=SimpleNamespace(frames=44100, samplerate=44100)):
            tuning, loudness, tool = analyzers.analyze_essentia(Path('admitted.wav'))
        self.assertEqual((tuning['status'], loudness['status']), ('complete', 'complete'))
        es.AudioLoader.assert_called_once()
        self.assertEqual(tool['profile'], 'stemwise-v1')

    def test_scratch_admission_checks_actual_filesystem_before_work_and_preserves_input(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            original = root/'original.flac'
            original.write_bytes(b'preserved exact original')
            with patch.object(resources.shutil, 'disk_usage', return_value=SimpleNamespace(free=0)):
                with self.assertRaisesRegex(resources.AcousticBudgetExceeded, 'analysis-scratch-space-unavailable'):
                    resources.require_analysis_capacity(180000, scratch_root=root/'not-created')
            self.assertEqual(original.read_bytes(), b'preserved exact original')
            self.assertFalse((root/'not-created').exists())
            required = 180000*48000*2*4*2//1000 + resources.analysis_resource_policy().minimumScratchReserveBytes
            with patch.object(resources.shutil, 'disk_usage', return_value=SimpleNamespace(free=required)):
                admitted = resources.require_analysis_capacity(180000, scratch_root=root)
            self.assertEqual(admitted['minimumScratchBytes'], required)

    def test_admission_boundary_and_parallel_working_set_are_explicit(self):
        policy = resources.analysis_resource_policy()
        with patch.object(resources, 'available_memory_bytes', return_value=8*1024**3):
            full = resources.analysis_resource_plan(600000)
            self.assertEqual(full['pcmBudgetBytes'], 8*1024**3-policy.minimumProcessReserveBytes)
            self.assertGreater(full['combinedPcmBytes'], 1024**3)
            self.assertTrue(full['parallel'])
            self.assertTrue(resources.analysis_resource_plan(600000)['fullWindowAdmitted'])
            self.assertTrue(resources.analysis_resource_plan(600001)['fullWindowAdmitted'])
            self.assertTrue(resources.analysis_resource_plan(180000)['parallel'])
        with patch.object(resources, 'available_memory_bytes', return_value=1024**3):
            long = resources.analysis_resource_plan(7200000)
            self.assertFalse(long['parallel'])
        self.assertEqual(long['essentiaWindowMs'], 30000)
        with self.assertRaises(ValueError):
            resources.analysis_resource_plan(0)


if __name__ == '__main__':
    unittest.main()
