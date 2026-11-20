"""Focused runtime regressions for acoustic support and coherent timing witnesses."""
import copy
import json
import sys
import tempfile
import unittest
from dataclasses import fields
from pathlib import Path
from unittest.mock import patch

import numpy as np

import stemwise_engine.alignment_endpoints as endpoints
import stemwise_engine.endpoint_ctc_evidence as ctc
import stemwise_engine.endpoint_repeat_alignment as repeat
from stemwise_engine.acoustic_inputs import AcousticBudget, AcousticBudgetExceeded
from stemwise_engine.endpoint_vocal_evidence import ROUTES
from stemwise_engine.alignment_phases import (AlignmentContext, EndpointText, EndpointEvidence, EndpointAudio,
    EndpointAlignmentRequest, EndpointServices, EndpointWorkers, AlignmentEvents, AlignmentProgress)
from stemwise_engine.lyrics_alignment import AlignmentWorkspace, snapshot_canonical_plain
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'Test suite/pc/unit'))
from lyric_content_fixtures import retained_content


def pair(intervals=((100, 200), (1000, 1100))):
    words = [{"wordId": f"w{i}", "startMs": start, "endMs": end,
              "confidence": .8, "acousticTokenCount": 1} for i, (start, end) in enumerate(intervals)]
    publisher = {"lineId": "line", "sourcePhysicalIndex": 0, "startMs": intervals[0][0],
                 "endMs": intervals[-1][1], "confidence": .8, "words": words}
    verifier = {"lineId": "line", "route": endpoints.WAV2VEC2_ROUTE,
                "startMs": publisher["startMs"], "endMs": publisher["endMs"],
                "words": [dict(word, startUtf16=i*2, endUtf16=i*2+1) for i, word in enumerate(words)]}
    return {"lines": [publisher]}, (verifier,)


def native_word_pair(*, wav_middle=(1000, 1600), mms_middle_confidence=.8):
    from types import SimpleNamespace
    from stemwise_engine import lyrics_align_pc as alignment, wav2vec2_forced_alignment as wav
    from stemwise_engine import mms_forced_alignment as mms
    from stemwise_engine.ctc_worker import chunk_time_map
    from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
    original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en',
                'text': 'one two three\none two three'}
    mapping = build_lexical_occurrence_mapping('TESTVID0001', original, (0, 1))
    owners = mapping['performedOccurrences']
    intervals = ((100, 200), (1000, 1600), (1800, 1900))
    plan = [{'lineId': row['occurrenceId'], 'lineIndex': index, 'sourcePhysicalIndex': index,
             'startWord': index*3, 'wordCount': 3, 'words': [
                 {'wordId': word['occurrenceWordId'], 'startWord': index*3+j, 'wordCount': 1,
                  'targetStart': index*3+j, 'targetEnd': index*3+j+1}
                 for j, word in enumerate(row['words'])]} for index, row in enumerate(owners)]
    forced = mms._aggregate_records(plan, [
        {'startMs': start+index*5000, 'endMs': end+index*5000,
         'confidence': mms_middle_confidence if index == 0 and j == 1 else .8, 'tokenCount': 1}
        for index in range(2) for j, (start, end) in enumerate(intervals)])
    spans = [SimpleNamespace(start=(start+index*5000)//10, end=(end+index*5000)//10, score=-.1)
             for index in range(2) for start, end in (intervals[0], wav_middle, intervals[2])]
    native, _ = wav._result_records(plan, spans, [chunk_time_map(0, 160000, 0, 1000)], 10000)
    verifier = alignment._forced_alignment_verifier_candidates(
        {'route': endpoints.WAV2VEC2_ROUTE, 'durationMs': 10000, 'lines': native},
        [[(0, 3, 'one'), (4, 7, 'two'), (8, 13, 'three')]]*2,
        expected_route=endpoints.WAV2VEC2_ROUTE, duration_ms=10000)
    return original, mapping, {'lines': forced}, verifier


class TimingEvidenceContracts(unittest.TestCase):
    def test_raw_peer_onset_dissent_blocks_complete_assessment_and_repeat_reference(self):
        from types import SimpleNamespace
        from stemwise_engine import owsm_ctc_forced_alignment as owsm
        original, mapping, forced, verifier = native_word_pair(wav_middle=(1250, 1750))
        owner = mapping['performedOccurrences'][0]
        request = [{'lineId': owner['occurrenceId'], 'sourcePhysicalIndex': 0, 'words': [
            {'wordId': word['occurrenceWordId'], 'acousticText': text}
            for word, text in zip(owner['words'], ('one', 'two', 'three'))]}]
        plan = [{'lineId': owner['occurrenceId'], 'tokenIds': [7, 8, 9], 'words': [
            {'wordId': word['occurrenceWordId'], 'targetStart': index, 'targetEnd': index+1}
            for index, word in enumerate(owner['words'])]}]
        witness = owsm._lexical_records(request, plan, [
            SimpleNamespace(token=token, start=start, end=end, score=-.1)
            for token, (start, end) in zip((7, 8, 9), ((10, 20), (75, 90), (180, 190)))], 10., 10000)[0]
        witness['route'] = owsm.ROUTES_BY_LANGUAGE['en']
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        _, summary = endpoints._apply_independent_endpoint_consensus(baseline,
            {endpoints.OWSM_CTC_FAMILY: (witness, None)}, include_unresolved=True)
        self.assertEqual(summary['occurrenceCandidates'][0]['publishDecision']['code'], 'qualified-word-contradiction')
        with self.subTest(consumer='complete-assessment'):
            words, reason = endpoints.assess_complete_word_sequence(forced['lines'][0],
                {endpoints.WAV2VEC2_FAMILY: verifier[0], endpoints.OWSM_CTC_FAMILY: witness},
                publisher_family='mms-fa')
            self.assertIsNone(words, 'Strong peers differ by 500ms despite each agreeing with the publisher')
            self.assertEqual(reason, 'qualified-word-contradiction')
        stale = copy.deepcopy(summary)
        reference = stale['occurrenceCandidates'][0]
        reference.update(publishDecision={'result': 'words'}, selectedPublisherFamily='mms-fa')
        reference.pop('qualifiedWordContradictions', None)
        with self.subTest(consumer='repeat-reference'):
            self.assertIsNone(repeat.reference_plan({'performedIndex': 1}, mapping, stale, {'tracks': [original]}))

    def test_weak_third_word_map_cannot_erase_two_complete_witnesses(self):
        _, _, forced, verifier = native_word_pair(mms_middle_confidence=.01)
        publisher = verifier[0]
        # Known-answer unprompted English observation; recognition is the external boundary here.
        whisper = {'lineId': publisher['lineId'], 'route': 'faster-whisper-precision-unprompted:en',
            'startMs': 110, 'endMs': 1910, 'words': [
                {key: value+10 if key in ('startMs', 'endMs') else value
                 for key, value in word.items() if key not in ('confidence', 'acousticTokenCount')}
                for word in publisher['words']]}
        with self.subTest(case='two-complete-plus-weak-third'):
            words, reason = endpoints.assess_complete_word_sequence(publisher,
                {'mms-fa': forced['lines'][0], endpoints.UNPROMPTED_WHISPER_FAMILY: whisper},
                publisher_family=endpoints.WAV2VEC2_FAMILY)
            self.assertIsNone(reason)
            self.assertEqual([word['startMs'] for word in words], [100, 1000, 1800])
        with self.subTest(case='only-independent-map-weak'):
            words, reason = endpoints.assess_complete_word_sequence(publisher,
                {'mms-fa': forced['lines'][0]}, publisher_family=endpoints.WAV2VEC2_FAMILY)
            self.assertIsNone(words)
            self.assertEqual(reason, 'low-forced-word-support')

    def test_native_owsm_lexical_ends_neither_supply_nor_veto_sung_word_ends(self):
        from types import SimpleNamespace
        from stemwise_engine import lyrics_align_pc as alignment, wav2vec2_forced_alignment as wav
        from stemwise_engine import owsm_ctc_forced_alignment as owsm
        from stemwise_engine.mms_forced_alignment import _aggregate_records
        from stemwise_engine.ctc_worker import chunk_time_map
        line_id, word_id = 'occurrence-'+'a'*24, 'occurrence-word-'+'b'*24
        request = [{'lineId': line_id, 'sourcePhysicalIndex': 0,
                    'words': [{'wordId': word_id, 'acousticText': 'yeah'}]}]
        plan = [{'lineId': line_id, 'lineIndex': 0, 'sourcePhysicalIndex': 0,
                 'words': [{'wordId': word_id, 'targetStart': 0, 'targetEnd': 1}]}]
        native, _ = wav._result_records(plan, [SimpleNamespace(start=50, end=150, score=-.1)],
            [chunk_time_map(0, 64000, 0, 200)], 4000)
        publisher = alignment._forced_alignment_verifier_candidates(
            {'route': endpoints.WAV2VEC2_ROUTE, 'durationMs': 4000, 'lines': native},
            [[(0, 4, 'yeah')]], expected_route=endpoints.WAV2VEC2_ROUTE, duration_ms=4000)[0]
        forced = _aggregate_records([{**plan[0], 'startWord': 0, 'wordCount': 1,
            'words': [{'wordId': word_id, 'startWord': 0, 'wordCount': 1}]}],
            [{'startMs': 1000, 'endMs': 3000, 'confidence': .8, 'tokenCount': 1}])[0]
        baseline = endpoints._refine_public_timings({'lines': [forced]}, (publisher,), include_unresolved=True)
        token_plan = [{'lineId': line_id, 'tokenIds': [7],
                       'words': [{'wordId': word_id, 'targetStart': 0, 'targetEnd': 1}]}]
        for label, start, end in (('matching-end', 50, 150), ('lexical-spike', 50, 54), ('onset-dissent', 70, 74)):
            witness = owsm._lexical_records(request, token_plan,
                [SimpleNamespace(token=7, start=start, end=end, score=-.00196)], 20., 4000)[0]
            witness.update(route=owsm.ROUTES_BY_LANGUAGE['en'],
                           endpointSemantics='first-to-last-approved-lexical-ctc-token-span-v1')
            with self.subTest(case=label, consumer='complete-word-admission'):
                words, _ = endpoints.assess_complete_word_sequence(publisher,
                    {endpoints.OWSM_CTC_FAMILY: witness}, publisher_family=endpoints.WAV2VEC2_FAMILY)
                self.assertIsNone(words, 'One lexical-token end is not a second sung-word-end witness')
            output, summary = endpoints._apply_independent_endpoint_consensus(baseline,
                {endpoints.OWSM_CTC_FAMILY: (witness,)}, include_unresolved=True)
            with self.subTest(case=label, consumer='publication-consensus'):
                if label == 'onset-dissent':
                    self.assertEqual(summary['resultClass'], 'line-unresolved')
                    self.assertNotIn('words', output[0])
                else:
                    self.assertEqual(summary['resultClass'], 'committed-words')
                    self.assertEqual(output[0]['words'][0]['endMs'], 3000)
                retained = summary['occurrenceCandidates'][0]['familyCandidates'][endpoints.OWSM_CTC_FAMILY]
                self.assertEqual(retained['endMs'], end*20)
                self.assertEqual(retained['words'][0]['ctcLogScore'], -.00196)

    def test_native_owsm_semantics_and_acoustic_scores_survive_consensus_readback(self):
        from types import SimpleNamespace
        from stemwise_engine import lyrics_align_pc as alignment, owsm_ctc_forced_alignment as worker
        from stemwise_engine.workflow_diagnostics import _endpoint_evidence_diagnostics
        forced, verifier = pair(((100, 200), (1000, 1600), (2000, 2100)))
        for line in (forced['lines'][0], verifier[0]):
            line['lineId'] = 'occurrence-'+'a'*24
            for index, word in enumerate(line['words']):
                word['wordId'] = 'occurrence-word-'+f'{index:024x}'
        line = forced['lines'][0]
        request_lines = [{'lineId': line['lineId'], 'sourcePhysicalIndex': 0,
            'words': [{'wordId': word['wordId'], 'acousticText': text}
                      for word, text in zip(line['words'], ('held', 'middle', 'word'))]}]
        plan = [{'lineId': line['lineId'], 'tokenIds': [7, 8, 9], 'words': [
            {'wordId': word['wordId'], 'targetStart': index, 'targetEnd': index+1}
            for index, word in enumerate(line['words'])]}]
        native_lines = worker._lexical_records(request_lines, plan, [
            SimpleNamespace(token=7, start=5, end=10, score=.0000001),
            SimpleNamespace(token=8, start=70, end=80, score=-.1),
            SimpleNamespace(token=9, start=100, end=105, score=-2.0)], 20., 2400)
        semantics = 'first-to-last-approved-lexical-ctc-token-span-v1'
        native = {'engine': worker.ENGINE, 'route': worker.ROUTES_BY_LANGUAGE['en'],
            'formatVersion': worker.FORMAT_VERSION, 'endpointSemantics': semantics,
            'durationMs': 2400, 'request': {'sha256': 'a'*64, 'byteCount': 17},
            'audio': {'sha256': 'b'*64, 'byteCount': 100}, 'lineCount': 1, 'lines': native_lines}
        candidates = alignment._closed_endpoint_witness_candidates(native,
            expected_engine=worker.ENGINE, expected_route=native['route'],
            expected_format_version=worker.FORMAT_VERSION, duration_ms=2400,
            request_sha256='a'*64, request_byte_count=17,
            audio_identity=native['audio'], request_lines=request_lines)
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        _, summary = endpoints._apply_independent_endpoint_consensus(baseline,
            {endpoints.OWSM_CTC_FAMILY: candidates}, include_unresolved=True)
        # Retain the actual lexical-token meaning and contrary numbers; neither is offset calibration.
        self.assertEqual(summary['occurrenceCandidates'][0]['publishDecision']['code'],
                         'qualified-word-contradiction')
        report = _endpoint_evidence_diagnostics(summary)
        retained = report['occurrenceCandidates'][0]['familyCandidates'][endpoints.OWSM_CTC_FAMILY]
        self.assertEqual(retained['endpointSemantics'], semantics)
        self.assertEqual(retained['ctcLogScore'], native_lines[0]['ctcLogScore'])
        self.assertEqual(retained['acousticTokenCount'], 3)
        for actual, expected in zip(retained['words'], native_lines[0]['words']):
            self.assertEqual(actual['ctcLogScore'], expected['ctcLogScore'])
            self.assertEqual(actual['acousticTokenCount'], expected['acousticTokenCount'])
            self.assertNotIn('confidence', actual)
        self.assertEqual(report['occurrenceCandidates'], summary['occurrenceCandidates'])
        from stemwise_engine.workflow_records import FutureSongError
        for field, value in (('endpointSemantics', 'calibrated-sung-word-offset'),
                             ('ctcLogScore', float('nan')), ('ctcLogScore', .000002), ('acousticTokenCount', 0)):
            changed = copy.deepcopy(summary)
            changed['occurrenceCandidates'][0]['familyCandidates'][endpoints.OWSM_CTC_FAMILY][field] = value
            with self.subTest(field=field), self.assertRaises(FutureSongError):
                _endpoint_evidence_diagnostics(changed)

    def test_native_zero_probability_is_retained_without_corroboration_or_word_veto(self):
        from types import SimpleNamespace
        from stemwise_engine import lyrics_align_pc as alignment, wav2vec2_forced_alignment as worker
        from stemwise_engine.ctc_worker import chunk_time_map
        forced, valid = pair()
        line = forced['lines'][0]
        plan = [{'lineIndex': 0, 'sourcePhysicalIndex': 0, 'lineId': line['lineId'], 'words': [
            {'wordId': word['wordId'], 'targetStart': index, 'targetEnd': index+1}
            for index, word in enumerate(line['words'])]}]
        baseline = endpoints._refine_public_timings(forced, valid, include_unresolved=True)
        before = copy.deepcopy(baseline)
        for first_end in (10, 35):
            with self.subTest(first_end=first_end):
                records, _ = worker._result_records(plan, [
                    SimpleNamespace(start=5, end=first_end, score=-1000.),
                    SimpleNamespace(start=50, end=55, score=-.1)],
                    [chunk_time_map(0, 32000, 0, 100)], 2000)
                candidates = alignment._forced_alignment_verifier_candidates(
                    {'route': endpoints.WAV2VEC2_ROUTE, 'durationMs': 2000, 'lines': records},
                    [[(0, 1, 'a'), (2, 3, 'b')]], expected_route=endpoints.WAV2VEC2_ROUTE,
                    duration_ms=2000)
                self.assertEqual(candidates[0]['words'][0]['confidence'], 0.)
                _, summary = endpoints._refine_public_timings(forced, candidates, include_unresolved=True)
                self.assertEqual(summary['occurrenceCandidates'][0]['publishDecision'],
                                 {'result': 'unresolved', 'code': 'low-forced-line-support'})
                observed = summary['occurrenceCandidates'][0]['familyCandidates'][endpoints.WAV2VEC2_FAMILY]
                self.assertEqual(observed['words'][0]['confidence'], 0.)
                self.assertNotIn(endpoints.WAV2VEC2_FAMILY,
                                 endpoints._qualified_word_candidates(summary['occurrenceCandidates'][0]))
                updated = copy.deepcopy(baseline[1]['occurrenceCandidates'][0])
                updated['familyCandidates'][endpoints.WAV2VEC2_FAMILY] = observed
                self.assertIsNone(endpoints._reassess_endpoint_update(
                    baseline[1]['occurrenceCandidates'][0], updated, baseline[0][0]))
        for score, result in ((-1000., 'unresolved'), (-744., 'words')):
            records, _ = worker._result_records(plan, [
                SimpleNamespace(start=5, end=10, score=score),
                SimpleNamespace(start=50, end=55, score=score)],
                [chunk_time_map(0, 32000, 0, 100)], 2000)
            candidates = alignment._forced_alignment_verifier_candidates(
                {'route': endpoints.WAV2VEC2_ROUTE, 'durationMs': 2000, 'lines': records},
                [[(0, 1, 'a'), (2, 3, 'b')]], expected_route=endpoints.WAV2VEC2_ROUTE,
                duration_ms=2000)
            _, summary = endpoints._refine_public_timings(forced, candidates, include_unresolved=True)
            with self.subTest(score=score):
                self.assertEqual(summary['occurrenceCandidates'][0]['publishDecision']['result'], result)
                if result == 'unresolved':
                    self.assertEqual(summary['occurrenceCandidates'][0]['publishDecision']['code'],
                                     'low-forced-line-support')
        self.assertEqual(baseline, before)

    def test_weak_other_word_cannot_erase_a_strong_native_word_contradiction(self):
        from types import SimpleNamespace
        from stemwise_engine import lyrics_align_pc as alignment, wav2vec2_forced_alignment as wav
        from stemwise_engine.mms_forced_alignment import _aggregate_records
        from stemwise_engine.ctc_worker import chunk_time_map
        forced, verifier = pair(((100, 200), (1000, 1100), (2000, 2100)))
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        before = copy.deepcopy(baseline)
        line = forced['lines'][0]
        words = line['words']
        wav_plan = [{'lineId': line['lineId'], 'lineIndex': 0, 'sourcePhysicalIndex': 0,
            'words': [{'wordId': word['wordId'], 'targetStart': i, 'targetEnd': i+1}
                      for i, word in enumerate(words)]}]
        native, _ = wav._result_records(wav_plan, [
            SimpleNamespace(start=5, end=10, score=-1000.),
            SimpleNamespace(start=50, end=85, score=-.1),
            SimpleNamespace(start=100, end=105, score=-.1)],
            [chunk_time_map(0, 38400, 0, 120)], 2400)
        candidate = alignment._forced_alignment_verifier_candidates(
            {'route': endpoints.WAV2VEC2_ROUTE, 'durationMs': 2400, 'lines': native},
            [[(0, 1, 'a'), (2, 3, 'b'), (4, 5, 'c')]], expected_route=endpoints.WAV2VEC2_ROUTE,
            duration_ms=2400)[0]
        self.assertEqual(candidate['words'][0]['confidence'], 0.)
        mms_plan = [{'lineId': line['lineId'], 'lineIndex': 0, 'sourcePhysicalIndex': 0,
            'startWord': 0, 'wordCount': 3, 'words': [
                {'wordId': word['wordId'], 'startWord': i, 'wordCount': 1}
                for i, word in enumerate(words)]}]
        mms = _aggregate_records(mms_plan, [{'startMs': word['startMs'], 'endMs': word['endMs'],
            'confidence': .01 if i == 0 else .9, 'tokenCount': 1}
            for i, word in enumerate(native[0]['words'])])[0]
        from stemwise_engine import owsm_ctc_forced_alignment as owsm
        native_request = [{'lineId': line['lineId'], 'sourcePhysicalIndex': 0, 'words': [
            {'wordId': word['wordId'], 'acousticText': text} for word, text in zip(words, ('a', 'b', 'c'))]}]
        token_plan = [{'lineId': line['lineId'], 'tokenIds': [7, 8, 9], 'words': [
            {'wordId': word['wordId'], 'targetStart': i, 'targetEnd': i+1}
            for i, word in enumerate(words)]}]
        witness = owsm._lexical_records(native_request, token_plan, [
            SimpleNamespace(token=token, start=start, end=end, score=-.1)
            for token, (start, end) in zip((7, 8, 9), ((5, 10), (50, 55), (100, 105)))],
            20., 2400)[0]
        witness['route'] = owsm.ROUTES_BY_LANGUAGE['en']
        for family, observed in ((endpoints.WAV2VEC2_FAMILY, candidate), ('mms-fa', {**mms, 'route': 'mms'})):
            updated = copy.deepcopy(baseline[1]['occurrenceCandidates'][0])
            updated['familyCandidates'][family] = endpoints._candidate_audit(observed)
            with self.subTest(family=family, consumer='reassess'):
                reassessed = endpoints._reassess_endpoint_update(
                    baseline[1]['occurrenceCandidates'][0], updated, baseline[0][0])
                self.assertIsNotNone(reassessed)
                self.assertNotIn('words', reassessed[0])
                self.assertEqual(reassessed[1]['publishDecision'],
                                 {'result': 'line-only', 'code': 'qualified-word-contradiction'})
                retained = reassessed[1]['qualifiedWordContradictions']['incomingCandidates'][family]
                self.assertEqual(retained['words'][1]['endMs'], 1700)
                self.assertEqual(retained['words'][0]['confidence'], observed['words'][0]['confidence'])
            partial = endpoints._refine_public_timings(
                {'lines': [mms]} if family == 'mms-fa' else forced,
                verifier if family == 'mms-fa' else (candidate,), include_unresolved=True)
            results = [('independent-consensus', endpoints._apply_independent_endpoint_consensus(partial,
                {endpoints.OWSM_CTC_FAMILY: (witness,)}, include_unresolved=True))]
            results.extend((merge.__name__, merge(baseline, partial)) for merge in (
                endpoints._merge_endpoint_route_passes, endpoints._merge_endpoint_passes))
            for consumer, result in results:
                with self.subTest(family=family, consumer=consumer):
                    self.assertNotIn('words', result[0][0])
                    self.assertEqual(result[1]['occurrenceCandidates'][0]['publishDecision'],
                                     {'result': 'unresolved' if consumer == 'independent-consensus' else 'line-only',
                                      'code': 'qualified-word-contradiction'})
                    if consumer == 'independent-consensus':
                        for merge in (endpoints._merge_endpoint_route_passes, endpoints._merge_endpoint_passes):
                            retained = merge(result, baseline)
                            self.assertFalse(any(line.get('words') for line in retained[0]))
                            self.assertEqual(retained[1]['occurrenceCandidates'][0]['qualifiedWordContradictions'],
                                             result[1]['occurrenceCandidates'][0]['qualifiedWordContradictions'])
        self.assertEqual(baseline, before)

    def test_native_boundary_veto_uses_its_own_support_and_keeps_the_strong_opposite_boundary(self):
        from types import SimpleNamespace
        from stemwise_engine import lyrics_align_pc as alignment, wav2vec2_forced_alignment as wav
        from stemwise_engine.mms_forced_alignment import _aggregate_records
        from stemwise_engine.ctc_worker import chunk_time_map
        forced, verifier = pair()
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        line = forced['lines'][0]
        plan = [{'lineId': line['lineId'], 'lineIndex': 0, 'sourcePhysicalIndex': 0,
            'words': [{'wordId': word['wordId'], 'targetStart': i, 'targetEnd': i+1}
                      for i, word in enumerate(line['words'])]}]
        mms_plan = [{**plan[0], 'startWord': 0, 'wordCount': 2, 'words': [
            {'wordId': word['wordId'], 'startWord': i, 'wordCount': 1}
            for i, word in enumerate(line['words'])]}]
        for weak in (0, 1):
            for changed in (0, 1):
                spans = [(25, 35), (50, 55)] if changed == 0 else [(5, 10), (50, 90)]
                native, _ = wav._result_records(plan, [
                    SimpleNamespace(start=start, end=end, score=-1000. if i == weak else -.1)
                    for i, (start, end) in enumerate(spans)], [chunk_time_map(0, 32000, 0, 100)], 2000)
                candidate = alignment._forced_alignment_verifier_candidates(
                    {'route': endpoints.WAV2VEC2_ROUTE, 'durationMs': 2000, 'lines': native},
                    [[(0, 1, 'a'), (2, 3, 'b')]], expected_route=endpoints.WAV2VEC2_ROUTE,
                    duration_ms=2000)[0]
                mms = _aggregate_records(mms_plan, [{'startMs': word['startMs'], 'endMs': word['endMs'],
                    'confidence': .01 if i == weak else .9, 'tokenCount': 1}
                    for i, word in enumerate(native[0]['words'])])[0]
                for family, observed in ((endpoints.WAV2VEC2_FAMILY, candidate), ('mms-fa', {**mms, 'route': 'mms'})):
                    updated = copy.deepcopy(baseline[1]['occurrenceCandidates'][0])
                    updated['familyCandidates'][family] = endpoints._candidate_audit(observed)
                    result = endpoints._reassess_endpoint_update(
                        baseline[1]['occurrenceCandidates'][0], updated, baseline[0][0])
                    with self.subTest(family=family, weak=weak, changed=changed):
                        if changed == weak:
                            self.assertIsNone(result)
                        else:
                            self.assertIsNotNone(result)
                            self.assertEqual(result[1]['publishDecision'],
                                {'result': 'unresolved', 'code': 'qualified-endpoint-contradiction'})

    def test_native_mms_line_average_cannot_qualify_a_weak_boundary_word(self):
        from stemwise_engine.mms_forced_alignment import _aggregate_records
        forced, verifier = pair()
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        line = forced['lines'][0]
        plan = [{'lineIndex': 0, 'sourcePhysicalIndex': 0, 'lineId': line['lineId'],
            'startWord': 0, 'wordCount': 2, 'words': [
                {'wordId': word['wordId'], 'startWord': index, 'wordCount': 1}
                for index, word in enumerate(line['words'])]}]
        for boundary in (0, 1):
            acoustic = [{'startMs': word['startMs'], 'endMs': word['endMs'],
                         'confidence': .01 if index == boundary else .8, 'tokenCount': 1}
                        for index, word in enumerate(line['words'])]
            if boundary == 0:
                acoustic[0].update(startMs=500, endMs=700)
            else:
                acoustic[-1]['endMs'] = 1800
            produced = _aggregate_records(plan, acoustic)[0]
            self.assertGreater(produced['confidence'], endpoints.MIN_MMS_LINE_CONFIDENCE)
            candidate = endpoints._candidate_audit({**produced, 'route': 'mms'}, include_confidence=True)
            updated = copy.deepcopy(baseline[1]['occurrenceCandidates'][0])
            updated['familyCandidates']['mms-fa'] = candidate
            with self.subTest(boundary=boundary):
                self.assertNotIn('mms-fa', endpoints._eligible_endpoint_candidates(updated))
                self.assertIsNone(endpoints._reassess_endpoint_update(
                    baseline[1]['occurrenceCandidates'][0], updated, baseline[0][0]))
                _, summary = endpoints._refine_public_timings({'lines': [produced]}, verifier,
                                                              include_unresolved=True)
                self.assertEqual(summary['occurrenceCandidates'][0]['publishDecision'],
                                 {'result': 'unresolved', 'code': 'low-forced-line-support'})
                retained = summary['occurrenceCandidates'][0]['familyCandidates']['mms-fa']
                for field in ('startMs', 'endMs', 'confidence', 'words'):
                    self.assertEqual(retained[field], candidate[field])
        # Weak interior words still allow line endpoints, but cannot qualify complete words.
        forced, verifier = pair(((100, 200), (1000, 1100), (2000, 2100)))
        line = forced['lines'][0]
        plan[0].update(wordCount=3, words=[
            {'wordId': word['wordId'], 'startWord': index, 'wordCount': 1}
            for index, word in enumerate(line['words'])])
        for support, expected in ((.01, 'line-only'), (endpoints.MIN_MMS_WORD_CONFIDENCE, 'words')):
            acoustic = [{'startMs': word['startMs'], 'endMs': word['endMs'],
                         'confidence': support if index == 1 else .8, 'tokenCount': 1}
                        for index, word in enumerate(line['words'])]
            produced = _aggregate_records(plan, acoustic)[0]
            _, summary = endpoints._refine_public_timings({'lines': [produced]}, verifier,
                                                          include_unresolved=True)
            self.assertEqual(summary['occurrenceCandidates'][0]['publishDecision']['result'], expected)
            self.assertIn('mms-fa', endpoints._eligible_endpoint_candidates(summary['occurrenceCandidates'][0]))

    @staticmethod
    def _word_diagnostic_cases():
        forced, verifier = pair(((100, 200), (1000, 1600), (2000, 2100)))
        for line in (forced['lines'][0], verifier[0]):
            line['lineId'] = 'occurrence-' + 'a' * 24
            for index, word in enumerate(line['words']):
                word['wordId'] = 'occurrence-word-' + f'{index:024x}'
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        witness = copy.deepcopy(verifier[0])
        witness['route'] = 'owsm-ctc-v4-1b-english-v1'
        for word in witness['words']:
            word.pop('startUtf16')
            word.pop('endUtf16')
        witness['words'][1]['startMs'] = 1400
        conflict = endpoints._apply_independent_endpoint_consensus(baseline,
            {endpoints.OWSM_CTC_FAMILY: (witness,)}, include_unresolved=True)
        shifted = copy.deepcopy(witness)
        for value in (shifted, *shifted['words']):
            value['startMs'] += 2500
            value['endMs'] += 2500
        both = endpoints._apply_independent_endpoint_consensus(baseline,
            {endpoints.OWSM_CTC_FAMILY: (shifted,)}, include_unresolved=True)
        weak = copy.deepcopy(forced)
        weak['lines'][0]['words'][1]['confidence'] = .01
        weak['lines'][0]['confidence'] = 1.61/3
        before = endpoints._refine_public_timings(weak, verifier, include_unresolved=True)
        changed, changed_verifier = copy.deepcopy(forced), copy.deepcopy(verifier)
        for line in (changed['lines'][0], changed_verifier[0]):
            line['words'][0]['endMs'] = 700
        after = endpoints._refine_public_timings(changed, changed_verifier, include_unresolved=True)
        results = {'word-conflict': conflict[1], 'line-and-word-conflict': both[1]}
        for name, updated in (('upgrade-conflict', after), ('upgrade-supported', baseline)):
            upgraded = endpoints._upgrade_endpoint_words(before[1]['occurrenceCandidates'][0],
                updated[1]['occurrenceCandidates'][0], before[0][0])
            if upgraded is None:
                raise AssertionError('actual word upgrade did not produce a result')
            results[name] = endpoints._summarize_endpoint_occurrences([upgraded[1]],
                reviewed_lrc_start_evidence_count=0)
        low_line = copy.deepcopy(forced)
        low_line['lines'][0]['confidence'] = .01
        for word in low_line['lines'][0]['words']:
            word['confidence'] = .01
        low_baseline = endpoints._refine_public_timings(low_line, verifier, include_unresolved=True)
        matching = copy.deepcopy(witness)
        matching['words'][1]['startMs'] = 1000
        results['onset-only-refusal'] = endpoints._apply_independent_endpoint_consensus(low_baseline,
            {endpoints.OWSM_CTC_FAMILY: (matching,)}, include_unresolved=True)[1]
        return results

    def test_word_consensus_producers_commit_complete_diagnostics_without_losing_dissent(self):
        from stemwise_engine.workflow_diagnostics import _alignment_diagnostics
        from stemwise_engine.workflow_reports import WorkflowReports
        from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION
        root_path = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory(dir=root_path/'Test suite/work') as name:
            root = Path(name)
            project = root_path/'PC side/stemwise-playlist-builder'
            store = WorkflowReports(workspace_root=root_path, root=root, owner=root/'owner',
                reports=root/'reports', runtime=root/'runtime', jobs=root/'jobs',
                config_path=project/'config.json', policy_dir=root/'policy',
                config={'projectDir': str(project)}, policy={}, native=True)
            video = 'abcdefghijk'
            sources = root/'jobs'/video/'sources.json'
            sources.parent.mkdir(parents=True)
            sources.write_text('{}', encoding='utf-8')
            job = {'videoId': video}
            for index, (case, summary) in enumerate(self._word_diagnostic_cases().items()):
                with self.subTest(case=case):
                    run_id = f'{index + 1:032x}'
                    state = {'attemptId': run_id, 'alignmentVersion': ALIGNMENT_VERSION,
                        'status': 'retryable-technical', 'durationMs': 5000,
                        'reasons': ['ValueError'], 'endpointEvidence': summary,
                        'endpointRouteAttempts': [{'routeId': 'htdemucs-vocals:baseline',
                            'reused': True, 'endpointEvidence': summary}], 'selectedEndpointAttemptIndex': 0}
                    diagnostics = _alignment_diagnostics(state)
                    self.assertEqual(diagnostics['endpointRouteAttempts'][0]['endpointEvidence']['occurrenceCandidates'],
                        summary['occurrenceCandidates'])
                    digest = store.commit_alignment_run(job, run_id=run_id, editorial_sha256='e'*64,
                        state=state, status='failed', failure_code='retryable-technical')
                    run_path = store.report_root(video)/'runs'/run_id/'run.json'
                    import hashlib
                    self.assertEqual(hashlib.sha256(run_path.read_bytes()).hexdigest(), digest)
                    record = json.loads(run_path.read_bytes())
                    evidence_path = run_path.parent/record['reportArtifacts'][0]['fileName']
                    replay = json.loads(evidence_path.read_bytes())['diagnostics']
                    self.assertEqual(replay, diagnostics)
                    self.assertEqual(record['failureCode'], 'retryable-technical')
                    self.assertEqual(replay['endpointRouteAttempts'][0]['endpointEvidence']['occurrenceCandidates'],
                        summary['occurrenceCandidates'])

    def test_word_consensus_diagnostic_contract_rejects_unbound_or_malformed_evidence(self):
        from stemwise_engine.workflow_diagnostics import _alignment_diagnostics
        from stemwise_engine.workflow_records import FutureSongError
        cases = self._word_diagnostic_cases()
        base = cases['word-conflict']
        invalid = []
        for field in ('previousPublisher', 'incomingCandidates'):
            changed = copy.deepcopy(base)
            conflict = changed['occurrenceCandidates'][0]['qualifiedWordContradictions']
            candidate = conflict[field] if field == 'previousPublisher' else conflict[field]['owsm-ctc']
            candidate['words'][0]['occurrenceWordId'] = 'occurrence-word-'+'f'*24
            invalid.append(changed)
        for mutation in ('bounds', 'family', 'missing-prior', 'extra-prose', 'false-decision'):
            changed = copy.deepcopy(base)
            occurrence = changed['occurrenceCandidates'][0]
            conflict = occurrence['qualifiedWordContradictions']
            if mutation == 'bounds':
                conflict['incomingCandidates']['owsm-ctc']['words'][0]['endMs'] = 2000
            elif mutation == 'family':
                conflict['incomingCandidates']['another-owsm-vote'] = conflict['incomingCandidates'].pop('owsm-ctc')
            elif mutation == 'missing-prior':
                conflict.pop('previousPublisher')
            elif mutation == 'extra-prose':
                conflict['privateText'] = 'must not escape'
            else:
                occurrence['publishDecision'] = {'result': 'words'}
            invalid.append(changed)
        for mutation in ('duplicate', 'unknown', 'absent', 'missing-words', 'changed-word-id'):
            changed = copy.deepcopy(cases['upgrade-supported'])
            occurrence = changed['occurrenceCandidates'][0]
            support = occurrence['supportingWordEndpointFamilies']
            if mutation == 'duplicate':
                support.append(support[0])
            elif mutation == 'unknown':
                support[0] = 'unknown-family'
            elif mutation == 'absent':
                occurrence['familyCandidates'].pop(support[0])
            elif mutation == 'missing-words':
                occurrence['familyCandidates'][support[-1]].pop('words')
            else:
                occurrence['familyCandidates'][support[-1]]['words'][0]['occurrenceWordId'] = 'occurrence-word-'+'f'*24
            invalid.append(changed)
        for field in ('previousCandidates', 'incomingCandidates'):
            changed = copy.deepcopy(cases['upgrade-conflict'])
            group = changed['occurrenceCandidates'][0]['qualifiedWordContradictions'][field]
            next(iter(group.values()))['words'][0]['occurrenceWordId'] = 'occurrence-word-'+'f'*24
            invalid.append(changed)
        for mutation in ('empty-prior', 'unknown-prior-family', 'mixed-prior-shapes'):
            changed = copy.deepcopy(cases['upgrade-conflict'])
            conflict = changed['occurrenceCandidates'][0]['qualifiedWordContradictions']
            if mutation == 'empty-prior':
                conflict['previousCandidates'] = {}
            elif mutation == 'unknown-prior-family':
                conflict['previousCandidates']['another-wav-vote'] = next(iter(conflict['previousCandidates'].values()))
            else:
                conflict['previousPublisher'] = next(iter(conflict['previousCandidates'].values()))
            invalid.append(changed)
        for case in ('word-conflict', 'upgrade-supported'):
            changed = copy.deepcopy(cases[case])
            occurrence = changed['occurrenceCandidates'][0]
            witness = copy.deepcopy(occurrence['familyCandidates']['language-wav2vec2'])
            witness['route'] = 'faster-whisper-precision-unprompted:en'
            witness['words'][0]['endMs'] = 2000
            if case == 'word-conflict':
                occurrence['qualifiedWordContradictions']['incomingCandidates']['unprompted-faster-whisper'] = witness
            else:
                occurrence['familyCandidates']['unprompted-faster-whisper'] = witness
                occurrence['endpointWitnessFamilies'].append('unprompted-faster-whisper')
                occurrence['supportingEndpointFamilies'].append('unprompted-faster-whisper')
                occurrence['supportingWordEndpointFamilies'].append('unprompted-faster-whisper')
            invalid.append(changed)
        for index, summary in enumerate(invalid):
            with self.subTest(index=index), self.assertRaises(FutureSongError):
                _alignment_diagnostics({'status': 'retryable-technical', 'endpointEvidence': summary})

    def test_three_route_cascade_keeps_interior_dissent_through_guarded_repair(self):
        import stemwise_engine.alignment_audio as audio
        import stemwise_engine.lyrics_align_pc as worker
        from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
        from stemwise_engine.lyrics_tokenization import display_words

        root_path = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory(dir=root_path/'Test suite/work') as name:
            root, video = Path(name), 'abcdefghijk'
            original = {'id': 'original-en', 'kind': 'original', 'format': 'plain', 'language': 'en',
                        'text': 'Left\nDisputed\nRight\nTail'}
            sources = (0, 1, 2, 3)
            workspace = AlignmentWorkspace(root/'alignment', workspace_root=root)
            _, canonical = snapshot_canonical_plain(workspace, video,
                json.dumps({'tracks': [original]}).encode('utf-8'))
            admission, retained = retained_content(workspace.job_dir(video), original, sources, canonical)
            state = {'canonicalPlainSha256': canonical, 'contentAdmission': admission.to_record(),
                     'contentBinding': admission.binding, 'pinnedModels': {}}
            workspace.write_state(video, state)
            retained_bytes = retained.read_bytes()
            mapping = build_lexical_occurrence_mapping(video, original, sources)
            ranges = [display_words(line) for line in original['text'].splitlines()]
            routes = endpoints.ENDPOINT_MMS_ROUTES
            calls, results = [], []

            def reply(target, *, weak_tail=False):
                lines = []
                for occurrence, (start, end) in zip(mapping['performedOccurrences'],
                        ((0, 100), target, (5000, 6000), (8000, 9000))):
                    confidence = .01 if weak_tail and occurrence['performedIndex'] == 3 else .9
                    lines.append({'lineId': occurrence['occurrenceId'],
                        'sourcePhysicalIndex': occurrence['sourcePhysicalIndex'],
                        'startMs': start, 'endMs': end, 'confidence': confidence,
                        'words': [{'wordId': occurrence['words'][0]['occurrenceWordId'],
                            'startMs': start, 'endMs': end, 'confidence': confidence,
                            'acousticTokenCount': 1}]})
                return {'lines': lines, 'durationMs': 10000}

            def load_mms(**request):
                route, variant = request['audio_view_id'], request['request_variant']
                calls.append(('mms', route, variant, copy.deepcopy(request['segments'])))
                target = ((500, 1500), (1900, 2900), (2500, 3500))[routes.index(route)]
                return reply(target, weak_tail=route != routes[-1]), True

            def load_wav2vec2(**request):
                route, variant = request['audio_view_id'], request['request_variant']
                calls.append(('wav2vec2', route, variant, copy.deepcopy(request['segments'])))
                return {**reply((2500, 3500)),
                    'route': endpoints.WAV2VEC2_ROUTES_BY_AUDIO_VIEW[route]}, True, {
                        'modelSha256': 'b'*64, 'snapshotManifestSha256': 'c'*64}

            def unexpected(*args, **kwargs):
                self.fail('no independent model or mix acquisition is needed')

            initial = reply((500, 1500))
            initial['route'] = endpoints.WAV2VEC2_ROUTE
            stage = endpoints.EndpointAlignmentStage(
                request=EndpointAlignmentRequest(
                    context=AlignmentContext(config={'analysisTimeoutMinutes': 1}, video_id=video,
                        workspace=workspace, temp_root=root, device='cpu', duration_ms=10000,
                        endpoint_duration_ms=10000),
                    text=EndpointText(original=original, alignment=None, lexical_mapping=mapping,
                        sources=sources, source_occurrences=sources, display_word_lines=ranges,
                        acoustic_language_lines=None),
                    evidence=EndpointEvidence(baseline_segments=[{'segmentIndex': 0,
                        'startLineIndex': 0, 'endLineIndex': 4, 'windowStartMs': 0, 'windowEndMs': 10000}],
                        seeded_mms_segments=None, verifier_candidates=worker._forced_alignment_verifier_candidates(
                            initial, ranges, expected_route=endpoints.WAV2VEC2_ROUTE, duration_ms=10000),
                        verifier_family=endpoints.WAV2VEC2_FAMILY, verifier_reused=True,
                        wav2vec2_language='en', free_family_candidates={}, independent_inputs={},
                        reviewed_lrc_starts={}, route_names={route: 'torchaudio-mms-fa-on-'+route for route in routes},
                        content_admission=admission.to_record()),
                    audio=EndpointAudio(expected_mix_sha256='a'*64, analysis_audio=None,
                        canonical_mix=unexpected,
                        get_endpoint_audio=lambda route: (root/(route+'.wav'), {'sha256': 'd'*64}, True))),
                services=EndpointServices(workers=EndpointWorkers(load_mms=load_mms,
                    load_wav2vec2=load_wav2vec2, load_owsm=unexpected, load_nfa=unexpected,
                    read_audio_provenance=audio.read_audio_provenance,
                    verifier_candidates=worker._forced_alignment_verifier_candidates,
                    needs_local_verifier=worker._needs_route_local_verifier),
                    events=AlignmentEvents(attempt=lambda *a, **k: None,
                        failure=lambda *a: self.fail(str(a)), roformer=lambda *a, **k: None),
                    progress=AlignmentProgress(state=state, endpoint_attempts=[], checkpoint=lambda: None),
                    release_model=lambda: None, runtime_probe_cache={}))

            def evaluate(route):
                ready, result = stage.evaluate_endpoint_route(route)
                results.append(copy.deepcopy(result))
                return ready, result

            selected, result = worker._run_conditional_cascade(routes, evaluate)
            self.assertEqual(selected, routes[-1])
            self.assertEqual(len(results), 3)
            original_dissent = results[0].summary['occurrenceCandidates'][1]['qualifiedEndpointContradictions']
            self.assertEqual(original_dissent['previousPublisher']['startMs'], 500)
            self.assertEqual(original_dissent['incomingCandidates'][endpoints.WAV2VEC2_FAMILY]['startMs'], 2500)
            for result in results:
                disputed = result.summary['occurrenceCandidates'][1]
                self.assertEqual(disputed.get('qualifiedEndpointContradictions'), original_dissent)
                self.assertEqual(disputed['publishDecision']['result'], 'unresolved')
                self.assertNotIn('selectedPublisherFamily', disputed)
                self.assertNotIn('words', result.timings[1])
            self.assertEqual(result.summary['lineReadyCount'], 3)
            self.assertEqual(result.summary['resultClass'], 'line-unresolved')
            for index in (0, 2, 3):
                self.assertEqual(result.summary['occurrenceCandidates'][index]['publishDecision']['result'], 'words')
            self.assertTrue(all(any(family == 'mms' and called_route == route and variant == 'anchored'
                and len(segments) > 1 for family, called_route, variant, segments in calls) for route in routes[:2]))
            self.assertEqual(retained.read_bytes(), retained_bytes)
            self.assertEqual(json.loads(workspace.state_path(video).read_bytes())['contentAdmission'], admission.to_record())

    def test_independent_dissent_remains_durable_after_later_qualified_route_agrees_with_it(self):
        forced, verifier = pair()
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        for line in (forced['lines'][0], verifier[0]):
            for value in (line, *line['words']):
                value['startMs'] += 2500
                value['endMs'] += 2500
        witness = {**verifier[0], 'route': 'owsm'}
        disputed = endpoints._apply_independent_endpoint_consensus(baseline,
            {endpoints.OWSM_CTC_FAMILY: (witness,)}, include_unresolved=True)
        self.assertEqual(disputed[1]['resultClass'], 'line-unresolved')
        later = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        for merge in (lambda before, after: endpoints._merge_endpoint_passes(before, after, include_unresolved=True),
                endpoints._merge_endpoint_route_passes):
            restored = merge(json.loads(json.dumps(disputed)), later)
            self.assertEqual(restored[1]['resultClass'], 'line-unresolved')
            contradiction = restored[1]['occurrenceCandidates'][0]['qualifiedEndpointContradictions']
            self.assertEqual(contradiction['previousPublisher']['startMs'], 100)
            self.assertEqual(contradiction['incomingCandidates'][endpoints.OWSM_CTC_FAMILY]['startMs'], 2600)
            self.assertNotIn('words', restored[0][0])
            self.assertNotIn('selectedPublisherFamily', restored[1]['occurrenceCandidates'][0])

    def test_real_endpoint_summaries_round_trip_report_contract(self):
        from stemwise_engine.workflow_diagnostics import _endpoint_evidence_diagnostics
        forced, verifier = pair()
        for line in (forced['lines'][0], verifier[0]):
            line['lineId'] = 'occurrence-'+'a'*24
            for index, word in enumerate(line['words']):
                word['wordId'] = 'occurrence-word-'+f'{index:024x}'
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        examples = [baseline]
        for witness in ({'route': 'owsm', 'startMs': 3000, 'endMs': 4000},
                        {'route': 'owsm', 'startMs': 4000, 'endMs': 3000}):
            examples.append(endpoints._apply_independent_endpoint_consensus(baseline,
                {endpoints.OWSM_CTC_FAMILY: (witness,)}))
        changed = copy.deepcopy(forced)
        changed_verifier = copy.deepcopy(verifier)
        for line in (changed['lines'][0], changed_verifier[0]):
            for value in (line, *line['words']):
                value['startMs'] += 2500
                value['endMs'] += 2500
        examples.append(endpoints._merge_endpoint_route_passes(baseline,
            endpoints._refine_public_timings(changed, changed_verifier, include_unresolved=True)))
        for _, summary in examples:
            with self.subTest(result=summary['resultClass']):
                report = _endpoint_evidence_diagnostics(summary)
                self.assertEqual(report['occurrenceCandidates'], summary['occurrenceCandidates'])
        from stemwise_engine.workflow_records import FutureSongError
        for field, value in (('endpointWitnessFamilies', ['unknown-family']),
                ('ineligibleEndpointFamilies', {'owsm-ctc': 'private lyric text'}),
                ('qualifiedEndpointContradictions', {'previousPublisher': 'unbound'})):
            malformed = copy.deepcopy(examples[0][1])
            malformed['occurrenceCandidates'][0][field] = value
            with self.assertRaises(FutureSongError):
                _endpoint_evidence_diagnostics(malformed)

    def test_singleton_requires_real_mass_and_aggregation_cannot_create_it(self):
        mass = np.zeros(31)
        mass[25] = 1
        failed = ctc.select_candidate(mass, [100, 100], origin_ms=0, step_ms=20)
        self.assertEqual(failed["result"], "abstain")
        self.assertTrue(failed["candidatesAgree"])
        records = [{"route": route, "shiftMs": shift, "decisions": {"startMs": failed, "endMs": failed}}
                   for route in ROUTES for shift in (-5, 0, 5)]
        families = {"a": {"startMs": 100, "endMs": 100}, "b": {"startMs": 100, "endMs": 100}}
        aggregate = ctc._assess(records, families)
        self.assertEqual(aggregate["startMs"]["result"], "abstain")
        self.assertTrue(aggregate["startMs"]["candidatesAgree"])
        accepted = ctc.select_candidate(mass, [500, 500], origin_ms=0, step_ms=20)
        self.assertEqual((accepted["result"], accepted["candidateMs"]), ("supported", 500))
        self.assertNotEqual(accepted["reason"], "identical-candidates")

    def test_disjoint_words_downgrade_but_disjoint_one_word_line_cannot_publish(self):
        forced, verifier = pair()
        before = json.dumps(forced)
        for word in verifier[0]["words"]:
            word["startMs"] += 250
            word["endMs"] += 250
        verifier[0].update(startMs=350, endMs=1350)
        published, summary = endpoints._refine_public_timings(forced, verifier)
        self.assertEqual(summary["resultClass"], "committed-lines")
        self.assertNotIn("words", published[0])
        self.assertEqual(json.dumps(forced), before)
        forced, verifier = pair(((100, 200),))
        verifier[0].update(startMs=350, endMs=450)
        verifier[0]["words"][0].update(startMs=350, endMs=450)
        published, summary = endpoints._refine_public_timings(forced, verifier)
        self.assertEqual(published, [])
        self.assertEqual(summary["unresolvedLineReasons"], {"line-interval-disjoint": 1})

    def test_intersection_is_strict_canonical_and_keeps_existing_ceilings(self):
        forced, verifier = pair(((100., 200.),))
        verifier[0].update(startMs=199.99, endMs=299.99)
        verifier[0]["words"][0].update(startMs=199.99, endMs=299.99)
        self.assertEqual(endpoints._refine_public_timings(forced, verifier)[1]["resultClass"], "committed-words")
        verifier[0]["startMs"] = verifier[0]["words"][0]["startMs"] = 200.
        self.assertEqual(endpoints._refine_public_timings(forced, verifier)[1]["resultClass"], "line-unresolved")
        forced, verifier = pair(((100, 1000),))
        verifier[0].update(startMs=400, endMs=1400)
        verifier[0]["words"][0].update(startMs=400, endMs=1400)
        self.assertEqual(endpoints._refine_public_timings(forced, verifier)[1]["resultClass"], "committed-words")
        verifier[0]["endMs"] = verifier[0]["words"][0]["endMs"] = 1401
        self.assertEqual(endpoints._refine_public_timings(forced, verifier)[1]["resultClass"], "line-unresolved")

    def test_new_qualified_dissent_reopens_accepted_line_without_mutation(self):
        forced, verifier = pair()
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        before = json.dumps(baseline)
        witnesses = {endpoints.OWSM_CTC_FAMILY: ({"route": "owsm", "lineId": "line", "startMs": 3000, "endMs": 4000},)}
        published, summary = endpoints._apply_independent_endpoint_consensus(baseline, witnesses)
        self.assertEqual(published, [])
        self.assertEqual(summary["unresolvedLineReasons"], {"qualified-endpoint-contradiction": 1})
        self.assertEqual(json.dumps(baseline), before)
        for diagnostic in (None, {"route": "owsm", "startMs": 3000, "endMs": 2000},
                           {"route": "owsm", "lineId": "different-occurrence", "startMs": 3000, "endMs": 4000}):
            retained, result = endpoints._apply_independent_endpoint_consensus(
                baseline, {endpoints.OWSM_CTC_FAMILY: (diagnostic,)})
            self.assertEqual(result["resultClass"], "committed-words")
            self.assertEqual(retained, baseline[0])

    def test_majority_cannot_erase_applicable_isolated_dissent(self):
        forced, verifier = pair()
        verifier[0].update(startMs=3000, endMs=4000)
        for word in verifier[0]['words']:
            word['startMs'] += 2900
            word['endMs'] += 2900
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        _, result = endpoints._apply_independent_endpoint_consensus(baseline, {
            family: ({"route": family, "startMs": 120, "endMs": 1120},)
            for family in (endpoints.OWSM_CTC_FAMILY, endpoints.NEMO_NFA_FAMILY)})
        self.assertEqual(result["lineReadyCount"], 0)

    def test_new_word_dissent_with_agreeing_line_only_downgrades_word_claim(self):
        forced, verifier = pair(((100, 200), (1000, 1500)))
        baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        witness = copy.deepcopy(verifier[0])
        witness["route"] = "owsm"
        witness["words"][0].update(startMs=350, endMs=450)
        published, result = endpoints._apply_independent_endpoint_consensus(
            baseline, {endpoints.OWSM_CTC_FAMILY: (witness,)})
        self.assertEqual(result["resultClass"], "committed-lines")
        self.assertNotIn("words", published[0])

    def test_route_and_anchored_merges_cannot_restore_a_contradicted_prior_claim(self):
        forced, verifier = pair()
        before = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        old_bytes = json.dumps(before)
        for candidate in (forced["lines"][0], verifier[0]):
            candidate["startMs"] += 2500
            candidate["endMs"] += 2500
            for word in candidate["words"]:
                word["startMs"] += 2500
                word["endMs"] += 2500
        after = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
        for merge in (lambda baseline, candidate: endpoints._merge_endpoint_passes(
                baseline, candidate, include_unresolved=True), endpoints._merge_endpoint_route_passes):
            with self.subTest(merge=merge):
                result = merge(before, after)
                self.assertEqual(result[1]["resultClass"], "line-unresolved")
                self.assertIn("qualifiedEndpointContradictions", result[1]["occurrenceCandidates"][0])
                unresolved_bytes = json.dumps(result)
                replayed = merge(json.loads(unresolved_bytes), after)
                self.assertEqual(replayed[1]["resultClass"], "line-unresolved")
                self.assertEqual(replayed[1]["occurrenceCandidates"][0]["qualifiedEndpointContradictions"],
                                 result[1]["occurrenceCandidates"][0]["qualifiedEndpointContradictions"])
                self.assertNotIn("selectedPublisherFamily", replayed[1]["occurrenceCandidates"][0])
                self.assertNotIn("words", replayed[0][0])
                self.assertEqual(json.dumps(result), unresolved_bytes)
                self.assertEqual(json.dumps(before), old_bytes)

    def test_normal_stage_reassesses_cached_dissent_without_another_call(self):
        work = Path(__file__).resolve().parents[3] / "Test suite/work/accuracy-timing"
        created = not work.exists()
        work.mkdir(exist_ok=True)
        try:
            with tempfile.TemporaryDirectory(dir=work) as name:
                root, video = Path(name), "TESTVID0001"
                workspace = AlignmentWorkspace(root / "alignment", workspace_root=root)
                original = {"id": "original", "kind": "original", "format": "plain", "language": "en", "text": "a b"}
                _, canonical_sha = snapshot_canonical_plain(workspace, video,
                    json.dumps({"tracks": [original]}).encode("utf-8"))
                admission, _ = retained_content(workspace.job_dir(video), original, (0,), canonical_sha)
                workspace.state_path(video).write_text(json.dumps({"canonicalPlainSha256": canonical_sha,
                    "contentAdmission": admission.to_record()}), encoding="utf-8")
                forced, verifier = pair()
                from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
                mapping = build_lexical_occurrence_mapping(video, original, (0,))
                owner = mapping['performedOccurrences'][0]
                for line in (forced['lines'][0], verifier[0]):
                    line['lineId'] = owner['occurrenceId']
                    for word, expected in zip(line['words'], owner['words']):
                        word['wordId'] = expected['occurrenceWordId']
                baseline = endpoints._refine_public_timings(forced, verifier, include_unresolved=True)
                def unexpected(*args, **kwargs):
                    self.fail("exact cached witness must be reused")
                request = EndpointAlignmentRequest(
                    context=AlignmentContext(config={}, video_id=video, workspace=workspace,
                        temp_root=root, device="cpu", duration_ms=2000, endpoint_duration_ms=2000),
                    text=EndpointText(original=original, alignment=None,
                        lexical_mapping=mapping,
                        sources=(0,), source_occurrences=(0,), display_word_lines=[[(0, 1, "a"), (2, 3, "b")]],
                        acoustic_language_lines=None),
                    evidence=EndpointEvidence(baseline_segments=[], seeded_mms_segments=None,
                        verifier_candidates=verifier, verifier_family=endpoints.WAV2VEC2_FAMILY,
                        verifier_reused=True, wav2vec2_language="en", free_family_candidates={},
                        independent_inputs={"en": ((0,), {}, [])}, reviewed_lrc_starts={}, route_names={},
                        content_admission=admission.to_record()),
                    audio=EndpointAudio(expected_mix_sha256="a"*64, analysis_audio=None,
                        canonical_mix=unexpected, get_endpoint_audio=unexpected))
                services = EndpointServices(
                    workers=EndpointWorkers(**{field.name: unexpected for field in fields(EndpointWorkers)}),
                    events=AlignmentEvents(attempt=unexpected, failure=unexpected, roformer=unexpected),
                    progress=AlignmentProgress(state={}, endpoint_attempts=[], checkpoint=unexpected),
                    release_model=unexpected, runtime_probe_cache={})
                stage = endpoints.EndpointAlignmentStage(request=request, services=services,
                    independent_witness_reused={endpoints.OWSM_CTC_FAMILY: True},
                    independent_witnesses={endpoints.OWSM_CTC_FAMILY: (
                        {"route": "owsm", "lineId": owner['occurrenceId'], "startMs": 3000, "endMs": 4000},)})
                durable_before = {path: path.read_bytes() for path in root.rglob("*.json")}
                result, _, _ = stage.apply_independent_consensus(baseline)
                self.assertEqual(result[1]["resultClass"], "line-unresolved")
                self.assertEqual({path: path.read_bytes() for path in durable_before}, durable_before)
        finally:
            if created:
                work.rmdir()

    def test_neutral_gate_keeps_one_producer_and_requires_independent_complete_words(self):
        forced, verifier = pair()
        publisher = forced["lines"][0]
        words, reason = endpoints.assess_complete_word_sequence(publisher,
            {endpoints.WAV2VEC2_FAMILY: verifier[0]}, publisher_family="mms-fa")
        self.assertIsNone(reason)
        self.assertEqual([(word["startMs"], word["endMs"]) for word in words], [(100, 200), (1000, 1100)])
        alternate = copy.deepcopy(verifier[0])
        for unit in (alternate, *alternate["words"]):
            unit["startMs"] += 10
            unit["endMs"] += 10
        words, reason = endpoints.assess_complete_word_sequence(alternate,
            {"mms-fa": publisher}, publisher_family="diagnostic-producer")
        self.assertIsNone(reason)
        self.assertEqual([word["startMs"] for word in words], [110, 1010])
        for fault in ("same-family", "qwen-shared", "missing-word", "identity", "support", "malformed", "ownership-mosaic"):
            candidate = copy.deepcopy(verifier[0])
            producer = copy.deepcopy(publisher)
            family, producer_family = endpoints.WAV2VEC2_FAMILY, "mms-fa"
            if fault == "same-family": family = producer_family
            if fault == "qwen-shared": family, producer_family = "qwen3-asr", "qwen3-forced-aligner"
            if fault == "missing-word": candidate["words"].pop()
            if fault == "identity": candidate["words"][0]["wordId"] = "different"
            if fault == "support": producer["words"][0]["confidence"] = .01
            if fault == "malformed": candidate["words"][0]["startMs"] = float("nan")
            if fault == "ownership-mosaic":
                producer["words"][1].update(startUtf16=2, endUtf16=3)
                for field in ("startUtf16", "endUtf16"):
                    candidate["words"][1].pop(field)
            with self.subTest(fault=fault):
                self.assertIsNone(endpoints.assess_complete_word_sequence(producer,
                    {family: candidate}, publisher_family=producer_family)[0])

    @staticmethod
    def acoustic(rows):
        rng = np.random.default_rng(rows)
        values = rng.uniform(.01, 1, (rows, 5))
        return np.log(values/values.sum(axis=1, keepdims=True))

    def test_repeat_constant_direction_is_not_temporal_evidence(self):
        constant = np.tile(np.log([.2, .5, .2, .1]), (5, 1))
        with self.assertRaisesRegex(ValueError, "temporal"):
            repeat.transfer(constant, np.arange(5)*20., constant, 5000+np.arange(5)*20., 0, [40])

    def test_repeat_alternate_optima_veto_and_unique_skipped_row_uses_median(self):
        ambiguous = np.array([[0., 0., 9., 9.], [9., 0., 0., 9.], [9., 9., 0., 0.]])
        with patch("scipy.spatial.distance.cdist", return_value=ambiguous):
            with self.assertRaisesRegex(ValueError, "ambiguous"):
                repeat.transfer(self.acoustic(3), np.arange(3)*20., self.acoustic(4),
                    5000+np.arange(4)*20., 0, [20])
        unique = np.array([[0., 9.], [9., 9.], [9., 0.]])
        with patch("scipy.spatial.distance.cdist", return_value=unique):
            self.assertEqual(repeat.transfer(self.acoustic(3), np.arange(3)*20., self.acoustic(2),
                5000+np.arange(2)*20., 0, [20]), [5010.])

    def test_repeat_extra_arrays_are_guarded_before_distance_allocation(self):
        values = self.acoustic(5)
        with patch.object(repeat, "CURRENT_BUDGET", AcousticBudget(available_bytes=2000)), \
             patch("scipy.spatial.distance.cdist", side_effect=AssertionError("distance allocation reached")) as distance:
            with self.assertRaises(AcousticBudgetExceeded):
                repeat.transfer(values, np.arange(5)*20., values, 5000+np.arange(5)*20., 0, [20])
            distance.assert_not_called()

    def test_repeat_envelope_contains_every_small_complete_path_median(self):
        rng = np.random.default_rng(86)
        for rows, columns in ((3, 3), (4, 4), (4, 3), (3, 5)):
            for case in range(4):
                cost = rng.integers(0, 3, size=(rows, columns)).astype(float)
                paths = []
                def visit(path, score):
                    row, column = path[-1]
                    if row == rows-1:
                        paths.append((score, path))
                        return
                    for dr, dc, weight in ((1, 1, 1), (1, 2, 1.5), (2, 1, 1.5)):
                        if row+dr < rows and column+dc < columns:
                            visit([*path, (row+dr, column+dc)], score+weight*cost[row+dr, column+dc])
                for column in range(columns):
                    visit([(0, column)], cost[0, column])
                optimum = min(score for score, _ in paths)
                for index in range(rows):
                    medians = set()
                    for score, path in paths:
                        if score == optimum:
                            distance = min(abs(row-index) for row, _ in path)
                            medians.add(float(np.median([5000+column*20 for row, column in path
                                                       if abs(row-index) == distance])))
                    with self.subTest(shape=(rows, columns), case=case, index=index), \
                         patch("scipy.spatial.distance.cdist", return_value=cost):
                        arguments = (self.acoustic(rows), np.arange(rows)*20., self.acoustic(columns),
                                     5000+np.arange(columns)*20., 0, [index*20])
                        if len(medians) == 1:
                            self.assertEqual(repeat.transfer(*arguments), [next(iter(medians))])
                        else:
                            with self.assertRaisesRegex(ValueError, "ambiguous"):
                                repeat.transfer(*arguments)


if __name__ == "__main__":
    unittest.main()
