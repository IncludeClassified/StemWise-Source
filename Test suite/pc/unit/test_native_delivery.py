"""Finalized native aliases share inference, but never overwrite historical Opus delivery."""
import copy
import hashlib
import json
import os
import shutil
import unittest
from unittest.mock import Mock, patch

import test_recording_workflow as fixtures
from stemwise_engine.common import sha256_file
from stemwise_engine.packages import create_child_zip, read_child
from stemwise_engine.native_delivery import publish, current
from stemwise_engine.original_delivery import read_bundle
from test_packages import song_document, PNG_1X1


class NativeDeliveryTest(unittest.TestCase):
    def test_unrelated_selection_edit_does_not_block_delivery_but_changed_recording_does(self):
        from stemwise_engine.native_delivery import _selected_ids
        selection = self.fixture.library/'comparison-results/library-files.json'
        value = json.loads(selection.read_bytes())
        value['playlist'].append({'videoId': '01234567890', 'source': 'youtube'})
        selection.write_text(json.dumps(value), encoding='utf-8')
        self.assertIn(self.workflow.video_id, _selected_ids(self.workflow))
        for row in value['playlist']:
            if row['videoId'] == self.workflow.video_id:
                row['source'] = 'youtube'
        selection.write_text(json.dumps(value), encoding='utf-8')
        self.assertEqual(_selected_ids(self.workflow), [])

    def public_snapshot(self):
        from stemwise_engine.native_delivery import _root
        root = _root(self.workflow)
        return {str(path.relative_to(root)): path.read_bytes()
                for path in root.rglob('*') if path.is_file()} if root.exists() else {}

    def setUp(self):
        from workflow_fixture import nested_case
        self.fixture = nested_case(self, fixtures.RecordingWorkflowTest)
        self.workflow = self.fixture.workflow()
        self.document = song_document(self.workflow.video_id)
        self.document['source']['recording'] = self.workflow.recording[3]
        cover = self.workflow.runtime / 'cover.png'
        cover.parent.mkdir(parents=True, exist_ok=True)
        cover.write_bytes(PNG_1X1)
        self.child = self.workflow.ready / f'{self.workflow.video_id}.stemwise.zip'
        create_child_zip(self.child, self.document, cover, self.workflow._runtime_config())
        self.job = {'state': 'DELIVERED', 'delivery': {'sha256': sha256_file(self.child)}}

    def finalization_fixture(self):
        from stemwise_engine.editorial_review import POLICY_VERSION
        self.prepare_policy()
        workflow = self.workflow

        source = workflow.runtime / 'children' / self.child.name
        source.parent.mkdir(parents=True)
        shutil.copy2(self.child, source)
        job = {'videoId': workflow.video_id, 'policyVersion': POLICY_VERSION, 'policy': workflow._policy_binding(),
               'state': 'VALIDATED', 'recordingInput': workflow.recording[3],
               'deliveryAllowed': True, 'qualificationClaimsAllowed': False,
               'validation': {'childSha256': sha256_file(source)}}
        self.fixture.write(workflow._job_path(workflow.video_id), job)
        return job

    def prepare_policy(self):
        workflow = self.workflow
        workflow.contract_identity = {}
        for name in ('song-enrichment-v2.schema.json', 'installed-enrichment-v2.schema.json'):
            schema = workflow.workspace_root/'StemWise/contracts/enrichment'/name
            schema.parent.mkdir(parents=True, exist_ok=True)
            schema.write_bytes(b'{"schema":"fixture"}')
            workflow.contract_identity[name] = sha256_file(schema)
        self.fixture.write(workflow.policy_dir / 'lyrics-policy.json', workflow.policy)
        workflow.policy_sha256 = workflow._execution_policy_sha256(workflow.policy)

    def reprocessing_boundaries(self):
        from contextlib import ExitStack
        stack = ExitStack()
        stack.enter_context(patch.object(self.workflow.report_store, 'commit_finalization_run', return_value=('f' * 32, 'e' * 64)))
        stack.enter_context(patch.object(self.workflow.report_store, 'workflow_operation_bindings', return_value={}))
        stack.enter_context(patch.object(self.workflow.report_store, 'commit_workflow_operation_record'))
        for name, value in (('_validated_snapshot_is_current', True),):
            stack.enter_context(patch.object(self.workflow, name, return_value=value))
        for name in ('_write_task', '_remove_retained_analysis_audio'):
            stack.enter_context(patch.object(self.workflow, name))
        return stack

    def prepare_reprocessing(self, *, two_aliases=False, registered=False, absent_plan=False):
        from stemwise_engine.native_delivery import _root
        self.prepare_policy()
        if registered:
            from stemwise_engine.recording_source import register_input
            register_input(self.fixture.library, 11, self.workflow.video_id, 'Native', 'Artist')
            selection = self.fixture.library / 'comparison-results/library-files.json'
            value = json.loads(selection.read_bytes())
            value['playlist'] = []
            self.fixture.write(selection, value)
            if absent_plan:
                selection.unlink()
                (self.fixture.library / 'catalogue.json').unlink()
            self.workflow = self.fixture.workflow()
            self.prepare_policy()
        if two_aliases:
            selection = self.fixture.library / 'comparison-results/library-files.json'
            value = json.loads(selection.read_bytes())
            value['playlist'].append({'videoId': 'lmnopqrstuv', 'source': 'qobuz', 'trackId': 11})
            self.fixture.write(selection, value)
            catalogue = self.fixture.library / 'catalogue.json'
            value = json.loads(catalogue.read_bytes())
            value['rows'].append({'videoId': 'lmnopqrstuv', 'position': 2, 'candidates': [{'id': 11}]})
            self.fixture.write(catalogue, value)
            value = json.loads(selection.read_bytes())
            value['catalogueSha256'] = sha256_file(catalogue)
            self.fixture.write(selection, value)
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            publish(self.workflow, self.job)
        self.fixture.write(self.workflow.ready / 'recording.json', self.workflow._result_binding(self.job))
        aliases = ([_root(self.workflow) / f'{self.workflow.video_id}.stemwise.zip'] if registered else
            [_root(self.workflow) / f"{row['videoId']}.stemwise.zip"
             for row in json.loads((self.fixture.library / 'comparison-results/library-files.json').read_bytes())['playlist']])
        before = {path: path.read_bytes() for path in (*aliases, self.child, self.workflow.ready / 'recording.json')}
        with self.reprocessing_boundaries(), patch.object(self.workflow, 'acquisition', return_value=Mock()):
            self.workflow.new(f'https://youtube.com/watch?v={self.workflow.video_id}')
        self.assertEqual({path: path.read_bytes() for path in before}, before)
        self.document['metadata']['title'] = 'Reprocessed enrichment'
        self.document['generatedAt'] = '2026-10-22T00:00:00Z'
        source = self.workflow.runtime / 'children' / self.child.name
        create_child_zip(source, self.document, self.workflow.runtime / 'cover.png', self.workflow._runtime_config())
        job = self.workflow._load_job(self.workflow.video_id)
        job.update(state='VALIDATED', validation={'childSha256': sha256_file(source)})
        # Substitute completed numerical/editorial review, retaining real New and Finalize.
        self.fixture.write(self.workflow._job_path(self.workflow.video_id), job)
        return aliases, before

    def test_fresh_current_new_preserves_completed_result_but_refuses_current_owner(self):
        from stemwise_engine.future_song import FutureSongError
        aliases, before = self.prepare_reprocessing()
        job = self.workflow._job_path(self.workflow.video_id)
        accepted = job.read_bytes()
        with self.reprocessing_boundaries(), patch.object(self.workflow, 'acquisition', return_value=Mock()) as acquire:
            with self.assertRaisesRegex(FutureSongError, 'current job'):
                self.workflow.new(f'https://youtube.com/watch?v={self.workflow.video_id}')
            acquire.assert_not_called()
        self.assertEqual(job.read_bytes(), accepted)
        self.assertEqual({path: path.read_bytes() for path in before}, before)
        self.assertEqual(self.fixture.old_job.read_bytes(), self.fixture.old_bytes)

    def test_fresh_current_finalize_replaces_only_the_bound_previous_same_recording(self):
        aliases, _ = self.prepare_reprocessing()
        with self.reprocessing_boundaries():
            result = self.workflow.finalize(self.workflow.video_id)
        self.assertTrue(result['phoneDeliveryAllowed'])
        self.assertEqual(read_bundle(aliases[0], self.workflow._runtime_config())[0], self.document)
        job = self.workflow._load_job(self.workflow.video_id)
        self.assertEqual(json.loads((self.workflow.ready / 'recording.json').read_bytes()), self.workflow._result_binding(job))
        self.assertFalse(list(self.workflow.history.rglob('*.zip')))

    def test_reset_aliases_publish_current_result_without_decoding_retired_private_metadata(self):
        self.assert_missing_aliases_do_not_require_previous_payload(partial=False)

    def test_partial_current_alias_publication_does_not_require_retired_private_metadata(self):
        self.assert_missing_aliases_do_not_require_previous_payload(partial=True)

    def assert_missing_aliases_do_not_require_previous_payload(self, *, partial):
        import io
        import zipfile
        aliases, _ = self.prepare_reprocessing(two_aliases=True)
        with zipfile.ZipFile(self.child) as archive:
            payloads = {item.filename: archive.read(item) for item in archive.infolist()}
        previous = json.loads(payloads['song.json'])
        previous['lyrics']['tokenizationVersion'] = 'retired-tokenizer'
        payloads['song.json'] = json.dumps(previous).encode('utf-8')
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, payload in payloads.items():
                archive.writestr(name, payload)
        self.child.write_bytes(output.getvalue())
        binding_path = self.workflow.ready / 'recording.json'
        binding = json.loads(binding_path.read_bytes())
        binding['enrichmentSha256'] = sha256_file(self.child)
        self.fixture.write(binding_path, binding)
        with self.assertRaisesRegex(ValueError, 'tokenizationVersion'):
            read_child(self.child, self.workflow._runtime_config())
        for alias in aliases:
            alias.unlink()
        if partial:
            self.write_complete_alias(aliases[0], self.document)
        retained_stamp = aliases[0].stat().st_mtime_ns if partial else None
        previous_sha = sha256_file(self.child)
        import stemwise_engine.native_delivery as native_delivery
        with self.reprocessing_boundaries(), patch.object(native_delivery, '_previous_private_delivery',
                wraps=native_delivery._previous_private_delivery) as read_previous:
            result = self.workflow.finalize(self.workflow.video_id)
            read_previous.assert_not_called()
        self.assertTrue(result['phoneDeliveryAllowed'])
        for alias in aliases:
            expected = copy.deepcopy(self.document)
            expected['source']['videoId'] = alias.name.removesuffix('.stemwise.zip')
            self.assertEqual(read_bundle(alias, self.workflow._runtime_config())[0], expected)
        if partial:
            self.assertEqual(aliases[0].stat().st_mtime_ns, retained_stamp)
        job = self.workflow._load_job(self.workflow.video_id)
        self.assertEqual(json.loads(binding_path.read_bytes()), self.workflow._result_binding(job))
        self.assertNotEqual(job['delivery']['sha256'], previous_sha)
        self.assertFalse(list(self.workflow.history.rglob('*.zip')))

    def test_reset_aliases_still_reject_unrelated_previous_recording_binding(self):
        aliases, _ = self.prepare_reprocessing()
        for alias in aliases:
            alias.unlink()
        marker = self.workflow.ready / 'recording.json'
        binding = json.loads(marker.read_bytes())
        binding['input']['byteCount'] += 1
        self.fixture.write(marker, binding)
        before = marker.read_bytes()
        with self.reprocessing_boundaries(), self.assertRaisesRegex(ValueError, 'Previous native delivery binding'):
            self.workflow.finalize(self.workflow.video_id)
        self.assertEqual(marker.read_bytes(), before)
        self.assertEqual(self.public_snapshot(), {})

    def test_registered_future_new_finalize_and_bridge_describe_use_the_same_complete_ready_zip(self):
        self.assert_registered_future_delivery()

    def test_registered_future_without_a_pinned_plan_finalizes_and_bridge_describes_the_ready_zip(self):
        self.assert_registered_future_delivery(absent_plan=True)

    def assert_registered_future_delivery(self, *, absent_plan=False):
        from stemwise_engine.bridge_delivery import describe
        from stemwise_engine.future_song import FutureSongWorkflow
        aliases, _ = self.prepare_reprocessing(registered=True, absent_plan=absent_plan)
        def policy(instance):
            instance.contract_identity = self.workflow.contract_identity
            instance.policy_sha256 = self.workflow.policy_sha256
            return self.workflow.policy
        with self.reprocessing_boundaries():
            self.assertTrue(self.workflow.finalize(self.workflow.video_id)['phoneDeliveryAllowed'])
        with patch.object(FutureSongWorkflow, '_validate_policy', policy), \
                patch.object(FutureSongWorkflow, '_validated_snapshot_is_current', return_value=True):
            descriptor = describe(self.workflow.config_path, self.workflow.video_id, aliases[0].stat().st_size)
        self.assertEqual(descriptor['recordingKey'], 'qobuz:11')
        self.assertEqual(descriptor['package'], {'path': f'Ready to Import/{aliases[0].name}',
            'name': aliases[0].name, 'size': aliases[0].stat().st_size, 'sha256': sha256_file(aliases[0])})
        self.assertEqual(read_bundle(aliases[0], self.workflow._runtime_config())[0], self.document)

    def test_registration_commit_respects_the_public_selection_lock(self):
        from stemwise_engine.common import exclusive_process_lock, ProcessLockBusyError
        from stemwise_engine.recording_source import register_input
        registration = self.fixture.audio.parent / 'enrichment-input.json'
        with exclusive_process_lock(self.fixture.library / 'comparison-results/delivery-selection.lock'):
            with self.assertRaises(ProcessLockBusyError):
                register_input(self.fixture.library, 11, self.workflow.video_id, 'Native', 'Artist')
        self.assertFalse(registration.exists())
        register_input(self.fixture.library, 11, self.workflow.video_id, 'Native', 'Artist')
        self.assertTrue(registration.is_file())

    def test_catalogue_uses_exact_registration_declarations_without_reading_unrelated_audio(self):
        from stemwise_engine.delivery_selection import catalogue
        from stemwise_engine.recording_source import register_input
        register_input(self.fixture.library, 11, self.workflow.video_id, 'Native', 'Artist')
        other = self.fixture.audio.parent.with_name('011') / 'enrichment-input.json'
        value = json.loads((self.fixture.audio.parent / 'enrichment-input.json').read_bytes())
        value['referenceVideoId'] = 'lmnopqrstuv'
        self.fixture.write(other, value)
        selection = self.fixture.library / 'comparison-results/library-files.json'
        value = json.loads(selection.read_bytes())
        value['playlist'] = []
        self.fixture.write(selection, value)
        self.assertEqual(catalogue(self.workflow), {self.workflow.video_id: 'qobuz:11'})
        with patch('stemwise_engine.recording_source.qobuz_file', side_effect=AssertionError('No catalogue-wide media reads')):
            self.assertEqual(catalogue(self.workflow), {self.workflow.video_id: 'qobuz:11'})
        self.fixture.audio.write_bytes(b'corrupt retained audio')
        self.assertEqual(catalogue(self.workflow), {self.workflow.video_id: 'qobuz:11'})
        with self.assertRaises(ValueError):
            self.fixture.workflow()

    def test_malformed_registered_selection_declaration_rejects_without_changing_ready(self):
        from stemwise_engine.delivery_selection import catalogue
        from stemwise_engine.recording_source import register_input
        register_input(self.fixture.library, 11, self.workflow.video_id, 'Native', 'Artist')
        registration = self.fixture.audio.parent / 'enrichment-input.json'
        original = json.loads(registration.read_bytes())
        selection = self.fixture.library / 'comparison-results/library-files.json'
        value = json.loads(selection.read_bytes())
        value['playlist'] = []
        self.fixture.write(selection, value)
        before = self.public_snapshot()
        for changes in ({'formatVersion': True}, {'trackId': True}, {'trackId': 22},
                {'file': 'audio/qobuz/22/native.flac'}, {'file': 'audio/qobuz/11/../native.flac'}):
            with self.subTest(changes=changes):
                self.fixture.write(registration, {**original, **changes})
                with self.assertRaises(ValueError):
                    catalogue(self.workflow)
                self.assertEqual(self.public_snapshot(), before)
        self.fixture.write(registration, original)
        selection.write_bytes(b'{invalid present plan')
        with self.assertRaises(ValueError):
            catalogue(self.workflow)
        self.assertEqual(self.public_snapshot(), before)

    def test_missing_public_alias_is_delivery_needed_not_completed(self):
        aliases, _ = self.prepare_reprocessing()
        with self.reprocessing_boundaries():
            self.workflow.finalize(self.workflow.video_id)
            aliases[0].unlink()
            job = self.workflow._load_job(self.workflow.video_id)
            for result in (self.workflow.summary(job), self.workflow.status(self.workflow.video_id, verify_delivery=True)):
                self.assertEqual(result['state'], 'DELIVERY_AUDIO_REQUIRED')
                self.assertFalse(result['phoneDeliveryAllowed'])
                self.assertNotIn('delivery', result)

    def test_unknown_native_candidate_cannot_finalize_an_unselected_extra_zip(self):
        from stemwise_engine.future_song import FutureSongError
        self.finalization_fixture()
        selection = self.fixture.library / 'comparison-results/library-files.json'
        value = json.loads(selection.read_bytes())
        value['playlist'] = [{'videoId': 'lmnopqrstuv', 'source': 'youtube'}]
        self.fixture.write(selection, value)
        before = self.child.read_bytes(), self.workflow._job_path(self.workflow.video_id).read_bytes()
        with self.reprocessing_boundaries(), self.assertRaisesRegex(FutureSongError, 'recording-not-selected'):
            self.workflow.finalize(self.workflow.video_id)
        self.assertEqual((self.child.read_bytes(), self.workflow._job_path(self.workflow.video_id).read_bytes()), before)
        self.assertEqual(self.public_snapshot(), {})

    def test_pinned_selection_precedes_registered_sources_and_conflicting_unpinned_sources_reject(self):
        from stemwise_engine.recording_source import register_input
        from stemwise_engine.delivery_selection import catalogue
        from qobuz_fixtures import write_download
        register_input(self.fixture.library, 11, self.workflow.video_id, 'Native', 'Artist')
        other = self.fixture.library / 'audio/qobuz/22/native.flac'
        other.parent.mkdir(parents=True)
        shutil.copy2(self.fixture.audio, other)
        write_download(other.parent, {'qobuzTrackId': 22, 'file': other.name})
        register_input(self.fixture.library, 22, self.workflow.video_id, 'Other', 'Artist')
        selection = self.fixture.library / 'comparison-results/library-files.json'
        value = json.loads(selection.read_bytes())
        for row, expected in (({'videoId': self.workflow.video_id, 'source': 'youtube'}, 'youtube:abcdefghijk'),
                ({'videoId': self.workflow.video_id, 'source': 'unavailable'}, None),
                ({'videoId': self.workflow.video_id, 'source': 'qobuz', 'trackId': 11}, 'qobuz:11')):
            value['playlist'] = [row]
            self.fixture.write(selection, value)
            self.assertEqual(catalogue(self.workflow)[self.workflow.video_id], expected)
        value['playlist'] = []
        self.fixture.write(selection, value)
        with self.assertRaisesRegex(ValueError, 'Conflicting registered recordings'):
            catalogue(self.workflow)

    def test_same_recording_mixed_alias_interruption_preserves_binding_and_retries_without_rewrite(self):
        import stemwise_engine.original_delivery as original_delivery
        aliases, before = self.prepare_reprocessing(two_aliases=True)
        replace = original_delivery.durable_replace
        def interrupted(source, target):
            if target == aliases[1]:
                raise OSError('second alias unavailable')
            return replace(source, target)
        with self.reprocessing_boundaries():
            with patch('stemwise_engine.original_delivery.durable_replace', side_effect=interrupted):
                with self.assertRaisesRegex(OSError, 'second alias unavailable'):
                    self.workflow.finalize(self.workflow.video_id)
            self.assertEqual(read_bundle(aliases[0], self.workflow._runtime_config())[0], self.document)
            self.assertEqual(aliases[1].read_bytes(), before[aliases[1]])
            self.assertEqual((self.workflow.ready / 'recording.json').read_bytes(), before[self.workflow.ready / 'recording.json'])
            self.assertTrue(any(path.read_bytes() == before[self.child] for path in self.workflow.history.rglob('*.zip')))
            stamp = aliases[0].stat().st_mtime_ns
            self.assertTrue(self.workflow.finalize(self.workflow.video_id)['phoneDeliveryAllowed'])
            self.assertEqual(aliases[0].stat().st_mtime_ns, stamp)
        self.assertEqual(read_bundle(aliases[1], self.workflow._runtime_config())[0]['metadata'], self.document['metadata'])
        self.assertFalse(list(self.workflow.history.rglob('*.zip')))

    def test_same_recording_reprocessing_rejects_unknown_alias_and_preserves_prior_binding(self):
        aliases, before = self.prepare_reprocessing()
        wrong = copy.deepcopy(self.document)
        wrong['metadata']['title'] = 'Unknown same-recording publication'
        self.write_complete_alias(aliases[0], wrong)
        unknown = aliases[0].read_bytes()
        with self.reprocessing_boundaries(), self.assertRaisesRegex(ValueError, 'previous'):
            self.workflow.finalize(self.workflow.video_id)
        self.assertEqual(aliases[0].read_bytes(), unknown)
        self.assertEqual((self.workflow.ready / 'recording.json').read_bytes(), before[self.workflow.ready / 'recording.json'])

    def test_same_recording_reprocessing_rejects_tampered_previous_private_bundle(self):
        aliases, _ = self.prepare_reprocessing()
        self.child.write_bytes(self.child.read_bytes() + b'changed private bytes')
        before = aliases[0].read_bytes(), (self.workflow.ready / 'recording.json').read_bytes()
        with self.reprocessing_boundaries(), self.assertRaisesRegex(ValueError, 'Previous native'):
            self.workflow.finalize(self.workflow.video_id)
        self.assertEqual((aliases[0].read_bytes(), (self.workflow.ready / 'recording.json').read_bytes()), before)

    def test_same_recording_reprocessing_rejects_tampered_previous_binding(self):
        aliases, _ = self.prepare_reprocessing()
        marker = self.workflow.ready / 'recording.json'
        value = json.loads(marker.read_bytes())
        value['input']['byteCount'] += 1
        self.fixture.write(marker, value)
        before = aliases[0].read_bytes(), marker.read_bytes()
        with self.reprocessing_boundaries(), self.assertRaisesRegex(ValueError, 'Previous native delivery binding'):
            self.workflow.finalize(self.workflow.video_id)
        self.assertEqual((aliases[0].read_bytes(), marker.read_bytes()), before)

    def test_canonical_finalize_commits_and_reuses_finished_alias(self):
        workflow, job = self.workflow, self.finalization_fixture()
        # Substitute the prior review proof/run recording, not loading, finalization,
        # package validation, recording binding, alias publication or durable job state.
        with patch.object(workflow, '_validated_snapshot_is_current', return_value=True), \
                patch.object(workflow.report_store, 'commit_finalization_run', return_value=('f' * 32, 'e' * 64)), \
                patch.object(workflow.report_store, 'workflow_operation_bindings', return_value={}), \
                patch.object(workflow.report_store, 'commit_workflow_operation_record'), \
                patch.object(workflow, '_write_task'), \
                patch.object(workflow, '_remove_retained_analysis_audio'):
            result = workflow.finalize(workflow.video_id)
            delivered = workflow._load_job(workflow.video_id)
            self.assertEqual(result['state'], 'DELIVERED')
            self.assertTrue(result['phoneDeliveryAllowed'])
            self.assertEqual(json.loads((workflow.ready / 'recording.json').read_bytes()),
                             workflow._result_binding(delivered))
            from stemwise_engine.common import resolve_path
            alias = resolve_path(result['phoneDeliveries'][0]['path'], base=self.fixture.workspace)
            self.assertEqual(read_bundle(alias, workflow._runtime_config())[0], self.document)
            before = (alias.stat().st_mtime_ns, workflow._job_path(workflow.video_id).read_bytes())
            self.assertEqual(workflow.finalize(workflow.video_id)['phoneDeliveries'], result['phoneDeliveries'])
            self.assertEqual((alias.stat().st_mtime_ns, workflow._job_path(workflow.video_id).read_bytes()), before)
            self.assertEqual(delivered['policy'], job['policy'])


    def test_interrupted_native_publication_recovers_terminal_job_without_inference_or_lost_rollback(self):
        workflow = self.workflow
        self.finalization_fixture()
        rollback = workflow.history / workflow.video_id / ('a' * 24 + '.' + self.child.name)
        rollback.parent.mkdir(parents=True)
        rollback.write_bytes(b'previous delivered package')
        with patch.object(workflow, '_validated_snapshot_is_current', return_value=True), \
                patch.object(workflow.report_store, 'commit_finalization_run', return_value=('f' * 32, 'e' * 64)), \
                patch.object(workflow.report_store, 'workflow_operation_bindings', return_value={}), \
                patch.object(workflow.report_store, 'commit_workflow_operation_record'), \
                patch.object(workflow, '_write_task'), \
                patch.object(workflow, '_remove_retained_analysis_audio'), \
                patch.object(workflow, 'validate', side_effect=AssertionError('Do not redo validation')):
            with patch('stemwise_engine.native_delivery.publish', side_effect=OSError('interrupted native publication')):
                with self.assertRaisesRegex(OSError, 'interrupted native publication'):
                    workflow.finalize(workflow.video_id)
            durable = json.loads(workflow._job_path(workflow.video_id).read_bytes())
            self.assertEqual(durable['state'], 'DELIVERED')
            self.assertTrue(rollback.exists())
            self.assertFalse((workflow.ready / 'recording.json').exists())
            before = json.loads(workflow._job_path(workflow.video_id).read_bytes())
            with patch.object(workflow, '_pipeline', side_effect=AssertionError('Do not redo inference')):
                result = workflow.finalize(workflow.video_id)
            self.assertEqual(result['state'], 'DELIVERED')
            self.assertTrue(result['phoneDeliveryAllowed'])
            self.assertFalse(rollback.exists())
            finished = json.loads(workflow._job_path(workflow.video_id).read_bytes())
            self.assertNotIn('nativePublication', finished)
            self.assertEqual({key: value for key, value in finished.items() if key != 'updatedAt'},
                {key: value for key, value in before.items() if key not in {'updatedAt', 'nativePublication'}})
            self.assertEqual(json.loads((workflow.ready / 'recording.json').read_bytes()),
                             workflow._result_binding(durable))

    def test_bridge_reconnect_finishes_pending_native_publication(self):
        self._check_bridge_reconnect_publication(partial=False)

    def test_bridge_completed_native_job_publishes_new_selected_alias(self):
        from stemwise_engine.bridge_song_dispatch import advance
        workflow = self.workflow
        self.finalization_fixture()
        with self.reprocessing_boundaries():
            workflow.finalize(workflow.video_id)
        first = self.fixture.workspace/'Ready to Import'/self.child.name
        before = first.read_bytes(), first.stat().st_mtime_ns
        alias = 'lmnopqrstuv'
        catalogue = self.fixture.library/'catalogue.json'
        value = json.loads(catalogue.read_bytes())
        value['rows'].append({'videoId':alias,'position':2,'candidates':[{'id':11}]})
        self.fixture.write(catalogue,value)
        selection = self.fixture.library/'comparison-results/library-files.json'
        value = json.loads(selection.read_bytes())
        value['playlist'].append({'videoId':alias,'source':'qobuz','trackId':11})
        value['catalogueSha256'] = sha256_file(catalogue)
        self.fixture.write(selection,value)
        previous_workflow = workflow
        workflow = self.fixture.workflow(verify_media=False)
        workflow.contract_identity = previous_workflow.contract_identity
        workflow.policy_sha256 = previous_workflow.policy_sha256
        from stemwise_engine.future_song import FutureSongWorkflow
        class Factory(FutureSongWorkflow):
            def __new__(cls,*args,**kwargs):
                return workflow
        context = {'formatVersion':1,'kind':'stemwise.add-song','requestId':'a'*32,
            'videoId':alias,'sourceUrl':'https://www.youtube.com/watch?v='+alias,'guideSha256':'b'*64}
        with patch('stemwise_engine.future_song.FutureSongWorkflow',Factory), \
                patch('stemwise_engine.recording_workflow.RecordingWorkflow',return_value=workflow), \
                patch.object(workflow,'_validated_snapshot_is_current',return_value=True), \
                patch.object(workflow,'_pipeline',side_effect=AssertionError('No inference')), \
                patch.object(workflow,'_windows_path',side_effect=str), \
                patch.object(workflow,'_write_task'), \
                patch.object(workflow,'_remove_retained_analysis_audio'), \
                patch.object(workflow.report_store,'commit_workflow_operation_record'), \
                patch.object(workflow.report_store,'workflow_operation_bindings',return_value={}):
            for _ in range(2):
                result = advance(workflow.config_path,context,1024**3)
                self.assertEqual(result['status'],'completed',result)
                self.assertEqual(result['package']['name'],alias+'.stemwise.zip')
        self.assertEqual((first.read_bytes(),first.stat().st_mtime_ns),before)
        actual, _, original = read_bundle(first.with_name(alias+'.stemwise.zip'),workflow._runtime_config())
        self.assertEqual(actual['source']['videoId'],alias)
        self.assertEqual(original['sha256'],workflow.recording.identity['sha256'])

    def test_bridge_reconnect_reuses_partially_published_native_alias(self):
        self._check_bridge_reconnect_publication(partial=True)

    def _check_bridge_reconnect_publication(self, *, partial):
        from contextlib import ExitStack
        from stemwise_engine.bridge_song_dispatch import advance
        import stemwise_engine.native_delivery as native_delivery
        workflow = self.workflow
        from stemwise_engine.future_song import FutureSongWorkflow
        class Factory(FutureSongWorkflow):
            def __new__(cls,*args,**kwargs):
                return workflow
        alias = 'lmnopqrstuv'
        selection = self.fixture.library / 'comparison-results/library-files.json'
        catalogue = self.fixture.library / 'catalogue.json'
        value = json.loads(catalogue.read_bytes())
        value['rows'].append({'videoId': alias, 'position': 2, 'candidates': [{'id': 11}]})
        self.fixture.write(catalogue, value)
        value = json.loads(selection.read_bytes())
        value['playlist'].append({'videoId': alias, 'source': 'qobuz', 'trackId': 11})
        value['catalogueSha256'] = sha256_file(catalogue)
        self.fixture.write(selection, value)
        self.finalization_fixture()
        rollback = workflow.history / workflow.video_id / ('a' * 24 + '.' + self.child.name)
        rollback.parent.mkdir(parents=True)
        rollback.write_bytes(b'previous delivered package')
        context = {'formatVersion': 1, 'kind': 'stemwise.add-song', 'requestId': 'a' * 32,
            'videoId': alias, 'sourceUrl': 'https://www.youtube.com/watch?v=' + alias,
            'guideSha256': 'b' * 64}
        real_publish = native_delivery.publish_bundle
        def interrupt(*args, **kwargs):
            real_publish(*args, **kwargs)
            raise OSError('interrupted after first alias')
        with self.reprocessing_boundaries(), ExitStack() as stack:
            stack.enter_context(patch.object(workflow, '_windows_path', side_effect=str))
            stack.enter_context(patch('stemwise_engine.future_song.FutureSongWorkflow', Factory))
            stack.enter_context(patch('stemwise_engine.recording_workflow.RecordingWorkflow', return_value=workflow))
            stack.enter_context(patch.object(workflow, '_pipeline', side_effect=AssertionError('No inference')))
            with (patch('stemwise_engine.native_delivery.publish_bundle', side_effect=interrupt) if partial else
                  patch('stemwise_engine.native_delivery.publish', side_effect=OSError('interrupted before aliases'))):
                with self.assertRaises(OSError):
                    workflow.finalize(workflow.video_id)
            durable = json.loads(workflow._job_path(workflow.video_id).read_bytes())
            self.assertTrue(rollback.exists())
            self.assertEqual(workflow.status(workflow.video_id)['state'], 'DELIVERY_PUBLICATION_PENDING')
            canonical = self.fixture.workspace / 'Ready to Import' / self.child.name
            before = canonical.stat().st_mtime_ns if partial else None
            result = advance(workflow.config_path, context, 1024 ** 3)
            self.assertEqual(result['status'], 'completed', result)
            self.assertEqual(result['videoId'], alias)
            self.assertEqual(result['package']['name'], alias + '.stemwise.zip')
            if partial:
                self.assertEqual(canonical.stat().st_mtime_ns, before)
            self.assertFalse(rollback.exists())
            final = json.loads(workflow._job_path(workflow.video_id).read_bytes())
            self.assertNotIn('nativePublication', final)
            self.assertEqual(final['delivery'], durable['delivery'])
            self.assertEqual(json.loads((workflow.ready / 'recording.json').read_bytes()), workflow._result_binding(final))
            saved = workflow._job_path(workflow.video_id).read_bytes()
            self.assertEqual(advance(workflow.config_path, context, 1024 ** 3)['status'], 'completed')
            inspected = advance(workflow.config_path, context, 1024 ** 3, inspect_only=True)
            self.assertEqual(inspected['status'], 'waiting')
            self.assertEqual(inspected['code'], 'stemwise_delivery_verification_required')
            self.assertNotIn('package', inspected)
            self.assertEqual(workflow._job_path(workflow.video_id).read_bytes(), saved)

    def test_exports_one_flat_bundle_for_each_selected_playlist_entry(self):
        self.fixture.write(self.fixture.library / 'comparison-results/library-files.json', {
            'catalogueSha256': sha256_file(self.fixture.library/'catalogue.json'),
            'flac': [{'trackId': 11, 'file': 'audio/qobuz/11/native.flac'}],
            'playlist': [{'videoId': 'abcdefghijk', 'source': 'qobuz', 'trackId': 11},
                         {'videoId': 'lmnopqrstuv', 'source': 'qobuz', 'trackId': 11},
                         {'videoId': '01234567890', 'source': 'youtube'}]})
        old = self.fixture.workspace / 'Ready to Import/01234567890.stemwise.zip'
        old.parent.mkdir(parents=True)
        old.write_bytes(b'historical Opus result')
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            exports = publish(self.workflow, self.job)
            self.assertEqual([x['videoId'] for x in exports], ['abcdefghijk', 'lmnopqrstuv'])
            for item in exports:
                from stemwise_engine.common import resolve_path
                path = resolve_path(item['path'], base=self.fixture.workspace)
                self.assertEqual(path.parent, self.fixture.workspace / 'Ready to Import')
                document, cover, original = read_bundle(path, self.workflow._runtime_config())
                self.assertEqual(original["sha256"], self.workflow.recording[3]["sha256"])
                expected = copy.deepcopy(self.document)
                expected['source']['videoId'] = item['videoId']
                self.assertEqual(document, expected)
                self.assertEqual(cover, PNG_1X1)
                stamp = path.stat().st_mtime_ns
                with patch('stemwise_engine.native_delivery.prepare_original', side_effect=AssertionError('Do not decode unchanged audio')):
                    publish(self.workflow, self.job)
                self.assertEqual(path.stat().st_mtime_ns, stamp)
            self.assertEqual(current(self.workflow, self.job), exports)
        self.assertEqual(old.read_bytes(), b'historical Opus result')
        self.assertEqual(self.fixture.old_job.read_bytes(), self.fixture.old_bytes)

    def test_duplicate_nested_delivery_fails_before_rewriting_current_package(self):
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            deliveries = publish(self.workflow,self.job)
            from stemwise_engine.common import resolve_path
            path = resolve_path(deliveries[0]['path'],base=self.fixture.workspace)
            duplicate = path.parent/'old'/path.name
            duplicate.parent.mkdir()
            duplicate.write_bytes(path.read_bytes())
            before = path.read_bytes(),duplicate.read_bytes()
            with self.assertRaisesRegex(ValueError,'Multiple public ZIPs'):
                publish(self.workflow,self.job)
            with self.assertRaisesRegex(ValueError,'Multiple public ZIPs'):
                current(self.workflow,self.job)
        self.assertEqual((path.read_bytes(),duplicate.read_bytes()),before)
        self.assertFalse(list((self.workflow.runtime / 'finalization-staging').rglob('cover.png')))

    def test_noncurrent_review_cannot_publish_by_setting_delivered_flag(self):
        from stemwise_engine.native_delivery import _root
        unrelated = _root(self.workflow) / 'unrelated.stemwise.zip'
        unrelated.parent.mkdir(parents=True, exist_ok=True)
        unrelated.write_bytes(b'existing unrelated owner alias')
        before = self.public_snapshot()
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=False):
            with self.assertRaisesRegex(ValueError, 'validated'):
                publish(self.workflow, self.job)
        self.assertEqual(self.public_snapshot(), before)

    def test_unselected_recording_has_no_second_public_bundle_for_the_same_song(self):
        self.fixture.write(self.fixture.library / 'comparison-results/library-files.json', {
            'catalogueSha256': sha256_file(self.fixture.library/'catalogue.json'),
            'flac': [{'trackId': 11, 'file': 'audio/qobuz/11/native.flac'}],
            'playlist': [{'videoId': 'abcdefghijk', 'source': 'youtube'}]})
        old = self.fixture.workspace / 'Ready to Import/abcdefghijk.stemwise.zip'
        old.parent.mkdir(parents=True)
        old.write_bytes(b'existing distinct YouTube recording')
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            from stemwise_engine.future_song import FutureSongError
            with self.assertRaisesRegex(FutureSongError, 'recording-not-selected'):
                publish(self.workflow, self.job)
            self.assertEqual(current(self.workflow, self.job), [])
        self.assertEqual(old.read_bytes(), b'existing distinct YouTube recording')
        self.assertEqual(self.fixture.old_job.read_bytes(), self.fixture.old_bytes)

    def test_tampered_finalized_result_cannot_publish(self):
        from stemwise_engine.native_delivery import _root
        unrelated = _root(self.workflow) / 'unrelated.stemwise.zip'
        unrelated.parent.mkdir(parents=True, exist_ok=True)
        unrelated.write_bytes(b'existing unrelated owner alias')
        self.child.write_bytes(b'changed after review')
        before = self.public_snapshot()
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            with self.assertRaisesRegex(ValueError, 'changed'):
                publish(self.workflow, self.job)
        self.assertEqual(self.public_snapshot(), before)

    def test_current_selected_native_can_replace_prior_youtube_or_native_recording_and_retry(self):
        from stemwise_engine.native_delivery import _root
        alias = _root(self.workflow) / f'{self.workflow.video_id}.stemwise.zip'
        alias.parent.mkdir(parents=True)
        cover = self.workflow.runtime / 'cover.png'
        child = self.workflow.runtime / 'old-youtube.zip'
        config = {**self.workflow._runtime_config(), 'recordingInput': None}
        from stemwise_engine.original_delivery import create_bundle, prepare_original
        for native in (False, True):
            with self.subTest(native=native):
                old = copy.deepcopy(self.document)
                if native:
                    old['source']['recording'].update(recordingKey='qobuz:22', trackId=22,
                        file='PC side/qobuz-comparison/audio/qobuz/22/native.flac')
                else:
                    old['source'].pop('recording')
                create_child_zip(child, old, cover, config)
                audio, identity = prepare_original(self.fixture.audio, old, self.workflow.runtime)
                create_bundle(alias, child, audio, identity, config, self.workflow.runtime)
                before = alias.read_bytes()
                with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
                    import stemwise_engine.original_delivery as original_delivery
                    replace = original_delivery.durable_replace
                    def interrupted(source, target):
                        if target == alias:
                            raise OSError('interrupted alias commit')
                        return replace(source, target)
                    with patch('stemwise_engine.original_delivery.durable_replace', side_effect=interrupted):
                        with self.assertRaisesRegex(OSError, 'interrupted alias commit'):
                            publish(self.workflow, self.job)
                    self.assertEqual(alias.read_bytes(), before)
                    self.assertTrue(any(path.read_bytes() == before for path in self.workflow.history.rglob('*.zip')))
                    result = publish(self.workflow, self.job)
                self.assertEqual(len(result), 1)
                self.assertNotEqual(alias.read_bytes(), before)
                self.assertEqual(read_bundle(alias, self.workflow._runtime_config())[0], self.document)

    def prepare_artwork_repair(self):
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            deliveries = publish(self.workflow, self.job)
        from stemwise_engine.common import resolve_path
        alias = resolve_path(deliveries[0]['path'], base=self.fixture.workspace)
        old_bytes = self.child.read_bytes()
        old_sha = hashlib.sha256(old_bytes).hexdigest()
        self.document['generatedAt'] = '2026-10-09T00:00:00Z'
        self.document['artwork']['source']['name'] = 'Reviewed replacement artwork'
        create_child_zip(self.child, self.document, self.workflow.runtime / 'cover.png', self.workflow._runtime_config())
        rollback = hashlib.sha256(b'stemwise-delivery-rollback\0' + old_sha.encode('ascii')
                                  + b'\0' + sha256_file(self.child).encode('ascii')).hexdigest()[:24]
        previous = self.workflow.history / self.workflow.video_id / f'{rollback}.{self.child.name}'
        previous.parent.mkdir(parents=True)
        previous.write_bytes(old_bytes)
        self.job['artworkRepair'] = {'previousChildSha256': old_sha,
                                    'previousArtworkSha256': hashlib.sha256(PNG_1X1).hexdigest()}
        self.job['delivery']['sha256'] = sha256_file(self.child)
        return alias, previous

    def test_authorized_artwork_repair_replaces_only_previous_alias_and_is_idempotent(self):
        alias, previous = self.prepare_artwork_repair()
        original_archive = previous.read_bytes()
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True), \
                patch('stemwise_engine.native_delivery.selected_original', side_effect=AssertionError('Do not reacquire reviewed native Original')):
            repaired = publish(self.workflow, self.job)
            self.assertEqual(read_bundle(alias, self.workflow._runtime_config())[0], self.document)
            stamp = alias.stat().st_mtime_ns
            self.assertEqual(previous.read_bytes(), original_archive)
            previous.unlink()
            self.assertEqual(publish(self.workflow, self.job), repaired)
            self.assertEqual(alias.stat().st_mtime_ns, stamp)
        self.assertFalse(previous.exists())

    def test_completed_artwork_repair_can_reselect_its_recording(self):
        alias, previous = self.prepare_artwork_repair()
        self.job['videoId'] = self.workflow.video_id
        self.job['recordingInput'] = self.workflow.recording.identity
        self.fixture.write(self.workflow._job_path(self.workflow.video_id),self.job)
        with patch.object(self.workflow,'_validated_snapshot_is_current',return_value=True):
            self.workflow.source.complete_delivery(self.workflow,self.job)
        previous.unlink()
        repaired = alias.read_bytes()
        from stemwise_engine.original_delivery import create_bundle, prepare_original
        import numpy as np
        import soundfile as sf
        other_audio = self.workflow.runtime/'other-recording.flac'
        sf.write(other_audio,np.zeros((48000,2)),48000,subtype='PCM_24')
        for native in (False,True):
            with self.subTest(native=native):
                other = copy.deepcopy(self.document)
                if native:
                    other['source']['recording'].update(recordingKey='qobuz:22',trackId=22,
                        file='PC side/qobuz-comparison/audio/qobuz/22/native.flac',
                        sha256=sha256_file(other_audio),byteCount=other_audio.stat().st_size)
                else:
                    other['source'].pop('recording')
                config = {**self.workflow._runtime_config(),'recordingInput':None}
                child = self.workflow.runtime/'replacement.zip'
                create_child_zip(child,other,self.workflow.runtime/'cover.png',config)
                audio,identity = prepare_original(other_audio,other,self.workflow.runtime)
                create_bundle(alias,child,audio,identity,config,self.workflow.runtime)
                workflow = self.fixture.workflow()
                job = json.loads(workflow._job_path(workflow.video_id).read_bytes())
                with patch.object(workflow,'_validated_snapshot_is_current',return_value=True), \
                        patch.object(workflow,'_pipeline',side_effect=AssertionError('No inference')):
                    publish(workflow,job)
                    stamp = alias.stat().st_mtime_ns
                    publish(workflow,job)
                    self.assertEqual(alias.stat().st_mtime_ns,stamp)
                self.assertEqual(alias.read_bytes(),repaired)

    def test_shared_public_readback_failure_preserves_rollback_and_retry_reuses_promoted_alias(self):
        import stemwise_engine.original_delivery as original_delivery
        alias, previous = self.prepare_artwork_repair()
        before, recovery, job_before = alias.read_bytes(), previous.read_bytes(), copy.deepcopy(self.job)
        read, replace = original_delivery.read_bundle, original_delivery.durable_replace
        promoted = False
        def promote(source, target):
            nonlocal promoted
            result = replace(source, target)
            if target == alias:
                promoted = True
            return result
        def unavailable(path, *args, **kwargs):
            if promoted and path == alias:
                raise OSError('public readback unavailable')
            return read(path, *args, **kwargs)
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            with patch('stemwise_engine.original_delivery.read_bundle', side_effect=unavailable), \
                    patch('stemwise_engine.original_delivery.durable_replace', side_effect=promote):
                with self.assertRaisesRegex(OSError, 'public readback unavailable'):
                    publish(self.workflow, self.job)
            self.assertEqual(self.job, job_before)
            self.assertEqual(previous.read_bytes(), recovery)
            self.assertTrue(any(path.read_bytes() == before for path in self.workflow.history.rglob('*.zip')))
            promoted = alias.read_bytes(), alias.stat().st_mtime_ns
            self.assertEqual(len(publish(self.workflow, self.job)), 1)
            self.assertEqual((alias.read_bytes(), alias.stat().st_mtime_ns), promoted)

    @unittest.skipUnless(hasattr(os, 'fork'), 'The supported PC publisher runs in WSL')
    def test_killed_publisher_preserves_current_zip_and_retry_cleans_private_staging(self):
        alias, previous = self.prepare_artwork_repair()
        before, recovery = alias.read_bytes(), previous.read_bytes()
        child = os.fork()
        if child == 0:
            try:
                with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True), \
                        patch('stemwise_engine.original_delivery.durable_replace', side_effect=lambda *_: os._exit(77)):
                    publish(self.workflow, self.job)
            finally:
                os._exit(78)
        _, status = os.waitpid(child, 0)
        self.assertEqual(os.waitstatus_to_exitcode(status), 77)
        self.assertEqual(alias.read_bytes(), before)
        self.assertEqual(previous.read_bytes(), recovery)
        self.assertEqual(set(alias.parent.iterdir()), {alias})
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            self.assertEqual(len(publish(self.workflow, self.job)), 1)
        self.assertEqual(read_bundle(alias, self.workflow._runtime_config())[0], self.document)
        self.assertEqual(set(alias.parent.iterdir()), {alias})
        self.assertFalse(list(self.workflow._finalization_staging_root().iterdir()))
        self.assertEqual(previous.read_bytes(), recovery)

    def test_artwork_repair_does_not_adopt_wrong_existing_alias(self):
        alias, _ = self.prepare_artwork_repair()
        wrong = copy.deepcopy(self.document)
        wrong['metadata']['title'] = 'Unrelated owner package'
        self.write_complete_alias(alias, wrong)
        saved = alias.read_bytes()
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            with self.assertRaisesRegex(ValueError, 'differs'):
                publish(self.workflow, self.job)
        self.assertEqual(alias.read_bytes(), saved)

    def test_selection_change_during_bundle_preparation_preserves_public_and_rollback(self):
        self.assert_selection_change_preserves_public([{'videoId': self.workflow.video_id, 'source': 'youtube'}])

    def test_selection_removal_during_bundle_preparation_preserves_public_and_rollback(self):
        self.assert_selection_change_preserves_public([{'videoId': 'lmnopqrstuv', 'source': 'youtube'}])

    def assert_selection_change_preserves_public(self, playlist):
        import stemwise_engine.native_delivery as native_delivery
        alias, previous = self.prepare_artwork_repair()
        before, recovery = alias.read_bytes(), previous.read_bytes()
        create = native_delivery.create_bundle
        def prepare(*args):
            create(*args)
            selection = self.fixture.library/'comparison-results/library-files.json'
            value = json.loads(selection.read_bytes())
            value['playlist'] = playlist
            self.fixture.write(selection, value)
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True), \
                patch.object(native_delivery, 'create_bundle', side_effect=prepare):
            with self.assertRaisesRegex(ValueError, 'recording changed'):
                publish(self.workflow, self.job)
        self.assertEqual(alias.read_bytes(), before)
        self.assertEqual(previous.read_bytes(), recovery)

    def write_complete_alias(self, path, document):
        from stemwise_engine.original_delivery import create_bundle, prepare_original
        metadata = self.workflow.runtime/'wrong-child.zip'
        create_child_zip(metadata, document, self.workflow.runtime/'cover.png', self.workflow._runtime_config())
        audio, identity = prepare_original(self.fixture.audio, document, self.workflow.runtime)
        create_bundle(path, metadata, audio, identity, self.workflow._runtime_config(), self.workflow.runtime)

    def prepare_two_aliases(self):
        self.fixture.write(self.fixture.library / 'comparison-results/library-files.json', {
            'catalogueSha256': sha256_file(self.fixture.library/'catalogue.json'),
            'flac': [{'trackId': 11, 'file': 'audio/qobuz/11/native.flac'}],
            'playlist': [{'videoId': 'abcdefghijk', 'source': 'qobuz', 'trackId': 11},
                         {'videoId': 'lmnopqrstuv', 'source': 'qobuz', 'trackId': 11}]})
        first, previous = self.prepare_artwork_repair()
        return first, first.parent / 'lmnopqrstuv.stemwise.zip', previous

    def test_wrong_later_alias_preserves_every_previous_alias(self):
        first, second, _ = self.prepare_two_aliases()
        before = first.read_bytes()
        wrong = copy.deepcopy(self.document)
        wrong['source']['videoId'] = 'lmnopqrstuv'
        wrong['metadata']['title'] = 'Unrelated owner package'
        self.write_complete_alias(second, wrong)
        saved = second.read_bytes()
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            with self.assertRaisesRegex(ValueError, 'differs'):
                publish(self.workflow, self.job)
        self.assertEqual(first.read_bytes(), before)
        self.assertEqual(second.read_bytes(), saved)

    def test_interrupted_alias_repair_resumes_without_rewriting_finished_alias(self):
        import stemwise_engine.original_delivery as original_delivery
        first, second, previous = self.prepare_two_aliases()
        old_second = second.read_bytes()
        real_replace = original_delivery.durable_replace
        def interrupted(source, path):
            if path == second:
                raise OSError('interrupted second alias')
            return real_replace(source, path)
        with patch.object(self.workflow, '_validated_snapshot_is_current', return_value=True):
            with patch.object(original_delivery, 'durable_replace', interrupted):
                with self.assertRaisesRegex(OSError, 'second alias'):
                    publish(self.workflow, self.job)
            self.assertEqual(read_bundle(first, self.workflow._runtime_config())[0], self.document)
            self.assertEqual(second.read_bytes(), old_second)
            stamp = first.stat().st_mtime_ns
            self.assertEqual(len(publish(self.workflow, self.job)), 2)
            self.assertEqual(first.stat().st_mtime_ns, stamp)
            self.assertEqual(read_bundle(second, self.workflow._runtime_config())[0]['metadata'], self.document['metadata'])
        self.assertTrue(previous.is_file())


if __name__ == '__main__':
    unittest.main()
