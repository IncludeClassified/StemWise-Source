function Invoke-RecordedDeviceOperation([scriptblock]$Operation, [string]$EvidenceMode = $Mode) {
    $EffectiveRunId = if ([string]::IsNullOrWhiteSpace($RunId)) {
        "TESTER-$Mode-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
    } else { $RunId }
    $SafeRunId = $EffectiveRunId -replace '[^A-Za-z0-9._-]', '_'
    $script:DeviceRunIdentifier = $SafeRunId
    $AttemptId = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + "-$PID"
    if ($SafeRunId -in @('.', '..')) { throw 'RunId cannot be . or ...' }
    $EvidenceRoot = [IO.Path]::GetFullPath((Join-Path $Suite 'evidence')).TrimEnd('\') + '\'
    $script:DeviceRunEvidenceRoot = [IO.Path]::GetFullPath(
        (Join-Path $EvidenceRoot "current-$EvidenceMode")
    )
    if (-not $script:DeviceRunEvidenceRoot.StartsWith(
            $EvidenceRoot,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'RunId escaped the evidence directory.'
    }
    foreach ($GeneratedRoot in @($EvidenceRoot, $script:DeviceRunEvidenceRoot)) {
        if (Test-Path -LiteralPath $GeneratedRoot) {
            if ((Get-Item -LiteralPath $GeneratedRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'Current test results cannot use a linked directory.'
            }
        }
    }
    if (Test-Path -LiteralPath $script:DeviceRunEvidenceRoot) {
        Remove-Item -LiteralPath $script:DeviceRunEvidenceRoot -Recurse -Force
    }
    $script:DeviceSelectionSequence = 0
    $script:DeviceLaneResults = [Collections.Generic.List[object]]::new()
    $script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
    $script:ObservedSkippedMethods = [Collections.Generic.HashSet[string]]::new()
    $script:ValidatedDeviceProfile = $null
    $script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
    $script:HostQualification = $null
    $script:FrozenArtifacts = @{}
    $script:QualificationSourceSha256 = $null
    $script:QualificationSourceManifest = $null
    $script:ProducerSystemSha256 = $null

    New-Item -ItemType Directory -Path $script:DeviceRunEvidenceRoot -Force |
        Out-Null
    $Started = Get-Date
    $Failures = [Collections.Generic.List[string]]::new()
    $Causes = [Collections.Generic.List[Exception]]::new()
    $FailureKind = $null
    $TranscriptStarted = $false
    $Artifact = $null
    $CaptureAudio = $EvidenceMode -cne 'PcSuite' -and ($ProfilePerformance -or
        $EvidenceMode -cin @('AudioQualify', 'AndroidSuite', 'Focused', 'Device', 'Actions'))
    $AudioSourceBefore = $null
    $AudioSourceAfter = $null
    $AudioArtifacts = @()
    $AudioExecutions = @()
    $AudioBuildEvidence = @()
    $AudioBuildConfiguration = $null
    function Read-AudioInputIdentity {
        $Identity = Invoke-QualificationProcess (Resolve-Python) @('-B', (Join-Path $Suite 'host/audio_evidence.py'), 'identity') -WorkingDirectory $LiveRoot -TimeoutSeconds 30
        if ($Identity.ExitCode -ne 0 -or $Identity.Stdout.Trim() -cnotmatch '^[0-9a-f]{64}$') { throw 'Cannot observe audio qualification input identity.' }
        return $Identity.Stdout.Trim()
    }
    try {
        Start-Transcript -Path (
            Join-Path $script:DeviceRunEvidenceRoot 'transcript.txt'
        ) -Force -UseMinimalHeader
        $TranscriptStarted = $true
        if ($CaptureAudio) { $AudioSourceBefore = Read-AudioInputIdentity }
        Initialize-QualificationSourceSnapshot
        & $Operation
    } catch {
        $Failures.Add($_.Exception.Message)
        $Causes.Add($_.Exception)
        $FailureKind = Get-QualificationFailureKind $_
    } finally {
        if ($TranscriptStarted) {
            try {
                Stop-Transcript | Out-Null
            } catch {
                $Failures.Add("transcript cleanup: $($_.Exception.Message)")
                $Causes.Add($_.Exception)
                $FailureKind = Merge-QualificationFailureKind $FailureKind 'cleanup'
            }
        }

        try {
            if ($CaptureAudio) {
                $AudioSourceAfter = Read-AudioInputIdentity
                if ($AudioSourceAfter -cne $AudioSourceBefore) { throw 'Audio source/test/build inputs changed during device execution.' }
            }
            Assert-QualificationSourceUnchanged
            if ($script:FrozenArtifacts.Count -gt 0) {
                Assert-FrozenArtifactsUnchanged
            }
            if ($CaptureAudio) {
                $AudioDirectory = Join-Path $script:DeviceRunEvidenceRoot 'audio-artifacts'
                New-Item -ItemType Directory -Path $AudioDirectory -Force | Out-Null
                foreach ($Kind in @('application', 'androidTest')) {
                    $Frozen = Get-FrozenArtifact 'qualification' $Kind
                    $Target = Join-Path $AudioDirectory ($Kind + '.apk')
                    Copy-Item -LiteralPath $Frozen.path -Destination $Target -Force
                    $Hash = (Get-FileSha256 $Target).ToLowerInvariant()
                    if ($Hash -cne $Frozen.evidence.sha256.ToLowerInvariant()) { throw 'Retained audio APK differs from the executed frozen artifact.' }
                    $AudioArtifacts += [ordered]@{ kind = $Kind; path = [IO.Path]::GetRelativePath($LiveRoot, $Target).Replace('\', '/'); sha256 = $Hash }
                    $AudioBuildConfiguration = $Frozen.evidence.buildConfiguration
                    foreach ($BuildInput in $Frozen.evidence.buildEvidence) {
                        $Retained = Join-Path $AudioDirectory ($BuildInput.kind + '-' + [IO.Path]::GetFileName($BuildInput.path))
                        Copy-Item -LiteralPath $BuildInput.path -Destination $Retained -Force
                        $InputHash = (Get-FileSha256 $Retained).ToLowerInvariant()
                        if ($InputHash -cne $BuildInput.sha256) { throw 'Development build evidence changed after artifact validation.' }
                        $AudioBuildEvidence += [ordered]@{ kind = $BuildInput.kind; path = [IO.Path]::GetRelativePath($LiveRoot, $Retained).Replace('\', '/'); sha256 = $InputHash }
                    }
                }
                foreach ($File in Get-ChildItem -LiteralPath $script:DeviceRunEvidenceRoot -Recurse -File | Where-Object {
                        $_.Name -cin @('selection.json', 'direct.json', 'fatal-retirement.json') -or
                        ($_.Name -ceq 'instrumentation.txt' -and $_.DirectoryName -match '[\\/]direct-[0-9]+$') -or
                        ($_.Name -ceq 'fatal-marker.txt') -or
                        ($_.Name -clike 'TEST-*.xml' -and $_.DirectoryName -match '[\\/]results(?:[\\/]|$)') }) {
                    $AudioExecutions += [ordered]@{ path = [IO.Path]::GetRelativePath($LiveRoot, $File.FullName).Replace('\', '/'); sha256 = (Get-FileSha256 $File.FullName).ToLowerInvariant() }
                }
            }
        } catch {
            $Failures.Add("attempt immutability: $($_.Exception.Message)")
            $Causes.Add($_.Exception)
            $SourceFailureKind = Merge-QualificationFailureKind 'infrastructure' (Get-QualificationFailureKind $_)
            $FailureKind = Merge-QualificationFailureKind $FailureKind $SourceFailureKind
        }

        $QualificationSourceEvidence = $null
        $DeviceInventoryEvidence = $null
        $DeviceExecutionEvidence = $null
        foreach ($Evidence in @(
                [ordered]@{
                    name = 'qualification source manifest'
                    file = 'qualification-source-manifest.json'
                    required = $Failures.Count -eq 0 -and $EvidenceMode -notin @('Focused', 'NativeDevice')
                    target = 'qualification'
                },
                [ordered]@{
                    name = 'device inventory'
                    file = 'inventory.json'
                    required = $Failures.Count -eq 0 -and $EvidenceMode -in @('Device', 'Actions', 'AndroidSuite')
                    target = 'inventory'
                },
                [ordered]@{
                    name = 'device execution accounting'
                    file = 'execution-accounting.json'
                    required = $Failures.Count -eq 0 -and $EvidenceMode -in @('Device', 'Actions', 'AndroidSuite')
                    target = 'execution'
                }
            )) {
            $EvidencePath = Join-Path $script:DeviceRunEvidenceRoot $Evidence.file
            try {
                if (Test-Path -LiteralPath $EvidencePath -PathType Leaf) {
                    $Binding = [ordered]@{
                        path = $Evidence.file
                        sha256 = (Get-FileSha256 $EvidencePath).ToLowerInvariant()
                    }
                    switch ($Evidence.target) {
                        'qualification' { $QualificationSourceEvidence = $Binding }
                        'inventory' { $DeviceInventoryEvidence = $Binding }
                        'execution' { $DeviceExecutionEvidence = $Binding }
                    }
                } elseif ($Evidence.required) {
                    throw "Required $($Evidence.name) is missing."
                }
            } catch {
                $Failures.Add("$($Evidence.name): $($_.Exception.Message)")
                $Causes.Add($_.Exception)
                $FailureKind = Merge-QualificationFailureKind $FailureKind 'infrastructure'
            }
        }

        if ($EvidenceMode -ceq 'AndroidSuite') {
            $Deliverable = $script:FrozenArtifacts['deliverable|application']
            if ($null -ne $Deliverable) {
                $Artifact = [ordered]@{
                    fileName = $Deliverable.evidence.fileName
                    bytes = $Deliverable.evidence.bytes
                    sha256 = $Deliverable.evidence.sha256
                }
            } elseif ($Failures.Count -eq 0) {
                $Failures.Add('Required current Development application is missing.')
                $FailureKind = Merge-QualificationFailureKind $FailureKind 'infrastructure'
            }
        }

        $Result = [ordered]@{
            schemaVersion = 2
            mode = if ($EvidenceMode -ceq 'PcSuite') { 'PcSuite' } else { $Mode }
            serial = $Serial
            runId = $EffectiveRunId
            attemptId = $AttemptId
            started = $Started.ToString('o')
            finished = (Get-Date).ToString('o')
            status = if ($Failures.Count -eq 0) { 'CLEANING' } else { 'FAIL' }
            failure = if ($Failures.Count -eq 0) { $null } else { $Failures -join ' | ' }
            failureKind = if ($Failures.Count -eq 0) { $null } elseif ($FailureKind) { $FailureKind } else { 'test-failure' }
            lanes = @($script:DeviceLaneResults)
            deviceProfile = $script:ValidatedDeviceProfile
            artifact = $Artifact
            artifacts = @($script:DeviceArtifacts)
            hostQualification = $script:HostQualification
            qualificationSourceSha256 = $script:QualificationSourceSha256
            qualificationSourceManifest = $QualificationSourceEvidence
            deviceInventory = $DeviceInventoryEvidence
            deviceExecutionAccounting = $DeviceExecutionEvidence
            producerSystemSha256 = $script:ProducerSystemSha256
            audioSourceSha256 = $AudioSourceBefore
            audioSourceEndSha256 = $AudioSourceAfter
            audioArtifacts = $AudioArtifacts
            audioExecutions = $AudioExecutions
            audioBuildConfiguration = $AudioBuildConfiguration
            audioBuildEvidence = $AudioBuildEvidence
        }
        try {
            $Result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (
                Join-Path $script:DeviceRunEvidenceRoot 'run.json'
            ) -Encoding UTF8
        } catch {
            $Failures.Add("result archive: $($_.Exception.Message)")
            $Causes.Add($_.Exception)
            $FailureKind = Merge-QualificationFailureKind $FailureKind 'infrastructure'
        }
    }

    if ($Failures.Count -gt 0) {
        throw (New-QualificationException "Device operation failures: $($Failures -join ' | ')" (
            [AggregateException]::new('Qualification causes', $Causes.ToArray())
        ) $FailureKind)
    }
    $CleanupWarnings = @()
    $TranscriptPath = Join-Path $script:DeviceRunEvidenceRoot 'transcript.txt'
    try {
        Remove-Item -LiteralPath $TranscriptPath -Force
    } catch {
        $CleanupWarnings += "${TranscriptPath}: $($_.Exception.Message)"
        Write-Warning "Disposable transcript remains at ${TranscriptPath}: $($_.Exception.Message)"
    }
    $FinalizationKind = 'infrastructure'
    try {
        $ReportPath = Join-Path $script:DeviceRunEvidenceRoot 'run.json'
        $Result = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
        $Result | Add-Member -NotePropertyName cleanupWarnings -NotePropertyValue $CleanupWarnings -Force
        $Result.status = 'PASS'
        $Result.finished = (Get-Date).ToString('o')
        $Result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    } catch {
        $Failure = New-QualificationException $_.Exception.Message $_ $FinalizationKind
        $Result.status = 'FAIL'
        $Result.failureKind = $FinalizationKind
        $Result.failure = $_.Exception.Message
        $Result.finished = (Get-Date).ToString('o')
        try {
            $Result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (
                Join-Path $script:DeviceRunEvidenceRoot 'run.json'
            ) -Encoding UTF8
        } catch {
            throw (New-QualificationException "$($Failure.Message) | failed-result archive: $($_.Exception.Message)" (
                [AggregateException]::new('Qualification finalization causes', [Exception[]]@($Failure, $_.Exception))
            ) (Merge-QualificationFailureKind $FinalizationKind 'infrastructure'))
        }
        throw $Failure
    }
}
