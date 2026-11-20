import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('ci_changes', ROOT / 'Test suite/host/ci_changes.py')
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class CiChangesTest(unittest.TestCase):
    def test_shared_dependency_lock_and_linux_supervisor_select_their_consumers(self):
        self.assertTrue(all(ci.affected(['Test suite/python-checks-requirements.txt']).values()))
        for path in ('PC side/stemwise-playlist-builder/src/common.py',
                     'Test suite/pc/unit/test_owned_linux_process.py'):
            self.assertTrue(ci.affected([path])['native'], path)

    def test_real_rename_to_documentation_retains_source_gate_selection(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL).decode().strip()
            git('init'); git('config', 'user.email', 'fixture@example.invalid'); git('config', 'user.name', 'Fixture')
            source = root / 'StemWise/audio-engine/src/main/cpp/Runtime.cpp'
            source.parent.mkdir(parents=True)
            source.write_text('int fixture() { return 0; }\n', encoding='utf-8')
            git('add', '.'); git('commit', '-m', 'base')
            base = git('rev-parse', 'HEAD')
            source.rename(root / 'notes.md')
            git('add', '-A'); git('commit', '-m', 'rename')
            paths = ci.changed_paths(root, base, git('rev-parse', 'HEAD'))
            self.assertIn(source.relative_to(root).as_posix(), paths)
            self.assertTrue(ci.affected(paths)['native'])

    def test_each_subsystem_selects_its_compile_and_regression_gates(self):
        for path, required in (
            ('PC side/stemwise-playlist-builder/src/common.py', {'pc-contracts', 'windows-ownership', 'native'}),
            ('StemWise/audio-engine/src/main/cpp/Runtime.cpp', {'native', 'android', 'audio'}),
            ('StemWise/app/src/main/cpp/network/WifiProcessBinding.c', {'native', 'android', 'audio'}),
            ('StemWise/app/src/main/java/app/stemwise/MainActivity.kt', {'android', 'audio'}),
            ('StemWise/third_party/phone-pc-bridge/source/bridge-core/mobile/files.go', {'bridge-core', 'android', 'windows-ownership'}),
            ('StemWise/contracts/enrichment/song-enrichment-v2.schema.json', {'pc-contracts', 'native', 'android', 'audio'}),
            ('Test suite/host/OwnedProcess.cs', set(ci.LANES)),
        ):
            with self.subTest(path=path):
                self.assertEqual(required, {name for name, enabled in ci.affected([path]).items() if enabled})

    def test_docs_only_and_shared_guide_follow_their_actual_consumers(self):
        self.assertFalse(any(ci.affected(['STEMWISE_DOCUMENTATION.md', 'PC side/README.md']).values()))
        self.assertEqual({'pc-contracts', 'android'}, {name for name, enabled in ci.affected(['SEND_TO_AGENTS.md']).items() if enabled})

    def test_shared_runner_inventory_waveform_and_catalogue_route_to_real_consumers(self):
        for path in ('Test suite/Run-Tester.ps1', 'Test suite/source_test_inventory.py'):
            self.assertTrue(all(ci.affected([path]).values()), path)
        waveform = ci.affected(['StemWise/tools/regenerate-golden-waveform.py'])
        self.assertTrue(waveform['pc-contracts'] and waveform['android'])
        self.assertFalse(ci.affected(['PC side/qobuz-comparison/catalogue.json'])['audio'])

    def test_real_git_diff_keeps_all_pushed_commits_and_unknown_base_fails_open(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL).decode().strip()
            git('init')
            git('config', 'user.email', 'fixture@example.invalid')
            git('config', 'user.name', 'Fixture')
            (root / 'README.md').write_text('fixture')
            git('add', '--', 'README.md')
            git('commit', '-m', 'base')
            base = git('rev-parse', 'HEAD')
            (root / 'source.py').write_text('broken fixture input')
            git('add', '--', 'source.py')
            git('commit', '-m', 'code')
            (root / 'README.md').write_text('docs')
            git('add', '--', 'README.md')
            git('commit', '-m', 'docs')
            self.assertEqual({'README.md', 'source.py'}, set(ci.changed_paths(root, base, git('rev-parse', 'HEAD'))))
            self.assertIsNone(ci.changed_paths(root, '0' * 40, git('rev-parse', 'HEAD')))
