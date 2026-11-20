from __future__ import annotations

import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.lyrics_intake import (  # noqa: E402
    LyricsInputError,
    build,
    compile_draft,
    parse_lrc,
    parser,
    scaffold,
)
from stemwise_engine.contract import MAX_JSON_BYTES  # noqa: E402


VIDEO_ID = "a1B2c3D4e5F"


class LyricsIntakeTests(unittest.TestCase):
    def test_withheld_ipa_preserves_candidate_without_publishing_it(self):
        draft = self._draft('tr', 'Latn')
        (draft / 'original.txt').write_text('Merhaba', encoding='utf-8')
        (draft / 'translation-en.txt').write_text('Hello', encoding='utf-8')
        candidate = draft / 'transliteration-ipa.unreviewed.txt'
        raw = b'Unconfirmed pronunciation candidate\n'
        candidate.write_bytes(raw)
        lyrics = compile_draft(draft, self.config, {VIDEO_ID})
        self.assertEqual([track['kind'] for track in lyrics['tracks']], ['original', 'translation'])
        self.assertIn('IPA transliteration is withheld pending review', lyrics['reason'])
        self.assertEqual(candidate.read_bytes(), raw)

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / "Test suite" / "work")
        self.root = Path(self.temporary.name)
        self.project = self.root / "project"
        self.output = self.root / "packages"
        (self.project / "owner-input" / "drafts").mkdir(parents=True)
        (self.output / "work").mkdir(parents=True)
        (self.output / "work" / "inventory.json").write_text(
            json.dumps({"items": [{"videoId": VIDEO_ID}]}), encoding="utf-8"
        )
        self.config = {"projectDir": str(self.project), "outputDir": str(self.output)}

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_legacy_normalize_command_is_not_available(self) -> None:
        with self.assertRaises(SystemExit):
            parser().parse_args(["--config", "unused.json", "normalize"])
        wrapper = (PROJECT_ROOT / 'lyrics.ps1').read_text(encoding='utf-8')
        advertised = set(re.findall(r"'([^']+)'", re.search(r'\[ValidateSet\(([^)]+)\)\]', wrapper).group(1)))
        subcommands = next(action for action in parser()._actions if hasattr(action, 'choices') and isinstance(action.choices, dict))
        self.assertEqual(advertised, set(subcommands.choices))

    def _draft(
        self, language: str, script: str, component_languages: list[str] | None = None
    ) -> Path:
        draft = self.project / "owner-input" / "drafts" / VIDEO_ID
        draft.mkdir()
        sources = {"original": {"name": "Owner transcription"}}
        primary = language.split("-", 1)[0]
        if primary != "en":
            sources["translationEn"] = {"name": "Owner-reviewed English translation"}
        eligible_components = [
            value for value in (component_languages or []) if value not in {"ar", "en"}
        ]
        if primary not in {"ar", "en", "mul"} or eligible_components:
            sources["ipa"] = {"name": "Owner-reviewed IPA"}
        manifest = {
            "videoId": VIDEO_ID,
            "originalLanguage": language,
            "originalScript": script,
            "sources": sources,
        }
        if component_languages is not None:
            manifest["componentLanguages"] = component_languages
        (draft / "lyrics.meta.json").write_text(
            json.dumps(manifest),
            encoding="utf-8",
        )
        return draft

    def test_content_conflict_blocks_both_intakes_and_readonly_comparison_preserves_bytes(self):
        from stemwise_engine.lyrics_intake import compare_drafts
        from stemwise_engine.pipeline import StemwisePipeline
        draft = self._draft('en', 'Latn')
        (draft/'original.txt').write_text('Different draft words', encoding='utf-8')
        sidecar = self.project/'owner-input'/f'{VIDEO_ID}.lyrics.json'
        sidecar.write_text(json.dumps({'status': 'complete', 'tracks': [{'id': 'original',
            'kind': 'original', 'language': 'en', 'script': 'Latn', 'format': 'plain',
            'source': {'name': 'Reviewed source'}, 'text': 'Retained reviewed words'}]}), encoding='utf-8')
        path = draft/'lyrics.meta.json'
        manifest = json.loads(path.read_bytes())
        manifest['contentReviewRequired'] = True
        path.write_text(json.dumps(manifest), encoding='utf-8')
        before = {file: file.read_bytes() for file in (path, draft/'original.txt', sidecar)}
        with self.assertRaisesRegex(LyricsInputError, 'content conflict'):
            compile_draft(draft, self.config, {VIDEO_ID})
        with self.assertRaisesRegex(LyricsInputError, 'content conflict'):
            scaffold(self.config, VIDEO_ID, 'en', 'Latn', True)
        result = build(self.config)
        self.assertEqual(result['failedCount'], 1)
        self.assertEqual(result['builtCount'], 0)
        consumer = StemwisePipeline.__new__(StemwisePipeline)
        consumer.lyrics_input_dir = sidecar.parent
        with self.assertRaisesRegex(LyricsInputError, 'content conflict'):
            consumer.load_lyrics_snapshot(VIDEO_ID)
        from stemwise_engine.lyrics_alignment import load_sidecar
        with self.assertRaisesRegex(LyricsInputError, 'content conflict'):
            load_sidecar(sidecar, root=sidecar.parent)
        compared = compare_drafts(self.config)
        self.assertEqual(compared['blocked'], [VIDEO_ID])
        self.assertEqual(compared['differences'], [])
        self.assertEqual({file: file.read_bytes() for file in before}, before)

    def test_draft_comparison_uses_isolated_matching_missing_and_outside_inventory_inputs(self):
        from stemwise_engine.lyrics_intake import compare_drafts
        draft = self._draft('en', 'Latn')
        (draft/'original.txt').write_text('Fixture lyric', encoding='utf-8')
        self.assertEqual(build(self.config)['builtCount'], 1)
        outside = 'z9Y8x7W6v5U'
        (draft.parent/outside).mkdir()
        missing = 'Q1w2E3r4T5y'
        (self.output/'work/inventory.json').write_text(json.dumps({'items': [
            {'videoId': VIDEO_ID}, {'videoId': missing}]}), encoding='utf-8')
        result = compare_drafts(self.config)
        self.assertEqual(result['blocked'], [])
        self.assertEqual(result['differences'], [])
        self.assertEqual(result['matching'], [VIDEO_ID])
        self.assertEqual(result['matchingCount'], 1)
        self.assertEqual(result['outsideInventory'], [outside])
        self.assertEqual(result['missingDrafts'], [missing])
        self.assertEqual(result['missingSidecars'], [outside])

    def test_acquire_cli_counts_rejection_and_compiles_successful_drafts(self):
        import io
        from contextlib import redirect_stdout
        from stemwise_engine.lyrics_intake import main
        draft = self._draft('en', 'Latn')
        (draft/'original.txt').write_text('Fixture lyric', encoding='utf-8')
        for acquired in ([], [VIDEO_ID]):
            with self.subTest(acquired=acquired):
                output = io.StringIO()
                with patch('stemwise_engine.lyrics_intake.load_config', return_value=self.config), \
                        patch('stemwise_engine.lyrics_acquire.acquire', return_value={'acquired': acquired,
                            'counts': {'api-error': 0, 'rejected': 1}}), redirect_stdout(output):
                    code = main(['--config', str(self.root/'config.json'), 'acquire'])
                result = json.loads(output.getvalue())
                self.assertEqual(code, 1)
                self.assertEqual(result['failedCount'], 1)
                self.assertEqual(result['compiledCount'], len(acquired))

    def test_lrc_fraction_offset_multiple_timestamp_and_order(self) -> None:
        path = self.root / "sample.lrc"
        path.write_text(
            "\ufeff[ar:Artist]\r\n[offset:+5]\r\n[01:02.3]later\r\n"
            "[00:01.01][00:02.001]earlier\r\n",
            encoding="utf-8",
        )
        self.assertEqual(
            parse_lrc(path),
            [
                {"startMs": 1015, "text": "earlier"},
                {"startMs": 2006, "text": "earlier"},
                {"startMs": 62305, "text": "later"},
            ],
        )

    def test_lrc_rejects_duplicate_negative_blank_and_enhanced(self) -> None:
        cases = {
            "duplicate.lrc": "[00:01]a\n[00:01.000]b\n",
            "negative.lrc": "[offset:-1001]\n[00:01]a\n",
            "blank.lrc": "[00:01]   \n",
            "enhanced.lrc": "[00:01]<00:01.100>a\n",
        }
        for filename, content in cases.items():
            with self.subTest(filename=filename):
                path = self.root / filename
                path.write_text(content, encoding="utf-8")
                with self.assertRaises(LyricsInputError):
                    parse_lrc(path)

    def test_lrc_error_does_not_echo_lyric_text(self) -> None:
        path = self.root / "private.lrc"
        path.write_text("DISTINCTIVE_PRIVATE_LYRIC", encoding="utf-8")
        with self.assertRaises(LyricsInputError) as raised:
            parse_lrc(path)
        self.assertNotIn("DISTINCTIVE_PRIVATE_LYRIC", str(raised.exception))

    def test_lrc_preserves_bracketed_lyrics_but_rejects_extra_bad_timestamps(self):
        path = self.root / 'sample.lrc'
        for body in ('salaːm', '/salaːm/', '[salaːm]', '[chorus]'):
            path.write_text('[00:01.00]' + body, encoding='utf-8')
            with self.subTest(body=body):
                self.assertEqual(parse_lrc(path), [{'startMs': 1000, 'text': body}])
        for body in ('[00:99]salam', '[0:01.1234]salam', '[00:02salam', '[offset:3]salam'):
            path.write_text('[00:01.00]' + body, encoding='utf-8')
            with self.subTest(body=body), self.assertRaises(LyricsInputError):
                parse_lrc(path)

    def test_selected_roles_never_read_a_malformed_excluded_companion(self):
        draft = self._draft('tr', 'Latn')
        (draft / 'original.txt').write_text('Merhaba', encoding='utf-8')
        (draft / 'translation-en.txt').write_text('Hello', encoding='utf-8')
        (draft / 'translation-en.lrc').write_text('broken', encoding='utf-8')
        (draft / 'transliteration-ipa.txt').write_bytes(b'\xff')
        manifest = json.loads((draft / 'lyrics.meta.json').read_text(encoding='utf-8'))
        manifest['sources']['translationEn'] = None
        (draft / 'lyrics.meta.json').write_text(json.dumps(manifest), encoding='utf-8')
        with self.assertRaises(LyricsInputError):
            compile_draft(draft, self.config, {VIDEO_ID})
        result = compile_draft(draft, self.config, {VIDEO_ID}, roles=frozenset({'original'}))
        self.assertEqual([track['text'] for track in result['tracks']], ['Merhaba'])
        self.assertEqual(result['status'], 'partial')

    def test_recording_identity_excludes_only_companion_metadata(self):
        from stemwise_engine.lyrics_intake import read_recording_draft
        draft = self.root / 'job/drafts'
        draft.mkdir(parents=True)
        (draft / 'original.txt').write_text('Merhaba', encoding='utf-8')
        metadata = {'videoId': VIDEO_ID, 'originalLanguage': 'tr', 'originalScript': 'Latn',
            'sources': {'original': {'name': 'Recording'}, 'ipa': {'name': 'First review'}}}
        path = draft / 'lyrics.meta.json'
        path.write_text(json.dumps(metadata), encoding='utf-8')
        before = read_recording_draft(draft.parent)[1::2]
        metadata['sources']['ipa'] = {'name': 'Changed companion'}
        metadata['sources']['translationEn'] = None
        path.write_text(json.dumps(metadata, indent=2), encoding='utf-8')
        self.assertEqual(read_recording_draft(draft.parent)[1::2], before)
        metadata['sources']['original']['name'] = 'Changed original'
        path.write_text(json.dumps(metadata), encoding='utf-8')
        self.assertNotEqual(read_recording_draft(draft.parent)[3], before[1])

    def test_utf8_bom_and_newline_normalization(self) -> None:
        draft = self._draft("en", "Latn")
        (draft / "original.txt").write_text("\ufeff\r\nFirst\rSecond\r\n\r\n", encoding="utf-8")
        lyrics = compile_draft(draft, self.config, {VIDEO_ID})
        self.assertEqual(lyrics["tracks"][0]["text"], "First\nSecond")
        self.assertEqual(lyrics["status"], "partial")

    def test_english_lrc_awaits_endpoints_and_forbids_companions(self) -> None:
        draft = self._draft("en-US", "Latn")
        (draft / "original.lrc").write_text("[00:00.005]Hello\n", encoding="utf-8")
        lyrics = compile_draft(draft, self.config, {VIDEO_ID})
        self.assertEqual(lyrics["status"], "partial")
        self.assertEqual(lyrics["reason"], "line endpoints require automatic refinement.")
        (draft / "translation-en.txt").write_text("Hello", encoding="utf-8")
        with self.assertRaisesRegex(LyricsInputError, "must not include"):
            compile_draft(draft, self.config, {VIDEO_ID})

    def test_arabic_requires_english_and_forbids_ipa(self) -> None:
        draft = self._draft("ar", "Arab")
        (draft / "original.lrc").write_text("[00:00.005]مرحبا\n", encoding="utf-8")
        partial = compile_draft(draft, self.config, {VIDEO_ID})
        self.assertEqual(partial["status"], "partial")
        self.assertIn("English translation", partial["reason"])
        (draft / "translation-en.txt").write_text("Hello", encoding="utf-8")
        self.assertEqual(compile_draft(draft, self.config, {VIDEO_ID})["status"], "partial")
        (draft / "transliteration-ipa.txt").write_text("marħaba", encoding="utf-8")
        with self.assertRaisesRegex(LyricsInputError, "must not include IPA"):
            compile_draft(draft, self.config, {VIDEO_ID})

    def test_other_language_requires_english_and_ipa(self) -> None:
        draft = self._draft("tr", "Latn")
        (draft / "original.lrc").write_text("[00:00.005]Merhaba\n", encoding="utf-8")
        (draft / "translation-en.txt").write_text("Hello", encoding="utf-8")
        (draft / "transliteration-ipa.txt").write_text("meɾhaba", encoding="utf-8")
        lyrics = compile_draft(draft, self.config, {VIDEO_ID})
        self.assertEqual(lyrics["status"], "partial")
        self.assertEqual(
            [(track["id"], track["kind"], track["language"]) for track in lyrics["tracks"]],
            [
                ("original", "original", "tr"),
                ("translation-en", "translation", "en"),
                ("transliteration-ipa", "transliteration", "tr-fonipa"),
            ],
        )
        self.assertEqual(lyrics["tracks"][2]["basedOnTrackId"], "original")

    def test_multilingual_components_control_ipa_without_guessing(self) -> None:
        draft = self._draft("mul", "Zyyy")
        (draft / "original.lrc").write_text("[00:00.005]Hello\n", encoding="utf-8")
        (draft / "translation-en.txt").write_text("Hello", encoding="utf-8")
        pending = compile_draft(draft, self.config, {VIDEO_ID})
        self.assertEqual(pending["status"], "partial")
        self.assertIn("component languages", pending["reason"])

        (draft / "transliteration-ipa.txt").write_text("hello", encoding="utf-8")
        with self.assertRaisesRegex(LyricsInputError, "componentLanguages is required"):
            compile_draft(draft, self.config, {VIDEO_ID})
        (draft / "transliteration-ipa.txt").unlink()

        manifest_path = draft / "lyrics.meta.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["componentLanguages"] = ["ar", "en"]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        arabic_english = compile_draft(draft, self.config, {VIDEO_ID})
        self.assertEqual(arabic_english["status"], "partial")
        self.assertEqual(arabic_english["tracks"][0]["componentLanguages"], ["ar", "en"])

        manifest["componentLanguages"] = ["ar", "tr"]
        manifest["sources"]["ipa"] = {"name": "Owner-reviewed IPA"}
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        missing_ipa = compile_draft(draft, self.config, {VIDEO_ID})
        self.assertIn("IPA transliteration", missing_ipa["reason"])
        (draft / "transliteration-ipa.txt").write_text("merhaba", encoding="utf-8")
        with_ipa = compile_draft(draft, self.config, {VIDEO_ID})
        self.assertEqual(with_ipa["status"], "partial")
        self.assertEqual(with_ipa["tracks"][2]["language"], "mul-fonipa")

    def test_txt_lrc_role_conflict_is_rejected(self) -> None:
        draft = self._draft("en", "Latn")
        (draft / "original.txt").write_text("Hello", encoding="utf-8")
        (draft / "original.lrc").write_text("[00:00]Hello", encoding="utf-8")
        with self.assertRaisesRegex(LyricsInputError, "only one"):
            compile_draft(draft, self.config, {VIDEO_ID})

    def test_draft_role_symlink_cannot_read_outside_editorial_directory(self) -> None:
        draft = self._draft("en", "Latn")
        outside = self.root / "outside.txt"
        outside.write_text("private external text", encoding="utf-8")
        try:
            (draft / "original.txt").symlink_to(outside)
        except OSError as error:
            self.skipTest(f"symlinks are unavailable: {error}")

        with self.assertRaisesRegex(LyricsInputError, "must not be symlinks"):
            compile_draft(draft, self.config, {VIDEO_ID})

    def test_oversized_draft_is_rejected_before_text_decode(self) -> None:
        draft = self._draft("en", "Latn")
        original = draft / "original.txt"
        with original.open("wb") as handle:
            handle.truncate(MAX_JSON_BYTES + 1)

        with self.assertRaisesRegex(LyricsInputError, "cannot read UTF-8 text"):
            compile_draft(draft, self.config, {VIDEO_ID})

    def test_existing_child_enforces_exact_duration(self) -> None:
        draft = self._draft("en", "Latn")
        (draft / "original.lrc").write_text("[00:01.000]Too late", encoding="utf-8")
        child = self.output / "children" / f"{VIDEO_ID}.stemwise.zip"
        child.parent.mkdir(parents=True)
        child.write_bytes(b"placeholder")
        with patch(
            'stemwise_engine.lyrics_intake.read_child',
            return_value=({"analysis": {"durationMs": 1000}}, b""),
        ):
            with self.assertRaisesRegex(LyricsInputError, "durationMs"):
                compile_draft(draft, self.config, {VIDEO_ID})

    def test_manifest_identity_inventory_and_source_validation(self) -> None:
        draft = self._draft("en", "Latn")
        (draft / "original.txt").write_text("Hello", encoding="utf-8")
        with self.assertRaisesRegex(LyricsInputError, "frozen playlist"):
            compile_draft(draft, self.config, set())
        manifest = json.loads((draft / "lyrics.meta.json").read_text(encoding="utf-8"))
        manifest["videoId"] = "z9Y8x7W6v5U"
        (draft / "lyrics.meta.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(LyricsInputError, "does not match"):
            compile_draft(draft, self.config, {VIDEO_ID})

    def test_invalid_draft_does_not_clobber_good_sidecar(self) -> None:
        draft = self._draft("en", "Latn")
        (draft / "original.txt").write_text("Hello", encoding="utf-8")
        output = self.project / "owner-input" / f"{VIDEO_ID}.lyrics.json"
        output.write_text('{"sentinel":true}\n', encoding="utf-8")
        (draft / "original.lrc").write_text("[00:00]Hello", encoding="utf-8")
        result = build(self.config)
        self.assertEqual(result["failedCount"], 1)
        self.assertEqual(output.read_text(encoding="utf-8"), '{"sentinel":true}\n')

    def test_build_is_atomic_and_scaffold_is_non_destructive(self) -> None:
        destination = scaffold(self.config, VIDEO_ID, "en", "Latn", False)
        self.assertTrue(destination.is_file())
        with self.assertRaisesRegex(LyricsInputError, "already exists"):
            scaffold(self.config, VIDEO_ID, "en", "Latn", False)
        (destination.parent / "original.txt").write_text("Hello", encoding="utf-8")
        result = build(self.config)
        self.assertEqual(result["built"], [VIDEO_ID])
        generated = json.loads(
            (self.project / "owner-input" / f"{VIDEO_ID}.lyrics.json").read_text(encoding="utf-8")
        )
        self.assertEqual(generated["tracks"][0]["text"], "Hello")

    def test_arabic_variety_tags_are_stored_as_ar(self) -> None:
        destination = scaffold(self.config, VIDEO_ID, "ary", "Arab", False)
        manifest = json.loads(destination.read_text(encoding="utf-8"))
        self.assertEqual(manifest["originalLanguage"], "ar")
        (destination.parent / "original.txt").write_text("مرحبا", encoding="utf-8")
        lyrics = compile_draft(destination.parent, self.config, {VIDEO_ID})
        self.assertEqual(lyrics["tracks"][0]["language"], "ar")

    def test_scaffold_does_not_attribute_unverified_input_to_owner(self) -> None:
        destination = scaffold(self.config, VIDEO_ID, "en", "Latn", False)
        manifest = json.loads(destination.read_text(encoding="utf-8"))
        self.assertEqual(
            manifest["sources"]["original"]["name"],
            "Unverified local transcription; source review required",
        )


if __name__ == "__main__":
    unittest.main()
