function Invoke-AudioBoundaryChecks {
    param([Parameter(Mandatory = $true)][string]$Report)

    $WorkRoot = [IO.Path]::GetFullPath((Join-Path $Suite 'work')).TrimEnd('\') + '\'
    $ProbeRoot = [IO.Path]::GetFullPath((Join-Path $WorkRoot ('audio-boundary-' + [guid]::NewGuid().ToString('N'))))
    if (-not $ProbeRoot.StartsWith($WorkRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Boundary probe escapes work directory.' }
    New-Item -ItemType Directory -Path $ProbeRoot -Force | Out-Null
    $Records = @()
    $Cases = @(
        @{
            id = 'app-private-owner-reference'; module = 'app'; task = ':app:compileDevelopmentKotlin'
            expected = 'Cannot access.*NativePlaybackEngineController'
            source = "package boundaryprobe`nimport app.stemwise.playback.engine.NativePlaybackEngineController`ninternal fun forbidden(owner: NativePlaybackEngineController) = owner.toString()`n"
        },
        @{
            id = 'engine-app-dependency'; module = 'audio-engine'; task = ':audio-engine:compileDevelopmentKotlin'
            expected = 'Unresolved reference.*Song'
            source = "package boundaryprobe`nimport app.stemwise.model.Song`ninternal fun forbidden(song: Song) = song.toString()`n"
        }
    )
    $Failure = $null
    $CleanupFailures = [Collections.Generic.List[Exception]]::new()
    $Restored = $null
    $Warmed = $false
    try {
        $NormalTasks = @(':audio-engine:compileDevelopmentKotlin', ':app:compileDevelopmentKotlin', '--daemon')
        $Warm = Invoke-QualificationProcess -FilePath $Gradle -Arguments $NormalTasks -WorkingDirectory $Repo -TimeoutSeconds 1800
        if ($Warm.ExitCode -ne 0) { throw "Normal compilation failed before boundary probes: $($Warm.Stdout) $($Warm.Stderr)" }
        $Warmed = $true
        foreach ($Case in $Cases) {
            $SourceRoot = Join-Path $ProbeRoot $Case.id
            New-Item -ItemType Directory -Path $SourceRoot -Force | Out-Null
            Set-Content -LiteralPath (Join-Path $SourceRoot 'AudioBoundaryProbe.kt') -Value $Case.source -Encoding utf8NoBOM
            $Init = Join-Path $ProbeRoot ($Case.id + '.gradle')
            $GroovyPath = $SourceRoot.Replace('\', '/').Replace("'", "\'")
            $Script = "gradle.projectsEvaluated { gradle.rootProject.project(':$($Case.module)').android.sourceSets.getByName('main').java.srcDir('$GroovyPath') }"
            Set-Content -LiteralPath $Init -Value $Script -Encoding utf8NoBOM
            $Result = Invoke-QualificationProcess -FilePath $Gradle -Arguments @($Case.task, '--daemon', '--init-script', $Init) -WorkingDirectory $Repo -TimeoutSeconds 1800
            $Lines = ($Result.Stdout + "`n" + $Result.Stderr) -split "`r?`n"
            $Diagnostic = @($Lines | Where-Object { $_ -match 'AudioBoundaryProbe.kt' -and $_ -match $Case.expected })
            if ($Result.ExitCode -eq 0 -or -not $Diagnostic.Count) {
                throw "Audio boundary probe $($Case.id) did not fail for the intended compiler rejection: $($Result.Stdout) $($Result.Stderr)"
            }
            $Records += [ordered]@{ id = $Case.id; exitCode = $Result.ExitCode; diagnostic = $Diagnostic }
        }
    } catch { Assert-QualificationOwnership $_; $Failure = $_ } finally {
        Assert-QualificationOwnership
        # Only these injected sources/init scripts belong to this invocation.
        try {
            if (Test-Path -LiteralPath $ProbeRoot) { Remove-Item -LiteralPath $ProbeRoot -Recurse -Force }
        } catch { Assert-QualificationOwnership $_; $CleanupFailures.Add($_.Exception) }
        if ($Warmed) {
            try {
                $Restored = Invoke-QualificationProcess -FilePath $Gradle -Arguments $NormalTasks -WorkingDirectory $Repo -TimeoutSeconds 1800
                if ($Restored.ExitCode -ne 0) { throw "Normal compilation failed after boundary probe cleanup: $($Restored.Stdout) $($Restored.Stderr)" }
            } catch { Assert-QualificationOwnership $_; $CleanupFailures.Add($_.Exception) }
        }
    }
    try {
        [ordered]@{ schemaVersion = 1; records = $Records
            restoredCompileExitCode = $(if ($Restored) { $Restored.ExitCode } else { $null })
            failure = $(if ($Failure) { $Failure.Exception.Message } else { $null })
            cleanupFailures = @($CleanupFailures | ForEach-Object Message) } |
            ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Report -Encoding utf8NoBOM
    } catch { Assert-QualificationOwnership $_; $CleanupFailures.Add($_.Exception) }
    if ($CleanupFailures.Count) {
        if ($Failure) { $CleanupFailures.Insert(0, $Failure.Exception) }
        throw [AggregateException]::new('Audio boundary check and cleanup failures', $CleanupFailures.ToArray())
    }
    if ($Failure) { throw $Failure }
    Write-Host 'AE-14 module boundary: both forbidden references rejected by the production compiler; normal compilation restored.'
}
