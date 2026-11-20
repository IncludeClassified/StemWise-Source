import sys
from qobuz_fixtures import write_download
import json
import io
import tempfile
import wave
from contextlib import redirect_stdout
from unittest.mock import patch
import unittest
from pathlib import Path
import numpy as np
from scipy import signal

from stemwise_media.recording_equivalence import analyze_equivalence, discover, fingerprint_index, supported
from stemwise_media.compare import _anchor
import stemwise_media.recording_equivalence as equivalence
import stemwise_media.recording_review as review


class RecordingEquivalenceTests(unittest.TestCase):
    def test_stale_confirmed_row_is_discovered_when_healthy_peer_pairs_its_flac(self):
        for deep in (False, True):
            for control, expected in (('stale-shared', {1}), ('current', set()),
                                      ('cleared-stale', {1}), ('unpaired', {1, 2}), ('rejected', set())):
                with self.subTest(deep=deep, control=control), tempfile.TemporaryDirectory(
                        dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
                    root = Path(name)
                    source = {video: root/f'{video}.audio' for video in ('abcdefghijk', 'lmnopqrstuv')}
                    candidate = root/'10.audio'
                    candidate.write_bytes(b'retained candidate')
                    rows = []
                    for position, (video, path) in enumerate(source.items(), 1):
                        path.write_bytes(b'original source')
                        match = {'trackId': 10, 'classification': 'strong_recording_match',
                            'fullContentSupported': True, 'candidateFile': candidate.name,
                            'candidateSha256': review.batch.file_sha256(candidate),
                            'channels': [{'channel': side, 'windows': 1, 'minimumCorrelation': 1} for side in ('left', 'right')]}
                        rows.append({'position': position, 'videoId': video, 'candidates': [],
                            'refinedAudioComparison': {'sampleRate': 48000, 'recipeVersion': 1,
                                'referenceFile': path.name, 'referenceSha256': review.batch.file_sha256(path),
                                'candidates': [match]}})
                    if control != 'current':
                        source['abcdefghijk'].write_bytes(b'repaired source')
                    if control == 'cleared-stale':
                        rows[0]['refinedAudioComparison']['candidates'][0]['classification'] = 'not_matched'
                    if control == 'unpaired':
                        rows[1]['refinedAudioComparison']['candidates'][0]['classification'] = 'not_matched'
                    if control == 'rejected':
                        rows[0]['rejectedTrackIds'] = [10]
                    catalogue = root/'catalogue.json'
                    catalogue.write_text(json.dumps({'rows': rows}), encoding='utf-8')
                    (root/'comparison-results').mkdir()
                    (root/'comparison-results/library-files.json').write_text('{"flac":[{"trackId":10}]}', encoding='utf-8')
                    inputs = {path: path.read_bytes() for path in (*source.values(), candidate)}
                    with patch.object(review.batch, 'ROOT', root), \
                            patch.object(review.batch, 'source_file', side_effect=source.get), \
                            patch.object(review.batch, 'qobuz_file', return_value=candidate), \
                            patch.object(sys, 'argv', ['recording_equivalence.py']+(['--deep'] if deep else [])), \
                            patch.object(equivalence, 'fingerprint', return_value=np.arange(400, dtype=np.uint32)), \
                            patch.object(review.batch, 'decode', return_value=np.zeros((16, 2))), \
                            patch.object(equivalence, 'analyze_equivalence', return_value={'classification': 'not_supported'}), \
                            redirect_stdout(io.StringIO()) as captured:
                        equivalence.main()
                    summary = json.loads(captured.getvalue().splitlines()[-1])
                    self.assertEqual(summary['comparedPairs'], len(expected))
                    self.assertEqual(summary['skippedEntries'], [])
                    saved = json.loads(catalogue.read_bytes())['rows']
                    self.assertEqual({row['position'] for row in saved if row.get('recordingEquivalence')}, expected)
                    self.assertEqual({path: path.read_bytes() for path in inputs}, inputs)

    def test_fingerprint_retirement_and_cancellation_preserve_catalogue(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled
        for error in (WorkerRetirementError('unretired fingerprint worker'), ProviderCancelled('cancelled')):
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
                root = Path(name)
                source = root/'source.audio'; source.write_bytes(b'original')
                catalogue = root/'catalogue.json'
                catalogue.write_text(json.dumps({'rows': [{'position': 1, 'videoId': 'abcdefghijk'}]}), encoding='utf-8')
                before = catalogue.read_bytes()
                (root/'comparison-results').mkdir()
                (root/'comparison-results/library-files.json').write_text('{"flac": []}', encoding='utf-8')
                with patch.object(review.batch, 'ROOT', root), patch.object(sys, 'argv', ['recording_equivalence.py']), \
                        patch.object(review.batch, 'source_file', return_value=source), \
                        patch.object(equivalence, 'run_process_tree', side_effect=error), \
                        patch.object(review.batch, 'decode', side_effect=AssertionError('fatal discovery cannot compare')):
                    with self.assertRaises(type(error)) as raised: equivalence.main()
                self.assertIs(raised.exception, error)
                self.assertEqual(catalogue.read_bytes(), before)
                self.assertEqual(source.read_bytes(), b'original')

    def test_fingerprint_failures_isolate_inputs_and_retry_in_both_modes(self):
        import subprocess
        from types import SimpleNamespace
        from qobuz_fixtures import flac_bytes
        for deep in (False, True):
            for failed in ('source', 'candidate'):
                with self.subTest(deep=deep, failed=failed), tempfile.TemporaryDirectory(
                        dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
                    root = Path(name)
                    rows = []
                    for position, video, track in ((1, 'abcdefghijk', 10), (2, 'lmnopqrstuv', 20)):
                        source = root/'audio/youtube'/video/'audio.webm'
                        native = root/'audio/qobuz'/str(track)/'audio.flac'
                        source.parent.mkdir(parents=True); native.parent.mkdir(parents=True)
                        source.write_bytes(b'source'); native.write_bytes(flac_bytes())
                        write_download(source.parent, {'videoId': video, 'file': source.name})
                        write_download(native.parent, {'qobuzTrackId': track, 'file': native.name})
                        rows.append({'position': position, 'videoId': video, 'candidates': [],
                            'refinedAudioComparison': {'candidates': [{'trackId': 10}, {'trackId': 20}]}})
                    catalogue = root/'catalogue.json'
                    catalogue.write_text(json.dumps({'rows': rows}), encoding='utf-8')
                    (root/'comparison-results').mkdir()
                    (root/'comparison-results/library-files.json').write_text(json.dumps(
                        {'flac': [{'trackId': 10}, {'trackId': 20}]}), encoding='utf-8')
                    failure_path = root/('audio/youtube/abcdefghijk/audio.webm' if failed == 'source' else 'audio/qobuz/10/audio.flac')
                    inputs = {path: path.read_bytes() for path in (root/'audio').rglob('*') if path.is_file()}
                    failing = True
                    def run(command, **kwargs):
                        path = Path(command[command.index('-i')+1])
                        if failing and path == failure_path:
                            raise subprocess.CalledProcessError(1, command, stderr='ordinary fingerprint failure')
                        return SimpleNamespace(stdout=np.arange(400, dtype='<u4').tobytes())
                    with patch.object(review.batch, 'ROOT', root), \
                            patch.object(sys, 'argv', ['recording_equivalence.py']+(['--deep'] if deep else [])), \
                            patch.object(equivalence, 'run_process_tree', side_effect=run), \
                            patch.object(review.batch, 'decode', return_value=self.audio) as decoded, \
                            patch.object(equivalence, 'analyze_equivalence', return_value={'classification': 'not_supported'}):
                        captured = io.StringIO()
                        with redirect_stdout(captured): equivalence.main()
                        summary = json.loads(captured.getvalue().splitlines()[-1])
                        self.assertEqual(len(summary['skippedEntries']), 1)
                        self.assertEqual(summary['skippedEntries'][0]['source'], 'youtube' if failed == 'source' else 'flac')
                        self.assertGreater(summary['comparedPairs'], 0)
                        self.assertNotIn(failure_path, [call.args[0] for call in decoded.call_args_list])
                        saved = json.loads(catalogue.read_bytes())['rows']
                        if failed == 'source': self.assertNotIn('recordingEquivalence', saved[0])
                        else:
                            for row in saved: self.assertNotIn(10, [c['trackId'] for c in row['recordingEquivalence']['candidates']])
                        failing = False
                        captured = io.StringIO()
                        with redirect_stdout(captured): equivalence.main()
                        self.assertEqual(json.loads(captured.getvalue().splitlines()[-1])['skippedEntries'], [])
                        final = json.loads(catalogue.read_bytes())['rows']
                        self.assertEqual({c['trackId'] for c in final[0]['recordingEquivalence']['candidates']}, {10, 20})
                    self.assertEqual({path: path.read_bytes() for path in inputs}, inputs)

    def setUp(self):
        import stemwise_media.bounded_pcm as pcm
        scratch = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work',
                                              prefix='recording-equivalence-')
        self.addCleanup(scratch.cleanup)
        patched = patch.object(pcm, 'SCRATCH_ROOT', Path(scratch.name)/'pcm')
        patched.start()
        self.addCleanup(patched.stop)

    @classmethod
    def setUpClass(cls):
        rng = np.random.default_rng(117)
        cls.audio = signal.sosfilt(signal.butter(4, 1500, fs=8000, output='sos'),
                                  rng.normal(0, .15, (40 * 8000, 2)), axis=0)

    def test_shared_silent_channel_agrees_but_lost_signal_and_all_silence_do_not(self):
        from stemwise_media.compare import analyze_waveforms
        for active in (0, 1):
            with self.subTest(active=active):
                audio = self.audio.copy(); audio[:, 1-active] = 0
                strict = analyze_waveforms(audio, audio, 8000)
                self.assertTrue(review.confirmed(strict), strict)
                self.assertTrue(supported(analyze_equivalence(audio, audio)))
                lost = audio.copy(); lost[:, active] = 0
                self.assertFalse(review.confirmed(analyze_waveforms(audio, lost, 8000)))
                self.assertFalse(supported(analyze_equivalence(audio, lost)))
        silent = np.zeros_like(self.audio)
        self.assertFalse(review.confirmed(analyze_waveforms(silent, silent, 8000)))
        self.assertFalse(supported(analyze_equivalence(silent, silent)))

    def test_batch_skips_absent_tracks_and_never_proposes_a_row_rejection(self):
        self.check_batch_skips_absent_tracks(False)

    def test_batch_accepts_rows_without_optional_strict_measurements(self):
        self.check_batch_skips_absent_tracks(True)

    def check_batch_skips_absent_tracks(self, omit_strict):
        root_path = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory(dir=root_path/'Test suite/work') as temporary:
            root = Path(temporary); output = root/'comparison-results'; output.mkdir()
            (output/'library-files.json').write_text(json.dumps({'flac': [{'trackId': i} for i in (10, 30, 40)]}), encoding='utf-8')
            rows = [{'position': i, 'videoId': f'source{i}', 'refinedAudioComparison': {'candidates': []},
                     'rejectedTrackIds': [10] if i == 1 else []} for i in (1, 2)]
            if omit_strict:
                for row in rows: row.pop('refinedAudioComparison')
            (root/'catalogue.json').write_text(json.dumps({'rows': rows}), encoding='utf-8')
            source = root/'source.opus'; source.write_bytes(b'source')
            candidate = root/'candidate.flac'; candidate.write_bytes(b'candidate')
            with patch.object(review.batch, 'ROOT', root), patch.object(sys, 'argv', ['recording_equivalence.py']), \
                    patch.object(review.batch, 'source_file', return_value=source), \
                    patch.object(review.batch, 'qobuz_file', side_effect=lambda identity: candidate if identity == 10 else None), \
                    patch.object(equivalence, 'fingerprint', return_value=np.arange(400, dtype=np.uint32)), \
                    patch.object(review.batch, 'decode', return_value=self.audio), \
                    patch.object(equivalence, 'analyze_equivalence', return_value={'classification': 'not_supported'}):
                captured = io.StringIO()
                with redirect_stdout(captured): equivalence.main()
            final = json.loads(captured.getvalue().splitlines()[-1])
            self.assertEqual(final['comparedPairs'], 1)
            self.assertEqual({entry['trackId'] for entry in final['skippedEntries']}, {30, 40})
            saved = json.loads((root/'catalogue.json').read_text(encoding='utf-8'))['rows']
            self.assertNotIn('recordingEquivalence', saved[0])
            self.assertEqual(saved[1]['recordingEquivalence']['candidates'][0]['trackId'], 10)

    def test_dead_mapped_scratch_is_reclaimed_while_live_mapping_is_preserved(self):
        import gc
        import hashlib
        import os
        import subprocess
        import time
        import stemwise_media.bounded_pcm as pcm
        import stemwise_media.compare as compare
        from stemwise_engine.common import _linux_process_identity
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from process_fixture import process_birth, read_line, terminate_exact
        root_path = Path(__file__).resolve().parents[3]
        # The only substitution paces the real ffmpeg command. Its supervisor,
        # descriptor inheritance, decode, mapping and numerical loop stay real.
        code = "\nimport json, sys, threading\nfrom pathlib import Path\nsys.path.insert(0, sys.argv[1])\nimport stemwise_media.bounded_pcm as pcm\nimport stemwise_media.compare as compare\npcm.SCRATCH_ROOT = Path(sys.argv[2])\nsource, mode = Path(sys.argv[3]), sys.argv[4]\noriginal_run = compare.run_process_tree\ndef paced_decode(command, **kwargs):\n    if command[0] == 'ffmpeg' and mode == 'decode':\n        command = list(command)\n        command.insert(command.index('-i'), '-re')\n        print(json.dumps({'stage': 'decode', 'path': str(Path(kwargs['stdout'].name).parent)}), flush=True)\n    return original_run(command, **kwargs)\ncompare.run_process_tree = paced_decode\nmapped = compare.decode(source, rate=8000)\nprint(json.dumps({'stage': 'mapped', 'path': str(Path(mapped.filename).parent),\n    'frames': len(mapped), 'energy': pcm.mean_square(mapped, axis=0).tolist()}), flush=True)\nif mode == 'numeric':\n    discard = pcm.discard\n    def numerical_chunk(array):\n        discard(array)\n        print(json.dumps({'stage': 'numerical-chunk', 'path': str(Path(mapped.filename).parent)}), flush=True)\n        if not threading.Event().wait(30):\n            raise TimeoutError('Numerical death boundary was not retired')\n    pcm.discard = numerical_chunk\n    pcm.mean_square(mapped, axis=0)\nelse:\n    if sys.stdin.readline().strip() != 'release':\n        raise RuntimeError('Live mapped owner lost its release handshake')\n"
        with tempfile.TemporaryDirectory(dir=root_path/'Test suite/work') as name:
            root = Path(name)
            scratch = root/'scratch'
            source = root/'known.wav'
            samples = (self.audio * 30000).astype('<i2')
            with wave.open(str(source), 'wb') as output:
                output.setparams((2, 2, 8000, 0, 'NONE', 'not compressed'))
                output.writeframes(samples.tobytes())
            source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
            expected = samples.astype(np.float64) / 32768
            expected_energy = np.mean(expected ** 2, axis=0)
            children, descendants = [], []
            def launch(mode):
                child = subprocess.Popen([sys.executable, '-B', '-c', code,
                    str(root_path/'PC side/qobuz-comparison'), str(scratch), str(source), mode],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                children.append(child)
                return child
            def message(child, stage):
                row = json.loads(read_line(child.stdout, timeout=10))
                self.assertEqual(stage, row['stage'])
                self.assertEqual(scratch.resolve(), Path(row['path']).parent.resolve())
                return row
            try:
                peer = launch('live')
                live = message(peer, 'mapped')
                self.assertEqual(len(samples), live['frames'])
                np.testing.assert_allclose(live['energy'], expected_energy, rtol=2e-6)
                live_pcm = Path(live['path'])/'pcm.bin'
                live_hash = hashlib.sha256(live_pcm.read_bytes()).hexdigest()
                for mode in ('decode', 'numeric'):
                    with self.subTest(mode=mode):
                        child = launch(mode)
                        row = message(child, 'decode' if mode == 'decode' else 'mapped')
                        abandoned = Path(row['path'])
                        if mode == 'decode':
                            deadline, decoder = time.monotonic()+10, None
                            while time.monotonic() < deadline and decoder is None:
                                try:
                                    guardians = Path(f'/proc/{child.pid}/task/{child.pid}/children').read_text().split()
                                except FileNotFoundError:
                                    self.assertIsNone(child.poll(), 'Scratch owner exited before decoder ownership proof')
                                    continue
                                for guardian in guardians:
                                    listing = Path(f'/proc/{guardian}/task/{guardian}/children')
                                    if not listing.exists():
                                        continue
                                    try:
                                        workers = listing.read_text().split()
                                    except FileNotFoundError:
                                        continue
                                    for worker in workers:
                                        try:
                                            arguments = Path(f'/proc/{worker}/cmdline').read_bytes().split(b'\0')
                                        except FileNotFoundError:
                                            continue
                                        if arguments and Path(os.fsdecode(arguments[0])).name == 'ffmpeg':
                                            held = []
                                            try:
                                                for descriptor in Path(f'/proc/{worker}/fd').iterdir():
                                                    try:
                                                        held.append(descriptor.resolve())
                                                    except FileNotFoundError:
                                                        continue
                                            except FileNotFoundError:
                                                continue
                                            if (abandoned/'lease.lock') in held and (abandoned/'pcm.bin').stat().st_size:
                                                decoder = int(worker)
                                                descendants.extend((int(pid), process_birth(int(pid))) for pid in (guardian, worker))
                                                self.assertIn(b'-re', arguments)
                                                break
                                if decoder is None:
                                    time.sleep(.01)
                            self.assertIsNotNone(decoder, 'Real supervised ffmpeg never inherited the scratch lease and produced PCM')
                            identity = _linux_process_identity(decoder)
                        else:
                            np.testing.assert_allclose(row['energy'], expected_energy, rtol=2e-6)
                            message(child, 'numerical-chunk')
                        child.kill()
                        child.communicate(timeout=5)
                        if mode == 'decode':
                            deadline = time.monotonic()+5
                            while _linux_process_identity(decoder) == identity and time.monotonic() < deadline:
                                time.sleep(.01)
                            self.assertNotEqual(identity, _linux_process_identity(decoder), 'Decoder survived its killed owner')
                        with patch.object(pcm, 'SCRATCH_ROOT', scratch):
                            replacement = compare.decode(source, rate=8000)
                            try:
                                self.assertIsInstance(replacement, np.memmap)
                                self.assertFalse(abandoned.exists(), 'Retired decoded scratch was not reclaimed by the next real decode')
                                np.testing.assert_allclose(replacement[:200], expected[:200], rtol=0, atol=0)
                                self.assertIsNone(peer.poll())
                                self.assertEqual(live_hash, hashlib.sha256(live_pcm.read_bytes()).hexdigest())
                                self.assertEqual(source_hash, hashlib.sha256(source.read_bytes()).hexdigest())
                            finally:
                                del replacement
                                gc.collect()
            finally:
                for child in reversed(children):
                    if child.poll() is None:
                        child.kill()
                    child.communicate(timeout=5)
                for pid, birth in descendants:
                    terminate_exact(pid, birth)
                with patch.object(pcm, 'SCRATCH_ROOT', scratch):
                    cleanup = pcm.temporary_directory()
                    cleanup.cleanup()

    def test_almost_silent_float32_prefix_cannot_win_the_alignment(self):
        rng = np.random.default_rng(291)
        block = rng.normal(0, .1, 8 * 8000).astype(np.float32)
        prefix = rng.normal(0, 1e-9, 30 * 8000).astype(np.float32)
        reference = np.r_[prefix, block]
        location, correlation = _anchor(reference, block)
        self.assertEqual(location, len(prefix))
        self.assertGreater(correlation, .99)

    def test_deep_check_handles_equalization_and_fractional_sample_delay(self):
        other = signal.lfilter([.55, .35, .1], [1], self.audio, axis=0)
        result = analyze_equivalence(self.audio, other, deep=True)
        self.assertTrue(supported(result))
        self.assertEqual(result['compensation'], 'fixed_stereo_filter')

    def test_deep_clock_check_keeps_speed_difference_explicit(self):
        other = signal.resample_poly(self.audio, 1001, 1000, axis=0)
        result = analyze_equivalence(self.audio, other, deep=True)
        self.assertTrue(supported(result))
        self.assertAlmostEqual(result['candidateTimeScale'], 1000 / 1001)
        self.assertFalse(result['fullContentSupported'])

    def test_deep_filter_keeps_out_of_phase_vocals_and_insufficient_audio_safe(self):
        audio = np.c_[self.audio[:, 0], -self.audio[:, 0]]
        other = signal.lfilter([.55, .35, .1], [1], audio, axis=0)
        self.assertTrue(supported(analyze_equivalence(audio, other, deep=True)))
        self.assertFalse(supported(analyze_equivalence(audio[:8000], other[:8000], deep=True)))

    def test_deep_filter_cannot_turn_different_words_over_shared_backing_into_a_match(self):
        rng = np.random.default_rng(324)
        voice = signal.sosfilt(signal.butter(4, 1500, fs=8000, output='sos'), rng.normal(0, .10, len(self.audio)))
        other = self.audio.copy()
        other[10 * 8000:30 * 8000] += voice[10 * 8000:30 * 8000, None]
        result = analyze_equivalence(self.audio, other, deep=True)
        self.assertFalse(supported(result))

    def test_fixed_stereo_width_and_gain_are_not_a_different_performance(self):
        other = self.audio @ np.array([[1.6, .6], [.6, 1.6]])
        result = analyze_equivalence(self.audio, other)
        self.assertEqual(result['classification'], 'same_performance_with_differences')
        self.assertFalse(result['fullContentSupported'])
        self.assertGreaterEqual(result['supportedWindowFraction'], .99)

    def test_mono_youtube_can_match_a_stereo_performance_without_claiming_the_same_master(self):
        mono = np.repeat(self.audio.mean(1)[:, None], 2, axis=1)
        result = analyze_equivalence(mono, self.audio)
        self.assertTrue(supported(result))
        self.assertFalse(result['fullContentSupported'])

    def test_a_success_label_without_distributed_measurements_cannot_resolve_review(self):
        self.assertFalse(supported({'classification': 'same_performance_with_differences', 'fullContentSupported': True}))

    def test_real_match_counter_remains_json_serializable(self):
        result = analyze_equivalence(self.audio, self.audio)
        self.assertIs(type(supported(result)), bool)
        self.assertEqual(json.loads(json.dumps({'supportedPairs': sum(supported(result) for _ in range(2))})),
                         {'supportedPairs': 2})

    def test_batch_resumes_real_comparisons_but_rechecks_a_changed_audio_binding(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'PC side/qobuz-comparison') as temporary:
            root = Path(temporary)
            for folder, identity, audio in [('youtube/abcdefghijk', {'videoId': 'abcdefghijk'}, self.audio),
                                            ('qobuz/10', {'qobuzTrackId': 10},
                                             signal.lfilter([.55, .35, .1], [1], self.audio, axis=0))]:
                base = root / 'audio' / folder
                base.mkdir(parents=True)
                with wave.open(str(base / 'audio.wav'), 'wb') as output:
                    output.setparams((2, 2, 8000, 0, 'NONE', 'not compressed'))
                    output.writeframes((audio * 30000).astype('<i2').tobytes())
                path = base/'audio.wav'
                write_download(base, identity | {'file': path.name, 'bytes': path.stat().st_size,
                    'sha256': review.batch.file_sha256(path)})
            results = root / 'comparison-results'
            results.mkdir()
            (results / 'library-files.json').write_text(json.dumps({'flac': [{'trackId': 10}]}))
            catalogue = root / 'catalogue.json'
            owner = {'source': 'youtube', 'file': 'audio/youtube/abcdefghijk/audio.wav'}
            catalogue.write_text(json.dumps({'rows': [{'position': 1, 'videoId': 'abcdefghijk', 'ownerAudioSelection': owner,
                'refinedAudioComparison': {'candidates': [{'trackId': 10}]}}]}))
            with patch.object(review.batch, 'ROOT', root), \
                    patch.object(sys, 'argv', ['recording_equivalence.py', '--deep']):
                first = io.StringIO()
                with redirect_stdout(first):
                    equivalence.main()
                self.assertEqual(json.loads(first.getvalue().splitlines()[-1])['supportedPairs'], 1)
                with patch.object(equivalence, 'analyze_equivalence', side_effect=AssertionError('Completed comparison was repeated')):
                    second = io.StringIO()
                    with redirect_stdout(second):
                        equivalence.main()
                self.assertEqual(json.loads(second.getvalue().splitlines()[-1])['comparedPairs'], 0)
                base = root / 'audio/qobuz/10'
                with wave.open(str(base / 'other.wav'), 'wb') as output:
                    output.setparams((2, 2, 8000, 0, 'NONE', 'not compressed'))
                    output.writeframes((self.audio[::-1] * 30000).astype('<i2').tobytes())
                write_download(base, {'qobuzTrackId': 10, 'file': 'other.wav'})
                third = io.StringIO()
                with redirect_stdout(third):
                    equivalence.main()
                self.assertEqual(json.loads(third.getvalue().splitlines()[-1])['supportedPairs'], 0)
            row = json.loads(catalogue.read_text())['rows'][0]
            self.assertEqual(row['ownerAudioSelection'], owner)
            self.assertFalse(supported(row['recordingEquivalence']['candidates'][0]))

    def test_batch_retains_success_and_retryable_failure_then_continues_the_next_row(self):
        for failing_key in (20, 1):
            with self.subTest(failing_key=failing_key), tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
                root = Path(name)
                paths = {key: root/f'{key}.audio' for key in (1, 2, 10, 20, 30)}
                for key, path in paths.items(): path.write_bytes(str(key).encode())
                rows = [{'position': position, 'videoId': video, 'candidates': [],
                         'rejectedTrackIds': rejected} for position, video, rejected in
                        ((1, 'abcdefghijk', [30]), (2, 'lmnopqrstuv', [10, 20]))]
                catalogue = root/'catalogue.json'
                catalogue.write_text(json.dumps({'rows': rows}), encoding='utf-8')
                (root/'comparison-results').mkdir()
                (root/'comparison-results/library-files.json').write_text(json.dumps(
                    {'flac': [{'trackId': track} for track in (10, 20, 30)]}), encoding='utf-8')
                decoded = []
                failing = True
                def decode(path, rate):
                    key = int(path.stem); decoded.append(key)
                    if key == failing_key and failing:
                        raise OSError('retired decoder failed')
                    return self.audio
                with patch.object(review.batch, 'ROOT', root), patch.object(sys, 'argv', ['recording_equivalence.py']), \
                        patch.object(review.batch, 'source_file', side_effect=lambda video: paths[1 if video == 'abcdefghijk' else 2]), \
                        patch.object(review.batch, 'qobuz_file', side_effect=lambda track: paths[track]), \
                        patch.object(equivalence, 'fingerprint', return_value=np.arange(400, dtype=np.uint32)), \
                        patch.object(review.batch, 'decode', side_effect=decode), \
                        patch.object(equivalence, 'analyze_equivalence', return_value={'classification': 'not_supported'}), \
                        redirect_stdout(io.StringIO()):
                    equivalence.main()
                    saved = json.loads(catalogue.read_text(encoding='utf-8'))['rows']
                    first = saved[0]['recordingEquivalence']
                    self.assertEqual([result['classification'] for result in first['candidates']], ['not_supported', 'error'] if failing_key == 20 else ['error', 'error'])
                    self.assertIn('retired decoder failed', first['candidates'][1]['reason'])
                    self.assertEqual(first['referenceSha256'], review.batch.file_sha256(paths[1]))
                    self.assertEqual(first['candidates'][0]['candidateSha256'], review.batch.file_sha256(paths[10]))
                    self.assertEqual(saved[1]['recordingEquivalence']['candidates'][0]['trackId'], 30)
                    decoded.clear(); failing = False
                    equivalence.main()
                    self.assertEqual(decoded, [1, 20] if failing_key == 20 else [1, 10, 20])
                    final = json.loads(catalogue.read_text(encoding='utf-8'))['rows']
                    if failing_key == 20:
                        self.assertEqual(final[0]['recordingEquivalence']['candidates'][0], first['candidates'][0])
                    self.assertEqual(final[0]['recordingEquivalence']['candidates'][1]['classification'], 'not_supported')

    def test_batch_propagates_fatal_errors_and_rejects_changed_reference_before_commit(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled
        for failure in (WorkerRetirementError('worker survived'), ProviderCancelled('cancelled'), 'reference-change', 'candidate-change'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
                root = Path(name)
                source, native = root/'source.audio', root/'10.audio'
                source.write_bytes(b'original'); native.write_bytes(b'candidate')
                catalogue = root/'catalogue.json'
                catalogue.write_text(json.dumps({'rows': [{'position': 1, 'videoId': 'abcdefghijk', 'candidates': []}]}), encoding='utf-8')
                before = catalogue.read_bytes()
                (root/'comparison-results').mkdir()
                (root/'comparison-results/library-files.json').write_text(json.dumps({'flac': [{'trackId': 10}]}), encoding='utf-8')
                def analyze(*args, **kwargs):
                    if isinstance(failure, Exception): raise failure
                    (source if failure == 'reference-change' else native).write_bytes(b'changed')
                    return {'classification': 'not_supported'}
                with patch.object(review.batch, 'ROOT', root), patch.object(sys, 'argv', ['recording_equivalence.py']), \
                        patch.object(review.batch, 'source_file', return_value=source), \
                        patch.object(review.batch, 'qobuz_file', return_value=native), \
                        patch.object(equivalence, 'fingerprint', return_value=np.arange(400, dtype=np.uint32)), \
                        patch.object(review.batch, 'decode', return_value=self.audio), \
                        patch.object(equivalence, 'analyze_equivalence', side_effect=analyze), \
                        redirect_stdout(io.StringIO()):
                    with self.assertRaises(type(failure) if isinstance(failure, Exception) else ValueError) as raised:
                        equivalence.main()
                if isinstance(failure, Exception): self.assertIs(raised.exception, failure)
                self.assertEqual(catalogue.read_bytes(), before)

    def test_local_edit_remains_visible_without_discarding_the_recording_identity(self):
        other = self.audio.copy()
        other[18 * 8000:19 * 8000, 1] = 0
        result = analyze_equivalence(self.audio, other)
        self.assertEqual(result['classification'], 'same_performance_with_differences')
        self.assertLess(result['supportedWindowFraction'], 1)
        self.assertTrue(any(a <= 18 < b for a, b in result['differentReferenceWindows']))

    def test_common_backing_does_not_confirm_a_different_vocal_performance(self):
        rng = np.random.default_rng(42)
        voice = signal.sosfilt(signal.butter(4, 1500, fs=8000, output='sos'), rng.normal(0, .15, len(self.audio)))
        other_voice = signal.sosfilt(signal.butter(4, 1500, fs=8000, output='sos'), rng.normal(0, .15, len(self.audio)))
        result = analyze_equivalence(self.audio + voice[:, None], self.audio + other_voice[:, None])
        self.assertEqual(result['classification'], 'not_supported')

    def test_a_common_chorus_cannot_hide_a_different_remainder(self):
        other = self.audio.copy()
        other[18 * 8000:] = other[18 * 8000:, ::-1] * -1
        result = analyze_equivalence(self.audio, other)
        self.assertEqual(result['classification'], 'not_supported')

    def test_search_finds_a_shifted_recording_without_using_titles(self):
        rng = np.random.default_rng(9)
        original = rng.integers(0, 2 ** 32, 400, dtype=np.uint32)
        shifted = np.r_[rng.integers(0, 2 ** 32, 16, dtype=np.uint32), original]
        index = fingerprint_index({1: original, 2: rng.integers(0, 2 ** 32, 400, dtype=np.uint32)})
        self.assertEqual(discover(shifted, index), [1])

    def test_deep_search_tolerates_changed_bits_in_both_fingerprint_halves(self):
        rng = np.random.default_rng(98)
        original = rng.integers(0, 2 ** 32, 400, dtype=np.uint32)
        altered = original ^ np.uint32(0x00010001)
        items = {1: original, 2: rng.integers(0, 2 ** 32, 400, dtype=np.uint32)}
        self.assertEqual(discover(altered, fingerprint_index(items)), [])
        self.assertEqual(discover(altered, fingerprint_index(items, fuzzy=True), fuzzy=True, limit=32), [1])
