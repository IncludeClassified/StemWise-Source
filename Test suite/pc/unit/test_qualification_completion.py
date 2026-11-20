"""The Windows qualification harness cannot pass after failed cleanup/finalization."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from powershell_fixture import literal_path, run_script

SUITE = Path(__file__).resolve().parents[2]


class QualificationCompletionTest(unittest.TestCase):
    def test_final_source_failure_preserves_typed_category_and_earlier_priority(self):
        with tempfile.TemporaryDirectory(dir=SUITE/'work') as name:
            root = Path(name)
            script = root/'typed-final-source.ps1'
            script.write_text("""param([string]$FixtureRoot)
$ErrorActionPreference='Stop'
. OWNED
. REPORT
$Suite=$FixtureRoot; $Mode='PcSuite'; $RunId='typed-final-source'; $Serial='fixture'
function Initialize-QualificationSourceSnapshot {}
function Assert-QualificationSourceUnchanged {
 $Failure=[Exception]::new('final source inventory failed')
 if($script:FinalKind -cne 'untyped') {$Failure.Data['failureKind']=$script:FinalKind}
 throw $Failure
}
foreach($script:FinalKind in @('timeout','cleanup','output-limit','untyped')) {
 foreach($script:MainKind in @('success','test-failure','cleanup')) {
  $Failure=$null
  try { Invoke-RecordedDeviceOperation {
   if($script:MainKind -cne 'success') {
    $Cause=[Exception]::new('earlier main failure')
    $Cause.Data['failureKind']=$script:MainKind
    throw $Cause
   }
  } } catch {$Failure=$_}
  $Expected=if($script:MainKind -ceq 'cleanup') {'cleanup'} elseif($script:FinalKind -ceq 'untyped') {'infrastructure'} else {$script:FinalKind}
  if(-not $Failure -or (Get-QualificationFailureKind $Failure) -cne $Expected) {
   throw "Wrong final category ($script:MainKind/$script:FinalKind), expected $Expected : $Failure"
  }
  $Report=Get-Content -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'run.json') -Raw | ConvertFrom-Json
  if($Report.status -cne 'FAIL' -or $Report.failureKind -cne $Expected -or
     $Report.failure -notlike '*final source inventory failed*') {throw 'Durable category or final cause lost'}
  if($script:MainKind -cne 'success' -and $Failure.Exception.Message -notlike '*earlier main failure*') {throw 'Earlier cause lost'}
 }
}
""".replace('OWNED', literal_path(SUITE/'host/Owned-Process.ps1'))
                .replace('REPORT', literal_path(SUITE/'host/Qualification-Report.ps1')), encoding='utf-8-sig')
            result = run_script(script, root, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout+result.stderr)

    def test_development_publication_preserves_prior_apk_until_exact_qualified_copy_succeeds(self):
        from test_tester_launcher import launcher_functions
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as name:
            root = Path(name)
            script = root / 'publish.ps1'
            script.write_text(launcher_functions() + r'''
param([string]$FixtureRoot)
$ErrorActionPreference='Stop'; $LiveRoot=$FixtureRoot; $Suite=Join-Path $LiveRoot 'Test suite'
$Candidate=Join-Path $Suite 'work/audio-current/development/candidate.apk'
$Destination=Join-Path $LiveRoot 'StemWise-development.apk'
New-Item -ItemType Directory -Path (Split-Path $Candidate) -Force | Out-Null
function Resolve-Python {'fixture-python'}
function Invoke-Gradle {throw 'Publication rebuilt the candidate'}
function Invoke-Checked($File,$Arguments,$Root,$Phase) {
 if($File -cne 'fixture-python' -or $Phase -cne 'audio-promotion-gate' -or
    $Arguments[[Array]::IndexOf($Arguments,'--apk')+1] -cne $Candidate) {throw 'Wrong promotion boundary'}
 $script:GateCalls++
 if($script:Scenario -ceq 'gate') {throw 'Rejected actual qualification'}
 if($script:Scenario -ceq 'replacement') {[IO.File]::WriteAllText($Candidate,'replacement')}
}
function Get-FileSha256($Path) {
 if($script:Scenario -ceq 'hash' -and $Path -clike '*.tmp') {return '0'*64}
 $Hasher=[Security.Cryptography.SHA256]::Create()
 try {[BitConverter]::ToString($Hasher.ComputeHash([IO.File]::ReadAllBytes($Path))).Replace('-','')}
 finally {$Hasher.Dispose()}
}
function Get-ApkEvidence($Path,$Role,$Kind) {
 if($Path -cne $Candidate -or $Role -cne 'deliverable' -or $Kind -cne 'application') {throw 'Wrong artifact inspected'}
 $Hash=Get-FileSha256 $Path
 if($script:Scenario -ceq 'copy') {$script:CopyLock=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)}
 @{sha256=$Hash}
}
function Assert-ApkEvidenceRole($Evidence) {}
foreach($script:Scenario in @('gate','replacement','copy','hash','success')) {
 [IO.File]::WriteAllText($Candidate,'qualified-development')
 [IO.File]::WriteAllText($Destination,'prior-delivery')
 $script:GateCalls=0; $script:CopyLock=$null; $Failure=$null
 try {Publish-DevelopmentApk} catch {$Failure=$_} finally {if($script:CopyLock) {$script:CopyLock.Dispose()}}
 if($script:GateCalls -ne 1) {throw "Publication did not validate its exact candidate once ($script:Scenario): $Failure"}
 if($script:Scenario -ceq 'success') {
  if($Failure -or [IO.File]::ReadAllText($Destination) -cne 'qualified-development' -or
     (Get-FileSha256 $Destination) -cne (Get-FileSha256 $Candidate)) {throw 'Exact successful Development publication failed'}
 } elseif(-not $Failure -or [IO.File]::ReadAllText($Destination) -cne 'prior-delivery') {throw "Failed $script:Scenario replaced prior publication"}
 if(@(Get-ChildItem -LiteralPath $LiveRoot -Filter 'StemWise-development.apk.*.tmp').Count) {throw 'Publication left owned staging bytes'}
}
'''.replace("param([string]$FixtureRoot)", "$FixtureRoot=" + literal_path(root)), encoding='utf-8-sig')
            result = run_script(script, timeout=20)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_finalization_failures_are_infrastructure_and_preserve_a_prior_timeout(self):
        cases = {
            'source': "function Assert-QualificationSourceUnchanged { throw 'source changed' }",
            'missing': "$Mode='Device'",
            'hash': "function Get-FileSha256 { throw 'evidence unreadable' }",
            'write': "function Set-Content { throw 'report write denied' }",
            'final-write': """$script:ReportWrites=0
function Set-Content {
 param([string]$LiteralPath, [string]$Encoding, [Parameter(ValueFromPipeline=$true)]$Value)
 process {
  $script:ReportWrites++
  if ($script:ReportWrites -eq 2) {throw 'final report write denied'}
  Microsoft.PowerShell.Management\\Set-Content -LiteralPath $LiteralPath -Value $Value -Encoding $Encoding
 }
}""",
        }
        for name, fault in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory(dir=SUITE / 'work') as directory:
                root = Path(directory)
                script = root / 'check.ps1'
                script.write_text("""param([string]$FixtureRoot)
$ErrorActionPreference='Stop'
. OWNED
. REPORT
$Suite=$FixtureRoot
$Mode='NativeDevice'; $RunId='finalize-test'; $Serial='fixture'
function Initialize-QualificationSourceSnapshot {}
function Assert-QualificationSourceUnchanged {}
FAULT
$Failure=$null
try { Invoke-RecordedDeviceOperation {
 if ('CASE' -eq 'hash') {
  [IO.File]::WriteAllText((Join-Path $script:DeviceRunEvidenceRoot 'qualification-source-manifest.json'),'{}')
 }
} } catch { $Failure=$_ }
if (-not $Failure) { throw 'Finalization failure was ignored' }
if ((Get-QualificationFailureKind $Failure) -cne 'infrastructure') {
 throw "Finalization misclassified: $(Get-QualificationFailureKind $Failure): $($Failure.Exception.Message)"
}
if ('CASE' -ne 'write') {
 $Result=Get-Content -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'run.json') -Raw | ConvertFrom-Json
 if ($Result.status -cne 'FAIL' -or $Result.failureKind -cne 'infrastructure') {throw 'Durable result misclassified'}
}
if ('CASE' -eq 'source') {
 $Mode='NativeDevice'
 $Failure=$null
 try { Invoke-RecordedDeviceOperation { throw [TimeoutException]::new('original timeout') } } catch {$Failure=$_}
 if ((Get-QualificationFailureKind $Failure) -cne 'timeout' -or $Failure.Exception.Message -notlike '*original timeout*' -or
     $Failure.Exception.Message -notlike '*source changed*') {throw 'Primary timeout or finalizer cause was lost'}
}
""".replace('OWNED', literal_path(SUITE / 'host/Owned-Process.ps1'))
                    .replace('REPORT', literal_path(SUITE / 'host/Qualification-Report.ps1'))
                    .replace('FAULT', fault).replace('CASE', name), encoding='utf-8-sig')
                result = run_script(script, root, timeout=20)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_disposable_transcript_cleanup_failure_preserves_verified_pass_with_its_exact_warning(self):
        for cause in ('transcript unlink denied',):
            with self.subTest(cause=cause), tempfile.TemporaryDirectory(dir=SUITE / 'work') as name:
                root = Path(name)
                script = root / 'check.ps1'
                script.write_text("""param([string]$FixtureRoot)
$ErrorActionPreference='Stop'
. MODULE
. OWNED
$Suite=$FixtureRoot
$Mode='NativeDevice'
$RunId='cleanup-test'
$Serial='fixture'
$script:QualificationSourceSha256=$null
$script:ProducerSystemSha256=$null
function Initialize-QualificationSourceSnapshot {}
function Assert-QualificationSourceUnchanged {}
function Remove-Item {
 param([string]$LiteralPath, [switch]$Force, [switch]$Recurse)
 if ([IO.Path]::GetFileName($LiteralPath) -ceq 'transcript.txt') { throw 'CAUSE' }
 Microsoft.PowerShell.Management\\Remove-Item @PSBoundParameters
}
$Caught=$null
try { Invoke-RecordedDeviceOperation {} } catch { $Caught=$_.Exception.Message }
if ($Caught) { throw 'Disposable transcript cleanup replaced a verified result' }
$Report=Get-Content -LiteralPath (Join-Path $FixtureRoot 'evidence/current-NativeDevice/run.json') -Raw | ConvertFrom-Json
if ($Report.status -cne 'PASS' -or $Report.failure -or $Report.failureKind -or
    @($Report.cleanupWarnings).Count -ne 1 -or $Report.cleanupWarnings[0] -notlike '*transcript.txt*CAUSE*' -or
    -not (Test-Path -LiteralPath (Join-Path $FixtureRoot 'evidence/current-NativeDevice/transcript.txt'))) {
 throw 'Verified result or exact disposable cleanup warning was lost'
}
""".replace('MODULE', literal_path(SUITE / 'host/Qualification-Report.ps1'))
                    .replace('OWNED', literal_path(SUITE / 'host/Owned-Process.ps1')).replace('CAUSE', cause),
                                  encoding='utf-8-sig')
                result = run_script(script, root, timeout=20)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_full_attempt_retains_the_qualified_development_asset_for_the_actual_claim_validator(self):
        import copy
        import hashlib
        import json
        from unittest.mock import patch
        from test_lyrics_qualification import QualificationFixture, _object_digest
        from test_tester_launcher import launcher_functions
        from stemwise_engine.lyrics_qualification import _validate_device_qualification_report
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as name:
            root = Path(name)
            producer = {key: hashlib.sha256(key.encode()).hexdigest() for key in (
                'sourceSha256', 'lyricsPolicySemanticsSha256', 'generatorConfigSha256', 'externalSchemaSha256')}
            # This consumer verifies the exact retained APK/report bytes. Source
            # discovery has its own real-tree checks and is not this fixture's input.
            for target in ('test_lyrics_qualification.producer_system_identity',
                           'stemwise_engine.lyrics_qualification.producer_system_identity'):
                boundary = patch(target, return_value=producer)
                boundary.start()
                self.addCleanup(boundary.stop)
            fixture = QualificationFixture(root / 'reference', claims_allowed=True, include_device=True)
            fixture.build()
            reference = fixture.root / 'results'
            identity_path = root / 'device-profile.json'
            identity_path.write_text(json.dumps({key: fixture.device_profile[key] for key in
                ('serial', 'model', 'device', 'socModel', 'androidBuildFingerprint', 'api')}), encoding='utf-8')
            build = root / 'build'
            build.mkdir()
            for filename in ('qualification.apk', 'qualification-test.apk'):
                (build / filename).write_bytes(fixture.deliverable_bytes)
            script = root / 'claim.ps1'
            script.write_text(launcher_functions() + """
$ErrorActionPreference='Stop'
$Suite=FIXTURE_ROOT; $Mode='Full'; $RunId='claim-fixture'; $DeviceLane='All'; $DeviceProfile=IDENTITY_PATH
$Reference=REFERENCE_ROOT; $Build=BUILD_ROOT; $LiveRoot=$Suite
$OwnerPackage='app.stemwise'; $DevelopmentPackage='app.stemwise.development'
$Template=Get-Content -LiteralPath (Join-Path $Reference 'android-run.json') -Raw | ConvertFrom-Json
$Serial=$Template.serial
$ExpectedIdentity=Get-Content -LiteralPath $DeviceProfile -Raw | ConvertFrom-Json
$ReleaseCertificateSha256='BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB'
$QualificationCertificateSha256=$Template.artifacts[0].certificateSha256
$script:PairBuilds=0; $script:UnexpectedBuilds=0
function Resolve-QualificationSigningConfiguration {}
function Get-ApkEvidence($Path,$Role,$Kind) {
 $Application=$DevelopmentPackage
 if ($Kind -ceq 'androidTest') {$Application+='.test'}
 $Certificate=$QualificationCertificateSha256
 $Hash=Get-FileSha256 $Path
 if ($script:RejectRetained -and $Path -clike '*.tmp') {$Hash='0'*64}
 [ordered]@{role=$Role;kind=$Kind;fileName=[IO.Path]::GetFileName($Path);bytes=(Get-Item $Path).Length;
  sha256=$Hash;applicationId=$Application;versionCode='56';versionName='3.7.21-development';certificateSha256=$Certificate;
  buildConfiguration=@{buildType='development';debuggable=$true;minifyEnabled=$true;shrinkResources=$true;identity=$DevelopmentPackage;signing='disposable'};
  buildEvidence=@()}
}
function Resolve-Python {'fixture-python'}
function Invoke-QualificationProcess { [pscustomobject]@{ExitCode=0;Stdout=('a'*64);Stderr=''} }
function Initialize-QualificationSourceSnapshot {
 $Files=@('qualification-source-manifest.json')
 if($Mode -ceq 'Device') {$Files+=@('inventory.json','execution-accounting.json')}
 foreach ($File in $Files) {
  Copy-Item -LiteralPath (Join-Path $Reference $File) -Destination (Join-Path $script:DeviceRunEvidenceRoot $File) -Force
 }
 if ($Mode -ceq 'PcSuite') {
  Copy-Item -LiteralPath (Join-Path $Reference 'host-qualification') -Destination $script:DeviceRunEvidenceRoot -Recurse
  $script:HostQualification=$Template.hostQualification
 }
 $script:QualificationSourceSha256=$Template.qualificationSourceSha256
 $script:ProducerSystemSha256=$Template.producerSystemSha256
}
function Assert-QualificationSourceUnchanged {}
function Get-QualificationSourceSha256 {$Template.qualificationSourceSha256}
function Resolve-Adb {'fixture-adb'}
function Invoke-AdbReadOnlyProbe($Adb,$Arguments,$Label) {
 switch ($Label) {
  'state' {'device'} 'serial' {$ExpectedIdentity.serial} 'model' {$ExpectedIdentity.model}
  'device' {$ExpectedIdentity.device} 'SoC model' {$ExpectedIdentity.socModel}
  'build fingerprint' {$ExpectedIdentity.androidBuildFingerprint} 'API' {[string]$ExpectedIdentity.api}
  default {throw "Unexpected physical identity probe: $Label"}
 }
}
function Initialize-FocusedArtifacts {
 $script:PairBuilds++
 Freeze-ApkArtifact (Join-Path $Build 'qualification.apk') 'qualification' 'application' | Out-Null
 Freeze-ApkArtifact (Join-Path $Build 'qualification-test.apk') 'qualification' 'androidTest' | Out-Null
}
function Invoke-Gradle($Arguments) {
 $script:UnexpectedBuilds++
 throw 'Qualification attempted another build instead of retaining the executed Development APK'
}
$Mode='PcSuite'
Invoke-RecordedDeviceOperation {} -EvidenceMode 'PcSuite'
$Mode='Device'
Invoke-RecordedDeviceOperation {
 Initialize-ApprovedDeviceProfile
 Require-Serial
 Initialize-ImmutableQualificationArtifacts -RetainDeliverable
 foreach ($Lane in $Template.lanes) {
  $script:DeviceLaneResults.Add($Lane)
 }
} -EvidenceMode 'AndroidSuite'
Write-CompleteSuiteReceipt
if ($script:PairBuilds -ne 1 -or $script:UnexpectedBuilds -ne 0) {throw 'Claim lost the qualified Development build boundary'}

""".replace('FIXTURE_ROOT', literal_path(root)).replace('REFERENCE_ROOT', literal_path(reference))
                .replace('BUILD_ROOT', literal_path(build)).replace('IDENTITY_PATH', literal_path(identity_path)), encoding='utf-8-sig')
            completed = run_script(script, timeout=20)
            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            report_root = root / 'evidence/current-Full'
            report = json.loads((report_root / 'run.json').read_text(encoding='utf-8-sig'))
            android_root = root / 'evidence/current-AndroidSuite'
            android_path = android_root / 'run.json'
            android = json.loads(android_path.read_text(encoding='utf-8-sig'))
            self.assertEqual(fixture.device_profile, android['deviceProfile'])
            self.assertIsNotNone(android['artifact'])
            delivered = [row for row in android['artifacts'] if row['role'] == 'deliverable']
            self.assertEqual(1, len(delivered))
            retained = android_root / delivered[0]['evidencePath']
            self.assertEqual(fixture.deliverable_bytes, retained.read_bytes())
            self.assertEqual(retained, android_root / 'audio-artifacts/application.apk')
            self.assertEqual({retained, android_root / 'audio-artifacts/androidTest.apk'}, set(root.rglob('evidence/**/*.apk')))
            _validate_device_qualification_report(report, _object_digest(fixture.device_profile),
                fixture.profile['androidQualification'], report_root)
            def bind_android(value):
                android_path.write_text(json.dumps(value), encoding='utf-8')
                composed = copy.deepcopy(report)
                composed['phases']['AndroidSuite']['sha256'] = hashlib.sha256(android_path.read_bytes()).hexdigest()
                return composed
            foreign_producer = copy.deepcopy(android)
            foreign_producer['producerSystemSha256'] = '0' * 64
            with self.assertRaisesRegex(ValueError, 'different source or producer'):
                _validate_device_qualification_report(bind_android(foreign_producer), _object_digest(fixture.device_profile),
                    fixture.profile['androidQualification'], report_root)
            for target in ('report', 'lane'):
                inconsistent = copy.deepcopy(android)
                (inconsistent if target == 'report' else inconsistent['lanes'][0])['failureKind'] = 'infrastructure'
                with self.subTest(target=target), self.assertRaisesRegex(ValueError, 'did not pass|failed lane'):
                    _validate_device_qualification_report(bind_android(inconsistent), _object_digest(fixture.device_profile),
                        fixture.profile['androidQualification'], report_root)
            report = bind_android(android)
            (build / 'qualification.apk').write_bytes(b'later unrelated build')
            _validate_device_qualification_report(report, _object_digest(fixture.device_profile),
                fixture.profile['androidQualification'], report_root)
            retained.write_bytes(b'tampered retained claim')
            with self.assertRaisesRegex(ValueError, 'evidence hash mismatch'):
                _validate_device_qualification_report(report, _object_digest(fixture.device_profile),
                    fixture.profile['androidQualification'], report_root)
