param(
    [string]$Go = 'go',
    [string]$Gomobile = 'gomobile',
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Ndk = $env:ANDROID_NDK_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$PublishTo = '',
    [ValidateSet('./protocol','./codex','./mobile','./pc','./cmd/bridge-pc-check','./files','./backend')]
    [ValidateNotNullOrEmpty()][string[]]$TestPackages = @('./protocol','./codex','./mobile','./pc','./cmd/bridge-pc-check','./files','./backend')
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$work = Join-Path $root '.work/sdk'
$archive = Join-Path $work 'archive'
if (-not $Sdk -or -not $Ndk -or -not $Jdk) { throw 'Set Android SDK, NDK and JDK paths before building.' }
$Go = (Get-Command $Go -ErrorAction Stop).Source
$Gomobile = (Get-Command $Gomobile -ErrorAction Stop).Source
if ($PublishTo) { $PublishTo = [IO.Path]::GetFullPath($PublishTo) }
$saved = @{}
foreach ($name in @('PATH','ANDROID_HOME','ANDROID_NDK_HOME','JAVA_HOME','TMP','TEMP','GOTMPDIR','GOFLAGS','GOTOOLCHAIN','GOWORK','GOOS','GOARCH','CGO_ENABLED')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function RemoveSdkWork {
    $exact = [IO.Path]::GetFullPath($work)
    if ($exact -ne [IO.Path]::GetFullPath((Join-Path $root '.work/sdk'))) { throw 'Unsafe SDK cleanup path.' }
    if (Test-Path -LiteralPath $exact) {
        if ((Get-Item -LiteralPath $exact -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'SDK staging is a reparse point.' }
        if (Get-ChildItem -LiteralPath $exact -Force -Recurse -Attributes ReparsePoint | Select-Object -First 1) { throw 'SDK staging contains a reparse point.' }
        Remove-Item -LiteralPath $exact -Recurse -Force
    }
}
function NativeRun([string]$tool, [string[]]$arguments) {
    & $tool @arguments
    if ($LASTEXITCODE -ne 0) { throw "SDK tool failed: $([IO.Path]::GetFileName($tool))" }
}
Push-Location $root
try {
    & (Join-Path $root '../test-suite/BuildWorkCheck.ps1')
    RemoveSdkWork
    New-Item -ItemType Directory -Path $archive -Force | Out-Null
    $env:PATH = (Join-Path $Jdk 'bin') + ';' + (Split-Path $Go) + ';' + (Split-Path $Gomobile) + ';' + $env:PATH
    $env:ANDROID_HOME = $Sdk
    $env:ANDROID_NDK_HOME = $Ndk
    $env:JAVA_HOME = $Jdk
    $env:TMP = $work
    $env:TEMP = $work
    $env:GOTMPDIR = $work
    # Ignore inherited test-skipping/alternate-executor flags and preserve pins.
    $env:GOFLAGS = '-mod=readonly'
    $env:GOTOOLCHAIN = 'local'
    $env:GOWORK = 'off'
    $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
    $pins=Get-Content -LiteralPath (Join-Path $root 'sdk-build-pins.json') -Raw | ConvertFrom-Json
    if(($pins.schema -cne 'bridge-sdk-build-v1') -or $pins.abi -cne 'arm64-v8a' -or
        ($pins.androidApi -isnot [long] -and $pins.androidApi -isnot [int]) -or
        ($pins.compileSdk -isnot [long] -and $pins.compileSdk -isnot [int]) -or
        $pins.androidApi -lt 21 -or $pins.compileSdk -lt $pins.androidApi){throw 'Unsupported SDK build pins.'}
    $androidTarget = 'android/arm64' # The sole supported ABI is validated above.
    $androidJar = Join-Path $Sdk ('platforms/android-'+$pins.compileSdk+'/android.jar')
    $goVersion=(& $Go env GOVERSION).Trim()
    if($LASTEXITCODE -ne 0 -or $goVersion -cne $pins.go){throw ('SDK requires Go '+$pins.go)}
    foreach($tool in @($Gomobile,(Get-Command gobind -ErrorAction Stop).Source)){
        $identity=(& $Go version -m $tool) -join "`n"
        if($LASTEXITCODE -ne 0 -or $identity -notmatch ('(?m)^\s+mod\s+golang\.org/x/mobile\s+'+[regex]::Escape($pins.xMobile)+'\s')){throw 'gomobile/gobind must match the pinned x/mobile version.'}
    }
    $javaVersion=(& (Join-Path $Jdk 'bin/javac.exe') -version 2>&1) -join "`n"
    if($LASTEXITCODE -ne 0 -or $javaVersion.Trim() -cne ('javac '+$pins.javac)){throw ('SDK requires javac '+$pins.javac)}
    $ndkVersion=Get-Content -LiteralPath (Join-Path $Ndk 'source.properties') | Where-Object {$_ -match '^Pkg.Revision\s*='}
    if(($ndkVersion -replace '^Pkg.Revision\s*=\s*','').Trim() -cne $pins.ndk){throw ('SDK requires Android NDK '+$pins.ndk)}
    if(-not (Test-Path -LiteralPath $androidJar)){throw 'Pinned Android compile SDK platform missing.'}
    $captured = Join-Path $work 'production-inputs.json'
    NativeRun (Get-Command python -ErrorAction Stop).Source @((Join-Path $root 'sdk_artifact.py'),'capture','--go',$Go,'--manifest',$captured)
    & (Join-Path $root '../test-suite/Run-Go.ps1') -Go $Go -Packages $TestPackages
    $native = Join-Path $work 'native.aar'
    NativeRun $Gomobile @('bind','-trimpath',('-target='+$androidTarget),('-androidapi='+$pins.androidApi),
        '-ldflags=-extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384',
        '-javapkg=local.phonepc.bridge','-o',$native,'./mobile')
    [IO.Compression.ZipFile]::ExtractToDirectory($native, $archive)
    # gomobile writes an absolute local module replacement into native build info.
    $nativeLibrary = Join-Path $archive 'jni/arm64-v8a/libgojni.so'
    $modinfo = Join-Path $work 'native-module-info.txt'
    NativeRun $Go @('run','./internal/sdkmodinfo/main.go','-binary',$nativeLibrary,'-source',$root,'-output',$modinfo)
    $receipt = [IO.File]::ReadAllText($modinfo)
    if ($receipt.Contains("'")) { throw 'SDK native module receipt cannot be quoted for the linker.' }
    $nativeFlags = "-extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384 -buildid= -X 'runtime.modinfo="+$receipt+"'"
    NativeRun $Gomobile @('bind','-trimpath',('-target='+$androidTarget),('-androidapi='+$pins.androidApi),
        ('-ldflags='+$nativeFlags),'-javapkg=local.phonepc.bridge','-o',$native,'./mobile')
    [IO.Compression.ZipFile]::ExtractToDirectory($native, $archive, $true)
    NativeRun $Go @('run','./internal/sdkmodinfo/main.go','-binary',$nativeLibrary,'-source',$root,'-output',$modinfo,'-verify')
    # Check the actual library before publishing, including writable RELRO boundaries.
    $readelf = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
    $segments = & $readelf -lW $nativeLibrary
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect native page alignment.' }
    $loads = @($segments | Where-Object { $_ -match '^\s+LOAD\s' })
    if (-not $loads.Count) { throw 'Native library has no load segments.' }
    foreach ($load in $loads) {
        if ($load -notmatch '\s(0x[0-9a-fA-F]+)\s*$' -or [Convert]::ToInt64($Matches[1],16) -lt 16384) {
            throw 'Native library is not 16 KB page aligned.'
        }
    }
    foreach ($segment in @($segments | Where-Object { $_ -match '^\s+GNU_RELRO\s' })) {
        $fields = $segment.Trim() -split '\s+'
        if (([Convert]::ToInt64($fields[2],16) + [Convert]::ToInt64($fields[5],16)) % 16384 -ne 0) {
            throw 'Native RELRO boundary is not 16 KB page aligned.'
        }
    }
    $classes = Join-Path $archive 'classes.jar'
    $javaOutput = Join-Path $work 'java'
    New-Item -ItemType Directory -Path $javaOutput -Force | Out-Null
    $androidSources = @((Get-Content -LiteralPath $captured -Raw | ConvertFrom-Json).inputs |
        Where-Object { $_.path -cmatch '^bridge-core/android/[^/]+\.java$' } |
        ForEach-Object { Join-Path (Split-Path $root) $_.path })
    if (-not $androidSources.Count) { throw 'Captured SDK wrapper source inventory is empty.' }
    NativeRun (Join-Path $Jdk 'bin/javac.exe') (@('-source','17','-target','17',
        '-classpath',($classes + ';' + $androidJar),
        '-d',$javaOutput) + $androidSources)
    NativeRun (Get-Command python -ErrorAction Stop).Source @((Join-Path $root 'sdk_archive.py'),'merge-jar','--jar',$classes,'--classes',$javaOutput)
    $publishedClasses = [IO.Compression.ZipFile]::OpenRead($classes)
    try {
        if (@($publishedClasses.Entries | Where-Object { $_.FullName -match '/(BridgeApps|BridgeWork|BridgeProducts|BridgeUploads|BridgeJobReport|BridgeAppProvider|CodexSession|RecoverySettings)(\$[^/]*)?\.class$' -or $_.FullName -eq 'local/phonepc/bridge/BridgeRecovery$ResumeReceiver.class' }).Count) {
            throw 'Published SDK contains a removed Android manager or direct-Codex client.'
        }
    } finally { $publishedClasses.Dispose() }
    Copy-Item -LiteralPath (Join-Path $root 'android/AndroidManifest.xml') -Destination (Join-Path $archive 'AndroidManifest.xml')
    Add-Content -LiteralPath (Join-Path $archive 'proguard.txt') -Value "`n-keep class local.phonepc.bridge.mobile.** { *; }`n-keep class local.phonepc.bridge.BridgeRecovery { public *; }`n-keep class local.phonepc.bridge.BridgeNetwork { public *; }`n"
    # These notices are required redistribution material, not verification evidence.
    $licenses = [Text.StringBuilder]::new()
    $noticeEnvironment = @{}
    foreach ($name in @('GOOS','GOARCH','CGO_ENABLED')) {
        $noticeEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    try {
        $env:GOOS, $env:GOARCH = $androidTarget -split '/'
        $env:CGO_ENABLED = '1'
        # gomobile's generated bindings also import the seq runtime.
        $modules = & $Go list -deps -tags=android -f '{{if .Module}}{{.Module.Path}}|{{.Module.Dir}}{{end}}' ./mobile golang.org/x/mobile/bind/seq
        if ($LASTEXITCODE -ne 0) { throw 'Cannot determine embedded dependency licenses.' }
    } finally {
        foreach ($name in $noticeEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $noticeEnvironment[$name], 'Process')
        }
    }
    foreach ($module in @($modules | Where-Object { $_ -match '\|' } | Sort-Object -Unique)) {
        $parts = $module -split '\|',2
        if ($parts[0] -eq 'phonepcbridge') { continue }
        $license = Get-ChildItem -LiteralPath $parts[1] -File | Where-Object { $_.Name -match '^(LICENSE|COPYING)(\..*)?$' } | Select-Object -First 1
        if (-not $license) { throw "Missing dependency license for $($parts[0])." }
        [void]$licenses.AppendLine("===== $($parts[0]) =====")
        [void]$licenses.AppendLine([IO.File]::ReadAllText($license.FullName))
        foreach ($notice in @(Get-ChildItem -LiteralPath $parts[1] -File | Where-Object { $_.Name -match '^NOTICE(\..*)?$' })) {
            [void]$licenses.AppendLine([IO.File]::ReadAllText($notice.FullName))
        }
    }
    $goLicense = Join-Path (Split-Path (Split-Path $Go)) 'LICENSE'
    if (-not (Test-Path -LiteralPath $goLicense)) {
        $goRoot = & $Go env GOROOT
        $goLicense = Join-Path $goRoot 'LICENSE'
    }
    [void]$licenses.AppendLine('===== Go runtime =====')
    [void]$licenses.AppendLine([IO.File]::ReadAllText($goLicense))
    New-Item -ItemType Directory -Path (Join-Path $archive 'assets') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $archive 'assets/phone-pc-bridge-licenses.txt'),$licenses.ToString())
    $ready = Join-Path $work 'phone-pc-bridge.aar'
    NativeRun (Get-Command python -ErrorAction Stop).Source @((Join-Path $root 'sdk_archive.py'),'pack-aar','--source',$archive,'--output',$ready)
    $identity=Join-Path $work 'phone-pc-bridge.build.json'
    NativeRun (Get-Command python -ErrorAction Stop).Source @((Join-Path $root 'sdk_artifact.py'),'create','--go',$Go,'--aar',$ready,'--manifest',$identity,'--captured',$captured)
    NativeRun (Get-Command python -ErrorAction Stop).Source @((Join-Path $root 'sdk_artifact.py'),'publish','--go',$Go,'--source-dir',$work,'--artifact-dir',$root)
    if ($PublishTo) {
        NativeRun (Get-Command python -ErrorAction Stop).Source @((Join-Path $root 'sdk_artifact.py'),'publish','--go',$Go,'--artifact-dir',[IO.Path]::GetFullPath($PublishTo))
    }
    Write-Output ('Built and verified embedded Android SDK ('+$pins.abi+', API '+$pins.androidApi+'+) and source/toolchain/artifact identity. App/device acceptance is separate.')
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    RemoveSdkWork
}
