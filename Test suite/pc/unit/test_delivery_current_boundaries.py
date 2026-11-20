"""Actual media boundaries for the current public delivery contract."""
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[3]
from stemwise_engine.common import run_process_tree, sha256_file, atomic_write_json
from stemwise_engine.original_delivery import _decoded_duration, _probe
from stemwise_engine.recording_source import retained_input


class DeliveryMediaBoundaries(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_native_duration_counts_actual_frames_between_packet_boundaries(self):
        for rate, frames in ((44100, 44101), (48000, 48017), (96000, 98431), (48000, 1)):
            with self.subTest(rate=rate, frames=frames):
                source = self.root / f'{rate}-{frames}.flac'
                sf.write(source, np.zeros((frames, 2), dtype=np.float32), rate, subtype='PCM_24')
                self.assertEqual(sf.info(source).frames, frames)
                self.assertEqual(_decoded_duration(source), round(frames * 1000 / rate))

    def test_native_32_bit_flac_is_rejected_before_recording_analysis(self):
        library = self.root / 'PC side/qobuz-comparison'
        source = library / 'audio/qobuz/11/source.flac'
        source.parent.mkdir(parents=True)
        run_process_tree(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=duration=1',
            '-c:a', 'flac', '-sample_fmt', 's32', '-bits_per_raw_sample', '32', '-strict', '-2', str(source)],
            capture_output=True, check=True, timeout=15)
        from stemwise_media.compare import probe
        self.assertEqual(int(probe(source)['bits_per_raw_sample']), 32)
        atomic_write_json(source.parent / 'download.json', {'qobuzTrackId': 11,
            'file': source.name, 'bytes': source.stat().st_size, 'sha256': sha256_file(source)})
        before = source.read_bytes(), (source.parent / 'download.json').read_bytes()
        with self.assertRaisesRegex(ValueError,
                r'^(Original FLAC precision must be 16 or 24 bits|Retained FLAC cannot be decoded; preserve its bytes for repair)$'):
            retained_input(library, 'qobuz:11')
        with self.assertRaisesRegex(ValueError, 'precision'):
            _probe(source)
        self.assertEqual((source.read_bytes(), (source.parent / 'download.json').read_bytes()), before)


if __name__ == '__main__':
    unittest.main()
