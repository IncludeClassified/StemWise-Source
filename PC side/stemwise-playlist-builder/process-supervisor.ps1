param([Parameter(Mandatory = $true)][string]$PayloadBase64)
. (Join-Path $PSScriptRoot 'atomic-json.ps1')
$ErrorActionPreference = 'Stop'
$payloadJson = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($PayloadBase64))
$payload = $payloadJson | ConvertFrom-Json
$startedAt = [DateTime]::UtcNow.ToString('o')
$exitCode = 1
$workerProcessId = $null
try {
    if ($payload.operationId -isnot [string] -or $payload.operationId -cnotmatch '^[0-9a-f]{32}$') {
        throw 'Invalid workflow operation ID.'
    }
    $env:STEMWISE_LAUNCH_TOKEN = $payload.operationId
    # Own this supervisor before any child starts; Windows closes the job on
    # supervisor death, including forced termination that skips PowerShell finally.
    Add-Type -Path (Join-Path $PSScriptRoot 'WorkerJob.cs')
    [StemWiseWorkerJob]::Attach()
    $processArguments = @(
        '-NoProfile',
        '-NonInteractive',
        '-ExecutionPolicy', 'Bypass',
        '-File', ('"{0}"' -f $payload.runner)
    ) + @($payload.arguments)
    $exitCode = [StemWiseWorkerJob]::Run($payload.powerShellExe,
        ($processArguments -join ' '), $payload.workingDirectory, $payload.stdout, $payload.stderr)
    $workerProcessId = [StemWiseWorkerJob]::WorkerProcessId
} catch {
    $message = "Detached Stemwise worker failed: $($_ | Out-String)"
    try {
        $cause = [Text.Encoding]::UTF8.GetBytes($message)
        if ('StemWiseWorkerJob' -as [type]) {
            [StemWiseWorkerJob]::AppendTail($payload.stderr, $cause, $cause.Length)
        } else {
            [IO.File]::WriteAllText($payload.stderr, $message.Substring([Math]::Max(0, $message.Length - 12000)),
                [Text.UTF8Encoding]::new($false))
        }
    } catch {
        # Completion still needs to be attempted if the private error log is
        # temporarily unavailable.
    }
    $exitCode = 1
}
$completionRecord = [ordered]@{
    formatVersion = 1
    operationId = $payload.operationId
    supervisorProcessId = $PID
    workerProcessId = $workerProcessId
    startedAt = $startedAt
    completedAt = [DateTime]::UtcNow.ToString('o')
    exitCode = [int]$exitCode
    stdout = $payload.stdout
    stderr = $payload.stderr
}
$completionWritten = $false
for ($attempt = 0; $attempt -lt 3 -and -not $completionWritten; $attempt++) {
    try {
        Write-StemWiseJsonAtomic -Path $payload.completion -Value $completionRecord
        $completionWritten = $true
    } catch {
        if ($attempt -eq 2) {
            [Console]::Error.WriteLine('completion-publication-failed')
            $exitCode = 1
        } else { Start-Sleep -Milliseconds 100 }
    }
}
exit ([int]$exitCode)
