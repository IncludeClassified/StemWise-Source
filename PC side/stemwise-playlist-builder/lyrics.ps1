[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('acquire', 'scaffold', 'build','compare', 'qa', 'status')]
    [string]$Command = 'status',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

$Command = $Command.ToLowerInvariant()
$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
$projectWindows = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $PSScriptRoot 'runtime-bootstrap.ps1')
$runtime = Get-StemWiseRuntime -BuilderWindows $projectWindows
$projectWsl = $runtime.Builder

$entry = 'stemwise_engine.lyrics_intake'
$config = "$projectWsl/config.json"
Invoke-StemWiseRuntime -Runtime $runtime -CommandArguments (@('-m', $entry, '--config', $config, $Command) + $RemainingArgs)
$wslExitCode = $LASTEXITCODE
exit $wslExitCode
