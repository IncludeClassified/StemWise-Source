"""Synthetic contract examples, not translation or performed-pronunciation qualification."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from stemwise_engine.companion_review import (CONVENTIONS, REVIEW_CHECKS, admit_companion,
    companion_binding, companion_review_packet, review_evidence_snapshot, validate_companion_review)
from stemwise_engine.common import json_bytes


class CompanionReviewTests(unittest.TestCase):
    def test_original_review_needs_no_generic_companion_approval(self):
        from stemwise_engine.workflow_review import review_template
        from stemwise_engine.editorial_review import validate_agent_review
        review = review_template('AAAAAAAAAAA')
        review.update(authorityOutcome='resolved', reviewer='Known-answer reviewer',
            completedAt='2026-11-13T00:00:00Z', sourceDisposition='accepted-exact-recording',
            recordingOrSourceAssociationReviewed=True, youtubeSurfaceVariantsReviewed=True,
            publicSourceResearchComplete=True, originalReviewed=True, unresolvedMaterialIssues=[])
        self.assertNotIn('exactLineAlignmentReviewed', review)
        self.assertEqual(review['companionReviews'], {})
        self.assertEqual(validate_agent_review(review, video_id='AAAAAAAAAAA',
            available_lyric_source_ids={'AAAAAAAAAAA'}), 'resolved')
        review['originalReviewed'] = False
        with self.assertRaisesRegex(ValueError, 'original lyrics were not reviewed'):
            validate_agent_review(review, video_id='AAAAAAAAAAA', available_lyric_source_ids={'AAAAAAAAAAA'})

    def test_input_rejection_does_not_hide_programming_or_filesystem_failures(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        from stemwise_engine.editorial_admission import available_lyrics
        from stemwise_engine.workflow_records import FutureSongError
        root = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory(dir=root/'Test suite/work') as directory:
            workflow = SimpleNamespace(root=Path(directory), workspace_root=Path(directory),
                _preflight_drafts=Mock())
            with patch('stemwise_engine.lyrics_alignment.AlignmentWorkspace'):
                workflow._preflight_drafts.side_effect = FutureSongError('invalid-original-input')
                self.assertEqual(available_lyrics(workflow, {'videoId': 'AAAAAAAAAAA'}, {})[0]['status'], 'unavailable')
                for failure in (TypeError('unexpected programming fault'), PermissionError('unreadable input')):
                    workflow._preflight_drafts.side_effect = failure
                    with self.assertRaises(type(failure)):
                        available_lyrics(workflow, {'videoId': 'AAAAAAAAAAA'}, {})

    def setUp(self):
        root = Path(__file__).resolve().parents[3]
        self.directory = tempfile.TemporaryDirectory(dir=root/'Test suite/work')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.evidence = self.root/'source.json'
        self.evidence.write_bytes(b'{"fixture":"synthetic known answer"}\n')
        self.reference = {'path': self.evidence.name,
            'sha256': hashlib.sha256(self.evidence.read_bytes()).hexdigest()}
        self.original = {'id': 'original', 'kind': 'original', 'format': 'plain',
            'language': 'fr', 'script': 'Latn', 'source': {'name': 'Synthetic original'},
            'text': 'bonjour monde\n\nbonjour monde'}
        self.candidate = {'id': 'translation-en', 'kind': 'translation', 'format': 'plain',
            'language': 'en', 'script': 'Latn', 'source': {'name': 'Synthetic translation'},
            'basedOnTrackId': 'original', 'text': 'hello world\n\nhello world'}
        self.content = {'version': 1, 'canonicalPlainSha256': 'c'*64, 'audioSha256': 'a'*64,
            'sourcePhysicalIndices': [0, 2], 'evidence': self.reference}
        self.binding = self.bind(self.candidate)
        self.review = self.decision(self.binding)

    def bind(self, candidate, **changes):
        arguments = {'original_input_sha256': hashlib.sha256(
                (self.original['text']+'\n').encode()).hexdigest(),
            'candidate_input_sha256': hashlib.sha256((candidate['text']+'\n').encode()).hexdigest(),
            'audio_sha256': 'a'*64, 'sources_sha256': 'b'*64, 'content_admission': self.content}
        arguments.update(changes)
        return companion_binding('AAAAAAAAAAA', self.original, candidate, **arguments)

    def decision(self, binding):
        kind = binding['kind']
        return {'formatVersion': 1, 'binding': copy.deepcopy(binding), 'reviewer': 'Synthetic fixture',
            'completedAt': '2026-10-31T00:00:00Z', 'decision': 'admit',
            'coveredOccurrenceIds': list(binding['occurrenceIds']), 'convention': CONVENTIONS[kind],
            'checks': dict.fromkeys(REVIEW_CHECKS[kind], 'reviewed'), 'issues': [],
            'evidence': [self.reference], 'rationale': 'Synthetic known-answer contract fixture.'}

    def admit(self, candidate=None, review=None, binding=None):
        return admit_companion(self.original, candidate or self.candidate,
            review if review is not None else self.review, binding=binding or self.binding,
            required=True, evidence_root=self.root)

    def test_companion_review_retains_exact_owner_content_decision_independently(self):
        from stemwise_engine.lyric_content import OWNER_VERIFICATION_FILE
        self.content['ownerVerification'] = {'path': OWNER_VERIFICATION_FILE, 'sha256': 'd'*64}
        current = self.bind(self.candidate)
        self.assertEqual(current['contentEvidence']['ownerVerification'], self.content['ownerVerification'])
        self.assertNotEqual(self.admit(binding=current)['state'], 'admitted')
        reviewed = self.decision(current)
        self.assertEqual(self.admit(review=reviewed, binding=current)['state'], 'admitted')
        self.content['ownerVerification']['sha256'] = 'e'*64
        changed = self.bind(self.candidate)
        self.assertNotEqual(self.admit(review=reviewed, binding=changed)['state'], 'admitted')
        self.assertEqual(changed['occurrenceIds'], current['occurrenceIds'])
        self.assertEqual(changed['candidateInputSha256'], current['candidateInputSha256'])

    def test_current_review_admits_exact_candidate_and_distinct_repeated_occurrences(self):
        self.assertEqual(self.admit()['state'], 'admitted')
        self.assertEqual(len(set(self.binding['occurrenceIds'])), 2)
        self.assertNotEqual(self.binding['candidateInputSha256'], self.binding['candidateSha256'])

    def test_candidate_only_change_stales_its_decision_and_preserves_other_role(self):
        ipa = {**self.candidate, 'id': 'transliteration-ipa', 'kind': 'transliteration',
               'language': 'fr-fonipa', 'text': 'bɔ̃ʒuʁ mɔ̃d\n\nbɔ̃ʒuʁ mɔ̃d'}
        ipa_binding = self.bind(ipa)
        ipa_review = self.decision(ipa_binding)
        changed = {**self.candidate, 'text': 'good morning world\n\nhello world'}
        self.assertEqual(self.admit(changed, binding=self.bind(changed))['state'], 'stale')
        self.assertEqual(self.admit(ipa, ipa_review, ipa_binding)['state'], 'admitted')

    def test_raw_changes_and_original_evidence_changes_cannot_inherit_review(self):
        for changes in ({'candidate_input_sha256': 'd'*64}, {'original_input_sha256': 'd'*64},
                        {'sources_sha256': 'd'*64}, {'original_metadata_sha256': 'd'*64}):
            with self.subTest(changes=changes):
                self.assertEqual(self.admit(binding=self.bind(self.candidate, **changes))['state'], 'stale')
        content = copy.deepcopy(self.content)
        content['evidence']['sha256'] = 'd'*64
        self.assertEqual(self.admit(binding=self.bind(self.candidate, content_admission=content))['state'], 'stale')

    def test_boolean_approval_and_missing_decision_do_not_admit(self):
        result = admit_companion(self.original, self.candidate, None, binding=self.binding,
                                 required=True, evidence_root=self.root)
        self.assertEqual(result['state'], 'unreviewed')
        self.assertEqual(self.admit(review={'translationReviewed': True})['state'], 'invalid')

    def test_invalid_candidate_reports_its_raw_identity_and_located_syntax_issue(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        from stemwise_engine.editorial_admission import companion_admissions
        from stemwise_engine.lyrics_intake import LyricsInputError
        draft = self.root/'drafts'
        draft.mkdir()
        (draft/'original.txt').write_text(self.original['text']+'\n', encoding='utf-8')
        (draft/'lyrics.meta.json').write_bytes(json_bytes({'videoId': 'AAAAAAAAAAA',
            'originalLanguage': 'fr', 'originalScript': 'Latn',
            'sources': {'original': {'name': 'Synthetic original'}}}))
        malformed = 't͡\n\nkat\n'.encode('utf-8')
        (draft/'transliteration-ipa.txt').write_bytes(malformed)
        workflow = SimpleNamespace(_job_dir=lambda _: self.root,
            _validate_authority_outcome=lambda _: ('pending', {}, {}),
            _preflight_drafts=Mock(side_effect=LyricsInputError('frozen contract rejected input')))
        result = companion_admissions(workflow, {'videoId': 'AAAAAAAAAAA'}, self.original,
            {'sha256': 'a'*64}, None)['transliteration']
        self.assertEqual(result['state'], 'invalid')
        self.assertEqual(result['candidateInputSha256'], hashlib.sha256(malformed).hexdigest())
        self.assertEqual(result['issues'][0], {'code': 'ipa-dangling-tie', 'line': 1, 'position': 2})
        self.assertNotIn('track', result)

    def test_same_line_count_wrong_blank_shape_is_never_waived(self):
        candidate = {**self.candidate, 'text': 'hello world\nhello world\n'}
        binding = self.bind(candidate)
        self.assertEqual(self.admit(candidate, self.decision(binding), binding)['state'], 'invalid')

    def test_missing_duplicate_reordered_occurrence_coverage_is_withheld(self):
        for coverage in (self.binding['occurrenceIds'][:1],
                         self.binding['occurrenceIds'][::-1], self.binding['occurrenceIds']*2):
            review = {**self.review, 'coveredOccurrenceIds': coverage}
            self.assertNotEqual(self.admit(review=review)['state'], 'admitted')

    def test_valid_ipa_syntax_does_not_supply_pronunciation_review(self):
        ipa = {**self.candidate, 'kind': 'transliteration', 'language': 'fr-fonipa',
               'text': 'kat\n\nkat'}
        binding = self.bind(ipa)
        review = self.decision(binding)
        review['checks']['performed-pronunciation'] = 'unresolved'
        self.assertEqual(self.admit(ipa, review, binding)['state'], 'review-required')

    def test_changed_missing_unsafe_or_invalid_evidence_cannot_admit(self):
        before = review_evidence_snapshot({'companionReviews': {'translation': self.review}}, self.root)
        self.evidence.write_bytes(b'changed')
        after = review_evidence_snapshot({'companionReviews': {'translation': self.review}}, self.root)
        self.assertNotEqual(before, after)
        self.assertEqual(self.admit()['state'], 'stale')
        self.evidence.unlink()
        self.assertEqual(self.admit()['state'], 'stale')
        review = copy.deepcopy(self.review)
        review['evidence'][0]['path'] = '../escape.json'
        self.assertEqual(self.admit(review=review)['state'], 'invalid')
        state = review_evidence_snapshot({'companionReviews': {'translation': review}}, self.root)
        self.assertEqual(state['translation']['state'], 'invalid')

    def test_private_parser_rejects_false_times_unknown_fields_and_missing_checks(self):
        for changes in ({'completedAt': '2026-02-30T00:00:00Z'}, {'unexpected': True},
                        {'checks': {}}, {'convention': 'dictionary-default'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_companion_review({**self.review, **changes}, 'translation')

    def test_unexpected_evidence_read_error_propagates_without_partial_success(self):
        with patch('stemwise_engine.companion_review.read_bounded_regular_bytes',
                   side_effect=PermissionError('inaccessible retained evidence')):
            with self.assertRaises(PermissionError):
                review_evidence_snapshot({'companionReviews': {'translation': self.review}}, self.root)
            with self.assertRaises(PermissionError):
                self.admit()

    def test_current_binding_cannot_be_used_for_different_candidate(self):
        changed = {**self.candidate, 'text': 'good morning world\n\nhello world'}
        self.assertEqual(self.admit(changed)['state'], 'stale')

    def test_translation_warning_requires_source_meaning_resolution(self):
        from stemwise_engine.lyrics_qa import audit_text
        candidate = {**self.candidate, 'text': 'hello.. world\n\nhello world'}
        binding = self.bind(candidate)
        review = self.decision(binding)
        audit = audit_text(candidate['text']+'\n', 'en', 'Latn')
        self.assertEqual(self.admit(candidate, review, binding)['state'], 'invalid')
        resolution = {'formatVersion': 1, 'videoId': binding['videoId'],
            'recordingSha256': binding['audioSha256'], 'textSha256': audit['sha256'],
            'trackKind': 'translation', 'issue': audit['issues'][0], 'decision': 'source-meaning-preserved',
            'reviewer': 'Synthetic fixture', 'completedAt': '2026-10-31T00:00:00Z',
            'rationale': 'Synthetic punctuation keeps the reviewed meaning.'}
        result = admit_companion(self.original, candidate, review, binding=binding,
            required=True, evidence_root=self.root, qa_resolutions=[resolution])
        self.assertEqual(result['state'], 'admitted')
        unrelated = admit_companion(self.original, candidate, review, binding=binding,
            required=True, evidence_root=self.root, qa_resolutions=[resolution, {'bad': True}])
        self.assertEqual(unrelated['state'], 'admitted')
        resolution['decision'] = 'literal-performed-content'
        result = admit_companion(self.original, candidate, review, binding=binding,
            required=True, evidence_root=self.root, qa_resolutions=[resolution])
        self.assertEqual(result['state'], 'invalid')

    def test_plain_translation_terminal_lfs_preserve_bound_content_and_corpus_issues(self):
        import json
        from stemwise_engine.companion_review import companion_issues
        from stemwise_engine.lyrics_qa import audit_library, audit_text
        output = self.root/'output'
        (output/'work').mkdir(parents=True)
        owner = self.root/'owner-input'
        owner.mkdir()
        (output/'work/inventory.json').write_bytes(json_bytes({'items': [
            {'videoId': 'AAAAAAAAAAA', 'position': 1}]}))
        for terminal_lfs in (0, 1, 2):
            with self.subTest(terminal_lfs=terminal_lfs):
                self.original['text'] = 'bonjour monde\n\nbonjour monde' + '\n'*terminal_lfs
                candidate = {**self.candidate, 'text': 'hello world\n\nhello world' + '\n'*terminal_lfs}
                before = copy.deepcopy(candidate)
                binding = self.bind(candidate, candidate_input_sha256=hashlib.sha256(
                    candidate['text'].encode()).hexdigest())
                bound = copy.deepcopy(binding)
                audit_bytes = b'hello world\n\nhello world' + b'\n'*max(1, terminal_lfs)
                expected = audit_text(audit_bytes.decode(), 'en', 'Latn')['issues']
                self.assertEqual([issue['code'] for issue in expected],
                                 ['trailing-blank-line'] if terminal_lfs == 2 else [])
                (owner/'AAAAAAAAAAA.lyrics.json').write_bytes(json_bytes({'status': 'partial',
                    'reason': 'Synthetic untimed fixture.', 'tracks': [self.original, candidate]}))
                audit_library({'projectDir': str(self.root), 'outputDir': str(output)})
                report = json.loads((owner/'lyrics-qa-report.json').read_bytes())
                companion = report['songs'][0]['companions']['translation']
                self.assertEqual(companion['issues'], expected)
                self.assertEqual(companion['state'], 'invalid' if terminal_lfs == 2 else 'structurally-valid')
                self.assertEqual(companion['sha256'], hashlib.sha256(candidate['text'].encode()).hexdigest())
                self.assertEqual(companion['reviewStatus'], 'unavailable')
                self.assertNotIn('track', companion)
                self.assertEqual(companion_issues(self.original, candidate), expected)
                result = self.admit(candidate, self.decision(binding), binding)
                self.assertEqual(result['state'], 'invalid' if terminal_lfs == 2 else 'admitted')
                if terminal_lfs < 2:
                    self.assertEqual(result['track'], before)
                self.assertEqual(candidate, before)
                self.assertEqual(binding, bound)

    def test_translation_resolutions_bind_exact_audit_bytes_for_plain_and_synced_candidates(self):
        from stemwise_engine.companion_review import companion_issues
        from stemwise_engine.lyrics_qa import audit_text
        for track_format, terminal_lfs in (('plain', 0), ('plain', 1), ('plain', 2), ('synced', 0), ('synced', 1)):
            with self.subTest(track_format=track_format, terminal_lfs=terminal_lfs):
                original = {**self.original, 'text': 'bonjour monde\n\nbonjour monde' + '\n'*terminal_lfs}
                candidate = {**self.candidate, 'text': 'hello.. world\n\nhello world' + '\n'*terminal_lfs}
                if track_format == 'synced':
                    original['text'] = 'bonjour monde\nbonjour monde' + '\n'*terminal_lfs
                    candidate.pop('text')
                    candidate.update(format='synced', lines=[
                        {'startMs': 0, 'endMs': 1000, 'text': 'hello.. world'},
                        {'startMs': 1000, 'endMs': 2000, 'text': 'hello world' + '\n'*terminal_lfs}])
                text = (candidate['text'] if track_format == 'plain' else 'hello.. world\nhello world' + '\n'*terminal_lfs)
                before = copy.deepcopy(candidate)
                content = {**self.content, 'sourcePhysicalIndices': [0, 1] if track_format == 'synced' else [0, 2]}
                binding = companion_binding('AAAAAAAAAAA', original, candidate,
                    original_input_sha256=hashlib.sha256(original['text'].encode()).hexdigest(),
                    candidate_input_sha256=hashlib.sha256(json_bytes(candidate)).hexdigest(),
                    audio_sha256='a'*64, sources_sha256='b'*64, content_admission=content)
                audit_bytes = text.encode() + (b'\n' if track_format == 'synced' or not terminal_lfs else b'')
                audit = audit_text(audit_bytes.decode(), 'en', 'Latn')
                resolution = {'formatVersion': 1, 'videoId': 'AAAAAAAAAAA',
                    'recordingSha256': 'a'*64, 'textSha256': hashlib.sha256(audit_bytes).hexdigest(),
                    'trackKind': 'translation', 'issue': next(issue for issue in audit['issues']
                        if issue['code'] == 'visual-elongation-or-punctuation'),
                    'decision': 'source-meaning-preserved', 'reviewer': 'Synthetic fixture',
                    'completedAt': '2026-10-31T00:00:00Z',
                    'rationale': 'Synthetic punctuation keeps the reviewed meaning.'}
                arguments = dict(binding=binding, required=True, evidence_root=self.root)
                result = admit_companion(original, candidate, self.decision(binding),
                    **arguments, qa_resolutions=[resolution])
                trailing_blank = terminal_lfs == 2 or (track_format == 'synced' and terminal_lfs == 1)
                self.assertEqual(result['state'], 'invalid' if trailing_blank else 'admitted')
                if trailing_blank:
                    self.assertEqual([issue['code'] for issue in result['issues']], ['trailing-blank-line'])
                else:
                    self.assertEqual(result['track'], before)
                    self.assertEqual(admit_companion(original, candidate, None, **arguments,
                        qa_resolutions=[resolution])['state'], 'unreviewed')
                    stale = {**resolution, 'textSha256': 'd'*64}
                    self.assertEqual(admit_companion(original, candidate, self.decision(binding),
                        **arguments, qa_resolutions=[stale])['state'], 'invalid')
                self.assertEqual(companion_issues(original, candidate), audit['issues'])
                self.assertEqual(candidate, before)
                self.assertEqual(result['binding'], binding)

    def test_corpus_audits_companions_without_inventing_review_authority(self):
        import json
        from stemwise_engine.lyrics_qa import audit_library
        output = self.root/'output'
        (output/'work').mkdir(parents=True)
        owner = self.root/'owner-input'
        owner.mkdir()
        (output/'work/inventory.json').write_bytes(json_bytes({'items': [
            {'videoId': 'AAAAAAAAAAA', 'position': 1}]}))
        translation = {**self.candidate, 'text': 'hello.. world\n\nhello world'}
        ipa = {**self.candidate, 'id': 'transliteration-ipa', 'kind': 'transliteration',
               'language': 'fr-fonipa', 'text': 'kat\n\nkat'}
        (owner/'AAAAAAAAAAA.lyrics.json').write_bytes(json_bytes({'status': 'partial',
            'reason': 'Synthetic untimed fixture.', 'tracks': [self.original, translation, ipa]}))
        audit_library({'projectDir': str(self.root), 'outputDir': str(output)})
        report = json.loads((owner/'lyrics-qa-report.json').read_bytes())
        song = report['songs'][0]
        self.assertEqual(song['originalTrackCount'], 1)
        self.assertEqual(song['reviewStatus'], 'unavailable')
        self.assertEqual(song['companions']['translation']['state'], 'invalid')
        self.assertEqual(song['companions']['transliteration']['state'], 'structurally-valid')
        self.assertEqual(song['companions']['transliteration']['reviewStatus'], 'unavailable')
        self.assertNotIn('hello', json.dumps(report))

    def test_packet_is_current_body_bearing_input_without_invented_approval(self):
        packet = companion_review_packet(self.original, {'translation': self.candidate},
                                         {'translation': self.binding})
        self.assertEqual(packet['original'], self.original)
        self.assertTrue(packet['physicalLines'][1]['stanzaBoundary'])
        self.assertEqual([line['occurrenceId'] for line in packet['physicalLines'] if not line['stanzaBoundary']],
                         self.binding['occurrenceIds'])
        self.assertNotIn('decision', packet['roles']['translation'])
        self.assertEqual(packet['roles']['translation']['binding'], self.binding)
        self.assertIn('meaning', packet['roles']['translation']['requiredChecks'])
        missing = companion_review_packet(self.original, {}, {}, video_id='AAAAAAAAAAA')
        self.assertEqual([line['occurrenceId'] for line in missing['physicalLines'] if not line['stanzaBoundary']],
                         self.binding['occurrenceIds'])
        self.assertEqual(missing['physicalLines'][0]['lineId'], packet['physicalLines'][0]['lineId'])
