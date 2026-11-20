from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import soundfile as sf

import stemwise_engine.endpoint_vocal_evidence as vocal
from stemwise_engine.common import sha256_file, json_bytes


class VocalEvidenceTests(unittest.TestCase):
    def setUp(self):
        active = patch('stemwise_engine.worker_runtime_probe.torch_runtime_identity', return_value={'cudaDevice': 'fixture'})
        active.start()
        self.addCleanup(active.stop)

    def test_separation_failure_retains_child_exception_without_outputs(self):
        from types import SimpleNamespace
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from process_fixture import prepare_runtime_layout
        with tempfile.TemporaryDirectory() as name:
            project = Path(name) / 'builder'
            prepare_runtime_layout(project)
            scratch = Path(name) / 'scratch'
            scratch.mkdir()
            clip = scratch / 'source.wav'
            clip.write_bytes(b'unchanged input')
            result = SimpleNamespace(returncode=1, stderr='RuntimeError: inner separation failure')
            with patch.object(vocal, 'run_process_tree', return_value=result):
                with self.assertRaisesRegex(RuntimeError, '^vocal-worker-failed$') as failure:
                    vocal._separate({'projectDir': str(project), 'analysisTimeoutMinutes': 1}, clip, scratch)
            references = failure.exception.diagnostics
            self.assertEqual(len(references), 1)
            diagnostic = Path(name)/references[0]['file']
            self.assertEqual(diagnostic.read_bytes(), result.stderr.encode('utf-8'))
            self.assertEqual(sha256_file(diagnostic), references[0]['sha256'])
            self.assertNotIn('inner separation failure', str(failure.exception))
            self.assertEqual(clip.read_bytes(), b'unchanged input')
            self.assertEqual(list(scratch.iterdir()), [clip])

    def test_separation_key_ignores_unrelated_endpoint_producers(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parents[2] / 'work') as name:
            source = Path(name) / 'source.wav'
            sf.write(source, np.column_stack((np.sin(np.arange(4800) / 20),
                np.cos(np.arange(4800) / 20))).astype('float32'), 48000, subtype='FLOAT')
            expected = vocal._separation_key(source)
            with patch('stemwise_engine.provenance.endpoint_producer_identity', return_value={'unrelatedEndpoint': 'changed'}):
                self.assertEqual(vocal._separation_key(source), expected)
            from stemwise_engine.model_registry import model_assets
            changed = model_assets('lyrics_align_pc')
            changed['PINNED_DEMUCS_SHA256'] = 'f' * 64
            with patch('stemwise_engine.model_registry.model_assets', return_value=changed):
                self.assertNotEqual(vocal._separation_key(source), expected)

    def test_context_reuse_compares_pcm_and_preserves_all_sources_on_rejection(self):
        import json, struct
        from types import SimpleNamespace
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        with tempfile.TemporaryDirectory() as name:
            job = Path(name) / 'job'
            cached = job / 'endpoint-ctc-evidence/old/context-000'
            cached.mkdir(parents=True)
            pcm = np.column_stack((np.sin(np.arange(4800)/20), np.cos(np.arange(4800)/20))).astype('float32')
            source = cached / 'source.wav'
            sf.write(source, pcm, 48000, subtype='FLOAT')
            original_source = source.read_bytes()
            changed_header = bytearray(original_source)
            peak = changed_header.find(b'PEAK', 0, 512)
            self.assertGreaterEqual(peak, 0)
            timestamp = struct.unpack_from('<I', changed_header, peak+12)[0]
            struct.pack_into('<I', changed_header, peak+12, (timestamp+1)&0xffffffff)
            transform = {**{k: 0 for k in ('offsetFrames', 'trimFrames', 'padFrames', 'separatorLatencyFrames')},
                         'measuredResidualMs': 0}
            views = {}
            for route in vocal.ROUTES:
                path = cached / (route + '.wav')
                sf.write(path, pcm*.5, 48000, subtype='FLOAT')
                views[route] = {'path': path.name, 'provenance': {'sha256': sha256_file(path),
                    'byteCount': path.stat().st_size, 'canonicalTimeTransform': transform,
                    'stream': {'sampleRate': 48000, 'channelCount': 2, 'frameCount': 4800, 'durationMs': 100},
                    'demucsModelSha256': 'a'*64}}
            prepared = {'views': views}
            approved = {'mix': {'sha256': sha256_file(source), 'byteCount': source.stat().st_size,
                'stream': {'sampleRate': 48000, 'channelCount': 2, 'frameCount': 4800, 'durationMs': 100}},
                'audioViews': {r: v['provenance'] for r, v in views.items()}}
            (cached / 'prepared.json').write_bytes(json_bytes(prepared))
            guard = SimpleNamespace(_read_audio_provenance=lambda *_args, **_kwargs: approved,
                PINNED_DEMUCS_SHA256='a'*64, _require_pinned_file=lambda *_args: None,
                _write_audio_provenance=None, _run_roformer_separation=None, _audio_stream=None)
            original_glob = Path.glob
            for index, scenario in enumerate(('same-pcm', 'missing-index', 'corrupt-index', 'missing-entry', 'stale-deleted-index', 'stale-damaged-index', 'changed-pcm', 'stale-model', 'changed-producer', 'missing-view', 'escaping-path', 'damaged-stem')):
                scratch = job / f'endpoint-owsm-ctc/new/context-{index:03d}'
                scratch.mkdir(parents=True)
                clip = scratch / 'source.wav'
                clip.write_bytes(changed_header)
                prior = clip.read_bytes()
                before = {r: (cached / v['path']).read_bytes() for r, v in views.items()}
                current = json.loads(json.dumps(prepared))
                guard.PINNED_DEMUCS_SHA256 = 'b'*64 if scenario == 'stale-model' else 'a'*64
                if scenario == 'changed-pcm':
                    altered = pcm.copy()
                    altered[100, 1] += .001
                    sf.write(clip, altered, 48000, subtype='FLOAT')
                    prior = clip.read_bytes()
                if scenario == 'missing-view':
                    current['views'].pop(vocal.ROUTES[-1])
                if scenario == 'escaping-path':
                    current['views'][vocal.ROUTES[0]]['path'] = '../outside.wav'
                if scenario == 'damaged-stem':
                    stem = cached / views[vocal.ROUTES[-1]]['path']
                    altered = bytearray(stem.read_bytes())
                    altered[-1] ^= 1
                    stem.write_bytes(altered)
                    before[vocal.ROUTES[-1]] = bytes(altered)
                (cached / 'prepared.json').write_bytes(json_bytes(current))
                vocal._index_separation(source, cached)
                index_path = job / 'vocal-context-index.json'
                if scenario == 'missing-index': index_path.unlink()
                if scenario == 'corrupt-index': index_path.write_bytes(b'{')
                if scenario == 'missing-entry': index_path.write_bytes(b'{}')
                if scenario in {'stale-deleted-index', 'stale-damaged-index'}:
                    import shutil
                    stale = job / f'endpoint-ctc-evidence/stale-{index}/context-000'
                    if scenario == 'stale-damaged-index':
                        shutil.copytree(cached, stale)
                        stem = stale / views[vocal.ROUTES[-1]]['path']
                        stem.write_bytes(b'damaged retained stem')
                    key = vocal._separation_key(source)[0]
                    index_path.write_bytes(json_bytes({key: str(stale.relative_to(job))}))
                if scenario == 'changed-producer':
                    (cached / 'vocal-separation.json').write_bytes(json_bytes({'schemaVersion': 1, 'key': '0' * 64}))
                with patch('stemwise_engine.alignment_models.require_pinned_file', return_value=None), patch(
                        'stemwise_engine.alignment_models.PINNED_DEMUCS_SHA256', guard.PINNED_DEMUCS_SHA256), patch(
                        'stemwise_engine.alignment_audio.read_audio_provenance', return_value=approved), patch.object(
                        Path, 'glob', autospec=True, side_effect=(lambda path, pattern: original_glob(path, pattern)
                            if scenario != 'same-pcm' else
                            (_ for _ in ()).throw(AssertionError('reuse scanned unrelated contexts')))), patch.dict(sys.modules, {'stemwise_engine.lyrics_align_pc': guard}), patch.object(
                        sys.modules['stemwise_engine.lyrics_alignment'], 'AlignmentWorkspace', AlignmentWorkspace):
                    result = vocal._reuse_separation(clip, scratch)
                if scenario in {'same-pcm', 'missing-index', 'corrupt-index', 'missing-entry', 'stale-deleted-index', 'stale-damaged-index'}:
                    self.assertEqual(set(result), set(vocal.ROUTES))
                    key = vocal._separation_key(source)[0]
                    self.assertEqual(json.loads(index_path.read_bytes())[key], str(cached.relative_to(job)))
                    self.assertEqual(clip.read_bytes(), prior)
                    for route, (path, provenance) in result.items():
                        self.assertEqual(path.parent, scratch)
                        self.assertEqual(path.read_bytes(), before[route])
                        self.assertEqual(provenance, views[route]['provenance'])
                else:
                    self.assertIsNone(result, scenario)
                    self.assertEqual(clip.read_bytes(), prior)
                    self.assertEqual(list(scratch.iterdir()), [clip])
                self.assertEqual(source.read_bytes(), original_source)
                self.assertEqual({r: (cached / v['path']).read_bytes() for r, v in views.items()}, before)







if __name__ == "__main__":
    unittest.main()
