"""Device preconditions converge and restore through actual settings wrappers."""
import base64
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from powershell_fixture import literal_path, run_script

SUITE = Path(__file__).resolve().parents[2]
HANDSHAKE = ('* daemon not running; starting now at tcp:5037\n'
             'could not read ok from ADB Server\n* failed to start daemon\n'
             'adb.exe: cannot connect to daemon')


class DeviceSettingRecoveryTests(unittest.TestCase):
    def settings(self, *, target='Airplane', phase='activate', fault=None,
                 applied=False, ready=False, operation_failure=False, readback=None):
        fixture = dict(target=target, phase=phase, fault=fault, applied=applied,
                       ready=ready, operationFailure=operation_failure,
                       handshake=HANDSHAKE, readback=readback)
        encoded = base64.b64encode(json.dumps(fixture).encode()).decode()
        with tempfile.TemporaryDirectory(dir=SUITE / 'work', prefix='device-settings-') as temporary:
            script = Path(temporary) / 'settings.ps1'
            script.write_text(r'''
$ErrorActionPreference='Stop'
. OWNED
$Tokens=$null; $Errors=$null
$Ast=[Management.Automation.Language.Parser]::ParseFile(LAUNCHER,[ref]$Tokens,[ref]$Errors)
if ($Errors.Count) {throw $Errors[0]}
foreach ($Definition in $Ast.EndBlock.Statements) {
    if ($Definition -is [Management.Automation.Language.FunctionDefinitionAst]) {
        . ([scriptblock]::Create($Definition.Extent.Text))
    }
}
. FOCUSED
$Fixture=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('FIXTURE')) | ConvertFrom-Json
$Serial='fixture-phone'
$script:DeviceInventoryAccount=@{methods=@(@{id='fixture.Audio#plays';class='fixture.Audio';requiresAudioIsolation=$true})}
$script:State=@{Awake='2';Zen='3';Airplane='disabled'}
if ($Fixture.ready) {$script:State=@{Awake='15';Zen='1';Airplane='enabled'}}
$Initial=@{}; foreach ($Name in $script:State.Keys) {$Initial[$Name]=$script:State[$Name]}
$script:Calls=[Collections.Generic.List[object]]::new()
$script:Restoring=$false; $script:Faults=0; $script:Operations=0; $script:ReadbackFaults=0
function Resolve-Adb {'fixture-adb'}
function Assert-DeviceUnlockPrepared {}
function Restore-DeviceUnlocked {}
function Invoke-QualificationProcess {
    param([string]$FilePath,[string[]]$Arguments,[int]$TimeoutSeconds)
    Assert-QualificationOwnership
    if ($FilePath -cne 'fixture-adb' -or $Arguments[0] -cne '-s' -or $Arguments[1] -cne $Serial) {
        throw 'Wrong device or executable'
    }
    $Command=$Arguments[2..($Arguments.Count-1)] -join ' '
    $Name=$null; $Value=$null; $Write=$true
    switch ($Command) {
        'shell settings get global stay_on_while_plugged_in' {$Name='Awake';$Write=$false}
        'shell settings get global zen_mode' {$Name='Zen';$Write=$false}
        'shell cmd connectivity airplane-mode' {$Name='Airplane';$Write=$false}
        'shell svc power stayon true' {$Name='Awake';$Value='15'}
        'shell settings put global stay_on_while_plugged_in 2' {$Name='Awake';$Value='2'}
        'shell settings put global stay_on_while_plugged_in 15' {$Name='Awake';$Value='15'}
        'shell cmd notification set_dnd priority' {$Name='Zen';$Value='1'}
        'shell cmd notification set_dnd alarms' {$Name='Zen';$Value='3'}
        'shell cmd connectivity airplane-mode enable' {$Name='Airplane';$Value='enabled'}
        'shell cmd connectivity airplane-mode disable' {$Name='Airplane';$Value='disabled'}
        default {throw "Unexpected device command: $Command"}
    }
    $Phase=if ($Write -and $Value -ceq $Initial[$Name]) {'restore'} elseif ($script:Restoring) {'restore'} else {'activate'}
    $script:Calls.Add(@{arguments=@($Arguments);name=$Name;write=$Write;phase=$Phase})
    if (-not $Write) {
        if ($null -ne $Fixture.readback -and $Name -ceq $Fixture.target -and $script:Faults -gt 0 -and $script:ReadbackFaults -eq 0) {
            $script:ReadbackFaults++
            return @{ExitCode=0;Stdout=$Fixture.readback;Stderr=''}
        }
        return @{ExitCode=0;Stdout=$script:State[$Name];Stderr=''}
    }
    $Inject=$Fixture.fault -and $Name -ceq $Fixture.target -and $Phase -ceq $Fixture.phase -and
        ($script:Faults -eq 0 -or $Fixture.fault -ceq 'exhausted')
    if ($Inject) {
        $script:Faults++
        if ($Fixture.applied) {$script:State[$Name]=$Value}
        if ($Fixture.fault -cin @('timeout','cleanup')) {
            $Failure=New-QualificationException "owned $($Fixture.fault) boundary" $null $Fixture.fault
            Assert-QualificationOwnership $Failure
            throw $Failure
        }
        $Diagnostic=switch ($Fixture.fault) {
            'refusal' {"$($Fixture.handshake)`nerror: device unauthorized"}
            'unknown' {'error: user command cannot connect to daemon'}
            default {$Fixture.handshake}
        }
        return @{ExitCode=1;Stdout='';Stderr=$Diagnostic}
    }
    $script:State[$Name]=$Value
    return @{ExitCode=0;Stdout='';Stderr=''}
}
$Failure=$null; $Kind=$null
try {
    Invoke-WithDeviceTestSettings {
        try {
            Invoke-WithAudioSelectionIsolation @('fixture.Audio') {
                $script:Operations++
                if ($script:State.Awake -cne '15' -or $script:State.Zen -cne '1' -or
                        $script:State.Airplane -cne 'enabled') {throw 'Operation ran without actual preconditions'}
                $script:Restoring=$true
                if ($Fixture.operationFailure) {throw 'original operation failure'}
            }
        } finally {$script:Restoring=$true}
    }
} catch {$Failure=$_.Exception.Message; $Kind=Get-QualificationFailureKind $_}
[Console]::Out.WriteLine('RESULT:'+(@{failure=$Failure;kind=$Kind;operations=$script:Operations;
    initial=$Initial;state=$script:State;faults=$script:Faults;calls=@($script:Calls.ToArray())} |
    ConvertTo-Json -Depth 8 -Compress))
'''.replace('OWNED', literal_path(SUITE / 'host/Owned-Process.ps1'))
                .replace('LAUNCHER', literal_path(SUITE / 'Run-Tester.ps1'))
                .replace('FOCUSED', literal_path(SUITE / 'device/Focused-Checks.ps1'))
                .replace('FIXTURE', encoded), encoding='utf-8-sig')
            completed = run_script(script, timeout=30)
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            rows = [line[7:] for line in completed.stdout.splitlines() if line.startswith('RESULT:')]
            self.assertEqual(len(rows), 1, completed.stdout + completed.stderr)
            return json.loads(rows[0])

    def writes(self, result, target, phase):
        return [call['arguments'] for call in result['calls']
                if call['write'] and call['name'] == target and call['phase'] == phase]

    def test_already_satisfied_settings_do_not_write_or_restore_redundantly(self):
        result = self.settings(ready=True)
        self.assertIsNone(result['failure'])
        self.assertEqual(result['operations'], 1)
        self.assertEqual(result['state'], result['initial'])
        self.assertFalse([call for call in result['calls'] if call['write']])

    def test_handshake_failure_after_application_uses_verified_state_without_replay(self):
        for target in ('Awake', 'Zen', 'Airplane'):
            for phase in ('activate', 'restore'):
                with self.subTest(target=target, phase=phase):
                    result = self.settings(target=target, phase=phase, fault='handshake', applied=True)
                    self.assertIsNone(result['failure'])
                    self.assertEqual(result['operations'], 1)
                    self.assertEqual(result['faults'], 1)
                    self.assertEqual(len(self.writes(result, target, phase)), 1)
                    self.assertEqual(result['state'], result['initial'])

    def test_handshake_before_application_retries_only_the_identical_setting_once(self):
        for target in ('Awake', 'Zen', 'Airplane'):
            for phase in ('activate', 'restore'):
                with self.subTest(target=target, phase=phase):
                    result = self.settings(target=target, phase=phase, fault='handshake')
                    self.assertIsNone(result['failure'])
                    self.assertEqual(result['operations'], 1)
                    writes = self.writes(result, target, phase)
                    self.assertEqual(len(writes), 2)
                    self.assertEqual(writes[0], writes[1])
                    indices = [index for index, call in enumerate(result['calls'])
                               if call['write'] and call['name'] == target and call['phase'] == phase]
                    self.assertTrue(any(not call['write'] and call['name'] == target
                                        for call in result['calls'][indices[0] + 1:indices[1]]))
                    self.assertEqual(result['state'], result['initial'])

    def test_exhausted_activation_is_infrastructure_and_never_runs_the_test(self):
        result = self.settings(fault='exhausted')
        self.assertEqual(result['kind'], 'infrastructure')
        self.assertEqual(result['operations'], 0)
        self.assertEqual(len(self.writes(result, 'Airplane', 'activate')), 2)
        self.assertEqual(result['state'], result['initial'])

    def test_malformed_recovery_readback_never_authorizes_another_write(self):
        for target, invalid in (('Awake', '-1'), ('Zen', 'unknown'), ('Airplane', 'unknown')):
            with self.subTest(target=target):
                result = self.settings(target=target, fault='handshake', readback=invalid)
                self.assertIsNotNone(result['failure'])
                self.assertIn(HANDSHAKE, result['failure'])
                self.assertIn(invalid, result['failure'])
                self.assertEqual(result['operations'], 0)
                self.assertEqual(len(self.writes(result, target, 'activate')), 1)
                self.assertEqual(result['state'], result['initial'])

    def test_refusal_unknown_timeout_and_cleanup_never_replay_a_setting(self):
        for fault in ('refusal', 'unknown', 'timeout', 'cleanup'):
            with self.subTest(fault=fault):
                result = self.settings(fault=fault)
                self.assertIsNotNone(result['failure'])
                self.assertEqual(result['operations'], 0)
                self.assertEqual(len(self.writes(result, 'Airplane', 'activate')), 1)
                if fault in ('timeout', 'cleanup'):
                    self.assertEqual(result['kind'], fault)
                else:
                    self.assertNotEqual(result['kind'], 'infrastructure')

    def test_exhausted_restoration_is_cleanup_and_preserves_original_failure(self):
        result = self.settings(phase='restore', fault='exhausted', operation_failure=True)
        self.assertEqual(result['kind'], 'cleanup')
        self.assertEqual(result['operations'], 1)
        self.assertIn('original operation failure', result['failure'])
        self.assertEqual(len(self.writes(result, 'Airplane', 'restore')), 2)
        self.assertEqual(result['state']['Awake'], '2')
        self.assertEqual(result['state']['Zen'], '3')
        self.assertEqual(result['state']['Airplane'], 'enabled')


if __name__ == '__main__':
    unittest.main()
