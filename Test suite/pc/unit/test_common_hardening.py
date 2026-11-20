from __future__ import annotations

import os
import json
import subprocess
import signal
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import process_birth, terminate_exact

import stemwise_engine.common as common  # noqa: E402


class CommonPortableDurabilityTests(unittest.TestCase):
    def test_generator_version_is_derived_without_changing_owner_choices(self):
        from stemwise_engine.enrichment_constants import GENERATOR_VERSION
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            path = Path(name)/'config.json'
            value = {'playlistUrl':'https://example.invalid/playlist', 'outputDirWindows':'output',
                'generatorVersion':'stale-duplicate', 'keepFailedAudio':True, 'analysisTimeoutMinutes':91}
            for has_duplicate in (True, False):
                if not has_duplicate: value.pop('generatorVersion')
                path.write_text(json.dumps(value), encoding='utf-8')
                config = common.load_config(path)
                self.assertEqual(config['generatorVersion'], GENERATOR_VERSION)
                self.assertTrue(config['keepFailedAudio'])
                self.assertEqual(config['analysisTimeoutMinutes'], 91)

    def test_cleanup_authority_survives_final_directory_removal(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            owner = {'kind': 'scratch'}
            target = common.create_manifested_directory(root, root/'scratch', owner=owner)
            original = os.rmdir
            def interrupt(path, *args, **kwargs):
                original(path, *args, **kwargs)
                if Path(path) == target:
                    raise KeyboardInterrupt('process death after rmdir')
            with mock.patch.object(os, 'rmdir', side_effect=interrupt):
                with self.assertRaises(KeyboardInterrupt):
                    common.remove_manifested_directory(root, target, owner=owner)
            self.assertFalse(target.exists())
            self.assertTrue(common.manifested_cleanup_receipt(target).is_file())
            common.remove_manifested_directory(root, target, owner=owner)
            self.assertEqual(list(root.iterdir()), [])

    def test_cleanup_authority_survives_manifest_unlink_before_directory_removal(self):
        for nonempty in (False, True):
            with self.subTest(nonempty=nonempty), tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                root = Path(name)
                owner = {'kind': 'analysis', 'recordingKey': 'qobuz:11'}
                target = common.create_manifested_directory(root, root/'scratch', owner=owner)
                if nonempty:
                    (target/'pcm').write_bytes(b'disposable')
                original = os.unlink
                def interrupt(path, *args, **kwargs):
                    original(path, *args, **kwargs)
                    if Path(path).name == common.GENERATED_MANIFEST_NAME:
                        raise KeyboardInterrupt('process death after receipt unlink')
                with mock.patch.object(os, 'unlink', side_effect=interrupt):
                    with self.assertRaises(KeyboardInterrupt):
                        common.remove_manifested_directory(root, target, owner=owner)
                self.assertTrue(target.is_dir())
                self.assertFalse((target/common.GENERATED_MANIFEST_NAME).exists())
                with self.assertRaises(ValueError):
                    common.remove_manifested_directory(root, target, owner={'kind': 'wrong'})
                common.remove_manifested_directory(root, target, owner=owner)
                self.assertEqual(list(root.iterdir()), [])

    def test_manifest_failure_removes_only_the_new_empty_directory_and_retry_works(self):
        for unrelated_file in (False, True):
            with self.subTest(unrelated_file=unrelated_file), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                target = root / 'analysis'
                def fail(*args, **kwargs):
                    if unrelated_file:
                        (target / 'unrelated').write_bytes(b'preserve')
                    raise OSError('disk full')
                with mock.patch.object(common, 'atomic_write_json', side_effect=fail):
                    with self.assertRaisesRegex(OSError, 'disk full'):
                        common.create_manifested_directory(root, target, owner={'kind':'test'})
                if unrelated_file:
                    self.assertEqual((target / 'unrelated').read_bytes(), b'preserve')
                else:
                    self.assertFalse(target.exists())
                    common.create_manifested_directory(root, target, owner={'kind':'test'})
                    common.remove_manifested_directory(root, target, owner={'kind':'test'})

    def test_manifested_cleanup_removes_only_owned_files_without_a_holding_folder(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            holding = root / "Pending deletion"
            owner = {"kind": "test"}
            for content in (b"first", b"second"):
                source = common.create_manifested_directory(root, root / "scratch", owner=owner)
                (source / "result").write_bytes(content)
                with self.assertRaises(ValueError):
                    common.remove_manifested_directory(root, source, owner={"kind": "wrong"})
                self.assertEqual((source / 'result').read_bytes(), content)
                common.remove_manifested_directory(root, source, owner=owner)
                self.assertFalse(source.exists())
                self.assertEqual(list(root.iterdir()), [], 'cleanup must not relocate data into another folder')
            self.assertFalse(holding.exists())

    def test_blocked_cleanup_recycles_and_refuses_unconfirmed_removal(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            source = root / 'scratch'
            source.mkdir()
            (source / 'result').write_bytes(b'disposable')
            with mock.patch.object(common.shutil, 'rmtree', side_effect=PermissionError('locked')), mock.patch.object(
                    common, '_recycle_disposable_path', side_effect=lambda path: None):
                with self.assertRaises(OSError):
                    common.remove_disposable_path(root, source)
            self.assertTrue(source.exists(), 'a successful recycle return cannot hide an unchanged target')
            real_remove = common.shutil.rmtree
            with mock.patch.object(common.shutil, 'rmtree', side_effect=PermissionError('locked')), mock.patch.object(
                    common, '_recycle_disposable_path', side_effect=lambda path: real_remove(path)):
                common.remove_disposable_path(root, source)
            self.assertFalse(source.exists())
            with self.assertRaises(ValueError):
                common.remove_disposable_path(root, root)

    def test_durable_replace_accepts_a_platform_file_descriptor(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            staged = root / ".value.tmp"
            destination = root / "value"
            staged.write_bytes(b"new")

            common.durable_replace(staged, destination)

            self.assertEqual(destination.read_bytes(), b"new")


@unittest.skipUnless(os.name == "posix", "process groups and directory fsync are WSL/POSIX boundaries")
class CommonHardeningTests(unittest.TestCase):
    @unittest.skipUnless(sys.platform == 'linux', 'Linux subreaper boundary')
    def test_live_supervisor_reaps_exited_orphan_without_losing_primary_exit(self):
        work = PROJECT_ROOT.parent.parent / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name)
            marker, release = root/'owners.json', root/'release'
            script = '''import json,os,sys,time
from pathlib import Path
root=Path(sys.argv[1])
read_fd,write_fd=os.pipe()
middle=os.fork()
if middle == 0:
 os.close(read_fd)
 child=os.fork()
 if child == 0:
  os.close(write_fd)
  os._exit(0)
 os.write(write_fd,str(child).encode())
 os._exit(0)
os.close(write_fd)
child=int(os.read(read_fd,64));os.close(read_fd)
os.waitpid(middle,0)
(root/'owners.tmp').write_text(json.dumps({'primary':os.getpid(),'orphan':child}))
os.replace(root/'owners.tmp',root/'owners.json')
deadline=time.monotonic()+10
while not (root/'release').exists():
 if time.monotonic() >= deadline: sys.exit(97)
 time.sleep(.01)
sys.exit(23)
'''
            with common.owned_process_tree([sys.executable, '-B', '-c', script, str(root)],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
                deadline = time.monotonic()+4
                while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue(marker.exists(), 'primary must publish exact fork ownership')
                owners = json.loads(marker.read_bytes())
                orphan = Path(f"/proc/{owners['orphan']}")
                deadline = time.monotonic()+3
                while orphan.exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                reaped_while_primary_alive = not orphan.exists()
                self.assertIsNotNone(common._linux_process_identity(owners['primary']))
                self.assertIsNone(process.poll())
                release.touch()
                process.communicate(timeout=4)
                self.assertEqual(process.returncode, 23, 'orphan reaping must preserve primary Popen status')
                self.assertTrue(reaped_while_primary_alive, 'live guardian retained an exited orphan')

    def test_unestablished_worker_retirement_never_returns_partial_success(self):
        work = PROJECT_ROOT.parent.parent / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as directory:
            guardian = Path(directory) / 'guardian.py'
            source = Path(common.__file__).read_text()
            source = source.replace('def _retire_owned_descendants() -> None:\n',
                'def _retire_owned_descendants() -> None:\n    raise RuntimeError("retirement unavailable")\n', 1)
            guardian.write_text(source)
            with mock.patch.object(common, '__file__', str(guardian)):
                with self.assertRaisesRegex(RuntimeError, 'could not be retired'):
                    common.run_process_tree([sys.executable, '-c', 'print("partial-success")'],
                        timeout=5, text=True, capture_output=True, check=False)

    def test_guardian_startup_timeout_and_cancellation_never_lose_the_original_failure(self):
        work = PROJECT_ROOT.parent.parent / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as directory:
            root = Path(directory)
            guardian = root/'guardian.py'
            source = Path(common.__file__).read_text(encoding='utf-8')
            guardian.write_text(source.replace('from __future__ import annotations\n',
                'from __future__ import annotations\nimport time; time.sleep(100)\n', 1), encoding='utf-8')
            marker = root/'command-started'
            command = [sys.executable, '-B', '-c',
                f'from pathlib import Path;Path({str(marker)!r}).touch()']
            with mock.patch.object(common, '__file__', str(guardian)):
                for mode in ('timeout', 'cancelled'):
                    with self.subTest(mode=mode):
                        started = time.monotonic()
                        with self.assertRaises(subprocess.TimeoutExpired if mode == 'timeout' else InterruptedError):
                            common.run_process_tree(command, timeout=.04 if mode == 'timeout' else 5,
                                cancelled=None if mode == 'timeout' else lambda: time.monotonic()-started >= .04,
                                text=True, capture_output=True)
                        self.assertLess(time.monotonic()-started, 1)
                        self.assertFalse(marker.exists(), 'an unready guardian cannot launch the command')
                started = time.monotonic()
                with self.assertRaisesRegex(common.WorkerRetirementError, 'startup'):
                    with common.owned_process_tree(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE):
                        self.fail('an unready guardian cannot expose a runnable command owner')
                self.assertLess(time.monotonic()-started, 7)
                self.assertFalse(marker.exists())

    def test_child_exit_125_remains_the_childs_exit_status(self):
        completed = common.run_process_tree([sys.executable, '-c', 'raise SystemExit(125)'],
            timeout=5, text=True, capture_output=True, check=False)
        self.assertEqual(completed.returncode, 125)

    def test_unavailable_parent_identity_fails_before_launch(self):
        with mock.patch.object(common, '_linux_process_identity', return_value=None), \
                mock.patch.object(common.subprocess, 'Popen') as launch:
            with self.assertRaisesRegex(RuntimeError, 'identity'):
                with common.owned_process_tree([sys.executable, '-c', 'pass']):
                    pass
        launch.assert_not_called()

    def _assert_stopped(self, pid: int) -> None:
        deadline = time.monotonic() + 3
        path = Path(f'/proc/{pid}/stat')
        while path.exists() and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertFalse(path.exists(), 'owned worker remains running after its operation ended')

    def _survivor_command(self, marker: Path, *, exit_code: int = 0) -> list[str]:
        child = ('import os,time\nfrom pathlib import Path\n'
                 f'Path({str(marker)+".birth"!r}).write_text(Path("/proc/self/stat").read_text().rsplit(")",1)[1].split()[19])\n'
                 f'Path({str(marker)!r}).write_text(str(os.getpid()))\n'
                 'while True: time.sleep(.1)\n')
        root = ('import subprocess,sys,time\nfrom pathlib import Path\n'
                f'subprocess.Popen([sys.executable,"-c",{child!r}],start_new_session=True, '
                'stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)\n'
                f'while not Path({str(marker)!r}).exists(): time.sleep(.01)\n'
                f'sys.exit({exit_code})\n')
        return [sys.executable, '-B', '-c', root]

    def test_success_and_failure_retire_new_session_descendants_only(self) -> None:
        work = PROJECT_ROOT.parent.parent / 'Test suite/work'
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(30)'], start_new_session=True)
        try:
            for exit_code in (0, 7):
                with self.subTest(exit_code=exit_code), tempfile.TemporaryDirectory(dir=work) as name:
                    marker = Path(name) / 'descendant.pid'
                    pid = None
                    try:
                        result = common.run_process_tree(self._survivor_command(marker, exit_code=exit_code),
                                                         timeout=5, capture_output=True)
                        self.assertEqual(result.returncode, exit_code)
                        pid = int(marker.read_text())
                        self._assert_stopped(pid)
                        self.assertIsNone(unrelated.poll(), 'cleanup must never target an unrelated process')
                    finally:
                        if pid is not None:
                            try: terminate_exact(pid, int(Path(str(marker)+".birth").read_text()))
                            except ProcessLookupError: pass
        finally:
            unrelated.kill()
            unrelated.wait(timeout=3)

    def test_timeout_retires_nested_supervisor_and_its_escaped_worker(self) -> None:
        work = PROJECT_ROOT.parent.parent / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            marker = Path(name) / 'descendant.pid'
            command = self._survivor_command(marker)
            command[-1] = command[-1].replace('sys.exit(0)', 'time.sleep(30)')
            outer = ('import sys\nfrom stemwise_engine.common import run_process_tree\n'
                     f'run_process_tree({command!r}, timeout=30, capture_output=True)\n')
            pid = None
            try:
                with self.assertRaises(subprocess.TimeoutExpired):
                    common.run_process_tree([sys.executable, '-B', '-c', outer], timeout=8,
                        capture_output=True, env={**os.environ, 'PYTHONPATH': str(PROJECT_ROOT / 'src')})
                pid = int(marker.read_text())
                self._assert_stopped(pid)
            finally:
                if pid is not None:
                    try: terminate_exact(pid, int(Path(str(marker)+".birth").read_text()))
                    except ProcessLookupError: pass

    def test_interrupt_and_hard_parent_loss_retire_worker_and_release_lease(self) -> None:
        from stemwise_engine.runtime_environment import command_environment
        work = PROJECT_ROOT.parent.parent / 'Test suite/work'
        for reason in (signal.SIGINT, signal.SIGKILL):
            with self.subTest(reason=reason), tempfile.TemporaryDirectory(dir=work) as name:
                project = Path(name)
                marker = project / 'descendant.pid'
                lock = project / 'stemwise-heavy-worker.lock'
                command = self._survivor_command(marker)
                command[-1] = command[-1].replace('sys.exit(0)', 'time.sleep(30)')
                code = ('from pathlib import Path\nfrom stemwise_engine.common import exclusive_process_lock,run_process_tree\n'
                        f'with exclusive_process_lock(Path({str(lock)!r})):\n'
                        f' run_process_tree({command!r}, timeout=30)\n')
                command = [sys.executable, '-B', '-c', code]
                with command_environment(command, {'start_new_session': True,
                        'stdout': subprocess.DEVNULL, 'stderr': subprocess.PIPE}) as options:
                    parent = subprocess.Popen(command, **options)
                pid = None
                try:
                    deadline = time.monotonic() + 5
                    while not marker.exists() and parent.poll() is None and time.monotonic() < deadline:
                        time.sleep(.02)
                    diagnostic = 'fixture must reach the actual worker before cancellation'
                    if not marker.exists() and parent.poll() is not None:
                        diagnostic = parent.communicate(timeout=3)[1].decode('utf-8', errors='replace')
                    self.assertTrue(marker.exists(), diagnostic)
                    pid = int(marker.read_text())
                    os.kill(parent.pid, reason)
                    parent.wait(timeout=5)
                    self._assert_stopped(pid)
                    with common.exclusive_process_lock(lock, wait_seconds=3):
                        pass
                finally:
                    if pid is None and marker.exists():
                        pid = int(marker.read_text())
                    if pid is not None:
                        try: terminate_exact(pid, int(Path(str(marker)+".birth").read_text()))
                        except ProcessLookupError: pass
                    if parent.poll() is None: parent.kill()
                    parent.wait(timeout=3)
                    parent.stderr.close()

    def test_large_standard_input_preserves_unicode_and_complete_target_order(self) -> None:
        targets = [f"word-{index}-\u0645\u062d\u062f\u062f" for index in range(12000)]
        request = json.dumps({"targets": targets}, ensure_ascii=False)
        self.assertGreater(len(request.encode("utf-8")), 128 * 1024)
        child = "import json,sys; print(json.dumps(json.load(sys.stdin)['targets'], ensure_ascii=False))"
        result = common.run_process_tree([sys.executable, "-c", child],
            input=request, timeout=10, capture_output=True, text=True, encoding="utf-8", check=True)
        self.assertEqual(json.loads(result.stdout), targets)

    def test_standard_input_conflict_is_rejected_before_spawning(self) -> None:
        with mock.patch.object(common.subprocess, "Popen") as spawn:
            with self.assertRaisesRegex(ValueError, "stdin"):
                common.run_process_tree([sys.executable], input="request", stdin=subprocess.PIPE, timeout=1)
            spawn.assert_not_called()

    def test_timeout_terminates_descendant_process_group(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / 'Test suite/work') as name:
            marker = Path(name) / "heartbeat.txt"
            grandchild = (
                "import pathlib,sys,time\n"
                "p=pathlib.Path(sys.argv[1])\n"
                "while True:\n"
                " p.open('a', encoding='ascii').write('x')\n"
                " time.sleep(0.03)\n"
            )
            parent = (
                "import subprocess,sys,time\n"
                f"subprocess.Popen([sys.executable, '-c', {grandchild!r}, sys.argv[1]])\n"
                "time.sleep(60)\n"
            )
            launch = subprocess.Popen
            def admitted(*args, **kwargs):
                process = launch(*args, **kwargs)
                deadline = time.monotonic()+5
                while not marker.exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                if not marker.exists():
                    common._terminate_process_tree(process)
                    process.communicate(timeout=5)
                    self.fail('Descendant never reached the heartbeat boundary')
                return process

            with mock.patch.object(common.subprocess, 'Popen', side_effect=admitted), \
                    self.assertRaises(subprocess.TimeoutExpired):
                common.run_process_tree(
                    [sys.executable, "-c", parent, str(marker)],
                    input="x" * (256 * 1024),
                    timeout=1.2,
                    capture_output=True,
                    text=True,
                )
            size_after_timeout = marker.stat().st_size
            time.sleep(0.2)
            self.assertEqual(marker.stat().st_size, size_after_timeout)

    def test_command_exit_retires_descendants_holding_output_pipes(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / 'Test suite/work') as name:
            marker = Path(name) / "heartbeat.txt"
            grandchild = (
                "import pathlib,sys,time\n"
                "p=pathlib.Path(sys.argv[1])\n"
                "while True:\n"
                " p.open('a', encoding='ascii').write('x')\n"
                " time.sleep(0.03)\n"
            )
            parent = (
                "import subprocess,sys,time\nfrom pathlib import Path\n"
                f"subprocess.Popen([sys.executable, '-c', {grandchild!r}, sys.argv[1]])\n"
                "while not Path(sys.argv[1]).exists() or Path(sys.argv[1]).stat().st_size == 0: time.sleep(.01)\n"
            )

            result = common.run_process_tree(
                [sys.executable, "-c", parent, str(marker)],
                timeout=5,
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.returncode, 0)
            size_after_timeout = marker.stat().st_size
            time.sleep(0.2)
            self.assertEqual(marker.stat().st_size, size_after_timeout)

    def test_durable_replace_fsyncs_file_then_directory(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            staged = root / ".value.tmp"
            destination = root / "value"
            staged.write_bytes(b"new")
            events: list[str] = []
            real_replace = os.replace

            def replace(source, target):
                events.append("replace")
                real_replace(source, target)

            with mock.patch('stemwise_engine.common.os.fsync', side_effect=lambda _fd: events.append("fsync")), mock.patch(
                'stemwise_engine.common.os.replace', side_effect=replace
            ):
                common.durable_replace(staged, destination)

            self.assertEqual(events, ["fsync", "replace", "fsync"])
            self.assertEqual(destination.read_bytes(), b"new")


if __name__ == "__main__":
    unittest.main()
