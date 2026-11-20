"""An empty vendor DTW cannot erase a private unprompted lexical observation."""
import builtins
import copy
import json
import logging
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace as NS
from unittest.mock import Mock, patch

import numpy as np
import soundfile as sf
from faster_whisper import WhisperModel
from faster_whisper.transcribe import Segment, Word

from test_future_song import WORKSPACE
from lyric_content_fixtures import write_recognition
from stemwise_engine import alignment_audio, alignment_models, lyrics_align_pc as align
from stemwise_engine import recording_evidence as evidence
from stemwise_engine.common import atomic_write_json, sha256_file
from stemwise_engine.lyric_content import _recognized
from stemwise_engine.lyrics_alignment import FasterWhisperEvidence
from stemwise_engine.workflow_records import LyricsContentBlocker
from stemwise_engine.workflow_status import validate_recovery_candidates


def vendor_generator(word_timestamps=True, *, alignments=()):
    """Run the installed generator; mock only native generation/DTW boundaries."""
    model = WhisperModel.__new__(WhisperModel)
    model.model = NS(align=Mock(return_value=[NS(text_token_probs=[.9, .9], alignments=list(alignments))]))
    model.tokens_per_second, model.frames_per_second = 50, 100
    model.time_precision, model.input_stride = .02, 2
    model.feature_extractor = NS(time_per_frame=.01, nb_max_frames=3000)
    model.logger = logging.getLogger('empty-dtw-known-answer')
    model.get_prompt = lambda *args, **kwargs: []
    model.generate_with_fallback = lambda *args: (NS(sequences_ids=[[1, 1]], no_speech_prob=0), -.1, 0., 1.)
    tokenizer = NS(sot_sequence=[], eot=2, timestamp_begin=100,
        split_to_word_tokens=lambda tokens: ([' first', ' first', '<eot>'], [[1], [1], [2]]),
        decode=lambda tokens: ' first first')
    options = NS(clip_timestamps=[], initial_prompt=None, multilingual=False, without_timestamps=False,
        prefix=None, hotwords=None, no_speech_threshold=None, log_prob_threshold=None,
        word_timestamps=word_timestamps, prepend_punctuations='', append_punctuations='',
        hallucination_silence_threshold=None, condition_on_previous_text=False,
        prompt_reset_on_temperature=.5)
    return model, model.generate_segments(np.zeros((80, 101), dtype=np.float32), tokenizer,
        options, False, encoder_output=object())


class WhisperLexicalCandidateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config = {'projectDir': str(self.root/'PC side/builder'), 'analysisTimeoutMinutes': 1}
        self.job = self.root/'jobs/AAAAAAAAAAA'
        (self.job/'drafts').mkdir(parents=True)
        (self.job/'drafts/original.txt').write_text('first first\n', encoding='utf-8')
        atomic_write_json(self.job/'drafts/lyrics.meta.json', {'originalLanguage': 'en'})
        self.audio = self.root/'mix.wav'
        sf.write(self.audio, np.ones((48000, 2), dtype=np.float32)*.1, 48000, subtype='FLOAT')
        self.workspace = evidence.AlignmentWorkspace(self.root, workspace_root=self.root)
        self.primary = write_recognition(self.workspace.job_dir('AAAAAAAAAAA'),
            'different words', (0,), sha256_file(self.audio))
        self.primary = self.primary.rename(self.primary.with_name('qwen-structure-en.private.json'))
        self.calls = []

    @contextmanager
    def boundaries(self, *, timed_error=None, untimed_error=None, after_untimed=None, empty_precision=False):
        wrapper = FasterWhisperEvidence.__new__(FasterWhisperEvidence)
        wrapper.timeout_seconds = 5
        wrapper.model_name, wrapper.device, wrapper.compute_type = align.DEFAULT_MODEL, 'cuda', 'float16'
        wrapper.snapshot_path = align._whisper_snapshot_path(Path(self.config['projectDir']).parent/'model-cache/lyrics-alignment')
        def transcribe(audio, **options):
            self.calls.append(copy.deepcopy(options))
            if options['word_timestamps'] and (options['beam_size'] == 10) != empty_precision:
                return iter([Segment(1, 0, 0, .8, ' sibling', [3], -.1, 1., 0.,
                    [Word(0, .8, ' sibling', .9)], 0.)]), NS(language='en', language_probability=.9)
            if options['word_timestamps'] and timed_error is not None:
                raise timed_error
            if not options['word_timestamps']:
                if untimed_error is not None:
                    raise untimed_error
                if after_untimed is not None:
                    after_untimed()
            _, segments = vendor_generator(options['word_timestamps'])
            return segments, NS(language='en', language_probability=.9)
        wrapper._model = NS(transcribe=Mock(side_effect=transcribe))
        self.owner = NS(compute_type='float16', get=Mock(return_value=wrapper), close=Mock())
        self.views = NS(outputs={}, runtime_cache={}, close=Mock(side_effect=self.owner.close))
        runtime = {'ctranslate2': '4.8.1', 'device': 'cuda', 'cudaDevice': {
            'name': 'fixture', 'uuid': 'a'*32, 'capability': [8, 9], 'totalMemoryBytes': 1_000_000},
            'cudaDriver': 12080, 'libraries': {}}
        def probe(*args, **kwargs):
            return NS(returncode=0, stdout=json.dumps({'streams': [{'sample_rate': '48000',
                'channels': 2, 'duration_ts': 48000, 'time_base': '1/48000'}]}))
        with patch.object(alignment_audio, 'run_process_tree', side_effect=probe), \
                patch('stemwise_engine.lyrics_alignment.resolve_compute_type', return_value='float16'), \
                patch('stemwise_engine.worker_runtime_probe.ctranslate2_runtime_identity', return_value=runtime), \
                patch.object(alignment_models, 'WhisperModelOwner', return_value=self.owner), \
                patch.object(align, '_verify_whisper_snapshot'), \
                patch.object(alignment_audio, 'AlignmentAudioViews', return_value=self.views):
            yield wrapper

    def collect(self, *, diagnostic=True):
        draft, draft_sha, metadata, metadata_sha = evidence.read_recording_draft(self.job)
        identity = alignment_audio.file_identity(self.audio)
        identity['canonicalTimeTransform'] = alignment_audio.identity_transform(identity['stream'])
        return evidence.collect_disagreement_candidate(self.config, self.workspace, 'AAAAAAAAAAA',
            self.audio, identity, {'text': draft, 'language': metadata['originalLanguage']},
            draft_sha, metadata_sha, self.primary, {'known': 'unresolved'}, diagnostic=diagnostic)

    def test_empty_dtw_vendor_generator_retains_untimed_text_without_timing_authority(self):
        model, segments = vendor_generator()
        with self.assertRaisesRegex(IndexError, 'boolean index'):
            list(segments)
        model.model.align.assert_called_once()
        with self.boundaries():
            packet = self.collect()
        self.assertEqual(len(packet['candidates']), 2, 'failed timing erased the independent generated text')
        lexical = next(candidate for candidate in packet['candidates']
            if 'lexical' in candidate['recognition']['path'])
        self.assertEqual((self.job/lexical['candidate']['path']).read_text(encoding='utf-8'), 'first first\n')
        raw = json.loads((self.primary.parent/lexical['recognition']['path']).read_bytes())
        self.assertEqual(raw['segments'], [{'id': 1, 'text': ' first first', 'tokens': [1, 1]}])
        self.assertIs(raw['timingAuthority'], False)
        self.assertIs(raw['publicationAuthority'], False)
        self.assertEqual(len(packet['routeFailures']), 1)
        self.assertFalse(self.calls[1]['word_timestamps'])
        self.assertIsNone(self.calls[1]['initial_prompt'])
        self.assertFalse(self.calls[1]['condition_on_previous_text'])
        self.assertEqual(self.calls[1]['beam_size'], 5)
        self.owner.close.assert_called_once()
        before = (self.job/'drafts/original.txt').read_bytes()
        with self.boundaries(), patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                return_value=('different words', {'language': 'en'}, True)):
            with self.assertRaises(LyricsContentBlocker) as raised:
                evidence.compare(self.config, self.root, 'AAAAAAAAAAA', self.audio, sha256_file(self.audio))
        self.assertEqual(len(raised.exception.recovery_candidates['candidates']), 2)
        self.assertEqual((self.job/'drafts/original.txt').read_bytes(), before)

    def test_unrelated_index_error_does_not_start_lexical_fallback(self):
        error = IndexError('unrelated decoder index')
        with self.boundaries(timed_error=error):
            packet = self.collect()
        self.assertEqual(len(packet['candidates']), 1)
        self.assertEqual(len(self.calls), 2)
        self.assertTrue(all(call['word_timestamps'] for call in self.calls))
        self.assertEqual(len(packet['routeFailures']), 1)
        with self.boundaries(timed_error=error):
            with self.assertRaises(IndexError) as raised:
                self.collect(diagnostic=False)
        self.assertIs(raised.exception, error)
        self.assertEqual(len(raised.exception.partial_candidates['candidates']), 1)

    def test_precision_fallback_retains_pinned_unprompted_decoder_options(self):
        with self.boundaries(empty_precision=True):
            packet = self.collect()
        self.assertEqual(len(packet['candidates']), 2)
        self.assertEqual(len(packet['routeFailures']), 1)
        self.assertEqual([call['word_timestamps'] for call in self.calls], [True, True, False])
        self.assertEqual(self.calls[-1], {'language': 'en', 'initial_prompt': None, 'beam_size': 10,
            'word_timestamps': False, 'condition_on_previous_text': False, 'vad_filter': False,
            'temperature': 0.0, 'patience': 1.5, 'repetition_penalty': 1.05})

    def test_alternative_timed_tuple_does_not_fulfill_default_profile(self):
        with self.boundaries():
            self.collect()
            path = self.workspace.transcript_path('AAAAAAAAAAA', 'en', 'precision-unprompted-mix')
            original = json.loads(path.read_bytes())
            for field in ('model', 'decoder', 'compute', 'runtime'):
                # Only the alternative is available; a retained exact default would legitimately fulfill the request.
                for frozen in self.primary.parent.glob('content-recovery-faster-whisper-*.private.json'):
                    if json.loads(frozen.read_bytes()) == original:
                        frozen.unlink()
                alternative = copy.deepcopy(original)
                if field == 'model':
                    alternative['model'] = 'alternative-model+precision-unprompted'
                    alternative['provenance'].update(modelSnapshot='alternative-snapshot',
                        modelSha256=None, modelManifestSha256=None)
                elif field == 'decoder':
                    alternative['provenance']['decoder']['temperature'] = .2
                elif field == 'compute':
                    alternative['provenance']['computeType'] = 'int8'
                else:
                    alternative['provenance']['producerRuntime']['cudaDevice']['uuid'] = 'b'*32
                atomic_write_json(path, alternative)
                self.assertTrue(_recognized(alternative, sha256_file(self.audio), candidate_only=True))
                before = len(self.calls)
                with self.subTest(alternative=field):
                    packet = self.collect()
                    self.assertEqual(len(self.calls), before+1, 'an alternative tuple suppressed the requested default decode')
                    self.assertTrue(any(json.loads((self.primary.parent/candidate['recognition']['path']).read_bytes())
                        == alternative for candidate in packet['candidates']))

    def test_cached_htdemucs_observations_remain_complete_without_separator_assets(self):
        separator = Path(self.config['projectDir']).parent/'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th'
        separator.parent.mkdir(parents=True)
        separator.write_bytes(b'mocked pinned separator boundary')
        vocals = self.root/'vocals.wav'
        vocals.write_bytes(self.audio.read_bytes())
        real_sha = align._sha256_file
        with self.boundaries(), patch.object(alignment_models, 'require_pinned_file'), \
                patch.object(align, '_sha256_file', side_effect=lambda path:
                    alignment_models.PINNED_DEMUCS_SHA256 if path == separator else real_sha(path)):
            def materialize(route):
                value = alignment_audio.write_audio_provenance(self.workspace, 'AAAAAAAAAAA', duration_ms=1000,
                    mix_audio=self.audio, vocals_audio=vocals, demucs_model_sha256=alignment_models.PINNED_DEMUCS_SHA256,
                    expected_mix_sha256=sha256_file(self.audio))
                return vocals, value['audioViews'][route], {}
            self.views.get_endpoint_audio = Mock(side_effect=materialize)
            self.collect()
            before = len(self.calls)
            replay = self.collect()
            self.assertEqual(replay['vocalRecovery']['status'], 'complete')
            separator.unlink()
            with patch.object(self.owner, 'get', side_effect=AssertionError('cached observations loaded a model')), \
                    patch.object(self.views, 'get_endpoint_audio', side_effect=AssertionError('cached observations rebuilt PCM')):
                replay = self.collect()
            self.assertEqual(replay['vocalRecovery']['status'], 'complete')
            self.assertEqual(len(self.calls), before)
            self.assertEqual(len(replay['candidates']), 4)

    def test_untimed_cache_survives_editorial_changes_and_passive_readback_without_models(self):
        with self.boundaries():
            packet = self.collect()
            self.assertEqual(len(packet['candidates']), 2)
            lexical = next(candidate for candidate in packet['candidates'] if 'lexical' in candidate['recognition']['path'])
            original_raw = (self.primary.parent/lexical['recognition']['path']).read_bytes()
            original_failure = packet['routeFailures'][0]
            (self.job/'drafts/original.txt').write_text('different draft\n', encoding='utf-8')
            atomic_write_json(self.job/'drafts/lyrics.meta.json', {'originalLanguage': 'en', 'note': 'editorial-only change'})
            with patch.object(self.owner, 'get', side_effect=AssertionError('cache replay loaded a model')):
                replay = self.collect()
            self.assertEqual(len(self.calls), 3)
            self.assertEqual((self.primary.parent/lexical['recognition']['path']).read_bytes(), original_raw)
            self.assertNotIn('routeFailures', replay)
        original_import = builtins.__import__
        def passive(name, *args, **kwargs):
            if name in {'stemwise_engine.recording_evidence', 'stemwise_engine.lyrics_align_pc',
                    'stemwise_engine.alignment_models'} or name.split('.')[0] in {
                    'torch', 'numpy', 'faster_whisper', 'ctranslate2'}:
                raise AssertionError('passive readback imported numerical code: '+name)
            return original_import(name, *args, **kwargs)
        with patch.object(builtins, '__import__', side_effect=passive):
            validate_recovery_candidates(self.job, self.primary.parent, replay,
                video_id='AAAAAAAAAAA', audio_sha=sha256_file(self.audio), duration_ms=1000)
        diagnostic = self.job/original_failure['diagnostics'][0]['file']
        diagnostic.write_bytes(diagnostic.read_bytes()+b'changed')
        with self.assertRaises(ValueError):
            validate_recovery_candidates(self.job, self.primary.parent, replay,
                video_id='AAAAAAAAAAA', audio_sha=sha256_file(self.audio), duration_ms=1000)

    def test_lexical_readback_rejects_authority_and_changed_raw_tokens(self):
        with self.boundaries():
            packet = self.collect()
        self.assertEqual(len(packet['candidates']), 2)
        candidate = next(candidate for candidate in packet['candidates'] if 'lexical' in candidate['recognition']['path'])
        raw = json.loads((self.primary.parent/candidate['recognition']['path']).read_bytes())
        self.assertEqual(_recognized(raw, sha256_file(self.audio), candidate_only=True), ' first first')
        with self.assertRaises(ValueError):
            _recognized(raw, sha256_file(self.audio))
        for field in ('timingAuthority', 'publicationAuthority'):
            changed = copy.deepcopy(raw)
            changed[field] = True
            with self.subTest(field=field), self.assertRaises(ValueError):
                _recognized(changed, sha256_file(self.audio), candidate_only=True)
        changed = copy.deepcopy(raw)
        changed['segments'][0]['tokens'] = []
        with self.assertRaises(ValueError):
            _recognized(changed, sha256_file(self.audio), candidate_only=True)

    def test_current_input_change_stops_fallback_before_candidate_publication(self):
        def change():
            (self.job/'drafts/original.txt').write_text('changed while decoding\n', encoding='utf-8')
        with self.boundaries(after_untimed=change):
            with self.assertRaises(LyricsContentBlocker):
                self.collect()
        self.assertFalse(list(self.primary.parent.glob('whisper-lexical-*.json')))
        self.assertFalse((self.job/'candidates').exists())
        self.owner.close.assert_called_once()

    def test_failed_untimed_retry_preserves_original_failure_and_healthy_sibling(self):
        failure = RuntimeError('untimed decoder failed')
        with self.boundaries(untimed_error=failure):
            packet = self.collect()
        self.assertEqual(len(self.calls), 3)
        self.assertEqual(len(packet['candidates']), 1)
        self.assertEqual(len(packet['routeFailures']), 2)
        self.assertFalse(list(self.primary.parent.glob('whisper-lexical-*.json')))


if __name__ == '__main__':
    unittest.main()
