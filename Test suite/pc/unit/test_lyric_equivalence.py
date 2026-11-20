"""Orthographic comparison never changes display or pronunciation authority."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from stemwise_engine.lyric_content import (require_exact_sequence, reference_adaptation,
    diagnostic_discrepancy_scope, require_recording_adjudication)
from stemwise_engine.lyrics_alignment import (build_lexical_occurrence_mapping, whisper_word_candidates,
    WordTimestamp)
from stemwise_engine.lyrics_align_pc import _forced_alignment_request
from stemwise_engine.lyrics_tokenization import display_words, lexical_authority_sha256
from stemwise_engine.mms_forced_alignment import _request_plan
from stemwise_engine.workflow_records import LyricsContentBlocker


class LyricEquivalenceTests(unittest.TestCase):
    def original(self, text, language='en'):
        return {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': language, 'text': text}

    def test_exact_line_anchors_keep_full_raw_indices_and_timestamps_after_substitution(self):
        from stemwise_engine.lyric_content import exact_line_anchors
        original = self.original('You feel the light\nHold my hand')
        words = [{'raw': raw, 'start': index*.5, 'end': index*.5+.4}
            for index, raw in enumerate(('We', 'feel', 'the', 'light', 'Hold', 'my', 'hand'))]
        before = copy.deepcopy((original, words))
        self.assertEqual(exact_line_anchors(original, (0, 1), words), (
            {'performedIndex': 0, 'sourcePhysicalIndex': 0, 'rawWordIndex': 1, 'matchedUnitCount': 3},
            {'performedIndex': 1, 'sourcePhysicalIndex': 1, 'rawWordIndex': 4, 'matchedUnitCount': 3}))
        self.assertEqual((original, words), before)

    def test_exact_line_anchors_do_not_reassign_raw_owners_after_filtering(self):
        from stemwise_engine.lyric_content import exact_line_anchors
        words = [{'raw': raw} for raw in ('A', 'X', 'A')]
        self.assertIsNone(exact_line_anchors(self.original('A\nA\nA'), (0, 1, 2), words))
        self.assertEqual(exact_line_anchors(self.original('A A A'), (0,), words), (
            {'performedIndex': 0, 'sourcePhysicalIndex': 0, 'rawWordIndex': 0, 'matchedUnitCount': 2},))
        # The final observed A belongs to the third target, not the filtered second target.
        self.assertEqual(exact_line_anchors(self.original('A\nA feel\nA'), (0, 1, 2),
                [{'raw': raw} for raw in ('A', 'X', 'feel', 'A')]), (
            {'performedIndex': 0, 'sourcePhysicalIndex': 0, 'rawWordIndex': 0, 'matchedUnitCount': 1},
            {'performedIndex': 1, 'sourcePhysicalIndex': 1, 'rawWordIndex': 2, 'matchedUnitCount': 1},
            {'performedIndex': 2, 'sourcePhysicalIndex': 2, 'rawWordIndex': 3, 'matchedUnitCount': 1}))

    def test_exact_line_anchors_withhold_deleted_ambiguous_and_unanchored_lines(self):
        from stemwise_engine.lyric_content import exact_line_anchors
        for text, indices, raw in (
                ('A\nA', (0, 1), ('A',)), ('A', (0,), ('A', 'A')),
                ('A\nB', (0, 1), ('A',)), ('You\nfeel', (0, 1), ('We', 'feel')),
                ('A\n', (0, 1), ('A',)), ('', (0,), ('A',))):
            with self.subTest(text=text, raw=raw):
                self.assertIsNone(exact_line_anchors(self.original(text), indices,
                    [{'raw': word} for word in raw]))

    def test_exact_line_anchors_keep_only_whole_raw_words_on_one_performed_line(self):
        from stemwise_engine.lyric_content import exact_line_anchors
        for text, raw in (('most\nforgiving', ('most-forgiving',)),
                ('most forgiving', ('most-mistaken',)), ('most forgiving', ('forgiving',))):
            indices = tuple(range(len(text.split('\n'))))
            with self.subTest(text=text, raw=raw):
                result = exact_line_anchors(self.original(text), indices, [{'raw': word} for word in raw])
                if raw == ('forgiving',):
                    self.assertEqual(result, ({'performedIndex': 0, 'sourcePhysicalIndex': 0,
                        'rawWordIndex': 0, 'matchedUnitCount': 1},))
                else:
                    self.assertIsNone(result)
        self.assertEqual(exact_line_anchors(self.original('Most-Forgiving'), (0,),
                [{'raw': 'most forgiving'}]), ({'performedIndex': 0, 'sourcePhysicalIndex': 0,
                    'rawWordIndex': 0, 'matchedUnitCount': 2},))

    def test_exact_line_anchors_preserve_performed_occurrences_and_raw_object_ownership(self):
        from stemwise_engine.lyric_content import exact_line_anchors
        words = [WordTimestamp(word=raw.lower(), raw=raw, start=index, end=index+.5)
            for index, raw in enumerate(('Hello', 'world', 'Hello', 'world'))]
        self.assertEqual(exact_line_anchors(self.original('\nHello world\n'), (1, 1), words), (
            {'performedIndex': 0, 'sourcePhysicalIndex': 1, 'rawWordIndex': 0, 'matchedUnitCount': 2},
            {'performedIndex': 1, 'sourcePhysicalIndex': 1, 'rawWordIndex': 2, 'matchedUnitCount': 2}))
        for indices, recognized in (((), words), ((-1,), words), ((3,), words), ((1,), []),
                ((1,), 'Hello world'), ((1,), [{'word': 'Hello'}])):
            with self.subTest(indices=indices, recognized=recognized):
                self.assertIsNone(exact_line_anchors(self.original('\nHello world\n'), indices, recognized))

    def test_hyphen_boundary_matches_without_changing_display_authority_or_occurrences(self):
        for connector in ('-', '\u2010', '\u2011'):
            for display, recognized in ((f'Most{connector}Forgiving', 'most forgiving'),
                    ('most forgiving', f'Most{connector}Forgiving')):
                original = self.original(display+'\n'+display)
                before = copy.deepcopy(original)
                mapping = build_lexical_occurrence_mapping('abcdefghijk', original, (0, 1))
                authority = lexical_authority_sha256(original)
                with self.subTest(display=display, recognized=recognized):
                    require_exact_sequence(original, (0, 1), recognized+' '+recognized, 'c'*64, 'a'*64)
                    self.assertEqual(original, before)
                    self.assertEqual(build_lexical_occurrence_mapping('abcdefghijk', original, (0, 1)), mapping)
                    self.assertEqual(lexical_authority_sha256(original), authority)
                    with self.assertRaises(LyricsContentBlocker):
                        require_exact_sequence(original, (0, 1), recognized, 'c'*64, 'a'*64)

    def test_reference_proposal_keeps_compound_words_whole_when_recovering_a_repeat(self):
        line = 'You are Most-Forgiving'
        original = self.original(line+'\n'+line)
        phrase = 'you are most forgiving'
        self.assertIsNone(reference_adaptation(original, phrase+' '+phrase))
        summary, proposal = reference_adaptation(original, ' '.join([phrase]*3))
        self.assertEqual(summary['status'], 'review-required')
        self.assertIs(summary['publicationAuthority'], False)
        self.assertIs(summary['timingAuthority'], False)
        self.assertEqual(proposal, (line+'\n')*3)
        summary, proposal = reference_adaptation(original, phrase+' you are most mistaken')
        self.assertEqual(summary['status'], 'unresolved')
        self.assertIsNone(proposal)

    def test_word_joins_contractions_romanization_and_marks_remain_distinct(self):
        for display, recognized, language in (
                ('re-sign', 'resign', 'en'), ('therapist', 'the rapist', 'en'),
                ('Most-Forgiving', 'mostforgiving', 'en'), ('Most-Forgiving', 'forgiving', 'en'),
                ("I'll", 'ill', 'en'), ("can't", 'can not', 'en'), ('well', "we'll", 'en'),
                ('Alhamdulillah', 'الحمد لله', 'mul'), ('عَلَم', 'علم', 'ar'),
                ('حَمْد', 'حَمد', 'ar'), ('café', 'cafe', 'en'), ('s', 'ş', 'tr')):
            with self.subTest(display=display, recognized=recognized), self.assertRaises(LyricsContentBlocker):
                require_exact_sequence(self.original(display, language), (0,), recognized, 'c'*64, 'a'*64)
        require_exact_sequence(self.original('don’t'), (0,), "don't", 'c'*64, 'a'*64)

    def test_real_mms_and_whisper_paths_preserve_one_owner_for_two_spoken_units(self):
        original = self.original('Most-Forgiving')
        mapping = build_lexical_occurrence_mapping('abcdefghijk', original, (0, 0))
        request, line_ids, word_ids, _ = _forced_alignment_request('abcdefghijk', [0, 0], mapping,
            [display_words(original['text'])]*2, duration_ms=4000)
        plan, acoustic, _, _ = _request_plan(request)
        self.assertEqual(acoustic, ['most', 'forgiving', 'most', 'forgiving'])
        self.assertEqual([[(word['wordId'], word['wordCount']) for word in line['words']] for line in plan],
            [[(word_ids[0][0], 2)], [(word_ids[1][0], 2)]])
        self.assertNotEqual(word_ids[0][0], word_ids[1][0])
        transcript = [WordTimestamp(word=word, raw=word, start=.1+index*.5, end=.4+index*.5)
            for index, word in enumerate(('most', 'forgiving', 'most', 'forgiving'))]
        candidates = whisper_word_candidates(original['text'], (0, 0), [transcript], 4000,
            occurrence_mapping=mapping, transcript_labels=['known-answer'])
        self.assertEqual([candidate['lineId'] for candidate in candidates], line_ids)
        self.assertEqual([candidate['words'][0] for candidate in candidates], [
            {'wordId': word_ids[0][0], 'startUtf16': 0, 'endUtf16': 14, 'startMs': 100, 'endMs': 900},
            {'wordId': word_ids[1][0], 'startUtf16': 0, 'endUtf16': 14, 'startMs': 1100, 'endMs': 1900}])

    def test_projection_and_discrepancy_scope_keep_display_owner_coordinates(self):
        from stemwise_engine.lyric_comparison import project, fold_units
        value = project('Most-Forgiving')
        self.assertEqual(value.words, ((0, 14, 'Most-Forgiving'),))
        self.assertEqual(value.units, ('most', 'forgiving'))
        self.assertEqual(value.owners, (0, 0))
        self.assertEqual(value.boundaries, (0, 2))
        mixed = project('Most-Forgiving I', forms=(('most forgiving', 'en'), ('I', 'tr')))
        self.assertEqual(mixed.units, ('most', 'forgiving', 'ı'))
        self.assertEqual(fold_units(project('Most-Forgiving I').raw_units, mixed.languages), mixed.units)
        for recognized, actual_owner in (('you are most mistaken', 3), ('you are most-mistaken', 2)):
            scope = diagnostic_discrepancy_scope(self.original('You are Most-Forgiving'), (0,), recognized)
            self.assertEqual(scope['possiblePerformedIndices'], [0])
            self.assertEqual(scope['targetWordIndices'], [2])
            self.assertEqual(scope['recognizedWordIndices'], [actual_owner])

    def test_adjudication_coordinates_stay_in_display_words_across_hyphen_boundaries(self):
        for display, recognized, expected_word, recognized_word in (
                ('You are Most-Forgiving always now', 'you are most forgiving now', 3, 4),
                ('You are most forgiving always now', 'you are Most-Forgiving now', 4, 3)):
            with self.subTest(display=display), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[2]/'work') as name:
                root = Path(name)
                evidence = root/'known-answer.json'
                raw = json.dumps({'recognized': recognized}).encode('utf-8')
                evidence.write_bytes(raw)
                review = {'formatVersion': 2, 'videoId': 'abcdefghijk', 'audioSha256': 'a'*64,
                    'draftSha256': 'c'*64, 'metadataSha256': 'd'*64,
                    'recognition': {'path': evidence.name, 'sha256': hashlib.sha256(raw).hexdigest()},
                    'reviewer': 'Known-answer fixture', 'completedAt': '2026-11-15T00:00:00Z',
                    'spans': [{'performedIndex': 0, 'sourcePhysicalIndex': 0,
                        'startWordIndex': expected_word, 'endWordIndex': expected_word+1,
                        'recognizedStartWordIndex': recognized_word, 'recognizedEndWordIndex': recognized_word,
                        'startMs': 100, 'endMs': 200, 'decision': 'confirmed-asr-omission',
                        'heardText': 'always', 'rationale': 'Independent known-answer fixture omission.'}]}
                result = require_recording_adjudication(review, video_id='abcdefghijk',
                    original=self.original(display), indices=(0,), transcript=recognized,
                    audio_sha='a'*64, duration_ms=1000, draft_sha='c'*64, metadata_sha='d'*64,
                    evidence_path=evidence, evidence_root=root)
                self.assertEqual(result['spanCount'], 1)
                self.assertEqual(evidence.read_bytes(), raw)
                self.assertEqual(review['spans'][0]['startWordIndex'], expected_word)

    def test_existing_reviewed_spoken_plan_keeps_owner_when_recognition_hyphenates_units(self):
        from test_spoken_targets import SpokenTargetsTests, VIDEO
        from stemwise_engine.spoken_targets import load
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2]/'work') as name:
            root = Path(name)
            original, _ = SpokenTargetsTests().fixture(root)
            before = copy.deepcopy(original)
            mapping = build_lexical_occurrence_mapping(VIDEO, original, (0, 1, 2))
            plan = load(root, VIDEO, original, (0, 1, 2), 'a'*64)
            value = {**original, '_spokenTargets': plan}
            require_exact_sequence(value, (0, 1, 2),
                'in twenty-twenty-six first one two hello merhaba sawubona', 'c'*64, 'a'*64)
            with self.assertRaises(LyricsContentBlocker):
                require_exact_sequence(value, (0, 1, 2),
                    'in twenty-six first one two hello merhaba sawubona', 'c'*64, 'a'*64)
            self.assertEqual(original, before)
            self.assertEqual(build_lexical_occurrence_mapping(VIDEO, original, (0, 1, 2)), mapping)


if __name__ == '__main__':
    unittest.main()
