"""Long recording comparison retains the two-hour range with bounded working RAM."""
import gc
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import numpy as np
from scipy import signal

ROOT = Path(__file__).resolve().parents[3]
import stemwise_media.bounded_pcm as pcm
import stemwise_media.compare as compare
from stemwise_engine.common import run_process_tree


class BoundedComparisonTests(unittest.TestCase):
    def test_unretired_decoder_keeps_scratch_until_its_exact_lease_closes(self):
        import os
        from stemwise_engine.common import WorkerRetirementError
        retained = []
        def unretired(command, **options):
            retained.append(os.dup(options['pass_fds'][0]))
            options['stdout'].write(b'pending PCM')
            raise WorkerRetirementError('decoder still owns its descriptors')
        try:
            with patch.object(compare, 'probe', return_value={'duration': 1}), \
                    patch.object(compare, 'run_process_tree', side_effect=unretired):
                with self.assertRaises(WorkerRetirementError):
                    compare.decode(Path('fixture.flac'))
            directories = list(pcm.SCRATCH_ROOT.glob('.comparison-pcm-*'))
            self.assertEqual(len(directories), 1)
            pending = directories[0]/'pcm.bin'
            self.assertEqual(pending.read_bytes(), b'pending PCM')
            next_owner = pcm.temporary_directory()
            next_owner.cleanup()
            self.assertTrue(pending.exists())
            os.close(retained.pop())
            next_owner = pcm.temporary_directory()
            next_owner.cleanup()
            self.assertFalse(directories[0].exists())
        finally:
            for descriptor in retained:
                os.close(descriptor)

    def setUp(self):
        scratch = tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work', prefix='bounded-comparison-')
        self.addCleanup(scratch.cleanup)
        patched = patch.object(pcm, 'SCRATCH_ROOT', Path(scratch.name)/'pcm')
        patched.start()
        self.addCleanup(patched.stop)

    def test_native_binding_import_does_not_require_scipy(self):
        code = "\nimport builtins, sys\nsys.path.insert(0, sys.argv[1])\noriginal = builtins.__import__\ndef without_scipy(name, *args, **kwargs):\n    if name == 'scipy' or name.startswith('scipy.'):\n        raise ModuleNotFoundError('metadata import requested scipy', name=name)\n    return original(name, *args, **kwargs)\nbuiltins.__import__ = without_scipy\nfrom stemwise_engine.recording_source import alignment_config\nassert callable(alignment_config)\nassert not any(name == 'scipy' or name.startswith('scipy.') for name in sys.modules)\n"
        result = subprocess.run([sys.executable, '-B', '-c', code,
            str(ROOT/'PC side/stemwise-playlist-builder/src')], capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_streaming_resampling_and_convolution_match_the_reference_operations(self):
        audio = np.random.default_rng(31).normal(0, .1, (30000, 2))
        kernel = np.sinc(np.arange(-32, 33) + .25) * np.blackman(65)
        kernel /= kernel.sum()
        for up, down in ((1, 6), (1001, 1000), (1000, 1001)):
            expected = signal.resample_poly(audio, up, down, axis=0)
            with patch.object(pcm, 'RAM_BYTES', 1024), patch.object(pcm, 'CHUNK_FRAMES', 4096):
                actual = pcm.resample(audio, up, down)
            np.testing.assert_array_equal(actual, expected)
            directory = Path(actual.filename).parent
            del actual
            gc.collect()
            self.assertFalse(directory.exists())
        expected = signal.oaconvolve(audio, kernel[:, None], mode='same', axes=0)
        with patch.object(pcm, 'RAM_BYTES', 1024), patch.object(pcm, 'CHUNK_FRAMES', 4096):
            actual = pcm.convolve(audio, kernel[:, None])
        np.testing.assert_allclose(actual, expected, atol=1e-12, rtol=1e-12)

    def test_bounded_windows_preserve_match_and_rejection_decisions(self):
        audio = np.random.default_rng(27).normal(0, .1, (50000, 2))
        for changed in (False, True):
            candidate = audio.copy()
            if changed:
                candidate[25000:27000, 1] *= -1
            with patch.object(pcm, 'RAM_BYTES', 1_000_000_000), patch.object(pcm, 'CHUNK_FRAMES', 1_000_000):
                expected = compare.analyze_waveforms(audio, candidate, 1000)
            with patch.object(pcm, 'RAM_BYTES', 1024), patch.object(pcm, 'CHUNK_FRAMES', 4096):
                actual = compare.analyze_waveforms(audio, candidate, 1000)
            self.assertEqual(actual['classification'], expected['classification'])
            self.assertEqual(actual['fullContentSupported'], expected['fullContentSupported'])
            self.assertEqual(actual.get('differingWindows'), expected.get('differingWindows'))

    def test_two_hour_dense_match_stays_within_the_working_memory_budget(self):
        script = "\nimport json, resource, sys, tempfile\nfrom pathlib import Path\nimport numpy as np\nroot = Path(sys.argv[1]); tempfile.tempdir = str(root)\nsys.path.insert(0, sys.argv[2])\nimport stemwise_media.bounded_pcm as pcm\nimport stemwise_media.compare as compare\npcm.SCRATCH_ROOT = root/'comparison-pcm'\naudio = np.memmap(root/'long.bin', mode='w+', dtype='float32', shape=(7200*1000, 2))\nrng = np.random.default_rng(52)\nfor first in range(0, len(audio), pcm.CHUNK_FRAMES):\n    audio[first:first+pcm.CHUNK_FRAMES] = rng.normal(0, .1, (min(pcm.CHUNK_FRAMES, len(audio)-first), 2))\n    pcm.discard(audio)\nbaseline = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*1024\nresult = compare.analyze_waveforms(audio, audio, 1000)\nprint(json.dumps({'classification': result['classification'], 'windows': result['channels'][0]['windows'],\n    'peakGrowthBytes': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*1024-baseline}))\n"
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            completed = run_process_tree([sys.executable, '-c', script, directory,
                str(ROOT/'PC side/qobuz-comparison')], text=True, capture_output=True, check=True, timeout=120)
            result = json.loads(completed.stdout)
        self.assertEqual(result['classification'], 'strong_recording_match')
        self.assertGreaterEqual(result['windows'], 7199)
        self.assertLess(result['peakGrowthBytes'], 192*1024*1024)

    def test_stuck_decoder_is_reaped_and_its_mapped_staging_is_removed(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            pid = root/'decoder.pid'
            def stuck(_command, **kwargs):
                kwargs['timeout'] = .5
                return run_process_tree([sys.executable, '-c',
                    'import os,time,sys;open(sys.argv[1],"w").write(str(os.getpid()));time.sleep(30)', str(pid)], **kwargs)
            before = set((ROOT/'Test suite/work').iterdir())
            started = time.monotonic()
            with patch.object(compare, 'probe', return_value={'duration': 7200}), \
                    patch.object(compare, 'run_process_tree', side_effect=stuck):
                with self.assertRaises(subprocess.TimeoutExpired):
                    compare.decode(root/'fixture.flac')
            self.assertLess(time.monotonic()-started, 5)
            self.assertTrue(pid.is_file())
            self.assertFalse((Path('/proc')/pid.read_text()).exists())
            self.assertEqual(set((ROOT/'Test suite/work').iterdir()), before)
