"""Selected native FLAC reaches the real decoder; old campaign state stays intact."""
import importlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import patch

import numpy as np
import soundfile as sf

PROJECT = Path(__file__).resolve().parents[3]
from stemwise_engine.common import atomic_write_json, run_process_tree
from test_packages import package_config, song_document
from stemwise_engine.waveform import build_waveform


class RecordingAnalysisTest(unittest.TestCase):
    def test_operation_readback_keeps_its_admitted_producer(self):
        import copy
        self.runtime_identity['execution'] = {'main': {'device': 'cpu', 'torch': 'qualified-v1'}}
        cleanup = self.module._finish_completed_cleanup
        expected = self.module.acoustic_producer_identity(self.config)
        def replace_latest(config, key, current):
            cleanup(config, key, current)
            producer = copy.deepcopy(current['producerIdentity'])
            producer['runtime']['execution']['main']['torch'] = 'other-execution'
            job = self.root/current['job']
            sibling = job.parent.parent/f'v2-{self.module.provenance_fingerprint(producer)}'/'job.json'
            self.write(sibling, {**json.loads(job.read_bytes()), 'producerIdentity': producer,
                'state': 'ANALYSIS_FAILED', 'reason': 'unrelated-execution-failed'})
            newer = job.stat().st_mtime_ns+1_000_000_000
            os.utime(sibling, ns=(newer, newer))
        def provenance(**kwargs):
            return self.runtime_identity if kwargs.get('observe_execution', True) else {
                name: value for name, value in self.runtime_identity.items() if name != 'execution'}
        with patch.object(self.module, 'collect_acoustic_provenance', side_effect=provenance), \
                patch.object(self.module, '_finish_completed_cleanup', side_effect=replace_latest), \
                patch.object(self.module, 'run_analysis_worker', self.worker):
            first = self.module.analyze(self.config, 'qobuz:11')
            reused = self.module.analyze(self.config, 'qobuz:11')
        for result in (first, reused):
            self.assertEqual(result['state'], 'ANALYSIS_COMPLETE')
            self.assertEqual(result['producerIdentity'], expected)
        self.assertTrue(reused['reused'])
        self.assertEqual(len(self.observed), 1)

    def test_status_and_reuse_isolate_damaged_siblings_but_verify_selected_result(self):
        import copy
        import io
        from contextlib import redirect_stdout
        self.runtime_identity['execution'] = {'main': {'device': 'cpu', 'torch': 'qualified-v1'}}
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            first = self.module.analyze(self.config, 'qobuz:11')
        producer = first['producerIdentity']
        job_path, analysis_path = self.root/first['job'], self.root/first['analysis']
        original = analysis_path.read_bytes()
        source = self.audio.read_bytes()
        sibling = job_path.parent.parent/('v2-'+'a'*64)/'job.json'
        sibling.parent.mkdir()
        read = self.module.read_bounded_json
        def provenance(**kwargs):
            return self.runtime_identity if kwargs.get('observe_execution', True) else {
                name: value for name, value in self.runtime_identity.items() if name != 'execution'}
        for fault in ('json', 'unreadable', 'disappearing', 'stat'):
            with self.subTest(fault=fault):
                sibling.write_text('{bad json' if fault == 'json' else '{}', encoding='utf-8')
                candidate_read = False
                def read_candidate(path, **kwargs):
                    nonlocal candidate_read
                    if Path(path) == sibling:
                        if fault == 'unreadable': raise PermissionError('unreadable unrelated job')
                        if fault == 'disappearing':
                            sibling.unlink()
                            raise FileNotFoundError('unrelated job disappeared')
                    value = read(path, **kwargs)
                    if Path(path) == sibling: candidate_read = True
                    return value
                # A matching candidate can disappear at the stat boundary too.
                stat = Path.stat
                if fault == 'stat':
                    sibling_producer = copy.deepcopy(producer)
                    sibling_producer['runtime']['execution']['main']['torch'] = 'other-execution'
                    sibling = job_path.parent.parent/f'v2-{self.module.provenance_fingerprint(sibling_producer)}'/'job.json'
                    self.write(sibling, {**json.loads(job_path.read_bytes()), 'producerIdentity': sibling_producer})
                def stat_candidate(path, *args, **kwargs):
                    if path == sibling and fault == 'stat' and candidate_read:
                        raise FileNotFoundError(2, 'unrelated job disappeared at stat')
                    return stat(path, *args, **kwargs)
                with patch.object(self.module, 'collect_acoustic_provenance', side_effect=provenance), \
                        patch.object(self.module, 'read_bounded_json', side_effect=read_candidate), \
                        patch.object(Path, 'stat', stat_candidate), \
                        patch.object(self.module, 'run_analysis_worker', side_effect=AssertionError('cache reuse reran models')):
                    self.assertEqual(self.module.status(self.config, 'qobuz:11')['analysis'], first['analysis'])
                    captured = io.StringIO()
                    with patch.object(self.module, 'load_config', return_value=self.config), redirect_stdout(captured):
                        self.assertEqual(self.module.main(['--config', str(self.builder/'config.json'), 'status', '--recording-key', 'qobuz:11']), 0)
                    self.assertEqual(json.loads(captured.getvalue())['analysis'], first['analysis'])
                    reused = self.module.analyze(self.config, 'qobuz:11')
                    self.assertTrue(reused['reused'])
                    self.assertEqual(reused['producerIdentity'], producer)
                self.assertEqual(analysis_path.read_bytes(), original)
                self.assertEqual(self.audio.read_bytes(), source)
                sibling.unlink(missing_ok=True)
        analysis_path.write_bytes(b'corrupted selected analysis')
        with patch.object(self.module, 'collect_acoustic_provenance', side_effect=provenance):
            with self.assertRaisesRegex(ValueError, 'was changed'):
                self.module.status(self.config, 'qobuz:11')
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled
        for error in (WorkerRetirementError('unretired cache reader'), ProviderCancelled('inspection cancelled')):
            sibling.write_text('{}', encoding='utf-8')
            def failed_read(path, **kwargs):
                if Path(path) == sibling: raise error
                return read(path, **kwargs)
            with patch.object(self.module, 'collect_acoustic_provenance', side_effect=provenance), \
                    patch.object(self.module, 'read_bounded_json', side_effect=failed_read):
                with self.assertRaises(type(error)) as raised: self.module.status(self.config, 'qobuz:11')
                self.assertIs(raised.exception, error)

    def test_fatal_model_retirement_preserves_decoded_input_and_prior_source(self):
        from stemwise_engine.common import WorkerRetirementError, process_lock_descriptor
        held, inputs = [], []
        source = self.audio.read_bytes()
        def worker(audio, *_args, **_options):
            inputs.append(Path(audio))
            held.append(os.dup(process_lock_descriptor(Path(audio).parent/'.stemwise-worker.lock')))
            raise WorkerRetirementError('model worker retains its input')
        try:
            with patch.object(self.module, 'run_analysis_worker', side_effect=worker):
                with self.assertRaises(WorkerRetirementError):
                    self.module.analyze(self.config, 'qobuz:11')
            self.assertEqual(len(inputs), 1)
            self.assertTrue(inputs[0].is_file())
            self.assertEqual(self.audio.read_bytes(), source)
            self.assertEqual(self.module.status(self.config, 'qobuz:11')['state'], 'ANALYSIS_FAILED')
        finally:
            for descriptor in held:
                os.close(descriptor)

    def test_completed_analysis_recovers_cleanup_after_receipt_unlink_without_rerunning_models(self):
        import stemwise_engine.common as common
        original = os.unlink
        def interrupt(path, *args, **kwargs):
            original(path, *args, **kwargs)
            if Path(path).name == common.GENERATED_MANIFEST_NAME:
                raise KeyboardInterrupt('interrupted cleanup')
        with patch.object(self.module, 'run_analysis_worker', self.worker), patch.object(os, 'unlink', side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.module.analyze(self.config, 'qobuz:11')
        completed = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(completed['state'], 'ANALYSIS_COMPLETE')
        self.assertTrue(completed['cleanupPending'])
        raw = (self.root/completed['analysis']).read_bytes()
        with patch.object(self.module, 'run_analysis_worker', side_effect=AssertionError('must reuse analysis')), \
                patch.object(self.module, 'decode_analysis_audio', side_effect=AssertionError('must not decode')):
            recovered = self.module.analyze(self.config, 'qobuz:11')
        self.assertTrue(recovered['reused'])
        self.assertNotIn('cleanupPending', recovered)
        self.assertEqual((self.root/recovered['analysis']).read_bytes(), raw)
        self.assertEqual(list((self.pc/'temporary-analysis/recordings').iterdir()), [])

    def test_disposable_run_failure_before_and_after_publication_preserves_analysis(self):
        original_write = self.module.atomic_write_json
        for phase in ('running', 'complete'):
            with self.subTest(phase=phase):
                def write(path, value, *args, **kwargs):
                    if '/runs/' in Path(path).as_posix() and value.get('status') == phase:
                        raise OSError('disposable telemetry unavailable')
                    return original_write(path, value, *args, **kwargs)
                with patch.object(self.module, 'atomic_write_json', side_effect=write), patch.object(
                        self.module, 'run_analysis_worker', self.worker):
                    result = self.module.analyze(self.config, 'qobuz:11')
                self.assertEqual(result['state'], 'ANALYSIS_COMPLETE')
                self.assertTrue((self.root/result['analysis']).is_file())

    def test_explicit_repair_runs_failed_components_and_preserves_committed_output_on_failure(self):
        import stemwise_engine.analysis_numerics as numerics
        def partial(*args):
            value = self.worker(*args)
            value['tuning'] = {'status': 'uncertain', 'frequencyHz': 440., 'centsFromA440': 0., 'reason': 'Intrinsic dispersion'}
            value['loudness'] = {'status': 'complete', 'integratedLufs': -18., 'loudnessRangeLu': 2.}
            value['toolRuns'][0] = {'name': 'beat-this-full', 'status': 'failed', 'version': '1', 'reason': 'transient'}
            value['toolRuns'][1] = {'name': 'stemwise-rhythm-normalizer', 'status': 'skipped', 'reason': 'No beats'}
            return value
        with patch.object(self.module, 'run_analysis_worker', partial):
            first = self.module.analyze(self.config, 'qobuz:11')
        path = self.root/first['analysis']
        committed_job = json.loads((self.root/first['job']).read_bytes())
        original = path.read_bytes()
        prior = json.loads(original)
        for fail in (True, False):
            def worker(audio, duration, project, timeout, *, repair_from):
                retained = json.loads(Path(repair_from).read_bytes())
                with patch.object(numerics, 'analyze_beat_this', side_effect=(RuntimeError('still failed') if fail else None),
                        return_value=([0, 400, 800], [0], {'name': 'beat-this-full', 'status': 'complete', 'version': '1'})) as beats, \
                        patch.object(numerics, 'analyze_skey', side_effect=AssertionError('successful key producer reran')), \
                        patch.object(numerics, 'analyze_essentia', side_effect=AssertionError('successful descriptor producer reran')), \
                        patch.object(numerics, 'analyze_allin1', side_effect=AssertionError('unchanged structure producer reran')), \
                        patch.object(numerics, 'build_waveform', side_effect=AssertionError('successful waveform reran')):
                    result = numerics._repair_analysis_worker(audio, duration, project, timeout, retained)
                self.assertEqual(beats.call_count, 1)
                return result
            with patch.object(self.module, 'run_analysis_worker', worker):
                if fail:
                    with self.assertRaises(RuntimeError):
                        self.module.analyze(self.config, 'qobuz:11', repair=True)
                    self.assertEqual(path.read_bytes(), original)
                    failed_job = json.loads((self.root/first['job']).read_bytes())
                    for field in ('formatVersion', 'engineVersion', 'input', 'producerIdentity', 'pcm', 'analysisSha256'):
                        self.assertEqual(failed_job[field], committed_job[field])
                    self.assertEqual(self.root/self.module.status(self.config, 'qobuz:11')['analysis'], path)
                    self.assertEqual(self.module.status(self.config, 'qobuz:11')['state'], 'ANALYSIS_COMPLETE')
                else:
                    repaired = self.module.analyze(self.config, 'qobuz:11', repair=True)
                    result = json.loads((self.root/repaired['analysis']).read_bytes())
                    self.assertEqual((self.root/repaired['analysis']).name, 'analysis-' + self.module.sha256_file(self.root/repaired['analysis']) + '.json')
                    self.assertEqual(path.read_bytes(), original)
                    self.assertEqual(result['rhythm']['bpm'], 150.)
                    for feature in ('key', 'structure', 'tuning', 'loudness', 'waveform'):
                        self.assertEqual(result[feature], prior[feature])

    def test_replaced_dead_running_attempt_is_retired_and_budget_eligible(self):
        import stemwise_engine.telemetry_retention as telemetry_retention
        producer = self.module.acoustic_producer_identity(self.config)
        script = ("import sys,json,os;sys.path.insert(0,sys.argv[1]);import stemwise_engine.recording_analysis as a;a.acoustic_producer_identity=lambda config:json.loads(sys.argv[3]);a.run_analysis_worker=lambda *args:os._exit(73);a.analyze(json.loads(sys.argv[2]),'qobuz:11')")
        killed = run_process_tree([sys.executable, '-B', '-c', script,
            str(Path(self.module.__file__).parent), json.dumps(self.config), json.dumps(producer)],
            capture_output=True, text=True, timeout=30)
        self.assertEqual(killed.returncode, 73, killed.stderr)
        first = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(first['state'], 'ANALYZING')
        self.assertEqual(first['latestRun']['status'], 'running')
        predecessor = self.root/first['latestRunFile']
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            second = self.module.analyze(self.config, 'qobuz:11')
        self.assertEqual(json.loads(predecessor.read_bytes())['status'], 'interrupted')
        source = self.root/'Engine telemetry/qobuz-11'
        result = telemetry_retention.cleanup_source(self.root, source, max_count=0, max_bytes=0)
        self.assertGreaterEqual(result['removedCount'], 1)
        self.assertFalse(predecessor.exists())
        self.assertTrue((self.root/second['latestRunFile']).is_file())

    def test_private_adapter_causes_remain_in_current_run_and_out_of_the_analysis_cache(self):
        import stemwise_engine.pipeline as pipeline
        def worker(audio, duration, project, timeout):
            result = self.worker(audio, duration, project, timeout)
            result['_privateDiagnostics'] = [{'errorType': 'RuntimeError', 'body': 'private optional descriptor cause'}]
            return result
        before = self.audio.read_bytes()
        with patch.object(pipeline, '_run_analysis_single', side_effect=worker):
            result = self.module.analyze(self.config, 'qobuz:11')
        self.assertEqual(result['state'], 'ANALYSIS_COMPLETE')
        reference, = result['latestRun']['privateDiagnostics']
        self.assertIn('Engine telemetry/', reference['file'])
        diagnostic = self.root/reference['file']
        self.assertEqual(diagnostic.read_text(encoding='utf-8'), 'private optional descriptor cause')
        self.assertEqual(self.module.sha256_file(diagnostic), reference['sha256'])
        self.assertNotIn('private optional', (self.root/result['analysis']).read_text(encoding='utf-8'))
        self.assertEqual(self.audio.read_bytes(), before)

    def test_private_run_records_actual_decode_analysis_and_cache_reuse(self):
        token = 'a' * 32
        def worker(*args):
            job_path, = (self.pc / 'generated-packages/recording-analysis').rglob('job.json')
            job = json.loads(job_path.read_bytes())
            running = json.loads((self.root / job['latestRunFile']).read_bytes())
            self.assertEqual(running['stages']['analysis']['status'], 'running')
            self.assertEqual(running['cacheReason'], 'no-matching-producer-pcm-cache')
            time.sleep(.02)
            return self.worker(*args)
        with patch.dict(os.environ, {'STEMWISE_LAUNCH_TOKEN': token}), \
                patch.object(self.module, 'run_analysis_worker', worker):
            first = self.module.analyze(self.config, 'qobuz:11')
        run = first['latestRun']
        self.assertEqual(run['operationId'], token)
        self.assertEqual(run['status'], 'complete')
        self.assertFalse(run['cacheReused'])
        self.assertEqual(run['cacheReason'], 'no-matching-producer-pcm-cache')
        self.assertEqual(run['pcm'], first['pcm'])
        self.assertEqual(run['producerSha256'], self.module.provenance_fingerprint(first['producerIdentity']))
        for stage in ('decode', 'analysis'):
            self.assertEqual(run['stages'][stage]['status'], 'complete')
            self.assertGreaterEqual(run['stages'][stage]['elapsedMs'], 0)
        self.assertGreaterEqual(run['stages']['analysis']['elapsedMs'], 20)
        telemetry = self.root / first['latestRunFile']
        self.assertEqual(json.loads(telemetry.read_bytes()), run)
        self.assertEqual(telemetry.relative_to(self.root).parts[:3], ('Engine telemetry', 'qobuz-11', 'acoustic'))
        self.assertIn(run['input']['sha256'], telemetry.parts)
        cache_job = json.loads((self.root / first['job']).read_bytes())
        self.assertNotIn('latestRun', cache_job)
        self.assertFalse(list((self.root / first['job']).parent.glob('*.private.txt')))
        before = (self.root / first['analysis']).read_bytes()
        with patch.dict(os.environ, {'STEMWISE_LAUNCH_TOKEN': 'bad-token'}), \
                patch.object(self.module, 'run_analysis_worker', side_effect=AssertionError('cache hit reran models')):
            again = self.module.analyze(self.config, 'qobuz:11')
        self.assertTrue(again['latestRun']['cacheReused'])
        self.assertEqual(again['latestRun']['cacheReason'], 'complete-recording-cache')
        self.assertEqual(again['latestRun']['pcm'], first['pcm'])
        self.assertNotIn('operationId', again['latestRun'])
        self.assertEqual((self.root / first['analysis']).read_bytes(), before)

    def test_failure_and_interrupt_retain_bounded_private_cause_with_pcm_binding(self):
        import stemwise_engine.pipeline as pipeline
        failures = (pipeline._analysis_failure('analysis worker failed',
            subprocess.CompletedProcess(['worker'], 17, '', 'private numerical stderr'), 'worker-exit'),
            KeyboardInterrupt('private interrupted inference'))
        for error in failures:
            with self.subTest(error=type(error).__name__), patch.object(self.module, 'run_analysis_worker', side_effect=error):
                with self.assertRaises(type(error)) as raised:
                    self.module.analyze(self.config, 'qobuz:11')
                self.assertIs(raised.exception, error)
                current = self.module.status(self.config, 'qobuz:11')
                self.assertEqual(current['state'], 'ANALYSIS_FAILED')
                run = current['latestRun']
                self.assertEqual(run['status'], 'interrupted' if isinstance(error, KeyboardInterrupt) else 'failed')
                self.assertEqual(run['stages']['decode']['status'], 'complete')
                self.assertEqual(run['stages']['analysis']['status'], run['status'])
                self.assertEqual(run['pcm']['durationMs'], 1000)
                self.assertFalse(run['cacheReused'])
                diagnostic = run['privateDiagnostic']
                path = (self.root / current['latestRunFile']).parent / diagnostic['file']
                self.assertLessEqual(path.stat().st_size, 12000)
                self.assertEqual(self.module.sha256_file(path), diagnostic['sha256'])
                self.assertEqual(path.stat().st_size, diagnostic['byteCount'])
                self.assertIn('private', path.read_text(encoding='utf-8'))
                if not isinstance(error, KeyboardInterrupt):
                    self.assertIn('private numerical stderr', path.read_text(encoding='utf-8'))
                    self.assertEqual(run['workerExitCode'], 17)

    def test_success_retains_prior_failure_and_status_rejects_wrong_telemetry_binding(self):
        with patch.object(self.module, 'run_analysis_worker', side_effect=RuntimeError('retained engine cause')):
            with self.assertRaises(RuntimeError):
                self.module.analyze(self.config, 'qobuz:11')
        failed = self.module.status(self.config, 'qobuz:11')
        failure_path = self.root / failed['latestRunFile']
        failure_bytes = failure_path.read_bytes()
        diagnostic_path = failure_path.parent / failed['latestRun']['privateDiagnostic']['file']
        diagnostic_bytes = diagnostic_path.read_bytes()
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            completed = self.module.analyze(self.config, 'qobuz:11')
        self.assertEqual(failure_path.read_bytes(), failure_bytes)
        self.assertEqual(diagnostic_path.read_bytes(), diagnostic_bytes)
        self.assertNotEqual(completed['latestRunFile'], failed['latestRunFile'])
        analysis_bytes = (self.root / completed['analysis']).read_bytes()
        telemetry = self.root / completed['latestRunFile']
        run = json.loads(telemetry.read_bytes())
        run['input']['sha256'] = '0' * 64
        atomic_write_json(telemetry, run)
        current = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(current['state'], 'ANALYSIS_COMPLETE')
        self.assertNotIn('latestRun', current)
        self.assertEqual(current['latestRunError'], 'ValueError')
        telemetry.unlink()
        current = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(current['state'], 'ANALYSIS_COMPLETE')
        self.assertIn('latestRunError', current)
        with patch.object(self.module, 'run_analysis_worker', side_effect=AssertionError('valid acoustic cache reran')):
            retry = self.module.analyze(self.config, 'qobuz:11')
        self.assertTrue(retry['reused'])
        self.assertEqual((self.root / retry['analysis']).read_bytes(), analysis_bytes)

    def test_failed_private_diagnostic_does_not_replace_the_worker_error(self):
        error = RuntimeError('original numerical failure')
        with patch.object(self.module, 'run_analysis_worker', side_effect=error), \
                patch('stemwise_engine.private_diagnostics.preserve_failure', side_effect=PermissionError('diagnostic locked')):
            with self.assertRaises(RuntimeError) as raised:
                self.module.analyze(self.config, 'qobuz:11')
        self.assertIs(raised.exception, error)
        current = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(current['state'], 'ANALYSIS_FAILED')
        self.assertEqual(current['latestRun']['diagnosticError'], 'PermissionError')
        self.assertNotIn('privateDiagnostic', current['latestRun'])

    def test_failure_telemetry_and_job_writes_preserve_original_worker_error(self):
        import stemwise_engine.pipeline as pipeline
        write = self.module.atomic_write_json
        for target in ('run', 'job', 'both'):
            error = pipeline._analysis_failure('original worker failure',
                subprocess.CompletedProcess(['worker'], 29, '', 'exact original worker stderr'), 'worker-exit')
            def denied(path, value, **kwargs):
                if ((target in ('run', 'both') and value.get('status') == 'failed')
                        or (target in ('job', 'both') and value.get('state') == 'ANALYSIS_FAILED')):
                    raise OSError('failure telemetry unavailable')
                return write(path, value, **kwargs)
            with self.subTest(target=target), patch.object(self.module, 'atomic_write_json', denied), \
                    patch.object(self.module, 'run_analysis_worker', side_effect=error):
                with self.assertRaises(RuntimeError) as raised:
                    self.module.analyze(self.config, 'qobuz:11')
            self.assertIs(raised.exception, error)
            self.assertEqual(error.returncode, 29)
            self.assertEqual(error.worker_reason, 'worker-exit')
            self.assertIn('exact original worker stderr', error.stderr)
            self.assertEqual(error.telemetry_error, 'OSError')
            current = self.module.status(self.config, 'qobuz:11')
            if target == 'run':
                self.assertEqual(current['state'], 'ANALYSIS_FAILED')
                self.assertEqual(current['telemetryError'], 'OSError')
            elif target == 'job':
                self.assertEqual(current['latestRun']['status'], 'failed')
                self.assertEqual(current['latestRun']['workerExitCode'], 29)

    def test_completed_cleanup_error_survives_failure_job_read_error(self):
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            before = self.module.analyze(self.config, 'qobuz:11')
        read = self.module.read_bounded_json
        cleanup_error = PermissionError('original completed cleanup error')
        failed = False
        def cleanup(*_):
            nonlocal failed
            failed = True
            raise cleanup_error
        def denied(path, **kwargs):
            if failed and path.name == 'job.json':
                raise OSError('cannot reread failure job')
            return read(path, **kwargs)
        with patch.object(self.module, '_finish_completed_cleanup', cleanup), \
                patch.object(self.module, 'read_bounded_json', denied):
            with self.assertRaises(PermissionError) as raised:
                self.module.analyze(self.config, 'qobuz:11')
        self.assertIs(raised.exception, cleanup_error)
        self.assertEqual(cleanup_error.telemetry_error, 'OSError')
        current = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(current['state'], 'ANALYSIS_COMPLETE')
        self.assertEqual(current['latestRun']['status'], 'failed')
        self.assertEqual(current['analysis'], before['analysis'])

    def test_fresh_final_cleanup_write_failure_marks_run_failed_but_keeps_cache(self):
        write = self.module.atomic_write_json
        cleanup_error = PermissionError('original cleanupPending clear failed')
        rejected = False
        def denied(path, value, **kwargs):
            nonlocal rejected
            if (not rejected and path.name == 'job.json' and value.get('state') == 'ANALYSIS_COMPLETE'
                    and 'cleanupPending' not in value):
                rejected = True
                raise cleanup_error
            return write(path, value, **kwargs)
        with patch.object(self.module, 'run_analysis_worker', self.worker), \
                patch.object(self.module, 'atomic_write_json', denied):
            with self.assertRaises(PermissionError) as raised:
                self.module.analyze(self.config, 'qobuz:11')
        self.assertIs(raised.exception, cleanup_error)
        self.assertTrue(rejected)
        current = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(current['state'], 'ANALYSIS_COMPLETE')
        self.assertEqual(current['latestRun']['status'], 'failed')
        self.assertTrue(current['cleanupPending'])
        diagnostic = (self.root / current['latestRunFile']).parent / current['latestRun']['privateDiagnostic']['file']
        self.assertIn('original cleanupPending clear failed', diagnostic.read_text(encoding='utf-8'))
        original = (self.root / current['analysis']).read_bytes()
        with patch.object(self.module, 'run_analysis_worker', side_effect=AssertionError('valid cache reran')):
            retry = self.module.analyze(self.config, 'qobuz:11')
        self.assertTrue(retry['reused'])
        self.assertEqual(retry['latestRun']['status'], 'complete')
        self.assertNotIn('cleanupPending', retry)
        self.assertEqual((self.root / retry['analysis']).read_bytes(), original)

    def test_cleanup_diagnostic_failure_keeps_completed_analysis_and_cache_retry(self):
        import stemwise_engine.common as common
        cleanup_error = PermissionError('owned cleanup pending')
        remove = common.remove_manifested_directory
        def denied(root, path, **kwargs):
            if kwargs['owner'].get('kind') == 'recording-analysis':
                raise cleanup_error
            return remove(root, path, **kwargs)
        with patch.object(self.module, 'run_analysis_worker', self.worker), \
                patch.object(common, 'remove_manifested_directory', denied), \
                patch('stemwise_engine.private_diagnostics.preserve_failure', side_effect=OSError('diagnostic unavailable')):
            with self.assertRaises(PermissionError) as raised:
                self.module.analyze(self.config, 'qobuz:11')
        self.assertIs(raised.exception, cleanup_error)
        current = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(current['state'], 'ANALYSIS_COMPLETE')
        self.assertEqual(current['latestRun']['diagnosticError'], 'OSError')
        original = (self.root/current['analysis']).read_bytes()
        with patch.object(self.module, 'run_analysis_worker', side_effect=AssertionError('completed analysis reran')):
            retry = self.module.analyze(self.config, 'qobuz:11')
        self.assertTrue(retry['latestRun']['cacheReused'])
        self.assertEqual((self.root/retry['analysis']).read_bytes(), original)

    def test_cleanup_failure_preserves_completed_cache_and_retry_does_not_run_models(self):
        import stemwise_engine.common as common
        remove = common.remove_manifested_directory
        abandoned = []
        def denied(root, path, **kwargs):
            if kwargs['owner'].get('kind') == 'recording-analysis':
                common.read_bounded_json(path / common.GENERATED_MANIFEST_NAME,
                    max_bytes=16384, root=root)
                abandoned.append(path)
                raise PermissionError('cleanup locked')
            return remove(root, path, **kwargs)
        with patch.object(self.module, 'run_analysis_worker', side_effect=self.worker), \
                patch.object(common, 'remove_manifested_directory', side_effect=denied):
            with self.assertRaisesRegex(PermissionError, 'cleanup locked'):
                self.module.analyze(self.config, 'qobuz:11')
        complete = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(complete['state'], 'ANALYSIS_COMPLETE')
        before = (self.root / complete['analysis']).read_bytes()
        with patch.object(self.module, 'run_analysis_worker', side_effect=AssertionError('completed inference repeated')):
            recovered = self.module.analyze(self.config, 'qobuz:11')
        self.assertTrue(recovered['reused'])
        self.assertEqual((self.root / recovered['analysis']).read_bytes(), before)
        self.assertFalse(any(path.exists() for path in abandoned))
        self.assertEqual(len(self.observed), 1)

    def setUp(self):
        if self._testMethodName == 'test_canonical_launcher_accepts_recording_actions':
            return
        self.module = importlib.import_module('stemwise_engine.recording_analysis')
        self.runtime_identity = {'packages': {'torch': {'version': 'qualified-v1'}}}
        runtime = patch.object(self.module, 'collect_acoustic_provenance',
                               side_effect=lambda **kwargs: self.runtime_identity, create=True)
        runtime.start()
        self.addCleanup(runtime.stop)
        self.temporary = tempfile.TemporaryDirectory(dir=PROJECT / 'Test suite/work')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.pc = self.root / 'PC side'
        self.builder = self.pc / 'stemwise-playlist-builder'
        self.config = package_config(self.root)
        self.config.update(projectDir=str(self.builder), pcSideRootWindows=str(self.pc),
                           outputDir=str(self.pc / 'generated-packages'), tempDir=str(self.pc / 'temporary-analysis'),
                           futureRootWindows=str(self.builder/'future-songs'))
        self.write(self.builder/'config.json', self.config)
        self.audio = self.pc / 'qobuz-comparison/audio/qobuz/11/song.flac'
        self.audio.parent.mkdir(parents=True)
        time = np.arange(48000) / 48000
        self.samples = np.column_stack((.2*np.sin(time*2*np.pi*220), .1*np.sin(time*2*np.pi*440)))
        sf.write(self.audio, self.samples, 48000, subtype='PCM_24')
        self.write(self.audio.parent / 'download.json', {'qobuzTrackId':11,'file':self.audio.name,'bytes':self.audio.stat().st_size,'sha256':__import__('hashlib').sha256(self.audio.read_bytes()).hexdigest()})
        self.write(self.pc / 'qobuz-comparison/comparison-results/library-files.json',
                   {'flac': [{'trackId': 11, 'file': 'audio/qobuz/11/song.flac', 'title': 'Song'}]})
        self.prior = self.pc / 'stemwise-playlist-builder/future-songs/policy-state/stemwise-future-song-21/jobs/abcdefghijk/job.json'
        self.write(self.prior, {'videoId': 'abcdefghijk', 'state': 'DELIVERED'})
        self.observed = []

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_json(path, value)

    def worker(self, audio, duration, project, timeout):
        decoded, rate = sf.read(audio, dtype='float32', always_2d=True)
        native, native_rate = sf.read(self.audio, dtype='float32', always_2d=True)
        self.assertEqual((rate, native_rate, duration), (48000, 48000, 1000))
        np.testing.assert_array_equal(decoded, native)
        self.observed.append(audio)
        value = song_document('abcdefghijk')['analysis']
        value['waveform'] = build_waveform(audio, duration_ms=duration)
        value['toolRuns'] = [{'name': run['name'], 'status': 'complete', 'version': '1'} for run in value['toolRuns']]
        if 'execution' in self.runtime_identity:
            profile = json.dumps(self.runtime_identity['execution']['main'], sort_keys=True, separators=(',', ':'))
            for tool in value['toolRuns']:
                if tool['name'] in {'beat-this-full', 's-key'}:
                    tool['profile'] = profile
        return value

    def test_execution_changes_cannot_reuse_and_inspection_never_observes_a_gpu(self):
        import copy
        cpu = {'device':'cpu', 'torch':'2.7'}
        cuda = {'device':'cuda', 'torch':'2.7', 'name':'GPU A', 'capability':[8, 6],
                'cudaRuntime':'12.8', 'driver':'580', 'uuid':'GPU-1', 'cudnn':'9', 'totalMemoryBytes':123}
        states = [cpu, cuda, {**cuda, 'name':'GPU B', 'uuid':'GPU-2'}, {**cuda, 'cudaRuntime':'13'}, cpu]
        paths = []
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            for execution in states:
                self.runtime_identity['execution'] = {'main':execution}
                completed = self.module.analyze(self.config, 'qobuz:11')
                paths.append(completed['analysis'])
            self.assertEqual(len(self.observed), 4)
            self.assertEqual(paths[0], paths[-1])
            self.assertEqual(len(set(paths)), 4)
        current = copy.deepcopy(self.runtime_identity)
        def inspect_identity(**kwargs):
            self.assertFalse(kwargs['observe_execution'], 'status must not observe a numerical device')
            return {key:value for key,value in current.items() if key != 'execution'}
        with patch.object(self.module, 'collect_acoustic_provenance', side_effect=inspect_identity):
            inspected = self.module.status(self.config, 'qobuz:11')
            self.assertEqual(inspected['state'], 'ANALYSIS_COMPLETE')
            self.assertIn('execution', inspected['producerIdentity']['runtime'])
        wrong = json.loads((self.root/paths[0]).read_bytes())
        wrong['toolRuns'][0]['profile'] = json.dumps(cuda, sort_keys=True, separators=(',', ':'))
        producer = self.module.acoustic_producer_identity(self.config)
        with self.assertRaisesRegex(ValueError, 'execution device differs'):
            self.module.require_execution_binding(wrong, producer)

    def test_native_input_isolated_results_reuse_and_no_phone_delivery(self):
        old = self.prior.read_bytes()
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            first = self.module.analyze(self.config, 'qobuz:11')
            again = self.module.analyze(self.config, 'qobuz:11')
        self.assertEqual(first['state'], 'ANALYSIS_COMPLETE')
        self.assertTrue(again['reused'])
        self.assertEqual(len(self.observed), 1)
        self.assertFalse(first['phoneDeliveryAllowed'])
        self.assertFalse(first['lyricsTimed'])
        self.assertFalse(self.observed[0].exists())
        self.assertTrue(self.audio.is_file())
        self.assertEqual(self.prior.read_bytes(), old)
        payload = self.root / first['analysis']
        retained = payload
        raw = retained.read_bytes()
        job_path = self.root / first['job']
        committed = json.loads(job_path.read_bytes())
        producer = committed['producerIdentity']
        decoded = self.module.decode_analysis_audio(self.audio, self.root / 'matching-pcm', timeout_seconds=15)
        with patch.object(self.module, 'decode_analysis_audio', side_effect=AssertionError('retained cache decoded again')), \
                patch.object(self.module, 'run_analysis_worker', side_effect=AssertionError('retained cache reran inference')):
            current = self.module.status(self.config, 'qobuz:11')
            self.assertEqual(current['state'], 'ANALYSIS_COMPLETE')
            self.assertEqual(self.root / current['analysis'], retained)
            self.assertEqual(current['producerIdentity'], producer)
            reused = self.module.analyze(self.config, 'qobuz:11')
            self.assertTrue(reused['reused'])
            self.assertEqual(self.root / reused['analysis'], retained)
            matched = self.module.matching_analysis(self.config, decoded, 1000, producer)
            self.assertEqual(matched, json.loads(raw))
        after = json.loads(job_path.read_bytes())
        for field in ('formatVersion', 'engineVersion', 'input', 'producerIdentity', 'pcm', 'analysisSha256'):
            self.assertEqual(after[field], committed[field])
        self.assertEqual(retained.read_bytes(), raw)
        payload.write_bytes(b'corrupt digest payload')
        with self.assertRaisesRegex(ValueError, 'was changed'):
            self.module.status(self.config, 'qobuz:11')
        self.assertIsNone(self.module.matching_analysis(self.config, decoded, 1000, producer))
        payload.unlink()
        payload.with_name('analysis.json').write_bytes(raw)
        with self.assertRaises(FileNotFoundError):
            self.module.status(self.config, 'qobuz:11')
        self.assertIsNone(self.module.matching_analysis(self.config, decoded, 1000, producer))

    def test_bounded_partial_analysis_is_published_and_reused_with_skipped_reasons(self):
        from stemwise_engine.analysis_resources import analysis_resource_plan, analysis_resource_policy
        for duration in (600000, 600001, 7200000, 1000):
            with self.subTest(duration=duration):
                producer = self.module.acoustic_producer_identity(self.config)
                workspace, pc, cache, audio, identity = self.module.selected_input(self.config, 'qobuz:11')
                cache = cache / str(duration)
                identity = {**identity, 'durationMs': duration}
                wav = self.root / 'known-pcm.wav'
                sf.write(wav, self.samples, 48000, subtype='FLOAT')
                analysis = song_document('abcdefghijk')['analysis']
                analysis.update(status='partial', durationMs=duration)
                analysis['tuning'] = {'status': 'complete', 'frequencyHz': 440., 'centsFromA440': 0.}
                analysis['rhythm'] = {'status': 'unavailable', 'reason': 'fixture-component-unavailable'}
                analysis['waveform'] = {'status': 'unavailable', 'reason': 'fixture-no-waveform'}
                analysis['toolRuns'] = [{'name': run['name'], 'status': 'complete', 'version': 'fixture'}
                    if run['name'] == 'essentia' else {'name': run['name'], 'status': 'skipped',
                    'reason': 'fixture-component-unavailable'} for run in analysis['toolRuns']]
                memory = (analysis_resource_policy().minimumProcessReserveBytes + 1000000
                          if duration == 1000 else 64*1024**3)
                with patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=memory):
                    plan = analysis_resource_plan(duration)
                self.assertTrue(plan['streamingAdmitted'])
                self.assertEqual(plan['fullWindowAdmitted'], duration != 1000)
                self.assertEqual(plan['reason'], 'analysis-full-window-budget-exceeded'
                                 if duration == 1000 else None)
                with patch.object(self.module, 'selected_input', return_value=(workspace, pc, cache, audio, identity)), \
                        patch.object(self.module, 'probe_duration_ms', return_value=duration):
                    self.module.cache_completed_analysis(self.config, 'qobuz:11', wav, duration,
                                                        analysis, identity, producer)
                    current = self.module.status(self.config, 'qobuz:11')
                    self.assertEqual(current['state'], 'ANALYSIS_COMPLETE')
                    self.assertEqual(current['analysisStatus'], 'partial')
                    self.assertEqual(json.loads((self.root/current['analysis']).read_bytes()), analysis)
                    self.module.cache_completed_analysis(self.config, 'qobuz:11', wav, duration,
                                                        analysis, identity, producer)
        self.assertFalse((self.root / 'Ready to Import').exists())

    def test_decode_and_publication_do_not_reserve_the_model_resource(self):
        import stemwise_engine.pipeline as pipeline
        from stemwise_engine.common import heavy_worker_lock_path, process_lock_descriptor
        from types import SimpleNamespace
        lock = heavy_worker_lock_path(self.config)
        decode = self.module.decode_analysis_audio
        publish = self.module._write_completed_analysis
        phases = []
        def decoding(*args, **kwargs):
            self.assertIsNone(process_lock_descriptor(lock))
            phases.append('decode')
            return decode(*args, **kwargs)
        def model(command, **kwargs):
            self.assertIsNotNone(process_lock_descriptor(lock))
            phases.append('model')
            self.assertEqual(command[1:5], ['-B', '-m', 'stemwise_engine.analysis_worker', 'single'])
            analysis = self.worker(Path(command[5]), int(command[6]), self.builder, 60)
            return SimpleNamespace(returncode=0, stdout='STEMWISE_ANALYSIS_RESULT='+json.dumps(analysis))
        def publication(*args, **kwargs):
            self.assertIsNone(process_lock_descriptor(lock))
            phases.append('publish')
            return publish(*args, **kwargs)
        with patch.object(self.module, 'decode_analysis_audio', decoding), \
                patch.object(pipeline, 'run_process_tree', model), \
                patch.object(self.module, '_write_completed_analysis', publication):
            result = self.module.analyze(self.config, 'qobuz:11')
        self.assertEqual(phases, ['decode', 'model', 'publish'])
        self.assertEqual(result['state'], 'ANALYSIS_COMPLETE')

    def test_fresh_analysis_publishes_despite_a_damaged_optional_cause(self):
        self._assert_damaged_optional_cause_isolated(repair=False)

    def test_repair_publishes_despite_a_damaged_optional_cause(self):
        self._assert_damaged_optional_cause_isolated(repair=True)

    def _assert_damaged_optional_cause_isolated(self, *, repair):
        import stemwise_engine.pipeline as pipeline
        from stemwise_engine import private_diagnostics as diagnostics
        source, unrelated = self.audio.read_bytes(), self.prior.read_bytes()
        prior_path = None
        if repair:
            def partial(*args):
                value = self.worker(*args)
                value['status'] = 'partial'
                value['rhythm'] = {'status': 'unavailable', 'reason': 'fixture-beat-failure'}
                value['toolRuns'][0] = {'name': 'beat-this-full', 'status': 'failed', 'version': '1', 'reason': 'fixture-beat-failure'}
                value['toolRuns'][1] = {'name': 'stemwise-rhythm-normalizer', 'status': 'skipped', 'reason': 'No beats'}
                return value
            with patch.object(self.module, 'run_analysis_worker', partial):
                before = self.module.analyze(self.config, 'qobuz:11')
            prior_path = self.root/before['analysis']
            prior_bytes = prior_path.read_bytes()
        damaged = []
        def model(command, **kwargs):
            self.assertEqual(command[1:5], ['-B', '-m', 'stemwise_engine.analysis_worker', 'single'])
            self.assertEqual('--repair-from' in command, repair)
            if repair:
                self.assertEqual(Path(command[command.index('--repair-from')+1]), prior_path)
            value = self.worker(Path(command[5]), int(command[6]), self.builder, 60)
            owner, workspace, _ = diagnostics._ANALYSIS_DIAGNOSTICS.get()
            self.assertEqual(workspace, self.root)
            owner.mkdir(parents=True, exist_ok=True)
            path, _ = diagnostics.retain(owner/'acoustic.failure.private.txt', b'Optional acoustic cause')
            path.write_bytes(b'damaged optional cause')
            damaged.append(path)
            value['_privateDiagnostics'] = [
                {'errorType': 'RuntimeError', 'body': 'Optional acoustic cause'},
                {'errorType': 'RuntimeError', 'body': 'Independent retained cause'}]
            return SimpleNamespace(returncode=0, stdout='STEMWISE_ANALYSIS_RESULT='+json.dumps(value))
        self.assertIs(self.module.run_analysis_worker, pipeline.run_analysis_worker)
        with patch.object(pipeline, 'run_process_tree', side_effect=model) as subprocess_boundary:
            result = self.module.analyze(self.config, 'qobuz:11', repair=repair)
        self.assertEqual(subprocess_boundary.call_count, 1)
        self.assertEqual(result['state'], 'ANALYSIS_COMPLETE')
        self.assertEqual(result['latestRun']['status'], 'complete')
        self.assertEqual(result['latestRun']['diagnosticError'], 'ValueError')
        retained = result['latestRun']['privateDiagnostics']
        self.assertEqual(len(retained), 1)
        self.assertEqual((self.root/retained[0]['file']).read_bytes(), b'Independent retained cause')
        self.assertEqual(damaged[0].read_bytes(), b'damaged optional cause')
        published = self.root/result['analysis']
        analysis = json.loads(published.read_bytes())
        self.assertNotIn('_privateDiagnostics', analysis)
        self.assertEqual(self.module.status(self.config, 'qobuz:11')['analysis'], result['analysis'])
        self.assertEqual(self.audio.read_bytes(), source)
        self.assertEqual(self.prior.read_bytes(), unrelated)
        if repair:
            self.assertNotEqual(published, prior_path)
            self.assertEqual(prior_path.read_bytes(), prior_bytes)
        published.write_bytes(b'damaged required analysis')
        with self.assertRaisesRegex(ValueError, 'was changed'):
            self.module.status(self.config, 'qobuz:11')

    def test_editorial_and_analysis_cannot_compute_the_same_cache_concurrently(self):
        from stemwise_engine.common import exclusive_process_lock, ProcessLockBusyError
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.pipeline import StemwisePipeline
        _, _, cache, audio, identity = self.module.selected_input(self.config, 'qobuz:11')
        decoded = self.module.decode_analysis_audio(audio, self.root / 'decoded', timeout_seconds=15)
        consumer = StemwisePipeline.__new__(StemwisePipeline)
        consumer.config = {**self.config, 'localRecordingKey': 'qobuz:11'}
        consumer.workspace_root = self.root
        from stemwise_engine.recording_source import RecordingSelection
        consumer.source = SimpleNamespace(recording=RecordingSelection(self.root, self.pc, audio, identity, ['abcdefghijk'], {}))
        with exclusive_process_lock(cache / 'workflow.lock'), \
                patch.object(StemwisePipeline, '_produce_analysis', side_effect=AssertionError('Duplicate work must not start')):
            with self.assertRaises(ProcessLockBusyError):
                consumer._analysis(decoded, 1000)
        self.assertEqual(self.module.status(self.config, 'qobuz:11')['state'], 'NOT_STARTED')

    def duplicate_input(self):
        other = self.audio.parents[1] / '22/song.flac'
        other.parent.mkdir()
        shutil.copyfile(self.audio, other)
        self.write(other.parent / 'download.json', {'qobuzTrackId':22,'file':other.name,'bytes':other.stat().st_size,'sha256':self.module.sha256_file(other)})
        self.write(self.pc / 'qobuz-comparison/comparison-results/library-files.json', {'flac': [
            {'trackId': 11, 'file': 'audio/qobuz/11/song.flac', 'title': 'Song'},
            {'trackId': 22, 'file': 'audio/qobuz/22/song.flac', 'title': 'Other entry'}]})
        return other

    def test_identical_decoded_recordings_share_analysis_without_sharing_job_identity(self):
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            first = self.module.analyze(self.config, 'qobuz:11')
        original = (self.root / first['job']).read_bytes()
        other = self.duplicate_input()
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(self.audio),
                        '-map_metadata', '-1', '-c:a', 'flac', '-compression_level', '0',
                        '-fflags', '+bitexact', '-flags:a', '+bitexact', str(other)],
                       check=True, capture_output=True, timeout=15)
        self.write(other.parent/'download.json',{'qobuzTrackId':22,'file':other.name,'bytes':other.stat().st_size,'sha256':self.module.sha256_file(other)})
        self.assertNotEqual(self.module.sha256_file(self.audio), self.module.sha256_file(other))
        with patch.object(self.module, 'run_analysis_worker', side_effect=AssertionError('Identical PCM must reuse analysis')):
            second = self.module.analyze(self.config, 'qobuz:22')
        self.assertTrue(second['reused'])
        self.assertEqual((self.root / second['analysis']).read_bytes(), (self.root / first['analysis']).read_bytes())
        self.assertEqual(json.loads((self.root / second['job']).read_bytes())['input']['recordingKey'], 'qobuz:22')
        self.assertEqual((self.root / first['job']).read_bytes(), original)
        self.assertFalse(second['phoneDeliveryAllowed'])
        self.assertFalse(second['lyricsTimed'])

    def test_shared_analysis_rejects_changed_audio(self):
        self.check_shared_cache_miss('audio')

    def test_shared_analysis_rejects_changed_runtime(self):
        self.check_shared_cache_miss('runtime')

    def test_shared_analysis_rejects_a_damaged_donor(self):
        self.check_shared_cache_miss('donor')

    def test_shared_analysis_rejects_digest_consistent_malformed_donor(self):
        self.runtime_identity['execution'] = {'main': {'device':'cpu', 'torch':'fixture'}}
        self.check_shared_cache_miss('donor-shape')

    def test_shared_analysis_rejects_a_malformed_donor_key_without_stopping(self):
        self.check_shared_cache_miss('donor-key')

    def test_shared_analysis_rechecks_bytes_changed_after_donor_admission(self):
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            first = self.module.analyze(self.config, 'qobuz:11')
        self.duplicate_input()
        status = self.module.status
        changes = []
        def change_after_admission(config, key, **kwargs):
            result = status(config, key, **kwargs)
            if key == 'qobuz:11' and result['state'] == 'ANALYSIS_COMPLETE':
                path = self.root / result['analysis']
                changed = json.loads(path.read_bytes())
                changed['toolRuns'][0]['version'] = 'unadmitted-version'
                self.write(path, changed)
                changes.append(path)
            return result
        with patch.object(self.module, 'status', side_effect=change_after_admission), \
                patch.object(self.module, 'run_analysis_worker', side_effect=self.worker) as worker:
            second = self.module.analyze(self.config, 'qobuz:22')
        self.assertEqual(len(changes), 1)
        self.assertFalse(second['reused'])
        self.assertEqual(worker.call_count, 1)
        self.assertEqual(json.loads((self.root / second['analysis']).read_bytes())['toolRuns'][0]['version'], '1')

    def check_shared_cache_miss(self, change):
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            first = self.module.analyze(self.config, 'qobuz:11')
        other = self.duplicate_input()
        if change == 'audio':
            sf.write(other, self.samples*.5, 48000, subtype='PCM_24')
            self.write(other.parent/'download.json',{'qobuzTrackId':22,'file':other.name,'bytes':other.stat().st_size,'sha256':self.module.sha256_file(other)})
        elif change == 'runtime':
            self.runtime_identity = {'packages': {'torch': {'version': 'changed-runtime'}}}
        elif change == 'donor':
            (self.root / first['analysis']).write_text('{}')
        elif change == 'donor-shape':
            import hashlib
            path = self.root/first['job']
            job = json.loads(path.read_bytes())
            malformed = b'{}'
            digest = hashlib.sha256(malformed).hexdigest()
            (path.parent/f'analysis-{digest}.json').write_bytes(malformed)
            job['analysisSha256'] = digest
            self.write(path, job)
        else:
            path = self.root / first['job']
            job = json.loads(path.read_bytes())
            job['input']['recordingKey'] = None
            self.write(path, job)
        self.audio = other
        with patch.object(self.module, 'run_analysis_worker', side_effect=self.worker) as worker:
            second = self.module.analyze(self.config, 'qobuz:22')
        self.assertFalse(second['reused'])
        self.assertEqual(worker.call_count, 1)
        self.assertEqual(second['state'], 'ANALYSIS_COMPLETE')

    def test_native_pcm_recovery_survives_process_death_before_and_during_decode(self):
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.recording_source import selection
        from stemwise_engine.common import sha256_file
        import stemwise_engine.youtube as native_pipeline
        from types import SimpleNamespace
        reference = self.module.decode_analysis_audio(self.audio, self.root / 'reference-pcm', timeout_seconds=15)
        config = {**self.config, 'localRecordingKey': 'qobuz:11'}
        library = self.pc / 'qobuz-comparison'
        inventory = json.loads((library / 'comparison-results/library-files.json').read_bytes())
        inventory['playlist'] = [{'videoId': 'AAAAAAAAAAA', 'source': 'qobuz', 'trackId': 11}]
        self.write(library / 'comparison-results/library-files.json', inventory)
        self.write(library / 'catalogue.json', {'rows': [{'position': 1, 'videoId': 'AAAAAAAAAAA',
            'candidates': [{'id': 11, 'title': 'Song', 'performer': {'name': 'Artist'}}]}]})
        selected = selection(config, 'qobuz:11')
        state = {'videoId': 'AAAAAAAAAAA', 'status': 'complete', 'recordingInput': selected.identity,
                 'originalAudio': {'retained': True, 'sha256': sha256_file(reference),
                                   'byteCount': reference.stat().st_size, 'durationMs': 1000}}
        script = "\nimport json, os, sys\nfrom pathlib import Path\nsys.path.insert(0, sys.argv.pop(1))\nimport stemwise_engine.youtube as native_pipeline\nfrom types import SimpleNamespace\nfrom stemwise_engine.pipeline import StemwisePipeline\nfrom stemwise_engine.recording_source import selection\nconfig, state, cut = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]\npipeline = StemwisePipeline.__new__(StemwisePipeline)\npipeline.config = config\npipeline.workspace_root = Path(config['projectDir']).parent.parent\npipeline.temp_root = Path(config['tempDir'])\npipeline.source = SimpleNamespace(recording=selection(config, config['localRecordingKey']))\ndef interrupted(source, temporary, **kwargs):\n    assert temporary.name == '.pcm-recovery-AAAAAAAAAAA'\n    assert not (pipeline.temp_root / 'AAAAAAAAAAA').exists()\n    if cut == 'during':\n        (temporary / 'analysis.wav').write_bytes(b'partial decoder output')\n    os._exit(73)\nnative_pipeline.decode_analysis_audio = interrupted\npipeline._require_retained_analysis_audio('AAAAAAAAAAA', state)\n"
        for cut in ('before', 'during'):
            with self.subTest(cut=cut):
                pipeline = StemwisePipeline.__new__(StemwisePipeline)
                pipeline.config, pipeline.workspace_root = config, self.root
                pipeline.temp_root = self.root / f'pcm-recovery-{cut}'
                pipeline.source = SimpleNamespace(recording=selected)
                child_config = {**config, 'tempDir': str(pipeline.temp_root)}
                completed = subprocess.run([sys.executable, '-B', '-c', script, str(Path(self.module.__file__).parent), json.dumps(child_config),
                    json.dumps(state), cut], capture_output=True, text=True, timeout=30)
                self.assertEqual(completed.returncode, 73, completed.stderr)
                final = pipeline.temp_root / 'AAAAAAAAAAA'
                staging = pipeline.temp_root / '.pcm-recovery-AAAAAAAAAAA'
                self.assertFalse(final.exists())
                self.assertTrue(staging.is_dir())
                if cut == 'during':
                    self.assertEqual((staging / 'analysis.wav').read_bytes(), b'partial decoder output')
                with patch.object(native_pipeline, 'decode_analysis_audio', wraps=native_pipeline.decode_analysis_audio) as decode, \
                        patch('stemwise_engine.pipeline.StemwisePipeline._analysis', side_effect=AssertionError('PCM recovery reran inference')):
                    restored = pipeline._require_retained_analysis_audio('AAAAAAAAAAA', state)
                    self.assertEqual(sha256_file(restored), state['originalAudio']['sha256'])
                    self.assertEqual(restored.read_bytes(), reference.read_bytes())
                    self.assertFalse(staging.exists())
                    self.assertEqual(pipeline._require_retained_analysis_audio('AAAAAAAAAAA', state), restored)
                    self.assertEqual(decode.call_count, 1)
                    changed = bytearray(restored.read_bytes())
                    changed[-1] ^= 1
                    restored.write_bytes(changed)
                    repaired = pipeline._require_retained_analysis_audio('AAAAAAAAAAA', state)
                    self.assertEqual(repaired.read_bytes(), reference.read_bytes())
                    self.assertEqual(decode.call_count, 2)
                    repaired.unlink()
                    self.assertEqual(pipeline._require_retained_analysis_audio('AAAAAAAAAAA', state).read_bytes(), reference.read_bytes())
                    self.assertEqual(decode.call_count, 3)
                    repaired.write_bytes(changed)
                    def changed_decode(source, staging, **kwargs):
                        (staging / 'analysis.wav').write_bytes(b'changed decode')
                    with patch.object(native_pipeline, 'decode_analysis_audio', side_effect=changed_decode):
                        with self.assertRaisesRegex(ValueError, 'differs from its completed checkpoint'):
                            pipeline._require_retained_analysis_audio('AAAAAAAAAAA', state)
                    self.assertEqual(repaired.read_bytes(), changed)
                    self.assertFalse(staging.exists())

    def test_native_editorial_pipeline_uses_the_same_shared_analysis_cache(self):
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.pipeline import StemwisePipeline
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            first = self.module.analyze(self.config, 'qobuz:11')
        self.duplicate_input()
        _, _, _, audio, identity = self.module.selected_input(self.config, 'qobuz:22')
        decoded = self.module.decode_analysis_audio(audio, self.root / 'decoded', timeout_seconds=15)
        pipeline = StemwisePipeline.__new__(StemwisePipeline)
        pipeline.config = {**self.config, 'localRecordingKey': 'qobuz:22'}
        pipeline.workspace_root = self.root
        from stemwise_engine.recording_source import RecordingSelection
        pipeline.source = SimpleNamespace(recording=RecordingSelection(self.root, self.pc, audio, identity, ['abcdefghijk'], {}))
        with patch.object(StemwisePipeline, '_produce_analysis', side_effect=AssertionError('Editorial duplicate must not rerun models')):
            analysis = pipeline._analysis(decoded, 1000)
        self.assertEqual(analysis, json.loads((self.root / first['analysis']).read_bytes()))
        self.assertEqual(self.module.status(self.config, 'qobuz:22')['state'], 'ANALYSIS_COMPLETE')
        self.assertEqual(pipeline._analysis_reuse['cacheReason'], 'independent-pcm-cache')
        self.assertTrue(pipeline._analysis_reuse['cacheReused'])

    def test_source_change_uses_new_namespace_without_overwriting_old_result(self):
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            old = self.module.analyze(self.config, 'qobuz:11')
        path = self.root / old['analysis']
        raw = path.read_bytes()
        sf.write(self.audio, self.samples*.5, 48000, subtype='PCM_24')
        self.write(self.audio.parent/'download.json',{'qobuzTrackId':11,'file':self.audio.name,'bytes':self.audio.stat().st_size,'sha256':self.module.sha256_file(self.audio)})
        status = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(status['state'], 'NOT_STARTED')
        self.assertEqual(path.read_bytes(), raw)

    def test_changed_acoustic_runtime_cannot_reuse_or_relabel_previous_cache(self):
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            old = self.module.analyze(self.config, 'qobuz:11')
        path = self.root / old['analysis']
        raw = path.read_bytes()
        self.runtime_identity = {'packages': {'torch': {'version': 'qualified-v2'}}}
        self.assertEqual(self.module.status(self.config, 'qobuz:11')['state'], 'NOT_STARTED')
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            new = self.module.analyze(self.config, 'qobuz:11')
        self.assertEqual(len(self.observed), 2)
        self.assertNotEqual(new['analysis'], old['analysis'])
        self.assertEqual(path.read_bytes(), raw)

    def other_producer_cache(self):
        import copy
        producer = copy.deepcopy(self.module.acoustic_producer_identity(self.config))
        producer['sources']['_analysis_worker'] = 'a'*64
        with patch.object(self.module, 'acoustic_producer_identity', return_value=producer), patch.object(
                self.module, 'run_analysis_worker', self.worker):
            return self.module.analyze(self.config, 'qobuz:11')







    def test_changed_worker_cannot_admit_another_producer(self):
        self.other_producer_cache()
        import copy
        current = copy.deepcopy(self.module.acoustic_producer_identity(self.config))
        current['sources']['_analysis_worker'] = '0' * 64
        with patch.object(self.module, 'acoustic_producer_identity', return_value=current):
            self.assertEqual(self.module.status(self.config, 'qobuz:11')['state'], 'NOT_STARTED')

    def test_unknown_extracted_or_batch_math_cannot_borrow_completed_cache(self):
        self.other_producer_cache()
        import copy
        for helper in ('_gpu_analysis', '_analysis_document', '_analysis_worker_many'):
            with self.subTest(helper=helper):
                current = copy.deepcopy(self.module.acoustic_producer_identity(self.config))
                current['sources'][helper] = '0' * 64
                with patch.object(self.module, 'acoustic_producer_identity', return_value=current):
                    self.assertEqual('NOT_STARTED', self.module.status(self.config, 'qobuz:11')['state'])

    def test_changed_analyzers_or_runtime_cannot_admit_another_producer(self):
        self.other_producer_cache()
        import copy
        for component in ('analyzers', 'runtime'):
            with self.subTest(component=component):
                current = copy.deepcopy(self.module.acoustic_producer_identity(self.config))
                if component == 'analyzers':
                    current['sources']['analyzers.py'] = '0' * 64
                else:
                    current['runtime'] = {'changed': True}
                with patch.object(self.module, 'acoustic_producer_identity', return_value=current):
                    self.assertEqual(self.module.status(self.config, 'qobuz:11')['state'], 'NOT_STARTED')

    def test_failed_other_producer_run_is_not_selected_for_new_work(self):
        old = self.other_producer_cache()
        path = self.root / old['job']
        job = json.loads(path.read_bytes())
        job['state'] = 'ANALYSIS_FAILED'
        self.write(path, job)
        result = self.module.status(self.config, 'qobuz:11')
        self.assertEqual(result['state'], 'NOT_STARTED')
        self.assertNotEqual(result['producerIdentity'], old['producerIdentity'])


    def test_source_identity_detects_same_stat_acoustic_edit_and_ignores_unrelated_pipeline_edit(self):
        source = self.root / 'identity-source'
        source.mkdir()
        canonical = Path(self.module.__file__).parent
        import stemwise_engine.provenance as provenance
        for name in (*provenance.ACOUSTIC_SOURCE_FILES, 'pipeline.py', 'youtube.py', 'common.py'):
            shutil.copy2(canonical / name, source / name)
        with patch.object(provenance, '__file__', str(source / 'provenance.py')):
            original = self.module.acoustic_producer_identity(self.config)
            path = source / 'analysis_numerics.py'
            stamp = path.stat()
            raw = path.read_bytes()
            changed = raw.replace(b'max_workers=2', b'max_workers=3', 1)
            self.assertNotEqual(raw, changed)
            path.write_bytes(changed)
            os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
            self.assertEqual(path.stat().st_size, stamp.st_size)
            self.assertEqual(path.stat().st_mtime_ns, stamp.st_mtime_ns)
            self.assertNotEqual(self.module.acoustic_producer_identity(self.config), original)
            path.write_bytes(raw)
            pipeline = source / 'pipeline.py'
            pipeline.write_bytes(pipeline.read_bytes() + b'\n# unrelated metadata publication change\n')
            self.assertEqual(self.module.acoustic_producer_identity(self.config), original)
            self.assertEqual(self.module.acoustic_producer_identity({**self.config, 'analysisTimeoutMinutes': 999}), original)

    def test_rejected_or_not_selected_file_never_runs(self):
        from stemwise_media.save_download import validate_retained_download
        receipt = self.audio.parent / 'download.json'
        accepted = json.loads(receipt.read_bytes())
        original = self.audio.read_bytes()
        with patch.object(self.module, 'decode_analysis_audio',
                side_effect=AssertionError('Rejected input reached decoding')) as decode, \
                patch.object(self.module, 'run_analysis_worker',
                    side_effect=AssertionError('Rejected input reached model work')) as worker:
            self.write(receipt, {**accepted, 'status': 'owner_rejected'})
            with self.assertRaisesRegex(ValueError, 'owner rejected'):
                self.module.analyze(self.config, 'qobuz:11')
            self.write(receipt, accepted)
            unselected = self.audio.parent.parent / '22' / self.audio.name
            unselected.parent.mkdir()
            shutil.copyfile(self.audio, unselected)
            self.write(unselected.parent / 'download.json', {**accepted, 'qobuzTrackId': 22})
            self.assertEqual(validate_retained_download(unselected.parent, 22), unselected)
            with self.assertRaisesRegex(ValueError, 'wanted retained FLAC'):
                self.module.analyze(self.config, 'qobuz:22')
            decode.assert_not_called()
            worker.assert_not_called()
        self.assertEqual(self.audio.read_bytes(), original)
        self.assertEqual(unselected.read_bytes(), original)
        self.assertFalse((self.pc / 'generated-packages').exists())

    def test_failed_model_cannot_be_recorded_as_complete_and_can_retry(self):
        def failure(*args):
            result = self.worker(*args)
            result['toolRuns'][0] = {'name': 'beat-this-full', 'status': 'failed', 'reason': 'Test failure'}
            return result
        with patch.object(self.module, 'run_analysis_worker', failure):
            with self.assertRaises(ValueError):
                self.module.analyze(self.config, 'qobuz:11')
        self.assertEqual(self.module.status(self.config, 'qobuz:11')['state'], 'ANALYSIS_FAILED')
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            self.assertEqual(self.module.analyze(self.config, 'qobuz:11')['state'], 'ANALYSIS_COMPLETE')

    def test_corrupted_completed_result_is_rejected_instead_of_reused(self):
        with patch.object(self.module, 'run_analysis_worker', self.worker):
            result = self.module.analyze(self.config, 'qobuz:11')
        (self.root / result['analysis']).write_text('{}')
        with self.assertRaises(ValueError):
            self.module.status(self.config, 'qobuz:11')
        with patch.object(self.module, 'run_analysis_worker', side_effect=AssertionError('No implicit rebuild')):
            with self.assertRaises(ValueError):
                self.module.analyze(self.config, 'qobuz:11')

    def test_canonical_launcher_accepts_recording_actions(self):
        with tempfile.TemporaryDirectory(dir=PROJECT/'Test suite/work') as temporary:
            targets = [PROJECT]
            for name in ('checkout alpha', 'different-checkout-beta'):
                checkout = Path(temporary)/name
                target = checkout/'PC side/STEMWISE_AGENT.ps1'
                target.parent.mkdir(parents=True)
                from test_agent_launcher import copy_launcher
                copy_launcher(target)
                targets.append(checkout)
            for checkout in targets:
                launcher = subprocess.check_output(['wslpath', '-w', str(checkout/'PC side/STEMWISE_AGENT.ps1')], text=True).strip().replace("'", "''")
                helper = subprocess.check_output(['wslpath','-w',str(checkout/'PC side/stemwise-playlist-builder/command-spec.ps1')],text=True).strip().replace("'","''")
                command = (f"$c=Get-Command '{launcher}'; . '{helper}'; $a=Get-StemWiseCommandSpecification; "
                           "if(-not $a.Contains('AnalyzeRecording') -or -not $a.Contains('RecordingStatus') "
                           "-or -not $c.Parameters.ContainsKey('RecordingKey')) {exit 1}")
                result = subprocess.run(['/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe',
                                         '-NoProfile', '-NonInteractive', '-Command', command],
                                         capture_output=True, timeout=30)
                self.assertEqual(result.returncode, 0, 'Canonical actions unavailable in '+str(checkout)+': '+result.stderr.decode(errors='replace')[-1000:])

    def test_detached_recording_launch_preserves_key_and_returns_correct_status_command(self):
        self.builder.mkdir(parents=True, exist_ok=True)
        launcher = self.pc / 'STEMWISE_AGENT.ps1'
        from test_agent_launcher import copy_launcher
        copy_launcher(launcher)
        for name in ['config.json', 'src/recording_analysis.py', 'runtime-bootstrap.ps1',
                     '.tools/bootstrap/bin/python', '.venv/bin/python', '.venv-allin1/bin/python']:
            self.write(self.builder / name, {})
        (self.builder / 'recording-analysis.ps1').write_text(
            "param([string]$Command,[string]$RecordingKey)\n"
            "$o=@{command=$Command;key=$RecordingKey}|ConvertTo-Json -Compress\n"
            "[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'observed.json'),$o)\nexit 0\n")
        windows = subprocess.check_output(['wslpath', '-w', str(launcher)], text=True).strip()
        for action, command in (('AnalyzeRecording', 'analyze'), ('RepairRecording', 'repair')):
            launched = subprocess.run(['/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe',
                                      '-NoProfile', '-NonInteractive', '-File', windows, '-Action',
                                      action, '-RecordingKey', 'qobuz:11'],
                                      capture_output=True, text=True, timeout=20)
            self.assertEqual(launched.returncode, 0, launched.stderr[-1000:])
            receipt = json.loads(launched.stdout)
            self.assertIsNone(receipt['videoId'])
            self.assertEqual(receipt['recordingKey'], 'qobuz:11')
            self.assertIn("-Action RecordingStatus -RecordingKey 'qobuz:11'", receipt['statusCommand'])
            completion = Path(subprocess.check_output(['wslpath', '-u', receipt['completion']], text=True).strip())
            deadline = time.monotonic() + 10
            while not completion.exists() and time.monotonic() < deadline:
                time.sleep(.05)
            self.assertTrue(completion.exists())
            self.assertEqual(json.loads(completion.read_text(encoding='utf-8-sig'))['exitCode'], 0)
            self.assertEqual(json.loads((self.builder / 'observed.json').read_text()),
                             {'command': command, 'key': 'qobuz:11'})


if __name__ == '__main__':
    unittest.main()
