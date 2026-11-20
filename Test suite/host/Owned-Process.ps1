function Assert-QualificationOwnership($Failure) {
    if ($Failure -and (Get-QualificationFailureKind $Failure) -ceq 'cleanup') {
        $script:QualificationRetirementFailure = if ($Failure -is [Management.Automation.ErrorRecord]) { $Failure.Exception } else { $Failure }
    }
    $Retirement = Get-Variable -Name QualificationRetirementFailure -Scope Script -ErrorAction SilentlyContinue
    if ($Retirement -and $Retirement.Value) { throw $Retirement.Value }
}

function Invoke-QualificationProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = (Get-Location).Path,
        [ValidateRange(1, 86400)][int]$TimeoutSeconds = 120,
        [string]$OutputLog,
        [string]$StandardInput
    )
    Assert-QualificationOwnership
    if (-not ('StemWise.Qualification.OwnedProcess' -as [type])) {
        Add-Type -Path (Join-Path $PSScriptRoot 'OwnedProcess.cs')
    }
    $Executable = (Get-Command $FilePath -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    if ([IO.Path]::GetExtension($Executable) -in @('.bat', '.cmd')) {
        # cmd expands percent/quotes even inside quoted batch arguments. Refuse ambiguous input.
        foreach ($Token in @($Executable) + $Arguments) {
            if ($Token.IndexOfAny([char[]]('%!"' + "`r`n")) -ge 0) { throw 'Unsafe batch argument cannot be represented exactly.' }
        }
        $CommandLine = '"' + [StemWise.Qualification.OwnedProcess]::Quote($Executable)
        foreach ($Argument in $Arguments) { $CommandLine += ' ' + [StemWise.Qualification.OwnedProcess]::Quote($Argument) }
        $CommandLine += '"'
        $Arguments = @('/d', '/s', '/c', $CommandLine)
        $Executable = $env:ComSpec
    }
    try {
        $Result = [StemWise.Qualification.OwnedProcess]::Run($Executable, $Arguments, $WorkingDirectory, $TimeoutSeconds,
            $(if ($OutputLog) { [IO.Path]::GetFullPath($OutputLog) } else { $null }), $StandardInput)
    } catch {
        $Cause = $_.Exception
        while ($Cause.InnerException -and -not $Cause.Data['failureKind']) { $Cause = $Cause.InnerException }
        Assert-QualificationOwnership $Cause
        throw $Cause
    }
    if ($Result.OutputTruncated -and -not $OutputLog) {
        $Failure = [IO.IOException]::new("Infrastructure output limit exceeded: $FilePath")
        $Failure.Data['failureKind'] = 'output-limit'
        $Failure.Data['stdout'] = $Result.Stdout
        $Failure.Data['stderr'] = $Result.Stderr
        throw $Failure
    }
    if ($Result.TimedOut) {
        $Failure = [TimeoutException]::new("Infrastructure timeout after ${TimeoutSeconds}s: $FilePath")
        $Failure.Data['stdout'] = $Result.Stdout
        $Failure.Data['stderr'] = $Result.Stderr
        $Failure.Data['failureKind'] = 'timeout'
        throw $Failure
    }
    return $Result
}

# Text-only native tools use this adapter; machine-readable callers use the result object.
function Invoke-QualificationOutput {
    $Executable = [string]$args[0]
    $Tokens = @($args | Select-Object -Skip 1)
    $Deadline = if ($Tokens -contains 'instrument') { 1200 } elseif ($Tokens -contains 'install') { 300 } else { 180 }
    $Result = Invoke-QualificationProcess -FilePath $Executable -Arguments $Tokens -TimeoutSeconds $Deadline
    $global:LASTEXITCODE = $Result.ExitCode
    if ($Result.Stderr) { [Console]::Error.Write($Result.Stderr) }
    if ($Result.Stdout) { return $Result.Stdout.TrimEnd("`r", "`n") -split "`r?`n" }
}

function Get-QualificationFailureKind($Failure) {
    $Cause = if ($Failure -is [Management.Automation.ErrorRecord]) { $Failure.Exception } else { $Failure }
    while ($Cause) {
        if ($Cause.Data['failureKind']) { return [string]$Cause.Data['failureKind'] }
        if ($Cause -is [TimeoutException]) { return 'timeout' }
        $Cause = $Cause.InnerException
    }
    return 'test-failure'
}

function New-QualificationException([string]$Message, $Failure, [string]$Kind) {
    $Cause = if ($Failure -is [Management.Automation.ErrorRecord]) { $Failure.Exception } else { $Failure }
    $Exception = [Exception]::new($Message, $Cause)
    if ($Cause) { foreach ($Key in $Cause.Data.Keys) { $Exception.Data[$Key] = $Cause.Data[$Key] } }
    $Exception.Data['failureKind'] = if ($Kind) { $Kind } else { Get-QualificationFailureKind $Cause }
    return $Exception
}

function ConvertFrom-QualificationJson($Result, [string]$Label) {
    try {
        if ($Result.ExitCode -ne 0) { throw "$Label exited with code $($Result.ExitCode)." }
        if ($Result.OutputTruncated) { throw "$Label exceeded the complete JSON capture bound." }
        $Value = ConvertFrom-Json -InputObject $Result.Stdout -ErrorAction Stop
        if ($null -eq $Value) { throw "$Label returned null JSON." }
        return $Value
    } catch {
        $Failure = New-QualificationException "$Label returned invalid JSON or failed: $($_.Exception.Message)" $_ 'infrastructure'
        $Failure.Data['stdout'] = $Result.Stdout
        $Failure.Data['stderr'] = $Result.Stderr
        throw $Failure
    }
}

function Merge-QualificationFailureKind([string]$Current, [string]$Incoming) {
    $Priority = @{'test-failure'=1; infrastructure=2; 'output-limit'=3; timeout=4; cleanup=5}
    if (-not $Current -or $Priority[$Incoming] -gt $Priority[$Current]) { return $Incoming }
    return $Current
}
