"""Offline clean-source smoke rejects missing prerequisites before claiming build/recovery coverage."""
from pathlib import Path
from types import SimpleNamespace
import subprocess
import sys
import tempfile
import io
from contextlib import redirect_stderr, redirect_stdout
import unittest
from unittest.mock import patch

PC = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PC))
import clean_checkout


class CleanCheckoutTest(unittest.TestCase):
    def test_checkout_preserves_only_unretired_work_and_retry_leaves_it_intact(self):
        from owned_process import OwnedProcessError
        from stemwise_engine.common import WorkerRetirementError, remove_disposable_path
        for android in (False, True):
            for fault in (None, ValueError('ordinary failure'),
                    OwnedProcessError({'failureKind': 'timeout'}),
                    OwnedProcessError({'failureKind': 'cleanup'}), WorkerRetirementError('worker survived')):
                with self.subTest(android=android, fault=type(fault).__name__), tempfile.TemporaryDirectory(dir=PC.parent/'work') as name:
                    root = Path(name)
                    (root/'Test suite/work').mkdir(parents=True)
                    snapshots, fail = [], True
                    def copy(destination, **kwargs):
                        destination.mkdir(parents=True)
                        (destination/'input.py').write_bytes(b'copied source')
                        registry = destination/'PC side/stemwise-playlist-builder/src/model-route-registry.json'
                        registry.parent.mkdir(parents=True)
                        registry.write_text('{"runtimeProfiles":{"main":{"python":"3.10"}}}', encoding='utf-8')
                        snapshots.append(destination.parent)
                    def run(command, *, cwd, timeout, output_log=None):
                        if output_log:
                            Path(output_log).write_bytes(b'partial process diagnostics')
                            if fail and fault is not None: raise fault
                        stdout = str(Path(cwd)/'site') if 'sysconfig' in ' '.join(command) else '3.10'
                        return subprocess.CompletedProcess(command, 73 if command[-1] == 'interrupt' else 0, stdout, '')
                    errors = io.StringIO()
                    with patch.object(clean_checkout, 'ROOT', root), patch.object(clean_checkout, 'copy_source', side_effect=copy), \
                            patch.object(clean_checkout, 'android_command', return_value=['fixture-compile']), \
                            patch.object(clean_checkout.importlib.metadata, 'version', return_value='78.1.0'), \
                            patch.object(clean_checkout.venv.EnvBuilder, 'create'), patch.object(clean_checkout, 'run', side_effect=run), \
                            redirect_stdout(io.StringIO()), redirect_stderr(errors):
                        if fault is None: clean_checkout.smoke(android=android)
                        else:
                            with self.assertRaises(type(fault)) as raised: clean_checkout.smoke(android=android)
                            self.assertIs(raised.exception, fault)
                        retained = isinstance(fault, WorkerRetirementError) or isinstance(fault, OwnedProcessError) and fault.failure_kind == 'cleanup'
                        first = snapshots[0]
                        self.assertEqual(first.exists(), retained)
                        if retained:
                            self.assertIn(str(first), errors.getvalue())
                            before = {path: path.read_bytes() for path in first.rglob('*') if path.is_file()}
                            self.assertIn(b'copied source', before.values())
                            self.assertIn(b'partial process diagnostics', before.values())
                            fail = False
                            clean_checkout.smoke(android=android)
                            self.assertFalse(snapshots[-1].exists())
                            self.assertEqual({path: path.read_bytes() for path in before}, before)
                            remove_disposable_path(root/'Test suite/work', first, workspace_root=root)
                            self.assertFalse(first.exists())

    def test_missing_build_dependency_fails_before_creating_a_source_snapshot(self):
        with patch.object(clean_checkout.importlib.metadata, 'version', side_effect=
                clean_checkout.importlib.metadata.PackageNotFoundError('setuptools')), \
                patch.object(clean_checkout, 'copy_source') as copied:
            with self.assertRaisesRegex(ValueError, 'needs existing setuptools'):
                clean_checkout.smoke()
            copied.assert_not_called()

    def test_absent_or_escaping_inventory_input_rejects_before_install(self):
        with tempfile.TemporaryDirectory(dir=PC.parent/'work') as name:
            root = Path(name)
            for relative in ('missing.py', '../outside.py'):
                with self.subTest(relative=relative), patch.object(clean_checkout, 'ROOT', root), \
                        patch.object(clean_checkout, 'checked', return_value=SimpleNamespace(stdout=relative+'\0')):
                    with self.assertRaisesRegex(ValueError, 'absent or not a confined regular file'):
                        clean_checkout.copy_source(root/'snapshot')

    def test_android_compile_is_offline_fresh_output_and_needs_retained_tools(self):
        with tempfile.TemporaryDirectory(dir=PC.parent/'work') as name:
            root = Path(name)
            properties = root/'StemWise/gradle/wrapper/gradle-wrapper.properties'
            properties.parent.mkdir(parents=True)
            properties.write_text('distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip', encoding='utf-8')
            (root/'StemWise/gradle/android-toolchain.properties').write_text('compileSdk=36\n', encoding='utf-8')
            sdk = root/'sdk'
            with self.assertRaisesRegex(ValueError, 'needs retained Gradle 8.13'):
                clean_checkout.android_command(root, gradle_home=root/'gradle', sdk=sdk)
            tool = root/'gradle/wrapper/dists/gradle-8.13-bin/fixture/gradle-8.13/bin'/(
                'gradle.bat' if clean_checkout.os.name == 'nt' else 'gradle')
            tool.parent.mkdir(parents=True)
            tool.write_text('fixture tool', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'SDK platform 36'):
                clean_checkout.android_command(root, gradle_home=root/'gradle', sdk=sdk)
            platform = sdk/'platforms/android-36/android.jar'
            platform.parent.mkdir(parents=True)
            platform.write_bytes(b'fixture platform')
            with patch.object(clean_checkout.shutil, 'which', return_value='java'):
                command = clean_checkout.android_command(root, gradle_home=root/'gradle', sdk=sdk)
            self.assertEqual(command[0], tool)
            self.assertIn(':app:compileDevelopmentKotlin', command)
            self.assertIn(':app:externalNativeBuildDevelopment', command)
            self.assertIn('--offline', command)
            self.assertIn('--no-build-cache', command)
            self.assertNotIn('assembleRelease', ' '.join(map(str, command)))
            self.assertFalse((root/'StemWise/app/build').exists())

    def test_process_failure_cannot_become_success(self):
        result = subprocess.CompletedProcess(['fixture'], 9, 'partial output', 'injected install refusal')
        with patch.object(clean_checkout, 'run', return_value=result), \
                self.assertRaisesRegex(RuntimeError, 'package-install exited 9.*expected 0'):
            clean_checkout.checked(['fixture'], cwd=PC, phase='package-install')
