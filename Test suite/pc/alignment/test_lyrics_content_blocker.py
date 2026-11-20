"""Content uncertainty exits through the real durable workflow boundary, without models."""
import hashlib
import json
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from test_future_song import WORKSPACE, workflow_fixture
import stemwise_engine.editorial_review as editorial_review
import stemwise_engine.future_song as future_song
from stemwise_engine.workflow_records import LyricsContentBlocker


VIDEO = 'abcdefghijk'
ATTEMPT = 'a' * 32


class LyricsContentBlockerWorkflowTests(unittest.TestCase):
    def fixture(self, root, *, state='ALIGNING_LYRICS', seed=True):
        workflow = workflow_fixture(root)
        workflow.root = root / 'future-songs'
        workflow.jobs = workflow.root / 'jobs'
        workflow.owner = workflow.root / 'owner-input'
        workflow.runtime = root / 'runtime'
        workflow.ready = root / 'Ready to Import'
        workflow.reports = root / 'Engine telemetry'
        workflow.config = {'projectDir': str(root / 'builder'), 'outputDir': str(workflow.runtime)}
        workflow.config_path = root / 'builder/config.json'
        workflow._song_policy_matches = lambda _job: True
        workflow._write_task = Mock()
        workflow._review_inputs = Mock(return_value={})
        workflow._validated_snapshot_is_current = Mock(return_value=False)
        workflow._commit_failed_alignment_attempt = Mock()
        workflow._record_validation_failure = Mock()
        workflow.review_construction.endpoint_failure_source = Mock(return_value={'reason': 'exhausted-endpoint-cascade'})
        workflow.review_construction.create_endpoint_review_attempt = Mock()
        workflow._require_selected_audio = Mock()
        if seed:
            for path, raw in (
                (workflow._job_dir(VIDEO) / 'drafts/original.txt', b'Approved repeated line\nApproved repeated line\n'),
                (workflow._job_dir(VIDEO) / 'drafts/lyrics.meta.json', json.dumps({'videoId': VIDEO,
                    'originalLanguage': 'en', 'originalScript': 'Latn',
                    'sources': {'original': {'name': 'Reviewed source'}}}).encode('utf-8')),
                (workflow._job_dir(VIDEO) / 'agent-review.json', b'{"authorityOutcome":"pending"}\n'),
                (workflow._owner_sidecar_path(VIDEO), b'{"tracks":[{"kind":"original","format":"plain"}]}\n'),
                (workflow.analysis_temp / 'current-evidence/cached-unprompted.json', b'{"evidence":"retained"}\n'),
                (workflow.ready / f'{VIDEO}.stemwise.zip', b'previously completed independent delivery'),
            ):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
            future_song.atomic_write_json(workflow._job_path(VIDEO), {
                'videoId': VIDEO, 'policyVersion': editorial_review.POLICY_VERSION,
                'state': state, 'deliveryAllowed': False,
                'activeAlignmentAttemptId': ATTEMPT,
                'endpointReview': {'attemptId': 'b' * 32, 'sourceAlignmentRunId': ATTEMPT},
            })
        return workflow

    @staticmethod
    def retained_bytes(workflow):
        paths = (workflow._job_dir(VIDEO) / 'drafts/original.txt',
                 workflow._job_dir(VIDEO) / 'drafts/lyrics.meta.json',
                 workflow._job_dir(VIDEO) / 'agent-review.json',
                 workflow._owner_sidecar_path(VIDEO),
                 workflow.analysis_temp / 'current-evidence/cached-unprompted.json',
                 workflow.ready / f'{VIDEO}.stemwise.zip')
        return {path: path.read_bytes() for path in paths}

    @staticmethod
    def blocker(workflow):
        plain = workflow._job_dir(VIDEO) / 'drafts/original.txt'
        return LyricsContentBlocker('wrong-or-uncertain-words', 'pronunciation-unresolved', binding={
            'canonicalPlainSha256': hashlib.sha256(plain.read_bytes()).hexdigest(),
            'audioSha256': 'c' * 64, 'sourcePhysicalIndices': [0, 1],
        })

    def assert_durable_block(self, workflow, error, retained, *, attempt_id=ATTEMPT, source_stage=None):
        restarted = self.fixture(workflow.workspace_root, seed=False)
        job = restarted._load_job(VIDEO)
        self.assertEqual(job['state'], 'NEEDS_LYRICS')
        outcome = {'code': 'lyrics-content-unresolved', 'reasons': list(error.reasons),
                   'draftSha256': restarted._recording_draft_sha(job)}
        if error.binding is not None:
            outcome['contentBinding'] = error.binding
        if source_stage is not None:
            outcome['sourceStage'] = source_stage
        self.assertEqual(job['outcome'], outcome)
        self.assertEqual(job.get('lastAlignmentAttemptId'), attempt_id)
        self.assertNotIn('activeAlignmentAttemptId', job)
        self.assertNotIn('error', job)
        status = restarted.status(VIDEO)
        self.assertEqual(status['state'], 'NEEDS_LYRICS')
        self.assertEqual(status['outcome'], job['outcome'])
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_recording_evidence_content_exit_persists_without_public_child_body_or_audio_changes(self):
        import sys
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow = self.fixture(Path(name), state='WAITING_FOR_EDITORIAL')
            sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
            from process_fixture import prepare_runtime_layout
            project = Path(workflow.config['projectDir'])
            self.assertEqual(project.parent, Path(name))
            prepare_runtime_layout(project)
            job = workflow._load_job(VIDEO)
            job.pop('activeAlignmentAttemptId')
            job.pop('endpointReview')
            workflow._save_job(job)
            metadata = workflow._job_dir(VIDEO) / 'drafts/lyrics.meta.json'
            future_song.atomic_write_json(metadata, {'videoId': VIDEO, 'originalLanguage': 'en',
                'originalScript': 'Latn', 'sources': {'original': {'name': 'Reviewed source'}}})
            workflow._runtime_config = lambda: workflow.config
            workflow._alignment_worker_environment = Mock(return_value={})
            workflow._pipeline = Mock()
            audio = Path(name) / 'analysis.wav'
            audio.write_bytes(b'unchanged analyzed source audio')
            workflow._completed_analysis_audio = Mock(return_value={
                'path': audio, 'sha256': hashlib.sha256(audio.read_bytes()).hexdigest()})
            workflow._review_inputs.return_value = {
                'draftSha256': workflow._recording_draft_sha(job),
                'originalMetadataSha256': hashlib.sha256(metadata.read_bytes()).hexdigest()}
            workflow.acquisition = Mock()
            retained = {**self.retained_bytes(workflow), audio: audio.read_bytes(), metadata: metadata.read_bytes()}
            response = {'status': 'lyrics-content-unresolved',
                        'reasons': ['word-identity-ambiguous', 'PRIVATE lyric body must never become a reason'],
                        'privateText': 'PRIVATE lyric body must never become a reason'}
            with patch('stemwise_engine.future_song.run_process_tree', return_value=SimpleNamespace(returncode=1,
                    stdout=json.dumps(response), stderr='PRIVATE acoustic traceback')) as child:
                result = workflow.recording_evidence(VIDEO)
            child.assert_called_once()
            workflow.acquisition.return_value.continue_after_content_rejection.assert_called_once()
            self.assertEqual(result['state'], 'WAITING_FOR_EDITORIAL')
            current = workflow._load_job(VIDEO)
            self.assertEqual(current['lyricContentLimitation']['reasons'], ['word-identity-ambiguous'])
            self.assertNotIn('outcome', current)
            self.assertNotIn('PRIVATE', workflow._job_path(VIDEO).read_text(encoding='utf-8'))
            self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_child_content_status_propagates_without_sidecar_publication(self):
        import sys
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow = self.fixture(Path(name))
            sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
            from process_fixture import prepare_runtime_layout
            project = Path(workflow.config['projectDir'])
            self.assertEqual(project.parent, Path(name))
            prepare_runtime_layout(project)
            error = self.blocker(workflow)
            for path in (Path(workflow.config['projectDir']) / '.venv-lyrics-align/bin/python',
                         Path(workflow.config['projectDir']) / 'src/lyrics_align_pc.py',
                         workflow.runtime / 'children' / f'{VIDEO}.stemwise.zip'):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fake expensive runtime boundary')
            state = {'attemptId': ATTEMPT, 'status': error.code,
                     'reasons': list(error.reasons), 'contentBinding': error.binding}
            future_song.atomic_write_json(workflow._alignment_state_path(VIDEO), state)
            retained = self.retained_bytes(workflow)
            before_job = workflow._job_path(VIDEO).read_bytes()
            with patch.object(workflow, '_alignment_worker_environment', return_value={}), \
                    patch('stemwise_engine.future_song.run_process_tree', return_value=SimpleNamespace(
                        returncode=2, stdout='', stderr='private lyric body must not be public')) as child:
                with self.assertRaises(LyricsContentBlocker) as caught:
                    workflow._align_future_lyrics(VIDEO, ATTEMPT, 'c' * 64, 'd' * 64,
                                                 error.binding['canonicalPlainSha256'], Path(name) / 'audio.wav')
            child.assert_called_once()
            self.assertEqual(caught.exception.reasons, error.reasons)
            self.assertEqual(caught.exception.binding, error.binding)
            self.assertNotIn('private lyric body', str(caught.exception))
            self.assertEqual(workflow._job_path(VIDEO).read_bytes(), before_job)
            self.assertEqual(self.retained_bytes(workflow), retained)

    def test_validate_persists_content_blocker_and_never_recovers_endpoints_or_relabels(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow = self.fixture(Path(name))
            error = self.blocker(workflow)
            retained = self.retained_bytes(workflow)
            job = workflow._load_job(VIDEO)
            job['outcome'] = {'code': 'line-unresolved', 'obsoleteReason': 'retained-old-detail'}
            workflow._save_job(job)
            workflow._validate_impl = Mock(side_effect=error)
            workflow.endpoint_evidence = Mock(side_effect=AssertionError('must not recover uncertain words'))
            with self.assertRaises(LyricsContentBlocker) as caught:
                workflow._validate_enhancements_action(VIDEO)
            self.assertIs(caught.exception, error)
            workflow._commit_failed_alignment_attempt.assert_called_once_with(VIDEO, error.code)
            workflow._record_validation_failure.assert_not_called()
            workflow.endpoint_evidence.assert_not_called()
            workflow.review_construction.endpoint_failure_source.assert_not_called()
            workflow.review_construction.create_endpoint_review_attempt.assert_not_called()
            self.assert_durable_block(workflow, error, retained)

    def test_failed_run_report_write_cannot_prevent_the_durable_content_block(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow = self.fixture(Path(name))
            error = self.blocker(workflow)
            retained = self.retained_bytes(workflow)
            workflow._validate_impl = Mock(side_effect=error)
            workflow._commit_failed_alignment_attempt.side_effect = OSError('isolated immutable report write failure')
            workflow.endpoint_evidence = Mock(side_effect=AssertionError('must not recover uncertain words'))
            with self.assertRaisesRegex(OSError, 'isolated immutable report write failure'):
                workflow._validate_enhancements_action(VIDEO)
            workflow._commit_failed_alignment_attempt.assert_called_once_with(VIDEO, error.code)
            workflow._record_validation_failure.assert_not_called()
            workflow.endpoint_evidence.assert_not_called()
            self.assert_durable_block(workflow, error, retained)

    def test_temporary_cleanup_failure_preserves_the_durable_block_and_retained_attempt(self):
        from stemwise_engine.common import GENERATED_MANIFEST_NAME
        for fault in ('os-error', 'manifest-identity'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
                workflow = self.fixture(Path(name))
                error = self.blocker(workflow)
                attempt = workflow._alignment_attempt_temp_dir(VIDEO, ATTEMPT)
                attempt.mkdir(parents=True)
                (attempt / GENERATED_MANIFEST_NAME).write_bytes(b'{}\n')
                (attempt / 'pending.private.json').write_bytes(b'{"retained":"attempt evidence"}\n')
                retained = {**self.retained_bytes(workflow),
                            **{path: path.read_bytes() for path in attempt.iterdir()}}
                workflow._validate_impl = Mock(side_effect=error)
                boundary = patch('stemwise_engine.future_song.remove_manifested_directory',
                    side_effect=OSError('isolated temporary cleanup failure')) if fault == 'os-error' else nullcontext()
                failure_type = OSError if fault == 'os-error' else future_song.FutureSongError
                message = 'isolated temporary cleanup failure' if fault == 'os-error' else 'manifest identity'
                with boundary, self.assertRaisesRegex(failure_type, message):
                    workflow._validate_enhancements_action(VIDEO)
                workflow._record_validation_failure.assert_not_called()
                self.assert_durable_block(workflow, error, retained)

    def test_direct_endpoint_callers_durably_block_and_preserve_inputs(self):
        for entry in ('endpoint-evidence', 'endpoint-review'):
            with self.subTest(entry=entry), tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
                workflow = self.fixture(Path(name), state='LINE_UNRESOLVED')
                error = self.blocker(workflow)
                retained = self.retained_bytes(workflow)
                # The review service is the expensive evidence boundary; orchestration,
                # exact exception propagation, worker lock and job publication stay real.
                with patch('stemwise_engine.future_song.EndpointReviewService.validate', side_effect=error) as review:
                    with self.assertRaises(LyricsContentBlocker) as caught:
                        if entry == 'endpoint-evidence':
                            workflow.endpoint_evidence(VIDEO, vocal=True)
                        else:
                            workflow._validate_endpoint_review(workflow._load_job(VIDEO), evidence_only=True, vocal=True)
                self.assertIs(caught.exception, error)
                review.assert_called_once()
                workflow._record_validation_failure.assert_not_called()
                workflow.review_construction.create_endpoint_review_attempt.assert_not_called()
                self.assert_durable_block(workflow, error, retained)

    def test_task_write_failure_cannot_replace_an_already_durable_content_block(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow = self.fixture(Path(name))
            error = self.blocker(workflow)
            retained = self.retained_bytes(workflow)
            workflow._validate_impl = lambda _video: workflow._validate_endpoint_review(
                workflow._load_job(VIDEO), evidence_only=True, vocal=True)
            workflow._write_task.side_effect = OSError('isolated task write failure')
            with patch('stemwise_engine.future_song.EndpointReviewService.validate', side_effect=error):
                with self.assertRaisesRegex(OSError, 'isolated task write failure'):
                    workflow._validate_enhancements_action(VIDEO)
            workflow._record_validation_failure.assert_not_called()
            self.assert_durable_block(workflow, error, retained)

    def test_endpoint_recovery_discovered_content_uncertainty_replaces_timing_only_outcome(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow = self.fixture(Path(name))
            error = self.blocker(workflow)
            retained = self.retained_bytes(workflow)
            workflow._validate_impl = Mock(side_effect=future_song.FutureSongOutcomeError(
                'LINE_UNRESOLVED', 'line-unresolved'))
            workflow.review_construction.endpoint_failure_source.side_effect = error
            workflow.endpoint_evidence = Mock(side_effect=AssertionError('must not predict uncertain words'))
            with self.assertRaises(LyricsContentBlocker) as caught:
                workflow._validate_enhancements_action(VIDEO)
            self.assertIs(caught.exception, error)
            workflow.endpoint_evidence.assert_not_called()
            workflow.review_construction.create_endpoint_review_attempt.assert_not_called()
            workflow._record_validation_failure.assert_not_called()
            self.assert_durable_block(workflow, error, retained)

    def test_delivered_record_does_not_reopen_content_when_package_readback_fails(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow = self.fixture(Path(name), state='DELIVERED')
            job = workflow._load_job(VIDEO)
            # Actual pending/resolved continuation has its own public workflow fixture in test_available_completion.
            job.pop('endpointReview')
            future_song.atomic_write_json(workflow._job_path(VIDEO), job)
            workflow._validated_snapshot_is_current.return_value = True
            workflow._validate_impl = Mock(side_effect=AssertionError('completed result must not reopen'))
            before = {path: path.read_bytes() for path in Path(name).rglob('*') if path.is_file()}
            result = workflow.validate(VIDEO)
            self.assertEqual(result['state'], 'STATUS_ERROR')
            self.assertEqual(result['statusError'], 'validation-incomplete-or-stale')
            self.assertEqual(workflow._load_job(VIDEO)['state'], 'DELIVERED')
            self.assertNotIn('delivery', result)
            workflow._validate_impl.assert_not_called()
            workflow._commit_failed_alignment_attempt.assert_not_called()
            self.assertEqual({path: path.read_bytes() for path in Path(name).rglob('*') if path.is_file()}, before)

    def test_unchanged_durable_block_rejects_all_recognition_and_timing_entry_points_before_workers(self):
        for entry in ('enhancements', 'recording-evidence', 'endpoint-evidence', 'endpoint-review'):
            with self.subTest(entry=entry), tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
                first = self.fixture(Path(name))
                error = self.blocker(first)
                first._record_validation_outcome(VIDEO, error)
                workflow = self.fixture(Path(name), seed=False)
                retained = self.retained_bytes(workflow)
                workflow._pipeline = Mock(side_effect=AssertionError('blocked before pipeline'))
                workflow._align_future_lyrics = Mock(side_effect=AssertionError('blocked before alignment'))
                with patch('stemwise_engine.future_song.run_process_tree', side_effect=AssertionError('blocked before recognition')) as child, \
                        patch('stemwise_engine.future_song.EndpointReviewService.validate', side_effect=AssertionError('blocked before endpoint inference')) as endpoint:
                    with self.assertRaises(LyricsContentBlocker):
                        if entry == 'enhancements':
                            workflow._validate_enhancements_action(VIDEO)
                        elif entry == 'recording-evidence':
                            workflow.recording_evidence(VIDEO)
                        elif entry == 'endpoint-evidence':
                            workflow.endpoint_evidence(VIDEO, vocal=True)
                        else:
                            workflow._validate_endpoint_review(workflow._load_job(VIDEO), evidence_only=True, vocal=True)
                workflow._pipeline.assert_not_called()
                workflow._align_future_lyrics.assert_not_called()
                child.assert_not_called()
                endpoint.assert_not_called()
                self.assert_durable_block(workflow, error, retained)

    def test_missing_recording_metadata_still_saves_block_without_inventing_input_identity(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow = self.fixture(Path(name))
            error = self.blocker(workflow)
            metadata = workflow._job_dir(VIDEO) / 'drafts/lyrics.meta.json'
            retained = self.retained_bytes(workflow)
            del retained[metadata]
            metadata.unlink()
            with self.assertRaisesRegex(ValueError, 'Input file is unavailable: lyrics.meta.json'):
                workflow._record_validation_outcome(VIDEO, error)
            current = self.fixture(Path(name), seed=False)._load_job(VIDEO)
            self.assertEqual(current['state'], 'NEEDS_LYRICS')
            self.assertEqual(current['outcome'], {'code': error.code, 'reasons': list(error.reasons),
                'contentBinding': error.binding})
            self.assertEqual(current['lastAlignmentAttemptId'], ATTEMPT)
            self.assertNotIn('activeAlignmentAttemptId', current)
            with patch('stemwise_engine.future_song.run_process_tree', side_effect=AssertionError('no work authorized')) as child:
                with self.assertRaises(LyricsContentBlocker) as caught:
                    workflow._require_changed_lyrics_content(current)
            self.assertEqual(caught.exception.binding, error.binding)
            child.assert_not_called()
            self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_changed_actual_draft_reaches_normal_authority_validation_without_implying_approval(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow = self.fixture(Path(name))
            workflow._record_validation_outcome(VIDEO, self.blocker(workflow))
            original = workflow._job_dir(VIDEO) / 'drafts/original.txt'
            original.write_bytes(b'Corrected performed words need independent approval\n')
            workflow._validate_authority_outcome = Mock(side_effect=future_song.FutureSongError('normal authority validation reached'))
            workflow._align_future_lyrics = Mock(side_effect=AssertionError('authority still required'))
            with self.assertRaisesRegex(future_song.FutureSongError, 'normal authority validation reached'):
                workflow._validate_impl(VIDEO)
            workflow._validate_authority_outcome.assert_called_once()
            workflow._align_future_lyrics.assert_not_called()
            self.assertEqual(workflow._load_job(VIDEO)['state'], 'NEEDS_LYRICS')


if __name__ == '__main__':
    unittest.main()
