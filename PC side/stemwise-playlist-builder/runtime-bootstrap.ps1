function Get-StemWiseRuntimeLayout {
    param([Parameter(Mandatory=$true)][string]$BuilderWindows)
    $layout = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $BuilderWindows) 'runtime-layout.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($layout.schemaVersion -ne 1 -or $layout.wslDistribution -notmatch '^[A-Za-z0-9][A-Za-z0-9_. -]*$') {
        throw 'The shared runtime layout or WSL distribution is invalid.'
    }
    foreach ($relative in @($layout.python.executable, $layout.python.managedInstallDirectory) + @($layout.environments.psobject.Properties.Value) + @($layout.environmentVariables.psobject.Properties.Value)) {
        if ($relative -notmatch '^[A-Za-z0-9_.\-/]+$' -or $relative.StartsWith('/') -or $relative -match '(^|/)\.\.(/|$)') {
            throw 'A shared runtime path escapes PC side.'
        }
    }
    foreach ($variable in $layout.environmentVariables.psobject.Properties.Name) {
        if ($variable -notmatch '^[A-Z][A-Z0-9_]*$') { throw 'A shared runtime environment variable is invalid.' }
    }
    return $layout
}

function Get-StemWiseRuntime {
    param([Parameter(Mandatory=$true)][string]$BuilderWindows, [string]$Route = 'main', [switch]$SkipInstalledCheck)
    $utf8 = New-Object Text.UTF8Encoding($false)
    $OutputEncoding = [Console]::OutputEncoding = $utf8
    $layout = Get-StemWiseRuntimeLayout -BuilderWindows $BuilderWindows
    $distribution = $layout.wslDistribution
    $builder = (& wsl.exe -d $distribution -- wslpath -a ($BuilderWindows -replace '\\','/')).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $builder) { throw 'Could not map the builder into WSL.' }
    $pcSide = $builder.Substring(0, $builder.LastIndexOf('/'))
    $relativePython = $layout.environments.$Route
    if (-not $relativePython) { throw 'The requested environment is missing from runtime-layout.json.' }
    $python = "$pcSide/$relativePython"
    $bootstrapPython = "$builder/.tools/bootstrap/bin/python"
    if (-not $SkipInstalledCheck) {
        & wsl.exe -d $distribution -- test -x $python
        if ($LASTEXITCODE -ne 0) { throw 'The WSL builder environment is not installed. Run .\stemwise.ps1 setup first.' }
        & wsl.exe -d $distribution -- test -x $bootstrapPython
        if ($LASTEXITCODE -ne 0) { throw 'The control environment is not installed. Run .\stemwise.ps1 setup first.' }
    }
    $environment = @('PYTHONDONTWRITEBYTECODE=1', 'OPENBLAS_NUM_THREADS=1', 'OMP_NUM_THREADS=1',
        "STEMWISE_ALLIN1_PYTHON=$pcSide/$($layout.environments.allin1)")
    foreach ($variable in $layout.environmentVariables.psobject.Properties) {
        $environment += "$($variable.Name)=$pcSide/$($variable.Value)"
    }
    if ($null -ne $env:STEMWISE_LAUNCH_TOKEN) {
        if ($env:STEMWISE_LAUNCH_TOKEN -cnotmatch '^[0-9a-f]{32}$') { throw 'Invalid workflow operation ID.' }
        $environment += "STEMWISE_LAUNCH_TOKEN=$env:STEMWISE_LAUNCH_TOKEN"
    }
    [pscustomobject]@{ Builder=$builder; PcSide=$pcSide; Python=$python; BootstrapPython=$bootstrapPython;
        Environment=$environment; Distribution=$distribution; Layout=$layout }
}

function Invoke-StemWiseRuntime {
    param([Parameter(Mandatory=$true)]$Runtime, [Parameter(Mandatory=$true)][string[]]$CommandArguments)
    $ErrorActionPreference = 'Continue'
    & wsl.exe -d $Runtime.Distribution -- env @($Runtime.Environment) $Runtime.BootstrapPython -B -m stemwise_engine.runtime_environment $Runtime.Python -B @CommandArguments
    $global:LASTEXITCODE = $LASTEXITCODE
}
