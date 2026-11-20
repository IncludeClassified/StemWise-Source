"""Bridge handoff admits only the selected, current audio-bearing public ZIP."""
import contextlib
import io
import json
import unittest
from unittest.mock import patch, Mock

import test_original_delivery as youtube_fixtures
import test_native_delivery as native_fixtures
import stemwise_engine.recording_workflow as recording_workflow
from stemwise_engine.bridge_delivery import describe
from stemwise_engine.common import exclusive_process_lock, ProcessLockBusyError, sha256_file
from stemwise_engine.future_song import FutureSongError, FutureSongWorkflow
from stemwise_engine.workflow_command import main


class BridgeDeliveryTest(unittest.TestCase):
    def test_known_oversize_stops_before_acquisition_and_manual_retry_retains_the_budget(self):
        from stemwise_engine.bridge_delivery import bind_budget, require_workflow_budget
        from stemwise_engine.workflow_records import BridgePackageTooLarge, workflow_failure_details
        from stemwise_engine.source_acquisition import SourceAcquisition
        workflow = self.workflow
        from dataclasses import replace
        native_identity = {'byteCount': self.path.stat().st_size}
        job = {'videoId': 'abcdefghijk', 'state': 'ANALYSIS_FAILED', 'deliveryAllowed': False,
               'error': 'bridge-package-too-large'}
        bind_budget(job, self.path.stat().st_size)
        before = self.path.read_bytes()
        with patch.object(workflow, '_save_job'), patch.object(workflow, '_write_task'), \
                patch.object(workflow, '_song_info', side_effect=AssertionError('acquisition forbidden')), \
                patch.object(workflow, '_pipeline', side_effect=AssertionError('model work forbidden')):
            with self.assertRaises(BridgePackageTooLarge) as error:
                service = workflow.acquisition(job['videoId'])
                SourceAcquisition(replace(service.context, recording_identity_json=json.dumps(native_identity).encode()),
                    service.operations).run(job)
            self.assertEqual(job['state'], 'ANALYSIS_FAILED')
            self.assertEqual(job['error'], 'bridge-package-too-large')
            self.assertIn('ManualImport', workflow_failure_details(error.exception)['nextAction'])
        original_limit = job['bridgeMaxFileBytes']
        with patch.object(workflow, '_load_job', return_value=job), patch.object(workflow, '_save_job'), \
                patch.object(workflow, '_write_task'), patch.object(workflow, 'summary', return_value={}), \
                patch.object(workflow, 'acquisition', return_value=Mock(run=lambda value, **kwargs: require_workflow_budget(value, recording_identity=native_identity))) as acquisition:
            with self.assertRaisesRegex(FutureSongError, 'cannot change'):
                workflow._retry_action('abcdefghijk', max_file_bytes=original_limit+1)
            acquisition.assert_not_called()
            workflow._retry_action('abcdefghijk', manual_import=True)
            self.assertTrue(job['bridgeManualImport'])
            self.assertEqual(job['bridgeMaxFileBytes'], original_limit)
            self.assertEqual(job['bridgeBudgetPreflight']['status'], 'manual-import')
        self.assertEqual(self.path.read_bytes(), before)
        # Manual PC completion never raises the independently supplied transport limit.
        with self.assertRaises(BridgePackageTooLarge) as completed:
            self.describe(original_limit-1)
        action = workflow_failure_details(completed.exception)['nextAction']
        self.assertIn('Ready to Import', action)
        self.assertNotIn('Retry', action)

    def test_unknown_compressed_size_is_not_a_successful_fit(self):
        from stemwise_engine.bridge_delivery import bind_budget, require_workflow_budget
        job = {'videoId': 'abcdefghijk'}
        bind_budget(job, 1024)
        require_workflow_budget(job)
        self.assertEqual(job['bridgeBudgetPreflight']['status'], 'not-rechecked')
        self.assertIsNone(job['bridgeBudgetPreflight']['minimumPackageBytes'])

    def test_standalone_audio_handoff_allows_absent_or_empty_plan(self):
        selection = self.workflow.pc_side_root/'qobuz-comparison/comparison-results/library-files.json'
        before = self.snapshot()
        for plan in ('absent', 'empty'):
            if plan == 'absent':
                selection.unlink()
            else:
                selection.write_text('{"playlist":[]}', encoding='utf-8')
            result = self.describe()
            self.assertEqual(result['recordingKey'], 'youtube:abcdefghijk')
            self.assertEqual(result['package']['sha256'], sha256_file(self.path))
            self.assertEqual(self.snapshot(), before)

    def setUp(self):
        from workflow_fixture import nested_case
        fixture = nested_case(self, youtube_fixtures.OriginalDeliveryTest)
        self.workflow, self.job, self.child, self.path, _ = fixture._delivered()
        ready = fixture.root / 'Ready to Import'
        self.path.parent.rename(ready)
        self.path = ready / self.path.name
        self.workflow.ready = ready
        self.workflow.jobs = fixture.root / 'jobs'
        self.workflow.config_path = fixture.root / 'config.json'
        self.workflow.status = lambda video_id, *, verify_delivery: self.job if verify_delivery else None
        self.job.update(deliveryAllowed=True)
        self.job['delivery']['readyToImport'] = True

    def snapshot(self):
        return self.path.read_bytes(), self.child.read_bytes(), json.dumps(self.job, sort_keys=True)

    def describe(self, limit=1024 ** 3):
        with patch('stemwise_engine.future_song.FutureSongWorkflow', return_value=self.workflow):
            return describe(self.workflow.config_path, 'abcdefghijk', limit)

    def test_exact_audio_bundle_descriptor_and_cli_keep_source_bytes(self):
        before = self.snapshot()
        result = self.describe(self.path.stat().st_size)
        self.assertEqual(result, {'formatVersion': 1, 'videoId': 'abcdefghijk',
            'recordingKey': 'youtube:abcdefghijk', 'package': {
                'path': 'Ready to Import/abcdefghijk.stemwise.zip', 'name': self.path.name,
                'size': self.path.stat().st_size, 'sha256': sha256_file(self.path)}})
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch('stemwise_engine.future_song.FutureSongWorkflow', return_value=self.workflow):
            self.assertEqual(main(['--config', str(self.workflow.config_path), 'bridgedelivery',
                '--video-id=abcdefghijk', '--max-file-bytes', str(1024 ** 3)]), 0)
        self.assertEqual(json.loads(output.getvalue()), result)
        self.assertEqual(self.snapshot(), before)

    def test_one_zip_hash_and_one_bundle_admission_reject_mid_verification_mutation(self):
        import stemwise_engine.bridge_delivery as bridge_delivery
        before = self.snapshot()
        import zipfile
        from pathlib import Path
        hashed_bytes, original_bytes = [], []
        open_path, read_member = Path.open, zipfile.ZipExtFile.read
        @contextlib.contextmanager
        def counted_open(path, *args, **kwargs):
            with open_path(path, *args, **kwargs) as stream:
                if path != self.path:
                    yield stream
                    return
                class Reader:
                    def read(self, size=-1):
                        data = stream.read(size)
                        hashed_bytes.append(len(data))
                        return data
                yield Reader()
        def counted_member(stream, *args, **kwargs):
            data = read_member(stream, *args, **kwargs)
            if stream.name.startswith('original.'):
                original_bytes.append(len(data))
            return data
        with patch('stemwise_engine.bridge_delivery.sha256_file', wraps=sha256_file) as digest, \
                patch('stemwise_engine.bridge_delivery.read_bundle', wraps=bridge_delivery.read_bundle) as bundle, \
                patch.object(Path, 'open', counted_open), patch.object(zipfile.ZipExtFile, 'read', counted_member):
            self.describe()
        self.assertEqual(digest.call_count, 1)
        self.assertEqual(bundle.call_count, 1)
        self.assertEqual(sum(hashed_bytes), self.path.stat().st_size)
        self.assertEqual(sum(original_bytes), self.job['delivery']['original']['byteCount'])
        self.assertEqual(self.snapshot(), before)
        read = bridge_delivery.read_bundle
        def mutate(*args, **kwargs):
            result = read(*args, **kwargs)
            with self.path.open('ab') as stream:
                stream.write(b'changed-during-verification')
            return result
        with patch('stemwise_engine.bridge_delivery.read_bundle', side_effect=mutate):
            with self.assertRaisesRegex(FutureSongError, 'changed during verification'):
                self.describe()
        self.assertEqual(self.child.read_bytes(), before[1])
        self.assertEqual(json.dumps(self.job, sort_keys=True), before[2])

    def test_private_metadata_only_cannot_be_reported_as_a_finished_song(self):
        self.path.write_bytes(self.child.read_bytes())
        self.job['delivery']['sha256'] = sha256_file(self.path)
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, 'exactly one Original'):
            self.describe()
        self.assertEqual(self.snapshot(), before)

    def test_active_owner_is_not_retried_or_modified(self):
        before = self.snapshot()
        with exclusive_process_lock(self.workflow._job_dir('abcdefghijk') / 'workflow.lock'):
            with self.assertRaises(ProcessLockBusyError):
                self.describe()
        self.assertEqual(self.snapshot(), before)

    def test_pending_rejected_changed_and_oversized_outputs_preserve_state(self):
        for field, value in [('state', 'VALIDATED'), ('deliveryAllowed', False)]:
            previous = self.job[field]
            self.job[field] = value
            before = self.snapshot()
            with self.assertRaises(FutureSongError):
                self.describe()
            self.assertEqual(self.snapshot(), before)
            self.job[field] = previous
        for limit in (0, True, self.path.stat().st_size - 1):
            before = self.snapshot()
            with self.assertRaises(FutureSongError):
                self.describe(limit)
            self.assertEqual(self.snapshot(), before)
        self.path.write_bytes(self.path.read_bytes() + b'changed')
        before = self.snapshot()
        with self.assertRaisesRegex(FutureSongError, 'differs from finalization'):
            self.describe()
        self.assertEqual(self.snapshot(), before)

    def test_unavailable_and_changed_selection_do_not_handoff_stale_audio(self):
        selection = self.workflow.pc_side_root / 'qobuz-comparison/comparison-results/library-files.json'
        selection.write_text(json.dumps({'playlist': [{'videoId': 'abcdefghijk', 'source': 'unavailable'}]}), encoding='utf-8')
        before = self.snapshot()
        with self.assertRaisesRegex(FutureSongError, 'recording-not-selected'):
            self.describe()
        self.assertEqual(self.snapshot(), before)
        with patch('stemwise_engine.bridge_delivery.catalogue', side_effect=[
                {'abcdefghijk': 'youtube:abcdefghijk'}, {'abcdefghijk': None}]):
            with self.assertRaisesRegex(FutureSongError, 'changed during verification'):
                self.describe()
        self.assertEqual(self.snapshot(), before)

    def test_selected_native_alias_uses_native_finalization_and_actual_audio(self):
        from workflow_fixture import nested_case
        fixture = nested_case(self, native_fixtures.NativeDeliveryTest)
        workflow = fixture.workflow
        fixture.finalization_fixture()
        with patch.object(workflow, '_validated_snapshot_is_current', return_value=True), \
                patch.object(workflow.report_store, 'commit_finalization_run', return_value=('f' * 32, 'e' * 64)), \
                patch.object(workflow.report_store, 'workflow_operation_bindings', return_value={}), \
                patch.object(workflow.report_store, 'commit_workflow_operation_record'), \
                patch.object(workflow, '_write_task'), \
                patch.object(workflow, '_remove_retained_analysis_audio'):
            workflow.finalize(workflow.video_id)
            selection = fixture.fixture.library / 'comparison-results/library-files.json'
            value = json.loads(selection.read_bytes())
            value['playlist'].append({'videoId': 'lmnopqrstuv', 'source': 'qobuz', 'trackId': 11})
            selection.write_text(json.dumps(value), encoding='utf-8')
            catalogue = fixture.fixture.library / 'catalogue.json'
            value = json.loads(catalogue.read_bytes())
            value['rows'].append({'videoId': 'lmnopqrstuv', 'position': 2, 'candidates': [{'id': 11}]})
            catalogue.write_text(json.dumps(value), encoding='utf-8')
            selected = json.loads(selection.read_bytes())
            selected['catalogueSha256'] = sha256_file(catalogue)
            selection.write_text(json.dumps(selected), encoding='utf-8')
            workflow.finalize(workflow.video_id)
            before = workflow._job_path(workflow.video_id).read_bytes()
            def policy(instance):
                instance.contract_identity = workflow.contract_identity
                instance.policy_sha256 = workflow.policy_sha256
                return workflow.policy
            with patch('stemwise_engine.recording_workflow.RecordingWorkflow', wraps=recording_workflow.RecordingWorkflow) as native, \
                    patch.object(FutureSongWorkflow, '_validate_policy', policy), \
                    patch.object(FutureSongWorkflow, '_validated_snapshot_is_current', return_value=True):
                result = describe(workflow.config_path, 'lmnopqrstuv', 1024 ** 3)
            native.assert_called_once_with(workflow.config_path, 'qobuz:11')
            self.assertEqual(result['recordingKey'], 'qobuz:11')
            self.assertEqual(result['package']['path'], 'Ready to Import/lmnopqrstuv.stemwise.zip')
            path = workflow.workspace_root / result['package']['path']
            self.assertEqual(result['package']['sha256'], sha256_file(path))
            self.assertEqual(workflow._job_path(workflow.video_id).read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
