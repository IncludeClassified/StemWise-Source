"""Real admission/ownership tests; the cheap backend reads each admitted input."""
import hashlib
import errno
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
from stemwise_engine.common import (ProcessLockBusyError, exclusive_process_lock, heavy_worker_lock_path, json_bytes)
import stemwise_engine.analysis_queue as analysis_queue


class AnalysisQueueTests(unittest.TestCase):
    def test_fatal_dispatch_preserves_aliases_and_requests_until_worker_lease_retires(self):
        from stemwise_engine.common import (WorkerRetirementError, process_lock_descriptor,
            ProcessLockBusyError, worker_directory_cleanup)
        path, held, aliases = self.input('one', b'owner audio'), [], []
        def backend(inputs, *_):
            alias = inputs[0][0]
            aliases.append(alias)
            held.append(os.dup(process_lock_descriptor(alias.parent/'.stemwise-worker.lock')))
            (alias.parent/'prepare.request.json').write_bytes(b'worker request')
            raise WorkerRetirementError('owned model still running')
        try:
            with self.assertRaises(WorkerRetirementError):
                analysis_queue.analyze(path, 1000, self.project, 30, backend)
            alias = aliases[0]
            self.assertEqual(alias.read_bytes(), b'owner audio')
            self.assertEqual((alias.parent/'prepare.request.json').read_bytes(), b'worker request')
            request = alias.parent.parent
            self.assertTrue((request/'request.json').exists() or (request/'retired.json').exists())
            with self.assertRaises(ProcessLockBusyError):
                with worker_directory_cleanup(request):
                    pass
            analysis_queue.dispose_scratch(request, directory=True)
            self.assertTrue(alias.exists())
            os.close(held.pop())
            analysis_queue.dispose_scratch(request, directory=True)
            self.assertFalse(request.exists())
            self.assertEqual(path.read_bytes(), b'owner audio')
        finally:
            for descriptor in held:
                os.close(descriptor)

    def test_shared_caller_deadline_does_not_shorten_an_admitted_stage_unnecessarily(self):
        first, second = self.input('one',b'one'), self.input('two',b'two')
        backend=self.backend
        timeouts=[]
        def observed(inputs,project,timeout):
            timeouts.append(timeout)
            return backend(inputs,project,timeout)
        self.backend=observed
        deadline=time.monotonic()+120
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir':str(self.project)})):
            threads=[self.start('one',first,deadline=deadline),self.start('two',second,deadline=deadline)]
            self.ready(2)
        self.finish(threads)
        self.assertFalse(self.errors)
        self.assertEqual(timeouts,[30])

    def test_explicit_queue_deadline_and_cancellation_retire_waiting_request(self):
        from stemwise_engine.provider_operations import ProviderCancelled
        path = self.input('one', b'one')
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            with self.assertRaisesRegex(TimeoutError, 'queue-admission'):
                analysis_queue.analyze(path, 1000, self.project, 30, self.backend, deadline=time.monotonic()+.15)
            with self.assertRaises(ProviderCancelled):
                analysis_queue.analyze(path, 1000, self.project, 30, self.backend, cancelled=lambda: True)
        self.assertEqual(self.calls, [])
        self.assertFalse(list((self.project/'runtime/acoustic-queue').glob('*/request.json')))

    def test_cross_filesystem_staging_copies_and_verifies_actual_audio(self):
        path = self.input('one', b'one')
        with patch.object(analysis_queue.os, 'link', side_effect=OSError(errno.EXDEV, 'cross-device')):
            self.assertEqual(analysis_queue.analyze(path, 1000, self.project, 30, self.backend)['value'], 'one')
        self.assertEqual(path.read_bytes(), b'one')

    def test_caller_remaining_time_reaches_real_worker_argument_parser(self):
        import contextlib
        import io
        from stemwise_engine import pipeline, analysis_worker, analysis_numerics
        path = self.input('one', b'one')
        observed = []
        def execute(command, **options):
            observed.append(options['timeout'])
            output = io.StringIO()
            arguments = command[4:]
            arguments[-1] = str(os.dup(int(arguments[-1])))
            with patch.object(analysis_numerics, '_analysis_worker', return_value={'durationMs':1000}), \
                    contextlib.redirect_stdout(output):
                self.assertEqual(analysis_worker.main(arguments), 0)
            return subprocess.CompletedProcess(command, 0, output.getvalue(), '')
        with patch.object(pipeline, 'run_process_tree', side_effect=execute):
            result = analysis_queue.analyze(path, 1000, self.project, 30, pipeline._run_analysis_batch,
                deadline=time.monotonic()+5)
        self.assertEqual(result, {'durationMs':1000})
        self.assertEqual(len(observed), 1)
        self.assertTrue(0 < observed[0] <= 5)

    def test_serial_timeout_leaves_unstarted_peer_pending_for_its_own_dispatch(self):
        first, second = self.input('one', b'one'), self.input('two', b'two')
        calls, failed = [], []
        def backend(inputs, *_):
            values = [path.read_bytes().decode() for path, _ in inputs]
            calls.append(values)
            if len(inputs) == 2:
                failed.append(values[0])
                return [TimeoutError('started song timed out'), analysis_queue.AnalysisInputNotStarted('not started')]
            return [{'durationMs': duration, 'value': values[index]} for index, (_, duration) in enumerate(inputs)]
        self.backend = backend
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            threads = [self.start('one', first), self.start('two', second)]
            self.ready(2)
        self.finish(threads)
        healthy = next(value for value in ('one', 'two') if value not in failed)
        self.assertEqual(self.results, {healthy: {'durationMs': 1000, 'value': healthy}})
        self.assertEqual(set(self.errors), set(failed))
        self.assertEqual([len(call) for call in calls], [2, 1])

    def test_retained_peer_batch_does_not_block_waiting_owners_next_dispatch(self):
        from stemwise_engine.common import atomic_write_json
        root = self.project/'runtime/acoustic-queue'
        requests = []
        for index, name in enumerate(('A', 'B', 'C')):
            audio = self.input(name, name.encode())
            directory = root/(name.lower()*32)
            directory.mkdir(parents=True)
            request = {'token':directory.name, 'pid':os.getpid(), 'start':'fixture', 'created':index,
                'producer':'fixture', 'audio':str(audio), 'audioSha256':hashlib.sha256(audio.read_bytes()).hexdigest(),
                'durationMs':1000, 'timeout':30}
            atomic_write_json(directory/'request.json', request)
            requests.append((directory, request))
        directory, request = requests[-1]
        real_remove = analysis_queue.shutil.rmtree
        retained = []
        def remove(path, *args, **kwargs):
            if Path(path).parent == directory and not retained:
                retained.append(Path(path))
                raise PermissionError('first batch retained')
            return real_remove(path, *args, **kwargs)
        def send(peer, response):
            owner = root/peer['token']
            analysis_queue._retire_request(owner, peer, response)
        with patch.object(analysis_queue, '_live', return_value=True), \
                patch.object(analysis_queue, '_send', side_effect=send), \
                patch.object(analysis_queue.shutil, 'rmtree', side_effect=remove):
            self.assertIsNone(analysis_queue._dispatch(root, directory, self.project, 'fixture', 30,
                self.backend, local_request=request))
            self.assertTrue(retained[0].is_dir())
            result = analysis_queue._dispatch(root, directory, self.project, 'fixture', 30,
                self.backend, local_request=request)
        self.assertEqual(result['analysis'], {'durationMs':1000, 'value':'C'})
        self.assertEqual(self.calls, [[b'A', b'B'], [b'C']])
        self.assertEqual(len(list(root.glob('*/retired.json'))), 3)

    def test_disposal_failure_preserves_completed_result_and_primary_worker_failure(self):
        from stemwise_engine.common import atomic_write_json, read_json
        real_remove = analysis_queue.shutil.rmtree
        for stage in ('batch', 'request'):
            for failed in (False, True):
                with self.subTest(stage=stage, failed=failed):
                    path = self.input(f'{stage}-{failed}', b'validated numerical PCM')
                    calls = []
                    def backend(inputs, *args):
                        calls.append(inputs)
                        if failed:
                            raise subprocess.TimeoutExpired('numerical-worker', 30, stderr=b'original worker cause')
                        return self.backend(inputs, *args)
                    def remove(directory, *args, **kwargs):
                        directory = Path(directory)
                        if (stage == 'batch' and directory.name.startswith('batch-') or
                                stage == 'request' and directory.parent.name == 'acoustic-queue'):
                            raise PermissionError('disposable directory is busy')
                        return real_remove(directory, *args, **kwargs)
                    with patch.object(analysis_queue.shutil, 'rmtree', side_effect=remove):
                        if failed:
                            with self.assertRaises(subprocess.TimeoutExpired) as caught:
                                analysis_queue.analyze(path, 1000, self.project, 30, backend)
                            self.assertIn('original worker cause', caught.exception.stderr)
                        else:
                            result = analysis_queue.analyze(path, 1000, self.project, 30, backend)
                            checkpoint = self.project / f'{stage}.checkpoint.json'
                            atomic_write_json(checkpoint, result)
                            self.assertEqual(read_json(checkpoint),
                                {'durationMs': 1000, 'value': 'validated numerical PCM'})
                    self.assertEqual(len(calls), 1, 'Disposal cannot repeat numerical execution')
                    self.assertEqual(path.read_bytes(), b'validated numerical PCM')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work')
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        self.calls = []
        self.results = {}
        self.errors = {}

    def input(self, name, value):
        path = self.project / name / 'analysis.wav'
        path.parent.mkdir()
        path.write_bytes(value)
        return path

    def backend(self, inputs, project, timeout):
        self.calls.append([path.read_bytes() for path, _ in inputs])
        return [{'durationMs': duration, 'value': path.read_bytes().decode()}
                for path, duration in inputs]

    def start(self, name, path, **options):
        def run():
            try:
                self.results[name] = analysis_queue.analyze(path, 1000, self.project, 30, self.backend, **options)
            except Exception as error:
                self.errors[name] = error
        thread = threading.Thread(target=run)
        thread.start()
        self.addCleanup(lambda: thread.join(10))
        return thread

    def ready(self, count):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if len(list((self.project / 'runtime/acoustic-queue').glob('*/request.json'))) == count:
                return
            time.sleep(.01)
        self.fail('Inputs did not enter the actual queue')

    def finish(self, threads):
        for thread in threads:
            thread.join(10)
            self.assertFalse(thread.is_alive(), 'Admission did not finish')
        self.assertEqual([], list((self.project / 'runtime/acoustic-queue').glob('*/request.json')))

    def test_lone_request_runs_without_waiting_for_another_song(self):
        path = self.input('one', b'one')
        start = time.monotonic()
        result = analysis_queue.analyze(path, 1000, self.project, 30, self.backend)
        self.assertEqual({'durationMs': 1000, 'value': 'one'}, result)
        self.assertEqual([[b'one']], self.calls)
        self.assertLess(time.monotonic() - start, 1)

    def test_retired_request_preserves_live_owner_then_scavenges_pcm_after_process_death(self):
        source = self.input('one', b'owned retained PCM')
        root = self.project / 'runtime/acoustic-queue'
        directory = root / ('a' * 32)
        directory.mkdir(parents=True)
        script = "import hashlib,json,os,sys,time\nfrom pathlib import Path\nsys.path.insert(0,sys.argv[1])\nimport stemwise_engine.analysis_queue as queue\nfrom stemwise_engine.common import exclusive_process_lock,json_bytes\ndirectory,source=map(Path,sys.argv[2:])\nwith exclusive_process_lock(directory/'owner.lock'):\n request={'token':directory.name,'pid':os.getpid(),'start':queue._owner(os.getpid()),\n  'created':1,'producer':'fixture','audio':str(source),'audioSha256':hashlib.sha256(source.read_bytes()).hexdigest(),\n  'durationMs':1000,'timeout':30}\n (directory/'request.json').write_bytes(json_bytes(request))\n (directory/'batch').mkdir()\n os.link(source,directory/'batch/0.wav')\n queue._retire_request(directory,request,{'requestSha256':hashlib.sha256(json_bytes(request)).hexdigest(),'analysis':{'durationMs':1000}})\n print('RETIRED',flush=True)\n time.sleep(30)\n"
        child = subprocess.Popen([sys.executable, '-B', '-c', script,
            str(ROOT / 'PC side/stemwise-playlist-builder/src'), str(directory), str(source)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            import select
            self.assertTrue(select.select([child.stdout], [], [], 5)[0], 'child did not retire its request')
            self.assertEqual(child.stdout.readline().strip(), 'RETIRED')
            self.assertEqual(analysis_queue._pending(root, 'fixture', 30), [])
            self.assertTrue(directory.exists(), 'live acknowledgement owner was removed')
        finally:
            child.kill()
            _, stderr = child.communicate(timeout=5)
        self.assertFalse(stderr, stderr)
        self.assertEqual(analysis_queue._pending(root, 'fixture', 30), [])
        self.assertFalse(directory.exists(), 'retired hardlinks survived their owner')
        self.assertEqual(source.read_bytes(), b'owned retained PCM')
        self.assertEqual(source.stat().st_nlink, 1)

    def test_live_pending_inputs_batch_in_twos_and_return_their_own_results(self):
        paths = [self.input(str(i), str(i).encode()) for i in range(3)]
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            threads = [self.start(str(i), path) for i, path in enumerate(paths)]
            self.ready(3)
        self.finish(threads)
        self.assertFalse(self.errors)
        self.assertEqual([1, 2], sorted(map(len, self.calls)))
        self.assertEqual({str(i): {'durationMs': 1000, 'value': str(i)} for i in range(3)}, self.results)

    def test_incomplete_batch_never_marks_the_missing_song_complete(self):
        first, second = self.input('one', b'one'), self.input('two', b'two')
        def missing(inputs, *_):
            return [{'durationMs': duration, 'value': path.read_bytes().decode()} for path, duration in inputs[:1]]
        self.backend = missing
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            threads = [self.start('one', first), self.start('two', second)]
            self.ready(2)
        self.finish(threads)
        self.assertEqual({}, self.results)
        self.assertEqual({'one', 'two'}, set(self.errors))

    def test_one_song_failure_preserves_the_other_completed_result(self):
        first, second = self.input('one', b'one'), self.input('two', b'two')
        def mixed(inputs, *_):
            return [ValueError('song failed') if path.read_bytes() == b'two' else
                    {'durationMs': duration, 'value': 'one'} for path, duration in inputs]
        self.backend = mixed
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            threads = [self.start('one', first), self.start('two', second)]
            self.ready(2)
        self.finish(threads)
        self.assertEqual({'one': {'durationMs': 1000, 'value': 'one'}}, self.results)
        self.assertEqual({'two'}, set(self.errors))

    def test_input_mutation_during_batch_is_rejected(self):
        path = self.input('one', b'one')
        def changed(inputs, *_):
            path.write_bytes(b'changed')
            return [{'durationMs': 1000, 'value': 'one'}]
        with self.assertRaisesRegex(RuntimeError, 'changed'):
            analysis_queue.analyze(path, 1000, self.project, 30, changed)
        self.assertEqual([], list((self.project / 'runtime/acoustic-queue').glob('*/request.json')))

    def test_readable_matching_response_file_cannot_fake_model_completion(self):
        path = self.input('one', b'one')
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            thread = self.start('one', path)
            self.ready(1)
            request_path = next((self.project / 'runtime/acoustic-queue').glob('*/request.json'))
            request = json.loads(request_path.read_text())
            (request_path.parent / 'result.json').write_text(json.dumps({
                'requestSha256': hashlib.sha256(json_bytes(request)).hexdigest(),
                'analysis': {'durationMs': 1000, 'value': 'fabricated'}}))
        self.finish([thread])
        self.assertEqual({'one': {'durationMs': 1000, 'value': 'one'}}, self.results)
        self.assertEqual([[b'one']], self.calls)
        self.assertFalse(self.errors)

    def test_same_user_socket_without_kernel_model_lease_cannot_complete_a_peer(self):
        first, second = self.input('one', b'one'), self.input('two', b'two')
        lock = heavy_worker_lock_path({'projectDir': str(self.project)})
        with exclusive_process_lock(lock):
            threads = [self.start('one', first)]
            self.ready(1)
            dispatch = self.project / 'runtime/acoustic-queue/dispatch.lock'
            deadline = time.monotonic() + 5
            while True:
                try:
                    with exclusive_process_lock(dispatch):
                        pass
                except ProcessLockBusyError:
                    break
                self.assertLess(time.monotonic(), deadline, 'first request did not own dispatch')
                time.sleep(.01)
            threads.append(self.start('two', second))
            self.ready(2)
            request = next(json.loads(path.read_text()) for path in
                           (self.project / 'runtime/acoustic-queue').glob('*/request.json')
                           if json.loads(path.read_text())['audio'] == str(second))
            response = {'requestSha256': hashlib.sha256(json_bytes(request)).hexdigest(),
                        'analysis': {'durationMs': 1000, 'value': 'fabricated'}}
            script = '''
import json,socket,struct,sys
with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as connection:
 connection.settimeout(2)
 connection.connect("\\0stemwise-acoustic-"+sys.argv[1])
 accepted=False
 try:
  if connection.recv(1)==b"1":
   raw=sys.argv[2].encode();connection.sendall(struct.pack("!Q",len(raw))+raw)
   accepted=connection.recv(1)==b"1"
 except OSError: pass
 print(json.dumps({"accepted":accepted}))
'''
            rogue = subprocess.run([sys.executable, '-B', '-c', script, request['token'], json.dumps(response)],
                                   capture_output=True, text=True, timeout=4)
        self.finish(threads)
        self.assertEqual(rogue.returncode, 0, rogue.stderr)
        self.assertEqual(json.loads(rogue.stdout), {'accepted': False})
        self.assertEqual({'one': {'durationMs': 1000, 'value': 'one'},
                          'two': {'durationMs': 1000, 'value': 'two'}}, self.results)
        self.assertEqual([[b'one', b'two']], self.calls)
        self.assertFalse(self.errors)

    def test_kernel_lease_identity_rejects_editable_pid_text_and_nonowner_process(self):
        lock = heavy_worker_lock_path({'projectDir': str(self.project)})
        with exclusive_process_lock(lock):
            lock.write_text('0\n')
            self.assertTrue(analysis_queue._peer_has_model_lease(os.getpid(), lock))
            script = ('import os,sys\nfrom pathlib import Path\n'
                      f'sys.path.insert(0,{str(ROOT / "PC side/stemwise-playlist-builder/src")!r})\n'
                      'import stemwise_engine.analysis_queue as analysis_queue\n'
                      'path=Path(sys.argv[1]);path.write_text(str(os.getpid())+"\\n")\n'
                      'with path.open("r+"):\n'
                      ' print(analysis_queue._peer_has_model_lease(os.getpid(),path))\n')
            rogue = subprocess.run([sys.executable, '-B', '-c', script, str(lock)],
                                   capture_output=True, text=True, timeout=4)
            self.assertEqual(rogue.returncode, 0, rogue.stderr)
            self.assertEqual(rogue.stdout.strip(), 'False')
            self.assertTrue(analysis_queue._peer_has_model_lease(os.getpid(), lock))

    def test_identical_pending_pcm_runs_once_and_completes_both_owners(self):
        first, second = self.input('one', b'same'), self.input('two', b'same')
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            threads = [self.start('one', first), self.start('two', second)]
            self.ready(2)
        self.finish(threads)
        self.assertEqual({'one': {'durationMs': 1000, 'value': 'same'},
                          'two': {'durationMs': 1000, 'value': 'same'}}, self.results)
        self.assertEqual([[b'same']], self.calls)
        self.assertFalse(self.errors)

    def test_queue_delay_does_not_spend_execution_watchdog(self):
        from types import SimpleNamespace
        path = self.input('one', b'one')
        elapsed = [0.]
        clock = SimpleNamespace(monotonic=lambda: time.monotonic()+elapsed[0],
            monotonic_ns=time.monotonic_ns, sleep=time.sleep)
        def lock(path, **options):
            if path.name == 'stemwise-heavy-worker.lock':
                options['wait_seconds'] = 0
            return exclusive_process_lock(path, **options)
        with patch.object(analysis_queue, 'time', clock), patch.object(
                analysis_queue, 'exclusive_process_lock', side_effect=lock):
            with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
                thread = self.start('one', path)
                self.ready(1)
                elapsed[0] = 8*3600
                time.sleep(.25)
                self.assertTrue(thread.is_alive(), 'Queue delay consumed a model execution budget')
            self.finish([thread])
        self.assertFalse(self.errors)
        self.assertEqual(self.results['one']['value'], 'one')

    def test_changed_queued_input_fails_once_without_starting_a_model(self):
        path = self.input('one', b'one')
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            thread = self.start('one', path)
            self.ready(1)
            path.write_bytes(b'changed')
        self.finish([thread])
        self.assertEqual({}, self.results)
        self.assertEqual([], self.calls)
        self.assertIsInstance(self.errors['one'], RuntimeError)
        self.assertIn('changed', str(self.errors['one']))

    def test_same_interpreter_with_different_virtual_environments_does_not_batch(self):
        first, second = self.input('one', b'one'), self.input('two', b'two')
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            threads = [self.start('one', first)]
            self.ready(1)
            with patch.object(analysis_queue.sys, 'prefix', sys.prefix + '-other-venv'):
                threads.append(self.start('two', second))
                self.ready(2)
        self.finish(threads)
        self.assertEqual([1, 1], list(map(len, self.calls)))
        self.assertEqual({'one': {'durationMs': 1000, 'value': 'one'},
                          'two': {'durationMs': 1000, 'value': 'two'}}, self.results)
        self.assertFalse(self.errors)

    def test_acknowledged_peer_is_not_reselected_while_its_caller_is_slow(self):
        first, second, third = [self.input(name, name.encode()) for name in ('one', 'two', 'three')]
        held, release, third_ran = threading.Event(), threading.Event(), threading.Event()
        receive = analysis_queue._receive
        dispatch = analysis_queue._dispatch
        backend = self.backend
        def slow_caller(listener, lock, directory, request):
            response = receive(listener, lock, directory, request)
            if response is not None and request['audio'] == str(second):
                held.set()
                if not release.wait(5):
                    raise RuntimeError('Slow-caller fixture was not released')
            return response
        def observed_backend(inputs, project, timeout):
            results = backend(inputs, project, timeout)
            if any(path.read_bytes() == b'three' for path, _ in inputs):
                third_ran.set()
            return results
        def first_dispatcher(*args, **kwargs):
            if kwargs['local_request']['audio'] != str(first) and not held.is_set():
                return None
            return dispatch(*args, **kwargs)
        self.backend = observed_backend
        threads = []
        with patch.object(analysis_queue, '_receive', slow_caller), \
                patch.object(analysis_queue, '_dispatch', side_effect=first_dispatcher):
            try:
                with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
                    threads.append(self.start('one', first))
                    self.ready(1)
                    threads.append(self.start('two', second))
                    self.ready(2)
                    threads.append(self.start('three', third))
                    self.ready(3)
                self.assertTrue(held.wait(3), 'the completed peer was not held after its acknowledgement')
                self.assertTrue(third_ran.wait(3), 'the next request did not enter model execution')
                self.assertEqual([b'one', b'two', b'three'], [value for batch in self.calls for value in batch])
            finally:
                release.set()
                self.finish(threads)
        self.assertEqual({name: {'durationMs': 1000, 'value': name} for name in ('one', 'two', 'three')}, self.results)
        self.assertFalse(self.errors)


if __name__ == '__main__':
    unittest.main()
