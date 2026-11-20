"""Engine admission protects recordings without depending on the caller's agent."""
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.editorial_review as editorial_review
import stemwise_engine.future_song as future_song
import stemwise_engine.workflow_command as workflow_command
import stemwise_engine.recording_workflow as recording_workflow
from stemwise_engine.common import ProcessLockBusyError, exclusive_process_lock
from workflow_fixture import initialized_workflow


class WorkflowAdmissionTests(unittest.TestCase):
    def test_recording_status_needs_no_agent_registration(self):
        for caller in ('', 'external-tool'):
            for key in ('qobuz:12', 'qobuz:11'):
                with self.subTest(caller=caller, key=key):
                    workflow = Mock(video_id='abcdefghijk')
                    workflow.status.return_value = {'state': 'WAITING_FOR_EDITORIAL'}
                    with patch.dict(os.environ, {'CODEX_THREAD_ID': caller}, clear=True), \
                            patch.object(recording_workflow, 'RecordingWorkflow', return_value=workflow) as constructor, \
                            contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                        result = workflow_command.main(['--config', 'missing.json', '--recording-key', key, 'status'])
                    self.assertEqual(result, 0)
                    constructor.assert_called_once_with(Path('missing.json'), key, verify_media=False)
                    workflow.status.assert_called_once_with('abcdefghijk')

    def test_new_lock_rejects_duplicate_without_changing_current_operation(self):
        work = ROOT / 'Test suite/work'
        work.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='campaign-routing-', dir=work) as directory:
            workflow = initialized_workflow(Path(directory))
            job = workflow._job_dir('abcdefghijk')
            job.mkdir(parents=True)
            current = job / 'current-operation.json'
            current.write_bytes(b'{"operationId":"active-owner"}\n')
            original = current.read_bytes()
            workflow._run_mutating_operation = Mock(
                side_effect=lambda *_args: current.write_bytes(b'{"operationId":"wrong-owner"}\n'))
            with exclusive_process_lock(job / 'workflow.lock'):
                with self.assertRaises(ProcessLockBusyError):
                    workflow.new('https://youtu.be/abcdefghijk')
            workflow._run_mutating_operation.assert_not_called()
            self.assertEqual(current.read_bytes(), original)

    def test_platform_restriction_rejects_mutations_before_any_work_or_journal(self):
        work = ROOT / 'Test suite/work'
        work.mkdir(parents=True, exist_ok=True)
        for restriction in (None, {}, {'code': 'platform-content-restriction'}):
            with self.subTest(restriction=restriction), tempfile.TemporaryDirectory(
                    prefix='platform-restriction-', dir=work) as directory:
                workflow = initialized_workflow(Path(directory))
                job_path = workflow._job_path('abcdefghijk')
                job_path.parent.mkdir(parents=True)
                job = {'videoId': 'abcdefghijk', 'policyVersion': editorial_review.POLICY_VERSION,
                       'state': 'WAITING_FOR_EDITORIAL', 'policy': {},
                       'platformContentRestriction': restriction}
                job_path.write_text(json.dumps(job), encoding='utf-8')
                original = job_path.read_bytes()
                # Coordination exists before admission; a rejected operation must
                # leave no job mutation, journal or publication beyond this lock.
                lock = job_path.parent / 'workflow.lock'
                with exclusive_process_lock(lock):
                    pass
                prior_lock = lock.read_bytes()
                callback = Mock()
                workflow._require_selected_audio = Mock()
                workflow._policy_binding = Mock(return_value={})
                with self.assertRaisesRegex(future_song.FutureSongError, 'platform-content-restriction'):
                    workflow._load_job('abcdefghijk')
                workflow._policy_binding.assert_not_called()
                with self.assertRaisesRegex(future_song.FutureSongError, 'platform-content-restriction'):
                    workflow._run_mutating_operation('abcdefghijk', 'New', callback)
                callback.assert_not_called()
                workflow._require_selected_audio.assert_not_called()
                self.assertEqual(job_path.read_bytes(), original)
                self.assertEqual({p.name for p in job_path.parent.iterdir()}, {'job.json', 'workflow.lock'})
                self.assertEqual(lock.read_bytes(), prior_lock)
                with exclusive_process_lock(lock):
                    pass
                self.assertEqual(workflow._load_job('abcdefghijk', require_active_policy=False), job)

    def test_platform_restriction_cli_rejects_every_mutating_route_before_model_admission(self):
        work = ROOT / 'Test suite/work'
        work.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='platform-routes-', dir=work) as directory:
            root = Path(directory)
            workflow = initialized_workflow(root)
            path = workflow._job_path('abcdefghijk')
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps({'videoId': 'abcdefghijk',
                'policyVersion': editorial_review.POLICY_VERSION,
                'platformContentRestriction': {'code': 'platform-content-restriction'}}), encoding='utf-8')
            original = path.read_bytes()
            before = {entry.relative_to(root): entry.read_bytes() if entry.is_file() else None
                      for entry in root.rglob('*')}
            routes = [['new', '--url', 'https://youtu.be/abcdefghijk']]
            routes += [[name, '--video-id', 'abcdefghijk'] for name in (
                'task', 'validate', 'finalize', 'retry',
                'recordingevidence', 'endpointevidence', 'endpointadjudicate')]
            routes += [[name, '--video-id', 'abcdefghijk', '--url',
                        'https://youtu.be/abcdefghijk'] for name in ('source', 'artwork')]
            for route in routes:
                with self.subTest(route=route), patch.object(workflow_command, 'FutureSongWorkflow',
                        return_value=workflow, wraps=future_song.FutureSongWorkflow), \
                        patch.object(workflow_command, 'model_operation_slot') as slot, \
                        contextlib.redirect_stderr(io.StringIO()) as error:
                    result = workflow_command.main(['--config', 'missing.json', *route])
                    self.assertNotEqual(result, 0)
                    self.assertEqual(json.loads(error.getvalue())['error'], 'platform-content-restriction')
                    slot.assert_not_called()
                    self.assertEqual(path.read_bytes(), original)
                    self.assertEqual({entry.relative_to(root): entry.read_bytes() if entry.is_file() else None
                                      for entry in root.rglob('*')}, before)

    def test_platform_restriction_status_remains_readable_without_review_material(self):
        workflow = future_song.FutureSongWorkflow.__new__(future_song.FutureSongWorkflow)
        job = {'videoId': 'abcdefghijk', 'state': 'WAITING_FOR_EDITORIAL',
               'platformContentRestriction': {'code': 'platform-content-restriction'}}
        workflow._load_job = Mock(return_value=job)
        workflow._reconcile_interrupted_validation = Mock(return_value=job)
        workflow._status_summary = Mock(return_value={'state': 'WAITING_FOR_EDITORIAL',
                                                      'deliveryAllowed': True})
        workflow._review_inputs = Mock()
        result = workflow.status('abcdefghijk')
        self.assertEqual(result['platformContentRestriction'], {'code': 'platform-content-restriction'})
        self.assertFalse(result['deliveryAllowed'])
        workflow._review_inputs.assert_not_called()


if __name__ == '__main__':
    unittest.main()
