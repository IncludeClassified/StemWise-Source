from __future__ import annotations

import json
import sys
import unittest
import uuid
from argparse import Namespace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

WORKSPACE = Path(__file__).resolve().parents[3]
PROJECT = WORKSPACE / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.common import manifested_temporary_directory
from stemwise_engine.lyrics_align_pc import _run_future_alignment_attempt
from stemwise_engine.lyrics_alignment import AlignmentWorkspace


SCRATCH_ROOT = WORKSPACE / "Test suite" / "work"


class FutureAlignmentTerminalStateTest(unittest.TestCase):
    def test_cached_endpoints_retire_resident_model_before_publication_and_next_song(self):
        from contextlib import ExitStack
        from tempfile import TemporaryDirectory
        from unittest.mock import MagicMock, Mock
        from subprocess import CompletedProcess
        from test_lyrics_alignment import accepted_result, plain_original, transcript, mms_timeline
        from test_alignment_batch_publication import install_worker_sources
        from test_alignment_window_synchronization import retained_state
        from process_fixture import prepare_runtime_layout
        from stemwise_engine import lyrics_align_pc as align, alignment_audio
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.runtime_environment import environment_python
        with TemporaryDirectory(dir=SCRATCH_ROOT) as name, ExitStack() as stack:
            root = Path(name)
            project = root/'pc/builder'
            prepare_runtime_layout(project)
            (project/'src').mkdir(parents=True)
            install_worker_sources(project)
            for dependency in (environment_python('main', project),
                    project.parent/'model-cache/torch/hub/checkpoints/model.pt',
                    project.parent/'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th'):
                dependency.parent.mkdir(parents=True, exist_ok=True)
                dependency.write_bytes(b'inert fixture dependency')
            video = 'KbKqkR3Q6SA'
            text = 'alpha beta gamma delta\none two three four'
            original = {**plain_original(text), 'source': {'name': 'Known fixture'}}
            raw = json.dumps({'status':'partial', 'reason':'Fixture timing pending', 'tracks':[original]}).encode()
            sidecars, output = root/'owner', root/'runtime'
            sidecars.mkdir()
            output.mkdir()
            sidecar = sidecars/f'{video}.lyrics.json'
            sidecar.write_bytes(raw)
            package = output/'children'/f'{video}.stemwise.zip'
            package.parent.mkdir()
            package.write_bytes(b'prior complete audio package')
            audio = root/'audio.wav'
            audio.write_bytes(b'bound synthetic numerical input')
            workspace = AlignmentWorkspace(root/'alignment', workspace_root=root)
            retained_state(workspace, video, original, raw, (0, 1))
            evidence = workspace.job_dir(video)/'native-asr-fixture-mix.private.json'
            for language in align.transcription_languages(original):
                workspace.transcript_path(video,language,'unprompted-mix').write_bytes(evidence.read_bytes())
            review = workspace.job_dir(video)/'review.private.json'
            review.write_bytes(b'prior approved review')
            config = {'projectDir':str(project), 'tempDir':str(root/'temp'), 'analysisTimeoutMinutes':1}
            args = SimpleNamespace(device='cpu', compute_type='float32', from_position=None, to_position=None,
                realign_synced=True, model='large-v3', rerun_transcript=False, no_commit=False,
                expected_mix_sha256='a'*64, analysis_audio=audio)
            identity = {'sha256':'a'*64, 'byteCount':audio.stat().st_size, 'stream':{
                'sampleRate':48000, 'channelCount':2, 'frameCount':480000, 'durationMs':10000}}
            identity['canonicalTimeTransform'] = alignment_audio.identity_transform(identity['stream'])
            provenance = {'mix':identity, 'audioViews':{'htdemucs-vocals':identity}}
            real_owner = align.models.WhisperModelOwner
            owners = []
            def make_owner(**options):
                owner = real_owner(**options)
                owner.model = Mock()
                owner.lease = MagicMock()
                if owners: owner.model.close.side_effect = OSError('native unload remains unconfirmed')
                owners.append(owner)
                return owner
            def recognize(**options):
                options['get_model']()  # A real resident owner remains live on the warm endpoint path.
                return transcript(text), {'type':'original-mix'}, False
            numerical_calls = []
            def numerical(**options):
                if len(numerical_calls) >= 2:
                    self.fail('Exact endpoint caches must not invoke another numerical worker')
                numerical_calls.append(options['helper'].name)
                request = options['request']
                lines = []
                for i, line in enumerate(request['lines']):
                    start = 1000+i*2000
                    words = [{'wordId':word['wordId'], 'startMs':start+j*200, 'endMs':start+j*200+180,
                        'confidence':.9, 'acousticTokenCount':1, 'romanizedWordCount':1}
                        for j,word in enumerate(line['words'])]
                    lines.append({'lineIndex':i, 'sourcePhysicalIndex':line['sourcePhysicalIndex'],
                        'lineId':line['lineId'], 'startMs':start, 'endMs':words[-1]['endMs'],
                        'confidence':.9, 'minimumWordConfidence':.9, 'displayWordCount':len(words),
                        'acousticTokenCount':len(words), 'romanizedWordCount':len(words), 'words':words})
                wav = options['helper'].name.startswith('wav2vec2')
                result = {'formatVersion':4 if wav else 5,
                    'engine':'wav2vec2-ctc-forced-alignment' if wav else 'torchaudio-mms-fa',
                    **mms_timeline(10000), 'segmentCount':1, 'segmentBoundaryGuardMs':250,
                    'displayWordCount':8, 'acousticTokenCount':8, 'romanizedWordCount':8, 'lineCount':2,
                    'confidence':{key:.9 for key in ('wordP10','wordP50','wordP90','lineP10','lineP50','lineP90')},
                    'lines':lines}
                if wav:
                    result['requestFormatVersion'] = align.MMS_REQUEST_VERSION
                    result.pop('romanizedWordCount')
                    for line in lines:
                        line.pop('romanizedWordCount')
                        for word in line['words']: word.pop('romanizedWordCount')
                options['output_path'].write_bytes(json.dumps(result).encode())
                return CompletedProcess([],0,'',''), request, None
            stack.enter_context(patch.object(align, '_verify_alignment_runtime', return_value={}))
            stack.enter_context(patch.object(align, '_verify_pinned_stack', return_value={}))
            stack.enter_context(patch.object(align, '_whisper_snapshot_path', return_value=root/'model'))
            stack.enter_context(patch.object(align.models, 'WhisperModelOwner', side_effect=make_owner))
            stack.enter_context(patch.object(align.mms_asr, 'adapters_for', return_value=()))
            stack.enter_context(patch.object(align, 'validate_child_zip', return_value={'analysis':{'durationMs':10000}}))
            stack.enter_context(patch.object(align, '_load_caption', return_value=None))
            stack.enter_context(patch.object(align, '_load_or_transcribe', side_effect=recognize))
            stack.enter_context(patch.object(align, 'align_known_structure', return_value=accepted_result((0,1),starts=(1000,3000))))
            stack.enter_context(patch.object(align, '_needs_additional_structural_evidence', return_value=False))
            stack.enter_context(patch.object(align, '_load_reviewed_lrc_starts', return_value={}))
            stack.enter_context(patch.object(align, '_build_initial_alignment_segments', return_value=[{
                'segmentIndex':0,'startLineIndex':0,'endLineIndex':2,'windowStartMs':0,'windowEndMs':10000}]))
            stack.enter_context(patch.object(align, '_wav2vec2_model_assets', return_value=(root/'model',{
                'modelSha256':'b'*64,'snapshotManifestSha256':'c'*64})))
            stack.enter_context(patch.object(align, '_wav2vec2_runtime_provenance', return_value={}))
            stack.enter_context(patch.object(align, '_mms_runtime_provenance', return_value={}))
            stack.enter_context(patch.object(align, '_forced_alignment_provenance', side_effect=lambda **kw:{'audio':kw['audio_identity']}))
            stack.enter_context(patch.object(alignment_audio, 'write_audio_provenance', return_value=provenance))
            stack.enter_context(patch.object(alignment_audio, 'read_audio_provenance', return_value=provenance))
            stack.enter_context(patch.object(alignment_audio, 'file_identity', return_value=identity))
            stack.enter_context(patch.object(alignment_audio.AlignmentAudioViews, 'get_endpoint_audio', return_value=(audio,identity,{})))
            stack.enter_context(patch.object(align, '_run_alignment_worker_batch', side_effect=numerical))
            call = lambda selected: align._align(config,args,selected_override=selected,
                paths_override=(sidecars,output,root/'cache',workspace),temp_root_override=root/'attempt')
            prime = call([{'position':1,'videoId':video}])
            self.assertEqual(prime['counts'].get('committed'),1,(prime,
                [(path.name,path.read_text(encoding='utf-8')) for path in workspace.job_dir(video).glob('*failure*.txt')]))
            cached = {path:path.read_bytes() for path in workspace.job_dir(video).glob('*public-endpoints*.private.json')}
            self.assertEqual(len(cached),2)
            report = workspace.reports/'latest.json'
            before = {path:path.read_bytes() for path in (sidecar,package,review,report,
                workspace.candidate_path(video),workspace.mapping_path(video),workspace.current_backup_path(video))}
            fatal = WorkerRetirementError('resident Whisper retirement failed')
            with patch.object(align.models.os, '_exit', side_effect=fatal), \
                    patch.object(align.AlignmentPublisher, 'publish', wraps=align.AlignmentPublisher().publish) as publisher, \
                    patch.object(align, '_prepare_alignment_text', wraps=align._prepare_alignment_text) as prepare:
                with self.assertRaises(WorkerRetirementError) as raised:
                    call([{'position':1,'videoId':video},{'position':2,'videoId':'abcdefghijk'}])
            self.assertIs(raised.exception,fatal)
            publisher.assert_not_called()
            self.assertEqual(prepare.call_count,1)
            self.assertEqual(len(numerical_calls),2)
            self.assertEqual({path:path.read_bytes() for path in cached},cached)
            self.assertEqual({path:path.read_bytes() for path in before},before)
            self.assertIsNotNone(owners[-1].model)
            owners[-1].lease.__exit__.assert_not_called()
            self.assertFalse(workspace.state_path('abcdefghijk').exists())

    def test_cancelled_session_with_failed_retirement_preserves_outer_owned_scratch(self):
        from tempfile import TemporaryDirectory
        from stemwise_engine import alignment_worker_session as sessions
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        for primary in (ProviderCancelled('cancelled'), ProviderDeadline('parent deadline')):
            with self.subTest(primary=type(primary).__name__), TemporaryDirectory(dir=SCRATCH_ROOT) as name:
                root = Path(name)
                fatal = WorkerRetirementError('worker still owns its input')
                native_failure = OSError('native retirement refused')
                def fail_close():
                    try:
                        raise native_failure
                    except OSError as error:
                        raise fatal from error
                with patch.object(sessions._Session, 'close', side_effect=fail_close):
                    with self.assertRaises(WorkerRetirementError) as raised:
                        with manifested_temporary_directory(root, prefix='owned-', owner='test-session',
                                workspace_root=root) as scratch:
                            (scratch/'input').write_bytes(b'retained worker input')
                            with sessions.alignment_worker_session():
                                raise primary
                self.assertIs(raised.exception, fatal)
                self.assertIs(raised.exception.__cause__, native_failure)
                self.assertIs(native_failure.__context__, primary)
                self.assertEqual((scratch/'input').read_bytes(), b'retained worker input')

    def test_separation_cleanup_retirement_is_not_masked_by_next_cleanup_error(self):
        from unittest.mock import Mock
        from stemwise_engine.alignment_audio import AlignmentAudioViews
        from stemwise_engine.common import WorkerRetirementError
        fatal = WorkerRetirementError('native consumer not retired')
        views = object.__new__(AlignmentAudioViews)
        views.closed = False
        views.release_model = Mock()
        views.roformer_temporary = Mock()
        views.roformer_temporary.cleanup.side_effect = fatal
        views.htdemucs_temporary = Mock()
        views.htdemucs_temporary.cleanup.side_effect = OSError('later cleanup failed')
        with self.assertRaises(WorkerRetirementError) as raised:
            views.close()
        self.assertIs(raised.exception, fatal)
        views.htdemucs_temporary.cleanup.assert_called_once_with(preserve=True)

    def test_parent_stop_survives_actual_alignment_cli_and_second_worker_hop(self):
        import subprocess
        from tempfile import TemporaryDirectory
        from stemwise_engine import worker_protocol
        from stemwise_engine.lyrics_align_pc import _run_conditional_cascade
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        child = '''import sys
from unittest.mock import patch
from stemwise_engine import lyrics_align_pc
from stemwise_engine import provider_operations as providers
with patch.object(lyrics_align_pc, 'load_config', side_effect=getattr(providers, sys.argv[1])('private original stop')):
    raise SystemExit(lyrics_align_pc.main(['--config', sys.argv[2], 'align', '--device', 'cpu']))
'''
        relay = '''import subprocess, sys
from pathlib import Path
from stemwise_engine.worker_protocol import require_worker_success, run_worker_main
def main():
    completed = subprocess.run([sys.executable, '-B', '-c', sys.argv[1], sys.argv[2], sys.argv[3]],
        capture_output=True, text=True, timeout=15)
    require_worker_success(completed, code='alignment-child-failed', diagnostic_path=Path(sys.argv[4]))
    Path(sys.argv[5]).write_bytes(b'forbidden relay continuation')
    return 0
raise SystemExit(run_worker_main(main))
'''
        for failure in (ProviderCancelled, ProviderDeadline):
            with self.subTest(failure=failure.__name__), TemporaryDirectory(dir=SCRATCH_ROOT) as name:
                root = Path(name)
                continued = root/'continued'
                completed = subprocess.run([sys.executable, '-B', '-c', relay, child, failure.__name__,
                    str(root/'config.json'), str(root/'relay.failure.private.txt'), str(continued)],
                    capture_output=True, text=True, timeout=30)
                calls = []
                def evaluate(route):
                    calls.append(route)
                    if route == 'first':
                        worker_protocol.require_worker_success(completed, code='relay-failed',
                            diagnostic_path=root/'parent.failure.private.txt')
                    return True, route
                with self.assertRaises(failure):
                    _run_conditional_cascade(('first', 'forbidden-fallback'), evaluate)
                self.assertEqual(calls, ['first'])
                self.assertFalse(continued.exists())
                from test_future_song import workflow_fixture
                from stemwise_engine.runtime_environment import environment_python
                workflow = workflow_fixture(root/'workflow')
                project = Path(workflow.config['projectDir'])
                for required in (environment_python('lyrics', project), project/'src/lyrics_align_pc.py',
                                 workflow.runtime/'children/abcdefghijk.stemwise.zip'):
                    required.parent.mkdir(parents=True, exist_ok=True)
                    required.write_bytes(b'inert fixture')
                with patch.object(workflow, '_alignment_worker_environment', return_value={}), \
                        patch.object(workflow, '_run_primary_worker', return_value=completed):
                    with self.assertRaises(failure):
                        workflow._align_future_lyrics('abcdefghijk', 'a'*32, 'b'*64, 'c'*64, 'd'*64,
                                                     root/'source.wav')

    def test_parent_stop_while_native_route_waits_in_queue_stops_fallback_and_next_song(self):
        from contextlib import ExitStack
        from tempfile import TemporaryDirectory
        from unittest.mock import Mock
        from stemwise_engine import lyrics_align_pc as align, analysis_queue
        from stemwise_engine.common import exclusive_process_lock, heavy_worker_lock_path
        from stemwise_engine.provider_operations import provider_operation, ProviderCancelled, ProviderDeadline
        for cancelled in (False, True):
            with self.subTest(cancelled=cancelled), TemporaryDirectory(dir=SCRATCH_ROOT) as name, ExitStack() as stack:
                root = Path(name)
                project = root/'PC side'/'builder'
                project.mkdir(parents=True)
                source = root/'source.wav'
                source.write_bytes(b'retained source')
                workspace = AlignmentWorkspace(root/'alignment', workspace_root=root)
                checks, elapsed, stopped = [0], [0], [False]
                budget = stack.enter_context(provider_operation(1, clock=lambda: elapsed[0], cancelled=lambda: stopped[0]))
                backend = Mock(side_effect=AssertionError('queued work must not be admitted'))
                def wait_for_queue(*args, **kwargs):
                    def check_parent():
                        checks[0] += 1
                        if checks[0] >= 2:
                            stopped[0], elapsed[0] = cancelled, 2
                        budget.remaining()
                        return False
                    return analysis_queue.analyze(source, 1000, project, 30, backend, cancelled=check_parent)
                original = {'id': 'original', 'kind': 'original', 'format': 'plain',
                    'language': 'en', 'script': 'Latn', 'text': 'hello world'}
                prepared = SimpleNamespace(lyrics={'tracks': [original]}, original=original,
                    original_raw=b'{}', expected_current_raw=b'{}', canonical_plain_sha256='c'*64)
                preparation = stack.enter_context(patch.object(align, '_prepare_alignment_text', return_value=prepared))
                stack.enter_context(patch.object(align, 'validate_child_zip', return_value={'analysis': {'durationMs': 1000}}))
                stack.enter_context(patch.object(align, '_verify_alignment_runtime', return_value={}))
                stack.enter_context(patch.object(align, '_verify_pinned_stack', return_value={}))
                stack.enter_context(patch.object(align, '_whisper_snapshot_path', return_value=root/'model'))
                stack.enter_context(patch.object(align.models, 'WhisperModelOwner'))
                stack.enter_context(patch.object(align.mms_asr, 'adapters_for', return_value=('fixture',)))
                native = stack.enter_context(patch.object(align.mms_asr, 'runtime_provenance', side_effect=wait_for_queue))
                fallback = stack.enter_context(patch.object(align, '_load_or_transcribe'))
                publish = stack.enter_context(patch.object(align.AlignmentPublisher, 'publish'))
                stack.enter_context(exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(project)})))
                args = SimpleNamespace(device='cpu', compute_type='float32', from_position=None,
                    to_position=None, realign_synced=False, model='large-v3', rerun_transcript=False,
                    expected_mix_sha256='a'*64)
                with self.assertRaises(ProviderCancelled if cancelled else ProviderDeadline):
                    align._align({'projectDir': str(project), 'analysisTimeoutMinutes': 1}, args,
                        selected_override=[{'position': 1, 'videoId': '-Abc_123XYZ'},
                                           {'position': 2, 'videoId': 'abcdefghijk'}],
                        paths_override=(root/'sidecars', root/'output', root/'cache', workspace),
                        temp_root_override=root/'temporary')
                self.assertEqual(checks[0], 2)
                preparation.assert_called_once()
                native.assert_called_once()
                fallback.assert_not_called()
                backend.assert_not_called()
                publish.assert_not_called()
                self.assertEqual(source.read_bytes(), b'retained source')
                self.assertFalse(list((project/'runtime/acoustic-queue').glob('*/request.json')))

    def test_serialized_fatal_first_song_stops_loop_with_and_without_output(self):
        from stemwise_engine import lyrics_align_pc as align, worker_protocol
        from stemwise_engine.common import WorkerRetirementError
        from subprocess import CompletedProcess
        from tempfile import TemporaryDirectory
        for intact in (False, True):
            with self.subTest(intact=intact), TemporaryDirectory(dir=SCRATCH_ROOT) as name:
                root = Path(name)
                workspace = AlignmentWorkspace(root/'alignment', workspace_root=root)
                output = root/'worker-output.json'
                if intact:
                    output.write_bytes(b'{"valid":"retained"}')
                completed = CompletedProcess([], 70, '',
                    'STEMWISE_WORKER_FAILURE={"formatVersion":1,"code":"alignment-model-retirement-failed"}\n')
                def prepare(**kwargs):
                    worker_protocol.raise_worker_failure('worker failed', completed, output)
                args = SimpleNamespace(device='cpu', compute_type='float32', from_position=None,
                    to_position=None, realign_synced=False, model='large-v3')
                with patch.object(align, '_prepare_alignment_text', side_effect=prepare) as preparation, \
                        patch.object(align.AlignmentPublisher, 'publish') as publish:
                    with self.assertRaises(WorkerRetirementError):
                        align._align({}, args,
                            selected_override=[{'position': 1, 'videoId': '-Abc_123XYZ'},
                                               {'position': 2, 'videoId': 'abcdefghijk'}],
                            paths_override=(root/'sidecars', root/'output', root/'cache', workspace),
                            temp_root_override=root/'temporary')
                preparation.assert_called_once()
                publish.assert_not_called()
                self.assertFalse(workspace.state_path('abcdefghijk').exists())
                self.assertEqual(output.exists(), intact)

    def test_audio_cleanup_retains_scratch_when_model_retirement_fails(self):
        from stemwise_engine.alignment_audio import AlignmentAudioViews
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled
        from unittest.mock import Mock
        for pending in (None, ProviderCancelled('cancelled'), RuntimeError('ordinary route failure')):
            with self.subTest(pending=type(pending).__name__):
                fatal = WorkerRetirementError('model survived retirement')
                views = object.__new__(AlignmentAudioViews)
                views.closed = False
                views.release_model = Mock(side_effect=fatal)
                views.roformer_temporary = Mock()
                views.htdemucs_temporary = Mock()
                with self.assertRaises(WorkerRetirementError) as raised:
                    try:
                        if pending is not None:
                            raise pending
                    finally:
                        views.close()
                self.assertIs(raised.exception, fatal)
                views.roformer_temporary.cleanup.assert_called_once_with(preserve=True)
                views.htdemucs_temporary.cleanup.assert_called_once_with(preserve=True)

    def test_parent_stop_survives_session_cleanup_failure(self):
        from stemwise_engine import alignment_worker_session as sessions
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        for failure in (ProviderCancelled('cancelled'), ProviderDeadline('parent deadline')):
            with self.subTest(failure=type(failure).__name__), \
                    patch.object(sessions._Session, 'close', side_effect=OSError('cleanup failed')):
                with self.assertRaises(type(failure)) as raised:
                    with sessions.alignment_worker_session():
                        raise failure
                self.assertIs(raised.exception, failure)
                self.assertIsNone(sessions.active_alignment_session())

    def test_parent_stop_survives_manifested_scratch_cleanup_failure(self):
        from stemwise_engine import common
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        from tempfile import TemporaryDirectory
        for failure in (ProviderCancelled('cancelled'), ProviderDeadline('parent deadline')):
            with self.subTest(failure=type(failure).__name__), TemporaryDirectory(dir=SCRATCH_ROOT) as name:
                root = Path(name)
                with patch.object(common, 'remove_manifested_directory', side_effect=OSError('cleanup failed')):
                    with self.assertRaises(type(failure)) as raised:
                        with manifested_temporary_directory(root, prefix='cancelled-', owner={'kind': 'test'},
                                                           workspace_root=root):
                            raise failure
                self.assertIs(raised.exception, failure)

    def test_parent_stop_during_first_song_never_prepares_next_or_publishes(self):
        from stemwise_engine import lyrics_align_pc as align
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline, ProviderStageDeadline
        from tempfile import TemporaryDirectory
        for failure in (ProviderCancelled('cancelled'), ProviderDeadline('parent deadline'),
                        ProviderStageDeadline('route deadline')):
            with self.subTest(failure=type(failure).__name__), TemporaryDirectory(dir=SCRATCH_ROOT) as name:
                root = Path(name)
                workspace = AlignmentWorkspace(root/'alignment', workspace_root=root)
                args = SimpleNamespace(device='cpu', compute_type='float32', from_position=None,
                    to_position=None, realign_synced=False, model='large-v3')
                with patch.object(align, '_prepare_alignment_text', side_effect=failure) as prepare, \
                        patch.object(align.AlignmentPublisher, 'publish') as publish:
                    call = lambda: align._align({}, args,
                        selected_override=[{'position': 1, 'videoId': '-Abc_123XYZ'},
                                           {'position': 2, 'videoId': 'abcdefghijk'}],
                        paths_override=(root/'sidecars', root/'output', root/'cache', workspace),
                        temp_root_override=root/'temporary')
                    if isinstance(failure, ProviderStageDeadline):
                        result = call()
                        self.assertEqual(result['counts']['failed'], 2)
                        self.assertEqual(prepare.call_count, 2)
                    else:
                        with self.assertRaises(type(failure)) as raised:
                            call()
                        self.assertIs(raised.exception, failure)
                        prepare.assert_called_once()
                        self.assertFalse(workspace.state_path('abcdefghijk').exists())
                    publish.assert_not_called()

    def test_preloop_failure_is_persisted_as_retryable_technical(self) -> None:
        video_id = "-Abc_123XYZ"
        attempt_id = uuid.uuid4().hex
        owner = {"kind": "terminal-state-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="future-align-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            future_root = scratch / "future-songs"
            workspace = AlignmentWorkspace(future_root, workspace_root=WORKSPACE)
            future_paths = (
                future_root / "owner-input",
                future_root / "runtime",
                scratch / "model-cache" / "lyrics-alignment",
                workspace,
            )
            unsafe_error = type("Runtime Error/unsafe", (RuntimeError,), {})
            with patch(
                'stemwise_engine.lyrics_align_pc._align', side_effect=unsafe_error("private path")
            ):
                with self.assertRaises(unsafe_error):
                    _run_future_alignment_attempt(
                        {"analysisTimeoutMinutes": 1},
                        Namespace(model="large-v3", canonical_plain_sha256="c" * 64),
                        video_id=video_id,
                        attempt_id=attempt_id,
                        workspace_root=WORKSPACE,
                        generated_root=(
                            scratch / "temporary" / "lyrics-alignment" / "future"
                        ),
                        future_workspace=workspace,
                        future_paths=future_paths,
                    )

            state = json.loads(
                workspace.state_path(video_id).read_text(encoding="utf-8")
            )
            self.assertEqual(state["attemptId"], attempt_id)
            self.assertEqual(state["canonicalPlainSha256"], "c" * 64)
            self.assertEqual(state["status"], "retryable-technical")
            self.assertEqual(state["reasons"], ["Runtime-Error-unsafe"])
            self.assertNotIn("private path", json.dumps(state))


if __name__ == "__main__":
    unittest.main()
