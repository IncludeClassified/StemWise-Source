"""Exercise the qualification dispatch with isolated fixtures and external tools substituted."""
import contextlib
import io
import json
import os
import re
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from powershell_fixture import read_launcher_source, executable
ROOT = Path(__file__).resolve().parents[3]
SUITE = ROOT / 'Test suite'
LAUNCHER = SUITE / 'Run-Tester.ps1'
NATIVE = runpy.run_path(str(SUITE / 'device/run_native_device.py'))


def function_source(source, start, end):
    from powershell_fixture import native_path
    module = native_path(SUITE / 'host/Owned-Process.ps1').replace("'", "''")
    xml_start = source.index('function Read-HostQualificationXml')
    xml_end = source.index('function Read-JUnitQualification',xml_start)
    return ". '" + module + "'\n" + source[xml_start:xml_end] + source[source.index('function ' + start):source.index('function ' + end)]


def ps_literal(value):
    if isinstance(value, (Path, str)) and (isinstance(value, Path) or str(value).startswith('/')):
        from powershell_fixture import native_path
        value = native_path(value)
    return "'" + str(value).replace("'", "''") + "'"








class HarnessExecutionTest(unittest.TestCase):
    def powershell(self, body):
        from powershell_fixture import run_script
        with tempfile.TemporaryDirectory(dir=SUITE/'work') as directory:
            script=Path(directory)/'fixture.ps1'
            script.write_text("$ErrorActionPreference='Stop'\n"+body,encoding='utf-8-sig')
            completed=run_script(script,timeout=30)
            self.assertEqual(0,completed.returncode,completed.stdout+completed.stderr)

    def test_actions_initialize_artifacts_before_real_selection_and_validate_arguments(self):
        source=read_launcher_source(LAUNCHER)
        definitions=function_source(source,'Invoke-StandardDeviceSelection','Resolve-Adb')
        start=source.index('function Invoke-ActionSurfaceTests')
        definitions+=source[start:source.index("\nif ($Mode -in @('Pc'",start)]
        with tempfile.TemporaryDirectory(dir=SUITE/'work') as directory:
            root=Path(directory)
            evidence=ps_literal(SUITE/'host/Qualification-Evidence.ps1')
            report=ps_literal(SUITE/'host/Qualification-Report.ps1')
            profile=ps_literal(SUITE/'device/Device-Profile.ps1')
            self.powershell(definitions+f"""
. {ps_literal(SUITE / 'device/Focused-Checks.ps1')}
. {evidence}
. {report}
. {profile}
. {ps_literal(SUITE / 'device/Device-Lanes.ps1')}
$Suite={ps_literal(root)}
$Repo=Join-Path $Suite 'StemWise'
$Mode='Actions'; $DeviceLane='All'; $Serial='fixture'
$DevelopmentPackage='app.stemwise.development'
$DeviceProfile=Join-Path $Suite 'profile.json'
@{{serial='fixture';model='model';device='device';socModel='soc';androidBuildFingerprint='build';api=35}} | ConvertTo-Json | Set-Content -LiteralPath $DeviceProfile
Initialize-ApprovedDeviceProfile
function Resolve-Adb {{return 'fixture-adb'}}
function Invoke-AdbChecked($Adb,$AdbArguments) {{
 if ($Adb -cne 'fixture-adb' -or ($AdbArguments -join ',') -cne 'shell,cmd,statusbar,collapse' -or
     $env:ANDROID_SERIAL -cne $Serial) {{throw 'Wrong exact device setup command'}}
 $script:AdbSetupCalls++
}}
function Invoke-AdbReadOnlyProbe($Adb,$Arguments,$Label) {{
 switch ($Label) {{ 'state' {{'device'}} 'serial' {{'fixture'}} 'model' {{'model'}} 'device' {{'device'}} 'SoC model' {{'soc'}} 'build fingerprint' {{'build'}} 'API' {{'35'}} 'Android users' {{'UserInfo{{0:Owner:13}}'}} default {{throw "Unknown ADB query $Label"}} }}
}}
function Invoke-QualificationOutput {{
 if (($args[0..7] -join ',') -cne 'fixture-adb,-s,fixture,shell,pm,list,packages,--user' -or
     $args[8] -cne '0' -or $args[9] -cnotin @($DevelopmentPackage,"$DevelopmentPackage.test")) {{throw 'Wrong package admission query'}}
 $global:LASTEXITCODE=0
}}
$script:DeviceRunEvidenceRoot=Join-Path $Suite 'attempt'
$script:DeviceSelectionSequence=0
$script:DeviceLaneResults=[Collections.Generic.List[object]]::new()
$DeviceLaneManifest=Join-Path $Suite 'lanes.json'
$ActionSuiteClass='fixture.Actions'
$script:DeviceInventoryAccount=@{{methods=@()}}
$ConnectedDeviceResults=Join-Path $Repo 'app/build/results'
$ConnectedDeviceReports=Join-Path $Repo 'app/build/reports'
$Apk=Join-Path $Suite 'fixture.apk'
[IO.File]::WriteAllText($Apk,'fixture build bytes')
function Initialize-QualificationSourceSnapshot {{}}
function Assert-QualificationSourceUnchanged {{}}
function Initialize-FocusedArtifacts {{
 $script:Initializations++
 foreach ($Kind in @('application','androidTest')) {{
  $script:FrozenArtifacts["qualification|$Kind"]= [pscustomobject]@{{path=$Apk;evidence=@{{sha256=Get-FileSha256 $Apk;fileName='fixture-x86_64-development.apk';kind=$Kind}}}}
 }}
}}
function Invoke-DeviceInventoryValidation {{}}
function Reset-DisposableDevicePackages {{}}
function Get-UtpInfrastructureRetryEvidence {{return $null}}
function Invoke-Gradle($Arguments) {{
 if ($script:AdbSetupCalls -ne 1) {{throw 'Device setup did not precede Gradle'}}
 if (@($Arguments | Where-Object {{ $_ -match 'outputLongWarmGrace|profileCacheIo|audioReleaseQualification' }}).Count) {{throw 'Development execution used a retired compile toggle'}}
 if ($Arguments -cnotcontains '-Pandroid.testInstrumentationRunnerArguments.class=fixture.Check' -or
     $Arguments -cnotcontains '-Pstemwise.developmentTargetAbi=x86_64' -or
     $Arguments -cnotcontains '-Pandroid.testInstrumentationRunnerArguments.stemwiseDisposableTarget=true') {{throw 'Wrong selected arguments'}}
 $script:GradleCalls++
 throw 'fixture-gradle-stop'
}}
function Assert-InstrumentationMethodAccounting {{}}
foreach ($Scenario in @('valid','empty-children','wrong-serial')) {{
 $script:FrozenArtifacts=@{{}}; $script:Initializations=0; $script:GradleCalls=0; $script:AdbSetupCalls=0
 $Serial=if ($Scenario -ceq 'wrong-serial') {{'unapproved'}} else {{'fixture'}}
 $Children=if ($Scenario -ceq 'empty-children') {{@()}} else {{@('fixture.Check')}}
 @{{explicitSuites=@(@{{wrapper=$ActionSuiteClass;children=$Children}})}} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $DeviceLaneManifest
 $Failure=$null
 try {{Invoke-ActionSurfaceTests}} catch {{$Failure=$_.Exception.Message}}
 if ($Scenario -ceq 'valid') {{
  if ($script:Initializations -ne 1 -or $script:GradleCalls -ne 1 -or $Failure -notlike '*fixture-gradle-stop*') {{throw "Actions never admitted its initialized exact selection: $Failure"}}
 }} elseif ($Scenario -ceq 'empty-children') {{
  if ($script:GradleCalls -ne 0 -or $Failure -notlike '*no declared children*') {{throw "Empty Actions selection was admitted: $Failure"}}
 }} elseif ($script:Initializations -ne 0 -or $script:GradleCalls -ne 0 -or $Failure -notlike '*unapproved device serial*') {{throw "Invalid target reached Actions work: $Failure"}}
}}
""")

    def test_actual_action_and_settings_wrappers_preserve_categories_and_causes(self):
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
            self.powershell(f"""
. {ps_literal(LAUNCHER)} -Mode PcPlan -PcTest test_harness_execution | Out-Null
. {ps_literal(SUITE / 'device/Focused-Checks.ps1')}
$Suite={ps_literal(Path(directory))}
$Mode='NativeDevice'; $Serial='fixture'; $RunId='typed-wrapper'
$DeviceLaneManifest=Join-Path $Suite 'lanes.json'
$ActionSuiteClass='fixture.Actions'
@{{explicitSuites=@(@{{wrapper=$ActionSuiteClass;children=@('fixture.Check')}})}} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $DeviceLaneManifest -Encoding UTF8
function Require-Serial {{}}
function Initialize-ImmutableQualificationArtifacts {{}}
function Invoke-DeviceInventoryValidation {{}}
function Assert-InstrumentationMethodAccounting {{}}
function Initialize-QualificationSourceSnapshot {{}}
function Assert-QualificationSourceUnchanged {{}}
$script:DeviceInventoryAccount=@{{methods=@()}}
function Invoke-StandardDeviceSelection {{
 $Cause=if ($script:CaseKind -eq 'timeout') {{[TimeoutException]::new('original operation timeout')}} else {{[IO.IOException]::new('original operation failure')}}
 throw (New-QualificationException $Cause.Message $Cause $script:CaseKind)
}}
function Reset-DisposableDevicePackages {{
 $script:ResetCount++
 if ($script:BreakCleanup -and $script:ResetCount -eq 3) {{throw [IO.IOException]::new('original package cleanup failure')}}
}}
foreach ($CaseKind in @('timeout','output-limit','infrastructure','test-failure')) {{
 foreach ($BreakCleanup in @($false,$true)) {{
  $script:CaseKind=$CaseKind; $script:BreakCleanup=$BreakCleanup; $script:ResetCount=0
  $Failure=$null
  try {{Invoke-RecordedDeviceOperation {{Invoke-ActionSurfaceTests}} | Out-Null}} catch {{$Failure=$_.Exception}}
  $Expected=if ($BreakCleanup) {{'cleanup'}} else {{$CaseKind}}
  $Recorded=Get-Content -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'run.json') -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($null -eq $Failure -or $Recorded.failureKind -cne $Expected -or (Get-QualificationFailureKind $Failure) -cne $Expected) {{throw "Action kind lost: $CaseKind/$BreakCleanup"}}
  if ($Failure.ToString() -notlike '*original operation*' -or ($BreakCleanup -and $Failure.ToString() -notlike '*original package cleanup*')) {{throw 'Action causes lost'}}
 }}
}}
function Resolve-Adb {{'fixture'}}
function Restore-DeviceUnlocked {{}}
function Invoke-AdbReadOnlyProbe($Adb,$Arguments,$Label) {{
 switch ($Arguments[-1]) {{
  'stay_on_while_plugged_in' {{$script:Awake}}
  'zen_mode' {{$script:Zen}}
  default {{throw 'Unexpected probe'}}
 }}
}}
function Invoke-QualificationProcess($FilePath,$Arguments,$TimeoutSeconds) {{
 if ($script:BreakCleanup -and $Arguments -contains 'put') {{throw [IO.IOException]::new('original settings restoration failure')}}
 switch ($Arguments[2..($Arguments.Count-1)] -join ' ') {{
  'shell svc power stayon true' {{$script:Awake='7'}}
  'shell cmd notification set_dnd priority' {{$script:Zen='1'}}
  'shell settings put global stay_on_while_plugged_in 0' {{$script:Awake='0'}}
  'shell cmd notification set_dnd all' {{$script:Zen='0'}}
  default {{throw 'Unexpected settings mutation'}}
 }}
 return @{{ExitCode=0;Stdout='';Stderr=''}}
}}
foreach ($BreakCleanup in @($false,$true)) {{
 $script:BreakCleanup=$BreakCleanup; $script:Awake='0'; $script:Zen='0'; $Failure=$null
 try {{Invoke-WithDeviceTestSettings {{throw [TimeoutException]::new('original settings operation timeout')}}}} catch {{$Failure=$_.Exception}}
 $Expected=if ($BreakCleanup) {{'cleanup'}} else {{'timeout'}}
 if ($null -eq $Failure -or (Get-QualificationFailureKind $Failure) -cne $Expected) {{throw "Settings kind lost: $BreakCleanup $Failure"}}
 if ($Failure.ToString() -notlike '*original settings operation timeout*' -or ($BreakCleanup -and $Failure.ToString() -notlike '*original settings restoration failure*')) {{throw 'Settings causes lost'}}
}}
""")

    def native_run(self, abi, device_abi=None, apk_abi=None, case='active-channels', proof_override=None):
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
            directory = Path(directory)
            apk = directory / 'app.apk'
            libraries = ('libc++_shared.so',)
            with zipfile.ZipFile(apk, 'w') as archive:
                for name in libraries:
                    archive.writestr(f'lib/{apk_abi or abi}/{name}', name.encode())
            commands = []
            def run(command, **kwargs):
                commands.append(command)
                if 'getprop' in command:
                    output = device_abi or abi
                elif str(command[0]).endswith('clang++.exe'):
                    Path(command[command.index('-o') + 1]).write_bytes(b'runner')
                    output = ''
                else:
                    output = 'active-DSP parts=0\n' * 12 + 'PASS active-channel history, allocation, and target measurements'
                    if proof_override is not None:
                        output = proof_override
                return subprocess.CompletedProcess(command, 0, output, '')
            argv = ['run_native_device.py', '--adb', 'fixture-adb', '--serial', 'fixture-phone',
                    '--apk', str(apk), '--ndk', str(directory / 'ndk'),
                    '--output', str(directory / 'result'), '--abi', abi, '--case', case]
            def run_remote(adb, remote, command, runner, timeout):
                return runner(adb + ['shell'] + command, timeout=timeout)
            with patch.object(sys, 'argv', argv), patch.dict(NATIVE['main'].__globals__, {
                    'run_owned': run,
                    'run_remote': run_remote}):
                with contextlib.redirect_stdout(io.StringIO()):
                    try:
                        NATIVE['main']()
                    except (Exception, SystemExit) as error:
                        return error, commands
            self.assertEqual(libraries, tuple(name for name in libraries
                                             if (directory / 'result' / name).is_file()))
            return None, commands

    def test_native_executor_uses_matching_arm_and_x86_apk_libraries_and_compiler(self):
        for abi, target in (('arm64-v8a', 'aarch64-linux-android28'),
                            ('x86_64', 'x86_64-linux-android28')):
            with self.subTest(abi=abi):
                error, commands = self.native_run(abi)
                self.assertIsNone(error)
                compiler = [command for command in commands if '--target=' + target in command]
                self.assertEqual(1, len(compiler), commands)

    def test_native_active_channels_compiles_and_runs_production_dsp_on_exact_target(self):
        error, commands = self.native_run('arm64-v8a', case='active-channels')
        self.assertIsNone(error)
        compiler = next(c for c in commands if str(c[0]).endswith('clang++.exe'))
        for source in ('TimePitchProcessorTest.cpp', 'TimePitchProcessor.cpp', 'TimePitchTransition.cpp'):
            self.assertTrue(any(str(arg).endswith(source) for arg in compiler))
        self.assertTrue(any('--active-channels' in str(arg) for c in commands for arg in c))







    def test_native_executor_rejects_device_or_apk_abi_mismatch_before_compiling_or_pushing(self):
        for device_abi, apk_abi in (('arm64-v8a', 'x86_64'), ('x86_64', 'arm64-v8a')):
            with self.subTest(device_abi=device_abi, apk_abi=apk_abi):
                error, commands = self.native_run('x86_64', device_abi, apk_abi)
                self.assertIsInstance(error, RuntimeError)
                self.assertIn('ABI', str(error))
                self.assertFalse(any('push' in command or
                                     str(command[0]).endswith('clang++.exe') for command in commands))

    def test_recorded_checks_replace_prior_results_and_preserve_current_failure(self):
        from test_tester_launcher import launcher_functions
        recorder = launcher_functions()
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
            body = recorder + f"""
$Suite={ps_literal(Path(directory))}
$Mode='NativeDevice'
function Initialize-QualificationSourceSnapshot {{}}
function Assert-QualificationSourceUnchanged {{}}
$RunId='first'
Invoke-RecordedDeviceOperation {{
    Set-Content -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'obsolete.txt') -Value 'old result'
}}
$FirstRoot=$script:DeviceRunEvidenceRoot
$First=Get-Content -Raw -LiteralPath (Join-Path $FirstRoot 'run.json') | ConvertFrom-Json
if ($First.status -ne 'PASS') {{ throw 'The completed first check was not recorded.' }}
$RunId='second'
$Rejected=$false
try {{
    Invoke-RecordedDeviceOperation {{
        if (Test-Path -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'obsolete.txt')) {{ throw 'Stale result survived.' }}
        throw 'current check failed'
    }}
}} catch {{ $Rejected=$_.Exception.Message -like '*current check failed*' }}
if (-not $Rejected) {{ throw 'The current failure was hidden.' }}
$Result=Get-Content -Raw -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'run.json') | ConvertFrom-Json
if ($Result.status -ne 'FAIL' -or $Result.runId -ne 'second') {{ throw 'The latest report is incorrect.' }}
foreach ($Kind in @('timeout','cleanup','infrastructure')) {{
 try {{ Invoke-RecordedDeviceOperation {{ throw (New-QualificationException 'typed operation failure' $null $Kind) }} }} catch {{}}
 $Typed=Get-Content -Raw -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'run.json') | ConvertFrom-Json
 if ($Typed.status -ne 'FAIL' -or $Typed.failureKind -cne $Kind) {{throw "Failure kind lost: $Kind"}}
}}
if ($FirstRoot -ne $script:DeviceRunEvidenceRoot -or
    @(Get-ChildItem -LiteralPath (Join-Path $Suite 'evidence') -Directory).Count -ne 1) {{ throw 'Archived attempts remain.' }}
"""
            self.powershell(body)

    def test_pc_dispatch_rejects_skipped_or_empty_suites_in_every_runtime(self):
        source = read_launcher_source(LAUNCHER)
        definitions = function_source(source, 'Invoke-Checked', 'Convert-ToWslPath')
        definitions += function_source(source, 'Get-PcTestPlan', 'Resolve-NativeTools')
        for boundary in ('unit', 'windows-unit', 'main-alignment', 'lyrics-alignment'):
            for outcome in ('pass', 'fail', 'skip', 'empty'):
                with self.subTest(boundary=boundary, outcome=outcome):
                    with tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
                        directory = Path(directory)
                        fixture_suite = directory / 'suite'
                        builder = directory / 'builder'
                        builder.mkdir()
                        (builder / 'runtime-bootstrap.ps1').write_text('', encoding='utf-8')
                        (fixture_suite / 'pc/unit').mkdir(parents=True)
                        (fixture_suite / 'pc/alignment').mkdir()
                        if (SUITE / 'pc/run_tests.py').exists():
                            shutil.copyfile(SUITE / 'pc/run_tests.py', fixture_suite / 'pc/run_tests.py')
                            shutil.copyfile(SUITE / 'pc/test_timing.py', fixture_suite / 'pc/test_timing.py')
                        main = "import unittest\nfrom pathlib import Path\nclass Check(unittest.TestCase):\n def test_required(self):\n  self.assertEqual(2, 1 + 1)\n  Path(__file__).with_suffix('.ran').write_text('executed', encoding='utf-8')\n"
                        bad = ("import unittest\n@unittest.skip('required launcher unavailable')\n"
                               "class Check(unittest.TestCase):\n def test_required(self): pass\n"
                               if outcome == 'skip' else "import unittest\nclass Check(unittest.TestCase):\n def test_required(self): self.fail('required fixture failure')\n" if outcome == 'fail' else '')
                        (fixture_suite / 'pc/unit/test_fixture.py').write_text(
                            main, encoding='utf-8')
                        (fixture_suite / 'pc/unit/test_second_fixture.py').write_text(
                            bad if boundary == 'unit' and outcome != 'pass' else main, encoding='utf-8')
                        shutil.copyfile(SUITE / 'pc/test_plan.py', fixture_suite / 'pc/test_plan.py')
                        rules = {'unit/test_fixture': {'runtime': 'base', 'cpu': 'all'},
                                 'unit/test_second_fixture': {'runtime': 'base', 'cpu': 'all'},
                                 'unit/test_windows_fixture': {'runtime': 'windows', 'cpu': 'none'},
                                 'alignment/test_main_fixture': {'runtime': 'base', 'cpu': 'none'},
                                 'alignment/test_lyrics_fixture': {'runtime': 'lyrics', 'cpu': 'none'}}
                        (fixture_suite / 'pc/test-runtimes.json').write_text(json.dumps({'version': 1, 'modules': rules}), encoding='utf-8')
                        (fixture_suite / 'pc/unit/test_windows_fixture.py').write_text(
                            "import os\nif os.name != 'nt': raise RuntimeError('Windows check reached WSL')\n" +
                            (bad if boundary == 'windows-unit' and outcome != 'pass' else main), encoding='utf-8')
                        for name, required in (('test_main_fixture', 'core'), ('test_lyrics_fixture', 'lyrics')):
                            altered = boundary == ('lyrics-alignment' if required == 'lyrics' else 'main-alignment')
                            (fixture_suite / f'pc/alignment/{name}.py').write_text(
                                (f"import os\nif os.environ.get('STEMWISE_PC_TEST_RUNTIME') != '{required}':\n"
                                 " raise RuntimeError('incorrect runtime for required alignment test')\n")
                                + (bad if altered and outcome != 'pass' else main), encoding='utf-8')
                        external_python = ('return & $FixtureProcess -FilePath $Python -Arguments $ChildArguments -WorkingDirectory $LiveRoot -TimeoutSeconds 60' if os.name == 'nt' or
                            not executable().lower().endswith('.exe') else r'''
$WslExecutable=Join-Path $env:WINDIR 'System32/wsl.exe'
$MappedPython=& $FixtureProcess -FilePath $WslExecutable -Arguments @('-d','Ubuntu','--','wslpath','-u',$Python) -WorkingDirectory $LiveRoot -TimeoutSeconds 30
if ($MappedPython.ExitCode -ne 0) {throw 'Fixture Python mapping failed'}
$Python=$MappedPython.Stdout.Trim()
$ChildArguments=@($ChildArguments | ForEach-Object {
 if ($_ -match '^[A-Za-z]:') {
  $Mapped=& $FixtureProcess -FilePath $WslExecutable -Arguments @('-d','Ubuntu','--','wslpath','-u',$_) -WorkingDirectory $LiveRoot -TimeoutSeconds 30
  if ($Mapped.ExitCode -ne 0) {throw 'Fixture path mapping failed'}
  $Mapped.Stdout.Trim()
 } else { $_ }
})
return & $FixtureProcess -FilePath $WslExecutable -Arguments (@('-d','Ubuntu','--','env',"STEMWISE_PC_TEST_RUNTIME=$env:STEMWISE_PC_TEST_RUNTIME",$Python) + $ChildArguments) -WorkingDirectory $LiveRoot -TimeoutSeconds 60
''')
                        body = definitions + f"""
$Suite={ps_literal(fixture_suite)}
$PcBuilder={ps_literal(directory / 'builder')}
$LiveRoot={ps_literal(directory)}
$Python={ps_literal(sys.executable)}
function Convert-ToWslPath($Path) {{ return $Path }}
function Get-TestRuntimeLayout {{ @{{wslDistribution='Ubuntu'}} }}
function Get-StemWiseRuntime($BuilderWindows,$Route,[switch]$SkipInstalledCheck) {{
 @{{Distribution='Ubuntu';Environment=@();BootstrapPython='fixture-bootstrap';
   Python=$BuilderWindows+$(if($Route -ceq 'lyrics') {{'/.venv-lyrics-align/bin/python'}} else {{'/.venv/bin/python'}})}}
}}
$FixtureProcess=${{function:Invoke-QualificationProcess}}
$script:PcSelections=[Collections.Generic.List[string]]::new()
function Invoke-QualificationProcess($FilePath,$Arguments,$WorkingDirectory,$TimeoutSeconds) {{
    $DirectoryIndex=[Array]::IndexOf($Arguments,'--directory')
    if ($DirectoryIndex -ge 0) {{
        $Selected=@($Arguments[($DirectoryIndex+2)..($Arguments.Count-1)])
        if ($Selected.Count -ne 1) {{throw 'Full combined independent PC modules into one deadline'}}
        if ($TimeoutSeconds -ne 1800) {{throw 'Required PC invocation deadline changed'}}
        $script:PcSelections.Add($Selected[0])
    }}
    if ($FilePath -ne 'wsl.exe') {{
        return & $FixtureProcess -FilePath $FilePath -Arguments $Arguments -WorkingDirectory $LiveRoot -TimeoutSeconds 60
    }}
    $Index=[Array]::LastIndexOf($Arguments, '-B')
    if ($Index -lt 0) {{ throw 'Python invocation absent' }}
    $env:STEMWISE_PC_TEST_RUNTIME=if ($Arguments[$Index-1] -match '[\\/]\\.venv-lyrics-align[\\/]') {{ 'lyrics' }} else {{ 'core' }}
    $ChildArguments=$Arguments[$Index..($Arguments.Count-1)]
    {external_python}
}}
$Failure=$null
try {{ Invoke-PcTests }} catch {{ $Failure=$_.Exception }}
if ({'$true' if outcome == 'pass' else '$false'} -ne ($null -eq $Failure)) {{
 throw 'PC dispatch accepted a failed/empty/skipped required suite or rejected valid tests: '+$Failure
}}
 $Expected=@('test_fixture','test_second_fixture','test_windows_fixture','test_main_fixture','test_lyrics_fixture')
 if ('{outcome}' -ceq 'empty') {{
  if ($script:PcSelections.Count -ne 0 -or $Failure.Message -notlike '*Empty or duplicate PC test inventory*') {{throw 'Empty inventory reached execution or lost its rejection'}}
 }} elseif (($script:PcSelections -join ',') -cne ($Expected -join ',')) {{throw 'Full did not execute every classified module exactly once'}}
 if ('{outcome}' -ceq 'pass') {{
 foreach ($Module in $Expected) {{
  $Directory=if ($Module -in @('test_main_fixture','test_lyrics_fixture')) {{'alignment'}} else {{'unit'}}
  if (-not (Test-Path -LiteralPath (Join-Path $Suite "pc/$Directory/$Module.ran"))) {{throw "Required module never executed: $Module"}}
 }}
 }} elseif ('{outcome}' -ne 'empty') {{
  if ((Get-QualificationFailureKind $Failure) -cne 'test-failure' -or
      $Failure.InnerException -isnot [AggregateException] -or
      $Failure.InnerException.InnerExceptions.Count -ne 1) {{throw 'Required module failure did not retain exactly one classified cause'}}
  $ModuleFailure=$Failure.InnerException.InnerExceptions[0]
  $ExpectedModule=@{{unit='test_second_fixture';'windows-unit'='test_windows_fixture';'main-alignment'='test_main_fixture';'lyrics-alignment'='test_lyrics_fixture'}}['{boundary}']
 $ExpectedCause=if ('{outcome}' -ceq 'fail') {{'*required fixture failure*'}} else {{'*PC qualification rejected*'}}
  if ($ModuleFailure.Message -notlike "*/${{ExpectedModule}}:*" -or
      $ModuleFailure.InnerException.Data['stderr'] -notlike $ExpectedCause) {{throw "Original required module selector or failure was lost: $Failure"}}
}}
"""
                        self.powershell(body)

    def test_pc_timeout_names_current_case_and_retires_its_exact_worker(self):
        from owned_process import run, OwnedProcessError
        from process_fixture import process_birth
        with tempfile.TemporaryDirectory(dir=SUITE/'work') as directory:
            root = Path(directory)
            fixture = root/'test_waiting.py'
            fixture.write_text('import json,os,sys,time,unittest\nfrom pathlib import Path\n'
                f'sys.path.insert(0, {str(SUITE/"pc")!r})\n'
                'from process_fixture import process_birth\n'
                'class Check(unittest.TestCase):\n def test_current_check(self):\n'
                '  Path(__file__).with_suffix(".owner.json").write_text(json.dumps({"pid":os.getpid(),'
                '"birth":process_birth(os.getpid())}), encoding="utf-8")\n  time.sleep(30)\n', encoding='utf-8')
            with self.assertRaises(OwnedProcessError) as caught:
                run([sys.executable, '-B', str(SUITE/'pc/run_tests.py'), '--directory', str(root),
                    'test_waiting'], timeout=3, cwd=ROOT)
            self.assertEqual(caught.exception.failure_kind, 'timeout')
            self.assertIn('test_current_check (test_waiting.Check', caught.exception.stderr)
            owner = json.loads(fixture.with_suffix('.owner.json').read_text(encoding='utf-8'))
            self.assertNotEqual(process_birth(owner['pid']), owner['birth'])

    def test_recorded_attempt_retains_exact_built_artifacts_and_rejects_changed_bytes(self):
        from test_tester_launcher import launcher_functions
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
            self.powershell(launcher_functions() + f"""
$Suite={ps_literal(directory)}; $LiveRoot=$Suite; $Mode='Focused'; $Serial='fixture'; $RunId='artifact-check'
$Build=Join-Path $Suite 'build'
New-Item -ItemType Directory -Path $Build | Out-Null
function Initialize-QualificationSourceSnapshot {{}}
function Assert-QualificationSourceUnchanged {{}}
function Assert-ApkEvidenceRole($Evidence) {{}}
function Resolve-Python {{'fixture-python'}}
function Invoke-QualificationProcess {{[pscustomobject]@{{ExitCode=0;Stdout=('a'*64);Stderr=''}}}}
function Get-ApkEvidence($Path,$Role,$Kind) {{
 @{{role=$Role;kind=$Kind;fileName=[IO.Path]::GetFileName($Path);bytes=(Get-Item $Path).Length;sha256=(Get-FileSha256 $Path);buildEvidence=@()}}
}}
foreach ($Scenario in @('success','operation-failure','changed')) {{
 $App=Join-Path $Build 'app-arm64-v8a-development.apk'
 $Test=Join-Path $Build 'test-arm64-v8a-development.apk'
 [IO.File]::WriteAllText($App,'exact app bytes'); [IO.File]::WriteAllText($Test,'exact test bytes')
 $Before=@((Get-FileSha256 $App),(Get-FileSha256 $Test)); $Failure=$null
 try {{ Invoke-RecordedDeviceOperation {{
  $FrozenApp=Freeze-ApkArtifact $App 'qualification' 'application'
  $FrozenTest=Freeze-ApkArtifact $Test 'qualification' 'androidTest'
  if ($FrozenApp.path -cne $App -or $FrozenTest.path -cne $Test) {{throw 'Lost actual built artifact references'}}
  if ($Scenario -ceq 'operation-failure') {{throw 'original operation failed'}}
  if ($Scenario -ceq 'changed') {{[IO.File]::WriteAllText($App,'changed app')}}
 }} }} catch {{$Failure=$_}}
 $Report=Get-Content -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'run.json') -Raw | ConvertFrom-Json
 if (@($Report.artifacts).Count -ne 2 -or $Report.artifacts[0].sha256 -cne $Before[0] -or
     $Report.artifacts[1].sha256 -cne $Before[1] -or (Get-FileSha256 $Test) -cne $Before[1]) {{throw 'Lost recorded artifact identity'}}
 if ($Scenario -ceq 'success') {{
  if ($Failure -or $Report.status -cne 'PASS' -or (Get-FileSha256 $App) -cne $Before[0]) {{throw 'Valid source artifacts were not retained'}}
 }} elseif ($Scenario -ceq 'operation-failure') {{
  if (-not $Failure -or $Report.status -cne 'FAIL' -or $Failure.Exception.Message -notlike '*original operation failed*' -or
      (Get-FileSha256 $App) -cne $Before[0]) {{throw 'Failed attempt lost its cause or source artifact'}}
 }} elseif (-not $Failure -or $Report.failureKind -cne 'infrastructure' -or
           $Failure.Exception.Message -notlike '*changed during the attempt*') {{throw 'Changed source bytes were admitted'}}
}}
""")

    def test_recorded_attempt_rejects_linked_results_before_replacing_owner_bytes(self):
        from test_tester_launcher import launcher_functions
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
            self.powershell(launcher_functions() + f"""
$FixtureRoot={ps_literal(directory)}; $Mode='Focused'; $RunId='link-check'; $Serial='fixture'
foreach ($Boundary in @('evidence','current')) {{
 $Suite=Join-Path $FixtureRoot $Boundary
 $Outside=Join-Path $FixtureRoot "owner-$Boundary"
 New-Item -ItemType Directory -Path $Outside -Force | Out-Null
 $Marker=Join-Path $Outside 'owner.txt'; [IO.File]::WriteAllText($Marker,'owner bytes')
 $Link=if ($Boundary -ceq 'evidence') {{Join-Path $Suite 'evidence'}} else {{Join-Path $Suite 'evidence/current-Focused'}}
 New-Item -ItemType Directory -Path (Split-Path $Link) -Force | Out-Null
 New-Item -ItemType Junction -Path $Link -Target $Outside | Out-Null
 try {{
  $Failure=$null; $script:Entered=$false
  try {{Invoke-RecordedDeviceOperation {{$script:Entered=$true}}}} catch {{$Failure=$_}}
  if (-not $Failure -or $script:Entered -or $Failure.Exception.Message -notlike '*linked directory*' -or
      [IO.File]::ReadAllText($Marker) -cne 'owner bytes') {{throw 'Linked results reached cleanup or operation'}}
 }} finally {{[IO.Directory]::Delete($Link)}}
}}
""")

    def test_disposable_transcript_deletion_failure_preserves_verified_pass(self):
        from test_tester_launcher import launcher_functions
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
            self.powershell(launcher_functions() + f"""
$Suite={ps_literal(directory)}; $Mode='NativeDevice'; $RunId='cleanup-warning'; $Serial='fixture'
function Initialize-QualificationSourceSnapshot {{}}
function Assert-QualificationSourceUnchanged {{}}
function Remove-Item {{
 param([string]$LiteralPath,[switch]$Recurse,[switch]$Force)
 if ($LiteralPath.EndsWith('transcript.txt')) {{throw 'fixture deletion denied'}}
 Microsoft.PowerShell.Management\\Remove-Item @PSBoundParameters
}}
Invoke-RecordedDeviceOperation {{}}
$Report=Get-Content -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'run.json') -Raw | ConvertFrom-Json
if ($Report.status -cne 'PASS' -or $Report.failure -or $Report.failureKind -or
    @($Report.cleanupWarnings).Count -ne 1 -or $Report.cleanupWarnings[0] -notlike '*transcript.txt*fixture deletion denied*' -or
    -not (Test-Path -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'transcript.txt'))) {{throw 'Disposable cleanup changed verdict or lost retained path'}}
""")

    def test_process_fault_lane_dispatches_only_its_sole_guarded_invocation(self):
        source=read_launcher_source(LAUNCHER)
        definitions=function_source(source,'Invoke-NativeProcessFaultDeviceLane','Invoke-DeviceTests')
        with tempfile.TemporaryDirectory(dir=SUITE/'work') as directory:
            self.powershell(definitions+f"""
$DeviceLaneManifest=Join-Path {ps_literal(directory)} 'lanes.json'
$Exact='app.stemwise.playback.engine.NativeProcessFaultInstrumentationTest#processFaultEvidenceSurvivesRuntimeReplacement'
function Reset-DisposableDevicePackages {{$script:Resets++}}
function Get-DeviceLaneSelectors {{return $script:Selectors}}
function Invoke-StandardDeviceSelection($Selected,$Arguments,$EvidenceLabel) {{
 if (@($Selected).Count -ne 1 -or $Selected[0] -cne $Exact -or
     $Arguments.stemwiseIntentionalNativeFault -cne 'true' -or
     $Arguments.stemwiseDisposableTarget -cne 'true' -or
     $Arguments.ContainsKey('listener') -or $Arguments.ContainsKey('filter')) {{throw 'Fault was combined with health or admitted without guards'}}
 $script:Calls++
}}
foreach ($Scenario in @('valid','two','wrong','missing-guard')) {{
 $script:Calls=0; $script:Resets=0; $Caught=$null
 $script:Selectors=if ($Scenario -eq 'two') {{@($Exact,'another')}} elseif ($Scenario -eq 'wrong') {{@('another')}} else {{@($Exact)}}
 $Flag=if ($Scenario -eq 'missing-guard') {{'false'}} else {{'true'}}
 @{{nativeProcessFault=@{{selector=$Exact;arguments=@{{stemwiseIntentionalNativeFault='true';stemwiseDisposableTarget=$Flag}}}}}} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $DeviceLaneManifest
 try {{Invoke-NativeProcessFaultDeviceLane}} catch {{$Caught=$_.Exception.Message}}
 if ($Scenario -eq 'valid') {{
  if ($Caught -or $script:Calls -ne 1 -or $script:Resets -ne 2) {{throw "Valid isolated action failed: $Caught"}}
 }} elseif (-not $Caught -or $script:Calls -ne 0 -or $script:Resets -ne 1) {{throw "Unsafe fault action reached instrumentation: $Scenario"}}
}}
""")



if __name__ == '__main__':
    unittest.main()
