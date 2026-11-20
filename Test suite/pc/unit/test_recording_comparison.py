"""Recording identity must depend on aligned audio, not names or duration."""
from __future__ import annotations

import sys
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path

import numpy as np

MODULE_PATH = Path(__file__).resolve().parents[3] / "PC side/qobuz-comparison/compare.py"
from stemwise_media import compare as comparison


class RecordingComparisonTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rate = 1000
        cls.audio = np.random.default_rng(4103).normal(0, .12, (45_000, 2))

    def test_same_audio_survives_gain_noise_and_silent_video_intro(self):
        candidate = self.audio * .6 + np.random.default_rng(22).normal(0, .001, self.audio.shape)
        reference = np.vstack([np.zeros((1700, 2)), self.audio])
        result = comparison.analyze_waveforms(reference, candidate, self.rate)
        self.assertEqual(result["classification"], "strong_recording_match")
        self.assertAlmostEqual(result["referenceOffsetSeconds"], 1.7, places=3)
        self.assertTrue(result["fullContentSupported"])

    def test_equal_duration_and_energy_do_not_identify_a_recording(self):
        other = np.random.default_rng(83).normal(0, .12, self.audio.shape)
        result = comparison.analyze_waveforms(self.audio, other, self.rate)
        self.assertEqual(result["classification"], "not_matched")

    def test_one_matching_middle_section_cannot_certify_the_whole_song(self):
        candidate = np.random.default_rng(83).normal(0, .12, self.audio.shape)
        candidate[15_000:30_000] = self.audio[15_000:30_000]
        result = comparison.analyze_waveforms(self.audio, candidate, self.rate)
        self.assertNotEqual(result["classification"], "strong_recording_match")

    def test_local_audio_edit_is_rejected_despite_matching_anchors(self):
        candidate = self.audio.copy()
        candidate[10_000:12_000] = 0
        result = comparison.analyze_waveforms(self.audio, candidate, self.rate)
        self.assertNotEqual(result["classification"], "strong_recording_match")

    def test_repeated_phrase_does_not_force_the_wrong_occurrence_alignment(self):
        # An exact repeat in the intro beats the slightly encoded occurrence in the song.
        body = self.audio + np.random.default_rng(22).normal(0, .0001, self.audio.shape)
        reference = np.vstack([self.audio[5550:13550], np.zeros((1700, 2)), body])
        result = comparison.analyze_waveforms(reference, self.audio, self.rate)
        self.assertEqual(result["classification"], "matching_body_with_extra_audio")
        self.assertAlmostEqual(result["referenceOffsetSeconds"], 9.7, places=3)
        self.assertTrue(all(c["minimumCorrelation"] >= .97 for c in result["channels"]))

    def test_consistent_repeat_anchors_cannot_hide_an_internal_edit(self):
        body = self.audio.copy()
        body[15000:16000] = np.random.default_rng(17).normal(0, .12, (1000, 2))
        body += np.random.default_rng(22).normal(0, .0001, self.audio.shape)
        reference = np.vstack([self.audio[5550:13550], np.zeros((1700, 2)), body])
        result = comparison.analyze_waveforms(reference, self.audio, self.rate, allow_edge_edits=True)
        self.assertEqual(result["classification"], "needs_review")
        self.assertEqual(result["reason"], "Dense waveform comparison differs")

    def test_unchecked_right_channel_cannot_hide_another_mix(self):
        candidate = self.audio.copy()
        candidate[:, 1] += np.random.default_rng(73).normal(0, .08, len(candidate))
        result = comparison.analyze_waveforms(self.audio, candidate, self.rate)
        self.assertNotEqual(result["classification"], "strong_recording_match")

    def test_phase_reversal_is_not_treated_as_the_same_waveform(self):
        self.assertLess(comparison._correlation(self.audio[:, 0], -self.audio[:, 0]), -.99)
        result = comparison.analyze_waveforms(self.audio, -self.audio, self.rate)
        self.assertNotEqual(result["classification"], "strong_recording_match")

    def test_fractional_sample_alignment_preserves_the_strict_stereo_requirement(self):
        from scipy import signal
        audio = signal.convolve(self.audio, signal.firwin(33, .55)[:, None], mode='same')
        kernel = np.sinc(np.arange(-32, 33) - .4) * np.blackman(65)
        kernel /= kernel.sum()
        delayed = signal.convolve(audio, kernel[:, None], mode='same')
        strict = comparison.analyze_waveforms(delayed, audio, self.rate)
        self.assertNotEqual(strict['classification'], 'strong_recording_match')
        result = comparison.analyze_waveforms(delayed, audio, self.rate, allow_fractional_delay=True)
        self.assertEqual(result['classification'], 'strong_recording_match')
        self.assertAlmostEqual(result['fractionalReferenceShiftSamples'], .4, delta=.03)
        self.assertTrue(all(c['minimumCorrelation'] >= .97 for c in result['channels']))
        changed = audio.copy()
        changed[15000:16000, 1] += np.random.default_rng(73).normal(0, .12, 1000)
        self.assertNotEqual(comparison.analyze_waveforms(delayed, changed, self.rate,
                            allow_fractional_delay=True)['classification'], 'strong_recording_match')

    def test_uniform_clock_drift_is_distinct_from_an_unmodified_recording(self):
        from scipy import signal
        for up, down in ((1001, 1000), (1000, 1001), (100073, 100000), (99939, 100000)):
            with self.subTest(up=up):
                reference = signal.resample_poly(self.audio, up, down, axis=0)
                result = comparison.analyze_clock_drift(reference, self.audio, self.rate)
                self.assertEqual(result['classification'], 'matching_body_with_speed_change', result)
                self.assertAlmostEqual(result['candidateTimeScale'], up / down, places=6)
                self.assertGreaterEqual(len(result['clockDriftEvidence']['anchors']), 5)
                self.assertFalse(result['fullContentSupported'])
                self.assertEqual(result['alignedWaveform']['classification'], 'strong_recording_match')
                self.assertTrue(all(c['minimumCorrelation'] >= .97 for c in result['alignedWaveform']['channels']))

    def test_clock_drift_search_cannot_hide_local_edits_or_a_changed_channel(self):
        from scipy import signal
        reference = signal.resample_poly(self.audio, 1001, 1000, axis=0)
        changed = self.audio.copy()
        changed[15000:16000, 1] += np.random.default_rng(73).normal(0, .12, 1000)
        self.assertNotEqual(comparison.analyze_clock_drift(reference, changed, self.rate)['classification'],
                            'matching_body_with_speed_change')

    def test_clock_estimation_rejects_repeated_nonlinear_and_unrelated_recordings(self):
        from scipy import signal
        repeated = np.tile(self.audio[:5000], (9, 1))
        nonlinear = np.arange(len(self.audio), dtype=np.float64)
        coordinates = nonlinear/(1.0005+nonlinear/len(nonlinear)*.001)
        warped = np.column_stack([np.interp(coordinates, nonlinear, self.audio[:, channel]) for channel in (0, 1)])
        unrelated = np.random.default_rng(91).normal(0, .12, self.audio.shape)
        for reference, candidate in ((signal.resample_poly(repeated, 100073, 100000, axis=0), repeated),
                (warped, self.audio), (unrelated, self.audio), (self.audio, self.audio)):
            with self.subTest(kind=reference.shape):
                self.assertNotEqual(comparison.analyze_clock_drift(reference, candidate, self.rate)['classification'],
                                    'matching_body_with_speed_change')

    def test_audible_unmatched_intro_is_reported_as_extra_material(self):
        intro = np.random.default_rng(22).normal(0, .12, (1700, 2))
        result = comparison.analyze_waveforms(np.vstack([intro, self.audio]), self.audio, self.rate)
        self.assertEqual(result["classification"], "matching_body_with_extra_audio")
        self.assertFalse(result["fullContentSupported"])

    def test_edge_review_identifies_the_body_without_certifying_changed_edges(self):
        for edge in ('beginning', 'ending'):
            with self.subTest(edge=edge):
                candidate = self.audio.copy()
                changed = slice(0, 1000) if edge == 'beginning' else slice(-1000, None)
                candidate[changed] = np.random.default_rng(17).normal(0, .12, (1000, 2))
                strict = comparison.analyze_waveforms(self.audio, candidate, self.rate)
                self.assertNotEqual(strict["classification"], "strong_recording_match")
                result = comparison.analyze_waveforms(self.audio, candidate, self.rate, allow_edge_edits=True)
                self.assertEqual(result["classification"], "matching_body_with_edge_edits")
                self.assertFalse(result["fullContentSupported"])
                self.assertGreaterEqual(result["body"]["durationSeconds"], 42.75)
                self.assertLessEqual(result["body"]["durationSeconds"], 44)
                self.assertTrue(all(c['minimumCorrelation'] >= .97 for c in result['body']['channels']))
                if edge == 'beginning':
                    self.assertGreaterEqual(result['body']['candidateStartSeconds'], 1)
                else:
                    self.assertLessEqual(result['body']['candidateStartSeconds'] + result['body']['durationSeconds'], 44)

    def test_edge_review_still_rejects_an_edit_inside_the_song(self):
        candidate = self.audio.copy()
        candidate[15_000:16_000] = np.random.default_rng(17).normal(0, .12, (1000, 2))
        result = comparison.analyze_waveforms(self.audio, candidate, self.rate, allow_edge_edits=True)
        self.assertEqual(result["classification"], "needs_review")

    def test_edge_review_cannot_discard_a_large_different_ending(self):
        candidate = self.audio.copy()
        candidate[-3_000:] = np.random.default_rng(17).normal(0, .12, (3_000, 2))
        result = comparison.analyze_waveforms(self.audio, candidate, self.rate, allow_edge_edits=True)
        self.assertNotEqual(result["classification"], "matching_body_with_edge_edits")

    def test_edge_review_does_not_hide_a_different_right_channel(self):
        candidate = self.audio.copy()
        candidate[15_000:16_000, 1] = np.random.default_rng(73).normal(0, .12, 1000)
        result = comparison.analyze_waveforms(self.audio, candidate, self.rate, allow_edge_edits=True)
        self.assertEqual(result["classification"], "needs_review")

    def test_silence_cannot_pass_as_matching_audio(self):
        result = comparison.analyze_waveforms(np.zeros_like(self.audio), np.zeros_like(self.audio), self.rate)
        self.assertEqual(result["classification"], "insufficient_audio")

    def test_high_frequency_mix_difference_is_not_hidden_by_coarse_alignment(self):
        from scipy.signal import resample_poly
        full = resample_poly(self.audio, 24, 1)
        candidate = full.copy()
        t = np.arange(len(full)) / 24000
        candidate[:, 1] += .12 * np.sin(2 * np.pi * 9000 * t)
        result = comparison.analyze_waveforms(full, candidate, 24000)
        self.assertNotEqual(result["classification"], "strong_recording_match")


class FlacIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=MODULE_PATH.parents[2] / "Test suite/work")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.wav = self.root / "source.wav"
        with wave.open(str(self.wav), "wb") as stream:
            stream.setparams((2, 2, 44100, 0, "NONE", "not compressed"))
            stream.writeframes(np.random.default_rng(124).integers(-8000, 8000, (4410, 2), dtype=np.int16).astype("<i2").tobytes())
        self.flac = self.root / "source.flac"
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(self.wav), str(self.flac)], check=True)

    def test_valid_flac_does_not_prove_its_upstream_history_or_provider(self):
        result = comparison.inspect_flac(self.flac)
        self.assertEqual(result["sampleFrames"], 4410)
        self.assertTrue(result["fullDecodeAndCrcPassed"])
        self.assertTrue(result["pcmChecksumMatches"])
        self.assertEqual(result["losslessHistory"], "not_proven_by_file_format")
        self.assertEqual(result["qobuzOrigin"], "not_proven_by_audio_analysis")

    def test_renaming_wav_to_flac_does_not_pass(self):
        fake = self.root / "fake.flac"
        fake.write_bytes(self.wav.read_bytes())
        with self.assertRaisesRegex(ValueError, "not a native FLAC"):
            comparison.inspect_flac(fake)

    def test_missing_samples_are_rejected_even_with_no_stored_checksum(self):
        data = bytearray(self.flac.read_bytes())
        info = int.from_bytes(data[18:26], "big")
        data[18:26] = ((info & ~((1 << 36) - 1)) | 5434).to_bytes(8, "big")
        data[26:42] = b"\0" * 16
        self.flac.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "sample count"):
            comparison.inspect_flac(self.flac)


if __name__ == "__main__":
    unittest.main()
