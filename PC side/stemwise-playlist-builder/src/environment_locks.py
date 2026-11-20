"""Install the reviewed Linux environments without resolving new dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import sys
import time

BUILDER = Path(__file__).resolve().parents[1]
FOUNDATION = {'setuptools', 'wheel', 'packaging', 'cython', 'numpy', 'torch', 'torchaudio'}


def install_command(command, environment, timeout, run):
    deadline = time.monotonic() + timeout
    for attempt in range(3):
        try:
            result = run(command, check=True, timeout=max(0.001, deadline-time.monotonic()),
                         env=environment, stderr=subprocess.PIPE, text=True)
        except subprocess.CalledProcessError as error:
            diagnostic = error.stderr or ''
            if diagnostic:
                print(diagnostic, file=sys.stderr, end='' if diagnostic.endswith('\n') else '\n')
            # DrvFS can temporarily refuse uv's cache-directory promotion. Reusing
            # verified cache entries is safe; hash, build and target errors still fail.
            transient = re.search(r'failed to rename file from .* to .*[/\\]archive-v0[/\\][^\n]*:.*Permission denied \(os error 13\)',
                                  diagnostic, re.DOTALL) is not None
            if not transient or 'hash mismatch' in diagnostic.lower() or attempt == 2 or time.monotonic() >= deadline:
                raise
            time.sleep(min(attempt+1, max(0, deadline-time.monotonic())))
        else:
            if result.stderr:
                print(result.stderr, file=sys.stderr, end='' if result.stderr.endswith('\n') else '\n')
            return


def locked_requirements(path, expected_hash):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_hash:
        raise ValueError('Environment lock changed: ' + str(path))
    blocks = re.split(r'(?m)(?=^[A-Za-z0-9][A-Za-z0-9_.-]*==)', data.decode('utf-8'))
    records = {}
    for block in blocks:
        if not block.strip():
            continue
        lines = block.splitlines()
        match = re.fullmatch(r'([A-Za-z0-9][A-Za-z0-9_.-]*)==([^\s;]+) \\', lines[0])
        if not match or not lines[1:] or any(not re.fullmatch(
                r'    --hash=sha256:[0-9a-f]{64}(?: \\)?', line) for line in lines[1:]):
            raise ValueError('Unhashed or unsupported locked requirement')
        name = match[1].lower().replace('_', '-').replace('.', '-')
        if name in records:
            raise ValueError('Duplicate locked requirement: ' + name)
        records[name] = block
    if not records:
        raise ValueError('Empty environment lock')
    return records


def dependency_identity(builder=BUILDER, *, environments=None, profiles=None):
    """Verified lock contents and typed runtime data, independent of listing prose."""
    from stemwise_engine.model_registry import runtime_profile
    builder = Path(builder).resolve()
    manifest = json.loads((builder/'environment-locks.json').read_text(encoding='utf-8'))
    if manifest.get('schemaVersion') != 1:
        raise ValueError('Unsupported locked environment')
    selected = environments
    environments = {}
    for name, entry in manifest['environments'].items():
        if selected is not None and name not in selected:
            continue
        relative = Path(entry['lock'])
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('Environment lock escaped builder')
        records = locked_requirements(builder/relative, entry['sha256'])
        environments[name] = {**{key: value for key, value in entry.items() if key not in {'lock', 'sha256'}},
            'packages': {package: {'version': block.splitlines()[0].split('==', 1)[1].split()[0],
                'hashes': sorted(re.findall(r'--hash=sha256:([0-9a-f]{64})', block))}
                for package, block in records.items()}}
    return {'python': manifest['pythonVersion'], 'platform': manifest['platform'],
        'environments': environments, 'profiles': {name: runtime_profile(name) for name in
            (profiles if profiles is not None else ('main', 'lyrics', 'owsm_ctc_forced_alignment', 'nemo_nfa_forced_alignment'))}}


def retained_sources(entry, builder):
    sources = []
    for source in entry['sources']:
        if 'path' in source:
            path = builder.parent/source['path']
            if not path.resolve().is_relative_to(builder.parent):
                raise ValueError('Retained artifact escaped PC side')
            variable = source.get('environmentVariable')
            if variable is not None:
                if not re.fullmatch(r'STEMWISE_[A-Z0-9_]+', variable):
                    raise ValueError('Retained artifact environment variable is invalid')
                if os.environ.get(variable):
                    path = Path(os.environ[variable]).expanduser().resolve()
            if not path.is_file():
                hint = f'; supply its exact approved wheel with {variable}' if variable else ''
                raise ValueError('Required retained artifact missing: '+source['path']+hint)
            if hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
                raise ValueError('Required retained artifact changed: '+source['path'])
            sources.append((str(path), source['sha256']))
        else:
            if not re.fullmatch(r'https://github.com/[\w.-]+/[\w.-]+\.git', source['repository']) or not re.fullmatch(r'[0-9a-f]{40}', source['commit']):
                raise ValueError('Source installation requires an exact reviewed Git commit')
            sources.append(('git+'+source['repository']+'@'+source['commit'], None))
    return sources


def check_artifacts(name, builder=BUILDER):
    """Standard-library preflight, available before installing the bootstrap package."""
    builder = Path(builder).resolve()
    manifest = json.loads((builder/'environment-locks.json').read_text(encoding='utf-8'))
    if manifest.get('schemaVersion') != 1 or name not in manifest['environments']:
        raise ValueError('Unsupported locked environment')
    entry = manifest['environments'][name]
    relative = Path(entry['lock'])
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('Environment lock escaped builder')
    locked_requirements(builder/relative, entry['sha256'])
    return retained_sources(entry, builder)


def prepare_artifacts(name, builder=BUILDER):
    """Retain an explicitly supplied, verified wheel at the current declared cache path."""
    builder = Path(builder).resolve()
    sources = check_artifacts(name, builder)
    manifest = json.loads((builder/'environment-locks.json').read_text(encoding='utf-8'))
    for declaration, (source, digest) in zip(manifest['environments'][name]['sources'], sources):
        if 'path' not in declaration:
            continue
        destination = builder.parent/declaration['path']
        if destination.exists():
            if hashlib.sha256(destination.read_bytes()).hexdigest() != digest:
                raise ValueError('Existing retained artifact changed; preserved: '+str(destination))
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix='.wheel-', dir=destination.parent)
        try:
            with os.fdopen(descriptor, 'wb') as output, open(source, 'rb') as original:
                shutil.copyfileobj(original, output)
            if hashlib.sha256(Path(temporary).read_bytes()).hexdigest() != digest:
                raise ValueError('Supplied retained artifact changed during staging')
            # Never replace an independently retained artifact that arrived during the copy.
            os.link(temporary, destination)
        finally:
            Path(temporary).unlink()


def validate_plan(name, builder=BUILDER):
    from stemwise_engine.model_registry import runtime_profile
    builder = Path(builder).resolve()
    manifest = json.loads((builder/'environment-locks.json').read_text(encoding='utf-8'))
    if manifest.get('schemaVersion') != 1 or name not in manifest['environments']:
        raise ValueError('Unsupported locked environment')
    profile = runtime_profile('lyrics' if name == 'lyrics' else 'main')
    if manifest.get('pythonVersion') != profile['python']:
        raise ValueError('Environment lock Python differs from current execution profile')
    entry = manifest['environments'][name]
    relative = Path(entry['lock'])
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('Environment lock escaped builder')
    records = locked_requirements(builder/relative, entry['sha256'])
    if name in ('main', 'lyrics'):
        for package, version in profile['packages'].items():
            key = package.lower().replace('_', '-').replace('.', '-')
            if key in records and records[key].splitlines()[0].split('==', 1)[1].split()[0] != version:
                raise ValueError('Environment lock differs from current model execution profile: '+package)
    sources = retained_sources(entry, builder)
    return manifest, entry, records, sources


def install(name, python, builder=BUILDER, run=None):
    from stemwise_engine.common import WorkerTemporaryDirectory
    if run is None:
        from stemwise_engine.common import run_process_tree
        run = run_process_tree
    builder = Path(builder).resolve()
    from stemwise_engine.runtime_environment import ENVIRONMENTS, environment_path
    interpreter = Path(os.path.abspath(python))
    if interpreter.is_relative_to(builder.parent) and any(
            interpreter.parent == environment_path(route, builder)/'bin' for route in ENVIRONMENTS):
        raise ValueError('Install into a staged environment through environment_bootstrap.py')
    manifest, entry, records, sources = validate_plan(name, builder)
    uv = str(builder/'.tools/uv')
    version = run([str(python), '--version'], capture_output=True, text=True, timeout=15, check=True)
    if version.stdout.strip() != 'Python '+manifest['pythonVersion']:
        raise ValueError('Locked environment requires Python '+manifest['pythonVersion'])
    work = builder.parent/'runtime/scratch/environment-install'
    work.mkdir(parents=True, exist_ok=True)
    with WorkerTemporaryDirectory(prefix='environment-lock-', dir=work) as temporary:
        # WSL installs on the owner's Windows volume: avoid dozens of concurrent directory
        # promotions, while keeping source builds bounded independently.
        environment = dict(os.environ, MAX_JOBS='2', UV_CONCURRENT_DOWNLOADS='1',
                           UV_CONCURRENT_INSTALLS='1', TMPDIR=temporary)
        environment.setdefault('UV_CACHE_DIR', str(builder.parent/'runtime/uv-cache'))
        # NATTEN imports Torch while building: its entire locked runtime must be
        # installed, including CPU/CUDA transitive wheels, before preparing that sdist.
        phases = ([block for package, block in records.items() if package in FOUNDATION],
                  [block for package, block in records.items() if package not in FOUNDATION and package != 'natten'],
                  [block for package, block in records.items() if package == 'natten'])
        for index, blocks in enumerate(phases):
            if not blocks:
                continue
            requirements = Path(temporary)/f'phase-{index}.txt'
            requirements.write_text(''.join(blocks), encoding='utf-8', newline='\n')
            command = [uv, 'pip', 'install', '--python', str(python), '--require-hashes',
                       '--no-deps', '--no-build-isolation', '-r', str(requirements),
                       '--index-url', 'https://pypi.org/simple']
            if entry.get('torchIndex'):
                command += ['--extra-index-url', entry['torchIndex'], '--index-strategy', 'unsafe-best-match']
            install_command(command, environment, 3600, run)
        for index, (source, required_hash) in enumerate(sources):
            command = [uv, 'pip', 'install', '--python', str(python), '--no-deps', '--no-build-isolation']
            if required_hash:
                requirement = Path(temporary)/f'source-{index}.txt'
                requirement.write_text(Path(source).as_uri()+' --hash=sha256:'+required_hash+'\n', encoding='utf-8', newline='\n')
                command += ['--require-hashes', '-r', str(requirement)]
            else:
                command.append(source)
            install_command(command, environment, 1200, run)
        install_command([uv, 'pip', 'install', '--python', str(python), '--no-deps',
                         '--no-build-isolation', '--editable', str(builder.parent)],
                        environment, 120, run)
        run([uv, 'pip', 'check', '--python', str(python)], check=True, timeout=60)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('environment', choices=('main', 'allin1', 'lyrics'))
    parser.add_argument('--python', type=Path)
    parser.add_argument('--check-inputs', action='store_true', help='Validate locks and retained inputs before creating/clearing an environment')
    parser.add_argument('--check-artifacts', action='store_true', help='Validate exact retained artifacts before bootstrap installation')
    parser.add_argument('--prepare-artifacts', action='store_true', help='Retain explicitly supplied verified artifacts before installation')
    args = parser.parse_args()
    if args.prepare_artifacts:
        prepare_artifacts(args.environment)
    elif args.check_artifacts:
        check_artifacts(args.environment)
    elif args.check_inputs:
        validate_plan(args.environment)
    elif args.python is None:
        parser.error('--python is required for installation')
    else:
        install(args.environment, args.python)
