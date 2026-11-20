"""Benchmark scratch stays owned through unresolved child retirement."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import patch
from contextlib import redirect_stderr, redirect_stdout

import numpy as np
import soundfile as sf
from stemwise_engine import common
from stemwise_engine import runtime_environment

PC = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PC/'performance'))
import benchmark_alignment_preparation as benchmark


class AlignmentPreparationBenchmarkTest(unittest.TestCase):
    def test_main_retains_unretired_inputs_and_leases_then_cleans_after_retirement(self):
        from process_fixture import prepare_runtime_layout
        from stemwise_engine.lyrics_align_pc import _worker_pcm_duration_ms
        from stemwise_engine import mms_forced_alignment as mms
        for failure in (None, ValueError('ordinary child failure'), common.WorkerRetirementError('child did not retire')):
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory(dir=PC.parent/'work') as name:
                root = Path(name)
                (root/'Test suite/work').mkdir(parents=True)
                project = root/'PC side/stemwise-playlist-builder'
                worker = project/'src/mms_forced_alignment.py'
                worker.parent.mkdir(parents=True)
                worker.write_bytes(b'fixture worker')
                prepare_runtime_layout(project)
                layout_path = project.parent/'runtime-layout.json'
                layout = json.loads(layout_path.read_bytes())
                layout['environments']['main'] = 'custom/main/bin/python3.10'
                layout_path.write_text(json.dumps(layout), encoding='utf-8')
                configured = project.parent/layout['environments']['main']
                legacy = project/'.venv/bin/python'
                for path in (configured, legacy):
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b'configured interpreter' if path == configured else b'decoy interpreter')
                checkpoint = root/'PC side/model-cache/torch/hub/checkpoints/model.pt'
                checkpoint.parent.mkdir(parents=True)
                checkpoint.write_bytes(b'fixture checkpoint')
                audio = [root/f'{index}.wav' for index in range(2)]
                specs = ((48024, 48000, 1001), (7938196, 44100, 180005)) if failure is None else (
                    (48000, 48000, 1000), (44100, 44100, 1000))
                for index, path in enumerate(audio):
                    frames, rate, _ = specs[index]
                    sf.write(path, np.full(frames, .1 + index*.1), rate)
                work = root/'Test suite/work/benchmark'
                source_bytes = {path: path.read_bytes() for path in [*audio, checkpoint, worker, configured, legacy, layout_path]}
                held, leases, fail = [], [], True
                def run(command, **kwargs):
                    self.assertEqual(Path(command[0]), configured)
                    with runtime_environment.command_environment(command, kwargs) as inherited:
                        env_leases = json.loads(inherited['env'][runtime_environment._INHERITED])
                        main_lease = next(record for record in env_leases
                            if record[0] == str(project/'.environment-use-main.lock'))
                        self.assertIn(main_lease[1], inherited['pass_fds'])
                        self.assertFalse(main_lease[2])
                    descriptor = common.process_lock_descriptor(work/common.WORKER_DIRECTORY_LOCK)
                    leases.append(descriptor is not None)
                    request, reply = Path(command[-3]), Path(command[-2])
                    contexts = json.loads(request.read_bytes())['contexts']
                    for context in contexts:
                        value = {'formatVersion': mms.FORMAT_VERSION, 'durationMs': context['durationMs'],
                            'lines': [{'lineId': 'line', 'sourcePhysicalIndex': 0,
                                'words': [{'wordId': 'word', 'acousticText': 'one'}]}],
                            'segments': [{'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 1,
                                'windowStartMs': 0, 'windowEndMs': context['durationMs']}]}
                        with patch.object(mms.ctc_worker, 'cached_emissions', return_value=(None, [], 480000)):
                            prepared = mms._prepare(Path(context['audio']), request, 'cpu', {},
                                request_value=value, emissions_only=True)
                        self.assertEqual(prepared['duration_ms'], specs[int(context['contextId'])][2])
                        Path(context['cacheOutput']).write_text(json.dumps({'key': context['emissionCacheKey']}), encoding='utf-8')
                    reply.write_bytes(b'partial or completed reply')
                    if fail and failure is not None:
                        if isinstance(failure, common.WorkerRetirementError) and descriptor is not None:
                            held.append(os.dup(descriptor))
                        raise failure
                    return subprocess.CompletedProcess(command, 0,
                        'STEMWISE_PREP_BENCH='+json.dumps({'modelLoads': 1, 'modelLoadSeconds': .01}), '')
                lyrics = ModuleType('stemwise_engine.lyrics_align_pc')
                lyrics.PINNED_MMS_SHA256 = hashlib.sha256(checkpoint.read_bytes()).hexdigest()
                lyrics._worker_pcm_duration_ms = _worker_pcm_duration_ms
                models = ModuleType('stemwise_engine.alignment_models')
                def pinned(path, digest, label): self.assertEqual(common.sha256_file(path), digest)
                models.require_pinned_file = pinned
                torch = ModuleType('torch')
                torch.Tensor = type('FixtureTensor', (), {})
                torch.device = lambda name: SimpleNamespace(type=name)
                torch.load = lambda path, **kwargs: json.loads(Path(path).read_bytes())
                torchaudio = ModuleType('torchaudio')
                torchaudio.pipelines = SimpleNamespace(MMS_FA=SimpleNamespace(sample_rate=16000,
                    get_tokenizer=lambda: lambda words: [[1] for word in words],
                    get_labels=lambda **kwargs: ['-', 'o'],
                    get_model=lambda **kwargs: self.fail('preflight must not load a model')))
                captured = io.StringIO()
                arguments = ['benchmark_alignment_preparation.py', '--audio', *map(str, audio), '--work', str(work), '--device', 'cpu']
                try:
                    with patch.object(benchmark, 'ROOT', root), patch.object(benchmark, 'PROJECT', project), \
                            patch.object(benchmark, 'WORKER', worker), patch.object(benchmark, 'telemetry'), \
                            patch.object(common, 'run_process_tree', side_effect=run), \
                            patch.dict(sys.modules, {lyrics.__name__: lyrics, models.__name__: models,
                                'torch': torch, 'torchaudio': torchaudio}), \
                            patch.object(sys, 'argv', arguments), redirect_stdout(io.StringIO()), redirect_stderr(captured):
                        if failure is None: benchmark.main()
                        else:
                            with self.assertRaises(type(failure)) as raised: benchmark.main()
                            self.assertIs(raised.exception, failure)
                        retained = isinstance(failure, common.WorkerRetirementError)
                        self.assertEqual(work.exists(), retained)
                        if retained:
                            self.assertIn(str(work), captured.getvalue())
                            before = {path: path.read_bytes() for path in work.rglob('*') if path.is_file()}
                            self.assertTrue(list(work.rglob('*.request.json')))
                            self.assertTrue(list(work.rglob('emissions-*.pt')))
                            owner = {'kind': 'alignment-preparation-benchmark'}
                            with self.assertRaises(common.ProcessLockBusyError):
                                common.remove_manifested_directory(root/'Test suite/work', work, owner=owner, workspace_root=root)
                            self.assertEqual({path: path.read_bytes() for path in before}, before)
                            for descriptor in held: os.close(descriptor)
                            held.clear()
                            common.remove_manifested_directory(root/'Test suite/work', work, owner=owner, workspace_root=root)
                            fail = False
                            benchmark.main()
                            self.assertFalse(work.exists())
                        self.assertEqual({path: path.read_bytes() for path in source_bytes}, source_bytes)
                        self.assertTrue(leases and all(leases), 'generated requests/emissions need a covering inherited lease')
                finally:
                    for descriptor in held: os.close(descriptor)


if __name__ == '__main__':
    unittest.main()
