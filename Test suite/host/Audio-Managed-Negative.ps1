function Invoke-AudioManagedNegativeChecks {
    param([Parameter(Mandatory = $true)][string]$Report)

    $Root = [IO.Path]::GetFullPath((Join-Path $Suite '..'))
    $Source = Join-Path $Repo 'audio-engine/src/main/java/app/stemwise/playback/engine/NativePlaybackEngineController.kt'
    $Work = [IO.Path]::GetFullPath((Join-Path (Join-Path $Suite 'work') ('audio-route-' + [guid]::NewGuid().ToString('N'))))
    $WorkPrefix = [IO.Path]::GetFullPath((Join-Path $Suite 'work')).TrimEnd('\') + '\'
    if (-not $Work.StartsWith($WorkPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Route probe escapes project work.' }
    New-Item -ItemType Directory -Path $Work -Force | Out-Null
    try {
        $Identity = Invoke-QualificationProcess (Resolve-Python) @('-B', '-c', 'import sys;sys.path.insert(0,sys.argv[1]);import audio_evidence;print(audio_evidence.source_identity())', (Join-Path $Suite 'host')) -WorkingDirectory $Root -TimeoutSeconds 30
        if ($Identity.ExitCode -ne 0) { throw 'Cannot identify managed mutation inputs.' }
        $CopiedSources = Join-Path $Work 'production-java'
        Copy-Item -LiteralPath (Join-Path $Repo 'audio-engine/src/main/java') -Destination $CopiedSources -Recurse
        $CopiedController = Join-Path $CopiedSources 'app/stemwise/playback/engine/NativePlaybackEngineController.kt'
        $Text = [IO.File]::ReadAllText($CopiedController, [Text.Encoding]::UTF8)
        # Route ingress, actor admission and physical sink choice independently fence stopped owners.
        $Fences = @(
            @('(newRouteRecoveryRequest && !runtimeStopped)', '(newRouteRecoveryRequest)'),
            @('(hasPhysicalPlayDemand() && outputPreparation == NativeOutputPreparationState.READY)',
              '(newRouteRecoveryRequest || (hasPhysicalPlayDemand() && outputPreparation == NativeOutputPreparationState.READY))'),
            @('recovery.phase == RouteRecoveryPhase.RETIRING_OUTPUT || !hasPhysicalPlayDemand()',
              'recovery.phase == RouteRecoveryPhase.RETIRING_OUTPUT')
        )
        $Mutated = $Text
        foreach ($Fence in $Fences) {
            if ([regex]::Matches($Text, [regex]::Escape($Fence[0])).Count -ne 1) {
                throw 'Stale-route mutation must match each production fence exactly once.'
            }
            $Mutated = $Mutated.Replace($Fence[0], $Fence[1])
        }
        [IO.File]::WriteAllText($CopiedController, $Mutated, [Text.UTF8Encoding]::new($false))
        $Destination = Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Report))) 'managed-negative'
        New-Item -ItemType Directory -Path $Destination -Force | Out-Null
        $Rows = @()
        $Method = 'app.stemwise.playback.engine.NativePlaybackControllerLifecycleTest.routeChangeReportedAfterTaskRemovalIsRetainedUntilTheNextOwner'
        foreach ($Phase in @('baseline', 'mutant', 'restored')) {
            $Results = Join-Path $Work $Phase
            $Init = Join-Path $Work 'results.gradle'
            $GroovyResults = $Results.Replace('\', '/').Replace("'", "\'")
            $SourceOverride = if ($Phase -eq 'mutant') {
                $GroovySources = $CopiedSources.Replace('\', '/').Replace("'", "\'")
                "p.tasks.named('compileDevelopmentKotlin').configure { t -> t.setSource(p.fileTree('$GroovySources'), p.fileTree('src/development/java'), p.fileTree(p.layout.buildDirectory.dir('generated/source/buildConfig/development'))) };"
            } else { '' }
            Set-Content -LiteralPath $Init -Encoding utf8NoBOM -Value "gradle.projectsEvaluated { def p = gradle.rootProject.project(':audio-engine'); $SourceOverride p.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { reports.junitXml.outputLocation.set(new File('$GroovyResults')) } }"
            $Log = Join-Path $Destination ($Phase + '.log')
            $Result = Invoke-QualificationProcess $Gradle @(':audio-engine:testDevelopmentUnitTest', '--tests', $Method, '--rerun', '--init-script', $Init, '--no-daemon') -WorkingDirectory $Repo -TimeoutSeconds 180 -OutputLog $Log
            if (-not (Test-Path -LiteralPath $Results)) {
                $Tail = (Get-Content -LiteralPath $Log -Encoding UTF8 -Tail 20) -join "`n"
                throw "Managed $Phase produced no JUnit execution; this is not mutation detection. Log: $Log`n$Tail"
            }
            $Files = @(Get-ChildItem -LiteralPath $Results -Filter 'TEST-*.xml' -File -ErrorAction Stop)
            if ($Files.Count -ne 1) { throw "Managed $Phase did not execute its exact production regression. Log: $Log" }
            $Xml = Join-Path $Destination ($Phase + '.xml')
            Copy-Item -LiteralPath $Files[0].FullName -Destination $Xml -Force
            $Rows += [ordered]@{ phase = $Phase; exitCode = $Result.ExitCode; xml = [ordered]@{
                path = [IO.Path]::GetRelativePath($Root, $Xml).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $Xml -Algorithm SHA256).Hash.ToLowerInvariant()
            } }
        }
    } catch { Assert-QualificationOwnership $_; throw } finally {
        Assert-QualificationOwnership
        Remove-Item -LiteralPath $Work -Recurse -Force
    }
    $After = Invoke-QualificationProcess (Resolve-Python) @('-B', '-c', 'import sys;sys.path.insert(0,sys.argv[1]);import audio_evidence;print(audio_evidence.source_identity())', (Join-Path $Suite 'host')) -WorkingDirectory $Root -TimeoutSeconds 30
    if ($After.ExitCode -ne 0 -or $After.Stdout.Trim() -cne $Identity.Stdout.Trim()) { throw 'Audio inputs changed during managed mutation execution.' }
    $Value = [ordered]@{ schemaVersion = 1; sourceSha256 = $Identity.Stdout.Trim(); required = @('stale-route-recovery'); records = $Rows }
    $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Report -Encoding utf8NoBOM
    Invoke-Checked (Resolve-Python) @('-B', (Join-Path $Suite 'host/audio_development_evidence.py'), 'managed-negative', '--report', $Report) -WorkingDirectory $Root -TimeoutSeconds 30
    foreach ($Phase in @('baseline', 'mutant', 'restored')) {
        Remove-Item -LiteralPath (Join-Path $Destination ($Phase + '.log')) -Force
    }
}
