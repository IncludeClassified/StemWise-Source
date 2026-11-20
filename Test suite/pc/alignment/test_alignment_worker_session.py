"""Operation-scoped workers amortize imports while retaining owned process teardown."""
from __future__ import annotations
import contextlib
import importlib
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[3]
SRC=ROOT/'PC side/stemwise-playlist-builder/src'
sys.path.insert(0,str(SRC))
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout
import stemwise_engine.common as common
try: session=importlib.import_module('stemwise_engine.alignment_worker_session')
except ModuleNotFoundError: session=None

@unittest.skipUnless(sys.platform=='linux','WSL owned worker sessions use private inherited sockets')
class AlignmentWorkerSessionTests(unittest.TestCase):
    def test_temporary_owners_retire_idle_numerical_workers_before_removing_their_files(self):
        for manifested in (False, True):
            with self.subTest(manifested=manifested), self.scope():
                owner = (common.manifested_temporary_directory(self.base, prefix='owned-',
                    owner={'kind':'alignment-session-fixture'}) if manifested else
                    common.WorkerTemporaryDirectory(prefix='owned-', dir=self.base))
                with owner as name:
                    directory = Path(name)
                    (directory/'request.json').write_bytes(b'operation request')
                    result = self.run_worker('--leases')
                    self.assertEqual(result.returncode, 0, result.stderr)
                    worker = json.loads(result.stdout)
                    self.assertTrue(any(Path(path).parent == directory for path, _ in worker['leases']))
                    self.assertIsNotNone(common._linux_process_identity(worker['pid']))
                self.assert_process_dead(worker['pid'])
                self.assertFalse(directory.exists())
                following = json.loads(self.run_worker().stdout)
                self.assertNotEqual(following['pid'], worker['pid'])
                self.assertEqual(following['count'], 1)

    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work')
        self.addCleanup(self.temporary.cleanup)
        self.base=Path(self.temporary.name)/'builder'
        self.base.mkdir()
        prepare_runtime_layout(self.base)
        self.package=self.base/'stemwise_engine'; self.package.mkdir()
        for name in ('__init__.py','common.py','runtime_environment.py','alignment_worker_session.py',
                     'acoustic_inputs.py','ctc_worker.py','worker_protocol.py','worker_publication.py','private_diagnostics.py'):
            source=SRC/name
            if source.is_file():(self.package/name).write_bytes(source.read_bytes())
        source='''import json,os,sys,subprocess,time
import stemwise_engine.acoustic_inputs as acoustic_inputs
COUNT=0
def main():
 global COUNT
 COUNT+=1
 if '--mutate-env' in sys.argv:
  os.environ['SESSION_TEST_VALUE']='changed-by-import'
  os.environ['SESSION_PRIVATE_MUTATION']='added-by-import'
 if '--leases' in sys.argv:
  from stemwise_engine.common import _PROCESS_LOCKS
  print(json.dumps({'pid':os.getpid(),'count':COUNT,'leases':[[str(p),fd] for p,fd in _PROCESS_LOCKS.get()]}));return 0
 if '--typed-retirement' in sys.argv:
  from stemwise_engine.common import WorkerRetirementError
  raise WorkerRetirementError('specific retirement failure')
 if '--fail' in sys.argv: raise ValueError('specific numerical failure')
 if '--native70' in sys.argv:
  print('alignment-model-retirement-failed',file=sys.stderr,flush=True)
  os._exit(70)
 if '--native-diagnostics' in sys.argv:
  os.write(2,b'n'*200000);sys.stderr.write('FINAL_PRIVATE_CAUSE');sys.stderr.flush()
 if '--sleep' in sys.argv: time.sleep(30)
 if '--healthy-stages' in sys.argv:
  from stemwise_engine.ctc_worker import execution_deadline
  for _ in range(3):
   with execution_deadline(.2): time.sleep(.1)
 if '--hung-stage' in sys.argv:
  from stemwise_engine.ctc_worker import execution_deadline
  with execution_deadline(.1):
   import ctypes
   ctypes.CDLL(None).pause()
 if '--native-block' in sys.argv:
  import ctypes
  ctypes.CDLL(None).pause()
 if '--capability' in sys.argv:
  print(json.dumps({'supported':False}));return 3
 if '--descendant' in sys.argv:
  child=subprocess.Popen([sys.executable,'-c',"import os,signal,time; os.setsid(); signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(60)"])
  print(json.dumps({'pid':os.getpid(),'count':COUNT,'child':child.pid}))
 else: print(json.dumps({'pid':os.getpid(),'count':COUNT,'environment':os.environ.get('SESSION_TEST_VALUE'),'leaked':os.environ.get('SESSION_PRIVATE_MUTATION')}))
 return 0
if __name__=='__main__':
 from stemwise_engine.worker_protocol import run_worker_main
 raise SystemExit(run_worker_main(main))
'''
        self.helper=self.package/'mms_forced_alignment.py'; self.helper.write_text(source)
        (self.package/'wav2vec2_forced_alignment.py').write_text(source)
        self.module='stemwise_engine.mms_forced_alignment'
        import stemwise_engine
        package_path=patch.object(stemwise_engine, '__path__', [str(self.package)])
        package_path.start(); self.addCleanup(package_path.stop)
        names=('stemwise_engine.mms_forced_alignment', 'stemwise_engine.wav2vec2_forced_alignment')
        cached={name:sys.modules.pop(name) for name in names if name in sys.modules}
        def restore_modules():
            for name in names: sys.modules.pop(name, None)
            sys.modules.update(cached)
            importlib.invalidate_caches()
        self.addCleanup(restore_modules)
        importlib.invalidate_caches()

    def scope(self):
        return session.alignment_worker_session()

    def environment(self, environment=None):
        return {**(os.environ if environment is None else environment), 'PYTHONPATH': str(self.base)}

    def run_worker(self,*arguments,timeout=10,environment=None):
        runner=session.run_alignment_process
        return runner([sys.executable,'-m',self.module,'--batch','unused',*arguments],timeout=timeout,
                      capture_output=True,text=True,env=self.environment(environment))

    def test_repeated_requests_share_imports_only_until_operation_closes(self):
        with self.scope():
            first=self.run_worker(); second=self.run_worker()
            a,b=json.loads(first.stdout),json.loads(second.stdout)
            self.assertEqual(a['pid'],b['pid'],'fresh worker startup must be amortized inside one operation')
            self.assertEqual((a['count'],b['count']),(1,2))
        self.assert_process_dead(a['pid'])
        with self.scope():
            later=json.loads(self.run_worker().stdout)
        self.assertEqual(later['count'],1,'a later operation cannot reuse resident state')
        self.assertNotEqual(later['pid'],a['pid'])
        self.assert_process_dead(later['pid'])

    def test_unlimited_orchestration_preserves_each_stages_kernel_watchdog(self):
        with self.scope():
            healthy = self.run_worker('--healthy-stages', timeout=None)
            self.assertEqual(healthy.returncode, 0, healthy.stderr)
            identity = json.loads(healthy.stdout)
            hung = self.run_worker('--hung-stage', timeout=None)
            self.assertEqual(hung.returncode, -signal.SIGALRM)
        self.assert_process_dead(identity['pid'])

    def test_exception_preserves_diagnostics_and_next_request_can_continue(self):
        with self.scope():
            before=json.loads(self.run_worker().stdout)
            failure=self.run_worker('--fail')
            self.assertEqual(failure.returncode,1)
            self.assertIn('ValueError: specific numerical failure',failure.stderr)
            after=json.loads(self.run_worker().stdout)
            self.assertEqual(before['pid'],after['pid'])
            self.assertEqual(after['count'],3)
        self.assert_process_dead(before['pid'])

    def test_idle_worker_returns_unused_native_heap_without_restarting(self):
        self.helper.write_text('''import ctypes,json,os,sys
ANCHOR=None
def main():
 global ANCHOR
 if '--allocate' in sys.argv:
  libc=ctypes.CDLL(None)
  libc.malloc.argtypes=[ctypes.c_size_t];libc.malloc.restype=ctypes.c_void_p
  libc.free.argtypes=[ctypes.c_void_p]
  blocks=[libc.malloc(65536) for _ in range(1024)]
  assert all(blocks)
  for block in blocks:ctypes.memset(block,1,65536)
  ANCHOR=libc.malloc(65536)
  assert ANCHOR
  ctypes.memset(ANCHOR,1,65536)
  for block in blocks:libc.free(block)
 print(json.dumps({'pid':os.getpid()}));return 0
''')
        def resident(pid):
            fields=Path(f'/proc/{pid}/statm').read_text().split()
            return int(fields[1])*os.sysconf('SC_PAGE_SIZE')
        with self.scope():
            first=self.run_worker()
            self.assertEqual(first.returncode,0,first.stderr)
            pid=json.loads(first.stdout)['pid']
            baseline=resident(pid)
            allocation=self.run_worker('--allocate')
            self.assertEqual(allocation.returncode,0,allocation.stderr)
            self.assertEqual(json.loads(allocation.stdout)['pid'],pid)
            self.assertLess(resident(pid)-baseline,16*1024*1024,
                'an idle worker must return the freed 64 MiB heap before acknowledging its result')
            self.assertEqual(json.loads(self.run_worker().stdout)['pid'],pid)
        self.assert_process_dead(pid)

    def test_timeout_poisoning_retires_the_session_without_retry(self):
        import subprocess
        with self.scope():
            first=json.loads(self.run_worker().stdout)
            with self.assertRaises(subprocess.TimeoutExpired) as failure:self.run_worker('--native-block',timeout=.15)
            self.assertEqual(failure.exception.timeout,.15)
            self.assertEqual(failure.exception.cmd,[sys.executable,'-m',self.module,'--batch','unused','--native-block'])
            self.assert_process_dead(first['pid'])
            with self.assertRaisesRegex(RuntimeError,'poison|closed'):
                self.run_worker()

    def test_native_failure_is_distinct_and_cannot_be_reused(self):
        with self.scope():
            first=json.loads(self.run_worker().stdout)
            completed=self.run_worker('--native70')
            self.assertEqual(completed.returncode,70)
            self.assertIn('alignment-model-retirement-failed',completed.stderr)
            self.assert_process_dead(first['pid'])
            with self.assertRaisesRegex(RuntimeError,'poison|closed'):self.run_worker()

    def test_typed_retirement_failure_terminates_and_poisons_the_session(self):
        with self.scope():
            first=json.loads(self.run_worker().stdout)
            completed=self.run_worker('--typed-retirement')
            self.assertEqual(completed.returncode,70,completed.stderr)
            self.assertIn('WorkerRetirementError: specific retirement failure',completed.stderr)
            self.assertIn('STEMWISE_WORKER_FAILURE={"formatVersion":1,"code":"alignment-model-retirement-failed"}',completed.stderr)
            self.assert_process_dead(first['pid'])
            with self.assertRaisesRegex(RuntimeError,'poison|closed'):self.run_worker()

    def test_typed_retirement_one_shot_preserves_the_same_status_and_cause(self):
        command=[sys.executable,'-m',self.module,'--audio','unused','--typed-retirement']
        completed=session.run_alignment_process(command,timeout=10,capture_output=True,text=True,env=self.environment())
        self.assertEqual(completed.returncode,70,completed.stderr)
        self.assertIn('WorkerRetirementError: specific retirement failure',completed.stderr)
        self.assertIn('"code":"alignment-model-retirement-failed"',completed.stderr)

    def test_source_change_cannot_reuse_old_imports(self):
        with self.scope():
            first=json.loads(self.run_worker().stdout)
            self.helper.write_text(self.helper.read_text()+'\nCHANGED=True\n')
            with self.assertRaisesRegex(RuntimeError,'source|binding|identity'):self.run_worker()
        self.assert_process_dead(first['pid'])

    def test_imported_numerical_helper_change_retires_cached_module_before_next_probe(self):
        with self.scope():
            first=json.loads(self.run_worker().stdout)
            helper=self.package/'acoustic_inputs.py'
            helper.write_bytes(helper.read_bytes()+b'\nCHANGED_NUMERICS=True\n')
            with self.assertRaisesRegex(RuntimeError,'source|binding|identity'):
                session.run_alignment_process([sys.executable,'-m',self.module,'--runtime-info'],
                    timeout=10,capture_output=True,text=True,env=self.environment())
        self.assert_process_dead(first['pid'])

    def test_operation_exit_retires_escaped_descendants(self):
        with self.scope():
            result=json.loads(self.run_worker('--descendant').stdout)
        self.assert_process_dead(result['pid'])
        self.assert_process_dead(result['child'])

    def test_effective_environment_changes_never_reuse_the_old_process(self):
        with self.scope():
            a=json.loads(self.run_worker(environment={**os.environ,'SESSION_TEST_VALUE':'a'}).stdout)
            b=json.loads(self.run_worker(environment={**os.environ,'SESSION_TEST_VALUE':'b'}).stdout)
            self.assertEqual((a['environment'],b['environment']),('a','b'))
            self.assertNotEqual(a['pid'],b['pid'])
        self.assert_process_dead(a['pid']); self.assert_process_dead(b['pid'])

    def test_heavy_lease_uses_owned_one_shots_and_is_not_retained(self):
        lock=self.base/'stemwise-heavy-worker.lock'
        with self.scope():
            with common.exclusive_process_lock(lock):
                first=json.loads(self.run_worker().stdout)
                second=json.loads(self.run_worker().stdout)
                self.assertNotEqual(first['pid'],second['pid'])
                self.assert_process_dead(first['pid']); self.assert_process_dead(second['pid'])
            with common.exclusive_process_lock(lock): pass
            a=json.loads(self.run_worker().stdout)
            b=json.loads(self.run_worker().stdout)
            self.assertEqual(a['pid'],b['pid'])

    def test_per_job_lease_change_retires_the_old_server_before_reuse(self):
        with self.scope():
            with common.exclusive_process_lock(self.base/'workflow.lock'):
                a=json.loads(self.run_worker().stdout)
                b=json.loads(self.run_worker().stdout)
                self.assertEqual(a['pid'],b['pid'])
            c=json.loads(self.run_worker().stdout)
            self.assertNotEqual(c['pid'],a['pid'])
            self.assert_process_dead(a['pid'])
        self.assert_process_dead(c['pid'])

    def test_malformed_or_stale_reply_poisons_and_retires_before_admission(self):
        from unittest.mock import patch
        for field,value in (('requestSha256','0'*64),('requestId',99),('requestId',True),
                            ('formatVersion',True),('returncode',False),('stdout',None)):
            with self.subTest(field=field,value=value),self.scope() as operation:
                a=json.loads(self.run_worker().stdout)
                worker=next(iter(operation.workers.values()))
                exchange=worker.exchange
                def tamper(packet,**kwargs):
                    result=exchange(packet,**kwargs)
                    if packet.get('kind')=='execute':result[field]=value
                    return result
                with patch.object(worker,'exchange',side_effect=tamper):
                    with self.assertRaisesRegex(RuntimeError,'reply binding'):
                        self.run_worker()
                self.assert_process_dead(a['pid'])
                with self.assertRaisesRegex(RuntimeError,'poison|closed'):self.run_worker()

    def test_parent_death_retires_worker_and_escaped_descendant(self):
        import subprocess
        script=f"""import os,sys
sys.path.insert(0,{str(self.base)!r})
from stemwise_engine.alignment_worker_session import alignment_worker_session,run_alignment_process
with alignment_worker_session():
 result=run_alignment_process([sys.executable,'-m',{self.module!r},'--batch','unused','--descendant'],timeout=10,capture_output=True,text=True)
 sys.stdout.write(result.stdout);sys.stdout.flush()
 os._exit(0)
"""
        completed=subprocess.run([sys.executable,'-B','-c',script],capture_output=True,text=True,timeout=15,env=self.environment())
        self.assertEqual(completed.returncode,0,completed.stderr)
        result=json.loads(completed.stdout)
        deadline=time.monotonic()+7
        while time.monotonic()<deadline and any(Path(f'/proc/{pid}').exists() for pid in result.values() if pid!=result['count']):
            time.sleep(.02)
        self.assert_process_dead(result['pid']);self.assert_process_dead(result['child'])

    def test_guardian_retirement_failure_cannot_report_success(self):
        import subprocess
        source=self.package/'common.py'
        body=source.read_text().replace('def _retire_owned_descendants() -> None:',
             "def _retire_owned_descendants() -> None:\n    raise RuntimeError('injected guardian retirement failure')",1)
        source.write_text(body)
        script=f"""import sys
sys.path.insert(0,{str(self.base)!r})
from stemwise_engine.alignment_worker_session import alignment_worker_session,run_alignment_process
with alignment_worker_session():
 run_alignment_process([sys.executable,'-m',{self.module!r},'--batch','unused'],timeout=10,capture_output=True,text=True)
"""
        result=subprocess.run([sys.executable,'-B','-c',script],capture_output=True,text=True,timeout=15,env=self.environment())
        self.assertNotEqual(result.returncode,0)
        self.assertIn('WorkerRetirementError: Owned command workers could not be retired',result.stderr)

    def test_cli_capability_exit_and_check_semantics_are_preserved(self):
        import subprocess
        with self.scope():
            a=json.loads(self.run_worker().stdout)
            result=self.run_worker('--capability')
            self.assertEqual(result.returncode,3)
            self.assertEqual(json.loads(result.stdout),{'supported':False})
            with self.assertRaises(subprocess.CalledProcessError):
                session.run_alignment_process([sys.executable,'-m',self.module,'--batch','unused','--capability'],
                    timeout=10,capture_output=True,text=True,check=True,env=self.environment())
            b=json.loads(self.run_worker().stdout)
            self.assertEqual(a['pid'],b['pid'])
            self.assertEqual(b['count'],4)

    def test_oversized_private_request_is_rejected_and_the_worker_is_retired(self):
        with self.scope():
            result=json.loads(self.run_worker().stdout)
            with self.assertRaisesRegex(ValueError,'frame is too large'):
                self.run_worker('x'*(1024*1024))
            self.assert_process_dead(result['pid'])

    def test_both_numerical_families_share_imported_runtime_in_one_interpreter(self):
        with self.scope():
            a=json.loads(self.run_worker().stdout)
            b=session.run_alignment_process([sys.executable,'-m','stemwise_engine.wav2vec2_forced_alignment','--batch','unused'],
                timeout=10,capture_output=True,text=True,env=self.environment())
            self.assertEqual(json.loads(b.stdout)['pid'],a['pid'])
        self.assert_process_dead(a['pid'])

    def test_distinct_runtime_pool_is_bounded_and_eviction_retires_first(self):
        with self.scope() as operation:
            outputs=[]
            for i in range(3):
                outputs.append(json.loads(self.run_worker(environment={**os.environ,'SESSION_TEST_VALUE':str(i)}).stdout))
                self.assertEqual(len(operation.workers),1)
                for prior in outputs[:-1]:self.assert_process_dead(prior['pid'])
            self.assertEqual(len({row['pid'] for row in outputs}),3)
        for row in outputs:self.assert_process_dead(row['pid'])

    def test_large_native_diagnostics_do_not_block_and_keep_bounded_failure_tail(self):
        with self.scope():
            result=self.run_worker('--native-diagnostics')
            self.assertEqual(result.returncode,0)
            self.assertEqual(json.loads(result.stdout)['count'],1)
            self.assertLessEqual(len(result.stderr.encode()),64*1024)
            self.assertTrue(result.stderr.endswith('FINAL_PRIVATE_CAUSE'))

    def test_switch_to_one_shot_heavy_path_also_retires_an_obsolete_job_lease(self):
        with self.scope():
            with common.exclusive_process_lock(self.base/'workflow.lock'):
                first=json.loads(self.run_worker().stdout)
            with common.exclusive_process_lock(self.base/'stemwise-heavy-worker.lock'):
                result=self.run_worker()
                self.assertEqual(result.returncode,0)
                self.assert_process_dead(first['pid'])
                self.assert_process_dead(json.loads(result.stdout)['pid'])

    def test_nested_inherited_job_leases_remain_authoritative_with_reused_low_fd(self):
        script=f"""import json,os,sys
from pathlib import Path
sys.path.insert(0,{str(self.base)!r})
import stemwise_engine.common as common
from stemwise_engine.alignment_worker_session import alignment_worker_session,run_alignment_process
base=Path({str(self.base)!r})
reserve=os.open(base/'reserved',os.O_CREAT|os.O_RDWR,0o600)
with common.exclusive_process_lock(base/'nested-first.lock'):
 os.close(reserve)
 with common.exclusive_process_lock(base/'nested-second.lock'):
  acquisition=[fd for path,fd in common._PROCESS_LOCKS.get()]
  assert acquisition != sorted(acquisition),acquisition
  with alignment_worker_session():
   first=run_alignment_process([sys.executable,'-m',{self.module!r},'--batch','unused','--leases'],timeout=10,capture_output=True,text=True)
   second=run_alignment_process([sys.executable,'-m',{self.module!r},'--batch','unused','--leases'],timeout=10,capture_output=True,text=True)
   assert first.returncode==0 and second.returncode==0,(first.stderr,second.stderr)
   print(json.dumps({{'first':json.loads(first.stdout),'second':json.loads(second.stdout),'acquisition':acquisition}}))
"""
        with common.exclusive_process_lock(self.base/'outer-job.lock'):
            result=common.run_process_tree([sys.executable,'-B','-c',script],timeout=15,capture_output=True,text=True,env=self.environment())
            self.assertEqual(result.returncode,0,result.stderr)
            rows=json.loads(result.stdout)
            self.assertEqual(rows['first']['pid'],rows['second']['pid'])
            self.assertEqual((rows['first']['count'],rows['second']['count']),(1,2))
            leases=rows['first']['leases']
            self.assertEqual({Path(path).name for path,fd in leases},
                {'outer-job.lock','nested-first.lock','nested-second.lock'})
            self.assertEqual([fd for path,fd in leases],sorted(rows['acquisition']))
            self.assert_process_dead(rows['first']['pid'])
            for name in ('nested-first.lock','nested-second.lock'):
                with common.exclusive_process_lock(self.base/name):pass

    def test_explicit_reserved_lease_environment_is_rejected_without_launch(self):
        stale={**os.environ,'STEMWISE_PROCESS_LOCK_LEASES':'[["/not-an-owned-lease",999999]]'}
        for held in (False,True):
            with self.subTest(held=held),self.scope() as operation:
                lease=common.exclusive_process_lock(self.base/'workflow.lock') if held else contextlib.nullcontext()
                with lease:
                    with self.assertRaisesRegex(ValueError,'reserved.*lease|lease.*environment'):
                        self.run_worker(environment=stale)
                    self.assertEqual(operation.workers,{})

    def test_single_and_prepare_entry_points_remain_owned_one_shots(self):
        for flag in ('--audio','--prepare-batch'):
            with self.subTest(flag=flag),self.scope():
                command=[sys.executable,'-m',self.module,flag,'unused']
                a=session.run_alignment_process(command,timeout=10,capture_output=True,text=True,env=self.environment())
                b=session.run_alignment_process(command,timeout=10,capture_output=True,text=True,env=self.environment())
                first,second=json.loads(a.stdout),json.loads(b.stdout)
                self.assertNotEqual(first['pid'],second['pid'],'only batch/probe entry points can retain imported runtime')
                self.assertEqual((first['count'],second['count']),(1,1))
                self.assert_process_dead(first['pid']);self.assert_process_dead(second['pid'])

    def test_other_runtime_starts_only_after_idle_workers_retire(self):
        with self.scope() as operation:
            first=json.loads(self.run_worker().stdout)
            script=("from pathlib import Path; "
                    f"assert not Path('/proc/{first['pid']}').exists(), 'idle numerical runtime overlaps next model'; "
                    "print('next-runtime-started')")
            result=common.run_process_tree([sys.executable,'-c',script],
                timeout=10,capture_output=True,text=True,env=self.environment())
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(result.stdout.strip(),'next-runtime-started')
            self.assertEqual(operation.workers,{})
            later=json.loads(self.run_worker().stdout)
            self.assertEqual(later['count'],1)
        self.assert_process_dead(first['pid']);self.assert_process_dead(later['pid'])

    def test_parent_model_lease_retires_idle_workers_before_admission(self):
        with self.scope() as operation:
            first=json.loads(self.run_worker().stdout)
            with common.heavy_worker_stage({'projectDir':str(self.base)},wait_seconds=0):
                self.assert_process_dead(first['pid'])
                self.assertEqual(operation.workers,{})
            later=json.loads(self.run_worker().stdout)
            self.assertNotEqual(first['pid'],later['pid'])
        self.assert_process_dead(later['pid'])

    def test_retirement_failure_does_not_start_another_runtime_or_take_its_lease(self):
        from unittest.mock import patch
        for launch in ('process','model'):
            with self.subTest(launch=launch),self.scope() as operation:
                first=json.loads(self.run_worker().stdout)
                worker=next(iter(operation.workers.values()))
                marker=self.base/'not-started'
                with patch.object(worker,'exchange',return_value={'malformed':'close acknowledgement'}):
                    with self.assertRaisesRegex(RuntimeError,'shutdown acknowledgement'):
                        if launch=='process':
                            common.run_process_tree([sys.executable,'-c',
                                f"from pathlib import Path; Path({str(marker)!r}).write_text('started')"],timeout=10)
                        else:
                            with common.heavy_worker_stage({'projectDir':str(self.base)},wait_seconds=0):
                                self.fail('model lease admitted despite failed worker retirement')
                self.assertFalse(marker.exists())
                self.assert_process_dead(first['pid'])
                self.assertEqual(operation.workers,{})
                with self.assertRaisesRegex(RuntimeError,'poisoned'):
                    common.run_process_tree([sys.executable,'-c',
                        f"from pathlib import Path; Path({str(marker)!r}).write_text('started')"],timeout=10)
                self.assertFalse(marker.exists())
                with common.exclusive_process_lock(common.heavy_worker_lock_path({'projectDir':str(self.base)}),wait_seconds=0):
                    pass

    def test_runtime_import_environment_mutations_are_discarded_between_requests(self):
        environment={**os.environ,'SESSION_TEST_VALUE':'approved'}
        with self.scope():
            first=self.run_worker('--mutate-env',environment=environment)
            self.assertEqual(first.returncode,0,first.stderr)
            second=self.run_worker(environment=environment)
            self.assertEqual(second.returncode,0,second.stderr)
            a,b=json.loads(first.stdout),json.loads(second.stdout)
            self.assertEqual(a['pid'],b['pid'])
            self.assertEqual((a['count'],b['count']),(1,2))
            self.assertEqual(b['environment'],'approved')
            self.assertIsNone(b['leaked'])

    def assert_process_dead(self,pid):
        path=Path(f'/proc/{pid}/stat')
        self.assertFalse(path.exists(),f'owned process {pid} survived operation retirement')

if __name__=='__main__':unittest.main()
