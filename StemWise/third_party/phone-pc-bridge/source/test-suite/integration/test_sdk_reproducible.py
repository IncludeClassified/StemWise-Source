"""Exercise SDK staging ownership with a real harmless POSIX process tree."""
import importlib.util
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


PROJECT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("sdk_reproducible", Path(__file__).with_name("check_sdk_reproducible.py"))
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)

WORKER = r'''
import os, signal, subprocess, sys, time
from pathlib import Path
root, role = Path(sys.argv[1]), sys.argv[2]
(root / (role + ".pid")).write_text(str(os.getpid()))
if role == "grandchild":
    with (root / "held-output").open("wb") as output:
        output.write(b"owned output"); output.flush()
        (root / "ready").touch()
        while True: time.sleep(.02)
else:
    stopped = False
    def stop(*_):
        global stopped
        stopped = True
    signal.signal(signal.SIGTERM, stop)
    child = subprocess.Popen([sys.executable, __file__, str(root), "child" if role == "parent" else "grandchild"])
    if role == "parent" and len(sys.argv) > 3:
        while not (root / "ready").is_file(): time.sleep(.01)
        (root / "bridge-core/phone-pc-bridge.aar").write_bytes(b"fixture artifact")
        sys.exit(0)
    while not stopped: time.sleep(.02)
    child.wait(timeout=5)
'''


@unittest.skipIf(os.name == "nt", "POSIX session retirement is exercised on a POSIX host")
class SdkReproducibleRetirementCheck(unittest.TestCase):
    def exercise_build(self, retirement_failure=False, normal_parent_exit=False):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        fixture = Path(tempfile.mkdtemp(prefix="sdk-retirement-fixture-", dir=work))
        launched, pids, stages = [], [], []
        try:
            worker = Path(fixture) / "worker.py"
            worker.write_text(WORKER, encoding="utf-8")
            original_popen = subprocess.Popen

            def launch(command, **options):
                root = Path(command[command.index("-File") + 1]).parent.parent
                stages.append(root.parent)
                self.assertTrue(options.get("start_new_session"), "Fixture must be launched into an owned session")
                worker_command = [sys.executable, str(worker), str(root), "parent"]
                if normal_parent_exit:
                    worker_command.append("exit-after-ready")
                build = original_popen(worker_command, **options)
                launched.append(build)
                original_wait = build.wait

                def wait(timeout=None):
                    if timeout == 900:
                        deadline = time.monotonic() + 10
                        while not (root / "ready").is_file():
                            if build.poll() is not None or time.monotonic() >= deadline:
                                self.fail("Harmless grandchild did not reach its output-handle barrier")
                            time.sleep(.01)
                        pids.extend(int((root / (role + ".pid")).read_text()) for role in ["parent", "child", "grandchild"])
                        return original_wait(timeout=10 if normal_parent_exit else .05)
                    return original_wait(timeout=timeout)

                build.wait = wait
                return build

            arguments = ["check_sdk_reproducible.py"]
            for name in ["go", "gomobile", "sdk", "ndk", "jdk"]:
                arguments += ["--" + name, str(fixture)]
            with mock.patch.object(sys, "argv", arguments), \
                 mock.patch.object(CHECK.runpy, "run_path", return_value={"source_paths": lambda *args, **kwargs: ["bridge-core/sdk-build-pins.json"]}), \
                 mock.patch.object(CHECK.subprocess, "Popen", side_effect=launch):
                if retirement_failure:
                    with mock.patch.object(CHECK, "retire_build", side_effect=CHECK.BuildRetirementError("fixture retirement unconfirmed")):
                        with self.assertRaisesRegex(CHECK.BuildRetirementError, "staging retained at") as failure:
                            CHECK.main()
                    self.assertIn(str(stages[0]), str(failure.exception))
                    self.assertTrue((stages[0] / "first/held-output").is_file())
                    os.kill(pids[-1], 0)
                else:
                    if normal_parent_exit:
                        with mock.patch("builtins.print"):
                            CHECK.main()
                    else:
                        with self.assertRaises(subprocess.TimeoutExpired):
                            CHECK.main()
                    for pid in pids:
                        with self.assertRaises(ProcessLookupError, msg="SDK descendant still lives after staging cleanup"):
                            os.kill(pid, 0)
                    self.assertFalse(stages[0].exists())
        finally:
            retirement_failure = None
            for build in launched:
                try:
                    CHECK.retire_build(build, timeout=3)
                except BaseException as failure:
                    retirement_failure = failure
            if retirement_failure:
                raise RuntimeError("Fixture process retirement unconfirmed; retained " + str(fixture) + " and " + str(stages)) from retirement_failure
            for stage in set(stages):
                if stage.exists():
                    shutil.rmtree(stage)
            shutil.rmtree(fixture)

    def test_timeout_retires_parent_and_grandchild_before_deleting_staging(self):
        self.exercise_build()

    def test_unconfirmed_retirement_preserves_owned_output_and_staging(self):
        self.exercise_build(retirement_failure=True)

    def test_parent_success_still_retires_lingering_descendants_before_cleanup(self):
        self.exercise_build(normal_parent_exit=True)


if __name__ == "__main__":
    unittest.main()
