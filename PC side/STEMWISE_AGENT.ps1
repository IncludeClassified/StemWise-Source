<#
.SYNOPSIS
Run an owner-authorized StemWise operation against an exact recording identity.
.DESCRIPTION
Use -Action Help to list current commands. Use -RecordingKey qobuz:TRACK_ID for
native recordings or -VideoId EXACT_VIDEO_ID for YouTube jobs. Durable job state
selects the next action; current policy enforces review and publication.
#>

[CmdletBinding()]
param(
    [string]$Action = 'Help',

    [string]$Url,
    [string]$VideoId,
    [string]$RecordingKey,
    [long]$MaxFileBytes,
    [switch]$ManualImport,
    [string]$ExpectedDeliverySha256,
    [string]$ExpectedAnalysisSha256,
    [switch]$Apply,
    [switch]$All,
    [switch]$Shadow,
    [string]$PreviewToken
)

. (Join-Path $PSScriptRoot 'stemwise-playlist-builder/command-spec.ps1')
if ($Action -eq 'SyllaCandidate' -and -not $Shadow) { throw 'SyllaCandidate is disabled without explicit -Shadow.' }
if ($Shadow -and $Action -ne 'SyllaCandidate') { throw '-Shadow requires SyllaCandidate.' }
if ($ManualImport -and $Action -ne 'Retry') { throw '-ManualImport requires Retry.' }
if ($Action -eq 'ReplaceAnalysis') {
    if ($Url -or ($VideoId -and $RecordingKey) -or (-not $VideoId -and -not $RecordingKey) -or
        ($VideoId -and $VideoId -cnotmatch '^[A-Za-z0-9_-]{11}$') -or
        ($RecordingKey -and $RecordingKey -cnotmatch '^qobuz:[1-9][0-9]*$') -or
        $ExpectedDeliverySha256 -cnotmatch '^[0-9a-f]{64}$' -or $ExpectedAnalysisSha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'ReplaceAnalysis requires one exact source selector and expected delivery/analysis SHA-256 values.'
    }
} elseif ($ExpectedDeliverySha256 -or $ExpectedAnalysisSha256) {
    throw 'Expected delivery/analysis hashes require ReplaceAnalysis.'
}
if ($PreviewToken -and ($Action -ne 'Reset' -or -not $Apply -or $PreviewToken -cnotmatch '^[0-9a-f]{64}$')) { throw '-PreviewToken requires Reset -Apply and an exact preview token.' }
if ($Apply -and $Action -ne 'Reset') { throw '-Apply requires Reset.' }
if ($All -and ($Action -ne 'Reset' -or $RecordingKey -or $VideoId)) { throw '-All requires Reset without a song selector.' }
if ($Action -eq 'Reprocess' -and ($Url -or ($RecordingKey -and $VideoId))) {
    throw 'Reprocess requires one exact RecordingKey or VideoId, without a URL override.'
}
$CommandSpecification = Get-StemWiseCommandSpecification
if (-not $CommandSpecification.Contains($Action)) { throw ('Unknown current action: ' + $Action) }
if ($Action -eq 'BridgeDelivery' -and
    ($VideoId -cnotmatch '\A[A-Za-z0-9_-]{11}\z' -or $RecordingKey -or $Url -or $MaxFileBytes -le 0)) {
    throw 'BridgeDelivery requires only exact -VideoId and current positive -MaxFileBytes.'
}
if ($PSBoundParameters.ContainsKey('MaxFileBytes') -and
    ($Action -notin @('New', 'Retry', 'BridgeDelivery') -or $MaxFileBytes -le 0)) {
    throw '-MaxFileBytes requires New, Retry or BridgeDelivery and a positive byte count.'
}

function Resolve-StemWiseWindowsPowerShell {
    [CmdletBinding()]
    param()

    # $PSHOME is host-specific (and can point at PowerShell 7), while this
    # workflow deliberately launches the inbox Windows PowerShell host that is
    # available on every supported Stemwise PC.
    $Candidates = New-Object System.Collections.Generic.List[string]
    foreach ($WindowsRoot in @($env:SystemRoot, $env:WINDIR)) {
        if (-not [string]::IsNullOrWhiteSpace($WindowsRoot)) {
            $Candidates.Add((Join-Path $WindowsRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'))
        }
    }
    try {
        $Discovered = (Get-Command powershell.exe -CommandType Application -ErrorAction Stop).Source
        if (-not [string]::IsNullOrWhiteSpace($Discovered)) {
            $Candidates.Add($Discovered)
        }
    } catch {
        # The explicit Windows paths above remain the preferred candidates.
    }
    foreach ($Candidate in @($Candidates | Select-Object -Unique)) {
        if (Test-Path -LiteralPath $Candidate -PathType Leaf) {
            return [IO.Path]::GetFullPath($Candidate)
        }
    }
    throw 'Windows PowerShell could not be found. Expected the inbox powershell.exe host under System32.'
}

. (Join-Path $PSScriptRoot 'stemwise-playlist-builder/atomic-json.ps1')

function Get-StemWiseActionDependencies {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Action,
        [Parameter(Mandatory = $true)][string]$AgentRoot,
        [Parameter(Mandatory = $true)][string]$Builder
    )

    # Diagnostics remain available when installation dependencies are missing.
    $Specification = Get-StemWiseCommandSpecification
    if (-not $Specification.Contains($Action)) { throw ('Unknown current action: ' + $Action) }
    $Phase = $Specification[$Action].Phase
    if ($Phase -in @('help', 'doctor')) { return @() }
    $RuntimeLayoutPath = Join-Path $AgentRoot 'runtime-layout.json'
    $RuntimeLayout = Get-Content -Raw -Encoding UTF8 -LiteralPath $RuntimeLayoutPath | ConvertFrom-Json
    if ($RuntimeLayout.schemaVersion -ne 1) { throw 'The shared Python runtime layout is invalid.' }
    foreach ($relative in @($RuntimeLayout.python.executable) + @($RuntimeLayout.environments.psobject.Properties.Value)) {
        if ($relative -notmatch '^[A-Za-z0-9_.\-/]+$' -or $relative.StartsWith('/') -or $relative -match '(^|/)\.\.(/|$)') {
            throw 'The shared Python runtime path escapes PC side.'
        }
    }
    $MainPython = Join-Path $AgentRoot $RuntimeLayout.environments.main
    $AllInOnePython = Join-Path $AgentRoot $RuntimeLayout.environments.allin1
    $LyricsPython = Join-Path $AgentRoot $RuntimeLayout.environments.lyrics
    $ModelRegistry = Join-Path $Builder 'src/model-route-registry.json'
    if ($Phase -eq 'maintenance') {
        return @((Join-Path $Builder 'runtime-bootstrap.ps1'),
            (Join-Path $Builder '.tools\bootstrap\bin\python'),
            $MainPython, (Join-Path $Builder 'config.json'),
            $(if ($Action -eq 'Reset') { Join-Path $Builder 'src\campaign_maintenance.py' }
              else { Join-Path $AgentRoot 'qobuz-comparison\audio_library.py' }))
    }

    if ($Phase -in @('analysis', 'analysis-status')) {
        $Recording = @(
            (Join-Path $Builder 'recording-analysis.ps1'),
            (Join-Path $Builder 'runtime-bootstrap.ps1'),
            (Join-Path $Builder '.tools\bootstrap\bin\python'),
            (Join-Path $Builder 'config.json'),
            (Join-Path $Builder 'src\recording_analysis.py'),
            $MainPython
        )
        if ($Action -in @('AnalyzeRecording', 'RepairRecording')) {
            $Recording += $AllInOnePython
        }
        return $Recording
    }

    $Minimal = @(
        (Join-Path $Builder 'future-song.ps1'),
        (Join-Path $Builder 'runtime-bootstrap.ps1'),
        (Join-Path $Builder '.tools\bootstrap\bin\python'),
        (Join-Path $Builder 'src\workflow_command.py'),
        (Join-Path $Builder 'config.json'),
        (Join-Path $Builder 'future-songs\policy\lyrics-policy.json'),
        $MainPython
    )
    if ($Phase -eq 'minimal') {
        return $Minimal
    }
    if ($Phase -eq 'sylla') {
        return @($Minimal + @(
            (Join-Path $Builder 'src\sylla_candidate.py'),
            (Join-Path $Builder 'src\sylla_forced_alignment.py'),
            (Join-Path $Builder 'src\sylla-model.json')
        ))
    }
    if ($Phase -eq 'recognition') {
        return @($Minimal + @(
            $LyricsPython,
            (Join-Path $Builder 'src\recording_evidence.py'),
            $ModelRegistry
        ))
    }

    $Alignment = @(
        $LyricsPython,
        (Join-Path $Builder 'src/lyrics_align_pc.py'),
        $ModelRegistry
    )
    if ($Phase -eq 'endpoint') {
        return @($Minimal + $Alignment | Select-Object -Unique)
    }

    # Acquisition uses analysis runtimes; Retry admits its actual phase from job state.
    $RuntimePythonPath = Join-Path $AgentRoot $RuntimeLayout.python.executable

    return @($Minimal + @(
        (Join-Path $Builder 'stemwise.ps1'),
        $AllInOnePython,
        $RuntimeLayoutPath,
        $RuntimePythonPath,
        $ModelRegistry
    ) | Select-Object -Unique)
}

function Start-StemWiseDetachedWorker {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$PowerShellExe,
        [Parameter(Mandatory = $true)][string]$Runner,
        [Parameter(Mandatory = $true)][string[]]$RunnerArguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Stdout,
        [Parameter(Mandatory = $true)][string]$Stderr,
        [Parameter(Mandatory = $true)][string]$Completion,
        [string]$OperationId = ([Guid]::NewGuid().ToString('N'))
    )

    if ($OperationId -cnotmatch '^[0-9a-f]{32}$') { throw 'Invalid workflow operation ID.' }

    # Start-Process is not detached from the caller's Windows job/process tree.
    # Shell supervisors therefore wait for the long worker and can discard this
    # launcher's receipt when their own deadline expires. Win32_Process.Create
    # asks the WMI service to own the supervisor process instead. The encoded
    # bootstrap keeps every path and argv item out of command-line parsing.
    $Payload = [ordered]@{
        powerShellExe = $PowerShellExe
        runner = $Runner
        arguments = @($RunnerArguments)
        workingDirectory = $WorkingDirectory
        stdout = $Stdout
        stderr = $Stderr
        completion = $Completion
        operationId = $OperationId
    }
    $PayloadJson = $Payload | ConvertTo-Json -Depth 4 -Compress
    $PayloadBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($PayloadJson))
    $Supervisor = Join-Path $PSScriptRoot 'stemwise-playlist-builder/process-supervisor.ps1'
    $CommandLine = ('"{0}" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "{1}" -PayloadBase64 {2}' -f
        $PowerShellExe, $Supervisor, $PayloadBase64)

    # Create the log paths before returning them in a receipt. Start-Process in
    # the detached supervisor will truncate and own them when its worker starts.
    $Utf8NoBom = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Stdout, '', $Utf8NoBom)
    [IO.File]::WriteAllText($Stderr, '', $Utf8NoBom)

    try {
        # Escape WMI/caller containment, then reject any remaining inherited job.
        $Startup = ([WmiClass]'Win32_ProcessStartup').CreateInstance()
        $Startup.CreateFlags = 0x01000000 # CREATE_BREAKAWAY_FROM_JOB
        $CreateResult = ([WmiClass]'Win32_Process').Create($CommandLine, $WorkingDirectory, $Startup)
    } catch {
        Remove-Item -LiteralPath $Stdout, $Stderr -Force -ErrorAction SilentlyContinue
        throw "Could not ask Windows to start the detached Stemwise worker: $($_.Exception.Message)"
    }
    if ([int]$CreateResult.ReturnValue -ne 0 -or [int]$CreateResult.ProcessId -le 0) {
        Remove-Item -LiteralPath $Stdout, $Stderr -Force -ErrorAction SilentlyContinue
        throw "Windows could not start the detached Stemwise worker (Win32 error $($CreateResult.ReturnValue))."
    }

    [pscustomobject]@{
        ProcessId = [int]$CreateResult.ProcessId
    }
}

function Resolve-StemWiseWatchVideoId {
    param([Parameter(Mandatory = $true)][string]$Value)

    $Candidate = $Value.Trim()
    if ($Candidate -match '[\x00-\x1f\x7f]') {
        throw 'New requires one supported YouTube song route without embedded control characters.'
    }
    if ($Candidate -match '\A[A-Za-z0-9_-]{11}\z') {
        return $Candidate
    }
    $Parsed = $null
    if (-not [Uri]::TryCreate($Candidate, [UriKind]::Absolute, [ref]$Parsed)) {
        throw 'New requires a valid absolute YouTube or YouTube Music watch URL.'
    }
    if ($Parsed.Scheme -ne 'https' -or $Parsed.UserInfo -or $Candidate -match '^https://[^/?#]+:[0-9]+(?:[/?#]|$)') {
        throw 'New requires an exact HTTPS YouTube song URL without credentials or a custom port.'
    }
    $HostName = $Parsed.DnsSafeHost.ToLowerInvariant()
    if ($HostName -notin @('youtu.be', 'youtube.com', 'www.youtube.com', 'music.youtube.com', 'm.youtube.com')) {
        throw 'New accepts only youtube.com, music.youtube.com, or youtu.be song URLs.'
    }
    $RawPath = [regex]::Match($Candidate, '(?i)^https://[^/?#]+(?<path>/[^?#]*)').Groups['path'].Value
    $QueryIds = @(
        foreach ($Pair in $Parsed.Query.TrimStart('?').Split('&')) {
            $Equal = $Pair.IndexOf('=')
            $Key = if ($Equal -ge 0) { $Pair.Substring(0, $Equal) } else { $Pair }
            if ([Uri]::UnescapeDataString($Key.Replace('+', ' ')) -ceq 'v') {
                $RawValue = if ($Equal -ge 0) { $Pair.Substring($Equal + 1) } else { '' }
                [Uri]::UnescapeDataString($RawValue.Replace('+', ' '))
            }
        }
    )
    if ($QueryIds.Count -gt 1) { throw 'The URL contains duplicate video IDs.' }
    $Identity = $null
    if ($HostName -eq 'youtu.be' -and $RawPath -cmatch '\A/([A-Za-z0-9_-]{11})\z') {
        $Identity = $Matches[1]
    }
    if ($HostName -ne 'youtu.be') {
        if ($RawPath -ceq '/watch' -and $QueryIds.Count -eq 1) { $Identity = $QueryIds[0] }
        if ($RawPath.Trim('/') -cmatch '\A(shorts|embed|live)/([A-Za-z0-9_-]{11})\z') { $Identity = $Matches[2] }
    }
    if ($Identity -cnotmatch '\A[A-Za-z0-9_-]{11}\z' -or
        ($QueryIds.Count -eq 1 -and $QueryIds[0] -cne $Identity)) {
        throw 'New requires one supported YouTube song route without conflicting video IDs.'
    }
    return $Identity
}

# Tests dot-source this script to exercise the real detached launcher without
# dispatching a Stemwise action. Normal documented invocation is never dotted.
if ($MyInvocation.InvocationName -eq '.') {
    return
}

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$LauncherPath = [IO.Path]::GetFullPath($MyInvocation.MyCommand.Path)
$AgentRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Builder = Join-Path $AgentRoot 'stemwise-playlist-builder'
$FutureRunner = Join-Path $Builder 'future-song.ps1'
$CoreRunner = Join-Path $Builder 'stemwise.ps1'
$LyricsAlignRunner = Join-Path $Builder 'lyrics-align.ps1'
$PolicyStateName = 'current'
$FutureStateRoot = Join-Path $Builder "future-songs\policy-state\$PolicyStateName"
$Jobs = Join-Path $FutureStateRoot 'jobs'
$NewVideoId = $null

if ($Action -in @('AnalyzeRecording', 'RepairRecording', 'RecordingStatus')) {
    if ($RecordingKey -cnotmatch '^qobuz:[1-9][0-9]*$' -or $VideoId -or $Url) {
        throw 'Recording actions require only -RecordingKey qobuz:TRACK_ID.'
    }
    $FutureRunner = Join-Path $Builder 'recording-analysis.ps1'
    $FutureStateRoot = Join-Path $AgentRoot 'generated-packages\recording-analysis'
} elseif ($RecordingKey) {
    if ($RecordingKey -cnotmatch '^qobuz:[1-9][0-9]*$' -or $VideoId -or
        (-not $CommandSpecification[$Action].Recording -and $Action -ne 'Reset') -or
        ($Url -and $Action -notin @('Source', 'Artwork'))) {
        throw 'Recording workflow actions require -RecordingKey qobuz:TRACK_ID; only Source/Artwork also accept -Url.'
    }
    $FutureStateRoot = Join-Path $AgentRoot 'generated-packages\recording-enrichment'
}

if ($Action -eq 'Help') {
    Write-Output ('Actions: ' + ($CommandSpecification.Keys -join ', '))
    Write-Output 'Select an exact RecordingKey or VideoId; Status and Task show the next action.'
    Write-Output 'SyllaCandidate -Shadow explicitly retains private evaluation only; it cannot corroborate or publish timing.'
    Write-Output 'ReplaceAnalysis stages only an explicitly authorized completed missing-PCM replacement; exact expected delivery/analysis hashes are required. Retry continues fresh analysis and review.'
    Write-Output 'Plan refreshes selection only. Reset previews selected public ZIP removal; -Apply requires the returned -PreviewToken and one song selector or explicit -All. Reprocess runs one existing recovery stage for an exact recording.'
    exit 0
}

if ($Action -in @('Plan', 'Reset')) {
    if ($Url -or $ManualImport -or $PSBoundParameters.ContainsKey('MaxFileBytes') -or
        ($RecordingKey -and $VideoId) -or ($Action -eq 'Plan' -and ($RecordingKey -or $VideoId))) {
        throw 'Plan takes no song selector; Reset accepts one exact RecordingKey or VideoId, or -All, and optional -Apply.'
    }
    if ($Action -eq 'Reset' -and $Apply -and -not ($RecordingKey -or $VideoId -or $All)) {
        throw 'Reset -Apply requires one exact RecordingKey or VideoId, or explicit -All.'
    }
    if ($Action -eq 'Reset' -and $Apply -and -not $PreviewToken) { throw 'Reset -Apply requires -PreviewToken from previewing the same selector.' }
    . (Join-Path $Builder 'runtime-bootstrap.ps1')
    $Runtime = Get-StemWiseRuntime -BuilderWindows $Builder
    $MaintenanceArguments = if ($Action -eq 'Plan') {
        @('-m', 'stemwise_media.audio_library', 'plan')
    } else {
        @('-m', 'stemwise_engine.campaign_maintenance', '--config', ($Runtime.Builder + '/config.json'))
    }
    if ($RecordingKey) { $MaintenanceArguments += @('--recording-key', $RecordingKey) }
    if ($VideoId) { $MaintenanceArguments += "--video-id=$VideoId" }
    if ($Apply) { $MaintenanceArguments += @('--apply', '--preview-token', $PreviewToken) }
    if ($All) { $MaintenanceArguments += '--all' }
    Invoke-StemWiseRuntime -Runtime $Runtime -CommandArguments $MaintenanceArguments
    exit $LASTEXITCODE
}

if ($Action -eq 'Task' -and -not $RecordingKey -and $VideoId -cnotmatch '\A[A-Za-z0-9_-]{11}\z') {
    throw 'Task requires an exact case-sensitive 11-character YouTube video ID.'
}

$WindowsPowerShell = Resolve-StemWiseWindowsPowerShell

if ($Action -eq 'Doctor') {
    # Doctor is itself the diagnostic and must remain runnable when canonical
    # components are missing. Report every launcher-visible defect, then run
    # each doctor that is present so its own detailed checks are not hidden.
    $DoctorFailed = $false
    try {
        $DoctorRequired = Get-StemWiseActionDependencies `
            -Action New -AgentRoot $AgentRoot -Builder $Builder
    } catch {
        [Console]::Error.WriteLine('Runtime dependency layout could not be read: ' + $_.Exception.Message)
        $DoctorFailed = $true
        $DoctorRequired = @()
    }
    foreach ($Path in $DoctorRequired) {
        if (-not (Test-Path -LiteralPath $Path)) {
            [Console]::Error.WriteLine("Canonical Stemwise dependency is missing: $Path")
            $DoctorFailed = $true
        }
    }

    foreach ($DoctorRunner in @($CoreRunner, $LyricsAlignRunner)) {
        if (-not (Test-Path -LiteralPath $DoctorRunner -PathType Leaf)) {
            [Console]::Error.WriteLine("Doctor runner is missing: $DoctorRunner")
            $DoctorFailed = $true
            continue
        }
        & $WindowsPowerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $DoctorRunner doctor
        if ($LASTEXITCODE -ne 0) {
            $DoctorFailed = $true
        }
    }
    if ($DoctorFailed) {
        [Console]::Error.WriteLine('Stemwise doctor found one or more readiness defects.')
        exit 1
    }
    Write-Output 'Stemwise doctor passed.'
    exit 0
}

$RequiredFiles = Get-StemWiseActionDependencies `
    -Action $Action -AgentRoot $AgentRoot -Builder $Builder
if ($RecordingKey -and $Action -notin @('AnalyzeRecording', 'RepairRecording', 'RecordingStatus')) {
    $RequiredFiles += @((Join-Path $Builder 'src\recording_workflow.py'),
                       (Join-Path $Builder 'src\recording_source.py'),
                       (Join-Path $Builder 'src\recording_analysis.py'))
}
foreach ($Path in $RequiredFiles) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Canonical Stemwise dependency is missing for ${Action}: $Path"
    }
}

$RunnerArguments = @($CommandSpecification[$Action].Command)
if ($Shadow) { $RunnerArguments += '-Shadow' }
if ($PSBoundParameters.ContainsKey('MaxFileBytes')) { $RunnerArguments += @('-MaxFileBytes', $MaxFileBytes) }
if ($ManualImport) { $RunnerArguments += '-ManualImport' }
if ($Action -eq 'ReplaceAnalysis') {
    $RunnerArguments += @('-ExpectedDeliverySha256', $ExpectedDeliverySha256, '-ExpectedAnalysisSha256', $ExpectedAnalysisSha256)
}
if ($RecordingKey -and $Action -notin @('AnalyzeRecording', 'RepairRecording', 'RecordingStatus')) {
    $RunnerArguments += @('-RecordingKey', $RecordingKey)
    if ($Action -in @('Source', 'Artwork')) {
        if (-not $Url) { throw "$Action requires the exact reference -Url." }
        $RunnerArguments += @('-Url', $Url)
    }
} else {
switch ($Action) {
    'AnalyzeRecording' {
        $RunnerArguments = @('analyze', '-RecordingKey', $RecordingKey)
    }
    'RepairRecording' {
        $RunnerArguments = @('repair', '-RecordingKey', $RecordingKey)
    }
    'RecordingStatus' {
        $RunnerArguments = @('status', '-RecordingKey', $RecordingKey)
    }
    'New' {
        if ([string]::IsNullOrWhiteSpace($Url)) {
            throw 'New requires -Url with the exact song URL used by Stemwise.'
        }
        $NewVideoId = Resolve-StemWiseWatchVideoId -Value $Url
        $RunnerArguments += @('-Url', $Url)
    }
    'Source' {
        if ([string]::IsNullOrWhiteSpace($VideoId) -or [string]::IsNullOrWhiteSpace($Url)) {
            throw 'Source requires the package -VideoId and exact counterpart -Url.'
        }
        $RunnerArguments += @('-VideoId', $VideoId, '-Url', $Url)
    }
    'Artwork' {
        if ([string]::IsNullOrWhiteSpace($VideoId) -or [string]::IsNullOrWhiteSpace($Url)) {
            throw 'Artwork requires the package -VideoId and exact inspected source -Url.'
        }
        $RunnerArguments += @('-VideoId', $VideoId, '-Url', $Url)
    }
    'Status' {
        if (-not [string]::IsNullOrWhiteSpace($VideoId)) {
            $RunnerArguments += @('-VideoId', $VideoId)
        }
    }
    default {
        if ([string]::IsNullOrWhiteSpace($VideoId)) {
            throw "$Action requires -VideoId."
        }
        $RunnerArguments += @('-VideoId', $VideoId)
    }
}
}

if ($Action -in @('New', 'Retry', 'ReplaceAnalysis', 'Validate', 'Finalize', 'RecordingEvidence', 'SyllaCandidate', 'EndpointAdjudicate', 'AnalyzeRecording', 'RepairRecording', 'Reprocess')) {
    $LaunchToken = [Guid]::NewGuid().ToString('N')
    $TelemetryVideoId = if ($Action -eq 'New') { $NewVideoId } else { $VideoId }
    $JobSlug = if ($RecordingKey) {
        $RecordingKey.Replace(':', '-')
    } elseif ($TelemetryVideoId -cmatch '\A[A-Za-z0-9_-]{11}\z') {
        "youtube-$TelemetryVideoId"
    } else { throw 'Telemetry requires an exact source identity.' }
    $TelemetryRoot = Join-Path (Split-Path -Parent $AgentRoot) 'Engine telemetry'
    $BackgroundRoot = Join-Path (Join-Path $TelemetryRoot $JobSlug) "operations\$LaunchToken"
    New-Item -ItemType Directory -Force -Path $BackgroundRoot | Out-Null
    $Stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $LaunchStem = "$Stamp-$LaunchToken"
    $Stdout = Join-Path $BackgroundRoot "$LaunchStem.stdout.log"
    $Stderr = Join-Path $BackgroundRoot "$LaunchStem.stderr.log"
    $Record = Join-Path $BackgroundRoot "$LaunchStem.launch.json"
    $Completion = Join-Path $BackgroundRoot "$LaunchStem.completion.json"

    if ($Action -eq 'New' -and -not $RecordingKey) {
        $EncodedUrl = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Url))
        $RunnerArguments = @('new', '-UrlBase64', $EncodedUrl)
        if ($PSBoundParameters.ContainsKey('MaxFileBytes')) { $RunnerArguments += @('-MaxFileBytes', $MaxFileBytes) }
    }

    $StatusVideoId = if ($RecordingKey) { $RecordingKey } elseif ($Action -eq 'New') { $NewVideoId } else { $VideoId }
    $EscapedLauncherPath = $LauncherPath.Replace("'", "''")
    $EscapedStatusVideoId = $StatusVideoId.Replace("'", "''")
    $Launch = [ordered]@{
        formatVersion = 3
        operationId = $LaunchToken
        launchState = 'pending'
        action = $Action
        videoId = $StatusVideoId
        processId = $null
        processStartTimeUtc = $null
        startedAt = [DateTime]::UtcNow.ToString('o')
        workingDirectory = $Builder
        powerShellExe = $WindowsPowerShell
        stdout = $Stdout
        stderr = $Stderr
        record = $Record
        completion = $Completion
        statusCommand = if ($Action -in @('AnalyzeRecording', 'RepairRecording')) {
            "& '$EscapedLauncherPath' -Action RecordingStatus -RecordingKey '$EscapedStatusVideoId'"
        } elseif ($RecordingKey) {
            "& '$EscapedLauncherPath' -Action Status -RecordingKey '$EscapedStatusVideoId'"
        } else {
            "& '$EscapedLauncherPath' -Action Status -VideoId '$EscapedStatusVideoId'"
        }
    }
    if ($RecordingKey) {
        $Launch.videoId = $null
        $Launch.recordingKey = $RecordingKey
    }

    # Persist the complete launch intent before creating any process. If the
    # launcher is interrupted after Win32_Process.Create but before returning a
    # receipt, this record still identifies the action and every output path.
    Write-StemWiseJsonAtomic -Path $Record -Value $Launch
    try {
        $Process = Start-StemWiseDetachedWorker `
            -PowerShellExe $WindowsPowerShell `
            -Runner $FutureRunner `
            -RunnerArguments $RunnerArguments `
            -WorkingDirectory $Builder `
            -Stdout $Stdout `
            -Stderr $Stderr `
            -Completion $Completion `
            -OperationId $LaunchToken
    } catch {
        $Launch['launchState'] = 'failed'
        $Launch['failedAt'] = [DateTime]::UtcNow.ToString('o')
        $Launch['launchError'] = $_.Exception.Message
        Write-StemWiseJsonAtomic -Path $Record -Value $Launch
        throw
    }

    # Commit the WMI-owned supervisor PID immediately; process metadata is a
    # useful enrichment, but must not widen the pending-record crash window.
    $Launch['launchState'] = 'launched'
    $Launch['processId'] = $Process.ProcessId
    $Launch['launchedAt'] = [DateTime]::UtcNow.ToString('o')
    try {
        Write-StemWiseJsonAtomic -Path $Record -Value $Launch
    } catch {
        # The durable pending intent already names every private output path.
        # Never suppress the caller's immediate receipt after WMI successfully
        # created the worker merely because the PID enrichment could not be
        # persisted on this attempt.
        $Launch['recordWriteWarning'] = $_.Exception.Message
    }

    $ProcessStartTimeUtc = $null
    try {
        $ProcessStartTimeUtc = (Get-Process -Id $Process.ProcessId -ErrorAction Stop).StartTime.ToUniversalTime().ToString('o')
    } catch {
        # A very short-lived failed child can exit before StartTime is read.
    }
    if (-not [string]::IsNullOrWhiteSpace($ProcessStartTimeUtc)) {
        $Launch['processStartTimeUtc'] = $ProcessStartTimeUtc
        try {
            Write-StemWiseJsonAtomic -Path $Record -Value $Launch
        } catch {
            $Launch['recordWriteWarning'] = $_.Exception.Message
        }
    }
    $Launch | ConvertTo-Json -Depth 6
    exit 0
}

& $WindowsPowerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $FutureRunner @RunnerArguments
exit $LASTEXITCODE
