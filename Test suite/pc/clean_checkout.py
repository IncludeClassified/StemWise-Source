"""Offline clean-source package/recovery smoke; optionally compile Android/native with retained tools."""
import argparse
import configparser
import importlib.metadata
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import venv
from contextlib import contextmanager

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'Test suite/host'))
from owned_process import OwnedProcessError, run


@contextmanager
def checkout_directory(*, prefix, dir):
    work = Path(dir).resolve()
    path = Path(tempfile.mkdtemp(prefix=prefix, dir=work)).resolve()
    unretired = False
    try:
        yield str(path)
    except BaseException as error:
        # The Linux runner loads the engine package; the standalone Windows compile does not need it.
        engine = sys.modules.get('stemwise_engine.common')
        unretired = (isinstance(error, OwnedProcessError) and error.failure_kind == 'cleanup'
                     or isinstance(error, getattr(engine, 'WorkerRetirementError', ())))
        if unretired:
            print(f'Unretired clean-checkout workspace retained: {path}', file=sys.stderr)
        raise
    finally:
        if not unretired:
            if path.is_symlink() or path.resolve().parent != work:
                raise ValueError('Clean-checkout cleanup target escapes its work directory')
            shutil.rmtree(path)


def checked(command, *, cwd, phase, timeout=90, expected=0, output_log=None):
    started = time.perf_counter()
    try:
        result = run([str(token) for token in command], cwd=cwd, timeout=timeout,
                     **({'output_log': output_log} if output_log else {}))
        if result.returncode != expected:
            raise RuntimeError(f'{phase} exited {result.returncode}, expected {expected}:\n'
                               f'{result.stdout}\n{result.stderr}')
        return result
    finally:
        print('CHECKOUT_PHASE ' + json.dumps({'phase': phase, 'seconds': time.perf_counter()-started}), flush=True)


def copy_source(destination, *, android=False):
    scopes = ['PC side/pyproject.toml', 'PC side/stemwise-playlist-builder/src', 'PC side/qobuz-comparison']
    if android:
        scopes += ['StemWise', 'Test suite', 'SEND_TO_AGENTS.md']
    inventory = checked(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', *scopes],
                        cwd=ROOT, phase='source-inventory', timeout=30)
    files = set(inventory.stdout.split('\0')) - {''}
    for relative in sorted(files):
        if relative.startswith('PC side/qobuz-comparison/') and Path(relative).suffix != '.py':
            continue
        source, target = ROOT/relative, destination/relative
        if not source.is_file() or source.is_symlink() or not source.resolve().is_relative_to(ROOT):
            raise ValueError(f'Clean-source input is absent or not a confined regular file: {relative}')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    for relative in ['PC side/pyproject.toml', 'PC side/stemwise-playlist-builder/src/__init__.py',
                     'PC side/qobuz-comparison/__init__.py']:
        if not (destination/relative).is_file():
            raise ValueError(f'Required clean-source package input missing: {relative}')


def android_command(snapshot, *, gradle_home=None, sdk=None):
    project = snapshot/'StemWise'
    properties = (project/'gradle/wrapper/gradle-wrapper.properties').read_text(encoding='utf-8')
    match = re.search(r'distributionUrl=.*?/gradle-([0-9.]+)-bin\.zip', properties)
    if not match:
        raise ValueError('Clean build requires the current pinned binary Gradle distribution')
    version = match[1]
    home = Path(gradle_home or os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))
    launcher = 'gradle.bat' if os.name == 'nt' else 'gradle'
    tools = sorted((home/'wrapper/dists'/f'gradle-{version}-bin').glob(f'*/gradle-{version}/bin/{launcher}'))
    if not tools:
        raise ValueError(f'Offline clean build needs retained Gradle {version} in {home}/wrapper/dists')
    configured_sdk = sdk or os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    if not configured_sdk and os.name == 'nt' and os.environ.get('LOCALAPPDATA'):
        configured_sdk = str(Path(os.environ['LOCALAPPDATA'])/'Android/Sdk')
    sdk_path = Path(configured_sdk) if configured_sdk else None
    toolchain = configparser.ConfigParser()
    toolchain.read_string('[android]\n'+(project/'gradle/android-toolchain.properties').read_text(encoding='utf-8'))
    platform = toolchain['android']['compileSdk']
    if sdk_path is None or not (sdk_path/f'platforms/android-{platform}/android.jar').is_file():
        raise ValueError(f'Offline clean build needs installed Android SDK platform {platform} (ANDROID_SDK_ROOT/ANDROID_HOME)')
    if not shutil.which('java') and not (Path(os.environ.get('JAVA_HOME', ''))/'bin'/('java.exe' if os.name == 'nt' else 'java')).is_file():
        raise ValueError('Offline clean build needs an existing JDK 17 installation')
    (project/'local.properties').write_text('sdk.dir='+sdk_path.resolve().as_posix()+'\n', encoding='utf-8')
    return [tools[0], ':app:compileDevelopmentKotlin', ':app:externalNativeBuildDevelopment',
            '--offline', '--no-build-cache', '--no-daemon', '--console=plain', '--max-workers=2']


PROBE = r'''
import os, sys
from pathlib import Path
from stemwise_engine import common, worker_publication as publication
from stemwise_media import catalogue_store
prefix = Path(sys.prefix).resolve()
for module in (common, publication, catalogue_store):
    assert Path(module.__file__).resolve().is_relative_to(prefix), module.__file__
assert catalogue_store.atomic_write_json is common.atomic_write_json
owner, staged = Path('owner.json').resolve(), Path('.owner.json.'+'a'*32+'.pending').resolve()
retained = Path('completed-output.bin')
if sys.argv[1] == 'interrupt':
    owner.write_bytes(b'old owner')
    staged.write_bytes(b'accepted candidate')
    retained.write_bytes(b'independent completed output')
    exchange = publication.replace_preserving_previous
    def stop(*args):
        exchange(*args)
        os._exit(73)
    publication.replace_preserving_previous = stop
    publication.publish_owner_file(owner, staged, b'old owner', max_bytes=1024)
    raise AssertionError('interruption was not reached')
elif sys.argv[1] == 'recover':
    marker = Path('.owner.json.publication-conflict.json')
    assert marker.is_file(), 'actual interruption left no recovery record'
    for _ in range(2):
        assert common.read_bounded_regular_bytes(owner, max_bytes=1024, root=owner.parent) == b'accepted candidate'
    assert not marker.exists()
    assert retained.read_bytes() == b'independent completed output'
    staged.write_bytes(b'rejected replacement')
    try:
        publication.publish_owner_file(owner, staged, b'stale owner', max_bytes=1024)
    except ValueError:
        pass
    else:
        raise AssertionError('stale owner publication was accepted')
    assert owner.read_bytes() == b'accepted candidate'
    assert staged.read_bytes() == b'rejected replacement'
    assert retained.read_bytes() == b'independent completed output'
else:
    raise AssertionError('unknown probe')
'''


def smoke(*, android=False):
    if android:
        work = ROOT/'Test suite/work'
        work.mkdir(exist_ok=True)
        with checkout_directory(prefix='clean-android-', dir=work) as temporary:
            source = Path(temporary)/'source'
            copy_source(source, android=True)
            checked(android_command(source), cwd=source/'StemWise', phase='android-native-compile', timeout=1800,
                    output_log=Path(temporary) / 'compile.log')
            print('Clean-source Android/native compile passed: fresh outputs, retained Gradle/SDK/dependency caches, '
                  'offline; no APK delivery or device qualification.', flush=True)
        return
    # Reuse installed build tools only; never acquire dependencies or initialize models.
    for name, version in [('setuptools', '78.1.0'), ('pip', None)]:
        try:
            observed = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError as error:
            raise ValueError(f'Offline package smoke needs existing {name}') from error
        if version and observed != version:
            raise ValueError(f'Offline package smoke needs retained {name}=={version}; found {observed}')
    if os.name != 'posix':
        raise ValueError('Package/recovery smoke requires the existing WSL/Linux runtime (use Run-Tester -Mode CleanCheckout)')
    work = ROOT/'Test suite/work'
    work.mkdir(exist_ok=True)
    with checkout_directory(prefix='clean-checkout-', dir=work) as temporary:
        root = Path(temporary).resolve()
        source = root/'source'
        copy_source(source)
        environment = root/'environment'
        started = time.perf_counter()
        venv.EnvBuilder(with_pip=False, symlinks=True).create(environment)
        print('CHECKOUT_PHASE '+json.dumps({'phase': 'environment', 'seconds': time.perf_counter()-started}), flush=True)
        python = environment/('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
        site = checked([python, '-I', '-B', '-c', 'import sysconfig; print(sysconfig.get_path("purelib"))'],
                       cwd=root, phase='environment-layout').stdout.strip()
        checked([sys.executable, '-I', '-B', '-m', 'pip', 'install', '--target', site, '--no-index', '--no-deps',
                 '--no-build-isolation', '--no-cache-dir', '--ignore-installed', source/'PC side'],
                cwd=root, phase='package-install', output_log=root / 'package-install.log')
        unrelated = root/'unrelated'
        unrelated.mkdir()
        entry = checked([python, '-I', '-B', '-m', 'stemwise_engine.model_registry', '--profile', 'main', 'python'],
                        cwd=unrelated, phase='public-module-entry')
        registry = json.loads((source/'PC side/stemwise-playlist-builder/src/model-route-registry.json').read_text(encoding='utf-8'))
        if entry.stdout.strip() != registry['runtimeProfiles']['main']['python']:
            raise ValueError('Installed public module did not return the source-pinned Python version')
        checked([python, '-I', '-B', '-c', PROBE, 'interrupt'], cwd=unrelated,
                phase='process-interruption', expected=73, timeout=20)
        checked([python, '-I', '-B', '-c', PROBE, 'recover'], cwd=unrelated, phase='restart-recovery', timeout=20)
        print('Clean-source smoke passed: offline package install, isolated imports, abrupt process death, '
              'recovery and preserved output.', flush=True)
        print('Scope: current tracked and nonignored source; retained interpreter/build dependencies/tool caches; '
              'no fresh-machine, model, provider, device or physical-audio qualification.', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android', action='store_true', help='Only compile Android/native on this host; Run-Tester also runs WSL package/recovery')
    args = parser.parse_args()
    try:
        smoke(android=args.android)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'Clean-source smoke failed: {error}', file=sys.stderr)
        raise SystemExit(1)
