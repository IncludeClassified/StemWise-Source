# Dot-sourced by Run-Tester.ps1; uses its artifact, device, mutex and evidence guards.
# Shared by every device lane, including focused and native-only runs.
function Get-DeviceKeyguardState {
    try {
        $Result = Invoke-QualificationProcess (Resolve-Adb) @('-s', $Serial, 'shell', 'dumpsys', 'window', 'policy') -TimeoutSeconds 20
        if ($Result.ExitCode -ne 0) { throw 'Keyguard probe failed' }
        $Values = @{}
        foreach ($Name in @('showing', 'inputRestricted', 'secure')) {
            $FieldMatches = [regex]::Matches($Result.Stdout, ('(?m)^\s*' + $Name + '=(true|false)\s*$'))
            if ($FieldMatches.Count -ne 1) { throw 'Unknown keyguard state' }
            $Values[$Name] = $FieldMatches[0].Groups[1].Value -ceq 'true'
        }
        return [pscustomobject]@{ secure = $Values.secure; unlocked = -not ($Values.showing -or $Values.inputRestricted) }
    } catch { throw [Exception]::new('Cannot verify the device keyguard state.') }
}

function Assert-DeviceUnlockPrepared {
    $ScreenOff = 'app.stemwise.playback.LoopSourceHandoffInstrumentationTest#clearingAnEditedLoopResumesLinearAudioInBothSources'
    $Selections = @($Class)
    $NeedsUnlock = $Mode -cin @('Full', 'AudioQualify', 'AndroidSuite') -or
        ($Mode -ceq 'Device' -and $Selections.Count -eq 0 -and $DeviceLane -cin @('All', 'explicit-suite')) -or
        @($Selections | Where-Object { $_ -cin @($ScreenOff, $ScreenOff.Split('#')[0],
            'app.stemwise.qualification.UnifiedPlayerPressureSuite') }).Count -gt 0
    if ($NeedsUnlock -and (Get-DeviceKeyguardState).secure -and
            [Environment]::GetEnvironmentVariable('STEMWISE_DEVICE_UNLOCK_PIN', 'Process') -cnotmatch '^[0-9]+$') {
        throw 'Screen-off tests require the process-only STEMWISE_DEVICE_UNLOCK_PIN for verified unlock cleanup.'
    }
}

function Restore-DeviceUnlocked {
    $Adb = Resolve-Adb
    function Invoke-UnlockAdb([string[]]$Arguments, [string]$InputText) {
        try {
            $Result = Invoke-QualificationProcess $Adb (@('-s', $Serial) + $Arguments) `
                -TimeoutSeconds 20 -StandardInput $InputText
            if ($Result.ExitCode -ne 0) { throw 'ADB failed' }
            return $Result.Stdout
        } catch {
            # Credential-bearing stdin and unexpected child output must never enter exception evidence.
            throw [Exception]::new('Device unlock command failed.')
        }
    }
    function Read-DeviceUnlocked {
        return (Get-DeviceKeyguardState).unlocked
    }
    $Remote = $null
    $Failure = $null
    try {
        Invoke-UnlockAdb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
        $Initial = Get-DeviceKeyguardState
        if ($Initial.unlocked) { return }
        Invoke-UnlockAdb @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
        Invoke-UnlockAdb @('shell', 'wm', 'dismiss-keyguard') | Out-Null
        if (Read-DeviceUnlocked) { return }
        $Size = Invoke-UnlockAdb @('shell', 'wm', 'size')
        $Bounds = [regex]::Matches($Size, '(?m)^(?:Physical|Override) size: ([0-9]+)x([0-9]+)\s*$')
        if ($Bounds.Count -eq 0) { throw 'Cannot establish display bounds for keyguard dismissal.' }
        $Width = [int]$Bounds[-1].Groups[1].Value; $Height = [int]$Bounds[-1].Groups[2].Value
        if ($Width -le 0 -or $Height -le 0) { throw 'Invalid display bounds for keyguard dismissal.' }
        Invoke-UnlockAdb @('shell', 'input', 'swipe', [string][int]($Width / 2), [string][int]($Height * .8),
            [string][int]($Width / 2), [string][int]($Height * .2), '300') | Out-Null
        if (-not $Initial.secure) {
            $Deadline = [DateTime]::UtcNow.AddSeconds(5)
            do {
                if (Read-DeviceUnlocked) { return }
                Start-Sleep -Milliseconds 200
            } while ([DateTime]::UtcNow -lt $Deadline)
            throw 'The nonsecure keyguard did not dismiss.'
        }
        $UnlockPin = [Environment]::GetEnvironmentVariable('STEMWISE_DEVICE_UNLOCK_PIN', 'Process')
        if ($UnlockPin -cnotmatch '^[0-9]+$') { throw 'A process-only numeric unlock PIN is required for this locked device.' }
        $Remote = '/data/local/tmp/stemwise-unlock-' + [guid]::NewGuid().ToString('N') + '.xml'
        Invoke-UnlockAdb @('shell', 'uiautomator', 'dump', $Remote) | Out-Null
        $Hierarchy = Invoke-UnlockAdb @('shell', 'cat', $Remote)
        $Settings = [Xml.XmlReaderSettings]::new(); $Settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $Settings.XmlResolver = $null
        $Reader = [Xml.XmlReader]::Create([IO.StringReader]::new($Hierarchy), $Settings)
        try { $Document = [Xml.XmlDocument]::new(); $Document.XmlResolver = $null; $Document.Load($Reader) }
        finally { $Reader.Dispose() }
        $Entry = @($Document.SelectNodes("//node[@package='com.android.systemui' and @resource-id='com.android.systemui:id/pinEntry' and @class='android.widget.EditText' and @focused='true' and @password='true']"))
        if ($Entry.Count -ne 1) { throw 'The focused secure SystemUI numeric PIN field was not observed; no credential entered.' }
        Invoke-UnlockAdb @('shell', 'sh') ("input text " + $UnlockPin + " >/dev/null 2>&1`ninput keyevent KEYCODE_ENTER >/dev/null 2>&1`n") | Out-Null
        $Deadline = [DateTime]::UtcNow.AddSeconds(5)
        do {
            if (Read-DeviceUnlocked) { return }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $Deadline)
        throw 'The single credential attempt did not unlock the device.'
    } catch {
        $Failure = $_.Exception.Message
    } finally {
        $UnlockPin = $null
        if ($Remote) {
            try { Invoke-UnlockAdb @('shell', 'rm', '-f', $Remote) | Out-Null }
            catch { $Failure = 'Device unlock hierarchy cleanup failed.' }
        }
        if ($Failure) { throw (New-QualificationException $Failure $null 'cleanup') }
    }
}

function Set-DeviceTestSetting {
    param(
        [string]$Adb,
        [ValidateSet('Awake', 'Zen', 'Airplane')][string]$Setting,
        [string]$Value
    )
    $ZenModes = @{ '0' = 'all'; '1' = 'priority'; '2' = 'none'; '3' = 'alarms' }
    switch ($Setting) {
        'Awake' {
            $Label = 'Stay Awake'
            $Read = @('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in')
            $Number = 0
            if ($Value -ceq 'enabled') { $Write = @('shell', 'svc', 'power', 'stayon', 'true') }
            elseif ([int]::TryParse($Value, [ref]$Number) -and $Number -ge 0) {
                $Write = @('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', $Value)
            } else { throw 'Invalid Stay Awake setting.' }
        }
        'Zen' {
            $Label = 'Do Not Disturb'
            if (-not $ZenModes.ContainsKey($Value)) { throw 'Invalid Do Not Disturb setting.' }
            $Read = @('shell', 'settings', 'get', 'global', 'zen_mode')
            $Write = @('shell', 'cmd', 'notification', 'set_dnd', $ZenModes[$Value])
        }
        'Airplane' {
            $Label = 'Airplane mode'
            if ($Value -cnotin @('enabled', 'disabled')) { throw 'Invalid airplane mode setting.' }
            $Read = @('shell', 'cmd', 'connectivity', 'airplane-mode')
            $Action = if ($Value -ceq 'enabled') { 'enable' } else { 'disable' }
            $Write = $Read + $Action
        }
    }
    $Failures = [Collections.Generic.List[string]]::new()
    $HostConnectionFailure = $false
    # Only these reversible settings may retry, after readback proves the write is still needed.
    for ($Attempt = 0; $Attempt -le 2; $Attempt++) {
        try { $Current = Invoke-AdbReadOnlyProbe $Adb $Read $Label }
        catch {
            if ($Failures.Count -gt 0) {
                throw (New-QualificationException "$Label setting: $($Failures -join '; ') | $($_.Exception.Message)" $_ $null)
            }
            throw
        }
        $Valid = $true
        if ($Setting -ceq 'Awake') {
            $Number = 0
            $Valid = [int]::TryParse($Current, [ref]$Number) -and $Number -ge 0
            $Satisfied = if ($Value -ceq 'enabled') { $Valid -and ($Number -band 7) -eq 7 } else { $Current -ceq $Value }
        } else {
            $Valid = if ($Setting -ceq 'Zen') { $ZenModes.ContainsKey($Current) } else { $Current -cin @('enabled', 'disabled') }
            $Satisfied = $Current -ceq $Value
        }
        if (-not $Valid) { throw "$Label could not be verified: '$Current'. $($Failures -join '; ')" }
        if ($Satisfied) { return }
        if ($Attempt -gt 0 -and -not $HostConnectionFailure) {
            throw "$Label could not be verified as '$Value' after the setting command."
        }
        if ($Attempt -eq 2) {
            throw (New-QualificationException "$Label setting failed: $($Failures -join '; ')" $null 'infrastructure')
        }
        $Result = Invoke-QualificationProcess -FilePath $Adb -Arguments (@('-s', $Serial) + $Write) -TimeoutSeconds 180
        $global:LASTEXITCODE = $Result.ExitCode
        if ($Result.Stderr) { [Console]::Error.Write($Result.Stderr) }
        $HostConnectionFailure = $false
        if ($Result.ExitCode -ne 0) {
            $Failures.Add("attempt $($Attempt + 1) (exit code $($Result.ExitCode)): stdout: $($Result.Stdout) stderr: $($Result.Stderr)")
            $HostConnectionFailure = Test-AdbHostConnectionFailure $Result.Stdout $Result.Stderr
            if (-not $HostConnectionFailure) {
                $Failure = New-QualificationException "$Label setting failed: $($Failures -join '; ')" $null 'test-failure'
                $Failure.Data['stdout'] = $Result.Stdout
                $Failure.Data['stderr'] = $Result.Stderr
                throw $Failure
            }
        }
    }
}

function Invoke-WithDeviceTestSettings([scriptblock]$Operation) {
    Assert-DeviceUnlockPrepared
    $Adb = Resolve-Adb
    $Awake = Invoke-AdbReadOnlyProbe $Adb @('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in') 'Stay Awake'
    $Zen = Invoke-AdbReadOnlyProbe $Adb @('shell', 'settings', 'get', 'global', 'zen_mode') 'Do Not Disturb'
    $ZenModes = @{ '0' = 'all'; '1' = 'priority'; '2' = 'none'; '3' = 'alarms' }
    $AwakeValue = 0
    if (-not [int]::TryParse($Awake, [ref]$AwakeValue) -or $AwakeValue -lt 0 -or -not $ZenModes.ContainsKey($Zen)) {
        throw 'Cannot preserve the phone test settings.'
    }
    $Failure = $null
    $RestoreFailures = @()
    $RestoreCauses = [Collections.Generic.List[Exception]]::new()
    try {
        Set-DeviceTestSetting $Adb Awake enabled
        Set-DeviceTestSetting $Adb Zen '1'
        Restore-DeviceUnlocked
        & $Operation
    } catch {
        $Failure = $_
    } finally {
        try { Restore-DeviceUnlocked }
        catch { $RestoreFailures += $_.Exception.Message; $RestoreCauses.Add($_.Exception) }
        try {
            Set-DeviceTestSetting $Adb Awake $Awake
        }
        catch { $RestoreFailures += $_.Exception.Message; $RestoreCauses.Add($_.Exception) }
        try {
            Set-DeviceTestSetting $Adb Zen $Zen
        }
        catch { $RestoreFailures += $_.Exception.Message; $RestoreCauses.Add($_.Exception) }
    }
    if ($RestoreFailures.Count -gt 0) {
        if ($null -ne $Failure) { $RestoreCauses.Insert(0, $Failure.Exception) }
        $Message = if ($null -ne $Failure) { $Failure.Exception.Message + ' | ' } else { '' }
        throw (New-QualificationException ($Message + "Phone settings restoration: $($RestoreFailures -join ' | ')") (
            [AggregateException]::new([Exception[]]$RestoreCauses.ToArray())
        ) 'cleanup')
    }
    if ($null -ne $Failure) { throw $Failure }
}

function Invoke-WithAudioSelectionIsolation {
    param([string[]]$Selectors, [scriptblock]$Operation, [switch]$PhysicalOutput)
    $RequiresIsolation = $PhysicalOutput.IsPresent
    if (-not $RequiresIsolation -and $Selectors.Count -gt 0) {
        $RequiresIsolation = $true
        foreach ($Selector in $Selectors) {
            $Rows = @($script:DeviceInventoryAccount.methods | Where-Object {
                $_.id -ceq $Selector -or $_.class -ceq $Selector -or $_.wrapper -ceq $Selector
            })
            if ($Rows.Count -eq 0 -or @($Rows | Where-Object { $_.requiresAudioIsolation -cne $true }).Count -gt 0) {
                $RequiresIsolation = $false
                break
            }
        }
    }
    if (-not $RequiresIsolation) { return & $Operation }

    $Adb = Resolve-Adb
    $Airplane = Invoke-AdbReadOnlyProbe $Adb @('shell', 'cmd', 'connectivity', 'airplane-mode') 'Airplane mode'
    if ($Airplane -cnotin @('enabled', 'disabled')) { throw 'Cannot preserve airplane mode.' }
    $Failure = $null
    try {
        Set-DeviceTestSetting $Adb Airplane enabled
        & $Operation
    } catch { $Failure = $_ }
    finally {
        try {
            Set-DeviceTestSetting $Adb Airplane $Airplane
        } catch {
            $Causes = [Collections.Generic.List[Exception]]::new()
            if ($null -ne $Failure) { $Causes.Add($Failure.Exception) }
            $Causes.Add($_.Exception)
            $Message = $(if ($null -ne $Failure) { $Failure.Exception.Message + ' | ' }) + $_.Exception.Message
            throw (New-QualificationException $Message ([AggregateException]::new([Exception[]]$Causes.ToArray())) 'cleanup')
        }
    }
    if ($null -ne $Failure) { throw $Failure }
}

function Initialize-FocusedArtifacts {
    $Variant = 'Development'
    $Arguments = @(':app:assemble' + $Variant)
    $Arguments += ':app:verifyDevelopmentArtifactIdentity'
    $Adb = Resolve-Adb
    $Abi = Invoke-AdbReadOnlyProbe $Adb @('shell', 'getprop', 'ro.product.cpu.abi') 'CPU ABI'
    if ($Abi -cnotin @('arm64-v8a', 'x86_64')) { throw "Unsupported Development CPU ABI: $Abi" }
    $script:FocusedDeviceAbi = $Abi
    $Arguments += "-Pstemwise.developmentTargetAbi=$Abi"
    if ($Mode -in @('Focused', 'Device', 'Actions', 'Full')) {
        $Arguments += @(':qa-provider:assembleDevelopment', ":app:assemble${Variant}AndroidTest")
    }
    Invoke-Gradle $Arguments
    Freeze-ApkArtifact (Resolve-GradleApk $DevelopmentApkOutput) 'qualification' 'application' | Out-Null
    if ($Mode -in @('Focused', 'Device', 'Actions', 'Full')) {
        Freeze-ApkArtifact (Resolve-GradleApk $DevelopmentTestApkOutput) 'qualification' 'androidTest' | Out-Null
        Freeze-ApkArtifact (Resolve-GradleApk $QaProviderApkOutput) 'synthetic-provider' 'application' | Out-Null
        Install-QualificationProvider
    }
}

function Get-UnifiedPlayerSuiteSelection {
    $Wrapper = 'app.stemwise.qualification.UnifiedPlayerPressureSuite'
    $FatalSelector = 'app.stemwise.playback.RuntimePlaybackControlsInstrumentationTest#nativeActorFatalFaultImmediatelySilencesAndQuarantinesWithoutPollSpin'
    $Rows = @($script:DeviceInventoryAccount.methods | Where-Object { [string]$_.wrapper -ceq $Wrapper })
    $Methods = @($Rows | ForEach-Object { [string]$_.id })
    if ($Rows.Count -eq 0 -or @($Rows | Where-Object { $_.excluded -or $_.lane -cne 'explicit-suite' }).Count -or
            @($Methods | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -or
            @($Methods | Sort-Object -Unique).Count -ne $Methods.Count -or
            @($Methods | Where-Object { $_ -ceq $FatalSelector }).Count -ne 1) {
        throw 'Unified playback requires a complete unique method inventory with its isolated fatal leaf.'
    }
    $SafeMethods = @($Methods | Where-Object { $_ -cne $FatalSelector })
    if ($SafeMethods.Count -eq 0) { throw 'Unified playback requires nonfatal inventoried methods.' }
    return @{
        wrapper = $Wrapper
        fatalSelector = $FatalSelector
        arguments = @{
            filter = 'app.stemwise.qualification.FocusedSuiteMethodFilter'
            stemwiseFocusedMethods = $SafeMethods -join ','
            listener = 'app.stemwise.qualification.FocusedSuiteMethodRunListener,app.stemwise.qualification.ProcessAudioHealthRunListener'
        }
    }
}

function Assert-FocusedSelectors {
    if ($Class.Count -eq 0 -or $DeviceLane -ne 'All') {
        throw 'Focused requires explicit -Class selections without -DeviceLane.'
    }
    if (@($Class | Sort-Object -Unique).Count -ne $Class.Count) { throw 'Focused selectors must be unique.' }
    foreach ($Selector in $Class) {
        $Rows = @($script:DeviceInventoryAccount.methods | Where-Object {
            [string]$_.id -ceq $Selector -or [string]$_.class -ceq $Selector -or
                [string]$_.wrapper -ceq $Selector
        })
        if ($Rows.Count -eq 0 -or @($Rows | Where-Object {
            $_.excluded -or $_.lane -notin @('ordinary', 'explicit-suite', 'optional')
        }).Count) { throw "Selection requires a dedicated workflow or is unknown: $Selector" }
        foreach ($Wrapper in @('app.stemwise.qualification.UnifiedPlayerPressureSuite',
                'app.stemwise.qualification.IsolatedDataEngineSurfaceSuite')) {
            if ($Selector -cne $Wrapper -and @($Rows | Where-Object { $_.wrapper -ceq $Wrapper }).Count) {
                if (-not $Selector.Contains('#') -or $Rows.Count -ne 1 -or [string]$Rows[0].id -cne $Selector) {
                    throw "Select exact class#method leaves or the complete $Wrapper to retain its gates."
                }
                if ($Class -ccontains $Wrapper) { throw "Do not combine $Wrapper with its selected leaves." }
            }
        }
    }
}

function Invoke-AudioDeviceChecks([object]$Plan) {
    Require-Serial
    Invoke-DeviceInventoryValidation
    $Required = @($Plan.required.device)
    $Rows = foreach ($Method in $Required) {
        $SelectedRows = @($script:DeviceInventoryAccount.methods | Where-Object { $_.id -ceq $Method })
        if ($SelectedRows.Count -ne 1 -or $SelectedRows[0].excluded -or
                $SelectedRows[0].lane -cnotin @('ordinary', 'explicit-suite', 'optional', 'fatal-two-process', 'native-process-fault')) {
            throw "Audio candidate has an unclassified or unsupported device ordering: $Method"
        }
        $SelectedRows[0]
    }
    $Manifest = Get-Content -LiteralPath $DeviceLaneManifest -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($Lane in @('fatal-two-process', 'native-process-fault')) {
        $Selected = @($Rows | Where-Object lane -ceq $Lane | ForEach-Object { $_.id } | Sort-Object)
        $Expected = if ($Lane -ceq 'fatal-two-process') { @($Manifest.fatalTwoProcess.phaseA, $Manifest.fatalTwoProcess.phaseB) } else { @($Manifest.nativeProcessFault.selector) }
        if ($Selected.Count -and ($Selected -join ',') -cne (($Expected | Sort-Object) -join ',')) {
            throw "Audio candidate requires the complete registered $Lane ordering."
        }
    }
    $Failure = $null
    try {
        $FocusedRows = @($Rows | Where-Object { $_.lane -cin @('ordinary', 'explicit-suite', 'optional') })
        $CompleteClasses = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($Group in @($FocusedRows | Where-Object lane -ceq 'ordinary' | Group-Object { [string]$_.class })) {
            $All = @($script:DeviceInventoryAccount.methods | Where-Object class -ceq $Group.Name)
            if (@($All | Where-Object { $_.excluded -or $_.lane -cne 'ordinary' -or $_.wrapper }).Count -eq 0 -and
                    (($All.id | Sort-Object) -join ',') -ceq (($Group.Group.id | Sort-Object) -join ',')) {
                $CompleteClasses.Add($Group.Name) | Out-Null
            }
        }
        $Emitted = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $Class = @(foreach ($Row in $FocusedRows) {
            if ($CompleteClasses.Contains([string]$Row.class)) {
                if ($Emitted.Add([string]$Row.class)) { [string]$Row.class }
            } else { [string]$Row.id }
        })
        if (((Get-ExpectedDeviceMethods | Sort-Object) -join ',') -cne (($FocusedRows.id | Sort-Object) -join ',')) {
            throw 'Audio candidate class selection changed its exact required methods.'
        }
        Invoke-FocusedChecks
        if (@($Rows | Where-Object lane -ceq 'native-process-fault').Count) { Invoke-NativeProcessFaultDeviceLane }
        if (@($Rows | Where-Object lane -ceq 'fatal-two-process').Count) { Invoke-FatalTwoProcessDeviceLane }
        $Class = $Required
        Assert-DeviceMethodAccounting
    } catch { $Failure = $_ } finally {
        try { Reset-DisposableDevicePackages } catch {
            if ($Failure) {
                throw (New-QualificationException "$($Failure.Exception.Message) | disposable cleanup: $($_.Exception.Message)" (
                    [AggregateException]::new([Exception[]]@($Failure.Exception, $_.Exception))) 'cleanup')
            }
            throw
        }
    }
    if ($Failure) { throw $Failure }
}

function Invoke-FocusedChecks {
    if (-not $DeviceProfile) { throw 'Focused/NativeDevice requires -DeviceProfile.' }
    Require-Serial
    if ($Mode -eq 'Focused') {
        Invoke-DeviceInventoryValidation
        Assert-FocusedSelectors
    }
    Initialize-FocusedArtifacts
    $Adb = Resolve-Adb
    if ($Mode -eq 'NativeDevice') {
        $Apk = Get-FrozenArtifact 'qualification' 'application'
        $NdkVersion = (Get-Content -LiteralPath (Join-Path $Repo 'gradle/android-toolchain.properties') -Raw |
            ConvertFrom-StringData).ndkVersion
        if (-not $NdkVersion) { throw 'Cannot resolve the app-pinned NDK.' }
        $Sdk = Split-Path (Split-Path $Adb)
        Invoke-Checked (Resolve-Python) @('-B', (Join-Path $Suite 'device/run_native_device.py'),
            '--adb', $Adb, '--serial', $Serial, '--apk', $Apk.path, '--abi', $script:FocusedDeviceAbi,
            '--case', $NativeCase, '--ndk', (Join-Path $Sdk "ndk/$NdkVersion"),
            '--output', (Join-Path $script:DeviceRunEvidenceRoot "native-$NativeCase")) $LiveRoot
        $script:DeviceLaneResults.Add([ordered]@{ lane = "native-$NativeCase"; status = 'PASS' })
        return
    }
    $Failure = $null
    try {
        $Handled = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $script:FocusedAdditionalRequiredMethods = @()
        foreach ($Selector in $Class) {
            if (-not $Handled.Add($Selector)) { continue }
            Invoke-AdbChecked $Adb @('shell', 'am', 'force-stop', '--user', '0', $DevelopmentPackage)
            $Extra = @{}
            $ActualSelectors = @($Selector)
            $SkipUnifiedWrapper = $false
            $UnifiedSelection = $null
            $Row = @($script:DeviceInventoryAccount.methods | Where-Object { [string]$_.id -ceq $Selector })
            if ($Selector.Contains('#') -and $Row.Count -eq 1 -and [string]$Row[0].wrapper -cin @(
                    'app.stemwise.qualification.UnifiedPlayerPressureSuite',
                    'app.stemwise.qualification.IsolatedDataEngineSurfaceSuite')) {
                $Wrapper = [string]$Row[0].wrapper
                $Leaves = @($Class | Where-Object {
                    $Leaf = $_
                    @($script:DeviceInventoryAccount.methods | Where-Object {
                        [string]$_.id -ceq $Leaf -and [string]$_.wrapper -ceq $Wrapper
                    }).Count -eq 1
                })
                foreach ($Leaf in $Leaves) { $Handled.Add($Leaf) | Out-Null }
                $ActualSelectors = @($Wrapper)
                $Extra.filter = 'app.stemwise.qualification.FocusedSuiteMethodFilter'
                $Extra.stemwiseFocusedMethods = $Leaves -join ','
                $Extra.listener = 'app.stemwise.qualification.FocusedSuiteMethodRunListener'
            }
            if ($ActualSelectors[0] -ceq 'app.stemwise.qualification.UnifiedPlayerPressureSuite') {
                $UnifiedSelection = Get-UnifiedPlayerSuiteSelection
                if (-not $Extra.ContainsKey('stemwiseFocusedMethods')) {
                    $Extra = $UnifiedSelection.arguments.Clone()
                } else {
                    $SafeLeaves = @($Extra.stemwiseFocusedMethods.Split(',') | Where-Object {
                        $_ -cne $UnifiedSelection.fatalSelector
                    })
                    $SkipUnifiedWrapper = $SafeLeaves.Count -eq 0
                    $Extra.stemwiseFocusedMethods = $SafeLeaves -join ','
                    $Extra.listener = (@($Extra.listener, 'app.stemwise.qualification.ProcessAudioHealthRunListener') |
                        Where-Object { $_ }) -join ','
                }
            }
            if ($ProfilePerformance) {
                if ([int]$ApprovedDeviceApi -lt 29) { throw 'Performance tracing requires API 29 or newer.' }
                $Extra.listener = (@($Extra.listener, 'app.stemwise.qualification.PlaybackPerformanceRunListener') |
                    Where-Object { $_ }) -join ','
            }
            if (-not $SkipUnifiedWrapper) {
                Invoke-StandardDeviceSelection $ActualSelectors -InstrumentationArguments $Extra -EvidenceLabel $Selector
            }
            if ($ActualSelectors[0] -ceq 'app.stemwise.qualification.UnifiedPlayerPressureSuite') {
                Invoke-AdbChecked $Adb @('shell', 'am', 'force-stop', '--user', '0', $DevelopmentPackage)
                $Fatal = $UnifiedSelection.fatalSelector
                Invoke-StandardDeviceSelection @(
                    $Fatal
                ) @{ stemwiseNativeFatalQualification = 'true' } -EvidenceLabel 'focused-native-fatal-quarantine'
                $script:FocusedAdditionalRequiredMethods += $Fatal
            }
        }
        Assert-DeviceMethodAccounting
    } catch { $Failure = $_ } finally {
        try { Invoke-AdbChecked $Adb @('shell', 'am', 'force-stop', '--user', '0', $DevelopmentPackage) } catch {
            if ($Failure) {
                throw (New-QualificationException "$($Failure.Exception.Message) | force-stop cleanup: $($_.Exception.Message)" (
                    [AggregateException]::new([Exception[]]@($Failure.Exception, $_.Exception))) 'cleanup')
            }
            throw
        }
    }
    if ($Failure) { throw $Failure }
}
