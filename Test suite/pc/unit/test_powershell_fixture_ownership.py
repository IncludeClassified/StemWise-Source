"""The public fixture adapter owns descendants when a script times out."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from powershell_fixture import executable, literal_path, run_script
from process_fixture import process_birth, terminate_exact

SUITE = Path(__file__).resolve().parents[2]


class PowerShellFixtureOwnershipTest(unittest.TestCase):
    def test_qualification_shell_discovers_windows_pwsh_on_wsl_without_using_windows5(self):
        shell = '/mnt/c/Program Files/PowerShell/7/pwsh.exe'
        with patch('powershell_fixture.shutil.which', side_effect=lambda name: shell if name == 'pwsh.exe' else None) as discover:
            self.assertEqual(shell, executable())
            self.assertEqual(['pwsh', 'pwsh.exe'], [call.args[0] for call in discover.call_args_list])
        with patch('powershell_fixture.shutil.which', return_value=None):
            with self.assertRaisesRegex(RuntimeError, 'PowerShell 7.2'):
                executable()

    def test_windows_process_inspection_uses_its_actual_shell_for_paths_and_birth_fence(self):
        import process_fixture
        shell = '/mnt/c/WINDOWS/System32/WindowsPowerShell/v1.0/powershell.exe'
        source = r'C:\Fixture source\OwnedProcess.cs'
        with patch('process_fixture.shutil.which', return_value=shell), \
                patch('powershell_fixture.executable', side_effect=RuntimeError('Unrelated qualification shell unavailable')), \
                patch('powershell_fixture.native_path', return_value=source) as convert, \
                patch('owned_process.run', return_value=SimpleNamespace(stdout='True\n')) as execute:
            self.assertEqual('True', process_fixture._windows_control('TerminateExact', 123, 456))
            convert.assert_called_once_with(SUITE / 'host/OwnedProcess.cs', shell=shell)
            arguments = execute.call_args.args[0]
            self.assertEqual(shell, arguments[0])
            self.assertIn('[StemWise.Qualification.OwnedProcess]::TerminateExact(123,456)', arguments[-1])
            self.assertIn(source, arguments[-1])

    @unittest.skipUnless(os.name == 'nt', 'Native Windows Job adapter')
    def test_timeout_retires_script_descendant(self):
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
            root = Path(directory)
            child = root / 'child.py'
            receipt = root / 'pid.txt'
            child.write_text('from pathlib import Path\nimport os,time\nPath(' +
                             repr(str(receipt)) + ').write_text(str(os.getpid()))\ntime.sleep(60)',
                             encoding='utf-8')
            script = root / 'run.ps1'
            script.write_text(f'''$ErrorActionPreference='Stop'
$arguments = '-B "' + {literal_path(child)} + '"'
$p=Start-Process -WindowStyle Hidden -PassThru -FilePath {literal_path(sys.executable)} -ArgumentList $arguments
Start-Sleep -Seconds 60
''', encoding='utf-8-sig')
            try:
                with self.assertRaises((subprocess.TimeoutExpired, RuntimeError)):
                    run_script(script, timeout=2)
                self.assertTrue(receipt.exists(), 'Fixture did not reach its child launch')
                pid = int(receipt.read_text(encoding='utf-8-sig'))
                birth = process_birth(pid)
                self.assertIsNone(birth, 'Timed-out fixture left its child running')
            finally:
                if receipt.exists():
                    pid = int(receipt.read_text(encoding='utf-8-sig'))
                    terminate_exact(pid, process_birth(pid))
