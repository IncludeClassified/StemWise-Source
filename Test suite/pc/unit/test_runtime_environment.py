import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT/'PC side/stemwise-playlist-builder/src'
import stemwise_engine.environment_bootstrap as bootstrap
import stemwise_engine.environment_locks as locks
import stemwise_engine.runtime_environment as runtime
from stemwise_engine.common import _linux_process_identity, run_process_tree
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_package, prepare_runtime_layout


def fixture_environment(path, marker):
    if path.name in ('.venv', '.venv-allin1', '.venv-lyrics-align'):
        prepare_runtime_layout(path.parent)
    binary = path / 'bin/python'
    binary.parent.mkdir(parents=True)
    binary.write_text(f'#!/bin/sh\nprintf {marker}', encoding='utf-8')
    binary.chmod(0o700)
    (path / 'pyvenv.cfg').write_text(
        f'home = {sys.base_prefix}/bin\ninclude-system-site-packages = false\n'
        f'version = {sys.version.split()[0]}\nprompt = {marker}\n', encoding='utf-8')


def fixture_promotion_journal(builder, current, scratch):
    from stemwise_engine.common import atomic_write_json
    journal = builder/'.environment-install-main.json'
    atomic_write_json(journal, {'formatVersion': 1, 'phase': 'prepared',
        'destination': str(current), 'scratch': str(scratch), 'staged': str(scratch/'replacement'),
        'replacementIdentity': bootstrap._environment_identity(current),
        'previousIdentity': bootstrap._environment_identity(scratch/'previous')})
    return journal


@unittest.skipUnless(sys.platform == 'linux', 'Real WSL kernel leases')
class RuntimeEnvironmentTest(unittest.TestCase):
    def _assert_usage_leases(self, actual, ambient, fixture_path, descriptor):
        fixture = [str(fixture_path), descriptor, False]
        self.assertEqual([record for record in actual if record[0] == str(fixture_path)], [fixture])
        self.assertEqual(sorted(actual), sorted([*ambient, fixture]))

    def test_configured_runtime_command_inherits_its_usage_lease(self):
        import json
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            builder = Path(name)/'builder'
            prepare_runtime_package(builder)
            layout_path = builder.parent/'runtime-layout.json'
            layout = json.loads(layout_path.read_text(encoding='utf-8'))
            for route in ('main', 'lyrics', 'allin1'):
                layout['environments'][route] = f'custom/{route}/bin/python3.10'
                binary = builder.parent/layout['environments'][route]
                binary.parent.mkdir(parents=True)
                binary.symlink_to(sys.executable)
                (binary.parent/'python3').symlink_to(sys.executable)
            layout_path.write_text(json.dumps(layout), encoding='utf-8')
            for route in ('main', 'lyrics', 'allin1'):
                configured = builder.parent/layout['environments'][route]
                for binary in (configured, configured.parent/'python3'):
                    with self.subTest(route=route, binary=binary.name):
                        incoming = runtime._LEASES.get()
                        ambient = [[str(path), fd, exclusive] for path, fd, exclusive in incoming]
                        fixture_path = builder.resolve()/f'.environment-use-{route}.lock'
                        script = ('import json; import stemwise_engine.runtime_environment as r; '
                            'print(json.dumps([[str(path), fd, exclusive] for path, fd, exclusive in r._LEASES.get()]))')
                        command = [str(binary), '-B', '-c', script]
                        with runtime.command_environment(command,
                                {'env': {**os.environ, 'PYTHONPATH': str(builder/'.fixture-packages')}}, builder=builder) as options:
                            fixture = [record for record in runtime._LEASES.get() if record[0] == fixture_path]
                            self.assertEqual(len(fixture), 1)
                            descriptor = fixture[0][1]
                            self.assertFalse(fixture[0][2])
                            self.assertTrue(({fd for _, fd, _ in incoming} | {descriptor}).issubset(options['pass_fds']))
                            result = run_process_tree(command, text=True, capture_output=True, timeout=5, **options)
                        self.assertEqual(result.returncode, 0, result.stderr)
                        self._assert_usage_leases(json.loads(result.stdout), ambient, fixture_path, descriptor)
                        self.assertEqual(runtime._LEASES.get(), incoming)

    def test_usage_lease_assertions_reject_wrong_fixture_or_lost_ambient_ownership(self):
        fixture_path = Path('/fixture/.environment-use-lyrics.lock')
        fixture = [str(fixture_path), 4, False]
        for ambient in ([], [['/outer/.environment-use-main.lock', 3, True]]):
            with self.subTest(ambient=ambient):
                self._assert_usage_leases([fixture, *reversed(ambient)], ambient, fixture_path, 4)
                invalid = {
                    'missing': list(ambient),
                    'wrong-root': [*ambient, ['/other/.environment-use-lyrics.lock', 4, False]],
                    'duplicate': [*ambient, fixture, fixture],
                    'exclusive': [*ambient, [str(fixture_path), 4, True]],
                    'wrong-descriptor': [*ambient, [str(fixture_path), 5, False]],
                    'unexpected-lease': [*ambient, fixture, ['/other/.environment-use-main.lock', 6, False]],
                }
                if ambient:
                    invalid['lost-ambient'] = [fixture]
                for fault, records in invalid.items():
                    with self.subTest(fault=fault), self.assertRaises(AssertionError):
                        self._assert_usage_leases(records, ambient, fixture_path, 4)

    def test_unretired_final_probe_preserves_new_environment_previous_and_journal(self):
        from stemwise_engine.common import WorkerRetirementError
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            builder = Path(directory)/'builder'
            current, scratch = builder/'.venv', builder/'.environment-build/environment-build-fixture'
            fixture_environment(current, 'new')
            fixture_environment(scratch/'previous', 'old')
            journal = fixture_promotion_journal(builder, current, scratch)
            fatal = WorkerRetirementError('probe still owns replacement environment')
            with self.assertRaises(WorkerRetirementError) as raised:
                bootstrap._finish_promotion('main', current, scratch, journal, builder,
                    lambda *args, **kwargs: (_ for _ in ()).throw(fatal))
            self.assertIs(raised.exception, fatal)
            self.assertTrue(journal.exists())
            self.assertEqual(subprocess.check_output([str(current/'bin/python')]), b'new')
            self.assertEqual(subprocess.check_output([str(scratch/'previous/bin/python')]), b'old')

    def test_real_supervised_readiness_keeps_exclusive_lease_and_rolls_back_failure(self):
        import json
        for fails in (False, True):
            with self.subTest(fails=fails), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                builder = Path(directory)/'PC side/builder'
                current = builder/'.venv'
                fixture_environment(current, 'new')
                (current/'bin/python').unlink()
                (current/'bin/python').symlink_to(sys.executable)
                scratch = builder/'.environment-build/environment-build-fixture'
                fixture_environment(scratch/'previous', 'old')
                probe = builder/'src/environment_probe.py'
                prepare_runtime_package(builder)
                probe.parent.mkdir(exist_ok=True)
                probe.write_text(
                    'import sys, subprocess\n'
                    'import stemwise_engine.runtime_environment as runtime\n'
                    'from stemwise_engine.common import run_process_tree\n'
                    'assert any(exclusive for path, fd, exclusive in runtime._LEASES.get())\n'
                    f'lock = {str(builder/".environment-use-main.lock")!r}\n'
                    'competing = "import fcntl,os,sys; f=os.open(sys.argv[1],os.O_RDWR); " + '
                    '"fcntl.flock(f,fcntl.LOCK_SH|fcntl.LOCK_NB)"\n'
                    'result = subprocess.run([sys.executable, "-c", competing, lock], capture_output=True, timeout=3)\n'
                    'assert result.returncode != 0, "exclusive promotion lease was downgraded"\n'
                    'run_process_tree([sys.executable, "-B", "-c", '
                    f'"import stemwise_engine.runtime_environment as r; assert any(str(path)=={str(builder/".environment-use-main.lock")!r} and mode for path,fd,mode in r._LEASES.get())"], '
                    f'env={{"PYTHONPATH": {str(builder/".fixture-packages")!r}}}, timeout=5, check=True)\n'
                    f'raise SystemExit({8 if fails else 0})\n', encoding='utf-8')
                journal = fixture_promotion_journal(builder, current, scratch)
                lease = runtime.environment_lease
                def bounded(*args, **kwargs):
                    return lease(*args, **kwargs, wait_seconds=.2)
                with lease('main', builder=builder, exclusive=True), patch.object(runtime, 'BUILDER', builder), \
                        patch.object(runtime, 'environment_lease', bounded), \
                        patch.dict(os.environ, {'PYTHONPATH': str(builder/'.fixture-packages')}):
                    if fails:
                        with self.assertRaises(subprocess.CalledProcessError):
                            bootstrap._finish_promotion('main', current, scratch, journal, builder, run_process_tree)
                    else:
                        try:
                            bootstrap._finish_promotion('main', current, scratch, journal, builder, run_process_tree)
                        except subprocess.CalledProcessError as error:
                            self.fail(error.stderr)
                if fails:
                    self.assertEqual(subprocess.check_output([str(current/'bin/python')]), b'old')
                self.assertFalse(scratch.exists())
                self.assertFalse(journal.exists())

    def test_readiness_failure_before_or_after_promotion_preserves_previous_interpreter(self):
        for boundary in ('staged', 'final'):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                builder = Path(directory)/'PC side/builder'
                current = builder/'.venv/bin/python'
                fixture_environment(current.parent.parent, 'old')
                checks = []
                def run(command, **kwargs):
                    if 'venv' in command:
                        fixture_environment(Path(command[-1]), 'new')
                        return
                    checks.append(Path(command[0]))
                    if (boundary == 'staged' and Path(command[0]) != current
                            or boundary == 'final' and Path(command[0]) == current):
                        raise subprocess.CalledProcessError(1, command, stderr='ImportError: compiled dependency')
                    return subprocess.CompletedProcess(command, 0, '{}', '')
                with patch.object(bootstrap, 'validate_plan'), patch('stemwise_engine.runtime_layout.load_layout', return_value={
                        'python': {'executable': 'runtime/python/bin/python3.10'},
                            'environments': {name: 'builder/'+path+'/bin/python' for name,path in
                                {'main': '.venv', 'allin1': '.venv-allin1', 'lyrics': '.venv-lyrics-align'}.items()}}), self.assertRaises(subprocess.CalledProcessError):
                    bootstrap.replace_environment('main', builder=builder, run=run, installer=lambda *a, **k: None)
                self.assertTrue(checks)
                self.assertEqual(subprocess.check_output([str(current)]), b'old')
                self.assertFalse((builder/'.environment-install-main.json').exists())
                self.assertEqual(list((builder/'.environment-build').iterdir()), [])

    def test_interrupted_first_install_and_replacement_recover_at_each_switch_boundary(self):
        from stemwise_engine.common import atomic_write_json
        class PowerLoss(BaseException):
            pass
        for existing in (False, True):
            for boundary in ('journal', 'retire', 'promote'):
                with self.subTest(existing=existing, boundary=boundary), \
                        tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                    builder = Path(directory)/'PC side/builder'
                    builder.mkdir(parents=True)
                    current = builder/'.venv/bin/python'
                    if existing:
                        fixture_environment(current.parent.parent, 'old')
                    def create(command, **kwargs):
                        if 'venv' not in command:
                            return subprocess.CompletedProcess(command, 0, '{}', '')
                        fixture_environment(Path(command[-1]), 'new')
                    def journal(*args, **kwargs):
                        atomic_write_json(*args, **kwargs)
                        if boundary == 'journal':
                            raise PowerLoss()
                    replace = Path.replace
                    def rename(path, target):
                        result = replace(path, target)
                        if ((boundary == 'retire' and path.name == '.venv')
                                or (boundary == 'promote' and path.name == 'replacement')):
                            raise PowerLoss()
                        return result
                    with patch.object(bootstrap, 'validate_plan'), \
                            patch('stemwise_engine.runtime_layout.load_layout', return_value={
                                'python': {'executable': 'runtime/python/bin/python3.10'},
                            'environments': {name: 'builder/'+path+'/bin/python' for name,path in
                                {'main': '.venv', 'allin1': '.venv-allin1', 'lyrics': '.venv-lyrics-align'}.items()}}):
                        with patch.object(bootstrap, 'atomic_write_json', side_effect=journal), \
                                patch.object(Path, 'replace', rename):
                            if existing or boundary != 'retire':
                                with self.assertRaises(PowerLoss):
                                    bootstrap.replace_environment('main', builder=builder, run=create, installer=lambda *a, **k: None)
                            else:
                                bootstrap.replace_environment('main', builder=builder, run=create, installer=lambda *a, **k: None)
                        bootstrap.replace_environment('main', builder=builder, run=create, installer=lambda *a, **k: None)
                    self.assertEqual(subprocess.check_output([str(current)]), b'new')
                    self.assertFalse((builder/'.environment-install-main.json').exists())
                    self.assertEqual(list((builder/'.environment-build').iterdir()), [])

    def test_committed_cleanup_interruption_never_probes_or_rolls_back_and_is_idempotent(self):
        import json
        from stemwise_engine.common import atomic_write_json
        class PowerLoss(BaseException):
            pass
        for boundary in ('commit', 'partial-cleanup'):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                builder = Path(directory)/'builder'
                current = builder/'.venv'
                fixture_environment(current, 'old')
                journal = builder/'.environment-install-main.json'
                def create(command, **kwargs):
                    if 'venv' in command:
                        fixture_environment(Path(command[-1]), 'new')
                    return subprocess.CompletedProcess(command, 0, '{}', '')
                def commit(path, record, **kwargs):
                    atomic_write_json(path, record, **kwargs)
                    if boundary == 'commit' and record['phase'] == 'cleanup':
                        raise PowerLoss()
                rmtree = bootstrap.shutil.rmtree
                def interrupted_cleanup(path, *args, **kwargs):
                    if boundary == 'partial-cleanup' and (Path(path)/'previous').exists():
                        (Path(path)/'previous/bin/python').unlink()
                        raise PowerLoss()
                    return rmtree(path, *args, **kwargs)
                with patch.object(bootstrap, 'validate_plan'), \
                        patch.object(bootstrap, 'atomic_write_json', side_effect=commit), \
                        patch.object(bootstrap.shutil, 'rmtree', side_effect=interrupted_cleanup), \
                        self.assertRaises(PowerLoss):
                    bootstrap.replace_environment('main', builder=builder, run=create, installer=lambda *a, **k: None)
                record = json.loads(journal.read_text(encoding='utf-8'))
                self.assertEqual(record['phase'], 'cleanup')
                scratch = Path(record['scratch'])
                self.assertEqual(subprocess.check_output([str(current/'bin/python')]), b'new')
                self.assertEqual((scratch/'previous/bin/python').exists(), boundary == 'commit')
                unlink = Path.unlink
                def interrupt_journal(path, *args, **kwargs):
                    if path == journal:
                        raise PowerLoss()
                    return unlink(path, *args, **kwargs)
                def forbidden(*args, **kwargs):
                    raise AssertionError('Committed cleanup must not probe, create or install an environment')
                with patch.object(bootstrap, 'validate_plan'):
                    with patch.object(Path, 'unlink', interrupt_journal), self.assertRaises(PowerLoss):
                        bootstrap.replace_environment('main', builder=builder, run=forbidden, installer=forbidden)
                    self.assertFalse(scratch.exists())
                    self.assertTrue(journal.exists())
                    bootstrap.replace_environment('main', builder=builder, run=forbidden, installer=forbidden)
                self.assertFalse(journal.exists())
                self.assertEqual(subprocess.check_output([str(current/'bin/python')]), b'new')

    def test_failed_cleanup_commit_preserves_previous_until_readiness_can_choose_rollback(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            builder = Path(directory)/'builder'
            current, scratch = builder/'.venv', builder/'.environment-build/environment-build-fixture'
            fixture_environment(current, 'new')
            fixture_environment(scratch/'previous', 'old')
            journal = fixture_promotion_journal(builder, current, scratch)
            with patch.object(bootstrap, 'atomic_write_json', side_effect=OSError('commit failed')), \
                    self.assertRaisesRegex(OSError, 'commit failed'):
                bootstrap._finish_promotion('main', current, scratch, journal, builder, lambda *a, **k: None)
            self.assertEqual(subprocess.check_output([str(scratch/'previous/bin/python')]), b'old')
            def reject_current(command, **kwargs):
                if Path(command[0]) == current/'bin/python':
                    raise RuntimeError('current readiness failed')
                return subprocess.CompletedProcess(command, 0, '{}', '')
            with patch.object(bootstrap, 'validate_plan'), self.assertRaisesRegex(RuntimeError, 'current readiness failed'):
                bootstrap.replace_environment('main', builder=builder, run=reject_current)
            self.assertEqual(subprocess.check_output([str(current/'bin/python')]), b'old')
            self.assertFalse(journal.exists())

    def test_rejected_rollback_candidates_preserve_both_trees_and_recovery_authority(self):
        for fault in ('identity', 'readiness', 'no-previous', 'committed-conflict', 'missing-previous'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                builder = Path(directory)/'builder'
                current, scratch = builder/'.venv', builder/'.environment-build/environment-build-fixture'
                fixture_environment(current, 'new')
                if fault != 'no-previous':
                    fixture_environment(scratch/'previous', 'old')
                else:
                    scratch.mkdir(parents=True)
                journal = fixture_promotion_journal(builder, current, scratch)
                if fault == 'missing-previous':
                    bootstrap.shutil.rmtree(scratch/'previous')
                    current.replace(scratch/'replacement')
                if fault == 'identity':
                    (scratch/'previous/pyvenv.cfg').write_text('damaged', encoding='utf-8')
                if fault == 'committed-conflict':
                    from stemwise_engine.common import atomic_write_json, read_bounded_json
                    record = read_bounded_json(journal, max_bytes=4096, root=builder)
                    atomic_write_json(journal, {**record, 'phase': 'cleanup'}, root=builder)
                    (current/'pyvenv.cfg').write_text('different owner', encoding='utf-8')
                def reject(command, **kwargs):
                    raise RuntimeError('readiness failed')
                with patch.object(bootstrap, 'validate_plan'), self.assertRaises((ValueError, RuntimeError)):
                    bootstrap.replace_environment('main', builder=builder, run=reject)
                self.assertTrue(journal.exists())
                self.assertTrue(scratch.exists())
                retained = scratch/'replacement' if fault == 'missing-previous' else current
                self.assertEqual(subprocess.check_output([str(retained/'bin/python')]), b'new')
                if fault not in ('no-previous', 'missing-previous'):
                    self.assertEqual(subprocess.check_output([str(scratch/'previous/bin/python')]), b'old')

    def test_nested_cli_preserves_inherited_lease_through_guardian(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory, \
                runtime.environment_lease('lyrics', builder=directory):
            lease = str(Path(directory)/'.environment-use-lyrics.lock')
            descendant = ('import stemwise_engine.runtime_environment as runtime; '
                          f'print(any(str(path)=={lease!r} for path,fd,exclusive in runtime._LEASES.get()))')
            child = ('from stemwise_engine.common import run_process_tree; '
                     f'run_process_tree({[sys.executable, "-B", "-c", descendant]!r}, timeout=5, check=True)')
            result = run_process_tree([sys.executable, '-B', '-m', 'stemwise_engine.runtime_environment',
                sys.executable, '-B', '-c', child], timeout=10, capture_output=True, text=True,
                check=True, env=dict(os.environ))
            self.assertEqual(result.stdout.strip(), 'True')

    def test_default_install_runners_retire_descendants_before_scratch_cleanup(self):
        for operation in ('bootstrap', 'install'):
            with self.subTest(operation=operation), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                builder = Path(directory)/'PC side/builder'
                uv = builder/'.tools/uv'
                uv.parent.mkdir(parents=True)
                marker = builder/'child-pid'
                uv.write_text('#!/usr/bin/env python3\n'
                    'import subprocess,sys\nfrom pathlib import Path\n'
                    'child=subprocess.Popen([sys.executable,"-c","import time;time.sleep(60)"],'
                    'start_new_session=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)\n'
                    f'Path({str(marker)!r}).write_text(str(child.pid))\n'
                    'sys.exit(1)\n', encoding='utf-8')
                uv.chmod(0o700)
                current = builder/'.venv/bin/python'
                fixture_environment(current.parent.parent, 'old')
                try:
                    with patch.object(bootstrap, 'validate_plan'), \
                            patch.object(locks, 'validate_plan', return_value=(
                                {'pythonVersion': sys.version.split()[0]}, {}, {}, [])), \
                            patch('stemwise_engine.runtime_layout.load_layout', return_value={
                                'python': {'executable': 'runtime/python/bin/python3.10'},
                            'environments': {name: 'builder/'+path+'/bin/python' for name,path in
                                {'main': '.venv', 'allin1': '.venv-allin1', 'lyrics': '.venv-lyrics-align'}.items()}}):
                        with self.assertRaises(subprocess.CalledProcessError):
                            if operation == 'bootstrap':
                                bootstrap.replace_environment('main', builder=builder)
                            else:
                                locks.install('main', sys.executable, builder=builder)
                    child_pid = int(marker.read_text())
                    self.assertIsNone(_linux_process_identity(child_pid), 'installer descendant survived failure')
                    self.assertEqual(subprocess.check_output([str(current)]), b'old')
                    self.assertFalse((builder/'.environment-install-main.json').exists())
                    self.assertFalse(any((builder/'.environment-build').glob('environment-build-*')))
                    self.assertFalse(any((builder.parent/'runtime/scratch/environment-install').glob('environment-lock-*')))
                finally:
                    if marker.exists():
                        child_pid = int(marker.read_text())
                        if _linux_process_identity(child_pid) is not None:
                            os.kill(child_pid, 9)

    def test_managed_child_preserves_none_and_empty_environment_with_active_lease(self):
        code = ('import os,sys;sys.path.insert(0,sys.argv[1]);import stemwise_engine.runtime_environment as runtime_environment;print(os.environ.get("STEMWISE_TEST_PARENT_ENV", "absent"))')
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory, \
                runtime.environment_lease('main', builder=directory), \
                patch.dict(os.environ, {'STEMWISE_TEST_PARENT_ENV': 'inherited'}):
            for environment, expected in ((None, 'inherited'), ({}, 'absent')):
                result = run_process_tree([sys.executable, '-B', '-c', code, str(SOURCE)],
                    env=environment, timeout=5, capture_output=True, text=True, check=True)
                self.assertEqual(result.stdout.strip(), expected)

    def test_persistent_workers_can_retire_in_a_different_order_than_admission(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            first = runtime.environment_lease('main', builder=directory)
            second = runtime.environment_lease('lyrics', builder=directory)
            first.__enter__()
            second.__enter__()
            try:
                first.__exit__(None, None, None)
                with runtime.environment_lease('main', builder=directory, exclusive=True, wait_seconds=0):
                    pass
                with self.assertRaises(TimeoutError):
                    with runtime.environment_lease('lyrics', builder=directory, exclusive=True, wait_seconds=0):
                        pass
            finally:
                second.__exit__(None, None, None)
            result = run_process_tree([sys.executable, '-B', '-c', 'import stemwise_engine.runtime_environment as runtime_environment; print("retired")'],
                timeout=5, capture_output=True, text=True, check=True,
                env=dict(os.environ))
            self.assertEqual(result.stdout.strip(), 'retired')

    def test_publication_waits_for_reader_and_preserves_runnable_current(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            builder = Path(directory)/'PC side/builder'
            current = builder/'.venv/bin/python'
            fixture_environment(current.parent.parent, 'old')
            staged, finished, errors = threading.Event(), threading.Event(), []
            def create(command, **kwargs):
                if 'venv' not in command:
                    return subprocess.CompletedProcess(command, 0, '{}', '')
                fixture_environment(Path(command[-1]), 'new')
            def install(*args, **kwargs):
                staged.set()
            def replace():
                try:
                    bootstrap.replace_environment('main', builder=builder, run=create, installer=install)
                except BaseException as error:
                    errors.append(error)
                finally:
                    finished.set()
            thread = threading.Thread(target=replace)
            def bounded_lease(*args, **kwargs):
                return runtime.environment_lease(*args, **kwargs, wait_seconds=1)
            try:
                with patch.object(bootstrap, 'validate_plan'), patch.object(bootstrap, 'environment_lease', bounded_lease), \
                        patch('stemwise_engine.runtime_layout.load_layout', return_value={
                            'python': {'executable': 'runtime/python/bin/python3.10'},
                            'environments': {name: 'builder/'+path+'/bin/python' for name,path in
                                {'main': '.venv', 'allin1': '.venv-allin1', 'lyrics': '.venv-lyrics-align'}.items()}}):
                    with runtime.environment_lease('main', builder=builder):
                        thread.start()
                        self.assertTrue(staged.wait(3), 'installer did not reach publication')
                        self.assertFalse(finished.wait(.2), 'installer replaced an environment still in use')
                        self.assertEqual(subprocess.check_output([str(current)]), b'old')
            finally:
                if thread.ident is not None:
                    thread.join(3)
            self.assertFalse(thread.is_alive(), 'environment replacement did not terminate')
            self.assertEqual(errors, [])
            self.assertEqual(subprocess.check_output([str(current)]), b'new')

    def test_shared_readers_block_writer_and_failed_wait_releases_descriptor(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            builder = Path(directory)
            with runtime.environment_lease('lyrics', builder=builder):
                code = ('from stemwise_engine.runtime_environment import environment_lease\n'
                        f'with environment_lease("lyrics", builder={str(builder)!r}, wait_seconds=0):\n print("read")\n')
                result = run_process_tree([sys.executable, '-B', '-c', code], timeout=5,
                    env=dict(os.environ), capture_output=True, text=True, check=True)
                self.assertEqual(result.stdout.strip(), 'read')
                with self.assertRaises(TimeoutError):
                    with runtime.environment_lease('lyrics', builder=builder, exclusive=True, wait_seconds=.05):
                        self.fail('writer entered with a live reader')
            with runtime.environment_lease('lyrics', builder=builder, exclusive=True, wait_seconds=.2):
                pass

    def test_owned_child_inherits_lease_until_retirement(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            builder = Path(directory)
            marker = builder/'ready'
            code = ('import time\nfrom pathlib import Path\nfrom stemwise_engine.runtime_environment import environment_lease\n'
                    'from stemwise_engine.common import run_process_tree\n'
                    f'with environment_lease("main", builder={str(builder)!r}):\n'
                    f' run_process_tree({[sys.executable, "-B", "-c", "from pathlib import Path; import time; Path("+repr(str(marker))+").touch(); time.sleep(30)"]!r}, timeout=30)\n')
            parent = subprocess.Popen([sys.executable, '-B', '-c', code],
                env=dict(os.environ), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                deadline = time.monotonic()+5
                while not marker.exists() and parent.poll() is None and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertTrue(marker.exists(), 'child must start before parent loss')
                with self.assertRaises(TimeoutError):
                    with runtime.environment_lease('main', builder=builder, exclusive=True, wait_seconds=0):
                        pass
                parent.kill()
                parent.wait(timeout=3)
                with runtime.environment_lease('main', builder=builder, exclusive=True, wait_seconds=5):
                    pass
            finally:
                if parent.poll() is None:
                    parent.kill()
                parent.wait(timeout=3)


if __name__ == '__main__':
    unittest.main()
