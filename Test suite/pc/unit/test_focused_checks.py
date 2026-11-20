"""Behavioral checks for focused profiles and benchmark acceptance; never accesses a phone."""
import json
import os
import sys
from pathlib import Path
from unittest import mock
import runpy
import shutil
import subprocess
import tempfile
import unittest


sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from powershell_fixture import native_path, read_launcher_source, executable
from owned_process import run as run_owned
ROOT = Path(__file__).resolve().parents[3]
HELPER = ROOT / 'Test suite/device/Focused-Checks.ps1'
BENCHMARK = runpy.run_path(str(ROOT / 'Test suite/native/compare_benchmarks.py'))


class FocusedChecksTest(unittest.TestCase):
    def test_failed_retirement_stops_qualification_aggregates(self):
        owned = native_path(ROOT / 'Test suite/host/Owned-Process.ps1').replace("'", "''")
        launcher = native_path(ROOT / 'Test suite/Run-Tester.ps1').replace("'", "''")
        for kind in ('cleanup', 'test-failure'):
          for owner in ('Invoke-HostQualification', 'Invoke-PcPhaseChecks', 'Invoke-CompleteAndroidChecks'):
            with self.subTest(owner=owner, kind=kind):
                self.powershell(". '" + owned + "'\n$Launcher='" + launcher + "'\n$Owner='" + owner + "'\n$Kind='" + kind + "'\n" + r'''
$Suite=Split-Path $DeviceProfile; $Repo=$Suite; $LiveRoot=$Suite
$script:DeviceRunEvidenceRoot=$Suite; $script:Calls=0
$Errors=$null; $Tokens=$null
$Ast=[Management.Automation.Language.Parser]::ParseFile($Launcher,[ref]$Tokens,[ref]$Errors)
$Function=$Ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $Owner},$true)
. ([scriptblock]::Create($Function.Extent.Text))
$Fatal=New-QualificationException 'fixture process failure' $null $Kind
function Fail-Owner {$script:Calls++; throw $Fatal}
function Invoke-Gradle {Fail-Owner}
function Invoke-AudioChecks {Fail-Owner}
function Invoke-Checked {Fail-Owner}
function Invoke-RecordedDeviceOperation {Fail-Owner}
function Invoke-AudioDevelopmentChecks {Fail-Owner}
function Invoke-WithDeviceMutex($Operation) {& $Operation}
function Invoke-WithWorkspaceMutex($Operation) {& $Operation}
function Remove-ValidatedHostQualificationOutputs {}
function Copy-HostQualificationDirectory {throw 'no report after failed retirement'}
function Resolve-Python {'fixture-python'}
function Convert-ToWslPath($Path) {$Path}
function Get-TestRuntimeLayout {@{wslDistribution='fixture'}}
$Output=Join-Path $Suite 'work/audio-current'; New-Item $Output -ItemType Directory -Force | Out-Null
@{deviceProfile=@{identity=@{serial='fixture'}}} | ConvertTo-Json -Depth 5 |
 Set-Content (Join-Path $Output 'plan.json')
$Preserved=Join-Path $Output $(if($Owner -eq 'Invoke-PcPhaseChecks') {'sanitizer-memory.json'} else {'physical-performance.json'})
Set-Content $Preserved 'prior exact-owner report'
$Serial='fixture'; $Mode='fixture'; $Class=@(); $DeviceLane='All'; $PresentationReports=$false
$Caught=$null
try {
 if($Owner -eq 'Invoke-HostQualification') {Invoke-HostQualification}
 elseif($Owner -eq 'Invoke-PcPhaseChecks') {Invoke-PcPhaseChecks -Scope Audio}
 else {Invoke-CompleteAndroidChecks -Scope Audio}
} catch {$Caught=$_}
$Expected=if($Kind -eq 'cleanup') {1} elseif($Owner -eq 'Invoke-HostQualification') {2} elseif($Owner -eq 'Invoke-PcPhaseChecks') {6} else {3}
if($Kind -eq 'cleanup' -and -not (Test-Path $Preserved)) {throw 'Unretired owner report was removed'}
if($script:Calls -ne $Expected -or $null -eq $Caught -or (Get-QualificationFailureKind $Caught) -cne $Kind) {
 throw "Failed retirement admitted later work: $Owner calls=$script:Calls failure=$Caught"
}
''')

    def test_failed_retirement_preserves_compiler_probe_files_and_blocks_restore(self):
        owned = native_path(ROOT / 'Test suite/host/Owned-Process.ps1').replace("'", "''")
        boundary = native_path(ROOT / 'Test suite/host/Audio-Module-Boundary.ps1').replace("'", "''")
        self.powershell(". '" + owned + "'\n. '" + boundary + "'\n" + r'''
$Suite=Split-Path $DeviceProfile; $Repo=$Suite; $Gradle='fixture-gradle'; $script:Calls=0
function Invoke-QualificationProcess {
 $script:Calls++
 if($script:Calls -eq 1) {return @{ExitCode=0;Stdout='';Stderr=''}}
 throw (New-QualificationException 'fixture compiler still owns input' $null 'cleanup')
}
$Caught=$null
try {Invoke-AudioBoundaryChecks -Report (Join-Path $Suite 'boundary.json')} catch {$Caught=$_}
$Inputs=@(Get-ChildItem (Join-Path $Suite 'work') -Recurse -Filter AudioBoundaryProbe.kt)
if($script:Calls -ne 2 -or $Inputs.Count -ne 1 -or $null -eq $Caught -or
 (Get-QualificationFailureKind $Caught) -cne 'cleanup') {throw 'Live compiler scratch was deleted or restoration admitted'}
''')

    def test_failed_retirement_preserves_adb_readback_and_managed_compiler_inputs(self):
        owned = native_path(ROOT / 'Test suite/host/Owned-Process.ps1').replace("'", "''")
        managed = native_path(ROOT / 'Test suite/host/Audio-Managed-Negative.ps1').replace("'", "''")
        for owner in ('readback', 'managed'):
          for kind in ('cleanup', 'test-failure'):
            with self.subTest(owner=owner, kind=kind):
                self.powershell(". '" + owned + "'\n. '" + managed + "'\n$Owner='" + owner + "'\n$Kind='" + kind + "'\n" + r'''
$Suite=Join-Path (Split-Path $DeviceProfile) 'suite'; $Repo=Join-Path $Suite 'repo'
New-Item (Join-Path $Suite 'work') -ItemType Directory -Force | Out-Null
$Failure=New-QualificationException 'fixture still owns its input' $null $Kind
$script:Calls=0
function Resolve-Python {'fixture-python'}
function Invoke-AdbReadOnlyProbe($Adb,$Arguments,$Label) {
 if($Arguments[1] -eq 'pm') {return 'package:/data/app/fixture/base.apk'}
 return ('a'*64)+'  /data/app/fixture/base.apk'
}
function Invoke-AdbChecked($Adb,$Arguments) {
 $script:Calls++; Set-Content $Arguments[-1] 'partially pulled APK'; throw $Failure
}
function Invoke-QualificationProcess {
 $script:Calls++
 if($script:Calls -eq 1) {return @{ExitCode=0;Stdout='exact source';Stderr=''}}
 throw $Failure
}
if($Owner -eq 'managed') {
 $Source=Join-Path $Repo 'audio-engine/src/main/java/app/stemwise/playback/engine'
 New-Item $Source -ItemType Directory -Force | Out-Null
 Set-Content (Join-Path $Source 'NativePlaybackEngineController.kt') '(newRouteRecoveryRequest && !runtimeStopped); (hasPhysicalPlayDemand() && outputPreparation == NativeOutputPreparationState.READY); recovery.phase == RouteRecoveryPhase.RETIRING_OUTPUT || !hasPhysicalPlayDemand()'
}
$Caught=$null
try {
 if($Owner -eq 'readback') {Get-InstalledDevelopmentApkIdentity 'fixture-adb' 'app.stemwise.development'}
 else {Invoke-AudioManagedNegativeChecks -Report (Join-Path $Suite 'report.json')}
} catch {$Caught=$_}
$Pattern=if($Owner -eq 'readback') {'installed.apk'} else {'results.gradle'}
$Claimed=@(Get-ChildItem (Join-Path $Suite 'work') -Recurse -Filter $Pattern -File)
$Expected=if($Kind -eq 'cleanup') {1} else {0}
if($Claimed.Count -ne $Expected -or $null -eq $Caught -or (Get-QualificationFailureKind $Caught) -cne $Kind -or
 $script:Calls -ne $(if($Owner -eq 'readback') {1} else {2})) {throw 'Owned compiler/ADB inputs were deleted or later work launched'}
''')

    def test_failed_retirement_preserves_installed_candidate_packages(self):
        owned = native_path(ROOT / 'Test suite/host/Owned-Process.ps1').replace("'", "''")
        development = native_path(ROOT / 'Test suite/host/Audio-Development.ps1').replace("'", "''")
        for kind in ('cleanup', 'test-failure'):
            with self.subTest(kind=kind):
                self.powershell(". '" + owned + "'\n. '" + development + "'\n$Kind='" + kind + "'\n" + r'''
$Suite=Join-Path (Split-Path $DeviceProfile) 'suite'; New-Item $Suite -ItemType Directory -Force | Out-Null
$FixtureRoot=Split-Path $Suite; $Serial='fixture'
$Application=Join-Path $FixtureRoot 'qualified.apk'; $Driver=Join-Path $FixtureRoot 'driver.apk'; $Mapping=Join-Path $FixtureRoot 'mapping.txt'
$Archive=[IO.Compression.ZipFile]::Open($Application,[IO.Compression.ZipArchiveMode]::Create)
try {$Stream=$Archive.CreateEntry('lib/arm64-v8a/libstemwise_audio_output.so').Open(); $Stream.WriteByte(42); $Stream.Dispose()} finally {$Archive.Dispose()}
Set-Content $Driver 'driver'; Set-Content $Mapping 'mapping'
function Get-FileSha256($Path) {(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Binding($Path,$Kind) {@{path=[IO.Path]::GetRelativePath($FixtureRoot,$Path);sha256=Get-FileSha256 $Path;kind=$Kind}}
$Plan=@{sourceSha256='exact-source';deviceProfile=@{identity=@{serial='fixture'}}}
$DeviceReport=Join-Path $FixtureRoot 'device.json'
@{status='PASS';audioSourceSha256='exact-source';audioSourceEndSha256='exact-source';
 audioArtifacts=@((Binding $Application 'application'),(Binding $Driver 'androidTest'));audioBuildEvidence=@((Binding $Mapping 'mapping'))} |
 ConvertTo-Json -Depth 8 | Set-Content $DeviceReport
$script:Packages=[Collections.Generic.HashSet[string]]::new(); $script:Instruments=0
function Require-Serial {}
function Resolve-Adb {'fixture-adb'}
function Assert-DisposableDevelopmentTarget {}
function Invoke-AdbReadOnlyProbe($Adb,$Arguments,$Label) {
 if($Label -eq 'minified audio ABI') {return 'arm64-v8a'}
 if($Arguments[1] -eq 'pm') {return 'package:/data/app/fixture/base.apk'}
 return ('a'*64)+'  /data/app/fixture/base.apk'
}
function Invoke-Checked($FilePath,$Arguments) {
 if($Arguments -contains 'install') {[void]$script:Packages.Add($(if($Arguments[-1] -like '*driver.apk') {'driver'} else {'application'}))}
 elseif($Arguments -contains 'uninstall') {[void]$script:Packages.Remove($(if($Arguments[-1] -like '*.test') {'driver'} else {'application'}))}
 else {throw 'Unexpected process boundary'}
}
function Invoke-WithAudioSelectionIsolation($Operation) {& $Operation}
function Invoke-QualificationProcess {$script:Instruments++; throw (New-QualificationException 'fixture instrument still owns installed packages' $null $Kind)}
$Caught=$null
try {Invoke-AudioDevelopmentChecks -Plan $Plan -Report (Join-Path $FixtureRoot 'development.json') -DeviceReport $DeviceReport} catch {$Caught=$_}
if($script:Instruments -ne 1 -or $script:Packages.Count -ne $(if($Kind -eq 'cleanup') {2} else {0}) -or
 $null -eq $Caught -or (Get-QualificationFailureKind $Caught) -cne $Kind) {throw "Installed candidate cleanup did not respect retirement: $Caught packages=$($script:Packages.Count)"}
''')

    def test_failed_retirement_blocks_device_lane_and_action_resets(self):
        owned = native_path(ROOT / 'Test suite/host/Owned-Process.ps1').replace("'", "''")
        lanes = native_path(ROOT / 'Test suite/device/Device-Lanes.ps1').replace("'", "''")
        launcher = native_path(ROOT / 'Test suite/Run-Tester.ps1').replace("'", "''")
        for owner in ('ordinary', 'explicit', 'device', 'actions'):
          for kind in ('cleanup', 'test-failure'):
            with self.subTest(owner=owner, kind=kind):
                self.powershell(". '" + owned + "'\n. '" + lanes + "'\n$Launcher='" + launcher + "'\n$Owner='" + owner + "'\n$Kind='" + kind + "'\n" + r'''
$Suite=Split-Path $DeviceProfile; $Class=@();$DeviceLane='ordinary';$ActionSuiteClass='fixture-suite'
$Claim=Join-Path $Suite 'installed-package';$script:Selections=0;$script:Resets=0
$script:DeviceLaneResults=[Collections.Generic.List[object]]::new()
$script:DeviceInventoryAccount=@{methods=@()}
$DeviceLaneManifest=Join-Path $Suite 'lanes.json'
@{explicitSuites=@(@{wrapper='fixture-suite';children=@('fixture.First','fixture.Second')})} | ConvertTo-Json -Depth 6 | Set-Content $DeviceLaneManifest
function Require-Serial {}
function Invoke-DeviceInventoryValidation {}
function Initialize-ImmutableQualificationArtifacts {}
function Get-DeviceLaneSelectors {@('fixture.First','fixture.Second')}
function Reset-DisposableDevicePackages {$script:Resets++; if(Test-Path $Claim) {Remove-Item $Claim}}
function Invoke-StandardDeviceSelection {
 $script:Selections++;Set-Content $Claim 'in use'; throw (New-QualificationException 'fixture process owns the installed package' $null $Kind)
}
if($Owner -eq 'actions') {
 $Errors=$null;$Tokens=$null
 $Ast=[Management.Automation.Language.Parser]::ParseFile($Launcher,[ref]$Tokens,[ref]$Errors)
 $Function=$Ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-ActionSurfaceTests'},$true)
 . ([scriptblock]::Create($Function.Extent.Text))
 function Assert-InstrumentationMethodAccounting {}
}
if($Owner -eq 'device') {function Invoke-OrdinaryDeviceLane {Invoke-StandardDeviceSelection}}
$Caught=$null
try {
 switch($Owner) {
  ordinary {Invoke-OrdinaryDeviceLane}
  explicit {Invoke-ExplicitSuiteDeviceLane}
  device {Invoke-DeviceTests}
  actions {Invoke-ActionSurfaceTests}
 }
} catch {$Caught=$_}
$ExpectedSelections=if($Kind -eq 'test-failure' -and $Owner -eq 'actions') {2} else {1}
if($script:Selections -ne $ExpectedSelections -or (Test-Path $Claim) -ne ($Kind -eq 'cleanup') -or
 $null -eq $Caught -or (Get-QualificationFailureKind $Caught) -cne $Kind) {throw "Device/action continuation crossed live package ownership: $Caught selections=$($script:Selections) resets=$($script:Resets)"}
''')

    def test_benchmark_exact_budget_accepts_equality_and_rejects_any_excess(self):
        for candidate, expected in ((110, 0), (111, 2), (109, 0)):
            with self.subTest(candidate=candidate), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
                directory = Path(directory)
                baseline = directory / 'baseline'; baseline.write_bytes(b'baseline executable fixture')
                after = directory / 'candidate'; after.write_bytes(b'candidate executable fixture')
                output = directory / 'result.json'
                def run(command, **kwargs):
                    measured = candidate if command[0] == str(after) else 100
                    text = '\n'.join(f'mode={mode} block=480 frames=512000 render_ns={measured} '
                        f'pcm_hash=123 allocations=0 p50_ns={measured} p95_ns={measured}'
                        for mode in ('direct', 'tuned', 'returned-neutral'))
                    return subprocess.CompletedProcess(command, 0, text, '')
                with mock.patch.object(sys, 'argv', ['compare_benchmarks', '--baseline', str(baseline),
                        '--candidate', str(after), '--runs', '3', '--output', str(output),
                        '--max-regression-percent', '10']), mock.patch.object(subprocess, 'run', side_effect=run):
                    self.assertEqual(expected, BENCHMARK['main']())
                self.assertEqual(expected == 0, json.loads(output.read_text(encoding='utf-8'))['status'] == 'PASS')

    def test_boundary_checks_preserve_probe_and_cleanup_failures(self):
        owned = native_path(ROOT/'Test suite/host/Owned-Process.ps1').replace("'", "''")
        module = native_path(ROOT/'Test suite/host/Audio-Module-Boundary.ps1').replace("'", "''")
        suite = native_path(ROOT/'Test suite').replace("'", "''")
        self.powershell(". '"+owned+"'\n. '"+module+"'\n$Suite='"+suite+"'\n"+r'''
Set-StrictMode -Version Latest
$Repo='fixture'; $Gradle='fixture-gradle'; $script:Calls=0
function Invoke-QualificationProcess($FilePath,$Arguments,$WorkingDirectory,$TimeoutSeconds) {
 $script:Calls++
 if($Arguments -cnotcontains '--daemon' -or $Arguments -ccontains '--no-daemon') {throw 'Gradle daemon was not reused'}
 if($script:Calls -eq 1) {return @{ExitCode=0;Stdout='';Stderr=''}}
 if($script:Calls -eq 2) {throw 'original compiler failure'}
 throw 'restore compiler failure'
}
$Report=Join-Path (Split-Path $DeviceProfile) 'boundary.json'
$Caught=$null
try {Invoke-AudioBoundaryChecks -Report $Report} catch {$Caught=$_}
if(-not $Caught -or $Caught.Exception -isnot [AggregateException]) {throw 'Both failures were not retained'}
if($Caught.Exception.InnerExceptions.Count -ne 2 -or
   $Caught.Exception.InnerExceptions[0].Message -cne 'original compiler failure' -or
   $Caught.Exception.InnerExceptions[1].Message -cne 'restore compiler failure') {throw 'First error was masked'}
$Receipt=Get-Content -LiteralPath $Report -Raw | ConvertFrom-Json
if($Receipt.failure -cne 'original compiler failure' -or $Receipt.cleanupFailures.Count -ne 1 -or
   $null -ne $Receipt.restoredCompileExitCode) {throw 'Failure report is inaccurate'}
$script:Calls=0
function Invoke-QualificationProcess {throw 'warm compiler failure'}
try {Invoke-AudioBoundaryChecks -Report $Report; throw 'Warm failure accepted'} catch {
 if($_.Exception.Message -cne 'warm compiler failure') {throw}
}
Write-Output 'Original and cleanup failures preserved'
''')

    def test_audio_failure_preserves_original_when_disposable_cleanup_also_fails(self):
        owned = native_path(ROOT / 'Test suite/host/Owned-Process.ps1').replace("'", "''")
        inventory = native_path(ROOT / 'Test suite/device/Device-Inventory.ps1').replace("'", "''")
        manifest = native_path(ROOT / 'Test suite/device/device-lanes.json').replace("'", "''")
        self.powershell(". '" + owned + "'\n. '" + inventory + "'\n$DeviceLaneManifest='" + manifest + "'\n" + r'''
$Mode='Focused'; $Class=@(); $DeviceLane='All'; $script:FocusedAdditionalRequiredMethods=@()
$script:DeviceInventoryAccount=@{methods=@(@{id='fixture#one';class='fixture';lane='ordinary'})}
function Require-Serial {}
function Invoke-DeviceInventoryValidation {}
function Invoke-FocusedChecks {throw (New-QualificationException 'original native assertion' $null 'test-failure')}
function Reset-DisposableDevicePackages {throw 'adb disconnected during cleanup'}
try {Invoke-AudioDeviceChecks @{required=@{device=@('fixture#one')}}; throw 'Failure accepted'} catch {
 if($_.Exception.Message -notlike '*original native assertion*adb disconnected*' -or
     (Get-QualificationFailureKind $_) -cne 'cleanup' -or
     $_.Exception.InnerException -isnot [AggregateException] -or
     $_.Exception.InnerException.InnerExceptions.Count -ne 2) {throw 'Original or cleanup cause lost'}
}
''')

    def test_audio_candidate_dispatches_protected_orderings_without_focused_bypass(self):
        inventory = native_path(ROOT / 'Test suite/device/Device-Inventory.ps1').replace("'", "''")
        manifest = native_path(ROOT / 'Test suite/device/device-lanes.json').replace("'", "''")
        self.powershell(". '" + inventory + "'\n$DeviceLaneManifest='" + manifest + "'\n" + r'''
$Mode='Focused'; $DeviceLane='All'; $script:DeviceRunEvidenceRoot=$null; $script:FocusedAdditionalRequiredMethods=@()
$Manifest=Get-Content -LiteralPath $DeviceLaneManifest -Raw | ConvertFrom-Json
$Required=@('fixture#ordinary',$Manifest.nativeProcessFault.selector,$Manifest.fatalTwoProcess.phaseA,$Manifest.fatalTwoProcess.phaseB)
$script:ObservedPassingMethods=[Collections.Generic.HashSet[string]]::new(); $script:ObservedSkippedMethods=@()
$script:DeviceInventoryAccount=@{methods=@(
 @{id='fixture#ordinary';class='fixture';lane='ordinary'},
 @{id=$Required[1];class='nativefault';lane='native-process-fault'},
 @{id=$Required[2];class='fatal';lane='fatal-two-process'},
 @{id=$Required[3];class='fatal';lane='fatal-two-process'}
)}
function Require-Serial {}
function Invoke-DeviceInventoryValidation {}
function Reset-DisposableDevicePackages {}
function Invoke-FocusedChecks {
 Assert-FocusedSelectors
 if($Class.Count -ne 1 -or $Class[0] -cne 'fixture') { throw 'Protected tests leaked into Focused' }
 Get-ExpectedDeviceMethods | ForEach-Object { Register-PassingInstrumentationMethod $_ }
}
function Invoke-NativeProcessFaultDeviceLane { Register-PassingInstrumentationMethod $Required[1] }
function Invoke-FatalTwoProcessDeviceLane { Register-PassingInstrumentationMethod $Required[2]; Register-PassingInstrumentationMethod $Required[3] }
Invoke-AudioDeviceChecks @{required=@{device=$Required}}
if($script:ObservedPassingMethods.Count -ne 4) { throw 'A protected ordering was omitted' }
''')

    def test_audio_candidate_groups_only_complete_ordinary_classes_without_widening(self):
        inventory = native_path(ROOT / 'Test suite/device/Device-Inventory.ps1').replace("'", "''")
        manifest = native_path(ROOT / 'Test suite/device/device-lanes.json').replace("'", "''")
        self.powershell(". '" + inventory + "'\n$DeviceLaneManifest='" + manifest + "'\n" + r'''
$Mode='Focused'; $DeviceLane='All'; $script:DeviceRunEvidenceRoot=$null; $script:FocusedAdditionalRequiredMethods=@()
$script:DeviceInventoryAccount=@{methods=@(
 @{id='fixture.Full#one';class='fixture.Full';lane='ordinary'},
 @{id='fixture.Full#two';class='fixture.Full';lane='ordinary'},
 @{id='fixture.Partial#one';class='fixture.Partial';lane='ordinary'},
 @{id='fixture.Partial#unselected';class='fixture.Partial';lane='ordinary'},
 @{id='fixture.Wrapped#one';class='fixture.Wrapped';lane='explicit-suite';wrapper='app.stemwise.qualification.IsolatedDataEngineSurfaceSuite'},
 @{id='fixture.Mixed#one';class='fixture.Mixed';lane='ordinary'},
 @{id='fixture.Mixed#protected';class='fixture.Mixed';lane='native-process-fault'},
 @{id='fixture.Excluded#one';class='fixture.Excluded';lane='ordinary'},
 @{id='fixture.Excluded#excluded';class='fixture.Excluded';lane='ordinary';excluded=$true}
)}
$Required=@('fixture.Full#one','fixture.Full#two','fixture.Partial#one','fixture.Wrapped#one','fixture.Mixed#one','fixture.Excluded#one')
$script:ObservedPassingMethods=[Collections.Generic.HashSet[string]]::new(); $script:ObservedSkippedMethods=@()
function Require-Serial {}
function Invoke-DeviceInventoryValidation {}
function Reset-DisposableDevicePackages {}
function Invoke-FocusedChecks {
 Assert-FocusedSelectors
 if(($Class -join ',') -cne 'fixture.Full,fixture.Partial#one,fixture.Wrapped#one,fixture.Mixed#one,fixture.Excluded#one') {
  throw "Grouping widened a partial, wrapped, protected or excluded class: $Class"
 }
 Get-ExpectedDeviceMethods | ForEach-Object { Register-PassingInstrumentationMethod $_ }
}
Invoke-AudioDeviceChecks @{required=@{device=$Required}}
if($script:ObservedPassingMethods.Count -ne $Required.Count) {throw 'Class projection lost exact methods'}
''')

    def test_focused_always_uses_development_app_and_driver_with_identity_verification(self):
        self.powershell(r'''
$Mode='Focused'; $Serial='fixture'; $DevelopmentApkOutput='development-app'; $DevelopmentTestApkOutput='development-test'
$script:Builds=0; $QaProviderApkOutput='provider-apk'; $script:ProviderInstalls=0
function Install-QualificationProvider { $script:ProviderInstalls++ }
function Resolve-Adb { 'fixture-adb' }
function Invoke-AdbReadOnlyProbe { 'arm64-v8a' }
function Resolve-GradleApk($Path) { $Path }
function Freeze-ApkArtifact { @{} }
function Invoke-Gradle($Arguments) {
 foreach($Required in @(':app:assembleDevelopment',':app:assembleDevelopmentAndroidTest',':app:verifyDevelopmentArtifactIdentity',':qa-provider:assembleDevelopment')) {
  if($Arguments -cnotcontains $Required) { throw "Development build omitted $Required" }
 }
 if(@($Arguments | Where-Object { $_ -match 'Release|audioReleaseQualification' }).Count) { throw 'Development qualification invoked a consumer Release build or retired switch' }
 $script:Builds++
}
Initialize-FocusedArtifacts
if($script:Builds -ne 1 -or $script:ProviderInstalls -ne 1) { throw 'Development app/driver/provider were not prepared once' }
''')

    def test_focused_build_cannot_toggle_playback_behavior_or_instrumentation(self):
        self.powershell(r'''
$Mode='Focused'; $Serial='fixture'; $DevelopmentApkOutput='app'; $DevelopmentTestApkOutput='test'
$QaProviderApkOutput='provider'; function Install-QualificationProvider {}
function Resolve-Adb { 'fixture-adb' }
function Invoke-AdbReadOnlyProbe { 'arm64-v8a' }
function Resolve-GradleApk($Path) { $Path }
function Freeze-ApkArtifact { @{} }
function Invoke-Gradle($Arguments) {
 if (@($Arguments | Where-Object { $_ -match 'outputLongWarmGrace|profileCacheIo|audioReleaseQualification' }).Count) {
  throw 'Frozen Development behavior depends on an obsolete compile toggle'
 }
 $script:Builds++
}
$script:Builds=0
Initialize-FocusedArtifacts
if ($script:Builds -ne 1) { throw 'Build path was not exercised' }
''')

    def test_current_qa_provider_install_preserves_data_and_requires_exact_readback(self):
        lanes = native_path(ROOT / 'Test suite/device/Device-Lanes.ps1').replace("'", "''")
        self.powershell(". '" + lanes + "'\n" + r'''
$Serial='fixture'; $script:ApprovedDevicePurpose='dedicated-qualification'
$script:Installs=0; $script:CrossUser=$false; $script:CorruptReadback=$false
$script:InstalledSigner='B'*64; $script:InstalledSha='D'*64
$Expected=@{applicationId='app.stemwise.testprovider';certificateSha256=('B'*64);sha256=('C'*64)}
function Resolve-Adb {'fixture-adb'}
function Assert-DisposableDevelopmentTarget {}
function Get-FrozenArtifact($Role,$Kind) {
 if($Role -cne 'synthetic-provider' -or $Kind -cne 'application') {throw 'Wrong provider artifact role'}
 @{path='current-provider.apk';evidence=$Expected}
}
function Invoke-AdbReadOnlyProbe {'Users: UserInfo{0:Owner:13} running UserInfo{10:Other:10}'}
function Invoke-QualificationOutput {
 $global:LASTEXITCODE=0
 $User=$args[[array]::IndexOf($args,'--user')+1]
 if($User -ceq '0' -or $script:CrossUser) {'package:app.stemwise.testprovider'}
}
function Get-InstalledDevelopmentApkIdentity($Adb,$Package) {
 if($Package -cne 'app.stemwise.testprovider') {throw 'Wrong installed package readback'}
 @{applicationId=$Package;certificateSha256=$script:InstalledSigner;sha256=$script:InstalledSha}
}
function Invoke-AdbChecked($Adb,$Arguments) {
 if(($Arguments -join ',') -cne 'install,-r,-t,current-provider.apk') {throw 'Provider update attempted destructive or stale installation'}
 $script:Installs++
 $script:InstalledSha=if($script:CorruptReadback) {'E'*64} else {$Expected.sha256}
}
Install-QualificationProvider
if($script:Installs -ne 1 -or $script:InstalledSha -cne $Expected.sha256) {throw 'Current provider was not installed/read back'}
$script:InstalledSigner='F'*64
try {Install-QualificationProvider; throw 'Foreign signer admitted'} catch {if($_.Exception.Message -notlike '*signing identity differs*') {throw}}
if($script:Installs -ne 1) {throw 'Foreign provider was mutated'}
$script:InstalledSigner=$Expected.certificateSha256; $script:CrossUser=$true
try {Install-QualificationProvider; throw 'Cross-user provider admitted'} catch {if($_.Exception.Message -notlike '*another Android user*') {throw}}
if($script:Installs -ne 1) {throw 'Cross-user provider was mutated'}
$script:CrossUser=$false; $script:ApprovedDevicePurpose='everyday'
try {Install-QualificationProvider; throw 'Everyday provider admitted'} catch {if($_.Exception.Message -notlike '*data is preserved*') {throw}}
if($script:Installs -ne 1) {throw 'Everyday fixture data was replaced'}
$script:ApprovedDevicePurpose='dedicated-qualification'; $script:CorruptReadback=$true
try {Install-QualificationProvider; throw 'Wrong installed bytes admitted'} catch {if($_.Exception.Message -notlike '*differs from the frozen current artifact*') {throw}}
if($script:Installs -ne 2) {throw 'Actual readback rejection path was not reached'}
Write-Output 'Provider install preservation and rejection assertions completed'
''')

    def test_native_abi_is_selected_before_build_and_passed_to_executor(self):
        self.powershell(r'''
$Mode='NativeDevice'; $Suite='fixture-suite'; $Repo=Split-Path $DeviceProfile
$LiveRoot=$Repo; $script:DeviceRunEvidenceRoot=Join-Path $Repo 'evidence'
$Serial='fixture-phone'; $script:DeviceLaneResults=[Collections.Generic.List[object]]::new()
function Resolve-Adb { Join-Path $Repo 'sdk/platform-tools/adb.exe' }
function Require-Serial {}
function Resolve-Python { 'fixture-python' }
function Resolve-GradleApk { 'fixture.apk' }
function Freeze-ApkArtifact { @{path='fixture.apk'} }
function Get-FrozenArtifact { @{path='fixture.apk'} }
function Initialize-QualificationSourceSnapshot {}
function Invoke-AdbReadOnlyProbe { $script:Abi }
function Invoke-Gradle($Arguments) {
 $script:Builds++
 if ($Arguments -cnotcontains "-Pstemwise.developmentTargetAbi=$script:Abi") { throw 'Wrong build ABI' }
}
function Invoke-Checked($File,$Arguments,$Root) {
 $Index=[Array]::IndexOf($Arguments,'--abi')
 if ($Index -lt 0 -or $Arguments[$Index+1] -cne $script:Abi) { throw 'Executor ABI absent or different' }
 $script:Executions++
}
New-Item -ItemType Directory -Path (Join-Path $Repo 'gradle') -Force | Out-Null
Set-Content -LiteralPath (Join-Path $Repo 'gradle/android-toolchain.properties') 'ndkVersion=27.2'
foreach ($script:Abi in @('x86_64','arm64-v8a','unsupported')) {
 $script:Builds=0; $script:Executions=0; $Failure=$null
 try { Invoke-FocusedChecks } catch { $Failure=$_.Exception.Message }
 if ($script:Abi -eq 'unsupported') {
  if (-not $Failure -or $script:Builds -ne 0 -or $script:Executions -ne 0) { throw 'Unsupported ABI reached build' }
 } elseif ($Failure -or $script:Builds -ne 1 -or $script:Executions -ne 1) {
  throw "Supported ABI did not reach matching executor: $Failure"
 }
}
''')

    def test_focused_run_uses_built_apk_without_archives_or_unrelated_source_scan(self):
        launcher = read_launcher_source(ROOT / 'Test suite/Run-Tester.ps1')
        definitions = '\n'.join(launcher[launcher.index('function ' + name):launcher.index('function ' + following)]
            for name, following in (
                ('Initialize-QualificationSourceSnapshot', 'Assert-QualificationSourceUnchanged'),
                ('Assert-QualificationSourceUnchanged', 'Get-ApkEvidence'),
                ('Freeze-ApkArtifact', 'Get-DevelopmentApkPairEvidence')))
        self.powershell(definitions + r'''
$script:DeviceRunEvidenceRoot = Join-Path (Split-Path $DeviceProfile) 'evidence'
$Apk = Join-Path (Split-Path $DeviceProfile) 'app.apk'
Set-Content -LiteralPath $Apk 'built app'
function Get-ApkEvidence($Path,$Role,$Kind) { return @{sha256=(Get-FileSha256 $Path)} }
function Assert-ApkEvidenceRole($Evidence) {}
function Register-ApkEvidence($Evidence) {}
function Get-FileSha256($Path) {
    $Hash = [Security.Cryptography.SHA256]::Create()
    try { [Convert]::ToBase64String($Hash.ComputeHash([IO.File]::ReadAllBytes($Path))) }
    finally { $Hash.Dispose() }
}
foreach ($Mode in @('Focused','NativeDevice')) {
    Initialize-QualificationSourceSnapshot
    Assert-QualificationSourceUnchanged
    $script:FrozenArtifacts = @{}
    $Frozen = Freeze-ApkArtifact $Apk 'qualification' 'application'
    if ($Frozen.path -ne $Apk -or (Test-Path $script:DeviceRunEvidenceRoot)) { throw 'unnecessary copy' }
    Assert-FrozenArtifactsUnchanged
}
Set-Content -LiteralPath $Apk 'changed app'
$Rejected=$false
try { Assert-FrozenArtifactsUnchanged } catch { $Rejected=$true }
if (-not $Rejected) { throw 'lost APK identity check' }
''')

    def test_frozen_device_reference_retains_the_built_abi_variant_and_exact_bytes(self):
        launcher = read_launcher_source(ROOT / 'Test suite/Run-Tester.ps1')
        definitions = launcher[launcher.index('function Freeze-ApkArtifact'):
                               launcher.index('function Get-FrozenArtifact')]
        self.powershell(definitions + r'''
$Mode='Device'; $script:FrozenArtifacts=@{}
$script:DeviceRunEvidenceRoot=Join-Path (Split-Path $DeviceProfile) 'evidence'
$Apk=Join-Path (Split-Path $DeviceProfile) 'stemwise-owner-data-rehearsal-arm64-v8a-development.apk'
Set-Content -LiteralPath $Apk 'built app bytes'
function Get-FileSha256($Path) {
 $Hash=[Security.Cryptography.SHA256]::Create()
 try { [Convert]::ToBase64String($Hash.ComputeHash([IO.File]::ReadAllBytes($Path))) }
 finally { $Hash.Dispose() }
}
function Get-ApkEvidence($Path,$Role,$Kind) { @{sha256=(Get-FileSha256 $Path);fileName=[IO.Path]::GetFileName($Path)} }
function Assert-ApkEvidenceRole($Evidence) {}
function Register-ApkEvidence($Evidence) {}
$Frozen=Freeze-ApkArtifact $Apk 'qualification' 'application'
if ($Frozen.path -cne $Apk -or -not (Test-Path -LiteralPath $Frozen.path)) { throw 'Lost current built artifact' }
if ($Frozen.evidence.fileName -cne [IO.Path]::GetFileName($Apk)) { throw 'Lost built variant name' }
if (Test-Path -LiteralPath $script:DeviceRunEvidenceRoot) { throw 'Created an unnecessary duplicate APK' }
if ((Get-FileSha256 $Frozen.path) -cne $Frozen.evidence.sha256) { throw 'Lost exact built bytes' }
''')

    def test_phone_settings_are_enforced_and_restored_on_success_or_failure(self):
        launcher = read_launcher_source(ROOT / 'Test suite/Run-Tester.ps1')
        wrapper = launcher[launcher.index('function Invoke-WithDeviceMutex'):
                           launcher.index('function Invoke-WithWorkspaceMutex')]
        owned = native_path(ROOT / 'Test suite/host/Owned-Process.ps1').replace("'", "''")
        self.powershell(". '" + owned + "'\n" + wrapper + r"""
$Serial='fixture-phone'
$DeviceLane='All'; $Class=@()
$script:DeviceInventoryAccount=@{methods=@(@{id='fixture.Audio#plays';class='fixture.Audio';requiresAudioIsolation=$true})}
function Get-DeviceKeyguardState { [pscustomobject]@{secure=$false;unlocked=$true} }
function Require-Serial {
    $script:Validations++
    if ($script:Validations -ne 1) { throw 'Repeated target validation instead of running the test' }
}
function Invoke-WithNonBlockingMutex($Name,$BusyMessage,[scriptblock]$Operation) { & $Operation }
function Resolve-Adb { return 'fixture-adb' }
function Restore-DeviceUnlocked {
    $script:UnlockCalls++
    if ($script:Scenario -eq 'operation-and-unlock-failure' -and $script:UnlockCalls -eq 2) { throw 'unlock cleanup failure' }
}
function Invoke-AdbReadOnlyProbe($Adb,$Arguments,$Label) {
    if ($Arguments[-1] -eq 'stay_on_while_plugged_in') { return $script:Awake }
    if ($Arguments[-1] -eq 'zen_mode') { return $script:Zen }
    if ($Arguments[-1] -eq 'airplane-mode') {
        $script:AirplaneProbes++
        if ($script:Scenario -eq 'invalid-airplane-baseline' -and $script:AirplaneProbes -eq 1) { return 'unknown' }
        $ActivationReadback = if ($InitialAirplane -ceq 'enabled') {2} else {3}
        $RestorationReadback = if ($InitialAirplane -ceq 'enabled') {3} else {5}
        if ($script:Scenario -eq 'airplane-readback-failure' -and $script:AirplaneProbes -eq $ActivationReadback) { return 'unknown' }
        if ($script:Scenario -in @('airplane-restore-readback-failure','operation-and-airplane-restore-failure') -and
                $script:AirplaneProbes -eq $RestorationReadback) { return 'unknown' }
        return $script:Airplane
    }
    throw 'Unexpected settings probe'
}
function Invoke-QualificationProcess($FilePath,$Arguments,$TimeoutSeconds) {
    $Command=$Arguments[2..($Arguments.Count-1)] -join ' '
    switch ($Command) {
        'shell svc power stayon true' {
            if ($script:Scenario -ne 'rejected-write') { $script:Awake='15' }
        }
        'shell cmd notification set_dnd priority' { $script:Zen='1' }
        'shell cmd connectivity airplane-mode enable' { $script:Airplane='enabled' }
        'shell cmd connectivity airplane-mode disable' { $script:Airplane='disabled' }
        'shell settings put global stay_on_while_plugged_in 2' { $script:Awake='2' }
        'shell cmd notification set_dnd alarms' { $script:Zen='3' }
        'shell input keyevent KEYCODE_WAKEUP' {}
        default { throw "Unexpected settings mutation: $Command" }
    }
    return @{ExitCode=0;Stdout='';Stderr=''}
}
foreach ($Mode in @('Device','Full')) {
foreach ($InitialAirplane in @('enabled','disabled')) {
foreach ($Scenario in @('success','operation-failure','rejected-write','operation-and-unlock-failure',
        'airplane-readback-failure','airplane-restore-readback-failure','operation-and-airplane-restore-failure',
        'invalid-airplane-baseline')) {
    $script:Scenario=$Scenario; $script:Awake='2'; $script:Zen='3'; $script:Calls=0; $script:Validations=0; $script:UnlockCalls=0
    $script:Airplane=$InitialAirplane; $script:AirplaneProbes=0
    $Failure=$null
    try {
        Invoke-WithDeviceMutex {
            Invoke-WithAudioSelectionIsolation @('fixture.Audio') {
            $script:Calls++
            if ($script:Awake -ne '15' -or $script:Zen -ne '1' -or $script:Airplane -cne 'enabled') { throw 'Settings absent during test' }
            if ($script:Scenario -in @('operation-failure','operation-and-unlock-failure','operation-and-airplane-restore-failure')) { throw 'original operation failure' }
            }
        }
    } catch { $Failure=$_.Exception.Message }
    if ($script:Awake -ne '2' -or $script:Zen -ne '3' -or $script:Airplane -cne $InitialAirplane) { throw "Settings leaked after $Scenario/$InitialAirplane" }
    if ($script:Validations -ne 1) { throw "Device setup did not run once: $Failure" }
    if ($Scenario -eq 'success' -and ($null -ne $Failure -or $script:Calls -ne 1)) { throw 'Valid test did not run' }
    if ($Scenario -eq 'operation-failure' -and ($Failure -notmatch 'original operation failure' -or $script:Calls -ne 1)) { throw 'Original failure lost' }
    if ($Scenario -eq 'rejected-write' -and ($Failure -notmatch 'Stay Awake' -or $script:Calls -ne 0)) { throw 'Test ran without Stay Awake' }
    if ($Scenario -eq 'operation-and-unlock-failure' -and ($Failure -notmatch 'original operation failure' -or
            $Failure -notmatch 'unlock cleanup failure' -or $script:UnlockCalls -ne 2)) {throw 'Unlock cleanup hid the original failure'}
    if ($Scenario -eq 'airplane-readback-failure' -and ($Failure -notmatch 'airplane mode' -or $script:Calls -ne 0)) { throw 'Test ran without verified airplane mode' }
    if ($Scenario -eq 'airplane-restore-readback-failure' -and ($Failure -notmatch 'Airplane mode could not be verified' -or $script:Calls -ne 1)) { throw "Lost airplane restoration failure: $Failure" }
    if ($Scenario -eq 'operation-and-airplane-restore-failure' -and ($Failure -notmatch 'original operation failure' -or
            $Failure -notmatch 'Airplane mode could not be verified' -or $script:Calls -ne 1)) { throw 'Airplane cleanup hid the original failure' }
    if ($Scenario -eq 'invalid-airplane-baseline' -and ($Failure -notmatch 'Cannot preserve' -or $script:Calls -ne 0 -or
            $script:AirplaneProbes -ne 1)) { throw 'Unpreservable airplane mode was admitted or changed' }
}
}
}
""")

    def test_airplane_is_scoped_to_complete_audio_selections(self):
        lanes = native_path(ROOT / 'Test suite/device/Device-Lanes.ps1').replace("'", "''")
        self.powershell(". '" + lanes + "'\n" + r'''
$script:DeviceInventoryAccount=@{methods=@(
 @{id='fixture.Audio#one';class='fixture.Audio';wrapper='fixture.AudioSuite';requiresAudioIsolation=$true},
 @{id='fixture.Audio#two';class='fixture.Audio';wrapper='fixture.AudioSuite';requiresAudioIsolation=$true},
 @{id='fixture.Network#wifi';class='fixture.Network';wrapper='fixture.MixedSuite';requiresAudioIsolation=$false},
 @{id='fixture.OtherAudio#plays';class='fixture.OtherAudio';wrapper='fixture.MixedSuite';requiresAudioIsolation=$true}
)}
function Resolve-Adb {'fixture'}
function Invoke-AdbReadOnlyProbe($Adb,$Arguments,$Label) {
 if (($Arguments -join ' ') -cne 'shell cmd connectivity airplane-mode') {throw 'Unexpected probe'}
 $script:Probes++; return $script:Airplane
}
function Invoke-FixtureSettingWrite($Arguments) {
 $script:Writes++
 switch ($Arguments[2..($Arguments.Count-1)] -join ' ') {
  'shell cmd connectivity airplane-mode enable' {$script:Airplane='enabled'}
  'shell cmd connectivity airplane-mode disable' {$script:Airplane='disabled'}
  default {throw 'Unexpected mutation'}
 }
 return @{ExitCode=0;Stdout='';Stderr=''}
}
function Invoke-QualificationProcess($FilePath,$Arguments,$TimeoutSeconds) {Invoke-FixtureSettingWrite $Arguments}
$Cases=@(
 @{selectors=@();audio=$false},
 @{selectors=@('fixture.Audio#one');audio=$true},
 @{selectors=@('fixture.Audio');audio=$true},
 @{selectors=@('fixture.AudioSuite');audio=$true},
 @{selectors=@('fixture.Network');audio=$false},
 @{selectors=@('fixture.UnmatchedNetwork');audio=$false},
 @{selectors=@('fixture.MixedSuite');audio=$false},
 @{selectors=@('fixture.Audio','fixture.Network');audio=$false},
 @{selectors=@('fixture.Audio','fixture.UnmatchedNetwork');audio=$false}
)
foreach ($Case in $Cases) {
 $script:Airplane='disabled'; $script:Probes=0; $script:Writes=0; $script:Calls=0
 Invoke-WithAudioSelectionIsolation $Case.selectors {
  $script:Calls++
  $Expected=if ($Case.audio) {'enabled'} else {'disabled'}
  if ($script:Airplane -cne $Expected) {throw 'Incorrect selection environment'}
 }
 if ($script:Calls -ne 1 -or $script:Airplane -cne 'disabled') {throw 'Operation or original state lost'}
 if ($Case.audio) {
  if ($script:Writes -ne 2 -or $script:Probes -ne 5) {throw 'Audio isolation was not verified and restored'}
 } elseif ($script:Writes -ne 0 -or $script:Probes -ne 0) {throw 'Nonaudio connectivity was touched'}
}
$script:Airplane='disabled'; $script:Calls=0
Invoke-WithAudioSelectionIsolation -PhysicalOutput -Operation {
 $script:Calls++; if ($script:Airplane -cne 'enabled') {throw 'Physical probe was not isolated'}
}
if ($script:Calls -ne 1 -or $script:Airplane -cne 'disabled') {throw 'Physical probe leaked airplane mode'}
$Serial='fixture'; $script:DeviceRunEvidenceRoot=$null
function Get-FrozenArtifact($Role,$Kind) {@{evidence=@{sha256='fixture'}}}
function Invoke-QualificationProcess($FilePath,$Arguments,$TimeoutSeconds) {
 if ($Arguments -notcontains 'instrument') {return Invoke-FixtureSettingWrite $Arguments}
 $Selector=$Arguments[$Arguments.IndexOf('class')+1]
 $Expected=if ($Selector -ceq 'fixture.Audio#one') {'enabled'} else {'disabled'}
 if ($script:Airplane -cne $Expected) {throw 'Direct instrumentation used the wrong audio environment'}
 @{ExitCode=0;Stdout='OK (1 test)';Stderr=''}
}
foreach ($Selector in @('fixture.Audio#one','fixture.Network#wifi')) {
 Invoke-DirectInstrumentation 'fixture' $Selector 'fixture/runner' | Out-Null
 if ($script:Airplane -cne 'disabled') {throw 'Direct instrumentation leaked airplane mode'}
}
''')

    def test_screen_off_preflight_requires_only_process_unlock_authority_for_owning_selections(self):
        account = runpy.run_path(str(ROOT / 'Test suite/device/device_inventory.py'))['build_accounting']()
        actual = [row for row in account['methods'] if row['id'] ==
                  'app.stemwise.playback.LoopSourceHandoffInstrumentationTest#clearingAnEditedLoopResumesLinearAudioInBothSources']
        self.assertEqual(1, len(actual))
        self.assertEqual('app.stemwise.qualification.UnifiedPlayerPressureSuite', actual[0]['wrapper'])
        self.powershell(r'''
$Prior=[Environment]::GetEnvironmentVariable('STEMWISE_DEVICE_UNLOCK_PIN','Process')
$script:SecureFixture=$true
function Get-DeviceKeyguardState { [pscustomobject]@{secure=$script:SecureFixture;unlocked=$true} }
try {
 [Environment]::SetEnvironmentVariable('STEMWISE_DEVICE_UNLOCK_PIN',$null,'Process')
 foreach($Mode in @('Full','AudioQualify','Device','Focused')) {
  $DeviceLane='All'
  $Class=if($Mode -ceq 'Focused') {@('app.stemwise.playback.LoopSourceHandoffInstrumentationTest#clearingAnEditedLoopResumesLinearAudioInBothSources')} else {@()}
  $Failure=$null; try {Assert-DeviceUnlockPrepared} catch {$Failure=$_}
  if(-not $Failure) {throw 'Screen-off selection ran without unlock authority'}
 }
 $Mode='Device'; $Class=@('fixture.Ordinary'); Assert-DeviceUnlockPrepared
 $Mode='Focused'; $Class=@('fixture.Ordinary#one'); Assert-DeviceUnlockPrepared
 $Mode='Device'; $Class=@(); $DeviceLane='ordinary'; Assert-DeviceUnlockPrepared
 $Mode='Full'; $script:SecureFixture=$false; Assert-DeviceUnlockPrepared
 $script:SecureFixture=$true
 [Environment]::SetEnvironmentVariable('STEMWISE_DEVICE_UNLOCK_PIN','1357','Process')
 $Mode='Full'; Assert-DeviceUnlockPrepared
} finally {[Environment]::SetEnvironmentVariable('STEMWISE_DEVICE_UNLOCK_PIN',$Prior,'Process')}
''')

    def test_secure_unlock_uses_guarded_stdin_once_and_verifies_actual_keyguard_state(self):
        from powershell_fixture import literal_path
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            fake = root / 'adb.py'
            fake.write_text(r'''
import json,os,sys
from pathlib import Path
path=Path(sys.argv[1]); state=json.loads(path.read_text(encoding='utf-8'))
arguments=sys.argv[2:]; body=sys.stdin.read(); command=arguments[2:]
scenario=state['scenario']; text=''; code=0
expected=os.environ.get('STEMWISE_DEVICE_UNLOCK_PIN','')
if expected and any(expected in argument for argument in arguments): state['argumentLeak']=True
if command==['shell','dumpsys','window','policy']:
 locked='true' if state['locked'] else 'false'
 text='KeyguardServiceDelegate\n  showing='+locked+'\n  inputRestricted='+locked+'\n'
 if scenario!='unknown-security': text+='  secure='+('false' if scenario=='nonsecure' else 'true')+'\n'
elif command==['shell','wm','size']: text='Physical size: 1440x3088\n'
elif command==['shell','wm','dismiss-keyguard']:
 if scenario=='nonsecure': state['locked']=False
elif command[:3]==['shell','uiautomator','dump']: state['hierarchy']=True
elif command[:2]==['shell','cat']:
 text=('<hierarchy><node package="com.android.systemui" resource-id="com.android.systemui:id/pinEntry" '
       'class="android.widget.EditText" focused="'+('false' if scenario=='wrong-field' else 'true')+'" password="true" /></hierarchy>')
 if scenario=='malformed-xml': text='<hierarchy'
elif command[:3]==['shell','rm','-f']: state['hierarchy']=False
elif command==['shell','sh']:
 state['attempts']+=1; state['stdinCorrect']=bool(expected) and expected in body
 if scenario=='echo-secret': text=body; code=1
 elif scenario!='failed-attempt': state['locked']=False
elif command[:2]!=['shell','input'] and command!=['shell','wm','dismiss-keyguard']: code=2
path.write_text(json.dumps(state),encoding='utf-8')
sys.stdout.write(text); sys.exit(code)
''', encoding='utf-8')
            batch = root / 'adb.cmd'
            # Public dummy fixture credentials never represent a device owner credential.
            fixture_python = sys.executable if os.name == 'nt' else shutil.which('python.exe')
            self.assertIsNotNone(fixture_python, 'Windows Python fixture dependency unavailable')
            batch.write_text('@echo off\n@"' + native_path(fixture_python) + '" -B "' + native_path(fake) + '" "' +
                             native_path(root / 'state.json') + '" %*\n', encoding='utf-8')
            owned = literal_path(ROOT / 'Test suite/host/Owned-Process.ps1')
            self.powershell('. ' + owned + '\n$FixtureRoot=' + literal_path(root) + r'''
$Serial='fixture'; $Prior=[Environment]::GetEnvironmentVariable('STEMWISE_DEVICE_UNLOCK_PIN','Process')
function Resolve-Adb {Join-Path $FixtureRoot 'adb.cmd'}
try {
 foreach($Scenario in @('already-unlocked','nonsecure','unknown-security','success','missing-pin','malformed-pin','wrong-field','malformed-xml','failed-attempt','echo-secret')) {
  [Environment]::SetEnvironmentVariable('STEMWISE_DEVICE_UNLOCK_PIN',$(if($Scenario -cin @('missing-pin','nonsecure')) {$null} elseif($Scenario -ceq 'malformed-pin') {'not-a-pin'} else {'1357'}),'Process')
  $State=@{scenario=$Scenario;locked=($Scenario -cne 'already-unlocked');attempts=0;hierarchy=$false;argumentLeak=$false;stdinCorrect=$false}
  $Path=Join-Path $FixtureRoot 'state.json'; $State|ConvertTo-Json|Set-Content -LiteralPath $Path -Encoding UTF8
  $Failure=$null; try {Restore-DeviceUnlocked} catch {$Failure=$_}
  $Actual=Get-Content -LiteralPath $Path -Raw|ConvertFrom-Json
  $ExpectedAttempts=if($Scenario -cin @('success','failed-attempt','echo-secret')) {1} else {0}
  if($Actual.attempts -ne $ExpectedAttempts -or $Actual.argumentLeak -or $Actual.hierarchy) {throw "Unsafe unlock boundary: $Scenario"}
  if($ExpectedAttempts -eq 1 -and -not $Actual.stdinCorrect) {throw 'Credential did not cross real owned stdin'}
  if($Scenario -cin @('already-unlocked','nonsecure','success')) {
   if($Failure -or $Actual.locked) {throw "Unlock did not verify success: $Scenario $Failure"}
  } elseif(-not $Failure) {throw "Invalid unlock passed: $Scenario"}
  if($Failure -and [string]$Failure -match '1357') {throw 'Credential leaked through failure output'}
 }
} finally {[Environment]::SetEnvironmentVariable('STEMWISE_DEVICE_UNLOCK_PIN',$Prior,'Process')}
''', timeout=60)

    def test_disposable_device_lane_does_not_build_owner_upgrade_artifacts(self):
        launcher = read_launcher_source(ROOT / 'Test suite/Run-Tester.ps1')
        start = launcher.index('function Initialize-ImmutableQualificationArtifacts')
        end = launcher.index('\nfunction ', start + 1)
        self.powershell(launcher[start:end] + """
$Mode='Device'; $DeviceLane='ordinary'; $Class=@(); $script:FrozenArtifacts=@{}; $script:Built=0
Initialize-ApprovedDeviceProfile
function Initialize-QualificationSourceSnapshot {}
function Initialize-FocusedArtifacts { $script:Built++ }
function Assert-QualificationSourceUnchanged {}
function Assert-FrozenArtifactsUnchanged {}
function Invoke-Gradle { throw 'Built unrelated owner upgrade artifacts' }
foreach ($DeviceLane in @('ordinary','runtime-update')) {
 $script:Built=0
 Initialize-ImmutableQualificationArtifacts
 if ($script:Built -ne 1) { throw "Disposable $DeviceLane artifacts were not built exactly once" }
}
""")

    def powershell(self, body, profile=None, timeout=20):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            path = Path(directory) / 'profile.json'
            path.write_text(json.dumps(profile or {
                'serial': 'phone-42', 'model': 'CurrentHighEnd', 'device': 'test',
                'socModel': 'test-soc', 'androidBuildFingerprint': 'exact-build', 'api': 36,
            }), encoding='utf-8')
            powershell = executable()
            def native_path(value):
                if os.name != 'nt' and powershell.lower().endswith('.exe'):
                    return subprocess.check_output(['wslpath', '-w', str(value)], text=True).strip()
                return str(value)
            script = ("$ErrorActionPreference='Stop'\n. '" + native_path(ROOT / 'Test suite/device/Device-Profile.ps1').replace("'", "''") + "'\n. '" + native_path(HELPER).replace("'", "''") +
                      "'\n$DeviceProfile='" + native_path(path).replace("'", "''") + "'\n" + body)
            result = run_owned([powershell,
                                     '-NoProfile', '-NonInteractive', '-Command', script],
                                    timeout=timeout)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_profile_accepts_exact_modern_device_without_old_app_version(self):
        self.powershell("""
foreach ($Mode in @('Focused','NativeDevice')) {
    Initialize-ApprovedDeviceProfile
    if ($ApprovedDeviceSerial -cne 'phone-42' -or $ApprovedDeviceApi -cne '36' -or
        $ApprovedDeviceModel -cne 'CurrentHighEnd' -or $null -eq $CurrentDeviceProfileFile) { throw 'lost identity' }
}
""")



    def test_invalid_profile_fails_before_selecting_device(self):
        for api in ('not-a-number', '36', 36.5, True):
            with self.subTest(api=api):
                self.powershell("""
$Mode='Focused'; $Rejected=$false
try { Initialize-ApprovedDeviceProfile } catch { $Rejected=$true }
if (-not $Rejected) { throw 'accepted invalid identity' }
""", {'serial': 'phone-42', 'model': 'phone', 'device': 'test', 'socModel': 'soc',
        'androidBuildFingerprint': 'exact', 'api': api})

    def test_selector_keeps_pressure_suite_gate_and_rejects_owner_workflows(self):
        self.powershell("""
$DeviceLane='All'
$script:DeviceInventoryAccount=@{ methods=@(
    @{id='test.Ui#draw';class='test.Ui';lane='ordinary'},
    @{id='test.Audio#seek';class='test.Audio';lane='explicit-suite';wrapper='app.stemwise.qualification.UnifiedPlayerPressureSuite'},
    @{id='test.Import#bind';class='test.Import';lane='explicit-suite';wrapper='app.stemwise.qualification.IsolatedDataEngineSurfaceSuite'},
    @{id='test.Owner#erase';class='test.Owner';lane='exact-candidate'}
) }
foreach ($Selector in @('test.Ui','test.Audio#seek','test.Import#bind','app.stemwise.qualification.UnifiedPlayerPressureSuite')) {
    $Class=@($Selector); Assert-FocusedSelectors
}
foreach ($Selector in @('test.Audio','test.Import','test.Owner','missing')) {
    $Class=@($Selector); $Rejected=$false
    try { Assert-FocusedSelectors } catch { $Rejected=$true }
    if (-not $Rejected) { throw "accepted unsafe selector $Selector" }
}
""")

    def test_focused_methods_run_through_actual_wrappers_with_listener_and_exact_filter(self):
        self.powershell("""
$Mode='Focused'; $DeviceLane='All'; $DevelopmentPackage='fixture'; $script:Calls=@()
$Class=@('test.Audio#seek','test.Audio#loop','test.Import#bind')
$script:DeviceInventoryAccount=@{methods=@(
 @{id='test.Audio#seek';class='test.Audio';lane='explicit-suite';wrapper='app.stemwise.qualification.UnifiedPlayerPressureSuite'},
 @{id='test.Audio#loop';class='test.Audio';lane='explicit-suite';wrapper='app.stemwise.qualification.UnifiedPlayerPressureSuite'},
 @{id='app.stemwise.playback.RuntimePlaybackControlsInstrumentationTest#nativeActorFatalFaultImmediatelySilencesAndQuarantinesWithoutPollSpin';class='app.stemwise.playback.RuntimePlaybackControlsInstrumentationTest';lane='explicit-suite';wrapper='app.stemwise.qualification.UnifiedPlayerPressureSuite'},
 @{id='test.Import#bind';class='test.Import';lane='explicit-suite';wrapper='app.stemwise.qualification.IsolatedDataEngineSurfaceSuite'}
)}
function Require-Serial {}
function Invoke-DeviceInventoryValidation {}
function Initialize-FocusedArtifacts {}
function Resolve-Adb {'fixture-adb'}
function Invoke-AdbChecked {}
function Invoke-StandardDeviceSelection($Selectors,$InstrumentationArguments,$EvidenceLabel) {
 $script:Calls+=@{selectors=$Selectors;extra=$InstrumentationArguments}
}
function Assert-DeviceMethodAccounting {
 if (($Class -join ',') -cne 'test.Audio#seek,test.Audio#loop,test.Import#bind') {throw 'Lost requested leaf accounting'}
}
Invoke-FocusedChecks
$Filtered=@($script:Calls|Where-Object {$_.extra.ContainsKey('stemwiseFocusedMethods')})
if ($Filtered.Count -ne 2) {throw 'Expected one filtered invocation per actual wrapper'}
foreach ($Call in $Filtered) {
 if ($Call.selectors.Count -ne 1 -or $Call.extra.filter -cne 'app.stemwise.qualification.FocusedSuiteMethodFilter' -or
     $Call.extra.listener.Split(',') -cnotcontains 'app.stemwise.qualification.FocusedSuiteMethodRunListener') {
  throw 'Leaf selection bypassed wrapper, filter or method ledger listener'
 }
 if ($Call.selectors[0] -ceq 'app.stemwise.qualification.UnifiedPlayerPressureSuite') {
  if ($Call.extra.stemwiseFocusedMethods -cne 'test.Audio#seek,test.Audio#loop' -or
      $Call.extra.listener.Split(',') -cnotcontains 'app.stemwise.qualification.ProcessAudioHealthRunListener') {throw 'Playback gates lost'}
 } elseif ($Call.selectors[0] -ceq 'app.stemwise.qualification.IsolatedDataEngineSurfaceSuite') {
  if ($Call.extra.stemwiseFocusedMethods -cne 'test.Import#bind' -or
      $Call.extra.listener.Split(',') -ccontains 'app.stemwise.qualification.ProcessAudioHealthRunListener') {throw 'Wrong isolation scope'}
 } else {throw 'Direct child was dispatched'}
}
$Fatal=@($script:Calls|Where-Object {$_.extra.stemwiseNativeFatalQualification -ceq 'true'})
if ($Fatal.Count -ne 1) {throw 'Playback retirement check absent or repeated per leaf'}
""")

    def test_filtered_accounting_requires_only_selected_leaves_and_explicit_fatal_check(self):
        launcher = read_launcher_source(ROOT / 'Test suite/Run-Tester.ps1')
        start = launcher.index('function Get-ExpectedDeviceMethods')
        end = launcher.index('function Resolve-Python', start)
        self.powershell(launcher[start:end] + """
$Mode='Focused'; $DeviceLane='All'; $Class=@('test.Audio#seek'); $script:DeviceRunEvidenceRoot=$null
$script:FocusedAdditionalRequiredMethods=@('test.Audio#fatal')
$script:DeviceInventoryAccount=@{methods=@(
 @{id='test.Audio#seek';class='test.Audio';wrapper='suite'},
 @{id='test.Audio#unselected';class='test.Audio';wrapper='suite'},
 @{id='test.Audio#fatal';class='test.Audio';wrapper='suite'}
)}
$Expected=@(Get-ExpectedDeviceMethods)
if (($Expected -join ',') -cne 'test.Audio#fatal,test.Audio#seek') {throw 'Claimed full suite or omitted quarantine protection'}
$script:ObservedPassingMethods=@('test.Audio#seek','test.Audio#fatal'); $script:ObservedSkippedMethods=@()
Assert-DeviceMethodAccounting
foreach ($Bad in @('missing','skipped','unexpected')) {
 $script:ObservedPassingMethods=@('test.Audio#seek','test.Audio#fatal'); $script:ObservedSkippedMethods=@()
 if ($Bad -ceq 'missing') {$script:ObservedPassingMethods=@('test.Audio#fatal')}
 if ($Bad -ceq 'skipped') {$script:ObservedPassingMethods=@('test.Audio#fatal'); $script:ObservedSkippedMethods=@('test.Audio#seek')}
 if ($Bad -ceq 'unexpected') {$script:ObservedPassingMethods+=@('test.Audio#unselected')}
 $Rejected=$false
 try {Assert-DeviceMethodAccounting} catch {$Rejected=$true}
 if (-not $Rejected) {throw "Accepted $Bad method accounting"}
}
""")

    def test_optional_diagnostic_requires_explicit_selection_and_its_real_result(self):
        inventory = native_path(ROOT / 'Test suite/device/Device-Inventory.ps1').replace("'", "''")
        self.powershell(". '" + inventory + "'\n" + """
$Mode='Device'; $DeviceLane='All'; $Class=@(); $script:DeviceRunEvidenceRoot=$null
$script:FocusedAdditionalRequiredMethods=@()
$script:DeviceInventoryAccount=@{methods=@(
 @{id='test.Playback#stress';class='test.Playback';lane='ordinary'},
 @{id='test.Acoustic#capture';class='test.Acoustic';lane='optional'}
)}
if ((@(Get-ExpectedDeviceMethods) -join ',') -cne 'test.Playback#stress') {
 throw 'Mandatory accounting included the optional acoustic diagnostic'
}
$script:ObservedPassingMethods=@('test.Playback#stress'); $script:ObservedSkippedMethods=@()
Assert-DeviceMethodAccounting
$Mode='Focused'; $Class=@('test.Acoustic')
Assert-FocusedSelectors
if ((@(Get-ExpectedDeviceMethods) -join ',') -cne 'test.Acoustic#capture') {
 throw 'Explicit acoustic selection lost its method accounting'
}
$Rejected=$false
try {Assert-DeviceMethodAccounting} catch {$Rejected=$true}
if (-not $Rejected) {throw 'Optional selection passed without its actual result'}
$script:ObservedPassingMethods=@('test.Acoustic#capture')
Assert-DeviceMethodAccounting
""")

    def test_benchmark_rejects_incomplete_allocating_or_changed_pcm(self):
        text = '\n'.join(f'mode={mode} block=480 frames=512000 render_ns=1000 '
                         'pcm_hash=123 allocations=0 p50_ns=10 p95_ns=20'
                         for mode in ('direct', 'tuned', 'returned-neutral'))
        rows = BENCHMARK['parse_output'](text)
        BENCHMARK['compare_rows'](rows, BENCHMARK['parse_output'](text))
        for bad in (text.replace('allocations=0', 'allocations=1'), text.splitlines()[0],
                    text.replace('p50_ns=10', 'p50_ns=0'),
                    text.replace('p50_ns=10 p95_ns=20', 'p50_ns=0 p95_ns=0'),
                    text.replace('p95_ns=20', 'p95_ns=1')):
            with self.assertRaises(ValueError):
                BENCHMARK['parse_output'](bad)
        with self.assertRaises(ValueError):
            BENCHMARK['compare_rows'](rows, BENCHMARK['parse_output'](text.replace('pcm_hash=123', 'pcm_hash=124')))


if __name__ == '__main__':
    unittest.main()
