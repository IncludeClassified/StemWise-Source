from __future__ import annotations

import base64
import copy
import io
import json
import struct
import sys
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

import stemwise_engine.contract as contract  # noqa: E402


TOOL_ORDER = (
    "beat-this-full",
    "stemwise-rhythm-normalizer",
    "s-key",
    "all-in-one",
    "essentia",
    "stemwise-waveform",
)


def unavailable(reason: str = "PC analysis audio was unavailable.") -> dict[str, str]:
    return {"status": "unavailable", "reason": reason}


def skipped_tools() -> list[dict[str, str]]:
    return [
        {"name": name, "status": "skipped", "reason": "Required analysis input was unavailable."}
        for name in TOOL_ORDER
    ]


def minimal_document() -> dict:
    return {
        "formatVersion": 2,
        "kind": "stemwise.song-enrichment",
        "source": {"provider": "youtube", "videoId": "a1B2c3D4e5F"},
        "generatedAt": "2026-07-26T12:00:00Z",
        "generator": {"name": "stemwise-pc", "version": "2.1.0"},
        "metadata": {
            "matchStatus": "youtube-fallback",
            "title": "Song title",
            "artists": ["Artist"],
            "sources": [
                {
                    "name": "YouTube Music",
                    "url": "https://music.youtube.com/watch?v=a1B2c3D4e5F",
                }
            ],
        },
        "artwork": {
            "file": "cover.png",
            "kind": "youtube-thumbnail",
            "source": {
                "name": "YouTube Music",
                "url": "https://music.youtube.com/watch?v=a1B2c3D4e5F",
            },
        },
        "lyrics": {
            "status": "unavailable",
            "reason": "No authorized lyrics source returned a reliable result.",
            "tracks": [],
        },
        "analysis": {
            "status": "unavailable",
            "rhythm": unavailable(),
            "key": unavailable(),
            "structure": unavailable(),
            "tuning": unavailable(),
            "loudness": unavailable(),
            "waveform": unavailable(),
            "toolRuns": skipped_tools(),
        },
    }


def waveform_frames(
    frame_count: int,
    minimum: int = -100,
    maximum: int = 100,
    rms: int = 50,
) -> str:
    frame = struct.pack("<hhH", minimum, maximum, rms)
    return base64.b64encode(frame * frame_count).decode("ascii")


def partial_waveform_document() -> dict:
    document = minimal_document()
    document["analysis"]["status"] = "partial"
    document["analysis"]["durationMs"] = 1_000
    document["analysis"]["waveform"] = {
        "status": "partial",
        "reason": "Only the 50 ms envelope completed.",
        "encoding": "s16le-min-max-rms-base64-v1",
        "levels": [
            {"frameDurationMs": 50, "frameCount": 20, "data": waveform_frames(20)}
        ],
    }
    document["analysis"]["toolRuns"][-1] = {
        "name": "stemwise-waveform",
        "status": "complete",
        "version": "1",
    }
    return document


class SongContractTests(unittest.TestCase):
    def test_completion_requires_nonempty_entire_word_timing_independent_of_companions(self):
        from stemwise_engine.editorial_admission import require_completed_lyrics
        from stemwise_engine.lyrics_tokenization import TOKENIZATION_VERSION
        from stemwise_engine.workflow_records import FutureSongError
        complete = {'status': 'partial', 'reason': 'Companion features are unavailable.',
            'tokenizationVersion': TOKENIZATION_VERSION, 'tracks': [{
            'id': 'original-fr', 'kind': 'original', 'language': 'fr', 'format': 'synced',
            'source': {'name': 'Known-answer source'}, 'lines': [{'text': 'bonjour monde',
                'startMs': 100, 'endMs': 900, 'words': [
                    {'startUtf16': 0, 'endUtf16': 7, 'startMs': 100, 'endMs': 400},
                    {'startUtf16': 8, 'endUtf16': 13, 'startMs': 500, 'endMs': 900}]}]}]}
        with self.assertRaisesRegex(FutureSongError, 'required companion'):
            require_completed_lyrics(complete, duration_ms=1000)
        complete['status'] = 'complete'
        complete.pop('reason')
        complete['tracks'][0].update(id='original-en', language='en')
        complete['tracks'][0]['lines'][0]['text'] = 'morning world'
        require_completed_lyrics(complete, duration_ms=1000)
        for fault in ('plain', 'line-only', 'missing-word', 'empty', 'unavailable', 'pending'):
            candidate = copy.deepcopy(complete)
            original = candidate['tracks'][0]
            endpoint = None
            if fault == 'plain':
                original.update(format='plain', text='bonjour monde')
                original.pop('lines')
            elif fault == 'line-only':
                original['lines'][0].pop('words')
            elif fault == 'missing-word':
                original['lines'][0]['words'].pop()
            elif fault == 'empty':
                original['lines'] = []
            elif fault == 'unavailable':
                candidate = {'status': 'unavailable', 'reason': 'Provider failed or no draft', 'tracks': []}
            else:
                endpoint = {'result': 'pending'}
            with self.subTest(fault=fault), self.assertRaises((FutureSongError, contract.ContractError)):
                require_completed_lyrics(candidate, duration_ms=1000, endpoint_review=endpoint)
        regional = json.loads((PROJECT_ROOT.parents[1]/'Test suite/android/unit/resources/fixtures/enrichment/regional-lyrics.json').read_text(encoding='utf-8'))
        with self.assertRaises(FutureSongError):
            require_completed_lyrics(regional['lyrics'], duration_ms=5000)

    def assert_invalid(self, document: dict, path: str | None = None) -> contract.ContractError:
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_song(document)
        if path is not None:
            self.assertEqual(caught.exception.path, path)
        return caught.exception

    def test_minimal_honest_unavailable_document_is_valid(self) -> None:
        document = minimal_document()
        self.assertIs(contract.validate_song(document), document)

    def test_root_accepts_one_generated_group_and_rejects_empty_update(self) -> None:
        metadata_only = minimal_document()
        for group in ("artwork", "lyrics", "analysis"):
            del metadata_only[group]
        contract.validate_song(metadata_only)

        wrong_generator = copy.deepcopy(metadata_only)
        wrong_generator["generator"]["name"] = "another-pc-manager"
        self.assert_invalid(wrong_generator, "$.generator.name")

        empty = {
            key: value
            for key, value in metadata_only.items()
            if key not in {"metadata", "artwork", "lyrics", "analysis"}
        }
        self.assert_invalid(empty, "$")

        raw = json.dumps(metadata_only, separators=(",", ":")).encode("utf-8")
        self.assertEqual(contract.load_and_validate_song_json(raw), metadata_only)
        package_document, artwork = contract.validate_package_files(raw)
        self.assertEqual(package_document, metadata_only)
        self.assertIsNone(artwork)

    def test_public_contract_is_current_v2_only(self) -> None:
        self.assertEqual(contract.FORMAT_VERSION, 2)
        legacy = minimal_document()
        legacy["formatVersion"] = 1
        self.assert_invalid(legacy, "$.formatVersion")
        raw = json.dumps(legacy, separators=(",", ":")).encode("utf-8")
        with self.assertRaises(contract.ContractError) as caught:
            contract.load_and_validate_song_json(raw)
        self.assertEqual(caught.exception.path, "$.formatVersion")

    def test_expected_video_id_is_case_sensitive(self) -> None:
        contract.validate_song(minimal_document(), expected_video_id="a1B2c3D4e5F")
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_song(minimal_document(), expected_video_id="A1B2c3D4e5F")
        self.assertEqual(caught.exception.path, "$.source.videoId")

    def test_unknown_property_is_rejected_at_every_shape(self) -> None:
        root = minimal_document()
        root["packageId"] = "not-allowed"
        self.assert_invalid(root, "$")

        nested = minimal_document()
        nested["metadata"]["mood"] = "quiet"
        self.assert_invalid(nested, "$.metadata")

    def test_boolean_is_not_accepted_as_integer_or_number(self) -> None:
        document = minimal_document()
        document["formatVersion"] = True
        self.assert_invalid(document, "$.formatVersion")

        document = partial_waveform_document()
        document["analysis"]["durationMs"] = True
        self.assert_invalid(document, "$.analysis.durationMs")

    def test_strict_loader_rejects_bom_duplicate_keys_and_nonfinite_numbers(self) -> None:
        valid = json.dumps(minimal_document(), separators=(",", ":")).encode("utf-8")
        with self.assertRaises(contract.ContractError):
            contract.load_and_validate_song_json(b"\xef\xbb\xbf" + valid)

        duplicate = valid.replace(b'{"formatVersion":2,', b'{"formatVersion":2,"formatVersion":2,', 1)
        with self.assertRaises(contract.ContractError) as caught:
            contract.load_and_validate_song_json(duplicate)
        self.assertIn("duplicate JSON property", caught.exception.message)

        document = minimal_document()
        document["metadata"]["description"] = float("nan")
        raw = json.dumps(document, separators=(",", ":")).encode("utf-8")
        with self.assertRaises(contract.ContractError) as caught:
            contract.load_and_validate_song_json(raw)
        self.assertIn("non-finite", caught.exception.message)

    def test_timestamp_dates_ids_and_https_urls_are_normalized(self) -> None:
        document = minimal_document()
        document["generatedAt"] = "2026-02-30T12:00:00Z"
        self.assert_invalid(document, "$.generatedAt")

        document = minimal_document()
        document["metadata"]["album"] = {"title": "Album", "releaseDate": "2026-02-30"}
        self.assert_invalid(document, "$.metadata.album.releaseDate")

        document = minimal_document()
        document["metadata"]["sources"][0]["url"] = "http://example.com/source"
        self.assert_invalid(document, "$.metadata.sources[0].url")

        document = minimal_document()
        document["metadata"]["identifiers"] = {"isrc": "US-RC1-76-07839"}
        self.assert_invalid(document, "$.metadata.identifiers.isrc")

    def test_metadata_rejects_empty_optional_collections_and_bad_totals(self) -> None:
        document = minimal_document()
        document["metadata"]["genres"] = []
        self.assert_invalid(document, "$.metadata.genres")

        document = minimal_document()
        document["metadata"]["album"] = {
            "title": "Album",
            "trackNumber": 11,
            "trackTotal": 10,
        }
        self.assert_invalid(document, "$.metadata.album.trackNumber")

    def test_partial_or_unavailable_lyrics_require_correct_track_shape(self) -> None:
        document = minimal_document()
        del document["lyrics"]["reason"]
        self.assert_invalid(document, "$.lyrics")

        document = minimal_document()
        document["lyrics"] = {"status": "partial", "reason": "Only some content.", "tracks": []}
        self.assert_invalid(document, "$.lyrics.tracks")

    def test_complete_lyrics_support_plain_original_and_require_valid_references(self) -> None:
        document = minimal_document()
        document["analysis"]["durationMs"] = 2_000
        document["lyrics"] = {
            "status": "complete",
            "tracks": [
                {
                    "id": "original-ar",
                    "kind": "original",
                    "language": "ar",
                    "script": "Arab",
                    "format": "synced",
                    "lines": [{"startMs": 100, "endMs": 500, "text": "سطر"}],
                    "source": {"name": "owner-supplied"},
                },
                {
                    "id": "translation-en",
                    "kind": "translation",
                    "basedOnTrackId": "original-ar",
                    "language": "en",
                    "script": "Latn",
                    "format": "plain",
                    "text": "Line",
                    "source": {"name": "owner-supplied"},
                },
            ],
        }
        contract.validate_song(document)

        broken = copy.deepcopy(document)
        broken["lyrics"]["tracks"][1]["basedOnTrackId"] = "missing-original"
        self.assert_invalid(broken, "$.lyrics.tracks[1].basedOnTrackId")

        plain = copy.deepcopy(document)
        plain["lyrics"]["tracks"] = [
            {
                "id": "original-ar",
                "kind": "original",
                "language": "ar",
                "format": "plain",
                "text": "سطر",
                "source": {"name": "owner-supplied"},
            }
        ]
        contract.validate_song(plain)

        with_reason = copy.deepcopy(plain)
        with_reason["lyrics"]["reason"] = "Optional producer note."
        contract.validate_song(with_reason)
        with self.assertRaises(contract.ContractError):
            contract.validate_lyrics(with_reason["lyrics"], duration_ms=2_000)

    def test_owner_lyrics_policy_requires_translation_and_ipa_for_complete_tracks(self) -> None:
        document = minimal_document()
        document["analysis"]["durationMs"] = 2_000
        original = {
            "id": "original-ja",
            "kind": "original",
            "language": "ja",
            "script": "Jpan",
            "format": "synced",
            "lines": [{"startMs": 100, "endMs": 500, "text": "歌"}],
            "source": {"name": "owner-supplied"},
        }
        document["lyrics"] = {"status": "complete", "tracks": [original]}
        contract.validate_song(document)
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_lyrics(document["lyrics"], duration_ms=2_000)
        self.assertEqual(caught.exception.path, "$.lyrics.tracks")

        document["lyrics"]["tracks"].append(
            {
                "id": "translation-en",
                "kind": "translation",
                "basedOnTrackId": "original-ja",
                "language": "en",
                "script": "Latn",
                "format": "plain",
                "text": "Song",
                "source": {"name": "machine-generated translation"},
            }
        )
        contract.validate_song(document)
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_lyrics(document["lyrics"], duration_ms=2_000)
        self.assertEqual(caught.exception.path, "$.lyrics.tracks")

        document["lyrics"]["tracks"].append(
            {
                "id": "transliteration-ipa",
                "kind": "transliteration",
                "basedOnTrackId": "original-ja",
                "language": "ja-fonipa",
                "script": "Latn",
                "format": "plain",
                "text": "kɐ",
                "source": {"name": "machine-generated IPA"},
            }
        )
        contract.validate_lyrics(document["lyrics"], duration_ms=2_000)

        partial = copy.deepcopy(document)
        partial["lyrics"] = {
            "status": "partial",
            "reason": "Requested translation and IPA are not available.",
            "tracks": [original],
        }
        contract.validate_lyrics(partial["lyrics"], duration_ms=2_000)

    def test_owner_lyrics_policy_rejects_redundant_or_malformed_generated_tracks(self) -> None:
        document = minimal_document()
        document["analysis"]["durationMs"] = 2_000
        document["lyrics"] = {
            "status": "partial",
            "reason": "Policy test.",
            "tracks": [
                {
                    "id": "original-en",
                    "kind": "original",
                    "language": "en",
                    "script": "Latn",
                    "format": "synced",
                    "lines": [{"startMs": 100, "endMs": 500, "text": "Line"}],
                    "source": {"name": "owner-supplied"},
                },
                {
                    "id": "translation-en",
                    "kind": "translation",
                    "basedOnTrackId": "original-en",
                    "language": "en",
                    "format": "plain",
                    "text": "Line",
                    "source": {"name": "owner-supplied"},
                },
            ],
        }
        contract.validate_song(document)
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_lyrics(document["lyrics"], duration_ms=2_000)
        self.assertEqual(caught.exception.path, "$.lyrics.tracks[1].language")

        malformed = copy.deepcopy(document)
        malformed["lyrics"]["tracks"] = [
            {
                "id": "original-ja",
                "kind": "original",
                "language": "ja",
                "format": "synced",
                "lines": [{"startMs": 100, "endMs": 500, "text": "歌"}],
                "source": {"name": "owner-supplied"},
            },
            {
                "id": "translation-phonetic",
                "kind": "translation",
                "basedOnTrackId": "original-ja",
                "language": "ja-fonipa",
                "script": "Latn",
                "format": "plain",
                "text": "kɐ",
                "source": {"name": "machine-generated IPA"},
            },
        ]
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_song(malformed)
        self.assertEqual(caught.exception.path, "$.lyrics.tracks[1].language")
        with self.assertRaises(contract.ContractError):
            contract.validate_lyrics(malformed['lyrics'], duration_ms=2_000)

    def test_multilingual_component_languages_determine_ipa_policy(self) -> None:
        document = minimal_document()
        document["analysis"]["durationMs"] = 2_000
        original = {
            "id": "original-mul",
            "kind": "original",
            "language": "mul",
            "componentLanguages": ["ar", "en"],
            "script": "Zyyy",
            "format": "synced",
            "lines": [{"startMs": 100, "endMs": 500, "text": "Line"}],
            "source": {"name": "owner-supplied"},
        }
        translation = {
            "id": "translation-en",
            "kind": "translation",
            "basedOnTrackId": "original-mul",
            "language": "en",
            "script": "Latn",
            "format": "plain",
            "text": "Line",
            "source": {"name": "Codex-reviewed translation"},
        }
        document["lyrics"] = {"status": "complete", "tracks": [original, translation]}
        contract.validate_lyrics(document["lyrics"], duration_ms=2_000)

        missing_components = copy.deepcopy(document["lyrics"])
        missing_components["tracks"][0].pop("componentLanguages")
        missing_components.update(
            status="partial",
            reason="Multilingual component languages are missing.",
        )
        contract.validate_lyrics(missing_components, duration_ms=2_000)
        missing_components.pop("reason")
        missing_components["status"] = "complete"
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_lyrics(missing_components, duration_ms=2_000)
        self.assertEqual(caught.exception.path, "$.lyrics.tracks[0].componentLanguages")

        ipa = {
            "id": "transliteration-ipa",
            "kind": "transliteration",
            "basedOnTrackId": "original-mul",
            "language": "mul-fonipa",
            "script": "Latn",
            "format": "plain",
            "text": "line",
            "source": {"name": "Codex-reviewed IPA"},
        }
        forbidden = copy.deepcopy(document)
        forbidden["lyrics"]["tracks"].append(ipa)
        contract.validate_song(forbidden)
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_lyrics(forbidden["lyrics"], duration_ms=2_000)
        self.assertEqual(caught.exception.path, "$.lyrics.tracks[2].language")

        eligible = copy.deepcopy(document)
        eligible["lyrics"]["tracks"][0]["componentLanguages"] = ["ar", "tr"]
        contract.validate_song(eligible)
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_lyrics(eligible["lyrics"], duration_ms=2_000)
        self.assertEqual(caught.exception.path, "$.lyrics.tracks")
        eligible["lyrics"]["tracks"].append(ipa)
        contract.validate_lyrics(eligible["lyrics"], duration_ms=2_000)

        multiple_eligible = copy.deepcopy(eligible)
        multiple_eligible["lyrics"]["tracks"][0]["componentLanguages"] = ["fr", "tr"]
        multiple_eligible["lyrics"]["tracks"][2]["language"] = "tr-fonipa"
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_lyrics(multiple_eligible["lyrics"], duration_ms=2_000)
        self.assertEqual(caught.exception.path, "$.lyrics.tracks[2].language")
        multiple_eligible["lyrics"]["tracks"][2]["language"] = "mul-fonipa"
        contract.validate_lyrics(multiple_eligible["lyrics"], duration_ms=2_000)

    def test_android_component_languages_are_not_owner_policy_constrained(self) -> None:
        document = minimal_document()
        document["lyrics"] = {
            "status": "complete",
            "tracks": [
                {
                    "id": "original-mixed",
                    "kind": "original",
                    "language": "mixed-language",
                    "componentLanguages": ["tr", "ar"],
                    "format": "plain",
                    "text": "Mixed text",
                    "source": {"name": "owner-supplied"},
                }
            ],
        }
        contract.validate_song(document)

    def test_v2_synced_lyrics_allow_ties_but_reject_descending_or_unbounded_lines(self) -> None:
        document = minimal_document()
        document["analysis"]["durationMs"] = 500
        document["lyrics"] = {
            "status": "partial",
            "reason": "Synchronization is incomplete.",
            "tracks": [
                {
                    "id": "original-en",
                    "kind": "original",
                    "language": "en",
                    "format": "synced",
                    "lines": [
                        {"startMs": 100, "endMs": 200, "text": "One"},
                        {"startMs": 100, "endMs": 300, "text": "Two"},
                    ],
                    "source": {"name": "owner-supplied"},
                }
            ],
        }
        contract.validate_song(document)

        document["lyrics"]["tracks"][0]["lines"][1]["startMs"] = 99
        self.assert_invalid(document, "$.lyrics.tracks[0].lines[1].startMs")

        document["lyrics"]["tracks"][0]["lines"][1].update(startMs=400, endMs=501)
        self.assert_invalid(document, "$.lyrics.tracks[0].lines[1].endMs")

    def test_lyrics_only_update_can_use_installed_duration(self) -> None:
        document = {
            key: value
            for key, value in minimal_document().items()
            if key not in {"metadata", "artwork", "analysis"}
        }
        document["lyrics"] = {
            "status": "partial",
            "reason": "Timing is still being reviewed.",
            "tracks": [
                {
                    "id": "original-en",
                    "kind": "original",
                    "language": "en",
                    "format": "synced",
                    "lines": [{"startMs": 500, "endMs": 501, "text": "Line"}],
                    "source": {"name": "owner-supplied"},
                }
            ],
        }
        contract.validate_song(document)
        contract.validate_song(document, installed_duration_ms=501)
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_song(document, installed_duration_ms=500)
        self.assertEqual(caught.exception.path, "$.lyrics.tracks[0].lines[0].endMs")

    def test_any_non_unavailable_analysis_requires_duration(self) -> None:
        document = minimal_document()
        document["analysis"]["status"] = "partial"
        document["analysis"]["key"] = {
            "status": "uncertain",
            "reason": "Close competing estimates.",
            "tonic": "A",
            "mode": "minor",
        }
        self.assert_invalid(document, "$.analysis.durationMs")

    def test_analysis_overall_status_must_match_feature_statuses(self) -> None:
        document = minimal_document()
        document["analysis"]["status"] = "partial"
        document["analysis"]["durationMs"] = 1_000
        self.assert_invalid(document, "$.analysis")

        document = partial_waveform_document()
        document["analysis"]["status"] = "unavailable"
        self.assert_invalid(document, "$.analysis")

    def test_complete_rhythm_requires_bpm_beats_and_strict_time_order(self) -> None:
        document = minimal_document()
        document["analysis"]["status"] = "partial"
        document["analysis"]["durationMs"] = 1_000
        document["analysis"]["rhythm"] = {
            "status": "complete",
            "bpm": 120,
            "beats": [
                {"timeMs": 100, "isDownbeat": True, "beatInBar": 1},
                {"timeMs": 100, "isDownbeat": False, "beatInBar": 2},
            ],
        }
        self.assert_invalid(document, "$.analysis.rhythm.beats[1].timeMs")

        document["analysis"]["rhythm"]["beats"][1]["timeMs"] = 600
        document["analysis"]["toolRuns"][0] = {
            "name": "beat-this-full",
            "status": "complete",
            "version": "1",
        }
        document["analysis"]["toolRuns"][1] = {
            "name": "stemwise-rhythm-normalizer",
            "status": "complete",
            "version": "1",
        }
        contract.validate_song(document)

        no_bpm = copy.deepcopy(document)
        del no_bpm["analysis"]["rhythm"]["bpm"]
        self.assert_invalid(no_bpm, "$.analysis.rhythm")

    def test_segments_and_sections_are_half_open_ordered_and_nonoverlapping(self) -> None:
        document = minimal_document()
        document["analysis"]["status"] = "partial"
        document["analysis"]["durationMs"] = 1_000
        document["analysis"]["structure"] = {
            "status": "partial",
            "reason": "Boundary estimate is incomplete.",
            "sections": [
                {"startMs": 0, "endMs": 600, "label": "intro"},
                {"startMs": 500, "endMs": 900, "label": "verse"},
            ],
        }
        self.assert_invalid(document, "$.analysis.structure.sections[1].startMs")

        document["analysis"]["structure"]["sections"][1]["startMs"] = 600
        document["analysis"]["toolRuns"][3] = {
            "name": "all-in-one",
            "status": "complete",
            "version": "1",
        }
        contract.validate_song(document)

    def test_key_tuning_and_confidence_status_rules(self) -> None:
        document = minimal_document()
        document["analysis"]["status"] = "partial"
        document["analysis"]["durationMs"] = 1_000
        document["analysis"]["key"] = {"status": "uncertain", "tonic": "H", "mode": "minor"}
        self.assert_invalid(document, "$.analysis.key.tonic")

        document["analysis"]["key"] = {
            "status": "uncertain",
            "reason": "Competing estimates.",
            "tonic": "C#",
            "mode": "minor",
            "confidence": 1.1,
        }
        self.assert_invalid(document, "$.analysis.key.confidence")

    def test_waveform_validates_levels_decoded_length_and_frame_values(self) -> None:
        contract.validate_song(partial_waveform_document())

        document = partial_waveform_document()
        document["analysis"]["waveform"]["levels"][0]["data"] = base64.b64encode(b"12345").decode("ascii")
        self.assert_invalid(document, "$.analysis.waveform.levels[0].data")

        document = partial_waveform_document()
        document["analysis"]["waveform"]["levels"][0]["data"] = waveform_frames(20, rms=32768)
        self.assert_invalid(document, "$.analysis.waveform.levels[0].data")

        document = partial_waveform_document()
        document["analysis"]["waveform"]["levels"][0]["data"] = waveform_frames(
            20, minimum=100, maximum=-100
        )
        self.assert_invalid(document, "$.analysis.waveform.levels[0].data")

        document = partial_waveform_document()
        level = document["analysis"]["waveform"]["levels"][0]
        level["frameCount"] = 19
        level["data"] = waveform_frames(19)
        self.assert_invalid(document, "$.analysis.waveform.levels[0].frameCount")

    def test_complete_waveform_requires_exact_three_level_set(self) -> None:
        document = partial_waveform_document()
        waveform = document["analysis"]["waveform"]
        waveform["status"] = "complete"
        del waveform["reason"]
        waveform["levels"] = [
            {
                "frameDurationMs": duration,
                "frameCount": (1_000 + duration - 1) // duration,
                "data": waveform_frames((1_000 + duration - 1) // duration),
            }
            for duration in (5, 50, 250)
        ]
        contract.validate_song(document)

        waveform["levels"][2]["frameDurationMs"] = 50
        self.assert_invalid(document, "$.analysis.waveform.levels[2].frameDurationMs")

    def test_fully_complete_analysis_and_authority_runs_are_valid(self) -> None:
        document = minimal_document()
        document["analysis"] = {
            "status": "complete",
            "durationMs": 1_000,
            "rhythm": {
                "status": "complete",
                "bpm": 120,
                "beats": [
                    {"timeMs": 0, "isDownbeat": True, "beatInBar": 1, "barIndex": 0},
                    {"timeMs": 500, "isDownbeat": False, "beatInBar": 2, "barIndex": 0},
                ],
            },
            "key": {"status": "complete", "tonic": "A", "mode": "minor", "confidence": 0.9},
            "structure": {
                "status": "complete",
                "sections": [{"startMs": 0, "endMs": 1_000, "label": "intro"}],
            },
            "tuning": {
                "status": "complete",
                "frequencyHz": 440.0,
                "centsFromA440": 0.0,
            },
            "loudness": {
                "status": "complete",
                "integratedLufs": -10.0,
                "loudnessRangeLu": 4.0,
            },
            "waveform": {
                "status": "complete",
                "encoding": "s16le-min-max-rms-base64-v1",
                "levels": [
                    {
                        "frameDurationMs": duration,
                        "frameCount": (1_000 + duration - 1) // duration,
                        "data": waveform_frames((1_000 + duration - 1) // duration),
                    }
                    for duration in (5, 50, 250)
                ],
            },
            "toolRuns": [
                {"name": name, "status": "complete", "version": "1"}
                for name in TOOL_ORDER
            ],
        }
        contract.validate_song(document)

        document["analysis"]["toolRuns"][0] = {
            "name": "beat-this-full",
            "status": "skipped",
            "reason": "No input.",
        }
        self.assert_invalid(document, "$.analysis.toolRuns")

    def test_tool_runs_require_exact_names_status_shape_and_versions(self) -> None:
        document = minimal_document()
        document["analysis"]["toolRuns"].pop()
        self.assert_invalid(document, "$.analysis.toolRuns")

        document = minimal_document()
        document["analysis"]["toolRuns"][0] = {
            "name": "beat-this-full",
            "status": "failed",
            "reason": "Process failed.",
        }
        self.assert_invalid(document, "$.analysis.toolRuns[0]")

    def test_usable_features_require_complete_authority_runs(self) -> None:
        document = minimal_document()
        document["analysis"]["status"] = "partial"
        document["analysis"]["durationMs"] = 1_000
        document["analysis"]["key"] = {
            "status": "complete",
            "tonic": "A",
            "mode": "minor",
        }
        self.assert_invalid(document, "$.analysis.toolRuns")

        document["analysis"]["toolRuns"][2] = {
            "name": "s-key",
            "status": "complete",
            "version": "1",
        }
        contract.validate_song(document)

        waveform = partial_waveform_document()
        waveform["analysis"]["toolRuns"][-1] = {
            "name": "stemwise-waveform",
            "status": "failed",
            "version": "1",
            "reason": "Encoding failed.",
        }
        self.assert_invalid(waveform, "$.analysis.toolRuns")

    def test_partial_beat_events_remain_valid_when_only_normalizer_failed(self) -> None:
        document = minimal_document()
        document["analysis"]["status"] = "partial"
        document["analysis"]["durationMs"] = 1_000
        document["analysis"]["rhythm"] = {
            "status": "partial",
            "reason": "Tempo normalization failed.",
            "beats": [{"timeMs": 100, "isDownbeat": True}],
        }
        document["analysis"]["toolRuns"][0] = {
            "name": "beat-this-full",
            "status": "complete",
            "version": "1",
        }
        document["analysis"]["toolRuns"][1] = {
            "name": "stemwise-rhythm-normalizer",
            "status": "failed",
            "version": "1",
            "reason": "Tempo normalization failed.",
        }
        contract.validate_song(document)

    def test_descriptors_require_the_pinned_essentia_profile(self) -> None:
        document = minimal_document()
        document["analysis"]["status"] = "partial"
        document["analysis"]["durationMs"] = 1_000
        document["analysis"]["loudness"] = {
            "status": "partial",
            "reason": "Integrated loudness was unavailable.",
            "descriptors": [{"id": "dynamic-complexity", "value": 4.1, "unit": "dB"}],
        }
        self.assert_invalid(document, "$.analysis.toolRuns")

        document["analysis"]["toolRuns"][4] = {
            "name": "essentia",
            "status": "complete",
            "version": "2.1",
            "profile": "stemwise-v1",
        }
        contract.validate_song(document)


class ArtworkContractTests(unittest.TestCase):
    # Valid 1 x 1 PNG. The recommendation for 1400+ square artwork is not a
    # hard validity rule, so a tiny image is appropriate for contract tests.
    PNG_1X1 = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC"
    )

    def test_non_png_and_trailing_payload_are_rejected_before_decode(self) -> None:
        with self.assertRaises(contract.ContractError):
            contract.validate_cover_png(b"not a png")
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_cover_png(self.PNG_1X1 + b"trailing")
        self.assertIn("after the IEND", caught.exception.message)

    def test_real_static_png_fully_decodes_when_pillow_is_available(self) -> None:
        try:
            import PIL  # noqa: F401
        except ImportError:
            self.skipTest("Pillow is installed by requirements-core.txt")
        info = contract.validate_cover_png(self.PNG_1X1)
        self.assertEqual((info.width, info.height), (1, 1))

    def test_apng_control_chunk_is_rejected(self) -> None:
        # Insert a syntactically placed acTL marker. The validator rejects the
        # animation declaration before Pillow/CRC validation.
        ihdr_end = 8 + 12 + 13
        actl = struct.pack(">I", 8) + b"acTL" + struct.pack(">II", 1, 0) + b"\0\0\0\0"
        animated = self.PNG_1X1[:ihdr_end] + actl + self.PNG_1X1[ihdr_end:]
        with self.assertRaises(contract.ContractError) as caught:
            contract.validate_cover_png(animated)
        self.assertIn("animated PNG", caught.exception.message)


class PublicApiTests(unittest.TestCase):
    def test_frozen_limits_match_android_3_6_2(self) -> None:
        self.assertEqual(contract.MAX_JSON_BYTES, 16 * 1024 * 1024)
        self.assertEqual(contract.MAX_COVER_BYTES, 16 * 1024 * 1024)
        self.assertEqual(contract.MAX_SINGLE_ZIP_BYTES, 32 * 1024 * 1024)
        self.assertEqual(contract.MAX_DURATION_MS, 7_200_000)


if __name__ == "__main__":
    unittest.main()
