"""Alignment runtime and content admission contracts; no model inference."""
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]

import stemwise_engine.lyrics_align_pc as align
import stemwise_engine.lyrics_alignment as lyrics_alignment
from stemwise_engine.acoustic_inputs import StreamedMonoAudio
from stemwise_engine.lyrics_alignment import align_known_structure, score_structure_witness


class AlignmentRuntimeContractsTests(unittest.TestCase):
    def test_primary_readiness_does_not_require_unused_roformer_assets(self):
        with patch.object(align, '_verify_whisper_snapshot', return_value=(ROOT, {'whisperModelSha256': 'a'*64})), \
                patch.object(align.models, 'require_pinned_file', side_effect=lambda _, sha, __: sha), \
                patch.object(align.models, 'roformer_assets', side_effect=AssertionError('optional route inspected')):
            core = align._verify_pinned_stack({'projectDir': str(ROOT)}, ROOT, align.DEFAULT_MODEL)
        self.assertEqual(set(core), {'whisperModelSha256', 'demucsModelSha256', 'mmsModelSha256'})
        dependencies = {name: SimpleNamespace(__version__='fixture')
                        for name in ('faster_whisper', 'rapidfuzz', 'syncalong')}
        dependencies['yt_dlp'] = SimpleNamespace(version=SimpleNamespace(__version__='fixture'))
        with patch.dict(sys.modules, dependencies), \
                patch.object(align.importlib.metadata, 'version', return_value='fixture'), \
                patch.object(align, '_paths', return_value=(ROOT, ROOT, ROOT, None)), \
                patch.object(align, '_verify_pinned_stack', return_value=core), \
                patch.object(align, '_verify_alignment_runtime', return_value={}), \
                patch.object(align, '_mms_runtime_provenance', return_value={}), \
                patch.object(align, '_roformer_runtime_provenance', return_value={}), \
                patch.object(align.models, 'require_roformer_wheel', side_effect=RuntimeError('pinned RoFormer wheel missing')), \
                patch.object(align.shutil, 'which', return_value='available'):
            doctor = align._doctor({'projectDir': str(ROOT/'PC side/stemwise-playlist-builder')})
        self.assertTrue(doctor['ready'])
        self.assertFalse(doctor['optionalFallbacksReady'])
        self.assertTrue(all('wheel missing' in route['error'] for route in doctor['optionalFallbacks'].values()))
        work = ROOT / 'Test suite/work/alignment-runtime-contracts'
        work.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as name:
            project = Path(name)/'builder'
            project.mkdir()
            sys.path.insert(0, str(ROOT/'Test suite/pc'))
            from process_fixture import prepare_runtime_layout
            prepare_runtime_layout(project)
            for relative in ('src/roformer_separation.py', '.venv/bin/python', 'source.wav'):
                path = project / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture')
            with self.assertRaisesRegex(RuntimeError, 'RoFormer wheel is missing'):
                align._run_roformer_separation(config={'projectDir': str(project)}, video_id='abcdefghijk',
                    route_id='kimberley-jensen-roformer-vocals', input_audio=project / 'source.wav',
                    input_identity={}, workspace=None, duration_ms=1000, expected_mix_sha256='a'*64,
                    temp_dir=project, device='cpu', timeout_seconds=1)

    def test_current_evidence_and_worker_commands_exclude_retired_operations(self):
        import contextlib
        import io
        import stemwise_engine.endpoint_dispatch as endpoint_dispatch
        import stemwise_engine.endpoint_ctc_evidence as endpoint_ctc_evidence
        import stemwise_engine.owsm_ctc_forced_alignment as owsm
        from importlib import import_module
        for name in ('endpoint_audio_evidence', 'endpoint_ctc_evidence', 'endpoint_repeat_alignment',
                     'endpoint_candidate_prediction', 'endpoint_native_ctc', 'endpoint_owsm_ctc'):
            module = import_module('stemwise_engine.' + name)
            self.assertIs(endpoint_dispatch.evidence_adapter(module.METHOD).module, module)
        with self.assertRaisesRegex(ValueError, 'Unsupported current'):
            endpoint_dispatch.evidence_adapter('local-vocal-boundary-v1')
        argv = ['worker', '--request', 'request.json', '--request-sha256', 'a'*64, '--output', 'result.json']
        with patch.object(sys, 'argv', argv), patch.object(owsm, 'run') as execute:
            self.assertEqual(owsm.main(), 0)
            execute.assert_called_once_with(Path('request.json'), 'a'*64, Path('result.json'))
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            owsm.parser().parse_args([*argv[1:], '--structure-comparison'])


    def test_parent_duration_matches_streamed_reader_at_rounding_boundaries(self):
        import numpy as np
        import soundfile as sf
        work = ROOT / 'Test suite/work/alignment-runtime-contracts'
        work.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as name:
            path = Path(name) / 'source.wav'
            for rate, frames, expected in ((44100, 7938196, 180005), (44100, 16782848, 380564),
                                           (44100, 10849281, 246016), (48000, 48024, 1001),
                                           (16000, 16008, 1001)):
                with self.subTest(rate=rate, frames=frames):
                    with sf.SoundFile(path, 'w', samplerate=rate, channels=1, subtype='PCM_16') as out:
                        out.write(np.ones((frames, 1), dtype=np.float32) * .25)
                    # Reopening is also the cached worker's metadata path.
                    for _ in range(2):
                        reader = StreamedMonoAudio(path, 16000)
                        self.assertEqual((reader.sample_count * 1000 + 8000) // 16000, expected)
                        self.assertEqual(align._worker_pcm_duration_ms({'stream': {
                            'sampleRate': rate, 'frameCount': frames}}), expected)

    def test_exact_short_lyrics_pass_both_floors_without_admitting_wrong_words(self):
        from stemwise_engine.lyric_content import require_exact_sequence
        from stemwise_engine.workflow_records import LyricsContentBlocker
        words = ['one', 'two', 'three', 'four', 'five', 'six', 'seven']
        for count in range(1, 8):
            text = ' '.join(words[:count])
            transcript = [SimpleNamespace(word=w, raw=w, start=i*.8/count, end=(i+1)*.8/count)
                          for i, w in enumerate(words[:count])]
            # Known matching indices isolate our gates from the external aligner's DP implementation.
            with self.subTest(count=count), \
                    patch.object(lyrics_alignment, 'WordTimestamp', SimpleNamespace), \
                    patch.object(lyrics_alignment, 'LyricLine', SimpleNamespace), \
                    patch.object(lyrics_alignment, 'normalize', side_effect=str.strip), \
                    patch.object(lyrics_alignment, '_word_score', return_value=100.), \
                    patch.object(lyrics_alignment, '_dp_align', return_value={i: i for i in range(count)}) as mapping:
                require_exact_sequence({'text': text}, [0], transcript, 'a'*64, 'b'*64)
                self.assertTrue(score_structure_witness(text, [0], text).accepted)
                result = align_known_structure(text, [0], transcript, 1000)
                self.assertTrue(result.accepted, result.reasons)
                mapping.return_value = {}
                self.assertFalse(score_structure_witness(text, [0], 'wrong').accepted)
                with self.assertRaises(LyricsContentBlocker):
                    require_exact_sequence({'text': text}, [0], ' '.join([*words[:count-1], 'wrong']),
                                           'a'*64, 'b'*64)
                mapping.return_value = {i: i for i in range(count-1)}
                self.assertFalse(align_known_structure(text, [0], transcript[:-1], 1000).accepted)
                with self.assertRaises(LyricsContentBlocker):
                    require_exact_sequence({'text': text}, [0], transcript[:-1], 'a'*64, 'b'*64)

    def test_qwen_snapshot_verification_reuses_only_unchanged_file_identity(self):
        work = ROOT / 'Test suite/work/alignment-runtime-contracts'
        work.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as name:
            cache = Path(name)
            snapshot = cache / 'models--Qwen--Qwen3-ASR-1.7B-hf/snapshots' / align.QWEN3_ASR_REVISION
            snapshot.mkdir(parents=True)
            model = snapshot / 'model.safetensors'
            model.write_bytes(b'first')
            attempt = {}
            with patch.object(align, '_qwen3_asr_snapshot_sha256', return_value=align.QWEN3_ASR_SNAPSHOT_SHA256) as verify:
                self.assertEqual(align._qwen3_asr_snapshot(cache, attempt_cache=attempt), snapshot)
                self.assertEqual(align._qwen3_asr_snapshot(cache, attempt_cache=attempt), snapshot)
                self.assertEqual(verify.call_count, 1)
                model.write_bytes(b'second')
                verify.return_value = '0' * 64
                with self.assertRaisesRegex(RuntimeError, 'SHA-256'):
                    align._qwen3_asr_snapshot(cache, attempt_cache=attempt)
                self.assertEqual(verify.call_count, 2)


if __name__ == '__main__':
    unittest.main()
