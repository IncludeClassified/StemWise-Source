"""Real hash-enforcement check with tiny local wheels; no network or owner environment mutation."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[3]
UV = ROOT/'PC side/stemwise-playlist-builder/.tools/uv'


@unittest.skipUnless(os.name == 'posix' and UV.is_file(), 'Requires the supported project-local WSL uv runtime')
class EnvironmentInstallationTests(unittest.TestCase):
    def test_changed_transitive_wheel_rejects_before_installing_any_package(self):
        with tempfile.TemporaryDirectory(prefix='locked-wheel-', dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            environment = dict(os.environ, UV_CACHE_DIR=str(root/'cache'))
            def run(*arguments):
                return subprocess.run([str(UV), *map(str, arguments)], capture_output=True, text=True,
                                      timeout=30, env=environment)
            def wheel(name, body, requires=''):
                normalized = name.replace('-', '_')
                target = root/f'{normalized}-1.0-py3-none-any.whl'
                info = normalized+'-1.0.dist-info/'
                with zipfile.ZipFile(target, 'w') as archive:
                    archive.writestr(normalized+'/__init__.py', body)
                    archive.writestr(info+'METADATA', 'Metadata-Version: 2.1\nName: '+name+'\nVersion: 1.0\n'+requires+'\n')
                    archive.writestr(info+'WHEEL', 'Wheel-Version: 1.0\nGenerator: fixture\nRoot-Is-Purelib: true\nTag: py3-none-any\n')
                    archive.writestr(info+'RECORD', '')
                return target
            child = wheel('stemwise-fixture-child', 'VALUE = 17\n')
            parent = wheel('stemwise-fixture-parent', 'from stemwise_fixture_child import VALUE\n',
                           'Requires-Dist: stemwise-fixture-child==1.0\n')
            lock = root/'requirements.txt'
            lock.write_text(''.join(path.name.split('-1.0-')[0].replace('_','-')+'==1.0 --hash=sha256:'+
                                    hashlib.sha256(path.read_bytes()).hexdigest()+'\n' for path in (child,parent)))
            for tampered in (False, True):
                venv = root/('changed' if tampered else 'healthy')
                # Force a new artifact acquisition; a previously verified cached wheel is still valid.
                environment['UV_CACHE_DIR'] = str(root/('changed-cache' if tampered else 'healthy-cache'))
                created = run('venv', '--python', sys.executable, venv)
                self.assertEqual(0, created.returncode, created.stderr)
                if tampered:
                    with child.open('ab') as stream:
                        stream.write(b'changed artifact bytes')
                result = run('pip','install','--python',venv/'bin/python','--require-hashes',
                             '--no-deps','--no-index','--find-links',root,'-r',lock)
                if tampered:
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('hash', result.stderr.lower())
                    probe = 'import importlib.util; assert importlib.util.find_spec("stemwise_fixture_parent") is None; assert importlib.util.find_spec("stemwise_fixture_child") is None'
                else:
                    self.assertEqual(0, result.returncode, result.stderr)
                    probe = 'import stemwise_fixture_parent; assert stemwise_fixture_parent.VALUE == 17'
                verified = subprocess.run([str(venv/'bin/python'),'-c',probe],capture_output=True,text=True,timeout=15)
                self.assertEqual(0, verified.returncode, verified.stderr)


if __name__ == '__main__':
    unittest.main()
