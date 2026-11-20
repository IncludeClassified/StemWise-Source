from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.mms_forced_alignment import FORMAT_VERSION, _request_plan, run
from stemwise_engine.ctc_worker import SEGMENT_BOUNDARY_GUARD_MS, align_ctc, chunk_time_map, segment_word_range, validate_global_results, validate_segment_boundary, window_to_emission_frames


def request() -> dict[str, object]:
    return {
        "formatVersion": FORMAT_VERSION,
        "durationMs": 10_000,
        "lines": [
            {
                "lineId": "line-1",
                "sourcePhysicalIndex": 0,
                "words": [{"wordId": "word-1", "acousticText": "one"}],
            },
            {
                "lineId": "line-2",
                "sourcePhysicalIndex": 1,
                "words": [{"wordId": "word-2", "acousticText": "two"}],
            },
        ],
        "segments": [
            {
                "segmentIndex": 0,
                "startLineIndex": 0,
                "endLineIndex": 1,
                "windowStartMs": 0,
                "windowEndMs": 5_000,
            },
            {
                "segmentIndex": 1,
                "startLineIndex": 1,
                "endLineIndex": 2,
                "windowStartMs": 5_000,
                "windowEndMs": 10_000,
            },
        ],
    }


class MmsSegmentTests(unittest.TestCase):
    def test_actual_decoder_repairs_short_repeated_targets_without_changing_words(self):
        import torch
        import stemwise_engine.mms_forced_alignment as worker
        from stemwise_engine.ctc_worker import decode_repaired
        from stemwise_engine.worker_protocol import WorkerReasonError
        value = request()
        value['durationMs'] = 4000
        value['segments'][0]['windowEndMs'] = 2000
        value['segments'][1].update(windowStartMs=2000, windowEndMs=4000)
        records, transcript, _, _ = worker._request_plan(value)
        emission = torch.tensor([[.01, .98, .01], [.98, .01, .01],
                                 [.01, .98, .01], [.01, .01, .98]]).log()
        prepared = dict(emission=emission, device='cpu', tokenizer=lambda words:
            [{'one': [1, 1], 'two': [2]}[word] for word in words], transcript=transcript,
            segments=value['segments'], records=records, duration_ms=4000, sample_rate=1000,
            chunk_map=[chunk_time_map(0, 4000, 0, 4)], source_sample_rate=1000,
            source_channel_count=1, source_frame_count=4000, sample_count=4000, chunk_samples=4000)
        before = copy.deepcopy(value)
        with self.assertRaises(WorkerReasonError) as caught:
            decode_repaired(prepared, value, worker._decode, False)
        self.assertEqual(caught.exception.reason_code, 'wav2vec2-segment-too-short:0')
        result, resolved, merges = decode_repaired(prepared, value, worker._decode, True)
        self.assertEqual(len(resolved['segments']), 1)
        self.assertEqual(merges, [{'segmentIndex': 0, 'boundary': None}])
        self.assertEqual([[w['wordId'] for w in line['words']] for line in result['lines']],
                         [['word-1'], ['word-2']])
        self.assertEqual(result['acousticTokenCount'], 3)
        self.assertEqual(value, before)
        too_short = {**prepared, 'emission': emission[:2], 'segment_results': {},
            'chunk_map': [chunk_time_map(0, 4000, 0, 2)]}
        with self.assertRaisesRegex(ValueError, 'cannot be widened'):
            decode_repaired(too_short, resolved, worker._decode, True)

    def test_numeric_targets_cannot_be_silently_dropped_or_partially_romanized(self):
        from stemwise_engine.workflow_records import LyricsContentBlocker
        for text in ('42', '1999', '24/7', 'B52', '١٢', 'Ⅻ'):
            value = request()
            value['lines'][0]['words'][0]['acousticText'] = text
            with self.subTest(text=text), self.assertRaises(LyricsContentBlocker) as caught:
                _request_plan(value)
            self.assertEqual(caught.exception.reasons, ('pronunciation-support-unavailable',))
        value = request()
        value['lines'][0]['words'][0]['acousticText'] = 'forty two'
        records, targets, _, _ = _request_plan(value)
        self.assertEqual(targets, ['forty', 'two', 'two'])
        self.assertEqual(records[0]['words'], [{'startWord': 0, 'wordCount': 2, 'wordId': 'word-1'}])

    def test_streamed_windows_match_full_reference_resampling_and_global_frame_count(self):
        import numpy as np
        import soundfile as sf
        import torch
        import torchaudio
        from stemwise_engine.acoustic_inputs import StreamedMonoAudio, mono_tensor
        previous_threads = torch.get_num_threads()
        torch.set_num_threads(1)
        self.addCleanup(torch.set_num_threads, previous_threads)
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            path = Path(name)/'source.wav'
            for rate in (44100, 48000, 96000):
                signal = (.2*np.sin(np.arange(rate*2+17)*.031)).astype('float32')
                for mode in ('mono', 'mid', 'left'):
                    values = signal[:, None] if mode == 'mono' else np.column_stack((signal,
                        -signal if mode == 'left' else signal*.7))
                    sf.write(path, values, rate, subtype='FLOAT')
                    expected = torchaudio.functional.resample(mono_tensor(torch.from_numpy(values.T)), rate, 16000)
                    audio = StreamedMonoAudio(path, 16000)
                    self.assertEqual(audio.representation, mode)
                    self.assertEqual(audio.sample_count, expected.shape[-1])
                    for first, last in ((0, 711), (15983, 17001), (audio.sample_count-19, audio.sample_count)):
                        torch.testing.assert_close(audio.read(first, last), expected[:, first:last], rtol=0, atol=1e-7)

    def test_both_workers_stream_twenty_minutes_and_maximum_duration_cold_and_warm(self):
        from stemwise_engine.common import run_process_tree
        code = """import sys
sys.path.insert(0, sys.argv[1])
from test_mms_forced_alignment import MmsSegmentTests
case = MmsSegmentTests()
try:
    case._assert_streaming_worker_memory(memory_fault=bool(int(sys.argv[2])))
finally:
    case.doCleanups()
"""
        earlier_test_peak = bytearray(96*1024*1024)
        del earlier_test_peak
        for fault in (False, True):
            result = run_process_tree([sys.executable, '-B', '-c', code,
                str(Path(__file__).resolve().parent), str(int(fault))],
                capture_output=True, text=True, timeout=180)
            if fault:
                self.assertNotEqual(result.returncode, 0, 'Over-budget allocation was hidden by a prior peak')
                self.assertIn('working memory budget', result.stderr)
            else:
                self.assertEqual(result.returncode, 0, result.stderr)

    def _assert_streaming_worker_memory(self, *, memory_fault=False):
        import numpy as np
        import resource
        import soundfile as sf
        import torch
        import torchaudio
        import transformers
        from transformers import (AutoConfig, AutoModelForCTC, Wav2Vec2CTCTokenizer,
            Wav2Vec2PhonemeCTCTokenizer, Wav2Vec2FeatureExtractor)
        import stemwise_engine.mms_forced_alignment as mms
        import stemwise_engine.wav2vec2_forced_alignment as wav2vec
        previous_threads = torch.get_num_threads()
        torch.set_num_threads(1)
        self.addCleanup(torch.set_num_threads, previous_threads)
        class Model:
            config = SimpleNamespace(inputs_to_logits_ratio=320)
            def to(self, *_): return self
            def __call__(self, samples=None, input_values=None):
                samples = samples if samples is not None else input_values
                calls.append(int(samples.shape[-1]))
                values = torch.zeros((1, round(samples.shape[-1]/320), 4))
                return (values, None) if samples is not input_values else SimpleNamespace(logits=values)
        class Extractor:
            def __call__(self, values, **kwargs):
                return {'input_values': torch.from_numpy(values).reshape(1, -1)}
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            model_path = root/'model'
            model_path.mkdir()
            resident_kib = int(next(line.split()[1] for line in Path('/proc/self/status').read_text().splitlines()
                                    if line.startswith('VmRSS:')))
            peak_budget_kib = resident_kib + 256*1024
            for duration in (1200, 7200):
                info = SimpleNamespace(frames=duration*48000, channels=2, samplerate=48000)
                reads, calls = [], []
                class Source:
                    frames, channels, samplerate = info.frames, 2, 48000
                    def __init__(self, *_): pass
                    def __enter__(self): return self
                    def __exit__(self, *_): pass
                    def seek(self, first): self.first = first
                    def read(self, count, **kwargs):
                        reads.append(count)
                        values = (.1*np.sin((np.arange(count)+self.first)*.031)).astype('float32')
                        return np.column_stack((values, values))
                plan = {'formatVersion': FORMAT_VERSION, 'durationMs': duration*1000,
                    'lines': [{'lineId': 'one', 'sourcePhysicalIndex': 0,
                        'words': [{'wordId': 'one', 'acousticText': 'a'}]}],
                    'segments': [{'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 1,
                        'windowStartMs': 0, 'windowEndMs': duration*1000}]}
                bundle = SimpleNamespace(sample_rate=16000, get_model=lambda **_: Model(),
                    get_labels=lambda **_: tuple(range(4)), get_tokenizer=lambda: lambda words: [[1] for _ in words])
                with patch.object(sf, 'info', return_value=info), patch.object(sf, 'SoundFile', Source), \
                        patch('stemwise_engine.acoustic_inputs.audio_mono_representation', return_value='mid'), \
                        patch.object(torchaudio, 'load', side_effect=AssertionError('No whole-source allocation')), \
                        patch.object(torchaudio.pipelines, 'MMS_FA', bundle), \
                        patch.object(transformers.AutoConfig, 'from_pretrained',
                            return_value=SimpleNamespace(pad_token_id=0, vocab_size=4)):
                    for worker, digit in ((mms, 'a'), (wav2vec, 'b')):
                        directory = root/f'{duration}-{digit}'
                        directory.mkdir()
                        state = {} if worker is mms else {'model': Model(), 'target_runtime': (
                            SimpleNamespace(pad_token_id=0), False, Extractor())}
                        def prepare():
                            args = (root/'source.wav', directory/'request.json')
                            if worker is wav2vec:
                                args += (model_path, 'en')
                            return worker._prepare(*args, 'cpu', state, emission_cache_key=digit*64,
                                request_value=plan, emissions_only=True)
                        prepared = prepare()
                        maps = prepared.get('chunk_map', prepared.get('chunks'))
                        self.assertEqual(maps[0]['targetStartFrame'], 0)
                        self.assertEqual(maps[-1]['targetEndFrame'], duration*16000)
                        self.assertEqual(maps[-1]['emissionEndFrame'], prepared['emission'].shape[0])
                        before = len(reads), len(calls)
                        warmed = prepare()
                        self.assertEqual((len(reads), len(calls)), before)
                        self.assertEqual(warmed['emission'].shape, prepared['emission'].shape)
                        self.assertTrue(warmed['cache_status']['ready'])
                self.assertLess(max(reads), 31*48000)
            # Model weights are substituted; measure working PCM/posterior overhead.
            injected = bytearray(300*1024*1024) if memory_fault else None
            self.assertLess(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss, peak_budget_kib,
                            'streaming worker exceeded working memory budget')

    def test_cold_and_warm_emission_preparation_accepts_180_seconds_without_lyric_context(self):
        import numpy as np
        import soundfile as sf
        import torch
        import torchaudio
        import stemwise_engine.mms_forced_alignment as worker
        calls = []
        class Model:
            def to(self, *_): return self
            def __call__(self, samples):
                calls.append(samples.shape[-1])
                return torch.zeros(1, samples.shape[-1]//320, 2), None
        bundle = SimpleNamespace(sample_rate=16000, get_model=lambda **_: Model(),
            get_labels=lambda **_: ('-', 'a'), get_tokenizer=lambda: lambda words: [[1] for _ in words])
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as temporary:
            root = Path(temporary)
            audio = root/'audio.wav'
            signal = (.1*np.sin(np.arange(180*16000)/20)).astype('float32')
            sf.write(audio, signal, 16000, subtype='FLOAT')
            plan = {'formatVersion': FORMAT_VERSION, 'durationMs': 180000,
                'lines': [{'lineId': 'one', 'sourcePhysicalIndex': 0,
                    'words': [{'wordId': 'one', 'acousticText': 'a'}]}],
                'segments': [{'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 1,
                    'windowStartMs': 0, 'windowEndMs': 180000}]}
            with patch.object(torchaudio.pipelines, 'MMS_FA', bundle), patch.object(torchaudio, 'load',
                    return_value=(torch.from_numpy(signal).unsqueeze(0), 16000)):
                import hashlib
                manifest = root/'manifest.json'
                cache = root/('emissions-'+'a'*64+'.pt')
                manifest.write_text(json.dumps({'formatVersion': 1, 'timeoutSeconds': 20,
                    'contexts': [{'contextId': 'one', 'audio': str(audio),
                        'audioSha256': hashlib.sha256(audio.read_bytes()).hexdigest(),
                        'durationMs': 180000, 'emissionCacheKey': 'a'*64, 'cacheOutput': str(cache)}]}))
                for _ in range(2):
                    worker.prepare_many(manifest, root/'reply.json', 'cpu')
                    result = torch.load(cache, weights_only=True)
                    self.assertEqual(result['emission'].shape[0], 9000)
                    self.assertEqual(json.loads((root/'reply.json').read_text())['contexts'][0]['durationMs'], 180000)
                self.assertEqual(calls, [480000]*6)
                prepared = worker._prepare(audio, root/'request.json', 'cpu', {}, request_value=plan)
                self.assertEqual(prepared['duration_ms'], 180000)
                self.assertEqual(tuple(prepared['emission'].shape), (9000, 2))
                self.assertEqual(prepared['transcript'], ['a'])
                self.assertEqual(prepared['chunk_map'][0]['targetStartFrame'], 0)
                self.assertEqual(prepared['chunk_map'][-1]['targetEndFrame'], 180*16000)
                self.assertEqual(prepared['chunk_map'][-1]['emissionEndFrame'], 9000)
                self.assertEqual(calls, [480000]*12)
                from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
                state = {}
                with patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=0), \
                        self.assertRaisesRegex(AcousticBudgetExceeded, 'acoustic-memory-capacity-unavailable'):
                    worker._prepare(audio, root/'request.json', 'cpu', state, request_value=plan)
                self.assertNotIn('model', state)
                self.assertEqual(calls, [480000]*12)

    def test_worker_preserves_word_confidence_when_excluding_outro(self):
        import torch
        import torchaudio

        emission = torch.tensor([[.98, .01, .005, .005], [.01, .98, .005, .005],
            *[[.01, .005, .98, .005]] * 2, *[[.001, .001, .001, .997]] * 4]).log()

        class Model:
            def __init__(self, with_star=True):
                self.with_star = with_star

            def to(self, _device):
                return self

            def __call__(self, _waveform):
                values = torch.cat((emission, torch.zeros(len(emission), 1)), dim=1) if self.with_star else emission
                return values.unsqueeze(0), None

        bundle = SimpleNamespace(sample_rate=16000, get_model=Model,
            get_labels=lambda **kwargs: tuple(range(4)),
            get_tokenizer=lambda: lambda words: [{"one": [1], "two": [2]}[word] for word in words])
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            value = request()
            value["durationMs"] = 800
            value["segments"] = [{"segmentIndex": 0, "startLineIndex": 0,
                "endLineIndex": 2, "windowStartMs": 0, "windowEndMs": 800}]
            request_path = root / "request.json"
            request_path.write_text(json.dumps(value), encoding="utf-8")
            output = root / "output.json"
            signal = torch.linspace(-.1,.1,12800).reshape(1,-1)
            import soundfile
            soundfile.write(root/'synthetic.wav',signal.numpy().T,16000,subtype='FLOAT')
            with patch.object(torchaudio.pipelines, "MMS_FA", bundle), \
                    patch.object(torchaudio, "load", return_value=(signal, 16000)):
                run(root / "synthetic.wav", request_path, output, "cpu")
            result = json.loads(output.read_bytes())
            words = [word for line in result["lines"] for word in line["words"]]
            self.assertEqual([(w["startMs"], w["endMs"]) for w in words], [(100, 200), (200, 400)])
            self.assertEqual([w["wordId"] for w in words], ["word-1", "word-2"])
            for word in words:
                self.assertAlmostEqual(word["confidence"], .98, places=6)
            self.assertEqual(json.loads(request_path.read_bytes()), value)

    def test_terminal_wildcard_preserves_repeated_tokens_and_real_eof(self):
        import torch
        import torchaudio

        emission = torch.tensor([[.98, .01, .01], [.01, .01, .98],
                                 [.98, .01, .01], [.01, .989, .001]]).log()
        before = emission.clone()
        targets = [0, 0]
        for frames, trailing, expected_end in ((4, True, 3), (3, True, 3), (4, False, 4)):
            paths, scores = align_ctc(emission[:frames], targets, blank=2,
                                       allow_trailing_audio=trailing)
            spans = torchaudio.functional.merge_tokens(paths[0], scores[0].exp(), blank=2)
            self.assertEqual([(s.token, s.start, s.end) for s in spans],
                             [(0, 0, 1), (0, 2, expected_end)])
            self.assertEqual(tuple(paths.shape), (1, frames))
            if trailing:
                self.assertAlmostEqual(spans[-1].score, .98, places=6)
            self.assertTrue(torch.equal(emission, before))
            self.assertEqual(targets, [0, 0])

    def test_mms_request_and_result_format_is_v5(self) -> None:
        self.assertEqual(FORMAT_VERSION, 5)

    def test_request_requires_exact_ordered_covering_segments(self) -> None:
        records, words, duration_ms, segments = _request_plan(request())
        self.assertEqual((len(records), words, duration_ms), (2, ["one", "two"], 10_000))
        self.assertEqual([segment["segmentIndex"] for segment in segments], [0, 1])

        malformed = []
        extra_key = copy.deepcopy(request())
        extra_key["segments"][0]["extra"] = 1  # type: ignore[index]
        malformed.append(extra_key)
        gap = copy.deepcopy(request())
        gap["segments"][1]["startLineIndex"] = 0  # type: ignore[index]
        malformed.append(gap)
        reversed_window = copy.deepcopy(request())
        reversed_window["segments"][1]["windowEndMs"] = 5_000  # type: ignore[index]
        malformed.append(reversed_window)
        overlapping_window = copy.deepcopy(request())
        overlapping_window["segments"][1]["windowStartMs"] = 4_999  # type: ignore[index]
        malformed.append(overlapping_window)
        missing_duration = copy.deepcopy(request())
        del missing_duration["durationMs"]
        malformed.append(missing_duration)

        for index, value in enumerate(malformed):
            with self.subTest(index=index), self.assertRaisesRegex(ValueError, "segment|duration"):
                _request_plan(value)

    def test_window_conversion_floors_starts_ceils_ends_and_preserves_global(self) -> None:
        chunks = [
            chunk_time_map(0, 1_000, 0, 3),
            chunk_time_map(1_000, 2_000, 3, 8),
        ]
        self.assertEqual(
            window_to_emission_frames(333, 1_501, 2_000, 1_000, chunks),
            (0, 6),
        )
        self.assertEqual(
            window_to_emission_frames(0, 2_000, 2_000, 1_000, chunks),
            (0, 8),
        )

    def test_segment_word_slices_cover_global_target_once(self) -> None:
        records, words, _, segments = _request_plan(request())
        ranges = [segment_word_range(records, segment) for segment in segments]
        self.assertEqual(ranges, [(0, 1), (1, 2)])
        self.assertEqual([word for start, end in ranges for word in words[start:end]], words)

    def test_artificial_boundary_guard_rejects_close_results(self) -> None:
        segment = {
            "segmentIndex": 1,
            "startLineIndex": 1,
            "endLineIndex": 2,
            "windowStartMs": 1_000,
            "windowEndMs": 4_000,
        }
        validate_segment_boundary(
            [{"startMs": 1_000 + SEGMENT_BOUNDARY_GUARD_MS, "endMs": 3_750}],
            segment,
            10_000,
        )
        with self.assertRaisesRegex(ValueError, "boundary guard"):
            validate_segment_boundary(
                [{"startMs": 1_249, "endMs": 3_500}], segment, 10_000
            )
        with self.assertRaisesRegex(ValueError, "boundary guard"):
            validate_segment_boundary(
                [{"startMs": 1_500, "endMs": 3_751}], segment, 10_000
            )

    def test_global_result_validation_rejects_reordered_words(self) -> None:
        records, _, duration_ms, _ = _request_plan(request())
        lines = [
            {
                "lineIndex": index,
                "sourcePhysicalIndex": record["sourcePhysicalIndex"],
                "lineId": record["lineId"],
                "words": [
                    {
                        "wordId": record["words"][0]["wordId"],
                        "startMs": start,
                        "endMs": end,
                    }
                ],
            }
            for index, (record, start, end) in enumerate(
                zip(records, (1_000, 900), (1_200, 1_100))
            )
        ]
        with self.assertRaisesRegex(ValueError, "globally ordered"):
            validate_global_results(lines, records, duration_ms)


if __name__ == "__main__":
    unittest.main()
