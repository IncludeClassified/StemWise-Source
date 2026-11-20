"""Batching must keep song identity, GPU serialization and independent failure."""
import sys
import io
import json
import os
import subprocess
import tempfile
import threading
import time
import unittest
import wave
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.pipeline as pipeline
import stemwise_engine.analysis_numerics as analysis_numerics
from stemwise_engine.common import run_process_tree, exclusive_process_lock, heavy_worker_lock_path


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class AcousticBatchTests(unittest.TestCase):
    def test_parallel_memory_recheck_retirement_stops_later_songs_and_retires_structure(self):
        from types import ModuleType, SimpleNamespace
        from unittest.mock import Mock
        import numpy as np
        import soundfile as sf
        from stemwise_engine import analyzers, analysis_resources as resources
        from stemwise_engine.common import WorkerRetirementError
        es, essentia = ModuleType('essentia.standard'), ModuleType('essentia')
        essentia.standard = es
        for algorithm in ('Windowing', 'Spectrum', 'SpectralPeaks', 'TuningFrequency'):
            setattr(es, algorithm, Mock())
        fatal, active, retired = WorkerRetirementError('unretired window decoder'), threading.Event(), threading.Event()
        calls, published = [], []
        def ffmpeg(command, **options):
            calls.append(command)
            self.assertTrue(active.wait(5), 'structure worker never became active')
            raise fatal
        def gpu(*args):
            self.assertTrue(retired.wait(5), 'fatal CPU stage did not cancel structure')
            return ({}, {}, {}, {}, {})
        def structure(inputs, *, cancelled, **options):
            active.set()
            deadline = time.monotonic()+5
            while not cancelled():
                if time.monotonic() >= deadline:
                    self.fail('structure cancellation was not requested')
                time.sleep(.01)
            retired.set()
            raise InterruptedError('structure retired after cancellation')
        # Both inputs pass parallel admission; Essentia's later recheck sees pressure.
        memory = iter([64*1024**3, 64*1024**3])
        with patch.dict(sys.modules, {'essentia': essentia, 'essentia.standard': es}), \
                patch.object(sf, 'info', return_value=SimpleNamespace(frames=48000*600, samplerate=48000)), \
                patch.object(resources, 'available_memory_bytes', side_effect=lambda: next(memory, 1024**3)), \
                patch.object(analyzers, 'run_process_tree', side_effect=ffmpeg), \
                patch.object(analysis_numerics, '_gpu_analysis', side_effect=gpu) as gpu_stage, \
                patch.object(analysis_numerics, 'analyze_allin1_many', side_effect=structure), \
                redirect_stderr(io.StringIO()):
            with self.assertRaises(WorkerRetirementError) as raised:
                analysis_numerics._analysis_worker_many([(Path('one.wav'), 600000), (Path('two.wav'), 600000)],
                    ROOT, 10, on_completed=lambda *row: published.append(row))
        self.assertIs(raised.exception, fatal)
        self.assertTrue(retired.is_set())
        self.assertEqual(gpu_stage.call_count, 1)
        self.assertEqual(len(calls), 1)
        self.assertIn('one.wav', calls[0])
        self.assertEqual(published, [])

    def test_hard_serial_timeout_distinguishes_started_failure_from_pending_input(self):
        from stemwise_engine.analysis_queue import AnalysisInputNotStarted
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            project = Path(name)
            failure = subprocess.TimeoutExpired('worker', 2, output='STEMWISE_ACOUSTIC_SERIAL_STARTED=0\n')
            with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(project)})), \
                    patch.object(pipeline, 'run_process_tree', side_effect=failure):
                rows = pipeline._run_analysis_batch([(project/'one.wav', 1000), (project/'two.wav', 1000)], project, 2)
            self.assertEqual(rows[0].worker_reason, 'worker-timeout')
            self.assertIsInstance(rows[1], AnalysisInputNotStarted)

    def test_serial_batch_failure_does_not_retire_an_unstarted_peer(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled
        inputs = [(Path('one.wav'), 1000), (Path('two.wav'), 2000)]
        for failed in (0, 1, None):
            with self.subTest(failed=failed):
                visited, published = [], []
                def analyze(audio, duration, *_):
                    visited.append(audio)
                    if failed is not None and audio == inputs[failed][0]:
                        raise TimeoutError('one song deadline')
                    return {'durationMs': duration}
                with patch.object(analysis_numerics, 'analysis_resource_plan', return_value={'parallel': False}), \
                        patch.object(analysis_numerics, '_analysis_worker', side_effect=analyze):
                    rows = analysis_numerics._analysis_worker_many(inputs, ROOT, 10,
                        on_completed=lambda index, row: published.append((index, row)))
                self.assertEqual(visited, [row[0] for row in inputs])
                self.assertEqual([index for index, _ in published], [0, 1])
                for index, row in enumerate(rows):
                    self.assertEqual('error' in row, index == failed)
        for failure in (ProviderCancelled('cancelled'), WorkerRetirementError('retirement'), KeyboardInterrupt()):
            with self.subTest(failure=type(failure).__name__), \
                    patch.object(analysis_numerics, 'analysis_resource_plan', return_value={'parallel': False}), \
                    patch.object(analysis_numerics, '_analysis_worker', side_effect=failure) as worker:
                with self.assertRaises(type(failure)):
                    analysis_numerics._analysis_worker_many(inputs, ROOT, 10)
                self.assertEqual(worker.call_count, 1)

    def numerical_fixture_package(self, project):
        package = project / 'fixture-imports/stemwise_engine'
        package.mkdir(parents=True)
        source = ROOT / 'PC side/stemwise-playlist-builder/src'
        (package / '__init__.py').write_text(f'__path__.append({str(source)!r})\n', encoding='utf-8')
        return package

    def test_adapter_fallback_causes_survive_the_normal_worker_transport_privately(self):
        import stemwise_engine.analyzers as analyzers
        import numpy as np
        import soundfile as sf
        from test_packages import song_document
        from stemwise_engine.contract import _validate_analysis
        from stemwise_engine.private_diagnostics import analysis_diagnostics
        fixture = song_document('abcdefghijk')['analysis']
        fixture['key'] = {'status': 'uncertain', 'tonic': 'C', 'mode': 'major', 'reason': 'Uncalibrated fixture class'}
        fixture['toolRuns'][2] = {'name': 's-key', 'status': 'complete', 'version': 'fixture'}
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            audio = root/'source.wav'
            signal = (.1*np.sin(np.arange(48000)*.03)).astype('float32')
            sf.write(audio, np.column_stack((signal, signal)), 48000, subtype='FLOAT')
            python = root/'isolated-python'
            python.touch()
            def beats(_):
                raise RuntimeError('distinct private beat model root')
            def worker(command, **kwargs):
                result = analysis_numerics._analysis_worker(audio, 1000, root, 5)
                return subprocess.CompletedProcess(command, 0, 'STEMWISE_ANALYSIS_RESULT='+json.dumps(result), '')
            with patch.object(analyzers, '_BEAT_THIS', beats), \
                    patch.object(analyzers, '_allin1_python', return_value=python), \
                    patch.object(analyzers, 'run_process_tree', return_value=subprocess.CompletedProcess(
                        ['model'], 1, '', 'distinct private child root\n'+'x'*800)), \
                    patch.object(analysis_numerics, 'analyze_skey', return_value=(fixture['key'], fixture['toolRuns'][2])), \
                    patch.object(analysis_numerics, 'analyze_essentia', return_value=(fixture['tuning'], fixture['loudness'], fixture['toolRuns'][4])), \
                    patch.object(pipeline, 'run_process_tree', side_effect=worker), \
                    analysis_diagnostics(root/'Engine telemetry/youtube-abcdefghijk/causes', workspace_root=root) as diagnostics:
                analysis = pipeline.run_analysis_worker(audio, 1000, root, 5)
            _validate_analysis(analysis, '$.analysis')
            self.assertEqual(analysis['key'], fixture['key'])
            self.assertEqual(analysis['status'], 'partial')
            self.assertNotIn('_privateDiagnostics', analysis)
            self.assertEqual(len(diagnostics['privateDiagnostics']), 2)
            bodies = []
            for ref in diagnostics['privateDiagnostics']:
                payload = (root/ref['file']).read_bytes()
                self.assertLessEqual(len(payload), 12000)
                self.assertEqual(__import__('hashlib').sha256(payload).hexdigest(), ref['sha256'])
                bodies.append(payload.decode('utf-8'))
            self.assertTrue(any('distinct private beat model root' in body for body in bodies))
            self.assertTrue(any('distinct private child root' in body for body in bodies))
            self.assertNotIn('distinct private', json.dumps(analysis))

    def test_real_batch_stage_failures_keep_each_cause_and_completed_peer(self):
        from test_packages import song_document
        fixture = song_document('abcdefghijk')['analysis']
        gpu_result = (fixture['rhythm'], fixture['toolRuns'][1], fixture['key'], fixture['toolRuns'][2], fixture['toolRuns'][0])
        cpu_result = (fixture['tuning'], fixture['loudness'], fixture['toolRuns'][4])
        structure_result = (fixture['structure'], None, fixture['toolRuns'][3])
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            project = Path(temporary)
            paths = [project / name for name in ('complete.wav', 'gpu.wav', 'cpu.wav')]
            for path in paths:
                with wave.open(str(path), 'wb') as output:
                    output.setparams((2, 2, 48000, 0, 'NONE', 'not compressed'))
                    output.writeframes(b'\0' * 48000 * 4)
            def gpu(audio, _):
                if audio.name == 'gpu.wav':
                    raise ValueError('exact private GPU cause')
                return gpu_result
            def cpu(audio):
                if audio.name == 'cpu.wav':
                    raise RuntimeError('exact private CPU cause')
                return cpu_result
            def worker(command, **kwargs):
                stdout, stderr = io.StringIO(), io.StringIO()
                with redirect_stdout(stdout), redirect_stderr(stderr):
                    analysis_numerics._analysis_worker_many([(path, 1000) for path in paths], project, 5,
                        on_completed=lambda index, analysis: print('STEMWISE_ACOUSTIC_ROW=' + json.dumps({'index': index, 'analysis': analysis})))
                return subprocess.CompletedProcess(command, 0, stdout.getvalue(), stderr.getvalue())
            with patch.object(analysis_numerics, '_gpu_analysis', gpu), patch.object(analysis_numerics, 'analyze_essentia', cpu), \
                    patch.object(analysis_numerics, 'analyze_allin1_many', return_value=[structure_result] * 3), \
                    patch.object(pipeline, 'run_process_tree', worker), \
                    exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(project)})):
                rows = pipeline._run_analysis_batch([(path, 1000) for path in paths], project, 5)
            self.assertEqual(rows[0]['durationMs'], 1000)
            self.assertNotIn('error', rows[0])
            for index, component in ((1, 'GPU'), (2, 'CPU')):
                self.assertIsInstance(rows[index], RuntimeError)
                self.assertEqual(rows[index].returncode, 0)
                self.assertEqual(rows[index].worker_reason, 'song-analysis-failed')
                self.assertIn(f'exact private {component} cause', rows[index].stderr)
                self.assertIn(f'Acoustic row {index} {component.lower()}', rows[index].stderr)
                self.assertNotIn('exact private ' + ('CPU' if component == 'GPU' else 'GPU'), rows[index].stderr)

    def test_normal_queued_entry_preserves_worker_cause_and_rejects_forged_diagnostics(self):
        import stemwise_engine.analysis_queue as analysis_queue
        from test_packages import package_config
        for exit_code in (0, 27):
            with self.subTest(exit_code=exit_code), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
                project = Path(temporary)
                audio = project / 'sample.wav'
                audio.write_bytes(b'known isolated numerical input')
                completed = subprocess.CompletedProcess(['worker'], exit_code, '', 'private queued numerical cause')
                with patch.object(pipeline, 'run_process_tree', return_value=completed):
                    with self.assertRaises(RuntimeError) as raised:
                        pipeline.run_analysis_worker(audio, 1000, project, 5)
                self.assertEqual(raised.exception.returncode, exit_code)
                self.assertIn('private queued numerical cause', raised.exception.stderr)
                self.assertEqual(raised.exception.worker_reason, 'worker-exit' if exit_code else 'result-marker-invalid')
                config = package_config(project)
                config['projectDir'] = str(project/'PC side/stemwise-playlist-builder')
                owner = pipeline.StemwisePipeline(config)
                owner._record_attempt_failure('abcdefghijk', raised.exception, None)
                pointer = owner._attempt_failure_path('abcdefghijk')
                reference = json.loads(pointer.read_bytes())
                record = json.loads((pointer.parent/reference['file']).read_bytes())
                self.assertEqual(record['workerExitCode'], exit_code)
                self.assertIn('private queued numerical cause', record['privateDiagnostic'])
                self.assertEqual([], list((project/'runtime/acoustic-queue').glob('*/request.json')))
        valid = {'requestSha256': 'a'*64, 'error': 'failed',
                 'workerFailure': {'exitCode': None, 'reason': 'worker-timeout', 'stderr': 'private'}}
        self.assertIs(analysis_queue._validate_response(valid, 'a'*64), valid)
        for changes in ({'exitCode': True}, {'stderr': 'x'*12001}, {'extra': 1}, {'reason': 'private prose'}):
            bad = {**valid, 'workerFailure': {**valid['workerFailure'], **changes}}
            with self.assertRaises(RuntimeError):
                analysis_queue._validate_response(bad, 'a'*64)
        with self.assertRaises(RuntimeError):
            analysis_queue._validate_response(valid, 'b'*64)

    def test_batch_failure_keeps_observed_exit_and_stderr_without_losing_completed_peer(self):
        for exit_code in (0, 19, None):
            with self.subTest(exit_code=exit_code), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
                project = Path(temporary)
                stdout = 'STEMWISE_ACOUSTIC_ROW={"index":0,"analysis":{"durationMs":1000,"answer":"retained"}}\n'
                if exit_code is None:
                    failure = subprocess.TimeoutExpired('worker', 5, output=stdout + 'STEMWISE_ACOUSTIC_ROW={', stderr=b'private timeout cause')
                    boundary = patch.object(pipeline, 'run_process_tree', side_effect=failure)
                else:
                    completed = subprocess.CompletedProcess(['worker'], exit_code, stdout, 'private worker cause')
                    boundary = patch.object(pipeline, 'run_process_tree', return_value=completed)
                with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(project)})), boundary:
                    rows = pipeline._run_analysis_batch([(project/'one.wav',1000), (project/'two.wav',1000)], project, 5)
                self.assertEqual(rows[0], {'durationMs':1000, 'answer':'retained'})
                self.assertIsInstance(rows[1], RuntimeError)
                self.assertEqual(rows[1].returncode, exit_code)
                self.assertIn('private', rows[1].stderr)
                self.assertEqual(rows[1].worker_reason, 'worker-timeout' if exit_code is None else
                                 'worker-exit' if exit_code else 'song-result-missing')

    def test_truncated_utf8_timeout_preserves_completed_row(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            project = Path(temporary)
            complete = 'STEMWISE_ACOUSTIC_ROW={"index":0,"analysis":{"durationMs":1000,"answer":"مكتمل"}}\n'.encode('utf-8')
            error = subprocess.TimeoutExpired('worker', 5,
                output=complete + b'STEMWISE_ACOUSTIC_ROW={"unfinished":"' + 'é'.encode('utf-8')[:1],
                stderr=b'original timeout')
            with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(project)})), \
                    patch.object(pipeline, 'run_process_tree', side_effect=error):
                rows = pipeline._run_analysis_batch([(project/'one.wav',1000), (project/'two.wav',1000)], project, 5)
            self.assertEqual(rows[0], {'durationMs': 1000, 'answer': 'مكتمل'})
            self.assertEqual(rows[1].worker_reason, 'worker-timeout')
            self.assertIn('original timeout', rows[1].stderr)

    def test_outer_timeout_preserves_completed_row_after_retiring_descendants(self):
        from stemwise_engine.common import exclusive_process_lock, heavy_worker_lock_path
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            project = Path(temporary)
            source = self.numerical_fixture_package(project)
            (source/'analysis_numerics.py').write_text('''import os, subprocess, sys, time
def _analysis_worker_many(inputs,project,timeout,on_completed):
 child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'])
 (project/'descendant').write_text(str(child.pid))
 on_completed(0,{'durationMs':1000,'answer':'retained'})
 while True: time.sleep(.05)
''', encoding='utf-8')
            with patch.dict(os.environ, {'PYTHONPATH':str(source.parent)}), \
                    exclusive_process_lock(heavy_worker_lock_path({'projectDir':str(project)})):
                rows = pipeline._run_analysis_batch([(project/'one.wav',1000), (project/'two.wav',1000)], project, 2)
            self.assertEqual(rows[0], {'durationMs':1000,'answer':'retained'})
            self.assertIsInstance(rows[1], RuntimeError)
            descendant = int((project/'descendant').read_text())
            from stemwise_engine.common import _linux_process_identity
            self.assertIsNone(_linux_process_identity(descendant))

    def test_terminal_retirement_protocol_rejects_streamed_rows_but_numeric_failure_keeps_completed_peer(self):
        from stemwise_engine.common import WorkerRetirementError, exclusive_process_lock, heavy_worker_lock_path
        for error in ('WorkerRetirementError', 'RuntimeError'):
            with self.subTest(error=error), tempfile.TemporaryDirectory() as directory:
                project = Path(directory)
                source = self.numerical_fixture_package(project)
                (source / 'analysis_numerics.py').write_text(
                    'from stemwise_engine.common import WorkerRetirementError\n'
                    'def _analysis_worker_many(inputs,project,timeout,on_completed):\n'
                    ' on_completed(0,{"durationMs":1000,"value":"owned result"})\n'
                    f' raise {error}("injected stage failure")\n', encoding='utf-8')
                inputs = [(project / 'one.wav',1000), (project / 'two.wav',1000)]
                with patch.dict(os.environ, {'PYTHONPATH':str(source.parent)}), \
                        exclusive_process_lock(heavy_worker_lock_path({'projectDir':str(project)})):
                    if error == 'WorkerRetirementError':
                        with self.assertRaises(WorkerRetirementError):
                            pipeline._run_analysis_batch(inputs, project, 5)
                    else:
                        rows = pipeline._run_analysis_batch(inputs, project, 5)
                        self.assertEqual(rows[0], {'durationMs':1000,'value':'owned result'})
                        self.assertIsInstance(rows[1], RuntimeError)

    def test_public_entry_batches_distinct_process_callers_and_routes_each_owned_result(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            project = Path(directory)
            source = self.numerical_fixture_package(project)
            # Substitute only numerical work; admission, IPC, supervision and row parsing are real.
            (source / 'analysis_numerics.py').write_text('''
import os
def _analysis_worker(audio,duration,*_):
 return {'durationMs':duration,'value':audio.read_text(),'workerPid':os.getpid(),'batchSize':1}
def _analysis_worker_many(inputs,project,timeout,on_completed):
 for index,(audio,duration) in enumerate(inputs):
  on_completed(index,{'durationMs':duration,'value':audio.read_text(),'workerPid':os.getpid(),'batchSize':len(inputs)})
''')
            inputs = [project / 'first.wav', project / 'second.wav']
            for path in inputs:
                path.write_text(path.stem)
            script = '\nimport json,sys\nfrom pathlib import Path\nfrom stemwise_engine.pipeline import run_analysis_worker\nprint(json.dumps(run_analysis_worker(Path(sys.argv[1]),1000,Path(sys.argv[2]),30)))\n'
            children = []
            try:
                with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(project)})):
                    for path in inputs:
                        children.append(subprocess.Popen([sys.executable, '-B', '-c', script, str(path), str(project)],
                            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            env={**os.environ, 'PYTHONPATH': str(source.parent)}))
                    deadline = time.monotonic() + 10
                    while len(list((project / 'runtime/acoustic-queue').glob('*/request.json'))) != 2:
                        if time.monotonic() >= deadline:
                            self.fail('Both distinct callers must reach live acoustic admission')
                        time.sleep(.02)
                rows = []
                for child in children:
                    output, error = child.communicate(timeout=15)
                    self.assertEqual(0, child.returncode, error[-1000:])
                    rows.append(json.loads(output))
                self.assertEqual(['first', 'second'], [row['value'] for row in rows])
                self.assertEqual([2, 2], [row['batchSize'] for row in rows])
                self.assertEqual(rows[0]['workerPid'], rows[1]['workerPid'])
                self.assertNotIn(rows[0]['workerPid'], [child.pid for child in children])
                self.assertFalse(Path(f"/proc/{rows[0]['workerPid']}").exists())
                self.assertEqual([], list((project / 'runtime/acoustic-queue').glob('*/request.json')))
            finally:
                for child in children:
                    if child.poll() is None:
                        child.kill()
                    child.communicate()

    def test_hung_second_gpu_stage_retires_worker_and_preserves_completed_first_song(self):
        script = "\nimport json,time,signal\nfrom pathlib import Path\nfrom unittest.mock import patch\nimport stemwise_engine.analysis_numerics as analysis_numerics\ndef terminate(*_): raise KeyboardInterrupt\nsignal.signal(signal.SIGTERM,terminate)\ndef gpu(path,duration):\n if path.stem=='bad': time.sleep(60)\n return ({'status':'complete'}, {'status':'complete'}, {'status':'uncertain'}, {'status':'complete'}, {'status':'complete'})\ndef allin1(inputs,*,on_result,**kwargs):\n rows=[({'status':'complete'},None,{'status':'complete'})]*len(inputs)\n for i,row in enumerate(rows): on_result(i,row)\n return rows\nwith patch.object(analysis_numerics,'_gpu_analysis',gpu),patch.object(analysis_numerics,'analyze_allin1_many',allin1),patch.object(analysis_numerics,'analyze_essentia',return_value=({'status':'complete'},{'status':'complete'},{'status':'complete'})),patch.object(analysis_numerics,'build_waveform',return_value={'status':'complete'}):\n analysis_numerics._analysis_worker_many([(Path('good.wav'),1000),(Path('bad.wav'),1000)],Path('.'),1,on_completed=lambda i,row:print('ROW='+json.dumps({'index':i,'analysis':row}),flush=True))\n"
        start = time.monotonic()
        completed = run_process_tree([sys.executable, '-B', '-c', script], timeout=8,
            text=True, capture_output=True, env={**os.environ, 'PYTHONPATH': str(ROOT / 'PC side/stemwise-playlist-builder/src')})
        self.assertNotEqual(0, completed.returncode)
        self.assertLess(time.monotonic() - start, 6, 'Native stage timeout must not await the second full-song budget')
        rows = [json.loads(line.removeprefix('ROW=')) for line in completed.stdout.splitlines() if line.startswith('ROW=')]
        self.assertEqual([0], [row['index'] for row in rows])
        self.assertEqual(1000, rows[0]['analysis']['durationMs'])

    def test_cpu_structure_overlaps_serial_gpu_work_and_keeps_song_identity(self):
        ready = threading.Event()
        paths = [(Path('first.wav'), 1000), (Path('second.wav'), 2000)]
        visited = []
        def beats(path, duration):
            self.assertTrue(ready.wait(2), 'All-In-One must overlap the GPU stages')
            visited.append(path.name)
            return [], [], {'name': 'beat-this', 'status': 'complete'}
        def structure(inputs, **kwargs):
            ready.set()
            return [({'status': 'complete', 'sections': [{'startMs': 0, 'endMs': d, 'label': p.stem}]},
                     None, {'name': 'all-in-one', 'status': 'complete'}) for p, d in inputs]
        with patch.object(analysis_numerics, 'analyze_beat_this', beats), \
                patch.object(analysis_numerics, 'normalize_rhythm', return_value=({'status': 'complete'}, {'status': 'complete'})), \
                patch.object(analysis_numerics, 'analyze_skey', return_value=({'status': 'uncertain'}, {'status': 'complete'})), \
                patch.object(analysis_numerics, 'analyze_essentia', return_value=({'status': 'complete'}, {'status': 'complete'}, {'status': 'complete'})), \
                patch.object(analysis_numerics, 'build_waveform', return_value={'status': 'complete'}), \
                patch.object(analysis_numerics, 'analyze_allin1_many', structure, create=True):
            rows = analysis_numerics._analysis_worker_many(paths, ROOT, 30)
        self.assertEqual(['first.wav', 'second.wav'], visited)
        self.assertEqual([1000, 2000], [row['durationMs'] for row in rows])
        self.assertEqual(['first', 'second'], [row['structure']['sections'][0]['label'] for row in rows])

    def test_failed_song_does_not_discard_other_song(self):
        def beats(path, duration):
            if path.stem == 'bad':
                raise ValueError('bad input')
            return [], [], {'name': 'beat-this', 'status': 'complete'}
        with patch.object(analysis_numerics, 'analyze_beat_this', beats), \
                patch.object(analysis_numerics, 'normalize_rhythm', return_value=({'status': 'complete'}, {'status': 'complete'})), \
                patch.object(analysis_numerics, 'analyze_skey', return_value=({'status': 'uncertain'}, {'status': 'complete'})), \
                patch.object(analysis_numerics, 'analyze_essentia', return_value=({'status': 'complete'}, {'status': 'complete'}, {'status': 'complete'})), \
                patch.object(analysis_numerics, 'build_waveform', return_value={'status': 'complete'}), \
                patch.object(analysis_numerics, 'analyze_allin1_many', return_value=[({'status': 'complete'}, None, {'status': 'complete'})] * 2, create=True):
            rows = analysis_numerics._analysis_worker_many([(Path('good.wav'), 1000), (Path('bad.wav'), 1000)], ROOT, 30)
        self.assertEqual(1000, rows[0]['durationMs'])
        self.assertNotIn('error', rows[0])
        self.assertIn('error', rows[1])


if __name__ == '__main__':
    unittest.main()
