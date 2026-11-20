from __future__ import annotations

import sys
import os
import io
import hashlib
import json
import tempfile
import time
import wave
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
from contextlib import redirect_stderr


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

import stemwise_engine.youtube as youtube  # noqa: E402


VIDEO_ID = "a1B2c3D4e5F"


class StrictAnalysisDecodeTests(unittest.TestCase):
    def test_truncated_pcm_fails_before_analysis_and_preserves_source_and_prior_pcm(self):
        for prior in (False, True):
            with self.subTest(prior=prior), tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as name:
                root = Path(name)
                source = root / 'source.wav'
                with wave.open(str(source), 'wb') as stream:
                    stream.setnchannels(1)
                    stream.setsampwidth(2)
                    stream.setframerate(48_000)
                    stream.writeframes(b'\0\1' * 48_000)
                valid = source.read_bytes()
                source.write_bytes(valid[:-3])
                output = root / 'analysis.wav'
                if prior:
                    output.write_bytes(valid)
                with self.assertRaises((RuntimeError, ValueError)):
                    youtube.decode_analysis_audio(source, root, timeout_seconds=10)
                self.assertEqual(source.read_bytes(), valid[:-3])
                if prior:
                    self.assertEqual(output.read_bytes(), valid)
                else:
                    self.assertFalse(output.exists())
                self.assertEqual({path.name for path in root.iterdir()},
                                 {'source.wav', 'analysis.wav'} if prior else {'source.wav'})

    def test_corrupt_compressed_packet_preserves_accepted_pcm_and_source(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as name:
            root = Path(name)
            source = root / 'source.aac'
            youtube.run_process_tree(['ffmpeg', '-y', '-v', 'error', '-f', 'lavfi', '-i',
                'sine=frequency=1000:sample_rate=48000:duration=2', '-c:a', 'aac', '-f', 'adts', str(source)],
                timeout=10, check=True, capture_output=True)
            output = youtube.decode_analysis_audio(source, root, timeout_seconds=10)
            prior = output.read_bytes()
            data = bytearray(source.read_bytes())
            frames, offset = [], 0
            while offset < len(data):
                self.assertEqual(bytes(data[offset:offset+2]), b'\xff\xf1')
                length = ((data[offset+3] & 3) << 11) | (data[offset+4] << 3) | (data[offset+5] >> 5)
                self.assertGreater(length, 7)
                frames.append((offset, length)); offset += length
            start, length = frames[len(frames) // 2]
            data[start+7:start+length] = b'\xff' * (length-7)
            source.write_bytes(data)
            with self.assertRaises(RuntimeError):
                youtube.decode_analysis_audio(source, root, timeout_seconds=10)
            self.assertEqual(output.read_bytes(), prior)
            self.assertEqual(source.read_bytes(), data)
            self.assertEqual({path.name for path in root.iterdir()}, {'source.aac', 'analysis.wav'})


class FakeYdl:
    def __init__(self, options, root: Path, *, duration: float, oversized: bool = False):
        self.options = options
        self.root = root
        self.duration = duration
        self.oversized = oversized

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def extract_info(self, _url, *, download):
        info = {"id": VIDEO_ID, "duration": self.duration}
        rejection = self.options["match_filter"](info, incomplete=False)
        if rejection:
            raise RuntimeError(rejection)
        if self.oversized:
            self.options["progress_hooks"][0](
                {"downloaded_bytes": youtube.MAX_ANALYSIS_SOURCE_BYTES + 1}
            )
        if download:
            (self.root / "source.m4a").write_bytes(b"source")
        return info

    @staticmethod
    def sanitize_info(info):
        return info


class YoutubeAudioBoundaryTests(unittest.TestCase):
    def test_retained_copy_cleanup_preserves_primary_failure_and_fatal_priority(self):
        import shutil
        import stemwise_media.quick_batch as quick_batch
        from stemwise_engine.common import WorkerRetirementError
        for boundary in ('copy', 'hash', 'replace', 'cancel', 'fatal', 'cleanup', 'cleanup-fatal'):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory(
                    dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                root = Path(name)
                source, temporary = root/'library/source.wav', root/'work'
                source.parent.mkdir()
                temporary.mkdir()
                source.write_bytes(b'Exact retained source')
                prior = temporary/'source.wav'
                prior.write_bytes(b'Prior source')
                primary = (KeyboardInterrupt('copy cancelled') if boundary == 'cancel' else
                    WorkerRetirementError('copy worker retained') if boundary == 'fatal' else
                    OSError('primary retained copy failure'))
                cleanup = (WorkerRetirementError('cleanup worker retained') if boundary == 'cleanup-fatal' else
                    PermissionError('pending file is locked'))
                pending = None
                copy, digest, replace, unlink = shutil.copyfile, youtube.sha256_file, youtube.durable_replace, Path.unlink
                def copy_candidate(original, destination):
                    nonlocal pending
                    pending = Path(destination)
                    copy(original, destination)
                    if boundary in ('copy', 'cancel', 'fatal', 'cleanup-fatal'):
                        raise primary
                def hash_candidate(path):
                    if boundary == 'hash' and Path(path) == pending:
                        raise primary
                    return digest(path)
                def publish_candidate(staged, destination, **kwargs):
                    if boundary == 'replace':
                        raise primary
                    return replace(staged, destination, **kwargs)
                def deny_pending(path, *args, **kwargs):
                    if path == pending:
                        raise cleanup
                    return unlink(path, *args, **kwargs)
                expected = cleanup if boundary in ('cleanup', 'cleanup-fatal') else primary
                with mock.patch.object(quick_batch, 'ROOT', source.parent), \
                        mock.patch.object(quick_batch, 'source_file', return_value=source), \
                        mock.patch.object(youtube.shutil, 'copyfile', side_effect=copy_candidate), \
                        mock.patch.object(youtube, 'sha256_file', side_effect=hash_candidate), \
                        mock.patch.object(youtube, 'durable_replace', side_effect=publish_candidate), \
                        mock.patch.object(Path, 'unlink', deny_pending), \
                        mock.patch.object(youtube, 'decode_analysis_audio') as decode:
                    with self.assertRaises(type(expected)) as raised:
                        youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=10)
                self.assertIs(raised.exception, expected)
                decode.assert_not_called()
                self.assertEqual(source.read_bytes(), b'Exact retained source')
                self.assertEqual(prior.read_bytes(), b'Exact retained source' if boundary == 'cleanup' else b'Prior source')
                self.assertFalse((temporary/'original-source.json').exists())
                if boundary != 'cleanup':
                    self.assertEqual(pending.read_bytes(), b'Exact retained source')
                    pending.unlink()

    def test_completed_retained_source_retry_reuses_exact_bytes_and_rejects_corruption(self):
        import stemwise_media.quick_batch as quick_batch
        import soundfile as sf
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            source, temporary = root/'library/source.wav', root/'work'
            source.parent.mkdir()
            with wave.open(str(source), 'wb') as stream:
                stream.setnchannels(1)
                stream.setsampwidth(2)
                stream.setframerate(48_000)
                stream.writeframes(b'\0\1' * 4800)
            original = source.read_bytes()
            with mock.patch.object(quick_batch, 'ROOT', source.parent), \
                    mock.patch.object(quick_batch, 'source_file', return_value=source), \
                    mock.patch.object(youtube, 'run_process_tree', wraps=youtube.run_process_tree) as process:
                failure = ValueError('isolated decoder startup failure')
                with mock.patch.object(youtube, 'decode_analysis_audio', side_effect=failure):
                    with self.assertRaises(ValueError) as raised:
                        youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=10)
                self.assertIs(raised.exception, failure)
                receipt = (temporary/'original-source.json').read_bytes()
                completed = temporary/json.loads(receipt)['file']
                self.assertEqual(completed.read_bytes(), original)
                def interrupted_copy(source, destination):
                    Path(destination).write_bytes(b'partial copy')
                    raise OSError('retry must not recopy completed source')
                with mock.patch.object(youtube.shutil, 'copyfile', side_effect=interrupted_copy) as copy:
                    info, wav = youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=10)
                copy.assert_not_called()
                self.assertEqual(info['id'], VIDEO_ID)
                self.assertEqual(sf.info(wav).frames, 4800)
                self.assertEqual(source.read_bytes(), original)
                self.assertEqual(completed.read_bytes(), original)
                self.assertEqual((temporary/'original-source.json').read_bytes(), receipt)
                self.assertTrue(all('_download-attempt' not in call.args[0] for call in process.call_args_list))
                completed.write_bytes(b'corrupt receipted source')
                before = {path: path.read_bytes() for path in temporary.iterdir()}
                with mock.patch.object(youtube, 'decode_analysis_audio') as decoder, \
                        mock.patch.object(youtube.shutil, 'copyfile') as copy:
                    with self.assertRaisesRegex(ValueError, 'Completed download source changed'):
                        youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=10)
                decoder.assert_not_called()
                copy.assert_not_called()
                self.assertEqual({path: path.read_bytes() for path in temporary.iterdir()}, before)
                self.assertEqual(source.read_bytes(), original)

    def test_fresh_retained_copy_failure_preserves_prior_source_until_verified_publication(self):
        import stemwise_media.quick_batch as quick_batch
        import soundfile as sf
        for failure in ('interruption', 'verification'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(
                    dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                root = Path(name)
                source, temporary = root/'library/source.wav', root/'work'
                source.parent.mkdir()
                temporary.mkdir()
                with wave.open(str(source), 'wb') as stream:
                    stream.setnchannels(1)
                    stream.setsampwidth(2)
                    stream.setframerate(48_000)
                    stream.writeframes(b'\0\1' * 4800)
                original = source.read_bytes()
                prior = temporary/'source.wav'
                prior.write_bytes(b'Prior temporary source')
                def fail_copy(source, destination):
                    Path(destination).write_bytes(b'Incomplete candidate')
                    if failure == 'interruption':
                        raise OSError('isolated retained copy interruption')
                with mock.patch.object(quick_batch, 'ROOT', source.parent), \
                        mock.patch.object(quick_batch, 'source_file', return_value=source):
                    with mock.patch.object(youtube.shutil, 'copyfile', side_effect=fail_copy), \
                            mock.patch.object(youtube, 'decode_analysis_audio') as decode:
                        with self.assertRaisesRegex((OSError, ValueError), 'copy interruption|changed during copying'):
                            youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=10)
                    decode.assert_not_called()
                    self.assertEqual(prior.read_bytes(), b'Prior temporary source')
                    self.assertEqual(source.read_bytes(), original)
                    self.assertEqual({path.name for path in temporary.iterdir()},
                                     {'source.wav', 'download-request.json'})
                    info, wav = youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=10)
                self.assertEqual(info['id'], VIDEO_ID)
                self.assertEqual(sf.info(wav).frames, 4800)
                self.assertEqual(source.read_bytes(), original)
                self.assertEqual(prior.read_bytes(), original)
                receipt = json.loads((temporary/'original-source.json').read_bytes())
                self.assertEqual(receipt['sha256'], hashlib.sha256(original).hexdigest())
                self.assertEqual(receipt['byteCount'], len(original))

    def test_download_child_retirement_remains_fatal_in_parent_and_retains_partial_source(self):
        import stemwise_media.quick_batch as quick_batch
        from stemwise_engine.common import WorkerRetirementError
        program = '''
import sys
from pathlib import Path
import stemwise_engine.youtube as youtube
from stemwise_engine.common import WorkerRetirementError
def fail(*args, **kwargs):
    Path(args[1].replace('%(ext)s', 'm4a.part')).write_bytes(b'accepted partial source')
    try:
        raise ValueError('native downloader retirement cause')
    except ValueError as cause:
        raise WorkerRetirementError('downloader worker still owns source') from cause
youtube._download_analysis_audio_attempt = fail
raise SystemExit(youtube._download_worker_main(sys.argv[1:]))
'''
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            run = youtube.run_process_tree
            def child(command, **kwargs):
                return run([sys.executable, '-B', '-c', program, *command[4:]], **kwargs)
            with mock.patch.object(quick_batch, 'ROOT', root/'library'), \
                    mock.patch.object(quick_batch, 'source_file', return_value=None), \
                    mock.patch.object(youtube, 'run_process_tree', side_effect=child) as process, \
                    mock.patch.object(youtube, 'decode_analysis_audio') as decode:
                with self.assertRaises(WorkerRetirementError) as raised:
                    youtube.download_analysis_audio(VIDEO_ID, root/'work', timeout_seconds=10)
            process.assert_called_once()
            decode.assert_not_called()
            self.assertIn(b'native downloader retirement cause', raised.exception.stderr)
            self.assertIn(b'WorkerRetirementError', raised.exception.stderr)
            self.assertEqual((root/'work/source.m4a.part').read_bytes(), b'accepted partial source')
            self.assertTrue((root/'work/download-request.json').is_file())

    def test_partial_download_and_completed_source_resume_without_losing_bytes(self):
        import stemwise_media.quick_batch as quick_batch
        for failure in ('download', 'decode'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(
                    dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                root = Path(name)
                temporary = root/'work'
                temporary.mkdir()
                calls = [0, 0]
                def process(command, **kwargs):
                    calls[0] += 1
                    partial = temporary/'source.m4a.part'
                    if failure == 'download' and calls[0] == 1:
                        partial.write_bytes(b'accepted partial bytes')
                        return SimpleNamespace(returncode=1, stderr='isolated transport fault')
                    if failure == 'download':
                        self.assertEqual(partial.read_bytes(), b'accepted partial bytes')
                        partial.unlink()
                    (temporary/'source.m4a').write_bytes(b'exact completed source')
                    (temporary/'download-info.json').write_text(json.dumps({'id': VIDEO_ID}), encoding='utf-8')
                    return SimpleNamespace(returncode=0, stderr='')
                def decode(source, destination, **kwargs):
                    calls[1] += 1
                    if failure == 'decode' and calls[1] == 1:
                        raise ValueError('isolated decoder fault')
                    self.assertEqual(source.read_bytes(), b'exact completed source')
                    audio = destination/'analysis.wav'
                    audio.write_bytes(b'PCM')
                    return audio
                with mock.patch.object(quick_batch, 'ROOT', root/'library'), \
                        mock.patch.object(youtube, 'run_process_tree', side_effect=process), \
                        mock.patch.object(youtube, 'decode_analysis_audio', side_effect=decode):
                    with self.assertRaises((RuntimeError, ValueError)):
                        youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=10)
                    info, audio = youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=10)
                    self.assertEqual(audio.read_bytes(), b'PCM')
                    self.assertEqual(info['id'], VIDEO_ID)
                    before = {path.name: path.read_bytes() for path in temporary.iterdir()}
                    with self.assertRaisesRegex(ValueError, 'request differs'):
                        youtube.download_analysis_audio('z9Y8x7W6v5U', temporary, timeout_seconds=10)
                    self.assertEqual({path.name: path.read_bytes() for path in temporary.iterdir()}, before)
                self.assertEqual(calls, [2, 1] if failure == 'download' else [1, 2])

    def test_decoder_rejects_overflow_and_keeps_every_exact_limit_sample(self):
        import numpy as np
        import soundfile as sf
        work = PROJECT_ROOT.parents[1] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name, mock.patch.object(youtube, 'MAX_DURATION_MS', 1000):
            root = Path(name)
            source = root / 'source.wav'
            exact = np.column_stack((np.linspace(-.2,.2,48000,dtype=np.float32),
                                     np.linspace(.2,-.2,48000,dtype=np.float32)))
            sf.write(source, exact, 48000, subtype='FLOAT')
            decoded = youtube.decode_analysis_audio(source, root/'exact', timeout_seconds=15)
            samples, rate = sf.read(decoded, dtype='float32', always_2d=True)
            self.assertEqual(rate, 48000)
            np.testing.assert_array_equal(samples, exact)
            sf.write(source, np.vstack((exact, exact[:48])), 48000, subtype='FLOAT')
            before = source.read_bytes()
            with self.assertRaisesRegex(ValueError, 'duration'):
                youtube.decode_analysis_audio(source, root/'overflow', timeout_seconds=15)
            self.assertEqual(source.read_bytes(), before)

    def test_download_decode_rejects_missing_or_understated_source_duration(self):
        import numpy as np
        import soundfile as sf
        import shutil
        work = PROJECT_ROOT.parents[1] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name, mock.patch.object(youtube, 'MAX_DURATION_MS', 1000):
            root = Path(name)
            source = root/'retained.flac'
            sf.write(source, np.zeros((48048,2)), 48000, subtype='PCM_24')
            before = source.read_bytes()
            real_process = youtube.run_process_tree
            for metadata in ({}, {'duration': .1}, {'duration': 1.001}):
                temporary = root/f'case-{len(metadata)}-{metadata.get("duration", "none")}'
                def process(command, **kwargs):
                    if '_download-attempt' in command:
                        shutil.copyfile(source, temporary/'source.flac')
                        Path(command[-2]).write_text(json.dumps({'id': VIDEO_ID, **metadata}), encoding='utf-8')
                        return SimpleNamespace(returncode=0, stderr='')
                    return real_process(command, **kwargs)
                with self.subTest(metadata=metadata), mock.patch.object(youtube, 'run_process_tree', side_effect=process):
                    with self.assertRaisesRegex(ValueError, 'duration'):
                        youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=15)
                self.assertEqual(source.read_bytes(), before)

    def test_retained_source_is_verified_and_reused_without_network(self) -> None:
        import stemwise_media.quick_batch as quick_batch
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            library, temporary = root / 'library', root / 'work'
            source = library / 'audio/youtube' / VIDEO_ID / 'source.webm'
            source.parent.mkdir(parents=True)
            original = b'exact retained compressed recording'
            source.write_bytes(original)
            (source.parent / 'download.json').write_text(json.dumps({'videoId': VIDEO_ID,
                'file': source.name, 'status': 'saved'}))
            temporary.mkdir()
            (temporary / 'source.old').write_bytes(b'old superseded temporary source' * 10)
            def decode(selected, output, **_kwargs):
                self.assertEqual(selected.read_bytes(), original)
                wav = output / 'analysis.wav'
                wav.write_bytes(b'PCM')
                return wav
            with mock.patch.object(quick_batch, 'ROOT', library), mock.patch.object(
                    youtube, 'decode_analysis_audio', side_effect=decode), mock.patch.object(
                    youtube, 'run_process_tree', side_effect=AssertionError('network must not run')):
                info, wav = youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=30)
            self.assertEqual(info['id'], VIDEO_ID)
            self.assertEqual(wav.read_bytes(), b'PCM')
            self.assertEqual(source.read_bytes(), original)
            binding = json.loads((temporary / 'original-source.json').read_bytes())
            self.assertEqual(binding['sha256'], hashlib.sha256(original).hexdigest())
            self.assertEqual((temporary / binding['file']).read_bytes(), original)
            self.assertEqual((temporary/'source.old').read_bytes(), b'old superseded temporary source' * 10)

    def test_retained_source_cannot_escape_its_library(self) -> None:
        import stemwise_media.quick_batch as quick_batch
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            library = root / 'library'
            library.mkdir()
            outside = root / 'owner-audio.webm'
            outside.write_bytes(b'owner audio')
            temporary = root / 'work'
            temporary.mkdir()
            prior = temporary / 'source.webm'
            prior.write_bytes(b'prior temporary work')
            with mock.patch.object(quick_batch, 'ROOT', library), mock.patch.object(
                    quick_batch, 'source_file', return_value=outside), mock.patch.object(
                    youtube, 'run_process_tree', side_effect=AssertionError('network must not run')):
                with self.assertRaises(ValueError):
                    youtube.download_analysis_audio(VIDEO_ID, temporary, timeout_seconds=30)
            self.assertEqual(outside.read_bytes(), b'owner audio')
            self.assertEqual(prior.read_bytes(), b'prior temporary work')

    def test_temporary_source_cannot_overwrite_retained_audio(self) -> None:
        import stemwise_media.quick_batch as quick_batch
        with tempfile.TemporaryDirectory() as name:
            library = Path(name)
            source = library / 'audio/youtube' / VIDEO_ID / 'source.webm'
            source.parent.mkdir(parents=True)
            source.write_bytes(b'retained owner recording')
            (source.parent / 'download.json').write_text(json.dumps({'videoId': VIDEO_ID,
                'file': source.name, 'status': 'saved'}))
            with mock.patch.object(quick_batch, 'ROOT', library), mock.patch.object(
                    youtube, 'run_process_tree', side_effect=AssertionError('network must not run')):
                with self.assertRaises(ValueError):
                    youtube.download_analysis_audio(VIDEO_ID, source.parent, timeout_seconds=30)
            self.assertEqual(source.read_bytes(), b'retained owner recording')

    def test_download_worker_preserves_bounded_private_cause(self) -> None:
        stderr = io.StringIO()
        with mock.patch.object(youtube, '_download_analysis_audio_attempt',
                               side_effect=RuntimeError('provider refusal detail')), redirect_stderr(stderr):
            result = youtube._download_worker_main(
                [VIDEO_ID, 'source.%(ext)s', 'bestaudio/best', 'null', 'info.json', '100'])
        self.assertEqual(result, 2)
        self.assertIn('RuntimeError', stderr.getvalue())
        self.assertIn('provider refusal detail', stderr.getvalue())
        self.assertLessEqual(len(stderr.getvalue()), 12001)

    def test_acquisition_checks_updates_before_constructing_the_downloader(self) -> None:
        events = []
        fake_module = SimpleNamespace(YoutubeDL=lambda _options: events.append("download"))
        with mock.patch.dict(sys.modules, {"yt_dlp": fake_module}), mock.patch(
            'stemwise_engine.youtube.maybe_update_ytdlp', create=True,
            side_effect=lambda *_args, **_kwargs: events.append("update"),
        ):
            youtube._ydl({"skip_download": True})
        self.assertEqual(events, ["update", "download"])

    def test_update_probes_the_requested_video_without_leaking_private_options(self) -> None:
        constructor = mock.Mock()
        fake_module = SimpleNamespace(YoutubeDL=constructor)
        url = youtube.canonical_url(VIDEO_ID)
        with mock.patch.dict(sys.modules, {"yt_dlp": fake_module}), mock.patch('stemwise_engine.youtube.maybe_update_ytdlp') as update:
            youtube._ydl({"_stemwise_probe_url": url, "skip_download": True})
        update.assert_called_once_with(youtube.DENO_RUNTIME, probe_url=url)
        self.assertNotIn("_stemwise_probe_url", constructor.call_args.args[0])
        self.assertEqual(constructor.call_args.args[0]["plugin_dirs"], [])

    def test_ytdlp_uses_the_pinned_workspace_deno(self) -> None:
        sentinel = object()
        constructor = mock.Mock(return_value=sentinel)
        fake_module = SimpleNamespace(YoutubeDL=constructor)
        with mock.patch.dict(sys.modules, {"yt_dlp": fake_module}), mock.patch('stemwise_engine.youtube.maybe_update_ytdlp'):
            self.assertIs(youtube._ydl({"skip_download": True}), sentinel)

        self.assertEqual(
            constructor.call_args.args[0]["js_runtimes"],
            {"deno": {"path": str(youtube.DENO_RUNTIME)}},
        )

    def test_known_overlong_video_is_rejected_before_download(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)

            with mock.patch(
                'stemwise_engine.youtube._ydl',
                side_effect=lambda options: FakeYdl(options, root, duration=7201),
            ):
                with self.assertRaisesRegex(RuntimeError, "Unsupported analysis duration"):
                    youtube._download_analysis_audio_attempt(
                        VIDEO_ID,
                        str(root / "source.%(ext)s"),
                        "bestaudio/best",
                        None,
                        root / "info.json",
                    )

            self.assertEqual(list(root.glob("source.*")), [])

    def test_decode_command_has_duration_rate_and_channel_ceilings(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)

            def run(command, **_kwargs):
                if "_download-attempt" in command:
                    (root / "source.m4a").write_bytes(b"source")
                    Path(command[-2]).write_text(
                        '{"id":"a1B2c3D4e5F","duration":10}\n', encoding="utf-8"
                    )
                else:
                    import soundfile as sf
                    sf.write(command[-1], [[0.0,0.0]], 48000, subtype='FLOAT')
                return SimpleNamespace(returncode=0, stderr="")

            with mock.patch('stemwise_engine.youtube.run_process_tree', side_effect=run) as process:
                youtube.download_analysis_audio(VIDEO_ID, root, timeout_seconds=30)

            self.assertEqual(process.call_count, 2)
            self.assertEqual((root / 'source.m4a').read_bytes(), b'source')
            binding = json.loads((root / 'original-source.json').read_text(encoding='utf-8'))
            self.assertEqual(binding['videoId'], VIDEO_ID)
            self.assertEqual(binding['file'], 'source.m4a')
            self.assertEqual(binding['byteCount'], len(b'source'))
            self.assertEqual(binding['sha256'], hashlib.sha256(b'source').hexdigest())
            command = process.call_args_list[1].args[0]
            self.assertEqual(command[command.index("-t") + 1], str(7200 + 1/48000))
            self.assertEqual(command[command.index("-ar") + 1], "48000")
            self.assertEqual(command[command.index("-ac") + 1], "2")
            self.assertEqual(
                command[command.index("-fs") + 1], str(youtube.MAX_ANALYSIS_WAV_BYTES)
            )

    def test_download_lets_current_ytdlp_choose_the_player_client(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            failed = SimpleNamespace(returncode=2, stderr="download failed")
            with mock.patch('stemwise_engine.youtube.run_process_tree', return_value=failed) as process:
                with self.assertRaisesRegex(RuntimeError, "strategies failed"):
                    youtube.download_analysis_audio(VIDEO_ID, root, timeout_seconds=30)

            process.assert_called_once()
            command = process.call_args.args[0]
            self.assertEqual(command[1:3], ["-m", "stemwise_engine.youtube"])
            self.assertEqual(command[6], "bestaudio/best")
            self.assertEqual(command[7], "null")

    def test_stalled_downloader_is_killed_by_outer_deadline(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            package = root / "stemwise_engine"
            package.mkdir()
            (package / "__init__.py").write_text("", encoding="utf-8")
            (package / "youtube.py").write_text("import time\ntime.sleep(60)\n", encoding="utf-8")
            started = time.monotonic()

            with mock.patch.dict(os.environ, {"PYTHONPATH": str(root)}):
                with self.assertRaisesRegex(RuntimeError, "exceeded its deadline"):
                    youtube.download_analysis_audio(VIDEO_ID, root, timeout_seconds=0.3)

            self.assertLess(time.monotonic() - started, 2.0)

    def test_unknown_duration_download_has_a_hard_source_byte_ceiling(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            observed: dict = {}

            def factory(options):
                observed.update(options)
                return FakeYdl(options, root, duration=10, oversized=True)

            with mock.patch('stemwise_engine.youtube._ydl', side_effect=factory):
                with self.assertRaisesRegex(RuntimeError, "source exceeds its byte limit"):
                    youtube._download_analysis_audio_attempt(
                        VIDEO_ID,
                        str(root / "source.%(ext)s"),
                        "bestaudio/best",
                        None,
                        root / "info.json",
                    )

            self.assertEqual(observed["max_filesize"], youtube.MAX_ANALYSIS_SOURCE_BYTES)


if __name__ == "__main__":
    unittest.main()
