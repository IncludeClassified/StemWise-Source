"""Bounded content ownership and real retained-observation investigation checks."""
import hashlib
import json
import sys
import tempfile
import unittest
from types import ModuleType
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'PC side/stemwise-playlist-builder/src'
sys.path.insert(0, str(ROOT / 'Test suite/pc/unit'))

from stemwise_engine.acoustic_inputs import AcousticBudget
from stemwise_engine.lyric_content import diagnostic_discrepancy_scope, investigate_retained_content, require_exact_sequence
from stemwise_engine.lyrics_alignment import (AlignmentWorkspace, AlignmentResult, AlignmentMetrics,
    build_lexical_occurrence_mapping, build_revision_mapping, snapshot_canonical_plain,
    save_alignment_artifacts)
from lyric_content_fixtures import write_recognition, retained_content
from stemwise_engine.workflow_records import LyricsContentBlocker, ContentEvidenceUnavailable
from test_qwen3_asr_transcribe import output_fixture, generation_fixture


def original(text):
    return {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en', 'text': text}


class ContentRevisionContracts(unittest.TestCase):
    def test_reference_adaptation_separates_repeats_and_whole_line_omissions_from_changed_words(self):
        from stemwise_engine.lyric_content import reference_adaptation
        cases = (
            ('Opening verse\nUnused verse\nClosing verse', 'Opening verse Closing verse',
                'review-required', ['omit-reference-lines'], 'Opening verse\nClosing verse\n'),
            ('Opening verse\n\nUnused verse\n\nClosing verse', 'Opening verse Closing verse',
                'review-required', ['omit-reference-lines'], 'Opening verse\n\nClosing verse\n'),
            ('Hold my hand', 'Hold my hand Hold my hand',
                'review-required', ['repeat-known-phrase'], 'Hold my hand\nHold my hand\n'),
            ('I repent repent repent', 'I repent',
                'review-required', ['reduce-known-repeat'], 'I repent\n'),
            ('يا رب', 'يا رب يا رب', 'review-required', ['repeat-known-phrase'], 'يا رب\nيا رب\n'),
            ('You feel I feel', 'We feel I feel', 'unresolved', ['unresolved-wording'], None),
            ('I know Oh Allah You forgive', 'I know You forgive',
                'unresolved', ['unresolved-wording'], None),
            ('Hold my hand', 'Hold my hand again', 'unresolved', ['unresolved-wording'], None),
        )
        for reference, recognition, status, kinds, expected_text in cases:
            with self.subTest(reference=reference, recognition=recognition):
                result, text = reference_adaptation(original(reference), recognition)
                self.assertEqual(text, expected_text)
                self.assertEqual(result['status'], status)
                self.assertEqual([change['kind'] for change in result['changes']], kinds)
                self.assertIs(result['publicationAuthority'], False)
                self.assertIs(result['timingAuthority'], False)
        self.assertIsNone(reference_adaptation(original('One line'), 'One line'))
        self.assertIsNone(reference_adaptation(original('One line'), ''))

    def setUp(self):
        from unittest.mock import patch
        boundary = patch('stemwise_engine.lyrics_align_pc._whisper_recognition_cache_identity',
            return_value={'fixture': 'current-unprompted-whisper-runtime'})
        boundary.start()
        self.addCleanup(boundary.stop)
        # Substitute only the optional recognizer's language metadata. Retained
        # artifact validation, capability selection and lexical comparison stay real.
        self.whisper_catalog = ModuleType('faster_whisper.tokenizer')
        self.whisper_catalog._LANGUAGE_CODES = ('en',)
        catalog_boundary = patch.dict(sys.modules, {'faster_whisper.tokenizer': self.whisper_catalog})
        catalog_boundary.start()
        self.addCleanup(catalog_boundary.stop)

    def test_repeated_omission_retains_both_owners_and_allocation_failure_is_explicit(self):
        track = original('A\nA')
        with self.assertRaises(LyricsContentBlocker) as caught:
            require_exact_sequence(track, (0, 1), 'A', 'c'*64, 'a'*64)
        self.assertEqual(caught.exception.discrepancy_scope['possiblePerformedIndices'], [0, 1])
        self.assertTrue(caught.exception.discrepancy_scope['complete'])
        bounded = diagnostic_discrepancy_scope(track, (0, 1), 'A', budget=AcousticBudget(available_bytes=8))
        self.assertFalse(bounded['complete'])
        self.assertEqual(bounded['possiblePerformedIndices'], [0, 1])
        localized = diagnostic_discrepancy_scope(original('before\nA\nbetween\nA\nafter'),
            (0, 1, 2, 3, 4), 'before A between after')
        self.assertEqual(localized['possiblePerformedIndices'], [3])

    def test_revision_maps_shifted_unique_regions_but_reassesses_changed_neighbors(self):
        before, after = original('one\ntwo\nthree\nfour'), original('new\none\ntwo\nchanged\nfour')
        revision = build_revision_mapping('abcdefghijk', before, after, (0,1,2,3), (0,1,2,3,4))
        self.assertEqual([(entry['previousPerformedIndex'], entry['currentPerformedIndex'])
            for entry in revision['regions']], [(0,1), (1,2), (3,4)])
        self.assertTrue(all(entry['assessmentState'] == 'requires-reassessment' for entry in revision['regions']))
        self.assertFalse(revision['regions'][1]['neighborContextUnchanged'])
        self.assertNotEqual(revision['regions'][0]['previousOccurrenceId'], revision['regions'][0]['currentOccurrenceId'])
        ambiguous = build_revision_mapping('abcdefghijk', original('A\nA'), original('A'), (0,1), (0,))
        self.assertEqual(ambiguous['regions'], [])
        exhausted = build_revision_mapping('abcdefghijk', before, after, (0,1,2,3), (0,1,2,3,4),
            budget=AcousticBudget(available_bytes=8))
        self.assertFalse(exhausted['complete'])
        self.assertEqual(exhausted['regions'], [])

    def test_normal_snapshot_and_artifact_path_binds_and_revalidates_revision_map(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root, workspace_root=root)
            old, current = original('one\ntwo'), original('new\none\ntwo')
            raw = lambda track: json.dumps({'tracks':[track]}).encode('utf-8')
            old_path, _ = snapshot_canonical_plain(workspace, 'abcdefghijk', raw(old))
            prior = old_path.read_bytes()
            _, canonical_sha = snapshot_canonical_plain(workspace, 'abcdefghijk', raw(current))
            admission, evidence = retained_content(workspace.job_dir('abcdefghijk'), current,
                (0,1,2), canonical_sha)
            workspace.write_state('abcdefghijk', {'contentAdmission':admission.to_record(),
                'contentBinding':admission.binding})
            mapping = build_lexical_occurrence_mapping('abcdefghijk', current, (0,1,2))
            result = AlignmentResult(True, (), (0,100,200), (0,1,2),
                AlignmentMetrics(3,3,3,3,3,1.,1.,1.,100.,1.))
            save_alignment_artifacts(workspace, 'abcdefghijk', candidate=None, result=result, lexical_mapping=mapping)
            artifact = json.loads(workspace.mapping_path('abcdefghijk').read_bytes())
            self.assertIn('revisionContinuity', artifact)
            self.assertEqual(artifact['revisionContinuity']['reassessedEvidence'], admission.to_record())
            self.assertEqual(old_path.read_bytes(), prior)
            revision_path = workspace.job_dir('abcdefghijk') / 'revision-map.private.json'
            changed = json.loads(revision_path.read_bytes())
            changed['regions'][0]['neighborContextUnchanged'] = True
            revision_path.write_text(json.dumps(changed), encoding='utf-8')
            candidate_path = workspace.candidate_path('abcdefghijk')
            candidate_path.write_text(json.dumps({'tracks': [current]}), encoding='utf-8')
            candidate_before = candidate_path.read_bytes()
            mapping_before = workspace.mapping_path('abcdefghijk').read_bytes()
            with self.assertRaisesRegex(ValueError, 'continuity'):
                save_alignment_artifacts(workspace, 'abcdefghijk', candidate={'tracks': [original('replacement')]},
                    result=result, lexical_mapping=mapping)
            self.assertEqual(candidate_path.read_bytes(), candidate_before)
            self.assertEqual(workspace.mapping_path('abcdefghijk').read_bytes(), mapping_before)

    def test_unchanged_snapshot_recomputes_old_policy_from_retained_canonical_pair(self):
        from unittest.mock import patch
        from stemwise_engine.provenance import content_assessment_identity
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root, workspace_root=root)
            video = 'abcdefghijk'
            before, current = original('one\ntwo'), original('new\none\ntwo')
            raw = lambda track: json.dumps({'tracks': [track]}).encode('utf-8')
            old_policy = {**content_assessment_identity(), 'contentPolicy': 'f' * 64}
            with patch('stemwise_engine.provenance.content_assessment_identity', return_value=old_policy):
                previous_path, previous_sha = snapshot_canonical_plain(workspace, video, raw(before))
                current_path, current_sha = snapshot_canonical_plain(workspace, video, raw(current))
            retained = {path: path.read_bytes() for path in (previous_path, current_path)}
            candidate_path = workspace.candidate_path(video)
            candidate_path.write_bytes(raw(current))
            candidate_before = candidate_path.read_bytes()
            revision_path = workspace.job_dir(video) / 'revision-map.private.json'
            mapping = build_lexical_occurrence_mapping(video, current, (0, 1, 2))
            result = AlignmentResult(True, (), (0, 100, 200), (0, 1, 2),
                AlignmentMetrics(3, 3, 3, 3, 3, 1., 1., 1., 100., 1.))

            snapshot_canonical_plain(workspace, video, raw(current))
            save_alignment_artifacts(workspace, video, candidate=None, result=result, lexical_mapping=mapping)
            expected = build_revision_mapping(video, before, current, (0, 1), (0, 1, 2))
            expected.update(previousCanonicalPlainSha256=previous_sha, currentCanonicalPlainSha256=current_sha)
            self.assertEqual(json.loads(revision_path.read_bytes()), expected)
            self.assertEqual(json.loads(workspace.mapping_path(video).read_bytes())['revisionContinuity'], expected)
            self.assertEqual(candidate_path.read_bytes(), candidate_before)
            self.assertEqual({path: path.read_bytes() for path in retained}, retained)

            for fault in ('region', 'current-hash', 'previous-bytes', 'missing-previous'):
                with self.subTest(fault=fault):
                    changed = json.loads(json.dumps(expected))
                    if fault == 'region':
                        changed['regions'][0]['neighborContextUnchanged'] = not changed['regions'][0]['neighborContextUnchanged']
                    elif fault == 'current-hash':
                        changed['currentCanonicalPlainSha256'] = 'a' * 64
                    elif fault == 'previous-bytes':
                        previous_path.write_bytes(b'changed source')
                    else:
                        previous_path.unlink()
                    revision_path.write_text(json.dumps(changed), encoding='utf-8')
                    protected = {path: path.read_bytes() for path in
                        (candidate_path, workspace.mapping_path(video), revision_path,
                         workspace.backup_pointer_path(video), current_path)}
                    with self.assertRaises(ValueError):
                        snapshot_canonical_plain(workspace, video, raw(current))
                    self.assertEqual({path: path.read_bytes() for path in protected}, protected)
                    previous_path.write_bytes(retained[previous_path])
            revision_path.write_text(json.dumps(expected), encoding='utf-8')

    def _bundle(self, root, *, qwen_text='A A'):
        from stemwise_engine.model_registry import model_assets
        from stemwise_engine.provenance import semantic_source_identity
        from stemwise_engine.qwen3_asr_transcribe import DECODER
        track = original('A\nA')
        pins = model_assets('lyrics_align_pc')
        for name, language, text in (('qwen-structure-en.private.json', 'en', 'A'),
                ('qwen-structure.private.json', None, 'language English<asr_text>' + qwen_text)):
            request = {'audio':{'sha256':'a'*64, 'byteCount':12}, 'language':language, 'model':{
                'revision':pins['QWEN3_ASR_REVISION'], 'snapshotSha256':pins['QWEN3_ASR_SNAPSHOT_SHA256']}}
            output = output_fixture(request)
            output['result'] = generation_fixture(text, language)
            cache = {'cacheVersion':1, 'provenance':{'audio':{**request['audio'], 'stream':{'durationMs':1000}},
                'language':language, 'modelRevision':pins['QWEN3_ASR_REVISION'],
                'modelSnapshotSha256':pins['QWEN3_ASR_SNAPSHOT_SHA256'], 'decoder':DECODER,
                'helperSha256':semantic_source_identity(SOURCE / 'qwen3_asr_transcribe.py'),
                'runtime':{'runtime':output['runtime'], 'supportedLanguages':['en']}}, 'workerOutput':output}
            (root / name).write_text(json.dumps(cache), encoding='utf-8')
        contrary = root / 'qwen-structure-en.private.json'
        from stemwise_engine.lyrics_align_pc import _whisper_recognition_cache_identity
        from stemwise_engine.lyrics_alignment import TRANSCRIPT_CACHE_VERSION
        witness = {'cacheVersion':TRANSCRIPT_CACHE_VERSION, 'cacheIdentity':_whisper_recognition_cache_identity(),
            'promptSha256':hashlib.sha256(b'').hexdigest(), 'provenance':{'decoder':{'promptMode':'withheld'},
                'audio':{'sha256':'a'*64, 'stream':{'durationMs':1000}}, 'durationMs':1000},
            'words':[{'raw':'A', 'start':0., 'end':.1}, {'raw':'A', 'start':.2, 'end':.3}]}
        (root / 'transcript.en.private.json').write_text(json.dumps(witness), encoding='utf-8')
        return track, contrary

    def test_retained_two_family_full_context_resolves_entire_repeat_scope_without_mutation(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            track, contrary = self._bundle(root)
            (root/'qwen-structure-broken.private.json').write_bytes(b'{broken')
            prior = {path:path.read_bytes() for path in root.iterdir()}
            resolution = investigate_retained_content(track, (0,1), 'a'*64, 1000,
                evidence_root=root, contrary_path=contrary, canonical_sha='c'*64)
            self.assertEqual(resolution['resolvedPerformedIndices'], [0,1])
            self.assertEqual(resolution['newDecodeCount'], 0)
            self.assertIs(resolution['publicationAuthority'], False)
            request = json.loads((root / resolution['request']['path']).read_bytes())
            self.assertEqual(request['canonicalRangeMs'], [0,1000])
            self.assertEqual(request['discrepancyScope']['possiblePerformedIndices'], [0,1])
            for path, raw in prior.items():
                self.assertEqual(path.read_bytes(), raw)
            self.assertEqual(investigate_retained_content(track, (0,1), 'a'*64, 1000,
                evidence_root=root, contrary_path=contrary, canonical_sha='c'*64), resolution)
            request_path = root / resolution['request']['path']
            request['canonicalRangeMs'] = [0, 999]
            request_path.write_bytes(json.dumps(request).encode('utf-8'))
            retained = {path: path.read_bytes() for path in root.iterdir()}
            with self.assertRaises(ContentEvidenceUnavailable) as caught:
                investigate_retained_content(track, (0,1), 'a'*64, 1000,
                    evidence_root=root, contrary_path=contrary, canonical_sha='c'*64)
            self.assertEqual(caught.exception.reasons, ('content-evidence-changed',))
            self.assertEqual(caught.exception.binding, {'canonicalPlainSha256': 'c'*64,
                'audioSha256': 'a'*64, 'sourcePhysicalIndices': [0,1]})
            self.assertEqual({path: path.read_bytes() for path in root.iterdir()}, retained)

    def test_substring_fails_and_unsupported_family_cannot_cast_a_vote(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            track, contrary = self._bundle(root, qwen_text='padding A A extra')
            self.assertIsNone(investigate_retained_content(track, (0,1), 'a'*64, 1000,
                evidence_root=root, contrary_path=contrary))
            self._bundle_reset(root)
            track, contrary = self._bundle(root)
            write_recognition(root, track, (0,1), recognized='wrong')
            self.assertIsNotNone(investigate_retained_content(track, (0,1), 'a'*64, 1000,
                evidence_root=root, contrary_path=contrary))

    def test_unsupported_retained_language_cannot_supply_the_required_family_vote(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            track, contrary = self._bundle(root)
            prior = {path: path.read_bytes() for path in root.iterdir()}
            self.whisper_catalog._LANGUAGE_CODES = ('fr',)
            self.assertIsNone(investigate_retained_content(track, (0,1), 'a'*64, 1000,
                evidence_root=root, contrary_path=contrary))
            self.assertEqual({path: path.read_bytes() for path in root.iterdir()}, prior)

    @staticmethod
    def _bundle_reset(root):
        for path in root.iterdir():
            path.unlink()


if __name__ == '__main__':
    unittest.main()
