"""Source-backed content survives an outlier without borrowing its word timings."""
import hashlib
import copy
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import numpy as np
import soundfile as sf

from unit import test_source_recording_content as source_fixture
from unit.lyric_content_fixtures import write_recognition
from stemwise_engine import lyric_content, recording_evidence, source_recording_content
from stemwise_engine.alignment_attempt import RecognitionAdmission, RecognitionAdmissionRequest
from stemwise_engine.common import atomic_write_json, sha256_file
from stemwise_engine.lyrics_alignment import AlignmentWorkspace, AlignmentResult, AlignmentMetrics


class SourceContentWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture = source_fixture.SourceRecordingContentTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.workspace = AlignmentWorkspace(fixture.root, workspace_root=fixture.root)
        fixture.evidence = self.workspace.job_dir(fixture.video)
        fixture.evidence.mkdir(parents=True)
        fixture.primary_path = fixture.evidence/'qwen-structure-en.private.json'
        fixture.original['text'] = 'You feel the sunshine\nWe sing together now\n\nYou feel the sunshine'
        fixture.draft.write_text(fixture.original['text']+'\n', encoding='utf-8')
        fixture.source_path.write_text('You feel the sunshine\nWe sing together now\n', encoding='utf-8')
        fixture.source_record.update(sha256=sha256_file(fixture.source_path),
            byteCount=fixture.source_path.stat().st_size)
        fixture.selected['sha256'] = fixture.source_record['sha256']
        fixture.publish_sources()
        self.words = fixture.original['text'].split()
        fixture.publish_primary(' '.join(self.words))
        self.audio = fixture.root/'original.wav'
        sf.write(self.audio, np.zeros(32000, dtype=np.float32), 16000, subtype='PCM_16')
        self.audio_sha = sha256_file(self.audio)
        fixture.cache['provenance']['audio']['sha256'] = self.audio_sha
        fixture.cache['workerOutput']['audio']['sha256'] = self.audio_sha
        atomic_write_json(fixture.primary_path, fixture.cache)
        self.inputs = fixture.inputs(audio_sha=self.audio_sha)
        self.config = {'projectDir': str(fixture.root/'project'), 'analysisTimeoutMinutes': 1}
        self.contrary = self.workspace.transcript_path(fixture.video, 'en', 'unprompted-mix')
        actual = ['We', *self.words[1:]]
        atomic_write_json(self.contrary, {'cacheVersion': 3, 'videoId': fixture.video,
            'model': 'large-v3+unprompted', 'language': 'en',
            'promptSha256': hashlib.sha256(b'').hexdigest(), 'cacheIdentity': {'diagnostic': True}, 'evidence': {},
            'provenance': {'audio': fixture.cache['provenance']['audio'], 'durationMs': 2000,
                'decoder': {'promptMode': 'withheld'}},
            'words': [{'word': word.lower(), 'raw': word, 'start': i*.1, 'end': i*.1+.08}
                for i, word in enumerate(actual)]})

    def decision(self):
        return source_recording_content.create(**self.inputs, selected_source=self.fixture.selected)

    def installed_admission(self):
        fixture = self.fixture
        reference = self.decision()
        draft, draft_sha, metadata, metadata_sha = recording_evidence.read_recording_draft(fixture.job)
        atomic_write_json(fixture.job/'job.json', {'videoId': fixture.video, 'state': 'WAITING_FOR_EDITORIAL',
            'recordingContentEvidence': {'audioSha256': self.audio_sha, 'draftSha256': draft_sha,
                'metadataSha256': metadata_sha, 'evidence': reference}})
        plain = {'tracks': [fixture.original]}
        raw = (json.dumps(plain, ensure_ascii=False)+'\n').encode('utf-8')
        canonical_sha = hashlib.sha256(raw).hexdigest()
        self.workspace.canonical_plain.mkdir()
        backup = self.workspace.canonical_plain/'current-plain.json'
        backup.write_bytes(raw)
        atomic_write_json(self.workspace.backup_pointer_path(fixture.video), {'pointerVersion': 1,
            'videoId': fixture.video, 'fileName': backup.name, 'sha256': canonical_sha})
        state = {'canonicalPlainSha256': canonical_sha}
        self.workspace.write_state(fixture.video, state)
        admission = RecognitionAdmission(RecognitionAdmissionRequest(fixture.video,
            json.dumps(fixture.original).encode('utf-8'), (0, 1, 3), canonical_sha, self.audio_sha),
            self.workspace, state)
        value = admission.admit(fixture.evidence/reference['path'])
        return admission, value, reference

    def test_compare_retains_outlier_and_returns_actual_bound_decision(self):
        fixture = self.fixture
        before = self.contrary.read_bytes()
        with patch.object(recording_evidence, '_load_or_run_qwen3_structure_transcript',
                return_value=(' '.join(self.words), fixture.cache['provenance'], True)):
            result = recording_evidence.compare(self.config, fixture.root, fixture.video, self.audio, self.audio_sha)
        self.assertTrue(result['structureSupport']['accepted'])
        reference = result['sourceRecordingDecision']
        self.assertEqual(Path(result['privateTranscript']), fixture.evidence/reference['path'])
        record = source_recording_content.current_admission(reference, **self.inputs)
        self.assertEqual(len(record['contrary']), 1)
        self.assertEqual(record['contrary'][0]['scope']['optimalEditCost'], 1)
        self.assertEqual(self.contrary.read_bytes(), before)
        self.assertFalse((fixture.job/'recording-evidence-adjudication.json').exists())
        self.assertFalse((fixture.job/'owner-verified-lyrics.json').exists())

    def test_outlier_cannot_supply_timing_and_merge_uses_only_matching_observation(self):
        fixture = self.fixture
        recognition, admission, reference = self.installed_admission()
        wrong = [SimpleNamespace(raw=word) for word in ['We', *self.words[1:]]]
        matching = [SimpleNamespace(raw=word) for word in self.words]
        align = Mock(side_effect=AssertionError('outlier reached timing alignment'))
        rejected = recognition.align(wrong, 2000, align,
            evidence_path=fixture.evidence/reference['path'])
        self.assertFalse(rejected.accepted)
        self.assertEqual(rejected.starts_ms, ())
        align.assert_not_called()
        result = AlignmentResult(True, (), (100, 600, 1400), (0, 1, 3),
            AlignmentMetrics(12, 12, 12, 3, 3, 1., 1., 1., 100., .8))
        merge = Mock(return_value=result)
        self.assertIs(recognition.align_many([wrong, matching], [fixture.evidence/reference['path']]*2,
            2000, merge), result)
        self.assertEqual(merge.call_args.args[2], [matching])
        excluded = recognition.align_many([wrong], [fixture.evidence/reference['path']], 2000,
            Mock(side_effect=AssertionError('empty eligible merge reached alignment')))
        self.assertFalse(excluded.accepted)
        self.assertEqual(excluded.starts_ms, ())
        self.assertIs(recognition.align_many([wrong, matching], [None, fixture.evidence/reference['path']],
            2000, merge), result)
        self.assertEqual(merge.call_args.args[2], [matching])
        from stemwise_engine.structure_selection import StructureSelectionStage
        from stemwise_engine.alignment_phases import StructureSelectionRequest
        stage = StructureSelectionStage(request=StructureSelectionRequest(video_id=fixture.video,
            original=fixture.original, duration_ms=2000, repeat_structure_kind='none',
            reviewed_baseline_structure=False, performed_structure_review_sha256=None,
            evidence_root=fixture.evidence), options=[('excluded', rejected, {}, True), ('matched', result, {}, True)],
            merge_transcripts=[wrong, matching], merge_evidence_paths=[None, fixture.evidence/reference['path']],
            progress=recognition.state)
        self.assertIs(stage.select().alignment, result)
        self.assertIsNone(admission.owner_verification)
        self.assertIsNone(admission.adjudication)

    def test_current_source_decision_reopens_exact_old_content_dispute(self):
        from stemwise_engine.lyrics_align_pc import _require_no_unchanged_content_block
        recognition, admission, reference = self.installed_admission()
        wrong = ' '.join(['We', *self.words[1:]])
        scope = lyric_content.diagnostic_discrepancy_scope(self.fixture.original, (0, 1, 3), wrong)
        prior = {'status': 'lyrics-content-unresolved', 'reasons': ['content-sequence-uncertain'],
            'contentBinding': admission.binding,
            'contentDispute': {'path': self.contrary.name, 'sha256': sha256_file(self.contrary), 'scope': scope}}
        _require_no_unchanged_content_block(prior, admission.canonical_plain_sha256, self.audio_sha,
            workspace=self.workspace, video_id=self.fixture.video, original=self.fixture.original)
        before = (self.fixture.evidence/reference['path']).read_bytes()
        self.fixture.source_path.write_text('Wrong published words\n', encoding='utf-8')
        with self.assertRaises((lyric_content.LyricsContentBlocker, lyric_content.ContentEvidenceUnavailable)):
            lyric_content.require_content(admission.to_record(), self.fixture.original, (0, 1, 3),
                admission.canonical_plain_sha256, self.audio_sha, self.fixture.evidence,
                adjudication_job_dir=self.fixture.job)
        self.assertEqual((self.fixture.evidence/reference['path']).read_bytes(), before)

    def coarse_case(self):
        recognition, admission, reference = self.installed_admission()
        path = write_recognition(self.fixture.evidence, self.fixture.original, (0, 1, 3),
            self.audio_sha, recognized=' '.join(['We', *self.words[1:]]))
        cache = json.loads(path.read_bytes())
        cache['provenance']['durationMs'] = cache['workerOutput']['durationMs'] = 2000
        for index, word in enumerate(cache['workerOutput']['words']):
            word.update(start=index*.15, end=index*.15+.1)
        atomic_write_json(path, cache)
        return recognition, admission, path

    def test_exact_coarse_anchors_preserve_correct_words_without_endpoint_authority(self):
        from stemwise_engine.structure_selection import StructureSelectionStage
        from stemwise_engine.alignment_phases import StructureSelectionRequest
        from stemwise_engine.alignment_attempt import RecognitionEvidenceSet
        from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
        from stemwise_engine.lyrics_tokenization import display_words
        from stemwise_engine import alignment_endpoints as endpoints, lyrics_align_pc
        recognition, admission, path = self.coarse_case()
        before = path.read_bytes()
        result, evidence = recognition.source_line_anchors(path, 2000)
        self.assertTrue(result.accepted)
        self.assertEqual(result.starts_ms, (150, 600, 1200))
        self.assertEqual([row['rawWordIndex'] for row in evidence['anchors']], [1, 4, 8])
        self.assertEqual(result.metrics.matched_word_count, 11)
        self.assertEqual(evidence['recognition']['sha256'], hashlib.sha256(before).hexdigest())
        stage = StructureSelectionStage(request=StructureSelectionRequest(video_id=self.fixture.video,
            original=self.fixture.original, duration_ms=2000, repeat_structure_kind='none',
            reviewed_baseline_structure=False, performed_structure_review_sha256=None,
            evidence_root=self.fixture.evidence), options=[('coarse', result, evidence, True)],
            merge_transcripts=[], merge_evidence_paths=[], progress=recognition.state)
        self.assertIs(stage.select().alignment, result)
        ledger = RecognitionEvidenceSet()
        mapping = build_lexical_occurrence_mapping(self.fixture.video, self.fixture.original, (0, 1, 3))
        free = endpoints.select_free_candidates(self.fixture.original, result, stage.options,
            ledger.transcripts, ledger.labels, ledger.reused, 2000, mapping)
        self.assertEqual(free, {})
        segments = lyrics_align_pc._build_initial_alignment_segments(self.fixture.original['text'],
            [0, 1, 3], mapping, 2000, free_family_candidates=free,
            transcripts=ledger.transcripts, transcript_labels=ledger.labels)
        self.assertEqual(segments, [{'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 3,
            'windowStartMs': 0, 'windowEndMs': 2000}])
        forced, verifier = [], []
        for line, offset in zip(mapping['performedOccurrences'], (0, 600, 1200)):
            words = [{'wordId': word['occurrenceWordId'], 'startMs': offset+index*100,
                'endMs': offset+index*100+80, 'confidence': .95, 'acousticTokenCount': 1}
                for index, word in enumerate(line['words'])]
            forced.append({'lineId': line['occurrenceId'], 'sourcePhysicalIndex': line['sourcePhysicalIndex'],
                'startMs': words[0]['startMs'], 'endMs': words[-1]['endMs'], 'confidence': .95, 'words': words})
            verifier.append({**copy.deepcopy(forced[-1]), 'route': endpoints.WAV2VEC2_ROUTE,
                'words': [{**word, 'startUtf16': start, 'endUtf16': end}
                    for word, (start, end, _) in zip(words,
                        display_words(self.fixture.original['text'].splitlines()[line['sourcePhysicalIndex']]))]})
        timings, summary = endpoints._refine_public_timings({'lines': forced}, tuple(verifier))
        self.assertEqual(summary['resultClass'], 'committed-words')
        self.assertEqual(timings[0]['words'][0]['startMs'], 0, 'coarse Feel anchor became the You endpoint')
        verifier[0]['words'] = verifier[0]['words'][1:]
        _, missing = endpoints._refine_public_timings({'lines': forced}, tuple(verifier))
        self.assertNotEqual(missing['resultClass'], 'committed-words')
        self.assertEqual(path.read_bytes(), before)
        self.assertIsNone(admission.owner_verification)

    def test_coarse_anchors_refuse_an_entire_unobserved_line(self):
        recognition, _, path = self.coarse_case()
        cache = json.loads(path.read_bytes())
        del cache['workerOutput']['words'][4:8]
        atomic_write_json(path, cache)
        self.assertIsNone(recognition.source_line_anchors(path, 2000))
        self.assertIsNone(recognition.source_line_anchors(None, 2000))


if __name__ == '__main__':
    unittest.main()
