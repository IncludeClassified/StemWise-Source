function Get-FileSha256([Parameter(Mandatory = $true)][string]$Path) {
    $Stream = [IO.File]::OpenRead($Path)
    try {
        $Hasher = [Security.Cryptography.SHA256]::Create()
        try {
            return [BitConverter]::ToString($Hasher.ComputeHash($Stream)).Replace('-', '')
        } finally {
            $Hasher.Dispose()
        }
    } finally {
        $Stream.Dispose()
    }
}

function Get-QualificationSourceFiles {
    $Response = Invoke-QualificationProcess (Resolve-Python) @('-B', '-m', 'stemwise_engine.source_inputs', '--root', $LiveRoot) -TimeoutSeconds 30
    if ($Response.ExitCode -ne 0) { throw "Source input enumeration failed: $($Response.Stderr)" }
    $Paths = @(ConvertFrom-QualificationJson $Response 'Source input manifest')
    if ($Paths.Count -eq 0) { throw 'Current source input manifest is empty.' }
    $Files = @($Paths | ForEach-Object { Get-Item -LiteralPath $_ -ErrorAction Stop })
    # Device identity is bound separately. A software-only PC phase has the same
    # source closure whether or not an Android profile was supplied.
    return @($Files | Sort-Object FullName -Unique)
}

function Get-QualificationSourceManifest {
    $LiveRootUri = [Uri]($LiveRoot.TrimEnd('\') + '\')
    return @(
        foreach ($File in Get-QualificationSourceFiles) {
            $File.Refresh()
            $Length = $File.Length
            $Modified = $File.LastWriteTimeUtc
            $Digest = Get-FileSha256 $File.FullName
            $File.Refresh()
            if (-not $File.Exists -or $File.Length -ne $Length -or $File.LastWriteTimeUtc -ne $Modified) {
                throw "Qualification input changed while hashing: $($File.FullName)"
            }
            [ordered]@{
                path = [Uri]::UnescapeDataString(
                    $LiveRootUri.MakeRelativeUri([Uri]$File.FullName).ToString()
                )
                bytes = $Length
                sha256 = $Digest
            }
        }
    )
}

function Get-QualificationSourceSha256([object[]]$Manifest = @()) {
    $Hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $Entries = if ($Manifest.Count -gt 0) {
            $Manifest
        } else {
            @(Get-QualificationSourceManifest)
        }
        foreach ($Entry in $Entries) {
            $Header = [Text.Encoding]::UTF8.GetBytes(
                "$($Entry.path)`0$($Entry.bytes)`0$($Entry.sha256)`0"
            )
            $Hasher.TransformBlock($Header, 0, $Header.Length, $null, 0) | Out-Null
        }
        $Hasher.TransformFinalBlock([byte[]]::new(0), 0, 0) | Out-Null
        return [BitConverter]::ToString($Hasher.Hash).Replace('-', '')
    } finally {
        $Hasher.Dispose()
    }
}

function Initialize-QualificationSourceSnapshot {
    if ($Mode -in @('Focused', 'NativeDevice')) { return }
    if ($null -eq $script:QualificationSourceSha256) {
        $script:QualificationSourceManifest = @(Get-QualificationSourceManifest)
        $script:QualificationSourceSha256 = Get-QualificationSourceSha256 (
            $script:QualificationSourceManifest
        )
        if ($null -ne $script:DeviceRunEvidenceRoot) {
            $script:QualificationSourceManifest | ConvertTo-Json -Depth 3 |
                Set-Content -LiteralPath (
                    Join-Path $script:DeviceRunEvidenceRoot 'qualification-source-manifest.json'
                ) -Encoding UTF8
        }
        Write-Host "QUALIFICATION_SOURCE_SHA256=$($script:QualificationSourceSha256)"
    }
    if ($null -eq $script:ProducerSystemSha256) {
        $Result = Invoke-QualificationProcess (Resolve-Python) @('-B',
            (Join-Path $PcBuilder 'src/lyrics_qualification.py'),
            '--producer-system-identity') -TimeoutSeconds 600
        if ($Result.Stderr) { Write-Host $Result.Stderr }
        $Identity = ConvertFrom-QualificationJson $Result 'Producer system identity'
        $script:ProducerSystemSha256 = [string]$Identity.producerSystemSha256
        if ($script:ProducerSystemSha256 -cnotmatch '^[0-9a-f]{64}$') {
            throw 'Producer system identity is invalid.'
        }
        Write-Host "PRODUCER_SYSTEM_SHA256=$($script:ProducerSystemSha256)"
    }
}

function Assert-QualificationSourceUnchanged {
    if ($Mode -in @('Focused', 'NativeDevice')) { return }
    if ($null -eq $script:QualificationSourceSha256) {
        throw 'Qualification source snapshot was not initialized.'
    }
    $Current = @(Get-QualificationSourceManifest)
    $Actual = Get-QualificationSourceSha256 $Current
    if ($Actual -cne $script:QualificationSourceSha256) {
        $BeforeByPath = @{}
        $CurrentByPath = @{}
        $script:QualificationSourceManifest | ForEach-Object { $BeforeByPath[$_.path] = $_ }
        $Current | ForEach-Object { $CurrentByPath[$_.path] = $_ }
        $Changes = [Collections.Generic.List[object]]::new()
        foreach ($Path in @($BeforeByPath.Keys + $CurrentByPath.Keys | Sort-Object -Unique)) {
            $Before = $BeforeByPath[$Path]
            $After = $CurrentByPath[$Path]
            if ($null -eq $Before) {
                $Changes.Add([ordered]@{ kind = 'added'; path = $Path; before = $null; after = $After })
            } elseif ($null -eq $After) {
                $Changes.Add([ordered]@{ kind = 'removed'; path = $Path; before = $Before; after = $null })
            } elseif ($Before.bytes -ne $After.bytes -or $Before.sha256 -cne $After.sha256) {
                $Changes.Add([ordered]@{ kind = 'changed'; path = $Path; before = $Before; after = $After })
            }
        }
        if ($null -ne $script:DeviceRunEvidenceRoot) {
            [ordered]@{
                expectedSha256 = $script:QualificationSourceSha256
                actualSha256 = $Actual
                changes = @($Changes)
            } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (
                Join-Path $script:DeviceRunEvidenceRoot 'qualification-source-mismatch.json'
            ) -Encoding UTF8
        }
        $ChangedPaths = @($Changes | Select-Object -First 12 | ForEach-Object {
            "$($_.kind):$($_.path)"
        }) -join ', '
        throw "Qualification inputs changed during the attempt: expected=$($script:QualificationSourceSha256) actual=$Actual changes=[$ChangedPaths] total=$($Changes.Count)"
    }
}

function Resolve-AndroidBuildTools {
    $SdkCandidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android\Sdk')
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($Sdk in $SdkCandidates) {
        $BuildToolsRoot = Join-Path $Sdk 'build-tools'
        if (-not (Test-Path -LiteralPath $BuildToolsRoot -PathType Container)) { continue }
        foreach ($Directory in @(Get-ChildItem -LiteralPath $BuildToolsRoot -Directory |
                Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending)) {
            $Aapt2 = Join-Path $Directory.FullName 'aapt2.exe'
            $ApkSigner = Join-Path $Directory.FullName 'apksigner.bat'
            if ((Test-Path -LiteralPath $Aapt2 -PathType Leaf) -and
                    (Test-Path -LiteralPath $ApkSigner -PathType Leaf)) {
                return [pscustomobject]@{
                    version = $Directory.Name
                    aapt2 = $Aapt2
                    apksigner = $ApkSigner
                }
            }
        }
    }
    throw 'Android SDK build tools with aapt2 and apksigner were not found.'
}

function Resolve-ApkAnalyzer {
    $SdkCandidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android\Sdk')
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($Sdk in $SdkCandidates) {
        $CommandLineToolsRoot = Join-Path $Sdk 'cmdline-tools'
        if (-not (Test-Path -LiteralPath $CommandLineToolsRoot -PathType Container)) { continue }
        $Directories = @(Get-ChildItem -LiteralPath $CommandLineToolsRoot -Directory)
        $Latest = @($Directories | Where-Object { $_.Name -eq 'latest' })
        $Versioned = @($Directories | Where-Object { $_.Name -ne 'latest' } |
            Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending)
        foreach ($Directory in @($Latest + $Versioned)) {
            $Analyzer = Join-Path $Directory.FullName 'bin\apkanalyzer.bat'
            if (Test-Path -LiteralPath $Analyzer -PathType Leaf) {
                return $Analyzer
            }
        }
    }
    throw 'Android SDK command-line tools with apkanalyzer were not found.'
}

function Assert-NoAppOwnedUnsafeYtDlpUpdaterReference(
    [Parameter(Mandatory = $true)][string]$Path
) {
    $Analyzer = Resolve-ApkAnalyzer
    $Descriptor = 'com.yausername.youtubedl_android.YoutubeDL com.yausername.youtubedl_android.YoutubeDL$UpdateStatus updateYoutubeDL(android.content.Context,com.yausername.youtubedl_android.YoutubeDL$UpdateChannel)'
    $DefaultDescriptor = 'com.yausername.youtubedl_android.YoutubeDL com.yausername.youtubedl_android.YoutubeDL$UpdateStatus updateYoutubeDL$default(com.yausername.youtubedl_android.YoutubeDL,android.content.Context,com.yausername.youtubedl_android.YoutubeDL$UpdateChannel,int,java.lang.Object)'
    $Apk = [IO.Path]::GetFullPath($Path)
    $Output = @(Invoke-QualificationOutput $Analyzer dex reference-tree --references-to $Descriptor $Apk)
    if ($LASTEXITCODE -ne 0) {
        throw "apkanalyzer could not inspect unsafe yt-dlp updater references in $([IO.Path]::GetFileName($Apk))."
    }
    $TargetRoots = @($Output | Where-Object { $_ -ceq $Descriptor })
    if ($TargetRoots.Count -ne 1) {
        throw "apkanalyzer did not report the exact unsafe yt-dlp updater target once; found $($TargetRoots.Count)."
    }
    $DefaultReferences = @($Output | Where-Object {
            $_.Trim() -ceq $DefaultDescriptor
        })
    if ($DefaultReferences.Count -ne 1) {
        throw "apkanalyzer did not report the pinned upstream default-method reference once; found $($DefaultReferences.Count)."
    }
    $AllowedBaseline = @($Descriptor, $DefaultDescriptor)
    $UnexpectedReferences = @($Output | ForEach-Object { $_.Trim() } |
        Where-Object { $_.Length -ne 0 -and $_ -cnotin $AllowedBaseline })
    if ($UnexpectedReferences.Count -ne 0) {
        $Sample = @($UnexpectedReferences | Select-Object -First 10) -join '; '
        throw "DEX code outside the pinned upstream baseline references YoutubeDL.updateYoutubeDL(...): $Sample"
    }
}

function Resolve-GradleApk([Parameter(Mandatory = $true)][string]$OutputDirectory) {
    $MetadataPath = Join-Path $OutputDirectory 'output-metadata.json'
    if (-not (Test-Path -LiteralPath $MetadataPath -PathType Leaf)) {
        throw "APK output metadata is missing: $MetadataPath"
    }
    $Metadata = Get-Content -LiteralPath $MetadataPath -Raw | ConvertFrom-Json
    $OutputFiles = @(
        $Metadata.elements |
            ForEach-Object { [string]$_.outputFile } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if ($OutputFiles.Count -ne 1) {
        throw "Expected one APK in $MetadataPath; found $($OutputFiles.Count)."
    }
    $OutputRoot = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\') + '\'
    $Apk = [IO.Path]::GetFullPath((Join-Path $OutputRoot $OutputFiles[0]))
    if (-not $Apk.StartsWith($OutputRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "APK output metadata escaped its output directory: $MetadataPath"
    }
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
        throw "APK declared by output metadata is missing: $Apk"
    }
    return $Apk
}

function Get-ApkEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Kind
    )
    $File = Get-Item -LiteralPath $Path -ErrorAction Stop
    $Tools = Resolve-AndroidBuildTools
    $BadgingOutput = @(Invoke-QualificationOutput $Tools.aapt2 dump badging $File.FullName |
        ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) { throw "aapt2 could not inspect $($File.Name)." }
    $Package = [regex]::Match(
        ($BadgingOutput -join "`n"),
        "(?m)^package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'"
    )
    if (-not $Package.Success) { throw "aapt2 did not report identity for $($File.Name)." }

    $SignerOutput = @(Invoke-QualificationOutput $Tools.apksigner verify --print-certs $File.FullName |
        ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) { throw "apksigner could not verify $($File.Name)." }
    $SignerMatches = @([regex]::Matches(
        ($SignerOutput -join "`n"),
        '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$'
    ))
    if ($SignerMatches.Count -ne 1) {
        throw "Expected exactly one APK signer for $($File.Name); found $($SignerMatches.Count)."
    }
    if ($Kind -eq 'application' -and
            $Role -notin @('synthetic-provider')) {
        Assert-NoAppOwnedUnsafeYtDlpUpdaterReference $File.FullName
    }
    $BuildConfiguration = $null
    $BuildEvidence = @()
    if ($Role -ceq 'qualification') {
        $MetadataPath = Join-Path $File.DirectoryName 'output-metadata.json'
        $Metadata = Get-Content -LiteralPath $MetadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $ExpectedVariant = if ($Kind -ceq 'application') { 'development' } else { 'developmentAndroidTest' }
        $ExpectedPackage = if ($Kind -ceq 'application') { $DevelopmentPackage } else { "$DevelopmentPackage.test" }
        if ($Metadata.variantName -cne $ExpectedVariant -or $Metadata.applicationId -cne $ExpectedPackage) {
            throw 'Qualification requires the actual Development output metadata.'
        }
        $BuildEvidence += [ordered]@{ kind = $Kind; path = $MetadataPath; sha256 = (Get-FileSha256 $MetadataPath).ToLowerInvariant() }
        if ($Kind -ceq 'application') {
            $Mapping = Join-Path $Repo 'app/build/outputs/mapping/development/mapping.txt'
            if ((Get-Content -LiteralPath $Mapping -TotalCount 1 -Encoding UTF8) -cnotlike '# compiler: R8*') {
                throw 'Development qualification requires the current R8 mapping.'
            }
            $BuildEvidence += [ordered]@{ kind = 'mapping'; path = $Mapping; sha256 = (Get-FileSha256 $Mapping).ToLowerInvariant() }
        }
        $BuildConfiguration = [ordered]@{ buildType = 'development'; debuggable = $true; minifyEnabled = $true; shrinkResources = $true; identity = $DevelopmentPackage; signing = 'disposable' }
    }
    return [ordered]@{
        role = $Role
        kind = $Kind
        fileName = $File.Name
        bytes = $File.Length
        sha256 = Get-FileSha256 -Path $File.FullName
        applicationId = $Package.Groups[1].Value
        versionCode = $Package.Groups[2].Value
        versionName = $Package.Groups[3].Value
        certificateSha256 = $SignerMatches[0].Groups[1].Value.ToUpperInvariant()
        buildConfiguration = $BuildConfiguration
        buildEvidence = $BuildEvidence
    }
}

function Register-ApkEvidence([Parameter(Mandatory = $true)][object]$Evidence) {
    if ($null -eq $script:DeviceArtifacts) {
        $script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
    }
    $ExistingRole = @($script:DeviceArtifacts | Where-Object {
            $_.role -eq $Evidence.role -and
            $_.kind -eq $Evidence.kind
        })
    if ($ExistingRole.Count -gt 1) {
        throw "APK role $($Evidence.role)/$($Evidence.kind) was registered more than once."
    }
    if ($ExistingRole.Count -eq 1) {
        if ($ExistingRole[0].sha256 -cne $Evidence.sha256) {
            throw "APK role $($Evidence.role)/$($Evidence.kind) changed hash: expected=$($ExistingRole[0].sha256) actual=$($Evidence.sha256)"
        }
        return
    }
    $script:DeviceArtifacts.Add($Evidence)
}

function Resolve-QualificationSigningConfiguration {
    if ($script:QualificationSigningLoaded) { return }
    $Metadata = Join-Path $Repo 'app/build/generated/qualification/signing-configuration.json'
    if (Test-Path -LiteralPath $Metadata) { Remove-Item -LiteralPath $Metadata -Force }
    Invoke-Gradle @(':app:writeQualificationSigningConfiguration')
    $Configuration = Get-Content -LiteralPath $Metadata -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($Configuration.formatVersion -ne 1 -or
            $Configuration.releaseCertificateSha256 -cnotmatch '^[0-9A-F]{64}$' -or
            ($Configuration.disposableCertificateSha256 -ne '' -and
             $Configuration.disposableCertificateSha256 -cnotmatch '^[0-9A-F]{64}$')) {
        throw 'Gradle emitted an invalid qualification signing configuration.'
    }
    $script:ReleaseCertificateSha256 = $Configuration.releaseCertificateSha256
    $script:QualificationCertificateSha256 = $Configuration.disposableCertificateSha256
    $script:QualificationSigningLoaded = $true
}

function Assert-ApkEvidenceRole([Parameter(Mandatory = $true)][object]$Evidence) {
    Resolve-QualificationSigningConfiguration
    $TargetApplicationId = switch ($Evidence.role) {
        'qualification' { $DevelopmentPackage }
        'deliverable' { $DevelopmentPackage }
        'synthetic-provider' { $QaProviderPackage }
        default { throw "Unknown APK evidence role: $($Evidence.role)" }
    }
    $ExpectedApplicationId = if ($Evidence.kind -eq 'androidTest') {
        "$TargetApplicationId.test"
    } else {
        $TargetApplicationId
    }
    $ExpectedCertificate = if ($Evidence.role -eq 'synthetic-provider') {
        $null
    } elseif ($Evidence.role -in @(
            'qualification',
            'deliverable'
        )) {
        $QualificationCertificateSha256
    } else {
        $ReleaseCertificateSha256
    }
    if ($Evidence.applicationId -ne $ExpectedApplicationId) {
        throw "APK role $($Evidence.role) has package $($Evidence.applicationId), expected $ExpectedApplicationId."
    }
    if ($Evidence.role -ceq 'qualification' -and
            ($Evidence.buildConfiguration.buildType -cne 'development' -or
             $Evidence.buildConfiguration.debuggable -ne $true -or
             $Evidence.buildConfiguration.minifyEnabled -ne $true -or
             $Evidence.buildConfiguration.shrinkResources -ne $true -or
             $Evidence.buildConfiguration.identity -cne $DevelopmentPackage -or
             $Evidence.buildConfiguration.signing -cne 'disposable')) {
        throw 'Qualification requires the minified, debuggable Development configuration.'
    }
    if ($null -ne $ExpectedCertificate -and
            $Evidence.certificateSha256 -ne $ExpectedCertificate) {
        throw "APK role $($Evidence.role) signing certificate differs from the resolved Gradle signing configuration."
    }
    if ($Evidence.role -eq 'synthetic-provider' -and
            $Evidence.certificateSha256 -in @(
                $ReleaseCertificateSha256,
                $QualificationCertificateSha256
            )) {
        throw 'Synthetic provider must be signed independently from both target roles.'
    }
}

function Freeze-ApkArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Kind
    )
    if ([string]::IsNullOrWhiteSpace($script:DeviceRunEvidenceRoot)) {
        throw 'Immutable artifacts require an attempt evidence directory.'
    }
    $Key = "$Role|$Kind"
    $SourceEvidence = Get-ApkEvidence $Path $Role $Kind
    Assert-ApkEvidenceRole $SourceEvidence
    if ($script:FrozenArtifacts.ContainsKey($Key)) {
        $Existing = $script:FrozenArtifacts[$Key]
        if ($Existing.evidence.sha256 -cne $SourceEvidence.sha256) {
            throw "Immutable artifact $Key was rebuilt with a different hash."
        }
        return $Existing
    }
    $Frozen = [pscustomobject]@{ path = $Path; evidence = $SourceEvidence }
    if ($Role -ceq 'deliverable' -and $Kind -ceq 'application') {
        $SourceEvidence['evidencePath'] = 'audio-artifacts/application.apk'
    }
    Register-ApkEvidence $Frozen.evidence
    $script:FrozenArtifacts[$Key] = $Frozen
    return $Frozen
}

function Get-FrozenArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Kind
    )
    $Key = "$Role|$Kind"
    if (-not $script:FrozenArtifacts.ContainsKey($Key)) {
        throw "Immutable artifact is missing: $Key"
    }
    return $script:FrozenArtifacts[$Key]
}

function Assert-FrozenArtifactsUnchanged {
    foreach ($Key in @($script:FrozenArtifacts.Keys | Sort-Object)) {
        $Frozen = $script:FrozenArtifacts[$Key]
        $Actual = Get-FileSha256 $Frozen.path
        if ($Actual -cne $Frozen.evidence.sha256) {
            throw "Immutable artifact changed during the attempt: $Key"
        }
    }
}

function Get-DevelopmentApkPairEvidence {
    $Pair = @(
        Get-ApkEvidence (Resolve-GradleApk $DevelopmentApkOutput) 'qualification' 'application'
        Get-ApkEvidence (Resolve-GradleApk $DevelopmentTestApkOutput) 'qualification' 'androidTest'
    )
    $Pair | ForEach-Object { Assert-ApkEvidenceRole $_ }
    return $Pair
}

function Initialize-ImmutableQualificationArtifacts([switch]$RetainDeliverable) {
    Initialize-QualificationSourceSnapshot
    Initialize-FocusedArtifacts
    if ($RetainDeliverable) {
        $Application = Get-FrozenArtifact 'qualification' 'application'
        Freeze-ApkArtifact $Application.path 'deliverable' 'application' | Out-Null
    }
    Assert-QualificationSourceUnchanged
    Assert-FrozenArtifactsUnchanged
}

function Publish-DevelopmentApk {
    $AudioCurrent = Join-Path $Suite 'work/audio-current'
    $Apk = Join-Path $AudioCurrent 'development/candidate.apk'
    $ExpectedHash = Get-FileSha256 $Apk
    Invoke-Checked (Resolve-Python) @('-B', (Join-Path $Suite 'host/audio_evidence.py'), 'promote',
        '--plan', (Join-Path $AudioCurrent 'plan.json'), '--receipt', (Join-Path $AudioCurrent 'candidate.json'),
        '--apk', $Apk) $LiveRoot -Phase 'audio-promotion-gate'
    $Evidence = Get-ApkEvidence $Apk 'deliverable' 'application'
    Assert-ApkEvidenceRole $Evidence
    if ($Evidence.sha256 -cne $ExpectedHash) { throw 'Development candidate changed during promotion validation.' }
    $Deliverable = [pscustomobject]@{ path = $Apk; evidence = $Evidence }
    $Destination = Join-Path $LiveRoot 'StemWise-development.apk'
    $Temporary = "$Destination.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::Copy($Deliverable.path, $Temporary)
        if ((Get-FileSha256 $Temporary) -cne $ExpectedHash) {
            throw 'Development APK copy does not match the exact qualified candidate.'
        }
        if ([IO.File]::Exists($Destination)) {
            [IO.File]::Replace($Temporary, $Destination, [NullString]::Value)
        } else {
            [IO.File]::Move($Temporary, $Destination)
        }
    } finally {
        if ([IO.File]::Exists($Temporary)) { [IO.File]::Delete($Temporary) }
    }
}
