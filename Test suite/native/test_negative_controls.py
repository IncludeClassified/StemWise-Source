"""Validate mutation result admission; these runner tests are not mutation qualification."""
import importlib.util
from contextlib import contextmanager, nullcontext
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('negative_controls', Path(__file__).with_name('run_negative_controls.py'))
controls = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controls)
from stemwise_engine import common


class NegativeControlRunnerTest(unittest.TestCase):
    def test_startup_creates_its_scratch_parent_and_cleans_up_before_copy_failure(self):
        for existing in (False, True):
            for report_kind in ('none', 'standard', 'custom'):
                with self.subTest(existing=existing, report=report_kind), tempfile.TemporaryDirectory(
                        prefix='negative-startup-', dir=controls.ROOT / 'Test suite/work') as directory:
                    checkout = Path(directory) / 'checkout'
                    scratch = checkout / 'Test suite/work'
                    if existing:
                        scratch.mkdir(parents=True)
                    report = (None if report_kind == 'none' else
                              scratch / 'audio-current/negative-controls.json' if report_kind == 'standard' else
                              Path(directory) / 'custom/report.json')
                    copied = []
                    held_leases = set()

                    @contextmanager
                    def file_lease(path, **_):
                        # This host fixture simulates Linux; real mutation runs exercise its native lock.
                        path.touch()
                        held_leases.add(path)
                        try:
                            yield
                        finally:
                            held_leases.remove(path)

                    def stop_copy(source, destination):
                        copied.append(destination)
                        self.assertEqual(scratch, destination.parent.parent)
                        lease = destination.parent / '.stemwise-worker.lock'
                        if os.name == 'nt':
                            self.assertIn(lease, held_leases)
                        else:
                            self.assertIsNotNone(common.process_lock_descriptor(lease))
                        raise RuntimeError('copy boundary reached')

                    arguments = ['negative-controls'] + (['--report', str(report)] if report else [])
                    with mock.patch.object(controls, 'ROOT', checkout), \
                            mock.patch.object(controls, 'os', SimpleNamespace(name='posix', environ=controls.os.environ)), \
                            (mock.patch.object(common, 'exclusive_process_lock', side_effect=file_lease)
                             if os.name == 'nt' else nullcontext()), \
                            mock.patch.object(controls.audio_evidence, 'source_identity', return_value='a' * 64), \
                            mock.patch.object(controls.shutil, 'which', side_effect=lambda name: '/tools/' + name), \
                            mock.patch.object(controls.shutil, 'copytree', side_effect=stop_copy), \
                            mock.patch.object(controls, 'run', return_value=subprocess.CompletedProcess([], 0, 'version\n', '')), \
                            mock.patch.object(controls.sys, 'argv', arguments), \
                            mock.patch.dict(controls.os.environ):
                        with self.assertRaisesRegex(RuntimeError, 'copy boundary reached'):
                            controls.main()
                    self.assertTrue(scratch.is_dir())
                    self.assertFalse(held_leases)
                    self.assertEqual(1, len(copied))
                    self.assertFalse(copied[0].parent.exists())
                    if report:
                        self.assertTrue(report.is_file())

    def test_only_the_exact_runtime_invariant_exit_is_admitted(self):
        assertion = 'required sample sequence'
        self.assertTrue(controls.detected(subprocess.CompletedProcess([], 1, '', 'FAIL: ' + assertion), assertion, controls.CLOSURE))
        for code, output in ((0, assertion), (-11, assertion), (1, 'compile error'), (139, assertion)):
            with self.subTest(code=code, output=output):
                self.assertFalse(controls.detected(subprocess.CompletedProcess([], code, output, ''), assertion, controls.CLOSURE))

    def test_echoed_expected_assertion_does_not_replace_the_actual_primary_failure(self):
        assertion = 'required sample sequence'
        for stdout, stderr in ((assertion, 'FAIL: unrelated failure'),
                               ('', 'diagnostic: ' + assertion + '\nFAIL: unrelated failure'),
                               ('', 'CurrentSongPcmCache test failure: ' + assertion)):
            with self.subTest(stdout=stdout, stderr=stderr):
                self.assertFalse(controls.detected(subprocess.CompletedProcess([], 1, stdout, stderr), assertion, controls.CLOSURE))

    def test_native_failure_formats_and_clear_diagnostics_are_admitted_at_the_primary_line(self):
        for name, line in (
            ('stale-control', 'Late stem mix control mailbox test failure: AE-01 stale control changed independent current endpoint'),
            ('wrong-cache-identity', 'CurrentSongPcmCache test failure: AE-07 wrong source identity exposed previous source PCM'),
            ('suffix-duplicate', 'FAIL: 192/zero/64 submits every original rendered sample exactly once without rerendering'),
            ('ignore-loop-clear', 'NativePlaybackRuntime test failure: AE-06 order=clear-pause-source-resume heldClear=0: AE-06 Clear preparation order=clear-pause-source-resume owner=1751/2 command=5 demand=1/1/1 revision=2')):
            target, assertion = controls.CASES[name][3], controls.CASES[name][-1]
            with self.subTest(name=name):
                self.assertTrue(controls.detected(subprocess.CompletedProcess([], 1, '', line), assertion, target))
                self.assertFalse(controls.detected(subprocess.CompletedProcess([], 1, line, ''), assertion, target))

    def test_clear_requires_its_actual_scenario_wrapper_and_matching_inner_order(self):
        target, assertion = controls.CASES['ignore-loop-clear'][3], controls.CASES['ignore-loop-clear'][-1]
        for held in (0, 1):
            for order in ('clear-only', 'clear-pause-source-resume', 'pause-clear-source-resume',
                          'pause-source-clear-resume', 'source-pause-clear-resume'):
                line = (f'NativePlaybackRuntime test failure: AE-06 order={order} heldClear={held}: '
                        f'AE-06 Clear preparation order={order} owner=1751/2 command=5 demand=1/1/1 revision=2')
                with self.subTest(held=held, order=order):
                    self.assertTrue(controls.detected(subprocess.CompletedProcess([], 1, '', line), assertion, target))
                    for invalid in (line.replace('heldClear=' + str(held), 'heldClear=2'),
                                    line.replace('Clear preparation order=' + order, 'Clear preparation order=' +
                                        ('clear-only' if order != 'clear-only' else 'pause-clear-source-resume')),
                                    line.replace(' owner=1751/2', ' owner=unknown'),
                                    line.replace('AE-06 order=', 'diagnostic: AE-06 order=')):
                        self.assertFalse(controls.detected(subprocess.CompletedProcess([], 1, '', invalid), assertion, target))

    def test_every_mutation_binds_an_exact_current_production_expression_and_detector(self):
        for name, (relative, before, after, target, arguments, assertion) in controls.CASES.items():
            with self.subTest(name=name):
                original = (controls.SOURCE / relative).read_text(encoding='utf-8')
                self.assertEqual(1, original.count(before))
                self.assertNotEqual(before, after)
                self.assertTrue(target.startswith('stemwise_') and assertion)

    def test_cache_starvation_admits_only_its_exact_early_clear_admission_failure(self):
        target, assertion = controls.CASES['cache-read-starves-clear'][3], controls.CASES['cache-read-starves-clear'][-1]
        line = ('NativePlaybackRuntime test failure: AE-06 order=clear-pause-source-resume heldClear=1: '
                'AE-06 Clear reaches continuation acceptance before the competing command')
        self.assertTrue(controls.detected(subprocess.CompletedProcess([], 1, '', line), assertion, target))
        for name, case in controls.CASES.items():
            if name != 'cache-read-starves-clear':
                with self.subTest(other_case=name):
                    self.assertFalse(controls.detected(subprocess.CompletedProcess([], 1, '', line), case[-1], case[3]))
        for invalid in (line.replace('heldClear=1', 'heldClear=0'),
                        line.replace('order=clear-pause-source-resume', 'order=pause-clear-source-resume'),
                        line.replace('before the competing command', 'before an unrelated command'),
                        'diagnostic: ' + line + '\nNativePlaybackRuntime test failure: unrelated failure',
                        line + '\nNativePlaybackRuntime test failure: unrelated failure'):
            with self.subTest(invalid=invalid):
                self.assertFalse(controls.detected(subprocess.CompletedProcess([], 1, '', invalid), assertion, target))
        self.assertFalse(controls.detected(subprocess.CompletedProcess([], 1, line, ''), assertion, target))
        for code in (0, -11, 124, 139):
            with self.subTest(code=code):
                self.assertFalse(controls.detected(subprocess.CompletedProcess([], code, '', line), assertion, target))

    def test_cache_starvation_final_preparation_requires_held_clear_and_matching_order(self):
        target, assertion = controls.CASES['cache-read-starves-clear'][3], controls.CASES['cache-read-starves-clear'][-1]
        line = ('NativePlaybackRuntime test failure: AE-06 order=clear-pause-source-resume heldClear=1: '
                'AE-06 Clear preparation order=clear-pause-source-resume owner=1751/2 command=5 demand=1/1/1 revision=2')
        self.assertTrue(controls.detected(subprocess.CompletedProcess([], 1, '', line), assertion, target))
        for invalid in (line.replace('heldClear=1', 'heldClear=0'),
                        line.replace('preparation order=clear-pause-source-resume', 'preparation order=clear-only'),
                        line.replace('owner=1751/2', 'owner=unknown')):
            with self.subTest(invalid=invalid):
                self.assertFalse(controls.detected(subprocess.CompletedProcess([], 1, '', invalid), assertion, target))


if __name__ == '__main__':
    unittest.main()
