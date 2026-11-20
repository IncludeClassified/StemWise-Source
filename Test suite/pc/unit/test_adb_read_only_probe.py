"""Read-only ADB recovery must stay at the exact host-connection boundary."""
import base64
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from powershell_fixture import literal_path, run_script

SUITE = Path(__file__).resolve().parents[2]
SERVER_CONNECT = 'adb: cannot connect to daemon at tcp:5037: cannot connect to 127.0.0.1:5037: Connection timed out (10060)'
SERVER_ERROR_CONNECT = ('* daemon still not running\r\n'
                        'error: cannot connect to daemon at tcp:5037: cannot connect to 127.0.0.1:5037: '
                        'A connection attempt failed because the connected party did not properly respond '
                        'after a period of time, or established connection failed because connected host '
                        'has failed to respond. (10060)\r\n')
SERVER_HANDSHAKE = ('* daemon not running; starting now at tcp:5037\n'
                    'could not read ok from ADB Server\n* failed to start daemon\n'
                    'adb.exe: failed to check server version: cannot connect to daemon')


class AdbReadOnlyProbeTests(unittest.TestCase):
    def probe(self, replies, *, profile=False):
        encoded = base64.b64encode(json.dumps({'replies': replies, 'profile': profile}).encode()).decode()
        with tempfile.TemporaryDirectory(dir=SUITE / 'work', prefix='adb-read-only-') as temporary:
            script = Path(temporary) / 'probe.ps1'
            script.write_text(r'''
$ErrorActionPreference='Stop'
. OWNED
foreach ($Path in @(LAUNCHER, PROFILE)) {
    $Tokens=$null; $ParseErrors=$null
    $Ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$Tokens,[ref]$ParseErrors)
    if ($ParseErrors.Count) {throw $ParseErrors[0]}
    foreach ($Definition in $Ast.EndBlock.Statements) {
        if ($Definition -is [Management.Automation.Language.FunctionDefinitionAst] -and
                $Definition.Name -cin @('Test-AdbHostConnectionFailure','Invoke-AdbReadOnlyProbe','Require-Serial')) {
            . ([scriptblock]::Create($Definition.Extent.Text))
        }
    }
}
$Fixture=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('FIXTURE')) | ConvertFrom-Json
$script:Calls=[Collections.Generic.List[object]]::new()
function Invoke-QualificationProcess {
    param([string]$FilePath,[string[]]$Arguments,[int]$TimeoutSeconds)
    $Index=$script:Calls.Count
    $script:Calls.Add([ordered]@{executable=$FilePath;arguments=@($Arguments)})
    if ($Index -ge @($Fixture.replies).Count) {throw 'Unexpected extra probe'}
    $Reply=@($Fixture.replies)[$Index]
    if ($Reply.exception) {
        $Failure=[Exception]::new("owned $($Reply.exception) boundary")
        $Failure.Data['failureKind']=$Reply.exception
        throw $Failure
    }
    return [pscustomobject]@{ExitCode=[int]$Reply.code;Stdout=[string]$Reply.stdout;
        Stderr=[string]$Reply.stderr;TimedOut=$false;OutputTruncated=$false}
}
$Serial='approved-serial'; $Mode='Focused'
$script:ApprovedDeviceSerial=$Serial; $script:ApprovedDeviceModel='model'
$script:ApprovedDeviceCodeName='device-code'; $script:ApprovedDeviceSocModel='soc'
$script:ApprovedDeviceBuildFingerprint='fingerprint'; $script:ApprovedDeviceApi='36'
$script:CurrentDeviceProfileFile=[pscustomobject]@{Name='owned-profile.json'}
$script:ValidatedDeviceProfile=$null
function Resolve-Adb {'fixture-adb'}
$Value=$null; $Failure=$null; $Kind=$null
try {
    if ($Fixture.profile) {Require-Serial; $Value='profile admitted'}
    else {$Value=Invoke-AdbReadOnlyProbe 'fixture-adb' @('get-state') 'state'}
} catch {$Failure=$_.Exception.Message; $Kind=$_.Exception.Data['failureKind']}
[Console]::Out.WriteLine('RESULT:'+([ordered]@{value=$Value;failure=$Failure;kind=$Kind;
    calls=@($script:Calls.ToArray());validated=$script:ValidatedDeviceProfile} | ConvertTo-Json -Depth 8 -Compress))
'''.replace('OWNED', literal_path(SUITE / 'host/Owned-Process.ps1'))
                .replace('LAUNCHER', literal_path(SUITE / 'Run-Tester.ps1'))
                .replace('PROFILE', literal_path(SUITE / 'device/Device-Profile.ps1'))
                .replace('FIXTURE', encoded), encoding='utf-8-sig')
            completed = run_script(script, timeout=30)
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            rows = [line.removeprefix('RESULT:') for line in completed.stdout.splitlines() if line.startswith('RESULT:')]
            self.assertEqual(len(rows), 1, completed.stdout + completed.stderr)
            result = json.loads(rows[0])
            for call in result['calls']:
                self.assertEqual(call['executable'], 'fixture-adb')
                self.assertEqual(call['arguments'][:2], ['-s', 'approved-serial'])
                if not profile:
                    self.assertEqual(call['arguments'], ['-s', 'approved-serial', 'get-state'])
            return result, completed.stderr

    def test_success_keeps_stdout_separate_and_does_not_retry(self):
        result, stderr = self.probe([{'code': 0, 'stdout': ' device \n', 'stderr': 'benign warning\n'}])
        self.assertEqual(result['value'], 'device')
        self.assertIsNone(result['failure'])
        self.assertEqual(len(result['calls']), 1)
        self.assertIn('benign warning', stderr)

    def test_known_host_connection_or_handshake_failure_retries_only_the_same_read(self):
        for diagnostic in (SERVER_CONNECT, SERVER_ERROR_CONNECT, SERVER_HANDSHAKE):
            with self.subTest(diagnostic=diagnostic):
                result, stderr = self.probe([{'code': 1, 'stderr': diagnostic}, {'code': 0, 'stdout': 'device\n'}])
                self.assertIsNone(result['failure'])
                self.assertEqual(result['value'], 'device')
                self.assertEqual(len(result['calls']), 2)
                self.assertIn(diagnostic, stderr)

    def test_exhausted_host_connection_failure_is_infrastructure_without_a_third_read(self):
        result, _ = self.probe([{'code': 1, 'stderr': SERVER_ERROR_CONNECT},
                               {'code': 2, 'stderr': SERVER_CONNECT}])
        self.assertEqual(len(result['calls']), 2)
        self.assertIsNone(result['value'])
        self.assertEqual(result['kind'], 'infrastructure')
        for detail in ('exit code 1', 'exit code 2', SERVER_ERROR_CONNECT, SERVER_CONNECT):
            self.assertIn(detail, result['failure'])

    def test_second_failure_keeps_both_exit_codes_and_streams_without_a_third_attempt(self):
        result, _ = self.probe([{'code': 1, 'stdout': 'first stdout', 'stderr': SERVER_CONNECT},
                               {'code': 2, 'stdout': 'second stdout', 'stderr': 'second failure detail'}])
        self.assertIsNone(result['value'])
        self.assertEqual(len(result['calls']), 2)
        for detail in ('exit code 1', 'exit code 2', 'first stdout', SERVER_CONNECT, 'second stdout', 'second failure detail'):
            self.assertIn(detail, result['failure'])

    def test_device_and_unknown_failures_never_retry(self):
        for diagnostic in ('error: device offline', 'error: device unauthorized',
                           "error: device 'approved-serial' not found", 'error: no devices/emulators found',
                           'error: more than one device/emulator', 'error: protocol fault: connection reset',
                           'Connection timed out: connect', '* failed to start daemon',
                           'error: user command cannot connect to daemon'):
            with self.subTest(diagnostic=diagnostic):
                result, _ = self.probe([{'code': 1, 'stderr': diagnostic}])
                self.assertEqual(len(result['calls']), 1)
                self.assertIn(diagnostic, result['failure'])

    def test_device_refusal_overrides_a_host_connection_signature(self):
        for refusal in ('device offline', 'device unauthorized', "device 'approved-serial' not found",
                        'no devices/emulators found', 'more than one device/emulator'):
            with self.subTest(refusal=refusal):
                result, _ = self.probe([{'code': 1, 'stderr': SERVER_ERROR_CONNECT + '\n' + refusal}])
                self.assertEqual(len(result['calls']), 1)
                self.assertIn(refusal, result['failure'])
                self.assertNotEqual(result['kind'], 'infrastructure')

    def test_timeout_cleanup_and_other_process_exceptions_propagate_without_retry(self):
        for kind in ('timeout', 'cleanup', 'infrastructure'):
            with self.subTest(kind=kind):
                result, _ = self.probe([{'exception': kind}])
                self.assertEqual(len(result['calls']), 1)
                self.assertEqual(result['failure'], f'owned {kind} boundary')
                self.assertEqual(result['kind'], kind)

    def test_successful_device_state_or_identity_mismatch_still_fails_profile_admission(self):
        values = ['device', 'approved-serial', 'model', 'device-code', 'soc', 'fingerprint', '36']
        for index, wrong in ((0, 'offline'), (1, 'different-serial'), (2, 'different-model')):
            with self.subTest(field=index):
                observed = list(values)
                observed[index] = wrong
                result, _ = self.probe([{'code': 0, 'stdout': value} for value in observed], profile=True)
                self.assertIn('Refusing device profile', result['failure'])
                self.assertIsNone(result['validated'])
                self.assertEqual(len(result['calls']), 7)
                self.assertEqual(len({tuple(call['arguments']) for call in result['calls']}), 7)


if __name__ == '__main__':
    unittest.main()
