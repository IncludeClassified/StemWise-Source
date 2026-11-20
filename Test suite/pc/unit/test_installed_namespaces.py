"""Installed engine/media ownership works independently of the launch directory."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from stemwise_engine.common import run_process_tree

ROOT = Path(__file__).resolve().parents[3]


class InstalledNamespacesTest(unittest.TestCase):
    def test_arbitrary_directory_uses_one_engine_identity_across_media_calls(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            code = """
import json, sys
from pathlib import Path
from stemwise_engine import common
from stemwise_media import catalogue_store
assert catalogue_store.atomic_write_bytes is common.atomic_write_bytes
assert 'common' not in sys.modules and 'catalogue_store' not in sys.modules
path = Path('catalogue.json')
catalogue_store.commit_catalogue(path.resolve(), {'rows': []})
assert catalogue_store.read_catalogue(path.resolve()) == {'rows': []}
print(json.dumps({'engine': str(Path(common.__file__).resolve()),
                 'media': str(Path(catalogue_store.__file__).resolve())}))
"""
            result = run_process_tree([sys.executable, '-E', '-B', '-c', code], cwd=directory,
                capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            locations = json.loads(result.stdout)
            self.assertEqual(Path(locations['engine']),
                ROOT / 'PC side/stemwise-playlist-builder/src/common.py')
            self.assertEqual(Path(locations['media']), ROOT / 'PC side/qobuz-comparison/catalogue_store.py')


    def test_module_control_entrypoint_runs_from_an_unrelated_directory(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            result = run_process_tree([sys.executable, '-E', '-B', '-m',
                'stemwise_engine.runtime_environment', sys.executable, '-E', '-B', '-m',
                'stemwise_engine.model_registry', '--profile', 'main', 'python'], cwd=directory,
                capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), '3.10.18')


if __name__ == '__main__':
    unittest.main()
