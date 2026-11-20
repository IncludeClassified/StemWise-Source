from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[3]
BUILDER = ROOT / "PC side" / "stemwise-playlist-builder"

import stemwise_engine.contract as contract  # noqa: E402
import stemwise_engine.song_enrichment_v2 as song_enrichment_v2  # noqa: E402
from stemwise_engine.lyrics_tokenization import (  # noqa: E402
    TOKENIZATION_VERSION,
    display_word_ranges,
    display_words,
)


CORPUS = (
    ROOT
    / "Test suite"
    / "android"
    / "unit"
    / "resources"
    / "fixtures"
    / "enrichment"
    / "lyrics-tokenization-v2-corpus.json"
)
V2_SCHEMA = ROOT / 'StemWise/contracts/enrichment/song-enrichment-v2.schema.json'


def valid_document() -> dict:
    return {
        "formatVersion": 2,
        "kind": "stemwise.song-enrichment",
        "source": {"provider": "youtube", "videoId": "a1B2c3D4e5F"},
        "generatedAt": "2026-08-23T12:00:00Z",
        "generator": {"name": "stemwise-pc", "version": "2.1.0"},
        "lyrics": {
            "status": "complete",
            "tokenizationVersion": TOKENIZATION_VERSION,
            "tracks": [
                {
                    "id": "original",
                    "kind": "original",
                    "language": "en",
                    "format": "synced",
                    "lines": [
                        {
                            "startMs": 100,
                            "endMs": 900,
                            "text": "can't re-enter B2B",
                            "words": [
                                {"startUtf16": 0, "endUtf16": 5, "startMs": 100, "endMs": 300},
                                {"startUtf16": 6, "endUtf16": 14, "startMs": 280, "endMs": 600},
                                {"startUtf16": 15, "endUtf16": 18, "startMs": 610, "endMs": 900},
                            ],
                        }
                    ],
                    "source": {"name": "Owner reviewed"},
                }
            ],
        },
    }


class SongEnrichmentV2Test(unittest.TestCase):
    def test_newer_script_letters_remain_words_and_distinct_authority(self):
        from stemwise_engine.lyrics_unicode import classification
        from stemwise_engine.lyrics_tokenization import lexical_authority_sha256
        for code, flags in ((0x11F12, 1), (0x11F13, 1), (0x1E4D0, 1), (0x1E4EC, 4), (0x1E4F0, 3)):
            self.assertEqual(classification(code)[0], flags, hex(code))
        self.assertEqual(display_word_ranges('\U00011f12\U00011f13'), [(0, 4)])
        def authority(letter):
            return lexical_authority_sha256({'id': 'original', 'kind': 'original',
                'language': 'jv', 'text': 'a ' + letter})
        self.assertNotEqual(authority('\U00011f12'), authority('\U00011f13'))

    @classmethod
    def setUpClass(cls) -> None:
        v2 = json.loads(V2_SCHEMA.read_text(encoding="utf-8"))
        cls.schema = Draft202012Validator(v2, format_checker=FormatChecker())

    def assert_schema_valid(self, document: dict) -> None:
        errors = sorted(self.schema.iter_errors(document), key=lambda error: list(error.path))
        self.assertEqual([], errors, [error.message for error in errors])

    def assert_schema_invalid(self, document: dict) -> None:
        self.assertTrue(list(self.schema.iter_errors(document)))

    def assert_contract_invalid(self, document: dict, path: str) -> None:
        with self.assertRaises(contract.ContractError) as caught:
            song_enrichment_v2.validate_song(document)
        self.assertEqual(path, caught.exception.path)

    def test_generator_identity_is_exact_current_release(self) -> None:
        for field, value in (("name", "another-pc"), ("version", "2.0.1")):
            with self.subTest(field=field):
                document = valid_document()
                document["generator"][field] = value
                self.assert_contract_invalid(document, f"$.generator.{field}")
                self.assert_schema_invalid(document)

    def test_pinned_unicode_table_preserves_assigned_unicode13_flags(self):
        import unicodedata
        from stemwise_engine.lyrics_unicode import classification, UNICODE_VERSION
        self.assertEqual(UNICODE_VERSION, '18.0.0')
        self.assertEqual(unicodedata.unidata_version, '13.0.0')
        for codepoint in range(0x110000):
            character = chr(codepoint)
            category = unicodedata.category(character)
            if category == 'Cn':
                continue  # Later assigned characters are covered by current-version known answers.
            word, number, mark = category[0] in 'LN', category[0] == 'N', category[0] == 'M'
            flags = int(word) | (int(number) << 1) | (int(mark) << 2)
            if classification(codepoint)[0] != flags:
                self.fail(f'Pinned Unicode classification differs at U+{codepoint:04X}')
        # Known answers from the pinned Script and Script_Extensions properties.
        for character, script in (('A', {'Latin'}), ('Ａ', {'Latin'}), ('K', {'Latin'}),
                ('ʻ', None), ('ʼ', {'Bengali', 'Cyrillic', 'Devanagari', 'Latin', 'Lisu', 'Thai', 'Toto'}),
                ('ー', {'cjk'}), ('𗀀', {'Tangut'}),
                ('\U00011f12', {'Kawi'}), ('\U0001e4d0', {'Nag_Mundari'}),
                ('ـ', {'Adlam', 'Arabic', 'Mandaic', 'Manichaean', 'Old_Uyghur',
                        'Psalter_Pahlavi', 'Hanifi_Rohingya', 'Sogdian', 'Syriac'})):
            self.assertEqual(classification(ord(character))[1], script, character)

    def test_shared_tokenizer_corpus(self) -> None:
        corpus = json.loads(CORPUS.read_text(encoding="utf-8"))
        self.assertEqual(TOKENIZATION_VERSION, corpus["tokenizationVersion"])
        for case in corpus["cases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(
                    [tuple(value) for value in case["ranges"]],
                    display_word_ranges(case["text"]),
                )
                encoded = case["text"].encode("utf-16-le")
                self.assertEqual(
                    [
                        (
                            start,
                            end,
                            encoded[start * 2 : end * 2].decode("utf-16-le"),
                        )
                        for start, end in case["ranges"]
                    ],
                    display_words(case["text"]),
                )
        for case in corpus["negativeCases"]:
            with self.subTest(case=case["id"]):
                self.assertNotEqual(
                    [tuple(value) for value in case["ranges"]],
                    display_word_ranges(case["text"]),
                )

    def test_valid_word_and_line_only_documents(self) -> None:
        document = valid_document()
        self.assertIs(song_enrichment_v2.validate_song(document), document)
        self.assert_schema_valid(document)

        line_only = copy.deepcopy(document)
        del line_only["lyrics"]["tokenizationVersion"]
        del line_only["lyrics"]["tracks"][0]["lines"][0]["words"]
        self.assertIs(song_enrichment_v2.validate_song(line_only), line_only)
        self.assert_schema_valid(line_only)

    def test_line_end_and_tokenization_version_are_conditional_requirements(self) -> None:
        missing_end = valid_document()
        del missing_end["lyrics"]["tracks"][0]["lines"][0]["endMs"]
        self.assert_contract_invalid(missing_end, "$.lyrics.tracks[0].lines[0]")
        self.assert_schema_invalid(missing_end)

        missing_version = valid_document()
        del missing_version["lyrics"]["tokenizationVersion"]
        self.assert_contract_invalid(missing_version, "$.lyrics")
        self.assert_schema_invalid(missing_version)

        malformed_tracks = valid_document()
        malformed_tracks["lyrics"]["tracks"] = None
        self.assert_contract_invalid(malformed_tracks, "$.lyrics.tracks")
        self.assert_schema_invalid(malformed_tracks)

    def test_line_starts_are_nondecreasing_and_array_order_breaks_ties(self) -> None:
        tied = valid_document()
        del tied["lyrics"]["tokenizationVersion"]
        del tied["lyrics"]["tracks"][0]["lines"][0]["words"]
        tied["lyrics"]["tracks"][0]["lines"].append(
            {"startMs": 100, "endMs": 500, "text": "same instant"}
        )
        self.assertIs(song_enrichment_v2.validate_song(tied), tied)
        self.assert_schema_valid(tied)

        descending = copy.deepcopy(tied)
        descending["lyrics"]["tracks"][0]["lines"][1]["startMs"] = 99
        self.assert_contract_invalid(
            descending,
            "$.lyrics.tracks[0].lines[1].startMs",
        )

    def test_words_require_exact_display_ranges_and_contained_times(self) -> None:
        bad_range = valid_document()
        bad_range["lyrics"]["tracks"][0]["lines"][0]["words"][0]["endUtf16"] = 4
        self.assert_contract_invalid(bad_range, "$.lyrics.tracks[0].lines[0].words[0]")

        outside_line = valid_document()
        outside_line["lyrics"]["tracks"][0]["lines"][0]["words"][0]["startMs"] = 99
        self.assert_contract_invalid(outside_line, "$.lyrics.tracks[0].lines[0].words[0]")

    def test_words_are_original_only_and_precision_is_never_public(self) -> None:
        translated = valid_document()
        translation = copy.deepcopy(translated["lyrics"]["tracks"][0])
        translation.update(
            id="translation-en",
            kind="translation",
            basedOnTrackId="original",
        )
        translated["lyrics"]["tracks"].append(translation)
        self.assert_contract_invalid(
            translated,
            "$.lyrics.tracks[1].lines[0].words",
        )
        self.assert_schema_invalid(translated)

        precision = valid_document()
        precision["lyrics"]["tracks"][0]["lines"][0]["precision"] = "high"
        self.assert_contract_invalid(precision, "$.lyrics.tracks[0].lines[0]")
        self.assert_schema_invalid(precision)

    def test_synced_companion_must_reuse_exact_original_timeline(self) -> None:
        document = valid_document()
        companion = copy.deepcopy(document["lyrics"]["tracks"][0])
        companion.update(
            id="translation-en",
            kind="translation",
            language="en",
            basedOnTrackId="original",
        )
        del companion["lines"][0]["words"]
        companion["lines"][0]["endMs"] = 899
        document["lyrics"]["tracks"].append(companion)

        self.assert_contract_invalid(document, "$.lyrics.tracks[1].lines")

    def test_public_contract_entrypoint_calls_the_complete_v2_lyrics_validator(self) -> None:
        lyrics = valid_document()["lyrics"]
        lyrics["tracks"][0]["lines"].append(
            {"startMs": 100, "endMs": 500, "text": "same instant"}
        )
        self.assertIs(contract.validate_lyrics(lyrics), lyrics)

    def test_companion_language_and_original_timing_boundaries(self) -> None:
        for language in ('en-US', 'EN'):
            document = valid_document()
            document['lyrics']['status'] = 'partial'
            document['lyrics']['reason'] = 'Missing IPA.'
            document['lyrics']['tracks'][0]['language'] = 'tr'
            document['lyrics']['tracks'].append({'id': 'translation-en', 'kind': 'translation',
                'language': language, 'format': 'plain', 'text': 'Hello',
                'basedOnTrackId': 'original', 'source': {'name': 'Fixture'}})
            with self.subTest(language=language):
                self.assert_contract_invalid(document, '$.lyrics.tracks[1].language')
                self.assert_schema_invalid(document)

        document['lyrics']['tracks'][1].update(language='en', format='synced',
            lines=[{'startMs': 100, 'endMs': 900, 'text': 'Hello'}])
        del document['lyrics']['tracks'][1]['text']
        original = document['lyrics']['tracks'][0]
        original.update(format='plain', text='Merhaba')
        del original['lines']
        self.assert_contract_invalid(document, '$.lyrics.tracks[1].lines')

    def test_ipa_syntax_is_checked_for_plain_and_synced_public_tracks(self) -> None:
        for body in ('😀', '\u0303a', 'a\u200eb', 't\u0361', 'ʰ', '[unclosed', 'مرحبا'):
            for synced in (False, True):
                document = valid_document()
                document['lyrics'].update(status='partial', reason='Missing translation.')
                document['lyrics']['tracks'][0]['language'] = 'tr'
                track = {'id': 'ipa', 'kind': 'transliteration', 'language': 'tr-fonipa',
                    'basedOnTrackId': 'original', 'source': {'name': 'Fixture'},
                    'format': 'synced' if synced else 'plain'}
                track.update({'lines': [{'startMs': 100, 'endMs': 900, 'text': body}]}
                    if synced else {'text': body})
                document['lyrics']['tracks'].append(track)
                with self.subTest(body=body, synced=synced):
                    self.assert_contract_invalid(document, '$.lyrics.tracks[1].lines[0].text'
                        if synced else '$.lyrics.tracks[1].text')

    def test_fonipa_must_be_a_variant_before_an_extension(self) -> None:
        for language in ('tr-x-fonipa', 'tr-u-fonipa'):
            document = valid_document()
            document['lyrics'].update(status='partial', reason='Missing translation.')
            document['lyrics']['tracks'][0]['language'] = 'tr'
            document['lyrics']['tracks'].append({'id': 'ipa', 'kind': 'transliteration',
                'language': language, 'format': 'plain', 'text': 'salam',
                'basedOnTrackId': 'original', 'source': {'name': 'Fixture'}})
            with self.subTest(language=language):
                self.assert_contract_invalid(document, '$.lyrics.tracks[1].language')
        self.assertTrue(contract._has_language_variant('tr-TR-fonipa', 'fonipa'))
        self.assertTrue(contract._has_language_variant('tr-fonipa-x-note', 'fonipa'))

    def test_shared_android_companion_contract_corpus(self):
        path = CORPUS.with_name('companion-contract-v1-corpus.json')
        for case in json.loads(path.read_text(encoding='utf-8'))['cases']:
            document = valid_document()
            document['lyrics'].update(status='partial', reason='Missing IPA.')
            document['lyrics'].pop('tokenizationVersion')
            original = document['lyrics']['tracks'][0]
            original.update(language='tr', format=case['originalFormat'])
            if case['originalFormat'] == 'plain':
                del original['lines']
                original['text'] = 'Merhaba'
            else:
                original['lines'] = [{'startMs': 100, 'endMs': 1000, 'text': 'Merhaba'}]
            companion = {'id': 'companion', 'kind': case.get('kind', 'translation'),
                'language': case['language'], 'format': case['companionFormat'],
                'basedOnTrackId': 'original', 'source': {'name': 'Fixture'}}
            text = 'salam' if companion['kind'] == 'transliteration' else 'Hello'
            companion.update({'text': text} if case['companionFormat'] == 'plain' else
                {'lines': [{'startMs': 100, 'endMs': case['endMs'], 'text': text}]})
            document['lyrics']['tracks'].append(companion)
            with self.subTest(case=case['id']):
                raw = json.dumps(document, ensure_ascii=False).encode('utf-8')
                if case['valid']:
                    self.assertEqual(song_enrichment_v2.load_and_validate_song_json(raw), document)
                else:
                    with self.assertRaises(contract.ContractError):
                        song_enrichment_v2.load_and_validate_song_json(raw)
                self.assertEqual(not list(self.schema.iter_errors(document)), case['schemaValid'])

    def test_v2_lyrics_preserve_all_structural_invariants_without_a_legacy_shape(self) -> None:
        duplicate_id = valid_document()
        companion = copy.deepcopy(duplicate_id["lyrics"]["tracks"][0])
        companion.update(kind="translation", language="en", basedOnTrackId="original")
        del companion["lines"][0]["words"]
        duplicate_id["lyrics"]["tracks"].append(companion)
        self.assert_contract_invalid(duplicate_id, "$.lyrics.tracks[1].id")

        bad_language = valid_document()
        bad_language["lyrics"]["tracks"][0]["language"] = "not_valid"
        self.assert_contract_invalid(bad_language, "$.lyrics.tracks[0].language")

        unknown_track_field = valid_document()
        unknown_track_field["lyrics"]["tracks"][0]["legacyText"] = "forbidden"
        self.assert_contract_invalid(unknown_track_field, "$.lyrics.tracks[0]")

        unresolved_companion = valid_document()
        companion = copy.deepcopy(unresolved_companion["lyrics"]["tracks"][0])
        companion.update(
            id="translation-en",
            kind="translation",
            language="en",
            basedOnTrackId="missing-original",
        )
        del companion["lines"][0]["words"]
        unresolved_companion["lyrics"]["tracks"].append(companion)
        self.assert_contract_invalid(
            unresolved_companion,
            "$.lyrics.tracks[1].basedOnTrackId",
        )

    def test_v1_transport_is_not_accepted_as_v2(self) -> None:
        document = valid_document()
        document["formatVersion"] = 1
        self.assert_contract_invalid(document, "$.formatVersion")
        self.assert_schema_invalid(document)


if __name__ == "__main__":
    unittest.main()
