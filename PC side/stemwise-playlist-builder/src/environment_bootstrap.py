"""Build a replacement environment before switching the current path."""
import argparse
import hashlib
from pathlib import Path
import shutil
import sys
import tempfile

from stemwise_engine.common import (WorkerRetirementError, atomic_write_json, exclusive_process_lock,
    read_bounded_json, run_process_tree, worker_directory_cleanup)
from stemwise_engine.environment_locks import install, validate_plan
from stemwise_engine.runtime_environment import environment_lease, environment_path, environment_python


def _environment_identity(path):
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_dir():
        raise ValueError('Environment identity is not a directory')
    info = path.stat()
    return {'device': info.st_dev, 'inode': info.st_ino,
            'configSha256': hashlib.sha256((path/'pyvenv.cfg').read_bytes()).hexdigest()}


def _ready(name, python, builder, run):
    import os
    run([str(python), '-B', '-m', 'stemwise_engine.environment_probe', 'readiness', name],
        check=True, timeout=120, capture_output=True, text=True,
        env={**os.environ, 'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1'})


def _finish_promotion(name, destination, scratch, journal, builder, run):
    record = read_bounded_json(journal, max_bytes=4096, root=builder)
    if record['phase'] == 'prepared':
        python = environment_python(name, builder)
        try:
            _ready(name, python, builder, run)
        except WorkerRetirementError:
            raise
        except Exception:
            previous = scratch/'previous'
            if record.get('previousIdentity') is None or _environment_identity(previous) != record['previousIdentity']:
                raise ValueError('No verified previous environment; installation and recovery state preserved')
            _ready(name, previous/python.relative_to(destination), builder, run)
            destination.replace(scratch/'replacement')
            previous.replace(destination)
            journal.unlink()
            shutil.rmtree(scratch)
            raise
        # Once old bytes may be deleted, recovery can only finish cleanup.
        atomic_write_json(journal, {**record, 'phase': 'cleanup'}, root=builder)
    if scratch.exists():
        shutil.rmtree(scratch)
    journal.unlink()


def replace_environment(name, *, builder=None, run=run_process_tree, installer=install):
    builder = Path(builder or Path(__file__).resolve().parents[1]).resolve()
    validate_plan(name, builder)
    destination = environment_path(name, builder)
    work = destination.parent/'.environment-build'
    if work.is_symlink():
        raise ValueError('Environment staging directory must remain on the destination filesystem')
    work.mkdir(parents=True, exist_ok=True)
    journal = builder/f'.environment-install-{name}.json'
    with exclusive_process_lock(builder/f'.environment-install-{name}.lock'):
        if destination.is_symlink():
            raise ValueError('Current environment must not be a symlink')
        if journal.exists():
            with environment_lease(name, builder=builder, exclusive=True):
                record = read_bounded_json(journal, max_bytes=4096, root=builder)
                if record.get('destination') != str(destination):
                    raise ValueError('Environment recovery destination differs')
                scratch = Path(record['scratch'])
                if scratch.parent != work or not scratch.name.startswith('environment-build-') or scratch.is_symlink():
                    raise ValueError('Environment recovery scratch is unsafe')
                previous = scratch/'previous'
                if (record.get('formatVersion') != 1 or record.get('phase') not in ('prepared', 'cleanup')
                        or record.get('staged') != str(scratch/'replacement')):
                    raise ValueError('Environment recovery staged identity differs')
                replacement_identity = record.get('replacementIdentity')
                if replacement_identity is not None and _environment_identity(destination) == replacement_identity:
                    _finish_promotion(name, destination, scratch, journal, builder, run)
                    return
                if record['phase'] == 'cleanup':
                    raise ValueError('Committed environment changed; installation and recovery state preserved')
                if not scratch.exists():
                    raise ValueError('Environment recovery cannot identify the installed destination')
                if destination.exists() and _environment_identity(destination) != record.get('previousIdentity'):
                    raise ValueError('Environment destination changed; conflicting environment preserved')
                if not destination.exists() and (previous.exists() or record.get('previousIdentity') is not None):
                    if _environment_identity(previous) != record.get('previousIdentity'):
                        raise ValueError('Environment recovery previous identity differs')
                    _ready(name, previous/environment_python(name, builder).relative_to(destination), builder, run)
                    previous.replace(destination)
                # A first install has no previous environment: discard only this
                # recorded staging tree and rebuild against the current lock plan.
                shutil.rmtree(scratch)
                journal.unlink()
        scratch = Path(tempfile.mkdtemp(prefix='environment-build-', dir=work))
        staged, previous = scratch/'replacement', scratch/'previous'
        try:
            from stemwise_engine.runtime_layout import load_layout
            python = builder.parent/load_layout(builder.parent)['python']['executable']
            run([str(builder/'.tools/uv'), 'venv', '--relocatable', '--python', str(python), str(staged)],
                check=True, timeout=60)
            installer(name, staged/'bin/python', builder=builder, run=run)
            _ready(name, staged/'bin/python', builder, run)
            with environment_lease(name, builder=builder, exclusive=True):
                atomic_write_json(journal, {'formatVersion': 1, 'phase': 'prepared',
                    'destination': str(destination), 'scratch': str(scratch), 'staged': str(staged),
                    'replacementIdentity': _environment_identity(staged),
                    'previousIdentity': _environment_identity(destination)}, root=builder)
                if destination.exists():
                    destination.replace(previous)
                try:
                    staged.replace(destination)
                except BaseException:
                    if previous.exists() and not destination.exists():
                        previous.replace(destination)
                    raise
                _finish_promotion(name, destination, scratch, journal, builder, run)
        finally:
            if not journal.exists():
                if scratch.exists() and not isinstance(sys.exc_info()[1], WorkerRetirementError):
                    with worker_directory_cleanup(scratch):
                        shutil.rmtree(scratch)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('environment', choices=('main', 'allin1', 'lyrics'))
    args = parser.parse_args()
    replace_environment(args.environment)
