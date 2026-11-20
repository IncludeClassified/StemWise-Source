"""Qualification binds executable inputs, including newly added compiled files."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'PC side/stemwise-playlist-builder/src/source_inputs.py'


class SourceInputsTest(unittest.TestCase):
    def test_pc_only_inputs_do_not_require_android_tests_or_guide(self):
        from stemwise_engine import source_inputs as module
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            for relative in (*[p for p in module.TREES if p.startswith('PC side/')],
                             'PC side/qobuz-comparison', 'StemWise/contracts/enrichment'):
                (root/relative).mkdir(parents=True, exist_ok=True)
            required = [p for p in module.FILES if p.startswith('PC side/')]
            for relative in required:
                path = root/relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'PC producer input')
            schema = root/'StemWise/contracts/enrichment/current.json'
            schema.write_bytes(b'{}')
            self.assertEqual(set(module.source_files(root, pc_only=True)),
                {root/p for p in required}|{schema})
            self.assertFalse((root/'Test suite').exists())
            self.assertFalse((root/'StemWise/app').exists())

    def test_current_manifest_includes_previously_omitted_execution_inputs(self):
        from stemwise_engine import source_inputs as module
        selected = {p.relative_to(ROOT).as_posix() for p in module.source_files(ROOT)}
        for relative in (
            'SEND_TO_AGENTS.md',
            'StemWise/third_party/phone-pc-bridge/android/phone-pc-bridge.aar',
            'StemWise/third_party/phone-pc-bridge/android/phone-pc-bridge.build.json',
            'StemWise/third_party/phone-pc-bridge/source/bridge-core/go.mod',
            'Test suite/source_test_inventory.py',
            'PC side/qobuz-comparison/compare.py',
            'PC side/runtime-layout.json',
            'PC side/pyproject.toml',
            'PC side/bootstrap-requirements.txt',
            'PC side/stemwise-playlist-builder/environment-locks.json',
            'PC side/stemwise-playlist-builder/future-songs/policy/lyrics-policy.json',
            'StemWise/audio-engine/src/main/cpp/CMakeLists.txt',
            'StemWise/audio-engine/build.gradle.kts',
            'StemWise/audio-engine/gradle.lockfile',
            'StemWise/audio-engine/consumer-rules.pro',
            'StemWise/app/src/main/cpp/CMakeLists.txt',
            'StemWise/app/src/main/java/app/stemwise/work/TaskWorkGate.kt',
            'StemWise/audio-engine/src/main/cpp/NativeAudioOutputJni.inc',
            'Test suite/native/NativePlaybackRuntimeCases.def',
            'StemWise/build-logic/qualification.gradle.kts',
            'StemWise/tools/native_packet_schema.py',
            'StemWise/tools/generate-contract-limits.py',
            'StemWise/design-system/stemwise-icons.json',
            'StemWise/third_party/phone-pc-bridge/source/test-suite/Run-Go.ps1',
            'PC side/stemwise-playlist-builder/WorkerJob.cs',
        ):
            self.assertTrue(relative in selected, f"Missing executable input: {relative}")
        self.assertTrue(any(p.endswith('.c') for p in selected))
        self.assertTrue(any('/locks/' in p for p in selected))
        self.assertFalse(any(p.startswith(('Test suite/work/', 'Test suite/evidence/')) or
                             (p.endswith('.md') and p != 'SEND_TO_AGENTS.md') for p in selected))

    def test_design_and_bridge_tests_are_inputs_but_sdk_staging_is_not(self):
        from stemwise_engine import source_inputs as module
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            for relative in (*module.TREES, 'PC side/qobuz-comparison'):
                (root/relative).mkdir(parents=True, exist_ok=True)
            for relative in module.FILES:
                path = root/relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture input')
            added = (
                'StemWise/design-system/extra.svg',
                'StemWise/third_party/phone-pc-bridge/source/test-suite/bridge-core/mobile/current_test.go',
            )
            generated = (
                'StemWise/third_party/phone-pc-bridge/source/bridge-core/.work/sdk/generated.go',
                'StemWise/third_party/phone-pc-bridge/source/test-suite/.work/go-stage/generated.go',
            )
            before = set(module.source_files(root))
            for relative in added + generated:
                path = root/relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'current input')
            self.assertEqual(set(module.source_files(root)) - before, {root/p for p in added})
            (root/added[0]).unlink()
            self.assertEqual(set(module.source_files(root)) - before, {root/added[1]})

    def test_cli_and_current_discovery_have_no_old_apk_prerequisite(self):
        from stemwise_engine import source_inputs as module
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            for relative in (*module.TREES, 'PC side/qobuz-comparison'):
                (root/relative).mkdir(parents=True, exist_ok=True)
            for relative in module.FILES:
                path = root/relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture input')
            current = root/'StemWise/app/src/current-source.kt'
            current.write_text('current executable input', encoding='utf-8')
            completed = subprocess.run([sys.executable, '-B', '-m', 'stemwise_engine.source_inputs', '--root', str(root)],
                                       capture_output=True, text=True, check=True, timeout=15)
            paths = {Path(p) for p in json.loads(completed.stdout)}
            self.assertEqual(paths, {root/relative for relative in module.FILES} | {current})
            self.assertTrue(all(p.is_file() for p in paths))
            self.assertFalse((root/'StemWise/.stemwise/test-fixtures').exists())

    def test_missing_packaged_aar_or_guide_rejects_the_snapshot(self):
        from stemwise_engine import source_inputs as module
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            for relative in (*module.TREES, 'PC side/qobuz-comparison'):
                (root/relative).mkdir(parents=True, exist_ok=True)
            for relative in module.FILES:
                path = root/relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture input')
            for relative in ('SEND_TO_AGENTS.md', 'StemWise/third_party/phone-pc-bridge/android/phone-pc-bridge.aar'):
                path = root/relative
                path.unlink()
                with self.subTest(missing=relative), self.assertRaises(FileNotFoundError):
                    module.source_files(root)
                path.write_bytes(b'fixture input')

    def test_missing_tree_fails_instead_of_returning_partial_identity(self):
        from stemwise_engine import source_inputs as module
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            with self.assertRaises(FileNotFoundError):
                module.source_files(Path(name))
