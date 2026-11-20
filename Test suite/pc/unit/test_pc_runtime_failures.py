"""Retirement and retained-audio control flow, with expensive boundaries replaced."""
from contextlib import contextmanager, redirect_stderr, nullcontext
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from stemwise_engine.common import WorkerRetirementError, process_lock_descriptor
from stemwise_engine import environment_locks, provider_operations, ytdlp_update
from stemwise_media import save_download
from stemwise_media.transfer_budget import TransferBudget

ROOT = Path(__file__).resolve().parents[3]


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class PcRuntimeFailureTests(unittest.TestCase):
    def test_pipeline_skip_admission_observes_current_execution_and_preserves_stored_result(self):
        import copy
        from stemwise_engine import pipeline
        from stemwise_engine.common import sha256_file
        from test_pipeline_hardening import package_config, VIDEO_ID
        from test_packages import write_child
        execution = [{'main':{'device':'cpu', 'torch':'fixture'}}]
        def producer(**kwargs):
            return {'numericalProducer':{'fixture':'v1'}, 'acousticRuntime':{
                **({'execution':copy.deepcopy(execution[0])} if kwargs.get('observe_execution') else {})}}
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name, \
                patch.object(pipeline, 'collect_provenance', side_effect=producer):
            config = package_config(Path(name))
            owner = pipeline.StemwisePipeline(config)
            child = owner.child_path(VIDEO_ID)
            write_child(child, VIDEO_ID, config)
            state = owner._new_state({'videoId':VIDEO_ID}, 'fixture')
            state.update(status='complete', childSha256=sha256_file(child))
            owner.save_checkpoint(state)
            before = owner.checkpoint_path(VIDEO_ID).read_bytes()
            for device, reusable in (('cpu', True), ('cuda', False), ('cpu', True)):
                execution[0] = {'main':{'device':device, 'torch':'fixture'}}
                fresh = pipeline.StemwisePipeline(config)
                self.assertEqual(fresh._can_skip({'videoId':VIDEO_ID}, force=False,
                    lyrics={}, owner_lyrics_sha256='fixture'), reusable)
                self.assertEqual(fresh.checkpoint_path(VIDEO_ID).read_bytes(), before)

    def test_stored_pipeline_and_native_checkpoint_readback_never_observe_a_gpu(self):
        from stemwise_engine import pipeline, recording_analysis, recording_source
        from stemwise_engine.provenance import provenance_fingerprint
        execution = {'main':{'device':'cuda', 'torch':'fixture'}}
        base = {'numericalProducer':{}, 'acousticRuntime':{'packages':{}}}
        stored = {**base, 'acousticRuntime':{**base['acousticRuntime'], 'execution':execution}}
        owner = pipeline.StemwisePipeline.__new__(pipeline.StemwisePipeline)
        owner.provenance, owner.provenance_sha256, owner.allin1_python, owner.config = {}, '', Path('unused'), {}
        state = {'provenanceSha256':provenance_fingerprint(stored), 'acousticExecution':execution}
        with patch.object(pipeline, 'collect_provenance', return_value=base), \
                patch.object(owner, '_ensure_provenance', side_effect=AssertionError('readback observed GPU')):
            self.assertEqual(owner._checkpoint_producer(state), stored)
        source = recording_source.RecordingSource.__new__(recording_source.RecordingSource)
        source.recording = SimpleNamespace(identity={'sha256':'a'*64})
        checkpoint = {'recordingInput':source.recording.identity,
            'acousticProducerIdentity':{'engineVersion':2, 'runtime':{'execution':execution}}}
        with patch.object(recording_analysis, 'acoustic_producer_identity', return_value={'engineVersion':2, 'runtime':{}}) as identity:
            self.assertTrue(source.checkpoint_matches(owner, checkpoint))
            identity.assert_called_once_with(owner.config, observe_execution=False)

    def test_numerical_adapters_report_their_selected_device_on_actual_results(self):
        from stemwise_engine import analyzers, provenance
        from test_analyzers import LABELS
        for selected in ('cpu', 'cuda'):
            with self.subTest(device=selected):
                execution = {'device':selected, 'torch':'fixture'}
                tensor = Mock()
                tensor.to.return_value = tensor
                tensor.unsqueeze.return_value = tensor
                torch = SimpleNamespace(cuda=SimpleNamespace(is_available=lambda:selected == 'cuda'),
                    hub=SimpleNamespace(get_dir=lambda:'/fixture'), device=lambda value:value,
                    inference_mode=nullcontext, zeros=Mock(), mean=Mock())
                factory = Mock(return_value=lambda audio:([.1, .5], [.1]))
                torch.mean.return_value.detach.return_value.cpu.return_value.reshape.return_value.tolist.return_value = [1.0]*24
                skey = SimpleNamespace(DEFAULT_CHECKPOINT_PATH='/fixture/skey.pt', key_map=LABELS,
                    load_audio=Mock(return_value=tensor), load_checkpoint=Mock(return_value={'audio':{'sr':48000}}),
                    load_model_components=Mock(return_value=(Mock(), Mock(), Mock())))
                with patch.dict(sys.modules, {'torch':torch, 'beat_this.inference':SimpleNamespace(File2Beats=factory),
                        'skey.key_detection':skey}), patch.object(Path, 'is_file', return_value=True), \
                        patch.object(analyzers, 'package_version', return_value='fixture'), \
                        patch.object(analyzers, '_full_window_reason', return_value=None), \
                        patch.object(analyzers, '_verify_file_sha256'), \
                        patch.object(analyzers, '_BEAT_THIS', None), patch.object(analyzers, '_BEAT_THIS_EXECUTION', None), \
                        patch.object(analyzers, '_SKEY_COMPONENTS', None), \
                        patch.object(analyzers, '_interpret_skey_scores', return_value={'status':'complete'}), \
                        patch('soundfile.info', return_value=SimpleNamespace(frames=48000, samplerate=48000)), \
                        patch.object(provenance, 'numerical_execution_identity', side_effect=lambda module,device:
                            execution if device == selected else self.fail('wrong selected device')):
                    beat = analyzers.analyze_beat_this(Path('fixture.wav'), 1000)[2]
                    again = analyzers.analyze_beat_this(Path('fixture.wav'), 1000)[2]
                    key = analyzers.analyze_skey(Path('fixture.wav'))[1]
                factory.assert_called_once_with(checkpoint_path='final0', device=selected, dbn=False)
                for tool in (beat, again, key):
                    self.assertEqual(tool['status'], 'complete', tool)
                    self.assertEqual(json.loads(tool['profile']), execution)

    def test_measurement_decoder_retains_inputs_after_fatal_retirement(self):
        import hashlib
        import zipfile
        from stemwise_engine import lyrics_qualification, original_delivery
        for fatal in (False, True):
            with self.subTest(fatal=fatal), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                bundle = root/'complete.zip'
                media = b'owned original samples'
                with zipfile.ZipFile(bundle, 'w') as archive:
                    archive.writestr('original.flac', media)
                original = {'name':'original.flac', 'sha256':hashlib.sha256(media).hexdigest()}
                reference = {'recordingSha256':original['sha256'], 'durationMs':1000}
                failure = WorkerRetirementError('measurement-unretired') if fatal else ValueError('retired-failure')
                inputs = []
                def decode(path):
                    self.assertIsNotNone(process_lock_descriptor(path.parent/'.stemwise-worker.lock'))
                    inputs.append(path)
                    raise failure
                descriptor = {'path':bundle.name, 'sha256':hashlib.sha256(bundle.read_bytes()).hexdigest()}
                with patch.object(original_delivery, 'read_bundle', return_value=({}, None, original)), \
                        patch.object(original_delivery, '_decoded_duration', side_effect=decode):
                    if fatal:
                        with self.assertRaises(WorkerRetirementError) as raised:
                            lyrics_qualification._measurement_export(root, descriptor, reference, {}, {})
                        self.assertIs(raised.exception, failure)
                    else:
                        self.assertFalse(lyrics_qualification._measurement_export(root, descriptor, reference, {}, {})[0]['valid'])
                self.assertEqual(inputs[0].exists(), fatal)
                if fatal: self.assertEqual(inputs[0].read_bytes(), media)

    def test_benchmark_retains_its_overlay_and_requests_after_fatal_retirement(self):
        import hashlib
        from stemwise_engine import common
        # Load consumers before substituting their imported process boundary.
        for module in ('lyrics_align_pc', 'alignment_worker_session'):
            __import__('stemwise_engine.' + module)
        spec = importlib.util.spec_from_file_location('isolated_alignment_benchmark',
            ROOT/'Test suite/pc/performance/benchmark_alignment_session.py')
        benchmark = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(benchmark)
        for fatal in (False, True):
            with self.subTest(fatal=fatal), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                audio, request, emissions, retained = [root/filename for filename in ('audio.wav','request.json','emissions.pt','source.json')]
                audio.write_bytes(b'owned audio')
                request.write_text(json.dumps({'durationMs':1000}), encoding='utf-8')
                emissions.write_bytes(b'owned emissions')
                retained.write_text(json.dumps({'requestSha256':hashlib.sha256(b'{"durationMs":1000}').hexdigest(),
                    'provenance':{'audio':{'sha256':benchmark.digest_file(audio)}}, 'durationMs':1000}), encoding='utf-8')
                fixture = root/'fixture.json'
                fixture.write_text(json.dumps({'formatVersion':1, 'worker':'mms', 'audio':str(audio),
                    'request':str(request), 'emissions':str(emissions), 'sourceCache':str(retained),
                    'audioSha256':benchmark.digest_file(audio), 'emissionsSha256':benchmark.digest_file(emissions),
                    'requestSha256':hashlib.sha256(b'{"durationMs":1000}').hexdigest(),
                    'durationMs':1000, 'emissionCacheKey':'a'*64}), encoding='utf-8')
                work = root/'benchmark'
                failure = WorkerRetirementError('benchmark-unretired') if fatal else ValueError('retired-failure')
                def run(*args, **kwargs):
                    self.assertIsNotNone(process_lock_descriptor(work/'.stemwise-worker.lock'))
                    self.assertEqual((work/'0/0/request.json').read_bytes(), request.read_bytes())
                    raise failure
                with patch.object(sys, 'argv', ['benchmark', '--fixture', str(fixture), '--work', str(work)]), \
                        patch.object(benchmark, 'require_production_cache'), patch.object(common, 'run_process_tree', side_effect=run):
                    with self.assertRaises(type(failure)) as raised:
                        benchmark.main()
                    self.assertIs(raised.exception, failure)
                self.assertEqual(work.exists(), fatal)
                if fatal:
                    self.assertEqual((work/'0/0/request.json').read_bytes(), request.read_bytes())
                    self.assertTrue((work/'src/stemwise_engine/ctc_worker.py').is_file())

    def test_installer_retains_worker_inputs_only_when_retirement_is_unconfirmed(self):
        for fatal in (False, True):
            with self.subTest(fatal=fatal), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                builder = Path(name)/'builder'
                builder.mkdir()
                failure = WorkerRetirementError('installer-unretired') if fatal else ValueError('retired-failure')
                inputs = []
                def run(command, **kwargs):
                    if '--version' in command:
                        return SimpleNamespace(stdout='Python 3.10.18')
                    path = Path(kwargs['env']['TMPDIR'])/'worker-input'
                    path.write_bytes(b'owned installer inputs')
                    self.assertIsNotNone(process_lock_descriptor(path.parent/'.stemwise-worker.lock'))
                    inputs.append(path)
                    raise failure
                with patch.object(environment_locks, 'validate_plan', return_value=(
                        {'pythonVersion':'3.10.18'}, {}, {'numpy':'numpy==2.2.6\n'}, [])):
                    with self.assertRaises(type(failure)) as raised:
                        environment_locks.install('main', sys.executable, builder, run=run)
                self.assertIs(raised.exception, failure)
                self.assertEqual(inputs[0].exists(), fatal)
                if fatal:
                    self.assertEqual(inputs[0].read_bytes(), b'owned installer inputs')

    def test_provider_retirement_survives_child_protocol_and_optional_update(self):
        from stemwise_engine import youtube
        fatal = WorkerRetirementError('exact worker retirement diagnostic')
        child_stderr = io.StringIO()
        request = json.dumps({'deno':'unused', 'probeUrl':'unused', 'scratchParent':'unused'})
        with patch.object(sys, 'argv', ['provider', '--ytdlp-update', request]), \
                patch.object(ytdlp_update, '_update_ytdlp', side_effect=fatal), redirect_stderr(child_stderr):
            exit_code = provider_operations._worker()
        @contextmanager
        def owner(*args, **kwargs):
            yield SimpleNamespace(returncode=exit_code,
                communicate=lambda **kw: (b'', child_stderr.getvalue().encode('utf-8')))
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name, \
                patch.object(ytdlp_update, 'RUNTIME', Path(name)), \
                patch.object(provider_operations, 'owned_process_tree', owner), \
                patch.object(youtube, 'activate_installed') as activate:
            with self.assertRaisesRegex(WorkerRetirementError, 'exact worker retirement diagnostic'):
                youtube._ydl({})
            activate.assert_not_called()

    def test_fresh_youtube_import_propagates_fatal_but_allows_optional_failure(self):
        from stemwise_engine import youtube
        for failure in (WorkerRetirementError('activation-unretired'), ValueError('optional-invalid')):
            spec = importlib.util.spec_from_file_location('isolated_youtube_activation', youtube.__file__)
            module = importlib.util.module_from_spec(spec)
            with patch.object(ytdlp_update, 'activate_installed', side_effect=failure), redirect_stderr(io.StringIO()):
                if isinstance(failure, WorkerRetirementError):
                    with self.assertRaises(WorkerRetirementError) as raised:
                        spec.loader.exec_module(module)
                    self.assertIs(raised.exception, failure)
                else:
                    spec.loader.exec_module(module)
                    self.assertTrue(callable(module._ydl))

    def test_reused_audio_validation_obeys_hash_decode_and_final_budget(self):
        import hashlib
        import soundfile
        from stemwise_engine.provider_operations import ProviderCancelled
        for phase in ('hash', 'decode-open', 'decode-block', 'decode-end', 'complete'):
            for expired in (False, True):
                with self.subTest(phase=phase, expired=expired), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                    base = Path(name)
                    audio = base/'song.flac'
                    audio.write_bytes(b'fLaC'+b'a'*(1024*1024+3))
                    data = {'qobuzTrackId':11, 'file':audio.name, 'bytes':audio.stat().st_size,
                            'sha256':hashlib.sha256(audio.read_bytes()).hexdigest()}
                    (base/'download.json').write_text(json.dumps(data), encoding='utf-8')
                    before = (base/'download.json').read_bytes()
                    cancelled = [False]
                    deadline = [time.monotonic()+60]
                    budget = TransferBudget(deadline[0], cancelled=lambda: cancelled[0])
                    def stop():
                        if expired:
                            object.__setattr__(budget, 'deadline', time.monotonic()-1)
                        else:
                            cancelled[0] = True
                    class Decoder:
                        format, frames = 'FLAC', 2
                        def __init__(self, *args):
                            if phase == 'decode-open': stop()
                        def __enter__(self): return self
                        def __exit__(self, *args): pass
                        def blocks(self, **kwargs):
                            if phase == 'decode-block': stop()
                            yield [0, 0]
                            if phase == 'decode-end': stop()
                    original_open = Path.open
                    class HashReader:
                        def __init__(self, stream): self.stream = stream
                        def __enter__(self): return self
                        def __exit__(self, *args): self.stream.close()
                        def read(self, size):
                            result = self.stream.read(size)
                            if phase == 'hash' and size > 4: stop()
                            return result
                    def opened(path, *args, **kwargs):
                        stream = original_open(path, *args, **kwargs)
                        return HashReader(stream) if path == audio and args == ('rb',) else stream
                    with patch.object(Path, 'open', opened), patch.object(soundfile, 'SoundFile', Decoder):
                        if phase == 'complete':
                            result = save_download._save_download(11, 'https://eu.doubledouble.top/dl/Test', base, budget=budget)
                            self.assertEqual(result['status'], 'already_saved')
                        else:
                            with self.assertRaises(TimeoutError if expired else ProviderCancelled):
                                save_download._save_download(11, 'https://eu.doubledouble.top/dl/Test', base, budget=budget)
                    self.assertEqual((base/'download.json').read_bytes(), before)
                    self.assertEqual(hashlib.sha256(audio.read_bytes()).hexdigest(), data['sha256'])

    def test_doubledouble_retained_validation_obeys_its_requested_deadline(self):
        import hashlib
        import soundfile
        from stemwise_media import doubledouble
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            base = Path(name)
            audio = base/'song.flac'
            audio.write_bytes(b'fLaC retained source')
            metadata = {'qobuzTrackId':11, 'file':audio.name, 'bytes':audio.stat().st_size,
                        'sha256':hashlib.sha256(audio.read_bytes()).hexdigest()}
            receipt = base/'download.json'
            receipt.write_text(json.dumps(metadata), encoding='utf-8')
            before = receipt.read_bytes()
            now = [100.]
            class Decoder:
                format, frames = 'FLAC', 2
                def __init__(self, *args): now[0] = 102.
                def __enter__(self): return self
                def __exit__(self, *args): pass
                def blocks(self, **kwargs): yield [0, 0]
            with patch.object(doubledouble.time, 'monotonic', side_effect=lambda:now[0]), \
                    patch.object(soundfile, 'SoundFile', Decoder), \
                    patch.object(doubledouble, 'http_request', side_effect=AssertionError('retained reuse started network')):
                with self.assertRaises(TimeoutError):
                    doubledouble._download(11, base, job_url=None, timeout_seconds=1)
            self.assertEqual(receipt.read_bytes(), before)
            self.assertEqual(hashlib.sha256(audio.read_bytes()).hexdigest(), metadata['sha256'])


if __name__ == '__main__':
    unittest.main()
