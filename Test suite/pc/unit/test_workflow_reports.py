"""Exercise current report serialization and publication without model dependencies."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
from stemwise_engine.workflow_context import report_control_path
from stemwise_engine.workflow_records import (FutureSongError, LyricsContentBlocker, OperationStart, ReportBindings,
                              WorkflowOutcome, operation_record_is_safe, engine_source_sha256)
from stemwise_engine.workflow_reports import ImmutableReportStore


class WorkflowReportsTest(unittest.TestCase):
    def test_endpoint_result_round_trips_through_persisted_state_and_partial_failure_reports(self):
        from stemwise_engine.workflow_records import EndpointReviewResult
        from stemwise_engine.workflow_reports import alignment_run_record
        from stemwise_engine.common import atomic_write_json, read_bounded_json
        result = EndpointReviewResult('a'*64, 'b'*64, 'c'*32, 'd'*64, 3)
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            path = root/'state.json'
            for index, status in enumerate(('committed-lines', 'retryable-technical')):
                atomic_write_json(path, {'status': status, 'endpointReview': result.to_record()})
                state = read_bounded_json(path, max_bytes=65536, root=root)
                self.assertEqual(EndpointReviewResult.from_record(state['endpointReview']), result)
                record, evidence = alignment_run_record({'policy': {'schemaSha256': 'e'*64}},
                    run_id=f'{index+1:032x}', video_id='a1B2c3D4e5F', state=state, checkpoint_key={},
                    checkpoint_raw=b'{}', completed_at=1, status='failed', run_artifacts={},
                    failure_code='retryable-technical', producing_inputs=None, observed_input_changes={})
                self.assertEqual(evidence['diagnostics']['endpointReview'], result.to_record())
                store = ImmutableReportStore(root, 65536)
                from contextlib import nullcontext
                from unittest.mock import patch
                # Linux retention tests exercise the kernel lock; this portable check exercises append/readback.
                with patch('stemwise_engine.telemetry_retention.exclusive_process_lock', return_value=nullcontext()), \
                        patch('stemwise_engine.telemetry_retention.cleanup_source', side_effect=AssertionError('append must not scan history')):
                    store.commit(root/'Engine telemetry/youtube-a1B2c3D4e5F/reports', record, evidence=evidence)
                malformed = {**state, 'endpointReview': {**result.to_record(), 'reviewedOccurrenceCount': True}}
                with self.assertRaisesRegex(FutureSongError, 'reviewed endpoint evidence'):
                    alignment_run_record({}, run_id='f'*32, video_id='a1B2c3D4e5F', state=malformed,
                        checkpoint_key={}, checkpoint_raw=b'{}', completed_at=1, status='failed',
                        run_artifacts={}, failure_code=None, producing_inputs=None, observed_input_changes={})

    def test_checkpoint_consumes_the_audio_stage_record_and_rejects_a_broken_vocal_chain(self):
        import shutil
        import subprocess
        import wave
        from unittest.mock import patch
        from workflow_fixture import initialized_workflow
        from stemwise_engine.alignment_audio import write_audio_provenance
        from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION, AlignmentWorkspace
        from stemwise_engine.common import atomic_write_json
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            workflow = initialized_workflow(root)
            project = Path(workflow.config['projectDir'])
            for name in ('lyrics_align_pc.py', 'lyrics_alignment.py', 'lyrics_tokenization.py',
                    'lyrics_unicode.py', 'alignment_attempt.py', 'mms_forced_alignment.py',
                    'owsm_ctc_forced_alignment.py', 'qwen3_asr_transcribe.py',
                    'wav2vec2_forced_alignment.py', 'roformer_separation.py', 'song_enrichment_v2.py', 'contract.py'):
                shutil.copyfile(ROOT/'PC side/stemwise-playlist-builder/src'/name, project/'src'/name)
            audio = root/'mix.wav'
            with wave.open(str(audio), 'wb') as stream:
                stream.setnchannels(2)
                stream.setsampwidth(2)
                stream.setframerate(48000)
                stream.writeframes(bytes(480*4))
            video = 'a1B2c3D4e5F'
            workspace = AlignmentWorkspace(workflow.root, workspace_root=root)
            def probe(command, **kwargs):
                self.assertEqual(command[0], 'ffprobe')
                self.assertEqual(Path(command[-1]), audio)
                return subprocess.CompletedProcess(command, 0, json.dumps({'streams': [{
                    'sample_rate': '48000', 'channels': 2, 'duration_ts': 480, 'time_base': '1/48000'}]}), '')
            # Host substitutes only ffprobe for the WAV above; provenance production and validation stay real.
            with patch('stemwise_engine.alignment_audio.run_process_tree', side_effect=probe) as external_probe:
                produced = write_audio_provenance(workspace, video, duration_ms=10,
                    mix_audio=audio, vocals_audio=audio, demucs_model_sha256='a'*64)
            self.assertEqual(external_probe.call_count, 2)
            self.assertEqual(produced['mix']['sha256'], hashlib.sha256(audio.read_bytes()).hexdigest())
            self.assertEqual(produced['mix']['stream'], {
                'sampleRate': 48000, 'channelCount': 2, 'frameCount': 480, 'durationMs': 10})
            job = workflow._base_job(video, f'https://youtu.be/{video}')
            state = {'alignmentVersion': ALIGNMENT_VERSION, 'durationMs': 10}
            checkpoint = workflow.report_store.alignment_checkpoint_key(job, editorial_sha256='b'*64, state=state)
            self.assertEqual(checkpoint['audio']['mix'], produced['mix'])
            self.assertEqual(checkpoint['audio']['audioViews'], produced['audioViews'])
            owner_raw = b'{"fixture":"retained independent owner decision"}'
            owner_sha = hashlib.sha256(owner_raw).hexdigest()
            owner_path = workspace.job_dir(video)/f'owner-content-{owner_sha}.private.json'
            owner_path.write_bytes(owner_raw)
            owner = {'version': 1, 'canonicalPlainSha256': 'c'*64,
                'audioSha256': produced['mix']['sha256'], 'sourcePhysicalIndices': [0],
                'evidence': {'path': owner_path.name, 'sha256': owner_sha},
                'ownerVerification': {'path': 'owner-verified-lyrics.json', 'sha256': owner_sha}}
            owner_key = workflow.report_store.alignment_checkpoint_key(job, editorial_sha256='b'*64,
                state={**state, 'contentAdmission': owner})
            self.assertEqual(owner_key['authority']['ownerVerification'], owner['ownerVerification'])
            self.assertEqual(owner_key['authority']['ownerEvidence'], owner['evidence'])
            self.assertNotIn('asr', owner_key.get('models', {}))
            owner_path.write_bytes(owner_raw+b' ')
            with self.assertRaisesRegex(FutureSongError, 'owner content evidence changed'):
                workflow.report_store.alignment_checkpoint_key(job, editorial_sha256='b'*64,
                    state={**state, 'contentAdmission': owner})
            produced['audioViews']['htdemucs-vocals']['sourceAudioSha256'] = '0'*64
            atomic_write_json(workspace.audio_provenance_path(video), produced)
            with self.assertRaisesRegex(FutureSongError, 'audio provenance'):
                workflow.report_store.alignment_checkpoint_key(job, editorial_sha256='b'*64, state=state)

    def test_specific_failures_round_trip_actionable_body_free_details(self):
        from stemwise_engine.workflow_records import (ArtworkUnavailableError, WorkflowInputError,
                                                      workflow_failure_details)
        cases = (
            (ArtworkUnavailableError('PRIVATE-LYRIC-BODY'), 'artwork-unavailable'),
            (WorkflowInputError('PRIVATE-LYRIC-BODY', reason_code='review-stale',
                affected_input='recording review', next_action='Renew the exact recording review.'), 'review-stale'),
            (WorkflowInputError('PRIVATE-LYRIC-BODY', reason_code='model-identity-mismatch',
                affected_input='recognition cache', next_action='Regenerate unusable recognition proof.'), 'model-identity-mismatch'),
            (WorkflowInputError('PRIVATE-LYRIC-BODY', reason_code='publication-conflict',
                affected_input='delivery rollback', next_action='Recover the existing publication.'), 'publication-conflict'),
        )
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            store = ImmutableReportStore(root, 65536)
            start = OperationStart('a' * 32, 'a1B2c3D4e5F', 'b' * 32, 'Validate', 1, 'NEW',
                                   ReportBindings('c' * 64, 'd' * 64, 'e' * 64))
            for index, (error, expected) in enumerate(cases):
                details = workflow_failure_details(error)
                self.assertEqual(details['reasonCode'], expected)
                self.assertTrue(details['affectedInput'])
                self.assertTrue(details['nextAction'])
                record = start.finish(f'{index:032x}', 2, 'NEW', expected).to_record()
                record['failureDetails'] = details
                self.assertTrue(operation_record_is_safe(record))
                store.commit(root / 'reports', record, current_pointer='current-operation.json')
                payload = (root / 'reports/runs' / record['runId'] / 'run.json').read_bytes()
                self.assertEqual(json.loads(payload)['failureDetails'], details)
                self.assertNotIn(b'PRIVATE-LYRIC-BODY', payload)

    def test_unexpected_failure_retains_bounded_causal_locations_without_exception_body(self):
        from stemwise_engine.workflow_records import workflow_failure_details, failure_details_are_safe
        try:
            try:
                raise OSError('PRIVATE-SOURCE-CREDENTIAL')
            except OSError as cause:
                raise KeyError('PRIVATE-LYRIC-BODY') from cause
        except KeyError as error:
            details = workflow_failure_details(error)
        self.assertEqual(details['reasonCode'], 'unexpected-error')
        self.assertEqual([row['type'] for row in details['causalTrace']], ['KeyError', 'OSError'])
        self.assertTrue(all(row['frames'] for row in details['causalTrace']))
        self.assertTrue(failure_details_are_safe(details))
        self.assertNotIn('PRIVATE-', json.dumps(details))


    def test_content_blocked_task_suppresses_retained_endpoint_instructions_but_timing_task_keeps_them(self):
        from stemwise_engine.workflow_tasks import TaskPlan
        from stemwise_engine.workflow_states import JobState
        shared = dict(video_id='abcdefghijk', policy='current-policy', guide='Builder guide',
                      directory='job', child='child.zip', launcher='STEMWISE_AGENT.ps1',
                      translation=False, ipa=False, endpoint_directory='retained-endpoint-review')
        blocked = TaskPlan(state=JobState.NEEDS_LYRICS, **shared).render().decode('utf-8')
        self.assertIn('Resolve missing or uncertain performed words', blocked)
        self.assertIn('Do not use forced alignment or prediction to resolve lyric content', blocked)
        self.assertNotIn('retained-endpoint-review', blocked)
        self.assertNotIn('EndpointAdjudicate', blocked)
        timing = TaskPlan(state=JobState.LINE_UNRESOLVED, **shared).render().decode('utf-8')
        self.assertIn('retained-endpoint-review', timing)
        self.assertIn('EndpointAdjudicate', timing)

    def test_task_selector_renders_native_commands_directly_and_quotes_launcher(self):
        from stemwise_engine.workflow_tasks import TaskPlan
        from stemwise_engine.workflow_states import JobState
        plan = TaskPlan('abcdefghijk', JobState.WAITING_FOR_EDITORIAL, 'policy', 'guide',
            'job', 'child', "C:/Owner's tools/launch me.ps1", False, False, recording_key='qobuz:11')
        rendered = plan.render().decode('utf-8')
        self.assertIn("C:/Owner''s tools/launch me.ps1", rendered)
        self.assertIn("-Action Status -RecordingKey 'qobuz:11'", rendered)
        self.assertIn("-Action Validate -RecordingKey 'qobuz:11'", rendered)
        self.assertIn('required companions remain pending and block final delivery', rendered)
        self.assertIn('Original timing can proceed independently', rendered)
        self.assertNotIn('the package may finish partial', rendered)
        self.assertNotIn('-VideoId', rendered)

    def test_content_blocker_accepts_only_bounded_body_free_reasons_and_exact_binding(self):
        binding = {'canonicalPlainSha256': 'a' * 64, 'audioSha256': 'b' * 64,
                   'sourcePhysicalIndices': [0, 4, 5]}
        blocker = LyricsContentBlocker('pronunciation-unresolved', 'word-identity-ambiguous',
                                      'pronunciation-unresolved', binding=binding)
        binding['sourcePhysicalIndices'].append(9)
        self.assertEqual(blocker.reasons, ('pronunciation-unresolved', 'word-identity-ambiguous'))
        self.assertEqual(blocker.binding['sourcePhysicalIndices'], [0, 4, 5])
        self.assertEqual((blocker.state, blocker.code), ('NEEDS_LYRICS', 'lyrics-content-unresolved'))
        for reasons in (('Private lyric body',), ('line\nwords',), ('x' * 161,),
                        tuple('reason-%d' % index for index in range(33)), (None,)):
            with self.subTest(reasons=reasons), self.assertRaises(ValueError):
                LyricsContentBlocker(*reasons)
        exact = blocker.binding
        for changes in ({'canonicalPlainSha256': 'A' * 64}, {'audioSha256': None},
                        {'canonicalPlainSha256': int('1' * 64)}, {'audioSha256': int('2' * 64)},
                        {'sourcePhysicalIndices': []}, {'sourcePhysicalIndices': [True]},
                        {'sourcePhysicalIndices': [-1]}, {'lyricBody': 'private body'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                LyricsContentBlocker('word-identity-ambiguous', binding={**exact, **changes})
        self.assertEqual(LyricsContentBlocker().reasons, ('content-evidence-unavailable',))
        self.assertNotIn('pronunciation-unresolved', str(blocker))

    def test_source_digest_is_named_order_independent_and_detects_changed_sources(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            first, second = Path(directory) / 'first', Path(directory) / 'second'
            first.mkdir()
            second.mkdir()
            for root, names in ((first, ('a.py', 'b.py')), (second, ('b.py', 'a.py'))):
                for name in names:
                    (root / name).write_text(name, encoding='utf-8')
            digest = engine_source_sha256(first)
            self.assertEqual(digest, engine_source_sha256(second))
            (second / 'a.py').write_text('changed', encoding='utf-8')
            self.assertNotEqual(digest, engine_source_sha256(second))
            (second / 'a.py').write_text('a.py', encoding='utf-8')
            (second / 'a.py').rename(second / 'renamed.py')
            self.assertNotEqual(digest, engine_source_sha256(second))


    def test_every_current_outcome_round_trips_through_the_store(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            store = ImmutableReportStore(root, 65536)
            reports = root / 'reports'
            start = OperationStart('a' * 32, 'a1B2c3D4e5F', 'b' * 32, 'Validate',
                                   1, 'NEW', ReportBindings('c' * 64, 'd' * 64, 'e' * 64))
            for index, outcome in enumerate(WorkflowOutcome):
                with self.subTest(outcome=outcome.value):
                    record = start.finish(f'{index:032x}', 2, 'NEW', outcome.value).to_record()
                    self.assertTrue(operation_record_is_safe(record))
                    digest = store.commit(reports, record, current_pointer='current-operation.json')
                    run_dir = reports / 'runs' / record['runId']
                    payload = (run_dir / 'run.json').read_bytes()
                    self.assertEqual(json.loads(payload), record)
                    self.assertEqual(hashlib.sha256(payload).hexdigest(), digest)
                    self.assertTrue(store.artifacts_match(run_dir, record))
                    self.assertFalse((reports / 'current-operation.json').exists())
                    self.assertEqual(report_control_path(reports, 'current-operation.json').parent, reports / 'runtime')
                    self.assertEqual(json.loads((report_control_path(reports, 'current-operation.json')).read_bytes()),
                                     {'runId': record['runId'], 'runSha256': digest})

    def test_invalid_outcome_cannot_commit_or_replace_the_current_pointer(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            store = ImmutableReportStore(root, 65536)
            reports = root / 'reports'
            start = OperationStart('a' * 32, 'a1B2c3D4e5F', 'b' * 32, 'Validate',
                                   1, 'NEW', ReportBindings('c' * 64, 'd' * 64, 'e' * 64))
            accepted = start.finish('e' * 32, 2, 'NEW', 'success').to_record()
            store.commit(reports, accepted, current_pointer='current-operation.json')
            before = {path.relative_to(root): path.read_bytes() for path in root.rglob('*')
                      if path.is_file()}
            for changes in ({'outcomeCode': 'invented-success'},
                            {'operationId': int('1' * 32)}, {'operationId': 'B' * 32},
                            {'status': 'failed'}, {'completedAtUnix': 0},
                            {'bindings': {'policySha256': 'd' * 64}}):
                with self.subTest(changes=changes):
                    rejected = copy.deepcopy(accepted)
                    rejected.update(runId='f' * 32, **changes)
                    self.assertFalse(operation_record_is_safe(rejected))
                    with self.assertRaises(FutureSongError):
                        store.commit(reports, rejected, current_pointer='current-operation.json')
                    self.assertFalse((reports / 'runs' / ('f' * 32)).exists())
                    self.assertEqual({path.relative_to(root): path.read_bytes()
                                      for path in root.rglob('*') if path.is_file()}, before)


if __name__ == '__main__':
    unittest.main()
