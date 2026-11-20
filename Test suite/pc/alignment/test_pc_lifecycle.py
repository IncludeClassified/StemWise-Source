"""Fatal owned-worker failures stop fallback; ordinary optional failures do not."""
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
import io
import json
from pathlib import Path
import sys
import tempfile
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'Test suite/pc/unit'))
from stemwise_engine import analyzers, alignment_endpoints as endpoints, future_song, lyrics_align_pc as alignment
from stemwise_engine import lyrics_acquire, recording_source, source_acquisition, youtube
from stemwise_engine.common import WorkerRetirementError, WORKER_RETIREMENT_FAILURE_MARKER
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class PcLifecycleTests(unittest.TestCase):
    def setUp(self):
        execution = patch('stemwise_engine.provenance.observe_numerical_execution',
            return_value={'device':'cpu', 'torch':'fixture'})
        execution.start()
        self.addCleanup(execution.stop)

    def test_future_attempt_retirement_survives_an_unavailable_failure_state_writer(self):
        import os
        from stemwise_engine.common import process_lock_descriptor
        with self.temporary() as name:
            root, fatal, held, files = Path(name), self.fatal(), [], []
            workspace = alignment.AlignmentWorkspace(root/'agent', workspace_root=root)
            args = SimpleNamespace(model='fixture', canonical_plain_sha256='c'*64, expected_mix_sha256='a'*64)
            def unretired(*args, temp_root_override, **options):
                pending = temp_root_override/'pending.wav'
                pending.write_bytes(b'unretired numerical output')
                files.append(pending)
                held.append(os.dup(process_lock_descriptor(temp_root_override/'.stemwise-worker.lock')))
                raise fatal
            try:
                with patch.object(alignment, '_align', side_effect=unretired), \
                        patch.object(workspace, 'write_state', side_effect=OSError('state unavailable')) as write:
                    with self.assertRaises(WorkerRetirementError) as raised:
                        alignment._run_future_alignment_attempt({'analysisTimeoutMinutes':1}, args,
                            video_id='abcdefghijk', attempt_id='b'*32, workspace_root=root,
                            generated_root=root/'generated', future_workspace=workspace,
                            future_paths=(root/'sidecars', root/'output', root/'models', workspace))
                self.assertIs(raised.exception, fatal)
                write.assert_not_called()
                self.assertEqual(files[0].read_bytes(), b'unretired numerical output')
            finally:
                for descriptor in held:
                    os.close(descriptor)

    def test_manifested_temporary_owner_preserves_live_worker_files(self):
        import os
        import select
        import subprocess
        from stemwise_engine.common import (manifested_temporary_directory, remove_manifested_directory,
            process_lock_descriptor, ProcessLockBusyError)
        with self.temporary() as name:
            root = Path(name)
            reader, writer = os.pipe()
            process = None
            owner = {'kind': 'retirement-fixture'}
            try:
                with self.assertRaises(WorkerRetirementError):
                    with manifested_temporary_directory(root, prefix='owned-', owner=owner) as path:
                        (path/'pending.wav').write_bytes(b'live PCM')
                        descriptor = process_lock_descriptor(path/'.stemwise-worker.lock')
                        self.assertIsNotNone(descriptor)
                        process = subprocess.Popen([sys.executable, '-B', '-c',
                            'import os,sys; media=open(sys.argv[2],"ab",buffering=0); '
                            'print("ready",flush=True); os.read(int(sys.argv[1]),1); media.write(b" retired")',
                            str(reader), str(path/'pending.wav')],
                            pass_fds=(descriptor, reader), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                        self.assertTrue(select.select([process.stdout], [], [], 5)[0], 'worker never acquired its lease')
                        self.assertEqual(process.stdout.readline(), b'ready\n')
                        raise self.fatal()
                self.assertEqual((path/'pending.wav').read_bytes(), b'live PCM')
                self.assertIsNone(process.poll())
                with self.assertRaises(ProcessLockBusyError):
                    remove_manifested_directory(root, path, owner=owner)
                self.assertTrue((path/'pending.wav').exists())
                os.close(writer)
                writer = None
                self.assertEqual(process.wait(timeout=5), 0)
                self.assertEqual((path/'pending.wav').read_bytes(), b'live PCM retired')
                self.assertTrue(remove_manifested_directory(root, path, owner=owner))
            finally:
                if writer is not None:
                    os.close(writer)
                os.close(reader)
                if process is not None:
                    if process.poll() is None:
                        process.kill()
                    process.communicate(timeout=5)
            with self.assertRaises(ValueError):
                with manifested_temporary_directory(root, prefix='owned-', owner=owner) as path:
                    (path/'pending.wav').write_bytes(b'retired PCM')
                    raise ValueError('ordinary failure after retirement')
            self.assertFalse(path.exists())

    def test_independently_closed_temporary_owners_keep_other_active_leases(self):
        from stemwise_engine.common import WorkerTemporaryDirectory, process_lock_descriptor
        with self.temporary() as name:
            first = WorkerTemporaryDirectory(prefix='first-', dir=name)
            second = WorkerTemporaryDirectory(prefix='second-', dir=name)
            try:
                first.cleanup()
                self.assertFalse(Path(first.name).exists())
                self.assertIsNotNone(process_lock_descriptor(Path(second.name)/'.stemwise-worker.lock'))
                (Path(second.name)/'still-owned').write_bytes(b'active')
            finally:
                second.cleanup()
            self.assertFalse(Path(second.name).exists())

    def test_temporary_lease_acquisition_failure_removes_only_its_new_directory(self):
        from contextlib import contextmanager
        from stemwise_engine import common
        @contextmanager
        def rejected(*args, **kwargs):
            raise ValueError('lease acquisition rejected')
            yield
        with self.temporary() as name:
            root = Path(name)
            with patch.object(common, 'exclusive_process_lock', rejected):
                with self.assertRaisesRegex(ValueError, 'acquisition rejected'):
                    common.WorkerTemporaryDirectory(prefix='owned-', dir=root)
            self.assertEqual(list(root.iterdir()), [])

    def test_audio_view_close_preserves_both_separation_owners_after_fatal_retirement(self):
        import os
        from stemwise_engine.common import WorkerTemporaryDirectory, process_lock_descriptor
        from stemwise_engine.alignment_audio import AlignmentAudioViews
        for release_failure in (False, True):
            with self.subTest(release_failure=release_failure), self.temporary() as name:
                root, fatal, held = Path(name), self.fatal(), []
                release = Mock(side_effect=ValueError('secondary unload failure') if release_failure else None)
                owner = AlignmentAudioViews(config={}, args=None, workspace=None, video_id='abcdefghijk',
                    duration_ms=1000, expected_mix_sha256=None, analysis_audio=None, temp_root=root,
                    release_model=release, pinned_models={}, state={}, runtime_cache={}, route_records={},
                    failure_codes={}, separate_vocals=None, run_roformer=None, worker_duration=None)
                owners = [WorkerTemporaryDirectory(prefix=prefix, dir=root) for prefix in ('htdemucs-', 'roformer-')]
                owner.htdemucs_temporary, owner.roformer_temporary = owners
                for temporary in owners:
                    path = Path(temporary.name)
                    (path/'pending.wav').write_bytes(b'live separated PCM')
                    held.append(os.dup(process_lock_descriptor(path/'.stemwise-worker.lock')))
                try:
                    with self.assertRaises(WorkerRetirementError) as raised:
                        try:
                            raise fatal
                        finally:
                            owner.close()
                    self.assertIs(raised.exception, fatal)
                    for temporary in owners:
                        self.assertEqual((Path(temporary.name)/'pending.wav').read_bytes(), b'live separated PCM')
                finally:
                    for descriptor in held:
                        os.close(descriptor)

    def test_provenance_retirement_cannot_start_the_next_probe(self):
        from stemwise_engine import provenance
        for probe in (lambda: provenance.executable_identity('ffmpeg'),
                      lambda: provenance._allin1_environment(Path(sys.executable))):
            fatal = self.fatal()
            with patch.object(provenance.shutil, 'which', return_value='/fixture/tool'), \
                    patch.object(provenance, 'run_process_tree', side_effect=fatal) as process:
                with self.assertRaises(WorkerRetirementError) as raised:
                    probe()
            self.assertIs(raised.exception, fatal)
            self.assertEqual(process.call_count, 1)

    def test_alignment_doctor_retirement_stops_environment_fallback(self):
        fatal = self.fatal()
        with patch.object(alignment, '_paths', return_value=(None, None, Path('models'), None)), \
                patch.object(alignment, '_verify_pinned_stack', return_value={}), \
                patch.object(alignment, '_verify_alignment_runtime', return_value={}), \
                patch.dict(sys.modules, {name: ModuleType(name) for name in ('faster_whisper', 'rapidfuzz', 'syncalong', 'yt_dlp')}), \
                patch.object(alignment, '_mms_runtime_provenance', side_effect=fatal) as mms, \
                patch.object(alignment, '_roformer_runtime_provenance') as roformer:
            with self.assertRaises(WorkerRetirementError) as raised:
                alignment._doctor({'projectDir': str(ROOT/'PC side/stemwise-playlist-builder')})
        self.assertIs(raised.exception, fatal)
        mms.assert_called_once()
        roformer.assert_not_called()

    def test_anchored_endpoint_retirement_stops_repairs(self):
        from test_alignment_window_synchronization import AlignmentWindowSynchronizationTests
        for boundary in ('mms', 'widened-verifier'):
            with self.subTest(boundary=boundary):
                fixture = AlignmentWindowSynchronizationTests()
                try:
                    stage = fixture.check_plan(unprompted=False)
                    initial = stage.request.evidence.baseline_segments
                    fatal = self.fatal()
                    if boundary == 'mms':
                        mms = Mock(side_effect=fatal)
                        stage.services = replace(stage.services, workers=replace(stage.services.workers, load_mms=mms))
                    else:
                        # The real repair branch widens this reported short segment.
                        mms = Mock(side_effect=alignment.worker_protocol.WorkerProcessError(
                            'fixture MMS', 'wav2vec2-segment-too-short:1'))
                        stage.services = replace(stage.services, workers=replace(stage.services.workers, load_mms=mms))
                    with patch.object(stage, 'load_route_wav2vec2', side_effect=fatal) as verifier:
                        with self.assertRaises(WorkerRetirementError) as raised:
                            stage.repair_anchored('htdemucs-vocals', initial, ('verifier',)*3)
                    self.assertIs(raised.exception, fatal)
                    mms.assert_called_once()
                    if boundary == 'mms':
                        verifier.assert_not_called()
                finally:
                    fixture.doCleanups()

    def test_lyric_acquisition_temporary_owners_preserve_live_handles(self):
        import os
        from stemwise_engine.common import process_lock_descriptor
        for boundary in ('caption', 'whisper', 'mms-asr', 'mms-final'):
            for fatal_retirement in (True, False):
                with self.subTest(boundary=boundary, fatal=fatal_retirement), self.temporary() as name:
                    root, held, paths = Path(name), [], []
                    failure = self.fatal() if fatal_retirement else ValueError('ordinary retired failure')
                    workspace = alignment.AlignmentWorkspace(root/'agent', workspace_root=root)
                    def pending(directory):
                        directory = Path(directory)
                        directory.mkdir(parents=True, exist_ok=True)
                        path = directory/'pending.private.wav'
                        path.write_bytes(b'owned worker bytes')
                        paths.append(path)
                        if fatal_retirement:
                            descriptor = process_lock_descriptor(directory/'.stemwise-worker.lock')
                            held.append(os.dup(descriptor))
                        raise failure
                    try:
                        if boundary == 'caption':
                            def download(video, directory, **options):
                                pending(directory)
                            with patch.object(alignment, '_current_cache_identity', return_value={}), \
                                    patch.object(alignment, 'download_youtube_caption_evidence', side_effect=download):
                                if fatal_retirement:
                                    with self.assertRaises(WorkerRetirementError):
                                        alignment._load_caption(video_id='abcdefghijk', original={'language':'en'},
                                            workspace=workspace, temp_root=root/'temp')
                                else:
                                    alignment._load_caption(video_id='abcdefghijk', original={'language':'en'},
                                        workspace=workspace, temp_root=root/'temp')
                        elif boundary == 'whisper':
                            with patch.object(alignment, '_whisper_recognition_cache_identity', return_value={}), \
                                    patch.object(alignment, 'deduplicated_lyrics_prompt', return_value='hello'), \
                                    patch.object(alignment.audio_store, 'read_audio_provenance', return_value=None), \
                                    patch.object(alignment, 'download_analysis_audio', side_effect=lambda video, directory, **kw: pending(directory)):
                                with self.assertRaises(type(failure)):
                                    alignment._load_or_transcribe(video_id='abcdefghijk', original={'text':'hello'},
                                        workspace=workspace, model_name='model', model_cache=root/'models', language='en',
                                        separate_vocals=False, project_dir=root, device='cpu', compute_type='float32',
                                        duration_ms=1000, get_model=Mock(), temp_root=root/'temp', timeout_seconds=5, rerun=True)
                        elif boundary == 'mms-final':
                            project = root/'builder'
                            prepare_runtime_layout(project)
                            for relative in ('builder/.venv/bin/python', 'builder/src/mms_forced_alignment.py',
                                    'model-cache/torch/hub/checkpoints/model.pt',
                                    'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th'):
                                dependency = root/relative
                                dependency.parent.mkdir(parents=True, exist_ok=True)
                                dependency.touch()
                            with patch.object(alignment, '_mms_runtime_provenance', return_value={}), \
                                    patch.object(alignment.audio_store, 'read_audio_provenance', return_value=None), \
                                    patch.object(alignment, 'download_analysis_audio', side_effect=lambda video, directory, **kw: pending(directory)):
                                with self.assertRaises(type(failure)):
                                    alignment._load_final_mms_alignment(config={'projectDir':str(project)},
                                        video_id='abcdefghijk', original={'text':'hello'},
                                        alignment=SimpleNamespace(source_physical_indices=(0,)), occurrence_mapping={},
                                        duration_ms=1000, workspace=workspace, temp_root=root/'temp', device='cpu', timeout_seconds=5)
                        else:
                            import stemwise_engine.mms_asr as mms
                            project = root/'builder'
                            prepare_runtime_layout(project)
                            def run(command, **options):
                                pending(Path(command[command.index('--output')+1]).parent)
                            with patch.object(mms, 'run_process_tree', side_effect=run):
                                with self.assertRaises(type(failure)):
                                    mms.load_or_transcribe(project=project, model_cache=root/'models', job_dir=root,
                                        audio=root/'source.wav', audio_identity={'sha256':'a'*64}, duration_ms=1000,
                                        route='mix', device='cpu', timeout=5, env={}, rerun=True, runtime_report={'recipe':'fixture'})
                        self.assertEqual(len(paths), 1)
                        self.assertEqual(paths[0].exists(), fatal_retirement)
                        if fatal_retirement:
                            self.assertEqual(paths[0].read_bytes(), b'owned worker bytes')
                    finally:
                        for descriptor in held:
                            os.close(descriptor)

    def temporary(self):
        return tempfile.TemporaryDirectory(prefix='nov6-pc-', dir=ROOT/'Test suite/work')

    def fatal(self):
        return WorkerRetirementError(WORKER_RETIREMENT_FAILURE_MARKER)

    def test_download_retirement_cancels_running_and_queued_transfers(self):
        from concurrent.futures import ThreadPoolExecutor
        from threading import Event
        from stemwise_media import save_download
        from stemwise_engine.provider_operations import ProviderCancelled
        with self.temporary() as name:
            root, fatal = Path(name), self.fatal()
            queued, peer_running = Event(), Event()
            provider_calls, peer_cancelled = [], []
            class ObservedExecutor(ThreadPoolExecutor):
                def __init__(self, **kwargs):
                    super().__init__(**kwargs)
                    self.submitted = 0
                def submit(self, *args, **kwargs):
                    future = super().submit(*args, **kwargs)
                    self.submitted += 1
                    if self.submitted == 3:
                        queued.set()
                    return future
            def provider(url, budget, **kwargs):
                track = int(url.rsplit('Job', 1)[1])
                provider_calls.append(track)
                pending = root/'audio/qobuz'/str(track)/'pending.partial'
                pending.write_bytes(b'owned transfer bytes')
                if track == 11:
                    self.assertTrue(peer_running.wait(5), 'second transfer did not enter provider work')
                    self.assertTrue(queued.wait(5), 'third transfer was not queued')
                    raise fatal
                if track == 22:
                    peer_running.set()
                    self.assertTrue(budget.cancelled.__self__.wait(5), 'running peer was not cancelled')
                    with self.assertRaises(ProviderCancelled):
                        budget.remaining()
                    peer_cancelled.append(track)
                    raise ProviderCancelled('provider-operation-cancelled')
                self.fail('queued transfer entered provider work after fatal retirement')
            # The collector waits on the peer, so only the failing worker can cancel before slot reuse.
            args = ['save_download'] + [f'{track}=https://eu.doubledouble.top/dl/Job{track}' for track in (22, 11, 33)]
            with patch.object(save_download, 'ROOT', root), \
                    patch.object(save_download, 'ThreadPoolExecutor', ObservedExecutor), \
                    patch.object(save_download, 'http_request', side_effect=provider), \
                    patch.object(sys, 'argv', args), redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                with self.assertRaises(WorkerRetirementError) as raised:
                    save_download.main()
            self.assertIs(raised.exception, fatal)
            self.assertCountEqual(provider_calls, [11, 22])
            self.assertEqual(peer_cancelled, [22])
            for track in (11, 22):
                self.assertEqual((root/'audio/qobuz'/str(track)/'pending.partial').read_bytes(), b'owned transfer bytes')

    def test_download_ordinary_failures_still_aggregate_and_finish_peers(self):
        from stemwise_media import save_download
        calls = []
        def transfer(track, url, *, budget):
            budget.remaining()
            calls.append(track)
            if track == '11':
                raise OSError('retired transfer failed')
            return {'trackId': track, 'status': 'saved'}
        output, errors = io.StringIO(), io.StringIO()
        args = ['save_download'] + [f'{track}=https://eu.doubledouble.top/dl/Job{track}' for track in (11, 22, 33)]
        with patch.object(save_download, 'save_download', side_effect=transfer), \
                patch.object(sys, 'argv', args), redirect_stdout(output), redirect_stderr(errors):
            self.assertEqual(save_download.main(), 1)
        self.assertCountEqual(calls, ['11', '22', '33'])
        self.assertCountEqual([json.loads(line)['trackId'] for line in output.getvalue().splitlines()], ['22', '33'])
        self.assertIn('retired transfer failed', errors.getvalue())

    def test_download_cleanup_failure_preserves_fatal_retirement(self):
        from concurrent.futures import ThreadPoolExecutor
        from stemwise_media import save_download
        class CleanupFailureExecutor(ThreadPoolExecutor):
            def shutdown(self, **kwargs):
                super().shutdown(**kwargs)
                raise OSError('secondary executor cleanup failure')
        fatal = self.fatal()
        with patch.object(save_download, 'ThreadPoolExecutor', CleanupFailureExecutor), \
                patch.object(save_download, 'save_download', side_effect=fatal), \
                patch.object(sys, 'argv', ['save_download', '11=https://eu.doubledouble.top/dl/Job11']):
            with self.assertRaises(WorkerRetirementError) as raised:
                save_download.main()
        self.assertIs(raised.exception, fatal)

    def test_musicbrainz_retirement_stops_pipeline_and_preserves_previous_child(self):
        import stemwise_engine.pipeline as pipeline_module
        from test_metadata_research import FakeClient, fallback, recording
        from test_pipeline_hardening import package_config, create_cover, VIDEO_ID
        from test_packages import write_child
        for boundary in ('search_recordings', 'lookup_recording'):
            with self.subTest(boundary=boundary), self.temporary() as name:
                root, fatal = Path(name), self.fatal()
                config = package_config(root)
                owner = pipeline_module.StemwisePipeline(config)
                client = FakeClient([recording()])
                setattr(client, boundary, Mock(side_effect=fatal))
                owner.metadata_client = client
                write_child(owner.child_path(VIDEO_ID), VIDEO_ID, config)
                previous = owner.child_path(VIDEO_ID).read_bytes()
                audio_paths = []
                def download(video, temporary):
                    audio = temporary/'analysis.wav'
                    audio.write_bytes(b'owned source audio')
                    audio_paths.append(audio)
                    return {'id': video}, audio
                with patch.object(youtube, 'fetch_video_info', return_value={'id': VIDEO_ID, 'duration': 240}), \
                        patch.object(owner, '_metadata', return_value=fallback()), \
                        patch.object(owner, '_create_cover', side_effect=create_cover), \
                        patch.object(owner, '_acquire_analysis_audio', side_effect=download), \
                        patch.object(pipeline_module, 'probe_duration_ms', return_value=240000), \
                        patch.object(owner, '_finalize_metadata', side_effect=AssertionError('later pipeline work')) as finalize, \
                        patch.object(owner, '_analysis') as analyze:
                    with self.assertRaises(WorkerRetirementError) as raised:
                        owner.process_song({'position': 1, 'videoId': VIDEO_ID}, force=True)
                self.assertIs(raised.exception, fatal)
                getattr(client, boundary).assert_called_once()
                finalize.assert_not_called()
                analyze.assert_not_called()
                self.assertEqual(owner.child_path(VIDEO_ID).read_bytes(), previous)
                self.assertEqual(audio_paths[0].read_bytes(), b'owned source audio')

    def test_musicbrainz_retired_provider_failures_keep_optional_fallback(self):
        from stemwise_engine import metadata_research
        from test_metadata_research import FakeClient, fallback, recording
        for boundary in ('search_recordings', 'lookup_recording'):
            with self.subTest(boundary=boundary):
                client, original = FakeClient([recording()]), fallback()
                setattr(client, boundary, Mock(side_effect=OSError('transient provider failure')))
                metadata, research = metadata_research.enrich_metadata(original, 240000, client)
                self.assertEqual(metadata, original)
                self.assertEqual(research['status'], 'unavailable')

    def test_windowed_essentia_retires_before_next_command(self):
        import numpy as np
        import soundfile as sf
        import stemwise_engine.analysis_resources as resources
        es, essentia = ModuleType('essentia.standard'), ModuleType('essentia')
        essentia.standard = es
        es.FrameGenerator = Mock(return_value=[np.zeros(4096)] * 8)
        es.Windowing = Mock(return_value=lambda frame: frame)
        es.Spectrum = Mock(return_value=lambda frame: frame)
        es.SpectralPeaks = Mock(return_value=lambda _: ([440., 880.], [1., .3]))
        es.TuningFrequency = Mock(return_value=Mock(return_value=(440., 0.)))
        for failed_call in (1, 2):
            with self.subTest(failed_call=failed_call):
                fatal = self.fatal()
                def ffmpeg(command, **kwargs):
                    if process.call_count == failed_call:
                        raise fatal
                    return SimpleNamespace(stdout=np.zeros(44100, dtype='<f4').tobytes())
                with patch.dict(sys.modules, {'essentia': essentia, 'essentia.standard': es}), \
                        patch.object(sf, 'info', return_value=SimpleNamespace(frames=44100, samplerate=44100)), \
                        patch.object(resources, 'require_analysis_capacity', return_value={'fullWindowAdmitted': False}), \
                        patch.object(analyzers, 'run_process_tree', side_effect=ffmpeg) as process:
                    with self.assertRaises(WorkerRetirementError) as raised:
                        analyzers.analyze_essentia(Path('fixture.wav'))
                self.assertIs(raised.exception, fatal)
                self.assertEqual(process.call_count, failed_call)

    def test_subtitle_retirement_stops_format_and_language_fallback(self):
        for failed_call in (1, 2):
            with self.subTest(failed_call=failed_call), self.temporary() as name:
                destination, fatal = Path(name), self.fatal()
                info = {'id': 'abcdefghijk', 'subtitles': {
                    language: [{'ext': ext, 'url': f'https://fixture/{language}.{ext}'}
                               for ext in ('vtt', 'json3')] for language in ('en', 'fr')}}
                with patch.object(source_acquisition, '_download_candidate',
                        side_effect=[fatal] if failed_call == 1 else [ValueError('optional format'), fatal]) as download:
                    with self.assertRaises(WorkerRetirementError) as raised:
                        source_acquisition.collect_normal_youtube_candidates(info, destination, video_id=info['id'])
                self.assertIs(raised.exception, fatal)
                self.assertEqual(download.call_count, failed_call)
                self.assertEqual(list(destination.iterdir()), [])

    def test_embedded_cover_retirement_stops_picture_and_remote_fallback(self):
        probe = SimpleNamespace(stdout=json.dumps({'streams': [
            {'index': index, 'width': 4, 'height': 4, 'disposition': {'attached_pic': 1}}
            for index in (0, 1)]}).encode())
        for failed_call in (1, 2):
            with self.subTest(failed_call=failed_call), self.temporary() as name:
                fatal = self.fatal()
                source = recording_source.RecordingSource({'artworkMaxPixels': 4, 'pngMaxBytes': 10000},
                    recording=SimpleNamespace(reference_ids=('abcdefghijk',)))
                source.embedded_cover = lambda destination: recording_source.embedded_cover(
                    Path(name)/'audio.flac', destination, max_pixels=4, max_bytes=10000)
                with patch.object(recording_source, 'run_process_tree',
                        side_effect=[fatal] if failed_call == 1 else [probe, fatal, SimpleNamespace(stdout=b'invalid picture')]) as process, \
                        patch.object(youtube, 'fetch_video_info') as remote, \
                        patch.object(youtube, 'create_cover_png'):
                    with self.assertRaises(WorkerRetirementError) as raised:
                        source.create_cover({}, 'abcdefghijk', Path(name)/'cover.png')
                self.assertIs(raised.exception, fatal)
                self.assertEqual(process.call_count, failed_call)
                remote.assert_not_called()
                self.assertFalse((Path(name)/'cover.png').exists())

    def test_pipeline_retirement_preserves_previous_child_and_stops_work(self):
        import stemwise_engine.pipeline as pipeline_module
        from test_pipeline_hardening import package_config, create_cover, youtube_metadata, VIDEO_ID
        from test_packages import write_child
        from stemwise_engine.pipeline import StemwisePipeline
        for boundary, secondary in (('lookup', None), ('audio', None), ('analysis', None),
                ('audio', 'stage'), ('analysis', 'stage'), ('lookup', 'diagnostic'),
                ('lookup', 'rollback')):
            with self.subTest(boundary=boundary, secondary=secondary), self.temporary() as name:
                root, fatal = Path(name), self.fatal()
                config = package_config(root)
                owner = StemwisePipeline(config)
                write_child(owner.child_path(VIDEO_ID), VIDEO_ID, config)
                previous = owner.child_path(VIDEO_ID).read_bytes()
                def download(video, temporary):
                    audio = temporary/'analysis.wav'
                    audio.write_bytes(b'fixture PCM')
                    return {'id': video}, audio
                stage, record, write = owner._stage, owner._record_attempt_failure, pipeline_module.atomic_write_bytes
                def report(state, name, status, **kwargs):
                    if secondary == 'stage' and status == 'failed':
                        raise OSError('optional stage diagnostic')
                    return stage(state, name, status, **kwargs)
                def rollback(path, data):
                    if secondary == 'rollback' and path == owner.child_path(VIDEO_ID):
                        raise OSError('rollback unavailable')
                    return write(path, data)
                with patch.object(youtube, 'fetch_video_info', side_effect=fatal if boundary == 'lookup' else None,
                            return_value={'id': VIDEO_ID, 'duration': 1}), \
                        patch.object(owner, '_metadata', return_value=youtube_metadata(VIDEO_ID)) as metadata, \
                        patch.object(owner, '_create_cover', side_effect=create_cover), \
                        patch.object(owner, '_acquire_analysis_audio', side_effect=fatal if boundary == 'audio' else download) as acquire, \
                        patch.object(pipeline_module, 'run_analysis_worker', side_effect=fatal) as analyze, \
                        patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                        patch.object(owner, '_stage', side_effect=report), \
                        patch.object(owner, '_record_attempt_failure', side_effect=OSError('optional diagnostic') if secondary == 'diagnostic' else record), \
                        patch.object(pipeline_module, 'atomic_write_bytes', side_effect=rollback), \
                        patch.object(pipeline_module, 'enrich_metadata', side_effect=lambda value, *_args, **_kw: (value, {})):
                    with self.assertRaises(WorkerRetirementError) as raised:
                        owner.process_song({'position': 1, 'videoId': VIDEO_ID}, force=True)
                self.assertIs(raised.exception, fatal)
                self.assertEqual(owner.child_path(VIDEO_ID).read_bytes(), previous)
                if secondary != 'rollback':
                    self.assertEqual(owner.load_checkpoint(VIDEO_ID)['status'], 'failed')
                if boundary == 'lookup':
                    metadata.assert_not_called()
                    acquire.assert_not_called()
                if boundary != 'analysis':
                    analyze.assert_not_called()

    def test_future_song_retirement_cannot_enter_capability_fallback(self):
        from test_future_song import workflow_fixture
        with self.temporary() as name:
            workflow, fatal = workflow_fixture(Path(name)), self.fatal()
            video = 'abcdefghijk'
            job = workflow._base_job(video, 'https://youtu.be/'+video)
            workflow._save_job(job)
            workflow._validated_snapshot_is_current = Mock(return_value=False)
            workflow._completed_analysis_audio = Mock(return_value={'byteCount': 1})
            workflow._pipeline = Mock()
            workflow._song_ledger = Mock(return_value=SimpleNamespace(record={}, save=Mock()))
            workflow._validate_impl = Mock(side_effect=fatal)
            workflow._validate_available_capabilities = Mock()
            workflow._commit_failed_alignment_attempt = Mock(side_effect=OSError('optional diagnostic'))
            workflow._record_validation_failure = Mock()
            with self.assertRaises(WorkerRetirementError) as raised:
                workflow._validate_action(video)
            self.assertIs(raised.exception, fatal)
            workflow._validate_available_capabilities.assert_not_called()
            workflow._commit_failed_alignment_attempt.side_effect = None
            workflow._validate_impl.side_effect = future_song.FutureSongError('ordinary enhancement failure')
            workflow._validate_action(video)
            workflow._validate_available_capabilities.assert_called_once()

    def test_pipeline_ordinary_failure_cannot_suppress_failed_rollback(self):
        from test_pipeline_hardening import package_config, VIDEO_ID
        from test_packages import write_child
        from stemwise_engine.pipeline import StemwisePipeline
        with self.temporary() as name:
            owner = StemwisePipeline(package_config(Path(name)))
            write_child(owner.child_path(VIDEO_ID), VIDEO_ID, owner.config)
            with patch.object(youtube, 'fetch_video_info', return_value={'id': VIDEO_ID}), \
                    patch.object(owner, '_metadata', side_effect=ValueError('functional failure')), \
                    patch('stemwise_engine.pipeline.atomic_write_bytes', side_effect=OSError('rollback unavailable')):
                with self.assertRaisesRegex(OSError, 'rollback unavailable'):
                    owner.process_song({'position': 1, 'videoId': VIDEO_ID}, force=True)

    def test_retained_reanalysis_retirement_preserves_committed_media_and_checkpoint(self):
        from test_pipeline_hardening import AnalysisRetryTests
        with self.temporary() as name:
            owner, item = AnalysisRetryTests().retained_rebuild_fixture(Path(name))
            video, fatal = item['videoId'], self.fatal()
            child = owner.child_path(video).read_bytes()
            checkpoint = owner.checkpoint_path(video).read_bytes()
            audio = owner.temp_root/video/'analysis.wav'
            pcm = audio.read_bytes()
            with patch('stemwise_engine.pipeline.run_analysis_worker', side_effect=fatal) as analyze, \
                    patch.object(owner, '_record_attempt_failure', side_effect=OSError('optional diagnostic')), \
                    patch('stemwise_engine.pipeline.probe_duration_ms', return_value=1000):
                with self.assertRaises(WorkerRetirementError) as raised:
                    owner.process_song(item, force=True)
            self.assertIs(raised.exception, fatal)
            analyze.assert_called_once()
            self.assertEqual(owner.child_path(video).read_bytes(), child)
            self.assertEqual(owner.checkpoint_path(video).read_bytes(), checkpoint)
            self.assertEqual(audio.read_bytes(), pcm)

    def test_endpoint_consensus_retirement_stops_next_model(self):
        from test_alignment_content_admission import AlignmentContentAdmissionTests
        with self.temporary() as name:
            stage, _ = AlignmentContentAdmissionTests().endpoint_stage(Path(name))
            stage.request = replace(stage.request, evidence=replace(stage.request.evidence,
                independent_inputs={'ar': ([0], {}, [])}))
            baseline = ([], {'resultClass': 'line-unresolved', 'occurrenceCandidates': [
                {'publishDecision': {'result': 'unresolved', 'code': 'line-start-disagreement'}}]})
            fatal = self.fatal()
            with patch.object(stage, 'load_independent_witness', side_effect=fatal) as load:
                with self.assertRaises(WorkerRetirementError) as raised:
                    stage.apply_independent_consensus(baseline)
            self.assertIs(raised.exception, fatal)
            load.assert_called_once_with(endpoints.OWSM_CTC_FAMILY)

    def test_cascade_retirement_stops_next_route(self):
        fatal = self.fatal()
        evaluate = Mock(side_effect=[fatal, (True, 'unexpected fallback')])
        with self.assertRaises(WorkerRetirementError) as raised:
            alignment._run_conditional_cascade(('first', 'second'), evaluate)
        self.assertIs(raised.exception, fatal)
        evaluate.assert_called_once_with('first')

    def test_caption_retirement_does_not_become_optional_absence(self):
        with self.temporary() as name:
            root, fatal = Path(name), self.fatal()
            workspace = alignment.AlignmentWorkspace(root/'agent', workspace_root=root)
            outcome = Mock()
            with patch.object(alignment, '_current_cache_identity', return_value={}), \
                    patch.object(alignment, 'download_youtube_caption_evidence', side_effect=fatal) as download:
                with self.assertRaises(WorkerRetirementError) as raised:
                    alignment._load_caption(video_id='abcdefghijk', original={'language': 'en'},
                        workspace=workspace, temp_root=root/'temp', record_outcome=outcome)
            self.assertIs(raised.exception, fatal)
            download.assert_called_once()
            outcome.assert_not_called()
            self.assertFalse(workspace.caption_path('abcdefghijk').exists())

    def test_selected_song_retirement_stops_next_song(self):
        with self.temporary() as name:
            root, fatal = Path(name), self.fatal()
            sidecars = root/'sidecars'
            sidecars.mkdir()
            original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en',
                        'script': 'Latn', 'source': {'name': 'Owner'}, 'text': 'one two three four'}
            for video in ('AAAAAAAAAAA', 'BBBBBBBBBBB'):
                (sidecars/f'{video}.lyrics.json').write_text(json.dumps({
                    'status': 'partial', 'reason': 'Timing pending', 'tracks': [original]}), encoding='utf-8')
            workspace = alignment.AlignmentWorkspace(root/'agent', workspace_root=root)
            args = alignment.parser().parse_args(['--config', str(root/'config.json'), 'align', '--device', 'cpu'])
            args.expected_mix_sha256 = 'a'*64
            with patch('stemwise_engine.lyrics_alignment.resolve_compute_type', return_value='float32'), \
                    patch.object(alignment, 'validate_child_zip', return_value={'analysis': {'durationMs': 1000}}), \
                    patch.object(alignment, '_verify_alignment_runtime', side_effect=fatal) as runtime, \
                    redirect_stdout(io.StringIO()):
                with self.assertRaises(WorkerRetirementError) as raised:
                    alignment._align({'projectDir': str(root/'builder'), 'tempDir': str(root/'temp')}, args,
                        selected_override=[{'position': i, 'videoId': video} for i, video in enumerate(
                            ('AAAAAAAAAAA', 'BBBBBBBBBBB'), 1)],
                        paths_override=(sidecars, root/'runtime', root/'models', workspace))
            self.assertIs(raised.exception, fatal)
            runtime.assert_called_once()
            self.assertFalse(workspace.state_path('BBBBBBBBBBB').exists())

    def test_alignment_cli_retirement_marker_reaches_future_song_caller(self):
        from test_future_song import workflow_fixture
        stderr, stdout = io.StringIO(), io.StringIO()
        fatal = self.fatal()
        with self.temporary() as name:
            root = Path(name)
            with patch.object(alignment, 'load_config', side_effect=fatal), \
                    redirect_stderr(stderr), redirect_stdout(stdout):
                code = alignment.main(['--config', str(root/'config.json'), 'align', '--device', 'cpu'])
            self.assertEqual(code, 70)
            self.assertIn(WORKER_RETIREMENT_FAILURE_MARKER, stderr.getvalue().splitlines())
            workflow = workflow_fixture(root)
            project = Path(workflow.config['projectDir'])
            video = 'abcdefghijk'
            for required in (project/'.venv-lyrics-align/bin/python', project/'src/lyrics_align_pc.py',
                             workflow.runtime/'children'/f'{video}.stemwise.zip'):
                required.parent.mkdir(parents=True, exist_ok=True)
                required.write_bytes(b'fixture')
            workflow._alignment_worker_environment = Mock(return_value={})
            completed = SimpleNamespace(returncode=code, stdout=stdout.getvalue(), stderr=stderr.getvalue())
            with patch.object(workflow, '_run_primary_worker', return_value=completed):
                with self.assertRaises(WorkerRetirementError) as raised:
                    workflow._align_future_lyrics(video, 'a'*32, 'b'*64, 'c'*64, 'd'*64, root/'audio.wav')
            self.assertEqual(raised.exception.returncode, 70)
            self.assertIn(WORKER_RETIREMENT_FAILURE_MARKER, raised.exception.stderr.decode('utf-8').splitlines())

    def test_ordinary_optional_failures_still_fall_back(self):
        evaluate = Mock(side_effect=[RuntimeError('optional route'), (True, 'fallback')])
        self.assertEqual(alignment._run_conditional_cascade(('first', 'second'), evaluate), ('second', 'fallback'))
        with self.temporary() as name:
            root = Path(name)
            info = {'id': 'abcdefghijk', 'subtitles': {'en': [
                {'ext': ext, 'url': f'https://fixture/{ext}'} for ext in ('vtt', 'json3')]}}
            with patch.object(source_acquisition, '_download_candidate',
                    side_effect=[ValueError('optional format'), b'{"events":[{"segs":[{"utf8":"caption bytes"}]}]}']) as download:
                result = source_acquisition.collect_normal_youtube_candidates(info, root/'candidates', video_id=info['id'])
            self.assertEqual(result['candidateCount'], 1)
            self.assertEqual(download.call_count, 2)
            workspace = alignment.AlignmentWorkspace(root/'agent', workspace_root=root)
            with patch.object(alignment, '_current_cache_identity', return_value={}), \
                    patch.object(alignment, 'download_youtube_caption_evidence', side_effect=ValueError('optional caption')):
                self.assertIsNone(alignment._load_caption(video_id=info['id'], original={'language': 'en'},
                    workspace=workspace, temp_root=root/'temp'))

    def test_youtube_download_retirement_reaches_pipeline_without_wrapping(self):
        with self.temporary() as name:
            fatal = self.fatal()
            with patch('stemwise_media.quick_batch.source_file', return_value=None), \
                    patch.object(youtube, 'run_process_tree', side_effect=fatal) as process, \
                    patch.object(youtube, 'decode_analysis_audio') as decode:
                with self.assertRaises(WorkerRetirementError) as raised:
                    youtube.download_analysis_audio('abcdefghijk', Path(name), timeout_seconds=60)
            self.assertIs(raised.exception, fatal)
            process.assert_called_once()
            decode.assert_not_called()

    def test_pipeline_download_retirement_retains_unresolved_worker_files(self):
        from test_pipeline_hardening import package_config, create_cover, youtube_metadata, VIDEO_ID
        from test_packages import write_child
        from stemwise_engine.pipeline import StemwisePipeline
        with self.temporary() as name:
            owner = StemwisePipeline(package_config(Path(name)))
            owner.config['keepFailedAudio'] = False
            write_child(owner.child_path(VIDEO_ID), VIDEO_ID, owner.config)
            previous = owner.child_path(VIDEO_ID).read_bytes()
            temporary = owner.temp_root/VIDEO_ID
            partial = temporary/'source.m4a.part'
            fatal = self.fatal()
            def download(command, **kwargs):
                self.assertEqual(command[1:4], ['-m', 'stemwise_engine.youtube', '_download-attempt'])
                partial.write_bytes(b'unresolved downloader bytes')
                raise fatal
            with patch.object(youtube, 'fetch_video_info', return_value={'id': VIDEO_ID, 'duration': 1}), \
                    patch.object(owner, '_metadata', return_value=youtube_metadata(VIDEO_ID)), \
                    patch.object(owner, '_create_cover', side_effect=create_cover), \
                    patch('stemwise_media.quick_batch.source_file', return_value=None), \
                    patch.object(youtube, 'run_process_tree', side_effect=download) as process, \
                    patch.object(owner, '_cleanup_temp', wraps=owner._cleanup_temp) as cleanup, \
                    patch.object(owner, '_analysis') as analyze:
                with self.assertRaises(WorkerRetirementError) as raised:
                    owner.process_song({'position': 1, 'videoId': VIDEO_ID}, force=True)
            self.assertIs(raised.exception, fatal)
            process.assert_called_once()
            analyze.assert_not_called()
            cleanup.assert_not_called()
            self.assertEqual(partial.read_bytes(), b'unresolved downloader bytes')
            self.assertTrue((temporary/'download-request.json').is_file())
            self.assertEqual(owner.child_path(VIDEO_ID).read_bytes(), previous)

    def test_decode_retains_pending_bytes_only_when_worker_retirement_is_unresolved(self):
        for unresolved in (True, False):
            with self.subTest(unresolved=unresolved), self.temporary() as name:
                root = Path(name)
                source = root/'source.m4a'
                source.write_bytes(b'accepted compressed source')
                pending_paths = []
                failure = self.fatal() if unresolved else RuntimeError('ordinary decode failure')
                def ffmpeg(command, **kwargs):
                    pending = Path(command[-1])
                    pending_paths.append(pending)
                    pending.write_bytes(b'unresolved decoder bytes')
                    raise failure
                with patch.object(youtube, 'run_process_tree', side_effect=ffmpeg) as process:
                    with self.assertRaises(type(failure)) as raised:
                        youtube.decode_analysis_audio(source, root, timeout_seconds=60)
                self.assertIs(raised.exception, failure)
                process.assert_called_once()
                self.assertEqual(source.read_bytes(), b'accepted compressed source')
                self.assertFalse((root/'analysis.wav').exists())
                self.assertEqual(len(pending_paths), 1)
                if unresolved:
                    self.assertTrue(pending_paths[0].is_file(), 'unresolved decoder output must remain available')
                    self.assertEqual(pending_paths[0].read_bytes(), b'unresolved decoder bytes')
                else:
                    self.assertFalse(pending_paths[0].exists())

    def test_youtube_artwork_retirement_stops_next_candidate(self):
        with self.temporary() as name:
            fatal, destination = self.fatal(), Path(name)/'cover.png'
            destination.write_bytes(b'previous cover')
            with patch.object(youtube, '_thumbnail_candidates', return_value=['https://fixture/first', 'https://fixture/second']), \
                    patch.object(youtube, '_download_image', side_effect=fatal) as download:
                with self.assertRaises(WorkerRetirementError) as raised:
                    youtube.create_cover_png({}, 'abcdefghijk', destination, max_pixels=4, max_bytes=10000)
            self.assertIs(raised.exception, fatal)
            download.assert_called_once()
            self.assertEqual(destination.read_bytes(), b'previous cover')

    def test_lyric_provider_retirement_stops_search_and_acquisition_fallback(self):
        for operation in ('search', 'acquire'):
            with self.subTest(operation=operation), self.temporary() as name:
                fatal, client = self.fatal(), Mock()
                client.search.side_effect = client.get_watch_playlist.side_effect = fatal
                item = {'videoId': 'abcdefghijk', 'position': 1, 'title': 'Example Song',
                        'uploader': 'Example Artist', 'durationSeconds': 189}
                with self.assertRaises(WorkerRetirementError) as raised:
                    if operation == 'search':
                        lyrics_acquire.discover_counterpart_candidates(item, client=client)
                    else:
                        lyrics_acquire.acquire_exact_item_draft(item, Path(name)/'draft', client=client, detector=Mock())
                self.assertIs(raised.exception, fatal)
                (client.search if operation == 'search' else client.get_watch_playlist).assert_called_once()
                client.get_lyrics.assert_not_called()
                self.assertFalse((Path(name)/'draft/original.txt').exists())

    def test_palette_cover_preserves_exact_rgba_through_both_routes(self):
        from PIL import Image
        image = Image.new('P', (4, 4))
        image.putpalette([255, 0, 0, 0, 255, 0, 0, 0, 255] + [0]*759)
        image.putdata([0, 1, 2, 1]*4)
        image.info['transparency'] = bytes([0, 128, 255])
        payload = io.BytesIO()
        image.save(payload, format='PNG')
        expected = list(image.convert('RGBA').getdata())
        for route in ('youtube', 'embedded'):
            with self.subTest(route=route), self.temporary() as name:
                destination = Path(name)/'cover.png'
                if route == 'youtube':
                    with patch.object(youtube, '_thumbnail_candidates', return_value=['https://fixture/image']), \
                            patch.object(youtube, '_download_image', return_value=payload.getvalue()):
                        youtube.create_cover_png({}, 'abcdefghijk', destination, max_pixels=4, max_bytes=10000)
                else:
                    probe = SimpleNamespace(stdout=json.dumps({'streams': [
                        {'index': 0, 'width': 4, 'height': 4, 'disposition': {'attached_pic': 1}}]}))
                    with patch.object(recording_source, 'run_process_tree', side_effect=[
                            probe, SimpleNamespace(stdout=payload.getvalue())]):
                        self.assertTrue(recording_source.embedded_cover(Path(name)/'audio.flac', destination,
                            max_pixels=4, max_bytes=10000))
                with Image.open(destination) as rendered:
                    self.assertEqual(rendered.size, (4, 4))
                    self.assertEqual(list(rendered.convert('RGBA').getdata()), expected)

    def test_counterpart_normalizes_only_each_sides_matching_artist_prefix(self):
        for source_prefix in ('', 'Example Artist - ', 'Other Artist - '):
            for candidate_prefix in ('', 'Example Artist - ', 'Other Artist - '):
                with self.subTest(source=source_prefix, candidate=candidate_prefix):
                    client = Mock()
                    client.search.return_value = [{'videoId': 'BBBBBBBBBBB',
                        'title': candidate_prefix+'Example Song (Official Video)',
                        'artists': [{'name': 'Example Artist'}], 'duration_seconds': 189}]
                    result = lyrics_acquire.discover_counterpart_candidates({'videoId': 'AAAAAAAAAAA',
                        'title': source_prefix+'Example Song (Official Audio)',
                        'uploader': 'Example Artist - Topic', 'durationSeconds': 189}, client=client)
                    expected = int(('Other Artist' not in source_prefix and 'Other Artist' not in candidate_prefix)
                                   or source_prefix == candidate_prefix)
                    self.assertEqual(result['candidateCount'], expected)
