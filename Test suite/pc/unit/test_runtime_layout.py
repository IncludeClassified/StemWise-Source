"""Required bootstrap inputs cannot report readiness when absent or outside PC side."""
import contextlib
import base64
import io
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from powershell_fixture import native_path

SUITE=Path(__file__).resolve().parents[2]
from stemwise_engine import runtime_layout as LAYOUT


class RuntimeLayoutTest(unittest.TestCase):
    def test_numerical_children_use_configured_routes_without_legacy_fallback(self):
        from stemwise_engine import worker_runtime_probe, endpoint_ctc_evidence, endpoint_vocal_evidence, mms_asr, analyzers
        self.layout['environments'] = {name: f'custom/{name}/bin/python3.10' for name in ('main', 'lyrics', 'allin1')}
        self.write_layout()
        project = self.root/'builder'
        for name in ('main', 'lyrics', 'allin1'):
            executable = self.root/f'custom/{name}/bin/python3.10'
            executable.parent.mkdir(parents=True)
            executable.write_text('configured command fixture', encoding='utf-8')
        scratch = self.root/'scratch'
        scratch.mkdir()
        class ObservedCommand(Exception):
            pass
        def observed(command, **kwargs):
            import stemwise_engine.runtime_environment as runtime
            self.assertEqual(command[0], str(self.root/'custom'/route/'bin/python3.10'))
            self.assertIn(project/f'.environment-use-{route}.lock', [path for path, _, _ in runtime._LEASES.get()])
            raise ObservedCommand
        for legacy in ('absent', 'stale'):
            if legacy == 'absent':
                (project/'.venv/bin/python').unlink()
            else:
                for name in ('.venv', '.venv-lyrics-align', '.venv-allin1'):
                    old = project/name/'bin/python'
                    old.parent.mkdir(parents=True, exist_ok=True)
                    old.write_text('must not run', encoding='utf-8')
            invocations = [
                ('main', worker_runtime_probe, lambda: worker_runtime_probe.selected_torch_runtime(project, 1), 'common'),
                ('main', endpoint_ctc_evidence, lambda: endpoint_ctc_evidence._invoke(project, 'prepare', scratch, 1, alignment=False), None),
                ('lyrics', endpoint_ctc_evidence, lambda: endpoint_ctc_evidence._invoke(project, 'infer', scratch, 1, alignment=True), None),
                ('main', endpoint_vocal_evidence, lambda: endpoint_vocal_evidence._separate_many(
                    {'projectDir': str(project), 'analysisTimeoutMinutes': 1}, [(scratch/'audio.wav', scratch)]), None),
                ('main', mms_asr, lambda: mms_asr.runtime_provenance(project, 'cpu', 1, {}), None),
            ]
            for route, module, invoke, boundary in invocations:
                with self.subTest(legacy=legacy, route=route, module=module.__name__):
                    target = 'stemwise_engine.common.run_process_tree' if boundary else module.__name__+'.run_process_tree'
                    with patch(target, side_effect=observed), patch('stemwise_engine.endpoint_vocal_evidence.heavy_worker_stage',
                            side_effect=lambda *_: contextlib.nullcontext()):
                        with self.assertRaises(ObservedCommand):
                            invoke()
            with patch.dict(os.environ):
                os.environ.pop('STEMWISE_ALLIN1_PYTHON', None)
                self.assertEqual(analyzers._allin1_python(project), self.root/'custom/allin1/bin/python3.10')
                route = 'allin1'
                def allin1_command(command, **kwargs):
                    self.assertEqual(command[0], str(self.root/'custom/allin1/bin/python3.10'))
                    return SimpleNamespace(returncode=0, stdout='STEMWISE_RESULT={}', stderr='')
                with patch.object(analyzers, 'run_process_tree', side_effect=allin1_command) as launched:
                    analyzers.analyze_allin1(scratch/'audio.wav', 1000, project_dir=project, timeout_seconds=1)
                    launched.assert_called_once()
        for route in ('main', 'lyrics', 'allin1'):
            (self.root/f'custom/{route}/bin/python3.10').unlink()
        from stemwise_engine.common import run_process_tree
        with patch('stemwise_engine.common.run_process_tree', wraps=run_process_tree) as launched:
            with self.assertRaisesRegex(RuntimeError, 'could not be observed'):
                worker_runtime_probe.selected_torch_runtime(project, 1)
            self.assertEqual(launched.call_args.args[0][0], str(self.root/'custom/main/bin/python3.10'))
        for alignment in (False, True):
            with patch.object(endpoint_ctc_evidence, 'run_process_tree', wraps=run_process_tree) as launched:
                with self.assertRaisesRegex(RuntimeError, 'worker failed'):
                    endpoint_ctc_evidence._invoke(project, 'infer', scratch, 1, alignment=alignment)
                route = 'lyrics' if alignment else 'main'
                self.assertEqual(launched.call_args.args[0][0], str(self.root/f'custom/{route}/bin/python3.10'))
        with patch.dict(os.environ), patch.object(analyzers, 'run_process_tree') as launched:
            os.environ.pop('STEMWISE_ALLIN1_PYTHON', None)
            result = analyzers.analyze_allin1(scratch/'audio.wav', 1000, project_dir=project, timeout_seconds=1)
            self.assertEqual(result[0]['status'], 'unavailable')
            launched.assert_not_called()

    def test_changed_environment_and_distribution_reach_installation_inventory_and_launcher(self):
        from stemwise_engine.runtime_environment import environment_path
        self.layout['wslDistribution'] = 'StemWise-Test'
        self.layout['environments'] = {'main': 'custom/primary/bin/python', 'allin1': 'custom/secondary/bin/python'}
        self.layout['environmentVariables'] = {'HF_HOME': 'custom/hf', 'TORCH_HOME': 'custom/torch'}
        self.write_layout()
        self.assertEqual(environment_path('main', self.root/'builder'), self.root/'custom/primary')
        command = [sys.executable, '-B', '-m', 'stemwise_engine.runtime_layout', '--pc-root', str(self.root), '--environment', 'main']
        selected = subprocess.run(command, capture_output=True, text=True, timeout=10, check=True)
        self.assertEqual(selected.stdout.strip(), 'custom/primary/bin/python')
        reported = LAYOUT.inventory(self.root)
        self.assertEqual(next(row['path'] for row in reported['routes'] if row.get('route') == 'main'),
                         'custom/primary/bin/python')
        shell = shutil.which('powershell.exe') or shutil.which('pwsh')
        self.assertIsNotNone(shell, 'The supported launcher check requires PowerShell')
        helper = Path(__file__).resolve().parents[3]/'PC side/stemwise-playlist-builder/runtime-bootstrap.ps1'
        quote = lambda value: "'"+str(value).replace("'", "''")+"'"
        script = """
$ErrorActionPreference = 'Stop'
$calls = New-Object 'Collections.Generic.List[object]'
function global:wsl.exe {
    $calls.Add([object]@($args))
    if ($args -contains 'wslpath') { '/fixture/pc/builder' }
    $global:LASTEXITCODE = 0
}
. HELPER
$runtime = Get-StemWiseRuntime -BuilderWindows BUILDER
Invoke-StemWiseRuntime -Runtime $runtime -CommandArguments @('-m', 'stemwise_engine.doctor')
[pscustomobject]@{python=$runtime.Python; environment=$runtime.Environment; calls=$calls} | ConvertTo-Json -Depth 6 -Compress
""".replace('HELPER', quote(native_path(helper, shell=shell))).replace('BUILDER', quote(native_path(self.root/'builder', shell=shell)))
        checked = subprocess.run([shell, '-NoProfile', '-NonInteractive', '-EncodedCommand',
            base64.b64encode(script.encode('utf-16le')).decode('ascii')], capture_output=True, text=True, timeout=15, check=True)
        actual = json.loads(checked.stdout)
        self.assertEqual(actual['python'], '/fixture/pc/custom/primary/bin/python')
        self.assertIn('HF_HOME=/fixture/pc/custom/hf', actual['environment'])
        self.assertIn('STEMWISE_ALLIN1_PYTHON=/fixture/pc/custom/secondary/bin/python', actual['environment'])
        self.assertTrue(all(call[:2] == ['-d', 'StemWise-Test'] for call in actual['calls']))
        self.assertIn('/fixture/pc/custom/primary/bin/python', actual['calls'][-1])
        self.assertIn('stemwise_engine.doctor', actual['calls'][-1])

    def test_setup_field_entry_validates_layout_before_returning_paths(self):
        command = [sys.executable, '-B', '-m', 'stemwise_engine.runtime_layout', '--pc-root', str(self.root),
                   '--python-field', 'executable']
        completed = subprocess.run(command, capture_output=True, text=True, timeout=10)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout.strip(), self.layout['python']['executable'])
        self.layout['python']['executable'] = '../escape'
        self.write_layout()
        rejected = subprocess.run(command, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertEqual(rejected.stdout, '')

    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(dir=SUITE/'work',prefix='runtime-layout-')
        self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)
        self.layout={'schemaVersion':1,'platform':'linux-x86_64','wslDistribution':'Ubuntu',
            'python':{'version':'3.10.18','executable':'runtime/python/bin/python3.10','managedInstallDirectory':'runtime/uv-python'},
            'environments':{'main':'builder/.venv/bin/python'},
            'retainedResources':[{'path':'model-cache/wheel.whl','sha256':hashlib.sha256(b'approved wheel').hexdigest()}],
            'externalInputs':[
                {'path':'builder/config.json','required':True,'purpose':'Owner configuration'},
                {'path':'runtime/deno/deno','required':True,'purpose':'Pinned JavaScript runtime'},
                {'path':'optional-cache','required':False,'purpose':'Optional input'}]}
        for name,data in [('runtime/python/bin/python3.10',b'interpreter fixture'),
                          ('builder/.venv/bin/python',b'interpreter fixture'),
                          ('model-cache/wheel.whl',b'approved wheel'),
                          ('builder/config.json',b'{}'),('runtime/deno/deno',b'pinned runtime fixture')]:
            target=self.root/name
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(data)
        self.write_layout()

    def write_layout(self):
        (self.root/'runtime-layout.json').write_text(json.dumps(self.layout),encoding='utf-8')

    def inventory(self):
        commands=[]
        def version(command,**kwargs):
            self.assertEqual(['--version'],command[1:],'Inventory must never perform numerical inference')
            commands.append(command)
            return SimpleNamespace(returncode=0,stdout='Python 3.10.18')
        with patch.object(LAYOUT.subprocess,'run',side_effect=version):
            result=LAYOUT.inventory(self.root)
        self.assertEqual(2,len(commands))
        self.assertIn('no model inference was performed',result['modelReadiness'])
        return result

    def test_complete_inputs_are_ready_without_optional_inputs_or_inference(self):
        result=self.inventory()
        self.assertTrue(result['ready'])
        optional=next(item for item in result['externalInputs'] if not item['required'])
        self.assertFalse(optional['available'])

    def test_missing_config_fails_readiness_without_changing_inputs(self):
        (self.root/'builder/config.json').unlink()
        before={path.relative_to(self.root):path.read_bytes() for path in self.root.rglob('*') if path.is_file()}
        result=self.inventory()
        self.assertFalse(result['ready'])
        self.assertFalse(next(item for item in result['externalInputs'] if item['input']=='builder/config.json')['available'])
        self.assertEqual(before,{path.relative_to(self.root):path.read_bytes() for path in self.root.rglob('*') if path.is_file()})

    def test_missing_deno_fails_readiness(self):
        (self.root/'runtime/deno/deno').unlink()
        self.assertFalse(self.inventory()['ready'])

    def test_cli_refuses_missing_required_input_without_inference(self):
        (self.root/'runtime/deno/deno').unlink()
        output=io.StringIO()
        def version(command,**kwargs):
            self.assertEqual(['--version'],command[1:])
            return SimpleNamespace(returncode=0,stdout='Python 3.10.18')
        with patch.object(LAYOUT.subprocess,'run',side_effect=version), patch.object(sys,'argv',
                ['runtime_layout.py','--pc-root',str(self.root)]), contextlib.redirect_stdout(output):
            status=LAYOUT.main()
        self.assertEqual(2,status)
        self.assertFalse(json.loads(output.getvalue())['ready'])

    def test_missing_external_input_contract_cannot_report_ready(self):
        del self.layout['externalInputs']
        self.write_layout()
        with self.assertRaisesRegex(ValueError,'External inputs'):
            LAYOUT.load_layout(self.root)

    def test_required_flags_are_boolean(self):
        for required in (None,'true',1):
            with self.subTest(required=required):
                self.layout['externalInputs'][0]['required']=required
                self.write_layout()
                with self.assertRaisesRegex(ValueError,'Boolean'):
                    LAYOUT.load_layout(self.root)

    def test_external_input_paths_cannot_escape(self):
        for path in ('../outside','/outside','C:/outside',r'\\server\share',''):
            with self.subTest(path=path):
                self.layout['externalInputs'][0]['path']=path
                self.write_layout()
                with self.assertRaisesRegex(ValueError,'relative|outside'):
                    LAYOUT.load_layout(self.root)

    def test_external_symlink_cannot_escape_even_when_its_target_exists(self):
        with tempfile.TemporaryDirectory(dir=SUITE/'work',prefix='runtime-outside-') as outside:
            target=Path(outside)
            (target/'config.json').write_bytes(b'private fixture')
            link=self.root/'external-link'
            if os.name=='nt':
                shell=shutil.which('powershell.exe') or shutil.which('pwsh')
                self.assertIsNotNone(shell,'Supported Windows fixture requires PowerShell')
                literal=lambda value: "'"+str(value).replace("'","''")+"'"
                subprocess.run([shell,'-NoProfile','-NonInteractive','-Command',
                    'New-Item -ItemType Junction -Path '+literal(link)+' -Target '+literal(target)+' | Out-Null'],
                    capture_output=True,text=True,timeout=15,check=True)
            else:
                link.symlink_to(target,target_is_directory=True)
            self.layout['externalInputs'][0]['path']='external-link/config.json'
            self.write_layout()
            with self.assertRaisesRegex(ValueError,'outside'):
                LAYOUT.load_layout(self.root)
