"""Exercise actual operation execution and verified per-job telemetry readback."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
from stemwise_engine.workflow_context import report_control_path
from stemwise_engine.workflow_records import FutureSongError, engine_source_sha256
from stemwise_engine.workflow_context import telemetry_job_root


class WorkflowOperationsTest(unittest.TestCase):
    def test_report_io_failure_preserves_interrupt_and_generic_failure_state(self):
        import errno
        from stemwise_engine.common import atomic_write_json, read_json
        video, attempt = 'abcdefghijk', 'a'*32
        for primary in (KeyboardInterrupt(), FutureSongError('original validation refusal'),
                RuntimeError('original numerical adapter failure')):
            with self.subTest(primary=type(primary).__name__), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                workflow = self.workflow(Path(name))
                path = workflow._job_path(video)
                atomic_write_json(path, {'videoId': video, 'state': 'ALIGNING_LYRICS',
                    'activeAlignmentAttemptId': attempt})
                workflow._load_job = lambda *_a, **_kw: read_json(path)
                workflow._validated_snapshot_is_current = Mock(return_value=False)
                workflow._write_task = Mock()
                workflow._cleanup_alignment_attempt_temp = Mock(return_value=0)
                workflow._validate_impl = Mock(side_effect=primary)
                report_error = OSError(errno.EIO, 'injected report storage failure')
                workflow._commit_failed_alignment_attempt = Mock(side_effect=report_error)
                retained = workflow._alignment_state_path(video).with_name('retained-numerical.private.json')
                retained.parent.mkdir(parents=True, exist_ok=True)
                retained.write_bytes(b'{"retained":"independent result"}\n')
                retained_before = retained.read_bytes()
                try:
                    workflow._validate_enhancements_action(video)
                except BaseException as error:
                    caught = error
                else:
                    self.fail('failed validation unexpectedly succeeded')
                with self.subTest(boundary='primary-error'):
                    if isinstance(primary, (KeyboardInterrupt, FutureSongError)):
                        self.assertIs(caught, primary)
                    else:
                        self.assertIsInstance(caught, FutureSongError)
                        self.assertIs(caught.__cause__, primary)
                with self.subTest(boundary='durable-failure'):
                    current = read_json(path)
                    self.assertEqual(current['state'], 'VALIDATION_FAILED')
                    self.assertEqual(current['lastAlignmentAttemptId'], attempt)
                    self.assertNotIn('activeAlignmentAttemptId', current)
                self.assertEqual(retained.read_bytes(), retained_before)

    def test_report_io_failure_cannot_mask_parent_stop_or_worker_retirement(self):
        import errno
        from stemwise_engine.common import atomic_write_json, read_json, WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        video = 'abcdefghijk'
        for primary in (ProviderCancelled('parent cancelled'), ProviderDeadline('parent deadline'),
                WorkerRetirementError('worker still owns resources')):
            with self.subTest(primary=type(primary).__name__), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                workflow = self.workflow(Path(name))
                path = workflow._job_path(video)
                atomic_write_json(path, {'videoId': video, 'state': 'ALIGNING_LYRICS',
                    'activeAlignmentAttemptId': 'a'*32})
                workflow._load_job = lambda *_a, **_kw: read_json(path)
                workflow._validated_snapshot_is_current = Mock(return_value=False)
                workflow._write_task = Mock()
                workflow._cleanup_alignment_attempt_temp = Mock(return_value=0)
                workflow._validate_impl = Mock(side_effect=primary)
                workflow._commit_failed_alignment_attempt = Mock(
                    side_effect=OSError(errno.EIO, 'injected report storage failure'))
                try:
                    workflow._validate_enhancements_action(video)
                except BaseException as error:
                    caught = error
                else:
                    self.fail('terminal validation failure unexpectedly succeeded')
                self.assertIs(caught, primary)

    def test_alignment_report_failure_preserves_original_typed_outcome_and_retires_active_state(self):
        import errno
        from stemwise_engine.common import atomic_write_json, read_json
        from stemwise_engine.workflow_records import FutureSongOutcomeError
        video, attempt = 'abcdefghijk', 'a'*32
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workflow = self.workflow(Path(name))
            path = workflow._job_path(video)
            atomic_write_json(path, {'videoId': video, 'state': 'ALIGNING_LYRICS',
                'activeAlignmentAttemptId': attempt})
            workflow._load_job = lambda *_a, **_kw: read_json(path)
            workflow._validated_snapshot_is_current = Mock(return_value=False)
            workflow._write_task = Mock()
            workflow._cleanup_alignment_attempt_temp = Mock(return_value=0)
            primary = FutureSongOutcomeError('RETRYABLE_TECHNICAL', 'retryable-technical')
            original_cause = RuntimeError('retained original worker failure')
            primary.__cause__ = original_cause
            report_error = OSError(errno.EIO, 'injected report storage failure')
            workflow._validate_impl = Mock(side_effect=primary)
            workflow._commit_failed_alignment_attempt = Mock(side_effect=report_error)
            retained = workflow._alignment_state_path(video).with_name('retained-numerical.private.json')
            retained.parent.mkdir(parents=True, exist_ok=True)
            retained.write_bytes(b'{"model":"unchanged valid prior result"}\n')
            before = retained.read_bytes()
            with self.assertRaises(FutureSongOutcomeError) as raised:
                workflow._validate_enhancements_action(video)
            self.assertIs(raised.exception, primary)
            self.assertIs(raised.exception.__cause__, report_error)
            self.assertIs(raised.exception.__cause__.__cause__, original_cause)
            current = read_json(path)
            self.assertEqual(current['state'], 'RETRYABLE_TECHNICAL')
            self.assertEqual(current['outcome'], {'code': 'retryable-technical'})
            self.assertEqual(current['lastAlignmentAttemptId'], attempt)
            self.assertNotIn('activeAlignmentAttemptId', current)
            self.assertEqual(workflow._workflow_operation_failure_code(raised.exception), 'retryable-technical')
            self.assertEqual(retained.read_bytes(), before)

    def test_capability_fallback_cannot_change_the_primary_failure_context_or_public_reason(self):
        from types import SimpleNamespace
        from stemwise_engine.workflow_records import WorkflowInputError
        video = 'abcdefghijk'
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workflow = self.workflow(Path(name))
            workflow._load_job = Mock(return_value={'videoId': video, 'state': 'WAITING_FOR_EDITORIAL'})
            workflow._validated_snapshot_is_current = Mock(return_value=False)
            workflow._completed_analysis_audio = Mock(return_value={'byteCount': 1})
            workflow._pipeline = Mock()
            workflow._song_ledger = Mock(return_value=SimpleNamespace(record={}, save=Mock()))
            primary = FutureSongError('injected primary report failure')
            fallback = WorkflowInputError('incomplete fallback timing', reason_code='line-unresolved',
                affected_input='word timing', next_action='Continue timing recovery.')
            workflow._validate_enhancements_action = Mock(side_effect=primary)
            workflow._validate_available_capabilities = Mock(side_effect=fallback)
            with self.assertRaises(FutureSongError) as raised:
                workflow._validate_action_normal(video)
            self.assertIs(raised.exception, primary)
            self.assertIsNot(raised.exception.__context__, fallback)
            self.assertEqual(workflow._workflow_operation_failure_code(raised.exception), 'future-song-error')

    def test_report_failure_never_downgrades_an_unretired_worker(self):
        from stemwise_engine.common import atomic_write_json, read_json, WorkerRetirementError
        from stemwise_engine.workflow_records import FutureSongOutcomeError
        video = 'abcdefghijk'
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workflow = self.workflow(Path(name))
            path = workflow._job_path(video)
            job = {'videoId': video, 'state': 'ALIGNING_LYRICS', 'activeAlignmentAttemptId': 'a'*32}
            atomic_write_json(path, job)
            workflow._load_job = lambda *_a, **_kw: read_json(path)
            workflow._validated_snapshot_is_current = Mock(return_value=False)
            workflow._validate_impl = Mock(side_effect=FutureSongOutcomeError('RETRYABLE_TECHNICAL', 'retryable-technical'))
            fatal = WorkerRetirementError('worker still owns resources')
            report_error = ValueError('report unavailable')
            report_error.__cause__ = fatal
            workflow._commit_failed_alignment_attempt = Mock(side_effect=report_error)
            with self.assertRaises(WorkerRetirementError) as raised:
                workflow._validate_enhancements_action(video)
            self.assertIs(raised.exception, fatal)
            self.assertEqual(read_json(path), job)

    def test_blocked_alignment_can_refresh_recording_evidence_without_restarting_alignment(self):
        from stemwise_engine.common import atomic_write_json, read_json
        video = 'abcdefghijk'
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workflow = self.workflow(Path(name))
            path = workflow._job_path(video)
            workflow._require_selected_audio = Mock()
            workflow._load_job = lambda *_a, **_kw: read_json(path)
            callback = Mock(return_value={'status': 'comparison-complete'})
            workflow._run_recording_evidence = callback
            pending = {'videoId': video, 'state': 'LINE_UNRESOLVED', 'outcome': {'code': 'line-unresolved'},
                'recordingContentEvidence': {'retained': 'unchanged'}, 'analysis': {'retained': 'unchanged'}}
            atomic_write_json(path, pending)
            self.assertEqual(workflow.recording_evidence(video), {'status': 'comparison-complete'})
            callback.assert_called_once_with(video)
            self.assertEqual(read_json(path), pending)
            callback.reset_mock()
            for state, recording in (('ALIGNING_LYRICS', None), ('VALIDATED', None), ('DELIVERED', None),
                    ('LINE_UNRESOLVED', {'status': 'running'})):
                with self.subTest(state=state, recording=recording):
                    current = {**pending, 'state': state}
                    if recording is not None:
                        current['recordingEvidence'] = recording
                    atomic_write_json(path, current)
                    before = path.read_bytes()
                    with self.assertRaisesRegex(FutureSongError, 'not permitted'):
                        workflow.recording_evidence(video)
                    callback.assert_not_called()
                    self.assertEqual(path.read_bytes(), before)

    def test_ordinary_validation_refresh_transition_uses_the_retained_owner_without_a_live_display_pointer(self):
        import copy
        from stemwise_engine.common import atomic_write_json, read_json
        from stemwise_engine.workflow_states import save_job
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            path = Path(name)/'job.json'
            validation = {'runId': 'a'*32, 'runSha256': 'b'*64,
                'displayReviewAttemptId': 'a'*32, 'displayReviewDeclarationSha256': 'c'*64,
                'displayOriginalSha256': 'd'*64}
            previous = {'state': 'DELIVERED', 'validation': validation,
                'delivery': {'sha256': 'e'*64, 'original': {'sha256': 'f'*64}}}
            for changed in (None, 'owner', 'delivery'):
                with self.subTest(changed=changed):
                    atomic_write_json(path, previous)
                    before = path.read_bytes()
                    renewed = {**copy.deepcopy(previous), 'state': 'VALIDATED',
                        'validation': {'kind': 'capability-completion'},
                        'capabilityRefresh': {'previousValidation': copy.deepcopy(validation)}}
                    if changed == 'owner':
                        renewed['capabilityRefresh']['previousValidation']['runSha256'] = '0'*64
                    if changed == 'delivery':
                        renewed['delivery']['original']['sha256'] = '0'*64
                    if changed is not None:
                        with self.assertRaises(ValueError):
                            save_job(path, renewed)
                        self.assertEqual(path.read_bytes(), before)
                    else:
                        save_job(path, renewed)
                        self.assertEqual(read_json(path)['state'], 'VALIDATED')

    def test_damaged_provider_diagnostic_preserves_original_error_and_failed_outcome(self):
        import io
        from contextlib import redirect_stderr
        from test_private_diagnostics import damaged_diagnostic_target
        from stemwise_engine.provider_operations import ProviderError, ProviderCancelled
        for kind in ('mismatch', 'directory'):
            for error in (ProviderError('DownloadError'), ProviderCancelled('cancelled')):
                with self.subTest(kind=kind, error=type(error).__name__), \
                        tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                    workflow = self.workflow(Path(name))
                    stderr = io.StringIO()
                    def fail():
                        raise error
                    with damaged_diagnostic_target(kind) as targets, redirect_stderr(stderr), \
                            self.assertRaises(type(error)) as raised:
                        workflow._run_mutating_operation('abcdefghijk', 'Source', fail)
                    self.assertIs(raised.exception, error)
                    operation = workflow.report_store.latest_workflow_operation('abcdefghijk')
                    self.assertEqual((operation['phase'], operation['status']), ('outcome', 'failed'))
                    self.assertEqual(operation['outcomeCode'], workflow._workflow_operation_failure_code(error))
                    self.assertIn('provider-private-diagnostic-retention-failed', stderr.getvalue())
                    self.assertEqual(len(targets), 1)

    def test_repair_classification_is_bound_to_producer_instead_of_recovery_wording(self):
        from stemwise_engine.workflow_records import workflow_failure_details, WorkflowInputError, ArtworkUnavailableError
        for text in ('Native PCM recovery input changed', 'Committed source crop differs; preserve it for recovery',
                'LRC coverage is incomplete', 'Retry requires a failed recoverable stage'):
            self.assertNotEqual(workflow_failure_details(FutureSongError(text))['reasonCode'], 'artwork-unavailable')
        details = []
        for text in ('recovery failed', 'different human wording with cover and artwork'):
            error = WorkflowInputError(text, reason_code='retryable-technical',
                affected_input='retained analysis PCM', next_action='Resolve exact source binding before Retry.')
            details.append(workflow_failure_details(error))
        self.assertEqual(details[0], details[1])
        self.assertEqual(workflow_failure_details(ArtworkUnavailableError('different wording'))['reasonCode'],
            'artwork-unavailable')

    def test_every_job_state_has_admission_and_active_stages_have_recovery(self):
        from stemwise_engine.workflow_states import JobState, JobProgress, JobStage, StageOutcome
        active = {JobStage.ANALYSIS, JobStage.RECORDING_EVIDENCE, JobStage.ALIGNMENT}
        for state in JobState:
            progress = JobProgress.current({'state': state.value})
            self.assertIsInstance(progress.rule.actions, frozenset)
            if progress.outcome == StageOutcome.ACTIVE:
                self.assertIn(progress.stage, active)
                self.assertIsNotNone(progress.rule.interrupted_state)
        recognition = JobProgress.current({'state': 'RETRYABLE_TECHNICAL',
            'outcome': {'code': 'retryable-technical', 'sourceStage': 'recording-evidence'}})
        self.assertEqual(recognition.rule.retry_action, 'RecordingEvidence')
        self.assertEqual(JobProgress.current({'state': 'RETRYABLE_TECHNICAL'}).rule.retry_action, 'Validate')

    def test_interrupted_recording_stage_recovers_durably_and_invalid_action_preserves_it(self):
        from stemwise_engine.common import atomic_write_json, read_json
        video = 'abcdefghijk'
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workflow = self.workflow(Path(name))
            path = workflow._job_path(video)
            job = {'videoId': video, 'state': 'WAITING_FOR_EDITORIAL',
                   'recordingEvidence': {'status': 'running', 'durationMs': 0}}
            atomic_write_json(path, job)
            workflow._load_job = lambda *_a, **_k: read_json(path)
            workflow._write_task = Mock()
            recovered = workflow._reconcile_interrupted_validation(job)
            self.assertEqual(recovered['state'], 'RETRYABLE_TECHNICAL')
            self.assertEqual(read_json(path)['progress'], {'stage': 'recording-evidence', 'outcome': 'failed'})
            before = path.read_bytes()
            callback = Mock()
            with self.assertRaisesRegex(FutureSongError, 'not permitted'):
                workflow._run_mutating_operation(video, 'Finalize', callback)
            callback.assert_not_called()
            self.assertEqual(path.read_bytes(), before)
            self.assertFalse(list(workflow.reports.rglob('run.json')), 'rejected work must not create a journal')
            # Public Retry already owns the CLI lock; Status must never borrow it.
            atomic_write_json(path, job)
            workflow._run_recording_evidence = lambda selector: workflow._load_job(selector)
            from stemwise_engine.common import exclusive_process_lock
            with exclusive_process_lock(workflow._job_dir(video)/'workflow.lock'):
                retried = workflow.retry(video)
            self.assertEqual(retried['state'], 'RETRYABLE_TECHNICAL')
            self.assertEqual(workflow.report_store.latest_workflow_operation(video)['action'], 'Retry')

    def test_provider_cause_survives_wrapper_in_exact_operation_private_telemetry(self):
        from stemwise_engine.provider_operations import ProviderError, ProviderDeadline
        from yt_dlp.utils import DownloadError
        for deadline in (False, True):
            with self.subTest(deadline=deadline), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                workflow = self.workflow(Path(name))
                cause = ProviderDeadline('provider-operation-deadline') if deadline else ProviderError('DownloadError')
                cause.stderr, cause.provider_stage = 'RuntimeError: exact private provider cause', '--youtube'
                def fail():
                    if deadline: raise cause
                    raise DownloadError('provider-DownloadError') from cause
                with self.assertRaises(ProviderDeadline if deadline else DownloadError):
                    workflow._run_mutating_operation('abcdefghijk', 'Source', fail)
                operation = workflow.report_store.latest_workflow_operation('abcdefghijk')
                self.assertEqual(operation['status'], 'failed')
                self.assertNotIn('exact private', json.dumps(operation))
                reference, = operation['privateDiagnostics']
                path = workflow.report_store.report_root('abcdefghijk')/reference['file']
                payload = path.read_bytes()
                self.assertEqual(len(payload), reference['byteCount'])
                self.assertEqual(hashlib.sha256(payload).hexdigest(), reference['sha256'])
                self.assertIn(b'exact private provider cause', payload)
                self.assertIn(operation['operationId'].encode(), payload)
                self.assertIn(b'--youtube', payload)
                self.assertEqual(operation['failureDetails']['affectedInput'],
                    'current provider operation' if deadline else 'provider response (DownloadError)')

    def test_every_fixed_endpoint_refusal_round_trips_through_actual_operation_and_status(self):
        from stemwise_engine.workflow_records import EndpointReviewSelectionError
        video = 'abcdefghijk'
        for code in ('endpoint-review-singing-selection-invalid', 'endpoint-review-ctc-evidence-abstained',
                     'endpoint-review-audio-evidence-invalid'):
            with self.subTest(code=code), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
                workflow = self.workflow(Path(name))
                workflow._status_summary = lambda job, **kwargs: dict(job)
                workflow._reconcile_interrupted_validation = lambda job: job
                workflow._load_job = lambda *_a, **_kw: {'videoId': video, 'state': 'NEW'}
                def reject():
                    raise EndpointReviewSelectionError(code)
                with self.assertRaises(EndpointReviewSelectionError):
                    workflow._run_mutating_operation(video, 'Validate', reject)
                operation = workflow.status(video)['latestOperation']
                self.assertEqual(operation['outcomeCode'], code)
                self.assertEqual(operation['failureDetails']['reasonCode'], code)
                self.assertTrue(operation['failureDetails']['nextAction'])

    def test_provider_context_cannot_replace_an_interrupt_or_unrelated_failure(self):
        from stemwise_engine.provider_operations import ProviderError
        for interrupted in (False, True):
            with self.subTest(interrupted=interrupted), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                workflow = self.workflow(Path(name))
                def fail():
                    try:
                        raise ProviderError('DownloadError')
                    except ProviderError:
                        if interrupted: raise KeyboardInterrupt()
                        raise KeyError('unrelated local defect')
                with self.assertRaises(KeyboardInterrupt if interrupted else KeyError):
                    workflow._run_mutating_operation('abcdefghijk', 'Source', fail)
                operation = workflow.report_store.latest_workflow_operation('abcdefghijk')
                self.assertEqual(operation['outcomeCode'], 'keyboard-interrupt' if interrupted else 'unexpected-error')
                self.assertNotIn('privateDiagnostics', operation)

    def test_canonical_job_placement_rejects_foreign_selectors_and_preserves_old_records(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            workspace = Path(directory)
            self.assertEqual(telemetry_job_root(workspace, 'youtube:-Abc_123XYZ'),
                             workspace / 'Engine telemetry/youtube--Abc_123XYZ')
            self.assertEqual(telemetry_job_root(workspace, 'qobuz:11'),
                             workspace / 'Engine telemetry/qobuz-11')
            for key in ('qobuz:011', 'qobuz:0', 'youtube:../outside', 'youtube:short', '../qobuz:11'):
                with self.subTest(key=key), self.assertRaises(ValueError):
                    telemetry_job_root(workspace, key)
            workflow = self.workflow(workspace)
            video_id = 'a1B2c3D4e5F'
            old = workspace / 'PC side/reports' / video_id / 'retained.json'
            old.parent.mkdir(parents=True)
            old.write_bytes(b'{"retained":"useful prior record"}\n')
            before = old.read_bytes()
            workflow._run_mutating_operation(video_id, 'Source', lambda: None)
            self.assertEqual(workflow.report_store.report_root(video_id),
                             telemetry_job_root(workspace, f'youtube:{video_id}') / 'reports')
            self.assertEqual(workflow.report_store.latest_workflow_operation(video_id)['phase'], 'outcome')
            self.assertEqual(old.read_bytes(), before)

    def workflow(self, root):
        from stemwise_engine.future_song import FutureSongWorkflow
        from workflow_fixture import initialized_workflow
        workflow = initialized_workflow(root)
        workflow._require_platform_content_allowed = Mock()
        return workflow

    def test_launch_token_is_the_actual_start_and_outcome_id_and_status_verifies_readback(self):
        video_id, token = 'a1B2c3D4e5F', 'b' * 32
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            workflow = self.workflow(Path(directory))
            job = {'videoId': video_id, 'state': 'NEW'}
            workflow._job_dir(video_id).mkdir(parents=True)
            workflow._job_path(video_id).write_text(json.dumps(job), encoding='utf-8')
            workflow._load_job = lambda *_a, **_kw: job
            workflow._reconcile_interrupted_validation = lambda value: value
            def summary(value, *, verify_delivery=True):
                self.assertFalse(verify_delivery)
                return dict(value)
            workflow._status_summary = summary

            def callback():
                start = workflow.status(video_id)['latestOperation']
                self.assertEqual((start['operationId'], start['phase'], start['status']),
                                 (token, 'start', 'started'))
                return 'completed'

            with patch.dict(os.environ, {'STEMWISE_LAUNCH_TOKEN': token}):
                self.assertEqual(workflow._run_mutating_operation(video_id, 'Source', callback), 'completed')
            status = workflow.status(video_id)
            operation = status['latestOperation']
            self.assertEqual(operation['bindings']['engineSourceSha256'],
                             engine_source_sha256(ROOT / 'PC side/stemwise-playlist-builder/src'))
            self.assertEqual((operation['operationId'], operation['phase'], operation['status']),
                             (token, 'outcome', 'complete'))
            report_root = workflow.report_store.report_root(video_id)
            pointer_path = report_control_path(report_root, 'current-operation.json')
            pointer = json.loads(pointer_path.read_bytes())
            run_path = report_root / 'runs' / pointer['runId'] / 'run.json'
            original = run_path.read_bytes()
            self.assertEqual(hashlib.sha256(original).hexdigest(), operation['runSha256'])
            for damage in ('changed-record', 'wrong-hash', 'bad-pointer', 'wrong-video'):
                with self.subTest(damage=damage):
                    run_path.write_bytes(original)
                    changed_pointer = dict(pointer)
                    if damage == 'changed-record':
                        changed = json.loads(original)
                        changed['operationId'] = 'c' * 32
                        run_path.write_text(json.dumps(changed), encoding='utf-8')
                    elif damage == 'wrong-hash':
                        changed_pointer['runSha256'] = 'd' * 64
                    elif damage == 'bad-pointer':
                        changed_pointer['runId'] = '../other'
                    else:
                        changed = json.loads(original)
                        changed['videoId'] = 'b1B2c3D4e5F'
                        payload = json.dumps(changed).encode('utf-8')
                        run_path.write_bytes(payload)
                        changed_pointer['runSha256'] = hashlib.sha256(payload).hexdigest()
                    pointer_path.write_text(json.dumps(changed_pointer), encoding='utf-8')
                    failed_status = workflow.status(video_id)
                    self.assertNotIn('latestOperation', failed_status)
                    self.assertEqual(failed_status['operationReportError'], 'workflow-operation-record-invalid')

    def test_direct_operations_generate_distinct_ids_and_invalid_token_cannot_replace_pointer(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            workflow = self.workflow(Path(directory))
            video_id = 'a1B2c3D4e5F'
            with patch.dict(os.environ, {}, clear=True):
                self.assertIsNone(workflow.report_store.latest_workflow_operation(video_id))
                workflow._run_mutating_operation(video_id, 'Source', lambda: None)
                first = workflow.report_store.latest_workflow_operation(video_id)['operationId']
                workflow._run_mutating_operation(video_id, 'Artwork', lambda: None)
                second = workflow.report_store.latest_workflow_operation(video_id)['operationId']
            self.assertRegex(first, r'^[0-9a-f]{32}$')
            self.assertNotEqual(first, second)
            before = {path.relative_to(directory): path.read_bytes()
                      for path in Path(directory).rglob('*') if path.is_file()}
            callback = Mock()
            for token in ('', 'A' * 32, 'a' * 31, '../operation', 'a' * 32 + '\n'):
                with self.subTest(token=token), patch.dict(os.environ, {'STEMWISE_LAUNCH_TOKEN': token}):
                    with self.assertRaises(FutureSongError):
                        workflow._run_mutating_operation(video_id, 'Source', callback)
            callback.assert_not_called()
            self.assertEqual({path.relative_to(directory): path.read_bytes()
                              for path in Path(directory).rglob('*') if path.is_file()}, before)


if __name__ == '__main__':
    unittest.main()
