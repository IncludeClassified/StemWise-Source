"""Stage ownership excludes CPU decoding without abandoning song/process ownership."""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[3] / "PC side/stemwise-playlist-builder"
import stemwise_engine.common as common


def bounded_ready_line(process, timeout):
    import select
    import os
    deadline = time.monotonic()+timeout
    line = b''
    while b'\n' not in line:
        remaining = deadline-time.monotonic()
        if remaining <= 0 or not select.select([process.stdout], [], [], remaining)[0]:
            raise TimeoutError('holder did not become ready before watchdog')
        block = os.read(process.stdout.fileno(), 4096)
        if not block:
            raise RuntimeError('holder exited before readiness')
        line += block
        if len(line) > 4096:
            raise ValueError('holder readiness exceeded bound')
    return line.split(b'\n', 1)[0].decode('utf-8')


@unittest.skipUnless(sys.platform == "linux", "kernel leases and owned workers run in WSL")
class GpuStageSchedulingTests(unittest.TestCase):
    def _environment(self):
        environment = {**os.environ, "PYTHONPATH": str(Path(common.__file__).parent)}
        environment.pop(common._PROCESS_LOCK_LEASES_ENV, None)
        return environment

    def _run(self, source, *, timeout=8):
        return common.run_process_tree(
            [sys.executable, "-B", "-c", source], timeout=timeout,
            capture_output=True, text=True, check=True,
            env=self._environment(),
        )

    def test_nested_and_inherited_stage_preserves_the_exact_kernel_lease(self):
        with tempfile.TemporaryDirectory(dir=PROJECT.parent.parent / "Test suite/work") as directory:
            config = {"projectDir": directory}
            lock = common.heavy_worker_lock_path(config)
            with common.heavy_worker_stage(config):
                descriptor = common.process_lock_descriptor(lock)
                self.assertIsNotNone(descriptor)
                with common.heavy_worker_stage(config, wait_seconds=0):
                    self.assertEqual(common.process_lock_descriptor(lock), descriptor)
                self.assertEqual(common.process_lock_descriptor(lock), descriptor)
                child = self._run(
                    "from pathlib import Path\n"
                    "from stemwise_engine.common import heavy_worker_stage, process_lock_descriptor\n"
                    f"config = {config!r}\n"
                    f"lock = Path({str(lock)!r})\n"
                    "before = process_lock_descriptor(lock)\n"
                    "assert before is not None\n"
                    "with heavy_worker_stage(config, wait_seconds=0):\n"
                    " assert process_lock_descriptor(lock) == before\n"
                    "assert process_lock_descriptor(lock) == before\n"
                    "print('inherited-exact')\n"
                )
                self.assertEqual(child.stdout.strip(), "inherited-exact")
                with self.assertRaises(common.ProcessLockBusyError):
                    with common.exclusive_process_lock(lock, wait_seconds=0):
                        self.fail("A nested/inherited stage cannot release its outer owner")
            self.assertIsNone(common.process_lock_descriptor(lock))
            with common.exclusive_process_lock(lock, wait_seconds=0):
                pass
            unrelated = Path(directory) / "unrelated.lock"
            unrelated.touch()
            with unrelated.open("r+") as handle:
                token = common._PROCESS_LOCKS.set(((lock.resolve(), handle.fileno()),))
                try:
                    with self.assertRaisesRegex(ValueError, "differs from the heavy lock"):
                        with common.heavy_worker_stage(config):
                            self.fail("A matching context label cannot establish a kernel lease")
                finally:
                    common._PROCESS_LOCKS.reset(token)

    def test_cpu_decoder_overlaps_next_model_only_after_owned_model_retirement(self):
        with tempfile.TemporaryDirectory(dir=PROJECT.parent.parent / "Test suite/work") as directory:
            root = Path(directory)
            config = {"projectDir": directory}
            lock = common.heavy_worker_lock_path(config)
            song = root / "song.lock"
            escaped = root / "model.pid"
            ready, release = root / "cpu.ready", root / "cpu.release"
            grandchild = (
                "import os,time\nfrom pathlib import Path\n"
                f"Path({str(escaped)!r}).write_text(str(os.getpid()))\n"
                "while True: time.sleep(.02)\n"
            )
            model = (
                "import subprocess,sys,time\nfrom pathlib import Path\n"
                "from stemwise_engine.common import process_lock_descriptor\n"
                f"assert process_lock_descriptor(Path({str(lock)!r})) is not None\n"
                f"subprocess.Popen([sys.executable,'-B','-c',{grandchild!r}],start_new_session=True)\n"
                f"while not Path({str(escaped)!r}).exists(): time.sleep(.005)\n"
            )
            with common.exclusive_process_lock(song):
                with common.heavy_worker_stage(config):
                    self._run(model)
                self.assertFalse(Path(f"/proc/{escaped.read_text()}").exists(),
                                 "Model descendants must retire before another stage can own the GPU")
                self.assertIsNone(common.process_lock_descriptor(lock))
                contender_source = (
                    "from pathlib import Path\nimport time\n"
                    "from stemwise_engine.common import heavy_worker_stage, exclusive_process_lock, ProcessLockBusyError\n"
                    f"ready,release = Path({str(ready)!r}),Path({str(release)!r})\n"
                    "deadline=time.monotonic()+5\n"
                    "while not ready.exists():\n"
                    " assert time.monotonic()<deadline, 'CPU decoder did not become ready'\n"
                    " time.sleep(.005)\n"
                    f"with heavy_worker_stage({config!r}, wait_seconds=0):\n"
                    " try:\n"
                    f"  with exclusive_process_lock(Path({str(song)!r}),wait_seconds=0):\n"
                    "   raise AssertionError('CPU decoding lost exact song ownership')\n"
                    " except ProcessLockBusyError: pass\n"
                    " release.write_text('next-model-owned')\n"
                )
                contender = subprocess.Popen(
                    [sys.executable, "-B", "-c", contender_source],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                    env=self._environment(),
                )
                try:
                    decoder = self._run(
                        "from pathlib import Path\nimport time\n"
                        "from stemwise_engine.common import process_lock_descriptor\n"
                        f"assert process_lock_descriptor(Path({str(lock)!r})) is None\n"
                        f"assert process_lock_descriptor(Path({str(song)!r})) is not None\n"
                        f"Path({str(ready)!r}).write_text('cpu-active')\n"
                        "deadline=time.monotonic()+5\n"
                        f"while not Path({str(release)!r}).exists():\n"
                        " assert time.monotonic()<deadline,'GPU could not run during CPU decoding'\n"
                        " time.sleep(.005)\n"
                        f"print(Path({str(release)!r}).read_text())\n"
                    )
                    _, stderr = contender.communicate(timeout=3)
                    self.assertEqual(contender.returncode, 0, stderr)
                    self.assertEqual(decoder.stdout.strip(), "next-model-owned")
                finally:
                    if contender.poll() is None:
                        contender.kill()
                    contender.communicate(timeout=3)

    def test_queue_expiry_never_launches_model_and_stage_failure_releases_lease(self):
        with tempfile.TemporaryDirectory(dir=PROJECT.parent.parent / "Test suite/work") as directory:
            config = {"projectDir": directory}
            lock = common.heavy_worker_lock_path(config)
            marker = Path(directory) / "model.started"
            with common.heavy_worker_stage(config):
                waiting = subprocess.run(
                    [sys.executable, "-B", "-c",
                     "from pathlib import Path\nfrom stemwise_engine.common import heavy_worker_stage, ProcessLockBusyError\n"
                     "try:\n"
                     f" with heavy_worker_stage({config!r},wait_seconds=.03):\n"
                     f"  Path({str(marker)!r}).write_text('launched')\n"
                     "except ProcessLockBusyError: print('queued-expired')\n"],
                    timeout=3, capture_output=True, text=True, check=True, env=self._environment(),
                )
                self.assertEqual(waiting.stdout.strip(), "queued-expired")
                self.assertFalse(marker.exists())
            with self.assertRaises(subprocess.CalledProcessError):
                with common.heavy_worker_stage(config, wait_seconds=0):
                    self._run("raise SystemExit(7)")
            with common.heavy_worker_stage(config, wait_seconds=0):
                self.assertIsNotNone(common.process_lock_descriptor(lock))

    def test_holder_readiness_watchdog_covers_silence_and_partial_lines(self):
        for output in ("", "print('hold',end='',flush=True);"):
            process = subprocess.Popen([sys.executable, '-B', '-c',
                "import time;"+output+"time.sleep(20)"], stdout=subprocess.PIPE)
            started = time.monotonic()
            try:
                with self.assertRaises(TimeoutError):
                    bounded_ready_line(process, .1)
                self.assertLess(time.monotonic()-started, 1)
            finally:
                process.kill()
                process.communicate(timeout=3)

if __name__ == "__main__":
    unittest.main()
