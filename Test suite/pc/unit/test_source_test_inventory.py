"""Source inventories must either identify JUnit tests or reject unsupported syntax."""
from pathlib import Path
import json
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SUITE = Path(__file__).resolve().parents[2]


class SourceTestInventoryTest(unittest.TestCase):
    def test_host_runtime_layout_selection_preserves_configured_numerical_route(self):
        portable = runpy.run_path(str(SUITE / 'host/portable_checks.py'))
        with patch.object(sys, 'path', [str(SUITE/'pc'), str(SUITE/'pc/unit'), *sys.path]):
            from test_plan import cpu_selections, method_inventory, plan
            selections = portable['portable_test_selections']()
            prefix = 'test_runtime_layout.'
            numerical = prefix + 'RuntimeLayoutTest.test_numerical_children_use_configured_routes_without_legacy_fallback'
            host = [name for name in selections if name.startswith(prefix)]
            cpu = [name for name in cpu_selections() if name.startswith(prefix)]
            self.assertNotIn(numerical, selections)
            self.assertEqual(cpu, [numerical])
            self.assertEqual(plan([numerical])[0]['runtime'], 'main')
            inventory = {prefix + method for method in method_inventory(SUITE/'pc/unit/test_runtime_layout.py')}
            self.assertEqual(set(host) | set(cpu), inventory)
            self.assertFalse(set(host) & set(cpu))
            loaded = unittest.defaultTestLoader.loadTestsFromNames(host)
            self.assertEqual({test.id() for group in loaded for test in group}, set(host))
            self.assertEqual(set(selections) - set(host), set(portable['PORTABLE_TEST_MODULES']) - {'test_runtime_layout'})

    def test_host_group_selection_rejects_unclassified_unknown_and_empty_groups(self):
        portable = runpy.run_path(str(SUITE / 'host/portable_checks.py'))
        select = portable['portable_test_selections']
        with tempfile.TemporaryDirectory(dir=SUITE/'work') as temporary, \
                patch.object(sys, 'path', [str(SUITE/'pc'), *sys.path]), \
                patch.dict(select.__globals__, PORTABLE_TEST_MODULES=('test_fixture',),
                           PORTABLE_TEST_GROUPS={'test_fixture': 'runtimeOnly'}):
            pc = Path(temporary)
            (pc/'unit').mkdir()
            (pc/'alignment').mkdir()
            source = pc/'unit/test_fixture.py'
            ordinary = 'import unittest\nclass Check(unittest.TestCase):\n def test_cpu(self): pass\n def test_host(self): pass\n'
            source.write_text(ordinary, encoding='utf-8')
            rule = {'runtime': 'base', 'cpu': ['Check.test_cpu'], 'runtimeOnly': ['Check.test_host']}
            config = pc/'test-runtimes.json'
            config.write_text(json.dumps({'version': 1, 'modules': {'unit/test_fixture': rule}}), encoding='utf-8')
            self.assertEqual(select(pc), ['test_fixture.Check.test_host'])
            source.write_text(ordinary + ' def test_unclassified(self): pass\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'every method classified'):
                select(pc)
            source.write_text(ordinary, encoding='utf-8')
            unknown = pc/'unit/test_unknown.py'
            unknown.write_text(ordinary, encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'explicit runtime classification'):
                select(pc)
            unknown.unlink()
            with patch.dict(select.__globals__, PORTABLE_TEST_GROUPS={'test_fixture': 'unknown'}), \
                    self.assertRaisesRegex(ValueError, 'Missing portable PC test group'):
                select(pc)
            rule.update(cpu=['Check.test_cpu', 'Check.test_host'], runtimeOnly=[])
            config.write_text(json.dumps({'version': 1, 'modules': {'unit/test_fixture': rule}}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Missing portable PC test group'):
                select(pc)

    def test_audio_membership_comes_only_from_real_annotations_and_survives_renames(self):
        inventory = runpy.run_path(str(SUITE / 'source_test_inventory.py'))
        managed = inventory['is_managed_audio_test']
        optional = inventory['is_optional_acoustic_test']
        source = """package renamed.fixture
import org.junit.Test as Scenario
import app.stemwise.qualification.ManagedAudioTest as AudioSuite
import app.stemwise.qualification.OptionalAcousticTest as AcousticSetup
// app.stemwise.playback.engine. @AudioSuite @Scenario fun fake() {}
class Renamed {
    val fake = "@AudioSuite @Scenario fun stringOnly() {}"
    @Scenario fun unrelated() {}
    @AudioSuite @Scenario(timeout = 1) fun renamedLiveMethod() {}
    @AcousticSetup @Scenario @AudioSuite fun optionalProbe() {}
}
"""
        owner = 'renamed.fixture.Renamed'
        self.assertFalse(managed(owner, 'unrelated', source))
        self.assertTrue(managed(owner, 'renamedLiveMethod', source))
        self.assertTrue(managed(owner, 'optionalProbe', source))
        self.assertTrue(optional(owner, 'optionalProbe', source))
        self.assertFalse(optional(owner, 'renamedLiveMethod', source))
        self.assertFalse(managed(owner, 'fake', source))
        self.assertFalse(managed(owner, 'stringOnly', source))
        unmarked = 'package app.stemwise.playback; class Playback { @org.junit.Test public void live() {} }'
        self.assertFalse(managed('app.stemwise.playback.Playback', 'live', unmarked))

    def test_class_membership_supports_qualified_and_java_annotations(self):
        inventory = runpy.run_path(str(SUITE / 'source_test_inventory.py'))
        source = """package independent;
@app.stemwise.qualification.ManagedAudioTest
public class Fixture {
    @org.junit.Test public void first() { }
    @org.junit.Test public void second() { }
}
"""
        for method in ('first', 'second'):
            self.assertTrue(inventory['is_managed_audio_test']('independent.Fixture', method, source))
        self.assertFalse(inventory['is_optional_acoustic_test']('independent.Fixture', 'first', source))

    def test_invalid_and_duplicate_audio_membership_annotations_fail_closed(self):
        inventory = runpy.run_path(str(SUITE / 'source_test_inventory.py'))
        for annotations in (
                '@app.stemwise.qualification.OptionalAcousticTest',
                '@app.stemwise.qualification.ManagedAudioTest @app.stemwise.qualification.ManagedAudioTest'):
            source = 'package fixture; ' + annotations + ' class Fixture { @org.junit.Test public void test() {} }'
            with self.subTest(annotations=annotations), self.assertRaises(ValueError):
                inventory['is_managed_audio_test']('fixture.Fixture', 'test', source)

    def test_portable_python_check_parses_current_unignored_git_sources(self):
        portable = runpy.run_path(str(SUITE / 'host/portable_checks.py'))
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as temporary:
            root = Path(temporary)
            subprocess.run(['git', 'init', '--quiet', str(root)], check=True)
            (root / '.gitignore').write_text('scratch/\n', encoding='utf-8')
            old = root / 'old tracked.py'
            old.write_text('value = 1\n', encoding='utf-8')
            kept = root / 'kept ü.py'
            kept.write_text('value = 2\n', encoding='utf-8')
            subprocess.run(['git', 'add', '--', '.gitignore', old.name, kept.name], cwd=root, check=True)
            renamed = root / 'renamed 새 이름.py'
            old.rename(renamed)
            added = root / 'new source.py'
            added.write_text('value = 3\n', encoding='utf-8')
            scratch = root / 'scratch'
            scratch.mkdir()
            (scratch / 'invalid ignored.py').write_text('def broken(:\n', encoding='utf-8')
            check = portable['check_python_sources']
            self.assertEqual({kept.name, renamed.name, added.name}, set(check(root)))
            for source in (renamed, added):
                valid = source.read_text(encoding='utf-8')
                source.write_text('def broken(:\n', encoding='utf-8')
                with self.subTest(source=source.name), self.assertRaises(SyntaxError) as rejected:
                    check(root)
                self.assertEqual(source.name, rejected.exception.filename)
                source.write_text(valid, encoding='utf-8')

    def test_new_unit_regressions_are_in_the_actual_host_selection(self):
        inventory = runpy.run_path(str(SUITE / 'host/host_inventory.py'))
        methods = inventory['discover']()
        self.assertIn('app.stemwise.playback.StemActivationTerminalTest#'
                      'retainedOwnerCannotLeaveActivationWaitingAfterQuarantineClosureOrOutputLoss', methods)
        self.assertIn('app.stemwise.testprovider.QualificationDocumentPathsTest#'
                      'exactIdentitiesCannotAliasAnotherRootAndBothRootsAreProtected', methods)

    def test_qualified_aliased_comments_and_parameterized_annotations(self):
        inventory = runpy.run_path(str(SUITE / 'host/host_inventory.py'))
        discover = inventory['discover']
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as temporary:
            root = Path(temporary)
            discover.__globals__['SOURCE_ROOTS'] = (root,)
            (root / 'Example.kt').write_text('''package app.test
import org.junit.Test as Scenario
// @Test fun comment() {}
class Example {
    val fake = "@Test fun string() {}"
    @org.junit.Test(timeout = 5) fun qualified() {}
    @Scenario fun `alias with spaces`() {}
}
''', encoding='utf-8')
            self.assertEqual(['app.test.Example#alias with spaces',
                              'app.test.Example#qualified'], discover())

    def test_multiple_top_level_classes_keep_their_own_methods(self):
        inventory = runpy.run_path(str(SUITE / 'host/host_inventory.py'))
        discover = inventory['discover']
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as temporary:
            root = Path(temporary)
            discover.__globals__['SOURCE_ROOTS'] = (root,)
            (root / 'Example.java').write_text('''package app.test;
class One { @org.junit.Test(timeout=5) public void first() {} }
class Two { @org.junit.Test public void second() {} }
''', encoding='utf-8')
            self.assertEqual(['app.test.One#first', 'app.test.Two#second'], discover())

    def test_nested_test_is_explicitly_unsupported_instead_of_misattributed(self):
        inventory = runpy.run_path(str(SUITE / 'host/host_inventory.py'))
        discover = inventory['discover']
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as temporary:
            root = Path(temporary)
            discover.__globals__['SOURCE_ROOTS'] = (root,)
            (root / 'Example.kt').write_text('''package app.test
class Outer { class Inner { @org.junit.Test fun nested() {} } }
''', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Unsupported nested JUnit'):
                discover()
