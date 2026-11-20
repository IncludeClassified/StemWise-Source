"""Bounded alignment batches reuse acoustic work without reusing lyric decisions."""
import hashlib
import importlib
import json
import os
import signal
import subprocess
import shutil
import sys
import tempfile
import unittest
import time
import weakref
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "PC side/stemwise-playlist-builder/src"
sys.path.insert(0, str(SRC))
import stemwise_engine.ctc_worker as ctc_worker
import stemwise_engine.mms_forced_alignment as mms
import stemwise_engine.wav2vec2_forced_alignment as wav


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


class AlignmentBatchTests(unittest.TestCase):
    def test_emission_producer_validation_cancellation_and_retirement_still_propagate(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled
        for error in (ValueError('invalid emission input'), ProviderCancelled('cancelled'),
                      WorkerRetirementError('worker survived')):
            with self.subTest(error=type(error).__name__), \
                    tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name, \
                    patch('stemwise_engine.private_diagnostics.retain_error') as capture:
                def fail():
                    raise error
                with self.assertRaises(type(error)) as raised:
                    ctc_worker.cached_emissions(Path(name)/'request.json', 'a'*64, 16000, 2, fail)
                self.assertIs(raised.exception, error)
                capture.assert_not_called()

    def test_damaged_optional_diagnostic_preserves_produced_emissions(self):
        import torch
        from test_private_diagnostics import damaged_diagnostic_target
        for kind, error in (('mismatch', OSError('cache disk unavailable')),
                            ('directory', RuntimeError('cache writer failed'))):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                produced = (torch.zeros((50, 2)), [ctc_worker.chunk_time_map(0, 16000, 0, 50)], 480000)
                status = {}
                def failed_write(value, path):
                    Path(path).write_bytes(b'partial cache')
                    raise error
                with patch.object(torch, 'save', side_effect=failed_write), \
                        damaged_diagnostic_target(kind) as targets:
                    result = ctc_worker.cached_emissions(root/'request.json', 'a'*64, 16000, 2,
                        lambda: produced, cache_status=status)
                self.assertIs(result[0], produced[0])
                self.assertIs(result[1], produced[1])
                self.assertEqual(result[2], produced[2])
                self.assertEqual(status, {'ready': False, 'diagnosticError': 'ValueError'})
                self.assertFalse((root/('emissions-'+'a'*64+'.tmp')).exists())
                self.assertFalse((root/('emissions-'+'a'*64+'.pt')).exists())
                self.assertEqual(len(targets), 1)

    def test_benchmark_admission_uses_a_real_producer_cache_and_semantic_recipe(self):
        import importlib.util
        import torch
        from stemwise_engine.lyrics_align_pc import _emission_cache_key, _wav2vec2_helper_hashes
        spec = importlib.util.spec_from_file_location('alignment_benchmark',
            ROOT/'Test suite/pc/performance/benchmark_alignment_session.py')
        benchmark = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(benchmark)
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            sources = root/'src'
            sources.mkdir()
            for filename in ('mms_forced_alignment.py', 'wav2vec2_forced_alignment.py',
                             'ctc_worker.py', 'acoustic_inputs.py', 'worker_protocol.py',
                             'worker_publication.py', 'common.py'):
                shutil.copyfile(SRC/filename, sources/filename)
            for worker in ('mms', 'wav2vec2'):
                producers = (_wav2vec2_helper_hashes(root) if worker == 'wav2vec2' else
                    {'mms_forced_alignment.py': ctc_worker.helper_source_identity(sources/'mms_forced_alignment.py')})
                provenance = {'audio': {'sha256': 'a'*64}, 'model': 'known-answer-fixture'}
                key = _emission_cache_key(provenance, producers, 'cpu')
                ctc_worker.cached_emissions(root/'request.json', key, 16000, 2,
                    lambda: (torch.zeros((50, 2)), [ctc_worker.chunk_time_map(0, 16000, 0, 50)], 480000))
                path = root/f'emissions-{key}.pt'
                fixture = {'worker': worker, 'emissionCacheKey': key, 'producerSha256s': producers,
                    'emissionDevice': 'cpu', 'emissionsSha256': benchmark.digest_file(path)}
                benchmark.require_production_cache(fixture, {'provenance': provenance}, path, sources)
                adapter = sources/'mms_forced_alignment.py'
                before = adapter.read_bytes()
                adapter.write_bytes(before+b'\n# unrelated explanation\n')
                benchmark.require_production_cache(fixture, {'provenance': provenance}, path, sources)
                adapter.write_bytes(before+b'\nNUMERICAL_FIXTURE_CHANGE = 2\n')
                with self.assertRaisesRegex(ValueError, 'another numerical producer'):
                    benchmark.require_production_cache(fixture, {'provenance': provenance}, path, sources)
                adapter.write_bytes(before)

    def test_isolated_cached_benchmark_runs_both_workers_without_parent_pythonpath(self):
        import numpy as np
        import soundfile as sf
        import torch
        import torchaudio
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            audio = root/'audio.wav'
            sf.write(audio, (.1*np.sin(np.arange(16000)*.03)).astype('float32'), 16000, subtype='FLOAT')
            audio_sha = hashlib.sha256(audio.read_bytes()).hexdigest()
            request = {'formatVersion': mms.FORMAT_VERSION, 'durationMs': 1000,
                'lines': [{'sourcePhysicalIndex': index, 'lineId': f'line-{index}',
                    'words': [{'wordId': f'word-{index}', 'acousticText': 'a'}]} for index in range(2)],
                'segments': [{'segmentIndex': index, 'startLineIndex': index, 'endLineIndex': index + 1,
                    'windowStartMs': index * 500, 'windowEndMs': (index + 1) * 500} for index in range(2)]}
            request_path = root/'request.json'
            request_path.write_text(json.dumps(request), encoding='utf-8')
            request_sha = digest(request)
            model = root/'model'
            model.mkdir()
            project = root/'project'
            project.mkdir()
            sources = root/'candidate-src'
            sources.mkdir()
            for path in SRC.iterdir():
                if path.is_file() and path.suffix in ('.py', '.json'):
                    shutil.copyfile(path, sources/path.name)
            # Substitute only the model/tokenizer library boundary. Numerical
            # workers, CTC decoding, subprocess ownership and benchmark are real.
            (sources/'transformers.py').write_text('''
from types import SimpleNamespace
class Wav2Vec2CTCTokenizer:
 pad_token_id=0
 word_delimiter_token_id=2
 unk_token_id=3
 all_special_ids=[0,3]
 @classmethod
 def from_pretrained(cls,*args,**kwargs): return cls()
 def get_vocab(self): return {'<pad>':0,'a':1,'|':2,'<unk>':3}
Wav2Vec2PhonemeCTCTokenizer=Wav2Vec2CTCTokenizer
class Wav2Vec2FeatureExtractor:
 sampling_rate=16000
 @classmethod
 def from_pretrained(cls,*args,**kwargs): return cls()
class AutoConfig:
 @classmethod
 def from_pretrained(cls,*args,**kwargs): return SimpleNamespace(pad_token_id=0,vocab_size=4)
class AutoModelForCTC:
 @classmethod
 def from_pretrained(cls,*args,**kwargs): raise AssertionError('Inference is forbidden')
''', encoding='utf-8')
            for filename, value in {
                'config.json': {'model_type': 'wav2vec2', 'pad_token_id': 0, 'vocab_size': 4},
                'tokenizer_config.json': {'tokenizer_class': 'Wav2Vec2CTCTokenizer', 'do_lower_case': False,
                    'unk_token': '<unk>', 'pad_token': '<pad>', 'word_delimiter_token': '|'},
                'vocab.json': {'<pad>': 0, 'a': 1, '|': 2, '<unk>': 3},
                'preprocessor_config.json': {'feature_extractor_type': 'Wav2Vec2FeatureExtractor',
                    'feature_size': 1, 'sampling_rate': 16000, 'padding_value': 0.0,
                    'do_normalize': True, 'return_attention_mask': False},
            }.items():
                (model/filename).write_text(json.dumps(value), encoding='utf-8')
            for worker in ('mms', 'wav2vec2'):
                directory = root/worker
                directory.mkdir()
                provenance = {'audio': {'sha256': audio_sha}}
                from stemwise_engine.lyrics_align_pc import _emission_cache_key, _wav2vec2_helper_hashes
                producers = (_wav2vec2_helper_hashes(SRC.parent) if worker == 'wav2vec2' else
                    {'mms_forced_alignment.py': ctc_worker.helper_source_identity(SRC/'mms_forced_alignment.py')})
                key = _emission_cache_key(provenance, producers, 'cpu')
                vocab = torchaudio.pipelines.MMS_FA.get_labels(star=None) if worker == 'mms' else range(4)
                token = vocab.index('a') if worker == 'mms' else 1
                values = torch.full((50, len(vocab)), -30.)
                values[:, 0] = 0
                for start, end in ((5, 15), (35, 45)):
                    values[start:end, 0] = -30.
                    values[start:end, token] = 0
                cache = directory/f'emissions-{key}.pt'
                torch.save({'key': key, 'sampleCount': 16000, 'emission': values, 'chunkSamples': 480000,
                    'chunks': [ctc_worker.chunk_time_map(0, 16000, 0, 50)]}, cache)
                bound = directory/'bound-result.json'
                bound.write_text(json.dumps({'requestSha256': request_sha, 'provenance': provenance,
                    'durationMs': 1000}), encoding='utf-8')
                fixture = directory/'fixture.json'
                fixture.write_text(json.dumps({'formatVersion': 1, 'worker': worker, 'audio': str(audio),
                    'request': str(request_path), 'emissions': str(cache), 'sourceCache': str(bound),
                    'audioSha256': audio_sha, 'emissionsSha256': hashlib.sha256(cache.read_bytes()).hexdigest(),
                    'requestSha256': request_sha, 'durationMs': 1000, 'emissionCacheKey': key,
                    'producerSha256s': producers, 'emissionDevice': 'cpu', 'model': str(model), 'language': 'en'}), encoding='utf-8')
                environment = dict(os.environ)
                environment.pop('PYTHONPATH', None)
                # CI uses the test interpreter without a managed project venv.
                bootstrap = (
                    'import runpy,sys; from pathlib import Path; '
                    'benchmark,project=sys.argv[1:3]; sys.argv=[benchmark,*sys.argv[3:]]; '
                    'scope=runpy.run_path(benchmark); '
                    'scope["main"].__globals__["PROJECT"]=Path(project); scope["main"]()')
                completed = subprocess.run([sys.executable, '-B', '-c', bootstrap,
                    str(ROOT/'Test suite/pc/performance/benchmark_alignment_session.py'), str(project),
                    '--fixture', str(fixture), '--candidate-src', str(sources),
                    '--work', str(directory/'measurement'), '--timeout', '45', '--repair-segments'],
                    capture_output=True, text=True, env=environment, timeout=240)
                self.assertEqual(completed.returncode, 0, completed.stderr[-3000:])
                rows = [json.loads(line) for line in completed.stdout.splitlines()]
                self.assertEqual([row['numericalProcesses'] for row in rows[:-1]], [3, 1, 1, 3])
                self.assertTrue(all(row['allWorkersRetired'] for row in rows[:-1]))
                self.assertTrue(all(row['segmentRepairs'] == 3 for row in rows[:-1]), rows)
                self.assertTrue(rows[-1]['completeOutputsEqual'])
                self.assertFalse((directory/'measurement').exists())

    def test_emission_preparation_creates_only_fully_validated_missing_cache_parents(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "Test suite/work") as folder:
            base = Path(folder)
            audio = base / "audio.wav"
            audio.write_bytes(b"audio")
            contexts = [{"contextId": str(i), "audio": str(audio),
                "audioSha256": hashlib.sha256(audio.read_bytes()).hexdigest(), "durationMs": 1,
                "emissionCacheKey": str(i + 1) * 64,
                "cacheOutput": str(base / "missing" / str(i) / ("emissions-" + str(i + 1) * 64 + ".pt"))}
                for i in range(2)]
            manifest = {"formatVersion": 1, "contexts": contexts, "timeoutSeconds": 10}
            path, output = base / "manifest.json", base / "reply.json"
            invalid = {**manifest, "contexts": [contexts[0], {**contexts[1], "audioSha256": "f" * 64}]}
            path.write_text(json.dumps(invalid))
            with self.assertRaises(ValueError):
                ctc_worker.prepare_batch_plan(path, output)
            self.assertFalse((base / "missing").exists(), "validate the complete batch before creating outputs")
            path.write_text(json.dumps(manifest))
            ctc_worker.prepare_batch_plan(path, output)
            for context in contexts:
                self.assertTrue(Path(context["cacheOutput"]).parent.is_dir(),
                                "prepare cache directories before expensive model inference")
                self.assertFalse(Path(context["cacheOutput"]).exists())

    def test_batch_preserves_the_exact_static_target_capability_result(self):
        request = {"approved": "target"}
        error = wav.TargetVocabularyUnsupported(request)
        with tempfile.TemporaryDirectory(dir=ROOT / "Test suite/work") as folder:
            output = Path(folder) / "capability.json"
            with patch.object(sys, "argv", ["worker", "--batch", "request.json", "--output", str(output),
                                              "--model", folder, "--language", "en"]), \
                    patch.object(wav, "run_batch", side_effect=error):
                self.assertEqual(wav.main(), 3)
            self.assertEqual(json.loads(output.read_bytes()), {"formatVersion": 1,
                "engine": "wav2vec2-target-capability", "requestSha256": digest(request), "supported": False})

    @unittest.skipUnless(sys.platform == "linux", "hard worker deadlines require the WSL runtime")
    def test_cuda_decode_cleanup_precedes_lease_release_on_success_and_failure(self):
        import stemwise_engine.common as common
        for failure in (False, True):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(dir=ROOT / "Test suite/work") as folder:
                base = Path(folder)
                audio = base / "audio.wav"
                audio.write_bytes(b"audio")
                stat = audio.stat()
                request = {"segments": []}
                context = {"contextId": "0", "audio": str(audio), "audioSha256": "a" * 64,
                    "requestSha256": digest(request), "output": str(base / "numeric.json"),
                    "audioFileIdentity": (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)}
                events = []
                @contextmanager
                def stage(config):
                    events.append("leased")
                    try: yield
                    finally:
                        self.assertEqual(events[-1], "cuda-cleaned")
                        events.append("released")
                def cleanup(state):
                    if state.get("cuda_model"):
                        self.assertEqual(events[-1], "decoding")
                        events.append("cuda-cleaned")
                def decode(prepared):
                    events.append("decoding")
                    if failure: raise ValueError("injected decode failure")
                    return {"numeric": True}
                with patch.object(ctc_worker, "batch_plan", return_value=({"timeoutSeconds": 2,
                        "repairSegments": False}, [(context, request)])), \
                        patch.object(common, "heavy_worker_stage", stage, create=True), \
                        patch.object(ctc_worker, "retire_model", side_effect=cleanup):
                    def execute():
                        ctc_worker.run_alignment_batch(base / "manifest.json", base / "reply.json", "cuda",
                            lambda *args: {}, decode, project_dir=base, cuda_decode=True)
                    if failure:
                        with self.assertRaisesRegex(ValueError, "injected decode failure"): execute()
                        self.assertFalse((base / "reply.json").exists())
                    else:
                        execute()
                        self.assertEqual(json.loads((base / "numeric.json").read_bytes()), {"numeric": True})
                self.assertEqual(events, ["leased", "decoding", "cuda-cleaned", "released"])

    @unittest.skipUnless(sys.platform == "linux", "hard worker deadlines require the WSL runtime")
    def test_kernel_deadline_terminates_a_native_call_and_restores_the_previous_timer(self):
        from stemwise_engine.common import run_process_tree
        script = ("import ctypes,signal,sys\n"
            f"sys.path.insert(0,{str(Path(mms.__file__).parent)!r})\n"
            "from stemwise_engine.ctc_worker import execution_deadline\n"
            "with execution_deadline(.1):\n"
            " with execution_deadline(2):\n"
            "  if signal.getitimer(signal.ITIMER_REAL)[0]>.11: raise SystemExit('nested deadline enlarged its caller')\n"
            "  ctypes.PyDLL(None).sleep(3)\n"
            "raise SystemExit('native call escaped its hard deadline')\n")
        started = time.monotonic()
        completed = run_process_tree([sys.executable, "-B", "-c", script],
                                     timeout=5, capture_output=True, text=True)
        self.assertEqual(completed.returncode, -signal.SIGALRM, completed.stderr)
        self.assertLess(time.monotonic() - started, 3)
        previous, timer = signal.getsignal(signal.SIGALRM), signal.getitimer(signal.ITIMER_REAL)
        try:
            signal.signal(signal.SIGALRM, signal.SIG_IGN)
            signal.setitimer(signal.ITIMER_REAL, 2)
            with ctc_worker.execution_deadline(1):
                self.assertEqual(signal.getsignal(signal.SIGALRM), signal.SIG_DFL)
            self.assertEqual(signal.getsignal(signal.SIGALRM), signal.SIG_IGN)
            self.assertGreater(signal.getitimer(signal.ITIMER_REAL)[0], 0)
            self.assertLessEqual(signal.getitimer(signal.ITIMER_REAL)[0], 2)
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, previous)
            signal.setitimer(signal.ITIMER_REAL, *timer)

    @unittest.skipUnless(sys.platform == "linux", "kernel lease retirement requires the WSL runtime")
    def test_failed_model_retirement_keeps_the_lease_until_process_death(self):
        from stemwise_engine.common import run_process_tree
        import fcntl
        with tempfile.TemporaryDirectory(dir=ROOT / "Test suite/work") as folder:
            lock = Path(folder) / "heavy.lock"
            script = f'''import contextlib,fcntl,sys,types
from pathlib import Path
sys.path.insert(0,{str(Path(mms.__file__).parent)!r})
import stemwise_engine.ctc_worker as m
lock=Path({str(lock)!r})
@contextlib.contextmanager
def stage(config):
 with lock.open("a+") as handle:
  fcntl.flock(handle,fcntl.LOCK_EX)
  try: yield
  finally: print("LEASE_RELEASED_BEFORE_PROCESS_EXIT",flush=True)
sys.modules['stemwise_engine.common']=types.SimpleNamespace(heavy_worker_stage=stage)
request={{"segments":[]}}
context={{"audio":str(lock),"audioFileIdentity":None}}
lock.touch()
stat=lock.stat()
context["audioFileIdentity"]=(stat.st_dev,stat.st_ino,stat.st_size,stat.st_mtime_ns,stat.st_ctime_ns)
def prepare(row,state,request):
 state["acquire_stage"]()
 return {{}}
def fail(state):
 with lock.open("a+") as contender:
  try: fcntl.flock(contender,fcntl.LOCK_EX|fcntl.LOCK_NB)
  except BlockingIOError: pass
  else: raise AssertionError("lease was released before model retirement")
 raise RuntimeError("injected cleanup failure")
m.retire_model=fail
m.batch_plan=lambda *args:({{"timeoutSeconds":2}},[(context,request)])
try:
 m.run_alignment_batch(lock,lock.with_suffix(".json"),"cpu",prepare,lambda value:{{}},
  project_dir=lock.parent,cuda_decode=False)
finally:
 print("FAILED_WORKER_UNWOUND_WHILE_STILL_ALIVE",flush=True)
'''
            completed = run_process_tree([sys.executable, "-B", "-c", script],
                                         timeout=5, capture_output=True, text=True)
            self.assertEqual(completed.returncode, 70, completed.stderr)
            self.assertIn("alignment-model-retirement-failed", completed.stderr)
            self.assertIn('RuntimeError: injected cleanup failure', completed.stderr)
            self.assertIn('Traceback', completed.stderr)
            self.assertEqual(completed.stdout, "")
            with lock.open("a+") as contender:
                fcntl.flock(contender, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_batch_reuses_model_repairs_only_failed_windows_and_binds_each_result(self):
        import torch
        import torchaudio
        import transformers

        # Independent acoustic reference: A 300–500 ms, B 1500–1700 ms,
        # C 2500–2700 ms. The first artificial boundary at 1600 ms is invalid.
        logits = torch.full((60, 6), -8.0)
        logits[:, 0] = 8.0
        for start, end, token in ((6, 10, 1), (30, 34, 2), (50, 54, 3)):
            logits[start:end, 0] = -8.0
            logits[start:end, token] = 8.0
        for frame in (20, 40):
            logits[frame, 0], logits[frame, 4] = -8.0, 8.0
        config = SimpleNamespace(pad_token_id=0, vocab_size=6, inputs_to_logits_ratio=800)
        models = []
        class Model:
            def __init__(self, *args, **kwargs):
                models.append(weakref.ref(self))
                self.config = config
            def to(self, *args, **kwargs): return self
            def eval(self): return self
            def __call__(self, *args, **kwargs):
                if args: return logits.log_softmax(-1).unsqueeze(0), None
                return SimpleNamespace(logits=logits.unsqueeze(0))
        tokenizer = SimpleNamespace(word_delimiter_token_id=4, unk_token_id=5,
            all_special_ids=[0, 5], pad_token_id=0,
            get_vocab=lambda: {"<pad>": 0, "a": 1, "b": 2, "c": 3, "|": 4, "<unk>": 5})
        class Extractor:
            sampling_rate = 16000
            def __call__(self, samples, **kwargs):
                return {"input_values": torch.tensor(samples).unsqueeze(0)}
        bundle = SimpleNamespace(sample_rate=16000, get_model=Model,
            get_labels=lambda **kwargs: tuple(range(6)),
            get_tokenizer=lambda: lambda words: [{"a": [1], "b": [2], "c": [3]}[word] for word in words])
        with tempfile.TemporaryDirectory(dir=ROOT / "Test suite/work") as folder, \
                patch.object(torchaudio.pipelines, "MMS_FA", bundle), \
                patch.object(torchaudio, "load", return_value=(torch.sin(torch.arange(48000).float()/20).unsqueeze(0)*.1, 16000)), \
                patch.object(transformers.AutoConfig, "from_pretrained", return_value=config), \
                patch.object(transformers.AutoModelForCTC, "from_pretrained", side_effect=Model), \
                patch.object(transformers.Wav2Vec2CTCTokenizer, "from_pretrained", return_value=tokenizer), \
                patch.object(transformers.Wav2Vec2FeatureExtractor, "from_pretrained", return_value=Extractor()):
            base = Path(folder)
            model = base / "model"
            model.mkdir()
            (model / "tokenizer_config.json").write_text(json.dumps({"tokenizer_class": "Wav2Vec2CTCTokenizer"}))
            request = {"formatVersion": 5, "durationMs": 3000,
                "lines": [{"sourcePhysicalIndex": i, "lineId": f"line-{word}",
                    "words": [{"wordId": f"word-{word}", "acousticText": word}]} for i, word in enumerate("abc")],
                "segments": [{"segmentIndex": i, "startLineIndex": i, "endLineIndex": i + 1,
                    "windowStartMs": [0, 1600, 2200][i], "windowEndMs": [1600, 2200, 3000][i]}
                    for i in range(3)]}
            for worker in (mms, wav):
                with self.subTest(worker=worker.__name__):
                    contexts = []
                    for i in range(2):
                        audio = base / f"{worker.__name__}-{i}.wav"
                        import numpy as np
                        import soundfile as sf
                        sf.write(audio, .1*np.sin(np.arange(48000)/20), 16000, subtype='FLOAT')
                        req = base / f"{worker.__name__}-{i}.request.json"
                        req.write_text(json.dumps(request))
                        contexts.append({"contextId": str(i), "audio": str(audio), "request": str(req),
                            "output": str(base / f"{worker.__name__}-{i}.out.json"),
                            "audioSha256": hashlib.sha256(audio.read_bytes()).hexdigest(),
                            "requestSha256": digest(request),
                            "emissionCacheKey": str(i + (1 if worker is mms else 3)) * 64})
                    manifest = {"formatVersion": 1, "contexts": contexts, "timeoutSeconds": 10,
                                "repairSegments": True}
                    manifest_path = base / f"{worker.__name__}.batch.json"
                    manifest_path.write_text(json.dumps(manifest))
                    before = len(models)
                    with patch.object(ctc_worker, "align_ctc", wraps=ctc_worker.align_ctc) as align:
                        if worker is mms:
                            worker.run_batch(manifest_path, base / "batch.out.json", "cpu")
                        else:
                            worker.run_batch(manifest_path, base / "batch.out.json", model, "en", "cpu")
                    self.assertEqual(len(models) - before, 1, "compatible views must share the loaded model")
                    self.assertIsNone(models[-1](), "the loaded model must retire before returning")
                    reply = json.loads((base / "batch.out.json").read_bytes())
                    self.assertEqual(reply["batchSha256"], digest(manifest))
                    self.assertEqual([r["contextId"] for r in reply["contexts"]], ["0", "1"])
                    for row, context in zip(reply["contexts"], contexts):
                        self.assertEqual(row["requestSha256"], digest(request))
                        final_request = {**request, "segments": row["resolvedSegments"]}
                        self.assertEqual(row["resolvedRequestSha256"], digest(final_request))
                        self.assertEqual(len(row["resolvedSegments"]), 2)
                        result = json.loads(Path(context["output"]).read_bytes())
                        self.assertEqual(result["segmentCount"], 2)
                        self.assertEqual([(line["lineId"], line["startMs"], line["endMs"])
                            for line in result["lines"]],
                            [("line-a", 300, 500), ("line-b", 1500, 1700), ("line-c", 2500, 2700)])
                        self.assertEqual(json.loads(Path(context["request"]).read_bytes()), request)
                    # Two original segments + their merged replacement + final segment per view.
                    self.assertEqual(align.call_count, 8)
                    # The ordinary path remains numerically identical after legal repair.
                    resolved_path = base / "resolved.request.json"
                    resolved_path.write_text(json.dumps(final_request))
                    reference = base / "reference.json"
                    if worker is mms:
                        worker.run(Path(context["audio"]), resolved_path, reference, "cpu",
                                   emission_cache_key=context["emissionCacheKey"])
                    else:
                        worker.run(Path(context["audio"]), resolved_path, reference, model, "en", "cpu",
                                   emission_cache_key=context["emissionCacheKey"])
                    self.assertEqual(json.loads(reference.read_bytes()), result)
                    # Emission-only batches accept no lyrics and do no target decoding.
                    preparing = [{"contextId": row["contextId"], "audio": row["audio"],
                        "audioSha256": row["audioSha256"], "durationMs": 3000,
                        "emissionCacheKey": str(i + (5 if worker is mms else 7)) * 64,
                        "cacheOutput": str(base / ("emissions-" + str(i + (5 if worker is mms else 7)) * 64 + ".pt"))}
                        for i, row in enumerate(contexts)]
                    preparation = {"formatVersion": 1, "contexts": preparing, "timeoutSeconds": 10}
                    manifest_path.write_text(json.dumps(preparation))
                    before = len(models)
                    def prepare(project=None):
                        if worker is mms:
                            worker.prepare_many(manifest_path, base / "prepared.json", "cpu", project_dir=project)
                        else:
                            worker.prepare_many(manifest_path, base / "prepared.json", model, "en", "cpu", project_dir=project)
                    import stemwise_engine.common as common
                    stages = []
                    @contextmanager
                    def stage(config):
                        stages.append("leased")
                        try:
                            yield
                        finally:
                            self.assertIsNone(models[-1](), "GPU ownership must outlive the loaded model")
                            stages.append("retired")
                    with patch.object(worker, "_decode", side_effect=AssertionError("preparation decoded lyrics")), \
                            patch.object(torch, "save", wraps=torch.save) as saved:
                        with patch.object(common, "heavy_worker_stage", stage, create=True):
                            prepare(base)
                        self.assertEqual(stages, ["leased", "retired"])
                        self.assertEqual(len(models) - before, 1)
                        self.assertIsNone(models[-1]())
                        self.assertEqual(saved.call_count, 2, "each prepared cache needs only one serialization")
                        with patch.object(common, "heavy_worker_stage", side_effect=AssertionError("cached preparation acquired GPU"), create=True):
                            prepare(base)
                        self.assertEqual(len(models) - before, 1, "valid emission files must avoid every model load")
                        self.assertEqual(saved.call_count, 2, "cache hits must not rewrite valid emission files")
                    prepared_reply = json.loads((base / "prepared.json").read_bytes())
                    self.assertEqual(prepared_reply["batchSha256"], digest(preparation))
                    for row, context in zip(prepared_reply["contexts"], preparing):
                        raw = Path(context["cacheOutput"]).read_bytes()
                        self.assertEqual(row, {"contextId": context["contextId"],
                            "audioSha256": context["audioSha256"], "durationMs": 3000,
                            "emissionCacheKey": context["emissionCacheKey"],
                            "cacheSha256": hashlib.sha256(raw).hexdigest(), "cacheByteCount": len(raw)})
                        cache = torch.load(context["cacheOutput"], weights_only=True)
                        self.assertEqual(cache["key"], context["emissionCacheKey"])
                        self.assertEqual(cache["sampleCount"], 48000)
                        self.assertEqual(tuple(cache["emission"].shape), (60, 6))
                    actual_prepare = worker._prepare
                    def fail_second(audio, *args, **kwargs):
                        if audio == Path(preparing[1]["audio"]):
                            raise RuntimeError("injected later view failure")
                        return actual_prepare(audio, *args, **kwargs)
                    with patch.object(worker, "_prepare", side_effect=fail_second):
                        with self.assertRaisesRegex(RuntimeError, "later view failure"):
                            if worker is mms:
                                worker.prepare_many(manifest_path, base / "prefix.json", "cpu")
                            else:
                                worker.prepare_many(manifest_path, base / "prefix.json", model, "en", "cpu")
                    prefix = json.loads((base / "prefix.json").read_bytes())
                    self.assertEqual(prefix["contexts"], prepared_reply["contexts"][:1],
                                     "a later failed view must preserve the valid completed preparation prefix")

    def test_invalid_batch_identity_is_rejected_before_model_or_output(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "Test suite/work") as folder:
            base = Path(folder)
            audio, request = base / "audio.wav", base / "request.json"
            audio.write_bytes(b"original")
            value = {"formatVersion": 5, "durationMs": 10, "lines": [], "segments": []}
            request.write_text(json.dumps(value))
            context = {"contextId": "0", "audio": str(audio), "request": str(request),
                "output": str(base / "out.json"), "audioSha256": "f" * 64,
                "requestSha256": digest(value), "emissionCacheKey": "a" * 64}
            for fault in ("audio", "request", "duplicate", "oversize"):
                with self.subTest(fault=fault):
                    current = {**context, "audioSha256": hashlib.sha256(audio.read_bytes()).hexdigest()}
                    if fault == "audio": current["audioSha256"] = "f" * 64
                    if fault == "request": current["requestSha256"] = "f" * 64
                    contexts = [current]
                    if fault == "duplicate": contexts *= 2
                    if fault == "oversize": contexts *= 5
                    manifest = base / "batch.json"
                    manifest.write_text(json.dumps({"formatVersion": 1, "contexts": contexts,
                        "timeoutSeconds": 1, "repairSegments": True}))
                    with self.assertRaises(ValueError):
                        mms.run_batch(manifest, base / "batch.out.json", "cpu")
                    self.assertFalse((base / "out.json").exists())
                    self.assertFalse((base / "batch.out.json").exists())


if __name__ == "__main__":
    unittest.main()
