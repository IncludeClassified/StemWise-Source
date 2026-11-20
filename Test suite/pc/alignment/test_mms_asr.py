import sys
import copy
import json
import hashlib
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout
import stemwise_engine.mms_asr as mms_asr


TEST_RUNTIME = {'torch': 'test-torch', 'torchaudio': 'test-torchaudio', 'transformers': 'test-transformers', 'resolvedDevice': 'cpu', 'dtype': 'float32', 'recipe': 'mms-greedy-20ms-v1'}


class MmsAsrTests(unittest.TestCase):
    def setUp(self):
        probe = patch.object(mms_asr, 'runtime_provenance', return_value=TEST_RUNTIME)
        probe.start()
        self.addCleanup(probe.stop)

    def test_runtime_or_device_change_cannot_reuse_or_relabel_existing_cache(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            project = root/'builder'
            prepare_runtime_layout(project)
            value = {'familyId': 'mms-asr', 'modelRevision': mms_asr.REVISION,
                'adapter': mms_asr.ADAPTER, 'assets': mms_asr.ASSETS,
                'audioSha256': 'a'*64, 'durationMs': 1000, 'frameMs': 20,
                'runtime': TEST_RUNTIME, 'words': [{'raw': 'test', 'start': .1, 'end': .4}]}
            def worker(argv, **_kwargs):
                Path(argv[argv.index('--output') + 1]).write_text(json.dumps(value))
                return SimpleNamespace(returncode=0, stderr='')
            args = dict(project=project, model_cache=root, job_dir=root, audio=root/'unused.wav',
                audio_identity={'sha256': 'a'*64}, duration_ms=1000, route='mix',
                device='cpu', timeout=10, env={}, rerun=False)
            with patch.object(mms_asr, 'run_process_tree', side_effect=worker) as inference:
                _, evidence, _ = mms_asr.load_or_transcribe(**args)
                self.assertEqual(evidence['runtime'], TEST_RUNTIME)
                cache = root / f'native-asr-{mms_asr.ADAPTER}-mix.private.json'
                original = json.loads(cache.read_bytes())
                for field, bad in (('torch', 'unrelated-runtime'), ('resolvedDevice', 'cuda'),
                                   ('dtype', 'float16'), ('recipe', 'different-decoder')):
                    changed = copy.deepcopy(original)
                    changed['workerOutput']['runtime'][field] = bad
                    cache.write_text(json.dumps(changed))
                    before = cache.read_bytes()
                    with self.assertRaises(FileNotFoundError):
                        mms_asr.load_or_transcribe(**args, cache_only=True)
                    self.assertEqual(cache.read_bytes(), before)
                self.assertEqual(inference.call_count, 1)

    def test_current_cache_requires_exact_helpers_and_preserves_a_different_producer(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            prepare_runtime_layout(root/'builder')
            value = {'familyId': 'mms-asr', 'modelRevision': mms_asr.REVISION,
                     'adapter': mms_asr.ADAPTER, 'assets': mms_asr.ASSETS,
                     'audioSha256': 'a'*64, 'durationMs': 1000, 'frameMs': 20, 'runtime': TEST_RUNTIME,
                     'words': [{'raw': 'test', 'start': .1, 'end': .4}]}
            def worker(argv, **kwargs):
                Path(argv[argv.index('--output')+1]).write_text(json.dumps(value))
                return SimpleNamespace(returncode=0, stderr='')
            args = dict(project=root/'builder', model_cache=root, job_dir=root,
                        audio=root/'missing.wav', audio_identity={'sha256': 'a'*64},
                        duration_ms=1000, route='mix', device='cpu', timeout=1, env={}, rerun=False)
            with patch.object(mms_asr, 'run_process_tree', side_effect=worker) as model:
                mms_asr.load_or_transcribe(**args)
                cache = root/f'native-asr-{mms_asr.ADAPTER}-mix.private.json'
                stored = json.loads(cache.read_text())
                current = stored['provenance']['helpers']
                binding = hashlib.sha256(json.dumps(current, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
                prior = {**current, 'mms_asr.py': 'b'*64, 'wav2vec2_forced_alignment.py': 'c'*64}
                stored['provenance']['helpers'] = prior
                cache.write_text(json.dumps(stored))
                before = cache.read_bytes()
                with self.assertRaises(FileNotFoundError):
                    mms_asr.load_or_transcribe(**args, cache_only=True)
                self.assertEqual(cache.read_bytes(), before)
                for name in prior:
                    changed = copy.deepcopy(stored)
                    changed['provenance']['helpers'][name] = 'd'*64
                    cache.write_text(json.dumps(changed))
                    with self.assertRaises(FileNotFoundError):
                        mms_asr.load_or_transcribe(**args, cache_only=True)
                self.assertEqual(model.call_count, 1)

    def test_cache_only_miss_never_starts_model_or_writes_a_cache(self):
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as directory:
            root = Path(directory)
            with patch.object(mms_asr, 'run_process_tree', side_effect=AssertionError('No cache-only inference')) as model:
                with self.assertRaises(FileNotFoundError):
                    mms_asr.load_or_transcribe(project=root, model_cache=root, job_dir=root,
                        audio=root / 'missing.wav', audio_identity={'sha256': 'a' * 64}, duration_ms=1000,
                        route='mix', device='cpu', timeout=10, env={}, rerun=False, cache_only=True)
            model.assert_not_called()
            self.assertFalse(list(root.iterdir()))


    def test_ctc_blanks_and_repeated_characters_keep_native_times(self):
        # Blank-separated identical letters survive; adjacent duplicates collapse.
        vocab = {0: "<pad>", 1: "a", 2: "|", 3: "b", 4: "<unk>"}
        words = mms_asr.decode_words([0, 1, 1, 0, 1, 2, 0, 3, 3, 0], vocab, 0, 2, 20, 200)
        self.assertEqual(words, [
            {"raw": "aa", "start": .02, "end": .10},
            {"raw": "b", "start": .14, "end": .18},
        ])
        self.assertEqual(mms_asr.decode_words([0, 0], vocab, 0, 2, 20, 40), [])
        with self.assertRaisesRegex(ValueError, "unknown"):
            mms_asr.decode_words([1, 4], vocab, 0, 2, 20, 40)
        with self.assertRaisesRegex(ValueError, "duration"):
            mms_asr.decode_words([1, 1, 1], vocab, 0, 2, 20, 30)

    def test_adapter_is_only_selected_for_supported_native_script(self):
        self.assertEqual(mms_asr.adapters_for({"language": "ku", "script": "Latn"}), ("kmr-script_latin",))
        self.assertEqual(mms_asr.adapters_for({"language": "zu", "script": "Latn"}), ("zul",))
        self.assertEqual(mms_asr.adapters_for({"language": "ZU-ZA", "script": "Latn"}), ("zul",))
        self.assertEqual(mms_asr.adapters_for({"language": "mul", "script": "Zyyy", "componentLanguages": ["ar", "en", "zu"]}), ("zul",))
        self.assertEqual(mms_asr.adapters_for({"language": "mul", "script": "Latn", "componentLanguages": ["ku", "zu", "ku"]}), ("kmr-script_latin", "zul"))
        for original in ({"language": "ku", "script": "Arab"}, {"language": "ar", "script": "Latn"}, {"language": "ku"}, {"language": "mul", "script": "Latn"}):
            self.assertEqual(mms_asr.adapters_for(original), ())


    def test_result_rejects_wrong_audio_adapter_and_invalid_word_times(self):
        value = {"familyId": "mms-asr", "modelRevision": mms_asr.REVISION,
                 "adapter": mms_asr.ADAPTER, "assets": mms_asr.ASSETS,
                 "audioSha256": "a" * 64, "durationMs": 1000, "frameMs": 20, "runtime": TEST_RUNTIME,
                 "words": [{"raw": "test", "start": .1, "end": .4}]}
        self.assertEqual(mms_asr.validate_output(value, "a" * 64, 1000), value["words"])
        zulu = {**value, "adapter": "zul", "assets": mms_asr.ASSETS_BY_ADAPTER["zul"]}
        self.assertEqual(mms_asr.validate_output(zulu, "a" * 64, 1000, "zul"), value["words"])
        with self.assertRaises(ValueError):
            mms_asr.validate_output(zulu, "a" * 64, 1000)
        for field, bad in (("audioSha256", "b" * 64), ("adapter", "eng"), ("assets", {}), ("durationMs", float("nan"))):
            with self.subTest(field=field), self.assertRaises(ValueError):
                mms_asr.validate_output({**value, field: bad}, "a" * 64, 1000)
        for bad in (float("nan"), -.1, 1.1):
            changed = copy.deepcopy(value)
            changed["words"][0]["start"] = bad
            with self.assertRaises(ValueError):
                mms_asr.validate_output(changed, "a" * 64, 1000)
        changed = copy.deepcopy(value)
        changed["words"].append({"raw": "overlap", "start": .3, "end": .5})
        with self.assertRaises(ValueError):
            mms_asr.validate_output(changed, "a" * 64, 1000)

    def test_cache_reuse_and_wrong_audio_worker_result_preserve_existing_data(self):
        # Substitute only inference; run real cache binding, output validation and conversion.
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temp:
            root = Path(temp)
            project = root/'builder'
            prepare_runtime_layout(project)
            audio = root / "audio.wav"
            audio.write_bytes(b"unchanged input PCM fixture")
            identity = {"sha256": mms_asr.sha256_file(audio)}
            value = {"familyId": "mms-asr", "modelRevision": mms_asr.REVISION,
                     "adapter": mms_asr.ADAPTER, "assets": mms_asr.ASSETS,
                     "audioSha256": identity["sha256"], "durationMs": 1000, "frameMs": 20, "runtime": TEST_RUNTIME,
                     "words": [{"raw": "test", "start": .1, "end": .4}]}

            def worker(argv, **_):
                adapter = argv[argv.index("--adapter") + 1]
                Path(argv[argv.index("--output") + 1]).write_text(json.dumps({
                    **value, "adapter": adapter, "assets": mms_asr.ASSETS_BY_ADAPTER[adapter],
                }))
                return SimpleNamespace(returncode=0, stderr="")

            args = dict(project=project, model_cache=root, job_dir=root, audio=audio,
                        audio_identity=identity, duration_ms=1000, route="mix",
                        device="cpu", timeout=10, env={}, rerun=False)
            with patch.object(mms_asr, "run_process_tree", side_effect=worker) as inference:
                words, _, reused = mms_asr.load_or_transcribe(**args)
                self.assertFalse(reused)
                self.assertEqual([(w.raw, w.start, w.end) for w in words], [("test", .1, .4)])
                self.assertTrue(mms_asr.load_or_transcribe(**args)[2])
                self.assertEqual(inference.call_count, 1)
                self.assertFalse(mms_asr.load_or_transcribe(**args, adapter="zul")[2])
                self.assertEqual(inference.call_count, 2)
                self.assertTrue(mms_asr.load_or_transcribe(**args)[2])
                self.assertEqual(inference.call_count, 2)
                cache = root / f"native-asr-{mms_asr.ADAPTER}-mix.private.json"
                before = cache.read_bytes()
                value["audioSha256"] = "0" * 64
                with self.assertRaisesRegex(ValueError, "binding differs"):
                    mms_asr.load_or_transcribe(**{**args, "rerun": True})
                self.assertEqual(cache.read_bytes(), before)
                self.assertEqual(audio.read_bytes(), b"unchanged input PCM fixture")


if __name__ == "__main__":
    unittest.main()
