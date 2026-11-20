import hashlib
import importlib.util
import json
import os
from contextlib import contextmanager, nullcontext
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
BUILDER = ROOT.parent/'PC side/stemwise-playlist-builder'
from stemwise_engine import common, environment_locks as LOCKS


class EnvironmentLocksTests(unittest.TestCase):
    @contextmanager
    def _installation_mutex_boundary(self):
        held_leases = set()
        directories = set()

        @contextmanager
        def file_lease(path, **_):
            # Windows simulates only the Linux mutex; temporary-directory ownership stays real.
            path.touch()
            directories.add(path.parent)
            held_leases.add(path)
            try:
                yield
            finally:
                held_leases.remove(path)

        with (patch.object(common, 'exclusive_process_lock', side_effect=file_lease)
              if os.name == 'nt' else nullcontext()):
            try:
                yield held_leases
            finally:
                self.assertFalse(held_leases)
                for directory in directories:
                    self.assertFalse(directory.exists(), 'Installation scratch must retire on success and failure')

    def test_empty_cache_accepts_only_explicit_exact_wheel_before_any_installation(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'work') as temporary:
            builder = Path(temporary)/'PC side/builder'
            builder.mkdir(parents=True)
            lock = builder/'lock.txt'
            lock.write_text('numpy==2.2.6 \\\n    --hash=sha256:'+'a'*64+'\n', encoding='utf-8')
            supplied = Path(temporary)/'approved.whl'
            supplied.write_bytes(b'known approved wheel')
            destination = builder.parent/'model-cache/wheel.whl'
            entry = {'lock': 'lock.txt', 'sha256': hashlib.sha256(lock.read_bytes()).hexdigest(),
                'sources': [{'path': 'model-cache/wheel.whl', 'sha256': hashlib.sha256(supplied.read_bytes()).hexdigest(),
                             'environmentVariable': 'STEMWISE_ROFORMER_WHEEL'}]}
            (builder/'environment-locks.json').write_text(json.dumps({
                'schemaVersion': 1, 'pythonVersion': '3.10.18', 'environments': {'main': entry}}), encoding='utf-8')
            with patch.dict(os.environ, {'STEMWISE_ROFORMER_WHEEL': ''}):
                with self.assertRaisesRegex(ValueError, 'STEMWISE_ROFORMER_WHEEL'):
                    LOCKS.check_artifacts('main', builder)
            with patch.dict(os.environ, {'STEMWISE_ROFORMER_WHEEL': str(supplied)}):
                sources = LOCKS.check_artifacts('main', builder)
                self.assertEqual(sources, [(str(supplied.resolve()), entry['sources'][0]['sha256'])])
                self.assertFalse(destination.exists(), 'Preflight must not mutate the cache')
                LOCKS.prepare_artifacts('main', builder)
                self.assertEqual(destination.read_bytes(), b'known approved wheel')
                LOCKS.prepare_artifacts('main', builder)
                supplied.write_bytes(b'wrong wheel')
                with self.assertRaisesRegex(ValueError, 'artifact changed'):
                    LOCKS.prepare_artifacts('main', builder)
                self.assertEqual(destination.read_bytes(), b'known approved wheel')
            with patch.dict(os.environ, {'STEMWISE_ROFORMER_WHEEL': ''}):
                LOCKS.validate_plan('main', builder)

    def test_current_profiles_accept_their_actual_locked_environments(self):
        for name in ('lyrics', 'allin1'):
            with self.subTest(environment=name):
                LOCKS.validate_plan(name)

    def test_all_reviewed_dependencies_have_exact_versions_and_artifact_hashes(self):
        manifest = json.loads((BUILDER/'environment-locks.json').read_text())
        for name, record in manifest['environments'].items():
            packages = LOCKS.locked_requirements(BUILDER/record['lock'], record['sha256'])
            self.assertIn('numpy', packages, name)
            if name != 'lyrics':
                self.assertIn('torch', packages, name)

    def test_changed_transitive_lock_and_missing_retained_input_fail_before_install(self):
        work = ROOT/'work'
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as temporary:
            builder = Path(temporary)/'PC side/builder'
            builder.mkdir(parents=True)
            lock = builder/'lock.txt'
            lock.write_text('numpy==2.2.6 \\\n    --hash=sha256:'+'a'*64+'\n')
            digest = hashlib.sha256(lock.read_bytes()).hexdigest()
            entry = {'lock':'lock.txt','sha256':digest,'sources':[{'path':'missing.whl','sha256':'b'*64}]}
            manifest = {'schemaVersion':1,'pythonVersion':'3.10.18','environments':{'main':entry}}
            (builder/'environment-locks.json').write_text(json.dumps(manifest))
            calls = []
            def run(command, **kwargs):
                calls.append(command)
                return subprocess.CompletedProcess(command, 0, 'Python 3.10.18\n', '')
            with self.assertRaisesRegex(ValueError, 'Required retained artifact missing'):
                LOCKS.install('main', 'python', builder, run)
            self.assertEqual([], calls)
            lock.write_text(lock.read_text().replace('2.2.6','2.2.7'))
            calls.clear()
            with self.assertRaisesRegex(ValueError, 'Environment lock changed'):
                LOCKS.install('main', 'python', builder, run)
            self.assertEqual([], calls)

    def test_unhashed_dependency_is_rejected_even_with_matching_file_hash(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'work') as temporary:
            lock = Path(temporary)/'unhashed.txt'
            lock.write_text('numpy==2.2.6\n')
            with self.assertRaisesRegex(ValueError, 'Unhashed'):
                LOCKS.locked_requirements(lock, hashlib.sha256(lock.read_bytes()).hexdigest())

    def test_transient_cache_rename_retries_identical_hash_checked_install_only(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'work') as temporary, \
                self._installation_mutex_boundary() as held_leases:
            builder = Path(temporary)/'PC side/builder'
            builder.mkdir(parents=True)
            lock = builder/'lock.txt'
            lock.write_text('numpy==2.2.6 \\\n    --hash=sha256:'+'a'*64+'\n')
            entry = {'lock':'lock.txt','sha256':hashlib.sha256(lock.read_bytes()).hexdigest(),'sources':[]}
            (builder/'environment-locks.json').write_text(json.dumps(
                {'schemaVersion':1,'pythonVersion':'3.10.18','environments':{'main':entry}}))
            for diagnostic, expected_attempts in [
                    ('failed to rename file from cache/.tmp to cache/archive-v0/entry: Permission denied (os error 13)', 2),
                    ('Hash mismatch for numpy', 1),
                    ('Permission denied opening the target environment', 1)]:
                attempts = []
                def run(command, **kwargs):
                    if '--version' in command:
                        return subprocess.CompletedProcess(command, 0, 'Python 3.10.18\n', '')
                    if 'install' in command and '--require-hashes' in command:
                        if os.name == 'nt':
                            self.assertIn(Path(kwargs['env']['TMPDIR']) / common.WORKER_DIRECTORY_LOCK, held_leases)
                        attempts.append(command)
                        if len(attempts) == 1:
                            raise subprocess.CalledProcessError(1, command, stderr=diagnostic)
                    return subprocess.CompletedProcess(command, 0, '', '')
                if expected_attempts == 2:
                    LOCKS.install('main', 'python', builder, run)
                    self.assertEqual(attempts[0], attempts[1])
                    self.assertIn('--require-hashes', attempts[0])
                else:
                    with self.assertRaises(subprocess.CalledProcessError):
                        LOCKS.install('main', 'python', builder, run)
                self.assertEqual(expected_attempts, len(attempts))

    def test_natten_build_runs_after_complete_locked_torch_runtime(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'work') as temporary, \
                self._installation_mutex_boundary() as held_leases:
            builder = Path(temporary)/'PC side/builder'
            builder.mkdir(parents=True)
            packages = ('numpy','setuptools','torch','typing-extensions','jinja2','natten')
            lock = builder/'lock.txt'
            lock.write_text(''.join(name+'==1.0 \\\n    --hash=sha256:'+'a'*64+'\n' for name in packages))
            entry = {'lock':'lock.txt','sha256':hashlib.sha256(lock.read_bytes()).hexdigest(),'sources':[]}
            (builder/'environment-locks.json').write_text(json.dumps(
                {'schemaVersion':1,'pythonVersion':'3.10.18','environments':{'allin1':entry}}))
            installed = set()
            def run(command, **kwargs):
                if '--version' in command:
                    return subprocess.CompletedProcess(command, 0, 'Python 3.10.18\n', '')
                if 'install' in command and os.name == 'nt':
                    self.assertIn(Path(kwargs['env']['TMPDIR']) / common.WORKER_DIRECTORY_LOCK, held_leases)
                if '--editable' in command:
                    self.assertEqual(set(packages), installed)
                    self.assertEqual(str(builder.parent), command[command.index('--editable')+1])
                    self.assertIn('--no-deps', command)
                    self.assertIn('--no-build-isolation', command)
                elif 'install' in command:
                    current = set(LOCKS.locked_requirements(
                        Path(command[command.index('-r')+1]),
                        hashlib.sha256(Path(command[command.index('-r')+1]).read_bytes()).hexdigest()))
                    if 'natten' in current:
                        self.assertLessEqual({'torch','numpy','typing-extensions','jinja2'}, installed)
                    installed.update(current)
                return subprocess.CompletedProcess(command, 0, '', '')
            LOCKS.install('allin1','python',builder,run)
            self.assertEqual(set(packages), installed)

    def test_cache_retry_is_bounded_and_never_retries_target_promotion(self):
        for diagnostic, count in [
                ('failed to rename file from cache/.tmp to cache/archive-v0/entry: Permission denied (os error 13)', 3),
                ('failed to rename file from venv/.tmp to venv/site-packages/package: Permission denied (os error 13)', 1)]:
            attempts = []
            def run(command, **kwargs):
                attempts.append(command)
                raise subprocess.CalledProcessError(1, command, stderr=diagnostic)
            with patch.object(LOCKS.time, 'sleep'), self.assertRaises(subprocess.CalledProcessError):
                LOCKS.install_command(['uv','pip','install','--require-hashes'], {}, 60, run)
            self.assertEqual(count, len(attempts))


if __name__ == '__main__':
    unittest.main()
