"""Unlimited resources retain exact acceptance, accounting and safe Retry."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
from stemwise_engine.alignment_attempt import SongAttemptLedger, SongAttemptBlocked
from stemwise_engine.common import run_process_tree
from stemwise_engine.common import atomic_write_json


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class SongAttemptTests(unittest.TestCase):
    def test_unchanged_restart_retries_only_confirmed_retired_failed_actions(self):
        from stemwise_engine.worker_protocol import WorkerProtocolError
        material = {'audio': 'exact-recording'}
        self.ledger.reserve([self.action('prior'), self.action('first-stage')])
        self.ledger.begin('prior')
        self.ledger.complete('prior', result={'manifestSha256': 'a'*64})
        self.ledger.begin('first-stage')
        self.now += 3
        self.ledger.fail('first-stage', WorkerProtocolError('ctc-worker-failed'))
        original_sha = self.ledger.record['materialSha256']
        self.ledger.restart(material)
        self.assertEqual(self.ledger.record['generation'], 2)
        self.assertEqual(self.ledger.record['materialSha256'], original_sha)
        self.assertEqual(self.ledger.record['generations'][-1]['actionCounts']['failed'], 1)
        self.assertEqual(self.ledger.record['generations'][-1]['actionCounts']['complete'], 1)
        self.assertEqual(self.ledger.record['recoverySeconds'], 3)
        self.ledger.reserve([self.action('first-stage')])
        self.ledger.begin('first-stage')
        self.ledger.complete('first-stage', result={'manifestSha256': 'b'*64})
        self.ledger.restart(material)
        self.assertEqual(self.ledger.record['generation'], 2)
        self.ledger.reserve([self.action('unknown')])
        self.ledger.begin('unknown')
        accepted = self.path.read_bytes()
        with self.assertRaises(SongAttemptBlocked):
            self.ledger.begin('unknown')
        self.assertEqual(self.path.read_bytes(), accepted)

    def test_restarts_bound_history_and_preserve_readable_current_decisions(self):
        for generation in range(12):
            actions = [{**self.action(f'{generation}-{index}'), 'request': {'detail': 'x'*900}}
                       for index in range(100)]
            self.ledger.reserve(actions)
            self.ledger.begin(actions[0]['requestId'])
            self.ledger.complete(actions[0]['requestId'], result={'status': 'available'})
            self.ledger.restart({'generation': generation})
            self.assertLessEqual(self.path.stat().st_size, 1024*1024)
            current = SongAttemptLedger(self.path)
            self.assertEqual(current.record['generation'], generation+2)
        current.reserve([self.action('current')])
        current.begin('current')
        self.assertEqual(SongAttemptLedger(self.path).record['actions']['current']['status'], 'unknown')
        committed = self.path.read_bytes()
        current.record['actions']['current']['result'] = 'x'*(1024*1024)
        with self.assertRaisesRegex(ValueError, 'readable byte limit'):
            current.save()
        self.assertEqual(self.path.read_bytes(), committed)

    def setUp(self):
        work = ROOT/'Test suite/work/accuracy-budget'
        work.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=work)
        self.addCleanup(self.temp.cleanup)
        self.now = 1000.
        self.path = Path(self.temp.name)/'song-attempt.private.json'
        self.ledger = SongAttemptLedger(self.path, {'audio': 'exact-recording'},
            clock=lambda: self.now)

    def action(self, name, region='region-a', kind='timing'):
        return dict(requestId=name, regionId=region, kind=kind, seconds=10, gpuGiB=2, hostGiB=3)

    def test_failed_recovery_retires_accounting_without_granting_unknown_replay(self):
        from subprocess import TimeoutExpired
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.worker_protocol import WorkerProtocolError
        self.ledger.reserve([self.action('local'), self.action('uncertain')])
        self.ledger.begin('local')
        self.now += 12
        self.ledger.fail('local', WorkerProtocolError('ctc-worker-failed'))
        self.assertEqual(self.ledger.record['recoverySeconds'], 12)
        self.assertIsNone(self.ledger.record['recoverySince'])
        self.ledger.begin('uncertain')
        accepted = self.path.read_bytes()
        with self.assertRaises(ValueError):
            self.ledger.fail('uncertain', WorkerRetirementError('unconfirmed'))
        self.assertEqual(self.path.read_bytes(), accepted)
        self.now += 8
        self.ledger.fail('uncertain', TimeoutExpired('local-worker', 8))
        self.assertEqual(self.ledger.record['actions']['uncertain']['failure'], {
            'exceptionType': 'TimeoutExpired', 'code': 'worker-stage-deadline'})
        self.assertEqual(self.ledger.record['recoverySeconds'], 20)
        with self.assertRaises(SongAttemptBlocked):
            self.ledger.begin('local')
        self.ledger.restart({'audio': 'replacement'})
        self.assertEqual(self.ledger.record['generations'][0]['actionCounts']['failed'], 2)

    def test_active_bookkeeping_preserves_terminal_primary_and_new_retirement_failure(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        for boundary in ('refresh', 'save'):
            for primary in (WorkerRetirementError('original-owner'), ProviderCancelled('cancelled'),
                            ProviderDeadline('parent-deadline'), RuntimeError('ordinary')):
                for bookkeeping in (OSError('accounting I/O'), WorkerRetirementError('new-owner')):
                    with self.subTest(boundary=boundary, primary=type(primary).__name__, bookkeeping=type(bookkeeping).__name__):
                        original = getattr(self.ledger, boundary)
                        calls = 0
                        def account():
                            nonlocal calls
                            calls += 1
                            if calls == 2:
                                raise bookkeeping
                            return original()
                        expected = (bookkeeping if not isinstance(primary, WorkerRetirementError)
                            and isinstance(bookkeeping, WorkerRetirementError) else primary)
                        with patch.object(self.ledger, boundary, side_effect=account):
                            with self.assertRaises(type(expected)) as raised:
                                with self.ledger.active():
                                    raise primary
                        self.assertIs(raised.exception, expected)
                        self.ledger.refresh()
                        self.ledger.record['activeSince'] = None
                        self.ledger.save()




    def test_primary_unknown_and_changed_inputs_cannot_be_renamed_as_baseline(self):
        with self.assertRaisesRegex(RuntimeError, 'interrupted'):
            with self.ledger.primary('alignment', {'audio': 'a', 'text': 'b'}):
                raise RuntimeError('interrupted')
        self.assertEqual(self.ledger.record['primaryActions']['alignment']['status'], 'unknown')
        restarted = SongAttemptLedger(self.path)
        with self.assertRaises(SongAttemptBlocked):
            with restarted.primary('alignment', {'audio': 'a', 'text': 'b'}):
                self.fail('interrupted primary replayed')

    def test_explicit_restart_preserves_prior_attempt_and_allows_corrected_inputs(self):
        with self.ledger.primary('alignment', {'text': 'old'}):
            pass
        self.ledger.restart({'text': 'corrected'})
        with self.ledger.primary('alignment', {'text': 'corrected'}):
            pass
        self.assertEqual(self.ledger.record['generation'], 2)
        self.assertEqual(self.ledger.record['generations'][0]['primaryActions']['alignment']['status'], 'complete')
        self.assertNotEqual(self.ledger.record['generations'][0]['primaryActions']['alignment']['requestSha256'],
            self.ledger.record['primaryActions']['alignment']['requestSha256'])

    @patch('stemwise_engine.pipeline.collect_provenance',
        new=lambda **_: {'fixture': 'initial-failure-numerical-producer'})
    def test_caught_initial_pipeline_failure_keeps_retry_admission(self):
        import time
        sys.path.insert(0, str(ROOT/'Test suite/pc/unit'))
        import stemwise_engine.pipeline as pipeline_module
        import stemwise_engine.youtube as youtube
        from stemwise_engine.pipeline import StemwisePipeline
        from test_pipeline_hardening import AnalysisRetryTests, package_config, VIDEO_ID
        from test_packages import song_document
        from stemwise_engine.future_song import FutureSongWorkflow
        self.ledger.clock = time.time
        self.ledger.record['startedAt'] = time.time()
        self.ledger.save()
        pipeline = StemwisePipeline(package_config(self.path.parent))
        pipeline._provenance_needs_refresh = False
        item = {'videoId': VIDEO_ID, 'position': 1, 'title': 'Song'}
        job = {'videoId': VIDEO_ID, 'state': 'ANALYSIS_FAILED'}
        from workflow_fixture import initialized_workflow
        workflow = initialized_workflow(self.path.parent)
        workflow._job_path = lambda _: self.path.parent/'job.json'
        workflow.report_store.report_root = lambda _: self.path.parent/'reports'
        workflow._load_job = lambda *args, **options: job
        workflow._song_ledger = lambda _: self.ledger
        workflow._require_platform_content_allowed = lambda _: None
        workflow._require_selected_audio = lambda _: None
        workflow._reconcile_interrupted_validation = lambda value, **options: value
        workflow._run_mutating_operation_under_telemetry = lambda video, action, callback: callback()
        atomic_write_json(workflow._job_path(VIDEO_ID), job)
        calls = 0
        def download(video, directory, **options):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise RuntimeError('caught acquisition failure')
            audio = directory/'analysis.wav'
            audio.write_bytes(b'known-answer numerical fixture')
            return {'id': video}, audio
        first, second, third = AnalysisRetryTests().patches(pipeline)
        with first, second, third, patch.object(youtube, 'download_analysis_audio', side_effect=download), \
                patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                patch.object(pipeline_module, 'enrich_metadata', side_effect=lambda value, *args, **kwargs: (value, {})), \
                patch.object(pipeline, '_analysis', return_value=song_document(VIDEO_ID)['analysis']):
            launch = lambda: workflow._process_song(pipeline, item, force=True)
            self.assertEqual(workflow._run_mutating_operation(VIDEO_ID, 'Retry', launch), 'failed')
            self.ledger.refresh()
            charged = self.ledger.record['activeSeconds']
            self.assertEqual(self.ledger.record['primaryActions']['acquisition-analysis']['status'], 'complete')
            self.assertNotIn('disposition', self.ledger.record)
            self.assertEqual(workflow._run_mutating_operation(VIDEO_ID, 'Retry', launch), 'complete')
        self.ledger.refresh()
        self.assertGreaterEqual(self.ledger.record['activeSeconds'], charged)
        self.assertEqual(calls, 2)
        self.assertEqual(pipeline.load_checkpoint(VIDEO_ID)['attempt'], 2)

    def test_nested_clock_excludes_recorded_wait(self):
        with self.ledger.active():
            self.now += 20
            with self.ledger.active():
                self.now += 10
            with self.ledger.lease_wait():
                self.now += 50
            self.now += 10
        self.assertEqual(self.ledger.record['activeSeconds'], 40)
        self.assertEqual(self.ledger.record['waitSeconds'], 50)

    def test_wait_accounting_failure_releases_the_acquired_heavy_lease(self):
        from stemwise_engine.common import heavy_worker_stage, heavy_worker_lock_path, exclusive_process_lock
        config = {'projectDir': str(self.path.parent/'project')}
        save = self.ledger.save
        writes = 0
        def fail_after_acquisition():
            nonlocal writes
            writes += 1
            if writes == 2:
                raise OSError('wait accounting save failed')
            save()
        with patch('stemwise_engine.alignment_attempt.current_song_ledger', return_value=self.ledger), \
                patch.object(self.ledger, 'save', side_effect=fail_after_acquisition):
            try:
                with heavy_worker_stage(config, wait_seconds=0):
                    self.fail('worker entered after wait accounting failed')
            except OSError as error:
                self.assertEqual(str(error), 'wait accounting save failed')
                # Keep the failing frame alive: cleanup must not depend on GC.
                self.assertIsNotNone(error.__traceback__)
                with exclusive_process_lock(heavy_worker_lock_path(config), wait_seconds=0):
                    pass
            else:
                self.fail('wait accounting error was suppressed')


    def test_supervisor_does_not_apply_old_attempt_time_quota(self):
        import time
        self.ledger.record['activeSeconds'] = 100000.
        self.ledger.record['startedAt'] = 1.
        self.ledger.save()
        self.ledger.clock = time.time
        with self.ledger.active():
            result = run_process_tree([sys.executable, '-c', 'print("completed")'], timeout=5, capture_output=True)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), b'completed')

    def test_command_admission_and_status_honor_terminal_across_stage_change(self):
        from stemwise_engine.future_song import FutureSongWorkflow, FutureSongError
        from types import SimpleNamespace
        sys.path.insert(0, str(ROOT/'Test suite/pc/unit'))
        from workflow_fixture import initialized_workflow
        workflow = initialized_workflow(self.path.parent)
        workflow._job_path = lambda _: self.path.parent/'job.json'
        workflow._job_dir = lambda _: self.path.parent
        workflow._require_platform_content_allowed = lambda _: None
        workflow._require_selected_audio = lambda _: None
        job = {'videoId': 'AAAAAAAAAAA', 'state': 'ANALYSIS_FAILED'}
        atomic_write_json(workflow._job_path('AAAAAAAAAAA'), job)
        workflow._load_job = lambda *_, **__: job
        self.ledger.stop('technical-failure', 'unknown-owner')
        workflow._song_ledger = lambda _: self.ledger
        workflow._validated_snapshot_is_current = lambda _, **options: False
        for state in ('ANALYSIS_FAILED', 'LINE_UNRESOLVED', 'RETRYABLE_TECHNICAL'):
            job['state'] = state
            with patch.object(workflow, '_run_mutating_operation_under_telemetry', side_effect=lambda *args: args[-1]()):
                self.assertEqual(workflow._run_mutating_operation('AAAAAAAAAAA', 'Retry', lambda: 'accepted'), 'accepted')
        self.assertEqual(self.ledger.record['generations'][0]['disposition']['code'], 'technical-failure')



if __name__ == '__main__':
    unittest.main()
