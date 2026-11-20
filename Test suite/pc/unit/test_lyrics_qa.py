from __future__ import annotations

import sys
import unittest
import unicodedata
import json
import tempfile
import copy
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.lyrics_qa import _title_language_expectation, audit_text, audit_library  # noqa: E402
from test_song_enrichment_v2 import valid_document


class LyricsQaTests(unittest.TestCase):
    def test_partial_library_keeps_valid_diagnostics_and_reports_missing_and_invalid_sidecars(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as directory:
            root = Path(directory)
            (root/'output/work').mkdir(parents=True)
            (root/'owner-input').mkdir()
            items = [{'videoId': video, 'position': i} for i, video in enumerate(
                ('abcdefghijk', 'lmnopqrstuv', '01234567890', 'zyxwvutsrqp'), 1)]
            (root/'output/work/inventory.json').write_text(json.dumps({'items': items}), encoding='utf-8')
            lyrics = valid_document()['lyrics']
            lyrics['tracks'][0].pop('lines')
            lyrics['tracks'][0].update(format='plain', text='Hello.. world', script='Latn')
            (root/'owner-input/abcdefghijk.lyrics.json').write_text(json.dumps(lyrics), encoding='utf-8')
            (root/'owner-input/01234567890.lyrics.json').write_text('{broken', encoding='utf-8')
            (root/'owner-input/zyxwvutsrqp.lyrics.json').write_text('{"tracks":[3]}', encoding='utf-8')
            result = audit_library({'projectDir': str(root), 'outputDir': str(root/'output')})
            report = json.loads((root/'owner-input/lyrics-qa-report.json').read_bytes())
            self.assertEqual((result['inventoryCount'], result['availableOriginalCount'], result['missingSidecarCount'],
                result['invalidSidecarCount']), (4, 1, 1, 2))
            self.assertEqual(len(report['songs']), 4)
            self.assertEqual(report['provisionalCount'], 1)
            self.assertIn('visual-elongation-or-punctuation', report['issueCodeCounts'])
            self.assertEqual(report['issueCodeCounts']['lyrics-sidecar-missing'], 1)
            self.assertEqual(report['issueCodeCounts']['lyrics-sidecar-invalid'], 2)

    def test_malformed_nested_lines_do_not_abort_valid_siblings(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as directory:
            root = Path(directory)
            (root/'output/work').mkdir(parents=True)
            (root/'owner-input').mkdir()
            videos = ('abcdefghijk', 'lmnopqrstuv', '01234567890', 'zyxwvutsrqp')
            (root/'output/work/inventory.json').write_text(json.dumps({'items': [
                {'videoId': video, 'position': index} for index, video in enumerate(videos)]}), encoding='utf-8')
            for index, video in enumerate(videos):
                lyrics = copy.deepcopy(valid_document()['lyrics'])
                if index in (0, 2):
                    lyrics['tracks'][0]['lines'] = [None] if index == 0 else [{'text': 'bad', 'words': [None]}]
                (root/'owner-input'/f'{video}.lyrics.json').write_text(json.dumps(lyrics), encoding='utf-8')
            result = audit_library({'projectDir': str(root), 'outputDir': str(root/'output')})
            self.assertEqual(result['invalidSidecarCount'], 2)
            self.assertEqual(result['availableOriginalCount'], 2)
            report = json.loads((root/'owner-input/lyrics-qa-report.json').read_bytes())
            self.assertEqual(len(report['songs']), 4)
            for index in (0, 2):
                issue = report['songs'][index]['issues'][0]
                self.assertIn('lines', issue['path'])
                self.assertTrue(issue['reason'])

    def issue_codes(self, text: str, language: str, script: str) -> list[str]:
        return [issue["code"] for issue in audit_text(text, language, script)["issues"]]

    def test_clean_native_diacritics_and_repetitions_are_preserved(self) -> None:
        text = "Gönlümde ayrı yerin\nGönlümde ayrı yerin"
        audit = audit_text(text, "tr", "Latn")
        self.assertEqual(audit["issueCount"], 0)
        self.assertEqual(audit["repeatedLineOccurrenceCount"], 1)
        self.assertFalse(audit["deduplicationApplied"])

    def test_one_final_lf_is_required_terminator_not_a_blank_line(self) -> None:
        clean_codes = self.issue_codes("First line\nSecond line\n", "en", "Latn")
        blank_codes = self.issue_codes("First line\nSecond line\n\n", "en", "Latn")
        self.assertNotIn("trailing-blank-line", clean_codes)
        self.assertIn("trailing-blank-line", blank_codes)

    def test_nfc_replacement_controls_and_presentation_forms_are_rejected(self) -> None:
        decomposed = unicodedata.normalize("NFD", "é")
        text = f"{decomposed}\ufffd\u202e\ufefb"
        codes = self.issue_codes(text, "ar", "Arab")
        self.assertIn("unicode-not-nfc", codes)
        self.assertIn("replacement-character", codes)
        self.assertIn("bidi-control", codes)
        self.assertIn("presentation-form-character", codes)

    def test_layout_markup_timestamp_and_decoration_are_flagged(self) -> None:
        text = "[Chorus]\n\n\n<font>line</font>\nPeace &amp; blessings\n[00:01.20] line ✿ــــ"
        codes = self.issue_codes(text, "en", "Latn")
        self.assertIn("editorial-role-label", codes)
        self.assertIn("multiple-blank-section-lines", codes)
        self.assertIn("embedded-html", codes)
        self.assertIn("embedded-html-entity", codes)
        self.assertIn("embedded-timestamp", codes)
        self.assertIn("decorative-symbol", codes)
        self.assertIn("visual-elongation-or-punctuation", codes)

    def test_unbracketed_role_and_caption_labels_are_flagged(self) -> None:
        codes = self.issue_codes("Chorus:\nMusic", "en", "Latn")
        self.assertIn("editorial-role-label", codes)
        self.assertIn("caption-artifact-label", codes)

    def test_editorial_repetition_notation_is_flagged(self) -> None:
        codes = self.issue_codes("Sing this line (x3)", "en", "Latn")
        self.assertIn("editorial-repetition-notation", codes)

    def test_unbalanced_delimiter_and_honorific_annotation_are_flagged(self) -> None:
        codes = self.issue_codes("A performed response (echo\nMuhammad (pbuh)", "en", "Latn")
        self.assertIn("unbalanced-delimiter", codes)
        self.assertIn("editorial-honorific-annotation", codes)

    def test_two_repeated_periods_are_also_visual_elongation(self) -> None:
        codes = self.issue_codes("First phrase.. second phrase\nOooh", "en", "Latn")
        self.assertIn("visual-elongation-or-punctuation", codes)

    def test_every_day_word_boundary_rule_is_context_sensitive(self) -> None:
        self.assertIn(
            "probable-every-day-word-boundary",
            self.issue_codes("Everyday is a new start", "en", "Latn"),
        )
        self.assertIn(
            "probable-every-day-word-boundary",
            self.issue_codes("Focus everyday on the goal", "en", "Latn"),
        )
        self.assertNotIn(
            "probable-every-day-word-boundary",
            self.issue_codes("Everyday life can be complex", "en", "Latn"),
        )

    def test_caption_artifact_labels_are_rejected(self) -> None:
        codes = self.issue_codes("A lyric line\n[Müzik]", "tr", "Latn")
        self.assertIn("caption-artifact-label", codes)

    def test_title_language_expectation_handles_named_versions(self) -> None:
        self.assertEqual(_title_language_expectation("Song (Turkish Version)"), {"tr", "mul"})
        self.assertEqual(_title_language_expectation("Song Bahasa Indonesia"), {"id", "mul"})
        self.assertIsNone(_title_language_expectation("Ordinary song title"))

    def test_indic_combining_marks_are_not_treated_as_detached(self) -> None:
        audit = audit_text("नमस्ते दुनिया", "hi", "Deva")
        self.assertNotIn("detached-combining-mark", [item["code"] for item in audit["issues"]])
        self.assertGreater(audit["combiningMarkCount"], 0)

    def test_detached_mark_and_unexpected_script_require_review(self) -> None:
        text = "\u0301bad\nمرحبا"
        audit = audit_text(text, "en", "Latn")
        codes = [issue["code"] for issue in audit["issues"]]
        self.assertIn("detached-combining-mark", codes)
        self.assertIn("additional-script-present", codes)

    def test_mixed_script_profile_accepts_multiple_letter_scripts(self) -> None:
        audit = audit_text("Hello\nمرحبا", "mul", "Zyyy")
        self.assertNotIn("additional-script-present", [item["code"] for item in audit["issues"]])
        self.assertEqual(audit["detectedLetterScripts"], {"Arab": 5, "Latn": 5})

    def test_persian_arabic_form_letters_are_reviewed_without_echoing_text(self) -> None:
        private = "يك كلمه"
        audit = audit_text(private, "fa", "Arab")
        matching = [
            issue for issue in audit["issues"] if issue["code"] == "arabic-form-letter-in-persian-urdu"
        ]
        self.assertEqual(len(matching), 1)
        self.assertNotIn(private, str(audit))

    def test_french_spacing_before_punctuation_is_profile_aware(self) -> None:
        codes = self.issue_codes("Pourquoi ?", "fr", "Latn")
        self.assertNotIn("space-before-punctuation", codes)
        self.assertIn("space-before-punctuation", self.issue_codes("Bonjour ,", "fr", "Latn"))

    def test_mojibake_and_probable_credit_header_are_flagged(self) -> None:
        codes = self.issue_codes("HASAN DURSUN HAYRANI\nGÃ¶nlÃ¼m", "tr", "Latn")
        self.assertIn("probable-source-credit-line", codes)
        self.assertIn("probable-mojibake", codes)
        self.assertNotIn("probable-mojibake", self.issue_codes("Canı dilden âşık oldum", "mul", "Zyyy"))

    def test_known_language_script_profile_is_checked(self) -> None:
        codes = self.issue_codes("hello", "en", "Cyrl")
        self.assertIn("language-script-profile-mismatch", codes)

    def test_armenian_profile_preserves_script_validation(self) -> None:
        text = "Բարև աշխարհ։\n"
        audit = audit_text(text, "hy", "Armn")
        self.assertEqual(audit["issues"], [])
        self.assertEqual(audit["detectedLetterScripts"], {"Armn": 10})
        self.assertEqual(audit_text("\ufb13\ufb17", "hy", "Armn")["detectedLetterScripts"], {"Armn": 2})
        self.assertIn("language-script-profile-mismatch", self.issue_codes(text, "hy", "Latn"))
        self.assertIn("additional-script-present", self.issue_codes(text, "en", "Latn"))
        self.assertIn("additional-script-present", self.issue_codes(text + "hello", "hy", "Armn"))
        self.assertIn("missing-language-profile", self.issue_codes(text, "zz", "Armn"))

    def test_mixed_script_non_multilingual_declaration_requires_review(self) -> None:
        codes = self.issue_codes("Hello\nمرحبا", "en", "Zyyy")
        self.assertIn("mixed-script-language-review", codes)


if __name__ == "__main__":
    unittest.main()
