[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Command = 'status',

    [string]$Url,
    [string]$UrlBase64,
    [string]$VideoId,
    [string]$RecordingKey,
    [long]$MaxFileBytes,
    [switch]$ManualImport,
    [switch]$Shadow,
    [string]$ExpectedDeliverySha256,
    [string]$ExpectedAnalysisSha256
)

. (Join-Path $PSScriptRoot 'command-spec.ps1')
$CurrentCommands = @((Get-StemWiseCommandSpecification).Values | Where-Object { $_.Recording } | ForEach-Object { $_.Command })
if ($Command -notin $CurrentCommands) { throw ('Unknown current workflow command: ' + $Command) }
if ($Command -eq 'syllacandidate' -and -not $Shadow) { throw 'syllacandidate is disabled without explicit -Shadow.' }
if ($Shadow -and $Command -ne 'syllacandidate') { throw '-Shadow requires syllacandidate.' }

if ($Command -eq 'replaceanalysis') {
    if ($Url -or $UrlBase64 -or ($VideoId -and $RecordingKey) -or (-not $VideoId -and -not $RecordingKey) -or
        ($VideoId -and $VideoId -cnotmatch '^[A-Za-z0-9_-]{11}$') -or
        ($RecordingKey -and $RecordingKey -cnotmatch '^qobuz:[1-9][0-9]*$') -or
        $ExpectedDeliverySha256 -cnotmatch '^[0-9a-f]{64}$' -or $ExpectedAnalysisSha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'replaceanalysis requires one exact source selector and expected delivery/analysis SHA-256 values.'
    }
} elseif ($ExpectedDeliverySha256 -or $ExpectedAnalysisSha256) {
    throw 'Expected delivery/analysis hashes require replaceanalysis.'
}
$Command = $Command.ToLowerInvariant()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-bootstrap.ps1')
$runtime = Get-StemWiseRuntime -BuilderWindows $PSScriptRoot
$projectWsl = $runtime.Builder

$entry = 'stemwise_engine.workflow_command'
$config = "$projectWsl/config.json"
$cliArgs = @('-m', $entry, '--config', $config, $Command)
if ($Command -eq 'bridgedelivery') {
    if ($RecordingKey -or $VideoId -cnotmatch '^[A-Za-z0-9_-]{11}$' -or $MaxFileBytes -le 0) {
        throw 'bridgedelivery requires exact -VideoId and current positive -MaxFileBytes.'
    }
}
if ($RecordingKey) {
    if ($RecordingKey -cnotmatch '^qobuz:[1-9][0-9]*$') {
        throw 'Use one exact Qobuz recording key and a recording workflow action.'
    }
    $cliArgs = @('-m', $entry, '--config', $config, '--recording-key', $RecordingKey, $Command)
}
if ($Shadow) { $cliArgs += '--shadow' }
if ($Command -eq 'replaceanalysis') {
    $cliArgs += @('--expected-delivery-sha256', $ExpectedDeliverySha256, '--expected-analysis-sha256', $ExpectedAnalysisSha256)
}
if ($PSBoundParameters.ContainsKey('MaxFileBytes')) {
    if ($Command -notin @('new', 'retry', 'bridgedelivery') -or $MaxFileBytes -le 0) {
        throw '-MaxFileBytes requires new, retry or bridgedelivery and a positive byte count.'
    }
    $cliArgs += @('--max-file-bytes', $MaxFileBytes)
}
if ($ManualImport) {
    if ($Command -ne 'retry') { throw '-ManualImport requires retry.' }
    $cliArgs += '--manual-import'
}

switch ($Command) {
    'new' {
        if ($RecordingKey -and -not $Url -and -not $UrlBase64) { break }
        if ([string]::IsNullOrWhiteSpace($Url) -eq [string]::IsNullOrWhiteSpace($UrlBase64)) {
            throw 'new requires exactly one of -Url or -UrlBase64.'
        }
        # wsl.exe may route ampersands in a URL through its command parser.
        # Base64 keeps the exact user URL as one shell-neutral argument.
        $encodedUrl = if ([string]::IsNullOrWhiteSpace($UrlBase64)) {
            [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Url))
        } else {
            try {
                $null = [Convert]::FromBase64String($UrlBase64)
            } catch {
                throw '-UrlBase64 is not valid Base64.'
            }
            $UrlBase64
        }
        $cliArgs += @('--url-base64', $encodedUrl)
    }
    'source' {
        if (-not $RecordingKey -and [string]::IsNullOrWhiteSpace($VideoId)) {
            throw 'source requires the package -VideoId.'
        }
        if ([string]::IsNullOrWhiteSpace($Url)) {
            throw 'source requires -Url with the distinct YouTube or YouTube Music counterpart.'
        }
        $encodedUrl = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Url))
        # Keep a leading-hyphen YouTube ID in the same argv token as its option.
        # Otherwise argparse interprets the ID itself as another option.
        if ($VideoId) { $cliArgs += "--video-id=$VideoId" }
        $cliArgs += @('--url-base64', $encodedUrl)
    }
    'artwork' {
        if ((-not $RecordingKey -and [string]::IsNullOrWhiteSpace($VideoId)) -or [string]::IsNullOrWhiteSpace($Url)) {
            throw 'artwork requires the package -VideoId and inspected source -Url.'
        }
        $encodedUrl = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Url))
        if ($VideoId) { $cliArgs += "--video-id=$VideoId" }
        $cliArgs += @('--url-base64', $encodedUrl)
    }
    'status' {
        if (-not [string]::IsNullOrWhiteSpace($VideoId)) {
            $cliArgs += "--video-id=$VideoId"
        }
    }
    default {
        if (-not $RecordingKey -and [string]::IsNullOrWhiteSpace($VideoId)) {
            throw "$Command requires -VideoId."
        }
        if ($VideoId) { $cliArgs += "--video-id=$VideoId" }
    }
}

Invoke-StemWiseRuntime -Runtime $runtime -CommandArguments $cliArgs
exit $LASTEXITCODE
