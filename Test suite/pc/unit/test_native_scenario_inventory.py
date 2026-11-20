"""Inventory focused dispatch registration, excluding cross-translation-unit support hooks."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]


def unregistered(source, registry):
    source = re.sub(r'//[^\n]*|/\*.*?\*/', '', source, flags=re.S)
    declared = set(re.findall(r'^STEMWISE_FOCUSED_CASE\("(--[a-z0-9-]+)"(?:,|\))', registry, re.M))
    dispatched = set(re.findall(r'std::string_view\(arguments\[1\]\)\s*==\s*"(--[a-z0-9-]+)"', source))
    return sorted(selector for selector in dispatched - declared
                  if selector != "--list-cases" and not selector.startswith("--benchmark-"))


def unreferenced(source):
    # Only anonymous-namespace functions are file-local scenarios. Named cache_test hooks
    # have external declarations and callers in the production resource translation unit.
    source = source[source.index('namespace {'):]
    source = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"', '', source, flags=re.S)
    return [name for name in re.findall(r'^(?:\[\[maybe_unused\]\]\s*)?void\s+(\w+)\(\)', source, re.M)
            if len(re.findall(r'\b' + re.escape(name) + r'\b', source)) == 1]


class NativeScenarioInventoryTest(unittest.TestCase):
    def test_runtime_has_no_unreferenced_scenarios(self):
        source = ROOT / 'Test suite/native/NativePlaybackRuntimeTest.cpp'
        registry = source.with_name('NativePlaybackRuntimeCases.def')
        text = source.read_text(encoding='utf-8')
        registry_text = registry.read_text(encoding='utf-8')
        self.assertEqual([], unregistered(text, registry_text))
        for support in sorted(source.parent.glob('NativePlaybackRuntime*.inc')):
            text += '\n' + support.read_text(encoding='utf-8')
        text += '\n' + registry_text
        self.assertEqual([], unreferenced(text))

    def test_normal_plan_rejects_aggregate_and_duplicate_execution(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('native_cases', ROOT/'Test suite/native/verify_native_cases.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        registry = 'STEMWISE_FOCUSED_CASE("--present", [] { scenario(); })'
        command = {'command': ['runtime.exe', '--present']}
        self.assertEqual([], module.missing_cases([command], registry, 'runtime'))
        with self.assertRaisesRegex(RuntimeError, 'Duplicate'):
            module.missing_cases([command, command], registry, 'runtime')
        with self.assertRaisesRegex(RuntimeError, 'Aggregate'):
            module.missing_cases([command, {'command': ['runtime.exe']}], registry, 'runtime')

    def test_sanitizers_consume_disjoint_registered_commands(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('sanitizers', ROOT/'Test suite/native/run_sanitizers.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        target = 'stemwise_native_playback_runtime_test'
        manifest = {'tests': [
            {'name': 'runtime_cache', 'command': [target, '--cache']},
            {'name': 'runtime_handoff', 'command': [target, '--handoff']},
            {'name': 'unbuilt', 'command': ['other']},
        ]}
        self.assertEqual(('runtime_cache', 'runtime_handoff'), module.selected_tests(manifest, (target,), 'memory'))
        with self.assertRaisesRegex(RuntimeError, 'absent'):
            module.selected_tests(manifest, (target, 'missing'), 'memory')
        manifest['tests'].append({'name': target, 'command': [target]})
        with self.assertRaisesRegex(RuntimeError, 'Aggregate'):
            module.selected_tests(manifest, (target,), 'memory')

    def test_memory_sanitizers_require_actual_managed_loop_selector(self):
        import runpy
        selected = runpy.run_path(str(ROOT/'Test suite/native/run_sanitizers.py'))['selected_tests']
        target = 'stemwise_native_playback_pcm_closure_test'
        manifest = {'tests': [{'name': 'managed', 'command': [target, '--managed-loop-continuity']},
                              {'name': target, 'command': [target]}]}
        self.assertEqual(('managed',), selected(manifest, (target,), 'memory'))
        manifest['tests'].pop(0)
        with self.assertRaisesRegex(RuntimeError, 'absent'):
            selected(manifest, (target,), 'memory')

    def test_comments_and_strings_cannot_register_a_scenario(self):
        self.assertEqual(['missing'], unreferenced('''
namespace cache_test { void beforePrepareLanes() {} }
namespace {
[[maybe_unused]] void missing() {}
// missing();
void called() {}
int main() { called(); puts("missing()"); }
'''))

    def test_pointer_and_lambda_registrations_reference_scenarios(self):
        self.assertEqual(['unused'], unreferenced('''
namespace {
void pointer() {}
void lambda() {}
void unused() {}
STEMWISE_FOCUSED_CASE("--pointer", pointer)
STEMWISE_FOCUSED_CASE("--lambda", [] { lambda(); })
'''))

    def test_focused_dispatch_must_be_registered(self):
        source = '''int main(int argc, char **arguments) {
if (std::string_view(arguments[1]) == "--missing") { missing(); }
if (std::string_view(arguments[1]) == "--present") { present(); }
}'''
        self.assertEqual(['--missing'], unregistered(source, 'STEMWISE_FOCUSED_CASE("--present")'))
        self.assertEqual(['--missing'], unregistered(source, '''STEMWISE_FOCUSED_CASE("--present")
// STEMWISE_FOCUSED_CASE("--missing")
const char *hint = "STEMWISE_FOCUSED_CASE(\\"--missing\\")";
'''))


if __name__ == '__main__':
    unittest.main()
