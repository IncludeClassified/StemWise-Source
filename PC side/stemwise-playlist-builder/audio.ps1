[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('download', 'register', 'compare', 'pair', 'plan')]
    [string]$Command = 'compare',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

$Command = $Command.ToLowerInvariant()
$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
$builderWindows = $PSScriptRoot
. (Join-Path $PSScriptRoot 'runtime-bootstrap.ps1')
$runtime = Get-StemWiseRuntime -BuilderWindows $builderWindows
$mappedArgs = foreach ($argument in $RemainingArgs) {
    if ($argument -match '^[A-Za-z]:[\\/]') {
        (& wsl.exe -d $runtime.Distribution -- wslpath -a ($argument -replace '\\', '/')).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Could not map an audio path into WSL.' }
    } else { $argument }
}
$environmentArgs = @($runtime.Environment)
if ($env:STEMWISE_DOUBLED_DOUBLE_TOKEN) { throw 'Use --job-url on Windows; pass session tokens privately inside WSL.' }
$ErrorActionPreference = 'Continue'
& wsl.exe -d $runtime.Distribution -- env @environmentArgs $runtime.BootstrapPython -B -m stemwise_engine.runtime_environment $runtime.Python -B -m stemwise_media.audio_library $Command @mappedArgs
exit $LASTEXITCODE
