"""Focused generation/rollback checks; all writes stay below Test suite/work."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

SPEC = importlib.util.spec_from_file_location("demo_sources", Path(__file__).with_name("generate_demo_sources.py"))
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


class DemoSourceGenerationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=generator.PROJECT / "Test suite/work", prefix="demo-generator-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.library = self.root / "StemWise/app/src/development/assets/demo_library"
        self.names = [f"part-{index}.flac" for index in range(1, 9)] + ["remaining.wv", "sources.json"]
        for song in ("DEMOUI00001", "DEMOUI00002", "DEMOUI00003"):
            folder = self.library / song
            folder.mkdir(parents=True)
            for name in self.names:
                (folder / name).write_bytes((song + name).encode())
        self.encoder = self.root / "Test suite/android/instrumentation/assets/encoder/remaining-float32.wv"
        self.encoder.parent.mkdir(parents=True)
        self.encoder.write_bytes(b"previous encoder")

    def snapshot(self):
        return {str(path.relative_to(self.root)): path.read_bytes() for path in self.root.rglob("*") if path.is_file()}

    def staged_demo(self, folder, native_original_root=None):
        for name in self.names:
            (folder / name).write_bytes(b"replacement " + name.encode())

    def real_parts(self, folder):
        (folder / "original.opus").write_bytes(b"source-identity-only")
        original = np.array([[.125, -.25], [.5, -.75], [0., 0.]] * 16, dtype="<f4")
        for index in range(1, 9):
            samples = np.array([[index * 17, -index * 23]] * len(original), dtype="<i2")
            self.write_flac(folder / f"part-{index}.flac", samples)
        return original

    def write_flac(self, path, samples, rate=48000, channels=2, raw_format="s16le", sample_format="s16"):
        generator.encode([generator.FFMPEG, "-v", "error", "-y", "-f", raw_format,
                          "-ar", str(rate), "-ac", str(channels), "-i", "-", "-c:a", "flac",
                          "-sample_fmt", sample_format, str(path)], samples.tobytes())

    def test_real_rebuild_preserves_every_part_byte_and_serial_remaining(self):
        originals = {folder.name: self.real_parts(folder) for folder in self.library.iterdir()}
        before = {path: path.read_bytes() for path in self.library.glob("*/part-*.flac")}
        expected = originals["DEMOUI00001"].astype(np.float64)
        for index in range(1, 9):
            expected -= np.array([[index * 17, -index * 23]] * len(expected), dtype=np.float64) / 32768
        with patch.object(generator, "native_original", side_effect=lambda folder, root: originals[folder.name]):
            generator.rebuild(self.root)
            generator.rebuild(self.root, check_only=True)
        self.assertEqual(before, {path: path.read_bytes() for path in before})
        actual = generator.decode(self.library / "DEMOUI00001/remaining.wv")
        self.assertEqual(expected.astype("<f4").tobytes(), actual.tobytes())

    def test_check_rejects_noncanonical_duplicate_and_changed_manifest_fields(self):
        folder = self.library / "DEMOUI00001"
        original = self.real_parts(folder)
        with patch.object(generator, "native_original", return_value=original):
            generator.generate(folder)
            path = folder / "sources.json"
            valid = path.read_bytes()
            wrong_label = json.loads(valid)
            wrong_label["lanes"][0]["label"] = "Wrong role"
            wrong_label.pop("integritySha256")
            wrong_label["integritySha256"] = generator.sha(generator.canonical(wrong_label))
            for invalid in (valid + b"\n", b'{"schemaVersion":3,' + valid[1:], generator.canonical(wrong_label)):
                with self.subTest(invalid=invalid[:30]):
                    path.write_bytes(invalid)
                    before = self.snapshot()
                    with self.assertRaises(ValueError):
                        generator.check_folder(folder)
                    self.assertEqual(before, self.snapshot())
                    path.write_bytes(valid)

    def test_generation_rejects_wrong_flac_format_or_frame_count_without_publication(self):
        folder = self.library / "DEMOUI00001"
        original = self.real_parts(folder)
        for rate, channels, sample_format, frames in ((44100, 2, "s16", 48), (48000, 1, "s16", 48),
                                                       (48000, 2, "s32", 48), (48000, 2, "s16", 47)):
            samples = np.zeros((frames, channels), dtype="<i4" if sample_format == "s32" else "<i2")
            self.write_flac(folder / "part-1.flac", samples, rate, channels,
                            "s32le" if sample_format == "s32" else "s16le", sample_format)
            before = self.snapshot()
            with patch.object(generator, "native_original", return_value=original):
                with self.assertRaises(ValueError):
                    generator.generate(folder)
            self.assertEqual(before, self.snapshot())

    def test_decoder_rejects_empty_partial_nonfinite_and_disguised_codec(self):
        stream = dict(codec_name="flac", sample_rate="48000", channels=2, bits_per_raw_sample="16")
        probe = json.dumps(dict(streams=[stream])).encode()
        for payload in (b"", bytes(7), np.array([[np.nan, 0.]], dtype="<f4").tobytes()):
            with patch.object(generator.subprocess, "check_output", side_effect=[probe, payload]):
                with self.assertRaises(ValueError):
                    generator.decode(self.library / "DEMOUI00001/part-1.flac")
        stream["codec_name"] = "pcm_s16le"
        with patch.object(generator.subprocess, "check_output", return_value=json.dumps(dict(streams=[stream])).encode()) as child:
            with self.assertRaises(ValueError):
                generator.decode(self.library / "DEMOUI00001/part-1.flac")
            self.assertEqual(1, child.call_count, "Wrong codec reached decoding")

    def test_missing_native_operand_rejects_without_publishing_or_decoding_original_with_ffmpeg(self):
        before = self.snapshot()
        with patch.object(generator, "decode", side_effect=AssertionError("Original decoded with FFmpeg")):
            with self.assertRaisesRegex(ValueError, "native-original-root"):
                generator.rebuild(self.root)
        self.assertEqual(before, self.snapshot())

    def test_encoder_failure_cannot_publish_any_demo_change(self):
        before = self.snapshot()
        with patch.object(generator, "generate", self.staged_demo), patch.object(generator, "check_folder"), \
                patch.object(generator.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "ffmpeg")):
            with self.assertRaises(subprocess.CalledProcessError):
                generator.rebuild(self.root)
        self.assertEqual(before, self.snapshot())

    def test_publication_failure_restores_every_previous_byte(self):
        before = self.snapshot()
        replace = generator.os.replace
        calls = 0

        def fail_once(source, destination):
            nonlocal calls
            self.assertFalse(destination.name.startswith("part-"), "A committed input entered publication/rollback")
            calls += 1
            if calls == 6:
                raise OSError("injected publication failure")
            return replace(source, destination)

        with patch.object(generator, "generate", self.staged_demo), patch.object(generator, "check_folder"), \
                patch.object(generator, "generate_encoder", lambda path: path.write_bytes(b"new encoder")), \
                patch.object(generator.os, "replace", fail_once):
            with self.assertRaisesRegex(OSError, "injected publication"):
                generator.rebuild(self.root)
        self.assertGreater(calls, 6, "Rollback did not execute")
        self.assertEqual(before, self.snapshot())

    def test_failed_rollback_keeps_unrestored_bytes_and_attempts_other_restores(self):
        before = self.snapshot()
        replace = generator.os.replace
        failed_destination = self.library / 'DEMOUI00001/remaining.wv'
        staged = []
        calls = 0

        def fail_publication_and_one_restore(source, destination):
            nonlocal calls
            calls += 1
            if calls == 3:
                raise OSError('publication failed')
            if source.parent.name == 'backups' and source.name == '0':
                staged.append(source.parent.parent)
                raise OSError('restore failed')
            return replace(source, destination)

        with patch.object(generator, 'generate', self.staged_demo), patch.object(generator, 'check_folder'), \
                patch.object(generator, 'generate_encoder', lambda path: path.write_bytes(b'new encoder')), \
                patch.object(generator.os, 'replace', fail_publication_and_one_restore):
            with self.assertRaisesRegex(RuntimeError, 'Fixture rollback incomplete') as caught:
                generator.rebuild(self.root)
        try:
            self.assertEqual('publication failed', str(caught.exception.__cause__))
            self.assertEqual(['restore failed'], [str(e) for e in caught.exception.rollback_errors])
            self.assertEqual(6, calls, 'Independent rollback attempts must continue')
            self.assertIn(str(staged[0]), str(caught.exception))
            self.assertEqual(before[str(failed_destination.relative_to(self.root))],
                             (staged[0] / 'backups/0').read_bytes())
            for relative, data in before.items():
                path = self.root / relative
                if path != failed_destination:
                    self.assertEqual(data, path.read_bytes(), relative)
        finally:
            for path in staged:
                import shutil
                shutil.rmtree(path)

    def test_check_only_rejects_corrupted_encoder_bits(self):
        bad = np.frombuffer(generator.encoder_payload(), dtype="<f4").copy().reshape(-1, 2)
        bad.view("<u4")[0, 0] = 1
        with patch.object(generator, "check_folder"), patch.object(generator, "decode", return_value=bad):
            with self.assertRaisesRegex(ValueError, "raw-bit oracle"):
                generator.rebuild(self.root, check_only=True)

    def test_manifest_producer_belongs_only_to_the_materialized_remaining_lane(self):
        folder = self.library / "DEMOUI00001"
        # The native decoder is the external boundary; encoding and manifest publication stay real.
        original = self.real_parts(folder)
        with patch.object(generator, "native_original", return_value=original):
            generator.generate(folder)
        manifest = json.loads((folder / "sources.json").read_bytes())
        self.assertEqual(3, manifest["schemaVersion"])
        self.assertNotIn("remainingProducer", manifest)
        self.assertEqual("remaining", manifest["lanes"][-1]["sourceId"])
        self.assertEqual("stemwise-native-original48-v1", manifest["lanes"][-1]["remainingProducer"])
        self.assertTrue(all("remainingProducer" not in lane for lane in manifest["lanes"][:-1]))

    def test_real_encoder_preserves_independent_signed_zero_and_subnormal_words(self):
        words = np.frombuffer(generator.encoder_payload(), dtype="<u4")
        self.assertEqual([0, 0x80000000, 0x3fc00000, 0xc0300000, 1, 0x80000001], words[:6].tolist())
        generator.generate_encoder(self.encoder)
        generator.check_encoder(self.encoder)


if __name__ == "__main__":
    unittest.main()
