"""Select existing non-live CI gates from changed production and test inputs."""
import os
from pathlib import Path
import re
import subprocess

LANES = ('pc-contracts', 'native', 'android', 'bridge-core', 'windows-ownership', 'audio')


def affected(paths):
    selected = set()
    for path in paths:
        if path.endswith('.md') and path != 'SEND_TO_AGENTS.md':
            continue
        if path == 'SEND_TO_AGENTS.md':
            selected.update(('pc-contracts', 'android'))
        elif path in ('Test suite/Run-Tester.ps1', 'Test suite/source_test_inventory.py',
                      'Test suite/python-checks-requirements.txt') or path.startswith(('.github/', 'Test suite/host/')):
            selected.update(LANES)
        elif path == 'StemWise/tools/regenerate-golden-waveform.py':
            selected.update(('pc-contracts', 'android'))
        elif path.startswith(('StemWise/contracts/', 'Test suite/fixtures/')):
            selected.update(('pc-contracts', 'native', 'android', 'audio'))
        elif path.startswith('StemWise/third_party/phone-pc-bridge/'):
            selected.update(('bridge-core', 'android', 'windows-ownership'))
        elif path.startswith(('PC side/', 'Test suite/pc/')):
            selected.update(('pc-contracts', 'windows-ownership'))
            if path in ('Test suite/pc/bridge_capacity_check.py', 'Test suite/pc/unit/test_bridge_capacity_check.py'):
                selected.add('bridge-core')
            if path in ('PC side/stemwise-playlist-builder/src/common.py', 'Test suite/pc/unit/test_owned_linux_process.py'):
                selected.add('native')
        elif path.startswith(('StemWise/audio-engine/', 'StemWise/app/src/main/cpp/', 'Test suite/native/')):
            selected.update(('native', 'android', 'audio'))
        elif path.startswith(('StemWise/', 'Test suite/android/', 'Test suite/device/')):
            selected.add('android')
            if path.startswith(('StemWise/app/', 'StemWise/gradle/', 'StemWise/build-logic/',
                                'Test suite/android/', 'Test suite/device/')) or path in ('StemWise/build.gradle.kts', 'StemWise/settings.gradle.kts'):
                selected.add('audio')
    return {lane: lane in selected for lane in LANES}


def changed_paths(root, base, head):
    if not all(re.fullmatch(r'[0-9a-f]{40,64}', value or '') and set(value) != {'0'} for value in (base, head)):
        return None
    try:
        value = subprocess.check_output(['git', 'diff', '--no-renames', '--name-only', '-z', base, head, '--'], cwd=root)
    except subprocess.CalledProcessError:
        return None
    return value.decode('utf-8').rstrip('\0').split('\0') if value else []


def main():
    paths = changed_paths(Path.cwd(), os.environ.get('BASE_SHA'), os.environ.get('HEAD_SHA'))
    selection = dict.fromkeys(LANES, True) if paths is None else affected(paths)
    output = '\n'.join(f'{lane}={str(selected).lower()}' for lane, selected in selection.items()) + '\n'
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as stream:
        stream.write(output)
    print(output, end='')


if __name__ == '__main__':
    main()
