"""CPU known answers for the private Sylla adapter; no model or CUDA execution."""
import copy
import hashlib
import json
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import Mock, patch

import numpy as np
import soundfile as sf
import torch

from stemwise_engine import sylla_forced_alignment as sylla
from stemwise_engine.common import WorkerRetirementError

WORKSPACE = Path(__file__).resolve().parents[3]


class SyllaForcedAlignmentTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.vocab = {'<blank>': 0, '|': 1, 'a': 2, 'b': 3}
        self.model = self.root/sylla.MODEL_SPEC['revision']
        self.model.mkdir()
        spec = copy.deepcopy(sylla.MODEL_SPEC)
        for row in spec['files']:
            raw = (json.dumps(self.vocab).encode() if row['name'] == 'vocab.json'
                else ('fixture '+row['name']).encode())
            (self.model/row['name']).write_bytes(raw)
            row.update(byteCount=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        patched = patch.object(sylla, 'MODEL_SPEC', spec)
        patched.start()
        self.addCleanup(patched.stop)
        self.runtime = {'runtimeVersion': 1, **copy.deepcopy(spec['runtime']),
            'requestedDevice': 'auto', 'resolvedDevice': 'cuda', 'cudaAvailable': True,
            'cudaDeviceName': 'fixture GPU, no device opened', 'cudaDeviceCapability': [12, 0],
            'dtype': 'float16', 'torchThreads': 4, 'deviceIndex': 0,
            'torchProducer': {'device': 'cuda', 'torch': spec['runtime']['packages']['torch'],
                'cudaDriver': 13000, 'cudaRuntime': spec['runtime']['cudaRuntime'], 'cudnn': '91002',
                'uuid': 'GPU-11111111-1111-1111-1111-111111111111',
                'name': 'fixture GPU, no device opened', 'capability': [12, 0], 'totalMemoryBytes': 16000000000}}
        self.request = {'formatVersion': 5, 'durationMs': 1000, 'segments': [{'segmentIndex': 0,
            'startLineIndex': 0, 'endLineIndex': 2, 'windowStartMs': 0, 'windowEndMs': 1000}],
            'lines': [{'lineId': 'verse-occurrence', 'sourcePhysicalIndex': 3,
                'words': [{'wordId': 'verse-word', 'acousticText': 'a', 'acousticLanguage': 'en'}]},
                {'lineId': 'repeat-occurrence', 'sourcePhysicalIndex': 3,
                'words': [{'wordId': 'repeat-word', 'acousticText': 'a', 'acousticLanguage': 'en'}]}]}
        self.audio = self.root/'original.wav'
        sf.write(self.audio, np.zeros((16000, 2), dtype=np.float32), 16000, subtype='FLOAT')
        self.request_path, self.output = self.root/'request.json', self.root/'result.json'
        self.request_path.write_text(json.dumps(self.request), encoding='utf-8')
        self.emission = torch.full((10, 4), -40.)
        for index, token in enumerate((0, 2, 2, 0, 1, 0, 2, 2, 0, 0)):
            self.emission[index, token] = 0
        self.emission = self.emission.log_softmax(-1)

    def model_boundary(self, model_path, state):
        state.update(model=object(), cuda_model=False)
        return object()

    def run_candidate(self, *, forward=None, load=None, runtime_values=None, cache_dir=None):
        with ExitStack() as stack:
            stack.enter_context(patch.object(sylla, 'runtime_info',
                **({'side_effect': runtime_values} if runtime_values is not None else {'return_value': self.runtime})))
            stack.enter_context(patch.object(sylla, '_load_model', side_effect=load or self.model_boundary))
            stack.enter_context(patch.object(sylla, '_forward_window', side_effect=forward or
                (lambda *args: self.emission.clone())))
            return sylla.run(self.audio, self.request_path, self.output, self.model, 'en', 'auto',
                **({'emission_cache_dir': cache_dir} if cache_dir is not None else {}))

    def cache_case(self):
        self.request['durationMs'] = self.request['segments'][0]['windowEndMs'] = 2000
        self.request_path.write_text(json.dumps(self.request), encoding='utf-8')
        sf.write(self.audio, np.zeros((32000, 2), dtype=np.float32), 16000, subtype='FLOAT')
        self.emission = torch.full((20, 4), -40.)
        for index in range(20):
            self.emission[index, 0] = -10.
            self.emission[index, 2 if index in (3, 4, 14, 15) else 3 if index in (16, 17)
                          else 1 if index == 9 else 0] = 0
        self.emission = self.emission.log_softmax(-1)
        return self.root/'persistent-emissions'

    def test_emission_cache_redecodes_changed_targets_and_segments_without_model(self):
        cache_dir = self.cache_case()
        first = self.run_candidate(cache_dir=cache_dir)
        cache = next(cache_dir.glob('emissions-*.pt'))
        before = cache.read_bytes()
        audio_record = sylla._file_record(self.audio)
        expected_binding = sylla.emission_binding({'sha256': audio_record['sha256'], 'byteCount': audio_record['byteCount'],
            'stream': {'sampleRate': 16000, 'channelCount': 2, 'frameCount': 32000}},
            sylla.model_identity(self.model), self.runtime, sylla.producer_identity())
        expected_key = sylla.ctc.canonical_sha256(expected_binding)
        saved = torch.load(cache, weights_only=True)
        self.assertEqual(saved['binding'], expected_binding)
        self.assertEqual(saved['key'], expected_key)
        self.assertEqual(cache.name, f'emissions-{expected_key}.pt')
        self.assertEqual([(line['startMs'], line['endMs']) for line in first['lines']], [(300, 500), (1400, 1600)])
        self.request['segments'] = [{'segmentIndex': index, 'startLineIndex': index, 'endLineIndex': index+1,
            'windowStartMs': index*1000, 'windowEndMs': (index+1)*1000} for index in range(2)]
        self.request['lines'][1]['words'][0].update(wordId='current-word', acousticText='b')
        self.request_path.write_text(json.dumps(self.request), encoding='utf-8')
        second = self.run_candidate(cache_dir=cache_dir, load=Mock(side_effect=AssertionError('cached model load')),
            forward=Mock(side_effect=AssertionError('cached forward')))
        self.assertEqual(second['lines'][1]['words'], [{'wordId': 'current-word', 'startMs': 1600, 'endMs': 1800}])
        self.assertNotEqual(first['requestSha256'], second['requestSha256'])
        self.assertEqual(second['emissionWindows'], first['emissionWindows'])
        self.assertEqual(second['canonicalTimeTransform'], first['canonicalTimeTransform'])
        self.assertEqual(cache.read_bytes(), before)
        self.assertEqual(torch.load(cache, weights_only=True)['key'], expected_key)
        self.assertTrue(all(second[key] is False for key in
            ('supportedAuthority', 'publicationAuthority', 'corroborationAuthority')))

    def test_emission_cache_identity_uses_actual_audio_model_source_decoder_and_runtime(self):
        cache_dir = self.cache_case()
        self.run_candidate(cache_dir=cache_dir)
        prior = {path: path.read_bytes() for path in cache_dir.glob('emissions-*.pt')}
        model_file = self.model/'model.safetensors'
        original_model, original_audio = model_file.read_bytes(), self.audio.read_bytes()
        for fault in ('audio', 'model', 'source', 'decoder', 'uuid', 'driver', 'cudnn'):
            with self.subTest(fault=fault), ExitStack() as stack:
                if fault == 'audio':
                    sf.write(self.audio, np.ones((32000, 2), dtype=np.float32)*.01, 16000, subtype='FLOAT')
                elif fault == 'model':
                    model_file.write_bytes(original_model+b' changed')
                    changed_spec = copy.deepcopy(sylla.MODEL_SPEC)
                    row = next(item for item in changed_spec['files'] if item['name'] == model_file.name)
                    row.update(byteCount=model_file.stat().st_size, sha256=hashlib.sha256(model_file.read_bytes()).hexdigest())
                    stack.enter_context(patch.object(sylla, 'MODEL_SPEC', changed_spec))
                elif fault == 'source':
                    changed = sylla.producer_identity()
                    changed['sylla_forced_alignment.py'] = 'a'*64
                    stack.enter_context(patch.object(sylla, 'producer_identity', return_value=changed))
                elif fault == 'decoder':
                    stack.enter_context(patch.dict(sylla.DECODER, {'coreSeconds': 10}))
                else:
                    changed = copy.deepcopy(self.runtime)
                    changed['torchProducer'].update({'uuid': 'GPU-22222222-2222-2222-2222-222222222222'} if fault == 'uuid'
                        else {'cudaDriver': 13001} if fault == 'driver' else {'cudnn': '91003'})
                    stack.enter_context(patch.object(self, 'runtime', changed))
                load = Mock(side_effect=self.model_boundary)
                self.run_candidate(cache_dir=cache_dir, load=load)
                load.assert_called_once()
                self.assertEqual({path: path.read_bytes() for path in prior}, prior)
            self.audio.write_bytes(original_audio)
            model_file.write_bytes(original_model)

    def test_partial_or_corrupt_emission_cache_cannot_replace_prior_output(self):
        cache_dir = self.cache_case()
        self.run_candidate(cache_dir=cache_dir)
        cache = next(cache_dir.glob('emissions-*.pt'))
        prior_cache, prior_output = cache.read_bytes(), self.output.read_bytes()
        for fault in ('missing-windows', 'windows', 'binding', 'chunks', 'nonfinite', 'out-of-range-window'):
            saved = torch.load(cache, weights_only=True)
            if fault == 'missing-windows': saved.pop('emissionWindows')
            elif fault == 'windows': saved['emissionWindows'][0]['rawEmissionFrames'] += 1
            elif fault == 'binding': saved['binding']['audio']['sha256'] = 'a'*64
            elif fault == 'chunks': saved['chunks'][0]['targetEndFrame'] -= 1
            elif fault == 'out-of-range-window':
                saved['chunks'][0].update(targetStartFrame=32000+sylla.DECODER['contextSeconds']*16000,
                                         targetEndFrame=32000)
            else: saved['emission'][0, 0] = float('nan')
            torch.save(saved, cache)
            corrupted = cache.read_bytes()
            load = Mock(side_effect=RuntimeError('replacement model unavailable'))
            with self.subTest(fault=fault), self.assertRaisesRegex(RuntimeError, 'replacement model unavailable'):
                self.run_candidate(cache_dir=cache_dir, load=load)
            load.assert_called_once()
            self.assertEqual(self.output.read_bytes(), prior_output)
            self.assertEqual(cache.read_bytes(), corrupted)
            cache.write_bytes(prior_cache)
        saved = torch.load(cache, weights_only=True)
        saved.pop('emissionWindows')
        torch.save(saved, cache)
        load = Mock(side_effect=self.model_boundary)
        repaired = self.run_candidate(cache_dir=cache_dir, load=load)
        load.assert_called_once()
        self.assertEqual(repaired['emissionWindows'][0]['rawEmissionFrames'], 20)
        repaired_cache = cache.read_bytes()
        self.request['lines'][1]['words'][0]['wordId'] = 'repaired-current-word'
        self.request_path.write_text(json.dumps(self.request), encoding='utf-8')
        replay = self.run_candidate(cache_dir=cache_dir, load=Mock(side_effect=AssertionError('repaired cache model load')))
        self.assertEqual(replay['lines'][1]['words'][0]['wordId'], 'repaired-current-word')
        self.assertEqual(cache.read_bytes(), repaired_cache)
        prior_output = self.output.read_bytes()
        decode = sylla._decode
        def change_after_decode(*args):
            result = decode(*args)
            with cache.open('ab') as changed:
                changed.write(b'changed after cache read')
            return result
        with patch.object(sylla, '_decode', side_effect=change_after_decode):
            with self.assertRaisesRegex(ValueError, 'sylla-emission-cache-changed'):
                self.run_candidate(cache_dir=cache_dir, load=Mock(side_effect=AssertionError('cache model load')))
        self.assertEqual(self.output.read_bytes(), prior_output)

    def test_optional_emission_cache_write_failure_keeps_computed_candidate(self):
        cache_dir = self.cache_case()
        with patch.object(torch, 'save', side_effect=OSError('fixture cache disk unavailable')):
            value = self.run_candidate(cache_dir=cache_dir)
        self.assertEqual(value['lines'][1]['endMs'], 1600)
        self.assertEqual(json.loads(self.output.read_bytes()), value)
        self.assertEqual(list(cache_dir.glob('emissions-*.pt')), [])
        unlink = Path.unlink
        def failed_cleanup(path, *args, **kwargs):
            if path.name.startswith('emissions-') and path.suffix == '.tmp':
                raise OSError('fixture cache cleanup unavailable')
            return unlink(path, *args, **kwargs)
        with patch.object(Path, 'unlink', new=failed_cleanup):
            value = self.run_candidate(cache_dir=cache_dir)
        self.assertEqual(json.loads(self.output.read_bytes()), value)
        self.assertTrue(any('fixture cache cleanup unavailable' in path.read_text(encoding='utf-8')
            for path in cache_dir.glob('emissions.failure.private*.txt')))

    def test_failed_or_unretired_forward_does_not_publish_emission_cache(self):
        cache_dir = self.cache_case()
        self.run_candidate()
        prior = self.output.read_bytes()
        for fault in ('cancelled', 'retirement', 'source-change'):
            def forward(*_):
                if fault == 'cancelled':
                    raise KeyboardInterrupt('fixture stop')
                if fault == 'source-change':
                    with self.audio.open('ab') as changed:
                        changed.write(b'changed during inference')
                return self.emission.clone()
            with self.subTest(fault=fault), ExitStack() as stack:
                if fault == 'retirement':
                    stack.enter_context(patch.object(sylla.ctc, 'retire_model', side_effect=RuntimeError('unconfirmed retirement')))
                with self.assertRaises(KeyboardInterrupt if fault == 'cancelled'
                        else WorkerRetirementError if fault == 'retirement' else ValueError):
                    self.run_candidate(cache_dir=cache_dir, forward=forward)
            self.assertEqual(self.output.read_bytes(), prior)
            self.assertEqual(list(cache_dir.glob('emissions-*.pt')), [])
            sf.write(self.audio, np.zeros((32000, 2), dtype=np.float32), 16000, subtype='FLOAT')

    def validate(self, value):
        return sylla.validate_result(value, self.request, sylla._file_record(self.audio)['sha256'],
            sylla.model_identity(self.model), self.vocab, self.runtime,
            {'sampleRate': 16000, 'channelCount': 2, 'frameCount': 16000}, language='en')

    def test_v2_turkish_normalization_retains_original_and_reports_unsupported_targets(self):
        vocab = {character: index for index, character in enumerate(
            ['<blank>', '|', *dict.fromkeys('ıislakntbuçğöşüؤمقلبحذهـا')])}
        for original, expected in (('ISLAK', 'ıslak'), ('İstanbul', 'istanbul'),
                ('I\u0307stanbul', 'istanbul'), ('Iİıi', 'ıiıi'), ('ÇĞÖŞÜ', 'çğöşü')):
            with self.subTest(original=original):
                row = sylla.target_record(original, 'tr-TR', vocab)
                self.assertEqual(row['originalText'], original)
                self.assertEqual(row['targetText'], expected)
                self.assertTrue(row['admitted'])
                self.assertEqual(sylla.target_record(expected, 'tr', vocab)['targetText'], expected)
        for original in ('قَلْبٌ', 'الْحُبّ', 'هٰذا', '🙂', '|'):
            row = sylla.target_record(original, 'ar', vocab)
            self.assertFalse(row['admitted'])
            self.assertEqual(row['originalText'], original)
            self.assertTrue(row['unknownCharacters'])
            self.assertTrue(all('codepoint' in unknown and 'unicodeName' in unknown for unknown in row['unknownCharacters']))
        row = sylla.target_record('!!!', 'en', vocab)
        self.assertEqual(row['targetText'], '')
        self.assertFalse(row['admitted'])
        self.assertEqual(sylla.target_record('ISLAK', ' tr ', vocab)['targetText'], 'ıslak')
        self.assertEqual(sylla.target_record("İstanbul'da", 'tr', vocab)['originalText'], "İstanbul'da")
        request = copy.deepcopy(self.request)
        request['lines'][0]['words'][0]['acousticText'] = 'a،'
        admission = sylla.target_admission(request, 'en', self.vocab)
        self.assertEqual(admission[0]['originalText'], 'a،')
        self.assertEqual(admission[0]['targetText'], 'a')
        request['componentLanguages'] = ['ar']
        with self.assertRaises(sylla.TargetAdmissionError) as raised:
            sylla.target_admission(request, 'en', self.vocab)
        self.assertEqual(raised.exception.reason_code, 'sylla-arabic-route-unqualified')

    def test_arabic_and_multiword_targets_refuse_before_runtime_or_model_loading(self):
        cases = (('ar', 'a', 'en'), (' ara ', 'a', 'en'), ('en', 'a', 'arb'),
                 ('Arabic', 'a', 'en'), ('en', 'a', 'ar'), ('en', 'سلام', 'en'),
                 ('en', 'a b', 'en'), ('en', '!!!', 'en'), ('en', '🙂', 'en'))
        vocab = {**self.vocab, 'س': 4, 'ل': 5, 'ا': 6, 'م': 7}
        for declared, text, owner in cases:
            request = copy.deepcopy(self.request)
            request['lines'][0]['words'][0].update(acousticText=text, acousticLanguage=owner)
            self.request_path.write_text(json.dumps(request), encoding='utf-8')
            with self.subTest(text=text, declared=declared, owner=owner), \
                    patch.object(sylla, 'model_identity'), patch.object(sylla, 'read_worker_json',
                        side_effect=lambda path: vocab if path.name == 'vocab.json' else request), \
                    patch.object(sylla, 'runtime_info') as runtime, patch.object(sylla, '_load_model') as load:
                with self.assertRaises(sylla.TargetAdmissionError) as raised:
                    sylla.run(self.audio, self.request_path, self.output, self.model, declared)
                self.assertEqual(raised.exception.records[0]['originalText'], text)
                self.assertEqual(raised.exception.records[0]['wordId'], 'verse-word')
                self.assertEqual(raised.exception.reason_code,
                    'sylla-arabic-route-unqualified' if declared.strip().lower() in ('ar', 'ara', 'arabic')
                    or owner in ('ar', 'arb') or text == 'سلام'
                    else 'sylla-target-unsupported')
                runtime.assert_not_called()
                load.assert_not_called()
                self.assertFalse(self.output.exists())

    def test_known_answer_ctc_preserves_repeated_occurrence_ids_and_recording_eof(self):
        admission = sylla.target_admission(self.request, 'en', self.vocab)
        maps = [sylla.ctc.chunk_time_map(0, 16000, 0, 10)]
        lines = sylla._decode(self.request, admission, self.vocab, self.emission, maps)
        self.assertEqual([line['lineId'] for line in lines], ['verse-occurrence', 'repeat-occurrence'])
        self.assertEqual([line['sourcePhysicalIndex'] for line in lines], [3, 3])
        self.assertEqual([word for line in lines for word in line['words']], [
            {'wordId': 'verse-word', 'startMs': 100, 'endMs': 300},
            {'wordId': 'repeat-word', 'startMs': 600, 'endMs': 800}])
        at_eof = self.emission.clone()
        at_eof[8:] = torch.tensor([-40., -40., 0., -40.]).log_softmax(-1)
        lines = sylla._decode(self.request, admission, self.vocab, at_eof, maps)
        self.assertEqual(lines[-1]['endMs'], 1000)
        self.assertEqual(len(lines[-1]['words']), 1)

    def tail_decode_case(self, held_end=10):
        request = copy.deepcopy(self.request)
        request['durationMs'] = 2000
        request['lines'][1]['words'][0]['acousticText'] = 'b'
        request['segments'] = [{'segmentIndex': index, 'startLineIndex': index, 'endLineIndex': index+1,
            'windowStartMs': index*1000, 'windowEndMs': (index+1)*1000} for index in range(2)]
        vocabulary = {**self.vocab, 'x': 4}
        emission = torch.full((40, 5), -40.)
        emission[:, 0] = 0.
        emission[:, 1] = -80.
        emission[6:held_end, 0], emission[6:held_end, 2] = -20., 0.
        emission[held_end:20, 0], emission[held_end:20, 2], emission[held_end:20, 4] = -20., -10., 0.
        emission[22, 1] = -8.
        emission[28:32, 0], emission[28:32, 3] = -20., 0.
        return request, vocabulary, emission.log_softmax(-1), [sylla.ctc.chunk_time_map(0, 32000, 0, 40)]

    def test_native_default_rejects_internal_tail_and_preserves_sustained_eof(self):
        from stemwise_engine.worker_protocol import WorkerReasonError
        for held_end, expected_end in ((10, 500), (18, 900)):
            request, vocabulary, emission, maps = self.tail_decode_case(held_end=held_end)
            admission = sylla.target_admission(request, 'en', vocabulary)
            with self.subTest(held_end=expected_end, boundary='internal'):
                with self.assertRaises(WorkerReasonError) as raised:
                    sylla._decode(request, admission, vocabulary, emission, maps)
                self.assertEqual(raised.exception.reason_code, 'wav2vec2-segment-boundary-guard:0:end')
            single = {**request, 'durationMs': 1000, 'lines': request['lines'][:1],
                'segments': [{'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 1,
                    'windowStartMs': 0, 'windowEndMs': 1000}]}
            with self.subTest(held_end=expected_end, boundary='recording-eof'):
                held = sylla._decode(single, admission[:1], vocabulary, emission[:20],
                    [sylla.ctc.chunk_time_map(0, 16000, 0, 20)])
                self.assertEqual(held[0]['words'], [{'wordId': 'verse-word', 'startMs': 300, 'endMs': expected_end}])

    def test_context_emissions_use_evaluated_half_up_crop_and_contiguous_time_map(self):
        audio = torch.zeros(45*16000)
        def forward(samples, *_):
            return torch.zeros((len(samples)//320+1, 4)).log_softmax(-1)
        with patch.object(sylla, '_forward_window', side_effect=forward):
            emission, chunks, windows = sylla._emissions(audio, object(), object(), 4)
        self.assertEqual([(row['inputStartSample'], row['inputEndSample']) for row in windows],
            [(0, 400000), (240000, 720000), (560000, 720000)])
        self.assertEqual([(row['retainedFirstFrame'], row['retainedEndFrame']) for row in windows],
            [(0, 1001), (250, 1251), (251, 501)])
        self.assertEqual(len(emission), 2252)
        self.assertEqual([sylla.ctc.emission_boundary_to_target_frame(index, chunks)
            for index in (0, 1001, 2002, 2252)], [0, 320000, 640000, 720000])

    def test_native_duration_preserves_ceil_resampling_and_exact_eof_projection(self):
        terminal = self.emission.clone()
        terminal[8:] = torch.tensor([-40., -40., 0., -40.]).log_softmax(-1)
        known = self.run_candidate(forward=lambda *args: terminal.clone())
        sf.write(self.audio, np.zeros((48022, 2), dtype=np.float32), 48000, subtype='FLOAT')
        stream = {'sampleRate': 48000, 'channelCount': 2, 'frameCount': 48022}
        maps = [sylla.ctc.chunk_time_map(0, 16008, 0, 10)]
        with self.subTest(boundary='normal invocation'):
            value = self.run_candidate(forward=lambda *args: terminal.clone())
            transform = value['canonicalTimeTransform']
            self.assertEqual(transform['sourceFrameCount'], 48022)
            self.assertEqual(transform['targetFrameCount'], 16008)
            self.assertEqual(transform['expectedTargetFrameCount'], 16007)
            self.assertEqual(transform['measuredResidualFrames'], 1)
            self.assertEqual(value['lines'][-1]['endMs'], 1000)
        with self.subTest(boundary='actual decoder at target EOF'):
            lines = sylla._decode(self.request, sylla.target_admission(self.request, 'en', self.vocab),
                self.vocab, terminal, maps)
            self.assertEqual(lines[-1]['words'], [{'wordId': 'repeat-word', 'startMs': 600, 'endMs': 1000}])
        with self.subTest(boundary='passive native geometry readback'):
            known['audioSha256'] = sylla._file_record(self.audio)['sha256']
            known['canonicalTimeTransform'] = sylla.ctc.canonical_time_transform(
                source_sample_rate=48000, source_channel_count=2, source_frame_count=48022,
                target_sample_rate=16000, target_frame_count=16008, chunk_target_frames=320000, chunks=maps)
            known['emissionWindows'][0].update(inputEndSample=16008, coreEndSample=16008)
            self.assertIs(sylla.validate_result(known, self.request, known['audioSha256'],
                sylla.model_identity(self.model), self.vocab, self.runtime, stream, language='en'), known)
            wrong = copy.deepcopy(known)
            wrong['canonicalTimeTransform'] = sylla.ctc.canonical_time_transform(
                source_sample_rate=48000, source_channel_count=2, source_frame_count=48022,
                target_sample_rate=16000, target_frame_count=16007, chunk_target_frames=320000,
                chunks=[sylla.ctc.chunk_time_map(0, 16007, 0, 10)])
            wrong['emissionWindows'][0].update(inputEndSample=16007, coreEndSample=16007)
            with self.assertRaises(ValueError):
                sylla.validate_result(wrong, self.request, known['audioSha256'],
                    sylla.model_identity(self.model), self.vocab, self.runtime, stream, language='en')

    def test_cli_runs_real_core_and_publishes_only_complete_private_candidate(self):
        before = self.audio.read_bytes(), self.request_path.read_bytes()
        with patch.object(sylla, 'runtime_info', return_value=self.runtime), \
                patch.object(sylla, '_load_model', side_effect=self.model_boundary), \
                patch.object(sylla, '_forward_window', return_value=self.emission.clone()), \
                patch.object(sylla.ctc, 'retire_model', wraps=sylla.ctc.retire_model) as retire:
            result = sylla.main(['--audio', str(self.audio), '--request', str(self.request_path),
                '--output', str(self.output), '--model', str(self.model), '--language', 'en'])
        self.assertEqual(result, 0)
        candidate = json.loads(self.output.read_text(encoding='utf-8'))
        self.assertIs(self.validate(candidate), candidate)
        self.assertEqual(candidate['status'], 'candidate')
        self.assertIs(candidate['supportedAuthority'], False)
        self.assertIs(candidate['publicationAuthority'], False)
        self.assertIs(candidate['corroborationAuthority'], False)
        self.assertEqual(candidate['targetAdmission'][0]['originalText'], 'a')
        self.assertEqual(len(candidate['lines']), 2)
        self.assertEqual((self.audio.read_bytes(), self.request_path.read_bytes()), before)
        retire.assert_called_once()

    def test_candidate_readback_rejects_changed_ids_geometry_runtime_admission_and_authority(self):
        value = self.run_candidate()
        mutations = (
            lambda row: row.update(publicationAuthority=True),
            lambda row: row.update(supportedAuthority=True),
            lambda row: row.update(corroborationAuthority=True),
            lambda row: row.update(audioSha256='a'*64),
            lambda row: row.update(requestSha256='a'*64),
            lambda row: row['producer'].update({'ctc_worker.py': 'a'*64}),
            lambda row: row['model'].update(revision='a'*40),
            lambda row: row['runtime'].update(cudaDeviceName='different device'),
            lambda row: row['targetAdmission'][0].update(targetText='b'),
            lambda row: row['targetAdmission'][0].update(requestLanguage='tr'),
            lambda row: row['canonicalTimeTransform'].update(sourceFrameCount=16001),
            lambda row: row['canonicalTimeTransform']['chunks'][0].update(targetStartFrame=1),
            lambda row: row['emissionWindows'][0].update(retainedEndFrame=9),
            lambda row: row['lines'][0]['words'][0].update(wordId='renumbered-word'),
            lambda row: row['lines'][0]['words'][0].update(endMs=100),
            lambda row: row['lines'][1]['words'][0].update(startMs=200),
            lambda row: row['lines'].pop(),
        )
        for mutate in mutations:
            changed = copy.deepcopy(value)
            mutate(changed)
            with self.subTest(mutate=mutate), self.assertRaises((ValueError, KeyError, TypeError)):
                self.validate(changed)

    def test_failed_numerics_retire_model_and_preserve_prior_output(self):
        self.output.write_bytes(b'prior valid candidate')
        failure = RuntimeError('fixture acoustic failure')
        with patch.object(sylla.ctc, 'retire_model', wraps=sylla.ctc.retire_model) as retire:
            with self.assertRaises(RuntimeError) as raised:
                self.run_candidate(forward=Mock(side_effect=failure))
        self.assertIs(raised.exception, failure)
        self.assertEqual(self.output.read_bytes(), b'prior valid candidate')
        retire.assert_called_once()
        self.assertNotIn('model', retire.call_args.args[0])

    def test_changed_source_or_request_after_forward_cannot_publish(self):
        for target in (self.audio, self.request_path):
            original = target.read_bytes()
            self.output.write_bytes(b'prior candidate')
            def forward(*args):
                target.write_bytes(original+b' ')
                return self.emission.clone()
            with self.subTest(target=target.name), self.assertRaisesRegex(ValueError, 'sylla-input-changed'):
                self.run_candidate(forward=forward)
            self.assertEqual(self.output.read_bytes(), b'prior candidate')
            target.write_bytes(original)

    def test_retirement_failure_is_fatal_and_retains_native_cause(self):
        original, retirement = RuntimeError('fixture model failure'), RuntimeError('fixture CUDA retirement failure')
        with patch.object(sylla.ctc, 'retire_model', side_effect=retirement):
            with self.assertRaises(WorkerRetirementError) as raised:
                self.run_candidate(forward=Mock(side_effect=original))
        self.assertIs(raised.exception.__cause__, retirement)
        self.assertFalse(self.output.exists())

    def test_pinned_snapshot_and_runtime_drift_fail_closed(self):
        record = sylla.model_identity(self.model)
        self.assertEqual(len(record['files']), 8)
        model_bytes = (self.model/'model.safetensors').read_bytes()
        (self.model/'model.safetensors').write_bytes(model_bytes+b' changed')
        with self.assertRaisesRegex(ValueError, 'asset bytes'):
            sylla.model_identity(self.model)
        (self.model/'model.safetensors').write_bytes(model_bytes)
        (self.model/'bg_head.safetensors').write_bytes(b'not the evaluated head')
        with self.assertRaisesRegex(ValueError, 'file set'):
            sylla.model_identity(self.model)
        (self.model/'bg_head.safetensors').unlink()
        self.assertIs(sylla._validate_runtime(self.runtime), self.runtime)
        for key, wrong in (('resolvedDevice', 'cpu'), ('dtype', 'float32'), ('python', 'future')):
            changed = copy.deepcopy(self.runtime)
            changed[key] = wrong
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'runtime-unqualified'):
                sylla._validate_runtime(changed)
        changed = copy.deepcopy(self.runtime)
        changed['packages']['transformers'] = 'future'
        with self.assertRaisesRegex(ValueError, 'runtime-unqualified'):
            sylla._validate_runtime(changed)
        changed = copy.deepcopy(self.runtime)
        changed['implementationSha256'][next(iter(changed['implementationSha256']))] = 'a'*64
        with patch.object(sylla, 'runtime_info', side_effect=lambda _: sylla._validate_runtime(changed)), \
                patch.object(sylla, '_load_model') as load:
            with self.assertRaisesRegex(ValueError, 'runtime-unqualified'):
                sylla.run(self.audio, self.request_path, self.output, self.model, 'en')
            load.assert_not_called()
        for key in ('enabledByDefault', 'automaticEnabled'):
            for invalid in (True, 0, None):
                with self.subTest(key=key, invalid=invalid), patch.dict(sylla.MODEL_SPEC, {key: invalid}):
                    with self.assertRaisesRegex(ValueError, 'disabled private shadow'):
                        sylla.model_identity(self.model)

    def test_runtime_probe_observes_full_cuda_identity_and_rejects_other_ordinals(self):
        from types import SimpleNamespace
        from stemwise_engine import worker_runtime_probe
        from stemwise_engine.worker_protocol import WorkerReasonError
        with ExitStack() as stack:
            stack.enter_context(patch.object(torch.cuda, 'is_available', return_value=True))
            selected = stack.enter_context(patch.object(torch.cuda, 'current_device', return_value=0))
            stack.enter_context(patch.object(torch.cuda, 'get_device_name', return_value=self.runtime['cudaDeviceName']))
            stack.enter_context(patch.object(torch.cuda, 'get_device_capability', return_value=(12, 0)))
            stack.enter_context(patch.object(torch, 'get_num_threads', return_value=4))
            stack.enter_context(patch.object(sylla.platform, 'python_version', return_value=self.runtime['python']))
            stack.enter_context(patch.object(sylla.importlib.metadata, 'version', side_effect=self.runtime['packages'].__getitem__))
            stack.enter_context(patch.object(sylla.importlib.util, 'find_spec',
                side_effect=lambda name: SimpleNamespace(origin='/fixture/'+name)))
            stack.enter_context(patch.object(sylla, '_file_record',
                side_effect=lambda path: {'sha256': self.runtime['implementationSha256'][path.name]}))
            observed = stack.enter_context(patch.object(worker_runtime_probe, 'torch_runtime_identity',
                return_value=self.runtime['torchProducer']))
            with self.subTest(case='complete-native-observation'):
                self.assertEqual(sylla.runtime_info(), self.runtime)
                observed.assert_called_once_with()
            for ordinals in ([1], [0, 1]):
                with self.subTest(ordinals=ordinals):
                    observed.reset_mock()
                    selected.side_effect = ordinals
                    with self.assertRaises(WorkerReasonError) as raised:
                        sylla.runtime_info()
                    self.assertEqual(raised.exception.reason_code, 'sylla-runtime-unqualified')
                    if ordinals == [1]:
                        observed.assert_not_called()

    def test_runtime_proof_is_required_and_validated_without_cuda_inspection(self):
        from stemwise_engine import worker_runtime_probe
        with patch.object(torch.cuda, 'is_available', side_effect=AssertionError('passive GPU probe')), \
                patch.object(worker_runtime_probe, 'torch_runtime_identity', side_effect=AssertionError('passive producer probe')):
            self.assertIs(sylla._validate_runtime(self.runtime), self.runtime)
            mutations = [lambda row: row.pop('torchProducer'), lambda row: row.pop('deviceIndex'),
                lambda row: row.update(deviceIndex=1), lambda row: row.update(deviceIndex=False)]
            mutations.extend(lambda row, key=key: row['torchProducer'].pop(key)
                for key in ('uuid', 'cudaDriver', 'cudnn', 'torch', 'cudaRuntime', 'name', 'capability', 'totalMemoryBytes'))
            mutations.extend(lambda row, key=key, value=value: row['torchProducer'].update({key: value})
                for key, value in (('uuid', ''), ('cudaDriver', 0), ('cudnn', '0'), ('torch', 'future'),
                    ('cudaRuntime', 'future'), ('name', 'different GPU'), ('capability', [8, 9]),
                    ('totalMemoryBytes', 0)))
            for index, mutate in enumerate(mutations):
                changed = copy.deepcopy(self.runtime)
                mutate(changed)
                with self.subTest(index=index), self.assertRaisesRegex(ValueError, 'sylla-runtime-unqualified'):
                    sylla._validate_runtime(changed)

    def test_runtime_drift_after_forward_retires_model_and_preserves_prior_output(self):
        self.run_candidate()
        prior = self.output.read_bytes()
        for field, value in (('uuid', 'GPU-22222222-2222-2222-2222-222222222222'),
                             ('cudaDriver', 13001), ('cudnn', '91003')):
            changed = copy.deepcopy(self.runtime)
            changed['torchProducer'][field] = value
            with self.subTest(field=field), \
                    patch.object(sylla.ctc, 'retire_model', wraps=sylla.ctc.retire_model) as retire:
                with self.assertRaisesRegex(ValueError, 'sylla-runtime-changed'):
                    self.run_candidate(runtime_values=[self.runtime, changed])
                retire.assert_called_once()
                self.assertNotIn('model', retire.call_args.args[0])
                self.assertEqual(self.output.read_bytes(), prior)


if __name__ == '__main__':
    unittest.main()
