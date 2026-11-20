[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ContextFile,
    [Parameter(Mandatory = $true)][string]$GuideFile,
    [Parameter(Mandatory = $true)][long]$MaxFileBytes,
    [switch]$Inspect
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$ProjectPrefix = $ProjectRoot.TrimEnd('\') + '\'
function Convert-BridgeInputPath([string]$Value) {
    $Exact = [IO.Path]::GetFullPath($Value)
    if (-not $Exact.StartsWith($ProjectPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $Exact -PathType Leaf) -or
        ((Get-Item -LiteralPath $Exact).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Bridge inputs must be regular files inside the configured StemWise project.'
    }
    $Mapped = & wsl.exe -d $runtime.Distribution -- wslpath -a ($Exact -replace '\\', '/')
    if ($LASTEXITCODE -ne 0 -or -not $Mapped) { throw 'Could not map a bridge input into WSL.' }
    return $Mapped.Trim()
}
if ($MaxFileBytes -le 0) { throw 'Use the live bridge handoff positive maxFileBytes.' }
. (Join-Path $PSScriptRoot 'stemwise-playlist-builder/runtime-bootstrap.ps1')
$runtime = Get-StemWiseRuntime -BuilderWindows (Join-Path $PSScriptRoot 'stemwise-playlist-builder')
$Builder = $runtime.Builder
$ContextPath = Convert-BridgeInputPath $ContextFile
$GuidePath = Convert-BridgeInputPath $GuideFile
$Arguments = @('-m', 'stemwise_engine.bridge_song_dispatch', '--config', "$($Builder.Trim())/config.json",
    '--context', $ContextPath, '--guide', $GuidePath, '--max-file-bytes', $MaxFileBytes)
if ($Inspect) { $Arguments += '--inspect' }
Invoke-StemWiseRuntime -Runtime $runtime -CommandArguments $Arguments
exit $LASTEXITCODE
