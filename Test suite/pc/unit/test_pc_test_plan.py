"""The real planner discovers CPU additions and refuses unclassified work."""
import json
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
import runpy
import io
from contextlib import redirect_stderr
from unittest.mock import patch

PC = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PC))
from test_plan import classifications, cpu_selections, method_inventory, plan


class PcTestPlanTest(unittest.TestCase):
    def test_module_batches_execute_every_member_and_preserve_failures_and_skips(self):
        with tempfile.TemporaryDirectory(dir=PC.parent/'work') as name:
            pc = Path(name)
            (pc/'unit').mkdir()
            (pc/'alignment').mkdir()
            (pc/'unit/test_fixture.py').write_text('''import unittest
from pathlib import Path
class Check(unittest.TestCase):
 def record(self, name):
  with Path('calls').open('a', encoding='utf-8') as output: output.write(name+'\\n')
 def test_cpu(self): self.record('cpu')
 def test_new_runtime(self): self.record('new_runtime')
 def test_failure(self):
  self.record('failure')
  self.fail('batched failure remains active')
 def test_later(self): self.record('later')
 def test_skip(self):
  self.record('skip')
  self.skipTest('batched skip cannot qualify')
''', encoding='utf-8')
            (pc/'test-runtimes.json').write_text(json.dumps({'version': 1, 'modules': {
                'unit/test_fixture': {'runtime': 'lyrics', 'cpu': ['Check.test_cpu'], 'batchSize': 2,
                    'runtimeOnly': ['Check.test_new_runtime', 'Check.test_failure', 'Check.test_later', 'Check.test_skip']}}}),
                encoding='utf-8')
            groups = plan(pc=pc, per_module=True)
            self.assertEqual([len(group['selections']) for group in groups], [2, 2, 1])
            results = [subprocess.run([sys.executable, '-B', str(PC/'run_tests.py'), '--directory',
                str(pc/'unit'), *group['selections']], cwd=pc, capture_output=True, text=True, timeout=15)
                for group in groups]
            self.assertEqual([result.returncode for result in results], [0, 1, 1])
            self.assertIn('batched failure remains active', results[1].stderr)
            self.assertIn('PC qualification rejected: 0 executed, 1 skipped', results[2].stderr)
            self.assertEqual((pc/'calls').read_text(encoding='utf-8').splitlines(),
                ['cpu', 'new_runtime', 'failure', 'later', 'skip'])
            self.assertEqual(cpu_selections(pc), ['test_fixture.Check.test_cpu'])
            self.assertEqual(plan(pc=pc)[0]['selections'], ['test_fixture'])
            focused = ['test_fixture.Check.test_new_runtime', 'test_fixture.Check.test_cpu']
            self.assertEqual(plan(focused, pc)[0]['selections'], focused)
            self.assertEqual([group['selections'] for group in plan(focused, pc, per_module=True)],
                [[focused[0]], [focused[1]]])

    def test_batch_configuration_refuses_unsupported_inheritance_and_discovery(self):
        with tempfile.TemporaryDirectory(dir=PC.parent/'work') as name:
            pc = Path(name)
            (pc/'unit').mkdir()
            (pc/'alignment').mkdir()
            source = pc/'unit/test_fixture.py'
            ordinary = 'import unittest\nclass Check(unittest.TestCase):\n def test_one(self): pass\n'
            source.write_text(ordinary, encoding='utf-8')
            config = pc/'test-runtimes.json'
            rule = {'runtime': 'base', 'cpu': 'all', 'batchSize': 1}
            def save():
                config.write_text(json.dumps({'version': 1, 'modules': {'unit/test_fixture': rule}}), encoding='utf-8')
            for size in (True, 0, -1, 1.5, '2', None):
                with self.subTest(size=size):
                    rule['batchSize'] = size
                    save()
                    with self.assertRaisesRegex(ValueError, 'Invalid PC batch size'):
                        plan(pc=pc, per_module=True)
            rule['batchSize'] = 1
            save()
            for unsupported in (
                    ordinary + 'class Inherited(Check):\n pass\n',
                    ordinary.replace('unittest.TestCase', 'ImportedCase'),
                    ordinary + 'def load_tests(loader, tests, pattern): return tests\n',
                    ordinary.replace('class Check', '@decorate\nclass Check')):
                with self.subTest(source=unsupported):
                    source.write_text(unsupported, encoding='utf-8')
                    with self.assertRaisesRegex(ValueError, 'flat unittest.TestCase'):
                        plan(pc=pc, per_module=True)

    def test_current_batches_cover_full_inventory_and_preserve_other_module_routes(self):
        rules = classifications()
        ordinary = plan()
        complete = plan(per_module=True)
        available = 'test_available_completion'
        batches = [group for group in complete if group['selections'][0].split('.')[0] == available]
        expected = [available + '.' + method for method in method_inventory(PC/'alignment'/f'{available}.py')]
        selected = [method for group in batches for method in group['selections']]
        self.assertEqual(selected, expected)
        self.assertEqual(len(selected), len(set(selected)))
        cli = subprocess.run([sys.executable, '-B', str(PC/'test_plan.py'), '--per-module', available],
                             capture_output=True, text=True, timeout=15)
        self.assertEqual(cli.returncode, 0, cli.stderr)
        self.assertEqual(json.loads(cli.stdout), batches)
        self.assertTrue(all(len(group['selections']) <= 20 and group['runtime'] == 'lyrics'
                            and group['directory'] == 'alignment' for group in batches))
        self.assertTrue(set(available + '.' + method for method in rules['alignment/' + available]['runtimeOnly'])
                        <= set(selected))
        expected_other = [(group['directory'], group['runtime'], selection) for group in ordinary
                          for selection in group['selections'] if selection != available]
        actual_other = [(group['directory'], group['runtime'], group['selections'][0]) for group in complete
                        if group['selections'][0].split('.')[0] != available]
        self.assertEqual(actual_other, expected_other)

    def test_cpu_failure_stops_before_later_work_and_never_returns_success(self):
        workflow = runpy.run_path(str(PC/'cpu_workflow_checks.py'))
        called = []
        def first():
            called.append('first')
            raise AssertionError('actual CPU fixture failure')
        def second():
            called.append('second')
        suite = unittest.TestSuite([unittest.FunctionTestCase(first), unittest.FunctionTestCase(second)])
        with patch.dict(workflow['main'].__globals__, cpu_selections=lambda: ['fixture']), \
                patch.object(unittest.defaultTestLoader, 'loadTestsFromNames', return_value=suite), \
                patch.object(subprocess, 'run'), redirect_stderr(io.StringIO()) as output:
            self.assertEqual(workflow['main'](), 1)
        self.assertEqual(called, ['first'])
        self.assertIn('actual CPU fixture failure', output.getvalue())

    def test_overlapping_selectors_execute_the_covered_failure_once(self):
        with tempfile.TemporaryDirectory(dir=PC.parent/'work') as name:
            pc = Path(name)
            (pc/'unit').mkdir()
            (pc/'alignment').mkdir()
            (pc/'unit/test_fixture.py').write_text('''import unittest
from pathlib import Path
class Check(unittest.TestCase):
 def test_failure(self):
  path=Path('calls')
  path.write_text(path.read_text()+'x' if path.exists() else 'x')
  self.fail('covered failure remains active')
''', encoding='utf-8')
            (pc/'test-runtimes.json').write_text(json.dumps({'version': 1, 'modules': {
                'unit/test_fixture': {'runtime': 'base', 'cpu': 'all'}}}), encoding='utf-8')
            groups = plan(['test_fixture.Check.test_failure', 'test_fixture.Check',
                           'test_fixture', 'test_fixture'], pc)
            self.assertEqual(groups[0]['selections'], ['test_fixture'])
            result = subprocess.run([sys.executable, '-B', str(PC/'run_tests.py'), '--directory',
                str(pc/'unit'), *groups[0]['selections']], cwd=pc,
                capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 1)
            self.assertIn('covered failure remains active', result.stderr)
            self.assertEqual((pc/'calls').read_text(), 'x')

    def test_current_inventory_is_complete_and_shared_with_runtime_routing(self):
        rules = classifications()
        cpu = cpu_selections()
        self.assertNotIn('test_qualification_completion', cpu)
        self.assertIn('test_bridge_song_dispatch', cpu)
        for module in ('test_pc_runtime_bootstrap', 'test_powershell_fixture_ownership'):
            self.assertEqual(rules['unit/' + module], {'runtime': 'windows', 'cpu': 'none'})
            self.assertNotIn(module, cpu)
        routed = {selection: group for group in plan() for selection in group['selections']}
        for module, rule in rules.items():
            group = routed[module.split('/')[1]]
            self.assertEqual(group['directory'], module.split('/')[0])
            self.assertEqual(group['runtime'], {'base': 'main', 'lyrics': 'lyrics',
                'analysis': 'allin1', 'windows': 'windows'}[rule['runtime']])

    def test_new_cpu_methods_run_and_mixed_or_unknown_additions_fail_closed(self):
        with tempfile.TemporaryDirectory(dir=PC.parent / 'work') as directory:
            pc = Path(directory)
            (pc / 'unit').mkdir()
            (pc / 'alignment').mkdir()
            source = pc / 'unit/test_fixture.py'
            source.write_text("raise AssertionError('must never import during planning')\n"
                "class Check:\n def test_one(self): pass\n", encoding='utf-8')
            config = pc / 'test-runtimes.json'
            rule = {'runtime': 'base', 'cpu': 'all'}
            def save():
                config.write_text(json.dumps({'version': 1, 'modules': {'unit/test_fixture': rule}}), encoding='utf-8')
            save()
            self.assertEqual(cpu_selections(pc), ['test_fixture'])
            with source.open('a', encoding='utf-8') as output:
                output.write(' def test_new(self): pass\n')
            self.assertEqual(cpu_selections(pc), ['test_fixture'])
            source.write_text('import unittest\nclass Check(unittest.TestCase):\n'
                ' def test_one(self): pass\n def test_new(self): self.fail("new CPU finding")\n', encoding='utf-8')
            result = subprocess.run([sys.executable, '-B', str(PC / 'run_tests.py'), '--directory',
                str(pc / 'unit'), *cpu_selections(pc)], capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 1)
            self.assertIn('new CPU finding', result.stderr)
            self.assertIn('Ran 2 tests', result.stderr)
            rule.update(cpu=['Check.test_one'], runtimeOnly=[])
            save()
            with self.assertRaisesRegex(ValueError, 'every method'):
                cpu_selections(pc)
            rule['runtimeOnly'] = ['Check.test_new']
            save()
            self.assertEqual(cpu_selections(pc), ['test_fixture.Check.test_one'])
            rule.update(runtime='windows', cpu='all')
            rule.pop('runtimeOnly')
            save()
            with self.assertRaisesRegex(ValueError, 'Windows checks cannot enter'):
                cpu_selections(pc)
            rule['cpu'] = 'none'
            save()
            self.assertEqual(cpu_selections(pc), [])
            self.assertEqual(plan(['test_fixture.Check.test_one'], pc), [
                {'directory': 'unit', 'runtime': 'windows', 'selections': ['test_fixture.Check.test_one']}])
            config.write_text(json.dumps({'version': True, 'modules': {'unit/test_fixture': rule}}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Unsupported'):
                classifications(pc)
            save()
            (pc / 'alignment/test_unknown.py').write_text('class Check:\n def test_one(self): pass\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'explicit runtime'):
                plan(['test_fixture'], pc)
            (pc / 'alignment/test_unknown.py').unlink()
            (pc / 'alignment/test_fixture.py').write_text('class Check:\n def test_one(self): pass\n', encoding='utf-8')
            config.write_text(json.dumps({'version': 1, 'modules': {
                'unit/test_fixture': rule, 'alignment/test_fixture': {'runtime': 'lyrics', 'cpu': 'none'}}}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Ambiguous'):
                cpu_selections(pc)
