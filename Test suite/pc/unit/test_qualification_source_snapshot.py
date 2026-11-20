"""Source identity must fail closed on missing inputs, read failure and concurrent mutation."""
from pathlib import Path
import runpy
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from powershell_fixture import native_path,run_script
SUITE=Path(__file__).resolve().parents[2]


class QualificationSourceSnapshotTest(unittest.TestCase):
    def test_android_source_identity_uses_host_python_without_wsl_or_model_environment(self):
        report=native_path(SUITE/'host/Qualification-Report.ps1').replace("'","''")
        self.run_fixture(". '"+report+"'\n"+r'''
$LiveRoot=$FixtureRoot
$PcBuilder=Join-Path $FixtureRoot 'PC side/stemwise-playlist-builder'
$IdentityTool=Join-Path $PcBuilder 'src/lyrics_qualification.py'
@'
import json,sys
assert sys.argv[1:] == ['--producer-system-identity']
print(json.dumps({'producerSystemSha256':'a'*64}))
'@ | Set-Content -LiteralPath $IdentityTool -Encoding utf8
function Convert-ToWslPath {throw 'Android identity unexpectedly required WSL'}
foreach ($Mode in @('Device','Actions')) {
 $script:QualificationSourceSha256=$null
 $script:ProducerSystemSha256=$null
 $script:DeviceRunEvidenceRoot=$null
 Initialize-QualificationSourceSnapshot
 if ($script:ProducerSystemSha256 -cne ('a'*64) -or
     $script:QualificationSourceSha256 -cnotmatch '^[0-9A-F]{64}$') {
  throw 'Host identity was not bound to the Android source snapshot'
 }
}
''')

    def run_fixture(self, body):
        with tempfile.TemporaryDirectory(dir=SUITE/'work',prefix="source ' identity-") as directory:
            root=Path(directory)
            module=native_path(SUITE/'host/Qualification-Evidence.ps1').replace("'","''")
            script=root/'check.ps1'
            owned=native_path(SUITE/'host/Owned-Process.ps1').replace("'","''")
            host_python = shutil.which('python.exe')
            if host_python is None:
                raise RuntimeError('Windows Python is required for the host qualification fixture')
            python=native_path(host_python).replace("'","''")
            inputs=runpy.run_path(str(SUITE.parent/'PC side/stemwise-playlist-builder/src/source_inputs.py'))
            for relative in inputs['TREES'] + ('PC side/qobuz-comparison',):
                (root/relative).mkdir(parents=True,exist_ok=True)
            for relative in inputs['FILES']:
                target=root/relative
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_text('fixture source',encoding='utf-8')
            script.write_text("param([string]$FixtureRoot)\n$ErrorActionPreference='Stop'\n. '"+owned+"'\n. '"+module+"'\nfunction Resolve-Python { return '"+python+"' }\n"+body,encoding='utf-8-sig')
            result=run_script(script,root,timeout=20)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)

    def test_real_manifest_binds_compiled_and_test_inputs_without_an_old_apk(self):
        self.run_fixture(r'''
$LiveRoot=$FixtureRoot
$Inputs=@('SEND_TO_AGENTS.md','StemWise/third_party/phone-pc-bridge/android/phone-pc-bridge.aar',
 'StemWise/third_party/phone-pc-bridge/android/phone-pc-bridge.build.json',
 'StemWise/third_party/phone-pc-bridge/source/bridge-core/current.go','StemWise/audio-engine/src/main/cpp/current.c','StemWise/audio-engine/src/main/cpp/CMakeLists.txt',
 'StemWise/audio-engine/src/main/cpp/current.inc','Test suite/native/current.def',
 'StemWise/app/src/main/java/app/stemwise/work/TaskWorkGate.kt',
 'Test suite/source_test_inventory.py','PC side/qobuz-comparison/current.py',
 'PC side/runtime-layout.json','PC side/stemwise-playlist-builder/environment-locks.json',
 'PC side/stemwise-playlist-builder/locks/current.txt',
 'PC side/stemwise-playlist-builder/future-songs/policy/current.json')
foreach ($Relative in $Inputs) {
 $Path=Join-Path $FixtureRoot $Relative
 New-Item -ItemType Directory -Path (Split-Path $Path) -Force | Out-Null
 [IO.File]::WriteAllText($Path,'before')
}
$Baseline=Get-QualificationSourceSha256
foreach ($Relative in $Inputs) {
 $Path=Join-Path $FixtureRoot $Relative
 [IO.File]::WriteAllText($Path,'changed')
 try {
  if ((Get-QualificationSourceSha256) -ceq $Baseline) {throw "Changed executable input was omitted: $Relative"}
 } finally {[IO.File]::WriteAllText($Path,'before')}
}
[IO.Directory]::Delete((Join-Path $FixtureRoot 'PC side/stemwise-playlist-builder/locks'),$true)
$Caught=$null
try {Get-QualificationSourceManifest | Out-Null} catch {$Caught=$_.Exception.Message}
if (-not $Caught) {throw 'Missing required current input tree was silently admitted'}
''')

    def test_real_read_failure_and_changed_read_never_publish_a_manifest(self):
        self.run_fixture(r'''
$LiveRoot=$FixtureRoot
$File=Join-Path $FixtureRoot 'input.txt'
[IO.File]::WriteAllText($File,'before')
function Get-QualificationSourceFiles {return @(Get-Item -LiteralPath $File)}
$Expected=@(Get-QualificationSourceManifest)
if ($Expected.Count -ne 1 -or $Expected[0].bytes -ne 6 -or $Expected[0].sha256 -cne '6DB7D803E74F1FFA7D8F5ADC0BF95B3E15BF4C8373FFFADF546227CC6C6742CB') {throw 'Stable source manifest changed'}
$Locked=[IO.File]::Open($File,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
 $Caught=$null; $Manifest=$null
 try {$Manifest=@(Get-QualificationSourceManifest)} catch {$Caught=$_.Exception.Message}
 if (-not $Caught -or $null -ne $Manifest) {throw 'Exclusive read failure returned a partial manifest'}
} finally {$Locked.Dispose()}
$OriginalHash=${function:Get-FileSha256}
function Get-FileSha256([string]$Path) {
 $Hash=& $OriginalHash $Path
 [IO.File]::AppendAllText($Path,'changed')
 return $Hash
}
$Caught=$null; $Manifest=$null
try {$Manifest=@(Get-QualificationSourceManifest)} catch {$Caught=$_.Exception.Message}
if ($Caught -notlike '*changed while hashing*' -or $null -ne $Manifest) {throw "Changed read admitted: $Caught"}
''')
