from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

SUITE = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(SUITE / 'pc'))
from powershell_fixture import native_path, run_script


class OwnedQualificationProcessTest(unittest.TestCase):
    def run_child(self, code, timeout=4, *, setup="", arguments=(), catch=False, batch=False, validate_json=False, working_directory=None, logged=False):
        shell = shutil.which('powershell.exe') or shutil.which('pwsh')
        if os.name != 'nt' and (not shell or not shell.lower().endswith('.exe')):
            self.skipTest('Windows process ownership requires Windows PowerShell')
        with tempfile.TemporaryDirectory(dir=SUITE / 'work',prefix="owned ' العربية-") as temporary:
            root = Path(temporary)
            child = root / 'child.py'
            child.write_text(code,encoding='utf-8')
            python = sys.executable if os.name == 'nt' else shutil.which('python.exe')
            self.assertIsNotNone(python,'Windows Python fixture dependency unavailable')
            module = native_path(SUITE / 'host/Owned-Process.ps1')
            executable = native_path(python)
            tokens = ['-u', native_path(child), *arguments]
            if batch:
                wrapper = root / "batch ' & العربية.cmd"
                wrapper.write_bytes(('@chcp 65001 >nul\n@echo off\n"' + native_path(python) + '" -u "' + native_path(child) + '" %*\n').replace('\n','\r\n').encode('utf-8'))
                executable = native_path(wrapper)
                tokens = list(arguments)
            script = root / 'run.ps1'
            invocation = "$result=Invoke-QualificationProcess -FilePath '" + executable.replace("'","''") + "' -Arguments @(" + ','.join("'" + item.replace("'","''") + "'" for item in tokens) + ") -TimeoutSeconds " + str(timeout)
            if working_directory:
                invocation += " -WorkingDirectory '" + working_directory.replace("'","''") + "'"
            if logged:
                log = native_path(root / 'output.log').replace("'", "''")
                invocation += " -OutputLog '" + log + "'"
                invocation += ";$log=[IO.File]::ReadAllText('" + log + "');$result=[ordered]@{exit=$result.ExitCode;outLength=$result.Stdout.Length;errLength=$result.Stderr.Length;outEnd=$result.Stdout.EndsWith('OUT-END');errEnd=$result.Stderr.EndsWith('ERR-END');logLength=$log.Length;outStart=$log.Contains('OUT-START');errStart=$log.Contains('ERR-START')}"
            if validate_json:
                invocation += ";$result=ConvertFrom-QualificationJson $result 'fixture'"
            if catch:
                invocation = "try {" + invocation + ";$result | ConvertTo-Json -Compress} catch {[ordered]@{kind=$_.Exception.Data['failureKind'];stdout=$_.Exception.Data['stdout'];stderr=$_.Exception.Data['stderr']} | ConvertTo-Json -Compress}"
            else:
                invocation += "\n$result | ConvertTo-Json -Compress"
            script.write_text("$ErrorActionPreference='Stop'\n. '" + module.replace("'","''") + "'\n" + setup + "\n" + invocation,encoding='utf-8-sig')
            return run_script(script,timeout=timeout+20)

    def test_json_stdout_and_warning_stderr_remain_separate(self):
        result = self.run_child('import sys\nprint(\'{"pass":true}\')\nprint("warning",file=sys.stderr)\n')
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        output = json.loads(result.stdout)
        self.assertTrue(json.loads(output['Stdout'])['pass'])
        self.assertEqual('warning',output['Stderr'].strip())

    def test_timeout_is_bounded_and_retains_partial_streams(self):
        started = time.monotonic()
        result = self.run_child('import time\nprint("partial",flush=True)\ntime.sleep(100)\n',timeout=1)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('Infrastructure timeout',result.stderr)
        self.assertLess(time.monotonic()-started,15)

    def test_unretired_output_readers_fail_closed_without_disposing_their_live_log(self):
        shell = shutil.which('powershell.exe') or shutil.which('pwsh')
        if os.name != 'nt' and (not shell or not shell.lower().endswith('.exe')):
            self.skipTest('Windows process ownership requires Windows PowerShell')
        from powershell_fixture import literal_path
        with tempfile.TemporaryDirectory(dir=SUITE / 'work', prefix='owned-reader-') as directory:
            root = Path(directory)
            child = root / 'child.py'
            child.write_text('print("retained reader output", flush=True)\n', encoding='utf-8')
            python = sys.executable if os.name == 'nt' else shutil.which('python.exe')
            self.assertIsNotNone(python)
            script = root / 'reader.ps1'
            script.write_text("$ErrorActionPreference='Stop'\n. " + literal_path(SUITE / 'host/Owned-Process.ps1') +
                "\n$Source=" + literal_path(SUITE / 'host/OwnedProcess.cs') +
                "\n$Python=" + literal_path(python) + "\n$Child=" + literal_path(child) +
                "\n$Log=" + literal_path(root / 'output.log') + r'''
$Code=[IO.File]::ReadAllText($Source).Replace('var buffer = new char[8192];',
 'System.Threading.Thread.Sleep(9000); var buffer = new char[8192];')
if($Code -ceq [IO.File]::ReadAllText($Source)) {throw 'Delayed-reader fault was not installed'}
Add-Type -TypeDefinition $Code
$Clock=[Diagnostics.Stopwatch]::StartNew(); $Failure=$null
try {Invoke-QualificationProcess $Python @('-u',$Child) -TimeoutSeconds 3 -OutputLog $Log} catch {$Failure=$_}
if($Clock.ElapsedMilliseconds -gt 8000 -or $null -eq $Failure -or
 (Get-QualificationFailureKind $Failure) -cne 'cleanup') {throw 'Unretired output ownership was not bounded and fatal'}
$ClosedEarly=$false
try {$File=[IO.File]::Open($Log,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$File.Dispose();$ClosedEarly=$true} catch {}
if($ClosedEarly) {throw 'Live reader log was disposed before reader retirement'}
$Again=$null
try {Invoke-QualificationProcess 'must-not-launch-after-reader-retirement-failure'} catch {$Again=$_}
if($null -eq $Again -or (Get-QualificationFailureKind $Again) -cne 'cleanup') {throw 'Output ownership failure admitted another launcher'}
$Retired=$false; $Wait=[Diagnostics.Stopwatch]::StartNew()
while(-not $Retired -and $Wait.ElapsedMilliseconds -lt 8000) {
 try {$File=[IO.File]::Open($Log,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$File.Dispose();$Retired=$true} catch {Start-Sleep -Milliseconds 20}
}
if(-not $Retired -or -not [IO.File]::ReadAllText($Log).Contains('retained reader output')) {throw 'Retired readers did not release and preserve their log'}
''', encoding='utf-8-sig')
            result = run_script(script, timeout=20)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_large_output_is_drained_but_capture_is_bounded(self):
        result = self.run_child('import sys\nsys.stdout.write("x" * (3 * 1024 * 1024))\nsys.stderr.write("diagnostic")\n')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('output limit',result.stderr)
        self.assertLess(len(result.stdout) + len(result.stderr), 2 * 1024 * 1024)

    def test_logged_verbose_output_retains_complete_streams_and_real_exit(self):
        for exit_code in (0, 23):
            with self.subTest(exit_code=exit_code):
                result = self.run_child('import sys\nsys.stdout.write("OUT-START"+"x"*(2*1024*1024)+"OUT-END")\nsys.stderr.write("ERR-START"+"y"*(2*1024*1024)+"ERR-END")\nsys.exit('+str(exit_code)+')', logged=True)
                self.assertEqual(0, result.returncode, result.stderr)
                value = json.loads(result.stdout)
                self.assertEqual(exit_code, value['exit'])
                self.assertEqual(1024 * 1024, value['outLength'])
                self.assertEqual(1024 * 1024, value['errLength'])
                self.assertTrue(value['outEnd'] and value['errEnd'])
                self.assertTrue(value['outStart'] and value['errStart'])
                self.assertGreater(value['logLength'], 4 * 1024 * 1024)

    def test_timeout_preserves_both_partial_streams_and_classification(self):
        result = self.run_child('import sys,time\nprint("partial-out",flush=True)\nprint("partial-err",file=sys.stderr,flush=True)\ntime.sleep(100)\n',timeout=1,catch=True)
        self.assertEqual(0,result.returncode,result.stderr)
        value=json.loads(result.stdout)
        self.assertEqual('timeout',value['kind'])
        self.assertEqual('partial-out',value['stdout'].strip())
        self.assertEqual('partial-err',value['stderr'].strip())

    def test_successful_parent_cannot_leave_a_detached_descendant(self):
        result = self.run_child('import subprocess,sys\np=subprocess.Popen([sys.executable,"-c","import time;time.sleep(100)"],creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)\nprint(p.pid,flush=True)\n')
        self.assertEqual(0,result.returncode,result.stderr)
        pid=int(json.loads(result.stdout)['Stdout'])
        shell=shutil.which('powershell.exe') or shutil.which('pwsh')
        probe=subprocess.run([shell,'-NoProfile','-NonInteractive','-Command',f'$p=Get-Process -Id {pid} -ErrorAction SilentlyContinue; if ($p -and -not $p.HasExited) {{exit 1}};exit 0'],capture_output=True,text=True,timeout=10)
        self.assertEqual(0,probe.returncode,probe.stderr)

    def test_batch_path_and_argument_shell_characters_survive(self):
        value="value & (x) العربية ' tail\\"
        result=self.run_child('import json,sys\nprint(json.dumps(sys.argv[1:]))\n',arguments=(value,),batch=True)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(0,json.loads(result.stdout)['ExitCode'],result.stdout)
        self.assertEqual([value],json.loads(json.loads(result.stdout)['Stdout']))

    def test_partial_setup_failure_is_bounded(self):
        started=time.monotonic()
        result=self.run_child('print("must not run")\n',working_directory='Z:\\missing-owned-process-fixture')
        self.assertNotEqual(0,result.returncode)
        self.assertNotIn('must not run',result.stdout)
        self.assertLess(time.monotonic()-started,10)

    def test_json_failure_retains_both_real_child_streams(self):
        for code in ('import sys\nprint("invalid-json")\nprint("warning",file=sys.stderr)\n',
                     'import sys\nprint("123")\nprint("warning",file=sys.stderr)\nsys.exit(7)\n'):
            with self.subTest(code=code):
                result=self.run_child(code,catch=True,validate_json=True)
                self.assertEqual(0,result.returncode,result.stderr)
                value=json.loads(result.stdout)
                self.assertEqual('infrastructure',value['kind'])
                self.assertTrue(value['stdout'].strip())
                self.assertEqual('warning',value['stderr'].strip())

    def test_python_adapter_timeout_leaves_an_unrelated_live_process_alone(self):
        host=native_path(SUITE/'host')
        code=('import json,subprocess,sys\n'
            + 'sys.path.insert(0,' + repr(host) + ')\nfrom owned_process import run,OwnedProcessError\n'
            + 'unrelated=subprocess.Popen([sys.executable,"-c","import time;time.sleep(100)"])\n'
            + 'try:\n try:\n  run([sys.executable,"-u","-c","import time;print(123,flush=True);time.sleep(100)"],timeout=1)\n'
            + ' except OwnedProcessError as error:\n  print(json.dumps({"kind":error.failure_kind,"partial":error.stdout,"unrelatedAlive":unrelated.poll() is None}))\n'
            + 'finally:\n unrelated.terminate();unrelated.wait(timeout=5)\n')
        result=self.run_child(code,timeout=8)
        self.assertEqual(0,result.returncode,result.stderr)
        outer=json.loads(result.stdout)
        self.assertEqual(0,outer['ExitCode'],outer['Stderr'])
        value=json.loads(outer['Stdout'])
        self.assertEqual('timeout',value['kind'])
        self.assertEqual('123',value['partial'].strip())
        self.assertTrue(value['unrelatedAlive'])

    def test_logged_buffer_boundary_is_visible_before_exit_and_preserves_unicode(self):
        from concurrent.futures import ThreadPoolExecutor
        from powershell_fixture import literal_path
        python = sys.executable if os.name == 'nt' else shutil.which('python.exe')
        self.assertIsNotNone(python, 'Windows Python fixture dependency unavailable')
        for encoding in ('utf-8-sig', 'utf-16', 'utf-16-be', 'utf-32', 'utf-32-be'):
            with self.subTest(encoding=encoding), tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
                root = Path(directory)
                text = 'العربية 😀 tail'
                data = text.encode(encoding)
                if encoding == 'utf-16-be': data = b'\xfe\xff' + data
                if encoding == 'utf-32-be': data = b'\x00\x00\xfe\xff' + data
                preamble = 3 if encoding == 'utf-8-sig' else 4 if encoding.startswith('utf-32') else 2
                expected_error = 'x' * (1024 - len('flushed-marker\n')) + 'flushed-marker\n'
                child = root / 'child.py'
                child.write_text('import pathlib,sys,time\nroot=pathlib.Path(sys.argv[1])\n'
                    + 'sys.stderr.buffer.write(' + repr(expected_error.encode()) + ');sys.stderr.buffer.flush()\n'
                    + 'data=' + repr(data) + '\n'
                    + 'sys.stdout.buffer.write(data[:' + str(preamble) + ']);sys.stdout.buffer.flush()\n'
                    + 'for value in data[' + str(preamble) + ':]:\n'
                    + ' sys.stdout.buffer.write(bytes([value]));sys.stdout.buffer.flush();time.sleep(.002)\n'
                    + '(root/"ready").touch()\ndeadline=time.monotonic()+8\n'
                    + 'while not (root/"release").exists():\n'
                    + ' if time.monotonic()>=deadline:raise TimeoutError("release missing")\n'
                    + ' time.sleep(.02)\n', encoding='utf-8')
                script = root / 'run.ps1'
                log = root / 'output.log'
                script.write_text("$ErrorActionPreference='Stop'\n[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)\n. " + literal_path(SUITE / 'host/Owned-Process.ps1')
                    + "\n$result=Invoke-QualificationProcess " + literal_path(python) + " @('-u',"
                    + literal_path(child) + ',' + literal_path(root) + ') -TimeoutSeconds 12 -OutputLog '
                    + literal_path(log) + '\n$result | ConvertTo-Json -Compress\n', encoding='utf-8-sig')
                with ThreadPoolExecutor(max_workers=1) as executor:
                    future = executor.submit(run_script, script, timeout=18)
                    try:
                        deadline = time.monotonic() + 8
                        while not (root / 'ready').exists() and not future.done() and time.monotonic() < deadline:
                            time.sleep(.02)
                        self.assertTrue((root / 'ready').exists(), 'Child never flushed its boundary output')
                        deadline = time.monotonic() + 2
                        while (not log.exists() or 'flushed-marker' not in log.read_text(encoding='utf-8')) and time.monotonic() < deadline:
                            time.sleep(.02)
                        self.assertFalse(future.done(), 'Child exited before the visibility observation')
                        self.assertIn('flushed-marker', log.read_text(encoding='utf-8'))
                    finally:
                        (root / 'release').touch()
                        result = future.result(timeout=25)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                captured = json.loads(result.stdout)
                self.assertEqual(captured['ExitCode'], 0)
                self.assertEqual(captured['Stdout'], text)
                self.assertEqual(captured['Stderr'], expected_error)
