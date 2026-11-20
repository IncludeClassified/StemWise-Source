"""Current PC format and numerical boundary regressions; no model imports."""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import json
import time

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.common as common
import installed_contract
import stemwise_engine.song_enrichment_v2 as song
import stemwise_engine.waveform as waveform
from test_song_enrichment_v2 import valid_document
from test_contract import minimal_document
from unittest.mock import patch


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class CurrentFormatBoundaries(unittest.TestCase):
    def test_pipeline_fixture_restores_launcher_runtime_before_next_module(self):
        import os
        script = '''import os, sys, types, unittest
from pathlib import Path
sys.path.insert(0, sys.argv[1])
from unit.workflow_fixture import isolate_pipeline_runtime
from unit.test_packages import package_config
from stemwise_engine.pipeline import StemwisePipeline
inherited = os.environ['STEMWISE_ALLIN1_PYTHON']
root = Path(sys.argv[2])
isolated, failed, normal = (types.ModuleType(name) for name in ('isolated', 'failed', 'normal'))
isolated.setUpModule = isolate_pipeline_runtime
def failed_setup():
    isolate_pipeline_runtime()
    raise RuntimeError('deliberate setup failure after environment isolation')
failed.setUpModule = failed_setup
class Isolated(unittest.TestCase):
    def test_pipeline(self):
        self.assertEqual(os.environ['STEMWISE_ALLIN1_PYTHON'], '')
        config = package_config(root/'isolated')
        owner = StemwisePipeline(config)
        self.assertTrue(owner.allin1_python.resolve().is_relative_to(owner.workspace_root))
class Normal(unittest.TestCase):
    def test_foreign_runtime(self):
        self.assertEqual(os.environ['STEMWISE_ALLIN1_PYTHON'], inherited)
        config = package_config(root/'normal')
        with self.assertRaisesRegex(ValueError, 'All-In-One runtime must stay inside'):
            StemwisePipeline(config)
class Unreachable(unittest.TestCase):
    def test_not_entered(self):
        self.fail('failed module setup must prevent test execution')
Isolated.__module__, Unreachable.__module__, Normal.__module__ = 'isolated', 'failed', 'normal'
sys.modules.update(isolated=isolated, failed=failed, normal=normal)
suite = unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromTestCase(case)
    for case in (Isolated, Unreachable, Normal)])
result = unittest.TextTestRunner().run(suite)
assert result.testsRun == 2 and not result.failures and len(result.errors) == 1
assert 'deliberate setup failure after environment isolation' in result.errors[0][1]
assert os.environ['STEMWISE_ALLIN1_PYTHON'] == inherited
'''
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            runtime = ROOT/'PC side/stemwise-playlist-builder/.venv-allin1/bin/python'
            result = subprocess.run([sys.executable, '-B', '-c', script,
                str(ROOT/'Test suite/pc'), directory],
                env={**os.environ, 'STEMWISE_ALLIN1_PYTHON': str(runtime)},
                capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_nested_fixture_allocation_failure_restores_actual_acoustic_dependency(self):
        import test_recording_workflow as fixtures
        import stemwise_engine.recording_analysis as recording_analysis
        from workflow_fixture import nested_case
        original = recording_analysis.collect_acoustic_provenance
        owner = unittest.TestCase()
        with patch.object(fixtures.tempfile, 'TemporaryDirectory', side_effect=OSError('allocation failed')):
            with self.assertRaisesRegex(OSError, 'allocation failed'):
                nested_case(owner, fixtures.RecordingWorkflowTest)
        owner.doCleanups()
        self.assertIs(recording_analysis.collect_acoustic_provenance, original)

    def test_editorial_and_capability_identity_ignore_unconsumed_draft_notes(self):
        from workflow_fixture import initialized_workflow
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            workflow = initialized_workflow(Path(directory))
            video = 'a1B2c3D4e5F'
            job_dir = workflow._job_dir(video)
            draft = job_dir/'drafts'
            draft.mkdir(parents=True)
            (draft/'original.txt').write_text('reviewed wording', encoding='utf-8')
            (job_dir/'agent-review.json').write_bytes(b'{}')
            job = workflow._base_job(video, f'https://youtu.be/{video}')
            workflow._save_job(job)
            before = (workflow._editorial_sha(job), workflow._capability_input_sha(video))
            (draft/'notes.md').write_text('unconsumed note', encoding='utf-8')
            self.assertEqual((workflow._editorial_sha(job), workflow._capability_input_sha(video)), before)
            (draft/'original.txt').write_text('changed wording', encoding='utf-8')
            self.assertNotEqual(workflow._editorial_sha(job), before[0])
            self.assertNotEqual(workflow._capability_input_sha(video), before[1])

    def test_feasible_acoustics_above_retired_quotas_and_capacity_rejection(self):
        from stemwise_engine.acoustic_inputs import AcousticBudget, AcousticBudgetExceeded
        with patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=10*1024**3):
            budget = AcousticBudget()
            budget.require(frames=16000001, states=1)
            budget.require(retained_bytes=256*1024**2+1)
            budget.require_recording(4*1024**3+1)
        with patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=100):
            with self.assertRaises(AcousticBudgetExceeded):
                budget.require(frames=10, states=11)

    def test_disposable_operation_reports_cannot_prevent_or_replace_success(self):
        import stemwise_engine.future_song as future
        from workflow_fixture import initialized_workflow
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            workflow = initialized_workflow(Path(directory))
            for failures in ([OSError('start unavailable'), None], [None, OSError('outcome unavailable')]):
                with self.subTest(failures=failures), patch.object(workflow, '_require_platform_content_allowed'), patch.object(
                        workflow.report_store, 'commit_workflow_operation_record', side_effect=failures):
                    published = Path(directory)/'published'
                    def publish():
                        published.write_bytes(b'complete')
                        return 23
                    self.assertEqual(workflow._run_mutating_operation('a1B2c3D4e5F', 'Source', publish), 23)
                    self.assertEqual(published.read_bytes(), b'complete')
            for states in ([OSError('snapshot unavailable')], [None, OSError('readback unavailable')]):
                with patch.object(workflow, '_require_platform_content_allowed'), patch.object(
                        workflow.report_store, 'workflow_operation_state', side_effect=states):
                    self.assertEqual(workflow._run_mutating_operation('a1B2c3D4e5F', 'Source', lambda: 23), 23)

    def test_ordinary_policy_uses_current_inputs_and_binds_production_contract(self):
        from stemwise_engine.future_song import FutureSongWorkflow, FutureSongError
        import shutil
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            workflow = FutureSongWorkflow.__new__(FutureSongWorkflow)
            workflow.workspace_root = root
            workflow.policy_dir = root/'policy'
            workflow.policy_dir.mkdir()
            source = ROOT/'PC side/stemwise-playlist-builder/future-songs/policy/lyrics-policy.json'
            path = workflow.policy_dir/'lyrics-policy.json'
            path.write_bytes(source.read_bytes())
            shutil.copytree(ROOT/'StemWise/contracts', root/'StemWise/contracts')
            workflow.config = {'generatorVersion': '2.1.0'}
            workflow.policy = workflow._validate_policy()
            before = workflow._policy_binding()
            # No evaluation profile or report is needed by ordinary runtime admission.
            self.assertNotIn('qualification', json.loads(path.read_bytes()))
            self.assertEqual(workflow._policy_binding(), before)
            workflow._validate_policy()
            schema = root/'StemWise/contracts/enrichment/song-enrichment-v2.schema.json'
            schema.write_bytes(schema.read_bytes()+b' ')
            with self.assertRaisesRegex(FutureSongError, 'production contract changed'):
                workflow._policy_binding()

    def test_exact_current_work_policy_recovery_preserves_owners_outputs_and_bindings(self):
        from stemwise_engine.future_song import FutureSongError
        from workflow_fixture import initialized_workflow
        import copy
        from stemwise_engine.song_enrichment_v2 import validate_song
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            workflow = initialized_workflow(root)
            current_binding = workflow._policy_binding()
            document = valid_document()
            validate_song(document)
            completed = root/'completed-enrichment.json'
            common.atomic_write_json(completed, document)
            video = document['source']['videoId']
            path = workflow._job_path(video)
            path.parent.mkdir(parents=True)
            policy_path = workflow.policy_dir/'lyrics-policy.json'
            policy = workflow.policy
            for knowledge in ('accepted', 'unknown'):
                job = {'videoId': video, 'policyVersion': policy['policyVersion'], 'state': 'ANALYZING',
                    'policy': current_binding,
                    'remoteOperation': {'id': 'current-owned-operation', 'knowledge': knowledge},
                    'completedOutput': {'path': completed.name, 'sha256': common.sha256_file(completed)}}
                common.atomic_write_json(path, job)
                before = path.read_bytes(), completed.read_bytes()
                self.assertEqual(workflow._load_job(video), job)
                changed = copy.deepcopy(policy)
                changed['delivery']['reviewEvidenceRetained'] = False
                common.atomic_write_json(policy_path, changed)
                self.assertEqual(workflow._load_job(video), job)
                self.assertTrue(workflow._validate_policy()['delivery']['reviewEvidenceRetained'])
                changed['delivery']['reviewEvidenceRetained'] = True
                changed['lyrics']['alignmentVersion'] -= 1
                common.atomic_write_json(policy_path, changed)
                self.assertEqual(workflow._validate_policy(), policy)
                from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION
                with patch('stemwise_engine.lyrics_alignment.ALIGNMENT_VERSION', ALIGNMENT_VERSION+1):
                    with self.assertRaisesRegex(FutureSongError, 'executable policy changed'):
                        workflow._load_job(video)
                common.atomic_write_json(policy_path, policy)
                workflow.policy = workflow._validate_policy()
                self.assertEqual(workflow._load_job(video), job)
                forged = copy.deepcopy(job)
                forged['policy']['schemaSha256'] = '0'*64
                common.atomic_write_json(path, forged)
                with self.assertRaisesRegex(FutureSongError, 'current executable policy'):
                    workflow._load_job(video)
                common.atomic_write_json(path, job)
                self.assertEqual((path.read_bytes(), completed.read_bytes()), before)
            installed = root/'StemWise/contracts/enrichment/installed-enrichment-v2.schema.json'
            installed.write_bytes(installed.read_bytes()+b' ')
            workflow.policy = workflow._validate_policy()
            with self.assertRaisesRegex(FutureSongError, 'current executable policy'):
                workflow._load_job(video)
            self.assertEqual((path.read_bytes(), completed.read_bytes()), before)

    def test_producer_identity_observes_compiled_inputs_and_locks_in_one_process(self):
        import stemwise_engine.lyrics_qualification as qualification
        import stemwise_engine.source_inputs as source_inputs
        from test_lyrics_qualification import QualificationFixture
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            for relative in source_inputs.TREES:
                (root/relative).mkdir(parents=True, exist_ok=True)
            for relative in source_inputs.FILES:
                path = root/relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('{}')
            builder = root/'PC side/stemwise-playlist-builder'
            (builder/'src/lyrics_qualification.py').write_bytes(Path(qualification.__file__).read_bytes())
            (root/'PC side/qobuz-comparison').mkdir()
            for relative in ('future-songs/policy/lyrics-policy.json',
                    'config.json'):
                (builder/relative).write_text('{}')
            schema = root/'StemWise/contracts/enrichment/song-enrichment-v2.schema.json'
            schema.parent.mkdir(parents=True)
            schema.write_text('{}')
            mutations = [root/'StemWise/audio-engine/src/main/cpp/current.c',
                         root/'StemWise/audio-engine/src/main/cpp/CMakeLists.txt',
                         builder/'locks/current.txt']
            for path in mutations:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'original')
            with patch.object(qualification, '__file__', str(builder/'src/lyrics_qualification.py')):
                fixture = QualificationFixture(root/'functional-fixture')
                fixture.build()
                qualification._validate_profile(fixture.profile)
                before = qualification.producer_system_identity()
                for path in mutations:
                    with self.subTest(input=path.name):
                        path.write_bytes(b'changed')
                        self.assertNotEqual(qualification.producer_system_identity(), before)
                        with self.assertRaisesRegex(ValueError, 'producer system differs'):
                            qualification._validate_profile(fixture.profile)
                        path.write_bytes(b'original')

    def test_shared_outer_blank_normalization_preserves_exact_interior_text(self):
        from stemwise_engine.lyrics_normalize import trim_blank_outer_lines
        from stemwise_engine.lyrics_acquire import _normalize_text, LyricsAcquisitionError
        from stemwise_engine.lyrics_intake import _read_text, LyricsInputError
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            path = Path(directory)/'lyrics.txt'
            for text, expected in (('\r\n \rAlpha\r\n\rBeta \r\n\t', 'Alpha\n\nBeta '),
                    ('  Alpha  \n\nBeta\n', '  Alpha  \n\nBeta'), ('\n\t\n', '')):
                self.assertEqual(trim_blank_outer_lines(text), expected)
                path.write_bytes(text.encode())
                if expected:
                    self.assertEqual(_normalize_text(text), expected)
                    self.assertEqual(_read_text(path), expected)
                else:
                    with self.assertRaises(LyricsAcquisitionError):
                        _normalize_text(text)
                    with self.assertRaises(LyricsInputError):
                        _read_text(path)

    def test_registry_rejects_malformed_asset_and_unknown_execution_profile_before_use(self):
        import stemwise_engine.model_registry as registry
        for declaration in ({'MODEL_SHA256': 'x'}, {'file.bin': [1, 'bad']},
                            {'MODEL_BYTE_COUNT': -1}, {'PYTHON_VERSION': 'latest'}):
            with self.assertRaises(ValueError):
                registry.validate_asset_declarations(declaration)
        with self.assertRaisesRegex(ValueError, 'Unsupported current execution profile'):
            registry.runtime_profile('unqualified-device')
        import stemwise_engine.doctor as doctor
        import stemwise_engine.endpoint_candidate_prediction as prediction
        from stemwise_engine.model_registry import model_assets
        self.assertEqual(doctor.EXPECTED_WAV2VEC2_FILES, model_assets('lyrics_align_pc')['PINNED_WAV2VEC2_MODELS']['tr']['files'])
        self.assertEqual(prediction.MODEL_FILES, model_assets('endpoint_candidate_prediction')['MODEL_FILES'])

    def test_new_ignores_obsolete_policy_but_preserves_current_accepted_owner(self):
        from stemwise_engine.future_song import FutureSongWorkflow, FutureSongError
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            workflow = object.__new__(FutureSongWorkflow)
            workflow.source_context = None
            workflow.key = 'unrelated diagnostic key'
            workflow.workspace_root = root
            workflow.install_root = root/'engine'
            workflow.root = workflow.install_root/'policy-state/current'
            workflow.jobs = workflow.root/'jobs'
            workflow._require_selected_audio = lambda _: None
            workflow._run_mutating_operation = Mock(return_value={'started': True})
            prior = workflow.install_root/'policy-state/unsupported/jobs/a1B2c3D4e5F/job.json'
            prior.parent.mkdir(parents=True)
            prior.write_text(json.dumps({'videoId':'a1B2c3D4e5F','policyVersion':'unsupported',
                'remoteOperation':{'id':'accepted-owner-operation'}}),encoding='utf-8')
            before = prior.read_bytes()
            other = workflow.jobs / 'bbbbbbbbbbb/job.json'
            other.parent.mkdir(parents=True)
            other.write_bytes(before)
            self.assertEqual(workflow.new('https://www.youtube.com/watch?v=a1B2c3D4e5F'), {'started': True})
            workflow._run_mutating_operation.reset_mock()
            current = workflow.jobs/'a1B2c3D4e5F/job.json'
            current.write_bytes(prior.read_bytes())
            with self.assertRaisesRegex(FutureSongError, 'current job'):
                workflow.new('https://www.youtube.com/watch?v=a1B2c3D4e5F')
            workflow._run_mutating_operation.assert_not_called()
            self.assertEqual(prior.read_bytes(),before)
            self.assertEqual(current.read_bytes(), before)
            self.assertEqual(other.read_bytes(), before)

    def test_current_endpoint_failure_admission_requires_complete_current_baselines(self):
        from stemwise_engine.endpoint_adjudication import failure_source
        from stemwise_engine.workflow_records import FutureSongError
        from types import SimpleNamespace
        import copy
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            from lyric_content_fixtures import retained_content
            from stemwise_engine.lyrics_alignment import AlignmentWorkspace, snapshot_canonical_plain
            video = 'a1B2c3D4e5F'
            lyrics = {'tracks': [{'kind': 'original', 'format': 'plain', 'language': 'en', 'text': 'hello'}]}
            workspace = AlignmentWorkspace(root, workspace_root=root)
            snapshot_canonical_plain(workspace, video, json.dumps(lyrics).encode('utf-8'))
            canonical = workspace.current_backup_path(video)
            evidence_root = workspace.job_dir(video)
            admission, _ = retained_content(evidence_root, lyrics['tracks'][0], (0,), common.sha256_file(canonical))
            mapping = {'performedOccurrences': [{'occurrenceId': '0', 'sourcePhysicalIndex': 0,
                'words': [{'occurrenceWordId': 'w0'}]}]}
            occurrences = [{'publishDecision': {'result': 'unresolved', 'code': 'line-end-disagreement'}}]
            endpoint = {'resultClass':'line-unresolved','unresolvedLineReasons':{'line-end-disagreement':1}}
            diagnostics = {'resultClass':'line-unresolved','reasons':['line-end-disagreement'],
                'endpointEvidence':endpoint,'selectedEndpointAttemptIndex':0,
                'contentAdmission': admission.to_record(),
                'internalRepeatStructureKind':'none','internalRepeatAuditComplete':True,
                'endpointRouteAttempts':[{'routeId':route+':baseline','endpointEvidence':{**endpoint,'occurrenceCandidates':occurrences}}
                    for route in ('htdemucs-vocals','kimberley-jensen-roformer-vocals','sucial-v2-roformer-dry-vocals')]}
            state = {'status':'line-unresolved','attemptId':'a'*32,'reasons':['line-end-disagreement'],
                'internalRepeatStructureKind':'none','internalRepeatAuditComplete':True}
            path, run_path = evidence_root/'state.json',root/'run.json'
            common.atomic_write_json(path,state)
            common.atomic_write_json(run_path,{'actualImmutableRun':True})
            context = SimpleNamespace(root=root, workspace_root=root, song_policy_matches=lambda _:True,
                lexical_mapping=lambda _: (mapping, {}), pipeline=lambda: None,
                completed_analysis_audio=lambda *_: {'sha256': 'a'*64},
                alignment_state_path=lambda _:path,report_store=SimpleNamespace(report_root=lambda _:root,
                alignment_failure_snapshot=lambda *_:(run_path,{'failureCode':'line-unresolved','summary':{'reasons':state['reasons']}},diagnostics)))
            job = {'videoId':'a1B2c3D4e5F','state':'LINE_UNRESOLVED','outcome':{'code':'line-unresolved'},'lastAlignmentAttemptId':'a'*32}
            admitted = failure_source(context,job,reasons_allowed={'line-end-disagreement'})
            self.assertEqual(admitted['alignmentAttemptId'],'a'*32)
            before = path.read_bytes(),run_path.read_bytes()
            for fault in ('missing-baseline','reordered-baseline','unproven-repeat','technical-failure','changed-attempt'):
                old = copy.deepcopy(diagnostics)
                changed = copy.deepcopy(job)
                if fault == 'missing-baseline':diagnostics['endpointRouteAttempts'].pop()
                elif fault == 'reordered-baseline':diagnostics['endpointRouteAttempts'].reverse()
                elif fault == 'unproven-repeat':diagnostics['internalRepeatAuditComplete'] = False
                elif fault == 'technical-failure':diagnostics['routeFailures'] = [{'code':'failed'}]
                else:changed['lastAlignmentAttemptId'] = 'b'*32
                with self.subTest(fault=fault),self.assertRaises(FutureSongError):
                    failure_source(context,changed,reasons_allowed={'line-end-disagreement'})
                self.assertEqual((path.read_bytes(),run_path.read_bytes()),before)
                diagnostics.clear();diagnostics.update(old)

    def test_job_state_transition_gate_preserves_delivered_and_unknown_jobs(self):
        import stemwise_engine.future_song as future
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            workflow = object.__new__(future.FutureSongWorkflow)
            workflow.workspace_root = Path(directory)
            workflow.jobs = Path(directory)/'jobs'
            path = workflow._job_path('a1B2c3D4e5F')
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps({'videoId': 'a1B2c3D4e5F', 'state': 'DELIVERED'}))
            before = path.read_bytes()
            for state in ('NEW', 'ALIGNING_LYRICS', 'forged-success'):
                with self.assertRaises(ValueError):
                    workflow._save_job({'videoId': 'a1B2c3D4e5F', 'state': state})
                self.assertEqual(path.read_bytes(), before)

    def test_failed_operation_commits_valid_reason_and_preserves_original_exception(self):
        import stemwise_engine.future_song as future
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            from workflow_fixture import initialized_workflow
            workflow = initialized_workflow(Path(directory))
            error = future.SupersededRecordingError('original reason')
            def fail():
                raise error
            with patch.object(workflow, '_require_platform_content_allowed'):
                with self.assertRaises(future.SupersededRecordingError) as caught:
                    workflow._run_mutating_operation('a1B2c3D4e5F', 'Source', fail)
            self.assertIs(caught.exception, error)
            records = [json.loads(path.read_bytes()) for path in
                       workflow.report_store.report_root('a1B2c3D4e5F').glob('runs/*/run.json')]
            self.assertEqual(len(records), 2)
            failed = next(record for record in records if record['phase'] == 'outcome')
            self.assertEqual(failed['outcomeCode'], 'superseded-recording')
            self.assertTrue(future.operation_record_is_safe(failed))
            with patch.object(workflow, '_require_platform_content_allowed'), patch.object(
                    workflow.report_store, 'commit_workflow_operation_record', side_effect=[None, OSError('report failure')]):
                with self.assertRaises(future.SupersededRecordingError) as caught:
                    workflow._run_mutating_operation('a1B2c3D4e5F', 'Source', fail)
            self.assertIs(caught.exception, error)
            self.assertIsNone(caught.exception.__cause__)

    def test_local_http_transfer_deadline_bytes_and_archive_expansion(self):
        import http.server
        import threading
        import zipfile
        from stemwise_media.transfer_budget import TransferBudget, validate_archive
        from stemwise_media.save_download import download_file
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                self.send_response(200)
                if self.path == '/declared-large':
                    self.send_header('Content-Length', '1000')
                self.end_headers()
                try:
                    for _ in range(30):
                        self.wfile.write(b'12345678')
                        self.wfile.flush()
                        if self.path == '/trickle':
                            time.sleep(.01)
                except (BrokenPipeError, ConnectionResetError):
                    pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                root = Path(directory)
                for route in ('declared-large', 'no-length', 'trickle'):
                    target = root/route
                    before = time.monotonic()
                    with self.subTest(route=route), self.assertRaises((ValueError, TimeoutError)):
                        download_file(f'http://127.0.0.1:{server.server_port}/{route}', target,
                            budget=TransferBudget(before+.06, max_bytes=32 if route != 'trickle' else 1000))
                    self.assertLess(time.monotonic()-before, 1)
                    self.assertLessEqual(target.stat().st_size if target.exists() else 0, 1000 if route == 'trickle' else 32)
                path = root/'archive.zip'
                with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                    archive.writestr('one.flac', b'x'*2048)
                with zipfile.ZipFile(path) as archive, self.assertRaisesRegex(ValueError, 'expansion'):
                    validate_archive(archive, TransferBudget(time.monotonic()+1), max_expanded_bytes=1024)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
            self.assertFalse(thread.is_alive())

    def test_completed_pipeline_requires_exact_child_bytes(self):
        from stemwise_engine.pipeline import StemwisePipeline
        from test_packages import package_config, write_child
        from stemwise_engine.packages import read_child, create_child_zip, PackageValidationError
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            config = package_config(root)
            pipeline = StemwisePipeline(config)
            video_id = 'a1B2c3D4e5F'
            child = pipeline.child_path(video_id)
            write_child(child, video_id, config)
            pipeline._ensure_provenance()
            state = {'videoId': video_id, 'status': 'complete', 'generatorVersion': config['generatorVersion'],
                'provenanceSha256': pipeline.provenance_sha256, 'ownerLyricsSha256': 'absent',
                'childSha256': common.sha256_file(child)}
            pipeline.save_checkpoint(state)
            self.assertTrue(pipeline._can_skip({'videoId': video_id}, force=False, lyrics={}, owner_lyrics_sha256='absent'))
            document, cover = read_child(child, config)
            from test_contract import waveform_frames
            document['analysis']['waveform']['levels'][0]['data'] = waveform_frames(20, -101, 101, 50)
            cover_path = root/'cover.png'
            cover_path.write_bytes(cover)
            create_child_zip(child, document, cover_path, config)
            self.assertFalse(pipeline._can_skip({'videoId': video_id}, force=False, lyrics={}, owner_lyrics_sha256='absent'))
            self.assertEqual(pipeline.load_checkpoint(video_id), state)

    def test_budgets_reject_declared_work_before_allocating_and_allow_exact_edge(self):
        from stemwise_engine.acoustic_inputs import AcousticBudget, AcousticBudgetExceeded
        budget = AcousticBudget(available_bytes=2400)
        budget.require(frames=10, states=10, arrays=3)
        for dimensions in ({'frames': 10, 'states': 11, 'arrays': 3},
                           {'frames': 10, 'states': 10, 'arrays': 4}):
            with self.subTest(dimensions=dimensions), self.assertRaises(AcousticBudgetExceeded):
                budget.require(**dimensions)
        budget.require(retained_bytes=0)
        from stemwise_engine.lyrics_tokenization import optimal_edit_owners
        # Matrix workspace and declared owner/row storage must fit before allocation.
        required = 3*3*4*4 + 6*256
        with self.assertRaises(AcousticBudgetExceeded):
            optimal_edit_owners(['alpha', 'beta'], ['alpha', 'gamma'],
                budget=AcousticBudget(available_bytes=required-1))
        owners = optimal_edit_owners(['alpha', 'beta'], ['alpha', 'gamma'],
            budget=AcousticBudget(available_bytes=required))
        self.assertEqual(owners['cost'], 1)
        self.assertEqual([list(matches) for matches in owners['matches']], [[0], []])
        self.assertEqual(owners['substituted'], {1})

    def test_audio_representation_handles_phase_cancellation_and_silent_views(self):
        import numpy as np
        from stemwise_engine.acoustic_inputs import informative_views, mono_samples
        signal = np.sin(np.arange(1000)*.13).astype(np.float32)
        for samples, views in ((np.column_stack((signal, signal)), {'left', 'right', 'mid'}),
                               (np.column_stack((signal, -signal)), {'left', 'right', 'side'}),
                               (np.column_stack((signal, signal*0)), {'left', 'mid', 'side'})):
            self.assertEqual(set(informative_views(samples)), views)
            mono, name = mono_samples(samples)
            self.assertTrue(np.isfinite(mono).all())
            self.assertGreater(float(np.linalg.norm(mono)), 1)
        with self.assertRaises(ValueError):
            informative_views(np.zeros((1000, 2)))

    def test_environment_install_failure_preserves_current_and_removes_staging(self):
        import stemwise_engine.environment_bootstrap as bootstrap
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            builder = root/'PC side/stemwise-playlist-builder'
            current = builder/'.venv'
            current.mkdir(parents=True)
            (builder.parent/'runtime-layout.json').write_bytes((ROOT/'PC side/runtime-layout.json').read_bytes())
            (current/'owner').write_bytes(b'working environment')
            def create(command, **kwargs):
                if 'venv' not in command:
                    return subprocess.CompletedProcess(command, 0, '{}', '')
                staged = Path(command[-1])
                (staged/'bin').mkdir(parents=True)
                (staged/'bin/python').write_bytes(b'replacement')
            def fail(*args, **kwargs):
                self.assertEqual((current/'owner').read_bytes(), b'working environment')
                raise RuntimeError('install failed')
            with patch.object(bootstrap, 'validate_plan'), self.assertRaisesRegex(RuntimeError, 'install failed'):
                bootstrap.replace_environment('main', builder=builder, run=create, installer=fail)
            self.assertEqual((current/'owner').read_bytes(), b'working environment')
            self.assertFalse((builder/'.environment-install-main.json').exists())
            self.assertEqual(list((builder/'.environment-build').iterdir()), [])

    def test_promoted_environment_recovery_after_scratch_cleanup_never_reinstalls(self):
        import stemwise_engine.environment_bootstrap as bootstrap
        class PowerLoss(BaseException):
            pass
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            builder = root/'PC side/stemwise-playlist-builder'
            current = builder/'.venv'
            current.mkdir(parents=True)
            (builder.parent/'runtime-layout.json').write_bytes((ROOT/'PC side/runtime-layout.json').read_bytes())
            (current/'pyvenv.cfg').write_bytes(b'old environment')
            journal = builder/'.environment-install-main.json'
            unlink = Path.unlink
            def interrupted(path, *args, **kwargs):
                if path == journal:
                    raise PowerLoss()
                return unlink(path, *args, **kwargs)
            def create(command, **kwargs):
                if 'venv' not in command:
                    return subprocess.CompletedProcess(command, 0, '{}', '')
                staged = Path(command[-1])
                (staged/'bin').mkdir(parents=True)
                (staged/'bin/python').write_bytes(b'new interpreter')
                (staged/'pyvenv.cfg').write_bytes(b'new environment')
            with patch.object(bootstrap, 'validate_plan'), \
                    patch.object(Path, 'unlink', interrupted), self.assertRaises(PowerLoss):
                bootstrap.replace_environment('main', builder=builder, run=create, installer=lambda *a, **k: None)
            self.assertTrue(journal.exists())
            self.assertFalse(any((builder/'.environment-build').glob('environment-build-*')))
            with patch.object(bootstrap, 'validate_plan'):
                bootstrap.replace_environment('main', builder=builder,
                    run=lambda *a, **k: self.fail('Recovery must not rebuild a promoted environment'),
                    installer=lambda *a, **k: self.fail('Recovery must not reinstall'))
            self.assertEqual((current/'bin/python').read_bytes(), b'new interpreter')
            self.assertFalse(journal.exists())
            self.assertEqual(list((builder/'.environment-build').iterdir()), [])

    def test_worker_json_and_failures_are_bounded_strict_and_private(self):
        import stemwise_engine.worker_protocol as worker
        from types import SimpleNamespace
        for value in ('{"x":1e309}', '{"x":1,"x":2}', '{}'*100):
            with self.assertRaises(worker.WorkerProtocolError):
                worker.parse_worker_json(value, max_bytes=64)
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            diagnostic = Path(directory)/'cause.private.txt'
            with self.assertRaises(worker.WorkerProtocolError) as caught:
                worker.require_worker_success(SimpleNamespace(returncode=1, stderr='private words'),
                    code='worker-failed', diagnostic_path=diagnostic)
            self.assertEqual(str(caught.exception), 'worker-failed')
            self.assertNotIn('private words', str(caught.exception))
            reference = caught.exception.diagnostics[0]
            self.assertEqual((Path(directory)/reference['file']).read_text(), 'private words')

    def test_catalogue_independent_rows_merge_and_conflicts_preserve_owner(self):
        from stemwise_media.catalogue_store import read_catalogue, commit_catalogue
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            path = Path(directory)/'catalogue.json'
            path.write_text(json.dumps({'rows': [{'position': 1}, {'position': 2}]}))
            first, second = read_catalogue(path), read_catalogue(path)
            first['rows'][0]['review'] = 'first'
            second['rows'][1]['review'] = 'second'
            commit_catalogue(path, first)
            committed = commit_catalogue(path, second)
            self.assertEqual([row.get('review') for row in read_catalogue(path)['rows']], ['first', 'second'])
            self.assertEqual(committed, read_catalogue(path))
            self.assertEqual(second, committed)
            self.assertEqual(second.baseline, dict(committed))
            first['rows'][1]['review'] = 'stale'
            raw = path.read_bytes()
            with self.assertRaisesRegex(ValueError, 'concurrently'):
                commit_catalogue(path, first)
            self.assertEqual(path.read_bytes(), raw)

    def test_catalogue_null_metadata_owner_edit_is_not_an_absent_value(self):
        from stemwise_media.catalogue_store import read_catalogue, commit_catalogue
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            path = Path(directory)/'catalogue.json'
            path.write_text(json.dumps({'rows': [{'position': 1}]}))
            first, second = read_catalogue(path), read_catalogue(path)
            first['ownerNote'] = 'proposed'
            second['ownerNote'] = None
            commit_catalogue(path, second)
            before = path.read_bytes()
            with self.assertRaisesRegex(ValueError, 'metadata changed'):
                commit_catalogue(path, first)
            self.assertEqual(path.read_bytes(), before)

    def test_disc_and_track_are_both_required_to_select_an_album_member(self):
        from stemwise_media.save_album import identify
        tracks = [{'id': disc, 'discNumber': disc, 'trackNumber': 1,
                   'title': 'Same', 'artist': 'Singer', 'album': 'Album'} for disc in (1, 2)]
        tags = {'disc': '2/2', 'track': '1/10', 'title': 'Same', 'artist': 'Singer', 'album': 'Album'}
        self.assertEqual(identify(tags, tracks)['id'], 2)
        del tags['disc']
        with self.assertRaises(ValueError):
            identify(tags, tracks)

    def test_model_chunks_cover_exact_pcm_and_never_submit_subframe_tail(self):
        from stemwise_engine.acoustic_inputs import chunk_ranges
        for blocks in (1, 2, 3):
            for tail in (0, 1, 160, 399, 400):
                count = blocks*480000 + tail
                chunks = chunk_ranges(count, 480000, minimum_samples=400)
                self.assertEqual(chunks[0][0], 0)
                self.assertEqual(chunks[-1][1], count)
                self.assertTrue(all(stop-start >= 400 for start, stop in chunks))
                self.assertTrue(all(a[1] == b[0] for a, b in zip(chunks, chunks[1:])))
        with self.assertRaises(ValueError):
            chunk_ranges(399, 480000, minimum_samples=400)

    def test_join_controls_have_narrow_script_context_review(self):
        from stemwise_engine.lyrics_qa import audit_text
        from stemwise_engine.lyrics_tokenization import display_words
        for text, language, script in [('ا\u200cب', 'ur', 'Arab'), ('ا\u200dب', 'ar', 'Arab')]:
            audit = audit_text(text, language, script)
            self.assertNotIn('zero-width-character', [issue['code'] for issue in audit['issues']])
            encoded = text.encode('utf-16-le')
            words = display_words(text)
            self.assertEqual(len(words), 1)
            self.assertEqual(words[0][2], text)
            self.assertEqual(encoded[words[0][0]*2:words[0][1]*2].decode('utf-16-le'), text)
        marked = 'اَ\u200cب'
        self.assertNotIn('zero-width-character', [issue['code'] for issue in audit_text(marked, 'ur', 'Arab')['issues']])
        self.assertEqual(display_words(marked)[0][2], marked)
        for text in ('\u200cاب', 'ا\u200c ', 'a\u200db', 'ا\u200c\u200cب'):
            self.assertIn('zero-width-character', [issue['code'] for issue in audit_text(text, 'ur', 'Arab')['issues']])

    def test_unavailable_analysis_keeps_installed_duration_bound(self):
        document = valid_document()
        line = document['lyrics']['tracks'][0]['lines'][0]
        line['startMs'], line['endMs'] = 2000, 3000
        del line['words']
        for analysis in (None, minimal_document()['analysis']):
            with self.subTest(analysis=analysis):
                if analysis is not None:
                    document['analysis'] = analysis
                with self.assertRaises(ValueError):
                    song.validate_song(document, installed_duration_ms=1000)

    def test_word_starts_are_ordered_but_overlaps_and_equal_starts_are_valid(self):
        document = valid_document()
        words = document['lyrics']['tracks'][0]['lines'][0]['words']
        words[1]['startMs'] = words[0]['startMs']
        song.validate_song(document)
        words[0]['startMs'], words[0]['endMs'] = 700, 800
        with self.assertRaises(ValueError):
            song.validate_song(document)

    def test_strict_readers_reject_nonfinite_depth_and_invalid_scalar_unicode(self):
        for payload in (b'{"x":1e309}', b'{"x":-1e309}', b'{"x":"\\ud800"}',
                        b'{"x":NaN}', b'{"x":1,"x":2}', b'[' * 66 + b'0' + b']' * 66):
            for parser in (common.parse_strict_json_bytes, installed_contract.parse_installed_json):
                with self.subTest(payload=payload[:30], parser=parser.__name__):
                    with self.assertRaises(ValueError):
                        parser(payload)
        self.assertEqual(common.parse_strict_json_bytes(b'{"x":1e308}'), {'x': 1e308})

    def test_wave_container_lengths_and_supported_fmt_are_bounded(self):
        fmt = struct.pack('<HHIIHH', 3, 1, 16000, 64000, 4, 32)
        body = b'WAVEfmt ' + struct.pack('<I', len(fmt)) + fmt + b'data' + struct.pack('<I', 4) + b'\0' * 4
        good = b'RIFF' + struct.pack('<I', len(body)) + body
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            path = Path(directory)/'input.wav'
            for payload in (good + b'extra', good[:4] + struct.pack('<I', len(body)-1) + good[8:],
                            good.replace(b'fmt \x10\0\0\0', b'fmt \xff\xff\xff\x7f')):
                path.write_bytes(payload)
                with self.assertRaises(waveform.WaveformError):
                    waveform._parse_wave(path)
            path.write_bytes(good)
            waveform._parse_wave(path)
