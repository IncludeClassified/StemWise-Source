"""Dedicated-target admission preserves everyday installs and verifies actual installed APK readback."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from powershell_fixture import literal_path, run_script
from test_tester_launcher import launcher_functions

SUITE = Path(__file__).resolve().parents[2]


class DisposableDeviceAdmissionTest(unittest.TestCase):
    def run_fixture(self, scenario):
        with tempfile.TemporaryDirectory(dir=SUITE / 'work') as temporary:
            root = Path(temporary)
            script = root / 'admission.ps1'
            script.write_text('param([string]$FixtureRoot)\n' + launcher_functions() + r'''
$ErrorActionPreference='Stop'
$Suite=$FixtureRoot; $Serial='FIXTURE'; $DevelopmentPackage='app.stemwise.development'
$QualificationCertificateSha256='A'*64
$script:Installed=$true; $script:CrossUser=$false; $script:Mutations=0; $script:Pulls=0
$OwnerData=Join-Path $FixtureRoot 'owner-data'; $OwnerGrant=Join-Path $FixtureRoot 'owner-grant'
[IO.File]::WriteAllText($OwnerData,'remembered controls')
[IO.File]::WriteAllText($OwnerGrant,'persisted root')
$InstalledApk=Join-Path $FixtureRoot 'actual.apk'; [IO.File]::WriteAllText($InstalledApk,'actual installed bytes')
function Resolve-Adb {'fixture-adb'}
function Require-Serial {$script:ValidatedDeviceProfile=@{serial='FIXTURE'}}
function Resolve-QualificationSigningConfiguration {}
function Invoke-AdbReadOnlyProbe($Adb,$Arguments,$Label) {
 switch ($Label) {
  'Android users' {return 'Users: UserInfo{0:Owner:13} running UserInfo{10:Other:10}'}
  'installed Development APK path' {return 'package:/data/app/current/base.apk'}
  'installed Development APK digest' {return ((Get-FileHash $InstalledApk -Algorithm SHA256).Hash.ToLowerInvariant()+'  /data/app/current/base.apk')}
  default {throw "Unexpected read-only probe: $Label"}
 }
}
function Invoke-QualificationOutput {
 $global:LASTEXITCODE=0
 $User=$args[[array]::IndexOf($args,'--user')+1]; $Package=$args[-1]
 if($Package -ceq $DevelopmentPackage -and (($User -ceq '0' -and $script:Installed) -or ($User -ceq '10' -and $script:CrossUser))) {"package:$Package"}
}
function Invoke-AdbChecked($Adb,$Arguments) {
 if($Arguments[0] -ceq 'pull') {$script:Pulls++; Copy-Item -LiteralPath $InstalledApk -Destination $Arguments[2]; return}
 if($Arguments[0] -ceq 'uninstall') {$script:Mutations++; $script:Installed=$false; return}
 if(($Arguments -join ' ') -clike 'shell am force-stop*') {return}
 throw ('Unexpected mutation: '+($Arguments -join ' '))
}
function Get-ApkEvidence($Path,$Role,$Kind) {
 @{applicationId=$DevelopmentPackage;sha256=(Get-FileHash $Path -Algorithm SHA256).Hash;
   certificateSha256=$script:Certificate}
}
$script:Certificate=$QualificationCertificateSha256
''' + scenario, encoding='utf-8')
            result = run_script(script, root, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_everyday_install_is_refused_before_any_mutation_and_keeps_data_and_grants(self):
        self.run_fixture(r'''
$script:ApprovedDevicePurpose=$null
$Failure=$null
try {Reset-DisposableDevicePackages} catch {$Failure=$_.Exception.Message}
if($Failure -notlike '*Existing Development data is preserved*') {throw "Wrong refusal: $Failure"}
if($script:Mutations -ne 0 -or $script:Pulls -ne 0 -or -not $script:Installed) {throw 'Everyday app was changed'}
if([IO.File]::ReadAllText($OwnerData) -cne 'remembered controls' -or [IO.File]::ReadAllText($OwnerGrant) -cne 'persisted root') {throw 'Everyday data/grants changed'}
''')

    def test_dedicated_target_repeats_actual_readback_and_cleanup_but_refuses_cross_user_or_wrong_signer(self):
        self.run_fixture(r'''
$script:ApprovedDevicePurpose='dedicated-qualification'
Reset-DisposableDevicePackages
if($script:Mutations -ne 1 -or $script:Pulls -ne 1 -or $script:Installed) {throw 'Dedicated reset did not run'}
# A new tester invocation must read the prior qualification install, not trust an admission flag.
$script:DisposableDevelopmentTargetSerial=$null; $script:Installed=$true
Reset-DisposableDevicePackages
if($script:Mutations -ne 2 -or $script:Pulls -ne 2) {throw 'Repeated run did not verify its installed bytes'}
$script:DisposableDevelopmentTargetSerial=$null; $script:Installed=$true; $script:CrossUser=$true
try {Reset-DisposableDevicePackages; throw 'Cross-user install was admitted'} catch {if($_.Exception.Message -notlike '*another Android user*') {throw}}
if($script:Mutations -ne 2 -or -not $script:Installed) {throw 'Cross-user package was changed'}
$script:CrossUser=$false; $script:Certificate='B'*64
try {Reset-DisposableDevicePackages; throw 'Unowned signer was admitted'} catch {if($_.Exception.Message -notlike '*not signed by the protected qualification identity*') {throw}}
if($script:Mutations -ne 2 -or -not $script:Installed) {throw 'Unowned package was changed'}
if(@(Get-ChildItem -LiteralPath (Join-Path $Suite 'work') -Directory -ErrorAction SilentlyContinue).Count) {throw 'Admission scratch survived'}
''')
