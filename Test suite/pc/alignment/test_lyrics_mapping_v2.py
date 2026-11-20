from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
BUILDER = ROOT / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.lyrics_alignment import (  # noqa: E402
    build_lexical_occurrence_mapping,
    reproject_display_only,
)
from stemwise_engine.lyrics_tokenization import (  # noqa: E402
    TOKENIZATION_VERSION,
    display_words,
    lexical_authority_sha256,
)
from stemwise_engine.song_enrichment_v2 import validate_lyrics  # noqa: E402
from syncalong.textnorm import normalize  # noqa: E402


VIDEO_ID = "KbKqkR3Q6SA"


def original_track(text: str, *, language: str = "ar", script: str = "Arab") -> dict:
    return {
        "id": "original",
        "kind": "original",
        "language": language,
        "script": script,
        "format": "plain",
        "text": text,
        "source": {"name": "Owner reviewed"},
    }


def timed_lyrics(original: dict, sources: tuple[int, ...], mapping: dict) -> dict:
    physical = original["text"].splitlines()
    lines = []
    for performed, (source, occurrence) in enumerate(
        zip(sources, mapping["performedOccurrences"])
    ):
        start_ms = 100 + performed * 1_500
        end_ms = start_ms + 1_200
        words = display_words(physical[source])
        step = 1_000 // len(words)
        lines.append(
            {
                "lineId": occurrence["lineId"],
                "occurrenceId": occurrence["occurrenceId"],
                "startMs": start_ms,
                "endMs": end_ms,
                "text": physical[source],
                "words": [
                    {
                        "wordId": private["occurrenceWordId"],
                        "authorityWordId": private["authorityWordId"],
                        "startUtf16": start_utf16,
                        "endUtf16": end_utf16,
                        "startMs": start_ms + word * step,
                        "endMs": start_ms + (word + 1) * step,
                    }
                    for word, ((start_utf16, end_utf16, _), private) in enumerate(
                        zip(words, occurrence["words"])
                    )
                ],
            }
        )
    synced = copy.deepcopy(original)
    synced.pop("text")
    synced["format"] = "synced"
    synced["lines"] = lines
    return {
        "status": "partial",
        "reason": "Only the reviewed original track is available.",
        "tokenizationVersion": TOKENIZATION_VERSION,
        "lexicalAuthoritySha256": mapping["lexicalAuthoritySha256"],
        "tracks": [synced],
    }


class LyricsMappingV2Tests(unittest.TestCase):
    def test_declared_azerbaijani_display_case_preserves_reviewed_authority(self):
        for language, components in [('az', None), *[(tag, [owner, 'en'])
                for tag in ('mul', 'MUL', 'mul-Latn') for owner in ('az', 'tr')]]:
            with self.subTest(language=language, components=components):
                plain = original_track('QIR', language=language, script='Latn')
                if components:
                    plain['componentLanguages'] = components
                mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, (0,))
                before = copy.deepcopy(mapping)
                timed = timed_lyrics(plain, (0,), mapping)
                owners = [[components[0]]] if components else None
                projected = reproject_display_only(timed, 'qır', mapping, 10_000,
                    authority_original_text=plain['text'], reviewed_word_languages=owners)
                self.assertEqual(projected['tracks'][0]['lines'][0]['text'], 'qır')
                self.assertEqual(projected['tracks'][0]['lines'][0]['words'],
                    [{key: value for key, value in word.items()
                      if key not in {'wordId', 'authorityWordId', 'occurrenceWordId'}}
                     for word in timed['tracks'][0]['lines'][0]['words']])
                with self.assertRaises(ValueError):
                    reproject_display_only(timed, 'qir', mapping, 10_000,
                        authority_original_text=plain['text'], reviewed_word_languages=owners)
                self.assertEqual(mapping, before)

    def test_mixed_display_case_uses_each_reviewed_word_and_repeated_occurrence_owner(self):
        for language in ('az', 'tr'):
            with self.subTest(language=language):
                plain = original_track('QIR I\nKİR I', language='mul', script='Latn')
                plain['componentLanguages'] = [language, 'en']
                sources = (0, 1, 0)
                mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, sources)
                timed = timed_lyrics(plain, sources, mapping)
                owners = [[language, 'en'] for _ in sources]
                before = copy.deepcopy((mapping, timed))
                result = reproject_display_only(timed, 'qır i\nkir i', mapping, 10_000,
                    authority_original_text=plain['text'], reviewed_word_languages=owners)
                self.assertEqual([line['text'] for line in result['tracks'][0]['lines']],
                    ['qır i', 'kir i', 'qır i'])
                self.assertEqual([line['startMs'] for line in result['tracks'][0]['lines']],
                    [line['startMs'] for line in timed['tracks'][0]['lines']])
                for edited in ('qir i\nkir i', 'qır ı\nkir i', 'qır i\nkır i'):
                    with self.subTest(edited=edited), self.assertRaises(ValueError):
                        reproject_display_only(timed, edited, mapping, 10_000,
                            authority_original_text=plain['text'], reviewed_word_languages=owners)
                for invalid in (owners[:-1], [[language]]*3, [['fr', 'en']]*3,
                        [owners[0], owners[1], ['en', 'en']]):
                    with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                        reproject_display_only(timed, plain['text'], mapping, 10_000,
                            authority_original_text=plain['text'], reviewed_word_languages=invalid)
                self.assertEqual((mapping, timed), before)

    def test_unreviewed_mixed_display_case_withholds_ambiguous_i_changes(self):
        for language in ('az', 'tr'):
            plain = original_track('QIR I\nHELLO', language='mul', script='Latn')
            plain['componentLanguages'] = [language, 'en']
            mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, (0, 1))
            timed = timed_lyrics(plain, (0, 1), mapping)
            allowed = reproject_display_only(timed, 'QIR I\nhello!', mapping, 10_000,
                authority_original_text=plain['text'])
            self.assertEqual(allowed['tracks'][0]['lines'][1]['text'], 'hello!')
            for edited in ('qır i\nhello', 'qir i\nhello', 'QIR ı\nhello'):
                with self.subTest(language=language, edited=edited), self.assertRaises(ValueError):
                    reproject_display_only(timed, edited, mapping, 10_000,
                        authority_original_text=plain['text'])

    def test_reviewed_unknown_component_does_not_supply_concrete_case_authority(self):
        plain = original_track('QIR I', language='mul', script='Latn')
        plain['componentLanguages'] = ['tr', 'swe']
        mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, (0,))
        timed = timed_lyrics(plain, (0,), mapping)
        owners = [['tr', None]]
        result = reproject_display_only(timed, 'qır I', mapping, 10_000,
            authority_original_text=plain['text'], reviewed_word_languages=owners)
        self.assertEqual(result['tracks'][0]['lines'][0]['text'], 'qır I')
        for edited in ('qır i', 'qır ı', 'qir I'):
            with self.subTest(edited=edited), self.assertRaises(ValueError):
                reproject_display_only(timed, edited, mapping, 10_000,
                    authority_original_text=plain['text'], reviewed_word_languages=owners)

    def test_english_display_case_does_not_admit_a_turkic_i(self):
        plain = original_track('I', language='en', script='Latn')
        mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, (0,))
        timed = timed_lyrics(plain, (0,), mapping)
        self.assertEqual(reproject_display_only(timed, 'i', mapping, 10_000,
            authority_original_text='I')['tracks'][0]['lines'][0]['text'], 'i')
        with self.assertRaises(ValueError):
            reproject_display_only(timed, 'ı', mapping, 10_000, authority_original_text='I')

    def test_modifier_apostrophe_preserves_the_supported_word_and_display_identity(self):
        plain = original_track("l'amour", language='fr', script='Latn')
        mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, (0,))
        timed = timed_lyrics(plain, (0,), mapping)
        edited = "l\u02bcamour"
        self.assertEqual(display_words(edited), [(0,7,edited)])
        self.assertEqual(lexical_authority_sha256(original_track(edited, language='fr', script='Latn')),
                         mapping['lexicalAuthoritySha256'])
        projected = reproject_display_only(timed, edited, mapping, 10_000)
        self.assertEqual(len(projected['tracks'][0]['lines'][0]['words']), 1)

    def test_turkish_display_equivalence_preserves_targets_and_the_frozen_authority(self):
        plain = original_track('KIR', language='tr', script='Latn')
        mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, (0,))
        timed = timed_lyrics(plain, (0,), mapping)
        before = copy.deepcopy(mapping)
        with self.assertRaisesRegex(ValueError, 'authority|acoustic'):
            reproject_display_only(timed, 'kir', mapping, 10_000)
        projected = reproject_display_only(timed, 'kır', mapping, 10_000,
            authority_original_text=plain['text'])
        line = projected['tracks'][0]['lines'][0]
        self.assertEqual(line['text'], 'kır')
        self.assertEqual((line['startMs'],line['endMs']),
            (timed['tracks'][0]['lines'][0]['startMs'],timed['tracks'][0]['lines'][0]['endMs']))
        self.assertEqual(mapping, before)

    def test_turkish_dotted_i_and_multilingual_ambiguity_preserve_authority(self):
        for source, allowed, rejected in [('KIR','kır','kir'), ('KİR','kir','kır')]:
            plain = original_track(source, language='tr', script='Latn')
            mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, (0,))
            original_hash = lexical_authority_sha256(plain)
            timed = timed_lyrics(plain, (0,), mapping)
            projected = reproject_display_only(timed, allowed, mapping, 10_000,
                authority_original_text=source)
            self.assertEqual(projected['tracks'][0]['lines'][0]['text'], allowed)
            with self.assertRaises(ValueError):
                reproject_display_only(timed, rejected, mapping, 10_000, authority_original_text=source)
            self.assertEqual(mapping['lexicalAuthoritySha256'], original_hash)
        plain = original_track('KIR', language='mul', script='Latn')
        plain['componentLanguages'] = ['tr', 'en']
        mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, (0,))
        with self.assertRaises(ValueError):
            reproject_display_only(timed_lyrics(plain, (0,), mapping), 'kir', mapping, 10_000,
                authority_original_text=plain['text'])

    def test_stable_text_free_ids_survive_acoustic_and_allowed_display_views(self) -> None:
        plain = original_track("اَلْحُبُّ جَمِيلٌ\ncan't re-enter")
        decorated = original_track("اَلْحُبُّ جَمِيلٌ!\nCAN’T re‐enter")
        sources = (0, 1, 0)
        baseline = build_lexical_occurrence_mapping(VIDEO_ID, plain, sources)
        display = build_lexical_occurrence_mapping(VIDEO_ID, decorated, sources)

        self.assertEqual(lexical_authority_sha256(plain), lexical_authority_sha256(decorated))
        self.assertEqual(baseline, display)
        self.assertNotEqual(normalize("اَلْحُبُّ"), "اَلْحُبُّ")
        self.assertNotIn("الحب", json.dumps(baseline, ensure_ascii=False))
        self.assertNotIn("re-enter", json.dumps(baseline, ensure_ascii=False))
        self.assertEqual(
            baseline["performedOccurrences"][0]["words"][0]["authorityWordId"],
            baseline["performedOccurrences"][2]["words"][0]["authorityWordId"],
        )
        self.assertNotEqual(
            baseline["performedOccurrences"][0]["occurrenceId"],
            baseline["performedOccurrences"][2]["occurrenceId"],
        )
        self.assertNotEqual(
            baseline["performedOccurrences"][0]["words"][0]["occurrenceWordId"],
            baseline["performedOccurrences"][2]["words"][0]["occurrenceWordId"],
        )

    def test_display_reprojection_preserves_time_and_updates_utf16_ranges(self) -> None:
        plain = original_track("اَلْحُبُّ جَمِيلٌ\ncan't re-enter")
        sources = (0, 1, 0)
        mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, sources)
        timed = timed_lyrics(plain, sources, mapping)
        before = [
            (
                line["startMs"],
                line["endMs"],
                [(word["startMs"], word["endMs"]) for word in line["words"]],
            )
            for line in timed["tracks"][0]["lines"]
        ]
        display = "✨ اَلْحُبُّ جَمِيلٌ!\n“CAN’T re‐enter”"

        projected = reproject_display_only(timed, display, mapping, 10_000)

        after = [
            (
                line["startMs"],
                line["endMs"],
                [(word["startMs"], word["endMs"]) for word in line["words"]],
            )
            for line in projected["tracks"][0]["lines"]
        ]
        self.assertEqual(before, after)
        self.assertEqual(
            [
                (word["startUtf16"], word["endUtf16"])
                for word in projected["tracks"][0]["lines"][0]["words"]
            ],
            [(start, end) for start, end, _ in display_words(display.splitlines()[0])],
        )
        self.assertGreater(projected["tracks"][0]["lines"][0]["words"][0]["startUtf16"], 0)
        external = json.dumps(projected, ensure_ascii=False)
        for private_key in (
            "lexicalAuthoritySha256",
            "lineId",
            "occurrenceId",
            "wordId",
            "authorityWordId",
            "occurrenceWordId",
        ):
            self.assertNotIn(private_key, external)
        self.assertIs(validate_lyrics(projected, duration_ms=10_000), projected)

    def test_lexical_word_order_line_and_connector_changes_are_rejected(self) -> None:
        plain = original_track("can't re-enter\nstay now", language="en", script="Latn")
        sources = (0, 1)
        mapping = build_lexical_occurrence_mapping(VIDEO_ID, plain, sources)
        timed = timed_lyrics(plain, sources, mapping)
        changed = (
            "can’t depart\nstay now",
            "stay now\ncan't re-enter",
            "can't\nre-enter\nstay now",
            "cant re-enter\nstay now",
            "can't reenter\nstay now",
        )
        for text in changed:
            with self.subTest(text=text), self.assertRaisesRegex(
                ValueError, "lexical authority|line structure|order"
            ):
                reproject_display_only(timed, text, mapping, 10_000)

    def test_pronunciation_marks_are_frozen_lexical_authority(self) -> None:
        arabic = original_track("اَلْحُبُّ جَمِيلٌ")
        mapping = build_lexical_occurrence_mapping(VIDEO_ID, arabic, (0,))
        timed = timed_lyrics(arabic, (0,), mapping)
        self.assertEqual(
            reproject_display_only(timed, "اَلْحُبُّ جَمِيلٌ", mapping, 10_000)["tracks"][0]["lines"][0]["text"],
            "اَلْحُبُّ جَمِيلٌ",
        )
        for changed in ('الحب جميل', 'آلحب جميل'):
            self.assertNotEqual(lexical_authority_sha256(arabic),
                lexical_authority_sha256(original_track(changed)))
            with self.assertRaises(ValueError):
                reproject_display_only(timed, changed, mapping, 10_000)
        unmarked = original_track('الحب جميل')
        unmarked_mapping = build_lexical_occurrence_mapping(VIDEO_ID, unmarked, (0,))
        with self.assertRaises(ValueError):
            reproject_display_only(timed_lyrics(unmarked, (0,), unmarked_mapping),
                                  arabic['text'], unmarked_mapping, 10_000)

        latin = original_track("Cafe noir", language="fr", script="Latn")
        latin_mapping = build_lexical_occurrence_mapping(VIDEO_ID, latin, (0,))
        with self.assertRaises(ValueError):
            reproject_display_only(
                timed_lyrics(latin, (0,), latin_mapping),
                "Café noir",
                latin_mapping,
                10_000,
            )
        with self.assertRaises(ValueError):
            reproject_display_only(
                timed_lyrics(latin, (0,), latin_mapping),
                "Cafeَ noir",
                latin_mapping,
                10_000,
            )


if __name__ == "__main__":
    unittest.main()
