param([string]$Go = 'go', [ValidateSet('./protocol','./codex','./mobile','./pc','./cmd/bridge-pc-check','./files','./backend')][string[]]$Packages = @('./protocol','./codex','./mobile','./pc','./cmd/bridge-pc-check','./files','./backend'), [switch]$Json, [switch]$Race, [ValidateNotNullOrEmpty()][string]$Run = '.')
$ErrorActionPreference = 'Stop'
$Go = (Get-Command $Go -ErrorAction Stop).Source
$module = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../bridge-core'))
$sources = Join-Path $PSScriptRoot 'bridge-core'
$work = Join-Path $PSScriptRoot ('.work/go-' + [guid]::NewGuid().ToString('N'))
$stagedModule = Join-Path $work 'module'
$saved = @{}
foreach ($name in @('GOFLAGS','TMP','TEMP','GOTMPDIR','GOWORK','GOTOOLCHAIN')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
if (-not $Packages.Count) { throw 'At least one test package required.' }
if ($Run.Contains('/')) { throw 'Use a top-level test regex for -Run; subtest filters cannot be verified by discovery.' }
Push-Location $module
try {
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    $env:GOFLAGS = '-mod=readonly'
    $env:TMP = $work
    $env:TEMP = $work
    $env:GOTMPDIR = $work
    $env:GOWORK = 'off'
    $env:GOTOOLCHAIN = 'local'
    $pins = Get-Content -LiteralPath (Join-Path $module 'sdk-build-pins.json') -Raw | ConvertFrom-Json
    $version = (& $Go env GOVERSION).Trim()
    if ($LASTEXITCODE -ne 0 -or $version -cne $pins.go) { throw ('Go suite requires pinned toolchain ' + $pins.go) }
    New-Item -ItemType Directory -Path $stagedModule -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $module 'go.mod') -Destination (Join-Path $stagedModule 'go.mod')
    Copy-Item -LiteralPath (Join-Path $module 'go.sum') -Destination (Join-Path $stagedModule 'go.sum')
    # One disposable module gives Go ordinary package/test discovery while tests stay in the suite.
    foreach ($file in Get-ChildItem -LiteralPath $module -Recurse -File -Filter '*.go') {
        $relative = $file.FullName.Substring($module.Length + 1)
        if ($relative -match '(^|[\\/])\.work[\\/]') { continue }
        $target = Join-Path $stagedModule $relative
        New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $target
    }
    foreach ($file in Get-ChildItem -LiteralPath $sources -Recurse -File -Filter '*.go') {
        $relative = $file.FullName.Substring($sources.Length + 1)
        $target = Join-Path $stagedModule $relative
        if (Test-Path -LiteralPath $target) { throw ('Suite file would replace production source: ' + $relative) }
        New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $target
    }
    Set-Location -LiteralPath $stagedModule
    $listed = @(& $Go list -f '{{.ImportPath}}|{{join .TestGoFiles ","}}|{{join .XTestGoFiles ","}}' @packages)
    if ($LASTEXITCODE -ne 0 -or $listed.Count -ne $packages.Count) { throw 'Go test discovery failed.' }
    foreach ($package in $listed) {
        if ($package -notmatch '\|[^|]+\|' -and $package -notmatch '\|\|[^|]+$') { throw "No suite tests discovered: $package" }
    }
    & $Go vet @Packages
    if ($LASTEXITCODE -ne 0) { throw 'Go vet failed.' }
    if ($Run -cne '.') {
        $discoveryFormat = @()
        if ($Race) { $discoveryFormat = @('-race') }
        foreach ($package in $Packages) {
            $selected = @(& $Go test @discoveryFormat $package '-timeout=2m' ('-list='+$Run) '-skip=' '-count=1')
            if ($LASTEXITCODE -ne 0) { throw ('Filtered Go discovery failed: ' + $package) }
            if (-not @($selected | Where-Object { $_ -cmatch '^Test($|[^a-z])' }).Count) {
                throw ('No suite tests match -Run in ' + $package)
            }
        }
    }
    $format = @()
    if ($Json) { $format = @('-json') }
    if ($Race) { $format += '-race' }
    & $Go test @format @Packages '-timeout=2m' ('-run='+$Run) '-skip=' '-count=1'
    if ($LASTEXITCODE -ne 0) { throw 'Go suite failed.' }
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    $exact = [IO.Path]::GetFullPath($work)
    $parent = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.work'))
    if ([IO.Path]::GetDirectoryName($exact) -ne $parent -or [IO.Path]::GetFileName($exact) -notmatch '^go-[0-9a-f]{32}$') { throw 'Unsafe suite cleanup path.' }
    if (Test-Path -LiteralPath $exact) {
        if ((Get-Item -LiteralPath $exact -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Suite staging is a reparse point.' }
        if (Get-ChildItem -LiteralPath $exact -Recurse -Force -Attributes ReparsePoint | Select-Object -First 1) { throw 'Suite staging contains a reparse point.' }
        Remove-Item -LiteralPath $exact -Recurse -Force
    }
}
