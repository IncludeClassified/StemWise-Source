"""Content uncertainty cannot be converted into timing authority."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
from stemwise_engine.lyric_content import (confirm_content, require_content, require_exact_sequence,
                           require_timing_only, validate_content_admission_record)
from lyric_content_fixtures import retained_content, write_recognition
from stemwise_engine.workflow_records import LyricsContentBlocker, FutureSongOutcomeError


class LyricContentTests(unittest.TestCase):
    def test_compiled_physical_lines_keep_every_word_in_content_admission(self):
        from stemwise_engine.lyrics_intake import compile_draft
        from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
        from stemwise_engine.song_enrichment_v2 import validate_lyrics, require_publication_lyrics
        from stemwise_engine.structure_selection import _baseline_source_indices
        for separator in ('\u2028', '\u2029', '\u0085', '\n', '\r\n', '\r'):
            for mixed in (0, 1, 2):
                with self.subTest(separator=repr(separator), mixed=mixed), \
                        tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                    draft = Path(name)
                    text = 'alpha' + separator + 'beta' + ('\n'*mixed + 'gamma' if mixed else '')
                    (draft/'original.txt').write_bytes(text.encode('utf-8'))
                    (draft/'lyrics.meta.json').write_text(json.dumps({'videoId': 'abcdefghijk',
                        'originalLanguage': 'en', 'originalScript': 'Latn',
                        'sources': {'original': {'name': 'Owner'}}}), encoding='utf-8')
                    compiled = compile_draft(draft, {'outputDir': str(draft/'output')}, {'abcdefghijk'}, expected_video_id='abcdefghijk')
                    validate_lyrics(compiled)
                    require_publication_lyrics(compiled)
                    original = compiled['tracks'][0]
                    indices = _baseline_source_indices(original['text'])
                    unicode_separator = separator in ('\u2028', '\u2029', '\u0085')
                    self.assertEqual(indices, ((0, mixed) if mixed else (0,)) if unicode_separator
                        else ((0, 1, mixed+1) if mixed else (0, 1)))
                    self.assertEqual(original['text'], text.replace('\r\n', '\n').replace('\r', '\n'))
                    mapping = build_lexical_occurrence_mapping('abcdefghijk', original, indices)
                    self.assertEqual([row['sourcePhysicalIndex'] for row in mapping['performedOccurrences']], list(indices))
                    self.assertEqual(sum(len(row['wordIds']) for row in mapping['authorityLines']), 3 if mixed else 2)
                    complete = ['alpha', 'beta'] + (['gamma'] if mixed else [])
                    for omitted in range(len(complete)):
                        with self.subTest(omitted=omitted), self.assertRaises(LyricsContentBlocker):
                            require_exact_sequence(original, indices,
                                ' '.join(complete[:omitted] + complete[omitted+1:]), None, None)
                    require_exact_sequence(original, indices, ' '.join(complete), None, None)
                    explicit = (*indices[::-1], indices[0])
                    words = {0: ['alpha', 'beta']} if unicode_separator else {0: ['alpha'], 1: ['beta']}
                    if mixed:
                        words[mixed if unicode_separator else mixed+1] = ['gamma']
                    performed = [word for source in explicit for word in words[source]]
                    explicit_mapping = build_lexical_occurrence_mapping('abcdefghijk', original, explicit)
                    self.assertEqual([row['sourcePhysicalIndex'] for row in explicit_mapping['performedOccurrences']], list(explicit))
                    require_exact_sequence(original, explicit, ' '.join(performed), None, None)
                    with self.assertRaises(LyricsContentBlocker):
                        require_exact_sequence(original, explicit, ' '.join(performed[:-1]), None, None)
                    with self.assertRaises(LyricsContentBlocker):
                        require_exact_sequence(original, (*indices, len(original['text'])+1), ' '.join(complete), None, None)

    def test_intake_canonical_arabic_language_reuses_exact_recording_adjudication(self):
        from stemwise_engine.common import atomic_write_json
        from stemwise_engine.lyrics_intake import compile_draft, read_recording_draft
        from stemwise_engine.lyric_content import ADJUDICATION_FILE
        for language in ('arz', 'ar-EG'):
            with self.subTest(language=language), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
                root = Path(name)
                job = root / 'jobs/abcdefghijk'
                drafts = job / 'drafts'
                drafts.mkdir(parents=True)
                (drafts / 'original.txt').write_text('واحد اثنان ثلاثة\n', encoding='utf-8')
                atomic_write_json(drafts / 'lyrics.meta.json', {'videoId': job.name,
                    'originalLanguage': language, 'originalScript': 'Arab', 'sources': {'original': {'name': 'Owner'}}})
                compiled = compile_draft(drafts, {'outputDir': str(root / 'output')}, {job.name}, expected_video_id=job.name)
                original = compiled['tracks'][0]
                self.assertEqual(original['language'], 'ar')
                _, draft_sha, _, metadata_sha = read_recording_draft(job)
                evidence = write_recognition(root / 'evidence', original, (0,), recognized='واحد ثلاثة')
                review = {'formatVersion': 2, 'videoId': job.name, 'audioSha256': 'a'*64,
                    'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                    'recognition': {'path': evidence.name, 'sha256': hashlib.sha256(evidence.read_bytes()).hexdigest()},
                    'reviewer': 'Known operator', 'completedAt': '2026-10-24T00:00:00Z',
                    'spans': [{'performedIndex': 0, 'sourcePhysicalIndex': 0, 'startWordIndex': 1, 'endWordIndex': 2,
                        'recognizedStartWordIndex': 1, 'recognizedEndWordIndex': 1, 'startMs': 100, 'endMs': 200,
                        'decision': 'confirmed-asr-omission', 'heardText': 'اثنان', 'rationale': 'Known exact recording span.'}]}
                atomic_write_json(job / ADJUDICATION_FILE, review)
                args = dict(evidence_path=evidence, evidence_root=evidence.parent, adjudication_job_dir=job)
                admission = confirm_content(original, (0,), 'c'*64, 'a'*64, **args)
                self.assertIsNotNone(admission.adjudication)
                changed = json.loads((drafts / 'lyrics.meta.json').read_bytes())
                changed['originalLanguage'] = 'ar'
                atomic_write_json(drafts / 'lyrics.meta.json', changed)
                with self.assertRaises(LyricsContentBlocker):
                    confirm_content(original, (0,), 'c'*64, 'a'*64, **args)

    def test_reviewed_omission_substitution_and_insertion_bind_every_current_consumer(self):
        from stemwise_engine.common import atomic_write_json
        from stemwise_engine.lyrics_intake import read_recording_draft
        from stemwise_engine.lyric_content import ADJUDICATION_FILE, require_admitted_sequence
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace, AlignmentResult, AlignmentMetrics
        from stemwise_engine.alignment_phases import StructureSelectionRequest
        from stemwise_engine.structure_selection import StructureSelectionStage
        from types import SimpleNamespace
        from stemwise_engine.workflow_records import ContentEvidenceUnavailable
        original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en', 'text': 'one two three'}
        cases = (('one three', 1, 2, 1, 1, 'omission', 'two'),
                 ('one wrong three', 1, 2, 1, 2, 'substitution', 'two'),
                 ('one extra two three', 1, 1, 1, 2, 'insertion', ''))
        for recognized, start, end, first, last, decision, heard in cases:
            with self.subTest(decision=decision), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
                root = Path(name)
                job = root / 'jobs/abcdefghijk'
                (job / 'drafts').mkdir(parents=True)
                (job / 'drafts/original.txt').write_text(original['text'] + '\n', encoding='utf-8')
                atomic_write_json(job / 'drafts/lyrics.meta.json', {'originalLanguage': 'en'})
                _, draft_sha, _, metadata_sha = read_recording_draft(job)
                workspace = AlignmentWorkspace(root, workspace_root=root)
                evidence = write_recognition(workspace.job_dir('abcdefghijk'), original, (0,), recognized=recognized)
                before = evidence.read_bytes()
                args = dict(evidence_path=evidence, evidence_root=evidence.parent, adjudication_job_dir=job)
                with self.assertRaises(LyricsContentBlocker):
                    confirm_content(original, (0,), 'c'*64, 'a'*64, **args)
                review = {'formatVersion': 2, 'videoId': 'abcdefghijk', 'audioSha256': 'a'*64,
                    'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                    'recognition': {'path': evidence.name, 'sha256': hashlib.sha256(before).hexdigest()},
                    'reviewer': 'Fixture operator', 'completedAt': '2026-10-24T00:00:00Z',
                    'spans': [{'performedIndex': 0, 'sourcePhysicalIndex': 0, 'startWordIndex': start,
                        'endWordIndex': end, 'recognizedStartWordIndex': first, 'recognizedEndWordIndex': last,
                        'startMs': 100, 'endMs': 200, 'decision': 'confirmed-asr-' + decision,
                        'heardText': heard, 'rationale': 'Independently confirmed this exact recording span.'}]}
                atomic_write_json(job / ADJUDICATION_FILE, review)
                admission = confirm_content(original, (0,), 'c'*64, 'a'*64, **args)
                self.assertIsNotNone(admission.adjudication)
                checked = require_content(admission.to_record(), original, (0,), 'c'*64, 'a'*64,
                    evidence.parent, adjudication_job_dir=job)
                self.assertEqual(checked, admission)
                require_admitted_sequence(checked, original, (0,), recognized, **args)
                require_timing_only(('line-start-disagreement',), checked)
                self.assertEqual(evidence.read_bytes(), before)
                exact = write_recognition(root / 'exact', original, (0,))
                exact.replace(evidence.parent / 'exact.private.json')
                exact = evidence.parent / 'exact.private.json'
                latest = confirm_content(original, (0,), 'c'*64, 'a'*64,
                    evidence_path=exact, evidence_root=evidence.parent)
                self.assertIsNone(latest.adjudication)
                result = AlignmentResult(True, (), (50,), (0,),
                    AlignmentMetrics(3, 3, 3, 1, 1, 1., 1., 1., 100., .8))
                stage = StructureSelectionStage(request=StructureSelectionRequest(video_id='abcdefghijk',
                    original=original, duration_ms=1000, repeat_structure_kind='none',
                    reviewed_baseline_structure=False, performed_structure_review_sha256=None,
                    evidence_root=evidence.parent), options=[('known-timing', result, {}, True)],
                    merge_transcripts=[[SimpleNamespace(raw=word) for word in text.split()]
                        for text in (recognized, original['text'])],
                    merge_evidence_paths=[evidence, exact], progress={
                        'canonicalPlainSha256': 'c'*64, 'contentBinding': latest.binding,
                        'contentAdmission': latest.to_record()})
                self.assertIs(stage.select().alignment, result)
                stage.merge_evidence_paths = [exact, evidence]
                with self.assertRaises(LyricsContentBlocker):
                    stage.select()
                from stemwise_engine.endpoint_ctc_evidence import require_endpoint_content
                declaration = {'contentAdmission': admission.to_record(),
                    'canonicalPlainSha256': 'c'*64, 'analysisAudio': {'sha256': 'a'*64}}
                occurrence = {'performedIndex': 0, 'sourcePhysicalIndex': 0,
                    'occurrenceId': 'independent-known-occurrence',
                    'publishDecision': {'result': 'unresolved', 'code': 'line-start-disagreement'}}
                mapping = {'performedOccurrences': [occurrence]}
                packet = {'occurrenceCandidates': [occurrence]}
                self.assertEqual(require_endpoint_content(declaration, mapping, packet,
                    {'tracks': [original]}, evidence.parent), admission)
                uncertain = copy.deepcopy(packet)
                uncertain['occurrenceCandidates'][0]['publishDecision']['code'] = 'ambiguous-pronunciation'
                with self.assertRaises(LyricsContentBlocker):
                    require_endpoint_content(declaration, mapping, uncertain, {'tracks': [original]}, evidence.parent)
                with self.assertRaises(LyricsContentBlocker):
                    require_admitted_sequence(checked, original, (0,), recognized,
                        evidence_path=evidence.with_name('another-recognizer.json'), evidence_root=evidence.parent,
                        adjudication_job_dir=job)
                with self.assertRaises(LyricsContentBlocker):
                    require_admitted_sequence(checked, original, (0,), recognized.replace('three', 'uncertain'), **args)
                changed = copy.deepcopy(review)
                changed['spans'][0]['rationale'] = 'Changed decision file'
                atomic_write_json(job / ADJUDICATION_FILE, changed)
                with self.assertRaises(LyricsContentBlocker):
                    require_content(admission.to_record(), original, (0,), 'c'*64, 'a'*64,
                        evidence.parent, adjudication_job_dir=job)
                (job / ADJUDICATION_FILE).write_bytes(b'{malformed review')
                with self.assertRaises(LyricsContentBlocker):
                    confirm_content(original, (0,), 'c'*64, 'a'*64, **args)
                atomic_write_json(job / ADJUDICATION_FILE, review)
                (job / 'drafts/lyrics.meta.json').write_bytes(b'{"originalLanguage":"fr"}')
                with self.assertRaises(LyricsContentBlocker):
                    require_content(admission.to_record(), original, (0,), 'c'*64, 'a'*64,
                        evidence.parent, adjudication_job_dir=job)

    def test_canonical_apostrophes_agree_for_recognition_and_explicit_omission_review(self):
        from stemwise_engine.lyric_content import require_recording_adjudication
        from stemwise_engine.lyrics_tokenization import lexical_authority_sha256
        original = {'id': 'original', 'kind': 'original', 'language': 'en', 'text': "Don't go slow"}
        self.assertEqual(lexical_authority_sha256(original),
                         lexical_authority_sha256({**original, 'text': 'Don’t go slow'}))
        with self.subTest(comparison='recognition'):
            require_exact_sequence(original, (0,), 'Don’t go slow', 'c'*64, 'a'*64)
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            _, evidence = retained_content(root, original, (0,))
            review = {'formatVersion': 2, 'videoId': 'abcdefghijk', 'audioSha256': 'a'*64,
                'draftSha256': 'c'*64, 'metadataSha256': 'd'*64,
                'recognition': {'path': evidence.name, 'sha256': hashlib.sha256(evidence.read_bytes()).hexdigest()},
                'reviewer': 'Fixture reviewer', 'completedAt': '2026-10-24T00:00:00Z',
                'spans': [{'performedIndex': 0, 'sourcePhysicalIndex': 0, 'startWordIndex': 1,
                    'endWordIndex': 2, 'recognizedStartWordIndex': 1, 'recognizedEndWordIndex': 1, 'startMs': 100, 'endMs': 200,
                    'decision': 'confirmed-asr-omission', 'heardText': 'go', 'rationale': 'Exact heard omission.'}]}
            before = evidence.read_bytes()
            with self.subTest(comparison='explicit-review'):
                result = require_recording_adjudication(review, video_id='abcdefghijk', original=original,
                    indices=(0,), transcript='Don’t slow', audio_sha='a'*64, duration_ms=1000,
                    draft_sha='c'*64, metadata_sha='d'*64, evidence_path=evidence, evidence_root=root)
                self.assertEqual(result['kind'], 'explicit-recording-span-adjudication')
            self.assertEqual(evidence.read_bytes(), before)
        for changed in ('Don t go slow', 'Dont go slow', 'Don’t go slow go slow', 'Don’t slow'):
            with self.subTest(changed=changed), self.assertRaises(LyricsContentBlocker):
                require_exact_sequence(original, (0,), changed, 'c'*64, 'a'*64)

    def test_unavailable_private_recognition_is_retryable_without_mutating_evidence(self):
        from stemwise_engine.workflow_records import FutureSongOutcomeError
        original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en', 'text': 'one two'}
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            for body in (None, b'{broken', b'[]'):
                path = root / 'recognition.private.json'
                if body is not None:
                    path.write_bytes(body)
                with self.assertRaises(FutureSongOutcomeError) as caught:
                    confirm_content(original, (0,), 'c'*64, 'a'*64,
                                    evidence_path=path, evidence_root=root)
                self.assertEqual(caught.exception.code, 'retryable-technical')
                self.assertNotIsInstance(caught.exception, LyricsContentBlocker)
                self.assertEqual(path.read_bytes() if path.exists() else None, body)

    def test_exact_retained_words_admit_only_timing_recovery(self):
        original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en',
                    'text': 'One two\nThree four'}
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            admission, path = retained_content(root, original, (0, 1))
            raw = path.read_bytes()
            record = admission.to_record()
            verified = require_content(record, original, (0, 1), 'c'*64, 'a'*64, root)
            self.assertEqual(verified, admission)
            require_timing_only(('line-start-disagreement', 'line-end-disagreement',
                'qualified-word-contradiction'), verified)
            self.assertEqual(path.read_bytes(), raw)
            self.assertNotIn('One', json.dumps(record))

    def test_partial_wrong_extra_and_missing_marks_are_uncertain(self):
        for original, recognized in (('one two three four', 'one two three'),
                ('one two three four', 'one two wrong four'),
                ('one two', 'one two extra'), ('عَلَم', 'علم'), ('café', 'cafe')):
            with self.subTest(original=original, recognized=recognized), \
                    self.assertRaises(LyricsContentBlocker):
                require_exact_sequence(original, (0,), recognized, 'c'*64, 'a'*64)

    def test_declared_turkish_case_is_shared_with_authority_identity(self):
        from stemwise_engine.lyrics_tokenization import lexical_authority_sha256
        for upper, lower in (('IŞIK', 'ışık'), ('İÇİN', 'için')):
            original = {'id': 'original', 'kind': 'original', 'language': 'tr', 'text': upper}
            require_exact_sequence(original, (0,), lower, 'c'*64, 'a'*64)
            self.assertEqual(lexical_authority_sha256(original),
                lexical_authority_sha256({**original, 'text': lower}))
        with self.assertRaises(LyricsContentBlocker):
            require_exact_sequence({'language': 'tr', 'text': 's'}, (0,), 'ş', 'c'*64, 'a'*64)

    def test_repeated_occurrences_cannot_be_omitted_expanded_or_rebound(self):
        original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en',
                    'text': 'one two\none two'}
        with self.assertRaises(LyricsContentBlocker):
            require_exact_sequence(original, (0, 1), 'one two', 'c'*64, 'a'*64)
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            admission, _ = retained_content(root, original, (0, 1))
            for indices in ((0,), (1, 0), (0, 1, 0)):
                with self.subTest(indices=indices), self.assertRaises(LyricsContentBlocker):
                    require_content(admission.to_record(), original, indices, 'c'*64, 'a'*64, root)

    def test_booleans_or_unknown_failure_do_not_confirm_content(self):
        original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en', 'text': 'one two'}
        for record in (None, True, {'accepted': True}, {'contentConfirmed': True}):
            with self.subTest(record=record), self.assertRaises(LyricsContentBlocker):
                require_content(record, original, (0,), 'c'*64, 'a'*64, ROOT)
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            admission, _ = retained_content(Path(name), original, (0,))
            for reasons in ((), ('low-lyric-word-coverage',), ('pronunciation-unsupported',), ('unknown',)):
                with self.subTest(reasons=reasons), self.assertRaises(LyricsContentBlocker):
                    require_timing_only(reasons, admission)

    def test_artifact_change_wrong_audio_or_stale_producer_blocks_without_mutation(self):
        original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en',
                    'text': 'one two three four'}
        for corruption in ('word', 'audio', 'producer', 'digest'):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
                root = Path(name)
                admission, path = retained_content(root, original, (0,))
                record = admission.to_record()
                value = json.loads(path.read_bytes())
                if corruption == 'word':
                    value['workerOutput']['words'][1]['raw'] = 'wrong'
                elif corruption == 'audio':
                    value['provenance']['audioSha256'] = 'b'*64
                elif corruption == 'producer':
                    value['provenance']['helpers']['mms_asr.py'] = 'b'*64
                else:
                    record['evidence']['sha256'] = 'b'*64
                path.write_text(json.dumps(value), encoding='utf-8')
                raw = path.read_bytes()
                with self.assertRaises(FutureSongOutcomeError) as caught:
                    require_content(record, original, (0,), 'c'*64, 'a'*64, root)
                self.assertEqual(caught.exception.code, 'lyrics-content-unresolved' if corruption == 'word'
                                 else 'retryable-technical')
                self.assertEqual(path.read_bytes(), raw)

    def test_serialization_validation_never_requires_or_grants_artifact_authority(self):
        original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en', 'text': 'one two'}
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            admission, path = retained_content(Path(name), original, (0,))
            record = admission.to_record()
            path.unlink()
            self.assertEqual(validate_content_admission_record(record), record)
            with self.assertRaises(FutureSongOutcomeError) as caught:
                require_content(record, original, (0,), 'c'*64, 'a'*64, Path(name))
            self.assertEqual(caught.exception.code, 'retryable-technical')
            for unsafe in ('../outside.json', '/absolute.json', 'C:/outside.json', 'a\\b.json'):
                altered = copy.deepcopy(record)
                altered['evidence']['path'] = unsafe
                with self.subTest(unsafe=unsafe), self.assertRaises(LyricsContentBlocker):
                    validate_content_admission_record(altered)
