from pathlib import Path
import hashlib
import os
import re
import runpy
import tempfile
import shutil
import sys
import subprocess
import unittest
import base64
import json


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import read_line
from powershell_fixture import read_launcher_source, executable
LIVE_ROOT = Path(__file__).resolve().parents[3]
LAUNCHER = LIVE_ROOT / "Test suite" / "Run-Tester.ps1"
BUILD_FILE = LIVE_ROOT / "StemWise" / "app" / "build.gradle.kts"
THIRD_PARTY_NOTICES = LIVE_ROOT / "STEMWISE_DOCUMENTATION.md"


def launcher_functions() -> str:
    # Load the real definitions without dispatching a lane. Private helper ordering
    # and extraction do not form a behavioral test contract.
    from powershell_fixture import literal_path
    suite = LIVE_ROOT / 'Test suite'
    paths = [LAUNCHER, *(suite / relative for relative in (
        'host/Qualification-Evidence.ps1', 'host/Qualification-Report.ps1',
        'device/Device-Lanes.ps1', 'device/Device-Inventory.ps1',
        'device/Device-Profile.ps1', 'device/Focused-Checks.ps1'))]
    return ". " + literal_path(suite / 'host/Owned-Process.ps1') + "\n" + r'''
foreach ($DefinitionPath in @(PATHS)) {
    $DefinitionErrors=$null; $DefinitionTokens=$null
    $DefinitionAst=[Management.Automation.Language.Parser]::ParseFile($DefinitionPath,[ref]$DefinitionTokens,[ref]$DefinitionErrors)
    if ($DefinitionErrors.Count) { throw $DefinitionErrors[0] }
    foreach ($Definition in $DefinitionAst.EndBlock.Statements) {
        if ($Definition -is [Management.Automation.Language.FunctionDefinitionAst]) {
            . ([scriptblock]::Create($Definition.Extent.Text))
        }
    }
}
'''.replace('PATHS', ','.join(literal_path(path) for path in paths))


def standard_selection_adb_stub() -> str:
    return r'''
$script:DeviceBoundaryEvents = [Collections.Generic.List[string]]::new()
$script:GradleBoundaryCalls = 0
$script:AdbSetupShouldFail = $false
$script:FixtureAirplane = 'disabled'
function Resolve-Adb { 'fixture-adb' }
function Assert-DisposableDevelopmentTarget {} # Admission is qualified independently; this fixture owns selection accounting.
function Restore-DeviceUnlocked {}
function Save-TargetCrashEvidence($Destination) {}
function Invoke-AdbChecked {
    param([string]$Adb, [string[]]$AdbArguments)
    if ($Adb -cne 'fixture-adb' -or $AdbArguments.Count -ne 4 -or
        ($AdbArguments -join ',') -cne 'shell,cmd,statusbar,collapse' -or
        $env:ANDROID_SERIAL -cne $Serial) {
        throw 'Unexpected external ADB setup command or target'
    }
    $script:DeviceBoundaryEvents.Add('adb')
    if ($script:AdbSetupShouldFail) { throw 'adb setup boom' }
}
$script:SelectionNativeProcess = ${function:Invoke-QualificationProcess}
function Invoke-QualificationProcess {
    param($FilePath, $Arguments, $WorkingDirectory, $TimeoutSeconds, $OutputLog, $StandardInput)
    if ($FilePath -cne 'fixture-adb') { return & $script:SelectionNativeProcess @PSBoundParameters }
    if (($Arguments[0..1] -join ',') -cne "-s,$Serial") { throw 'Wrong settings target' }
    switch ($Arguments[2..($Arguments.Count-1)] -join ',') {
        'shell,cmd,connectivity,airplane-mode,enable' {$script:FixtureAirplane='enabled'}
        'shell,cmd,connectivity,airplane-mode,disable' {$script:FixtureAirplane='disabled'}
        default {throw 'Unexpected settings command'}
    }
    return @{ExitCode=0;Stdout='';Stderr=''}
}
function Invoke-AdbReadOnlyProbe($Adb,$Arguments,$Label) {
    if (($Arguments -join ',') -cne 'shell,cmd,connectivity,airplane-mode') {throw 'Unexpected settings query'}
    return $script:FixtureAirplane
}
function Assert-AdbSetupBeforeGradle {
    if ($script:DeviceBoundaryEvents.Count -eq 0 -or
        $script:DeviceBoundaryEvents[$script:DeviceBoundaryEvents.Count - 1] -cne 'adb') {
        throw 'Gradle started before the exact ADB setup command'
    }
    $script:DeviceBoundaryEvents.Add('gradle')
    $script:GradleBoundaryCalls++
}
'''


class TesterLauncherPolicyTest(unittest.TestCase):
    def test_empty_crash_xml_is_retained_before_private_log_cleanup(self):
        from powershell_fixture import literal_path, run_script
        with tempfile.TemporaryDirectory(dir=LIVE_ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            script = root / 'crash.ps1'
            stub = standard_selection_adb_stub().replace('function Save-TargetCrashEvidence($Destination) {}', '')
            script.write_text(launcher_functions() + stub + '\n$FixtureRoot=' + literal_path(root) + r'''
$ErrorActionPreference='Stop'; $Mode='Focused'; $Serial='fixture'; $DevelopmentPackage='app.stemwise.development'
$Repo=Join-Path $FixtureRoot 'repo'; $ConnectedDeviceResults=Join-Path $Repo 'app/build/results'
$ConnectedDeviceReports=Join-Path $Repo 'app/build/reports'; $script:DeviceRunEvidenceRoot=Join-Path $FixtureRoot 'evidence'
$script:DeviceSelectionSequence=0; $PressureSeed=0; $PressureOperations=0
function Assert-QualificationSourceUnchanged {}
function Assert-FrozenArtifactsUnchanged {}
function Register-ApkEvidence($Evidence) {}
function Get-FrozenArtifact($Role,$Kind) {@{evidence=@{role=$Role;kind=$Kind;sha256=$Kind;fileName='fixture-arm64-v8a-development.apk'}}}
function Get-DevelopmentApkPairEvidence {@((Get-FrozenArtifact 'qualification' 'application').evidence,(Get-FrozenArtifact 'qualification' 'androidTest').evidence)}
function Invoke-QualificationProcess($FilePath,$Arguments,$TimeoutSeconds) {
 if($FilePath -cne 'fixture-adb' -or ($Arguments -join ',') -cne '-s,fixture,logcat,-b,crash,-d,-t,500,-v,long') {throw 'Unexpected crash command'}
 @{ExitCode=0;Stdout=@'
[ 11-03 04:03:13.987 444:444 E/AndroidRuntime ]
FATAL EXCEPTION: main
Process: app.stemwise.development, PID: 444
java.lang.AssertionError: actual target failure

[ 11-03 04:03:14.000 444:444 E/AndroidRuntime ]
FATAL EXCEPTION: main
Process: unrelated.private, PID: 444
unrelated secret

[ 11-03 04:04:13.987 6270:6270 F/DEBUG ]
*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***

[ 11-03 04:04:13.988 6270:6270 F/DEBUG ]
pid: 5708, tid: 5708, name: track  >>> app.stemwise.development <<<

[ 11-03 04:04:13.989 6270:6270 F/DEBUG ]
actual native target failure
'@}
}
function Invoke-Gradle($Arguments) {
 Assert-AdbSetupBeforeGradle
 New-Item -ItemType Directory -Path $ConnectedDeviceResults,$ConnectedDeviceReports -Force | Out-Null
 [IO.File]::WriteAllText((Join-Path $ConnectedDeviceResults 'TEST-crash.xml'),'<testsuite tests="0"><system-err>Process crashed</system-err></testsuite>')
 [IO.File]::WriteAllText((Join-Path $ConnectedDeviceResults 'logcat-private.txt'),'private')
 [IO.File]::WriteAllText((Join-Path $ConnectedDeviceReports 'failure.txt'),'actual report')
 throw (New-QualificationException 'Process crashed' $null 'test-failure')
}
try {Invoke-StandardDeviceSelection @('app.fixture.Crash') -EvidenceLabel 'crash'; throw 'Crash accepted'} catch {
 if($_.Exception.Message -notlike '*Process crashed*') {throw 'Original crash was lost'}
}
$Selection=Get-ChildItem -LiteralPath $script:DeviceRunEvidenceRoot -Filter selection.json -Recurse | Select-Object -First 1
$Receipt=Get-Content -LiteralPath $Selection.FullName -Raw | ConvertFrom-Json
if($Receipt.status -cne 'FAIL' -or -not $Receipt.resultsArchived -or -not $Receipt.reportsArchived) {throw 'Crash archives absent'}
$Xml=Join-Path $Selection.DirectoryName 'results/TEST-crash.xml'
if(-not (Test-Path -LiteralPath $Xml) -or ([xml](Get-Content -LiteralPath $Xml -Raw)).SelectNodes('//testcase').Count) {throw 'Actual empty XML was lost or invented'}
$Crash=Get-Content -LiteralPath (Join-Path $Selection.DirectoryName 'target-crash.txt') -Raw
if($Crash -notlike '*actual target failure*actual native target failure*' -or $Crash -like '*unrelated*' -or
   $Crash -notlike '*11-03 04:03:13.987*' -or $Crash -notlike '*may predate this selection*') {throw 'Target crash, timestamps or uncertainty lost; or unrelated records retained'}
if(@(Get-ChildItem -LiteralPath $FixtureRoot -Filter 'logcat-*.txt' -Recurse).Count) {throw 'Broad private logcat survived'}
''', encoding='utf-8-sig')
            result = run_script(script, timeout=25)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    @classmethod
    def setUpClass(cls) -> None:
        cls.source = read_launcher_source(LAUNCHER)

    def test_pc_plan_uses_execution_routing_without_importing_tests_or_writing_state(self):
        shell = executable()
        self.assertIsNotNone(shell)
        work = LIVE_ROOT / 'Test suite/work'
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name) / "owner's 資料"
            suite = root / 'Test suite'
            launcher = suite / 'Run-Tester.ps1'
            launcher.parent.mkdir(parents=True)
            launcher.write_text(self.source, encoding='utf-8-sig')
            shutil.copytree(LIVE_ROOT / 'Test suite/host', suite / 'host')
            (root / 'PC side/stemwise-playlist-builder').mkdir(parents=True)
            shutil.copyfile(LIVE_ROOT/'PC side/runtime-layout.json', root/'PC side/runtime-layout.json')
            shutil.copyfile(LIVE_ROOT/'PC side/stemwise-playlist-builder/runtime-bootstrap.ps1',
                            root/'PC side/stemwise-playlist-builder/runtime-bootstrap.ps1')
            (suite / 'pc').mkdir()
            shutil.copyfile(LIVE_ROOT / 'Test suite/pc/test_plan.py', suite / 'pc/test_plan.py')
            rules = {'alignment/test_future_song': {'runtime': 'lyrics', 'cpu': 'none'},
                     'alignment/test_roformer_cascade': {'runtime': 'base', 'cpu': 'none'},
                     'unit/test_packages': {'runtime': 'base', 'cpu': 'all'},
                     'unit/test_windows_fixture': {'runtime': 'windows', 'cpu': 'none'}}
            (suite / 'pc/test-runtimes.json').write_text(json.dumps({'version': 1, 'modules': rules}), encoding='utf-8')
            for module in rules:
                target = suite / 'pc' / (module + '.py')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("raise AssertionError('Planner imported a test')\nclass Check:\n def test_required(self): pass\n", encoding='utf-8')
            before = {path.relative_to(root): path.read_bytes() for path in root.rglob('*') if path.is_file()}
            native = str(launcher)
            if os.name != 'nt':
                native = subprocess.check_output(['wslpath', '-w', native], text=True, timeout=60).strip()
            exact = 'test_future_song.FutureSongTests.test_generated_task_uses_only_quoted_canonical_launcher_commands'
            selections = [exact, 'test_roformer_cascade', 'test_packages', 'test_windows_fixture.Check.test_required']
            def run(selectors):
                literal = lambda value: "'" + value.replace("'", "''") + "'"
                script = "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)\n& " + literal(native)
                script += ' -Mode PcPlan -PcTest @(' + ','.join(map(literal, selectors)) + ')'
                return subprocess.run([shell, '-NoProfile', '-NonInteractive', '-EncodedCommand',
                    base64.b64encode(script.encode('utf-16le')).decode('ascii')],
                    capture_output=True, timeout=30)
            result = run(selections)
            self.assertEqual(result.returncode, 0, result.stderr)
            plans = json.loads(result.stdout.decode('utf-8-sig'))
            self.assertEqual([plan['Runtime'] for plan in plans], ['lyrics', 'main', 'main', 'windows'])
            for selector, plan in zip(selections, plans):
                self.assertEqual(plan['Argv'][-1], selector)
                if plan['Runtime'] == 'windows':
                    self.assertTrue(plan['Interpreter'].lower().endswith('python.exe'))
                    self.assertEqual(plan['FilePath'], plan['Interpreter'])
                    self.assertEqual(plan['Arguments'], plan['Argv'])
                    self.assertTrue(plan['Argv'][1].replace('\\', '/').endswith('/Test suite/pc/run_tests.py'))
                else:
                    self.assertEqual(plan['FilePath'], 'wsl.exe')
                    layout = json.loads((root/'PC side/runtime-layout.json').read_text(encoding='utf-8'))
                    self.assertTrue(plan['Interpreter'].endswith('/'+layout['environments'][plan['Runtime']]))
                    self.assertEqual(plan['WorkingDirectory'], plan['Arguments'][3])
                    self.assertTrue(plan['Argv'][1].endswith('/Test suite/pc/run_tests.py'))
                self.assertIn("owner''s 資料", plan['Command'])
            self.assertNotEqual(run(['test_not_a_known_module']).returncode, 0)
            undeclared = suite/'pc/alignment/test_new_undeclared.py'
            undeclared.write_text("raise AssertionError('No implicit runtime')\n", encoding='utf-8')
            try:
                self.assertNotEqual(run([exact]).returncode, 0)
            finally:
                undeclared.unlink()
            self.assertEqual(before, {path.relative_to(root): path.read_bytes()
                                     for path in root.rglob('*') if path.is_file()})

    def test_native_wsl_path_conversion_preserves_unicode_and_caller_encodings(self):
        definitions = launcher_functions()
        from powershell_fixture import literal_path
        definitions += '\n$PcBuilder='+literal_path(LIVE_ROOT/'PC side/stemwise-playlist-builder')+'\n'
        script = definitions + r'''
$ErrorActionPreference = 'Stop'
foreach ($CodePage in @(437, 65001)) {
    [Console]::OutputEncoding = [Text.Encoding]::GetEncoding($CodePage)
    $OriginalOutput = $OutputEncoding
    foreach ($Suffix in @('with spaces', ('with spaces/' + [char]0x8cc7 + [char]0x6599))) {
        $Windows = 'C:/StemWise Space/' + $Suffix + '/Test suite'
        $Expected = '/mnt/c/StemWise Space/' + $Suffix + '/Test suite'
        $Actual = Convert-ToWslPath $Windows
        if ($Actual -cne $Expected) { throw 'Native wslpath output was corrupted' }
        if ([Console]::OutputEncoding.CodePage -ne $CodePage -or $OutputEncoding -ne $OriginalOutput) {
            throw 'Path conversion changed caller encoding'
        }
    }
}
'''
        shells = {executable()}
        for shell in sorted(shells):
            with self.subTest(shell=shell):
                result = subprocess.run([shell, '-NoProfile', '-NonInteractive', '-EncodedCommand',
                    base64.b64encode(script.encode('utf-16le')).decode('ascii')],
                    capture_output=True, timeout=40)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)


    def test_host_inventory_discovers_methods_and_rejects_duplicate_identities(self) -> None:
        inventory = runpy.run_path(str(LIVE_ROOT / "Test suite/host/host_inventory.py"))
        discover = inventory["discover"]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            discover.__globals__["SOURCE_ROOTS"] = (root,)
            fixture = "package app.test\nclass Example {\n@Test fun works() {}\n}\n"
            (root / "Example.kt").write_text(fixture, encoding="utf-8")
            self.assertEqual(["app.test.Example#works"], discover())
            (root / "Duplicate.kt").write_text(fixture, encoding="utf-8")
            with self.assertRaisesRegex(inventory["InventoryError"], "Duplicate Host test identities"):
                discover()






    def test_direct_instrumentation_rejects_skips_and_accepts_success(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        validator = launcher_functions()
        script = validator + r'''
$Skipped = @'
INSTRUMENTATION_STATUS_CODE: -4
Time: 0.01
OK (1 test)
'@
$Passed = @'
INSTRUMENTATION_STATUS_CODE: 0
Time: 0.01
OK (1 test)
'@
$Empty = @'
Time: 0.01
OK (0 tests)
'@
if ((Test-DirectInstrumentationPass 0 $Skipped) -or
    (Test-DirectInstrumentationPass 0 $Empty) -or
    -not (Test-DirectInstrumentationPass 0 $Passed)) { exit 1 }
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_cleanup_distinguishes_absent_package_from_failed_probe(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        validator = launcher_functions()
        script = validator + r'''
Assert-AdbPackageProbeSucceeded 0 'app.absent' @()
if (Test-AdbPackageListed 'app.absent' @()) { exit 1 }
if (-not (Test-AdbPackageListed 'app.target' @('package:app.target'))) { exit 2 }
if (Test-AdbPackageListed 'app.target' @('package:app.target.test')) { exit 3 }
$Threw = $false
try {
    Assert-AdbPackageProbeSucceeded 1 'app.unknown' @('device offline')
} catch {
    $Threw = $true
}
if (-not $Threw) { exit 4 }
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_device_mutex_rejects_a_second_process(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        mutex = launcher_functions()
        # This contract checks cross-process exclusion without touching a phone.
        mutex += "\nfunction Require-Serial {}\nfunction Invoke-WithDeviceTestSettings($Action) { & $Action }\n"
        serial = f"TESTER-MUTEX-{os.getpid()}"
        first_script = mutex + f"\n$Serial = '{serial}'\n$Mode = 'Device'\n" + r'''
Invoke-WithDeviceMutex {
    [Console]::Out.WriteLine('LOCKED')
    [Console]::Out.Flush()
    $Release = [Console]::In.ReadLineAsync()
    if (-not $Release.Wait(90000) -or $Release.Result -cne 'RELEASE') { throw 'Mutex holder release handshake failed' }
}
'''
        first = subprocess.Popen(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", first_script],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            self.assertEqual("LOCKED", read_line(first.stdout,timeout=10).strip())
            second_script = mutex + f"\n$Serial = '{serial}'\n$Mode = 'Device'\n" + r'''
Invoke-WithDeviceMutex { Write-Output 'SECOND' }
'''
            second = subprocess.run(
                [powershell, "-NoProfile", "-NonInteractive", "-Command", second_script],
                capture_output=True,
                text=True,
                check=False,
            timeout=60)
            self.assertNotEqual(0, second.returncode)
            self.assertIn(
                "already using device", second.stdout + second.stderr
            )
        finally:
            try:
                stdout, stderr = first.communicate(input="RELEASE\n", timeout=10)
            except subprocess.TimeoutExpired:
                first.kill()
                stdout, stderr = first.communicate()
                self.fail("Mutex holder did not finish:\n" + stdout + stderr)
        self.assertEqual(0, first.returncode, stdout + stderr)

    def test_workspace_mutex_rejects_a_second_process(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        mutex = launcher_functions()
        workspace = f"C:\\StemWise-Tester-Mutex-{os.getpid()}"
        first_script = mutex + f"\n$LiveRoot = '{workspace}'\n" + r'''
Invoke-WithWorkspaceMutex {
    [Console]::Out.WriteLine('LOCKED')
    [Console]::Out.Flush()
    $Release = [Console]::In.ReadLineAsync()
    if (-not $Release.Wait(90000) -or $Release.Result -cne 'RELEASE') { throw 'Mutex holder release handshake failed' }
}
'''
        first = subprocess.Popen(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", first_script],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            self.assertEqual("LOCKED", read_line(first.stdout,timeout=10).strip())
            second_script = mutex + f"\n$LiveRoot = '{workspace}'\n" + r'''
Invoke-WithWorkspaceMutex { Write-Output 'SECOND' }
'''
            second = subprocess.run(
                [powershell, "-NoProfile", "-NonInteractive", "-Command", second_script],
                capture_output=True,
                text=True,
                check=False,
            timeout=60)
            self.assertNotEqual(0, second.returncode)
            self.assertIn(
                "already using workspace", second.stdout + second.stderr
            )
        finally:
            try:
                stdout, stderr = first.communicate(input="RELEASE\n", timeout=10)
            except subprocess.TimeoutExpired:
                first.kill()
                stdout, stderr = first.communicate()
                self.fail("Workspace mutex holder did not finish:\n" + stdout + stderr)
        self.assertEqual(0, first.returncode, stdout + stderr)




    def test_recorded_device_operation_writes_failure_transcript_and_json(self) -> None:
        from powershell_fixture import literal_path
        powershell = executable()
        self.assertIsNotNone(powershell)
        recorder = launcher_functions()
        script = recorder + r'''
$ErrorActionPreference = 'Stop'
$TempBase = [IO.Path]::GetFullPath(SCRATCH_ROOT)
$Suite = Join-Path $TempBase ('stemwise-recorder-' + [guid]::NewGuid().ToString('N'))
$Suite = [IO.Path]::GetFullPath($Suite)
$LiveRoot = $Suite
$Mode = 'Device'
$Serial = 'TEST-SERIAL'
$RunId = 'TEST-RUN'
function Initialize-QualificationSourceSnapshot { $script:QualificationSourceSha256 = 'FIXTURE' }
function Assert-QualificationSourceUnchanged {}
function Assert-FrozenArtifactsUnchanged {}
function Resolve-Python { return 'fixture-python' }
$script:IdentityCalls = 0
function Invoke-QualificationProcess($FilePath, $Arguments, $WorkingDirectory, $TimeoutSeconds) {
    if ($FilePath -cne 'fixture-python' -or $Arguments[-1] -cne 'identity') { throw 'Unexpected recorder process' }
    $script:IdentityCalls++
    return @{ ExitCode = 0; Stdout = ('a' * 64) }
}
function Get-FrozenArtifact($Role, $Kind) {
    if ($Role -cne 'qualification' -or $Kind -cnotin @('application', 'androidTest')) { throw 'Unexpected recorder artifact' }
    $Path = Join-Path $Suite "$Kind.apk"
    return @{ path = $Path; evidence = @{ sha256 = Get-FileSha256 $Path;
        buildConfiguration = @{ buildType = 'development' }; buildEvidence = @() } }
}
try {
    New-Item -ItemType Directory -Path $Suite | Out-Null
    foreach ($Kind in @('application', 'androidTest')) {
        Set-Content -LiteralPath (Join-Path $Suite "$Kind.apk") -Value "fixture $Kind" -Encoding utf8NoBOM
    }
    $Caught = $null
    try {
        Invoke-RecordedDeviceOperation {
            Write-Host 'DURABLE_MARKER'
            throw 'fixture failure'
        }
    } catch {
        $Caught = $_.Exception.Message
    }
    $RunFiles = @(Get-ChildItem -LiteralPath $Suite -Filter run.json -Recurse -File)
    $TranscriptFiles = @(
        Get-ChildItem -LiteralPath $Suite -Filter transcript.txt -Recurse -File
    )
    if ($RunFiles.Count -ne 1 -or $TranscriptFiles.Count -ne 1) { exit 1 }
    $Result = Get-Content -LiteralPath $RunFiles[0].FullName -Raw | ConvertFrom-Json
    $Transcript = Get-Content -LiteralPath $TranscriptFiles[0].FullName -Raw
    if ($Transcript -match '(?m)^Host Application:') { exit 6 }
    if ($Result.status -ne 'FAIL' -or $Result.failure -cne 'fixture failure' -or
        $Result.failureKind -cne 'test-failure' -or
        [string]::IsNullOrWhiteSpace([string]$Result.attemptId)) {
        exit 2
    }
    if ($script:IdentityCalls -ne 2 -or $Result.audioSourceSha256 -cne ('a' * 64) -or
        $Result.audioSourceEndSha256 -cne ('a' * 64) -or $Result.audioArtifacts.Count -ne 2) { exit 4 }
    foreach ($Artifact in $Result.audioArtifacts) {
        if ($Artifact.sha256 -cne (Get-FileSha256 (Join-Path $Suite "$($Artifact.kind).apk")).ToLowerInvariant() -or
            $Artifact.sha256 -cne (Get-FileSha256 (Join-Path $LiveRoot $Artifact.path)).ToLowerInvariant()) { exit 5 }
    }
    if ($Transcript -notlike '*DURABLE_MARKER*' -or $Caught -notlike '*fixture failure*') {
        exit 3
    }
} finally {
    if ($Suite.StartsWith($TempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $Suite)) {
        Remove-Item -LiteralPath $Suite -Recurse -Force
    }
}
'''.replace('SCRATCH_ROOT', literal_path(LIVE_ROOT / 'Test suite/work'))
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_checked_native_command_transcribes_output_and_preserves_exit_code(self) -> None:
        from powershell_fixture import literal_path
        powershell = executable()
        self.assertIsNotNone(powershell)
        checked = launcher_functions()
        script = checked + r'''
$Transcript = Join-Path ([IO.Path]::GetTempPath()) (
    'stemwise-checked-' + [guid]::NewGuid().ToString('N') + '.txt'
)
try {
    $ErrorActionPreference = 'Stop'
    Start-Transcript -Path $Transcript -Force | Out-Null
    try {
        Invoke-Checked $env:ComSpec @(
            '/d', '/c', 'echo HOST_STDOUT & echo HOST_STDERR 1>&2 & exit /b 7'
        ) ([IO.Path]::GetTempPath())
    } catch {
        $Caught = $_.Exception.Message
    } finally {
        Stop-Transcript | Out-Null
    }
    $Text = Get-Content -LiteralPath $Transcript -Raw
    if ($Caught -notlike '*exit code 7*' -or
        $Text -notlike '*HOST_STDOUT*' -or $Text -notlike '*HOST_STDERR*') {
        exit 1
    }
} finally {
    if (Test-Path -LiteralPath $Transcript) {
        Remove-Item -LiteralPath $Transcript -Force
    }
}
'''
        with tempfile.TemporaryDirectory(dir=LIVE_ROOT / 'Test suite/work') as directory:
            completed = subprocess.run(
                [powershell, "-NoProfile", "-NonInteractive", "-Command",
                 '$Suite=' + literal_path(directory) + '\n' + script],
                capture_output=True,
                text=True,
                check=False,
                timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_host_qualification_runs_fresh_tasks_without_presentation_reports(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        qualification = launcher_functions()
        script = qualification + r'''
$TempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$Repo = Join-Path $TempBase ('stemwise-host-qualification-' + [guid]::NewGuid().ToString('N'))
$Repo = [IO.Path]::GetFullPath($Repo)
$Build = Join-Path $Repo 'app\build'
$script:DeviceRunEvidenceRoot = Join-Path $Repo 'evidence\attempt'
$script:Calls = [Collections.Generic.List[string]]::new()
function Invoke-Gradle([string[]]$Arguments) {
    $script:Calls.Add(($Arguments -join ' '))
    if ($Arguments[0] -eq ':app:testDevelopmentUnitTest') {
        $Results = Join-Path $Build 'test-results\testDevelopmentUnitTest'
        $Report = Join-Path $Build 'reports\tests\testDevelopmentUnitTest'
        New-Item -ItemType Directory -Path $Results, $Report -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $Results 'TEST-first.xml') -Value @'
<testsuite tests="3" failures="0" errors="0" skipped="0">
  <testcase classname="fixture.First" name="one" />
  <testcase classname="fixture.First" name="two" />
  <testcase classname="fixture.First" name="three" />
</testsuite>
'@
        Set-Content -LiteralPath (Join-Path $Results 'TEST-second.xml') -Value @'
<testsuite tests="2" failures="0" errors="0" skipped="0">
  <testcase classname="fixture.Second" name="four" />
  <testcase classname="fixture.Second" name="five" />
</testsuite>
'@
        $EngineResults = Join-Path $Repo 'audio-engine\build\test-results\testDevelopmentUnitTest'
        New-Item -ItemType Directory -Path $EngineResults -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $EngineResults 'TEST-engine.xml') -Value @'
<testsuite tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="fixture.Engine" name="six" />
</testsuite>
'@
        return
    }
    $Reports = Join-Path $Build 'reports'
    New-Item -ItemType Directory -Path $Reports -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $Reports 'lint-results-development.xml') -Value (
        '<issues><issue severity="Warning"/><issue severity="Hint"/></issues>'
    )
}
function Get-HostExpectedMethods {
    return @(
        'fixture.First#one', 'fixture.First#two', 'fixture.First#three',
        'fixture.Second#four', 'fixture.Second#five', 'fixture.Engine#six'
    )
}
try {
    $StaleResults = Join-Path $Build 'test-results\testDevelopmentUnitTest'
    $StaleReports = Join-Path $Build 'reports'
    $StaleEngineResults = Join-Path $Repo 'audio-engine\build\test-results\testDevelopmentUnitTest'
    New-Item -ItemType Directory -Path $StaleResults, $StaleReports, $StaleEngineResults -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $StaleResults 'TEST-stale.xml') -Value 'stale'
    Set-Content -LiteralPath (Join-Path $StaleEngineResults 'TEST-stale.xml') -Value 'stale'
    Set-Content -LiteralPath (Join-Path $StaleReports 'unrelated.txt') -Value 'keep'
    Invoke-HostQualification
    if ($script:Calls.Count -ne 2 -or
        $script:Calls[0] -ne ':app:testDevelopmentUnitTest --rerun :audio-engine:testDevelopmentUnitTest --rerun' -or
        $script:Calls[1] -ne ':app:lintDevelopment --rerun') { exit 1 }
    if ($script:HostQualification.unitTests.counts.suites -ne 3 -or
        $script:HostQualification.unitTests.counts.tests -ne 6 -or
        $script:HostQualification.unitTests.counts.skipped -ne 0 -or
        $script:HostQualification.lint.counts.issues -ne 2 -or
        $script:HostQualification.lint.counts.warnings -ne 1 -or
        $script:HostQualification.lint.counts.hints -ne 1) { exit 2 }
    $Archive = Join-Path $script:DeviceRunEvidenceRoot 'host-qualification'
    foreach ($Relative in @(
        'unit-tests\results\TEST-first.xml',
        'unit-tests\results\TEST-second.xml',
        'unit-tests\results\TEST-engine.xml',
        'lint\lint-results-development.xml'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $Archive $Relative) -PathType Leaf)) {
            exit 3
        }
    }
    if ((Test-Path -LiteralPath (Join-Path $StaleResults 'TEST-stale.xml')) -or
        (Test-Path -LiteralPath (Join-Path $StaleEngineResults 'TEST-stale.xml'))) { exit 4 }
    $Inventory = Get-Content -LiteralPath (Join-Path $Archive 'unit-tests\inventory.json') -Raw | ConvertFrom-Json
    if ($Inventory.expectedCount -ne 6 -or $Inventory.observedCount -ne 6) { exit 8 }
    if ((Test-Path -LiteralPath (Join-Path $Archive 'unit-tests\report')) -or
        (Test-Path -LiteralPath (Join-Path $Archive 'lint\lint-results-development.html'))) { exit 7 }
    if (-not (Test-Path -LiteralPath (Join-Path $StaleReports 'unrelated.txt'))) { exit 5 }
    $Rejected = $false
    try { Remove-ValidatedHostQualificationOutputs @('reports\unrelated.txt') }
    catch { $Rejected = $_.Exception.Message -like '*unapproved*' }
    if (-not $Rejected -or
        -not (Test-Path -LiteralPath (Join-Path $StaleReports 'unrelated.txt'))) { exit 6 }
} finally {
    if ($Repo.StartsWith($TempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $Repo)) {
        Remove-Item -LiteralPath $Repo -Recurse -Force
    }
}
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_host_qualification_fails_closed_when_junit_xml_is_missing(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        qualification = launcher_functions()
        script = qualification + r'''
$TempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$Repo = Join-Path $TempBase ('stemwise-host-missing-' + [guid]::NewGuid().ToString('N'))
$Repo = [IO.Path]::GetFullPath($Repo)
$Build = Join-Path $Repo 'app\build'
$script:DeviceRunEvidenceRoot = Join-Path $Repo 'evidence\attempt'
$script:Calls = 0
function Invoke-Gradle([string[]]$Arguments) {
    $script:Calls++
    if ($Arguments[0] -eq ':app:testDevelopmentUnitTest') {
        New-Item -ItemType Directory -Path (
            Join-Path $Build 'test-results\testDevelopmentUnitTest'
        ), (Join-Path $Build 'reports\tests\testDevelopmentUnitTest') -Force | Out-Null
        Set-Content -LiteralPath (
            Join-Path $Build 'reports\tests\testDevelopmentUnitTest\index.html'
        ) -Value '<html>tests</html>'
        $EngineResults = Join-Path $Repo 'audio-engine\build\test-results\testDevelopmentUnitTest'
        New-Item -ItemType Directory -Path $EngineResults -Force | Out-Null
        if ($script:Missing -eq 'audio-engine') {
            Set-Content -LiteralPath (Join-Path $Build 'test-results\testDevelopmentUnitTest\TEST-app.xml') -Value @'
<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="fixture.App" name="one" /></testsuite>
'@
        }
        if ($script:Missing -eq 'app') {
            Set-Content -LiteralPath (Join-Path $EngineResults 'TEST-engine.xml') -Value @'
<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="fixture.Engine" name="one" /></testsuite>
'@
        }
        return
    }
    $Reports = Join-Path $Build 'reports'
    New-Item -ItemType Directory -Path $Reports -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $Reports 'lint-results-development.xml') -Value '<issues/>'
    Set-Content -LiteralPath (Join-Path $Reports 'lint-results-development.txt') -Value 'No issues found.'
    Set-Content -LiteralPath (Join-Path $Reports 'lint-results-development.html') -Value '<html>lint</html>'
}
function Get-HostExpectedMethods { @('fixture.App#one', 'fixture.Engine#one') }
try {
    foreach ($script:Missing in @('both', 'audio-engine', 'app')) {
        $script:Calls = 0
        $script:DeviceRunEvidenceRoot = Join-Path $Repo ("evidence\attempt-" + $script:Missing)
        $Failure = $null
        try { Invoke-HostQualification } catch { $Failure = $_.Exception.Message }
        $Expected = if ($script:Missing -eq 'app') { '*identity accounting failed*' }
            else { '*Audio-engine JUnit execution records are missing*' }
        if ($Failure -notlike $Expected -or $script:Calls -ne 2) { throw "Missing $script:Missing : $Failure" }
        if ($null -ne $script:HostQualification.unitTests -or
            $script:HostQualification.lint.counts.issues -ne 0) { exit 2 }
    }
} finally {
    if ($Repo.StartsWith($TempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $Repo)) {
        Remove-Item -LiteralPath $Repo -Recurse -Force
    }
}
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_host_junit_reports_require_exact_identities_and_real_passes(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        parser = launcher_functions()
        # Real report parsing -> identity accounting -> pass gate; no Gradle
        # substitute is allowed to provide the outcome this test checks.
        with tempfile.TemporaryDirectory() as temporary:
            results = Path(temporary)
            script = results / "check.ps1"
            script.write_text(parser + r'''
$ErrorActionPreference = 'Stop'
$Result = Join-Path $PSScriptRoot 'TEST-fixture.xml'
function Require-Rejection($Action, $Message) {
    $Failure = $null
    try { & $Action | Out-Null } catch { $Failure = $_.Exception.Message }
    if (-not $Failure -or $Failure -notlike $Message) {
        throw "Expected $Message; received $Failure"
    }
}
foreach ($Outcome in @('failure', 'error', 'skipped')) {
    Set-Content -LiteralPath $Result -Value (
        '<testsuite tests="1" failures="0" errors="0" skipped="0">' +
        '<testcase classname="fixture.Sample" name="broken"><' + $Outcome +
        ' /></testcase></testsuite>'
    )
    Require-Rejection { Read-JUnitQualification $PSScriptRoot } '*count differs from testcase outcomes*'
    $Failures = [int]($Outcome -eq 'failure'); $Errors = [int]($Outcome -eq 'error')
    $Skipped = [int]($Outcome -eq 'skipped')
    Set-Content -LiteralPath $Result -Value (
        "<testsuite tests=`"1`" failures=`"$Failures`" errors=`"$Errors`" skipped=`"$Skipped`">" +
        '<testcase classname="fixture.Sample" name="broken"><' + $Outcome + ' /></testcase></testsuite>')
    Require-Rejection { Assert-JUnitQualificationPass (Read-JUnitQualification $PSScriptRoot) } '*JUnit reported*'
}
Set-Content -LiteralPath $Result -Value @'
<testsuite tests="1" failures="1" errors="0" skipped="0">
  <testcase classname="fixture.Sample" name="passes" />
</testsuite>
'@
Require-Rejection { Read-JUnitQualification $PSScriptRoot } '*count differs from testcase outcomes*'
Set-Content -LiteralPath $Result -Value @'
<testsuite tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="fixture.Sample" name="passes" />
</testsuite>
'@
$Counts = Read-JUnitQualification $PSScriptRoot
if ($Counts.tests -ne 1 -or $Counts.failures -ne 0 -or $Counts.errors -ne 0 -or $Counts.skipped -ne 0) {
    throw 'Valid report counts changed'
}
Assert-HostMethodAccounting @('fixture.Sample#passes') $Counts.methods
Assert-JUnitQualificationPass $Counts
Require-Rejection { Assert-HostMethodAccounting @('fixture.Sample#missing') $Counts.methods } '*identity accounting failed*'
Require-Rejection { Assert-HostMethodAccounting @('fixture.Sample#passes','fixture.Sample#missing') $Counts.methods } '*identity accounting failed*'
$Valid = Get-Content -LiteralPath $Result -Raw
Set-Content -LiteralPath $Result -Value ($Valid -replace 'tests="1"', 'tests="2"')
Require-Rejection { Read-JUnitQualification $PSScriptRoot } '*testcase count differs*'
Set-Content -LiteralPath $Result -Value ($Valid -replace 'name="passes"', 'name=""')
Require-Rejection { Read-JUnitQualification $PSScriptRoot } '*unnamed testcase*'
Set-Content -LiteralPath $Result -Value '<testsuite tests="0" failures="0" errors="0" skipped="0" />'
Require-Rejection { Read-JUnitQualification $PSScriptRoot } '*zero tests*'
Set-Content -LiteralPath $Result -Value $Valid
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'TEST-duplicate.xml') -Value $Valid
Require-Rejection { Read-JUnitQualification $PSScriptRoot } '*duplicate testcase identity*'
''', encoding="utf-8")
            path = str(script)
            if os.name != "nt" and powershell.lower().endswith(".exe"):
                path = subprocess.check_output(["wslpath", "-w", path], text=True, timeout=60).strip()
            completed = subprocess.run(
                [powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", path],
                capture_output=True, text=True, check=False, timeout=20,
            )
            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_host_qualification_rejects_junit_failures_and_lint_errors(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        qualification = launcher_functions()
        script = qualification + r'''
$TempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$Repo = Join-Path $TempBase ('stemwise-host-failing-' + [guid]::NewGuid().ToString('N'))
$Repo = [IO.Path]::GetFullPath($Repo)
$Build = Join-Path $Repo 'app\build'
$script:DeviceRunEvidenceRoot = Join-Path $Repo 'evidence\attempt'
function Invoke-Gradle([string[]]$Arguments) {
    if ($Arguments[0] -eq ':app:testDevelopmentUnitTest') {
        $Results = Join-Path $Build 'test-results\testDevelopmentUnitTest'
        $Report = Join-Path $Build 'reports\tests\testDevelopmentUnitTest'
        New-Item -ItemType Directory -Path $Results, $Report -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $Results 'TEST-failing.xml') -Value @'
<testsuite tests="2" failures="1" errors="0" skipped="0">
  <testcase classname="fixture.Failing" name="broken"><failure /></testcase>
  <testcase classname="fixture.Failing" name="passes" />
</testsuite>
'@
        $EngineResults = Join-Path $Repo 'audio-engine\build\test-results\testDevelopmentUnitTest'
        New-Item -ItemType Directory -Path $EngineResults -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $EngineResults 'TEST-engine.xml') -Value @'
<testsuite tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="fixture.Engine" name="passes" />
</testsuite>
'@
        Set-Content -LiteralPath (Join-Path $Report 'index.html') -Value '<html>tests</html>'
        return
    }
    $Reports = Join-Path $Build 'reports'
    New-Item -ItemType Directory -Path $Reports -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $Reports 'lint-results-development.xml') -Value (
        '<issues><issue severity="Error"/></issues>'
    )
    Set-Content -LiteralPath (Join-Path $Reports 'lint-results-development.txt') -Value (
        '1 error, 0 warnings'
    )
    Set-Content -LiteralPath (Join-Path $Reports 'lint-results-development.html') -Value (
        '<html>lint</html>'
    )
}
function Get-HostExpectedMethods {
    return @('fixture.Failing#broken', 'fixture.Failing#passes', 'fixture.Engine#passes')
}
try {
    $Failure = $null
    try { Invoke-HostQualification } catch { $Failure = $_.Exception.Message }
    if ($Failure -notlike '*JUnit reported 1 failures*' -or
        $Failure -notlike '*Lint reported 0 fatal and 1 error*') { exit 1 }
    if ($script:HostQualification.unitTests.counts.failures -ne 1 -or
        $script:HostQualification.unitTests.counts.tests -ne 3 -or
        $script:HostQualification.unitTests.counts.suites -ne 2 -or
        $script:HostQualification.lint.counts.errors -ne 1) { exit 2 }
} finally {
    if ($Repo.StartsWith($TempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $Repo)) {
        Remove-Item -LiteralPath $Repo -Recurse -Force
    }
}
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)


    def test_recorded_operation_rejects_run_id_path_escape_before_starting(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        recorder = launcher_functions()
        script = recorder + r'''
$TempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$Suite = Join-Path $TempBase ('stemwise-recorder-path-' + [guid]::NewGuid().ToString('N'))
$Suite = [IO.Path]::GetFullPath($Suite)
$Mode = 'Device'
$Serial = 'TEST-SERIAL'
$RunId = '..'
$script:Touched = $false
try {
    New-Item -ItemType Directory -Path $Suite -Force | Out-Null
    $Failure = $null
    try {
        Invoke-RecordedDeviceOperation { $script:Touched = $true }
    } catch { $Failure = $_.Exception.Message }
    if ($Failure -notlike '*RunId cannot be*' -or $script:Touched) { exit 1 }
    if (Test-Path -LiteralPath (Join-Path $Suite 'evidence')) { exit 2 }
    if (@(Get-ChildItem -LiteralPath $Suite -Force).Count -ne 0) { exit 3 }
} finally {
    if ($Suite.StartsWith($TempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $Suite)) {
        Remove-Item -LiteralPath $Suite -Recurse -Force
    }
}
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_apk_evidence_registration_deduplicates_exact_artifact(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        registration = launcher_functions()
        script = registration + r'''
$script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
$App = [pscustomobject]@{ role = 'qualification'; kind = 'application'; sha256 = 'APP' }
Register-ApkEvidence $App
Register-ApkEvidence $App
Register-ApkEvidence ([pscustomobject]@{
    role = 'qualification'; kind = 'androidTest'; sha256 = 'APP'
})
Register-ApkEvidence ([pscustomobject]@{
    role = 'deliverable'; kind = 'application'; sha256 = 'APP'
})
$Rejected = $false
try {
    Register-ApkEvidence ([pscustomobject]@{
        role = 'qualification'; kind = 'application'; sha256 = 'APP-NEW'
    })
} catch { $Rejected = $_.Exception.Message -like '*changed hash*' }
if (-not $Rejected -or $script:DeviceArtifacts.Count -ne 3) { exit 1 }
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_standard_selection_archives_results_even_when_gradle_fails(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        selection = launcher_functions()
        script = selection + standard_selection_adb_stub() + r'''
$TempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$TempRoot = Join-Path $TempBase ('stemwise-selection-' + [guid]::NewGuid().ToString('N'))
$TempRoot = [IO.Path]::GetFullPath($TempRoot)
$Repo = Join-Path $TempRoot 'repo'
$ConnectedDeviceResults = Join-Path $Repo 'app\build\generated-results'
$ConnectedDeviceReports = Join-Path $Repo 'app\build\generated-reports'
$script:DeviceRunEvidenceRoot = Join-Path $TempRoot 'evidence'
$script:DeviceSelectionSequence = 0
$Serial = 'TEST-SERIAL'
$PressureSeed = 17
$PressureOperations = 23
$script:GradleShouldFail = $true
$script:InjectedKind = 'test-failure'
$script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
$script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
$script:ObservedSkippedMethods = [Collections.Generic.HashSet[string]]::new()
$script:DeviceInventoryAccount=@{methods=@(
    @{id='app.test.First#passes';class='app.test.First';requiresAudioIsolation=$true},
    @{id='app.test.First#skips';class='app.test.First';requiresAudioIsolation=$true},
    @{id='app.test.First#fails';class='app.test.First';requiresAudioIsolation=$true},
    @{id='app.test.First#unselectedNetwork';class='app.test.First';requiresAudioIsolation=$false},
    @{id='app.test.Second#passes';class='app.test.Second';requiresAudioIsolation=$false}
)}
function Assert-QualificationSourceUnchanged {}
function Assert-FrozenArtifactsUnchanged {}
function Get-FrozenArtifact([string]$Role, [string]$Kind) {
    $Hash = if ($Kind -eq 'application') { 'APP' } else { 'TEST' }
    return [pscustomobject]@{ evidence = [pscustomobject]@{ sha256 = $Hash; fileName = "$Role-$Kind-arm64-v8a-development.apk" } }
}
function Get-DevelopmentApkPairEvidence {
    $Role = 'qualification'
    return @(
        [ordered]@{ role = $Role; kind = 'application'; sha256 = 'APP' },
        [ordered]@{ role = $Role; kind = 'androidTest'; sha256 = 'TEST' }
    )
}
function Invoke-Gradle([string[]]$Arguments) {
    Assert-AdbSetupBeforeGradle
    $FocusedLeaves = [Environment]::GetEnvironmentVariable('ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.stemwiseFocusedMethods', 'Process')
    $ExpectedAirplane = if ($FocusedLeaves) {'enabled'} else {'disabled'}
    if ($script:FixtureAirplane -cne $ExpectedAirplane) {throw 'Actual standard selection used the wrong audio environment'}
    New-Item -ItemType Directory -Path $ConnectedDeviceResults -Force | Out-Null
    New-Item -ItemType Directory -Path $ConnectedDeviceReports -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'result.txt') -Value result
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceReports 'report.txt') -Value report
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'logcat-owner-private.txt') -Value private
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceReports 'logcat-system-private.txt') -Value private
    if ($script:GradleShouldFail) {
        Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
            '<testsuite><testcase classname="app.test.First" name="passes" />' +
            '<testcase classname="app.test.First" name="skips"><skipped /></testcase>' +
            '<testcase classname="app.test.First" name="fails"><failure /></testcase></testsuite>'
        )
        throw (New-QualificationException 'gradle boom' $null $script:InjectedKind)
    }
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
        '<testsuite><testcase classname="app.test.Second" name="passes" /></testsuite>'
    )
}
try {
    New-Item -ItemType Directory -Path $ConnectedDeviceResults -Force | Out-Null
    New-Item -ItemType Directory -Path $ConnectedDeviceReports -Force | Out-Null
    New-Item -ItemType Directory -Path $script:DeviceRunEvidenceRoot -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'stale.txt') -Value stale
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceReports 'stale.txt') -Value stale

    $FirstFailure = $null
    try {
        Invoke-StandardDeviceSelection @('app.test.First') `
            @{ listener = 'fixture.Listener'; stemwiseFocusedMethods = 'app.test.First#passes,app.test.First#skips,app.test.First#fails' } -EvidenceLabel 'first'
    } catch {
        $FirstFailure = $_.Exception.Message
    }
    if ($script:FixtureAirplane -cne 'disabled') {throw 'Failed standard audio selection leaked airplane mode'}
    $First = Get-ChildItem -LiteralPath $script:DeviceRunEvidenceRoot -Directory |
        Sort-Object Name | Select-Object -First 1
    $FirstResult = Get-Content -LiteralPath (
        Join-Path $First.FullName 'selection.json'
    ) -Raw | ConvertFrom-Json
    if ($FirstFailure -notlike '*gradle boom*' -or $FirstResult.status -ne 'FAIL' -or
        $FirstResult.failureKind -cne 'test-failure' -or
        $FirstResult.schemaVersion -ne 4 -or
        @($FirstResult.passingMethods).Count -ne 1 -or
        @($FirstResult.skippedMethods).Count -ne 1 -or
        @($FirstResult.failedMethods).Count -ne 1 -or
        $FirstResult.failedMethods[0] -ne 'app.test.First#fails' -or
        $script:ObservedPassingMethods.Count -ne 0 -or
        $script:ObservedSkippedMethods.Count -ne 0) {
        exit 1
    }
    if (-not (Test-Path -LiteralPath (Join-Path $First.FullName 'results\result.txt')) -or
        -not (Test-Path -LiteralPath (Join-Path $First.FullName 'reports\report.txt'))) {
        exit 2
    }
    if ((Test-Path -LiteralPath (Join-Path $First.FullName 'results\stale.txt')) -or
        (Test-Path -LiteralPath (Join-Path $First.FullName 'reports\stale.txt')) -or
        -not $FirstResult.resultsArchived -or -not $FirstResult.reportsArchived) {
        exit 5
    }
    if (@(Get-ChildItem -LiteralPath $First.FullName -Recurse -File -Filter 'logcat-*.txt').Count -ne 0 -or
        @(Get-ChildItem -LiteralPath $ConnectedDeviceResults -Recurse -File -Filter 'logcat-*.txt').Count -ne 0 -or
        @(Get-ChildItem -LiteralPath $ConnectedDeviceReports -Recurse -File -Filter 'logcat-*.txt').Count -ne 0) {
        exit 7
    }
    $Arguments = @($FirstResult.gradleArguments)
    foreach ($Required in @(
        '-Pandroid.testInstrumentationRunnerArguments.listener=fixture.Listener',
        '-Pandroid.testInstrumentationRunnerArguments.pressureSeed=17',
        '-Pandroid.testInstrumentationRunnerArguments.stemwisePressureSeed=17',
        '-Pandroid.testInstrumentationRunnerArguments.pressureOperations=23',
        '--no-daemon',
        '--console=plain'
    )) {
        if ($Required -notin $Arguments) { exit 6 }
    }

    $script:GradleShouldFail = $false
    Invoke-StandardDeviceSelection @('app.test.Second') -EvidenceLabel 'second'
    $Selections = @(
        Get-ChildItem -LiteralPath $script:DeviceRunEvidenceRoot -Directory | Sort-Object Name
    )
    if ($Selections.Count -ne 2) { exit 3 }
    $SecondResult = Get-Content -LiteralPath (
        Join-Path $Selections[1].FullName 'selection.json'
    ) -Raw | ConvertFrom-Json
    if ($SecondResult.status -ne 'PASS' -or
        -not $SecondResult.resultsArchived -or $SecondResult.reportsArchived -or
        -not (Test-Path -LiteralPath (Join-Path $Selections[1].FullName 'results')) -or
        (Test-Path -LiteralPath (Join-Path $Selections[1].FullName 'reports')) -or
        @($SecondResult.artifacts).Count -ne 2 -or
        $SecondResult.artifacts[0].role -ne 'qualification' -or
        @($SecondResult.failedMethods).Count -ne 0 -or
        $script:DeviceArtifacts.Count -ne 2 -or
        $script:ObservedPassingMethods.Count -ne 1 -or
        $script:ObservedSkippedMethods.Count -ne 0) {
        exit 4
    }
    if (@(Get-ChildItem -LiteralPath $Selections[1].FullName -Recurse -File -Filter 'logcat-*.txt').Count -ne 0 -or
        @(Get-ChildItem -LiteralPath $ConnectedDeviceResults -Recurse -File -Filter 'logcat-*.txt').Count -ne 0 -or
        @(Get-ChildItem -LiteralPath $ConnectedDeviceReports -Recurse -File -Filter 'logcat-*.txt').Count -ne 0) {
        exit 8
    }
    $BeforeAdbFailure = $script:GradleBoundaryCalls
    $script:AdbSetupShouldFail = $true
    $SetupFailure = $null
    try { Invoke-StandardDeviceSelection @('app.test.Second') -EvidenceLabel 'adb-fail' }
    catch { $SetupFailure = $_.Exception.Message }
    finally { $script:AdbSetupShouldFail = $false }
    $SetupResult = Get-Content -LiteralPath (
        Join-Path $script:DeviceRunEvidenceRoot '03-adb-fail\selection.json'
    ) -Raw | ConvertFrom-Json
    if ($SetupFailure -notlike '*adb setup boom*' -or $SetupResult.status -cne 'FAIL' -or
        $script:GradleBoundaryCalls -ne $BeforeAdbFailure -or
        $script:DeviceBoundaryEvents.Count -ne 5) {
        throw 'ADB setup failure must stop before Gradle and remain a failed selection'
    }
    $script:GradleShouldFail = $true
    foreach ($Kind in @('timeout', 'infrastructure', 'cleanup')) {
        $script:InjectedKind = if ($Kind -eq 'timeout') { 'timeout' } else { 'test-failure' }
        if ($Kind -eq 'infrastructure') {
            function Copy-SanitizedDeviceEvidenceTree { throw 'evidence copy denied' }
        }
        if ($Kind -eq 'cleanup') {
            function Remove-BroadDeviceLogcatArtifacts { throw 'logcat cleanup denied' }
        }
        $TypedFailure = $null
        try { Invoke-StandardDeviceSelection @('app.test.First') -EvidenceLabel $Kind }
        catch { $TypedFailure = $_ }
        $Latest = Get-ChildItem -LiteralPath $script:DeviceRunEvidenceRoot -Directory | Sort-Object Name | Select-Object -Last 1
        $TypedResult = Get-Content -LiteralPath (Join-Path $Latest.FullName 'selection.json') -Raw | ConvertFrom-Json
        if (-not $TypedFailure -or (Get-QualificationFailureKind $TypedFailure) -cne $Kind -or
            $TypedResult.failureKind -cne $Kind -or $TypedResult.status -cne 'FAIL' -or
            $TypedFailure.Exception.Message -notlike '*gradle boom*') { throw "Selection failure kind lost: $Kind" }
    }
    if (($script:DeviceBoundaryEvents -join ',') -cne
        'adb,gradle,adb,gradle,adb,adb,gradle,adb,gradle,adb,gradle') {
        throw 'Selection setup/Gradle order changed'
    }
} finally {
    if ($TempRoot.StartsWith($TempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $TempRoot)) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_development_success_retains_only_junit_before_next_selection_and_feeds_consumer(self):
        from powershell_fixture import literal_path, run_script
        sys.path.insert(0, str(LIVE_ROOT / 'Test suite/host'))
        import audio_evidence as audio
        with tempfile.TemporaryDirectory(dir=LIVE_ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            artifacts = []
            for kind in ('application', 'androidTest'):
                path = root / (kind + '.apk')
                path.write_bytes(kind.encode())
                artifacts.append(dict(audio.file_binding(path, root), kind=kind))
            script = root / 'selection.ps1'
            script.write_text(launcher_functions() + standard_selection_adb_stub() +
                              '\n$FixtureRoot=' + literal_path(root) + r'''
$ErrorActionPreference='Stop'; $Mode='Focused'; $Serial='fixture'
$Repo=Join-Path $FixtureRoot 'repo'
$ConnectedDeviceResults=Join-Path $Repo 'app/build/results'
$ConnectedDeviceReports=Join-Path $Repo 'app/build/reports'
$script:DeviceRunEvidenceRoot=Join-Path $FixtureRoot 'evidence'
$script:DeviceSelectionSequence=0
$script:ObservedPassingMethods=[Collections.Generic.HashSet[string]]::new()
$script:ObservedSkippedMethods=[Collections.Generic.HashSet[string]]::new()
function Assert-QualificationSourceUnchanged {}
function Assert-FrozenArtifactsUnchanged {}
function Register-ApkEvidence($Evidence) {}
function Get-FrozenArtifact($Role,$Kind) {
 @{evidence=@{role=$Role;kind=$Kind;sha256=(Get-FileSha256 (Join-Path $FixtureRoot ($Kind+'.apk')));
              fileName='fixture-arm64-v8a-development.apk'}}
}
function Get-DevelopmentApkPairEvidence {
 @((Get-FrozenArtifact 'qualification' 'application').evidence,
   (Get-FrozenArtifact 'qualification' 'androidTest').evidence)
}
function Invoke-Gradle($Arguments) {
 Assert-AdbSetupBeforeGradle
 New-Item -ItemType Directory -Path (Join-Path $ConnectedDeviceResults 'device') -Force | Out-Null
 New-Item -ItemType Directory -Path $ConnectedDeviceReports -Force | Out-Null
 [IO.File]::WriteAllText((Join-Path $ConnectedDeviceResults 'device/TEST-fixture.xml'),
  ('<testsuite><testcase classname="app.fixture.Class" name="'+$script:Method+'"><system-err>private</system-err></testcase><system-out>private</system-out></testsuite>'))
 [IO.File]::WriteAllText((Join-Path $ConnectedDeviceResults 'logcat-owner.txt'),'private')
 [IO.File]::WriteAllText((Join-Path $ConnectedDeviceResults 'test-result.pb'),'not-needed')
 [IO.File]::WriteAllText((Join-Path $ConnectedDeviceReports 'report.html'),'not-needed')
}
foreach($script:Method in @('one','two')) {
 Invoke-StandardDeviceSelection @('app.fixture.Class#'+$script:Method) -EvidenceLabel $script:Method
}
''', encoding='utf-8-sig')
            completed = run_script(script, timeout=30)
            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            evidence = root / 'evidence'
            selections = sorted(evidence.rglob('selection.json'))
            self.assertEqual(2, len(selections))
            for selection in selections:
                receipt = audio.load_json(selection)
                self.assertTrue(receipt['resultsArchived'])
                self.assertFalse(receipt['reportsArchived'])
                files = sorted(path.relative_to(selection.parent).as_posix()
                               for path in selection.parent.rglob('*') if path.is_file())
                self.assertEqual(['results/device/TEST-fixture.xml', 'selection.json'], files)
                self.assertNotIn('private', (selection.parent / files[0]).read_text(encoding='utf-8'))
            configuration = {'buildType': 'development', 'debuggable': True, 'minifyEnabled': True, 'shrinkResources': True,
                             'identity': 'app.stemwise.development', 'signing': 'disposable'}
            build_evidence = []
            for kind, variant in (('application', 'development'), ('androidTest', 'developmentAndroidTest'), ('mapping', None)):
                path = root / (kind + '.json')
                path.write_text(json.dumps({'variantName': variant, 'applicationId': configuration['identity'] +
                                ('.test' if kind == 'androidTest' else '')}) if variant else '# compiler: R8\n', encoding='utf-8')
                build_evidence.append(dict(audio.file_binding(path, root), kind=kind))
            report = {'status': 'PASS', 'deviceProfile': {'serial': 'fixture'},
                      'artifacts': audio.load_json(selections[0])['artifacts'], 'audioArtifacts': artifacts,
                      'audioSourceSha256': 'current', 'audioSourceEndSha256': 'current',
                      'audioBuildConfiguration': configuration, 'audioBuildEvidence': build_evidence,
                      'audioExecutions': [audio.file_binding(path, root) for path in evidence.rglob('*') if path.is_file()]}
            run = evidence / 'run.json'
            run.write_text(json.dumps(report), encoding='utf-8')
            required = ['app.fixture.Class#one', 'app.fixture.Class#two']
            plan = {'sourceSha256': 'current', 'deviceProfile': {'identity': {'serial': 'fixture'}},
                    'candidateProfile': {'buildConfiguration': configuration}, 'required': {'device': required}}
            self.assertEqual([(method, 'passed') for method in required], audio.device_records(run, plan, root))

    def test_standard_selection_retries_only_exact_utp_connection_timeouts(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        selection = launcher_functions()
        script = selection + standard_selection_adb_stub() + r'''
$TempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$TempRoot = Join-Path $TempBase ('stemwise-utp-retry-' + [guid]::NewGuid().ToString('N'))
$TempRoot = [IO.Path]::GetFullPath($TempRoot)
$Repo = Join-Path $TempRoot 'repo'
$ConnectedDeviceResults = Join-Path $Repo 'app\build\generated-results'
$ConnectedDeviceReports = Join-Path $Repo 'app\build\generated-reports'
$script:DeviceRunEvidenceRoot = Join-Path $TempRoot 'evidence-pass'
$script:DeviceSelectionSequence = 0
$script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
$script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
$script:ObservedSkippedMethods = [Collections.Generic.HashSet[string]]::new()
$script:DeviceInventoryAccount = [pscustomobject]@{ methods = @(
    [pscustomobject]@{ id = 'app.test.Retry#passes'; class = 'app.test.Retry'; wrapper = 'app.test.FilteredSuite'; excluded = $false },
    [pscustomobject]@{ id = 'app.test.Other#unselected'; class = 'app.test.Other'; wrapper = 'app.test.FilteredSuite'; excluded = $false }
) }
$Serial = 'TEST-SERIAL'
$PressureSeed = 0
$PressureOperations = 0
$Gradle = 'fake-gradlew.bat'
$script:GradleCalls = 0
$script:ResetCount = 0
$script:SecondShouldFail = $false
$script:ResetShouldFail = $false
$script:BeforeAllZero = $false
$script:QualificationChecks = 0
$script:FrozenChecks = 0
$script:QualificationFailAt = 0
$Marker = 'Exception thrown during onAfterAll invocation of plugin AndroidTestApkInstallerPlugin: Connection timed out: connect'
function Assert-QualificationSourceUnchanged {
    $script:QualificationChecks++
    if ($script:QualificationChecks -eq $script:QualificationFailAt) {
        throw 'qualification preflight boom'
    }
}
function Assert-FrozenArtifactsUnchanged { $script:FrozenChecks++ }
function Reset-DisposableDevicePackages {
    $script:ResetCount++
    if ($script:ResetShouldFail) { throw 'reset preflight boom' }
}
function Get-FrozenArtifact([string]$Role, [string]$Kind) {
    $Hash = if ($Kind -eq 'application') { 'APP' } else { 'TEST' }
    return [pscustomobject]@{ evidence = [pscustomobject]@{ sha256 = $Hash; fileName = 'stemwise-x86_64-development.apk' } }
}
function Get-DevelopmentApkPairEvidence {
    $Role = 'qualification'
    return @(
        [ordered]@{ role = $Role; kind = 'application'; sha256 = 'APP' },
        [ordered]@{ role = $Role; kind = 'androidTest'; sha256 = 'TEST' }
    )
}
function Register-ApkEvidence([object]$Evidence) { $script:DeviceArtifacts.Add($Evidence) }
function Invoke-Gradle([string[]]$Arguments) {
    Assert-AdbSetupBeforeGradle
    $script:GradleCalls++
    New-Item -ItemType Directory -Path $ConnectedDeviceResults -Force | Out-Null
    New-Item -ItemType Directory -Path $ConnectedDeviceReports -Force | Out-Null
    $SystemErr = if ($script:GradleCalls -eq 1) {
        "<system-err>$Marker</system-err>"
    } else { '' }
    $Testcase = if ($script:BeforeAllZero -and $script:GradleCalls -eq 1) { '' } else {
        '<testcase classname="app.test.Retry" name="passes" />'
    }
    $Count = if ($Testcase) { 1 } else { 0 }
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
        "<testsuite name=`"app.test.Retry`" tests=`"$Count`" failures=`"0`" errors=`"0`" skipped=`"0`">" +
        $Testcase + $SystemErr + '</testsuite>'
    )
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceReports 'report.txt') -Value report
    if ($script:GradleCalls -eq 1) { throw "$Gradle failed with exit code 1." }
    if ($script:SecondShouldFail) { throw 'second attempt boom' }
}
try {
    New-Item -ItemType Directory -Path $script:DeviceRunEvidenceRoot -Force | Out-Null
    Invoke-StandardDeviceSelection @('app.test.FilteredSuite') @{
        stemwiseFocusedMethods = 'app.test.Retry#passes'
    } -EvidenceLabel 'retry-pass'
    $Pass = Get-Content -LiteralPath (
        Join-Path $script:DeviceRunEvidenceRoot '01-retry-pass\selection.json'
    ) -Raw | ConvertFrom-Json
    if ($script:GradleCalls -ne 2 -or $script:ResetCount -ne 1 -or
            $script:QualificationChecks -ne 3 -or $script:FrozenChecks -ne 3 -or
            $Pass.status -ne 'PASS' -or $Pass.retry.attemptCount -ne 2 -or
            ($Pass.selectors -join ',') -cne 'app.test.Retry#passes' -or
            ($Pass.instrumentationSelectors -join ',') -cne 'app.test.FilteredSuite' -or
            $Pass.retry.finalStatus -ne 'PASS' -or @($Pass.passingMethods).Count -ne 1 -or
            -not (Test-Path -LiteralPath (
                Join-Path $script:DeviceRunEvidenceRoot '01-retry-pass\retry-trigger\retry.json'
            )) -or -not (Test-Path -LiteralPath (
                Join-Path $script:DeviceRunEvidenceRoot '01-retry-pass\retry-trigger\results\TEST-fixture.xml'
            )) -or -not (Test-Path -LiteralPath (
                Join-Path $script:DeviceRunEvidenceRoot '01-retry-pass\retry-trigger\reports\report.txt'
            ))) {
        exit 1
    }

    $script:DeviceRunEvidenceRoot = Join-Path $TempRoot 'evidence-fail'
    $script:DeviceSelectionSequence = 0
    $script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
    $script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
    $script:GradleCalls = 0
    $script:ResetCount = 0
    $script:QualificationChecks = 0
    $script:FrozenChecks = 0
    $script:SecondShouldFail = $true
    New-Item -ItemType Directory -Path $script:DeviceRunEvidenceRoot -Force | Out-Null
    $Failure = $null
    try { Invoke-StandardDeviceSelection @('app.test.Retry') -EvidenceLabel 'retry-fail' } catch {
        $Failure = $_.Exception.Message
    }
    $Fail = Get-Content -LiteralPath (
        Join-Path $script:DeviceRunEvidenceRoot '01-retry-fail\selection.json'
    ) -Raw | ConvertFrom-Json
    if ($Failure -notlike '*second attempt boom*' -or $script:GradleCalls -ne 2 -or
            $Fail.status -ne 'FAIL' -or $Fail.retry.finalStatus -ne 'FAIL' -or
            @($Fail.passingMethods).Count -ne 1 -or @($Fail.failedMethods).Count -ne 0 -or
            $script:ObservedPassingMethods.Count -ne 0 -or
            $script:DeviceArtifacts.Count -ne 0) {
        exit 2
    }

    $script:DeviceRunEvidenceRoot = Join-Path $TempRoot 'evidence-reset-fail'
    $script:DeviceSelectionSequence = 0
    $script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
    $script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
    $script:GradleCalls = 0
    $script:ResetCount = 0
    $script:QualificationChecks = 0
    $script:FrozenChecks = 0
    $script:SecondShouldFail = $false
    $script:ResetShouldFail = $true
    New-Item -ItemType Directory -Path $script:DeviceRunEvidenceRoot -Force | Out-Null
    $Failure = $null
    try { Invoke-StandardDeviceSelection @('app.test.Retry') -EvidenceLabel 'reset-fail' } catch {
        $Failure = $_.Exception.Message
    }
    $ResetFail = Get-Content -LiteralPath (
        Join-Path $script:DeviceRunEvidenceRoot '01-reset-fail\selection.json'
    ) -Raw | ConvertFrom-Json
    if ($Failure -notlike '*reset preflight boom*' -or $script:GradleCalls -ne 1 -or
            $ResetFail.status -ne 'FAIL' -or $ResetFail.retry.attemptCount -ne 1 -or
            $script:ObservedPassingMethods.Count -ne 0 -or $script:DeviceArtifacts.Count -ne 0) {
        exit 7
    }

    $script:DeviceRunEvidenceRoot = Join-Path $TempRoot 'evidence-validation-fail'
    $script:DeviceSelectionSequence = 0
    $script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
    $script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
    $script:GradleCalls = 0
    $script:ResetCount = 0
    $script:QualificationChecks = 0
    $script:FrozenChecks = 0
    $script:ResetShouldFail = $false
    $script:QualificationFailAt = 2
    New-Item -ItemType Directory -Path $script:DeviceRunEvidenceRoot -Force | Out-Null
    $Failure = $null
    try { Invoke-StandardDeviceSelection @('app.test.Retry') -EvidenceLabel 'validation-fail' } catch {
        $Failure = $_.Exception.Message
    }
    $ValidationFail = Get-Content -LiteralPath (
        Join-Path $script:DeviceRunEvidenceRoot '01-validation-fail\selection.json'
    ) -Raw | ConvertFrom-Json
    if ($Failure -notlike '*qualification preflight boom*' -or $script:GradleCalls -ne 1 -or
            $script:ResetCount -ne 1 -or $ValidationFail.status -ne 'FAIL' -or
            $script:ObservedPassingMethods.Count -ne 0 -or $script:DeviceArtifacts.Count -ne 0) {
        exit 8
    }
    $script:QualificationFailAt = 0
    if (($script:DeviceBoundaryEvents -join ',') -cne ((@('adb','gradle') * 6) -join ',')) {
        throw 'Every initial/retried Gradle attempt requires its own exact ADB setup'
    }

    New-Item -ItemType Directory -Path $ConnectedDeviceResults -Force | Out-Null
    New-Item -ItemType Directory -Path $ConnectedDeviceReports -Force | Out-Null
    try { throw "$Gradle failed with exit code 1." } catch { $ExactFailure = $_ }
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
        '<testsuite tests="1" failures="0" errors="0" skipped="0">' +
        '<testcase classname="app.test.Retry" name="passes" />' +
        "<system-err>$($Marker -replace 'onAfterAll','onBeforeAll')</system-err></testsuite>"
    )
    if ($null -ne (Get-UtpInfrastructureRetryEvidence $ExactFailure @('app.test.Retry'))) { exit 3 }
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
        '<testsuite tests="0" failures="0" errors="0" skipped="0">' +
        "<system-err>$($Marker -replace 'onAfterAll','onBeforeAll')</system-err></testsuite>"
    )
    $BeforeAllRetry = Get-UtpInfrastructureRetryEvidence `
        $ExactFailure @('app.test.Retry')
    if ($null -eq $BeforeAllRetry -or
            $BeforeAllRetry.phase -cne 'before-all-zero-test' -or
            @($BeforeAllRetry.passingMethods).Count -ne 0) { exit 12 }
    $AfterAllMarker = $Marker
    $InstallMarker = "ErrorName: UNKNOWN`nNameSpace: DdmlibAndroidDeviceController"
    foreach ($InstallStage in @('commit', 'create', 'write')) {
        $InstallCause = if ($InstallStage -ceq 'write') {
            "Failed to install-write all apks`ncom.android.ddmlib.InstallException: Failed to install-write all apks" +
                "`n`t at com.android.ddmlib.SplitApkInstaller.install(SplitApkInstaller.java)"
        } else {
            $InstallSite = if ($InstallStage -ceq 'create') { 'createMultiInstallSession' } else { 'installCommit' }
            "Connection timed out: connect`njava.net.ConnectException: Connection timed out: connect" +
                "`n`t at com.android.ddmlib.SplitApkInstallerBase.$InstallSite(SplitApkInstallerBase.java)"
        }
        $Marker = "$InstallMarker`nErrorCode: 1`nErrorType: TEST`nMessage: Failed to install split APK(s): [fixture.apk]`n$InstallCause" +
            "`n`t at com.android.tools.utp.plugins.deviceprovider.ddmlib.DdmlibAndroidDevice.installPackages(DdmlibAndroidDevice.kt:76)"
        if ($InstallStage -cne 'write') { $ConnectionInstallMarker = $Marker }
        $script:DeviceRunEvidenceRoot = Join-Path $TempRoot "evidence-install-$InstallStage"
        $script:DeviceSelectionSequence = 0
        $script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
        $script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
        $script:GradleCalls = 0
        $script:ResetCount = 0
        $script:QualificationChecks = 0
        $script:FrozenChecks = 0
        $script:BeforeAllZero = $true
        New-Item -ItemType Directory -Path $script:DeviceRunEvidenceRoot -Force | Out-Null
        Invoke-StandardDeviceSelection @('app.test.FilteredSuite') @{
            stemwiseFocusedMethods = 'app.test.Retry#passes'
        } -EvidenceLabel 'install'
        $Recovered = Get-Content -LiteralPath (
            Join-Path $script:DeviceRunEvidenceRoot '01-install\selection.json'
        ) -Raw | ConvertFrom-Json
        $Trigger = Get-Content -LiteralPath (
            Join-Path $script:DeviceRunEvidenceRoot '01-install\retry-trigger\retry.json'
        ) -Raw | ConvertFrom-Json
        $TriggerXml = Read-HostQualificationXml (
            Join-Path $script:DeviceRunEvidenceRoot '01-install\retry-trigger\results\TEST-fixture.xml'
        )
        if ($script:GradleCalls -ne 2 -or $script:ResetCount -ne 1 -or
                $script:QualificationChecks -ne 3 -or $script:FrozenChecks -ne 3 -or
                $Recovered.status -cne 'PASS' -or $Recovered.retry.attemptCount -ne 2 -or
                $Recovered.retry.finalStatus -cne 'PASS' -or $Trigger.phase -cne 'install-zero-test' -or
                $Trigger.marker -cne $InstallMarker -or @($Trigger.passingMethods).Count -ne 0 -or
                @($TriggerXml.DocumentElement.SelectNodes('testcase')).Count -ne 0 -or
                ($Recovered.selectors -join ',') -cne 'app.test.Retry#passes' -or
                ($Recovered.instrumentationSelectors -join ',') -cne 'app.test.FilteredSuite' -or
                ($Recovered.passingMethods -join ',') -cne 'app.test.Retry#passes' -or
                $script:ObservedPassingMethods.Count -ne 1 -or $script:DeviceArtifacts.Count -ne 2) {
            throw "Exact $InstallStage installation timeout did not retry through a clean frozen selection"
        }
    }
    foreach ($RejectedInstall in @(
        $ConnectionInstallMarker.Replace('java.net.ConnectException:', 'java.net.SocketTimeoutException:'),
        $ConnectionInstallMarker.Replace('DdmlibAndroidDevice.installPackages(', 'DdmlibAndroidDevice.executeShellCommand('),
        $ConnectionInstallMarker.Replace('Message: Failed to install', 'Message: Unrelated device operation'),
        $ConnectionInstallMarker.Replace('DdmlibAndroidDeviceController', 'UnrelatedController')
    )) {
        Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
            '<testsuite tests="0" failures="0" errors="0" skipped="0">' +
            "<system-err>$RejectedInstall</system-err></testsuite>"
        )
        if ($null -ne (Get-UtpInfrastructureRetryEvidence $ExactFailure @('app.test.Retry'))) {
            throw 'Non-installer or non-connection installation failure became retryable'
        }
    }
    foreach ($Counts in @('tests="1" failures="0" errors="0" skipped="0"',
            'tests="1" failures="1" errors="0" skipped="0"',
            'tests="1" failures="0" errors="1" skipped="0"',
            'tests="1" failures="0" errors="0" skipped="1"')) {
        Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
            "<testsuite $Counts>" + '<testcase classname="app.test.Retry" name="passes" />' +
            "<system-err>$ConnectionInstallMarker</system-err></testsuite>"
        )
        if ($null -ne (Get-UtpInfrastructureRetryEvidence $ExactFailure @('app.test.Retry'))) {
            throw 'Installation retry admitted executed, failed or skipped tests'
        }
    }
    $Marker = $AfterAllMarker
    $script:BeforeAllZero = $false
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
        '<testsuite tests="1" failures="0" errors="0" skipped="1">' +
        '<testcase classname="app.test.Retry" name="passes"><skipped /></testcase>' +
        "<system-err>$Marker</system-err></testsuite>"
    )
    if ($null -ne (Get-UtpInfrastructureRetryEvidence $ExactFailure @('app.test.Retry'))) { exit 4 }
    $script:DeviceInventoryAccount.methods += [pscustomobject]@{
        id = 'app.test.Retry#missing'; class = 'app.test.Retry'; excluded = $false
    }
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
        '<testsuite tests="1" failures="0" errors="0" skipped="0">' +
        '<testcase classname="app.test.Retry" name="passes" />' +
        "<system-err>$Marker</system-err></testsuite>"
    )
    if ($null -ne (Get-UtpInfrastructureRetryEvidence $ExactFailure @('app.test.Retry'))) { exit 5 }
    $script:DeviceInventoryAccount.methods = @(
        [pscustomobject]@{ id = 'app.test.Retry#passes'; class = 'app.test.Retry'; excluded = $false }
    )
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
        '<testsuite tests="1" failures="1" errors="0" skipped="0">' +
        '<testcase classname="app.test.Retry" name="passes"><failure /></testcase>' +
        "<system-err>$Marker</system-err></testsuite>"
    )
    if ($null -ne (Get-UtpInfrastructureRetryEvidence $ExactFailure @('app.test.Retry'))) { exit 9 }
    Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value (
        '<testsuite tests="1" failures="0" errors="0" skipped="0">' +
        '<testcase classname="app.test.Retry" name="unexpected" />' +
        "<system-err>$Marker</system-err></testsuite>"
    )
    if ($null -ne (Get-UtpInfrastructureRetryEvidence $ExactFailure @('app.test.Retry'))) { exit 10 }
    try { throw 'different failure' } catch { $DifferentFailure = $_ }
    if ($null -ne (Get-UtpInfrastructureRetryEvidence $DifferentFailure @('app.test.Retry'))) { exit 11 }

    $Marker = 'Exception thrown during onBeforeAll invocation of plugin AndroidTestLogcatPlugin: Connection timed out: connect'
    $script:DeviceRunEvidenceRoot = Join-Path $TempRoot 'evidence-logcat-before-all'
    $script:DeviceSelectionSequence = 0
    $script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
    $script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
    $script:GradleCalls = 0
    $script:ResetCount = 0
    $script:QualificationChecks = 0
    $script:FrozenChecks = 0
    $script:BeforeAllZero = $true
    New-Item -ItemType Directory -Path $script:DeviceRunEvidenceRoot -Force | Out-Null
    Invoke-StandardDeviceSelection @('app.test.Retry') -EvidenceLabel 'logcat-before-all'
    $Recovered = Get-Content -LiteralPath (
        Join-Path $script:DeviceRunEvidenceRoot '01-logcat-before-all\selection.json'
    ) -Raw | ConvertFrom-Json
    $Trigger = Get-Content -LiteralPath (
        Join-Path $script:DeviceRunEvidenceRoot '01-logcat-before-all\retry-trigger\retry.json'
    ) -Raw | ConvertFrom-Json
    if ($script:GradleCalls -ne 2 -or $script:ResetCount -ne 1 -or
            $script:QualificationChecks -ne 3 -or $script:FrozenChecks -ne 3 -or
            $Recovered.status -cne 'PASS' -or $Recovered.retry.attemptCount -ne 2 -or
            $Trigger.phase -cne 'before-all-zero-test' -or $Trigger.marker -cne $Marker -or
            @($Trigger.passingMethods).Count -ne 0 -or @($Recovered.passingMethods).Count -ne 1) { exit 14 }
    foreach ($Rejected in @(
        ('<not-testsuite name="testsuite" tests="0" failures="0" errors="0" skipped="0">' +
            "<system-err>$Marker</system-err></not-testsuite>"),
        ('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="app.test.Retry" name="passes" />' +
            "<system-err>$Marker</system-err></testsuite>"),
        ('<testsuite tests="0" failures="0" errors="0" skipped="0">' +
            "<system-err>$($Marker -replace 'onBeforeAll','onAfterAll')</system-err></testsuite>"),
        ('<testsuite tests="0" failures="0" errors="0" skipped="0">' +
            "<system-err>$($Marker -replace 'AndroidTestLogcatPlugin','UnknownPlugin')</system-err></testsuite>")
    )) {
        Set-Content -LiteralPath (Join-Path $ConnectedDeviceResults 'TEST-fixture.xml') -Value $Rejected
        if ($null -ne (Get-UtpInfrastructureRetryEvidence $ExactFailure @('app.test.Retry'))) { exit 15 }
    }
} finally {
    if ($TempRoot.StartsWith($TempBase, [StringComparison]::OrdinalIgnoreCase) -and
            (Test-Path -LiteralPath $TempRoot)) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}
'''
        # The generated behavioral fixture can exceed Windows' command-line length limit.
        with tempfile.TemporaryDirectory(prefix="stemwise-utp-script-") as directory:
            script_path = Path(directory) / "check.ps1"
            script_path.write_text(script, encoding="utf-8-sig")
            native_script_path = str(script_path)
            if os.name != "nt" and powershell.lower().endswith(".exe"):
                native_script_path = subprocess.check_output(
                    ["wslpath", "-w", native_script_path], text=True
                , timeout=60).strip()
            completed = subprocess.run(
                [powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                 "-File", native_script_path],
                capture_output=True, text=True, check=False,
            timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_connected_results_reject_duplicate_method_outcomes(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        reader = launcher_functions()
        with tempfile.TemporaryDirectory(prefix="stemwise-duplicate-results-") as directory:
            fixture = Path(directory)
            # Include contradictory and identical duplicates within one result set.
            for second in ('<testcase classname="C" name="m" />',
                           '<testcase classname="C" name="m"><skipped /></testcase>'):
                (fixture / "TEST-one.xml").write_text(
                    '<testsuite><testcase classname="C" name="m" />' + second + '</testsuite>'
                )
                script = reader + r'''
$Failure = $null
try { Read-InstrumentationMethodResults $args[0] | Out-Null }
catch { $Failure = $_.Exception.Message }
if ($Failure -notlike '*duplicate testcase identity: C#m*') { exit 1 }
'''
                script_path = fixture / "check.ps1"
                script_path.write_text(script)
                from powershell_fixture import run_script
                completed = run_script(script_path, fixture, timeout=30)
                self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_method_accounting_requires_a_non_skipped_pass_for_every_method(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        accounting = launcher_functions()
        script = accounting + r'''
$TempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$TempRoot = Join-Path $TempBase ('stemwise-method-accounting-' + [guid]::NewGuid().ToString('N'))
$script:DeviceRunEvidenceRoot = Join-Path $TempRoot 'evidence'
$script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
$script:ObservedSkippedMethods = [Collections.Generic.HashSet[string]]::new()
$script:DeviceInventoryAccount = [pscustomobject]@{
    methods = @(
        [pscustomobject]@{ id = 'app.test.Sample#first'; class = 'app.test.Sample'; lane = 'ordinary' },
        [pscustomobject]@{ id = 'app.test.Sample#second'; class = 'app.test.Sample'; lane = 'ordinary' }
    )
}
try {
    New-Item -ItemType Directory -Path $TempRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $script:DeviceRunEvidenceRoot -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $TempRoot 'TEST-fixture.xml') -Value @'
<testsuite>
  <testcase classname="app.test.Sample" name="first" />
  <testcase classname="app.test.Sample" name="second"><skipped /></testcase>
</testsuite>
'@
    $Observed = Read-InstrumentationMethodResults $TempRoot
    $Observed.passing | ForEach-Object { Register-PassingInstrumentationMethod $_ }
    $Observed.skipped | ForEach-Object { Register-SkippedInstrumentationMethod $_ }
    $Failed = $false
    try {
        Assert-InstrumentationMethodAccounting @(
            'app.test.Sample#first', 'app.test.Sample#second'
        ) 'fixture'
    } catch { $Failed = $true }
    if (-not $Failed) { exit 1 }
    Register-PassingInstrumentationMethod 'app.test.Sample#second'
    Assert-InstrumentationMethodAccounting @(
        'app.test.Sample#first', 'app.test.Sample#second'
    ) 'fixture'
    $Evidence = Get-Content -LiteralPath (
        Join-Path $script:DeviceRunEvidenceRoot 'execution-accounting.json'
    ) -Raw | ConvertFrom-Json
    if ($Evidence.status -ne 'PASS' -or $Evidence.passingCount -ne 2 -or
        $Evidence.skippedCount -ne 1) { exit 2 }
    $script:ObservedPassingMethods.Clear()
    Register-PassingInstrumentationMethod 'app.test.Sample#first'
    Register-PassingInstrumentationMethod 'app.test.Sample#second'
    $OutOfScopePassed = $true
    try {
        Assert-InstrumentationMethodAccounting @('app.test.Sample#first') 'fixture-scope'
    } catch { $OutOfScopePassed = $false }
    if ($OutOfScopePassed) { exit 3 }
    $script:ObservedPassingMethods.Clear()
    $script:ObservedSkippedMethods.Clear()
    Register-PassingInstrumentationMethod 'app.test.Sample#first'
    Register-SkippedInstrumentationMethod 'app.test.Sample#second'
    $OutOfScopeSkipPassed = $true
    try {
        Assert-InstrumentationMethodAccounting @('app.test.Sample#first') 'fixture-skip-scope'
    } catch { $OutOfScopeSkipPassed = $false }
    if ($OutOfScopeSkipPassed) { exit 4 }
    $script:ObservedPassingMethods.Clear()
    $script:ObservedSkippedMethods.Clear()
    Register-PassingInstrumentationMethod 'APP.TEST.SAMPLE#FIRST'
    Register-PassingInstrumentationMethod 'app.test.Sample#second'
    $WrongCasePassed = $true
    try {
        Assert-InstrumentationMethodAccounting @(
            'app.test.Sample#first', 'app.test.Sample#second'
        ) 'fixture-case'
    } catch { $WrongCasePassed = $false }
    if ($WrongCasePassed) { exit 5 }
} finally {
    if ($TempRoot.StartsWith($TempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $TempRoot)) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_inventory_validation_archives_exact_account_output(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        inventory = launcher_functions()
        script = inventory + r'''
$TempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$TempRoot = Join-Path $TempBase ('stemwise-inventory-' + [guid]::NewGuid().ToString('N'))
$TempRoot = [IO.Path]::GetFullPath($TempRoot)
$LiveRoot = $TempRoot
$script:DeviceRunEvidenceRoot = Join-Path $TempRoot 'evidence'
$FakePython = Join-Path $TempRoot 'fake-python.cmd'
$DeviceInventory = Join-Path $TempRoot 'inventory.py'
function Resolve-Python { return $FakePython }
try {
    New-Item -ItemType Directory -Path $script:DeviceRunEvidenceRoot -Force | Out-Null
    Set-Content -LiteralPath $FakePython -Value @(
        '@echo off',
        'echo {"status":"PASS","methodCount":199}'
    )
    Invoke-DeviceInventoryValidation
    $Archived = Get-Content -LiteralPath (
        Join-Path $script:DeviceRunEvidenceRoot 'inventory.json'
    ) -Raw | ConvertFrom-Json
    if ($Archived.status -ne 'PASS' -or $Archived.methodCount -ne 199) { exit 1 }
} finally {
    if ($TempRoot.StartsWith($TempBase, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $TempRoot)) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_full_unified_wrapper_filters_fatal_and_preserves_exact_lane_coverage(self):
        from powershell_fixture import literal_path, run_script
        with tempfile.TemporaryDirectory(dir=LIVE_ROOT/'Test suite/work') as name:
            root = Path(name)
            manifest = root/'lanes.json'
            wrapper = 'app.stemwise.qualification.UnifiedPlayerPressureSuite'
            manifest.write_text(json.dumps({'explicitSuites': [
                {'wrapper': wrapper, 'children': ['test.Audio']}
            ]}), encoding='utf-8')
            script = root/'unified.ps1'
            script.write_text(launcher_functions() + '\n$DeviceLaneManifest=' + literal_path(manifest) + r'''
$ErrorActionPreference='Stop'
$Unified='app.stemwise.qualification.UnifiedPlayerPressureSuite'
$Fatal='app.stemwise.playback.RuntimePlaybackControlsInstrumentationTest#nativeActorFatalFaultImmediatelySilencesAndQuarantinesWithoutPollSpin'
$Rows=@(
 @{id='test.Audio#seek';class='test.Audio';wrapper=$Unified;lane='explicit-suite'},
 @{id='test.Audio#loop';class='test.Audio';wrapper=$Unified;lane='explicit-suite'},
 @{id=$Fatal;class=$Fatal.Split('#')[0];wrapper=$Unified;lane='explicit-suite'},
 @{id='test.Ordinary#other';class='test.Ordinary';lane='ordinary'}
)
$script:DeviceInventoryAccount=@{methods=$Rows}
$script:Seen=[Collections.Generic.List[object]]::new()
function Reset-DisposableDevicePackages {}
function Require-Serial {}
function Invoke-DeviceInventoryValidation {}
function Initialize-FocusedArtifacts {}
function Resolve-Adb {'fixture-adb'}
function Invoke-AdbChecked {}
function Assert-DeviceMethodAccounting {}
function Invoke-StandardDeviceSelection($Selectors,$InstrumentationArguments,$EvidenceLabel) {
 $script:Seen.Add(@{selectors=@($Selectors);extra=$InstrumentationArguments.Clone()})
 if ($script:FailWrapper -and $Selectors[0] -ceq $Unified) {throw 'injected-wrapper-failure'}
}
foreach ($Route in @('explicit','focused','focused-fatal-only')) {
 $script:Seen.Clear()
 if ($Route -ceq 'explicit') { Invoke-ExplicitSuiteDeviceLane } else {
  $Mode='Focused';$DeviceLane='All';$DeviceProfile='fixture';$DevelopmentPackage='fixture'
  $Class=if ($Route -ceq 'focused-fatal-only') {@($Fatal)} else {@($Unified)}
  Invoke-FocusedChecks
 }
 if ($Route -ceq 'focused-fatal-only') {
  if ($script:Seen.Count -ne 1 -or $script:Seen[0].selectors[0] -cne $Fatal -or
      $script:Seen[0].extra.stemwiseNativeFatalQualification -cne 'true' -or
      $script:Seen[0].extra.ContainsKey('listener') -or $script:Seen[0].extra.ContainsKey('filter')) {
   throw 'Exact fatal selection was not isolated'
  }
  continue
 }
 if ($script:Seen.Count -ne 2) {throw "Wrong $Route invocation count"}
 $Safe=$script:Seen[0];$Destructive=$script:Seen[1]
 if ($Safe.selectors.Count -ne 1 -or $Safe.selectors[0] -cne $Unified -or
     $Safe.extra.filter -cne 'app.stemwise.qualification.FocusedSuiteMethodFilter' -or
     $Safe.extra.stemwiseFocusedMethods -cne 'test.Audio#seek,test.Audio#loop') {
  throw "$Route wrapper did not select every safe inventoried leaf exactly"
 }
 $Listeners=$Safe.extra.listener.Split(',')
 if ($Listeners.Count -ne 2 -or
     $Listeners -cnotcontains 'app.stemwise.qualification.FocusedSuiteMethodRunListener' -or
     $Listeners -cnotcontains 'app.stemwise.qualification.ProcessAudioHealthRunListener' -or
     $Safe.extra.ContainsKey('stemwiseNativeFatalQualification')) {throw "$Route lost wrapper gates"}
 if ($Destructive.selectors.Count -ne 1 -or $Destructive.selectors[0] -cne $Fatal -or
     $Destructive.extra.stemwiseNativeFatalQualification -cne 'true' -or
     $Destructive.extra.ContainsKey('listener') -or $Destructive.extra.ContainsKey('filter')) {
  throw "$Route fatal invocation was not isolated after the wrapper"
 }
 $Covered=@($Safe.extra.stemwiseFocusedMethods.Split(',')) + @($Destructive.selectors)
 if ($Covered.Count -ne 3 -or @($Covered | Sort-Object -Unique).Count -ne 3 -or
     @($Rows | Where-Object wrapper -CEQ $Unified | Where-Object {$_.id -cnotin $Covered}).Count) {
  throw "$Route lost full-lane method accounting"
 }
}
$script:FailWrapper=$true
foreach ($Route in @('explicit','focused')) {
 $script:Seen.Clear();$Failure=$null;$Class=@($Unified)
 try {
  if ($Route -ceq 'explicit') {Invoke-ExplicitSuiteDeviceLane} else {Invoke-FocusedChecks}
 } catch {$Failure=$_.Exception.Message}
 if ($Failure -notlike '*injected-wrapper-failure*' -or $script:Seen.Count -ne 1 -or
     $script:Seen[0].selectors[0] -cne $Unified) {throw "$Route continued to fatal after wrapper failure"}
}
$script:FailWrapper=$false
foreach ($Invalid in @('missing','no-fatal','empty')) {
 $script:Seen.Clear()
 $script:DeviceInventoryAccount=if ($Invalid -ceq 'missing') {$null} elseif ($Invalid -ceq 'no-fatal') {
  @{methods=@($Rows | Where-Object id -CNE $Fatal)}
 } else { @{methods=@($Rows | Where-Object id -CEQ $Fatal)} }
 $Failure=$null
 try {Invoke-ExplicitSuiteDeviceLane} catch {$Failure=$_.Exception.Message}
 if (-not $Failure -or $script:Seen.Count -ne 0) {throw "Invalid $Invalid inventory reached execution"}
}
''', encoding='utf-8-sig')
            completed = run_script(script, timeout=60)
            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_standard_selection_transfers_real_long_methods_through_batch_environment(self):
        from powershell_fixture import literal_path, run_script
        from unittest.mock import patch
        shells = [executable()]
        inventory = runpy.run_path(str(LIVE_ROOT/'Test suite/device/device_inventory.py'))['build_accounting']()
        wrapper = 'app.stemwise.qualification.UnifiedPlayerPressureSuite'
        fatal = 'app.stemwise.playback.RuntimePlaybackControlsInstrumentationTest#nativeActorFatalFaultImmediatelySilencesAndQuarantinesWithoutPollSpin'
        methods = ','.join(row['id'] for row in inventory['methods']
                           if row.get('wrapper') == wrapper and row['id'] != fatal)
        self.assertGreater(len(methods), 8191)
        with tempfile.TemporaryDirectory(dir=LIVE_ROOT/'Test suite/work') as name:
            root = Path(name)
            (root/'methods.txt').write_text(methods, encoding='utf-8')
            script = root/'environment.ps1'
            script.write_text(launcher_functions() + standard_selection_adb_stub() + '\n$FixtureRoot=' + literal_path(root) + r'''
$ErrorActionPreference='Stop'
$Property='ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.stemwiseFocusedMethods'
$Expected=[IO.File]::ReadAllText((Join-Path $FixtureRoot 'methods.txt'))
$Batch=Join-Path $FixtureRoot 'gradle-fixture.cmd'
$Python=(Get-Command python.exe -CommandType Application | Select-Object -First 1).Source
$Code="import json,os,sys;print(json.dumps({'focused':os.environ.get('$Property'),'present':'$Property' in os.environ,'argv':sys.argv[1:]}))"
[IO.File]::WriteAllText($Batch, ('@echo off' + "`r`n" + '@"'+$Python+'" -B -c "'+$Code+'" %*'+"`r`n"), [Text.Encoding]::ASCII)
$Repo=Join-Path $FixtureRoot 'repo'
$ConnectedDeviceResults=Join-Path $Repo 'app/build/results'
$ConnectedDeviceReports=Join-Path $Repo 'app/build/reports'
$script:DeviceRunEvidenceRoot=Join-Path $FixtureRoot 'evidence'
$script:DeviceSelectionSequence=0
$script:ObservedPassingMethods=[Collections.Generic.HashSet[string]]::new()
$script:ObservedSkippedMethods=[Collections.Generic.HashSet[string]]::new()
$Serial='fixture-serial'
function Assert-QualificationSourceUnchanged {}
function Assert-FrozenArtifactsUnchanged {}
function Register-ApkEvidence($Evidence) {}
function Get-FrozenArtifact($Role,$Kind) {
 @{evidence=@{sha256=$Kind;fileName='fixture-arm64-v8a-development.apk'}}
}
function Get-DevelopmentApkPairEvidence {
 @(@{role='qualification';kind='application';sha256='application'},
   @{role='qualification';kind='androidTest';sha256='androidTest'})
}
function Invoke-Gradle($Arguments) {
 Assert-AdbSetupBeforeGradle
 $Receipt=Invoke-QualificationProcess $Batch $Arguments -TimeoutSeconds 10
 $Actual=ConvertFrom-QualificationJson $Receipt 'actual batch environment'
 if (($script:Filtered -and (-not $Actual.present -or $Actual.focused -cne $Expected)) -or
     (-not $script:Filtered -and ($Actual.present -or $null -ne $Actual.focused)) -or
     @($Actual.argv | Where-Object {$_ -clike '-Pandroid.testInstrumentationRunnerArguments.stemwiseFocusedMethods=*'}).Count) {
  throw 'Exact focused presence/value did not cross the real batch child in its environment'
 }
 New-Item -ItemType Directory -Path $ConnectedDeviceResults -Force | Out-Null
 $Methods=if ($script:Filtered) {$Expected.Split(',')} else {@('app.fixture.Ordinary#works')}
 $Cases=foreach ($Method in $Methods) {
  $Pair=$Method.Split('#')
  '<testcase classname="'+$Pair[0]+'" name="'+$Pair[1]+'" />'
 }
 [IO.File]::WriteAllText((Join-Path $ConnectedDeviceResults 'TEST-fixture.xml'),
  '<testsuite>'+($Cases -join '')+'</testsuite>')
 if ($script:FailLaunch) {throw (New-QualificationException 'injected-launch-failure' $null 'test-failure')}
}
$Original=[Environment]::GetEnvironmentVariable($Property,'Process')
try {
 foreach ($script:Filtered in @($false,$true)) {
  foreach ($script:FailLaunch in @($false,$true)) {
   foreach ($PriorKind in @('absent','empty','value')) {
    if ($PriorKind -ceq 'absent') {[Environment]::SetEnvironmentVariable($Property,[NullString]::Value,'Process')}
    elseif ($PriorKind -ceq 'empty') {[Environment]::SetEnvironmentVariable($Property,'','Process')}
    else {[Environment]::SetEnvironmentVariable($Property,'prior-selection-preserved','Process')}
    $Prior=[Environment]::GetEnvironmentVariable($Property,'Process')
    $PriorPresent=[Environment]::GetEnvironmentVariables('Process').Contains($Property)
    $Failure=$null
    try {
     if ($script:Filtered) {
      Invoke-StandardDeviceSelection @('app.stemwise.qualification.UnifiedPlayerPressureSuite') `
       @{stemwiseFocusedMethods=$Expected} -EvidenceLabel ('filtered-'+$script:FailLaunch+'-'+$PriorKind)
     } else {
      Invoke-StandardDeviceSelection @('app.stemwise.qualification.IsolatedDataEngineSurfaceSuite') `
       -EvidenceLabel ('ordinary-'+$script:FailLaunch+'-'+$PriorKind)
     }
    } catch {$Failure=$_.Exception.Message}
    if (($script:FailLaunch -and $Failure -notlike '*injected-launch-failure*') -or
        (-not $script:FailLaunch -and $Failure)) {throw "Unexpected selection outcome: $Failure"}
    if ([Environment]::GetEnvironmentVariables('Process').Contains($Property) -ne $PriorPresent -or
        [Environment]::GetEnvironmentVariable($Property,'Process') -cne $Prior) {throw 'Prior process environment presence/value was not restored'}
   }
  }
 }
 if ($script:ObservedPassingMethods.Count -ne $Expected.Split(',').Count+1) {throw 'Exact method accounting was lost'}
 if (($script:DeviceBoundaryEvents -join ',') -cne ((@('adb','gradle') * 12) -join ',')) {
  throw 'Every batch launch requires exact ADB setup first'
 }
} finally {
 if ($null -eq $Original) {[Environment]::SetEnvironmentVariable($Property,[NullString]::Value,'Process')}
 else {[Environment]::SetEnvironmentVariable($Property,$Original,'Process')}
}
''', encoding='utf-8-sig')
            for shell in shells:
                with self.subTest(shell=shell), patch('powershell_fixture.executable', return_value=shell):
                    completed = run_script(script, timeout=90)
                    self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_explicit_group_preserves_test_and_cleanup_failures(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        explicit = launcher_functions()
        script = explicit + r'''
$DeviceLaneManifest = [IO.Path]::GetTempFileName()
$script:ResetCount = 0
$script:Seen = [Collections.Generic.List[string]]::new()
function Reset-DisposableDevicePackages {
    $script:ResetCount++
    if ($script:ResetCount -eq 2) { throw 'cleanup boom' }
}
function Invoke-StandardDeviceSelection {
    param([string[]]$Selectors, [hashtable]$InstrumentationArguments, [string]$EvidenceLabel)
    $script:Seen.Add($EvidenceLabel)
    throw 'group boom'
}
try {
    [ordered]@{
        explicitSuites = @(
            [ordered]@{ wrapper = 'app.test.FirstSuite'; children = @('app.test.First') },
            [ordered]@{ wrapper = 'app.test.SecondSuite'; children = @('app.test.Second') }
        )
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $DeviceLaneManifest
    $Failure = $null
    try { Invoke-ExplicitSuiteDeviceLane } catch { $Failure = $_.Exception.Message }
    if ($Failure -notlike '*app.test.FirstSuite*group boom*' -or
        $Failure -notlike '*app.test.FirstSuite cleanup*cleanup boom*') {
        exit 1
    }
    if ($script:Seen.Count -ne 1 -or $script:Seen[0] -notlike '*FirstSuite*') {
        exit 2
    }
} finally {
    Remove-Item -LiteralPath $DeviceLaneManifest -Force
}
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_device_class_refuses_protected_suite_children_before_build_or_execution(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        script = launcher_functions() + r"""
$DeviceLane = 'All'
$script:DeviceLaneResults = [Collections.Generic.List[object]]::new()
$script:Seen = [Collections.Generic.List[string]]::new()
$script:DeviceInventoryAccount = [pscustomobject]@{ methods = @(
    [pscustomobject]@{ id='app.ChildAudio#fault'; class='app.ChildAudio'; wrapper='app.stemwise.qualification.UnifiedPlayerPressureSuite'; excluded=$false },
    [pscustomobject]@{ id='app.ChildData#residue'; class='app.ChildData'; wrapper='app.stemwise.qualification.IsolatedDataEngineSurfaceSuite'; excluded=$false },
    [pscustomobject]@{ id='app.Ordinary#works'; class='app.Ordinary'; wrapper=$null; excluded=$false }
) }
function Require-Serial {}
function Initialize-ImmutableQualificationArtifacts { $script:Seen.Add('built') }
function Invoke-DeviceInventoryValidation {}
function Reset-DisposableDevicePackages {}
function Invoke-StandardDeviceSelection { param($Selectors, $EvidenceLabel) $script:Seen.Add($Selectors -join ',') }
function Assert-DeviceMethodAccounting {}
foreach ($Selector in @('app.ChildAudio','app.ChildAudio#fault','app.ChildData','app.ChildData#residue',
        'app.stemwise.qualification.UnifiedPlayerPressureSuite',
        'app.stemwise.qualification.IsolatedDataEngineSurfaceSuite')) {
    $Class = @($Selector)
    $Failure = $null
    try { Invoke-DeviceTests } catch { $Failure = $_.Exception.Message }
    if ($Failure -notlike '*Focused*' -or $script:Seen.Count -ne 0) { exit 1 }
}
$Class = @('app.Ordinary')
Invoke-DeviceTests
if (($script:Seen -join '|') -ne 'built|app.Ordinary') { exit 2 }
"""
        completed = subprocess.run([powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True, text=True, check=False, timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_device_all_stops_after_first_failure_and_still_cleans_up(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        device = launcher_functions()
        script = device + r'''
$Class = @()
$DeviceLane = 'All'
$script:Seen = [Collections.Generic.List[string]]::new()
$script:DeviceLaneResults = [Collections.Generic.List[object]]::new()
$script:AccountingCalls = 0
function Require-Serial {}
function Initialize-ImmutableQualificationArtifacts {}
function Invoke-DeviceInventoryValidation {}
function Reset-DisposableDevicePackages {}
function Invoke-OrdinaryDeviceLane {
    $script:Seen.Add('ordinary')
    throw 'ordinary boom'
}
function Invoke-ExplicitSuiteDeviceLane { $script:Seen.Add('explicit-suite') }
function Invoke-LifecycleDeviceLane {
    $script:Seen.Add('lifecycle-phase')
    throw 'lifecycle boom'
}
function Invoke-FatalTwoProcessDeviceLane { $script:Seen.Add('fatal-two-process') }
function Invoke-ExactCandidateDeviceLane { $script:Seen.Add('exact-candidate') }
function Assert-DeviceMethodAccounting { $script:AccountingCalls++ }

$Failure = $null
try { Invoke-DeviceTests } catch { $Failure = $_.Exception.Message }
if (($script:Seen -join ',') -ne 'ordinary') {
    exit 1
}
if ($Failure -notlike '*ordinary boom*' -or $Failure -like '*lifecycle boom*') {
    exit 2
}
if ($script:DeviceLaneResults.Count -ne 1 -or
    @($script:DeviceLaneResults | Where-Object status -eq 'FAIL').Count -ne 1) {
    exit 3
}
if ($script:AccountingCalls -ne 0) { exit 6 }

$script:Seen = [Collections.Generic.List[string]]::new()
$script:DeviceLaneResults = [Collections.Generic.List[object]]::new()
$script:AccountingCalls = 0
function Reset-DisposableDevicePackages { throw 'cleanup blocked' }
$Failure = $null
try { Invoke-DeviceTests } catch { $Failure = $_.Exception.Message }
if (($script:Seen -join ',') -ne 'ordinary') { exit 4 }
if ($Failure -notlike '*ordinary boom*' -or $Failure -notlike '*cleanup blocked*') {
    exit 5
}
if ($script:AccountingCalls -ne 0) { exit 7 }
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)




    def test_device_evidence_archive_excludes_broad_logcat(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        helper = launcher_functions()
        script = helper + r"""
$TestRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'stemwise-evidence-' + [guid]::NewGuid().ToString('N')
)
$Source = Join-Path $TestRoot 'source'
$Destination = Join-Path $TestRoot 'destination'
$Nested = Join-Path $Source 'device'
try {
    New-Item -ItemType Directory -Path $Nested -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $Nested 'TEST-result.xml') -Value '<testsuite/>'
    Set-Content -LiteralPath (Join-Path $Nested 'utp.0.log') -Value 'diagnostic'
    Set-Content -LiteralPath (Join-Path $Nested 'logcat-owner-private.txt') -Value 'private'
    Copy-SanitizedDeviceEvidenceTree $Source $Destination
    Remove-BroadDeviceLogcatArtifacts $Source
    if (-not (Test-Path -LiteralPath (Join-Path $Destination 'device\TEST-result.xml') -PathType Leaf)) {
        throw 'Structured result was not retained.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Destination 'device\utp.0.log') -PathType Leaf)) {
        throw 'UTP infrastructure log was not retained.'
    }
    if (Test-Path -LiteralPath (Join-Path $Destination 'device\logcat-owner-private.txt')) {
        throw 'Broad per-test logcat was retained.'
    }
    if (Test-Path -LiteralPath (Join-Path $Source 'device\logcat-owner-private.txt')) {
        throw 'Raw broad per-test logcat was retained.'
    }
} finally {
    if (Test-Path -LiteralPath $TestRoot) {
        Remove-Item -LiteralPath $TestRoot -Recurse -Force
    }
}
"""
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)







    def test_verified_uninstall_rejects_a_package_that_remains_listed(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        removal = launcher_functions()
        script = removal + r'''
$Serial = 'FIXTURE'
function Invoke-QualificationOutput {
    $global:LASTEXITCODE = 0
    if ($args -contains 'list') { 'package:app.stemwise' }
}
function Invoke-AdbChecked {}
$Failure = $null
try { Remove-DevicePackageVerified 'Fixture-Adb' 'app.stemwise' }
catch { $Failure = $_.Exception.Message }
if ($Failure -notlike '*survived verified uninstall*') { exit 1 }
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_frozen_artifact_recheck_rejects_post_build_byte_change(self) -> None:
        powershell = executable()
        self.assertIsNotNone(powershell)
        immutable = launcher_functions()
        script = immutable + r'''
$File = [IO.Path]::GetTempFileName()
try {
    Set-Content -LiteralPath $File -Value 'before' -NoNewline
    $Expected = Get-FileSha256 $File
    $script:FrozenArtifacts = @{
        'deliverable|application' = [pscustomobject]@{
            path = $File
            evidence = [pscustomobject]@{ sha256 = $Expected }
        }
    }
    Assert-FrozenArtifactsUnchanged
    Set-Content -LiteralPath $File -Value 'after' -NoNewline
    $Failure = $null
    try { Assert-FrozenArtifactsUnchanged } catch { $Failure = $_.Exception.Message }
    if ($Failure -notlike '*changed during the attempt*') { exit 1 }
} finally {
    Remove-Item -LiteralPath $File -Force
}
'''
        completed = subprocess.run(
            [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True,
            text=True,
            check=False,
        timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)















if __name__ == "__main__":
    unittest.main()
