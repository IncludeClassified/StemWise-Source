"""Real numerical failure orchestration with substituted child process results."""
import hashlib
import os
import sys
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.alignment_models as alignment_models
import stemwise_engine.lyrics_align_pc as align


class WorkerFailureTests(unittest.TestCase):
    def test_typed_worker_failure_round_trip_ignores_human_wording(self):
        import stemwise_engine.worker_protocol as worker_protocol
        from stemwise_engine.common import run_process_tree
        script = "\nimport sys\nfrom stemwise_engine.worker_protocol import WorkerReasonError, run_worker_main\ndef main(): raise WorkerReasonError('wav2vec2-segment-boundary-guard:2:end', sys.argv[1])\nraise SystemExit(run_worker_main(main))\n"
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            for wording in ('recovery failed', 'coverage changed; artwork cover.png'):
                completed = run_process_tree([sys.executable, '-B', '-c', script, wording],
                    env={**os.environ, 'PYTHONPATH': str(ROOT/'PC side/stemwise-playlist-builder/src')},
                    capture_output=True, text=True, timeout=10)
                self.assertEqual(completed.returncode, 1)
                self.assertEqual(completed.stdout, '')
                with self.assertRaises(worker_protocol.WorkerProcessError) as caught:
                    worker_protocol.raise_worker_failure('alignment', completed, root/'output.json')
                self.assertEqual(caught.exception.code, 'wav2vec2-segment-boundary-guard:2:end')
            completed.stderr = 'ValueError: CUDA out of memory while reading cover.png'
            with self.assertRaises(worker_protocol.WorkerProcessError) as caught:
                worker_protocol.raise_worker_failure('alignment', completed, root/'output.json')
            self.assertEqual(caught.exception.code, 'process-exit')

    def test_forced_model_exits_flush_original_initialization_emission_and_retirement_causes(self):
        import stemwise_engine.worker_protocol as worker_protocol
        from stemwise_engine.common import run_process_tree, WorkerRetirementError
        script = "\nimport sys, traceback\nfrom contextlib import nullcontext\nfrom pathlib import Path\nfrom types import SimpleNamespace\nmode, cause = sys.argv[1:]\ndef fail(*args, **kwargs): raise ValueError(cause)\nif mode.startswith('nfa-'):\n import stemwise_engine.nemo_nfa_forced_alignment as worker\n sys.modules['torch'] = SimpleNamespace(Tensor=type('Tensor', (), {}))\n sys.modules['omegaconf'] = SimpleNamespace(OmegaConf=SimpleNamespace(\n     structured=lambda value: value, from_dotlist=lambda value: value, merge=lambda *values: None))\n worker.heavy_worker_stage = lambda _: nullcontext()\n model = SimpleNamespace(to=fail if mode.endswith('retirement') else lambda *a: None)\n module = SimpleNamespace(AlignmentConfig=object, setup_model=fail if mode.endswith('initialization') else lambda: (model,),\n     get_batch_variables=fail if mode.endswith('emission') else lambda: (), viterbi_decoding=lambda: None)\n def main(cfg):\n  module.setup_model()\n  module.get_batch_variables()\n module.main = main\n worker._load_nfa_module = lambda _: module\n worker.retire_model = lambda state: state.clear()\n worker._execute_nfa(['python', str(Path.cwd()/'align.py')], Path.cwd(), {})\nelse:\n import stemwise_engine.owsm_ctc_forced_alignment as worker\n worker.heavy_worker_stage = lambda _: nullcontext()\n worker._load_aligner = fail if mode.endswith('initialization') else lambda *a: SimpleNamespace(s2t_model=object(), ctc=object())\n worker.retire_model = fail\n with worker._model_stage({}, 'fixture-route'): pass\n"
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            for mode in ('nfa-initialization', 'nfa-emission', 'nfa-retirement',
                         'owsm-initialization', 'owsm-retirement'):
                with self.subTest(mode=mode):
                    cause = 'original-private-' + mode
                    completed = run_process_tree([sys.executable, '-B', '-c', script, mode, cause],
                        cwd=root, env={**os.environ, 'PYTHONPATH': str(ROOT / 'PC side/stemwise-playlist-builder/src')},
                        capture_output=True, text=True, check=False, timeout=30)
                    self.assertEqual(completed.returncode, 70, completed.stderr)
                    with self.assertRaises(WorkerRetirementError) as caught:
                        worker_protocol.raise_worker_failure(mode, completed, root / 'output.json')
                    reference = caught.exception.private_diagnostics[0]
                    body = (root / reference['file']).read_bytes()
                    self.assertIn(cause.encode(), body)
                    self.assertIn(b'Traceback', body)
                    self.assertEqual(hashlib.sha256(body).hexdigest(), reference['sha256'])
                    self.assertNotIn(cause, str(caught.exception))

    def assert_private_cause(self, error, root, expected):
        refs = error.private_diagnostics
        self.assertEqual(len(refs), 1)
        reference = refs[0]
        files = list(root.rglob(reference['file']))
        self.assertEqual(len(files), 1)
        body = files[0].read_bytes()
        self.assertLessEqual(len(body), 64*1024)
        self.assertEqual(hashlib.sha256(body).hexdigest(), reference['sha256'])
        self.assertEqual(len(body), reference['byteCount'])
        self.assertTrue(body.decode('utf-8').endswith(expected))
        self.assertNotIn(expected, str(error))

    def test_all_runtime_probe_failures_retain_the_original_child_stderr(self):
        for family in ('mms', 'wav2vec2', 'roformer'):
            with self.subTest(family=family), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
                root = Path(name)
                helper = root / 'PC side/builder/src/worker.py'
                helper.parent.mkdir(parents=True)
                helper.write_text('not executed', encoding='utf-8')
                cause = 'private child failure-' + family
                completed = SimpleNamespace(returncode=7, stdout='', stderr=cause)
                method = getattr(align, '_' + family + '_runtime_provenance')
                process = 'run_process_tree' if family == 'roformer' else '_run_numerical_process'
                with patch.object(alignment_models, family + '_runtime_pins', return_value={}), \
                        patch.object(align, process, return_value=completed), self.assertRaises(RuntimeError) as caught:
                    method(Path('unused-python'), helper, device='cpu', timeout_seconds=1)
                self.assert_private_cause(caught.exception, root, cause)

    def test_roformer_separation_failure_keeps_cause_after_attempt_scratch_is_removed(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            project = root / 'PC side/builder'
            helper = project / 'src/roformer_separation.py'
            sys.path.insert(0, str(ROOT/'Test suite/pc'))
            from process_fixture import prepare_runtime_layout
            prepare_runtime_layout(project)
            python = align.environment_python('main', project)
            for path in (helper, python):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'not executed')
            audio = root / 'owner.wav'
            audio.write_bytes(b'preserved owner audio')
            workspace = align.AlignmentWorkspace(root / 'agent', workspace_root=root)
            video = 'abcdefghijk'
            workspace.job_dir(video).mkdir(parents=True)
            cause = 'private model load failure'
            with tempfile.TemporaryDirectory(dir=root) as scratch, \
                    patch.object(alignment_models, 'require_roformer_wheel',
                        return_value=alignment_models.ROFORMER_PACKAGE_WHEEL_SHA256) as wheel, \
                    patch.object(alignment_models, 'roformer_assets', return_value={'checkpoint': root/'model',
                        'checkpointSha256': 'a'*64, 'config': root/'model.json', 'configSha256': 'b'*64}), \
                    patch.object(align, '_roformer_runtime_provenance', return_value={}), \
                    patch.object(align, 'heavy_worker_stage', return_value=nullcontext()), \
                    patch.object(align, 'run_process_tree',
                        return_value=SimpleNamespace(returncode=2, stdout='', stderr=cause)) as child:
                with self.assertRaises(align.worker_protocol.WorkerProcessError) as caught:
                    align._run_roformer_separation(config={'projectDir': str(project)}, video_id=video,
                        route_id='kimberley-jensen-roformer-vocals', input_audio=audio,
                        input_identity={'sha256': 'c'*64}, workspace=workspace, duration_ms=1000,
                        expected_mix_sha256='c'*64, temp_dir=Path(scratch), device='cpu', timeout_seconds=1)
            wheel.assert_called_once_with(project.parent)
            child.assert_called_once()
            self.assert_private_cause(caught.exception, workspace.job_dir(video), cause)
            self.assertEqual(audio.read_bytes(), b'preserved owner audio')
