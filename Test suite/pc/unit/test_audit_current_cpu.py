"""Deterministic regressions at the current PC production boundaries."""
import base64
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

ROOT = Path(__file__).resolve().parents[3]


class CurrentCpuAuditTests(unittest.TestCase):
    def test_retained_additional_youtube_recording_remains_admitted(self):
        from stemwise_engine.common import require_youtube_audio_allowed, YouTubeAudioReplacedError
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            root = Path(temporary)
            selection = root/'comparison-results/library-files.json'
            selection.parent.mkdir()
            value = {'playlist': [{'videoId': 'abcdefghijk', 'source': 'qobuz', 'trackId': 11}],
                     'youtube': [{'videoId': 'abcdefghijk', 'file': 'audio/youtube/abcdefghijk/source.opus'}]}
            selection.write_text(json.dumps(value), encoding='utf-8')
            require_youtube_audio_allowed('abcdefghijk', root=root)
            value['youtube'] = []
            selection.write_text(json.dumps(value), encoding='utf-8')
            with self.assertRaises(YouTubeAudioReplacedError):
                require_youtube_audio_allowed('abcdefghijk', root=root)

    def test_retained_owner_sidecars_use_current_contract(self):
        from stemwise_engine.song_enrichment_v2 import validate_lyrics
        owner = ROOT/'PC side/stemwise-playlist-builder/owner-input'
        paths = sorted(owner.glob('*.lyrics.json'))
        self.assertTrue(paths)
        for path in paths:
            with self.subTest(sidecar=path.name):
                value = json.loads(path.read_text(encoding='utf-8'))
                validate_lyrics(value)

    def test_multilingual_library_qa_matches_direct_declared_components(self):
        from stemwise_engine.lyrics_qa import audit_library, audit_text
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            root = Path(temporary)
            (root/'owner-input').mkdir()
            (root/'work').mkdir()
            original = {'id': 'original', 'kind': 'original', 'format': 'plain',
                'source': {'name': 'Owner-supplied'}, 'language': 'mul', 'script': 'Zyyy',
                'componentLanguages': ['ur', 'fr'], 'text': 'آب\u200cوتاب\nBonjour : salut'}
            (root/'work/inventory.json').write_text(json.dumps({'items': [
                {'videoId': 'abcdefghijk', 'position': 1, 'title': 'Selected'}]}), encoding='utf-8')
            path = root/'owner-input/abcdefghijk.lyrics.json'
            for text in (original['text'], original['text']+'\u202e'):
                original['text'] = text
                path.write_text(json.dumps({'status': 'partial',
                    'reason': 'English translation and IPA are pending owner review.',
                    'tracks': [original]}), encoding='utf-8')
                actual = audit_library({'projectDir': str(root), 'outputDir': str(root)})
                expected = audit_text(text, 'mul', 'Zyyy', component_languages=['ur', 'fr'])
                report = json.loads(Path(actual['report']).read_text(encoding='utf-8'))
                self.assertEqual(report['invalidSidecarCount'], 0)
                song = report['songs'][0]
                self.assertEqual(song['original']['issues'], expected['issues'])
                self.assertTrue(song['requiresEnglishTranslation'])
                self.assertTrue(song['missingEnglishTranslation'])
                self.assertTrue(song['requiresIpa'])
                self.assertTrue(song['missingIpa'])
                self.assertFalse(song['ipaRequirementPending'])

    def test_plan_never_exports_or_retires_audio(self):
        import stemwise_media.enrichment_plan as plan
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            root = Path(temporary)
            (root/'catalogue.json').write_text('{"rows": []}', encoding='utf-8')
            (root/'comparison-results').mkdir()
            (root/'comparison-results/library-files.json').write_text('{}', encoding='utf-8')
            with patch.object(plan, 'ROOT', root), patch.object(plan.batch, 'export_library',
                    side_effect=AssertionError('planning mutated source selection')), \
                    patch.object(plan.batch, 'build_library', return_value={}, create=True), \
                    patch.object(plan, 'build_plan', return_value={'playlistCounts': {}, 'recordingCounts': {}}):
                plan.save_plan()

    def test_waveform_stereo_phase_preserves_energy_and_peaks(self):
        from stemwise_engine.waveform import waveform_from_samples
        signal = np.array([.5, -.5]*120, dtype=np.float32)
        decoded = []
        for right in (signal, -signal):
            value = waveform_from_samples(np.column_stack((signal, right)), 48000, chunk_frames=17)
            decoded.append(struct.unpack('<hhH', base64.b64decode(value['levels'][0]['data'])))
        self.assertEqual(decoded[0], decoded[1])
        self.assertEqual(decoded[0], (-16384, 16384, 16384))

    def test_definite_download_rejection_releases_only_rejected_reservation(self):
        import stemwise_media.doubledouble as module
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            root = Path(temporary)
            replies = [{'active': False}, {'success': False, 'error': 'rejected'},
                       {'active': False}, {'success': True, 'id': 'Exact'}, {'status': 'done'}]
            with patch.object(module, 'ROOT', root), patch.object(module, '_read_json', side_effect=replies), \
                    patch.object(module, '_save_download', return_value={'status': 'saved'}):
                with self.assertRaisesRegex(RuntimeError, 'rejected'):
                    module.download(11)
                self.assertEqual(module.download(11), {'status': 'saved'})
            with patch.object(module, 'ROOT', root), patch.object(module, '_read_json',
                    side_effect=[{'active': False}, TimeoutError('response lost')]):
                with self.assertRaises(TimeoutError):
                    module.download(22)
            with patch.object(module, 'ROOT', root), patch.object(module, '_read_json',
                    side_effect=AssertionError('must not resubmit')):
                with self.assertRaisesRegex(ValueError, 'submission is unknown'):
                    module.download(22)

    def test_semantic_identity_ignores_prose_but_binds_operations(self):
        from stemwise_engine.provenance import semantic_source_identity
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            source = Path(temporary)/'algorithm.py'
            source.write_text('"""purpose"""\ndef calculate(x):\n    """explain"""\n    return x+1\n', encoding='utf-8')
            original = semantic_source_identity(source)
            source.write_text('# comment\n"""changed prose"""\ndef calculate(x):\n    """new explanation"""\n    return x + 1\n', encoding='utf-8')
            self.assertEqual(semantic_source_identity(source), original)
            source.write_text('def calculate(x):\n    return x+2\n', encoding='utf-8')
            self.assertNotEqual(semantic_source_identity(source), original)

    def test_selection_edits_do_not_block_unrelated_current_recordings(self):
        from stemwise_engine.delivery_selection import catalogue
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            root = Path(temporary)
            workflow = SimpleNamespace(workspace_root=root, pc_side_root=root/'PC side')
            path = workflow.pc_side_root/'qobuz-comparison/comparison-results/library-files.json'
            path.parent.mkdir(parents=True)
            for source in ('youtube', 'qobuz'):
                path.write_text(json.dumps({'playlist': [
                    {'videoId': 'abcdefghijk', 'source': 'youtube'},
                    {'videoId': 'lmnopqrstuv', 'source': source, 'trackId': 11}]}), encoding='utf-8')
                self.assertEqual(catalogue(workflow)['abcdefghijk'], 'youtube:abcdefghijk')

    def test_every_consensus_publisher_respects_reviewed_start(self):
        from stemwise_engine.alignment_endpoints import _refine_public_timings, _apply_independent_endpoint_consensus, OWSM_CTC_FAMILY, NEMO_NFA_FAMILY
        forced = {'lines': [{'lineId': 'one', 'sourcePhysicalIndex': 0,
            'startMs': 1000, 'endMs': 1500, 'confidence': .9, 'words': []}]}
        for start, ready in ((2000, 0), (1200, 1)):
            candidates = ({'route': 'independent', 'startMs': start, 'endMs': start+500},)
            baseline = _refine_public_timings(forced, (None,),
                reviewed_lrc_starts={0: 1000}, include_unresolved=True)
            timings, summary = _apply_independent_endpoint_consensus(baseline,
                {OWSM_CTC_FAMILY: candidates, NEMO_NFA_FAMILY: candidates}, include_unresolved=True)
            self.assertEqual(summary['lineReadyCount'], ready)
            if ready:
                self.assertLessEqual(abs(timings[0]['startMs']-1000), 300)
