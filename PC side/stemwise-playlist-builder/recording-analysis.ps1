[CmdletBinding()]
param(
    [Parameter(Position=0)][ValidateSet('analyze','repair','status')][string]$Command,
    [Parameter(Mandatory=$true)][ValidatePattern('^qobuz:[1-9][0-9]*$')][string]$RecordingKey
)
$Command = $Command.ToLowerInvariant()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-bootstrap.ps1')
$runtime = Get-StemWiseRuntime -BuilderWindows $PSScriptRoot
$builderWsl = $runtime.Builder
Invoke-StemWiseRuntime -Runtime $runtime -CommandArguments @(
    '-m', 'stemwise_engine.recording_analysis', "--config", "$builderWsl/config.json", $Command, "--recording-key", $RecordingKey)
exit $LASTEXITCODE
