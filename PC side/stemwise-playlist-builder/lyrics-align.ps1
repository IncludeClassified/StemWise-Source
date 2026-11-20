[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('setup', 'doctor', 'model', 'inventory', 'align', 'status', 'validate')]
    [string]$Command = 'status',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
$projectWindows = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $PSScriptRoot 'runtime-bootstrap.ps1')
$runtime = Get-StemWiseRuntime -BuilderWindows $projectWindows -Route lyrics -SkipInstalledCheck:($Command -eq 'setup')
$projectWsl = $runtime.Builder
$python = $runtime.Python
$runtimePython = $runtime.BootstrapPython
$version = ($runtime.Layout.python.version.Split('.')[0..1] -join '.')
$venv = $python.Substring(0, $python.LastIndexOf('/bin/'))
$sitePackages = "$venv/lib/python$version/site-packages"
$libraryPath = @(
    "$sitePackages/nvidia/cublas/lib",
    "$sitePackages/nvidia/cudnn/lib",
    "$sitePackages/nvidia/cuda_nvrtc/lib"
) -join ':'
$environmentArgs = @($runtime.Environment) + @("LD_LIBRARY_PATH=$libraryPath")
$setupEnvironmentArgs = @($runtime.Environment)

if ($Command -eq 'setup') {
    foreach ($setupRoute in @('lyrics', 'main')) {
        & wsl.exe -d $runtime.Distribution -- env @setupEnvironmentArgs $runtimePython -m stemwise_engine.environment_locks $setupRoute --check-inputs
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    foreach ($setupRoute in @('lyrics', 'main')) {
        & wsl.exe -d $runtime.Distribution -- env @setupEnvironmentArgs $runtimePython -m stemwise_engine.environment_bootstrap $setupRoute
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    exit $LASTEXITCODE
}

& wsl.exe -d $runtime.Distribution -- test -x $python
if ($LASTEXITCODE -ne 0) {
    throw 'The lyric-alignment environment is missing. Run .\lyrics-align.ps1 setup.'
}

$entry = 'stemwise_engine.lyrics_align_pc'
$config = "$projectWsl/config.json"
$ErrorActionPreference = 'Continue'

function Invoke-AlignmentPython {
    param([string]$Subcommand, [string[]]$Arguments = @())
    & wsl.exe -d $runtime.Distribution -- env @environmentArgs $runtimePython -B -m stemwise_engine.runtime_environment $python -B -m $entry --config $config $Subcommand @Arguments
    $script:CommandExitCode = $LASTEXITCODE
}

switch ($Command) {
    'doctor' { Invoke-AlignmentPython 'doctor' $RemainingArgs; exit $script:CommandExitCode }
    'model' { Invoke-AlignmentPython 'preload-model' $RemainingArgs; exit $script:CommandExitCode }
    'inventory' { Invoke-AlignmentPython 'inventory' $RemainingArgs; exit $script:CommandExitCode }
    'align' { Invoke-AlignmentPython 'align' $RemainingArgs; exit $script:CommandExitCode }
    'status' { Invoke-AlignmentPython 'status' $RemainingArgs; exit $script:CommandExitCode }
    'validate' { Invoke-AlignmentPython 'validate' $RemainingArgs; exit $script:CommandExitCode }
}
