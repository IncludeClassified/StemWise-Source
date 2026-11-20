function Install-QualificationProvider {
    $Adb = Resolve-Adb
    Assert-DisposableDevelopmentTarget $Adb
    $Provider = Get-FrozenArtifact 'synthetic-provider' 'application'
    $Package = $Provider.evidence.applicationId
    if ($Package -cne 'app.stemwise.testprovider') { throw 'Unrecognized qualification provider package.' }
    $Users = Invoke-AdbReadOnlyProbe $Adb @('shell', 'pm', 'list', 'users') 'Android users'
    $UserIds = @([regex]::Matches($Users, 'UserInfo\{([0-9]+):') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    if ($UserIds.Count -eq 0 -or '0' -cnotin $UserIds) { throw 'Could not establish the Android user inventory.' }
    foreach ($UserId in $UserIds) {
        $Packages = @(Invoke-QualificationOutput $Adb -s $Serial shell pm list packages --user $UserId $Package |
            ForEach-Object { $_.ToString() })
        Assert-AdbPackageProbeSucceeded $LASTEXITCODE $Package $Packages
        if (-not (Test-AdbPackageListed $Package $Packages)) { continue }
        if ($UserId -cne '0') { throw 'QA provider exists in another Android user; package replacement is refused.' }
        if ($script:ApprovedDevicePurpose -cne 'dedicated-qualification') {
            throw 'Existing QA provider data is preserved. Use a declared dedicated-qualification target.'
        }
        $Installed = Get-InstalledDevelopmentApkIdentity $Adb $Package
        if ($Installed.certificateSha256 -cne $Provider.evidence.certificateSha256) {
            throw 'Existing QA provider signing identity differs; its data is preserved.'
        }
    }
    # Replacement preserves fixture data; signer mismatch never authorizes uninstall.
    Invoke-AdbChecked $Adb @('install', '-r', '-t', $Provider.path)
    $Installed = Get-InstalledDevelopmentApkIdentity $Adb $Package
    if ($Installed.sha256 -cne $Provider.evidence.sha256 -or
            $Installed.certificateSha256 -cne $Provider.evidence.certificateSha256) {
        throw 'Installed QA provider differs from the frozen current artifact.'
    }
}

function Install-QualificationInstrumentation {
    Assert-DisposableDevelopmentTarget (Resolve-Adb)
    $PreviousSerial = $env:ANDROID_SERIAL
    try {
        $env:ANDROID_SERIAL = $Serial
        $Abi = Invoke-AdbReadOnlyProbe (Resolve-Adb) @('shell', 'getprop', 'ro.product.cpu.abi') 'CPU ABI'
        if ($Abi -cnotin @('arm64-v8a','x86_64')) { throw "Unsupported Development ABI: $Abi" }
        $BuildArguments = @(
            "-Pstemwise.developmentTargetAbi=$Abi",
            ':app:installDevelopment',
            ':app:installDevelopmentAndroidTest'
        )
        Invoke-Gradle $BuildArguments
        Get-DevelopmentApkPairEvidence | ForEach-Object { Register-ApkEvidence $_ }
    } finally {
        $env:ANDROID_SERIAL = $PreviousSerial
    }
}

function Test-DirectInstrumentationPass {
    param(
        [int]$ExitCode,
        [string]$Text
    )
    return $ExitCode -eq 0 -and
        $Text -notmatch '(?m)^FAILURES!!!|INSTRUMENTATION_FAILED' -and
        $Text -notmatch '(?m)^INSTRUMENTATION_STATUS_CODE:\s*-(3|4)\s*$' -and
        $Text -match '(?m)^OK \(1 test\)\s*$'
}

function Invoke-DirectInstrumentation {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Selector,
        [Parameter(Mandatory = $true)][string]$Component,
        [hashtable]$Arguments = @{},
        [switch]$AllowNonPass
    )
    $Arguments = $Arguments.Clone()
    $ExpectedApp = Get-FrozenArtifact 'qualification' 'application'
    $ExpectedTest = Get-FrozenArtifact 'qualification' 'androidTest'
    $Arguments.stemwiseExpectedApplicationSha256 = $ExpectedApp.evidence.sha256
    $Arguments.stemwiseExpectedTestSha256 = $ExpectedTest.evidence.sha256
    $Command = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $Selector)
    foreach ($Name in @($Arguments.Keys | Sort-Object)) {
        $Command += @('-e', $Name, [string]$Arguments[$Name])
    }
    $Command += $Component
    $Invocation = Invoke-WithAudioSelectionIsolation @($Selector) {
        Invoke-QualificationProcess $Adb $Command -TimeoutSeconds 1200
    }
    $Output = @($Invocation.Stdout.TrimEnd("`r", "`n") -split "`r?`n")
    $ExitCode = $Invocation.ExitCode
    $Output | ForEach-Object { Write-Host $_ }
    $Text = $Output -join "`n"
    if ($script:DeviceRunEvidenceRoot) {
        $script:DeviceSelectionSequence++
        $Directory = Join-Path $script:DeviceRunEvidenceRoot ('direct-' + $script:DeviceSelectionSequence.ToString('D3'))
        New-Item -ItemType Directory -Path $Directory -Force | Out-Null
        $Raw = Join-Path $Directory 'instrumentation.txt'
        [IO.File]::WriteAllText($Raw, $Text, [Text.UTF8Encoding]::new($false))
        [ordered]@{ schemaVersion = 1; selector = $Selector; component = $Component; arguments = $Arguments
            exitCode = $ExitCode; stderr = $Invocation.Stderr
            output = [ordered]@{ path = [IO.Path]::GetRelativePath($LiveRoot, $Raw).Replace('\', '/'); sha256 = (Get-FileSha256 $Raw).ToLowerInvariant() }
            artifacts = @($ExpectedApp.evidence, $ExpectedTest.evidence)
        } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Directory 'direct.json') -Encoding utf8NoBOM
    }
    if (-not $AllowNonPass.IsPresent) {
        if (-not (Test-DirectInstrumentationPass $ExitCode ($Text + "`n" + $Invocation.Stderr))) {
            throw "Instrumentation selector failed: $Selector"
        }
    }
    return $Text
}

function Assert-AdbPackageProbeSucceeded {
    param(
        [int]$ExitCode,
        [string]$Package,
        [string[]]$Output
    )
    if ($ExitCode -ne 0) {
        throw "Could not inspect disposable package ${Package}: $($Output -join ' ')"
    }
}

function Test-AdbPackageListed {
    param(
        [string]$Package,
        [string[]]$Output
    )
    return @($Output | Where-Object { $_.Trim() -eq "package:$Package" }).Count -gt 0
}

function Remove-DevicePackageVerified {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Package
    )
    $ProbeOutput = @(
        Invoke-QualificationOutput $Adb -s $Serial shell pm list packages --user 0 $Package 2>&1 |
            ForEach-Object { $_.ToString() }
    )
    Assert-AdbPackageProbeSucceeded $LASTEXITCODE $Package $ProbeOutput
    if (Test-AdbPackageListed $Package $ProbeOutput) {
        Invoke-AdbChecked $Adb @('shell', 'am', 'force-stop', '--user', '0', $Package)
        Invoke-AdbChecked $Adb @('uninstall', $Package)
    }
    $After = @(
        Invoke-QualificationOutput $Adb -s $Serial shell pm list packages --user 0 $Package 2>&1 |
            ForEach-Object { $_.ToString() }
    )
    Assert-AdbPackageProbeSucceeded $LASTEXITCODE $Package $After
    if (Test-AdbPackageListed $Package $After) {
        throw "Disposable package survived verified uninstall: $Package"
    }
}

function Reset-DisposableDevicePackages {
    Assert-QualificationOwnership
    $Adb = Resolve-Adb
    Assert-DisposableDevelopmentTarget $Adb
    foreach ($Package in @("$DevelopmentPackage.test", $DevelopmentPackage)) {
        Remove-DevicePackageVerified $Adb $Package
    }
}

function Invoke-OrdinaryDeviceLane {
    Write-Host 'DEVICE_LANE_START ordinary'
    Reset-DisposableDevicePackages
    foreach ($Selector in Get-DeviceLaneSelectors 'ordinary') {
        try {
            # One class per instrumentation process. A dead Compose host cannot poison later
            # classes. Only an exact UTP connection timeout with either zero executed tests or a
            # complete passing result retries once.
            Invoke-StandardDeviceSelection @($Selector) `
                -EvidenceLabel ("ordinary-" + $Selector.Split('.')[-1])
        } catch { Assert-QualificationOwnership $_;
            $SelectionFailure = $_.Exception.Message
            $SelectionCause = $_
            try { Reset-DisposableDevicePackages } catch { Assert-QualificationOwnership $_;
                throw (New-QualificationException "${Selector}: $SelectionFailure | isolation cleanup: $($_.Exception.Message)" $SelectionCause 'cleanup')
            }
            throw (New-QualificationException "${Selector}: $SelectionFailure" $SelectionCause $null)
        }
    }
    Write-Host 'DEVICE_LANE_PASS ordinary'
}

function Invoke-ExplicitSuiteDeviceLane {
    Write-Host 'DEVICE_LANE_START explicit-suite'
    Reset-DisposableDevicePackages
    $Manifest = Get-Content -LiteralPath $DeviceLaneManifest -Raw | ConvertFrom-Json
    $UnifiedClass = 'app.stemwise.qualification.UnifiedPlayerPressureSuite'
    $DataClass = 'app.stemwise.qualification.IsolatedDataEngineSurfaceSuite'
    $GroupFailures = [Collections.Generic.List[string]]::new()
    $GroupFailureKind = 'test-failure'
    $UnifiedSelection = $null
    foreach ($SuiteDefinition in @($Manifest.explicitSuites)) {
        $SuiteClass = [string]$SuiteDefinition.wrapper
        $Children = @($SuiteDefinition.children | ForEach-Object { [string]$_ })
        if ($Children.Count -eq 0) { throw "Explicit suite has no children: $SuiteClass" }
        Write-Host "DEVICE_EXPLICIT_GROUP_START $SuiteClass children=$($Children.Count)"
        $Extra = @{}
        $Selections = [Collections.Generic.List[string[]]]::new()
        $Children | ForEach-Object { $Selections.Add([string[]]@([string]$_)) }
        if ($SuiteClass -eq $UnifiedClass) {
            $UnifiedSelection = Get-UnifiedPlayerSuiteSelection
            $Extra = $UnifiedSelection.arguments.Clone()
            # This wrapper owns the process-wide playback baseline, session cleanup, and final
            # audio-health ledger. Its children cannot be substituted directly.
            $Selections = [Collections.Generic.List[string[]]]::new()
            $Selections.Add([string[]]@($SuiteClass))
        } elseif ($SuiteClass -eq $DataClass) {
            # Direct child substitution must retain the wrapper's same-process final residue gate.
            $Extra['listener'] =
                'app.stemwise.qualification.StandardInstrumentationResidueRunListener'
        }
        # Non-wrapper children run one class per process. This preserves every failure while
        # preventing one closed Activity/Compose root from cascading through unrelated classes.
        $UnsafeToContinue = $false
        foreach ($Selection in $Selections) {
            $SelectionName = [string]$Selection[0]
            try {
                Invoke-StandardDeviceSelection $Selection $Extra `
                    -EvidenceLabel (
                        "explicit-" + $SuiteClass.Split('.')[-1] + '-' +
                        $SelectionName.Split('.')[-1]
                    )
            } catch { Assert-QualificationOwnership $_;
                $GroupFailureKind = Merge-QualificationFailureKind $GroupFailureKind (Get-QualificationFailureKind $_)
                $GroupFailures.Add("${SuiteClass}/${SelectionName}: $($_.Exception.Message)")
                Write-Host "DEVICE_EXPLICIT_CLASS_FAIL $SuiteClass $SelectionName"
                try {
                    Reset-DisposableDevicePackages
                } catch { Assert-QualificationOwnership $_;
                    $GroupFailureKind = 'cleanup'
                    $GroupFailures.Add("${SuiteClass} cleanup: $($_.Exception.Message)")
                }
                $UnsafeToContinue = $true
            }
            if ($UnsafeToContinue) { break }
        }
        if (-not $UnsafeToContinue -and
                @($GroupFailures | Where-Object { $_ -like "$SuiteClass/*" }).Count -eq 0) {
            Write-Host "DEVICE_EXPLICIT_GROUP_PASS $SuiteClass"
        } else {
            Write-Host "DEVICE_EXPLICIT_GROUP_FAIL $SuiteClass"
        }
        if ($UnsafeToContinue) { break }
    }
    if ($GroupFailures.Count -gt 0) {
        throw (New-QualificationException "Explicit-suite group failures: $($GroupFailures -join ' | ')" $null $GroupFailureKind)
    }
    if ($null -eq $UnifiedSelection) { throw 'Explicit suites did not select the inventoried Unified playback wrapper.' }
    Invoke-StandardDeviceSelection @($UnifiedSelection.fatalSelector) @{ stemwiseNativeFatalQualification = 'true' } `
        -EvidenceLabel 'explicit-native-fatal-quarantine'
    Write-Host 'DEVICE_LANE_PASS explicit-suite'
}

function Invoke-LifecycleDeviceLane {
    Write-Host 'DEVICE_LANE_START lifecycle-phase'
    Reset-DisposableDevicePackages
    $Adb = Resolve-Adb
    $Manifest = Get-Content -LiteralPath $DeviceLaneManifest -Raw | ConvertFrom-Json
    foreach ($Workflow in $Manifest.lifecycleWorkflows) {
        # connectedAndroidTest uninstalls its target after every selection, which destroys the
        # exact private state the next lifecycle phase must recover. Install once per workflow and
        # execute its ordered phases directly in the same disposable package lifetime.
        Reset-DisposableDevicePackages
        Install-QualificationInstrumentation
        $Phases = @($Workflow.phases)
        for ($Index = 0; $Index -lt $Phases.Count; $Index++) {
            $Phase = [string]$Phases[$Index]
            Invoke-DirectInstrumentation $Adb `
                $Phase `
                $StandardInstrumentationComponent @{
                stemwiseDisposableTarget = 'true'
                stemwiseDeviceSerial = $Serial
            } | Out-Null
            Register-PassingInstrumentationMethod $Phase
            if ($Workflow.forceStopBetweenPhases -and $Index -lt ($Phases.Count - 1)) {
                Invoke-AdbChecked $Adb @('shell', 'am', 'force-stop', '--user', '0', $DevelopmentPackage)
            }
        }
    }
    Reset-DisposableDevicePackages
    Write-Host 'DEVICE_LANE_PASS lifecycle-phase'
}

function Invoke-FatalTwoProcessDeviceLane {
    Write-Host 'DEVICE_LANE_START fatal-two-process'
    Reset-DisposableDevicePackages
    $Adb = Resolve-Adb
    Install-QualificationInstrumentation
    $EffectiveRunId = if ([string]::IsNullOrWhiteSpace($RunId)) {
        if ($script:DeviceRunIdentifier) { $script:DeviceRunIdentifier } else { 'TESTER-DEVICE-' + (Get-Date -Format 'yyyyMMddHHmmss') }
    } else { $RunId }
    $SafeRunId = $EffectiveRunId -replace '[^A-Za-z0-9._-]', '_'
    $Marker = "/sdcard/Android/data/$DevelopmentPackage/files/qualification/native-fatal-retirement-$SafeRunId.txt"
    $MarkerBefore = Invoke-QualificationProcess $Adb @('-s', $Serial, 'shell', 'test', '-e', $Marker) -TimeoutSeconds 30
    if ($MarkerBefore.ExitCode -ne 1 -or $MarkerBefore.Stdout.Trim() -or $MarkerBefore.Stderr.Trim()) { throw 'Fatal-retirement requires an observed absent fresh marker.' }
    $PhaseArguments = @{
        stemwiseDisposableTarget = 'true'
        stemwiseFatalRetirementQualification = 'true'
        stemwiseFatalRetirementRunId = $SafeRunId
        stemwiseDeviceSerial = $Serial
    }
    $PhaseA = Invoke-DirectInstrumentation $Adb `
        'app.stemwise.playback.NativeFatalProcessRetirementInstrumentationTest#fatalThenRecentsRemovalRetiresProcess' `
        $StandardInstrumentationComponent $PhaseArguments -AllowNonPass
    if ($PhaseA -match 'FATAL_RECENTS_PROCESS_RETIREMENT_DID_NOT_OCCUR') {
        throw 'Fatal-retirement phase A returned instead of retiring the process.'
    }
    if (-not (Test-AdbPathExists $Adb $Marker)) {
        throw 'Fatal-retirement phase A marker is missing.'
    }
    $MarkerText = Invoke-AdbReadOnlyProbe $Adb @('shell', 'cat', $Marker) 'fatal-retirement marker'
    $ProcessAfter = Invoke-QualificationProcess $Adb @('-s', $Serial, 'shell', 'pidof', $DevelopmentPackage) -TimeoutSeconds 30
    if ($ProcessAfter.ExitCode -ne 1 -or $ProcessAfter.Stdout.Trim() -or $ProcessAfter.Stderr.Trim()) {
        throw 'Fatal-retirement phase A left the app process alive.'
    }
    $FatalPhaseA = 'app.stemwise.playback.NativeFatalProcessRetirementInstrumentationTest#fatalThenRecentsRemovalRetiresProcess'
    Register-PassingInstrumentationMethod $FatalPhaseA
    Invoke-DirectInstrumentation $Adb `
        'app.stemwise.playback.NativeFatalProcessRetirementInstrumentationTest#freshProcessAfterFatalRetirementCanPlay' `
        $StandardInstrumentationComponent $PhaseArguments | Out-Null
    $MarkerAfter = Invoke-QualificationProcess $Adb @('-s', $Serial, 'shell', 'test', '-e', $Marker) -TimeoutSeconds 30
    if ($MarkerAfter.ExitCode -ne 1 -or $MarkerAfter.Stdout.Trim() -or $MarkerAfter.Stderr.Trim()) {
        throw 'Fatal-retirement phase B left its marker behind.'
    }
    $FatalPhaseB = 'app.stemwise.playback.NativeFatalProcessRetirementInstrumentationTest#freshProcessAfterFatalRetirementCanPlay'
    Register-PassingInstrumentationMethod $FatalPhaseB
    if ($script:DeviceRunEvidenceRoot) {
        $MarkerPath = Join-Path $script:DeviceRunEvidenceRoot 'fatal-marker.txt'
        [IO.File]::WriteAllText($MarkerPath, $MarkerText, [Text.UTF8Encoding]::new($false))
        [ordered]@{ schemaVersion = 1; runId = $SafeRunId
            marker = [ordered]@{ path = [IO.Path]::GetRelativePath($LiveRoot, $MarkerPath).Replace('\', '/'); sha256 = (Get-FileSha256 $MarkerPath).ToLowerInvariant() }
            markerBefore = [ordered]@{ exitCode = $MarkerBefore.ExitCode; stdout = $MarkerBefore.Stdout; stderr = $MarkerBefore.Stderr }
            processAfter = [ordered]@{ exitCode = $ProcessAfter.ExitCode; stdout = $ProcessAfter.Stdout; stderr = $ProcessAfter.Stderr }
            markerAfter = [ordered]@{ exitCode = $MarkerAfter.ExitCode; stdout = $MarkerAfter.Stdout; stderr = $MarkerAfter.Stderr }
        } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:DeviceRunEvidenceRoot 'fatal-retirement.json') -Encoding utf8NoBOM
    }
    Write-Host 'DEVICE_LANE_PASS fatal-two-process'
}

function Invoke-CurrentRuntimeUpdateDeviceLane {
    $Adb = Resolve-Adb
    Reset-DisposableDevicePackages
    Install-QualificationInstrumentation
    $Steps = @(
        'quarantinesHostileLegacyWithoutExecutingBundled',
        'bundledExtractorBootstrapsWithoutGithubAndPassesOfflineChecks',
        'bundledReleaseIsEligibleOfflineAndPreservesSignedAuthority',
        'stagesCommittedVerifiedDownloadForProcessDeath',
        'freshProcessPromotesCommittedVerifiedDownloadOffline',
        'stagesOfficialInitializationPreservationForProcessDeath',
        'freshProcessRecoversInitializationPreservation',
        'futurePortableSchemaFailsClosedWithoutRewritingAnyBytes',
        'reacquiresSameOfficialAfterPrivateLossWithoutPortableRollback'
    )
    $Component = "$DevelopmentPackage.test/app.stemwise.integration.IsolatedUpdaterInstrumentationRunner"
    foreach ($Step in $Steps) {
        # An exact force-stop is the process boundary; portable/private state stays intact.
        Invoke-AdbChecked $Adb @('shell', 'am', 'force-stop', '--user', '0', $DevelopmentPackage)
        $Selector = "app.stemwise.integration.CurrentVerifiedYtDlpUpdaterInstrumentationTest#$Step"
        Invoke-DirectInstrumentation $Adb $Selector $Component | Out-Host
        Register-PassingInstrumentationMethod $Selector
    }
}

function Invoke-NativeProcessFaultDeviceLane {
    Write-Host 'DEVICE_LANE_START native-process-fault'
    Reset-DisposableDevicePackages
    $Manifest = Get-Content -LiteralPath $DeviceLaneManifest -Raw | ConvertFrom-Json
    $Action = $Manifest.nativeProcessFault
    $Selectors = @(Get-DeviceLaneSelectors 'native-process-fault')
    if ($Selectors.Count -ne 1 -or $Selectors[0] -cne $Action.selector -or
            $Action.arguments.stemwiseIntentionalNativeFault -cne 'true' -or
            $Action.arguments.stemwiseDisposableTarget -cne 'true') {
        throw 'Intentional native faults require one independently inventoried guarded method.'
    }
    # One exact invocation without a health listener/filter; retirement removes its fault counters.
    Invoke-StandardDeviceSelection $Selectors @{
        stemwiseIntentionalNativeFault = 'true'
        stemwiseDisposableTarget = 'true'
    } -EvidenceLabel 'native-process-fault'
    Reset-DisposableDevicePackages
    Write-Host 'DEVICE_LANE_PASS native-process-fault'
}

function Invoke-DeviceTests {
    param([switch]$RetainDeliverable)
    Require-Serial
    Invoke-DeviceInventoryValidation
    foreach ($Selector in $Class) {
        $Protected = @($script:DeviceInventoryAccount.methods | Where-Object {
            ($_.id -ceq $Selector -or $_.class -ceq $Selector -or $_.wrapper -ceq $Selector) -and
                $_.wrapper -cin @('app.stemwise.qualification.UnifiedPlayerPressureSuite',
                    'app.stemwise.qualification.IsolatedDataEngineSurfaceSuite')
        })
        if ($Protected.Count -gt 0) {
            throw 'Use Focused for protected suite selections so their wrapper and final gates remain active.'
        }
    }
    $LaneFailures = [Collections.Generic.List[string]]::new()
    $FailureKind = 'test-failure'
    $Lanes = if ($Class.Count -gt 0) {
        if ($DeviceLane -ne 'All') {
            throw '-Class and -DeviceLane cannot be combined.'
        }
        @('class')
    } elseif ($DeviceLane -eq 'All') {
        @(
            'ordinary', 'explicit-suite', 'lifecycle-phase',
            'fatal-two-process', 'native-process-fault'
        )
    } else {
        @($DeviceLane)
    }

    Initialize-ImmutableQualificationArtifacts -RetainDeliverable:$RetainDeliverable

    try {
        foreach ($Lane in $Lanes) {
            $LaneStarted = Get-Date
            $LaneFailure = $null
            $CleanupFailure = $null
            $UnsafeToContinue = $false
            try {
                switch ($Lane) {
                    'class' {
                        Reset-DisposableDevicePackages
                        Invoke-StandardDeviceSelection $Class -EvidenceLabel 'class'
                    }
                    'ordinary' { Invoke-OrdinaryDeviceLane }
                    'explicit-suite' { Invoke-ExplicitSuiteDeviceLane }
                    'lifecycle-phase' { Invoke-LifecycleDeviceLane }
                    'fatal-two-process' { Invoke-FatalTwoProcessDeviceLane }
                    'native-process-fault' { Invoke-NativeProcessFaultDeviceLane }
                    'runtime-update' { Invoke-CurrentRuntimeUpdateDeviceLane }
                }
            } catch { Assert-QualificationOwnership $_;
                $LaneFailure = $_.Exception.Message
                $LaneFailureKind = Get-QualificationFailureKind $_
                $FailureKind = Merge-QualificationFailureKind $FailureKind $LaneFailureKind
                $LaneFailures.Add("${Lane}: $LaneFailure")
                try {
                    Reset-DisposableDevicePackages
                } catch { Assert-QualificationOwnership $_;
                    $CleanupFailure = $_.Exception.Message
                    $FailureKind = 'cleanup'
                    $LaneFailures.Add("${Lane} cleanup: $CleanupFailure")
                }
                $UnsafeToContinue = $true
            }

            $script:DeviceLaneResults.Add([ordered]@{
                lane = $Lane
                target = $null
                started = $LaneStarted.ToString('o')
                finished = (Get-Date).ToString('o')
                status = if ($null -eq $LaneFailure) { 'PASS' } else { 'FAIL' }
                failure = $LaneFailure
                cleanupFailure = $CleanupFailure
                failureKind = if ($CleanupFailure) { 'cleanup' } elseif ($LaneFailure) { $LaneFailureKind } else { $null }
            })
            if ($UnsafeToContinue) { break }
        }
    } finally {
        Assert-QualificationOwnership
        try {
            Reset-DisposableDevicePackages
        } catch { Assert-QualificationOwnership $_;
            $LaneFailures.Add("final cleanup: $($_.Exception.Message)")
            $FailureKind = 'cleanup'
        }
    }

    if ($LaneFailures.Count -eq 0) {
        try {
            Assert-DeviceMethodAccounting
        } catch { Assert-QualificationOwnership $_;
            $LaneFailures.Add("method accounting: $($_.Exception.Message)")
        }
    }
    if ($LaneFailures.Count -gt 0) {
        throw (New-QualificationException "Device lane failures: $($LaneFailures -join ' | ')" $null $FailureKind)
    }
}
