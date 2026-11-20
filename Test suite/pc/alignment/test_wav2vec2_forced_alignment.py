from __future__ import annotations

import hashlib
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.ctc_worker import REQUEST_VERSION, chunk_time_map, emission_boundary_to_target_frame
from stemwise_engine.wav2vec2_forced_alignment import (
    CHUNK_SECONDS,
    FORMAT_VERSION,
    RETAINED_CORE_SECONDS,
    STRIDE_SECONDS,
    TargetVocabularyUnsupported,
    _normalized_word,
    _overlapped_emission_chunks,
    _result_records,
    _resolved_tokenizer_class,
    _segment_target_plan,
    _target_plan,
    main,
)


class FakeTokenizer:
    def __init__(self) -> None:
        characters = "abcdeghiklmnoprstuvyz\u00e7\u011f\u0131\u00f6\u015f\u00fc'"
        self.vocabulary = {"<pad>": 0, "<unk>": 1, "|": 2}
        self.vocabulary.update(
            {character: index + 3 for index, character in enumerate(characters)}
        )
        self.word_delimiter_token_id = 2
        self.unk_token_id = 1
        self.all_special_ids = [0, 1]

    def get_vocab(self) -> dict[str, int]:
        return self.vocabulary


class FakePhonemeTokenizer:
    def __init__(self, phonemes: dict[str, str]) -> None:
        self.vocabulary = {
            "<pad>": 0,
            "<s>": 1,
            "</s>": 2,
            "<unk>": 3,
            "n": 4,
            "\u0295": 5,
        }
        self.word_delimiter_token = "|"
        self.word_delimiter_token_id = 3
        self.phone_delimiter_token = " "
        self.unk_token_id = 3
        self.all_special_ids = [0, 1, 2, 3]
        self.phonemes = phonemes
        self.languages: list[str] = []

    def get_vocab(self) -> dict[str, int]:
        return self.vocabulary

    def phonemize(self, text: str, phonemizer_lang: str) -> str:
        self.languages.append(phonemizer_lang)
        return self.phonemes[text]


def span(token: int, start: int, end: int, probability: float = 0.9) -> SimpleNamespace:
    return SimpleNamespace(token=token, start=start, end=end, score=math.log(probability))


class FakeEmission:
    def __init__(self, length: int) -> None:
        self.shape = (length, 32)
        self.slices: list[tuple[int, int]] = []

    def __getitem__(self, item: slice) -> "FakeEmission":
        start, end, step = item.indices(self.shape[0])
        if step != 1:
            raise AssertionError("emission slices must be contiguous")
        self.slices.append((start, end))
        return FakeEmission(max(0, end - start))


class Wav2Vec2ForcedAlignmentTests(unittest.TestCase):
    def test_legacy_grapheme_snapshot_infers_missing_tokenizer_config(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory)
            (model / "config.json").write_text(
                json.dumps({"model_type": "wav2vec2"}), encoding="utf-8"
            )
            (model / "vocab.json").write_text("{}", encoding="utf-8")

            self.assertEqual(
                _resolved_tokenizer_class(model), "Wav2Vec2CTCTokenizer"
            )

    def test_result_v4_reports_request_v5(self) -> None:
        self.assertEqual((FORMAT_VERSION, REQUEST_VERSION), (4, 5))
        self.assertEqual(
            (CHUNK_SECONDS, STRIDE_SECONDS, RETAINED_CORE_SECONDS),
            (30, 5, 20),
        )

    def test_turkish_normalization_canonicalizes_case_and_punctuation(self) -> None:
        vocabulary = FakeTokenizer().get_vocab()

        self.assertEqual(
            _normalized_word("I\u011eDIR\u2019!", "tr", vocabulary),
            "\u0131\u011fd\u0131r'",
        )
        self.assertEqual(_normalized_word("HÂLÂ", "tr", vocabulary), "hala")
        self.assertEqual(_normalized_word("tempo4", "tr", vocabulary), "tempo4")
        with self.assertRaisesRegex(ValueError, "no alignable"):
            _normalized_word("?!", "tr", vocabulary)

    def test_target_plan_preserves_ids_and_keeps_delimiters_outside_words(self) -> None:
        tokenizer = FakeTokenizer()
        request = {
            "lines": [
                {
                    "lineId": "occurrence-1",
                    "sourcePhysicalIndex": 3,
                    "words": [
                        {"wordId": "word-1", "acousticText": "bir"},
                        {"wordId": "word-2", "acousticText": "iki"},
                    ],
                },
                {
                    "lineId": "occurrence-2",
                    "sourcePhysicalIndex": 8,
                    "words": [{"wordId": "word-3", "acousticText": "son"}],
                },
            ]
        }

        targets, lines, delimiter = _target_plan(request, tokenizer, "tr")

        self.assertEqual(delimiter, tokenizer.word_delimiter_token_id)
        self.assertEqual(
            [lines[0]["words"][0]["wordId"], lines[1]["words"][0]["wordId"]],
            ["word-1", "word-3"],
        )
        first = lines[0]["words"][0]
        second = lines[0]["words"][1]
        self.assertEqual(targets[first["targetEnd"]], delimiter)
        self.assertEqual(second["targetStart"], first["targetEnd"] + 1)
        self.assertNotIn(
            delimiter, targets[first["targetStart"] : first["targetEnd"]]
        )

    def test_phoneme_target_has_no_word_delimiter_and_preserves_word_spans(self) -> None:
        tokenizer = FakePhonemeTokenizer({"one": "n |", "two": "n |"})
        request = {
            "lines": [
                {
                    "lineId": "occurrence-1",
                    "sourcePhysicalIndex": 3,
                    "words": [{"wordId": "word-1", "acousticText": "one"}],
                },
                {
                    "lineId": "occurrence-2",
                    "sourcePhysicalIndex": 8,
                    "words": [{"wordId": "word-2", "acousticText": "two"}],
                },
            ]
        }

        targets, lines, delimiter = _target_plan(
            request, tokenizer, "en-us", phoneme=True
        )

        self.assertIsNone(delimiter)
        self.assertEqual(targets, [4, 4])
        self.assertEqual(tokenizer.languages, ["en-us", "en-us"])
        self.assertEqual(
            [
                (word["targetStart"], word["targetEnd"])
                for line in lines
                for word in line["words"]
            ],
            [(0, 1), (1, 2)],
        )
        segment_targets, segment_plan = _segment_target_plan(
            targets,
            lines,
            {"startLineIndex": 1, "endLineIndex": 2},
            has_word_delimiters=False,
        )
        self.assertEqual(segment_targets, [4])
        self.assertEqual(segment_plan[0]["words"][0]["targetStart"], 0)
        self.assertEqual(
            len(targets)
            + sum(left == right for left, right in zip(targets, targets[1:])),
            3,
        )

    def test_phoneme_target_uses_each_words_bound_component_language(self) -> None:
        tokenizer = FakePhonemeTokenizer({"سلام": "\u0295 |", "merhaba": "n |"})
        request = {
            "lines": [
                {
                    "lineId": "occurrence-1",
                    "sourcePhysicalIndex": 0,
                    "words": [
                        {
                            "wordId": "word-1",
                            "acousticText": "سلام",
                            "acousticLanguage": "ar",
                        },
                        {
                            "wordId": "word-2",
                            "acousticText": "merhaba",
                            "acousticLanguage": "tr",
                        },
                    ],
                }
            ]
        }

        targets, lines, delimiter = _target_plan(
            request, tokenizer, "en-us", phoneme=True
        )

        self.assertIsNone(delimiter)
        self.assertEqual(targets, [5, 4])
        self.assertEqual(tokenizer.languages, ["ar", "tr"])
        self.assertEqual(
            [
                (word["targetStart"], word["targetEnd"])
                for word in lines[0]["words"]
            ],
            [(0, 1), (1, 2)],
        )

    def test_phoneme_target_expands_a_geminated_supported_phone(self) -> None:
        tokenizer = FakePhonemeTokenizer({"word": "\u0295\u0295 |"})
        request = {
            "lines": [
                {
                    "lineId": "occurrence-1",
                    "sourcePhysicalIndex": 3,
                    "words": [{"wordId": "word-1", "acousticText": "word"}],
                }
            ]
        }

        targets, lines, delimiter = _target_plan(
            request, tokenizer, "ar", phoneme=True
        )

        self.assertIsNone(delimiter)
        self.assertEqual(targets, [5, 5])
        self.assertEqual(
            (lines[0]["words"][0]["targetStart"], lines[0]["words"][0]["targetEnd"]),
            (0, 2),
        )
        length_targets, _, _ = _target_plan(
            request,
            FakePhonemeTokenizer({"word": "n\u02d0 |"}),
            "ar",
            phoneme=True,
        )
        self.assertEqual(length_targets, [4, 4])

    def test_phoneme_target_preserves_supported_compound_sounds(self) -> None:
        request = {"lines": [{"lineId": "occurrence-1", "sourcePhysicalIndex": 0,
                              "words": [{"wordId": "word-1", "acousticText": "word"}]}]}
        for vocabulary, expected in (({"d": 6, "z": 7}, [6, 7]),
                                     ({"d": 6, "z": 7, "dz": 8}, [8])):
            with self.subTest(vocabulary=vocabulary):
                tokenizer = FakePhonemeTokenizer({"word": "dz |"})
                tokenizer.vocabulary.update(vocabulary)
                targets, lines, delimiter = _target_plan(request, tokenizer, "hy", phoneme=True)
                self.assertEqual(targets, expected)
                self.assertIsNone(delimiter)
                self.assertEqual(lines[0]["words"][0]["wordId"], "word-1")
                self.assertEqual((lines[0]["words"][0]["targetStart"],
                                  lines[0]["words"][0]["targetEnd"]), (0, len(expected)))

    def test_unencodable_compound_cannot_drop_or_substitute_a_sound(self) -> None:
        request = {"lines": [{"lineId": "occurrence-1", "sourcePhysicalIndex": 0,
                              "words": [{"wordId": "word-1", "acousticText": "word"}]}]}
        tokenizer = FakePhonemeTokenizer({"word": "dz\u0280 |"})
        tokenizer.vocabulary.update({"d": 6, "z": 7, "r": 8})
        with self.assertRaisesRegex(ValueError, "outside the vocabulary"):
            _target_plan(request, tokenizer, "hy", phoneme=True)

    def test_unsupported_target_reports_capability_before_audio_or_model_inference(self) -> None:
        self._check_unsupported_target(phoneme=True)

    def test_unsupported_grapheme_reports_capability_without_hiding_malformed_ids(self) -> None:
        self._check_unsupported_target(phoneme=False)

    def _check_unsupported_target(self, *, phoneme: bool) -> None:
        request = {"formatVersion": 5, "durationMs": 1000, "lines": [
            {"lineId": "line-1", "sourcePhysicalIndex": 0,
             "words": [{"wordId": "word-1", "acousticText": "word"}]}],
            "segments": [{"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": 1,
                          "windowStartMs": 0, "windowEndMs": 1000}]}
        tokenizer = FakePhonemeTokenizer({"word": "\u0280 |"}) if phoneme else FakeTokenizer()
        if not phoneme:
            request["lines"][0]["words"][0]["acousticText"] = "j"
        with self.assertRaises(TargetVocabularyUnsupported):
            _target_plan(request, tokenizer, "hy", phoneme=phoneme)
        tokenizer_loader = SimpleNamespace(from_pretrained=lambda *_a, **_k: tokenizer)
        features = SimpleNamespace(from_pretrained=lambda *_a, **_k: SimpleNamespace(sampling_rate=16000))
        def forbid_inference(*_args: object, **_kwargs: object) -> None:
            self.fail("unsupported targets must stop before audio or model inference")
        modules = {"torch": SimpleNamespace(),
                   "torchaudio": SimpleNamespace(load=forbid_inference),
                   "transformers": SimpleNamespace(
                       AutoConfig=SimpleNamespace(from_pretrained=forbid_inference),
                       Wav2Vec2CTCTokenizer=tokenizer_loader,
                       Wav2Vec2PhonemeCTCTokenizer=tokenizer_loader,
                       Wav2Vec2FeatureExtractor=features)}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "model"
            model.mkdir()
            (model / "tokenizer_config.json").write_text(json.dumps({
                "tokenizer_class": "Wav2Vec2PhonemeCTCTokenizer" if phoneme else "Wav2Vec2CTCTokenizer"}), encoding="utf-8")
            request_path, output = root / "request.json", root / "output.json"
            request_path.write_text(json.dumps(request), encoding="utf-8")
            arguments = ["worker", "--audio", str(root / "absent.wav"), "--request",
                         str(request_path), "--output", str(output), "--model", str(model),
                         "--language", "hy", "--device", "cpu"]
            with patch.dict(sys.modules, modules), patch.object(sys, "argv", arguments):
                self.assertEqual(main(), 3)
            self.assertEqual(json.loads(output.read_bytes()), {
                "formatVersion": 1, "engine": "wav2vec2-target-capability",
                "requestSha256": hashlib.sha256(json.dumps(request, ensure_ascii=False,
                    sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest(),
                "supported": False})
            output.unlink()
            with patch.dict(sys.modules, modules), patch.object(sys, "argv", [*arguments, '--check-targets']):
                self.assertEqual(main(), 3)
            self.assertFalse(json.loads(output.read_bytes())['supported'])
            output.unlink()
            request["lines"][0]["words"].append(
                {"wordId": "word-1", "acousticText": "word"})
            request_path.write_text(json.dumps(request), encoding="utf-8")
            with patch.dict(sys.modules, modules), patch.object(sys, "argv", arguments):
                with self.assertRaisesRegex(ValueError, "word ID"):
                    main()
            self.assertFalse(output.exists(), "malformed targets cannot authorize fallback")

    def test_phoneme_target_rejects_empty_reserved_and_out_of_vocab_tokens(self) -> None:
        request = {
            "lines": [
                {
                    "lineId": "occurrence-1",
                    "sourcePhysicalIndex": 3,
                    "words": [{"wordId": "word-1", "acousticText": "word"}],
                }
            ]
        }
        for phones, message in (
            ("|", "no alignable phonemes"),
            ("<unk> |", "reserved token"),
            ("q |", "outside the vocabulary"),
        ):
            with self.subTest(phones=phones):
                with self.assertRaisesRegex(ValueError, message):
                    _target_plan(
                        request,
                        FakePhonemeTokenizer({"word": phones}),
                        "en-us",
                        phoneme=True,
                    )

    def test_numeric_records_preserve_occurrence_ids_and_exclude_delimiter_spans(self) -> None:
        tokenizer = FakeTokenizer()
        request = {
            "lines": [
                {
                    "lineId": "occurrence-1",
                    "sourcePhysicalIndex": 3,
                    "words": [
                        {"wordId": "word-1", "acousticText": "bir"},
                        {"wordId": "word-2", "acousticText": "iki"},
                    ],
                },
                {
                    "lineId": "occurrence-2",
                    "sourcePhysicalIndex": 8,
                    "words": [{"wordId": "word-3", "acousticText": "son"}],
                },
            ]
        }
        targets, plan, delimiter = _target_plan(request, tokenizer, "tr")
        spans = [
            span(token, index * 10, index * 10 + 8, 0.8 if token == delimiter else 0.9)
            for index, token in enumerate(targets)
        ]
        chunks = [chunk_time_map(0, 16_000, 0, len(targets) * 10)]

        lines, confidences = _result_records(plan, spans, chunks, 1_000)

        self.assertEqual([line["lineId"] for line in lines], ["occurrence-1", "occurrence-2"])
        self.assertEqual(
            [word["wordId"] for line in lines for word in line["words"]],
            ["word-1", "word-2", "word-3"],
        )
        self.assertTrue(all(abs(value - 0.9) < 1e-9 for value in confidences))
        self.assertNotIn("acousticText", json.dumps(lines))
        self.assertEqual(lines[0]["words"][0]["acousticTokenCount"], 3)
        self.assertEqual(lines[0]["acousticTokenCount"], 6)

    def test_global_overlap_stream_is_bounded_contiguous_and_truthful(self) -> None:
        sample_count = 240 * 16_000
        calls: list[tuple[int, int]] = []
        raw_emissions: list[FakeEmission] = []

        def infer_window(
            _waveform: object,
            start: int,
            end: int,
            *_args: object,
        ) -> FakeEmission:
            calls.append((start, end))
            emitted = FakeEmission(round((end - start) / 320))
            raw_emissions.append(emitted)
            return emitted

        with patch(
            'stemwise_engine.wav2vec2_forced_alignment._infer_window', side_effect=infer_window
        ):
            emissions, chunks, chunk_samples = _overlapped_emission_chunks(
                object(), sample_count, object(), object(), object(), object(), 320
            )

        expected_calls = [
            (start, min(start + 480_000, sample_count))
            for start in range(0, sample_count, 320_000)
            if start < sample_count
        ]
        self.assertEqual(calls, expected_calls)
        self.assertEqual(chunk_samples, 480_000)
        self.assertTrue(all(end - start <= chunk_samples for start, end in calls))
        self.assertEqual(
            [emitted.slices for emitted in raw_emissions],
            [[(0, 1_250)]] + [[(250, 1_250)]] * 10 + [[(250, 1_000)]],
        )
        self.assertEqual(
            [emitted.shape[0] for emitted in emissions],
            [1_250] + [1_000] * 10 + [750],
        )
        self.assertEqual(
            [
                (chunk["targetStartFrame"], chunk["targetEndFrame"])
                for chunk in chunks
            ],
            [(0, 400_000)]
            + [
                (400_000 + index * 320_000, 720_000 + index * 320_000)
                for index in range(10)
            ]
            + [(3_600_000, sample_count)],
        )
        self.assertEqual(
            [
                (chunk["emissionStartFrame"], chunk["emissionEndFrame"])
                for chunk in chunks
            ],
            [(0, 1_250)]
            + [
                (1_250 + index * 1_000, 2_250 + index * 1_000)
                for index in range(10)
            ]
            + [(11_250, 12_000)],
        )
        emission_boundaries = [0] + [
            chunk["emissionEndFrame"] for chunk in chunks
        ]
        target_boundaries = [0] + [chunk["targetEndFrame"] for chunk in chunks]
        self.assertEqual(
            [
                emission_boundary_to_target_frame(boundary, chunks)
                for boundary in emission_boundaries
            ],
            target_boundaries,
        )
        self.assertEqual(target_boundaries, sorted(set(target_boundaries)))
        self.assertEqual(
            chunks[-1]["emissionEndFrame"],
            sum(int(emitted.shape[0]) for emitted in emissions),
        )

    def test_overlap_stride_rounding_uses_projected_not_actual_emission_count(self) -> None:
        raw_emissions = [FakeEmission(1_499), FakeEmission(499)]

        with patch(
            'stemwise_engine.wav2vec2_forced_alignment._infer_window',
            side_effect=raw_emissions,
        ):
            emissions, chunks, _ = _overlapped_emission_chunks(
                object(), 480_001, object(), object(), object(), object(), 320
            )

        self.assertEqual(
            [emitted.slices for emitted in raw_emissions],
            [[(0, 1_250)], [(250, 499)]],
        )
        self.assertEqual([emitted.shape[0] for emitted in emissions], [1_250, 249])
        self.assertEqual(
            [
                (chunk["targetStartFrame"], chunk["targetEndFrame"])
                for chunk in chunks
            ],
            [(0, 400_000), (400_000, 480_001)],
        )

    def test_overlap_stream_rejects_a_material_model_ratio_mismatch(self) -> None:
        with patch(
            'stemwise_engine.wav2vec2_forced_alignment._infer_window',
            return_value=FakeEmission(1_400),
        ):
            with self.assertRaisesRegex(RuntimeError, "length is inconsistent"):
                _overlapped_emission_chunks(
                    object(), 480_000, object(), object(), object(), object(), 320
                )

    def test_segment_target_slice_rebases_tokens_and_offsets_emission_frames(self) -> None:
        tokenizer = FakeTokenizer()
        request = {
            "lines": [
                {
                    "lineId": "occurrence-1",
                    "sourcePhysicalIndex": 3,
                    "words": [{"wordId": "word-1", "acousticText": "bir"}],
                },
                {
                    "lineId": "occurrence-2",
                    "sourcePhysicalIndex": 8,
                    "words": [{"wordId": "word-2", "acousticText": "son"}],
                },
            ]
        }
        targets, plan, _ = _target_plan(request, tokenizer, "tr")
        segment_targets, segment_plan = _segment_target_plan(
            targets,
            plan,
            {
                "segmentIndex": 1,
                "startLineIndex": 1,
                "endLineIndex": 2,
                "windowStartMs": 500,
                "windowEndMs": 1_000,
            },
        )
        first_targets, _ = _segment_target_plan(
            targets,
            plan,
            {
                "segmentIndex": 0,
                "startLineIndex": 0,
                "endLineIndex": 1,
                "windowStartMs": 0,
                "windowEndMs": 500,
            },
        )
        spans = [span(token, index * 10, index * 10 + 8) for index, token in enumerate(segment_targets)]
        lines, _ = _result_records(
            segment_plan,
            spans,
            [
                chunk_time_map(0, 8_000, 0, 50),
                chunk_time_map(8_000, 16_000, 50, 100),
            ],
            1_000,
            frame_offset=50,
        )

        global_start = plan[1]["words"][0]["targetStart"]
        self.assertEqual(segment_plan[0]["words"][0]["targetStart"], 1)
        self.assertEqual(segment_targets, targets[global_start - 1 :])
        self.assertEqual(first_targets + segment_targets, targets)
        self.assertEqual(lines[0]["startMs"], 600)
        self.assertEqual(lines[0]["lineIndex"], 1)

    def test_one_global_segment_preserves_baseline_target_and_records(self) -> None:
        tokenizer = FakeTokenizer()
        request = {
            "lines": [
                {
                    "lineId": "occurrence-1",
                    "sourcePhysicalIndex": 3,
                    "words": [{"wordId": "word-1", "acousticText": "bir"}],
                },
                {
                    "lineId": "occurrence-2",
                    "sourcePhysicalIndex": 8,
                    "words": [{"wordId": "word-2", "acousticText": "son"}],
                },
            ]
        }
        targets, plan, _ = _target_plan(request, tokenizer, "tr")
        segment_targets, segment_plan = _segment_target_plan(
            targets,
            plan,
            {
                "segmentIndex": 0,
                "startLineIndex": 0,
                "endLineIndex": 2,
                "windowStartMs": 0,
                "windowEndMs": 1_000,
            },
        )
        spans = [span(token, index * 10, index * 10 + 8) for index, token in enumerate(targets)]
        chunks = [chunk_time_map(0, 16_000, 0, len(targets) * 10)]

        baseline = _result_records(plan, spans, chunks, 1_000)
        segmented = _result_records(segment_plan, spans, chunks, 1_000)

        self.assertEqual(segment_targets, targets)
        self.assertEqual(segment_plan, plan)
        self.assertEqual(segmented, baseline)


if __name__ == "__main__":
    unittest.main()
