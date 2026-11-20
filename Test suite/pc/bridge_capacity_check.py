"""Qualify the seven Linux Bridge capacity cases on bounded, private tmpfs mounts."""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / 'StemWise/third_party/phone-pc-bridge/source'
CASES = {
    'files': (('TestStorageReservationsOnSmallFilesystem', '32m', 4096),),
    'backend': (('TestPiStorageReserveOnSmallFilesystem', '32m', 4096),
                ('TestPiStorageReserveOnInodeLimitedFilesystem', '1g', 128)),
    'pc': (('TestPcStorageReserveOnSmallFilesystem', '32m', 4096),
           ('TestPcStorageReserveOnInodeLimitedFilesystem', '1g', 128),
           ('TestPcStorageReservationsShareCapacity', '544m', 4096)),
    'cmd/bridge-pc-check': (('TestProducerStorageCapacityPreservesSource', '32m', 4096),),
}


def run(command, *, timeout=240, input=None, **kwargs):
    with subprocess.Popen(command, start_new_session=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, stdin=subprocess.PIPE if input is not None else None,
                          text=True, encoding='utf-8', **kwargs) as process:
        try:
            stdout, stderr = process.communicate(input=input, timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
            raise
        if process.returncode:
            raise RuntimeError(f'{command[0]} failed ({process.returncode}):\n{stdout}\n{stderr}')
        return stdout


def require_pass(events, package, test):
    rows = [json.loads(line) for line in events.splitlines() if line.strip()]
    if any(row.get('Action') in ('fail', 'skip') for row in rows):
        raise RuntimeError('Required capacity test failed or skipped: ' + test)
    passed = Counter((row.get('Package'), row.get('Test')) for row in rows
                     if row.get('Action') == 'pass' and row.get('Test'))
    if passed != Counter({('phonepcbridge/' + package, test): 1}):
        raise RuntimeError('Required capacity execution absent, duplicated or substituted: ' + test)


def execute(binary, test, mount, size, inodes):
    run(['mount', '-t', 'tmpfs', '-o', f'size={size},nr_inodes={inodes}', 'tmpfs', mount], timeout=10)
    try:
        environment = dict(os.environ, TMPDIR=mount, BRIDGE_TEST_SMALL_FS=mount)
        print(run([binary, '-test.v=test2json', '-test.run=^' + re.escape(test) + '$',
                   '-test.timeout=45s', '-test.count=1'], env=environment, timeout=55), end='')
    finally:
        run(['umount', mount], timeout=10)


def main():
    if len(sys.argv) == 7 and sys.argv[1] == '--execute':
        execute(*sys.argv[2:])
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--go')
    args = parser.parse_args()
    if sys.platform != 'linux':
        parser.error('Run with Linux Python, including WSL; private mount namespaces are required.')
    local_go = BRIDGE / '.work/toolchain/go/bin/go.exe'
    args.go = (args.go or shutil.which('go') or shutil.which('go.exe') or
               (str(local_go) if local_go.is_file() else 'go'))
    for tool in (args.go, 'unshare', 'mount', 'umount'):
        if shutil.which(tool) is None:
            parser.error('Capacity qualification blocked: required Linux tool unavailable: ' + tool)
    try:
        run(['unshare', '--user', '--map-root-user', '--mount', '--fork', 'true'], timeout=10)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        parser.error('Capacity qualification blocked: private Linux mount namespaces unavailable: ' + str(error))
    pins = json.loads((BRIDGE / 'bridge-core/sdk-build-pins.json').read_text(encoding='utf-8'))
    environment = dict(os.environ, GOFLAGS='-mod=readonly', GOWORK='off', GOTOOLCHAIN='local',
                       GOOS='linux', GOARCH='amd64', CGO_ENABLED='0')
    if args.go.endswith('.exe'):
        forwarded = ('GOFLAGS', 'GOWORK', 'GOTOOLCHAIN', 'GOOS', 'GOARCH', 'CGO_ENABLED')
        environment['WSLENV'] = ':'.join([item for item in environment.get('WSLENV', '').split(':')
            if item and item.split('/')[0] not in forwarded] + list(forwarded))
    if run([args.go, 'env', 'GOVERSION'], env=environment).strip() != pins['go']:
        raise RuntimeError('Capacity qualification requires pinned Go ' + pins['go'])
    with tempfile.TemporaryDirectory(prefix='bridge-capacity-', dir=ROOT / 'Test suite/work') as directory:
        work = Path(directory)
        module = work / 'module'
        module.mkdir()
        for name in ('go.mod', 'go.sum'):
            shutil.copyfile(BRIDGE / 'bridge-core' / name, module / name)
        for source in (BRIDGE / 'bridge-core', BRIDGE / 'test-suite/bridge-core'):
            for path in source.rglob('*.go'):
                relative = path.relative_to(source)
                if '.work' in relative.parts:
                    continue
                target = module / relative
                if target.exists():
                    raise RuntimeError('Suite would replace production source: ' + str(relative))
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
        for package, cases in CASES.items():
            binary = work / (package.replace('/', '-') + '.test')
            run([args.go, 'test', '-c', '-o', '../' + binary.name, './' + package],
                cwd=module, env=environment)
            for test, size, inodes in cases:
                mount = work / 'mount'
                mount.mkdir()
                try:
                    output = run(['unshare', '--user', '--map-root-user', '--mount', '--fork',
                        '--kill-child=SIGKILL', sys.executable, str(Path(__file__).resolve()),
                        '--execute', str(binary), test, str(mount), size, str(inodes)], timeout=80)
                    events = run([args.go, 'tool', 'test2json', '-p', 'phonepcbridge/' + package],
                                 env=environment, input=output)
                    require_pass(events, package, test)
                    print('PASS ' + package + '/' + test, flush=True)
                finally:
                    mount.rmdir()


if __name__ == '__main__':
    main()
