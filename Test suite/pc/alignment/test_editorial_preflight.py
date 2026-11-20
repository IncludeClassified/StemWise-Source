import copy
import io
import json
import tempfile
import unittest
import wave
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from test_future_song import WORKSPACE, workflow_fixture
import stemwise_engine.future_song as future_song
import stemwise_engine.workflow_command as workflow_command
import stemwise_engine.recording_evidence as recording_evidence
from stemwise_engine.common import atomic_write_json, sha256_file
import test_artwork_recording as artwork_tests


class EditorialPreflightTests(unittest.TestCase):
    def fixture(self, root, language='en'):
        workflow = workflow_fixture(root)
        job = {'videoId': 'AAAAAAAAAAA', 'state': 'VALIDATION_FAILED',
               'error': 'future-song-error', 'policyVersion': future_song.editorial.POLICY_VERSION,
               'policy': workflow._policy_binding()}
        directory = workflow._job_dir(job['videoId'])
        (directory/'drafts').mkdir(parents=True)
        (directory/'drafts/original.txt').write_bytes(b'one two three four five six\n')
        atomic_write_json(workflow._job_path(job['videoId']), job)
        workflow._pipeline = Mock()
        workflow._review_inputs = Mock(return_value={'audioSha256': 'a'*64, 'draftSha256': 'd'*64, 'originalMetadataSha256': 'e'*64, 'sourcesSha256': 'f'*64, 'plainDraftLayout': {}})
        workflow._alignment_worker_environment = Mock(return_value={})
        workflow._completed_analysis_audio = Mock(return_value={
            'path': str(root/'audio.wav'), 'sha256': 'a'*64})
        workflow._runtime_config = lambda: workflow.config
        metadata = {'videoId': job['videoId'], 'originalLanguage': language,
                    'originalScript': 'Latn', 'sources': {'original': {'name': 'Source'}}}
        (directory/'drafts/lyrics.meta.json').write_text(json.dumps(metadata), encoding='utf-8')
        return workflow, job, directory, metadata

    def test_invalid_inputs_reject_before_runtime_without_mutating_pending_work(self):
        for fault in ('original-crlf', 'component-languages'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
                workflow, job, directory, meta = self.fixture(Path(name), 'tr')
                if fault == 'original-crlf':
                    (directory/'drafts/original.txt').write_bytes(b'one two three four five six\r\n')
                elif fault == 'component-languages':
                    meta['componentLanguages'] = ['tr', 'en']
                (directory/'drafts/lyrics.meta.json').write_text(json.dumps(meta), encoding='utf-8')
                before = artwork_tests.ArtworkRecordingTests._job_file_snapshot(Path(name), directory)
                state = copy.deepcopy(job)
                expected = {'original-crlf': 'original.txt: must use LF',
                            'component-languages': 'componentLanguages is allowed only'}[fault]
                with patch('stemwise_engine.future_song.run_process_tree', return_value=SimpleNamespace(
                        returncode=0, stdout='{}', stderr='')) as runtime:
                    with self.assertRaisesRegex((future_song.FutureSongError, ValueError), expected):
                        workflow.recording_evidence(job['videoId'])
                runtime.assert_not_called()
                workflow._review_inputs.assert_not_called()
                workflow._alignment_worker_environment.assert_not_called()
                workflow._pipeline.assert_not_called()
                self.assertEqual(workflow._load_job(job['videoId']), state)
                self.assertEqual(before, artwork_tests.ArtworkRecordingTests._job_file_snapshot(Path(name), directory))

    def test_pending_comparison_allows_missing_or_malformed_companions_without_approving_lyrics(self):
        for fault in ('missing', 'companion-crlf', 'companion-source'):
            with self.subTest(fault=fault):
                self._pending_comparison_preserves_independent_original(fault)

    def test_repeated_recording_request_binds_original_inputs_without_derived_companion_status(self):
        from lyric_content_fixtures import write_recognition
        for mutation in ('none', 'companion', 'original'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
                root = Path(name)
                workflow, job, directory, _ = self.fixture(root, 'tr')
                del workflow._review_inputs
                audio = root/'audio.wav'
                with wave.open(str(audio), 'wb') as stream:
                    stream.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
                    stream.writeframes(b'\0\0'*19200)
                identity = {'path': str(audio), 'sha256': sha256_file(audio), 'durationMs': 1200}
                workflow._completed_analysis_audio.return_value = identity
                atomic_write_json(workflow.runtime/'work/checkpoints'/f"{job['videoId']}.json",
                                  {'originalAudio': identity})
                atomic_write_json(directory/'sources.json', {'videoId': job['videoId']})
                calls = 0

                def recognize(**request):
                    evidence_root = request['workspace'].job_dir(job['videoId'])
                    fixture = write_recognition(evidence_root, 'one two three four five six', (0,), identity['sha256'])
                    value = json.loads(fixture.read_text(encoding='utf-8'))
                    value['fixtureIteration'] = calls
                    atomic_write_json(fixture, value)
                    fixture.replace(evidence_root/'qwen-structure-tr.private.json')
                    return 'one two three four five six', {'language': 'tr'}, False

                def compare(command, **_kwargs):
                    nonlocal calls
                    calls += 1
                    output = io.StringIO()
                    with redirect_stdout(output):
                        code = recording_evidence.main(command[3:])
                    if calls == 2 and mutation != 'none':
                        path = directory/'drafts'/('original.txt' if mutation == 'original' else 'translation-en.txt')
                        path.write_text('changed during recognition\n', encoding='utf-8')
                    return SimpleNamespace(returncode=code, stdout=output.getvalue(), stderr='')

                with patch('stemwise_engine.future_song.read_child', return_value=({'metadata': {}}, None)), \
                        patch('stemwise_engine.editorial_admission.available_lyrics',
                              side_effect=AssertionError('recording requests must not read derived role status')), \
                        patch('stemwise_engine.recording_evidence._load_or_run_qwen3_structure_transcript',
                              side_effect=recognize), \
                        patch('stemwise_engine.future_song.run_process_tree', side_effect=compare):
                    workflow.recording_evidence(job['videoId'])
                    prior = copy.deepcopy(workflow._load_job(job['videoId'])['recordingContentEvidence'])
                    if mutation == 'original':
                        with self.assertRaisesRegex(future_song.FutureSongError, 'inputs changed during recognition'):
                            workflow.recording_evidence(job['videoId'])
                        self.assertEqual(workflow._load_job(job['videoId'])['recordingContentEvidence'], prior)
                    else:
                        workflow.recording_evidence(job['videoId'])
                        current = workflow._load_job(job['videoId'])
                        self.assertEqual(current['recordingEvidence']['status'], 'complete')
                        self.assertEqual(current['recordingContentEvidence']['draftSha256'], prior['draftSha256'])
                        self.assertNotEqual(current['recordingContentEvidence']['evidence'], prior['evidence'])
                self.assertEqual(calls, 2)
                self.assertEqual(sha256_file(audio), identity['sha256'])
                stored = workflow._load_job(job['videoId'])
                self.assertNotIn('validation', stored)
                self.assertNotIn('delivery', stored)

    def _pending_comparison_preserves_independent_original(self, fault):
        with tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
            workflow, job, directory, meta = self.fixture(Path(name), 'tr')
            if fault != 'missing':
                (directory/'drafts/translation-en.txt').write_bytes(
                    b'one two three four five six\r\n' if fault == 'companion-crlf'
                    else b'one two three four five six\n')
                if fault == 'companion-crlf':
                    meta['sources']['translationEn'] = {'name': 'Reviewed translation'}
                atomic_write_json(directory/'drafts/lyrics.meta.json', meta)
            audio = Path(name)/'audio.wav'
            with wave.open(str(audio), 'wb') as stream:
                stream.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
                stream.writeframes(b'\0\0'*19200)
            workflow._completed_analysis_audio.return_value = {
                'path': str(audio), 'sha256': sha256_file(audio), 'durationMs': 1200}
            before = artwork_tests.ArtworkRecordingTests._job_file_snapshot(Path(name), directory)
            def recognize(**request):
                from lyric_content_fixtures import write_recognition
                retained = request['workspace'].job_dir(job['videoId'])/'qwen-structure-tr.private.json'
                fixture = write_recognition(retained.parent, 'one two three four five six', (0,),
                                            sha256_file(audio))
                fixture.replace(retained)
                return 'one two three four five six', {'language': 'tr'}, False
            def compare(command, **_kwargs):
                self.assertEqual(command[1:3], ['-m', 'stemwise_engine.recording_evidence'])
                output = io.StringIO()
                with redirect_stdout(output):
                    code = recording_evidence.main(command[3:])
                return SimpleNamespace(returncode=code, stdout=output.getvalue(), stderr='')
            with patch('stemwise_engine.recording_evidence._load_or_run_qwen3_structure_transcript',
                       side_effect=recognize) as recognizer, patch(
                    'stemwise_engine.future_song.run_process_tree', side_effect=compare) as runtime:
                result = workflow.recording_evidence(job['videoId'])
            runtime.assert_called_once()
            recognizer.assert_called_once()
            self.assertTrue(result['structureSupport']['accepted'])
            self.assertTrue(result['reviewRequired'])
            self.assertEqual(result['state'], 'VALIDATION_FAILED')
            stored = workflow._load_job(job['videoId'])
            evidence = stored['recordingContentEvidence']
            retained = workflow._alignment_state_path(job['videoId']).parent/evidence['evidence']['path']
            self.assertEqual(evidence['evidence']['sha256'], sha256_file(retained))
            self.assertEqual(evidence['audioSha256'], sha256_file(audio))
            self.assertEqual(stored['recordingEvidence']['status'], 'complete')
            self.assertEqual(stored['state'], 'VALIDATION_FAILED')
            self.assertNotIn('validation', stored)
            self.assertNotIn('delivery', stored)
            self.assertEqual(before, artwork_tests.ArtworkRecordingTests._job_file_snapshot(Path(name), directory))
            from stemwise_engine.editorial_admission import available_lyrics
            projection, capabilities = available_lyrics(workflow, stored,
                {'path': str(audio), 'sha256': sha256_file(audio), 'durationMs': 1200})
            self.assertEqual(capabilities['content'], 'admitted')
            self.assertEqual([track['kind'] for track in projection['tracks']], ['original'])
            state = 'missing' if fault == 'missing' else 'invalid'
            self.assertEqual(capabilities['companionAdmission']['translation']['state'], state)
            original_only, needs_translation, _ = workflow._preflight_drafts(stored, roles=frozenset({'original'}))
            self.assertTrue(needs_translation)
            self.assertEqual(original_only['tracks'][0], projection['tracks'][0])
            from stemwise_engine.editorial_admission import require_completed_lyrics
            from stemwise_engine.workflow_records import WorkflowInputError
            with self.assertRaises(WorkflowInputError) as pending:
                require_completed_lyrics(projection, duration_ms=1200)
            self.assertEqual(pending.exception.failure_details['reasonCode'], 'line-unresolved')
            self.assertEqual(before, artwork_tests.ArtworkRecordingTests._job_file_snapshot(Path(name), directory))


class AutomaticEndpointContinuationTests(unittest.TestCase):
    def fixture(self, root, *, reason='exhausted-endpoint-cascade'):
        workflow = workflow_fixture(root)
        job = {'videoId': 'AAAAAAAAAAA', 'state': 'ALIGNING_LYRICS',
               'activeAlignmentAttemptId': 'a'*32,
               'policyVersion': future_song.editorial.POLICY_VERSION,
               'policy': workflow._policy_binding()}
        atomic_write_json(workflow._job_path(job['videoId']), job)
        workflow._validated_snapshot_is_current = lambda _: False
        workflow._validate_impl = Mock(side_effect=future_song.FutureSongOutcomeError(
            'LINE_UNRESOLVED', 'line-unresolved'))
        workflow._commit_failed_alignment_attempt = Mock()
        workflow._cleanup_alignment_attempt_temp = Mock()
        workflow._write_task = Mock()
        workflow.review_construction.endpoint_failure_source = lambda *_args, **_kwargs: {'reason': reason}
        def pointer(current):
            current['endpointReview'] = {'attemptId': 'b'*32, 'sourceAlignmentRunId': 'a'*32}
            workflow._save_job(current)
        workflow.review_construction.create_endpoint_review_attempt = pointer
        workflow.endpoint_evidence = Mock(return_value={'automaticallySelected': True})
        workflow._validate_endpoint_review = Mock(return_value={'state': 'WAITING_FOR_DISPLAY_REVIEW'})
        return workflow, job

    def test_new_exhausted_alignment_continues_through_existing_publication_gate(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
            workflow, job = self.fixture(Path(name))
            # Public Validate may finish audio with partial lyrics; this gate owns enhancement recovery.
            result = workflow._validate_enhancements_action(job['videoId'])
            workflow.endpoint_evidence.assert_called_once_with(job['videoId'], vocal=True)
            workflow._validate_endpoint_review.assert_called_once()
            self.assertEqual(result['state'], 'WAITING_FOR_DISPLAY_REVIEW')
            stored = workflow._load_job(job['videoId'])
            self.assertEqual(stored['state'], 'LINE_UNRESOLVED')
            self.assertEqual(stored['endpointReview']['sourceAlignmentRunId'], 'a'*32)
            self.assertEqual(stored['lastAlignmentAttemptId'], 'a'*32)
            self.assertNotIn('activeAlignmentAttemptId', stored)
            self.assertNotIn('delivery', stored)

    def test_abstention_or_failed_selection_never_reports_success(self):
        for fault in ('abstain', 'forged-selection', 'technical', 'not-endpoints', 'stale-attempt',
                      'no-new-attempt'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
                workflow, job = self.fixture(Path(name), reason='different' if fault == 'not-endpoints'
                                               else 'exhausted-endpoint-cascade')
                if fault == 'abstain':
                    workflow.endpoint_evidence.return_value = {'automaticallySelected': False}
                elif fault == 'forged-selection':
                    workflow._validate_endpoint_review.side_effect = future_song.FutureSongError('stale selection')
                elif fault == 'technical':
                    workflow.endpoint_evidence.side_effect = future_song.FutureSongError('worker failure')
                elif fault == 'stale-attempt':
                    workflow.review_construction.create_endpoint_review_attempt = lambda current: workflow._save_job({
                        **current, 'endpointReview': {'sourceAlignmentRunId': 'c'*32}})
                elif fault == 'no-new-attempt':
                    job.pop('activeAlignmentAttemptId')
                    workflow._save_job(job)
                with self.assertRaises(future_song.FutureSongError):
                    workflow._validate_enhancements_action(job['videoId'])
                stored = workflow._load_job(job['videoId'])
                self.assertEqual(stored['state'], 'LINE_UNRESOLVED')
                self.assertEqual(stored['outcome']['code'], 'line-unresolved')
                self.assertNotIn('delivery', stored)
                if fault != 'forged-selection':
                    workflow._validate_endpoint_review.assert_not_called()
                if fault in ('not-endpoints', 'stale-attempt', 'no-new-attempt'):
                    workflow.endpoint_evidence.assert_not_called()


class NativeCliCompletionTests(unittest.TestCase):
    def test_status_preserves_verified_delivery_and_never_promotes_private_only_results(self):
        for allowed, expected in ((True, 'DELIVERED'), (False, 'DELIVERY_AUDIO_REQUIRED'),
                                  (None, 'DELIVERY_AUDIO_REQUIRED'), (1, 'DELIVERY_AUDIO_REQUIRED'),
                                  ('true', 'DELIVERY_AUDIO_REQUIRED')):
            with self.subTest(allowed=allowed), tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
                path = Path(name)/'job.json'
                path.write_bytes(b'preserved job')
                result = {'state': expected}
                if allowed is not None:
                    result['phoneDeliveryAllowed'] = allowed
                workflow = SimpleNamespace(video_id='AAAAAAAAAAA', _job_path=lambda _: path,
                                           status=lambda _: copy.deepcopy(result))
                output = io.StringIO()
                with patch('stemwise_engine.recording_workflow.RecordingWorkflow', return_value=workflow), redirect_stdout(output):
                    code = workflow_command.main(['--config', str(Path(name)/'config.json'),
                                             '--recording-key', 'qobuz:11', 'status'])
                self.assertEqual(code, 0)
                actual = json.loads(output.getvalue())
                self.assertEqual(actual['state'], expected)
                self.assertEqual(actual['phoneDeliveryAllowed'], False if allowed is None else allowed)
                self.assertEqual(path.read_bytes(), b'preserved job')
