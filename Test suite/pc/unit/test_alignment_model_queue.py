"""Emission preparation shares loaded models, never another song's lyric decisions."""
import hashlib
import json
import subprocess
import shutil
import sys
from pathlib import Path
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.analysis_queue as analysis_queue
from stemwise_engine.common import exclusive_process_lock, heavy_worker_lock_path


@unittest.skipUnless(sys.platform == 'linux', 'kernel-owned queue runs in WSL')
class AlignmentModelQueueTests(unittest.TestCase):
    def test_fatal_preparation_retains_request_output_and_aliases(self):
        import os
        import stemwise_engine.alignment_model_queue as adapter
        from stemwise_engine.common import WorkerRetirementError, process_lock_descriptor
        held, files = [], []
        options = [self.adapter_options(index) for index in range(2)]
        def worker(command, **kwargs):
            request = Path(command[command.index('--prepare-batch')+1])
            output = Path(command[command.index('--output')+1])
            output.write_bytes(b'pending worker output')
            files.extend((request, output))
            held.append(os.dup(process_lock_descriptor(request.parent.parent/'.stemwise-worker.lock')))
            return subprocess.CompletedProcess(command, 70, '', 'STEMWISE_WORKER_RETIREMENT_FAILED')
        try:
            with patch.object(adapter, 'run_process_tree', side_effect=worker) as execute:
                errors = self.run_adapters(options)
            self.assertEqual(execute.call_count, 1)
            self.assertEqual(set(errors), {0, 1})
            self.assertTrue(all(isinstance(error, WorkerRetirementError) for error in errors.values()))
            self.assertTrue(all(path.exists() for path in files))
            manifest = json.loads(files[0].read_bytes())
            self.assertTrue(all(Path(row['audio']).exists() for row in manifest['contexts']))
        finally:
            for descriptor in held:
                os.close(descriptor)

    def test_preparation_json_disposal_preserves_bound_cache_and_partial_worker_failure(self):
        import stemwise_engine.alignment_model_queue as adapter
        real_unlink = Path.unlink
        for index, (name, failed) in enumerate((name, failed)
                for name in ('prepare.request.json', 'prepare.output.json') for failed in (False, True)):
            with self.subTest(name=name, failed=failed):
                options = [self.adapter_options(index * 2 + offset) for offset in range(2)]
                def unlink(path, *args, **kwargs):
                    if path.name == name and path.is_file():
                        raise PermissionError('disposable preparation JSON is busy')
                    return real_unlink(path, *args, **kwargs)
                def worker(command, **kwargs):
                    return self.worker_reply(command, returncode=7 if failed else 0, truncate=failed)
                with patch.object(Path, 'unlink', unlink), \
                        patch.object(adapter, 'run_process_tree', side_effect=worker) as execute:
                    errors = self.run_adapters(options)
                self.assertEqual(execute.call_count, 1)
                self.assertEqual(len(errors), int(failed))
                for offset, request in enumerate(options):
                    if offset in errors:
                        self.assertEqual(errors[offset].returncode, 7)
                        self.assertIn('private-native-cause', errors[offset].stderr)
                        self.assertFalse(request['cache_path'].exists())
                    else:
                        self.assertEqual(request['cache_path'].read_bytes(),
                            b'owned:' + request['audio'].read_bytes())

    def test_unwritable_failure_diagnostic_keeps_the_successful_neighbor(self):
        contexts = [self.context(index) for index in range(2)]
        contexts[0][1]['diagnosticPath'] = str(self.project/'zero.prepare.failure.private.txt')
        def mixed(rows, *args):
            return [RuntimeError('preparation failed') if Path(row['audio']).read_bytes() == b'audio-0'
                    else self.backend([row], *args)[0] for row in rows]
        with patch.object(analysis_queue, 'atomic_write_bytes', side_effect=OSError('diagnostic disk full')):
            with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
                first = self.start(0, contexts[0], mixed)
                self.ready(1)
                second = self.start(1, contexts[1], mixed)
                self.ready(2)
            self.finish([first, second])
        self.assertEqual(set(self.errors), {0})
        self.assertIn('native-emission-preparation-failed', str(self.errors[0]))
        self.assertIn('preparation failed', self.errors[0].stderr)
        self.assertEqual(set(self.results), {1})
        self.assertEqual(contexts[1][2].read_bytes(), b'prepared:audio-1')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work')
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        self.calls, self.results, self.errors = [], {}, {}

    def context(self, index, *, compatibility='a' * 64):
        audio = self.project / f'{index}.wav'
        audio.write_bytes(f'audio-{index}'.encode())
        key = hashlib.sha256(audio.read_bytes()).hexdigest()
        destination = self.project / 'temporary' / str(index) / f'emissions-{key}.pt'
        preparation = {'mode': 'alignment-emissions', 'family': 'wav2vec2',
            'compatibilitySha256': compatibility, 'cacheKey': key,
            'cacheRoot': str(self.project / 'temporary'), 'cacheOutput': str(destination)}
        return audio, preparation, destination

    def backend(self, contexts, *_):
        self.calls.append([Path(row['audio']).read_bytes() for row in contexts])
        results = []
        for row in contexts:
            path = Path(row['cacheOutput'])
            path.write_bytes(b'prepared:' + Path(row['audio']).read_bytes())
            results.append({'durationMs': row['durationMs'], 'cacheKey': row['emissionCacheKey'],
                'cachePath': str(path), 'cacheSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'cacheByteCount': path.stat().st_size})
        return results

    def start(self, index, context, backend=None):
        audio, preparation, _ = context
        def run():
            try:
                self.results[index] = analysis_queue.analyze(audio, 1000, self.project, 30,
                    backend or self.backend, preparation=preparation)
            except Exception as error:
                self.errors[index] = error
        thread = threading.Thread(target=run)
        thread.start()
        self.addCleanup(lambda: thread.join(5))
        return thread

    def ready(self, count):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self.errors:
                self.fail(f'Queue admission failed: {self.errors!r}')
            if len(list((self.project / 'runtime/acoustic-queue').glob('*/request.json'))) == count:
                return
            time.sleep(.01)
        self.fail('Required emission preparations did not enter the real queue')

    def finish(self, threads):
        for thread in threads:
            thread.join(5)
            self.assertFalse(thread.is_alive())
        self.assertFalse(list((self.project / 'runtime/acoustic-queue').glob('*/request.json')))

    def batch_with_peer(self, context, backend):
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            first = self.start('subject', context, backend)
            self.ready(1)
            peer = self.start('peer', self.context('peer'), backend)
            self.ready(2)
        self.finish([first, peer])

    def test_two_demanded_views_share_one_backend_and_publish_their_exact_cache_before_ack(self):
        contexts = [self.context(index) for index in range(2)]
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            threads = [self.start(index, context) for index, context in enumerate(contexts)]
            self.ready(2)
        self.finish(threads)
        self.assertEqual(self.errors, {})
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(sorted(self.calls[0]), [b'audio-0', b'audio-1'])
        for index, (_, preparation, destination) in enumerate(contexts):
            self.assertEqual(destination.read_bytes(), b'prepared:audio-' + str(index).encode())
            self.assertEqual(self.results[index]['cacheKey'], preparation['cacheKey'])
            self.assertEqual(self.results[index]['cacheSha256'], hashlib.sha256(destination.read_bytes()).hexdigest())

    def test_lone_input_defers_without_preparation_child_or_claiming_a_numeric_cache(self):
        import stemwise_engine.alignment_model_queue as adapter
        audio, preparation, destination = self.context(0)
        with patch.object(analysis_queue, '_install_prepared', side_effect=AssertionError('Defer cannot publish')):
            result = analysis_queue.analyze(audio, 1000, self.project, 30,
                lambda *_: self.fail('Lone input started a redundant preparation worker'), preparation=preparation)
        self.assertEqual(result, {'durationMs': 1000, 'deferToAlignmentWorker': True})
        self.assertFalse(destination.exists())
        with patch.object(adapter, 'run_process_tree', side_effect=AssertionError('Lone extra model process')):
            options = self.adapter_options(1)
            adapter.prepare_emissions(**options)
        self.assertFalse(options['cache_path'].exists(), 'Only the actual alignment worker can produce its numerical result')

    def test_two_requested_aliases_still_prepare_once_and_publish_both_destinations(self):
        first, second = self.context(0), self.context(1)
        second[0].write_bytes(first[0].read_bytes())
        preparation = {**second[1], 'cacheKey': first[1]['cacheKey']}
        destination = second[2].with_name(first[2].name)
        preparation['cacheOutput'] = str(destination)
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            threads = [self.start(0, first), self.start(1, (second[0], preparation, destination))]
            self.ready(2)
        self.finish(threads)
        self.assertEqual(self.errors, {})
        self.assertEqual(self.calls, [[b'audio-0']])
        self.assertEqual(first[2].read_bytes(), b'prepared:audio-0')
        self.assertEqual(destination.read_bytes(), first[2].read_bytes())

    def test_different_model_runtime_or_language_identity_never_shares_a_batch(self):
        contexts = [self.context(index, compatibility=str(index) * 64) for index in range(2)]
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            threads = [self.start(index, context) for index, context in enumerate(contexts)]
            self.ready(2)
        self.finish(threads)
        self.assertEqual(self.errors, {})
        self.assertEqual(self.calls, [], 'Each incompatible lone request defers to its own alignment worker')
        for index in range(2):
            self.assertEqual(self.results[index], {'durationMs': 1000, 'deferToAlignmentWorker': True})
            self.assertFalse(contexts[index][2].exists())

    def test_modified_pending_destination_cannot_publish_before_original_owner_validation(self):
        contexts = [self.context(index) for index in range(2)]
        wrong = self.project / 'temporary/unrelated' / contexts[1][2].name
        with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})):
            first = self.start(0, contexts[0])
            self.ready(1)
            second = self.start(1, contexts[1])
            self.ready(2)
            for path in (self.project / 'runtime/acoustic-queue').glob('*/request.json'):
                request = json.loads(path.read_bytes())
                if request['audio'] == str(contexts[1][0]):
                    request['preparation']['cacheOutput'] = str(wrong)
                    path.write_text(json.dumps(request))
        self.finish([first, second])
        self.assertIn(1, self.errors)
        self.assertNotIn(0, self.errors)
        self.assertEqual(contexts[0][2].read_bytes(), b'prepared:audio-0')
        self.assertFalse(contexts[1][2].exists(), 'Rejected original request must not publish a cache')
        self.assertFalse(wrong.exists(), 'A rejected request cannot leave a trusted-looking cache anywhere')

    def test_changed_artifact_is_rejected_without_replacing_existing_cache(self):
        audio, preparation, destination = self.context(0)
        destination.parent.mkdir(parents=True)
        destination.write_bytes(b'prior-cache')
        def stale(contexts, *args):
            rows = self.backend(contexts, *args)
            Path(rows[0]['cachePath']).write_bytes(b'stale')
            return rows
        self.batch_with_peer((audio, preparation, destination), stale)
        self.assertIn('missing or stale', str(self.errors['subject']))
        self.assertNotIn('peer', self.errors)
        self.assertEqual(destination.read_bytes(), b'prior-cache')

    def test_unknown_mode_and_destination_escape_are_rejected_before_backend(self):
        audio, preparation, destination = self.context(0)
        for changed in ({'mode': 'anything'}, {'cacheOutput': str(self.project / destination.name)},
                        {'cacheOutput': str(destination.with_name('weights.pt'))}):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                analysis_queue.analyze(audio, 1000, self.project, 30, self.backend,
                                       preparation={**preparation, **changed})
        self.assertEqual(self.calls, [])
        self.assertFalse(destination.exists())

    def test_preparation_timeout_preserves_its_stage_identity_and_private_cause(self):
        audio, preparation, destination = self.context(0)
        diagnostic = self.project / 'job/ctc.prepare.failure.private.txt'
        preparation['diagnosticPath'] = str(diagnostic)
        def timed_out(*_):
            raise subprocess.TimeoutExpired('native-worker', 30, stderr=b'bounded-native-timeout')
        self.batch_with_peer((audio, preparation, destination), timed_out)
        self.assertIsInstance(self.errors['subject'], subprocess.TimeoutExpired)
        self.assertEqual(self.errors['subject'].cmd, 'native-emission-preparation')
        self.assertEqual(self.errors['subject'].timeout, 30)
        self.assertIn('bounded-native-timeout', diagnostic.read_text())
        self.assertFalse(destination.exists())

    def adapter_options(self, index=0):
        audio, preparation, destination = self.context(index)
        helper = self.project / 'src/mms_forced_alignment.py'
        helper.parent.mkdir(exist_ok=True)
        import stemwise_engine.alignment_model_queue as adapter
        for name in ('mms_forced_alignment.py', 'ctc_worker.py', 'acoustic_inputs.py',
                     'worker_protocol.py', 'worker_publication.py'):
            shutil.copyfile(Path(adapter.__file__).with_name(name), helper.with_name(name))
        return dict(audio=audio, duration_ms=1000,
            config={'projectDir': str(self.project), 'tempDir': preparation['cacheRoot']},
            timeout_seconds=30, python=Path(sys.executable), helper=helper, device='cpu',
            cache_key=preparation['cacheKey'], cache_path=destination,
            producer={'runtime': {'version': 'pinned'}, 'mmsModelSha256': 'a' * 64},
            diagnostic_path=self.project / f'job-{index}/mms.prepare.failure.private.txt')

    def worker_reply(self, command, *, returncode=0, truncate=False, swap=False):
        manifest = json.loads(Path(command[command.index('--prepare-batch') + 1]).read_bytes())
        output = Path(command[command.index('--output') + 1])
        rows = []
        contexts = manifest['contexts'][:1] if truncate else manifest['contexts']
        for context in contexts:
            artifact = Path(context['cacheOutput'])
            artifact.parent.mkdir(parents=True, exist_ok=True)
            artifact.write_bytes(b'owned:' + Path(context['audio']).read_bytes())
            rows.append({key: context[key] for key in ('contextId', 'audioSha256', 'durationMs', 'emissionCacheKey')})
            rows[-1].update(cacheSha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
                            cacheByteCount=artifact.stat().st_size)
        if swap:
            rows[0]['audioSha256'] = '0' * 64
        output.write_text(json.dumps({'formatVersion': 1,
            'batchSha256': hashlib.sha256(json.dumps(manifest, ensure_ascii=False, sort_keys=True,
                separators=(',', ':')).encode()).hexdigest(), 'contexts': rows}))
        return SimpleNamespace(returncode=returncode, stdout='', stderr='private-native-cause')

    def run_adapters(self, options):
        import stemwise_engine.alignment_model_queue as adapter
        errors, threads = {}, []
        def run(index):
            try:
                adapter.prepare_emissions(**options[index])
            except Exception as error:
                errors[index] = error
        with exclusive_process_lock(heavy_worker_lock_path(options[0]['config'])):
            for index in range(len(options)):
                thread = threading.Thread(target=run, args=(index,))
                threads.append(thread)
                thread.start()
                self.ready(index + 1)
        self.finish(threads)
        return errors

    def test_adapter_uses_bound_prepare_protocol_and_existing_cache_does_not_take_gpu(self):
        import stemwise_engine.alignment_model_queue as adapter
        options, peer = self.adapter_options(), self.adapter_options(1)
        with patch.object(adapter, 'run_process_tree', side_effect=lambda command, **_: self.worker_reply(command)) as worker:
            self.assertEqual(self.run_adapters([options, peer]), {})
            self.assertEqual(worker.call_count, 1)
            self.assertEqual(options['cache_path'].read_bytes(), b'owned:audio-0')
            with exclusive_process_lock(heavy_worker_lock_path(options['config'])):
                adapter.prepare_emissions(**options)
            self.assertEqual(worker.call_count, 1, 'Existing cache needs worker validation, no speculative inference')

    def test_legacy_inherited_model_lease_defers_to_single_actual_numerical_worker(self):
        import stemwise_engine.alignment_model_queue as adapter
        options = self.adapter_options()
        with exclusive_process_lock(heavy_worker_lock_path(options['config'])):
            with patch.object(adapter, 'run_process_tree', side_effect=AssertionError('Redundant preparation child')), \
                    patch.object(analysis_queue, 'analyze', side_effect=AssertionError('Legacy owner enqueued behind itself')):
                adapter.prepare_emissions(**options)
        self.assertFalse(options['cache_path'].exists())
        import stemwise_engine.common as common
        unrelated = self.project / 'unrelated.lock'
        unrelated.touch()
        with unrelated.open('r+') as handle:
            token = common._PROCESS_LOCKS.set(((heavy_worker_lock_path(options['config']), handle.fileno()),))
            try:
                with self.assertRaisesRegex(ValueError, 'differs from the heavy lock'):
                    adapter.prepare_emissions(**options)
            finally:
                common._PROCESS_LOCKS.reset(token)

    def test_adapter_rejects_swapped_binding_and_preserves_stderr_privately(self):
        import stemwise_engine.alignment_model_queue as adapter
        options, peer = self.adapter_options(), self.adapter_options(1)
        with patch.object(adapter, 'run_process_tree', side_effect=lambda command, **_: self.worker_reply(command, swap=True)):
            errors = self.run_adapters([options, peer])
        self.assertIn('native-emission-preparation-failed', str(errors[0]))
        self.assertNotIn('private-native-cause', str(errors[0]))
        self.assertIn('private-native-cause', options['diagnostic_path'].read_text())
        self.assertFalse(options['cache_path'].exists())

    def test_adapter_partial_worker_failure_preserves_only_completed_bound_cache(self):
        import stemwise_engine.alignment_model_queue as adapter
        options = [self.adapter_options(index) for index in range(2)]
        errors, threads, calls = {}, [], []
        def worker(command, **_):
            calls.append(command)
            return self.worker_reply(command, returncode=7, truncate=True)
        def run(index):
            try:
                adapter.prepare_emissions(**options[index])
            except Exception as error:
                errors[index] = error
        with patch.object(adapter, 'run_process_tree', side_effect=worker):
            with exclusive_process_lock(heavy_worker_lock_path(options[0]['config'])):
                for index in range(2):
                    thread = threading.Thread(target=run, args=(index,))
                    threads.append(thread)
                    thread.start()
                self.ready(2)
            self.finish(threads)
        self.assertEqual(len(calls), 1)
        self.assertEqual(len(errors), 1)
        complete = next(index for index in range(2) if index not in errors)
        failed = next(iter(errors))
        self.assertEqual(options[complete]['cache_path'].read_bytes(), b'owned:audio-' + str(complete).encode())
        self.assertFalse(options[failed]['cache_path'].exists())
        self.assertIn('private-native-cause', options[failed]['diagnostic_path'].read_text())

    def test_unestablished_retirement_never_admits_even_an_intact_prepared_cache(self):
        import stemwise_engine.alignment_model_queue as adapter
        from stemwise_engine.common import WorkerRetirementError
        for index, guardian in enumerate((False, True)):
            with self.subTest(guardian=guardian):
                options, peer = self.adapter_options(index * 2), self.adapter_options(index * 2 + 1)
                def worker(command, **_):
                    result = self.worker_reply(command, returncode=70)
                    if guardian:
                        raise RuntimeError('Owned command workers could not be retired')
                    return result
                with patch.object(adapter, 'run_process_tree', side_effect=worker):
                    errors = self.run_adapters([options, peer])
                    self.assertEqual(set(errors), {0, 1})
                    for error in errors.values():
                        if guardian:
                            self.assertEqual(str(error), 'native-emission-preparation-failed')
                        else:
                            self.assertIsInstance(error, WorkerRetirementError)
                        self.assertNotIn('private-native-cause', str(error))
                for request in (options, peer):
                    self.assertFalse(request['cache_path'].exists())
                    diagnostic = request['diagnostic_path'].read_text()
                    self.assertIn('could not be retired' if guardian else 'private-native-cause', diagnostic)


if __name__ == '__main__':
    unittest.main()
