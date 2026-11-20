"""Preservation and byte-identity regressions from the PC audit."""
import importlib.util
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[3]
from qobuz_fixtures import flac_bytes, write_download
import stemwise_media.save_download as save_download
import stemwise_media.recording_review as recording_review


class RetainedDownloadTests(unittest.TestCase):

    def test_vocal_benchmark_uses_configured_interpreters_and_holds_their_leases(self):
        import io
        import contextlib
        import subprocess
        import numpy as np
        import soundfile as sf
        from process_fixture import prepare_runtime_layout
        from stemwise_engine.runtime_environment import environment_python, environment_lease
        spec = importlib.util.spec_from_file_location('vocal_benchmark_routing',
            ROOT/'Test suite/pc/performance/benchmark_vocal_preparation.py')
        benchmark = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(benchmark)
        class ReachedBatch(Exception):
            pass
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            project = root/'project'
            project.mkdir()
            prepare_runtime_layout(project)
            layout_path = root/'runtime-layout.json'
            layout = json.loads(layout_path.read_bytes())
            layout['environments'].update(main='project/managed-main/bin/python3.10',
                lyrics='project/managed-lyrics/bin/python3.10')
            layout_path.write_text(json.dumps(layout), encoding='utf-8')
            audio = root/'audio.wav'
            sf.write(audio, np.zeros((48_000*8, 2), dtype=np.float32), 48_000, subtype='FLOAT')
            modes = []
            def launch(argv, **options):
                mode = argv[-1]
                role = 'lyrics' if mode == 'serial' else 'main'
                self.assertEqual(Path(argv[0]), environment_python(role, project))
                with self.assertRaises(TimeoutError):
                    with environment_lease(role, builder=project, exclusive=True, wait_seconds=0):
                        pass
                modes.append(mode)
                if mode == 'batch':
                    raise ReachedBatch()
                return subprocess.CompletedProcess(argv, 0, json.dumps({'loads': {}, 'outputs': []}), '')
            with patch.object(sys, 'argv', ['benchmark', '--input', str(audio), '--work', str(root/'runs'), '--contexts', '1']), \
                    patch.object(benchmark, 'PROJECT', project), \
                    patch.object(benchmark, 'load_config', return_value={'projectDir': str(project)}), \
                    patch('stemwise_engine.alignment_models.roformer_offline_env', return_value={}), \
                    patch.object(benchmark, 'run_process_tree', side_effect=launch), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(ReachedBatch):
                    benchmark.main()
            self.assertEqual(modes, ['serial', 'batch'])
            self.assertFalse((root/'runs/result.json').exists())


    def test_quick_cache_recomputes_when_either_input_changes_at_the_same_path(self):
        import numpy as np
        batch = recording_review.batch
        for changed in ('none', 'reference', 'candidate'):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
                root = Path(temporary)
                reference = root/'audio/youtube/AAAAAAAAAAA/reference.flac'
                candidate = root/'audio/qobuz/10/candidate.flac'
                for path in (reference, candidate):
                    path.parent.mkdir(parents=True)
                reference.write_bytes(flac_bytes(1))
                candidate.write_bytes(flac_bytes(3))
                cached = {'trackId': 10, 'candidateFile': candidate.relative_to(root).as_posix(),
                    'candidateSha256': batch.file_sha256(candidate), 'classification': 'strong_recording_match'}
                row = {'videoId': 'AAAAAAAAAAA', 'position': 1, 'candidates': [{'id': 10}],
                    'metadataSelection': {'status': 'selected_candidate', 'trackId': 10},
                    'quickAudioComparison': {'status': 'matched', 'matchedTrackId': 10,
                        'sampleRate': batch.RATE, 'referenceFile': reference.relative_to(root).as_posix(),
                        'referenceSha256': batch.file_sha256(reference), 'recipeVersion': 1, 'candidates': [cached]}}
                if changed != 'none':
                    (reference if changed == 'reference' else candidate).write_bytes(
                        flac_bytes(2) if changed == 'reference' else flac_bytes(4))
                write_download(reference.parent, {'videoId': 'AAAAAAAAAAA', 'file': reference.name,
                    'bytes': reference.stat().st_size, 'sha256': batch.file_sha256(reference)})
                write_download(candidate.parent, {'qobuzTrackId': 10, 'file': candidate.name})
                with patch.object(batch, 'ROOT', root), \
                        patch.object(batch, 'decode', return_value=np.zeros((8, 2))) as decode, \
                        patch.object(batch, 'analyze_waveforms', return_value={'classification': 'not_matched'}):
                    batch.compare_rows({'rows': [row]})
                self.assertEqual(decode.call_count, 0 if changed == 'none' else 2)
                self.assertEqual(row['quickAudioComparison']['status'], 'matched' if changed == 'none' else 'unconfirmed')

    def test_equivalence_consumer_requires_current_reference_and_candidate_bytes(self):
        batch = recording_review.batch
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            reference, candidate = root/'reference.opus', root/'candidate.flac'
            reference.write_bytes(b'reference1')
            candidate.write_bytes(b'candidate1')
            result = {'trackId': 10, 'classification': 'same_performance_with_differences',
                'checkedWindows': 20, 'supportedWindowFraction': 1, 'channelMedians': [.99, .99],
                'anchors': [{'offsetSeconds': 0, 'correlation': .99}]*3,
                'candidateFile': candidate.name, 'candidateSha256': batch.file_sha256(candidate)}
            row = {'videoId': 'AAAAAAAAAAA', 'recordingEquivalence': {'sampleRate': 8000,
                'recipeVersion': 1, 'referenceFile': reference.name,
                'referenceSha256': batch.file_sha256(reference), 'candidates': [result]}}
            with patch.object(batch, 'ROOT', root), patch.object(batch, 'source_file', return_value=reference), \
                    patch.object(batch, 'qobuz_file', return_value=candidate):
                self.assertEqual(recording_review.equivalences(row), [result])
                candidate.write_bytes(b'candidate2')
                self.assertEqual(recording_review.equivalences(row), [])
                candidate.write_bytes(b'candidate1')
                reference.write_bytes(b'reference2')
                self.assertEqual(recording_review.equivalences(row), [])

    def test_failed_model_identity_never_leaves_a_cached_instance(self):
        import stemwise_engine.analyzers as analyzers
        for fault in ('wrong-digest', 'absent-after-load', 'interrupted-load', 'malformed-model'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
                cache = Path(temporary)
                checkpoint = cache / 'checkpoints/beat_this-final0.ckpt'
                calls = []
                def load(**kwargs):
                    calls.append(kwargs)
                    if fault == 'interrupted-load':
                        raise OSError('interrupted')
                    if fault != 'absent-after-load':
                        checkpoint.parent.mkdir(exist_ok=True)
                        checkpoint.write_bytes(b'bad' if fault == 'wrong-digest' else b'valid')
                    return object() if fault == 'malformed-model' else lambda *_: ([1], [1])
                fake_torch = SimpleNamespace(cuda=SimpleNamespace(is_available=lambda: False),
                                             hub=SimpleNamespace(get_dir=lambda: str(cache)))
                with patch.object(analyzers, '_BEAT_THIS', None), \
                        patch.object(analyzers, 'BEAT_THIS_FINAL0_SHA256', hashlib.sha256(b'valid').hexdigest()), \
                        patch.object(analyzers, 'package_version', return_value='fixture'), \
                        patch.dict(sys.modules, {'torch': fake_torch,
                            'beat_this.inference': SimpleNamespace(File2Beats=load)}):
                    self.assertEqual(analyzers.analyze_beat_this(cache/'audio.wav', 2000)[2]['status'], 'failed')
                    self.assertIsNone(analyzers._BEAT_THIS)
                    self.assertEqual(analyzers.analyze_beat_this(cache/'audio.wav', 2000)[2]['status'], 'failed')
                    self.assertIsNone(analyzers._BEAT_THIS)

    def test_serial_benchmark_wrapper_reaches_every_route_with_the_actual_audio_digest(self):
        import stemwise_engine.endpoint_vocal_evidence as endpoint_vocal_evidence
        import stemwise_engine.lyrics_alignment as lyrics_alignment
        import stemwise_engine.alignment_models as alignment_models
        import stemwise_engine.alignment_audio as alignment_audio
        import stemwise_engine.lyrics_align_pc as lyrics_align_pc
        benchmark_path = ROOT / 'Test suite/pc/performance/benchmark_vocal_preparation.py'
        specification = importlib.util.spec_from_file_location('vocal_preparation_fixture', benchmark_path)
        benchmark = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(benchmark)
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            clip = root / 'audio.wav'
            clip.write_bytes(b'bound source PCM')
            routes = endpoint_vocal_evidence.ROUTES
            identities = {'mix': {'sha256': hashlib.sha256(clip.read_bytes()).hexdigest()},
                          'audioViews': {routes[0]: {'sha256': 'a'*64}}}
            def separate(**arguments):
                return root/arguments['route_id'], {'sha256': 'b'*64}, False
            with patch.object(endpoint_vocal_evidence, '_reuse_separation', return_value=None), \
                    patch.object(lyrics_alignment, 'separate_vocals_demucs', return_value=root/'vocals.wav'), \
                    patch.object(alignment_models, 'require_pinned_file'), \
                    patch.object(alignment_audio, 'audio_stream', return_value={'durationMs': 8000}), \
                    patch.object(alignment_audio, 'write_audio_provenance', return_value=identities) as provenance, \
                    patch.object(lyrics_align_pc, '_run_roformer_separation', side_effect=separate) as roformer:
                result = benchmark._serial_separate_local({'projectDir': str(root/'builder'),
                    'analysisTimeoutMinutes': 1}, clip, root/'scratch')
            self.assertEqual(set(result), set(routes))
            self.assertEqual(provenance.call_args.kwargs['expected_mix_sha256'], hashlib.sha256(clip.read_bytes()).hexdigest())
            self.assertEqual(roformer.call_count, 2)

    def test_retained_bytes_must_match_complete_identity(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            base = Path(temporary)
            good = flac_bytes()
            metadata = {'qobuzTrackId': 10, 'file': 'song.flac', 'bytes': len(good),
                        'sha256': hashlib.sha256(good).hexdigest()}
            audio = base / 'song.flac'
            audio.write_bytes(good)
            (base / 'download.json').write_text(json.dumps(metadata))
            with patch.object(save_download, 'http_request', side_effect=AssertionError('Unexpected HTTP worker')):
                self.assertEqual(save_download._save_download(10, 'https://eu.doubledouble.top/dl/Example', base)['status'], 'already_saved')
                for changed in (b'', good[:-1], b'not FLAC' + good[8:], good[:-1] + b'x'):
                    audio.write_bytes(changed)
                    with self.subTest(changed=changed), self.assertRaises(ValueError):
                        save_download._save_download(10, 'https://eu.doubledouble.top/dl/Example', base)
                    self.assertEqual(audio.read_bytes(), changed)
                audio.unlink()
                with self.assertRaises(ValueError):
                    save_download.validate_retained_download(base, 10, metadata)

    def test_prose_never_creates_owner_authority(self):
        for note in ('هذه النسخة ليست مقبولة', 'هل هذه مقبولة؟', 'accepted مقبولة'):
            row = {'position': 1, 'videoId': 'source', 'ownerAudioSelection': {'source': 'youtube'},
                   'ownerRecordingNote': {'note': note}}
            self.assertFalse(recording_review.owner_selection_is_current(row))


if __name__ == '__main__':
    unittest.main()
