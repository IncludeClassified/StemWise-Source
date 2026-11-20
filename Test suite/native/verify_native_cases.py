"""Require every nonbenchmark focused native dispatch to have an actual CTest command."""
import argparse
import json
from pathlib import Path
import re
import subprocess


def missing_cases(tests, source, executable):
    expected = set(re.findall(r'^STEMWISE_FOCUSED_CASE\("(--[a-z0-9-]+)",', source, re.M))
    if not expected:
        raise RuntimeError('Native focused dispatcher is empty')
    commands = [tuple(test.get('command', [])) for test in tests]
    if any(len(command) == 1 and Path(command[0]).stem == executable for command in commands):
        raise RuntimeError('Aggregate native replay must not be registered: ' + executable)
    for selector in expected:
        matches = [command for command in commands if len(command) == 2 and
                   Path(command[0]).stem == executable and command[1] == selector]
        if len(matches) > 1:
            raise RuntimeError('Duplicate focused native registration: ' + selector)
    return sorted(selector for selector in expected if not any(
        len(command) == 2 and Path(command[0]).stem == executable and command[1] == selector for command in commands))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--ctest', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(subprocess.check_output([str(args.ctest), '--test-dir', str(args.build), '--show-only=json-v1']))
    root = Path(__file__).resolve().parent
    for source, executable in [('NativePlaybackRuntimeCases.def', 'stemwise_native_playback_runtime_test'),
                                ('SongRenderPipelineCases.def', 'stemwise_song_render_pipeline_test')]:
        missing = missing_cases(manifest['tests'], (root / source).read_text(encoding='utf-8'), executable)
        if missing:
            raise RuntimeError('Unregistered native correctness cases: ' + ', '.join(missing))
    print('Every nonbenchmark native focused dispatch is registered in CTest')


if __name__ == '__main__':
    main()
