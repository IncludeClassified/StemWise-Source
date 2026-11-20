function Register-PassingInstrumentationMethod([string]$MethodId) {
    if ($null -eq $script:ObservedPassingMethods) {
        $script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
    }
    $script:ObservedPassingMethods.Add($MethodId) | Out-Null
}

function Register-SkippedInstrumentationMethod([string]$MethodId) {
    if ($null -eq $script:ObservedSkippedMethods) {
        $script:ObservedSkippedMethods = [Collections.Generic.HashSet[string]]::new()
    }
    $script:ObservedSkippedMethods.Add($MethodId) | Out-Null
}

function Read-InstrumentationMethodResults([string]$ResultsDirectory) {
    $Files = @(Get-ChildItem -LiteralPath $ResultsDirectory -Filter 'TEST-*.xml' -Recurse -File)
    if ($Files.Count -eq 0) { throw 'Connected test results contain no TEST-*.xml files.' }

    $Passing = [Collections.Generic.HashSet[string]]::new()
    $Skipped = [Collections.Generic.HashSet[string]]::new()
    $Failed = [Collections.Generic.HashSet[string]]::new()
    $Seen = [Collections.Generic.HashSet[string]]::new()
    foreach ($File in $Files) {
        $Document = Read-HostQualificationXml $File.FullName
        foreach ($Case in @($Document.SelectNodes('//testcase'))) {
            $ClassName = [string]$Case.classname
            $MethodName = [string]$Case.name
            if ([string]::IsNullOrWhiteSpace($ClassName) -or
                    [string]::IsNullOrWhiteSpace($MethodName)) {
                throw "Connected result has an unnamed testcase: $($File.FullName)"
            }
            $MethodId = "$ClassName#$MethodName"
            if (-not $Seen.Add($MethodId)) {
                throw "Connected result reported a duplicate testcase identity: $MethodId"
            }
            if ($null -ne $Case.SelectSingleNode('skipped')) {
                $Skipped.Add($MethodId) | Out-Null
            } elseif ($null -ne $Case.SelectSingleNode('failure') -or
                    $null -ne $Case.SelectSingleNode('error')) {
                $Failed.Add($MethodId) | Out-Null
            } else {
                $Passing.Add($MethodId) | Out-Null
            }
        }
    }
    if ($Passing.Count + $Skipped.Count + $Failed.Count -eq 0) {
        throw 'Connected test XML contains no testcases.'
    }
    return [pscustomobject]@{
        passing = @($Passing | Sort-Object)
        skipped = @($Skipped | Sort-Object)
        failed = @($Failed | Sort-Object)
    }
}

function Get-ExpectedDeviceMethods {
    $Rows = @($script:DeviceInventoryAccount.methods)
    if ($Rows.Count -eq 0) { throw 'Device method accounting has no inventory rows.' }
    if ($Class.Count -gt 0) {
        $Selected = foreach ($Selector in $Class) {
            if ($Selector.Contains('#')) {
                $Rows | Where-Object { [string]$_.id -ceq $Selector }
            } else {
                $Rows | Where-Object {
                    [string]$_.class -ceq $Selector -or [string]$_.wrapper -ceq $Selector
                }
            }
        }
    } elseif ($DeviceLane -eq 'All') {
        $Selected = @($Rows | Where-Object { $_.lane -cne 'optional' })
    } else {
        $Selected = @($Rows | Where-Object { [string]$_.lane -ceq $DeviceLane })
    }
    $Expected = @(
        $Selected |
            Where-Object { -not $_.excluded } |
            ForEach-Object { [string]$_.id } |
            Sort-Object -Unique
    )
    if ($DeviceLane -ceq 'runtime-update') {
        $Expected = @($Rows | Where-Object { $_.class -ceq 'app.stemwise.integration.CurrentVerifiedYtDlpUpdaterInstrumentationTest' } | ForEach-Object { $_.id })
    }
    if ($Mode -ceq 'Focused') {
        $Expected = @((@($Expected) + @($script:FocusedAdditionalRequiredMethods)) | Sort-Object -Unique)
    }
    return $Expected
}

function Assert-InstrumentationMethodAccounting {
    param(
        [Parameter(Mandatory = $true)][string[]]$ExpectedMethods,
        [string]$Label = 'device'
    )
    $Passing = @($script:ObservedPassingMethods | Sort-Object)
    $Skipped = @($script:ObservedSkippedMethods | Sort-Object)
    $Executed = @((@($Passing) + @($Skipped)) | Sort-Object -Unique)
    $Missing = @($ExpectedMethods | Where-Object { $_ -cnotin $Passing })
    $Unexpected = @($Executed | Where-Object { $_ -cnotin $ExpectedMethods })
    $Result = [ordered]@{
        schemaVersion = 1
        label = $Label
        status = if ($Missing.Count -eq 0 -and $Unexpected.Count -eq 0) { 'PASS' } else { 'FAIL' }
        expectedCount = $ExpectedMethods.Count
        passingCount = $Passing.Count
        skippedCount = $Skipped.Count
        expectedMethods = $ExpectedMethods
        passingMethods = $Passing
        skippedMethods = $Skipped
        missingMethods = $Missing
        unexpectedMethods = $Unexpected
    }
    if (-not [string]::IsNullOrWhiteSpace($script:DeviceRunEvidenceRoot)) {
        $Result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (
            Join-Path $script:DeviceRunEvidenceRoot 'execution-accounting.json'
        ) -Encoding UTF8
    }
    if ($Result.status -ne 'PASS') {
        throw "Instrumentation method accounting failed: missing=$($Missing -join ', ') unexpected=$($Unexpected -join ', ')"
    }
}

function Assert-DeviceMethodAccounting {
    Assert-InstrumentationMethodAccounting (Get-ExpectedDeviceMethods) 'device'
}

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $Command) { return $Command.Source }
    }
    throw 'Python is required for device inventory validation.'
}

function Invoke-DeviceInventoryValidation {
    $Python = Resolve-Python
    $Output = @()
    $ExitCode = -1
    Push-Location $LiveRoot
    try {
        $Result = Invoke-QualificationProcess $Python @('-B', $DeviceInventory, 'account') -TimeoutSeconds 120
        if ($Result.Stderr) { Write-Host $Result.Stderr }
        $Output = @($Result.Stdout)
        $ExitCode = $Result.ExitCode
    } finally {
        Pop-Location
    }
    $Output | ForEach-Object { Write-Host $_ }

    $ArchiveFailure = $null
    if (-not [string]::IsNullOrWhiteSpace($script:DeviceRunEvidenceRoot)) {
        try {
            ($Output -join [Environment]::NewLine) | Set-Content -LiteralPath (
                Join-Path $script:DeviceRunEvidenceRoot 'inventory.json'
            ) -Encoding UTF8
        } catch {
            $ArchiveFailure = $_.Exception.Message
        }
    }
    if ($ExitCode -eq 0) {
        try {
            $script:DeviceInventoryAccount = ConvertFrom-QualificationJson $Result 'Device inventory'
            if ([string]$script:DeviceInventoryAccount.status -cne 'PASS') {
                throw 'Device inventory did not report PASS.'
            }
        } catch {
            throw
        }
    }

    if ($ExitCode -ne 0 -and $null -ne $ArchiveFailure) {
        throw "Device inventory validation failed with exit code $ExitCode | inventory archive: $ArchiveFailure"
    }
    if ($ExitCode -ne 0) {
        throw "Device inventory validation failed with exit code $ExitCode."
    }
    if ($null -ne $ArchiveFailure) {
        throw "Device inventory archive failed: $ArchiveFailure"
    }
}

function Get-DeviceLaneSelectors([string]$Lane) {
    $Python = Resolve-Python
    $Result = Invoke-QualificationProcess $Python @('-B', $DeviceInventory, 'selectors','--lane',$Lane) -TimeoutSeconds 120
    if ($Result.Stderr) { Write-Host $Result.Stderr }
    $Output = @($Result.Stdout)
    if ($Result.ExitCode -ne 0) {
        $Output | ForEach-Object { Write-Host $_ }
        throw "Device inventory could not resolve the $Lane lane."
    }
    $Value = ($Output -join '').Trim()
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Device inventory returned no selectors for $Lane."
    }
    return @($Value.Split(',', [StringSplitOptions]::RemoveEmptyEntries))
}
