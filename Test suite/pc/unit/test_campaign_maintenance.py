"""Reset changes only selected public aliases and never consumes retained work."""
from pathlib import Path
from unittest.mock import patch
import contextlib
import hashlib
import io
import json
import tempfile
import unittest

from stemwise_engine import campaign_maintenance as maintenance
from stemwise_engine.common import ProcessLockBusyError, exclusive_process_lock


ROOT = Path(__file__).resolve().parents[3]
A, B, C = 'abcdefghijk', 'lmnopqrstuv', '0123456789_'


class CampaignMaintenanceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='maintenance-', dir=ROOT / 'Test suite/work')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.builder = self.root / 'PC side/stemwise-playlist-builder'
        self.library = self.root / 'PC side/qobuz-comparison'
        self.ready = self.root / 'Ready to Import'
        self.builder.mkdir(parents=True)
        self.ready.mkdir()
        self.config = self.builder / 'config.json'
        self.config.write_text(json.dumps({'playlistUrl': 'https://example.invalid/playlist',
            'generatorVersion': '2.1.0', 'outputDirWindows': '../generated-packages',
            'pcSideRootWindows': '..', 'futureRootWindows': 'future-songs',
            'futureReadyDirWindows': '../../Ready to Import'}), encoding='utf-8')
        self.library.mkdir()
        self.catalogue = self.library / 'catalogue.json'
        self.catalogue.write_text('{"rows": []}', encoding='utf-8')
        self.projection = self.library / 'comparison-results/library-files.json'
        self.projection.parent.mkdir()
        self.projection.write_text(json.dumps({'catalogueSha256': hashlib.sha256(self.catalogue.read_bytes()).hexdigest(),
            'playlist': [{'videoId': A, 'source': 'qobuz', 'trackId': 12},
                {'videoId': B, 'source': 'qobuz', 'trackId': 12}]}), encoding='utf-8')
        self.packages = {identity: self.ready / f'{identity}.stemwise.zip' for identity in (A, B, C)}
        for identity, path in self.packages.items():
            path.write_bytes(identity.encode())
        self.retained = self.builder / 'future-songs/owner-input/original.txt'
        self.retained.parent.mkdir(parents=True)
        self.retained.write_bytes(b'approved words, still require their exact recording review')

    def apply_reset(self, **scope):
        preview = maintenance.reset(self.config, **scope)
        return maintenance.reset(self.config, apply=True, preview_token=preview['previewToken'], **scope)

    def test_changed_preview_selection_or_replaced_output_is_rejected_before_removal(self):
        preview = maintenance.reset(self.config, all_selected=True)
        self.packages[B].write_bytes(b'replacement package after preview')
        with self.assertRaisesRegex(ValueError, 'preview changed'):
            maintenance.reset(self.config, apply=True, all_selected=True, preview_token=preview['previewToken'])
        self.assertTrue(all(path.is_file() for path in self.packages.values()))
        preview = maintenance.reset(self.config, all_selected=True)
        projection = json.loads(self.projection.read_text(encoding='utf-8'))
        projection['playlist'][1]['trackId'] = 13
        self.projection.write_text(json.dumps(projection), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'preview changed'):
            maintenance.reset(self.config, apply=True, all_selected=True, preview_token=preview['previewToken'])
        self.assertTrue(all(path.is_file() for path in self.packages.values()))

    def test_preview_has_no_output_or_input_mutations_and_counts_aliases_once(self):
        before = {path: path.read_bytes() for path in (*self.packages.values(), self.retained)}
        result = maintenance.reset(self.config)
        self.assertFalse(result['applied'])
        self.assertTrue(result['canApply'])
        self.assertEqual(result['selectedPlaylistEntries'], 2)
        self.assertEqual(result['distinctRecordings'], 1)
        self.assertEqual(result['publicPackages'], [self.packages[A].name, self.packages[B].name])
        self.assertEqual(result['removedPackages'], [])
        self.assertEqual({path: path.read_bytes() for path in before}, before)

    def test_apply_removes_only_selected_aliases_preserves_inputs_and_is_idempotent(self):
        retained = self.retained.read_bytes(), self.packages[C].read_bytes()
        result = self.apply_reset(all_selected=True)
        self.assertEqual(result['remainingCampaignPackages'], 0)
        self.assertTrue(result['applied'])
        self.assertFalse(result['processingStarted'])
        self.assertFalse(self.packages[A].exists())
        self.assertFalse(self.packages[B].exists())
        self.assertEqual((self.retained.read_bytes(), self.packages[C].read_bytes()), retained)
        self.assertEqual(self.apply_reset(all_selected=True)['removedPackages'], [])

    def test_exact_video_scope_preserves_the_other_alias(self):
        result = self.apply_reset(video_id=A)
        self.assertEqual(result['removedPackages'], [self.packages[A].name])
        self.assertEqual(result['remainingCampaignPackages'], 1)
        self.assertTrue(self.packages[B].is_file())
        self.assertTrue(self.packages[C].is_file())

    def test_apply_requires_an_exact_selector_or_explicit_all(self):
        with self.assertRaisesRegex(ValueError, 'explicit.*all'):
            maintenance.reset(self.config, apply=True)
        self.assertTrue(all(path.is_file() for path in self.packages.values()))
        with self.assertRaises(ValueError):
            maintenance.reset(self.config, apply=True, video_id=A, all_selected=True)


    def test_apply_requires_the_exact_preview_before_removing_any_package(self):
        maintenance.reset(self.config, all_selected=True)
        self.packages[B].write_bytes(b'replacement package after preview')
        with self.assertRaisesRegex(ValueError, 'preview'):
            maintenance.reset(self.config, apply=True, all_selected=True)
        self.assertTrue(all(path.is_file() for path in self.packages.values()))

    def test_registered_standalone_song_is_not_part_of_default_campaign_reset(self):
        registration = self.library / 'audio/qobuz/99/enrichment-input.json'
        registration.parent.mkdir(parents=True)
        registration.write_text(json.dumps({'formatVersion': 1, 'trackId': 99,
            'referenceVideoId': C, 'title': 'Separate song', 'artist': 'Owner', 'album': '',
            'file': 'audio/qobuz/99/retained.flac'}), encoding='utf-8')
        result = self.apply_reset(all_selected=True)
        self.assertTrue(self.packages[C].is_file())
        self.assertEqual(result['selectedPlaylistEntries'], 2)
        self.assertEqual(result['distinctRecordings'], 1)

    def test_stale_selection_fails_before_any_removal(self):
        self.catalogue.write_bytes(b'{"rows": [], "changed": true}')
        with self.assertRaisesRegex(ValueError, 'stale'):
            self.apply_reset(all_selected=True)
        self.assertTrue(all(path.is_file() for path in self.packages.values()))

    def test_live_model_lease_refuses_reset_without_touching_outputs(self):
        with exclusive_process_lock(self.builder / 'runtime/model-operations/1.lock'):
            with self.assertRaises(ProcessLockBusyError):
                self.apply_reset(all_selected=True)
        self.assertTrue(all(path.is_file() for path in self.packages.values()))

    def test_current_accepted_or_completed_job_is_preserved_and_blocks_apply(self):
        directory = self.builder / f'future-songs/policy-state/current-qobuz-12-{"a" * 64}/jobs/{A}'
        directory.mkdir(parents=True)
        job = directory / 'job.json'
        for state in ('ANALYZING', 'DELIVERED'):
            with self.subTest(state=state):
                job.write_text(json.dumps({'videoId': A, 'state': state,
                    'remoteOperation': {'id': 'accepted-id', 'knowledge': 'unknown'}}), encoding='utf-8')
                before = job.read_bytes()
                preview = maintenance.reset(self.config)
                self.assertFalse(preview['canApply'])
                self.assertEqual(preview['retainedCurrentJobs'][0]['state'], state)
                with self.assertRaisesRegex(ValueError, 'current jobs are preserved'):
                    self.apply_reset(all_selected=True)
                self.assertEqual(job.read_bytes(), before)
                self.assertTrue(self.packages[A].is_file())

    def test_partial_current_workspace_and_live_workflow_owner_refuse_reset(self):
        directory = self.builder / f'future-songs/policy-state/current/jobs/{A}'
        directory.mkdir(parents=True)
        draft = directory / 'draft.txt'
        draft.write_bytes(b'recover this interrupted request')
        self.assertFalse(maintenance.reset(self.config)['canApply'])
        with self.assertRaisesRegex(ValueError, 'current jobs are preserved'):
            self.apply_reset(all_selected=True)
        with exclusive_process_lock(directory / 'workflow.lock'):
            with self.assertRaises(ProcessLockBusyError):
                self.apply_reset(all_selected=True)
        self.assertEqual(draft.read_bytes(), b'recover this interrupted request')

    def test_symlink_output_cannot_escape_the_selected_root(self):
        self.packages[A].unlink()
        self.packages[A].symlink_to(self.retained)
        with self.assertRaises(ValueError):
            self.apply_reset(all_selected=True)
        self.assertTrue(self.retained.is_file())
        self.assertTrue(self.packages[B].is_file())

    def test_interrupted_removal_is_explicit_and_next_apply_finishes_only_remaining_aliases(self):
        unlink = Path.unlink
        def fail_second(path, *args, **kwargs):
            if path == self.packages[B]:
                raise OSError('injected reset interruption')
            return unlink(path, *args, **kwargs)
        with patch.object(Path, 'unlink', fail_second), self.assertRaisesRegex(OSError, 'injected'):
            self.apply_reset(all_selected=True)
        self.assertFalse(self.packages[A].exists())
        self.assertTrue(self.packages[B].is_file())
        result = self.apply_reset(all_selected=True)
        self.assertEqual(result['removedPackages'], [self.packages[B].name])
        self.assertTrue(self.packages[C].is_file())
        self.assertTrue(self.retained.is_file())

    def test_cli_failure_does_not_claim_that_no_mutations_could_have_occurred(self):
        output = io.StringIO()
        with patch.object(maintenance, 'reset', side_effect=OSError('interrupted')), contextlib.redirect_stderr(output):
            code = maintenance.main(['--config', str(self.config), '--apply'])
        result = json.loads(output.getvalue())
        self.assertEqual(code, 2)
        self.assertFalse(result['completed'])
        self.assertTrue(result['mayHaveRemovedOutputs'])

    def test_public_reprocess_constructs_a_real_workflow_and_never_resubmits_an_active_stage(self):
        from workflow_fixture import initialized_workflow
        from stemwise_engine import workflow_command
        from stemwise_engine.future_song import FutureSongWorkflow
        workflow = initialized_workflow(self.root)
        job = workflow._base_job(C, f'https://youtu.be/{C}')
        workflow._save_job(job)
        job.update(state='ANALYZING', remoteOperation={'id': 'retain-this-operation', 'knowledge': 'unknown'})
        workflow._save_job(job)
        stdout, stderr = io.StringIO(), io.StringIO()
        with (patch.object(FutureSongWorkflow, 'acquisition', side_effect=AssertionError('must not submit')),
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr)):
            code = workflow_command.main(['--config', str(self.config), 'reprocess', f'--video-id={C}'])
        self.assertEqual(code, 0, stderr.getvalue())
        self.assertEqual(json.loads(stdout.getvalue())['reprocessAction'], 'status')
        self.assertEqual(workflow._load_job(C)['remoteOperation'], job['remoteOperation'])

    def test_public_reprocess_new_keeps_actual_duplicate_admission_and_model_lease(self):
        from workflow_fixture import initialized_workflow
        from stemwise_engine import workflow_command
        from stemwise_engine.future_song import FutureSongWorkflow
        initialized_workflow(self.root)
        calls = []
        def acquired(workflow, url, *, max_file_bytes=None):
            calls.append(url)
            job = workflow._base_job(C, url)
            workflow._save_job(job)
            job['state'] = 'ANALYZING'
            workflow._save_job(job)
            return workflow.summary(job, verify_delivery=False)
        with patch.object(FutureSongWorkflow, '_new_action', acquired):
            for expected in ('new', 'status'):
                stdout, stderr = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    code = workflow_command.main(['--config', str(self.config), 'reprocess', f'--video-id={C}'])
                self.assertEqual(code, 0, stderr.getvalue())
                self.assertEqual(json.loads(stdout.getvalue())['reprocessAction'], expected)
        self.assertEqual(calls, [f'https://www.youtube.com/watch?v={C}'])

    def test_reprocess_cannot_enter_an_owned_workflow(self):
        from workflow_fixture import initialized_workflow
        from stemwise_engine import workflow_command
        workflow = initialized_workflow(self.root)
        stdout, stderr = io.StringIO(), io.StringIO()
        with (exclusive_process_lock(workflow._job_dir(C) / 'workflow.lock'),
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr)):
            code = workflow_command.main(['--config', str(self.config), 'reprocess', f'--video-id={C}'])
        self.assertNotEqual(code, 0)
        self.assertFalse(workflow._job_path(C).exists())

    def test_blocked_content_reprocess_returns_task_without_retrying_or_changing_words(self):
        from workflow_fixture import initialized_workflow
        from stemwise_engine import workflow_command
        from stemwise_engine.future_song import FutureSongWorkflow
        workflow = initialized_workflow(self.root)
        job = workflow._base_job(C, f'https://youtu.be/{C}')
        workflow._save_job(job)
        job.update(state='NEEDS_LYRICS', outcome={'code': 'lyrics-content-unresolved'})
        workflow._save_job(job)
        before = workflow._job_path(C).read_bytes()
        stdout, stderr = io.StringIO(), io.StringIO()
        with (patch.object(FutureSongWorkflow, 'retry', side_effect=AssertionError('must not retry content')),
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr)):
            code = workflow_command.main(['--config', str(self.config), 'reprocess', f'--video-id={C}'])
        self.assertEqual(code, 0, stderr.getvalue())
        self.assertEqual(json.loads(stdout.getvalue())['reprocessAction'], 'task')
        self.assertEqual(workflow._job_path(C).read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
