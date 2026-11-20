param([string]$Go = 'go')
$ErrorActionPreference = 'Stop'
$Go = (Get-Command $Go -ErrorAction Stop).Source
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$work = Join-Path $PSScriptRoot ('.work/discovery-' + [guid]::NewGuid().ToString('N'))
$exact = [IO.Path]::GetFullPath($work)
$parent = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.work'))
if ([IO.Path]::GetDirectoryName($exact) -ne $parent -or [IO.Path]::GetFileName($exact) -notmatch '^discovery-[0-9a-f]{32}$') { throw 'Unsafe discovery staging path.' }
try {
    $core = Join-Path $exact 'bridge-core'
    $suite = Join-Path $exact 'test-suite'
    New-Item -ItemType Directory -Path (Join-Path $core 'protocol'),(Join-Path $suite 'bridge-core/protocol'),(Join-Path $suite '.work') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Run-Go.ps1') -Destination (Join-Path $suite 'Run-Go.ps1')
    Copy-Item -LiteralPath (Join-Path $project 'bridge-core/sdk-build-pins.json') -Destination (Join-Path $core 'sdk-build-pins.json')
    [IO.File]::WriteAllText((Join-Path $core 'go.mod'),"module phonepcbridge`n`ngo 1.27.1`n")
    [IO.File]::WriteAllText((Join-Path $core 'go.sum'),'')
    [IO.File]::WriteAllText((Join-Path $core 'protocol/value.go'),"package protocol`nfunc Value() int { return 7 }`n")
    $regression = Join-Path $suite 'bridge-core/protocol/value_test.go'
    $shell = (Get-Process -Id $PID).Path
    function InvokeFixture {
        param([switch]$Json, [string]$Run = '.')
        $format = @()
        if ($Json) { $format = @('-Json') }
        $result = & $shell -NoProfile -File (Join-Path $suite 'Run-Go.ps1') -Go $Go -Packages './protocol' -Run $Run @format 2>&1
        return @{Code=$LASTEXITCODE;Text=($result -join "`n")}
    }
    $result = InvokeFixture
    if ($result.Code -eq 0 -or $result.Text -notlike '*No suite tests discovered*') { throw 'An empty suite was not refused.' }
    [IO.File]::WriteAllText($regression,@'
package protocol
import "testing"
func TestActualRegression(t *testing.T) { if Value() != 8 { t.Fatal("actual fixture regression") } }
'@)
    $result = InvokeFixture
    if ($result.Code -eq 0 -or $result.Text -notlike '*actual fixture regression*') { throw 'The actual failing Go regression did not fail the runner.' }
    [IO.File]::WriteAllText($regression,@'
package protocol
import "testing"
func TestActualRegression(t *testing.T) { if Value() != 7 { t.Fatal("wrong production value") } }
func TestIntentionallySkipped(t *testing.T) { t.Skip("JSON event distinction fixture") }
'@)
    $result = InvokeFixture
    if ($result.Code -ne 0) { throw ('The corrected real regression did not pass: ' + $result.Text) }
    $result = InvokeFixture -Json
    if ($result.Code -ne 0) { throw ('JSON mode did not run the corrected regression: ' + $result.Text) }
    $events = @($result.Text -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    $passes = @($events | Where-Object { $_.Action -ceq 'pass' -and $_.Package -ceq 'phonepcbridge/protocol' -and $_.Test -ceq 'TestActualRegression' })
    $skips = @($events | Where-Object { $_.Action -ceq 'skip' -and $_.Test -ceq 'TestIntentionallySkipped' })
    $falsePasses = @($events | Where-Object { $_.Action -ceq 'pass' -and $_.Test -ceq 'TestIntentionallySkipped' })
    if ($passes.Count -ne 1 -or $skips.Count -ne 1 -or $falsePasses.Count -ne 0) { throw 'JSON mode lost the actual pass/skip distinction.' }
    $passingSource = [IO.File]::ReadAllText($regression)
    Add-Content -LiteralPath $regression -Value "`nfunc TestExcludedFailure(t *testing.T) { t.Fatal(`"excluded fixture regression`") }"
    $result = InvokeFixture -Json -Run '^TestActualRegression$'
    if ($result.Code -ne 0) { throw ('An exact focused selection failed: ' + $result.Text) }
    $events = @($result.Text -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    if (@($events | Where-Object { $_.Action -ceq 'pass' -and $_.Test -ceq 'TestActualRegression' }).Count -ne 1 -or @($events | Where-Object { $_.Test -ceq 'TestExcludedFailure' }).Count) { throw 'Focused selection did not execute exactly the requested test.' }
    $result = InvokeFixture
    if ($result.Code -eq 0 -or $result.Text -notlike '*excluded fixture regression*') { throw 'Default gate no longer runs the complete suite.' }
    $result = InvokeFixture -Run '^TestMissing$'
    if ($result.Code -eq 0 -or $result.Text -notlike '*No suite tests match*') { throw 'An empty focused selection became a passing gate.' }
    $result = InvokeFixture -Run '['
    if ($result.Code -eq 0 -or $result.Text -notlike '*Filtered Go discovery failed*') { throw 'An invalid Go regex was accepted.' }
    $result = InvokeFixture -Run 'TestActualRegression/missing'
    if ($result.Code -eq 0 -or $result.Text -notlike '*top-level test regex*') { throw 'An unverifiable subtest selection was accepted.' }
    [IO.File]::WriteAllText($regression,$passingSource)
    [IO.File]::WriteAllText((Join-Path $core 'protocol/value.go'),@'
package protocol
import "fmt"
func Value() int { fmt.Printf("%d", "invalid format argument"); return 7 }
'@)
    $result = InvokeFixture -Json
    if ($result.Code -eq 0 -or $result.Text -notlike '*Go vet failed*') { throw 'A real vet failure did not fail the runner.' }
    'Ordinary Go discovery rejects empty/invalid filters, executes selected tests, preserves the full default gate and propagates actual regression/vet failures; JSON mode exposes actual pass/skip events.'
} finally {
    if (Test-Path -LiteralPath $exact) {
        if ((Get-Item -LiteralPath $exact -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Discovery staging is a reparse point.' }
        if (Get-ChildItem -LiteralPath $exact -Recurse -Force -Attributes ReparsePoint | Select-Object -First 1) { throw 'Discovery staging contains a reparse point.' }
        Remove-Item -LiteralPath $exact -Recurse -Force
    }
}
