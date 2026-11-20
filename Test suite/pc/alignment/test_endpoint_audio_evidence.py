from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import soundfile as sf

from stemwise_engine.common import sha256_file
from stemwise_engine.endpoint_audio_evidence import METHOD, _pages, _render, evidence_directory, verify_packet, verify_stored_packet, write_packet


class EndpointAudioEvidenceTests(unittest.TestCase):
    def test_bound_packet_is_repeatable_private_and_rejects_tampering(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            attempt = root / "endpoint-review" / ("a" * 32)
            attempt.mkdir(parents=True)
            sentinel = attempt / "review.json"
            sentinel.write_bytes(b"untouched pending review")
            audio = root / "source.wav"
            rate = 16000
            tone = .5 * np.sin(2 * np.pi * 440 * np.arange(rate * 2) / rate)
            # Opposite-phase channels must remain visible, not cancel to silence.
            sf.write(audio, np.column_stack((tone, -tone)), rate, subtype="FLOAT")
            declaration = {"analysisAudio": {"sha256": sha256_file(audio), "byteCount": audio.stat().st_size, "durationMs": 2000},
                           "occurrences": [{"occurrenceId": "occurrence-1", "performedIndex": 1,
                                            "candidateFamilies": {"mms-fa": {"startMs": 100, "endMs": 1000},
                                                                  "language-wav2vec2": {"startMs": 200, "endMs": 1200}}}]}
            result = write_packet(attempt, declaration, "b" * 64, audio)
            binding = {key: result[key] for key in ("method", "manifestSha256")}
            self.assertEqual(binding["method"], METHOD)
            directory = evidence_directory(attempt)
            before = {p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in directory.iterdir()}
            with patch('stemwise_engine.endpoint_audio_evidence._render', side_effect=AssertionError('unchanged evidence rendered again')):
                self.assertEqual(write_packet(attempt, declaration, "b" * 64, audio), result)
                verify_packet(attempt, declaration, "b" * 64, audio, binding)
            self.assertEqual(before, {p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in directory.iterdir()})
            verify_packet(attempt, declaration, "b" * 64, audio, binding)
            self.assertEqual(sentinel.read_bytes(), b"untouched pending review")
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_bytes())
            self.assertNotIn(str(root), manifest_path.read_text())
            self.assertEqual(len(manifest["pages"]), 2)
            from PIL import Image
            pixels = np.asarray(Image.open(directory / manifest["pages"][0]["file"]))
            import io
            silence = np.asarray(Image.open(io.BytesIO(_render(np.zeros((rate * 2, 2)), rate, declaration["occurrences"][0], "startMs", 0, 2000))))
            self.assertGreater(float(pixels[285:605, 100:1760].mean()), float(silence[285:605, 100:1760].mean()) + 10)
            verify_stored_packet(attempt, binding)
            with self.assertRaises(ValueError):
                verify_packet(attempt, declaration, "c" * 64, audio, binding)
            changed = copy.deepcopy(declaration)
            changed["occurrences"][0]["candidateFamilies"]["mms-fa"]["startMs"] += 1
            with self.assertRaises(ValueError):
                verify_packet(attempt, changed, "b" * 64, audio, binding)
            page = directory / manifest["pages"][0]["file"]
            page.write_bytes(b"fabricated image")
            manifest["pages"][0]["sha256"] = hashlib.sha256(page.read_bytes()).hexdigest()
            manifest_path.write_text(json.dumps(manifest))
            forged = {"method": METHOD, "manifestSha256": sha256_file(manifest_path)}
            with self.assertRaisesRegex(ValueError, "image is invalid"):
                verify_packet(attempt, declaration, "b" * 64, audio, forged)
            with audio.open("ab") as stream:
                stream.write(b"changed")
            with self.assertRaisesRegex(ValueError, "source is stale"):
                write_packet(attempt, declaration, "b" * 64, audio)

    def test_short_pcm_and_linked_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            attempt = root / "endpoint-review" / ("a" * 32)
            attempt.mkdir(parents=True)
            audio = root / "short.wav"
            sf.write(audio, np.zeros((16, 1)), 16000)
            declaration = {"analysisAudio": {"sha256": sha256_file(audio), "byteCount": audio.stat().st_size, "durationMs": 1},
                           "occurrences": [{"occurrenceId": "occurrence-1", "performedIndex": 0,
                                            "candidateFamilies": {"mms-fa": {"startMs": 0, "endMs": 1}}}]}
            write_packet(attempt, declaration, "b" * 64, audio)
            manifest = evidence_directory(attempt) / "manifest.json"
            manifest.unlink()
            try:
                manifest.symlink_to(audio)
            except OSError:
                self.skipTest("symlinks unavailable")
            with self.assertRaises((OSError, ValueError)):
                write_packet(attempt, declaration, "b" * 64, audio)

    def test_windows_cover_widely_separated_candidates_without_unbounded_pages(self):
        declaration = {"analysisAudio": {"durationMs": 90000}, "occurrences": [
            {"candidateFamilies": {"a": {"startMs": 100, "endMs": 1000}, "b": {"startMs": 60000, "endMs": 89999}}}]}
        pages = list(_pages(declaration))
        self.assertTrue(all(0 <= start < end <= 90000 and end - start <= 10000 for _, _, start, end in pages))
        self.assertEqual(pages[0][2], 0)
        self.assertEqual(pages[-1][3], 90000)


if __name__ == "__main__":
    unittest.main()
