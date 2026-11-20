"""Resident Whisper must unload before another GPU stage can own the lease."""
import unittest
import copy
import sys
from pathlib import Path
from types import SimpleNamespace

from stemwise_engine.lyrics_alignment import FasterWhisperEvidence
import stemwise_engine.lyrics_align_pc as lyrics_align_pc
from stemwise_engine.lyrics_alignment import transcript_cache_matches, TRANSCRIPT_CACHE_VERSION


class WhisperRetirementTests(unittest.TestCase):
    def test_recognition_reuses_only_exact_current_identity(self):
        current = lyrics_align_pc._whisper_recognition_cache_identity()
        prior = copy.deepcopy(current)
        prior['helpersSha256']['FasterWhisperEvidence'] = '1393d9d9c195fbf5efddc03da99f6e9e9c5faeced4191de1b0bc173386e24e2f'
        args = dict(video_id='12345678901', model='large-v3', language='en',
                    prompt_sha256='a'*64, provenance={'audio': 'exact native PCM'}, cache_identity=current)
        value = {'cacheVersion': TRANSCRIPT_CACHE_VERSION, 'videoId': args['video_id'],
                 'model': args['model'], 'language': 'en', 'promptSha256': 'a'*64,
                 'provenance': args['provenance'], 'cacheIdentity': prior, 'words': []}
        before = copy.deepcopy(value)
        self.assertFalse(transcript_cache_matches(value, **args))
        exact = {**value, "cacheIdentity": current}
        self.assertTrue(transcript_cache_matches(exact, **args))
        self.assertEqual(value, before)
        for key in ('normalizationSha256', 'transcriptTypesSha256'):
            changed = copy.deepcopy(exact)
            changed['cacheIdentity'][key] = '0'*64
            self.assertFalse(transcript_cache_matches(changed, **args))
        for name in current['helpersSha256']:
            changed = copy.deepcopy(exact)
            changed['cacheIdentity']['helpersSha256'][name] = '0'*64
            self.assertFalse(transcript_cache_matches(changed, **args))
        forged_current = copy.deepcopy(current)
        forged_current['helpersSha256']['FasterWhisperEvidence'] = 'f'*64
        self.assertFalse(transcript_cache_matches(exact, **{**args, 'cache_identity': forged_current}))

    def test_explicit_retirement_is_idempotent_and_prevents_later_inference(self):
        calls = []
        model = FasterWhisperEvidence.__new__(FasterWhisperEvidence)
        model._model = SimpleNamespace(model=SimpleNamespace(unload_model=lambda: calls.append('unload')))
        model.close()
        model.close()
        self.assertEqual(calls, ['unload'])
        self.assertIsNone(model._model)
        with self.assertRaisesRegex(RuntimeError, 'retired'):
            model.transcribe(None, language=None, prompt=None)

    def test_failed_retirement_keeps_owner_reference_and_propagates(self):
        def fail():
            raise RuntimeError('device retirement failed')
        resident = SimpleNamespace(model=SimpleNamespace(unload_model=fail))
        model = FasterWhisperEvidence.__new__(FasterWhisperEvidence)
        model._model = resident
        with self.assertRaisesRegex(RuntimeError, 'device retirement failed'):
            model.close()
        self.assertIs(model._model, resident)



if __name__ == '__main__':
    unittest.main()
