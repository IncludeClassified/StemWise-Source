[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('setup', 'doctor', 'inventory', 'status', 'audio')]
    [string]$Command = 'status',

    [string]$RoformerWheel,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

$Command = $Command.ToLowerInvariant()
$ErrorActionPreference = 'Stop'
if ($RoformerWheel -and $Command -ne 'setup') { throw '-RoformerWheel requires setup.' }
if ($Command -eq 'audio') {
    & "$PSScriptRoot/audio.ps1" @RemainingArgs
    exit $LASTEXITCODE
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
$projectWindows = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $PSScriptRoot 'runtime-bootstrap.ps1')
$runtime = Get-StemWiseRuntime -BuilderWindows $projectWindows -SkipInstalledCheck:($Command -eq 'setup')
$projectWsl = $runtime.Builder
$environmentArgs = @($runtime.Environment)

if ($Command -eq 'setup') {
    if ($RoformerWheel) {
        $wheelWindows = (Resolve-Path -LiteralPath $RoformerWheel -ErrorAction Stop).Path
        $wheelWsl = (& wsl.exe -d $runtime.Distribution -- wslpath -a ($wheelWindows -replace '\\', '/')).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $wheelWsl) { throw 'Could not map the supplied approved wheel into WSL.' }
        $environmentArgs += "STEMWISE_ROFORMER_WHEEL=$wheelWsl"
    }
    & wsl.exe -d $runtime.Distribution -- env @environmentArgs python3 "$projectWsl/src/environment_locks.py" main --check-artifacts
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & wsl.exe -d $runtime.Distribution -u root -- bash "$projectWsl/setup_system.sh"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & wsl.exe -d $runtime.Distribution -- env @environmentArgs STEMWISE_SKIP_APT=1 bash "$projectWsl/setup_wsl.sh"
    exit $LASTEXITCODE
}

$python = $runtime.Python

$entry = 'stemwise_engine.stemwise_pc'
$config = "$projectWsl/config.json"
# Native programs legitimately use stderr for progress and deprecation notices.
# Preserve their real exit code instead of letting PowerShell's Stop policy turn
# a harmless stderr line into a terminating wrapper exception.
$ErrorActionPreference = 'Continue'
& wsl.exe -d $runtime.Distribution -- env @environmentArgs $runtime.BootstrapPython -B -m stemwise_engine.runtime_environment $python -B -m $entry --config $config $Command @RemainingArgs
$wslExitCode = $LASTEXITCODE
exit $wslExitCode
