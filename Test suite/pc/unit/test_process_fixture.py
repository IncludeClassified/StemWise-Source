"""Real exact-identity rejection and bounded readiness on each native process boundary."""
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from process_fixture import process_birth, terminate_exact, read_line


class ProcessFixtureTest(unittest.TestCase):
    def test_changed_birth_never_signals_a_live_process(self):
        birth=process_birth(os.getpid())
        self.assertIsNotNone(birth)
        self.assertFalse(terminate_exact(os.getpid(),birth+1))
        self.assertEqual(birth,process_birth(os.getpid()))

    def test_never_ready_child_times_out_and_is_reaped(self):
        started=time.monotonic()
        child=subprocess.Popen([sys.executable,'-u','-c','import time;time.sleep(100)'],stdout=subprocess.PIPE,text=True)
        birth=process_birth(child.pid)
        try:
            with self.assertRaises(TimeoutError):
                read_line(child.stdout,timeout=.1)
        finally:
            terminate_exact(child.pid,birth)
            child.communicate(timeout=5)
        self.assertIsNotNone(child.returncode)
        self.assertLess(time.monotonic()-started,7)

    def test_partial_setup_failure_retires_the_captured_child(self):
        child=subprocess.Popen([sys.executable,'-u','-c','import time;print("ready",flush=True);time.sleep(100)'],stdout=subprocess.PIPE,text=True)
        birth=process_birth(child.pid)
        try:
            self.assertEqual('ready',read_line(child.stdout,timeout=5).strip())
            raise ValueError('isolated setup failure')
        except ValueError:
            pass
        finally:
            terminate_exact(child.pid,birth)
            child.communicate(timeout=5)
        self.assertIsNotNone(child.returncode)
