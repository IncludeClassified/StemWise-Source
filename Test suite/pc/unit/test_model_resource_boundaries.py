"""Production model entry points reject unsafe allocations and incomplete output."""
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]

class ModelResourceBoundaries(unittest.TestCase):
    def test_roformer_rejects_large_pcm_before_decoding_or_model_allocation(self):
        import torchaudio
        import stemwise_engine.analysis_resources
        import stemwise_engine.acoustic_inputs
        import yaml
        import stemwise_engine.roformer_separation as worker
        from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            root = Path(temporary)
            config, output = root/'config.yaml', root/'prior.wav'
            config.write_text('model:\n  sample_rate: 48000\n')
            output.write_bytes(b'last good source')
            package = SimpleNamespace(demix_track=Mock(side_effect=AssertionError('model forbidden')),
                                      get_model_from_config=Mock(side_effect=AssertionError('model forbidden')))
            args = SimpleNamespace(route=next(iter(worker.ROUTES)), config=config, checkpoint=root/'checkpoint',
                checkpoint_sha256='a'*64, config_sha256='a'*64, audio=root/'long.wav',
                input_sha256='a'*64, output=output)
            with patch.dict(sys.modules, {'mel_band_roformer': package,
                    'mel_band_roformer.inference': SimpleNamespace(SafeLoaderWithTuple=yaml.SafeLoader),
                    'ml_collections': SimpleNamespace(ConfigDict=lambda value: SimpleNamespace(
                        model=SimpleNamespace(**value['model']),
                        training=SimpleNamespace(target_instrument='vocals')))}), \
                    patch.object(worker, '_package_identity', return_value={}), \
                    patch.object(worker, '_sha256_file', return_value='a'*64), \
                    patch('stemwise_engine.alignment_models.require_pinned_file'), \
                    patch('torchaudio.load', side_effect=RuntimeError('decode boundary')):
                for duration in (180, 300, 3600):
                    with self.subTest(duration=duration), patch('soundfile.info', return_value=SimpleNamespace(
                            frames=48_000*duration, channels=2, samplerate=48_000)):
                        with patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=128*1024**2 if duration == 3600 else 10*1024**3), self.assertRaisesRegex(AcousticBudgetExceeded if duration == 3600 else RuntimeError, 'capacity-unavailable' if duration == 3600 else 'decode boundary'):
                            worker.run(args)
            self.assertEqual(output.read_bytes(), b'last good source')

    def test_qwen_generation_boundary_retains_unusable_observation_without_lexical_claim(self):
        import torch
        import stemwise_engine.qwen3_asr_transcribe as worker
        request = {'formatVersion': worker.FORMAT_VERSION, 'language': None,
            'audio': {'path': '/audio.wav', 'sha256': 'a'*64, 'byteCount': 12},
            'model': {'path': '/snapshots/'+'b'*40, 'revision': 'b'*40, 'snapshotSha256': 'c'*64}}
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            output = Path(temporary)/'last-good.json'
            request_path = Path(temporary)/'request.json'
            request_path.write_bytes(b'{}')
            output.write_bytes(b'last good')
            model = Mock()
            model.to.return_value = model
            model.generation_config.eos_token_id = [9, 10]
            processor = Mock()
            processor.apply_transcription_request.return_value = {'input_ids': torch.tensor([[1]])}
            from types import ModuleType
            module = ModuleType('transformers.models.qwen3_asr.processing_qwen3_asr')
            module.LANGUAGE_CODE_TO_NAME = {'en': 'English'}
            sys.path.insert(0, str(ROOT/'Test suite/pc/alignment'))
            from test_qwen3_asr_transcribe import CharacterTokenizer, output_fixture
            processor.tokenizer = CharacterTokenizer()
            runtime = output_fixture(request)['runtime']
            runtime.update(requestedDevice='cpu', resolvedDevice='cpu', cudaAvailable=False,
                cudaDeviceName=None, cudaRuntime=None, cudnn=None)
            bindings = {'AutoConfig': Mock(), 'AutoProcessor': Mock(), 'AutoModelForSpeechSeq2Seq': Mock()}
            bindings['AutoConfig'].from_pretrained.return_value = SimpleNamespace(model_type='qwen3_asr')
            bindings['AutoProcessor'].from_pretrained.return_value = processor
            bindings['AutoModelForSpeechSeq2Seq'].from_pretrained.return_value = model
            with patch.dict(sys.modules, {'transformers': SimpleNamespace(**bindings),
                    'transformers.models.qwen3_asr.processing_qwen3_asr': module}), \
                    patch.object(worker, 'read_worker_json', return_value=request), \
                    patch.object(worker, '_resolved_bindings', return_value=(Path('/audio.wav'), Path('/model'))), \
                    patch.object(worker, '_runtime_identity', return_value=runtime), \
                    patch('soundfile.info', return_value=SimpleNamespace(
                        frames=48_000*300, channels=2, samplerate=48_000)):
                limit = worker.DECODER['maxNewTokens']
                for count, last, reason in ((limit, 2, 'truncated'), (2, 2, 'incomplete')):
                    with self.subTest(count=count):
                        model.generate.return_value = torch.tensor([[1]+[2]*(count-1)+[last]])
                        with self.assertRaisesRegex(ValueError, reason):
                            worker.run(request_path, output, 'cpu')
                        import json
                        retained = json.loads(output.read_bytes())['result']
                        self.assertIsNone(retained['transcription'])
                        self.assertEqual(retained['observation']['completion']['state'], reason)
                        self.assertEqual(len(retained['observation']['generatedTokenIds']), count)
                model.generation_config.eos_token_id = [0]
                text = 'language English<asr_text>Complete text'
                model.generate.return_value = torch.tensor([[1]+[ord(char) for char in text]+[0]])
                self.assertEqual(worker._transcribe(Path('/audio.wav'), Path('/model'), 'cpu', None)[-1]['transcription'],
                                 'Complete text')

    def test_owsm_budgets_precede_decode_and_posterior_allocation(self):
        import stemwise_engine.owsm_ctc_forced_alignment as worker
        from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
        huge = SimpleNamespace(frames=48_000*60*60, samplerate=48_000, channels=2)
        with patch.object(worker, '_audio_info', return_value=huge), \
                patch('soundfile.info', return_value=huge), \
                patch('soundfile.read', side_effect=AssertionError('must reject before allocating')), \
                patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=1024):
            with self.assertRaises(AcousticBudgetExceeded):
                worker._prepare_speech(Path('/long.wav'))
        for duration in (180, 300):
            info = SimpleNamespace(frames=48_000*duration, samplerate=48_000, channels=2)
            with patch.object(worker, '_audio_info', return_value=info), \
                    patch('soundfile.info', return_value=info), \
                    patch('soundfile.read', side_effect=RuntimeError('decode boundary')):
                with self.assertRaisesRegex(RuntimeError, 'decode boundary'):
                    worker._prepare_speech(Path('/ordinary.wav'))
        aligner = SimpleNamespace(token_list=range(50_000), fs=16000, context_len_in_secs=2,
            frames_per_sec=12.5, s2t_train_args=SimpleNamespace(preprocessor_conf={'speech_length': 30}))
        for duration in (180, 300):
            worker._require_posterior_allocation(aligner, 16_000*duration, retained_bytes=16_000*duration*4)
        with patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=1024), self.assertRaises(AcousticBudgetExceeded):
            worker._require_posterior_allocation(aligner, 16_000*60*60, retained_bytes=1024)

    def test_mms_streams_all_long_input_occurrences_through_normal_worker(self):
        import numpy as np
        import soundfile as sf
        import torch
        import stemwise_engine.mms_asr as worker
        import transformers
        previous_threads = torch.get_num_threads()
        torch.set_num_threads(1)
        self.addCleanup(torch.set_num_threads, previous_threads)
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            root = Path(temporary)
            audio, output = root/'long.wav', root/'transcript.json'
            sf.write(audio, np.tile([.1, -.1], 16_000*181//2), 16_000, subtype='PCM_16')
            tokenizer = SimpleNamespace(pad_token_id=0, word_delimiter_token_id=2,
                                       get_vocab=lambda: {'<blank>': 0, 'a': 1, '|': 2})
            class Tokenizer:
                pad_token_id, word_delimiter_token_id = 0, 2
                def __len__(self): return 3
                get_vocab = tokenizer.get_vocab
            tokenizer = Tokenizer()
            tokenizer.get_vocab = lambda: {'<blank>': 0, 'a': 1, '|': 2}
            windows = []
            def extract(samples, **kwargs):
                windows.append(len(samples))
                return {'input_values': torch.from_numpy(samples).unsqueeze(0)}
            extractor = Mock(side_effect=extract)
            extractor.sampling_rate = 16000
            model = Mock()
            model.to.return_value = model
            model.eval.return_value = model
            model.target_lang = worker.ADAPTER
            model.lm_head.out_features = 3
            model.config.pad_token_id = 0
            model.config.inputs_to_logits_ratio = 320
            def infer(input_values):
                frames = round(input_values.shape[-1]/320)
                logits = torch.full((1, frames, 3), -10.)
                logits[:, :, 1] = 10.
                logits[:, 49::50, 2] = 20.
                return SimpleNamespace(logits=logits)
            model.side_effect = infer
            assets = worker.ASSETS_BY_ADAPTER[worker.ADAPTER]
            with patch.object(worker, 'sha256_file', side_effect=lambda path: assets[path.name] if path.name in assets else 'a'*64), \
                    patch.object(worker, '_runtime_identity', return_value={}), \
                    patch.object(transformers.Wav2Vec2CTCTokenizer, 'from_pretrained', return_value=tokenizer), \
                    patch.object(transformers.Wav2Vec2FeatureExtractor, 'from_pretrained', return_value=extractor), \
                    patch.object(transformers.Wav2Vec2ForCTC, 'from_pretrained', return_value=model), \
                    patch('torchaudio.load', side_effect=AssertionError('whole input decode forbidden')):
                worker.run(audio, root, output, 'cpu')
            import json
            words = json.loads(output.read_bytes())['words']
            self.assertGreater(len(windows), 6)
            self.assertLessEqual(max(windows), 30*16000)
            self.assertGreater(words[-1]['end'], 180.)
            self.assertEqual(json.loads(output.read_bytes())['durationMs'], 181_000)
