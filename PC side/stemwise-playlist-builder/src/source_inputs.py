"""Current build, producer and test inputs; excludes generated and private work."""
import argparse
import json
import os
from pathlib import Path


TREES = (
    'StemWise/app/src', 'StemWise/audio-engine/src', 'StemWise/qa-provider/src', 'StemWise/gradle',
    'StemWise/third_party/phone-pc-bridge/source/bridge-core',
    'StemWise/third_party/phone-pc-bridge/contracts',
    'StemWise/third_party/phone-pc-bridge/source/test-suite',
    'StemWise/build-logic', 'StemWise/tools', 'StemWise/contracts', 'StemWise/design-system', '.github/workflows',
    'Test suite',
    'PC side/stemwise-playlist-builder/src',
    'PC side/stemwise-playlist-builder/locks',
    'PC side/stemwise-playlist-builder/future-songs/policy',
)
FILES = (
    'SEND_TO_AGENTS.md',
    'StemWise/third_party/phone-pc-bridge/android/phone-pc-bridge.aar',
    'StemWise/third_party/phone-pc-bridge/android/phone-pc-bridge.build.json',
    'StemWise/third_party/phone-pc-bridge/SHA256SUMS.txt',
    'StemWise/build.gradle.kts', 'StemWise/settings.gradle.kts',
    'StemWise/gradle.properties', 'StemWise/gradlew', 'StemWise/gradlew.bat',
    'StemWise/app/build.gradle.kts', 'StemWise/app/gradle.lockfile',
    'StemWise/app/proguard-rules.pro', 'StemWise/audio-engine/build.gradle.kts',
    'StemWise/audio-engine/gradle.lockfile',
    'StemWise/audio-engine/consumer-rules.pro', 'StemWise/qa-provider/build.gradle.kts',
    'PC side/runtime-layout.json', 'PC side/DEPENDENCY_MANIFEST.json',
    'PC side/pyproject.toml', 'PC side/bootstrap-requirements.txt',
    'PC side/stemwise-playlist-builder/environment-locks.json',
)
SCRATCH = frozenset({
    '__pycache__', '.pytest_cache', '.mypy_cache', '.ruff_cache', '.gradle',
    'node_modules',
})
SCRATCH_PATHS = frozenset({
    'Test suite/work', 'Test suite/evidence',
    'StemWise/third_party/phone-pc-bridge/source/bridge-core/.work',
    'StemWise/third_party/phone-pc-bridge/source/test-suite/.work',
})


def source_files(workspace: Path, *, pc_only=False) -> tuple[Path, ...]:
    workspace = workspace.resolve(strict=True)
    paths = set()

    def include(path):
        if not path.is_file() or path.is_symlink():
            raise FileNotFoundError(f'Required source input is missing or linked: {path}')
        path.resolve(strict=True).relative_to(workspace)
        paths.add(path)

    trees = (*[path for path in TREES if path.startswith('PC side/')], 'StemWise/contracts/enrichment') if pc_only else TREES
    files = [path for path in FILES if path.startswith('PC side/')] if pc_only else FILES
    for relative in trees:
        tree = workspace / relative
        if not tree.is_dir() or tree.is_symlink():
            raise FileNotFoundError(f'Required source tree is missing or linked: {tree}')
        for directory, children, names in os.walk(tree):
            children[:] = sorted(
                name for name in children
                if name not in SCRATCH and
                (Path(directory) / name).relative_to(workspace).as_posix()
                not in SCRATCH_PATHS
            )
            for name in children:
                if (Path(directory) / name).is_symlink():
                    raise ValueError(f'Linked source directory: {Path(directory) / name}')
            for name in names:
                path = Path(directory) / name
                if path.suffix.lower() not in {'.md', '.pyc', '.pyo'}:
                    include(path)
    for relative in files:
        include(workspace / relative)
    comparison = workspace / 'PC side/qobuz-comparison'
    if not comparison.is_dir() or comparison.is_symlink():
        raise FileNotFoundError(f'Required source tree is missing or linked: {comparison}')
    for path in comparison.glob('*.py'):
        include(path)
    for parent in (workspace / 'PC side', workspace / 'PC side/stemwise-playlist-builder'):
        for pattern in ('*.ps1', '*.cs', 'setup_*.sh', 'requirements-*.txt'):
            for path in parent.glob(pattern):
                include(path)
    return tuple(sorted(paths, key=lambda path: path.relative_to(workspace).as_posix()))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps([str(path) for path in source_files(args.root)]))
