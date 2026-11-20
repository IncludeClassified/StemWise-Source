"""Explicit replacement admission keeps a completed recording usable until new publication."""
import copy
import hashlib
import io
import json
from contextlib import ExitStack, contextmanager, redirect_stdout
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from stemwise_engine.common import atomic_write_json, json_bytes, sha256_file, WorkerRetirementError
from stemwise_engine.future_song import FutureSongWorkflow, FutureSongError
from stemwise_engine import workflow_command, job_ownership, original_delivery, pipeline as pipeline_module, youtube
from stemwise_engine.provenance import provenance_fingerprint
from workflow_fixture import initialized_workflow, nested_case
from alignment import test_available_completion as completion_fixture
from test_packages import song_document, PNG_1X1
from stemwise_engine.packages import create_child_zip, read_child as fixture_read_child
from stemwise_engine.original_delivery import read_bundle as fixture_read_bundle

ROOT = Path(__file__).resolve().parents[3]


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class AnalysisReplacementTests(unittest.TestCase):
    @contextmanager
    def completed_display_fixture(self):
        with patch('stemwise_engine.packages.read_child', side_effect=fixture_read_child), \
                patch.object(pipeline_module, 'read_child', side_effect=fixture_read_child), \
                patch('stemwise_engine.future_song.read_child', side_effect=fixture_read_child), \
                patch.object(original_delivery, 'read_bundle', side_effect=fixture_read_bundle):
            with patch.object(youtube, 'decode_analysis_audio', self.real_decode):
                case = nested_case(self, completion_fixture.AvailableCompletionTests)
            case.current_display_fixture()
            with patch.object(case.workflow, '_run_primary_worker',
                    side_effect=AssertionError('completed fixture must reuse retained timing')):
                self.assertEqual(case.workflow.validate(case.video)['state'], 'VALIDATED')
            yield case

    def test_display_validation_finalize_cleanup_then_replacement_requires_exact_pcm_hash(self):
        with self.completed_display_fixture() as case:
            workflow, video, owner, child = case.workflow, case.video, case.pipeline, case.child
            temporary = case.audio.parent
            expected_sha = sha256_file(case.audio)
            sidecar = workflow._owner_sidecar_path(video)
            validated = workflow._load_job(video)
            self.assertTrue(workflow._validated_snapshot_is_current(validated))
            with patch.object(youtube, 'decode_analysis_audio', self.real_decode):
                self.assertEqual(workflow.finalize(video)['state'], 'DELIVERED')
            self.assertFalse(temporary.exists())
            delivered = workflow._load_job(video)
            public = workflow.ready/child.name
            before = {path: path.read_bytes() for path in
                (workflow._job_path(video), child, owner.checkpoint_path(video), public, sidecar)}
            self.extract.assert_not_called()
            self.decode.assert_not_called()
            with self.assertRaisesRegex(FutureSongError, 'exact completed recording and delivery'):
                workflow.replace_analysis(video, expected_delivery_sha256=delivered['delivery']['sha256'],
                    expected_analysis_sha256='0'*64)
            self.assertEqual({path: path.read_bytes() for path in before}, before)
            self.extract.assert_not_called()
            self.decode.assert_not_called()
            self.assertEqual(workflow.replace_analysis(video,
                expected_delivery_sha256=delivered['delivery']['sha256'],
                expected_analysis_sha256=expected_sha)['state'], 'ANALYSIS_REPLACEMENT_PENDING')
            request = workflow._load_job(video)['analysisReplacement']
            self.assertEqual(request['analysisSha256'], expected_sha)
            self.assertEqual(request['deliverySha256'], delivered['delivery']['sha256'])
            self.assertEqual(public.read_bytes(), before[public])
            self.assertEqual(child.read_bytes(), before[child])
            self.assertEqual(owner.checkpoint_path(video).read_bytes(), before[owner.checkpoint_path(video)])
            self.extract.assert_called_once()
            self.decode.assert_called_once()

    def test_existing_policy_jobs_preserve_authority_and_reject_processing_changes(self):
        from stemwise_engine import editorial_review, lyrics_alignment, future_song, workflow_records
        policy = {'policyVersion': editorial_review.POLICY_VERSION,
            'lyrics': {'alignmentVersion': lyrics_alignment.ALIGNMENT_VERSION},
            'delivery': {'reviewEvidenceRetained': True}}
        actions = ['New', 'Source', 'Artwork', 'RecordingEvidence', 'Validate', 'Finalize', 'Retry']
        for available in (actions, [*actions, 'ReplaceAnalysis']):
            with self.subTest(actions=available):
                emitted = {**policy, 'privateReports': {'workflowOperationLedger': {
                    'actions': available, 'outcomeCodes': sorted(workflow_records.OUTCOME_CODES)}}}
                job = self.workflow._load_job(self.video, require_active_policy=False)
                job['policy']['lyricsPolicySha256'] = hashlib.sha256(json_bytes(emitted)).hexdigest()
                atomic_write_json(self.paths[0], job)
                before = self.snapshot()
                status = self.command('status')
                self.assertIn('Finalize', status['permittedActions'])
                self.assertEqual(self.workflow._load_job(self.video)['policy'], job['policy'])
                self.assertEqual(self.snapshot(), before)
                for field in ('lyricsPolicySha256', 'schemaSha256'):
                    invalid = copy.deepcopy(job)
                    invalid['policy'][field] = '0'*64
                    atomic_write_json(self.paths[0], invalid)
                    with self.assertRaises(FutureSongError):
                        self.command('finalize')
                    self.assertEqual(json.loads(self.paths[0].read_bytes()), invalid)
                invalid = {**job, 'videoId': 'lmnopqrstuv'}
                atomic_write_json(self.paths[0], invalid)
                with self.assertRaises(FutureSongError):
                    self.command('finalize')
                self.assertEqual(json.loads(self.paths[0].read_bytes()), invalid)
                atomic_write_json(self.paths[0], job)
                with patch.object(future_song, 'WORKFLOW_OPERATION_ACTION_ORDER', ('New',)), \
                        patch.object(future_song, 'OUTCOME_CODES', frozenset({'new-report-outcome'})):
                    self.assertEqual(self.workflow._execution_policy_sha256(policy), self.workflow.policy_sha256)
                    self.assertEqual(self.workflow._load_job(self.video)['policy'], job['policy'])
                with patch.object(lyrics_alignment, 'ALIGNMENT_VERSION', 'changed-numerical-policy'):
                    with self.assertRaises(FutureSongError):
                        self.command('finalize')

    def test_decoder_fixture_restores_native_consumers(self):
        from stemwise_engine import recording_analysis, recording_source
        self.assertIs(recording_analysis.decode_analysis_audio, self.real_decode)
        self.assertIs(recording_source.decode_analysis_audio, self.real_decode)

    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.root = Path(self.stack.enter_context(tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work')))
        self.workflow = initialized_workflow(self.root)
        self.video = 'abcdefghijk'
        self.fresh = b'new exact float32 PCM boundary'
        self.historical = hashlib.sha256(b'unavailable historical float32').hexdigest()
        self.source_bytes = b'verified retained Original'
        self.producer = {'fixture': 'unavailable numerical producer'}
        self.real_decode = youtube.decode_analysis_audio
        # Import decoder consumers before replacing the provider they bind at import.
        from stemwise_engine import recording_analysis, recording_source
        self.stack.enter_context(patch.object(pipeline_module, 'collect_provenance', return_value=self.producer))
        self.stack.enter_context(patch.object(youtube, 'probe_duration_ms', return_value=1000))
        self.stack.enter_context(patch.object(pipeline_module, 'probe_duration_ms', return_value=1000))
        self.stack.enter_context(patch('stemwise_engine.packages.read_child', side_effect=lambda *_a, **_k: (self.document, b'cover')))
        self.stack.enter_context(patch.object(pipeline_module, 'read_child', side_effect=lambda *_a, **_k: (self.document, b'cover')))
        self.stack.enter_context(patch('stemwise_engine.future_song.read_child', side_effect=lambda *_a, **_k: (self.document, b'cover')))
        self.stack.enter_context(patch.object(original_delivery, 'read_bundle', side_effect=lambda *_a, **_k: (self.document, b'cover', {})))
        def extract(_path, _config, temporary, **_kwargs):
            source = temporary/'original.flac'
            source.write_bytes(self.source_bytes)
            return source, {}, {}, {}
        self.extract = self.stack.enter_context(patch.object(original_delivery, 'extract_original', side_effect=extract))
        def decode(_source, temporary, **_kwargs):
            temporary.mkdir(parents=True, exist_ok=True)
            output = temporary/'analysis.wav'
            output.write_bytes(self.fresh)
            return output
        self.decode = self.stack.enter_context(patch.object(youtube, 'decode_analysis_audio', side_effect=decode))
        self.seed(self.workflow)

    def seed(self, workflow):
        self.workflow = workflow
        self.root = workflow.workspace_root
        self.video = getattr(workflow, 'video_id', self.video)
        owner = workflow._pipeline()
        self.document = song_document(self.video)
        self.document['source'] = workflow.source.identity(self.video)
        self.document['generator']['version'] = workflow.config['generatorVersion']
        self.document['analysis']['durationMs'] = 1000
        child = owner.child_path(self.video)
        child.parent.mkdir(parents=True, exist_ok=True)
        child.write_bytes(b'previous independent numerical child')
        checkpoint = {'videoId': self.video, 'status': 'complete', 'generatorVersion': workflow.config['generatorVersion'],
            'provenanceSha256': provenance_fingerprint(self.producer), 'childSha256': sha256_file(child),
            'originalAudio': {'sha256': self.historical, 'byteCount': 900, 'durationMs': 1000, 'retained': True}}
        if workflow.source_context is not None:
            checkpoint['recordingInput'] = workflow.recording.identity
        owner.save_checkpoint(checkpoint)
        public = workflow.ready/child.name
        public.parent.mkdir(parents=True, exist_ok=True)
        public.write_bytes(b'previous complete delivered recording')
        job = workflow._base_job(self.video, f'https://youtu.be/{self.video}')
        workflow._save_job(job)
        job['state'] = 'WAITING_FOR_EDITORIAL'; workflow._save_job(job)
        job.update(state='VALIDATED', validation={'kind': 'capability-completion', 'audioSha256': self.historical},
            endpointReview={'attemptId': 'a'*32, 'result': 'accepted'}, displayReview={'attemptId': 'b'*32})
        workflow._save_job(job)
        job.update(state='DELIVERED', delivery={'sha256': sha256_file(public), 'original': {'name': 'original.flac',
            'sha256': hashlib.sha256(self.source_bytes).hexdigest(), 'bytes': len(self.source_bytes)}})
        workflow._save_job(job)
        if workflow.source_context is not None:
            atomic_write_json(workflow.ready/'recording.json', workflow._result_binding(job))
        atomic_write_json(workflow._job_dir(self.video)/'sources.json', {'videoId': self.video, 'containsLyrics': True})
        draft = workflow._job_dir(self.video)/'drafts/original.txt'
        draft.parent.mkdir()
        draft.write_text('retained approved wording', encoding='utf-8')
        review = workflow._endpoint_review_attempt_dir(self.video, 'a'*32)/'review.json'
        review.parent.mkdir(parents=True)
        atomic_write_json(review, {'outcome': 'resolved'})
        self.paths = (workflow._job_path(self.video), child, owner.checkpoint_path(self.video), public, draft, review)

    def command(self, command, *arguments):
        args = ['--config', str(self.workflow.config_path)]
        if self.workflow.source_context is not None:
            args += ['--recording-key', self.workflow.key]
        args += [command, '--video-id', self.video, *arguments]
        with redirect_stdout(io.StringIO()):
            return workflow_command.execute(workflow_command.parser().parse_args(args))

    def replace(self, **overrides):
        job = self.workflow._load_job(self.video, require_active_policy=False)
        delivery = overrides.get('delivery', job.get('analysisReplacement', {}).get('deliverySha256', job['delivery']['sha256']))
        return self.command('replaceanalysis', '--expected-delivery-sha256', delivery,
            '--expected-analysis-sha256', overrides.get('analysis', self.historical))

    def snapshot(self):
        return {path: path.read_bytes() for path in self.paths}

    def prepare_replacement_owner(self):
        owner = self.workflow._pipeline()
        cover = self.root/'replacement-cover.png'
        cover.write_bytes(PNG_1X1)
        create_child_zip(self.paths[1], self.document, cover, owner.config)
        checkpoint = owner.load_checkpoint(self.video)
        checkpoint['childSha256'] = sha256_file(self.paths[1])
        owner.save_checkpoint(checkpoint)
        atomic_write_json(self.workflow._job_dir(self.video)/'agent-review.json', {})
        self.replace()

    def replacement_preserved_bytes(self):
        root = job_ownership.replacement_root(self.workflow, self.video)
        return {path: path.read_bytes() for path in [self.paths[3], *root.rglob('*')]
                if path.is_file() and not path.name.endswith('.lock')}

    def source_input_bytes(self):
        directory = self.workflow._job_dir(self.video)
        return {**self.snapshot(), **{directory/name: (directory/name).read_bytes()
                for name in ('sources.json', 'agent-review.json')}}

    def complete_replacement_retry(self):
        with patch('stemwise_engine.packages.read_child', side_effect=fixture_read_child), \
                patch.object(pipeline_module, 'read_child', side_effect=fixture_read_child), \
                patch('stemwise_engine.future_song.read_child', side_effect=fixture_read_child), \
                patch.object(pipeline_module.StemwisePipeline, '_analysis',
                    return_value=copy.deepcopy(self.document['analysis'])) as numerical:
            result = self.command('retry')
        numerical.assert_called_once()
        self.assertEqual(result['state'], 'WAITING_FOR_EDITORIAL')
        current = self.workflow._pipeline().load_checkpoint(self.video)
        request = self.workflow._load_job(self.video)['analysisReplacement']
        self.assertEqual(current['analysisReplacementOperationId'], request['operationId'])
        self.assertEqual(current['originalAudio']['sha256'], hashlib.sha256(self.fresh).hexdigest())
        self.assertEqual(current['childSha256'], sha256_file(self.paths[1]))
        return result

    def test_failed_replacement_validate_and_source_preserve_owner_ledger_and_retry(self):
        self.prepare_replacement_owner()
        with patch.object(pipeline_module.StemwisePipeline, '_analysis', side_effect=RuntimeError('replacement failed')):
            with self.assertRaisesRegex(FutureSongError, 'Explicit replacement analysis did not complete'):
                self.command('retry')
        self.assertEqual(self.workflow._load_job(self.video)['state'], 'ANALYSIS_FAILED')
        ledger = self.workflow._job_dir(self.video)/'song-attempt.private.json'
        before_ledger = ledger.read_bytes()
        self.assertIsNotNone(self.workflow._song_ledger(self.video).blocking_disposition)
        self.assert_replacement_task_retries()
        before, preserved = self.source_input_bytes(), self.replacement_preserved_bytes()
        with patch('stemwise_engine.source_acquisition.collect_cross_id_evidence',
                return_value={'videoId': 'lmnopqrstuv'}) as acquisition:
            for action, arguments in (('validate', ()), ('source', ('--url', 'https://youtu.be/lmnopqrstuv'))):
                with self.subTest(action=action):
                    with self.assertRaises(FutureSongError):
                        self.command(action, *arguments)
                    acquisition.assert_not_called()
                    self.assertEqual(ledger.read_bytes(), before_ledger)
                    self.assertEqual(self.source_input_bytes(), before)
                    self.assertEqual(self.replacement_preserved_bytes(), preserved)
        self.complete_replacement_retry()
        self.assertEqual(self.replacement_preserved_bytes(), preserved)

    def test_replacement_retry_diagnostic_failures_preserve_expected_failure_and_prior_results(self):
        self.prepare_replacement_owner()
        before = {path: path.read_bytes() for path in self.paths[1:]}
        preserved = self.replacement_preserved_bytes()
        write = pipeline_module.atomic_write_json
        pointer = self.workflow._pipeline()._attempt_failure_path(self.video)
        for boundary in (None, 'diagnostic', 'pointer'):
            with self.subTest(boundary=boundary):
                failed_writes = []
                def diagnostic_write(path, value, **options):
                    if ((boundary == 'diagnostic' and Path(path).parent == pointer.parent/'runs')
                            or (boundary == 'pointer' and Path(path) == pointer)):
                        failed_writes.append(Path(path))
                        raise OSError('optional diagnostic storage unavailable')
                    return write(path, value, **options)
                with patch.object(pipeline_module.StemwisePipeline, '_analysis',
                            side_effect=RuntimeError('exact replacement numerical failure')) as numerical, \
                        patch.object(pipeline_module, 'atomic_write_json', side_effect=diagnostic_write):
                    with self.assertRaisesRegex(FutureSongError, 'Explicit replacement analysis did not complete'):
                        self.command('retry')
                numerical.assert_called_once()
                self.assertEqual(len(failed_writes), int(boundary is not None))
                self.assertEqual(self.workflow._load_job(self.video)['state'], 'ANALYSIS_FAILED')
                outcome = self.workflow.report_store.latest_workflow_operation(self.video)
                self.assertEqual(outcome['outcomeCode'], 'future-song-error')
                self.assertEqual({path: path.read_bytes() for path in before}, before)
                self.assertEqual(self.replacement_preserved_bytes(), preserved)
                if boundary is None:
                    reference = json.loads(pointer.read_text(encoding='utf-8'))
                    diagnostic = pointer.parent/reference['file']
                    self.assertEqual(sha256_file(diagnostic), reference['sha256'])
                    self.assertIn('exact replacement numerical failure',
                        json.loads(diagnostic.read_text(encoding='utf-8'))['privateDiagnostic'])

    def test_replacement_retry_diagnostic_failures_preserve_fatal_and_interruption_identity(self):
        self.prepare_replacement_owner()
        before = {path: path.read_bytes() for path in self.paths[1:]}
        preserved = self.replacement_preserved_bytes()
        write = pipeline_module.atomic_write_json
        pointer = self.workflow._pipeline()._attempt_failure_path(self.video)
        for boundary in ('diagnostic', 'pointer'):
            for primary in (WorkerRetirementError('unretired replacement worker'), KeyboardInterrupt('owner stop')):
                with self.subTest(boundary=boundary, primary=type(primary).__name__):
                    failed_writes = []
                    def diagnostic_write(path, value, **options):
                        if ((boundary == 'diagnostic' and Path(path).parent == pointer.parent/'runs')
                                or (boundary == 'pointer' and Path(path) == pointer)):
                            failed_writes.append(Path(path))
                            raise OSError('optional diagnostic storage unavailable')
                        return write(path, value, **options)
                    with patch.object(pipeline_module.StemwisePipeline, '_analysis', side_effect=primary) as numerical, \
                            patch.object(pipeline_module, 'atomic_write_json', side_effect=diagnostic_write):
                        with self.assertRaises(type(primary)) as raised:
                            self.command('retry')
                    self.assertIs(raised.exception, primary)
                    numerical.assert_called_once()
                    self.assertEqual(len(failed_writes), 1)
                    self.assertEqual(self.workflow._load_job(self.video)['state'], 'ANALYSIS_FAILED')
                    self.assertEqual({path: path.read_bytes() for path in before}, before)
                    self.assertEqual(self.replacement_preserved_bytes(), preserved)

    def test_interrupted_replacement_without_blocking_ledger_refuses_source_until_retry(self):
        self.prepare_replacement_owner()
        save = FutureSongWorkflow._save_job
        def interrupt_after_analyzing(workflow, job):
            save(workflow, job)
            if job['state'] == 'ANALYZING':
                raise KeyboardInterrupt('interrupted after durable analysis admission')
        with patch.object(FutureSongWorkflow, '_save_job', interrupt_after_analyzing), \
                patch.object(pipeline_module.StemwisePipeline, '_analysis') as numerical:
            with self.assertRaises(KeyboardInterrupt):
                self.command('retry')
            numerical.assert_not_called()
        self.assertEqual(self.workflow._load_job(self.video)['state'], 'ANALYZING')
        self.assertIsNone(self.workflow._song_ledger(self.video).blocking_disposition)
        self.assert_replacement_task_retries()
        before, preserved = self.source_input_bytes(), self.replacement_preserved_bytes()
        ledger = self.workflow._job_dir(self.video)/'song-attempt.private.json'
        before_ledger = ledger.read_bytes()
        with patch('stemwise_engine.source_acquisition.collect_cross_id_evidence',
                return_value={'videoId': 'lmnopqrstuv'}) as acquisition:
            with self.assertRaises(FutureSongError):
                self.command('source', '--url', 'https://youtu.be/lmnopqrstuv')
            acquisition.assert_not_called()
        self.assertEqual(self.source_input_bytes(), before)
        self.assertEqual(ledger.read_bytes(), before_ledger)
        self.assertEqual(self.replacement_preserved_bytes(), preserved)
        self.complete_replacement_retry()
        self.assertEqual(self.replacement_preserved_bytes(), preserved)

    def assert_replacement_task_retries(self):
        status = self.command('status')
        self.assertEqual(status['nextAction'], 'Retry')
        self.assertIn('Retry', status['permittedActions'])
        self.assertNotIn('Validate', status['permittedActions'])
        self.command('task')
        task = (self.workflow._job_dir(self.video)/'AGENT_TASK.txt').read_text(encoding='utf-8')
        self.assertIn('-Action Retry ', task)
        self.assertNotIn('-Action Validate ', task)

    def test_stranded_editorial_replacement_recovers_frozen_request_through_public_retry(self):
        self.prepare_replacement_owner()
        job = self.workflow._load_job(self.video)
        # A prior Source action could durably strand this uncommitted current owner.
        atomic_write_json(self.paths[0], {**job, 'state': 'WAITING_FOR_EDITORIAL'})
        preserved = self.replacement_preserved_bytes()
        directory = self.workflow._job_dir(self.video)
        inputs = {path: path.read_bytes() for path in
                  (directory/'sources.json', directory/'agent-review.json', self.paths[4])}
        status = self.command('status')
        self.assertIn('Retry', status['permittedActions'])
        self.assertNotIn('Source', status['permittedActions'])
        self.assertNotIn('Validate', status['permittedActions'])
        with patch('stemwise_engine.source_acquisition.collect_cross_id_evidence') as acquisition:
            self.complete_replacement_retry()
            acquisition.assert_not_called()
        self.assertEqual({path: path.read_bytes() for path in inputs}, inputs)
        self.assertEqual(self.replacement_preserved_bytes(), preserved)

    def test_verified_replacement_commit_allows_source_with_retained_replacement_marker(self):
        self.prepare_replacement_owner()
        preserved = self.replacement_preserved_bytes()
        self.complete_replacement_retry()
        self.assertIn('analysisReplacement', self.workflow._load_job(self.video))
        with patch('stemwise_engine.source_acquisition.collect_cross_id_evidence',
                return_value={'videoId': 'lmnopqrstuv'}) as acquisition:
            result = self.command('source', '--url', 'https://youtu.be/lmnopqrstuv')
        acquisition.assert_called_once()
        self.assertEqual(result['sourceAction']['result'], 'staged')
        self.assertEqual(result['state'], 'WAITING_FOR_EDITORIAL')
        self.assertIn('analysisReplacement', self.workflow._load_job(self.video))
        self.assertEqual(self.replacement_preserved_bytes(), preserved)

    def test_status_does_not_recover_replacement_journal_while_publishing_owner_holds_lease(self):
        import threading
        from concurrent.futures import ThreadPoolExecutor
        self.prepare_replacement_owner()
        entered, release = threading.Event(), threading.Event()
        owner = self.workflow._pipeline()
        pending = owner._analysis_replacement_root(self.video)/'pending.json'
        write = pipeline_module.atomic_write_json
        def hold_prepared_pair(path, record, **kwargs):
            write(path, record, **kwargs)
            if Path(path) == pending and record.get('phase') == 'prepared':
                entered.set()
                if not release.wait(20):
                    raise TimeoutError('Prepared replacement owner was not released')
        with patch('stemwise_engine.packages.read_child', side_effect=fixture_read_child), \
                patch.object(pipeline_module, 'read_child', side_effect=fixture_read_child), \
                patch('stemwise_engine.future_song.read_child', side_effect=fixture_read_child), \
                patch.object(pipeline_module.StemwisePipeline, '_analysis',
                    return_value=copy.deepcopy(self.document['analysis'])), \
                patch.object(pipeline_module, 'atomic_write_json', side_effect=hold_prepared_pair), \
                ThreadPoolExecutor(max_workers=1) as executor:
            worker = executor.submit(self.command, 'retry')
            try:
                self.assertTrue(entered.wait(20), 'Retry did not reach actual prepared-pair publication')
                prepared = pending.read_bytes()
                pair = {path: path.read_bytes() for path in self.paths[1:3]}
                preserved = self.replacement_preserved_bytes()
                ledger = self.workflow._job_dir(self.video)/'song-attempt.private.json'
                before_ledger = ledger.read_bytes()
                recover = pipeline_module.StemwisePipeline._recover_analysis_replacement
                with patch.object(pipeline_module.StemwisePipeline, '_recover_analysis_replacement',
                        autospec=True, side_effect=recover) as recovery, \
                        patch.object(pipeline_module.StemwisePipeline, '_ensure_provenance',
                            side_effect=AssertionError('Status initialized execution provenance')):
                    status = self.command('status')
                self.assertTrue(pending.is_file(), 'Status consumed the live replacement publication journal')
                self.assertEqual(pending.read_bytes(), prepared)
                self.assertEqual({path: path.read_bytes() for path in pair}, pair)
                self.assertEqual(self.replacement_preserved_bytes(), preserved)
                self.assertEqual(ledger.read_bytes(), before_ledger)
                recovery.assert_not_called()
                self.assertEqual(status['state'], 'ANALYZING')
                self.assertNotIn('Source', status['permittedActions'])
                self.assertNotIn('Validate', status['permittedActions'])
                self.assertFalse(worker.done())
            finally:
                release.set()
                completed = worker.result(timeout=20)
            self.assertEqual(completed['state'], 'WAITING_FOR_EDITORIAL')
            self.assertEqual(self.command('status')['state'], 'WAITING_FOR_EDITORIAL')
        current = owner.load_checkpoint(self.video)
        self.assertEqual(current['analysisReplacementOperationId'],
            self.workflow._load_job(self.video)['analysisReplacement']['operationId'])
        self.assertEqual(current['originalAudio']['sha256'], hashlib.sha256(self.fresh).hexdigest())
        self.assertEqual(current['childSha256'], sha256_file(self.paths[1]))

    def test_pending_and_committed_replacement_status_only_inspect_readonly_provenance(self):
        self.prepare_replacement_owner()
        def inspect(**kwargs):
            self.assertIsNot(kwargs.get('observe_execution'), True,
                             'Status observed execution hardware instead of stored analysis')
            return self.producer
        for committed in (False, True):
            if committed:
                self.complete_replacement_retry()
            before, preserved = self.source_input_bytes(), self.replacement_preserved_bytes()
            with self.subTest(committed=committed), \
                    patch.object(pipeline_module.StemwisePipeline, '_ensure_provenance',
                        side_effect=AssertionError('Status initialized execution provenance')), \
                    patch.object(pipeline_module, 'collect_provenance', side_effect=inspect) as provenance:
                status = self.command('status')
                self.assertEqual(status['state'], 'WAITING_FOR_EDITORIAL' if committed
                                 else 'ANALYSIS_REPLACEMENT_PENDING')
                if committed:
                    self.assertTrue(provenance.called, 'Committed inspection skipped current producer admission')
            self.assertEqual(self.source_input_bytes(), before)
            self.assertEqual(self.replacement_preserved_bytes(), preserved)

    def test_replacement_status_reloads_captured_pending_job_after_owner_commits(self):
        self.prepare_replacement_owner()
        captured = self.workflow._load_job(self.video)
        self.complete_replacement_retry()
        preserved = self.replacement_preserved_bytes()
        with patch.object(pipeline_module.StemwisePipeline, '_ensure_provenance',
                side_effect=AssertionError('Status initialized execution provenance')):
            status = self.workflow._status_summary(captured, verify_delivery=False)
        self.assertEqual(status['state'], 'WAITING_FOR_EDITORIAL')
        self.assertIn('Source', status['permittedActions'])
        self.assertNotIn('Retry', status['permittedActions'])
        self.assertEqual(self.replacement_preserved_bytes(), preserved)

    def test_native_busy_replacement_status_keeps_all_recommendations_observation_only(self):
        import threading
        from concurrent.futures import ThreadPoolExecutor
        from test_recording_workflow import RecordingWorkflowTest
        from stemwise_engine.recording_workflow import RecordingWorkflow
        from stemwise_engine.common import exclusive_process_lock
        with self.completed_display_fixture() as case:
            completed_lyrics = fixture_read_child(case.child, case.pipeline.config)[0]['lyrics']
        native = nested_case(self, RecordingWorkflowTest)
        base = initialized_workflow(native.workspace)
        self.seed(RecordingWorkflow(base.config_path, 'qobuz:11'))
        self.document['lyrics'] = completed_lyrics
        self.prepare_replacement_owner()
        job = self.workflow._load_job(self.video)
        # Numerical/publication results are fixture boundaries; native status and its lease stay real.
        for state in ('ANALYZING', 'WAITING_FOR_EDITORIAL', 'VALIDATED'):
            job['state'] = state
            self.workflow._save_job(job)
        self.paths[3].write_bytes(b'new completed native publication boundary')
        job['validation'] = {'kind': 'capability-completion',
            'inputSha256': self.workflow._capability_input_sha(self.video)}
        job.update(state='DELIVERED', delivery={**job['delivery'],
            'sha256': sha256_file(self.paths[3]), 'bytes': self.paths[3].stat().st_size})
        self.workflow._save_job(job)
        atomic_write_json(self.workflow.ready/'recording.json', self.workflow._result_binding(job))
        for publication_pending in (True, False):
            if publication_pending:
                job['nativePublication'] = {'phase': 'pending', 'packageSha256': job['delivery']['sha256']}
            else:
                job.pop('nativePublication')
            self.workflow._save_job(job)
            entered, release = threading.Event(), threading.Event()
            def hold_workflow_lease():
                with exclusive_process_lock(self.workflow._job_dir(self.video)/'workflow.lock'):
                    entered.set()
                    if not release.wait(20):
                        raise TimeoutError('Native publication owner was not released')
            before = self.source_input_bytes(), self.replacement_preserved_bytes()
            with self.subTest(publication_pending=publication_pending), \
                    patch.object(FutureSongWorkflow, '_validated_snapshot_is_current', return_value=True), \
                    patch('stemwise_engine.native_delivery.current', return_value=[]), \
                    ThreadPoolExecutor(max_workers=1) as executor:
                worker = executor.submit(hold_workflow_lease)
                try:
                    self.assertTrue(entered.wait(20), 'Native workflow lease was not admitted')
                    result = self.workflow.status(self.video, verify_delivery=not publication_pending)
                    self.assertEqual(result['state'], 'DELIVERY_PUBLICATION_PENDING' if publication_pending
                                     else 'DELIVERY_AUDIO_REQUIRED', result)
                    self.assertEqual(result['permittedActions'], [])
                    self.assertEqual(result['nextAction'], 'Status')
                    if 'deliveryVerification' in result:
                        self.assertEqual(result['deliveryVerification']['nextAction'], 'Status')
                    self.assertEqual((self.source_input_bytes(), self.replacement_preserved_bytes()), before)
                finally:
                    release.set()
                    worker.result(timeout=20)
                available = self.workflow.status(self.video, verify_delivery=not publication_pending)
                self.assertIn('Finalize', available['permittedActions'])
                self.assertEqual(available['nextAction'], 'Finalize')

    def test_stranded_replacement_missing_or_altered_owner_never_admits_source_or_analysis(self):
        self.prepare_replacement_owner()
        job = self.workflow._load_job(self.video)
        atomic_write_json(self.paths[0], {**job, 'state': 'WAITING_FOR_EDITORIAL'})
        root = job_ownership.replacement_root(self.workflow, self.video)
        originals = {path: path.read_bytes() for path in
                     (root/'request.json', root/'fresh-pcm.json', root/'previous.json')}
        for name in ('request.json', 'fresh-pcm.json', 'previous.json'):
            for path, raw in originals.items():
                path.write_bytes(raw)
            path = root/name
            if name == 'request.json':
                path.unlink()
            else:
                path.write_bytes(b'altered frozen replacement owner')
            before, preserved = self.source_input_bytes(), self.replacement_preserved_bytes()
            with self.subTest(owner=name), \
                    patch('stemwise_engine.source_acquisition.collect_cross_id_evidence',
                        return_value={'videoId': 'lmnopqrstuv'}) as acquisition, \
                    patch.object(pipeline_module.StemwisePipeline, '_analysis') as numerical:
                for action, arguments in (('retry', ()), ('source', ('--url', 'https://youtu.be/lmnopqrstuv'))):
                    with self.assertRaises((FutureSongError, OSError, ValueError)):
                        self.command(action, *arguments)
                acquisition.assert_not_called()
                numerical.assert_not_called()
                self.assertEqual(self.source_input_bytes(), before)
                self.assertEqual(self.replacement_preserved_bytes(), preserved)
        for path, raw in originals.items():
            path.write_bytes(raw)

    def test_explicit_public_admission_preserves_delivery_and_retires_old_decisions(self):
        before = self.snapshot()
        for command in ('retry', 'new'):
            with self.subTest(command=command), self.assertRaises(FutureSongError):
                if command == 'new':
                    workflow_command.execute(workflow_command.parser().parse_args(['--config', str(self.workflow.config_path),
                        'new', '--url', f'https://youtu.be/{self.video}']))
                else:
                    self.command(command)
        self.assertEqual(self.snapshot(), before)
        result = self.replace()
        self.assertEqual(result['state'], 'ANALYSIS_REPLACEMENT_PENDING')
        self.assertEqual(result['permittedActions'], ['Retry'])
        status = self.command('status')
        self.assertEqual(status['state'], result['state'])
        self.assertEqual(status['permittedActions'], ['Retry'])
        self.assertNotIn('reviewInputs', status)
        saved = self.workflow._load_job(self.video)
        self.assertNotIn('endpointReview', saved)
        self.assertNotIn('displayReview', saved)
        self.assertNotIn('validation', saved)
        self.assertEqual({path: path.read_bytes() for path in self.paths[1:]}, {path: before[path] for path in self.paths[1:]})
        committed = self.snapshot()
        self.assertEqual(self.replace()['state'], result['state'])
        self.assertEqual(self.snapshot(), committed)
        self.assertEqual(self.decode.call_count, 1)
        self.assertEqual(self.decode.call_args.kwargs['timeout_seconds'], int(self.workflow.config['analysisTimeoutMinutes'])*60)
        with patch.object(FutureSongWorkflow, '_validate_endpoint_review', side_effect=AssertionError('old review reused')):
            for action, arguments in (('validate', ()), ('source', ('--url', 'https://youtu.be/lmnopqrstuv'))):
                with self.subTest(action=action), self.assertRaises(FutureSongError):
                    self.command(action, *arguments)
                self.assertEqual(self.snapshot(), committed)

    def test_intact_active_unrelated_and_stale_requests_are_refused_without_mutation(self):
        before = self.snapshot()
        for changes in ({'delivery': '0'*64}, {'analysis': '0'*64}):
            with self.subTest(changes=changes), self.assertRaises(FutureSongError):
                self.replace(**changes)
        self.assertEqual(self.snapshot(), before)
        job = self.workflow._load_job(self.video)
        atomic_write_json(self.paths[0], {**job, 'state': 'ANALYZING'})
        active = self.snapshot()
        with self.assertRaises(FutureSongError):
            self.replace()
        self.assertEqual(self.snapshot(), active)
        atomic_write_json(self.paths[0], job)
        temporary = self.workflow._pipeline()._temp_dir(self.video)
        (temporary/'analysis.wav').write_bytes(b'preserve incomplete or intact PCM')
        with self.assertRaises(FutureSongError):
            self.replace()
        self.assertEqual((temporary/'analysis.wav').read_bytes(), b'preserve incomplete or intact PCM')
        self.assertEqual(self.decode.call_count, 0)
        with self.assertRaises(FutureSongError):
            workflow_command.execute(workflow_command.parser().parse_args(['--config', str(self.workflow.config_path),
                'replaceanalysis', '--video-id', 'lmnopqrstuv', '--expected-delivery-sha256', job['delivery']['sha256'],
                '--expected-analysis-sha256', self.historical]))

    def test_interrupted_admission_resumes_one_exact_owner(self):
        before = self.snapshot()
        save = FutureSongWorkflow._save_job
        def interrupt(workflow, job):
            if job['state'] == 'ANALYSIS_REPLACEMENT_PENDING':
                raise KeyboardInterrupt()
            return save(workflow, job)
        with patch.object(FutureSongWorkflow, '_save_job', interrupt), self.assertRaises(KeyboardInterrupt):
            self.replace()
        self.assertEqual(self.snapshot(), before)
        self.replace()
        self.assertEqual(self.decode.call_count, 1)
        owner, root = job_ownership.replacement_record(self.workflow, self.workflow._load_job(self.video))
        self.assertEqual((root/'previous.zip').read_bytes(), before[self.paths[1]])
        self.assertEqual((root/'previous.json').read_bytes(), before[self.paths[2]])

    def test_exact_reconstruction_refuses_replacement_and_cleans_only_its_staging(self):
        self.fresh = b'unavailable historical float32'
        before = self.snapshot()
        with self.assertRaisesRegex(FutureSongError, 'unnecessary'):
            self.replace()
        self.assertEqual(self.snapshot(), before)
        self.assertFalse(job_ownership.replacement_root(self.workflow, self.video).exists())

    def test_accepted_retry_reaches_fresh_review_without_publishing_or_replaying(self):
        # An explicit owner exception may replace old executable-policy authority.
        job = self.workflow._load_job(self.video)
        job['policy']['lyricsPolicySha256'] = '0'*64
        atomic_write_json(self.paths[0], job)
        with self.assertRaises(FutureSongError):
            self.command('retry')
        self.replace()
        previous_delivery = self.paths[3].read_bytes()
        calls = []
        def numerical_commit(owner, item, previous, previous_raw, previous_child, document, audio,
                             lyrics, lyrics_sha, *, force, replacement_operation_id):
            self.assertTrue(force)
            self.assertEqual(Path(audio).read_bytes(), self.fresh)
            self.assertEqual(previous_child, self.paths[1].read_bytes())
            state = owner._new_state(item, lyrics_sha, retain_prepared=False)
            state.update(status='complete', originalAudio=previous['originalAudio'],
                analysisReplacementOperationId=replacement_operation_id)
            owner.child_path(self.video).write_bytes(b'known replacement numerical artifact boundary')
            state['childSha256'] = sha256_file(owner.child_path(self.video))
            owner.save_checkpoint(state)
            calls.append(replacement_operation_id)
            return 'complete'
        with patch.object(pipeline_module.StemwisePipeline, '_rebuild_retained_analysis', numerical_commit):
            result = self.command('retry')
        self.assertEqual(result['state'], 'WAITING_FOR_EDITORIAL')
        self.assertEqual(len(calls), 1)
        self.assertEqual(self.paths[3].read_bytes(), previous_delivery)
        self.assertNotIn('validation', self.workflow._load_job(self.video))
        self.assertEqual(self.replace()['state'], 'WAITING_FOR_EDITORIAL')
        self.assertEqual(len(calls), 1)
        source, receipt, root = job_ownership.replacement_source(self.workflow, self.workflow._load_job(self.video))
        self.assertEqual(source.read_bytes(), self.source_bytes)
        source.write_bytes(b'changed source')
        with self.assertRaises(FutureSongError):
            job_ownership.replacement_source(self.workflow, self.workflow._load_job(self.video))

    def test_native_public_route_binds_the_actual_selected_flac(self):
        from test_recording_workflow import RecordingWorkflowTest
        from stemwise_engine.recording_workflow import RecordingWorkflow
        native = nested_case(self, RecordingWorkflowTest)
        base = initialized_workflow(native.workspace)
        self.seed(RecordingWorkflow(base.config_path, 'qobuz:11'))
        source_before = native.audio.read_bytes()
        before = self.snapshot()
        result = self.replace()
        self.assertEqual(result['state'], 'ANALYSIS_REPLACEMENT_PENDING')
        job = self.workflow._load_job(self.video)
        self.assertEqual(job['analysisReplacement']['source']['recording'], self.workflow.recording.identity)
        source, _, _ = job_ownership.replacement_source(self.workflow, job)
        self.assertEqual(source.read_bytes(), source_before)
        self.assertEqual(native.audio.read_bytes(), source_before)
        self.assertEqual({path: path.read_bytes() for path in self.paths[1:]}, {path: before[path] for path in self.paths[1:]})
        self.extract.assert_not_called()

    def test_retry_uses_new_pcm_owner_and_preserves_old_pair_on_worker_failure(self):
        self.replace()
        before = {path: path.read_bytes() for path in self.paths[1:]}
        failure = WorkerRetirementError('numerical worker is unretired')
        def numerical(owner, audio, duration):
            self.assertEqual(Path(audio).read_bytes(), self.fresh)
            self.assertEqual(duration, 1000)
            raise failure
        with patch.object(pipeline_module.StemwisePipeline, '_analysis', numerical):
            with self.assertRaises(WorkerRetirementError) as raised:
                self.command('retry')
        self.assertIs(raised.exception, failure)
        self.assertEqual({path: path.read_bytes() for path in before}, before)
        self.assertEqual(self.workflow._load_job(self.video)['state'], 'ANALYSIS_FAILED')
        self.assertTrue((job_ownership.replacement_root(self.workflow, self.video)/'fresh-pcm.json').is_file())


    def test_primary_worker_failure_survives_failure_state_write_error(self):
        self.replace()
        save = FutureSongWorkflow._save_job
        failure = WorkerRetirementError('numerical worker is unretired')
        def fail_report(workflow, job):
            if job['state'] == 'ANALYSIS_FAILED':
                raise OSError('failure status disk unavailable')
            return save(workflow, job)
        with patch.object(pipeline_module.StemwisePipeline, '_analysis', side_effect=failure), \
                patch.object(FutureSongWorkflow, '_save_job', fail_report):
            with self.assertRaises(WorkerRetirementError) as raised:
                self.command('retry')
        self.assertIs(raised.exception, failure)
        self.assertTrue((job_ownership.replacement_root(self.workflow, self.video)/'previous.zip').is_file())

    def test_finalize_reconciles_interruption_after_replacement_tree_removal(self):
        with self.completed_display_fixture() as case:
            self.document['lyrics'] = fixture_read_child(case.child, case.pipeline.config)[0]['lyrics']
        self.replace()
        job = self.workflow._load_job(self.video)
        # Model/publication boundaries are fixture results; this exercises only completed cleanup.
        for state in ('ANALYZING', 'WAITING_FOR_EDITORIAL', 'VALIDATED'):
            job['state'] = state
            self.workflow._save_job(job)
        self.paths[3].write_bytes(b'new independently completed public artifact')
        job.update(state='DELIVERED', delivery={**job['delivery'], 'sha256': sha256_file(self.paths[3])})
        self.workflow._save_job(job)
        root = job_ownership.replacement_root(self.workflow, self.video)
        save = FutureSongWorkflow._save_job
        def interrupt(workflow, current):
            if current['state'] == 'DELIVERED' and not current.get('analysisReplacement'):
                raise KeyboardInterrupt()
            return save(workflow, current)
        with patch.object(FutureSongWorkflow, '_validated_snapshot_is_current', return_value=True), \
                patch('stemwise_engine.original_delivery.verify_original_delivery'), \
                patch.object(type(self.workflow.source), 'complete_delivery', return_value=[]), \
                patch.object(FutureSongWorkflow, '_remove_retained_analysis_audio'), \
                patch.object(FutureSongWorkflow, '_cleanup_completed_endpoint_contexts'):
            with patch.object(FutureSongWorkflow, '_save_job', interrupt), self.assertRaises(KeyboardInterrupt):
                self.command('finalize')
            self.assertFalse(root.exists())
            self.assertIn('analysisReplacement', self.workflow._load_job(self.video))
            self.assertEqual(self.command('finalize')['state'], 'DELIVERED')
        self.assertNotIn('analysisReplacement', self.workflow._load_job(self.video))
        self.assertEqual(self.paths[3].read_bytes(), b'new independently completed public artifact')

    def test_capacity_refusal_precedes_decode_and_preserves_completed_inputs(self):
        from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
        before = self.snapshot()
        with patch('stemwise_engine.analysis_resources.require_analysis_capacity',
                side_effect=AcousticBudgetExceeded('analysis-scratch-space-unavailable')) as capacity:
            with self.assertRaises(AcousticBudgetExceeded):
                self.replace()
        self.decode.assert_not_called()
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(capacity.call_args.args, (1000,))
        self.assertEqual(capacity.call_args.kwargs['scratch_root'], job_ownership.replacement_root(self.workflow, self.video))

    def test_shared_transaction_commits_fresh_owner_and_retry_consumes_it_without_analysis_replay(self):
        self.assert_shared_transaction()

    def test_native_shared_transaction_keeps_historical_independent_cache_untouched(self):
        from test_recording_workflow import RecordingWorkflowTest
        from stemwise_engine.recording_workflow import RecordingWorkflow
        native = nested_case(self, RecordingWorkflowTest)
        base = initialized_workflow(native.workspace)
        self.seed(RecordingWorkflow(base.config_path, 'qobuz:11'))
        with patch('stemwise_engine.recording_analysis.acoustic_producer_identity', return_value={'fixture': 'observed native producer', 'runtime': {}}), \
                patch('stemwise_engine.recording_analysis.status', side_effect=AssertionError('historical native cache must stay independent')):
            self.assert_shared_transaction()

    def assert_shared_transaction(self):
        owner = self.workflow._pipeline()
        cover = self.root/'fixture-cover.png'
        cover.write_bytes(PNG_1X1)
        create_child_zip(self.paths[1], self.document, cover, owner.config)
        checkpoint = owner.load_checkpoint(self.video)
        checkpoint['childSha256'] = sha256_file(self.paths[1])
        owner.save_checkpoint(checkpoint)
        if self.workflow.source_context is not None:
            self.paths[3].write_bytes(self.paths[1].read_bytes())
            job = self.workflow._load_job(self.video)
            job['delivery']['sha256'] = sha256_file(self.paths[3])
            self.workflow._save_job(job)
            atomic_write_json(self.workflow.ready/'recording.json', self.workflow._result_binding(job))
        previous_pair = self.paths[1].read_bytes(), self.paths[2].read_bytes()
        previous_public = self.paths[3].read_bytes()
        with patch('stemwise_engine.packages.read_child', side_effect=fixture_read_child), \
                patch.object(pipeline_module, 'read_child', side_effect=fixture_read_child), \
                patch('stemwise_engine.future_song.read_child', side_effect=fixture_read_child):
            self.replace()
            request = self.workflow._load_job(self.video)['analysisReplacement']
            write = pipeline_module.atomic_write_json
            def interrupt(path, record, **kwargs):
                write(path, record, **kwargs)
                if Path(path).name == 'pending.json' and record.get('phase') == 'committed':
                    raise KeyboardInterrupt('process interrupted after actual numerical pair commit')
            analysis = copy.deepcopy(self.document['analysis'])
            boundary = '_produce_analysis' if self.workflow.source_context is not None else '_analysis'
            def numerical(producer, audio, duration):
                self.assertEqual(Path(audio).read_bytes(), self.fresh)
                if self.workflow.source_context is not None:
                    from stemwise_engine.recording_analysis import selected_input
                    from stemwise_engine.common import process_lock_descriptor
                    _, _, cache_root, _, _ = selected_input(producer.config, producer.config['localRecordingKey'])
                    self.assertIsNotNone(process_lock_descriptor(cache_root/'workflow.lock'))
                return analysis
            with patch.object(pipeline_module.StemwisePipeline, boundary, autospec=True, side_effect=numerical) as analyzer, \
                    patch.object(pipeline_module, 'atomic_write_json', side_effect=interrupt):
                with self.assertRaises(KeyboardInterrupt):
                    self.command('retry')
            self.assertEqual(analyzer.call_count, 1)
            self.assertEqual(self.paths[3].read_bytes(), previous_public)
            self.assertNotEqual((self.paths[1].read_bytes(), self.paths[2].read_bytes()), previous_pair)
            with patch.object(pipeline_module.StemwisePipeline, boundary, side_effect=AssertionError('numerical work replayed')):
                self.assertEqual(self.command('retry')['state'], 'WAITING_FOR_EDITORIAL')
            current = self.workflow._pipeline().load_checkpoint(self.video)
            document, current_cover = fixture_read_child(self.paths[1], owner.config)
            self.assertEqual(current['analysisReplacementOperationId'], request['operationId'])
            self.assertEqual(current['originalAudio']['sha256'], hashlib.sha256(self.fresh).hexdigest())
            self.assertEqual(current['childSha256'], sha256_file(self.paths[1]))
            self.assertEqual(document['source'], self.workflow.source.identity(self.video))
            self.assertEqual(document['analysis'], analysis)
            self.assertEqual(current_cover, PNG_1X1)
            self.assertEqual((self.workflow.analysis_temp/self.video/'analysis.wav').read_bytes(), self.fresh)
            self.assertEqual(self.paths[3].read_bytes(), previous_public)
            _, root = job_ownership.replacement_record(self.workflow, self.workflow._load_job(self.video))
            self.assertEqual(((root/'previous.zip').read_bytes(), (root/'previous.json').read_bytes()), previous_pair)

    def test_replacement_retry_validates_manual_import_and_immutable_budget_before_analysis(self):
        budget = 100000
        job = self.workflow._load_job(self.video)
        job['bridgeMaxFileBytes'] = budget
        self.workflow._save_job(job)
        self.replace()
        before = self.snapshot()
        with patch.object(job_ownership, 'run_analysis_replacement', side_effect=AssertionError('replacement admitted invalid Retry arguments')) as run:
            for arguments, message in ((('--manual-import',), 'Manual import retry requires'),
                    (('--max-file-bytes', str(budget+1)), 'Accepted bridge budget cannot change'),
                    (('--max-file-bytes', '0'), 'Bridge budget must be a positive')):
                with self.subTest(arguments=arguments), self.assertRaisesRegex(FutureSongError, message):
                    self.command('retry', *arguments)
                run.assert_not_called()
                self.assertEqual(self.snapshot(), before)
        failure = WorkerRetirementError('stop at actual numerical boundary')
        with patch.object(pipeline_module.StemwisePipeline, '_analysis', side_effect=failure) as analysis:
            with self.assertRaises(WorkerRetirementError) as raised:
                self.command('retry', '--max-file-bytes', str(budget))
        self.assertIs(raised.exception, failure)
        analysis.assert_called_once()
        saved = self.workflow._load_job(self.video)
        self.assertEqual(saved['bridgeMaxFileBytes'], budget)
        self.assertNotIn('bridgeManualImport', saved)
        self.assertEqual({path: path.read_bytes() for path in self.paths[1:]}, {path: before[path] for path in self.paths[1:]})
