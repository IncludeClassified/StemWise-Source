"""Exercise the actual Linux supervisor through the qualification adapter."""
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from contextlib import contextmanager
from unittest import mock

SUITE = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(SUITE / 'host'))
from owned_process import OwnedProcessError, run


@unittest.skipUnless(sys.platform == 'linux', 'Linux process containment')
class OwnedLinuxProcessTest(unittest.TestCase):
    def child(self, code, **options):
        return run([sys.executable, '-u', '-c', code], timeout=options.pop('timeout', 3), **options)

    def assert_retired(self, pid):
        self.assertFalse(Path(f'/proc/{pid}').exists(), f'Owned descendant {pid} survived retirement')

    def test_silent_success_and_separate_streams_and_nonzero_exit(self):
        self.assertEqual('', self.child('pass', check=True).stdout)
        result = self.child('import sys;print("result");print("warning",file=sys.stderr)')
        self.assertEqual('result\n', result.stdout)
        self.assertEqual('warning\n', result.stderr)
        with self.assertRaises(subprocess.CalledProcessError) as failure:
            self.child('import sys;print("partial");sys.exit(7)', check=True)
        self.assertEqual(7, failure.exception.returncode)
        self.assertEqual('partial\n', failure.exception.stdout)

    def test_verbose_output_fails_closed_at_capture_bound(self):
        with self.assertRaises(OwnedProcessError) as failure:
            self.child('import sys;sys.stdout.write("x" * (2 * 1024 * 1024))')
        self.assertIn('output limit', str(failure.exception))
        self.assertEqual(1024 * 1024, len(failure.exception.stdout))

    def test_logged_verbose_output_preserves_complete_streams_and_exit(self):
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
            log = Path(directory) / 'output.log'
            result = self.child('import sys;sys.stdout.write("OUT-START"+"x"*(2*1024*1024)+"OUT-END");'
                'sys.stderr.write("ERR-START"+"y"*(2*1024*1024)+"ERR-END");sys.exit(23)', output_log=log)
            self.assertEqual(23, result.returncode)
            self.assertEqual(1024 * 1024, len(result.stdout))
            self.assertEqual(1024 * 1024, len(result.stderr))
            self.assertTrue(result.stdout.endswith('OUT-END'))
            self.assertTrue(result.stderr.endswith('ERR-END'))
            self.assertGreater(log.stat().st_size, 4 * 1024 * 1024)
            self.assertIn('OUT-START', log.read_text())
            self.assertIn('ERR-START', log.read_text())

    def test_success_retires_detached_descendant_and_inherited_pipe(self):
        result = self.child('import subprocess,sys; p=subprocess.Popen([sys.executable,"-c",'
            '"import time;time.sleep(100)"],start_new_session=True);print(p.pid)', check=True)
        self.assert_retired(int(result.stdout))

    def test_total_startup_timeout_retires_registered_owner_without_touching_unrelated_child(self):
        from stemwise_engine.common import owned_process_tree
        registered = []
        @contextmanager
        def observe(*args, **kwargs):
            with owned_process_tree(*args, **kwargs) as child:
                registered.append(child.pid)
                yield child
        temporary = tempfile.TemporaryDirectory(dir=SUITE / 'work', prefix='owned-timeout-')
        self.addCleanup(temporary.cleanup)
        ready = Path(temporary.name) / 'emitted'
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(100)'])
        try:
            started = time.monotonic()
            with mock.patch('stemwise_engine.common.owned_process_tree', side_effect=observe), \
                    self.assertRaises(OwnedProcessError) as failure:
                self.child('import os,sys,time;from pathlib import Path;print(os.getpid(),flush=True);'
                    'print("warning",file=sys.stderr,flush=True);'
                    f'Path({str(ready)!r}).touch();time.sleep(100)',
                    timeout=.3)
            self.assertEqual('timeout', failure.exception.failure_kind)
            self.assertEqual(len(registered), 1)
            self.assert_retired(registered[0])
            # The total deadline includes guardian and interpreter startup. Emission is
            # authoritative only after the child has flushed both independent streams.
            if ready.exists():
                self.assertEqual('warning\n', failure.exception.stderr)
                self.assert_retired(int(failure.exception.stdout))
            self.assertIsNone(unrelated.poll())
            self.assertLess(time.monotonic() - started, 4)
        finally:
            unrelated.kill()
            unrelated.wait(timeout=3)

    def test_deadline_still_classifies_timeout_after_both_pipes_close(self):
        with self.assertRaises(OwnedProcessError) as failure:
            self.child('import os,time;os.close(1);os.close(2);time.sleep(100)', timeout=.3)
        self.assertEqual('timeout', failure.exception.failure_kind)

    def test_failed_spawn_never_reports_success(self):
        result = run(['/nonexistent-stemwise-owned-command'], timeout=3)
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(result.stderr)

    def test_interrupt_retires_real_child_before_propagating(self):
        with tempfile.TemporaryDirectory(dir=SUITE / 'work', prefix='owned-linux-') as temporary:
            marker = Path(temporary) / 'child.pid'
            stop = threading.Event()
            def interrupt_after_start():
                deadline = time.monotonic() + 3
                while not marker.is_file():
                    if time.monotonic() >= deadline or stop.wait(.01):
                        return
                os.kill(os.getpid(), signal.SIGINT)
            interrupter = threading.Thread(target=interrupt_after_start)
            interrupter.start()
            try:
                with self.assertRaises(KeyboardInterrupt):
                    self.child('import os,time,pathlib;pathlib.Path(' + repr(str(marker)) +
                        ').write_text(str(os.getpid()));time.sleep(100)', timeout=5)
            finally:
                stop.set()
                interrupter.join(timeout=4)
                self.assertFalse(interrupter.is_alive())
            self.assertTrue(marker.is_file(), 'Interrupt must occur after actual child startup')
            self.assert_retired(int(marker.read_text()))


if __name__ == '__main__':
    unittest.main()
