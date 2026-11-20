"""Normal PC module entry points, substituting only expensive numerical APIs."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from allin1_fixtures import worker_fixture

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'PC side/stemwise-playlist-builder/src'


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class PcOrchestrationEntryTests(unittest.TestCase):
    def test_completed_playlist_checkpoint_reuses_actual_producer_and_preserves_bytes(self):
        import copy
        from PIL import Image
        from stemwise_engine.common import atomic_write_json, create_manifested_directory, sha256_file
        from stemwise_engine.future_song import FutureSongError, FutureSongWorkflow
        from stemwise_engine.lyrics_align_pc import _checkpoint_mix_sha256
        from stemwise_engine.source_acquisition import SourceAcquisition
        import numpy as np
        import soundfile as sf
        from stemwise_engine.packages import create_child_zip
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.provenance import numerical_source_identity, provenance_fingerprint
        from test_packages import package_config, song_document
        current = {'provenanceVersion': 2, 'numericalProducer': numerical_source_identity(),
                   'runtime': {'python': 'fixture'}, 'packages': {'decoder': {'version': 'exact'}}}
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory, \
                patch('stemwise_engine.pipeline.collect_provenance', return_value=current), \
                patch('stemwise_engine.pipeline.probe_duration_ms', return_value=1000) as duration_probe:
            root = Path(directory)
            pipeline = StemwisePipeline({**package_config(root), 'retainSuccessfulAnalysisAudio': True})
            item = {'videoId': 'abcdefghijk', 'title': 'Exact recording'}
            document = song_document(item['videoId'])
            cover = root / 'cover.png'
            Image.new('RGB', (32, 32), 'red').save(cover)
            child = pipeline.child_path(item['videoId'])
            create_child_zip(child, document, cover, pipeline.config)
            state = pipeline._new_state(item, 'absent')
            pipeline.temp_root.mkdir(parents=True, exist_ok=True)
            temporary = create_manifested_directory(pipeline.temp_root, pipeline.temp_root / item['videoId'],
                owner={'kind': 'analysis-temporary', 'videoId': item['videoId']}, workspace_root=root)
            audio = temporary / 'analysis.wav'
            sf.write(audio, np.zeros((48000, 2)), 48000, subtype='FLOAT')
            state.update(status='complete', childSha256=sha256_file(child),
                         provenanceSha256=provenance_fingerprint(current), originalAudio={
                             'retained': True, 'sha256': sha256_file(audio),
                             'byteCount': audio.stat().st_size, 'durationMs': 1000})
            pipeline.save_checkpoint(state)
            checkpoint = pipeline.checkpoint_path(item['videoId'])
            saved = (child.read_bytes(), checkpoint.read_bytes())
            with patch.object(pipeline, '_analysis', side_effect=AssertionError('Unchanged inference repeated')), \
                    patch.object(pipeline, '_song_info', side_effect=AssertionError('Unchanged source reacquired')):
                self.assertEqual(pipeline.process_song(item), 'skipped')
            from workflow_fixture import initialized_workflow
            workflow = initialized_workflow(root)
            workflow.workspace_root, workflow.jobs, workflow.ready = root, root / 'jobs', root / 'ready'
            workflow.config, workflow.runtime = pipeline.config, pipeline.output_dir
            workflow._pipeline = lambda: pipeline
            job = {'videoId': item['videoId'], 'state': 'VALIDATION_FAILED'}
            atomic_write_json(workflow._job_path(item['videoId']), job)
            draft = workflow._job_dir(item['videoId']) / 'drafts/original.txt'
            draft.parent.mkdir()
            draft.write_bytes(b'Owner approved words')
            with patch.object(workflow, '_process_song', side_effect=AssertionError('Unchanged analysis forced')), \
                    patch.object(workflow, '_song_info', side_effect=AssertionError('Source metadata reacquired')), \
                    patch.object(workflow, '_write_task') as write_task:
                workflow.acquisition(job['videoId']).run(job, force_analysis=True)
                retained = workflow._completed_analysis_audio(pipeline, item['videoId'])
            duration_probe.assert_any_call(audio)
            write_task.assert_not_called()
            self.assertEqual(job['state'], 'VALIDATION_FAILED')
            self.assertEqual(retained['path'].read_bytes(), audio.read_bytes())
            self.assertEqual(draft.read_bytes(), b'Owner approved words')
            self.assertEqual(_checkpoint_mix_sha256(pipeline.output_dir, pipeline.config, item['videoId'],
                duration_ms=1000, active_pipeline=pipeline), sha256_file(audio))
            report = pipeline.write_report()
            self.assertEqual(report['states'][0]['provenanceSha256'], provenance_fingerprint(current))
            for field in ('allin1_worker.py', 'modelAssets', 'runtime'):
                changed = copy.deepcopy(current)
                if field == 'runtime':
                    changed['runtime']['python'] = 'changed'
                else:
                    changed['numericalProducer'][field] = '0' * 64
                pipeline.provenance = changed
                pipeline.provenance_sha256 = provenance_fingerprint(changed)
                self.assertFalse(pipeline._can_skip(item, force=False, lyrics=document['lyrics'],
                                                   owner_lyrics_sha256='absent'), field)
                with self.assertRaises(FutureSongError):
                    workflow._completed_analysis_audio(pipeline, item['videoId'])
                with self.assertRaises(ValueError):
                    _checkpoint_mix_sha256(pipeline.output_dir, pipeline.config, item['videoId'],
                        duration_ms=1000, active_pipeline=pipeline)
            self.assertEqual((child.read_bytes(), checkpoint.read_bytes()), saved)

    def test_environment_package_probe_has_machine_stdout_and_rejects_unknown_commands(self):
        command = [sys.executable, '-B', str(SOURCE / 'environment_probe.py')]
        completed = subprocess.run([*command, 'packages', 'stemwise-deliberately-absent-fixture'],
                                   capture_output=True, text=True, timeout=10)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        value = json.loads(completed.stdout)
        self.assertEqual(value['python'], sys.version.split()[0])
        self.assertEqual(value['packages'], {'stemwise-deliberately-absent-fixture': {'available': False}})
        rejected = subprocess.run([*command, 'unknown'], capture_output=True, text=True, timeout=10)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertEqual(rejected.stdout, '')

    def test_current_batch_entry_publishes_exact_inputs_and_preserves_results_on_setup_rejection(self):
        from stemwise_engine.common import run_process_tree
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            driver, environment = worker_fixture(root, 'success')
            inputs = []
            for index in range(2):
                audio = root / f'input-{index}.wav'
                audio.write_bytes(bytes([index]))
                inputs.append({'index': index, 'audio': str(audio), 'source': str(audio), 'durationMs': 1000})
            request = root / 'request.json'
            request.write_text(json.dumps({'requestId': 'exact-current', 'timeoutSeconds': 5,
                                           'inputs': inputs}), encoding='utf-8')
            command = [sys.executable, '-B', str(driver), 'batch', '--timeout-seconds', '5', str(request)]
            completed = run_process_tree(command, capture_output=True, text=True, timeout=10, env=environment)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            saved = {}
            for item in inputs:
                path = root / f'result-{item["index"]}.json'
                saved[path] = path.read_bytes()
                row = json.loads(saved[path])
                self.assertEqual({key: row[key] for key in ('index', 'source', 'durationMs')},
                                 {key: item[key] for key in ('index', 'source', 'durationMs')})
                self.assertEqual(row['requestId'], 'exact-current')
                self.assertEqual(row['result']['segments'], [{'start': 0, 'end': 1, 'label': 'verse'}])
            hub = root / 'fake-models/huggingface_hub.py'
            hub.write_text(hub.read_text(encoding='utf-8').replace("b'fixture model'", "b'wrong model'"),
                           encoding='utf-8')
            rejected = run_process_tree(command, capture_output=True, text=True, timeout=10, env=environment)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn('identity mismatch', rejected.stderr)
            self.assertNotIn('STEMWISE_RESULT=', rejected.stdout)
            for path, data in saved.items():
                self.assertEqual(path.read_bytes(), data)


if __name__ == '__main__':
    unittest.main()
