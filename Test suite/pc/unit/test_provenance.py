from __future__ import annotations

import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.provenance import (  # noqa: E402
    PROVENANCE_VERSION,
    collect_provenance,
    collect_acoustic_provenance,
    file_identity,
    provenance_fingerprint,
)


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class ProvenanceTests(unittest.TestCase):
    def test_observed_numerical_tuple_changes_with_device_gpu_and_runtime(self):
        from types import SimpleNamespace
        import stemwise_engine.provenance as provenance
        properties = SimpleNamespace(uuid='GPU-1', name='GPU A', total_memory=1024)
        cuda = SimpleNamespace(current_device=lambda:0, get_device_properties=lambda index:properties,
            get_device_capability=lambda index:(8, 6))
        torch = SimpleNamespace(__version__='2.7', cuda=cuda, version=SimpleNamespace(cuda='12.8'),
            backends=SimpleNamespace(cudnn=SimpleNamespace(version=lambda:9)),
            device=lambda name:SimpleNamespace(type=name, index=None))
        with patch.object(provenance, 'run_process_tree', return_value=SimpleNamespace(stdout='580\n')) as driver:
            cpu = provenance.numerical_execution_identity(torch, 'cpu')
            driver.assert_not_called()
            first = provenance.numerical_execution_identity(torch, 'cuda')
            properties.uuid = '1'
            normalized = provenance.numerical_execution_identity(torch, 'cuda')
            self.assertEqual(normalized['uuid'], 'GPU-1')
            self.assertEqual(first, normalized)
            properties.uuid = 'GPU-1'
            self.assertNotEqual(provenance_fingerprint(cpu), provenance_fingerprint(first))
            self.assertEqual(first, provenance.numerical_execution_identity(torch, 'cuda'))
            properties.uuid, properties.name = 'GPU-2', 'GPU B'
            self.assertNotEqual(first, provenance.numerical_execution_identity(torch, 'cuda'))
            properties.uuid, properties.name = 'GPU-1', 'GPU A'
            torch.version.cuda = '13'
            self.assertNotEqual(first, provenance.numerical_execution_identity(torch, 'cuda'))
            torch.version.cuda = '12.8'
            driver.return_value.stdout = '590'
            self.assertNotEqual(first, provenance.numerical_execution_identity(torch, 'cuda'))

    def test_full_diagnostics_do_not_invalidate_current_acoustic_identity(self):
        numerical = {'numericalProducer': {'analyzer': 'a'*64},
            'acousticRuntime': {'packages': {'torch': {'version': 'current'}}, 'models': {'weights': 'b'*64}},
            'packages': {'yt-dlp': {'version': 'old'}}, 'models': {'weights': {'available': True}},
            'sourceProvenance': {'analyzer': 'raw-file-old'}}
        changed = {**numerical, 'packages': {'yt-dlp': {'version': 'new'}},
            'models': {'weights': {'available': False}}, 'sourceProvenance': {'analyzer': 'raw-comment-edit'}}
        self.assertEqual(provenance_fingerprint(numerical), provenance_fingerprint(changed))
        for key in ('numericalProducer', 'acousticRuntime'):
            self.assertNotEqual(provenance_fingerprint(numerical), provenance_fingerprint({**changed, key: {'changed': True}}))

    def test_endpoint_identity_binds_transitive_forward_recursion_and_semantic_dependencies(self):
        import shutil
        import stemwise_engine.provenance as provenance
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as directory:
            project = Path(directory)
            shutil.copytree(PROJECT_ROOT/'src', project/'src', ignore=shutil.ignore_patterns('__pycache__'))
            for name in ('environment-locks.json',):
                shutil.copy2(PROJECT_ROOT/name, project/name)
            shutil.copytree(PROJECT_ROOT/'locks', project/'locks')
            with patch.object(provenance, '__file__', str(project/'src/provenance.py')):
                provenance.endpoint_producer_identity.cache_clear()
                original = provenance.endpoint_producer_identity()
                content = provenance.content_assessment_identity()
                physical = project/'src/lyrics_normalize.py'
                raw_physical = physical.read_bytes()
                physical.write_bytes(raw_physical.replace(b".split('\\n')", b".splitlines()", 1))
                provenance.endpoint_producer_identity.cache_clear()
                self.assertNotEqual(provenance.endpoint_producer_identity(), original)
                self.assertNotEqual(provenance.content_assessment_identity(), content)
                physical.write_bytes(raw_physical)
                provenance.endpoint_producer_identity.cache_clear()
                numeric = project/'src/ctc_numerics.py'
                numeric.write_bytes(numeric.read_bytes().replace(b'np.logaddexp', b'np.maximum', 1))
                provenance.endpoint_producer_identity.cache_clear()
                self.assertNotEqual(provenance.endpoint_producer_identity(), original)
                original = provenance.endpoint_producer_identity()
                requirement = project/'requirements-analysis.txt'
                requirement.write_bytes(b'# explanation only\n')
                provenance.endpoint_producer_identity.cache_clear()
                self.assertEqual(provenance.endpoint_producer_identity(), original)
                import json
                import hashlib
                manifest_path = project/'environment-locks.json'
                manifest = json.loads(manifest_path.read_bytes())
                entry = manifest['environments']['main']
                lock = project/entry['lock']
                raw = lock.read_bytes()
                offset = raw.index(b'--hash=sha256:') + len(b'--hash=sha256:')
                raw = raw[:offset] + (b'1' if raw[offset:offset+1] != b'1' else b'2') + raw[offset+1:]
                lock.write_bytes(raw)
                entry['sha256'] = hashlib.sha256(raw).hexdigest()
                manifest_path.write_text(json.dumps(manifest), encoding='utf-8')
                provenance.endpoint_producer_identity.cache_clear()
                self.assertNotEqual(provenance.endpoint_producer_identity(), original)
            provenance.endpoint_producer_identity.cache_clear()

    def test_owsm_cache_identity_ignores_bookkeeping_and_unrelated_model_runtime(self):
        import shutil
        import stemwise_engine.provenance as provenance
        import stemwise_engine.model_registry as registry
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as directory:
            source = Path(directory)/'src'
            shutil.copytree(PROJECT_ROOT/'src', source, ignore=shutil.ignore_patterns('__pycache__'))
            actual_profile = registry.runtime_profile
            with patch.object(provenance, '__file__', str(source/'provenance.py')):
                provenance.endpoint_producer_identity.cache_clear()
                before = provenance.endpoint_producer_identity('owsm')
                for name in ('alignment_attempt.py', 'private_diagnostics.py', 'nemo_nfa_forced_alignment.py'):
                    path = source/name
                    path.write_text(path.read_text(encoding='utf-8')+'\nunrelated_bookkeeping_value = 123\n', encoding='utf-8')
                with patch.object(registry, 'runtime_profile', side_effect=lambda name:
                        {'unrelated': True} if name == 'main' else actual_profile(name)):
                    provenance.endpoint_producer_identity.cache_clear()
                    self.assertEqual(provenance.endpoint_producer_identity('owsm'), before)
                path = source/'owsm_ctc_forced_alignment.py'
                path.write_text(path.read_text(encoding='utf-8')+'\nnumerical_decoder_value = 123\n', encoding='utf-8')
                provenance.endpoint_producer_identity.cache_clear()
                self.assertNotEqual(provenance.endpoint_producer_identity('owsm'), before)
            provenance.endpoint_producer_identity.cache_clear()

    def test_mms_identity_binds_shared_fusion_dispatch_and_pcm_reader(self):
        import shutil
        import stemwise_engine.provenance as provenance
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            project = Path(name)
            shutil.copytree(PROJECT_ROOT/'src', project/'src', ignore=shutil.ignore_patterns('__pycache__'))
            shutil.copy2(PROJECT_ROOT/'environment-locks.json', project/'environment-locks.json')
            shutil.copytree(PROJECT_ROOT/'locks', project/'locks')
            with patch.object(provenance, '__file__', str(project/'src/provenance.py')):
                provenance.endpoint_producer_identity.cache_clear()
                before = provenance.provenance_fingerprint(provenance.endpoint_producer_identity('mms'))
                unrelated = project/'src/owsm_ctc_forced_alignment.py'
                original = unrelated.read_bytes()
                unrelated.write_bytes(original+b'\nUNRELATED_OWSM_MODEL_REVISION = "fixture"\n')
                provenance.endpoint_producer_identity.cache_clear()
                self.assertEqual(provenance.provenance_fingerprint(provenance.endpoint_producer_identity('mms')), before)
                unrelated.write_bytes(original)
                for filename, old, new in (
                        ('endpoint_owsm_ctc.py', b'return merged-np.log(len(ROUTES))', b'return merged'),
                        ('endpoint_vocal_evidence.py', b'x = resample_poly(x, sample_rate//factor, rate//factor, axis=0)',
                            b'x = resample_poly(x, sample_rate//factor, rate//factor, axis=0)*.5')):
                    with self.subTest(filename=filename):
                        path = project/'src'/filename
                        raw = path.read_bytes()
                        changed = raw.replace(old, new, 1)
                        self.assertNotEqual(raw, changed)
                        path.write_bytes(changed)
                        provenance.endpoint_producer_identity.cache_clear()
                        try:
                            self.assertNotEqual(provenance.provenance_fingerprint(
                                provenance.endpoint_producer_identity('mms')), before)
                        finally:
                            path.write_bytes(raw)
                            provenance.endpoint_producer_identity.cache_clear()
            provenance.endpoint_producer_identity.cache_clear()

    def test_actual_status_entry_reads_recorded_producer_without_initializing_current_models(self):
        import contextlib
        import io
        import json
        import stemwise_engine.stemwise_pc as stemwise_pc
        import stemwise_engine.pipeline as pipeline
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as name:
            root = Path(name)
            project = root / 'PC side/stemwise-playlist-builder'
            project.mkdir(parents=True)
            (project.parent/'runtime-layout.json').write_bytes((PROJECT_ROOT.parent/'runtime-layout.json').read_bytes())
            config = project / 'config.json'
            config.write_text(json.dumps({'playlistUrl': 'https://music.youtube.com/playlist?list=fixture',
                'outputDirWindows': str(root / 'output'), 'tempDirWindows': str(root / 'temporary'),
                'generatorVersion': 'fixture'}), encoding='utf-8')
            checkpoint = root / 'output/work/checkpoints/abcdefghijk.json'
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_text(json.dumps({'videoId': 'abcdefghijk', 'status': 'complete',
                'provenanceSha256': 'a' * 64}), encoding='utf-8')
            before = checkpoint.read_bytes()
            output = io.StringIO()
            with patch.object(pipeline, 'collect_provenance', side_effect=AssertionError('Status opened models/probes')), \
                    contextlib.redirect_stdout(output):
                self.assertEqual(stemwise_pc.main(['--config', str(config), 'status']), 0)
            report = json.loads(output.getvalue())
            self.assertEqual(report['states'][0]['provenanceSha256'], 'a' * 64)
            self.assertEqual(report['currentRuntimeVerification'], {'requested': False})
            self.assertEqual(checkpoint.read_bytes(), before)
            current = {'numericalProducer': {'source': 'current'}}
            with patch.object(pipeline, 'collect_provenance', return_value=current) as verify:
                owner = pipeline.StemwisePipeline(stemwise_pc.load_config(config))
                verify.assert_not_called()
                state = owner._new_state({'videoId': 'abcdefghijk'}, 'absent')
                verify.assert_called_once()
                self.assertEqual(state['provenanceSha256'], provenance_fingerprint(current))



    def test_numerical_source_identity_reuses_prose_but_binds_code_and_assets(self):
        import shutil
        import stemwise_engine.provenance as provenance
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            source = Path(provenance.__file__).parent
            for name in ('analysis_numerics.py', 'analyzers.py', 'analysis_resources.py', 'analysis-resource-policy.json', 'waveform.py', 'youtube.py', 'allin1_worker.py', 'allin1_demix_worker.py', 'private_diagnostics.py', 'common.py'):
                shutil.copyfile(source/name, root/name)
            with patch.object(provenance, '__file__', str(root/'provenance.py')), \
                    patch('stemwise_engine.model_registry.model_assets', return_value={'model': 'current'}) as assets:
                original = provenance.numerical_source_identity()
                diagnostics = root / 'private_diagnostics.py'
                diagnostics.write_text(diagnostics.read_text(encoding='utf-8').replace(
                    "'Child stderr:\\n'", "'Retained child stderr:\\n'") +
                    '\nDIAGNOSTIC_CAPTURE_LIMIT = 11000\n', encoding='utf-8')
                self.assertEqual(provenance.numerical_source_identity(), original)
                analyzer = root/'analyzers.py'
                analyzer.write_text('# only explanation\n'+analyzer.read_text(encoding='utf-8'), encoding='utf-8')
                self.assertEqual(provenance.numerical_source_identity(), original)
                analyzer.write_text(analyzer.read_text(encoding='utf-8').replace(
                    '1000.0 + 0.5', '1000.0 + 0.25'), encoding='utf-8')
                self.assertNotEqual(provenance.numerical_source_identity(), original)
                changed = provenance.numerical_source_identity()
                numerical = root / 'analysis_numerics.py'
                numerical.write_text(numerical.read_text(encoding='utf-8').replace(
                    'return rhythm, normalizer_tool, key, skey_tool, beat_tool',
                    'return rhythm, normalizer_tool, key, skey_tool, None'), encoding='utf-8')
                self.assertNotEqual(provenance.numerical_source_identity(), changed)
                changed = provenance.numerical_source_identity()
                assets.return_value = {'model': 'different'}
                self.assertNotEqual(provenance.numerical_source_identity(), changed)
                changed = provenance.numerical_source_identity()
                with patch('stemwise_engine.original_delivery.STRICT_DECODE_OPTIONS', ['-different-decode-policy']):
                    self.assertNotEqual(provenance.numerical_source_identity(), changed)
                with patch('stemwise_engine.contract.WAVEFORM_ENCODING', 'different-waveform-encoding'):
                    self.assertNotEqual(provenance.numerical_source_identity(), changed)
                with patch('stemwise_engine.contract.WAVEFORM_LEVELS_MS', frozenset((10, 50, 250))):
                    self.assertNotEqual(provenance.numerical_source_identity(), changed)
        self.assertEqual(provenance_fingerprint({'numericalProducer': original, 'sourceProvenance': 'old'}),
                         provenance_fingerprint({'numericalProducer': original, 'sourceProvenance': 'new'}))

    def test_acoustic_identity_excludes_downloader_and_reuses_model_pins_without_reading_weights(self):
        import stemwise_engine.provenance as provenance
        environment = {'available': True, 'packages': {'torch': {'version': 'numerical'},
            'huggingface-hub': {'version': 'downloader-v1'}}}
        with patch.object(provenance, '_main_model_identities', side_effect=AssertionError('No weight hashing')), \
                patch.object(provenance, '_allin1_model_identity', side_effect=AssertionError('No weight hashing')), \
                patch.object(provenance, '_htdemucs_model_identity', side_effect=AssertionError('No weight hashing')), \
                patch.object(provenance, 'package_identity', side_effect=lambda name: {'version': name}), \
                patch.object(provenance, '_allin1_environment', return_value=environment), \
                patch.object(provenance, 'executable_identity', return_value={'version': 'fixture'}):
            snapshot = collect_acoustic_provenance()
            environment['packages']['huggingface-hub']['version'] = 'downloader-v2'
            self.assertEqual(provenance_fingerprint(snapshot), provenance_fingerprint(collect_acoustic_provenance()))
        self.assertFalse({'yt-dlp', 'Pillow', 'requests'} & set(snapshot['packages']))
        self.assertEqual(snapshot['models']['beatThisFinal0'], provenance.BEAT_THIS_FINAL0_SHA256)
        self.assertEqual(snapshot['models']['allInOne']['files'], provenance.ALLIN1_MODEL_SHA256)
        self.assertIn('torch', snapshot['packages'])
        self.assertNotIn('huggingface-hub', snapshot['allInOneEnvironment']['packages'])

    def test_file_identity_distinguishes_missing_verified_and_changed(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as directory:
            path = Path(directory) / "weights.bin"
            expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            self.assertEqual(
                {"fileName": path.name, "available": False, "expectedSha256": expected},
                file_identity(path, expected_sha256=expected),
            )

            path.write_bytes(b"hello")
            exact = file_identity(path, expected_sha256=expected)
            self.assertTrue(exact["available"])
            self.assertTrue(exact["verified"])
            self.assertEqual(5, exact["bytes"])

            path.write_bytes(b"changed")
            self.assertFalse(file_identity(path, expected_sha256=expected)["verified"])

    def test_fingerprint_is_canonical_and_changes_with_identity(self) -> None:
        left = {"packages": {"skey": "0.1.0"}, "models": {"skey": "abc"}}
        reordered = {"models": {"skey": "abc"}, "packages": {"skey": "0.1.0"}}
        changed = {"models": {"skey": "def"}, "packages": {"skey": "0.1.0"}}
        self.assertEqual(provenance_fingerprint(left), provenance_fingerprint(reordered))
        self.assertNotEqual(provenance_fingerprint(left), provenance_fingerprint(changed))

    def test_snapshot_stays_pc_only_and_handles_missing_allin1_environment(self) -> None:
        snapshot = collect_provenance(allin1_python=Path("/does/not/exist"))
        self.assertEqual(PROVENANCE_VERSION, snapshot["provenanceVersion"])
        self.assertIn("ffmpeg", snapshot["decoders"])
        self.assertIn("skey", snapshot["models"])
        self.assertFalse(snapshot["allInOneEnvironment"]["available"])
        self.assertNotIn("generatedAt", snapshot)
        self.assertEqual(64, len(provenance_fingerprint(snapshot)))


if __name__ == "__main__":
    unittest.main()
