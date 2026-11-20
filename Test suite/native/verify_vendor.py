"""Check retained source identity, or reconstruct it from pinned upstream files."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
VENDOR = ROOT/'StemWise/audio-engine/src/main/cpp/third_party'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def relative_path(value):
    path = Path(value)
    if path.is_absolute() or '..' in path.parts or not path.parts:
        raise ValueError('Vendor path escaped its source root')
    return path


def verify(vendor=VENDOR, upstream_root=None, fetch=False):
    vendor = Path(vendor).resolve()
    manifest = json.loads((vendor/'vendor-sources.json').read_text(encoding='utf-8'))
    if manifest.get('schemaVersion') != 1:
        raise ValueError('Unsupported vendor source manifest')
    expected = {'vendor-sources.json'}
    dependencies = manifest['dependencies']
    for dependency in dependencies:
        base = relative_path(dependency['directory'])
        for source in dependency['files']:
            path = base/relative_path(source['path'])
            if path.as_posix() in expected:
                raise ValueError('Duplicate vendor file')
            expected.add(path.as_posix())
            if digest((vendor/path).read_bytes()) != source['sha256']:
                raise ValueError('Vendor file changed: '+path.as_posix())
        for patch in dependency['patches']:
            path = relative_path(patch['path'])
            expected.add(path.as_posix())
            if digest((vendor/path).read_bytes()) != patch['sha256']:
                raise ValueError('Vendor patch changed: '+path.as_posix())
    actual = {path.relative_to(vendor).as_posix() for path in vendor.rglob('*') if path.is_file()}
    if actual != expected:
        raise ValueError('Undeclared or missing vendor files: '+str(sorted(actual ^ expected)))
    if upstream_root is None and not fetch:
        return len(expected)
    work = ROOT/'Test suite/work'
    work.mkdir(parents=True, exist_ok=True)
    for dependency in dependencies:
        def original(source):
            if fetch:
                url = dependency['repository'].replace('https://github.com/', 'https://raw.githubusercontent.com/')+'/'+dependency['commit']+'/'+source['path']
                with urllib.request.urlopen(url, timeout=30) as response:
                    data = response.read()
            else:
                data = (Path(upstream_root)/dependency['directory']/source['path']).read_bytes()
            if digest(data) != source['upstreamSha256']:
                raise ValueError('Upstream source changed: '+source['path'])
            data = data.replace(b'\r\n', b'\n')
            if source['lineEndings'] == 'crlf':
                data = data.replace(b'\n', b'\r\n')
            elif source['lineEndings'] != 'lf':
                raise ValueError('Unknown vendor line-ending recipe')
            return source['path'], data
        with ThreadPoolExecutor(max_workers=8) as pool:
            sources = list(pool.map(original, dependency['files']))
        with tempfile.TemporaryDirectory(prefix='vendor-reconstruct-', dir=work) as temporary:
            staging = Path(temporary)
            subprocess.run(['git', 'init', '-q', str(staging)], check=True, capture_output=True, timeout=15)
            for relative, data in sources:
                path = staging/relative_path(relative)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            for patch in dependency['patches']:
                subprocess.run(['git', '-c', 'core.autocrlf=false', 'apply', '--binary', '--whitespace=nowarn',
                                str(vendor/patch['path'])], cwd=staging, check=True,
                               capture_output=True, timeout=30)
            actual = {p.relative_to(staging).as_posix() for p in staging.rglob('*')
                      if p.is_file() and '.git' not in p.relative_to(staging).parts}
            if actual != {source['path'] for source in dependency['files']}:
                raise ValueError('Vendor patch added undeclared files')
            for source in dependency['files']:
                if digest((staging/source['path']).read_bytes()) != source['sha256']:
                    raise ValueError('Vendor reconstruction differs: '+source['path'])
    return len(expected)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    choice = parser.add_mutually_exclusive_group()
    choice.add_argument('--upstream-root', type=Path)
    choice.add_argument('--fetch', action='store_true', help='Fetch only exact manifest commits; retain no downloads')
    args = parser.parse_args()
    count = verify(upstream_root=args.upstream_root, fetch=args.fetch)
    completed = 'inventory/hashes and ordered upstream reconstruction' if args.upstream_root is not None or args.fetch else 'inventory/hashes only'
    print('PASS: vendor '+completed+' ('+str(count)+')')
