$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$temporary = Join-Path $PSScriptRoot ('.work/cleanup-' + [guid]::NewGuid().ToString('N'))
try {
    foreach ($case in @(
        @{File='bridge-core/build-sdk.ps1';Function='RemoveSdkWork';Stage='.work/sdk'}
    )) {
        $tokens=$null; $parseErrors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $project $case.File),[ref]$tokens,[ref]$parseErrors)
        if ($parseErrors.Count) { throw 'Build script does not parse.' }
        $definitions=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $case.Function},$false))
        if ($definitions.Count -ne 1) { throw 'Actual build cleanup function missing.' }
        $cleanup=$definitions[0].Body.GetScriptBlock()
        $root=Join-Path $temporary $case.Function
        $bridgeCheckRoot=$root
        $stage=Join-Path $root $case.Stage
        $work=$stage; $bridgeCheckWork=$stage
        New-Item -ItemType Directory -Path $stage -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $stage 'discard.tmp'),'disposable')
        & $cleanup
        if (Test-Path -LiteralPath $stage) { throw 'Actual build staging was retained.' }
        & $cleanup # Already absent is harmless.
        $keep=Join-Path $root 'keep'
        New-Item -ItemType Directory -Path $keep -Force | Out-Null
        $sentinel=Join-Path $keep 'untouched.tmp'
        [IO.File]::WriteAllText($sentinel,'preserve')
        $work=$keep; $bridgeCheckWork=$keep
        $failure=$null
        try { & $cleanup } catch { $failure=$_ }
        if (-not $failure -or $failure.Exception.Message -notlike 'Unsafe * cleanup path.' -or -not (Test-Path -LiteralPath $sentinel)) { throw 'Wrong cleanup target was not rejected intact.' }
        $work=$stage; $bridgeCheckWork=$stage
        New-Item -ItemType Directory -Path $stage -Force | Out-Null
        $link=Join-Path $stage 'outside'
        try {
            New-Item -ItemType Junction -Path $link -Target $keep | Out-Null
            $failure=$null
            try { & $cleanup } catch { $failure=$_ }
            if (-not $failure -or $failure.Exception.Message -notlike '* contains a reparse point.' -or -not (Test-Path -LiteralPath $sentinel)) { throw 'Linked cleanup target was not rejected intact.' }
        } finally {
            if (Test-Path -LiteralPath $link) { Remove-Item -LiteralPath $link -Force }
        }
        & $cleanup
    }
    'Actual build cleanup deletes only its staging; wrong paths and linked directories reject.'
} finally {
    $exact=[IO.Path]::GetFullPath($temporary)
    if ([IO.Path]::GetDirectoryName($exact) -ne [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.work')) -or [IO.Path]::GetFileName($exact) -notmatch '^cleanup-[0-9a-f]{32}$') { throw 'Unsafe test cleanup path.' }
    if (Test-Path -LiteralPath $exact) {
        if ((Get-Item -LiteralPath $exact -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Test staging is a reparse point.' }
        if (Get-ChildItem -LiteralPath $exact -Force -Recurse -Attributes ReparsePoint | Select-Object -First 1) { throw 'Test staging contains a reparse point.' }
        Remove-Item -LiteralPath $exact -Recurse -Force
    }
}
