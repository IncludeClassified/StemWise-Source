import copy
import builtins
from contextlib import ExitStack, redirect_stdout, redirect_stderr
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import traceback
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
import soundfile as sf
from stemwise_engine import nemo_lexical_candidate as lexical
from stemwise_engine import ctc_worker, worker_protocol, worker_runtime_probe
from stemwise_engine.alignment_phases import AudioIdentity
from stemwise_engine.common import WorkerRetirementError, atomic_write_json, run_process_tree, sha256_file
from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline


class NemoLexicalCandidateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.project = self.root/'builder'
        self.project.mkdir()
        self.job = self.root/'job'
        self.job.mkdir()
        self.audio = self.root/'original.wav'
        self.left = np.sin(np.arange(9600)/17).astype('float32')*.2
        sf.write(self.audio, np.column_stack((self.left, -self.left)), 48000, subtype='FLOAT')
        self.identity = AudioIdentity(sha256_file(self.audio), self.audio.stat().st_size, 48000, 2, 9600).to_record()
        self.runtime = {'python': lexical.nfa.PYTHON_VERSION, 'nemoToolkit': lexical.nfa.NEMO_VERSION,
            'torch': lexical.nfa.TORCH_VERSION, 'cudaRuntime': lexical.nfa.CUDA_VERSION,
            'cudaDevice': {'name': 'Known fixture CUDA', 'capability': [12, 0], 'totalMemoryBytes': 16*1024**3}}
        self.torch_identity = {'device': 'cuda', 'torch': lexical.nfa.TORCH_VERSION,
            'cudaRuntime': lexical.nfa.CUDA_VERSION, 'cudaDriver': 13020, 'cudnn': '91200',
            'uuid': 'GPU-11111111-1111-1111-1111-111111111111', **self.runtime['cudaDevice']}
        self.device_index = 0
        self.original = {'language': 'ar', 'script': 'Arab', 'text': 'مولاي نور'}
        self.events, self.requests = [], []
        self.output = [SimpleNamespace(text='  مَوْلَايَ نور\n', y_sequence=np.array([3, 7, 9]))]
        self.model_error = self.load_error = self.on_transcribe = None
        self.apply_head = self.apply_attention = True
        self.model_path = self.root/'model.nemo'
        self.model_path.write_bytes(b'fixture weights')
        case = self

        class EncDecHybridRNNTCTCBPEModel:
            def __init__(self):
                self.cur_decoder = 'rnnt'
                self.ctc_decoding = SimpleNamespace(cfg={'strategy': 'greedy'}, compute_timestamps=False)
                self.encoder = SimpleNamespace(self_attention_model='rel_pos', att_context_size=[-1, -1])
            def eval(self): return self
            def change_decoding_strategy(self, *, decoder_type):
                case.assertEqual(decoder_type, 'ctc')
                if case.apply_head:
                    self.cur_decoder = decoder_type
            def change_attention_model(self, *, self_attention_model, att_context_size):
                if case.apply_attention:
                    self.encoder.self_attention_model = self_attention_model
                    self.encoder.att_context_size = att_context_size
            def transcribe(self, **arguments):
                case.events.append('transcribe')
                case.assertEqual(set(arguments), {'audio', 'batch_size', 'return_hypotheses', 'timestamps', 'verbose'})
                case.assertEqual({key: value for key, value in arguments.items() if key != 'audio'},
                    {'batch_size': 1, 'return_hypotheses': False, 'timestamps': False, 'verbose': False})
                case.assertEqual(self.cur_decoder, 'ctc')
                samples, rate = sf.read(arguments['audio'][0], dtype='float32')
                case.assertEqual((samples.shape, rate), ((9600,), 48000))
                np.testing.assert_array_equal(samples, case.left)
                print('private vendor chatter')
                if case.on_transcribe:
                    case.on_transcribe()
                if case.model_error:
                    raise case.model_error
                return case.output
        EncDecHybridRNNTCTCBPEModel.__module__ = 'nemo.collections.asr.models.hybrid_rnnt_ctc_bpe_models'
        self.model_class = EncDecHybridRNNTCTCBPEModel

        def restore(path, *, map_location):
            self.assertEqual((path, map_location), (str(self.model_path), 'cuda:0'))
            self.events.append('load')
            if self.load_error:
                raise self.load_error
            return self.model_class()
        self.modules = {'torch': SimpleNamespace(device=lambda value: value,
            cuda=SimpleNamespace(current_device=lambda: self.device_index,
                                 synchronize=lambda: self.events.append('synchronize'),
                                 empty_cache=lambda: self.events.append('empty-cache'))),
            'nemo.collections.asr.models': SimpleNamespace(ASRModel=SimpleNamespace(restore_from=restore),
                EncDecHybridRNNTCTCModel=self.model_class)}
        lifetime = ExitStack()
        self.addCleanup(lifetime.close)
        lifetime.enter_context(patch.dict(sys.modules, self.modules))
        self.local_runtime = lexical._local_runtime
        self.model_location = lexical._model_path
        lifetime.enter_context(patch.object(lexical, '_local_runtime', return_value=self.root/'runtime'))
        lifetime.enter_context(patch.object(lexical, '_model_path', return_value=self.model_path))
        lifetime.enter_context(patch.object(lexical.nfa, '_runtime_identity', side_effect=lambda: copy.deepcopy(self.runtime)))
        lifetime.enter_context(patch.object(worker_runtime_probe, 'torch_runtime_identity',
            side_effect=lambda: copy.deepcopy(self.torch_identity)))
        lifetime.enter_context(patch.object(lexical.nfa, 'SOUNDFILE_VERSION', sf.__version__))
        lifetime.enter_context(patch.object(lexical.nfa, 'LIBSNDFILE_VERSION', sf.__libsndfile_version__))
        lifetime.enter_context(patch('stemwise_engine.common.run_process_tree', side_effect=self.dispatch))

    @property
    def cache(self):
        return self.job/'nemo-lexical-ar-mix.private.json'

    def dispatch(self, command, **settings):
        self.assertTrue(settings['capture_output'])
        self.assertFalse(settings['check'])
        self.assertEqual(command[0], str(lexical.worker_python(self.project)))
        arguments = command[command.index('stemwise_engine.nemo_lexical_candidate')+1:]
        if '--request' in arguments:
            path = Path(arguments[arguments.index('--request')+1])
            self.requests.append(json.loads(path.read_bytes()))
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(sys, 'argv', ['nemo_lexical_candidate', *arguments]), \
                redirect_stdout(stdout), redirect_stderr(stderr):
            try:
                code = worker_protocol.run_worker_main(lexical.main)
                code = 0 if code is None else code
            except SystemExit as error:
                code = error.code
        return subprocess.CompletedProcess(command, code, stdout.getvalue(), stderr.getvalue())

    def load(self, **overrides):
        return lexical.load_or_transcribe(**{**dict(project=self.project, job_dir=self.job, video_id='TESTVID0001',
            audio=self.audio, audio_identity=self.identity, original=self.original, timeout_seconds=10,
            require_inputs=lambda: None), **overrides})

    def stale_cache(self):
        self.load()
        value = json.loads(self.cache.read_bytes())
        value['provenance']['runtime']['torchProducer']['uuid'] = 'GPU-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
        atomic_write_json(self.cache, value)
        self.events.clear()
        return self.cache.read_bytes()

    def test_normal_adapter_worker_reuses_exact_raw_candidate_without_editorial_or_model_work(self):
        candidate, reused = self.load()
        self.assertFalse(reused)
        self.assertEqual(candidate['transcription'], '  مَوْلَايَ نور\n')
        self.assertEqual(candidate['rawOutput'], [{'text': '  مَوْلَايَ نور\n', 'tokens': [3, 7, 9]}])
        self.assertEqual(self.events, ['load', 'transcribe', 'synchronize', 'empty-cache'])
        self.assertEqual(len(self.requests), 1)
        self.assertNotIn(self.original['text'], json.dumps(self.requests[0], ensure_ascii=False))
        self.assertEqual(lexical.recognized(candidate, self.identity['sha256'], candidate_only=True), candidate['transcription'])
        before = self.cache.read_bytes()
        self.original['text'] = 'نور مولاي'
        self.events.clear()
        self.assertEqual(self.load(), (candidate, True))
        self.assertEqual(self.events, [])
        self.assertEqual(self.cache.read_bytes(), before)

    def test_producer_matches_actual_lyrics_and_native_interpreters_and_rejects_changed_source(self):
        from stemwise_engine.runtime_environment import environment_lease, environment_python
        project = Path(lexical.__file__).resolve().parents[1]
        code = '''import builtins,json
original_import = builtins.__import__
def passive(name, *args, **kwargs):
    if name.split('.')[0] in {'torch', 'nemo'}:
        raise AssertionError('Producer identity imported numerical runtime')
    return original_import(name, *args, **kwargs)
builtins.__import__ = passive
from stemwise_engine.nemo_lexical_candidate import producer_identity, MODEL, DECODER
print(json.dumps({'producer': producer_identity(), 'model': MODEL, 'decoder': DECODER}, sort_keys=True))
'''
        observed = []
        for environment, python in (('lyrics', environment_python('lyrics', project)),
                                    (lexical.ENVIRONMENT, lexical.worker_python(project))):
            with environment_lease(environment, builder=project):
                completed = run_process_tree([str(python), '-B', '-c', code], capture_output=True,
                    text=True, check=False, timeout=45, env=os.environ.copy())
            self.assertEqual(completed.returncode, 0, completed.stderr)
            observed.append(worker_protocol.parse_worker_json(completed.stdout))
        self.assertEqual(observed[0], observed[1])
        source = Path(lexical.__file__).parent
        isolated = self.root/'isolated-producer'
        isolated.mkdir()
        for name in observed[0]['producer']['helpers']:
            (isolated/name).write_bytes((source/name).read_bytes())
        with patch.object(lexical, '__file__', str(isolated/'nemo_lexical_candidate.py')):
            self.assertEqual(lexical.producer_identity(), observed[0]['producer'])
            changed = isolated/'acoustic_inputs.py'
            original = changed.read_text(encoding='utf-8')
            self.assertTrue(original.startswith('"""'))
            changed.write_text('"""Prose-only clarification."""'+original[original.index('"""', 3)+3:]
                +'\n# Additional explanation, without executable changes.\n', encoding='utf-8')
            self.assertEqual(lexical.producer_identity(), observed[0]['producer'])
            changed.write_bytes(changed.read_bytes()+b'\nQUALIFICATION_MUTATION = 1\n')
            self.assertNotEqual(lexical.producer_identity()['helpers']['acoustic_inputs.py'],
                                observed[0]['producer']['helpers']['acoustic_inputs.py'])

    def test_same_class_gpu_driver_or_cudnn_change_misses_cache_and_preserves_failed_replacement(self):
        candidate, _ = self.load()
        before = self.cache.read_bytes()
        initial = copy.deepcopy(self.torch_identity)
        self.model_error = RuntimeError('replacement numerical attempt failed')
        for field, replacement in (('uuid', 'GPU-22222222-2222-2222-2222-222222222222'),
                                   ('cudaDriver', 13030), ('cudnn', '91300')):
            self.torch_identity = {**initial, field: replacement}
            self.events.clear()
            with self.subTest(field=field):
                with self.assertRaises(worker_protocol.WorkerProtocolError):
                    self.load()
                self.assertIn('load', self.events, 'A changed observed producer incorrectly reused the old cache')
                self.assertEqual(self.cache.read_bytes(), before)
        self.torch_identity = initial
        self.model_error = None
        self.events.clear()
        self.assertEqual(self.load(), (candidate, True))
        self.assertEqual(self.events, [])

    def test_nonzero_current_cuda_device_refuses_before_model_or_cache_reuse(self):
        self.load()
        before = self.cache.read_bytes()
        self.events.clear()
        self.device_index = 1
        with self.assertRaises(worker_protocol.WorkerProtocolError):
            self.load()
        self.assertEqual(self.events, [])
        self.assertEqual(self.cache.read_bytes(), before)

    def test_candidate_and_passive_readback_never_grant_ordinary_authority(self):
        from stemwise_engine.lyric_content import _recognized
        candidate, _ = self.load()
        for reader in (lexical.recognized, _recognized):
            with self.assertRaises(ValueError):
                reader(candidate, self.identity['sha256'])
        with patch.object(lexical, '_runtime_identity', side_effect=AssertionError('passive GPU probe')):
            self.assertEqual(lexical.validate(candidate, self.identity['sha256']), candidate)
            for field, changed_value in (('uuid', ''), ('cudaDriver', True), ('cudnn', 'unknown'),
                                         ('torch', 'different runtime'), ('name', 'different device')):
                changed = copy.deepcopy(candidate)
                changed['provenance']['runtime']['torchProducer'][field] = changed_value
                with self.subTest(runtime_field=field), self.assertRaises(ValueError):
                    lexical.validate(changed, self.identity['sha256'])
        for fault in ('contentAuthority', 'timingAuthority', 'publicationAuthority', 'transcription', 'model', 'tokens'):
            changed = copy.deepcopy(candidate)
            if fault.endswith('Authority'): changed[fault] = True
            elif fault == 'transcription': changed[fault] = 'invented'
            elif fault == 'model': changed['provenance']['model']['sha256'] = 'b'*64
            else: changed['rawOutput'][0]['tokens'] = [True]
            with self.subTest(fault=fault), self.assertRaises(ValueError):
                lexical.validate(changed, self.identity['sha256'])

    def test_produced_candidate_survives_current_passive_status_without_authority(self):
        from stemwise_engine.recording_evidence import stage_audio_candidate
        from stemwise_engine.lyrics_intake import read_recording_draft
        from stemwise_engine.workflow_status import validate_recovery_candidates
        from stemwise_engine.lyric_content import _recognized
        candidate, _ = self.load()
        editorial = self.root/'editorial'
        (editorial/'drafts').mkdir(parents=True)
        (editorial/'drafts/original.txt').write_text(self.original['text']+'\n', encoding='utf-8')
        atomic_write_json(editorial/'drafts/lyrics.meta.json',
            {'originalLanguage': 'ar', 'originalScript': 'Arab'})
        _, draft_sha, _, metadata_sha = read_recording_draft(editorial)
        reference = {'path': self.cache.name, 'sha256': sha256_file(self.cache)}
        staged = stage_audio_candidate(editorial, self.job, 'TESTVID0001',
            {'sha256': self.identity['sha256'], 'durationMs': self.identity['stream']['durationMs']}, reference)
        packet = {'formatVersion': 1, 'videoId': 'TESTVID0001', 'audioSha256': self.identity['sha256'],
            'durationMs': self.identity['stream']['durationMs'], 'draftSha256': draft_sha,
            'metadataSha256': metadata_sha, 'primaryRecognition': reference, 'candidates': [staged],
            'status': 'pending-recording-review', 'publicationAuthority': False}
        before = {path: path.read_bytes() for root in (self.job, editorial)
                  for path in root.rglob('*') if path.is_file()}
        original_import = builtins.__import__
        def passive(name, *args, **kwargs):
            if name in {'stemwise_engine.recording_evidence', 'stemwise_engine.lyrics_align_pc',
                    'stemwise_engine.alignment_models'} or name.split('.')[0] in {
                    'torch', 'numpy', 'faster_whisper', 'ctranslate2'}:
                raise AssertionError('Passive Status imported numerical code: '+name)
            return original_import(name, *args, **kwargs)
        with patch.object(builtins, '__import__', side_effect=passive):
            validate_recovery_candidates(editorial, self.job, packet, video_id='TESTVID0001',
                audio_sha=self.identity['sha256'], duration_ms=self.identity['stream']['durationMs'])
        self.assertEqual({path: path.read_bytes() for path in before}, before)
        self.assertEqual((editorial/staged['candidate']['path']).read_text(encoding='utf-8'), 'مَوْلَايَ نور\n')
        self.assertIs(staged['publicationAuthority'], False)
        for key in ('contentAuthority', 'timingAuthority', 'publicationAuthority'):
            self.assertIs(candidate[key], False)
        with self.assertRaises(ValueError):
            _recognized(candidate, self.identity['sha256'])

    def test_mixed_or_wrong_language_refuses_before_worker_or_cache_mutation(self):
        before = self.stale_cache()
        for original in ({**self.original, 'language': 'en'}, {**self.original, 'script': 'Latn'},
                {**self.original, 'componentLanguages': ['ar', 'en']}, {**self.original, 'text': 'مولاي hello'},
                {**self.original, 'text': '   '}):
            with self.subTest(original=original), self.assertRaises(ValueError):
                self.load(original=original)
        self.assertEqual(self.events, [])
        self.assertEqual(self.cache.read_bytes(), before)

    def test_empty_or_malformed_actual_output_preserves_previous_cache_and_retires_model(self):
        before = self.stale_cache()
        for output in ([], ['not a hypothesis'], [SimpleNamespace(text='', y_sequence=[3])],
                       [SimpleNamespace(text='نور', y_sequence=[])],
                       [SimpleNamespace(text='نور', y_sequence=[-1])]):
            self.output = output
            self.events.clear()
            with self.subTest(output=output), self.assertRaises(worker_protocol.WorkerProtocolError) as raised:
                self.load()
            self.assertEqual(raised.exception.code, 'nemo-lexical-worker-failed')
            self.assertEqual(self.events[-2:], ['synchronize', 'empty-cache'])
            self.assertEqual(self.cache.read_bytes(), before)

    def test_wrong_applied_head_or_attention_refuses_before_transcription(self):
        before = self.stale_cache()
        for setting in ('apply_head', 'apply_attention'):
            setattr(self, setting, False)
            self.events.clear()
            with self.subTest(setting=setting), self.assertRaises(worker_protocol.WorkerProtocolError):
                self.load()
            self.assertNotIn('transcribe', self.events)
            self.assertEqual(self.events[-2:], ['synchronize', 'empty-cache'])
            self.assertEqual(self.cache.read_bytes(), before)
            setattr(self, setting, True)

    def test_capacity_and_changed_recording_refuse_before_model_load(self):
        from stemwise_engine.acoustic_inputs import AcousticBudget
        before = self.stale_cache()
        with patch('stemwise_engine.acoustic_inputs.CURRENT_BUDGET', AcousticBudget(available_bytes=1)), \
                self.assertRaises(worker_protocol.WorkerProtocolError):
            self.load()
        self.assertEqual(self.events, [])
        self.audio.write_bytes(b'changed Original')
        with self.assertRaises((ValueError, RuntimeError)):
            self.load()
        self.assertEqual(self.events, [])
        self.assertEqual(self.cache.read_bytes(), before)

    def test_input_change_after_transcription_and_caller_cancellation_preserve_cache(self):
        before = self.stale_cache()
        original_bytes = self.audio.read_bytes()
        self.on_transcribe = lambda: self.audio.write_bytes(b'changed while model ran')
        with self.assertRaises(worker_protocol.WorkerProtocolError):
            self.load()
        self.assertEqual(self.cache.read_bytes(), before)
        self.audio.write_bytes(original_bytes)
        self.on_transcribe = None
        calls = []
        def require_inputs():
            calls.append(1)
            if len(calls) == 2:
                raise ProviderCancelled('caller stopped after worker retirement')
        with self.assertRaises(ProviderCancelled):
            self.load(require_inputs=require_inputs)
        self.assertEqual(self.cache.read_bytes(), before)

    def test_worker_cancellation_and_deadline_keep_their_dispositions(self):
        before = self.stale_cache()
        for error in (ProviderCancelled('cancelled'), ProviderDeadline('deadline')):
            self.model_error = error
            self.events.clear()
            with self.subTest(error=type(error)), self.assertRaises(type(error)):
                self.load()
            self.assertEqual(self.events[-2:], ['synchronize', 'empty-cache'])
            self.assertEqual(self.cache.read_bytes(), before)

    def test_failed_retirement_is_fatal_even_during_cancellation(self):
        before = self.stale_cache()
        self.model_error = ProviderCancelled('parent stop remains in native cause')
        def fatal(code):
            traceback.print_exc()
            worker_protocol.emit_worker_failure(WorkerRetirementError(code), code=code)
            raise SystemExit(70)
        with patch.object(ctc_worker, 'retire_model', side_effect=RuntimeError('retirement failed')), \
                patch.object(worker_protocol, 'fatal_model_failure', side_effect=fatal), \
                self.assertRaises(WorkerRetirementError) as raised:
            self.load()
        self.assertIn('retirement', str(raised.exception))
        self.assertIn(b'parent stop remains', raised.exception.stderr)
        self.assertEqual(self.cache.read_bytes(), before)
        self.model_error = None
        self.load_error = RuntimeError('native initialization failed')
        with patch.object(worker_protocol, 'fatal_model_failure', side_effect=fatal), \
                self.assertRaises(WorkerRetirementError) as raised:
            self.load()
        self.assertIn(b'native initialization failed', raised.exception.stderr)
        self.assertEqual(self.cache.read_bytes(), before)

    def test_closed_worker_request_rejects_conditioning_and_hash_changes_before_load(self):
        self.load()
        request = self.requests[0]
        path = self.root/'request.private.json'
        self.events.clear()
        atomic_write_json(path, request)
        with self.assertRaisesRegex(ValueError, 'request changed'):
            lexical.run_request(path, 'b'*64)
        for key in ('prompt', 'gt_text_batch', 'lyrics'):
            atomic_write_json(path, {**request, key: 'approved words must never condition recognition'})
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'request is invalid'):
                lexical.run_request(path, sha256_file(path))
        changed = copy.deepcopy(request)
        changed['provenance']['model']['sha256'] = 'b'*64
        atomic_write_json(path, changed)
        with self.assertRaisesRegex(ValueError, 'producer changed'):
            lexical.run_request(path, sha256_file(path))
        self.assertEqual(self.events, [])
        runtime = lexical.worker_python(self.project).parents[2]
        align, lock = runtime/'tools/nemo_forced_aligner/align.py', runtime/'uv.lock'
        align.parent.mkdir(parents=True)
        align.write_bytes(b'pinned aligner')
        lock.write_bytes(b'pinned lock')
        model = self.project.parent/'model-cache/lyrics-alignment/models--nvidia--stt_ar_fastconformer_hybrid_large_pcd_v1.0'/(
            'snapshots')/lexical.nfa.MODEL_REVISION/lexical.nfa.MODEL_ARTIFACT
        model.parent.mkdir(parents=True)
        model.write_bytes(b'pinned fixture weights')
        with ExitStack() as bindings:
            for prefix, item in (('NFA_ALIGN', align), ('NEMO_LOCK', lock), ('MODEL', model)):
                bindings.enter_context(patch.object(lexical.nfa, prefix+'_SHA256', sha256_file(item)))
                bindings.enter_context(patch.object(lexical.nfa, prefix+'_BYTE_COUNT', item.stat().st_size))
            checkout = bindings.enter_context(patch.object(lexical.nfa, '_verify_source_checkout'))
            self.assertEqual(self.local_runtime(self.project), runtime)
            checkout.assert_called_once_with(runtime)
            self.assertEqual(self.model_location(self.project), model)
            align.write_bytes(b'changed aligner')
            with self.assertRaisesRegex(ValueError, 'content binding'):
                self.local_runtime(self.project)
            model.write_bytes(b'changed weights')
            with self.assertRaisesRegex(ValueError, 'content binding'):
                self.model_location(self.project)


if __name__ == '__main__':
    unittest.main()
