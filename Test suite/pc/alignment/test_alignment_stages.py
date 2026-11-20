"""Current typed stage handoffs are independent of CLI construction and mutable caller inputs."""
import copy
from dataclasses import FrozenInstanceError
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / 'PC side/stemwise-playlist-builder/src'
from stemwise_engine.alignment_phases import AlignmentContext, EndpointText, EndpointEvidence

STAGE_IMPORT_CHECK = '''
import importlib, sys
from stemwise_engine import (alignment_endpoints, alignment_models, alignment_audio,
    editorial_review, structure_selection, wav2vec2_forced_alignment)
if len(sys.argv) > 1:
    importlib.import_module(sys.argv[1])
forbidden = ('stemwise_engine.mms_forced_alignment', 'stemwise_engine.lyrics_align_pc',
             'torch', 'ctranslate2')
loaded = sorted(name for name in sys.modules if any(
    name == root or name.startswith(root + '.') for root in forbidden))
assert not loaded, 'Forbidden stage import: ' + ', '.join(loaded)
'''


class AlignmentStageTests(unittest.TestCase):
    def test_stage_modules_import_without_cli_or_model_construction(self):
        env = dict(os.environ, PYTHONPATH=str(SRC))
        subprocess.run([sys.executable, '-B', '-c', STAGE_IMPORT_CHECK],
            check=True, capture_output=True, timeout=30, env=env)

    def test_stage_import_guard_rejects_forbidden_modules_and_descendants(self):
        env = dict(os.environ, PYTHONPATH=str(SRC))
        for name in ('stemwise_engine.mms_forced_alignment', 'stemwise_engine.lyrics_align_pc'):
            with self.subTest(name=name):
                result = subprocess.run([sys.executable, '-B', '-c', STAGE_IMPORT_CHECK, name],
                    capture_output=True, text=True, timeout=30, env=env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Forbidden stage import: ' + name, result.stderr)
        for name in ('stemwise_engine.mms_forced_alignment.child',
                     'stemwise_engine.lyrics_align_pc.child', 'torch.child', 'ctranslate2.child'):
            with self.subTest(name=name):
                result = subprocess.run([sys.executable, '-B', '-c',
                    'import sys; sys.modules[sys.argv.pop()] = object()\n' + STAGE_IMPORT_CHECK, name],
                    capture_output=True, text=True, timeout=30, env=env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Forbidden stage import: ' + name, result.stderr)

    def test_handoffs_snapshot_caller_owned_inputs_and_keep_transition_state_separate(self):
        config = {'analysisTimeoutMinutes': 1}
        context = AlignmentContext(config=config, video_id='abcdefghijk', workspace=None,
            temp_root=Path('.'), device='cpu', duration_ms=1000, endpoint_duration_ms=1000)
        original = {'text': 'one two'}
        mapping = {'performedOccurrences': [{'sourcePhysicalIndex': 0}]}
        words = [[(0, 3, 'one'), (4, 7, 'two')]]
        sources = [0]
        text = EndpointText(original=original, alignment=None, lexical_mapping=mapping,
            sources=sources, source_occurrences=(0,), display_word_lines=words, acoustic_language_lines=None)
        segments = [{'segmentIndex': 0}]
        witnesses = ({'route': 'known answer'},)
        evidence = EndpointEvidence(baseline_segments=segments, seeded_mms_segments=None,
            verifier_candidates=witnesses, verifier_family='known answer', verifier_reused=False,
            wav2vec2_language='en', free_family_candidates={}, independent_inputs={},
            reviewed_lrc_starts={}, route_names={})
        config['analysisTimeoutMinutes'] = 999
        original['text'] = 'wrong'
        mapping['performedOccurrences'].clear()
        words[0].clear()
        sources.clear()
        segments.clear()
        witnesses[0]['route'] = 'changed'
        self.assertEqual(context.config['analysisTimeoutMinutes'], 1)
        self.assertEqual(text.original['text'], 'one two')
        self.assertEqual(text.lexical_mapping['performedOccurrences'], [{'sourcePhysicalIndex': 0}])
        self.assertEqual(text.sources, (0,))
        self.assertEqual(len(text.display_word_lines[0]), 2)
        self.assertEqual(evidence.baseline_segments, [{'segmentIndex': 0}])
        self.assertEqual(evidence.verifier_candidates, ({'route': 'known answer'},))
        with self.assertRaises(FrozenInstanceError):
            context.duration_ms = 2000

    def test_audio_view_owner_reuses_output_and_closes_temporary_resources_after_failure_or_cancel(self):
        import shutil
        import wave
        from types import SimpleNamespace
        from unittest.mock import Mock
        from stemwise_engine.alignment_audio import AlignmentAudioViews
        from stemwise_engine.common import sha256_file
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        from stemwise_engine.lyrics_align_pc import _worker_pcm_duration_ms
        for failure in (None, RuntimeError('boundary failure'), KeyboardInterrupt()):
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                audio = root/'mix.wav'
                with wave.open(str(audio), 'wb') as stream:
                    stream.setnchannels(2)
                    stream.setsampwidth(2)
                    stream.setframerate(48000)
                    stream.writeframes(bytes(4800*4))
                workspace = AlignmentWorkspace(root/'agent', workspace_root=root)
                generated = []
                def separate(source, destination, **kwargs):
                    destination.mkdir(parents=True)
                    generated.append(destination)
                    if failure is not None:
                        raise failure
                    vocals = destination/'vocals.wav'
                    shutil.copyfile(source, vocals)
                    return vocals
                release = Mock()
                owner = AlignmentAudioViews(config={'projectDir': str(root), 'analysisTimeoutMinutes': 1},
                    args=SimpleNamespace(device='cpu'), workspace=workspace, video_id='abcdefghijk',
                    duration_ms=100, expected_mix_sha256=sha256_file(audio), analysis_audio=audio,
                    temp_root=root/'temporary', release_model=release, pinned_models={'demucsModelSha256':'d'*64},
                    state={}, runtime_cache={}, route_records={}, failure_codes={},
                    separate_vocals=separate, run_roformer=Mock(side_effect=AssertionError('unused route')),
                    worker_duration=_worker_pcm_duration_ms)
                try:
                    if failure is None:
                        result = owner.get_endpoint_audio('htdemucs-vocals')
                        self.assertIs(owner.get_endpoint_audio('htdemucs-vocals'), result)
                        self.assertEqual(result[1]['sourceAudioSha256'], sha256_file(audio))
                    else:
                        with self.assertRaises(type(failure)):
                            owner.get_endpoint_audio('htdemucs-vocals')
                finally:
                    owner.close()
                    owner.close()
                self.assertEqual(len(generated), 1)
                self.assertFalse(generated[0].exists())
                self.assertEqual(release.call_count, 2)

    def test_recognition_evidence_keeps_each_transcript_bound_to_its_route_and_cache(self):
        from stemwise_engine.alignment_attempt import RecognitionEvidenceSet
        evidence = RecognitionEvidenceSet()
        words = ['known', 'words']
        path = Path('known.private.json')
        evidence.add('native:mix', words, True, path)
        words.clear()
        evidence.add('whisper:mix', ['other'], False)
        self.assertEqual(evidence.transcripts, [['known', 'words'], ['other']])
        self.assertEqual(evidence.labels, ['native:mix', 'whisper:mix'])
        self.assertEqual(evidence.reused, [True, False])
        self.assertEqual(evidence.path_for('native:mix'), path)
        self.assertIsNone(evidence.path_for('whisper:mix'))
