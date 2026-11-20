"""The adapter binds independent All-In-One results to the submitted recordings."""
import json
import os
import signal
import sys
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[3] / 'PC side/stemwise-playlist-builder'
import stemwise_engine.analyzers as analyzers
import stemwise_engine.allin1_worker as allin1_worker
from stemwise_engine.common import run_process_tree


class AllInOneBatchTests(unittest.TestCase):
    def test_unretired_batch_preserves_request_aliases_and_outputs(self):
        from stemwise_engine.common import WorkerRetirementError, process_lock_descriptor
        held, retained = [], []
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            paths, python = self.fixture(root)
            fatal = WorkerRetirementError('All-In-One still owns scratch')
            def child(command, **options):
                request = Path(command[-1])
                retained.append(request)
                held.append(os.dup(process_lock_descriptor(request.parent/'.stemwise-worker.lock')))
                (request.parent/'pending.private.json').write_bytes(b'live result')
                raise fatal
            try:
                with patch.object(analyzers, '_allin1_python', return_value=python), \
                        patch.object(analyzers, 'run_process_tree', side_effect=child) as process:
                    with self.assertRaises(WorkerRetirementError) as raised:
                        analyzers.analyze_allin1_many([(path, 1000) for path in paths], project_dir=root, timeout_seconds=5)
                self.assertIs(raised.exception, fatal)
                self.assertEqual(process.call_count, 1)
                request = retained[0]
                data = json.loads(request.read_bytes())
                self.assertTrue(all(Path(row['audio']).exists() for row in data['inputs']))
                self.assertEqual((request.parent/'pending.private.json').read_bytes(), b'live result')
            finally:
                for descriptor in held:
                    os.close(descriptor)

    def test_timeout_result_callback_rejection_stops_restart_and_propagates(self):
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            paths, python = self.fixture(root)
            def child(command, **kwargs):
                request = json.loads(Path(command[-1]).read_text(encoding='utf-8'))
                (Path(command[-1]).parent/'batch-active.json').write_text(json.dumps({
                    'requestId': request['requestId'], 'index': 0, 'usedSeconds': {'0': .1, '1': .1}}), encoding='utf-8')
                return SimpleNamespace(returncode=-signal.SIGALRM, stderr='native deadline context', stdout='')
            def callback(index, result):
                raise RuntimeError('timeout publication rejected')
            with patch.object(analyzers, '_allin1_python', return_value=python), \
                    patch.object(analyzers, 'run_process_tree', side_effect=child) as process:
                with self.assertRaisesRegex(RuntimeError, 'timeout publication rejected'):
                    analyzers.analyze_allin1_many([(path, 1000) for path in paths], project_dir=root,
                        timeout_seconds=5, on_result=callback)
            self.assertEqual(process.call_count, 1)
            self.assertFalse(any(root.glob('left/allin1-*')))

    def test_first_native_timeout_retires_tree_then_runs_unfinished_peer(self):
        from allin1_fixtures import worker_fixture
        for stage in ('first-demix', 'first-inference', 'last-demix', 'last-inference'):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory(
                    dir=PROJECT.parents[1]/'Test suite/work') as name:
                root = Path(name)
                paths, _ = self.fixture(root)
                driver, environment = worker_fixture(root, stage, native_seconds=8)
                native_pids = []
                commands = []
                outputs = []
                def launch(command, **kwargs):
                    self.assertEqual(command[1:4], ['-B', '-m', 'stemwise_engine.allin1_worker'])
                    command = [sys.executable, '-B', str(driver), *command[4:]]
                    kwargs['env'] = environment
                    for native in native_pids:
                        self.assertFalse(Path(f'/proc/{native}').exists(), 'Previous worker tree must retire before restarting')
                    try:
                        completed = run_process_tree(command, **kwargs)
                    except Exception as error:
                        outputs.append((type(error).__name__, getattr(error, 'stdout', None), getattr(error, 'stderr', None)))
                        raise
                    outputs.append(completed.stderr)
                    outputs.extend(path.read_text(encoding='utf-8') for path in Path(command[-1]).parent.glob('failure-*.private.txt'))
                    commands.append(command)
                    for line in completed.stdout.splitlines():
                        if line.startswith('NATIVE_CHILD_PID='):
                            native_pids.append(int(line.split('=')[1]))
                    return completed
                with patch.object(analyzers, '_allin1_python', return_value=Path(sys.executable)), \
                        patch.object(analyzers, 'run_process_tree', side_effect=launch):
                    results = analyzers.analyze_allin1_many([(path, 1000) for path in paths],
                        project_dir=root, timeout_seconds=5)
                expected = ['unavailable', 'complete'] if stage.startswith('first') else ['complete', 'unavailable']
                self.assertEqual([row[0]['status'] for row in results], expected, (results, outputs))
                self.assertEqual(len(commands), 1 if stage == 'last-inference' else 2)
                self.assertTrue(native_pids)
                for native in native_pids:
                    self.assertFalse(Path(f'/proc/{native}').exists())
                events = (root/'phase-events.txt').read_text(encoding='utf-8').splitlines()
                for index in range(2):
                    name = f'input-{index:06d}'
                    self.assertEqual(events.count('demix '+name), 1, events)
                    if stage.endswith('inference'):
                        self.assertEqual(events.count('spec '+name), 1, events)
                        self.assertEqual(events.count('inference '+name), 1, events)

    def test_retirement_refusal_propagates_instead_of_completing_retained_rows(self):
        from stemwise_engine.common import WorkerRetirementError
        for streaming in (False, True):
            with self.subTest(streaming=streaming), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                paths, python = self.fixture(root)
                refused = threading.Event()
                published = threading.Event()
                callbacks = []
                def callback(index, row):
                    callbacks.append((index, refused.is_set()))
                    published.set()
                def child(args, **kwargs):
                    self.publish(args, 0)
                    if streaming:
                        self.assertTrue(published.wait(2))
                    refused.set()
                    raise WorkerRetirementError('Owned command workers could not be retired')
                with patch.object(analyzers, '_allin1_python', return_value=python), \
                        patch.object(analyzers, 'run_process_tree', side_effect=child):
                    with self.assertRaises(WorkerRetirementError):
                        analyzers.analyze_allin1_many([(path,1000) for path in paths],
                            project_dir=root, timeout_seconds=5, on_result=callback if streaming else None)
                self.assertFalse(any(after_refusal for _, after_refusal in callbacks))

    def test_native_inference_cannot_borrow_the_next_songs_deadline(self):
        self.check_native_deadline('inference')

    def test_native_demix_cannot_borrow_the_next_songs_deadline(self):
        self.check_native_deadline('demix')

    def check_native_deadline(self, stage):
        from allin1_fixtures import worker_fixture
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            paths, _ = self.fixture(root)
            request_path = root / 'request.json'
            request_path.write_text(json.dumps({'requestId': 'current', 'timeoutSeconds': 5,
                'inputs': [{'index': index, 'audio': str(path), 'source': str(path), 'durationMs': 1000}
                           for index, path in enumerate(paths)]}), encoding='utf-8')
            driver, environment = worker_fixture(root, stage, native_seconds=8)
            completed = run_process_tree([sys.executable, '-B', str(driver), 'batch',
                '--timeout-seconds', '5', str(request_path)], timeout=9, text=True,
                capture_output=True, env=environment)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn('STAGE_STARTED=', completed.stdout, completed.stderr)
            stage_started = float(completed.stdout.split('STAGE_STARTED=')[1].splitlines()[0])
            self.assertLess(time.monotonic() - stage_started, 5.8)
            native_pid = int(completed.stdout.split('NATIVE_CHILD_PID=')[1].splitlines()[0])
            self.assertFalse(Path(f'/proc/{native_pid}').exists(), 'Native worker must retire before return')
            self.assertTrue(all('result' not in json.loads(path.read_text(encoding='utf-8'))
                                for path in root.glob('result-*.json')))
            if stage == 'demix':
                self.assertFalse((root / 'demix-0.json').exists())
            else:
                self.assertEqual(-signal.SIGALRM, completed.returncode)

    def test_shared_setup_has_one_song_budget_and_cannot_publish_completion(self):
        from allin1_fixtures import worker_fixture
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            paths, _ = self.fixture(root)
            driver, environment = worker_fixture(root, 'setup')
            request = root / 'request.json'
            request.write_text(json.dumps({'requestId': 'shared-setup', 'timeoutSeconds': 1,
                'inputs': [{'index': index, 'source': str(path), 'audio': str(path), 'durationMs': 1000}
                           for index, path in enumerate(paths)]}), encoding='utf-8')
            completed = run_process_tree([sys.executable, '-B', str(driver), 'batch',
                '--timeout-seconds', '1', str(request)], env=environment, timeout=5,
                capture_output=True, text=True)
            self.assertEqual(completed.returncode, -signal.SIGALRM, completed.stderr)
            self.assertIn('STAGE_STARTED=', completed.stdout, completed.stderr)
            started = float(completed.stdout.split('STAGE_STARTED=')[1].splitlines()[0])
            self.assertLess(time.monotonic() - started, 1.8)
            self.assertEqual(list(root.glob('result-*.json')), [])
            self.assertNotIn('STEMWISE_RESULT=', completed.stdout)

    def fixture(self, root):
        paths = [root / 'left' / 'audio.wav', root / 'right' / 'audio.wav']
        for index, path in enumerate(paths):
            path.parent.mkdir()
            path.write_bytes(bytes([index]))
        python = root / 'python'
        python.touch()
        return paths, python

    @staticmethod
    def publish(args, index, *, wrong_source=False):
        request_path = Path(args[-1])
        request = json.loads(request_path.read_text())
        item = request['inputs'][index]
        row = {
            'requestId': request['requestId'], 'index': index,
            'source': 'wrong.wav' if wrong_source else item['source'],
            'durationMs': item['durationMs'],
            'result': {'version': 'test', 'bpm': 120, 'beats': [0, .5, 1.0],
                       'downbeats': [0], 'segments': [{'start': 0, 'end': 1.0, 'label': str(index)}]},
        }
        destination = request_path.parent / f'result-{index}.json'
        temporary = destination.with_suffix('.tmp')
        temporary.write_text(json.dumps(row))
        temporary.replace(destination)
        return request

    def test_completed_input_is_published_before_later_work_returns(self):
        published = threading.Event()
        callbacks = []
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            paths, python = self.fixture(root)
            def child(args, **kwargs):
                self.publish(args, 0)
                self.assertTrue(published.wait(2), 'Completed first input must survive a blocked second input')
                return SimpleNamespace(returncode=1, stdout='', stderr='second track failed')
            def callback(index, result):
                callbacks.append((index, result[0]['status']))
                if index == 0:
                    published.set()
            with patch.object(analyzers, '_allin1_python', return_value=python), \
                    patch.object(analyzers, 'run_process_tree', side_effect=child):
                results = analyzers.analyze_allin1_many(
                    [(path, 1000) for path in paths], project_dir=root, timeout_seconds=10,
                    on_result=callback)
            self.assertEqual(callbacks, [(0, 'complete'), (1, 'unavailable')])
            self.assertEqual([row[0]['status'] for row in results], ['complete', 'unavailable'])
            self.assertEqual([], [scratch for path in paths for scratch in path.parent.glob('allin1-*')])

    def test_callback_failure_retires_monitor_after_child_and_cleans_scratch(self):
        attempted = threading.Event()
        retired = threading.Event()
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            paths, python = self.fixture(root)
            def child(args, **kwargs):
                self.publish(args, 0)
                self.assertTrue(attempted.wait(2))
                retired.set()
                return SimpleNamespace(returncode=0, stdout='', stderr='')
            def callback(index, result):
                attempted.set()
                raise RuntimeError('publication rejected')
            with patch.object(analyzers, '_allin1_python', return_value=python), \
                    patch.object(analyzers, 'run_process_tree', side_effect=child):
                with self.assertRaisesRegex(RuntimeError, 'publication rejected'):
                    analyzers.analyze_allin1_many(
                        [(path, 1000) for path in paths], project_dir=root, timeout_seconds=10,
                        on_result=callback)
            self.assertTrue(retired.is_set())
            self.assertEqual([], [scratch for path in paths for scratch in path.parent.glob('allin1-*')])
            self.assertFalse(any(thread.name == 'stemwise-allin1-results' for thread in threading.enumerate()))

    def test_results_bind_exact_sources_despite_reordering_and_matching_basenames(self):
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            paths, python = self.fixture(root)
            def child(args, **kwargs):
                request = self.publish(args, 1)
                self.publish(args, 0)
                aliases = [item['audio'] for item in request['inputs']]
                self.assertEqual(len(set(Path(path).stem for path in aliases)), 2)
                self.assertEqual(kwargs['timeout'], 20)
                return SimpleNamespace(returncode=0, stdout='', stderr='')
            with patch.object(analyzers, '_allin1_python', return_value=python), \
                    patch.object(analyzers, 'run_process_tree', side_effect=child):
                results = analyzers.analyze_allin1_many(
                    [(paths[0], 750), (paths[1], 1200)], project_dir=root, timeout_seconds=10)
            self.assertEqual([result[0]['sections'][0]['label'] for result in results], ['0', '1'])
            self.assertEqual(results[0][0]['sections'][0]['endMs'], 750)
            self.assertEqual(results[1][0]['sections'][0]['endMs'], 1000)
            self.assertEqual([], [scratch for path in paths for scratch in path.parent.glob('allin1-*')],
                             'No separated or spectrogram scratch survives')
            self.assertEqual([path.read_bytes() for path in paths], [b'\x00', b'\x01'])

    def test_later_failure_preserves_only_independently_completed_song(self):
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            paths, python = self.fixture(root)
            def child(args, **kwargs):
                self.publish(args, 0)
                return SimpleNamespace(returncode=1, stdout='', stderr='second track failed')
            with patch.object(analyzers, '_allin1_python', return_value=python), \
                    patch.object(analyzers, 'run_process_tree', side_effect=child):
                results = analyzers.analyze_allin1_many(
                    [(path, 1000) for path in paths], project_dir=root, timeout_seconds=10)
            self.assertEqual(results[0][0]['status'], 'complete')
            self.assertEqual(results[1][0]['status'], 'unavailable')
            self.assertEqual(results[1][2]['status'], 'failed')

    def test_mismatched_row_and_zero_exit_without_result_cannot_report_completion(self):
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            paths, python = self.fixture(root)
            def child(args, **kwargs):
                self.publish(args, 0, wrong_source=True)
                return SimpleNamespace(returncode=0, stdout='', stderr='')
            with patch.object(analyzers, '_allin1_python', return_value=python), \
                    patch.object(analyzers, 'run_process_tree', side_effect=child):
                results = analyzers.analyze_allin1_many(
                    [(path, 1000) for path in paths], project_dir=root, timeout_seconds=10)
            self.assertEqual([result[0]['status'] for result in results], ['unavailable', 'unavailable'])

    def test_invalid_admission_does_not_start_models(self):
        with patch.object(analyzers, 'run_process_tree') as child:
            for inputs, timeout in [([], 10), ([(Path('missing'), 1000)], 10),
                                    ([(Path(__file__), True)], 10), ([(Path(__file__), 1000)], True)]:
                with self.assertRaises(ValueError):
                    analyzers.analyze_allin1_many(inputs, project_dir=PROJECT, timeout_seconds=timeout)
            child.assert_not_called()

    def test_cancellation_propagates_and_removes_only_owned_scratch(self):
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            paths, python = self.fixture(root)
            with patch.object(analyzers, '_allin1_python', return_value=python), \
                    patch.object(analyzers, 'run_process_tree', side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    analyzers.analyze_allin1_many(
                        [(path, 1000) for path in paths], project_dir=root, timeout_seconds=10,
                        on_result=lambda index, result: self.fail('Cancelled input was published'))
            self.assertEqual([], [scratch for path in paths for scratch in path.parent.glob('allin1-*')])
            self.assertEqual([path.read_bytes() for path in paths], [b'\x00', b'\x01'])
            self.assertFalse(any(thread.name == 'stemwise-allin1-results' for thread in threading.enumerate()))

    def run_worker_with_one_bad_input(self, *, second_spent=0.0, bad_demix=False):
        with tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            paths, _ = self.fixture(root)
            request = {'requestId': 'current', 'timeoutSeconds': 20, 'inputs': [
                {'audio': str(path), 'source': str(path), 'index': index, 'durationMs': 1000}
                for index, path in enumerate(paths)]}
            request_path = root / 'request.json'
            request_path.write_text(json.dumps(request))
            # Upstream's stem-only names require the adapter's distinct aliases.
            request['inputs'][1]['audio'] = str(paths[1].with_name('second.wav'))
            Path(request['inputs'][1]['audio']).write_bytes(b'1')
            request_path.write_text(json.dumps(request))
            module = types.ModuleType('allin1.analyze')
            calls = []
            def extract(demixed, spec_dir, multiprocess):
                if demixed[0].name == 'second':
                    raise RuntimeError('bad spectrogram')
                return [spec_dir / 'first.npy']
            module.extract_spectrograms = extract
            module.load_pretrained_model = lambda **kwargs: calls.append('ensemble') or object()
            module.run_inference = lambda **kwargs: SimpleNamespace(
                path=kwargs['path'], bpm=120, beats=[0, .5], downbeats=[0],
                segments=[SimpleNamespace(start=0, end=1, label='verse')])
            def analyze(paths, **kwargs):
                paths.sort(reverse=True)
                demixed = module.demix(paths, kwargs['demix_dir'], kwargs['device'])
                specs = module.extract_spectrograms(demixed, kwargs['spec_dir'], False)
                model = module.load_pretrained_model(model_name='harmonix-all', device='cpu')
                return [module.run_inference(path=path, spec_path=spec, model=model)
                        for path, spec in zip(paths, specs)]
            package = SimpleNamespace(analyze=analyze)
            typings = types.ModuleType('allin1.typings')
            typings.AnalysisResult = lambda **kwargs: SimpleNamespace(**kwargs)
            def child(args, **kwargs):
                child_request = json.loads(Path(args[-2]).read_text())
                destination = Path(args[-1])
                for item in child_request['inputs']:
                    output = destination / 'htdemucs' / Path(item['audio']).stem
                    output.mkdir(parents=True, exist_ok=True)
                    for stem in ['bass', 'drums', 'other', 'vocals']:
                        (output / f'{stem}.wav').write_bytes(b'pcm')
                    status = '{' if bad_demix and item['index'] == 1 else json.dumps(
                        {'ok': True, 'seconds': second_spent if item['index'] == 1 else 0,
                         'requestId': child_request['requestId'], **item})
                    (root / f'demix-{item["index"]}.json').write_text(status)
                return SimpleNamespace(returncode=0)
            began = time.monotonic()
            with patch.dict(sys.modules, {'allin1.analyze': module, 'allin1.typings': typings}), \
                    patch('signal.signal'), \
                    patch('subprocess.run', side_effect=child), \
                    patch('importlib.metadata.version', return_value='test'):
                allin1_worker.run_batch(request_path, began, package)
            rows = [json.loads((root / f'result-{index}.json').read_text()) for index in range(2)]
            self.assertIn('result', rows[0], 'A completed demix must retain its own inference budget')
            self.assertEqual(rows[0]['result']['segments'][0]['label'], 'verse')
            self.assertEqual(rows[0]['source'], str(paths[0]))
            self.assertNotIn('result', rows[1])
            private = root/'failure-1.private.txt'
            self.assertTrue(private.is_file())
            self.assertLessEqual(private.stat().st_size, 12000)
            if not bad_demix and second_spent == 0:
                self.assertIn('bad spectrogram', private.read_text(encoding='utf-8'))
            self.assertEqual(calls, ['ensemble'], 'Only one ensemble is loaded')
            return rows

    def test_worker_keeps_good_input_when_another_preparation_fails(self):
        """Exercise the batch worker hooks; substitute only heavy model APIs."""
        rows = self.run_worker_with_one_bad_input()
        self.assertEqual(rows[1]['error'], 'RuntimeError')

    def test_worker_does_not_lend_another_songs_budget_to_an_expired_input(self):
        rows = self.run_worker_with_one_bad_input(second_spent=25.0)
        self.assertEqual(rows[1]['error'], 'TimeoutError')

    def test_broken_demix_receipt_does_not_discard_the_other_songs_work(self):
        rows = self.run_worker_with_one_bad_input(bad_demix=True)
        self.assertEqual(rows[1]['error'], 'RuntimeError')



if __name__ == '__main__':
    unittest.main()
