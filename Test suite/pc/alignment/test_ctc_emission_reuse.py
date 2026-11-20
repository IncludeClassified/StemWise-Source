"""Retry windows must reuse inference, never reuse decoded lyric decisions."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest.mock import patch

PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"
from stemwise_engine.mms_forced_alignment import run as run_mms
from stemwise_engine.wav2vec2_forced_alignment import run as run_wav


class EmissionReuseTests(unittest.TestCase):
    def test_mms_counts_display_romanized_words_and_actual_acoustic_tokens_separately(self):
        from stemwise_engine.mms_forced_alignment import _aggregate_records
        lines = _aggregate_records([{'lineIndex': 0, 'sourcePhysicalIndex': 0, 'lineId': 'line',
            'startWord': 0, 'wordCount': 2, 'words': [{'wordId': 'word', 'startWord': 0, 'wordCount': 2}]}],
            [{'startMs': 100, 'endMs': 200, 'confidence': .9, 'tokenCount': 3},
             {'startMs': 200, 'endMs': 400, 'confidence': .8, 'tokenCount': 4}])
        self.assertEqual(lines[0]['displayWordCount'], 1)
        self.assertEqual(lines[0]['romanizedWordCount'], 2)
        self.assertEqual(lines[0]['acousticTokenCount'], 7)
        self.assertEqual(lines[0]['words'][0]['acousticTokenCount'], 7)
        self.assertEqual(lines[0]['words'][0]['romanizedWordCount'], 2)
        self.assertNotIn('acousticWordCount', lines[0])

    def test_optional_cache_write_failure_keeps_computed_result_but_required_publish_fails(self):
        import torch
        import stemwise_engine.ctc_worker as ctc_worker
        import stemwise_engine.mms_forced_alignment as worker
        from unittest.mock import Mock
        work = Path(__file__).resolve().parents[2] / 'work'
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name)
            request = root / 'request.json'
            key = 'a' * 64
            emission = torch.zeros((2, 3))
            chunks = [ctc_worker.chunk_time_map(0, 100, 0, 2)]
            result = (emission, chunks, 100)
            destination = root / f'emissions-{key}.pt'
            for error_type in (RuntimeError, OSError):
                with self.subTest(error=error_type.__name__):
                    status = {'ready': True, 'identity': {'stale': True}}
                    produce = Mock(return_value=result)
                    def fail_save(payload, temporary):
                        Path(temporary).write_bytes(b'partial serialization')
                        raise error_type('serialization failed')
                    with patch.object(torch, 'save', side_effect=fail_save):
                        actual = ctc_worker.cached_emissions(request, key, 100, 3, produce,
                                                          cache_status=status)
                    self.assertIs(actual[0], emission)
                    produce.assert_called_once_with()
                    self.assertFalse(status['ready'])
                    self.assertNotIn('identity', status)
                    self.assertFalse(destination.with_suffix('.tmp').exists())
                    diagnostic = status['writeDiagnostic']
                    body = (root / diagnostic['file']).read_text(encoding='utf-8')
                    self.assertIn('serialization failed', body)
                    self.assertLessEqual(diagnostic['byteCount'], 12000)
                    prepared = {'sample_count': 100, 'emission': emission,
                        'chunk_samples': 100, 'chunks': chunks, 'cache_status': status}
                    with patch.object(torch, 'save', side_effect=fail_save), self.assertRaises(error_type):
                        ctc_worker.publish_emissions({'cacheOutput': str(destination),
                            'emissionCacheKey': key}, prepared)
                    self.assertFalse(destination.with_suffix('.tmp').exists())

    def test_both_workers_redecode_windows_and_recover_invalid_cache(self):
        import torch
        import torchaudio
        import transformers

        # Independent acoustic reference: A at 300–500 ms, B at 1500–1700 ms.
        logits = torch.full((40, 5), -8.0)
        logits[:, 0] = 8.0
        for start, end, token in ((6, 10, 1), (30, 34, 2)):
            logits[start:end, 0] = -8.0
            logits[start:end, token] = 8.0
        logits[20, 0], logits[20, 3] = -8.0, 8.0  # Wav2Vec2 word delimiter.
        config = SimpleNamespace(pad_token_id=0, vocab_size=5, inputs_to_logits_ratio=800)
        calls = []

        class Model:
            def __init__(self, *args, **kwargs):
                calls.append(1)
                self.config = config

            def to(self, *args, **kwargs):
                return self

            def eval(self):
                return self

            def __call__(self, *args, **kwargs):
                if args:
                    return logits.log_softmax(-1).unsqueeze(0), None
                return SimpleNamespace(logits=logits.unsqueeze(0))

        bundle = SimpleNamespace(sample_rate=16000, get_model=Model,
            get_labels=lambda **kwargs: tuple(range(5)),
            get_tokenizer=lambda: lambda words: [{"a": [1], "b": [2]}[word] for word in words])
        tokenizer = SimpleNamespace(word_delimiter_token_id=3, unk_token_id=4,
            all_special_ids=[0, 4], pad_token_id=0,
            get_vocab=lambda: {"<pad>": 0, "a": 1, "b": 2, "|": 3, "<unk>": 4})
        class Extractor:
            sampling_rate = 16000
            def __call__(self, samples, **kwargs):
                return {"input_values": torch.tensor(samples).unsqueeze(0)}

        with tempfile.TemporaryDirectory() as name, \
                patch.object(torchaudio.pipelines, "MMS_FA", bundle), \
                patch.object(torchaudio, "load", return_value=(torch.sin(torch.arange(32000).float()/20).unsqueeze(0)*.1, 16000)), \
                patch.object(transformers.AutoConfig, "from_pretrained", return_value=config), \
                patch.object(transformers.AutoModelForCTC, "from_pretrained", side_effect=Model), \
                patch.object(transformers.Wav2Vec2CTCTokenizer, "from_pretrained", return_value=tokenizer), \
                patch.object(transformers.Wav2Vec2FeatureExtractor, "from_pretrained", return_value=Extractor()):
            root = Path(name)
            audio = root / "synthetic.wav"
            import numpy as np
            import soundfile as sf
            sf.write(audio, .1*np.sin(np.arange(32000)/20), 16000, subtype='FLOAT')
            model = root / "model"
            model.mkdir()
            (model / "tokenizer_config.json").write_text(json.dumps({"tokenizer_class": "Wav2Vec2CTCTokenizer"}))
            request = {"formatVersion": 5, "durationMs": 2000,
                "lines": [{"sourcePhysicalIndex": i, "lineId": f"line-{word}",
                    "words": [{"wordId": f"word-{word}", "acousticText": word}]} for i, word in enumerate("ab")]}
            whole = [{"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": 2,
                "windowStartMs": 0, "windowEndMs": 2000}]
            split = [{"segmentIndex": i, "startLineIndex": i, "endLineIndex": i+1,
                "windowStartMs": i*1000, "windowEndMs": (i+1)*1000} for i in range(2)]
            for worker in ("mms", "wav"):
                with self.subTest(worker=worker):
                    forbid_model_import = [False]
                    proxy = ModuleType("transformers")
                    def library_attribute(name):
                        if name == "AutoModelForCTC" and forbid_model_import[0]:
                            raise AssertionError("cache hit imported the unused model loader")
                        return getattr(transformers, name)
                    proxy.__getattr__ = library_attribute
                    before = len(calls)
                    key = ("a" if worker == "mms" else "b") * 64
                    output = root / "output.json"
                    def execute(segments, current_key=key):
                        request["segments"] = segments
                        path = root / f"{worker}-{len(segments)}.request.json"
                        path.write_text(json.dumps(request), encoding="utf-8")
                        if worker == "mms":
                            run_mms(audio, path, output, "cpu", emission_cache_key=current_key)
                        else:
                            with patch.dict(sys.modules, {"transformers": proxy}):
                                run_wav(audio, path, output, model, "en", "cpu", emission_cache_key=current_key)
                        result = json.loads(output.read_bytes())
                        self.assertEqual(result["segmentCount"], len(segments))
                        self.assertEqual(result['displayWordCount'], 2)
                        self.assertEqual(result['acousticTokenCount'], 2)
                        from stemwise_engine.lyrics_align_pc import _validate_numeric_mms_result
                        _validate_numeric_mms_result(result, expected_sources=[0, 1], duration_ms=2000,
                            expected_line_ids=['line-a', 'line-b'], expected_word_ids=[['word-a'], ['word-b']],
                            expected_format_version=5 if worker == 'mms' else 4,
                            expected_engine='torchaudio-mms-fa' if worker == 'mms' else 'wav2vec2-ctc-forced-alignment',
                            expected_segments=segments)
                        words = [word for line in result["lines"] for word in line["words"]]
                        self.assertEqual([(w["wordId"], w["startMs"], w["endMs"]) for w in words],
                                         [("word-a", 300, 500), ("word-b", 1500, 1700)])
                        self.assertEqual(json.loads(path.read_bytes()), request)
                        return result
                    original = execute(whole)
                    forbid_model_import[0] = True
                    retried = execute(split)
                    forbid_model_import[0] = False
                    self.assertEqual(original["lines"], retried["lines"])
                    self.assertEqual(len(calls) - before, 1, "changed windows must not repeat inference")
                    # Even a cache hit must enforce the current artificial boundary.
                    invalid = [dict(segment) for segment in split]
                    invalid[0]["windowEndMs"] = invalid[1]["windowStartMs"] = 1600
                    with self.assertRaisesRegex(ValueError, "boundary guard"):
                        execute(invalid)
                    self.assertEqual(json.loads(output.read_bytes()), retried)
                    self.assertEqual(len(calls) - before, 1)
                    # Broken tensors and different bound inputs require fresh inference.
                    (root / f"emissions-{key}.pt").write_bytes(b"invalid cache")
                    execute(split)
                    self.assertEqual(len(calls) - before, 2)
                    execute(split, ("c" if worker == "mms" else "d") * 64)
                    self.assertEqual(len(calls) - before, 3)
                    saved = torch.load(root / f"emissions-{key}.pt", weights_only=True)
                    for damage in ("nonfinite", "map-gap", "wrong-sample-count"):
                        broken = {**saved, "emission": saved["emission"].clone(),
                                  "chunks": [dict(chunk) for chunk in saved["chunks"]]}
                        if damage == "nonfinite":
                            broken["emission"][0, 0] = float("nan")
                        elif damage == "map-gap":
                            broken["chunks"][0]["targetStartFrame"] = 1
                        else:
                            broken["sampleCount"] += 1
                        torch.save(broken, root / f"emissions-{key}.pt")
                        expected_calls = len(calls) + 1
                        execute(split)
                        self.assertEqual(len(calls), expected_calls, damage)


if __name__ == "__main__":
    unittest.main()
