from __future__ import annotations

import copy
import json
import string
import sys
import tempfile
import unittest
import wave
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

import stemwise_engine.mms_forced_alignment as mms
import stemwise_engine.wav2vec2_forced_alignment as w2v
from stemwise_engine.acoustic_inputs import AcousticBudget, AcousticBudgetExceeded, CURRENT_BUDGET
from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, whisper_word_candidates
from stemwise_engine.alignment_endpoints import _build_bootstrap_segments, _validate_occurrence_identity
from stemwise_engine.lyrics_align_pc import (
    _build_initial_alignment_segments,
    _forced_alignment_request,
)
from stemwise_engine.lyrics_tokenization import display_words
from syncalong.transcribe import WordTimestamp


class DecodeBoundaryReached(Exception):
    pass


class InitialAlignmentPlanningTests(unittest.TestCase):
    def plan(self, duration_ms):
        text = "amber silver\nriver rising\namber silver\nriver rising\nquiet morning\namber silver\nriver rising"
        sources = list(range(7))
        original = {"id": "original", "kind": "original", "language": "en",
                    "script": "Latn", "format": "plain", "text": text}
        mapping = build_lexical_occurrence_mapping("KbKqkR3Q6SA", original, sources)
        words = [display_words(text.splitlines()[source]) for source in sources]
        transcript = []
        for index, line in enumerate(words):
            start = (index + .2) * duration_ms / len(sources) / 1000
            for offset, (_, _, word) in enumerate(line):
                transcript.append(WordTimestamp(word=word, raw=word, start=start + offset,
                                                end=start + offset + .5))
        return text, sources, mapping, words, transcript

    def prepare_until_decode(self, request, root, worker):
        audio = root / "song.wav"
        with wave.open(str(audio), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(16_000)
            output.writeframes(b"\xe8\x03\x18\xfc" * (request['durationMs'] * 8))
        dimensions = []
        require = AcousticBudget.require

        def checked(budget, **values):
            require(budget, **values)
            dimensions.append(values)

        load = Mock(side_effect=DecodeBoundaryReached)
        # Tokenizer/model libraries are external boundaries. The workers' real
        # request, target, duration, allocation and DP checks remain in use.
        bundle = SimpleNamespace(sample_rate=16_000,
            get_tokenizer=lambda: lambda words: [[1] * len(word) for word in words],
            get_labels=lambda **_: tuple(range(32)))
        torch = SimpleNamespace(device=lambda name: SimpleNamespace(type=name),
                                cuda=SimpleNamespace(is_available=lambda: False), float32="float32")
        torchaudio = SimpleNamespace(pipelines=SimpleNamespace(MMS_FA=bundle), load=load)
        config = SimpleNamespace(pad_token_id=0, vocab_size=32)
        transformers = SimpleNamespace(AutoConfig=SimpleNamespace(from_pretrained=lambda *_a, **_kw: config),
            AutoModelForCTC=object,
            Wav2Vec2CTCTokenizer=object, Wav2Vec2FeatureExtractor=object,
            Wav2Vec2PhonemeCTCTokenizer=object)
        tokenizer = SimpleNamespace(get_vocab=lambda: {**{c: i + 3 for i, c in enumerate(string.ascii_lowercase)},
            "<pad>": 0, "|": 1, "<unk>": 2}, word_delimiter_token_id=1,
            pad_token_id=0, unk_token_id=2, all_special_ids=[0, 2])
        with patch.dict(sys.modules, {"torch": torch, "torchaudio": torchaudio,
                                      "transformers": transformers}), \
                patch.object(AcousticBudget, "require", checked):
            with self.assertRaises(DecodeBoundaryReached):
                if worker is mms:
                    worker._prepare(audio, root / "request.json", "cpu", {'acquire_stage': load}, request_value=request)
                else:
                    worker._prepare(audio, root / "request.json", root, "en", "cpu",
                        {"target_runtime": (tokenizer, False, SimpleNamespace(sampling_rate=16_000)),
                         'acquire_stage': load},
                        request_value=request)
        load.assert_called_once()
        return [value for value in dimensions if "frames" in value and "states" in value]

    def test_three_and_five_minute_repeated_occurrences_reach_both_workers_with_bounded_contexts(self):
        work = PROJECT_ROOT.parents[1] / "Test suite" / "work"
        with tempfile.TemporaryDirectory(dir=work) as temporary:
            root = Path(temporary)
            for duration in (180_000, 300_000):
                text, sources, mapping, words, transcript = self.plan(duration)
                free = {}
                before = json.dumps((mapping, free), sort_keys=True)
                segments = _build_initial_alignment_segments(text, sources, mapping, duration,
                    free_family_candidates=free, transcripts=[transcript],
                    transcript_labels=["faster-whisper:en"])
                request, line_ids, word_ids, _ = _forced_alignment_request(
                    "KbKqkR3Q6SA", sources, mapping, words, duration_ms=duration, segments=segments)
                records, _, actual_duration, worker_segments = mms._request_plan(request)
                self.assertEqual(actual_duration, duration)
                self.assertEqual(worker_segments, segments)
                self.assertEqual(len(segments), len(sources))
                self.assertEqual([row["lineId"] for row in records], line_ids)
                self.assertEqual([word["wordId"] for row in records for word in row["words"]],
                                 sum(word_ids, []))
                self.assertEqual(len(set(line_ids)), len(sources))
                self.assertEqual([row["sourcePhysicalIndex"] for row in request["lines"]], sources)
                self.assertEqual([(s["startLineIndex"], s["endLineIndex"]) for s in segments],
                                 [(i, i + 1) for i in range(len(sources))])
                self.assertEqual(segments[0]["windowStartMs"], 0)
                self.assertEqual(segments[-1]["windowEndMs"], duration)
                for index, (left, right) in enumerate(zip(segments, segments[1:])):
                    self.assertEqual(left["windowEndMs"], right["windowStartMs"])
                    cut = left["windowEndMs"]
                    self.assertGreaterEqual(cut - round(transcript[index * 2 + 1].end * 1000), 250)
                    self.assertGreaterEqual(round(transcript[index * 2 + 2].start * 1000) - cut, 250)
                for worker in (mms, w2v):
                    with self.subTest(duration=duration, worker=worker.__name__):
                        dimensions = self.prepare_until_decode(request, root, worker)
                        self.assertEqual(len(dimensions), len(sources))
                        for index, value in enumerate(dimensions):
                            window = segments[index]["windowEndMs"] - segments[index]["windowStartMs"]
                            characters = sum(len(word[2]) for word in words[index])
                            # W2V includes the inter-word delimiter and the leading
                            # delimiter on every context after the first.
                            targets = characters + (1 + (index > 0) if worker is w2v else 0)
                            self.assertEqual(value["frames"], (window + 19) // 20 + 2)
                            self.assertEqual(value["states"], 2 * targets + 1)
                            self.assertEqual(value["arrays"], 3)
                            retained = 30 * 16000 * 4 * 2 + ((window + 19) // 20 + 2) * 32 * 4
                            self.assertEqual(value["retained_bytes"], retained)
                            CURRENT_BUDGET.require(**value)
                self.assertEqual(json.dumps((mapping, free), sort_keys=True), before)
                # Repeated text retains seven distinct physical identities.
                _validate_occurrence_identity(tuple(sources))
                hints = whisper_word_candidates(text, sources, [transcript], duration,
                    occurrence_mapping=mapping, transcript_labels=["faster-whisper:en"])
                repair_segments = _build_bootstrap_segments(len(sources), duration, hints)
                repaired, repair_ids, repair_words, _ = _forced_alignment_request(
                    "KbKqkR3Q6SA", sources, mapping, words, duration_ms=duration, segments=repair_segments)
                self.assertEqual((repair_ids, repair_words), (line_ids, word_ids))
                self.assertEqual(mms._request_plan(repaired)[3], repair_segments)

    def test_missing_guarded_partition_keeps_complete_target_for_worker_capacity_admission(self):
        text, sources, mapping, _, transcript = self.plan(120_000)
        self.assertEqual(_build_initial_alignment_segments(text, sources, mapping, 120_000,
            free_family_candidates={}, transcripts=[], transcript_labels=[]),
            [{"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": len(sources),
              "windowStartMs": 0, "windowEndMs": 120_000}])
        for missing in ([], [transcript[:2]]):
            self.assertEqual(_build_initial_alignment_segments(text, sources, mapping, 300_000,
                free_family_candidates={}, transcripts=missing,
                transcript_labels=["faster-whisper:en"] * len(missing)),
                [{"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": len(sources),
                  "windowStartMs": 0, "windowEndMs": 300_000}])

    def test_workers_reject_insufficient_capacity_before_decode(self):
        work = PROJECT_ROOT.parents[1] / "Test suite" / "work"
        text, sources, mapping, words, _ = self.plan(180_000)
        request, _, _, _ = _forced_alignment_request("KbKqkR3Q6SA", sources, mapping, words,
                                                    duration_ms=180_000)
        with tempfile.TemporaryDirectory(dir=work) as temporary:
            for worker in (mms, w2v):
                for fault in ("duration", "targets", "dp"):
                    value = copy.deepcopy(request)
                    if fault != "duration":
                        value["durationMs"] = 120_000
                        value["segments"][0]["windowEndMs"] = 120_000
                        value["lines"][0]["words"][0]["acousticText"] = "a" * (2001 if fault == "targets" else 1500)
                    with self.subTest(worker=worker.__name__, fault=fault):
                        with patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=1024), self.assertRaises(AcousticBudgetExceeded):
                            self.prepare_until_decode(value, Path(temporary), worker)


if __name__ == "__main__":
    unittest.main()
