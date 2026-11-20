"""Actual Windows wrappers, WSL cache environment and managed-runtime ownership."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_package

ROOT = Path(__file__).resolve().parents[3]
BUILDER = ROOT / 'PC side/stemwise-playlist-builder'


@unittest.skipUnless(sys.platform == 'win32', 'Windows wrappers require the Windows/WSL host')
class PcRuntimeBootstrapTests(unittest.TestCase):
    def test_bridge_and_ordinary_wrappers_share_cache_roots_and_environment_lease(self):
        def mapped(path):
            return subprocess.check_output(['wsl.exe', '-d', 'Ubuntu', '--', 'wslpath', '-a',
                str(path).replace('\\', '/')], text=True, timeout=15).strip()
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            builder = root / 'PC side/stemwise-playlist-builder'
            source = builder / 'src'
            source.mkdir(parents=True)
            for name in ('future-song.ps1', 'recording-analysis.ps1', 'runtime-bootstrap.ps1', 'command-spec.ps1'):
                shutil.copyfile(BUILDER / name, builder / name)
            shutil.copyfile(ROOT / 'PC side/STEMWISE_BRIDGE.ps1', root / 'PC side/STEMWISE_BRIDGE.ps1')
            for name in ('runtime_environment.py', 'common.py'):
                shutil.copyfile(BUILDER / 'src' / name, source / name)
            (builder / '.venv/bin').mkdir(parents=True)
            wsl_builder = mapped(builder)
            subprocess.run(['wsl.exe', '-d', 'Ubuntu', '--', 'ln', '-s', '/usr/bin/python3',
                wsl_builder + '/.venv/bin/python'], check=True, timeout=15)
            entry = "import json,os,sys,time\nfrom pathlib import Path\nimport stemwise_engine.runtime_environment as runtime\nroot=Path(__file__).parents[1]\nif Path(__file__).stem=='bridge_song_dispatch':\n (root/'ready').touch(); deadline=time.monotonic()+30\n while not (root/'release').exists():\n  if time.monotonic()>deadline:raise TimeoutError('fixture release missing')\n  time.sleep(.02)\nprint('fixture progress',file=sys.stderr)\nprint(json.dumps({'python':sys.executable,'torch':os.environ.get('TORCH_HOME'),\n 'hf':os.environ.get('HF_HOME'),'allin1':os.environ.get('STEMWISE_ALLIN1_PYTHON'),\n 'leases':[str(path) for path,fd,exclusive in runtime._LEASES.get()]}))\n"
            for name in ('bridge_song_dispatch.py', 'workflow_command.py', 'recording_analysis.py'):
                (source / name).write_text(entry, encoding='utf-8')
            prepare_runtime_package(builder)
            (builder / 'config.json').write_text('{}', encoding='utf-8')
            context, guide = root / 'context.json', root / 'guide.md'
            context.write_text('{}', encoding='utf-8'); guide.write_text('fixture', encoding='utf-8')
            environment = {key: value for key, value in os.environ.items()
                if key not in ('TORCH_HOME', 'HF_HOME', 'STEMWISE_ALLIN1_PYTHON', 'STEMWISE_LAUNCH_TOKEN')}
            powershell = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File']
            command = powershell + [str(root / 'PC side/STEMWISE_BRIDGE.ps1'),
                '-ContextFile', str(context), '-GuideFile', str(guide), '-MaxFileBytes', '8192']
            child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                encoding='utf-8', env=environment)
            try:
                deadline = time.monotonic() + 15
                while not (builder / 'ready').exists() and child.poll() is None and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertTrue((builder / 'ready').exists(), child.stderr.read() if child.poll() is not None else 'Bridge child did not reach the held command boundary')
                probe = ('import sys;from stemwise_engine.runtime_environment import environment_lease\ntry:\n with environment_lease("main",builder=sys.argv[1],exclusive=True,wait_seconds=.15):\n  print("unprotected")\nexcept TimeoutError:print("blocked")')
                checked = subprocess.run(['wsl.exe', '-d', 'Ubuntu', '--', wsl_builder + '/.tools/bootstrap/bin/python', '-B', '-c', probe,
                    wsl_builder], capture_output=True, text=True, timeout=5, check=True)
                self.assertEqual(checked.stdout.strip(), 'blocked', checked.stderr)
            finally:
                (builder / 'release').touch()
                output, error = child.communicate(timeout=10)
            self.assertEqual(child.returncode, 0, error)
            rows = [json.loads(output)]
            for wrapper, args in (('future-song.ps1', ['status']),
                                  ('recording-analysis.ps1', ['status', '-RecordingKey', 'qobuz:11'])):
                result = subprocess.run(powershell + [str(builder / wrapper)] + args,
                    capture_output=True, text=True, encoding='utf-8', env=environment, timeout=15, check=True)
                rows.append(json.loads(result.stdout))
            for row in rows:
                self.assertEqual(row, {'python': wsl_builder + '/.venv/bin/python',
                    'torch': mapped(root / 'PC side') + '/model-cache/torch',
                    'hf': mapped(root / 'PC side') + '/model-cache/huggingface',
                    'allin1': wsl_builder + '/.venv-allin1/bin/python',
                    'leases': [wsl_builder + '/.environment-use-main.lock']})
            with subprocess.Popen(['wsl.exe', '-d', 'Ubuntu', '--', wsl_builder + '/.tools/bootstrap/bin/python', '-B', '-c', probe,
                    wsl_builder], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as check:
                output, error = check.communicate(timeout=5)
                self.assertEqual((check.returncode, output.strip()), (0, 'unprotected'), error)
