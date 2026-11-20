"""Current test lanes have no historical device, APK or delivery prerequisites."""
from pathlib import Path
import contextlib
import io
import runpy
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from powershell_fixture import literal_path, run_script

SUITE = Path(__file__).resolve().parents[2]


class CurrentTesterModesTest(unittest.TestCase):
    def test_actual_launcher_canonicalizes_mode_before_guards_and_dispatch(self):
        self.run_fixture(r'''
$SourceSuite=SOURCE_SUITE
$Launcher=Join-Path $FixtureRoot 'Run-Tester.ps1'
foreach($Directory in @('host','device')) {
 $Target=Join-Path $FixtureRoot $Directory
 New-Item -ItemType Directory -Path $Target | Out-Null
 Get-ChildItem -LiteralPath (Join-Path $SourceSuite $Directory) -Filter '*.ps1' |
  Copy-Item -Destination $Target
}
$Source=Get-Content -LiteralPath (Join-Path $SourceSuite 'Run-Tester.ps1') -Raw -Encoding UTF8
$Errors=$null; $Tokens=$null
$Ast=[Management.Automation.Language.Parser]::ParseInput($Source,[ref]$Tokens,[ref]$Errors)
if($Errors.Count) {throw $Errors[0]}
$Dispatch=@($Ast.EndBlock.Statements | Where-Object {
 $_ -is [Management.Automation.Language.PipelineAst] -and
 $_.PipelineElements[0] -is [Management.Automation.Language.CommandAst] -and
 $_.PipelineElements[0].GetCommandName() -ceq 'Invoke-TesterMode'
})
if($Dispatch.Count -ne 1) {throw 'Expected the actual launcher dispatch boundary'}
# Keep the entire entry script, its parameter binder, guards and dispatcher real.
$Boundaries=@'
function Initialize-ApprovedDeviceProfile {}
function Invoke-WithWorkspaceMutex($Operation) {& $Operation}
function Initialize-SuitePlan {
 $global:ModeCalls.Add('plan:'+$Mode)
 if($Serial -cne 'MiXeDSerial' -or $RunId -cne 'MiXeDRun') {throw 'Non-mode arguments changed'}
 if($global:ModeFailure -ceq 'plan') {throw 'original plan failure'}
}
function Invoke-AudioChecks {
 $global:ModeCalls.Add('checks:'+$Mode)
 if($global:ModeFailure -ceq 'checks') {throw 'original checks failure'}
}
function Invoke-CompleteSuites {throw 'Partial Full reached complete execution'}
function Invoke-CompletePcChecks {throw 'Partial PcSuite reached complete execution'}
function Invoke-CompleteAndroidChecks {throw 'Partial AndroidSuite reached complete execution'}
'@
[IO.File]::WriteAllText($Launcher,$Source.Insert($Dispatch[0].Extent.StartOffset,$Boundaries+"`n"),
 [Text.UTF8Encoding]::new($false))
$global:ModeCalls=[Collections.Generic.List[string]]::new()
$global:ModeFailure=$null
foreach($Case in @('AudioCheck','audiocheck','AUDIOCHECK','aUdIoChEcK','AudioPlan','audioplan','AUDIOPLAN','aUdIoPlAn')) {
 $global:ModeCalls.Clear()
 $Output=@(& $Launcher -Mode $Case -Serial 'MiXeDSerial' -RunId 'MiXeDRun' 6>&1)
 $Canonical=if($Case -like '*check'){'AudioCheck'}else{'AudioPlan'}
 $Expected=if($Canonical -ceq 'AudioCheck'){'plan:AudioCheck,checks:AudioCheck'}else{'plan:AudioPlan'}
 if(($global:ModeCalls -join ',') -cne $Expected -or
     ($Output -join "`n") -cnotlike "*Tester $Canonical operation completed.*") {
  throw "Accepted mode skipped or changed its operation: $Case"
 }
}
foreach($Failure in @('plan','checks')) {
 $global:ModeCalls.Clear(); $global:ModeFailure=$Failure; $Rejected=$false
 try {& $Launcher -Mode 'aUdIoChEcK' -Serial 'MiXeDSerial' -RunId 'MiXeDRun' | Out-Null}
 catch {$Rejected=$_.Exception.Message -ceq "original $Failure failure"}
 $Expected=if($Failure -ceq 'plan'){'plan:AudioCheck'}else{'plan:AudioCheck,checks:AudioCheck'}
 if(-not $Rejected -or ($global:ModeCalls -join ',') -cne $Expected) {throw 'Mode failure was swallowed or continued'}
}
$global:ModeFailure=$null
foreach($Case in @('fUlL','pCsUiTe','aNdRoIdSuItE','aUdIoQuAlIfY')) {
 foreach($Selection in @(@{Class=@('MiXeDClass#Method')},@{PcTest=@('test_MiXeD')},@{DeviceLane='explicit-suite'})) {
  $global:ModeCalls.Clear(); $Rejected=$false
  try {& $Launcher -Mode $Case @Selection | Out-Null}
  catch {$Rejected=$_.Exception.Message -like '*Complete suite commands do not accept partial selections*'}
  if(-not $Rejected -or $global:ModeCalls.Count) {throw "Mixed-case complete mode bypassed partial-selection rejection: $Case"}
 }
}
'''.replace('SOURCE_SUITE', literal_path(SUITE)))

    def test_pc_module_failure_before_host_replaces_prior_phase_pass_and_cannot_compose(self):
        from test_tester_launcher import launcher_functions
        self.run_fixture(launcher_functions() + r'''
$Suite=$FixtureRoot; $LiveRoot=$FixtureRoot; $Mode='Full'; $Serial=$null; $RunId='pc-failure'; $DeviceProfile=$null
$PcBuilder=$FixtureRoot
Set-Content (Join-Path $PcBuilder 'runtime-bootstrap.ps1') ''
function Get-StemWiseRuntime { @{Distribution='fixture';Environment=@();BootstrapPython='fixture-bootstrap';Python='fixture-python'} }
function Get-TestRuntimeLayout { @{wslDistribution='fixture'} }
function Convert-ToWslPath($Path) {$Path}
$PcRoot=Join-Path $Suite 'evidence/current-PcSuite'; $AndroidRoot=Join-Path $Suite 'evidence/current-AndroidSuite'
New-Item -ItemType Directory -Path $PcRoot,$AndroidRoot -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $PcRoot 'run.json'),'{}')
[IO.File]::WriteAllText((Join-Path $AndroidRoot 'run.json'),'preserve previous Android bytes')
function Invoke-WithWorkspaceMutex($Operation) {& $Operation}
function Initialize-QualificationSourceSnapshot {
 $script:QualificationSourceSha256='fixture'
 [IO.File]::WriteAllText((Join-Path $script:DeviceRunEvidenceRoot 'qualification-source-manifest.json'),'[]')
}
function Assert-QualificationSourceUnchanged {}
function Get-PcTestPlan {param([switch]$PerModule)
 @{Runtime='fixture';FilePath='fixture-python';Arguments=@('test_fixture')}
}
function Invoke-HostTests($CoveredPcModules) {}
function Resolve-Python {'fixture-python'}
function Invoke-Checked($FilePath,$Arguments,$WorkingDirectory,$Phase) {
 if($Phase -ceq 'pc-runtime-and-tests') {throw (New-QualificationException 'module assertion before host' $null 'test-failure')}
}
function Invoke-AudioChecks {throw 'Host checks must not begin after failed PC modules'}
function Resolve-Adb {throw 'PC touched phone'}
$Failure=$null
try {Invoke-CompletePcChecks -Scope All} catch {$Failure=$_}
$Actual=Get-Content -LiteralPath (Join-Path $PcRoot 'run.json') -Raw | ConvertFrom-Json
if(-not $Failure -or $Actual.status -cne 'FAIL' -or $Actual.failure -notlike '*module assertion before host*' -or
   [IO.File]::ReadAllText((Join-Path $AndroidRoot 'run.json')) -cne 'preserve previous Android bytes') {throw 'Whole PC outcome lost ownership'}
try {Write-CompleteSuiteReceipt; throw 'Failed PC phase composed'} catch {
 if($_.Exception.Message -notlike '*PcSuite evidence did not pass*') {throw}
}
''')

    def test_composition_failure_invalidates_passed_audio_candidate_before_promotion(self):
        from test_tester_launcher import launcher_functions
        self.run_fixture(launcher_functions() + r'''
$Suite=Join-Path $FixtureRoot 'Test suite'; $LiveRoot=$FixtureRoot
$Output=Join-Path $Suite 'work/audio-current'; $DeviceRoot=Join-Path $Suite 'evidence/current-AndroidSuite'
$FullRoot=Join-Path $Suite 'evidence/current-Full'; $Apk=Join-Path $Output 'development/candidate.apk'
New-Item -ItemType Directory -Path $Output,$DeviceRoot,$FullRoot,(Split-Path $Apk) -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $Output 'plan.json'),'{}')
[IO.File]::WriteAllText((Join-Path $DeviceRoot 'run.json'),'{}')
[IO.File]::WriteAllText((Join-Path $FullRoot 'run.json'),'old complete pass')
[IO.File]::WriteAllText($Apk,'exact candidate')
[IO.File]::WriteAllText((Join-Path $LiveRoot 'StemWise-development.apk'),'prior delivery')
function Resolve-Python {'fixture-python'}
function Write-CompleteSuiteReceipt {throw 'actual composition rejected'}
function Get-ApkEvidence {throw 'Rejected candidate reached publication copy'}
function Invoke-Checked($FilePath,$Arguments,$WorkingDirectory,$Phase) {
 if($Phase -ceq 'audio-candidate-aggregate') {
  @{result='passed';failures=@()} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Output 'candidate.json') -Encoding UTF8
 } elseif($Phase -ceq 'audio-promotion-gate') {
  $Candidate=Get-Content -LiteralPath (Join-Path $Output 'candidate.json') -Raw | ConvertFrom-Json
  if($Candidate.result -cne 'passed') {throw 'Candidate gate rejected failed composition'}
 } else {throw 'Unexpected external command'}
}
$Rejected=$false
try {Invoke-AudioAggregate} catch {$Rejected=$_.Exception.Message -like '*actual composition rejected*'}
$Candidate=Get-Content -LiteralPath (Join-Path $Output 'candidate.json') -Raw | ConvertFrom-Json
if(-not $Rejected -or $Candidate.result -cne 'failed' -or -not $Candidate.attemptFailures -or
   (Test-Path -LiteralPath (Join-Path $FullRoot 'run.json'))) {throw 'Composition failure left qualifying output'}
$Rejected=$false
try {Publish-DevelopmentApk} catch {$Rejected=$_.Exception.Message -like '*gate rejected failed composition*'}
if(-not $Rejected -or [IO.File]::ReadAllText((Join-Path $LiveRoot 'StemWise-development.apk')) -cne 'prior delivery') {throw 'Failed composition was deliverable'}
''')

    def test_pc_and_android_source_closure_does_not_depend_on_optional_profile(self):
        from test_tester_launcher import launcher_functions
        self.run_fixture(launcher_functions() + r'''
$LiveRoot=$FixtureRoot
$Source=Join-Path $FixtureRoot 'source.py'; [IO.File]::WriteAllText($Source,'actual source')
$Profile=Join-Path $FixtureRoot 'phone-profile.json'; [IO.File]::WriteAllText($Profile,'exact separately-bound profile')
function Resolve-Python {'fixture-python'}
function Invoke-QualificationProcess($FilePath,$Arguments,$TimeoutSeconds) {
 if(($Arguments -join ',') -cne ('-B,-m,stemwise_engine.source_inputs,--root,'+$FixtureRoot)) {throw 'Unexpected source discovery'}
 @{ExitCode=0;Stdout=ConvertTo-Json -InputObject @($Source) -Compress}
}
$script:CurrentDeviceProfileFile=$null
$Before=@(Get-QualificationSourceManifest) | ConvertTo-Json -Depth 4 -Compress
$script:CurrentDeviceProfileFile=Get-Item -LiteralPath $Profile
$After=@(Get-QualificationSourceManifest) | ConvertTo-Json -Depth 4 -Compress
if($Before -cne $After -or $After -like '*phone-profile*') {throw 'Optional phone profile changed the software source closure'}
''')

    def test_portable_source_only_requires_actual_complete_pc_module_selection(self):
        portable = runpy.run_path(str(SUITE / 'host/portable_checks.py'))
        planner = runpy.run_path(str(SUITE / 'pc/test_plan.py'))
        modules = [name for group in planner['plan']() for name in group['selections']] + ['test_native_device_merger']
        main = portable['main']
        with mock.patch.object(sys, 'argv', ['portable_checks.py', '--source-only', '--covered-pc-modules', *modules]), \
                mock.patch.dict(main.__globals__, {'check_python_sources': lambda: []}), \
                mock.patch.object(portable['subprocess'], 'run') as command, \
                mock.patch.object(unittest.defaultTestLoader, 'loadTestsFromNames', side_effect=AssertionError('Portable modules ran twice')):
            self.assertEqual(0, main())
            self.assertEqual(1, command.call_count)
        missing = [name for name in modules if name != portable['PORTABLE_TEST_MODULES'][0]]
        with mock.patch.object(sys, 'argv', ['portable_checks.py', '--source-only', '--covered-pc-modules', *missing]), \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as rejected:
            main()
        self.assertEqual(2, rejected.exception.code)

    def test_pc_recorder_uses_phase_owner_with_full_outer_mode_and_preserves_android_evidence(self):
        from test_tester_launcher import launcher_functions
        self.run_fixture(launcher_functions() + r'''
$Suite=$FixtureRoot; $LiveRoot=$FixtureRoot; $Mode='Full'; $Serial=$null; $RunId='fixture'
$Android=Join-Path $Suite 'evidence/current-AndroidSuite'
New-Item -ItemType Directory -Path $Android -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $Android 'preserved.txt'),'actual prior Android evidence')
function Initialize-QualificationSourceSnapshot {
 $script:QualificationSourceSha256='fixture'
 [IO.File]::WriteAllText((Join-Path $script:DeviceRunEvidenceRoot 'qualification-source-manifest.json'),'[]')
}
function Assert-QualificationSourceUnchanged {}
function Get-FrozenArtifact {throw 'PC requested APK proof'}
function Resolve-Adb {throw 'PC requested ADB'}
function Invoke-QualificationProcess {throw 'PC requested audio/device identity capture'}
Invoke-RecordedDeviceOperation {
 $script:HostQualification=@{unitTests=@{counts=@{tests=1;failures=0;errors=0;skipped=0}};lint=@{counts=@{fatal=0;errors=0}}}
} -EvidenceMode 'PcSuite'
$Report=Get-Content -LiteralPath (Join-Path $Suite 'evidence/current-PcSuite/run.json') -Raw | ConvertFrom-Json
if($Report.status -cne 'PASS' -or $Report.mode -cne 'PcSuite' -or $Report.audioArtifacts.Count -or
   $Report.deviceInventory -or $Report.deviceExecutionAccounting -or -not $Report.hostQualification.unitTests -or
   -not (Test-Path -LiteralPath (Join-Path $Android 'preserved.txt'))) {throw 'PC recorder inherited phone responsibilities or damaged Android records'}
''')

    def test_real_dispatcher_runs_two_complete_phases_in_selected_order(self):
        from test_tester_launcher import launcher_functions
        self.run_fixture(launcher_functions() + r'''
$Suite=$FixtureRoot; $LiveRoot=$FixtureRoot; $Repo=Join-Path $FixtureRoot 'repo'; $PcBuilder=$FixtureRoot
Set-Content (Join-Path $PcBuilder 'runtime-bootstrap.ps1') ''
function Get-StemWiseRuntime { @{Distribution='fixture';Environment=@();BootstrapPython='fixture-bootstrap';Python='fixture-python'} }
function Get-TestRuntimeLayout { @{wslDistribution='fixture'} }
$Serial='fixture'; $DeviceProfile='fixture-profile'; $Class=@(); $DeviceLane='All'
$script:Events=[Collections.Generic.List[string]]::new()
function Initialize-ApprovedDeviceProfile {}
function Require-Serial {$script:Events.Add('preflight')}
function Assert-DeviceUnlockPrepared {}
function Resolve-Python {'fixture-python'}
function Convert-ToWslPath($Path) {$Path}
function Invoke-WithWorkspaceMutex($Operation) {& $Operation}
function Invoke-WithDeviceMutex($Operation) {$script:Events.Add('android:settings'); & $Operation}
function Invoke-RecordedDeviceOperation($Operation,$EvidenceMode) {
 $script:DeviceRunEvidenceRoot=Join-Path $FixtureRoot ('recorded-'+$EvidenceMode); & $Operation
}
function Get-PcTestPlan {param([switch]$PerModule)
 @{Runtime='fixture';FilePath='fixture-python';Arguments=@('unit/test_ci_changes')}
}
function Invoke-NativeTests {param([switch]$PlanOnly,$Report)
 $script:Events.Add($(if($PlanOnly){'setup:native-inventory'}else{'pc:native'}))
 if(-not $PlanOnly -and $script:FailPc) {throw 'PC native failure'}
}
function Invoke-AudioBoundaryChecks($Report) {$script:Events.Add('pc:boundary')}
function Invoke-AudioManagedNegativeChecks($Report) {$script:Events.Add('pc:managed')}
function Invoke-HostQualification {$script:Events.Add('pc:all-jvm-lint')}
function Invoke-AndroidHostTests {$script:Events.Add('pc:audio-jvm')}
function Invoke-DeviceTests {
 if($Mode -cne 'Device' -or $Class.Count -or $DeviceLane -cne 'All') {throw 'Full Android selection changed'}
 $script:Events.Add('android:all-device')
 if($script:FailAndroid) {throw 'Android test failure'}
}
function Invoke-AudioDeviceChecks($Plan) {$script:Events.Add('android:audio-device')}
function Invoke-AudioDevelopmentChecks($Plan,$Report,$DeviceReport) {$script:Events.Add('android:jni')}
function Invoke-Checked {param($FilePath,$Arguments,$WorkingDirectory,$TimeoutSeconds,$Phase)
 if($Phase -ceq 'bridge-capacity') {
  if($FilePath -cne 'wsl.exe' -or $Arguments[-1] -notlike '*/pc/bridge_capacity_check.py' -or
      $TimeoutSeconds -ne 1200) {throw 'Capacity runner invocation changed'}
  if($script:FailCapacity) {throw (New-QualificationException 'capacity unavailable' $null 'infrastructure')}
 }
 if($Phase -ceq 'audio-plan') {
  $Index=[Array]::IndexOf($Arguments,'--output')
  @{required=@{jvm=@('fixture#jvm');device=@('fixture#device')}; deviceProfile=@{identity=@{serial='fixture'}}} |
   ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Arguments[$Index+1] -Encoding UTF8
 } elseif($Phase -ceq 'audio-candidate-aggregate') {$script:Events.Add('aggregate')}
 elseif($Phase -ceq 'audio-physical-performance') {$script:Events.Add('android:physical')}
 else {$script:Events.Add('pc:'+$Phase)}
}
foreach($Case in @('Full','FullAndroidFirst','PcSuite','AndroidSuite','AudioQualify','AudioAggregate')) {
 $script:Events.Clear(); $Mode=if($Case -ceq 'FullAndroidFirst'){'Full'}else{$Case}
 $AndroidFirst=$Case -ceq 'FullAndroidFirst'
 Invoke-TesterMode
 $Pc=@($script:Events | Where-Object {$_ -like 'pc:*'})
 $Android=@($script:Events | Where-Object {$_ -like 'android:*'})
 if($Case -ceq 'PcSuite' -and ($Android.Count -or $script:Events.Contains('preflight') -or -not $Pc.Count)) {throw 'PC phase touched phone or omitted PC'}
 if($Case -ceq 'AndroidSuite' -and ($Pc.Count -or -not $Android.Count)) {throw 'Android phase ran PC checks or omitted Android'}
 if($Case -cin @('Full','FullAndroidFirst','PcSuite') -and
     @($script:Events | Where-Object {$_ -ceq 'pc:bridge-capacity'}).Count -ne 1) {throw 'Complete PC omitted required capacity execution'}
 if($Case -cin @('AndroidSuite','AudioQualify','AudioAggregate') -and
     $script:Events.Contains('pc:bridge-capacity')) {throw 'Capacity checks broadened an unrelated lane'}
 if($Case -cin @('Full','FullAndroidFirst')) {
  if(-not $script:Events.Contains('pc:all-jvm-lint') -or -not $script:Events.Contains('pc:pc-runtime-and-tests') -or
      -not $script:Events.Contains('android:all-device') -or $script:Events.Contains('android:audio-device')) {throw 'Full missed a complete component or repeated subset'}
  $FirstPc=$script:Events.IndexOf($Pc[0]); $LastPc=$script:Events.IndexOf($Pc[-1])
  $FirstAndroid=$script:Events.IndexOf($Android[0]); $LastAndroid=$script:Events.IndexOf($Android[-1])
  if(($AndroidFirst -and $LastAndroid -ge $FirstPc) -or (-not $AndroidFirst -and $LastPc -ge $FirstAndroid)) {throw 'Test phases interleaved'}
 }
 if($Case -ceq 'AudioQualify' -and ($script:Events.Contains('pc:all-jvm-lint') -or
     $script:Events.Contains('pc:pc-runtime-and-tests') -or -not $script:Events.Contains('pc:audio-jvm') -or
     -not $script:Events.Contains('android:audio-device'))) {throw 'AudioQualify broadened to Full'}
 if($Case -cin @('Full','FullAndroidFirst','AudioQualify','AudioAggregate') -and $script:Events[-1] -cne 'aggregate') {throw 'Aggregate did not follow both phases'}
 if($Case -cin @('PcSuite','AndroidSuite') -and $script:Events.Contains('aggregate')) {throw 'Standalone phase claimed whole delivery'}
}
foreach($First in @('Pc','Android')) {
 $script:Events.Clear(); $Mode='Full'; $AndroidFirst=$First -ceq 'Android'
 $script:FailPc=$First -ceq 'Pc'; $script:FailAndroid=$First -ceq 'Android'
 $Rejected=$false
 try {Invoke-TesterMode} catch {$Rejected=$_.Exception.Message -like '*phase failed*'}
 if(-not $Rejected -or $script:Events.Contains('aggregate') -or
     ($First -ceq 'Pc' -and @($script:Events | Where-Object {$_ -like 'android:*'}).Count) -or
     ($First -ceq 'Android' -and @($script:Events | Where-Object {$_ -like 'pc:*'}).Count)) {throw 'Failed first phase started second phase or aggregated'}
}
$script:Events.Clear(); $Mode='PcSuite'; $script:FailCapacity=$true
$Rejected=$false
try {Invoke-TesterMode} catch {$Rejected=$_.Exception.Message -like '*capacity unavailable*'}
if(-not $Rejected -or $script:Events.Contains('pc:native') -or $script:Events.Contains('aggregate')) {
 throw 'Unavailable required capacity qualification was silently accepted or continued'
}
''')

    def test_pc_preparation_ignores_stale_deleted_or_invalid_optional_phone_profile(self):
        from test_tester_launcher import launcher_functions
        self.run_fixture(launcher_functions() + r'''
$Suite=$FixtureRoot; $LiveRoot=$FixtureRoot; $Mode='PcSuite'; $DeviceProfile=$null
$Output=Join-Path $Suite 'work/audio-current'; New-Item -ItemType Directory -Path $Output -Force | Out-Null
$Profile=Join-Path $FixtureRoot 'profile.json'
@{serial='fixture';model='phone';device='phone';socModel='soc';androidBuildFingerprint='build';api=36} |
 ConvertTo-Json | Set-Content -LiteralPath $Profile -Encoding UTF8
$Current='a'*64
function Resolve-Python {'fixture-python'}
function Invoke-QualificationProcess($FilePath,$Arguments,$WorkingDirectory,$TimeoutSeconds) {
 if($Arguments[-1] -cne 'identity') {throw 'PC preparation invoked an unrelated process'}
 @{ExitCode=0;Stdout=$Current}
}
function Require-Serial {throw 'PC touched phone'}
function Invoke-WithDeviceMutex {throw 'PC took device mutex'}
function Invoke-NativeTests {param([switch]$PlanOnly,$Report)
 if(-not $PlanOnly) {throw 'Preparation executed native checks'}
}
function Invoke-Checked($FilePath,$Arguments,$WorkingDirectory,$Phase) {$script:PlanArguments=$Arguments}
foreach($Case in @('current','stale','deleted','escaped','changed','malformed')) {
 $Path=if($Case -ceq 'deleted'){'deleted.json'}elseif($Case -ceq 'escaped'){'../outside.json'}else{'profile.json'}
 $Previous=@{sourceSha256=$(if($Case -ceq 'stale'){'b'*64}else{$Current});
  deviceProfile=@{path=$Path;sha256=$(if($Case -ceq 'changed'){'c'*64}else{(Get-FileSha256 $Profile).ToLowerInvariant()})}}
 if($Case -ceq 'malformed') {
  [IO.File]::WriteAllText((Join-Path $Output 'plan.json'),'not JSON')
 } else {$Previous | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Output 'plan.json') -Encoding UTF8}
 Initialize-SuitePlan
 $HasProfile='--device-profile' -cin $script:PlanArguments
 if($HasProfile -ne ($Case -ceq 'current')) {throw "Optional profile preservation incorrect: $Case"}
 if($DeviceProfile) {throw 'Preparation leaked profile into PC caller state'}
}
''')

    def run_fixture(self, body, expected_exit=0):
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
            root = Path(directory)
            script = root / 'check.ps1'
            script.write_text("param([string]$FixtureRoot)\n$ErrorActionPreference='Stop'\n" + body,
                              encoding='utf-8-sig')
            result = run_script(script, root, timeout=20)
            self.assertEqual(expected_exit, result.returncode, result.stdout + result.stderr)
            return result

    def test_pc_modules_collect_assertion_failures_with_original_causes(self):
        result = self.run_fixture("""
. OWNED
$Errors=$null; $Tokens=$null
$Ast=[Management.Automation.Language.Parser]::ParseFile(LAUNCHER,[ref]$Tokens,[ref]$Errors)
$Function=$Ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-PcTests'},$true)
. ([scriptblock]::Create($Function.Extent.Text))
$LiveRoot=$FixtureRoot; $script:Calls=@()
function Get-PcTestPlan { param([switch]$PerModule)
 if (-not $PerModule) {throw 'PC modules were not independently selected'}
 foreach ($Name in @('first','second','third','last')) {
  [pscustomobject]@{Runtime='fixture';FilePath='fixture-python';Arguments=@('-B',$Name)}
 }
}
function Invoke-Checked { param($FilePath,$Arguments,$WorkingDirectory,$Phase)
 $Name=$Arguments[-1]; $script:Calls+=,$Name
 if ($Name -in @('first','third')) {
  $Cause=New-QualificationException "assertion $Name" $null 'test-failure'
  $Cause.Data['stdout']="output $Name"; $Cause.Data['stderr']="trace $Name"
  throw $Cause
 }
}
try {Invoke-PcTests; throw 'PC failures were accepted'} catch {
 if (($script:Calls -join ',') -cne 'first,second,third,last') {throw 'Later independent modules did not execute'}
 if ((Get-QualificationFailureKind $_) -cne 'test-failure') {throw 'Aggregate lost test failure classification'}
 $Aggregate=$_.Exception.InnerException
 if ($Aggregate -isnot [AggregateException] -or $Aggregate.InnerExceptions.Count -ne 2) {throw 'Aggregate lost a cause'}
 foreach ($Index in @(0,1)) {
  $Name=@('first','third')[$Index]; $Failure=$Aggregate.InnerExceptions[$Index]
  if ($Failure.Message -notlike "*fixture*$Name*assertion $Name*" -or
      $Failure.InnerException.Message -cne "assertion $Name" -or
      $Failure.InnerException.Data['stdout'] -cne "output $Name" -or
      $Failure.InnerException.Data['stderr'] -cne "trace $Name") {throw 'Module selector or original failure output was lost'}
 }
 Write-Output 'PC_AGGREGATE_VERIFIED'
 throw
}
""".replace('OWNED', literal_path(SUITE / 'host/Owned-Process.ps1'))
            .replace('LAUNCHER', literal_path(SUITE / 'Run-Tester.ps1')), expected_exit=1)
        self.assertIn('PC_AGGREGATE_VERIFIED', result.stdout)

    def test_pc_modules_abort_immediately_on_infrastructure_or_unknown_failures(self):
        self.run_fixture("""
. OWNED
$Errors=$null; $Tokens=$null
$Ast=[Management.Automation.Language.Parser]::ParseFile(LAUNCHER,[ref]$Tokens,[ref]$Errors)
$Function=$Ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-PcTests'},$true)
. ([scriptblock]::Create($Function.Extent.Text))
$LiveRoot=$FixtureRoot
function Get-PcTestPlan { param([switch]$PerModule)
 foreach ($Name in @('assertion','unsafe','later')) {
  [pscustomobject]@{Runtime='fixture';FilePath='fixture-python';Arguments=@('-B',$Name)}
 }
}
function Invoke-Checked { param($FilePath,$Arguments,$WorkingDirectory,$Phase)
 $Name=$Arguments[-1]; $script:Calls+=,$Name
 if ($Name -ceq 'assertion') {throw (New-QualificationException 'first assertion' $null 'test-failure')}
 if ($Name -ceq 'unsafe') {throw $script:UnsafeFailure}
}
foreach ($Kind in @('timeout','cleanup','infrastructure','output-limit','unknown')) {
 $script:Calls=@(); $script:UnsafeFailure=[Exception]::new("unsafe $Kind")
 if ($Kind -cne 'unknown') {$script:UnsafeFailure.Data['failureKind']=$Kind}
 $Rejected=$false
 try {Invoke-PcTests} catch {
  $Rejected=$_.Exception.Message -ceq "unsafe $Kind"
 }
 if (-not $Rejected -or ($script:Calls -join ',') -cne 'assertion,unsafe') {throw "PC continued after $Kind or replaced its failure"}
}
""".replace('OWNED', literal_path(SUITE / 'host/Owned-Process.ps1'))
            .replace('LAUNCHER', literal_path(SUITE / 'Run-Tester.ps1')))

    def test_host_entry_executes_only_current_portable_checks(self):
        self.run_fixture("""
$Errors=$null; $Tokens=$null
$Ast=[Management.Automation.Language.Parser]::ParseFile(LAUNCHER,[ref]$Tokens,[ref]$Errors)
$Function=$Ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-HostTests'},$true)
. ([scriptblock]::Create($Function.Extent.Text))
$Suite='current-suite'; $LiveRoot='current-root'
$script:Calls=@()
function Resolve-Python { return 'python' }
function Invoke-Checked { param($Executable,$Arguments,$Directory)
 $script:Calls+=@{executable=$Executable;arguments=$Arguments;directory=$Directory}
}
Invoke-HostTests
if ($script:Calls.Count -ne 1 -or $script:Calls[0].executable -cne 'python' -or
    $script:Calls[0].arguments[-1] -notlike '*host*portable_checks.py' -or
    $script:Calls[0].directory -cne 'current-root') { throw 'Host invoked broader work or publication' }
""".replace('LAUNCHER', literal_path(SUITE / 'Run-Tester.ps1')))

    def test_measured_process_phase_keeps_real_failure_and_output(self):
        self.run_fixture("""
. OWNED
$Suite=$FixtureRoot
$Errors=$null; $Tokens=$null
$Ast=[Management.Automation.Language.Parser]::ParseFile(LAUNCHER,[ref]$Tokens,[ref]$Errors)
$Function=$Ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-Checked'},$true)
. ([scriptblock]::Create($Function.Extent.Text))
$script:Rejected=$false
$Lines=@(& {
 try {Invoke-Checked (Get-Command python.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source @('-B','-c',"import sys; print('retained stdout'); print('injected stderr', file=sys.stderr); sys.exit(9)") $FixtureRoot -Phase 'fixture-install'}
 catch {
  if ($_.Exception.Data['stdout'] -notlike '*retained stdout*' -or $_.Exception.Data['stderr'] -notlike '*injected stderr*') {throw "Failure lost its actual output: $($_.Exception.Message)"}
  $script:Rejected=$true
 }
} 6>&1)
if (-not $script:Rejected) {throw 'Failed process was accepted'}
$Timing=@($Lines | ForEach-Object {"$_"} | Where-Object {$_ -like 'TESTER_PHASE *'})
if ($Timing.Count -ne 1) {throw 'Missing or duplicate process phase timing'}
$Phase=$Timing[0].Substring('TESTER_PHASE '.Length) | ConvertFrom-Json
if ($Phase.phase -cne 'fixture-install' -or $Phase.seconds -le 0) {throw 'Wrong process duration or phase'}
""".replace('OWNED', literal_path(SUITE/'host/Owned-Process.ps1'))
            .replace('LAUNCHER', literal_path(SUITE/'Run-Tester.ps1')))

    def test_junit_timing_uses_recorded_method_time_and_rejects_absent_values(self):
        self.run_fixture("""
$Errors=$null; $Tokens=$null
$Ast=[Management.Automation.Language.Parser]::ParseFile(LAUNCHER,[ref]$Tokens,[ref]$Errors)
foreach ($Name in @('Read-HostQualificationXml','Write-JUnitTimings','Invoke-Gradle')) {
 $Function=$Ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $Name},$true)
 . ([scriptblock]::Create($Function.Extent.Text))
}
$Xml=Join-Path $FixtureRoot 'TEST-fixture.xml'
[IO.File]::WriteAllText($Xml,'<testsuite><testcase classname="fixture" name="slow" time="10.5"/><testcase classname="fixture" name="fast" time="2"/></testsuite>')
$Lines=@(& {Write-JUnitTimings $FixtureRoot} 6>&1)
$Summary=([string]$Lines[0]).Substring('JUNIT_TIMING '.Length) | ConvertFrom-Json
if ($Summary.methods -ne 2 -or $Summary.slowestMethods[0].method -cne 'fixture#slow' -or
    $Summary.slowestMethods[0].reportedSeconds -ne 10.5 -or $Summary.scope -notlike '*may be cached*') {throw 'JUnit time was misreported'}
foreach ($Time in @('','NaN','-1','infinity','bad')) {
 [IO.File]::WriteAllText($Xml,('<testsuite><testcase classname="fixture" name="unknown" time="'+$Time+'"/></testsuite>'))
 $Rejected=$false
 try {Write-JUnitTimings $FixtureRoot} catch {$Rejected=$_.Exception.Message -like '*duration is absent or invalid*'}
 if (-not $Rejected) {throw 'Invalid duration acquired a timing claim'}
}
$Repo=$FixtureRoot; $Gradle='fixture'; $PresentationReports=$false
function Invoke-Checked {throw 'original gradle rejection'}
$Rejected=$false
try {Invoke-Gradle @(':app:testDevelopmentUnitTest')} catch {$Rejected=$_.Exception.Message -ceq 'original gradle rejection'}
if (-not $Rejected) {throw 'Optional timing replaced the original Gradle failure'}
""".replace('LAUNCHER', literal_path(SUITE/'Run-Tester.ps1')))

    def test_native_exact_selection_preserves_full_coverage_and_rejects_unknown_names(self):
        self.run_fixture("""
$Errors=$null; $Tokens=$null
$Ast=[Management.Automation.Language.Parser]::ParseFile(LAUNCHER,[ref]$Tokens,[ref]$Errors)
if ($Errors.Count) {throw 'Launcher syntax errors'}
$Function=$Ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-NativeTests'},$true)
. ([scriptblock]::Create($Function.Extent.Text))
$Suite=$FixtureRoot; $Repo=$FixtureRoot
$Tool=Join-Path $FixtureRoot 'native-tool'
Set-Content -LiteralPath $Tool -Value 'fixture'
function Resolve-NativeTools { return @{DevCmd=$Tool;CMake=$Tool;CTest=$Tool;Ninja=$Tool} }
function Import-MsvcEnvironment($Path) {}
function Invoke-Checked { param($FilePath,$Arguments,$WorkingDirectory)
 $script:Calls+=,@($Arguments)
}
$script:CatalogExit=0
function Invoke-QualificationProcess { param($FilePath,$Arguments,$WorkingDirectory,$TimeoutSeconds)
 if ($Arguments -notcontains '--show-only=json-v1') {throw 'Unexpected native inventory request'}
 return @{ExitCode=$script:CatalogExit;Stderr='catalog unavailable';Stdout='{"tests":[{"name":"exact.name"},{"name":"exactXname"}]}'}
}
$NativeTest=@(); $script:Calls=@()
Invoke-NativeTests
if ($script:Calls.Count -ne 3 -or $script:Calls[2] -contains '--tests-regex' -or
    $script:Calls[2] -notcontains '--no-tests=error') {throw 'Default native coverage was narrowed'}
$NativeTest=@('exact.name','exact.name'); $script:Calls=@()
Invoke-NativeTests
$Arguments=$script:Calls[2]
$Pattern=$Arguments[[Array]::IndexOf($Arguments,'--tests-regex')+1]
if ($script:Calls.Count -ne 3 -or $Arguments -notcontains '--no-tests=error' -or
    'exact.name' -cnotmatch $Pattern -or 'exactXname' -cmatch $Pattern -or 'exact.name.more' -cmatch $Pattern) {
 throw 'Native selector was not an exact literal test name'
}
foreach ($Invalid in @('missing','EXACT.name',' ')) {
 $NativeTest=@('exact.name',$Invalid); $script:Calls=@(); $Rejected=$false
 try {Invoke-NativeTests} catch {$Rejected=$_.Exception.Message -like '*Unknown exact native tests*'}
 if (-not $Rejected -or $script:Calls.Count -ne 1) {throw 'Unknown selection reached build or execution'}
}
$NativeTest=@('exact.name'); $script:CatalogExit=1; $script:Calls=@(); $Rejected=$false
try {Invoke-NativeTests} catch {$Rejected=$_.Exception.Message -like '*Native test inventory failed*catalog unavailable*'}
if (-not $Rejected -or $script:Calls.Count -ne 1) {throw 'Inventory failure reached build or execution'}
$Rejected=$false
try {& LAUNCHER -Mode Full -NativeTest 'exact.name'} catch {$Rejected=$_.Exception.Message -like '*NativeTest requires Native mode*'}
if (-not $Rejected) {throw 'Full accepted a partial native filter'}
""".replace('LAUNCHER', literal_path(SUITE / 'Run-Tester.ps1')))

    def test_current_exact_profile_accepts_modern_phone_and_emulator_and_rejects_changed_identity(self):
        self.run_fixture("""
. PROFILE
function Resolve-Adb { return 'adb' }
function Invoke-AdbReadOnlyProbe { param($Adb,$Arguments,$Label)
 switch ($Arguments[-1]) {
  'get-state' {return 'device'}
  'get-serialno' {return $script:Actual.serial}
  'ro.product.model' {return $script:Actual.model}
  'ro.product.device' {return $script:Actual.device}
  'ro.soc.model' {return $script:Actual.socModel}
  'ro.build.fingerprint' {return $script:Actual.androidBuildFingerprint}
  'ro.build.version.sdk' {return [string]$script:Actual.api}
  default {throw 'Unexpected device probe'}
 }
}
foreach ($Target in @('modern-phone','emulator-5562')) {
 $Mode='Device'; $DeviceLane='native-process-fault'; $Serial=$Target
 $script:Actual=@{serial=$Target;model=$Target;device='current';socModel='current';androidBuildFingerprint='current/build';api=36}
 $DeviceProfile=Join-Path $FixtureRoot 'device.json'
 $script:Actual | ConvertTo-Json | Set-Content -LiteralPath $DeviceProfile -Encoding UTF8
 Initialize-ApprovedDeviceProfile
 Require-Serial
 if ($script:ValidatedDeviceProfile.serial -cne $Target) {throw 'Current profile was not retained'}
 $script:Actual.androidBuildFingerprint='changed/build'
 $Rejected=$false
 try {Require-Serial} catch {$Rejected=$true}
 if (-not $Rejected) {throw 'Changed device was admitted'}
}
""".replace('PROFILE', literal_path(SUITE / 'device/Device-Profile.ps1')))
