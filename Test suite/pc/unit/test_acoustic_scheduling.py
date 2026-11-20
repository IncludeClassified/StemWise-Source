"""Independent CPU stages overlap one GPU lane without skipping results or leases."""
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from pathlib import Path
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[3] / 'PC side/stemwise-playlist-builder'
import stemwise_engine.pipeline as pipeline
import stemwise_engine.analysis_numerics as analysis_numerics
from stemwise_engine.common import exclusive_process_lock, process_lock_descriptor, ProcessLockBusyError


class AcousticSchedulingTests(unittest.TestCase):
    def test_cpu_stages_overlap_serial_gpu_stages_and_inherit_the_lease(self):
        gpu = threading.Event()
        structure = threading.Event()
        cpu = threading.Event()
        stages = []
        main_thread = threading.get_ident()
        with tempfile.TemporaryDirectory(dir=PROJECT.parent.parent / 'Test suite/work') as directory:
            lease_path = Path(directory) / 'heavy.lock'

            def stage(name):
                self.assertIsNotNone(process_lock_descriptor(lease_path))
                stages.append(name)
                return {'name': name, 'status': 'complete'}

            def beat(*args):
                tool = stage('beats')
                gpu.set()
                self.assertTrue(structure.wait(2), 'Structure must overlap GPU work')
                self.assertTrue(cpu.wait(2), 'Essentia must overlap GPU work')
                return [100], [100], tool

            def key(*args):
                self.assertIn('beats', stages)
                return {'status': 'complete', 'label': 'C'}, stage('key')

            def allin1(*args, **kwargs):
                self.assertEqual(threading.get_ident(), main_thread, 'Subprocess cancellation needs the main thread')
                tool = stage('structure')
                structure.set()
                self.assertTrue(gpu.wait(2), 'GPU work must overlap structure')
                return {'status': 'complete', 'sections': [1]}, {'bpm': 120}, tool

            def essentia(*args):
                tool = stage('essentia')
                cpu.set()
                self.assertTrue(gpu.wait(2))
                return {'status': 'complete', 'cents': 0}, {'status': 'complete', 'lufs': -12}, tool

            with exclusive_process_lock(lease_path), \
                    patch.object(analysis_numerics, 'analyze_beat_this', beat), \
                    patch.object(analysis_numerics, 'normalize_rhythm', return_value=({'status': 'complete', 'beatsMs': [100]}, {'name': 'normalizer', 'status': 'complete'})), \
                    patch.object(analysis_numerics, 'analyze_skey', key), \
                    patch.object(analysis_numerics, 'analyze_allin1', allin1), \
                    patch.object(analysis_numerics, 'analyze_essentia', essentia), \
                    patch.object(analysis_numerics, 'build_waveform', return_value={'status': 'complete', 'peaks': [2]}):
                result = analysis_numerics._analysis_worker(Path(directory) / 'audio.wav', 1000, PROJECT, 10)
        self.assertEqual(result['status'], 'complete')
        self.assertEqual(result['key']['label'], 'C')
        self.assertEqual(result['structure']['sections'], [1])
        self.assertEqual(result['tuning']['cents'], 0)
        self.assertEqual(result['loudness']['lufs'], -12)
        self.assertEqual(result['waveform']['peaks'], [2])
        self.assertEqual(result['diagnostics'], {'allInOneRhythm': {'bpm': 120}})
        self.assertEqual([x['name'] for x in result['toolRuns']],
                         ['beats', 'normalizer', 'key', 'structure', 'essentia', 'stemwise-waveform'])

    def test_stage_exception_does_not_return_success(self):
        with patch.object(analysis_numerics, 'analyze_beat_this', side_effect=RuntimeError('failed GPU')), \
                patch.object(analysis_numerics, 'analyze_allin1', return_value=({}, None, {})), \
                patch.object(analysis_numerics, 'analyze_essentia', return_value=({}, {}, {})), \
                patch.object(analysis_numerics, 'build_waveform', return_value={}):
            with self.assertRaisesRegex(RuntimeError, 'failed GPU'):
                analysis_numerics._analysis_worker(Path('audio.wav'), 1000, PROJECT, 10)

    def test_second_analysis_waits_for_the_resource_instead_of_failing(self):
        launched = threading.Event()
        requested = threading.Event()
        errors = []
        results = []
        def child(*args, **kwargs):
            launched.set()
            return SimpleNamespace(returncode=0, stdout='STEMWISE_ANALYSIS_RESULT={"durationMs":1000}')

        with tempfile.TemporaryDirectory(dir=PROJECT.parent.parent / 'Test suite/work') as directory:
            project = Path(directory)
            (project / 'audio.wav').write_bytes(b'owned scheduling input')
            def request():
                requested.set()
                try:
                    results.append(pipeline.run_analysis_worker(project / 'audio.wav', 1000, project, 10))
                except Exception as error:
                    errors.append(error)
            with patch.object(pipeline, 'run_process_tree', child):
                with exclusive_process_lock(pipeline.heavy_worker_lock_path({'projectDir': str(project)})):
                    waiting = threading.Thread(target=request)
                    waiting.start()
                    self.assertTrue(requested.wait(1))
                    self.assertFalse(launched.wait(.2), 'A second model worker must never overlap the holder')
                waiting.join(2)
        self.assertFalse(waiting.is_alive())
        self.assertEqual(errors, [], 'Resource contention must queue, not fail analysis')
        self.assertEqual(results, [{'durationMs': 1000}])

    def test_queue_timeout_cannot_admit_a_second_holder(self):
        with tempfile.TemporaryDirectory(dir=PROJECT.parent.parent / 'Test suite/work') as directory:
            path = Path(directory) / 'stemwise-heavy-worker.lock'
            with exclusive_process_lock(path):
                with self.assertRaises(ProcessLockBusyError):
                    with exclusive_process_lock(path, wait_seconds=.1):
                        self.fail('An expired wait must never acquire a held resource')

    def test_future_pipeline_releases_resource_for_preparation_and_publication(self):
        from stemwise_engine.future_song import FutureSongWorkflow
        with tempfile.TemporaryDirectory(dir=PROJECT.parent.parent / 'Test suite/work') as directory:
            project = Path(directory)
            (project / 'audio.wav').write_bytes(b'owned scheduling input')
            workflow = FutureSongWorkflow.__new__(FutureSongWorkflow)
            workflow.config = {'projectDir': str(project)}
            lock = pipeline.heavy_worker_lock_path(workflow.config)
            phases = []
            def model(*args, **kwargs):
                self.assertIsNotNone(process_lock_descriptor(lock))
                with self.assertRaises(ProcessLockBusyError):
                    with exclusive_process_lock(lock, wait_seconds=0):
                        self.fail('Model work cannot overlap another owner')
                phases.append('model')
                return SimpleNamespace(returncode=0, stdout='STEMWISE_ANALYSIS_RESULT={"durationMs":1000}')
            def process(item, *, force=False):
                self.assertTrue(force)
                for phase in ('metadata', 'decode'):
                    self.assertIsNone(process_lock_descriptor(lock), phase + ' must not occupy the resource')
                    with exclusive_process_lock(lock, wait_seconds=0):
                        phases.append(phase)
                pipeline.run_analysis_worker(project / 'audio.wav', 1000, project, 10)
                self.assertIsNone(process_lock_descriptor(lock), 'Packaging must release the resource')
                with exclusive_process_lock(lock, wait_seconds=0):
                    phases.append('package')
                return 'complete'
            with patch.object(pipeline, 'run_process_tree', model):
                result = workflow._process_song(SimpleNamespace(process_song=process), {}, force=True)
        self.assertEqual(result, 'complete')
        self.assertEqual(phases, ['metadata', 'decode', 'model', 'package'])
