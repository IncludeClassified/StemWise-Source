import json
import copy
import importlib.util
import os
from pathlib import Path
import subprocess
import signal
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.common as common
import stemwise_engine.lyrics_align_pc as alignment
import stemwise_engine.nemo_nfa_forced_alignment as nfa


class NfaSchedulingTests(unittest.TestCase):
    def test_nfa_benchmark_imports_candidate_helpers(self):
        import io
        import shutil
        import sysconfig
        from contextlib import redirect_stdout
        path = ROOT/'Test suite/pc/performance/benchmark_owsm_scheduling.py'
        spec = importlib.util.spec_from_file_location('nfa_candidate_benchmark', path)
        benchmark = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(benchmark)
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            project = root/'PC side/builder'
            source = project/'src'
            source.mkdir(parents=True)
            (root/'Test suite/work').mkdir(parents=True)
            python = project.parent/'runtime/nemo-speech-3.0.0/.venv/bin/python'
            python.parent.mkdir(parents=True)
            python.symlink_to(sys.executable)
            shutil.copyfile(Path(sys.prefix)/'pyvenv.cfg', python.parent.parent/'pyvenv.cfg')
            packages = python.parent.parent/'lib'/f'python{sys.version_info.major}.{sys.version_info.minor}'/'site-packages'
            packages.parent.mkdir(parents=True)
            packages.symlink_to(sysconfig.get_path('purelib'), target_is_directory=True)
            (source/'audit_candidate_helper.py').write_text("REVISION = 'candidate-only'\n", encoding='utf-8')
            (source/'nemo_nfa_forced_alignment.py').write_text('''import argparse,hashlib,json
from pathlib import Path
import numpy as np
import soundfile as sf
from stemwise_engine.audit_candidate_helper import REVISION
def _prepare_mono_audio(source, target):
    sf.write(target, np.zeros((16, 1), dtype='float32'), 16000, subtype='FLOAT')
    return {'sampleRate':16000,'frameCount':16,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()}
def run(args):
    assert REVISION == 'candidate-only'
    raw=Path(args.request).read_bytes();request=json.loads(raw)
    preparation=_prepare_mono_audio(None,Path(args.output).with_suffix('.wav'))
    result={'request':{'sha256':args.request_sha256,'byteCount':len(raw)},
        'audio':request['audio'],'lineCount':len(request['lines']), 'lines':[], 'preprocessing':preparation}
    Path(args.output).write_text(json.dumps(result),encoding='utf-8')
    return 0
def main():
    parser=argparse.ArgumentParser()
    for name in ('request','request-sha256','output','timeout-seconds'):parser.add_argument('--'+name,required=True)
    return run(parser.parse_args())
''', encoding='utf-8')
            request = root/'request.json'
            request.write_text(json.dumps({'audio': {'sha256': 'a'*64}, 'durationMs': 1000, 'lines': [{}]}), encoding='utf-8')
            args = ['benchmark', '--nfa', '--request', str(request), '--source', str(source),
                    '--rounds', '1', '--jobs-per-round', '1', '--timeout', '30']
            output = io.StringIO()
            with patch.object(benchmark, 'PROJECT', project), patch.object(benchmark, 'ROOT', root), \
                    patch.object(sys, 'argv', args), redirect_stdout(output):
                benchmark.main()
            self.assertTrue(json.loads(output.getvalue().splitlines()[-1])['identicalControlledResults'])

    def test_current_benchmark_runs_seeded_children_and_rejects_a_changed_reference(self):
        import hashlib
        import io
        import shutil
        import sysconfig
        from contextlib import redirect_stdout
        path = ROOT / 'Test suite/pc/performance/benchmark_owsm_scheduling.py'
        spec = importlib.util.spec_from_file_location('current_endpoint_benchmark', path)
        benchmark = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(benchmark)
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            project = root / 'PC side/builder'
            source = project / 'src'
            source.mkdir(parents=True)
            (root / 'Test suite/work').mkdir(parents=True)
            python = project.parent / 'runtime/espnet-202511-owsm/.venv/bin/python'
            python.parent.mkdir(parents=True)
            python.symlink_to(sys.executable)
            shutil.copyfile(Path(sys.prefix) / 'pyvenv.cfg', python.parent.parent / 'pyvenv.cfg')
            packages = python.parent.parent / 'lib' / f'python{sys.version_info.major}.{sys.version_info.minor}' / 'site-packages'
            packages.parent.mkdir(parents=True)
            packages.symlink_to(sysconfig.get_path('purelib'), target_is_directory=True)
            # Substitute only the model/aligner process output. The current tool
            # still stages source, seeds each child, supervises it and reads back results.
            worker = source / 'owsm_ctc_forced_alignment.py'
            (source/'audit_candidate_helper.py').write_text("REVISION = 'known-boundary'\n", encoding='utf-8')
            worker.write_text('''import argparse,hashlib,json
from pathlib import Path
from stemwise_engine.audit_candidate_helper import REVISION
p=argparse.ArgumentParser()
for name in ('request','request-sha256','output','timeout-seconds'):p.add_argument('--'+name,required=True)
a=p.parse_args();raw=Path(a.request).read_bytes();request=json.loads(raw)
assert hashlib.sha256(raw).hexdigest()==a.request_sha256
result={'request':{'sha256':a.request_sha256,'byteCount':len(raw)},
 'audio':{'sha256':request['audio']['sha256']},'lineCount':len(request['lines']),
 'lines':[{'startMs':100,'endMs':900}],'model':{'revision':REVISION}}
Path(a.output).write_text(json.dumps(result),encoding='utf-8')
''', encoding='utf-8')
            request = root / 'request.json'
            request.write_text(json.dumps({'audio': {'sha256': 'a'*64}, 'durationMs': 1000,
                'lines': [{'text': 'exact known words'}]}), encoding='utf-8')
            reference = root / 'reference.json'
            expected = {'request': {'sha256': hashlib.sha256(request.read_bytes()).hexdigest(),
                'byteCount': request.stat().st_size}, 'audio': {'sha256': 'a'*64}, 'lineCount': 1,
                'lines': [{'startMs': 100, 'endMs': 900}], 'model': {'revision': 'known-boundary'}}
            reference.write_text(json.dumps(expected), encoding='utf-8')
            args = ['benchmark', '--request', str(request), '--reference-result', str(reference),
                '--source', str(source), '--rounds', '2', '--jobs-per-round', '2', '--timeout', '30']
            output = io.StringIO()
            with patch.object(benchmark, 'PROJECT', project), patch.object(benchmark, 'ROOT', root), \
                    patch.object(sys, 'argv', args), redirect_stdout(output):
                benchmark.main()
            rows = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertEqual(len(rows), 3)
            self.assertEqual([row['jobs'] for row in rows[:2]], [2, 2])
            self.assertTrue(rows[-1]['boundReferenceCompared'])
            self.assertTrue(rows[-1]['identicalControlledResults'])
            self.assertEqual(rows[-1]['workerSha256'], hashlib.sha256(worker.read_bytes()).hexdigest())
            self.assertFalse((root / 'Test suite/work/endpoint-scheduling-current').exists())
            expected['lines'][0]['endMs'] = 901
            reference.write_text(json.dumps(expected), encoding='utf-8')
            args[args.index('--rounds') + 1] = '1'
            with patch.object(benchmark, 'PROJECT', project), patch.object(benchmark, 'ROOT', root), \
                    patch.object(sys, 'argv', args), redirect_stdout(io.StringIO()), \
                    self.assertRaisesRegex(ValueError, 'differs from the bound reference'):
                benchmark.main()

    def test_benchmark_excludes_only_container_hash_after_exact_pcm_comparison(self):
        path=ROOT/'Test suite/pc/performance/benchmark_owsm_scheduling.py'
        spec=importlib.util.spec_from_file_location('endpoint_benchmark',path)
        benchmark=importlib.util.module_from_spec(spec);spec.loader.exec_module(benchmark)
        first={'preprocessing':{'sha256':'a'*64,'sampleRate':16000,'frameCount':32000},
               'lines':[{'startMs':100,'endMs':900}],'model':{'sha256':'c'*64}}
        second=copy.deepcopy(first);second['preprocessing']['sha256']='b'*64
        pcm={'sha256':'d'*64,'sampleRate':16000,'frameCount':32000,'channels':1}
        compared=benchmark.numerical_results([first,second],[pcm,pcm])
        self.assertEqual(compared[0],compared[1])
        self.assertEqual(first['preprocessing']['sha256'],'a'*64)
        changed=copy.deepcopy(second);changed['lines'][0]['endMs']=901
        compared=benchmark.numerical_results([first,changed],[pcm,pcm])
        self.assertNotEqual(compared[0],compared[1])
        with self.assertRaisesRegex(ValueError,'sample data differs'):
            benchmark.numerical_results([first,second],[pcm,{**pcm,'sha256':'e'*64}])

    def test_nfa_cache_requires_exact_current_identity(self):
        name='nemo_nfa_forced_alignment.py'
        previous={name:'20296ea0bfbd776950678ef6188a99086dc08164e3ee7382dd1d099b6951445c'}
        current={name:alignment._sha256_file(Path(nfa.__file__))}
        self.assertFalse(alignment._ctc_cache_helpers_match(previous,current))
        self.assertTrue(alignment._ctc_cache_helpers_match(current,current))
        self.assertFalse(alignment._ctc_cache_helpers_match({name:'0'*64},current))
        with patch.object(alignment,'_sha256_file',return_value='1'*64):
            self.assertFalse(alignment._ctc_cache_helpers_match(previous,{name:'1'*64}))

    def test_model_failures_abort_without_publication_or_a_surviving_lease(self):
        for phase in ('initialization', 'emission', 'retirement'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory(
                    dir=ROOT/'Test suite/work') as directory:
                root = Path(directory)
                source = "import sys\nfrom pathlib import Path\nfrom types import SimpleNamespace as NS\nimport stemwise_engine.nemo_nfa_forced_alignment as worker\nworker.__file__=str(Path(sys.argv[1])/'src/worker.py')\nphase=sys.argv[2]\ndef fail(*args,**kwargs):raise RuntimeError('injected model failure')\nmodel=NS(to=lambda device:None)\nmodule=NS(AlignmentConfig=object,setup_model=fail if phase=='initialization' else lambda:(model,None),\n get_batch_variables=fail if phase=='emission' else lambda:(),viterbi_decoding=lambda:None)\ndef main(cfg):module.setup_model();module.get_batch_variables()\nmodule.main=main\nworker._load_nfa_module=lambda _:module\nworker.retire_model=fail if phase=='retirement' else lambda state:state.clear()\nsys.modules['torch']=NS(Tensor=type('Tensor',(),{}))\nsys.modules['omegaconf']=NS(OmegaConf=NS(structured=lambda _: {},from_dotlist=lambda _: {},merge=lambda *args:{}))\nworker._execute_nfa(['python',str(Path(sys.argv[1])/'align.py')],Path(sys.argv[1]),{})\n(Path(sys.argv[1])/'false-result.json').write_text('{}')\n"
                environment = {**os.environ, 'PYTHONPATH': str(Path(nfa.__file__).parent)
                    + os.pathsep + str(Path(common.__file__).parent)}
                result = common.run_process_tree([sys.executable,'-B','-c',source,str(root),phase],
                    timeout=8,capture_output=True,text=True,env=environment)
                self.assertEqual(result.returncode,70,result.stderr)
                self.assertIn(f'nfa-model-{phase}-failed',result.stderr)
                self.assertFalse((root/'false-result.json').exists())
                with common.heavy_worker_stage({'projectDir':str(root)},wait_seconds=0):
                    pass

    def test_native_startup_has_a_kernel_deadline(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root=Path(directory)
            source="import sys,time\nfrom pathlib import Path\nimport stemwise_engine.nemo_nfa_forced_alignment as worker\nworker._load_nfa_module=lambda _:time.sleep(10)\nsys.modules['torch']=object()\nprint('entered-startup',flush=True)\nwith worker.execution_deadline(.1):\n worker._execute_nfa(['python',str(Path(sys.argv[1])/'align.py')],Path(sys.argv[1]),{})\n(Path(sys.argv[1])/'false-result.json').write_text('{}')\n"
            result=common.run_process_tree([sys.executable,'-B','-c',source,str(root)],timeout=8,
                capture_output=True,text=True,env={**os.environ,'PYTHONPATH':str(Path(nfa.__file__).parent)
                    +os.pathsep+str(Path(common.__file__).parent)})
            self.assertIn('entered-startup',result.stdout)
            self.assertEqual(result.returncode, -signal.SIGALRM, result.stderr)
            self.assertFalse((root/'false-result.json').exists())

    def test_native_runtime_retires_gpu_before_cpu_decoding_and_restores_threads(self):
        for fail_decode in (False, True):
            with self.subTest(fail_decode=fail_decode), tempfile.TemporaryDirectory(
                    dir=ROOT / 'Test suite/work') as directory:
                project = Path(directory)
                heavy = common.heavy_worker_lock_path({'projectDir': str(project)})
                song = project / 'song.lock'
                events = []
                threads = [16]
                class Tensor:
                    def cpu(self):
                        self.device = 'cpu'
                        return self
                class Model:
                    def to(self, device):
                        self.device = device
                model = Model()
                fake_torch = SimpleNamespace(Tensor=Tensor, get_num_threads=lambda: threads[0],
                    set_num_threads=lambda value: threads.__setitem__(0, value))
                def peer(available):
                    environment = {**os.environ, 'PYTHONPATH': str(Path(common.__file__).parent)}
                    environment.pop(common._PROCESS_LOCK_LEASES_ENV, None)
                    source = ('from stemwise_engine.common import heavy_worker_stage,ProcessLockBusyError\n'
                        'try:\n'
                        f' with heavy_worker_stage({dict(projectDir=str(project))!r},wait_seconds=0): print("free")\n'
                        'except ProcessLockBusyError: print("busy")\n')
                    result = subprocess.run([sys.executable, '-B', '-c', source], timeout=5,
                        capture_output=True, text=True, env=environment, check=True)
                    self.assertEqual(result.stdout.strip(), 'free' if available else 'busy')
                def setup(*args):
                    self.assertIsNotNone(common.process_lock_descriptor(heavy))
                    self.assertIsNotNone(common.process_lock_descriptor(song))
                    peer(False)
                    events.append('model')
                    return model, None
                def emit(*args, **kwargs):
                    self.assertIsNotNone(common.process_lock_descriptor(heavy))
                    events.append('infer')
                    return Tensor(), Tensor(), Tensor(), Tensor(), [], .08
                def retire(state):
                    self.assertIsNotNone(common.process_lock_descriptor(heavy))
                    self.assertEqual(state['model'].device, 'cpu')
                    state.clear()
                    events.append('retire')
                def decode(*args):
                    self.assertIsNone(common.process_lock_descriptor(heavy))
                    self.assertTrue(all(t.device == 'cpu' for t in args[:4]))
                    self.assertEqual(threads[0], 1)
                    peer(True)
                    events.append('decode')
                    if fail_decode:
                        raise ValueError('injected CPU failure')
                    return ['actual-path']
                module = SimpleNamespace(setup_model=setup, get_batch_variables=emit,
                    viterbi_decoding=decode, AlignmentConfig=object)
                def main(_cfg):
                    module.setup_model()
                    values = module.get_batch_variables()
                    self.assertEqual(module.viterbi_decoding(*values[:4]), ['actual-path'])
                module.main = main
                def load(_):
                    self.assertIsNone(common.process_lock_descriptor(heavy))
                    peer(True)
                    events.append('imports')
                    return module
                omega = SimpleNamespace(OmegaConf=SimpleNamespace(structured=lambda _: {},
                    from_dotlist=lambda _: {}, merge=lambda *args: {}))
                before = Path.cwd(), sys.argv[:], sys.path[:], os.environ.get('NFA_TEST_ENV')
                with common.exclusive_process_lock(song), \
                        patch.object(nfa, '__file__', str(project/'src/worker.py')), \
                        patch.object(nfa, '_load_nfa_module', side_effect=load), \
                        patch.object(nfa, 'retire_model', side_effect=retire), \
                        patch.dict(sys.modules, {'torch': fake_torch, 'omegaconf': omega}):
                    call = lambda: nfa._execute_nfa(['python',str(project/'align.py')],
                        project, {'NFA_TEST_ENV': 'changed'})
                    if fail_decode:
                        with self.assertRaisesRegex(ValueError, 'injected CPU'):
                            call()
                    else:
                        call()
                self.assertEqual(events, ['imports','model','infer','retire','decode'])
                self.assertEqual(threads[0], 16)
                self.assertEqual((Path.cwd(),sys.argv,sys.path,os.environ.get('NFA_TEST_ENV')), before)
                self.assertIsNone(common.process_lock_descriptor(heavy))

    def test_parent_does_not_reserve_gpu_for_worker_preparation(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            project = root / 'PC side/builder'
            runtime = project.parent / 'runtime/nemo-speech-3.0.0'
            model = (project.parent / 'model-cache/lyrics-alignment'
                / 'models--nvidia--stt_ar_fastconformer_hybrid_large_pcd_v1.0'
                / 'snapshots' / nfa.MODEL_REVISION / nfa.MODEL_ARTIFACT)
            audio = root / 'audio.wav'
            for path in (runtime / '.venv/bin/python', project / 'src/nemo_nfa_forced_alignment.py',
                         model, runtime / 'tools/nemo_forced_aligner/align.py', runtime / 'uv.lock', audio):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture')
            (project / 'src/acoustic_inputs.py').write_bytes((ROOT / 'PC side/stemwise-playlist-builder/src/acoustic_inputs.py').read_bytes())
            job = root / 'job';job.mkdir()
            heavy = common.heavy_worker_lock_path({'projectDir': str(project)})
            song = root / 'song.lock'
            def execute(args, **kwargs):
                self.assertIsNone(common.process_lock_descriptor(heavy),
                    'The caller reserves the GPU during NeMo startup and input checks')
                self.assertIsNotNone(common.process_lock_descriptor(song))
                self.assertEqual(args[args.index('--timeout-seconds') + 1], '600')
                self.assertIsNone(kwargs['timeout'])
                self.assertEqual(kwargs['env']['TMPDIR'],str(root/'temporary'))
                Path(args[args.index('--output') + 1]).write_text('{}')
                return subprocess.CompletedProcess(args, 0, '', '')
            with common.exclusive_process_lock(song), \
                    patch.object(alignment, '_worker_cuda_device', return_value={}), \
                    patch.object(alignment, '_forced_alignment_request', return_value=({'lines': []}, [], [], {})), \
                    patch.object(alignment, '_closed_endpoint_witness_candidates', return_value=()), \
                    patch.object(alignment, 'run_process_tree', side_effect=execute):
                candidates, reused = alignment._load_or_run_nemo_nfa_alignment(
                    config={'projectDir': str(project)}, video_id='abcdefghijk', language='ar',
                    sources=[], duration_ms=1000, workspace=SimpleNamespace(job_dir=lambda _: job),
                    audio=audio, audio_identity={'sha256': 'a'*64, 'byteCount': 7},
                    temp_dir=root/'temporary', timeout_seconds=600,
                    occurrence_mapping={}, display_word_lines=[])
            self.assertEqual((candidates, reused), ((), False))


if __name__ == '__main__':
    unittest.main()
