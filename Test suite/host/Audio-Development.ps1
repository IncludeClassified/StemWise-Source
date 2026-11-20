function Invoke-AudioDevelopmentChecks {
    param(
        [Parameter(Mandatory = $true)][object]$Plan,
        [Parameter(Mandatory = $true)][string]$Report,
        [Parameter(Mandatory = $true)][string]$DeviceReport
    )

    Require-Serial
    $Root = [IO.Path]::GetFullPath((Join-Path $Suite '..'))
    $Directory = Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Report))) 'development'
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $Apk = Join-Path $Directory 'candidate.apk'
    $TestApk = Join-Path $Directory 'driver.apk'
    $Mapping = Join-Path $Directory 'mapping.txt'
    $Device = Get-Content -LiteralPath $DeviceReport -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($Device.status -cne 'PASS' -or $Device.audioSourceSha256 -cne $Plan.sourceSha256 -or
            $Device.audioSourceEndSha256 -cne $Plan.sourceSha256) {
        throw 'Development public-session checks require the current successful functional execution.'
    }
    function Copy-RecordedAudioFile([object]$Binding, [string]$Destination) {
        $Source = [IO.Path]::GetFullPath((Join-Path $Root $Binding.path))
        if (-not $Source.StartsWith($Root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or
                (Get-FileSha256 $Source).ToLowerInvariant() -cne $Binding.sha256.ToLowerInvariant()) {
            throw 'Recorded Development input is outside the project or changed bytes.'
        }
        Copy-Item -LiteralPath $Source -Destination $Destination -Force
        if ((Get-FileSha256 $Destination).ToLowerInvariant() -cne $Binding.sha256.ToLowerInvariant()) {
            throw 'Retained Development input differs from the executed frozen artifact.'
        }
    }
    foreach ($Item in @(
            @{ bindings = @($Device.audioArtifacts | Where-Object kind -ceq 'application'); path = $Apk },
            @{ bindings = @($Device.audioArtifacts | Where-Object kind -ceq 'androidTest'); path = $TestApk },
            @{ bindings = @($Device.audioBuildEvidence | Where-Object kind -ceq 'mapping'); path = $Mapping })) {
        if ($Item.bindings.Count -ne 1) { throw 'Functional execution must retain one exact application, driver and R8 mapping.' }
        Copy-RecordedAudioFile $Item.bindings[0] $Item.path
    }
    function Bind-AudioFile([string]$Path) {
        [ordered]@{ path = [IO.Path]::GetRelativePath($Root, $Path).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $Adb = Resolve-Adb
    Assert-DisposableDevelopmentTarget $Adb
    $Abi = Invoke-AdbReadOnlyProbe $Adb @('shell', 'getprop', 'ro.product.cpu.abi') 'minified audio ABI'
    if ($Abi -cne 'arm64-v8a') { throw 'Development audio qualification currently requires the ARM64 candidate ABI.' }
    $Archive = [IO.Compression.ZipFile]::OpenRead($Apk)
    try {
        $Entries = @($Archive.Entries | Where-Object { $_.FullName -ceq 'lib/arm64-v8a/libstemwise_audio_output.so' })
        if ($Entries.Count -ne 1) { throw 'Minified candidate does not contain exactly one audio JNI library.' }
        $Stream = $Entries[0].Open()
        try {
            $Hasher = [Security.Cryptography.SHA256]::Create()
            try { $NativeHash = [Convert]::ToHexString($Hasher.ComputeHash($Stream)).ToLowerInvariant() }
            finally { $Hasher.Dispose() }
        } finally { $Stream.Dispose() }
    } finally { $Archive.Dispose() }
    $RawPath = Join-Path $Directory 'instrumentation.txt'
    $ReadbackPath = Join-Path $Directory 'installed-sha256.txt'
    $DriverReadbackPath = Join-Path $Directory 'installed-driver-sha256.txt'
    $InstalledApp = $false
    $InstalledTest = $false
    try {
        Invoke-Checked $Adb @('-s', $Serial, 'install', '-r', '-t', $Apk) -WorkingDirectory $Root -TimeoutSeconds 120
        $InstalledApp = $true
        Invoke-Checked $Adb @('-s', $Serial, 'install', '-r', '-t', $TestApk) -WorkingDirectory $Root -TimeoutSeconds 120
        $InstalledTest = $true
        $Location = Invoke-AdbReadOnlyProbe $Adb @('shell', 'pm', 'path', 'app.stemwise.development') 'installed minified APK'
        if ($Location -cnotmatch '^package:(/data/app/\S+/base\.apk)$') { throw 'Installed minified candidate has an unexpected package location.' }
        $Readback = Invoke-AdbReadOnlyProbe $Adb @('shell', 'sha256sum', $Matches[1]) 'installed minified APK digest'
        Set-Content -LiteralPath $ReadbackPath -Value $Readback -Encoding utf8NoBOM
        $DriverLocation = Invoke-AdbReadOnlyProbe $Adb @('shell', 'pm', 'path', 'app.stemwise.development.test') 'installed minified test APK'
        if ($DriverLocation -cnotmatch '^package:(/data/app/\S+/base\.apk)$') { throw 'Installed minified driver has an unexpected package location.' }
        $DriverReadback = Invoke-AdbReadOnlyProbe $Adb @('shell', 'sha256sum', $Matches[1]) 'installed minified test APK digest'
        Set-Content -LiteralPath $DriverReadbackPath -Value $DriverReadback -Encoding utf8NoBOM
        $Selector = 'app.stemwise.playback.engine.MinifiedAudioJniInstrumentationTest'
        $ApplicationSha = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
        $DriverSha = (Get-FileHash -LiteralPath $TestApk -Algorithm SHA256).Hash.ToLowerInvariant()
        $Result = Invoke-WithAudioSelectionIsolation -PhysicalOutput -Operation {
            Invoke-QualificationProcess $Adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'stemwiseExpectedApplicationSha256', $ApplicationSha, '-e', 'stemwiseExpectedTestSha256', $DriverSha,
                '-e', 'class', $Selector,
                'app.stemwise.development.test/app.stemwise.qualification.DevelopmentStandardInstrumentationRunner') -WorkingDirectory $Root -TimeoutSeconds 120
        }
        Set-Content -LiteralPath $RawPath -Value ($Result.Stdout + "`n" + $Result.Stderr) -Encoding utf8NoBOM
        if ($Result.ExitCode -ne 0) { throw "Minified instrumentation process failed: $($Result.Stdout) $($Result.Stderr)" }
    } catch { Assert-QualificationOwnership $_; throw } finally {
        Assert-QualificationOwnership
        # These two package identities are the same disposable target used by the normal tester.
        $CleanupFailures = @()
        foreach ($Package in @($(if ($InstalledTest) { 'app.stemwise.development.test' }), $(if ($InstalledApp) { 'app.stemwise.development' }))) {
            if (-not $Package) { continue }
            try { Invoke-Checked $Adb @('-s', $Serial, 'uninstall', $Package) -WorkingDirectory $Root -TimeoutSeconds 60 }
            catch { Assert-QualificationOwnership $_; $CleanupFailures += "${Package}: $($_.Exception.Message)" }
        }
        if ($CleanupFailures.Count) { throw "Minified qualification cleanup failed: $($CleanupFailures -join ' | ')" }
    }
    [ordered]@{
        schemaVersion = 1; sourceSha256 = $Plan.sourceSha256; deviceProfile = $Plan.deviceProfile.identity; abi = $Abi
        apk = Bind-AudioFile $Apk; testApk = Bind-AudioFile $TestApk; mapping = Bind-AudioFile $Mapping
        instrumentation = Bind-AudioFile $RawPath; installedReadback = Bind-AudioFile $ReadbackPath
        installedSha256 = ($Readback -split '\s+')[0]; nativeSha256 = $NativeHash
        installedDriverReadback = Bind-AudioFile $DriverReadbackPath; installedTestSha256 = ($DriverReadback -split '\s+')[0]
        instrumentationSupport = Bind-AudioFile (Join-Path $Suite 'android/development/instrumentation-support.pro')
        buildConfiguration = [ordered]@{ buildType = 'development'; debuggable = $true; minifyEnabled = $true; shrinkResources = $true; identity = 'app.stemwise.development'; signing = 'disposable'; auditHelpers = $true; nativeAuditOptimized = $true; nativeCacheIoProfiling = $true; externalDriverRetention = $true }
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Report -Encoding utf8NoBOM
    Invoke-Checked (Resolve-Python) @('-B', (Join-Path $Suite 'host/audio_development_evidence.py'), 'development', '--report', $Report) -WorkingDirectory $Root -TimeoutSeconds 30
}
