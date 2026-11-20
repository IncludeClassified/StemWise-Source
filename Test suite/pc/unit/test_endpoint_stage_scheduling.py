"""Endpoint model children own the GPU; their parent CPU work retains only the song."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import numpy as np

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.common as common
import stemwise_engine.endpoint_native_ctc as native
import stemwise_engine.endpoint_owsm_ctc as owsm
import stemwise_engine.owsm_ctc_forced_alignment as primary_owsm


@unittest.skipUnless(sys.platform == 'linux', 'kernel model leases run in WSL')
class EndpointStageSchedulingTests(unittest.TestCase):
    def setUp(self):
        device = {'name': 'Fixture CUDA accelerator', 'capability': [12, 0], 'totalMemoryBytes': 16*1024**3}
        for worker in (native.nfa, owsm.worker):
            observation = patch.object(worker, 'cuda_device_identity', return_value=device)
            observation.start()
            self.addCleanup(observation.stop)

    def test_primary_owsm_owns_only_model_work_and_retires_before_cpu_alignment(self):
        import hashlib
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            project = Path(temporary)
            lock = common.heavy_worker_lock_path({'projectDir': str(project)})
            song = project / 'song.lock'
            request_path, output = project / 'request.json', project / 'result.json'
            request = {'route': primary_owsm.ROUTE, 'runtime': {}, 'durationMs': 2000,
                       'audio': {'sha256': 'a'*64, 'byteCount': 42}, 'lines': [{'lineId': 'line'}]}
            request_path.write_text(json.dumps(request))
            paths = {'modelRoot': project / 'model', 'align': project / 'source/a/b/align.py',
                     'lock': project / 'runtime/lock.json', 'audio': project / 'audio.wav'}
            events = []
            model = object()
            speech = np.zeros(32000, dtype=np.float32)
            aligner = SimpleNamespace(s2t_model=model, ctc=model, token_list=['blank', 'word'],
                fs=16000, context_len_in_secs=0, frames_per_sec=50,
                s2t_train_args=SimpleNamespace(preprocessor_conf={'speech_length': 1}))
            def lease_free(stage):
                self.assertIsNone(common.process_lock_descriptor(lock), stage)
                self.assertIsNotNone(common.process_lock_descriptor(song))
                child = ('from stemwise_engine.common import heavy_worker_stage\n'
                         f'with heavy_worker_stage({dict(projectDir=str(project))!r},wait_seconds=0):\n'
                         ' print("peer-ran")\n')
                result = common.run_process_tree([sys.executable, '-B', '-c', child], timeout=4,
                    capture_output=True, text=True, check=True,
                    env={**os.environ, 'PYTHONPATH': str(Path(common.__file__).parent)})
                self.assertEqual(result.stdout.strip(), 'peer-ran')
                events.append(stage)
            def resolve(_request):
                lease_free('input-check')
                return paths
            def prepare(_audio):
                lease_free('audio-preparation')
                return speech, {'prepared': True}
            def load(*_):
                self.assertIsNotNone(common.process_lock_descriptor(lock), 'Model has no GPU lease')
                events.append('load-model')
                return aligner
            def infer(actual_speech):
                self.assertIs(actual_speech, speech)
                self.assertIsNotNone(common.process_lock_descriptor(lock))
                peer = ('from stemwise_engine.common import heavy_worker_stage,ProcessLockBusyError\n'
                        'try:\n'
                        f' with heavy_worker_stage({dict(projectDir=str(project))!r},wait_seconds=0):\n'
                        '  raise AssertionError("Concurrent GPU owner admitted")\n'
                        'except ProcessLockBusyError: print("busy")\n')
                environment = {**os.environ, 'PYTHONPATH': str(Path(common.__file__).parent)}
                environment.pop(common._PROCESS_LOCK_LEASES_ENV, None)
                result = subprocess.run([sys.executable, '-B', '-c', peer], timeout=4,
                    capture_output=True, text=True, check=True, env=environment)
                self.assertEqual(result.stdout.strip(), 'busy')
                events.append('infer')
                return 'posterior', 32000
            aligner.get_lpz = infer
            def retire(state):
                self.assertIsNone(aligner.s2t_model, 'Aligner still owns its GPU model')
                self.assertIsNone(aligner.ctc, 'Aligner still owns its GPU CTC head')
                self.assertIs(state.pop('model'), model)
                self.assertTrue(state['cuda_model'])
                self.assertIsNotNone(common.process_lock_descriptor(lock))
                events.append('retire-model')
            def decode(actual_aligner, posterior, samples, *args):
                self.assertIs(actual_aligner, aligner)
                self.assertEqual((posterior, samples), ('posterior', 32000))
                lease_free('cpu-alignment')
                return [{'lineId': 'line', 'startMs': 100, 'endMs': 900}]
            with common.exclusive_process_lock(song), \
                    patch.object(primary_owsm, '__file__', str(project / 'src/worker.py')), \
                    patch.object(primary_owsm, '_validated_request', side_effect=lambda value: value), \
                    patch.object(primary_owsm, '_resolved_bindings', side_effect=resolve), \
                    patch.object(primary_owsm, '_runtime_identity', return_value={}), \
                    patch.object(primary_owsm, '_prepare_speech', side_effect=prepare), \
                    patch.object(primary_owsm, '_load_aligner', side_effect=load), \
                    patch.object(primary_owsm, '_preflight', return_value=(['word'], [{}], {})), \
                    patch.object(primary_owsm, 'retire_model', side_effect=retire), \
                    patch.object(primary_owsm, '_decode_lines', side_effect=decode, create=True):
                primary_owsm.run(request_path, hashlib.sha256(request_path.read_bytes()).hexdigest(), output)
            self.assertEqual(events, ['input-check', 'audio-preparation', 'load-model', 'infer',
                                      'retire-model', 'cpu-alignment'])
            self.assertEqual(json.loads(output.read_text())['lines'],
                             [{'lineId': 'line', 'startMs': 100, 'endMs': 900}])

    def test_primary_owsm_initialization_and_retirement_fail_closed(self):
        for failure in ('initialization', 'retirement'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(
                    dir=ROOT / 'Test suite/work') as temporary:
                project = Path(temporary)
                child = ('import os\nfrom pathlib import Path\nfrom types import SimpleNamespace\n'
                    'import stemwise_engine.owsm_ctc_forced_alignment as worker\n'
                    f'worker.__file__ = {str(project / "src/worker.py")!r}\n'
                    'def fail(*args): raise RuntimeError("injected model failure")\n'
                    'worker._load_aligner = lambda *args: SimpleNamespace(s2t_model=object())\n'
                    'worker.retire_model = lambda *args: None\n'
                    f'worker.{"_load_aligner" if failure == "initialization" else "retire_model"} = fail\n'
                    'print(os.getpid(),flush=True)\n'
                    'with worker._model_stage({},worker.ROUTE): pass\n'
                    f'Path({str(project / "result.json")!r}).write_text("false success")\n')
                environment = {**os.environ, 'PYTHONPATH': str(Path(primary_owsm.__file__).parent)
                               + os.pathsep + str(Path(common.__file__).parent)}
                result = common.run_process_tree([sys.executable, '-B', '-c', child], timeout=5,
                    capture_output=True, text=True, check=False, env=environment)
                self.assertEqual(result.returncode, 70)
                self.assertIn(f'owsm-model-{failure}-failed', result.stderr)
                self.assertFalse((project / 'result.json').exists())
                self.assertFalse(Path('/proc', result.stdout.strip()).exists())
                with common.heavy_worker_stage({'projectDir': str(project)}, wait_seconds=0):
                    pass

    def test_primary_owsm_queue_wait_does_not_consume_its_hard_stage_deadline(self):
        for compute_seconds in (.02, .5):
            with self.subTest(compute_seconds=compute_seconds), tempfile.TemporaryDirectory(
                    dir=ROOT / 'Test suite/work') as temporary:
                project = Path(temporary)
                ready, start = project / 'ready', project / 'start'
                child = ('import time\nfrom pathlib import Path\nfrom types import SimpleNamespace\n'
                    'import stemwise_engine.owsm_ctc_forced_alignment as worker\n'
                    f'worker.__file__ = {str(project / "src/worker.py")!r}\n'
                    'worker._load_aligner = lambda *args: SimpleNamespace(s2t_model=object())\n'
                    'worker.retire_model = lambda *args: None\n'
                    'deadline=time.monotonic()+4\n'
                    f'while not Path({str(start)!r}).exists():\n'
                    ' assert time.monotonic()<deadline\n time.sleep(.005)\n'
                    f'Path({str(ready)!r}).write_text("ready")\n'
                    'with worker.execution_deadline(.15):\n'
                    ' with worker._model_stage({},worker.ROUTE):\n'
                    f'  time.sleep({compute_seconds!r})\n'
                    'print("completed")\n')
                environment = {**os.environ, 'PYTHONPATH': str(Path(primary_owsm.__file__).parent)
                               + os.pathsep + str(Path(common.__file__).parent)}
                with common.owned_process_tree([sys.executable, '-B', '-c', child],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=environment) as process:
                    with common.heavy_worker_stage({'projectDir': str(project)}):
                        start.write_text('start')
                        deadline = time.monotonic() + 4
                        while not ready.exists():
                            self.assertLess(time.monotonic(), deadline)
                            time.sleep(.005)
                        with self.assertRaises(subprocess.TimeoutExpired):
                            process.wait(timeout=.3)
                    stdout, stderr = process.communicate(timeout=4)
                    if compute_seconds < .15:
                        self.assertEqual(process.returncode, 0, stderr)
                        self.assertEqual(stdout.strip(), 'completed')
                    else:
                        import signal
                        self.assertEqual(process.returncode, -signal.SIGALRM)
                        self.assertNotIn('completed', stdout)
                with common.heavy_worker_stage({'projectDir': str(project)}, wait_seconds=0):
                    pass

    def test_native_and_owsm_release_retired_model_before_cpu_reply_and_next_recording(self):
        for module in (native, owsm):
            with self.subTest(worker=module.__name__), tempfile.TemporaryDirectory(
                    dir=ROOT / 'Test suite/work') as temporary:
                project = Path(temporary)
                config = {'projectDir': str(project)}
                lock = common.heavy_worker_lock_path(config)
                song = project / 'song.lock'
                model_pid = project / 'model.pid'
                contexts = ([project/'context'] if module is native else
                    [{'index': 0, 'path': str(project/'context')}])
                record = {'model': module.numerical_model()}
                expected = [record] if module is native else {'0': record}
                grandchild = ('import os,time\nfrom pathlib import Path\n'
                    f'Path({str(model_pid)!r}).write_text(str(os.getpid()))\n'
                    'while True: time.sleep(.01)\n')
                child = ('import subprocess,sys,time\nfrom pathlib import Path\n'
                    'from stemwise_engine.common import heavy_worker_stage,process_lock_descriptor\n'
                    f'config={config!r}\nlock=Path({str(lock)!r})\n'
                    'assert process_lock_descriptor(lock) is not None, "GPU child lacks ownership"\n'
                    'with heavy_worker_stage(config,wait_seconds=0): pass\n'
                    f'subprocess.Popen([sys.executable,"-B","-c",{grandchild!r}],start_new_session=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)\n'
                    f'while not Path({str(model_pid)!r}).exists(): time.sleep(.005)\n'
                    f'print({json.dumps(expected)!r})\n')
                environment = {**os.environ, 'PYTHONPATH': str(Path(common.__file__).parent)}
                environment.pop(common._PROCESS_LOCK_LEASES_ENV, None)
                invoked = []

                def supervised_child(_command, **kwargs):
                    invoked.append(True)
                    self.assertIsNotNone(common.process_lock_descriptor(lock))
                    return common.run_process_tree([sys.executable, '-B', '-c', child],
                        timeout=4, capture_output=True, text=True, check=True, env=environment)

                parse = module.parse_worker_json
                def cpu_reply(raw):
                    self.assertIsNone(common.process_lock_descriptor(lock), 'CPU reply kept the GPU')
                    self.assertIsNotNone(common.process_lock_descriptor(song), 'Song owner was lost')
                    self.assertFalse(Path(f'/proc/{model_pid.read_text()}').exists(),
                                     'A model descendant survived GPU handoff')
                    peer = ('from pathlib import Path\n'
                        'from stemwise_engine.common import heavy_worker_stage,exclusive_process_lock,ProcessLockBusyError\n'
                        f'with heavy_worker_stage({config!r},wait_seconds=0):\n'
                        ' try:\n'
                        f'  with exclusive_process_lock(Path({str(song)!r}),wait_seconds=0):\n'
                        '   raise AssertionError("Another recording acquired this song")\n'
                        ' except ProcessLockBusyError: pass\n'
                        ' print("independent-recording-ran")\n')
                    result = common.run_process_tree([sys.executable, '-B', '-c', peer],
                        timeout=4, capture_output=True, text=True, check=True, env=environment)
                    self.assertEqual(result.stdout.strip(), 'independent-recording-ran')
                    return parse(raw)

                with common.exclusive_process_lock(song), \
                        patch.object(module, 'run_process_tree', side_effect=supervised_child), \
                        patch.object(module, 'parse_worker_json', side_effect=cpu_reply):
                    self.assertEqual(module._infer(project, contexts, 4, replay=True), expected)
                self.assertEqual(invoked, [True])

    def test_failed_gpu_child_releases_lease_without_publishing_a_cpu_result(self):
        for module in (native, owsm):
            with self.subTest(worker=module.__name__), tempfile.TemporaryDirectory(
                    dir=ROOT / 'Test suite/work') as temporary:
                project = Path(temporary)
                lock = common.heavy_worker_lock_path({'projectDir': str(project)})
                contexts = ([project/'context'] if module is native else
                    [{'index': 0, 'path': str(project/'context')}])
                def fail(_command, **kwargs):
                    self.assertIsNotNone(common.process_lock_descriptor(lock))
                    raise subprocess.TimeoutExpired('owned-model', 4)
                with patch.object(module, 'run_process_tree', side_effect=fail):
                    with self.assertRaises(subprocess.TimeoutExpired):
                        module._infer(project, contexts, 4, replay=True)
                self.assertIsNone(common.process_lock_descriptor(lock))
                with common.heavy_worker_stage({'projectDir': str(project)}, wait_seconds=0):
                    pass


if __name__ == '__main__':
    unittest.main()
