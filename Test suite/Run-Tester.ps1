#requires -Version 7.2
[CmdletBinding()]
param(
    [ValidateSet('Host', 'Native', 'Android', 'Lint', 'Build', 'DeviceValidate', 'Device', 'Actions', 'Full', 'Focused', 'NativeDevice', 'Pc', 'PcPlan', 'CleanCheckout', 'AudioPlan', 'AudioCheck', 'AudioQualify', 'PcSuite', 'AndroidSuite', 'AudioAggregate')]
    [string]$Mode = 'Host',
    [string]$Serial,
    [string[]]$Class = @(),
    [string[]]$PcTest = @(),
    [string[]]$NativeTest = @(),
    [ValidateSet('All', 'ordinary', 'explicit-suite', 'lifecycle-phase', 'fatal-two-process', 'native-process-fault', 'runtime-update')]
    [string]$DeviceLane = 'All',
    [int]$PressureSeed = 0,
    [int]$PressureOperations = 0,
    [string]$RunId,
    [string]$DeviceProfile,
    [ValidateSet('active-channels', 'merger-math')]
    [string]$NativeCase = 'active-channels',
    [switch]$ProfilePerformance,
    [switch]$PresentationReports,
    [switch]$CompileAndroid,
    [switch]$AndroidFirst
)

$ErrorActionPreference = 'Stop'
$Mode = ($MyInvocation.MyCommand.Parameters['Mode'].Attributes |
    Where-Object { $_ -is [Management.Automation.ValidateSetAttribute] }).ValidValues | Where-Object { $_ -eq $Mode }
if ($NativeTest.Count -and $Mode -ne 'Native') { throw '-NativeTest requires Native mode; Full always runs every native check.' }
if ($CompileAndroid -and $Mode -ne 'CleanCheckout') { throw '-CompileAndroid requires CleanCheckout mode.' }
if ($AndroidFirst -and $Mode -ne 'Full') { throw '-AndroidFirst requires Full mode.' }
if ($Mode -cin @('Full', 'PcSuite', 'AndroidSuite', 'AudioQualify') -and
        ($Class.Count -or $PcTest.Count -or $DeviceLane -cne 'All')) {
    throw 'Complete suite commands do not accept partial selections; use Pc, Android, Device or Focused.'
}
. (Join-Path $PSScriptRoot 'host/Owned-Process.ps1')
if ($ProfilePerformance -and $Mode -ne 'Focused') { throw '-ProfilePerformance requires Focused mode.' }
$Suite = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
$LiveRoot = [IO.Path]::GetFullPath((Join-Path $Suite '..')).TrimEnd('\')
$Repo = Join-Path $LiveRoot 'StemWise'
$PcBuilder = Join-Path $LiveRoot 'PC side\stemwise-playlist-builder'
$Gradle = Join-Path $Repo 'gradlew.bat'
$DevelopmentPackage = 'app.stemwise.development'
$ActionSuiteClass = 'app.stemwise.qualification.AppActionSurfaceSuite'
$DeviceInventory = Join-Path $Suite 'device\device_inventory.py'
$HostInventory = Join-Path $Suite 'host\host_inventory.py'
$DeviceLaneManifest = Join-Path $Suite 'device\device-lanes.json'
$QaProviderApkOutput = Join-Path $Repo 'qa-provider\build\outputs\apk\development'
$QaProviderPackage = 'app.stemwise.testprovider'
$QaProviderAuthority = 'app.stemwise.testprovider.documents'
$QaProviderTreeUri = "content://$QaProviderAuthority/tree/root"
$QaProviderAlternateTreeUri = "content://$QaProviderAuthority/tree/alternate"
$QualificationBuildType = 'development'
$DevelopmentApkOutput = Join-Path $Repo "app/build/outputs/apk/$QualificationBuildType"
$DevelopmentTestApkOutput = Join-Path $Repo "app/build/outputs/apk/androidTest/$QualificationBuildType"
$ReleaseCertificateSha256 = $null
$QualificationCertificateSha256 = $null
$QualificationSigningLoaded = $false
$script:CurrentDeviceProfileFile = $null
$StandardInstrumentationComponent =
    'app.stemwise.development.test/app.stemwise.qualification.DevelopmentStandardInstrumentationRunner'
$RequiredHostQualificationMethods = @(
    'app.stemwise.integration.PortableSourceJobAuthorityTest#recoveredProcessingJobCannotSubmitAgain',
    'app.stemwise.integration.PortableSourceJobAuthorityTest#frozenPlanSurvivesPrivateDatabaseLossWithTheExactAcknowledgedRemoteJob',
    'app.stemwise.integration.PortableSourceJobAuthorityTest#unacknowledgedRestartCannotDispatchAgainOrManufactureCompletion'
)
$ConnectedDeviceResults =
    Join-Path $Repo "app/build/outputs/androidTest-results/connected/$QualificationBuildType"
$ConnectedDeviceReports =
    Join-Path $Repo "app/build/reports/androidTests/connected/$QualificationBuildType"
$script:DeviceRunEvidenceRoot = $null
$script:DeviceSelectionSequence = 0
$script:DeviceLaneResults = [Collections.Generic.List[object]]::new()
$script:DeviceInventoryAccount = $null
$script:ObservedPassingMethods = [Collections.Generic.HashSet[string]]::new()
$script:ObservedSkippedMethods = [Collections.Generic.HashSet[string]]::new()
$script:ValidatedDeviceProfile = $null
$script:DeviceArtifacts = [Collections.Generic.List[object]]::new()
$script:HostQualification = $null
$script:FrozenArtifacts = @{}
$script:QualificationSourceSha256 = $null
$script:QualificationSourceManifest = $null
$script:ProducerSystemSha256 = $null

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [ValidateRange(1, 86400)][int]$TimeoutSeconds = 1800,
        [string]$Phase = 'process'
    )
    $Clock = [Diagnostics.Stopwatch]::StartNew()
    $OutputRoot = if ($script:DeviceRunEvidenceRoot) { $script:DeviceRunEvidenceRoot } else { Join-Path $Suite 'work' }
    $OutputLog = Join-Path $OutputRoot ('command-' + [guid]::NewGuid().ToString('N') + '.log')
    try {
        $Result = Invoke-QualificationProcess -FilePath $FilePath -Arguments $Arguments `
            -WorkingDirectory $WorkingDirectory -TimeoutSeconds $TimeoutSeconds -OutputLog $OutputLog
    } catch {
        Write-Host "Command output: $OutputLog"
        foreach ($Stream in @('stdout','stderr')) { if ($_.Exception.Data[$Stream]) { Write-Host $_.Exception.Data[$Stream] } }
        throw
    } finally {
        $Clock.Stop()
        Write-Host ('TESTER_PHASE ' + ([ordered]@{phase=$Phase; executable=[IO.Path]::GetFileName($FilePath); seconds=$Clock.Elapsed.TotalSeconds} | ConvertTo-Json -Compress))
    }
    if ($Result.Stdout) { Write-Host $Result.Stdout }
    if ($Result.Stderr) { Write-Host $Result.Stderr }
    if ($Result.ExitCode -ne 0) {
        $Failure = New-QualificationException "$FilePath failed with exit code $($Result.ExitCode)." $null 'test-failure'
        $Failure.Data['stdout'] = $Result.Stdout
        $Failure.Data['stderr'] = $Result.Stderr
        throw $Failure
    }
    if ($Result.OutputTruncated) {
        Write-Host "Full command output retained at $OutputLog; displayed streams contain their bounded tails."
    } elseif (Test-Path -LiteralPath $OutputLog) {
        try { Remove-Item -LiteralPath $OutputLog -Force -ErrorAction Stop }
        catch { Write-Warning "Disposable command output remains at ${OutputLog}: $($_.Exception.Message)" }
    }
}

function Get-TestRuntimeLayout {
    . (Join-Path $PcBuilder 'runtime-bootstrap.ps1')
    Get-StemWiseRuntimeLayout -BuilderWindows $PcBuilder
}

function Convert-ToWslPath([string]$Path) {
    $Distribution = (Get-TestRuntimeLayout).wslDistribution
    $Result = Invoke-QualificationProcess 'wsl.exe' @('-d',$Distribution,'--','wslpath','-a',($Path -replace '\\', '/')) -TimeoutSeconds 30
    $WslPath = $Result.Stdout.Trim()
    if ($Result.ExitCode -ne 0 -or -not $WslPath) { throw "Could not map this path into WSL: $Path; $($Result.Stderr)" }
    return $WslPath
}

function Get-PcTestPlan([string[]]$Selections = @(), [switch]$PerModule) {
    . (Join-Path $PcBuilder 'runtime-bootstrap.ps1')
    $Distribution = (Get-TestRuntimeLayout).wslDistribution
    $BuilderWsl = Convert-ToWslPath $PcBuilder
    $SuiteWsl = Convert-ToWslPath $Suite
    $PlanOptions = if ($PerModule) { @('--per-module') } else { @() }
    $Inventory = Invoke-QualificationProcess 'wsl.exe' (@('-d',$Distribution,'--','/usr/bin/python3',
        '-B', "$SuiteWsl/pc/test_plan.py") + $PlanOptions + $Selections) -WorkingDirectory $LiveRoot -TimeoutSeconds 30
    if ($Inventory.ExitCode -ne 0) { throw "PC test classification failed: $($Inventory.Stderr)" }
    $Groups = ConvertFrom-Json -InputObject $Inventory.Stdout
    foreach ($Group in $Groups) {
        if ($Group.Runtime -eq 'windows') {
            $Interpreter = (Get-Command python.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
            $Argv = @('-B', (Join-Path $Suite 'pc/run_tests.py'), '--directory', (Join-Path $Suite "pc/$($Group.directory)")) + $Group.selections
            $FilePath = $Interpreter
            $CommandArguments = $Argv
            $WorkingDirectory = $LiveRoot
        } else {
            $DirectoryWsl = "$SuiteWsl/pc/$($Group.directory)"
            $Runtime = Get-StemWiseRuntime -BuilderWindows $PcBuilder -Route $Group.Runtime -SkipInstalledCheck
            $Interpreter = $Runtime.Python
            $Argv = @('-B', "$SuiteWsl/pc/run_tests.py", '--directory', $DirectoryWsl) + $Group.selections
            $FilePath = 'wsl.exe'
            $CommandArguments = @('-d', $Runtime.Distribution, '--cd', $BuilderWsl, '--', 'env') +
                @($Runtime.Environment) + @($Runtime.BootstrapPython, '-B', '-m', 'stemwise_engine.runtime_environment', $Interpreter) + $Argv
            $WorkingDirectory = $BuilderWsl
        }
        $Command = "& '" + $FilePath.Replace("'", "''") + "' " + (($CommandArguments | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ' ')
        [pscustomobject]@{
            Runtime = $Group.Runtime; WorkingDirectory = $WorkingDirectory; Interpreter = $Interpreter
            FilePath = $FilePath; Arguments = $CommandArguments; Argv = $Argv; Command = $Command
        }
    }
}

function Invoke-PcTests([object[]]$Plans = @(Get-PcTestPlan -PerModule)) {
    $Failures = [Collections.Generic.List[Exception]]::new()
    foreach ($Plan in $Plans) {
        try {
            Invoke-Checked $Plan.FilePath $Plan.Arguments $LiveRoot -Phase 'pc-runtime-and-tests'
        } catch {
            $Cause = $_.Exception
            while ($Cause.InnerException -and -not $Cause.Data['failureKind']) { $Cause = $Cause.InnerException }
            # Only an explicitly classified test failure proves the process retired safely.
            if ((Get-QualificationFailureKind $_) -ne 'test-failure' -or
                $Cause.Data['failureKind'] -ne 'test-failure') { throw }
            $Selector = $Plan.Arguments[-1]
            $Failures.Add((New-QualificationException "$($Plan.Runtime)/${Selector}: $($_.Exception.Message)" $_ 'test-failure'))
        }
    }
    if ($Failures.Count) {
        throw (New-QualificationException "PC module failures: $(($Failures | ForEach-Object Message) -join ' | ')" (
            [AggregateException]::new($Failures.ToArray())
        ) 'test-failure')
    }
}

function Resolve-NativeTools {
    $ProgramFilesX86 = [Environment]::GetFolderPath(
        [Environment+SpecialFolder]::ProgramFilesX86
    )
    $VsWhere = Join-Path $ProgramFilesX86 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path -LiteralPath $VsWhere -PathType Leaf)) {
        throw 'Visual Studio Build Tools are required for the native tests.'
    }
    $Result = Invoke-QualificationProcess $VsWhere @('-latest','-products','*','-requires','Microsoft.VisualStudio.Component.VC.Tools.x86.x64','-property','installationPath') -TimeoutSeconds 30
    if ($Result.ExitCode -ne 0) { throw 'Visual Studio inventory failed.' }
    $Installation = $Result.Stdout.Trim()
    if (-not $Installation) { throw 'No MSVC x64 installation was found.' }
    $Bundled = Join-Path $Installation 'Common7\IDE\CommonExtensions\Microsoft\CMake'
    return [pscustomobject]@{
        DevCmd = Join-Path $Installation 'Common7\Tools\VsDevCmd.bat'
        CMake = Join-Path $Bundled 'CMake\bin\cmake.exe'
        CTest = Join-Path $Bundled 'CMake\bin\ctest.exe'
        Ninja = Join-Path $Bundled 'Ninja\ninja.exe'
    }
}

function Import-MsvcEnvironment([string]$DevCmd) {
    $CommandLine = 'chcp 65001 >nul & call "' + $DevCmd + '" -no_logo -arch=x64 -host_arch=x64 >nul && set'
    $Result = Invoke-QualificationProcess $env:ComSpec @('/d','/s','/c',$CommandLine) -TimeoutSeconds 120
    $Lines = $Result.Stdout -split "`r?`n"
    if ($Result.ExitCode -ne 0) { throw 'MSVC environment initialization failed.' }
    foreach ($Line in $Lines) {
        $Separator = $Line.IndexOf('=')
        if ($Separator -gt 0) {
            [Environment]::SetEnvironmentVariable(
                $Line.Substring(0, $Separator),
                $Line.Substring($Separator + 1),
                'Process'
            )
        }
    }
}

function Invoke-NativeTests {
    param([switch]$PlanOnly, [string]$Report)
    $Tools = Resolve-NativeTools
    foreach ($Tool in @($Tools.DevCmd, $Tools.CMake, $Tools.CTest, $Tools.Ninja)) {
        if (-not (Test-Path -LiteralPath $Tool -PathType Leaf)) {
            throw "Required native test tool is missing: $Tool"
        }
    }
    Import-MsvcEnvironment $Tools.DevCmd
    $Build = Join-Path $Suite 'work\native-host'
    Invoke-Checked $Tools.CMake @(
        '-S', (Join-Path $Repo 'audio-engine\src\main\cpp'),
        '-B', $Build,
        '-G', 'Ninja',
        '-DCMAKE_BUILD_TYPE=Release',
        "-DCMAKE_MAKE_PROGRAM=$($Tools.Ninja)"
    ) $Repo -Phase 'native-configure'
    $TestArguments = @('--test-dir', $Build, '--output-on-failure', '--no-tests=error')
    if ($PlanOnly) {
        # CTest omits commands for executables that have not been built.
        Invoke-Checked $Tools.CMake @('--build', $Build, '--parallel') $Repo -Phase 'native-discovery-build'
        $Inventory = Invoke-QualificationProcess $Tools.CTest @('--test-dir', $Build, '--show-only=json-v1') -WorkingDirectory $Repo -TimeoutSeconds 30
        if ($Inventory.ExitCode -ne 0) { throw "Native test discovery failed: $($Inventory.Stderr)" }
        [IO.File]::WriteAllText($Report, $Inventory.Stdout, [Text.UTF8Encoding]::new($false))
        return
    }
    if ($Report) { $TestArguments += @('--output-junit', $Report) }
    if ($NativeTest.Count) {
        $Inventory = Invoke-QualificationProcess $Tools.CTest @(
            '--test-dir', $Build, '--show-only=json-v1'
        ) -WorkingDirectory $Repo -TimeoutSeconds 30
        if ($Inventory.ExitCode -ne 0) { throw "Native test inventory failed: $($Inventory.Stderr)" }
        $Registered = ConvertFrom-Json -InputObject $Inventory.Stdout
        $Unknown = @($NativeTest | Where-Object { $_ -cnotin $Registered.tests.name })
        if ($Unknown.Count) { throw "Unknown exact native tests: $($Unknown -join ', ')" }
        $Selected = @($NativeTest | Sort-Object -Unique -CaseSensitive)
        $Pattern = '^(' + (($Selected | ForEach-Object { [Regex]::Escape($_) }) -join '|') + ')$'
        $TestArguments += @('--tests-regex', $Pattern)
        Write-Host "Selected native tests: $($Selected -join ', ')"
    }
    Invoke-Checked $Tools.CMake @('--build', $Build, '--parallel') $Repo -Phase 'native-build'
    Invoke-Checked $Tools.CTest $TestArguments $Repo -Phase 'native-tests'
}

function Invoke-Gradle([string[]]$Arguments) {
    $Arguments += '-PstemwiseMinifiedDevelopment=true'
    if ($PresentationReports) { $Arguments += '-PstemwisePresentationReports=true' }
    try {
        Invoke-Checked $Gradle ($Arguments + @('--no-daemon', '--console=plain')) $Repo -Phase 'gradle-build-and-checks'
    } finally {
        if ($Arguments -contains ':app:testDevelopmentUnitTest') {
            try { Write-JUnitTimings (Join-Path $Repo 'app/build/test-results/testDevelopmentUnitTest') }
            catch { Write-Host "JUNIT_TIMING unavailable: $($_.Exception.Message)" }
        }
    }
}

function Remove-ValidatedHostQualificationOutputs([string[]]$RelativePaths, [ValidateSet('app', 'audio-engine')][string]$Module = 'app') {
    $Approved = @(
        'test-results\testDevelopmentUnitTest',
        'reports\tests\testDevelopmentUnitTest',
        'reports\lint-results-development.xml',
        'reports\lint-results-development.txt',
        'reports\lint-results-development.html'
    )
    $BuildRoot = [IO.Path]::GetFullPath((Join-Path $Repo "$Module\build")).TrimEnd('\') + '\'
    foreach ($RelativePath in $RelativePaths) {
        if ($Approved -notcontains $RelativePath) {
            throw "Refusing unapproved Host qualification output: $RelativePath"
        }
        $Output = [IO.Path]::GetFullPath((Join-Path $BuildRoot $RelativePath))
        if (-not $Output.StartsWith($BuildRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Host qualification output escaped $Module/build: $Output"
        }
        if (Test-Path -LiteralPath $Output) {
            Remove-Item -LiteralPath $Output -Recurse -Force
        }
    }
}

function Read-HostQualificationXml([Parameter(Mandatory = $true)][string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required Host qualification XML is missing: $Path"
    }
    $Settings = [Xml.XmlReaderSettings]::new()
    $Settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $Settings.XmlResolver = $null
    $Reader = [Xml.XmlReader]::Create($Path, $Settings)
    try {
        $Document = [Xml.XmlDocument]::new()
        $Document.XmlResolver = $null
        $Document.Load($Reader)
        return $Document
    } finally {
        $Reader.Dispose()
    }
}

function Read-JUnitQualification([Parameter(Mandatory = $true)][string]$Results) {
    if (-not (Test-Path -LiteralPath $Results -PathType Container)) {
        throw "Unit-test results are missing: $Results"
    }
    $Files = @(Get-ChildItem -LiteralPath $Results -File -Filter 'TEST-*.xml' |
        Sort-Object FullName)
    if ($Files.Count -eq 0) { throw "No JUnit XML files were produced in $Results." }

    $Counts = [ordered]@{ suites = 0L; tests = 0L; failures = 0L; errors = 0L; skipped = 0L }
    $Methods = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($File in $Files) {
        $Document = Read-HostQualificationXml $File.FullName
        if ($Document.DocumentElement.LocalName -cne 'testsuite') {
            throw "JUnit XML must have a testsuite root: $($File.FullName)"
        }
        $Counts.suites++
        foreach ($Name in @('tests', 'failures', 'errors', 'skipped')) {
            $Value = $Document.DocumentElement.GetAttribute($Name)
            if ($Value -cnotmatch '^\d+$') {
                throw "JUnit XML has an invalid $Name count: $($File.FullName)"
            }
            $Parsed = 0L
            if (-not [long]::TryParse($Value, [ref]$Parsed)) {
                throw "JUnit XML has an out-of-range $Name count: $($File.FullName)"
            }
            $Counts[$Name] += $Parsed
        }
        $Cases = @($Document.DocumentElement.SelectNodes('testcase'))
        if ($Cases.Count -ne [long]$Document.DocumentElement.GetAttribute('tests')) {
            throw "JUnit testcase count differs from tests attribute: $($File.FullName)"
        }
        foreach ($Outcome in @('failure', 'error', 'skipped')) {
            $CountName = if ($Outcome -eq 'skipped') { 'skipped' } else { $Outcome + 's' }
            $Actual = @($Document.DocumentElement.SelectNodes("testcase[$Outcome]")).Count
            if ($Actual -ne [long]$Document.DocumentElement.GetAttribute($CountName)) {
                throw "JUnit $CountName count differs from testcase outcomes: $($File.FullName)"
            }
        }
        foreach ($Case in $Cases) {
            $ClassName = [string]$Case.GetAttribute('classname')
            $MethodName = [string]$Case.GetAttribute('name')
            if ([string]::IsNullOrWhiteSpace($ClassName) -or
                    [string]::IsNullOrWhiteSpace($MethodName)) {
                throw "JUnit XML contains an unnamed testcase: $($File.FullName)"
            }
            $MethodId = "$ClassName#$MethodName"
            if (-not $Methods.Add($MethodId)) {
                throw "JUnit reported a duplicate testcase identity: $MethodId"
            }
        }
    }
    if (($Counts.failures + $Counts.errors + $Counts.skipped) -gt $Counts.tests) {
        throw 'JUnit failure, error, and skipped counts exceed the total test count.'
    }
    if ($Counts.tests -eq 0) { throw 'JUnit reported zero tests.' }
    if ($Methods.Count -ne $Counts.tests) {
        throw 'JUnit unique testcase identities do not equal the total test count.'
    }
    $Counts['methods'] = @($Methods | Sort-Object)
    return $Counts
}

function Write-JUnitTimings([string]$Results) {
    $Rows = @(foreach ($File in @(Get-ChildItem -LiteralPath $Results -Filter 'TEST-*.xml' -File -ErrorAction Stop)) {
        $Document = Read-HostQualificationXml $File.FullName
        foreach ($Case in $Document.SelectNodes('//testcase')) {
            $Seconds = 0.0
            if (-not [double]::TryParse($Case.GetAttribute('time'), [Globalization.NumberStyles]::Float,
                    [Globalization.CultureInfo]::InvariantCulture, [ref]$Seconds) -or
                    [double]::IsNaN($Seconds) -or [double]::IsInfinity($Seconds) -or $Seconds -lt 0) {
                throw "JUnit testcase duration is absent or invalid: $($File.Name)"
            }
            [ordered]@{method=$Case.GetAttribute('classname')+'#'+$Case.GetAttribute('name');
                reportedSeconds=$Seconds; reportLastWriteUtc=$File.LastWriteTimeUtc.ToString('o')}
        }
    })
    if (-not $Rows.Count) { throw 'No timed JUnit testcases are available.' }
    Write-Host ('JUNIT_TIMING ' + ([ordered]@{
        scope='retained task XML; may be cached; reported method time includes JUnit fixture accounting'
        methods=$Rows.Count
        slowestMethods=@($Rows | Sort-Object { $_.reportedSeconds } -Descending | Select-Object -First 10)
    } | ConvertTo-Json -Depth 4 -Compress))
}

function Get-HostExpectedMethods {
    $Python = Resolve-Python
    $Result = Invoke-QualificationProcess $Python @('-B', $HostInventory) -TimeoutSeconds 120
    if ($Result.Stderr) { Write-Host $Result.Stderr }
    $Inventory = ConvertFrom-QualificationJson $Result 'Host test inventory'
    if ([string]$Inventory.status -cne 'PASS' -or @($Inventory.methods).Count -eq 0) {
        throw 'Host test inventory did not report a non-empty PASS result.'
    }
    $Methods = @(
        $Inventory.methods | ForEach-Object { [string]$_ } | Sort-Object -Unique
    )
    $MissingRequired = @(
        $RequiredHostQualificationMethods |
            Where-Object { $_ -cnotin $Methods }
    )
    if ($MissingRequired.Count -gt 0) {
        throw "Mandatory Host qualification methods are missing: $($MissingRequired -join ', ')"
    }
    return $Methods
}

function Assert-HostMethodAccounting {
    param(
        [Parameter(Mandatory = $true)][string[]]$Expected,
        [Parameter(Mandatory = $true)][string[]]$Observed
    )
    $Missing = @($Expected | Where-Object { $_ -cnotin $Observed })
    $Unexpected = @($Observed | Where-Object { $_ -cnotin $Expected })
    if ($Missing.Count -gt 0 -or $Unexpected.Count -gt 0) {
        throw "Host test identity accounting failed: missing=$($Missing -join ', ') unexpected=$($Unexpected -join ', ')"
    }
}

function Assert-JUnitQualificationPass([Parameter(Mandatory = $true)][object]$Counts) {
    if (($Counts.failures + $Counts.errors + $Counts.skipped) -gt 0) {
        throw "JUnit reported $($Counts.failures) failures, $($Counts.errors) errors, and $($Counts.skipped) skipped."
    }
}

function Read-LintQualification {
    param(
        [Parameter(Mandatory = $true)][string]$Xml
    )
    $Document = Read-HostQualificationXml $Xml
    if ($Document.DocumentElement.LocalName -cne 'issues') {
        throw "Lint XML must have an issues root: $Xml"
    }

    $Counts = [ordered]@{
        issues = 0L
        fatal = 0L
        errors = 0L
        warnings = 0L
        hints = 0L
        informational = 0L
    }
    foreach ($Issue in @($Document.SelectNodes('/issues/issue'))) {
        $Severity = $Issue.GetAttribute('severity')
        switch -CaseSensitive ($Severity) {
            'Fatal' { $Counts.fatal++ }
            'Error' { $Counts.errors++ }
            'Warning' { $Counts.warnings++ }
            'Hint' { $Counts.hints++ }
            'Information' { $Counts.informational++ }
            default { throw "Lint XML has an unknown severity '$Severity': $Xml" }
        }
        $Counts.issues++
    }

    return $Counts
}

function Copy-HostQualificationDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        throw "Required Host qualification report directory is missing: $Source"
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force |
        Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
}

function Invoke-HostQualification {
    $Build = Join-Path $Repo 'app\build'
    $UnitResults = Join-Path $Build 'test-results\testDevelopmentUnitTest'
    $EngineUnitResults = Join-Path $Repo 'audio-engine/build/test-results/testDevelopmentUnitTest'
    $UnitReport = Join-Path $Build 'reports\tests\testDevelopmentUnitTest'
    $LintXml = Join-Path $Build 'reports\lint-results-development.xml'
    $LintText = Join-Path $Build 'reports\lint-results-development.txt'
    $LintHtml = Join-Path $Build 'reports\lint-results-development.html'
    $Archive = Join-Path $script:DeviceRunEvidenceRoot 'host-qualification'
    $Failures = [Collections.Generic.List[string]]::new()
    $FailureKind = 'test-failure'
    $script:HostQualification = [ordered]@{ unitTests = $null; lint = $null }

    Remove-ValidatedHostQualificationOutputs @(
        'test-results\testDevelopmentUnitTest', 'reports\tests\testDevelopmentUnitTest'
    )
    Remove-ValidatedHostQualificationOutputs @('test-results\testDevelopmentUnitTest', 'reports\tests\testDevelopmentUnitTest') -Module 'audio-engine'
    try {
        Invoke-Gradle @(':app:testDevelopmentUnitTest', '--rerun', ':audio-engine:testDevelopmentUnitTest', '--rerun')
    } catch {
        Assert-QualificationOwnership $_
        $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        $Failures.Add("testDevelopmentUnitTest: $($_.Exception.Message)")
    }
    try {
        $ArchivedResults = Join-Path $Archive 'unit-tests\results'
        $ArchivedReport = Join-Path $Archive 'unit-tests\report'
        Copy-HostQualificationDirectory $UnitResults $ArchivedResults
        $EngineXml = @(Get-ChildItem -LiteralPath $EngineUnitResults -File -Filter 'TEST-*.xml')
        if (-not $EngineXml.Count) { throw 'Audio-engine JUnit execution records are missing.' }
        foreach ($Xml in $EngineXml) {
            $Destination = Join-Path $ArchivedResults $Xml.Name
            if (Test-Path -LiteralPath $Destination) { throw "Duplicate JVM report owner: $($Xml.Name)" }
            Copy-Item -LiteralPath $Xml.FullName -Destination $Destination
        }
        if ($PresentationReports) {
            Copy-HostQualificationDirectory $UnitReport $ArchivedReport
        }
        $Counts = Read-JUnitQualification $ArchivedResults
        $ExpectedMethods = Get-HostExpectedMethods
        Assert-HostMethodAccounting $ExpectedMethods @($Counts.methods)
        [ordered]@{
            schemaVersion = 1
            expectedCount = $ExpectedMethods.Count
            observedCount = @($Counts.methods).Count
            expectedMethods = $ExpectedMethods
            observedMethods = @($Counts.methods)
        } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (
            Join-Path $Archive 'unit-tests\inventory.json'
        ) -Encoding UTF8
        $script:HostQualification.unitTests = [ordered]@{
            counts = $Counts
            reportLocations = [ordered]@{
                junitXml = 'host-qualification/unit-tests/results'
            }
        }
        Assert-JUnitQualificationPass $Counts
    } catch {
        Assert-QualificationOwnership $_
        $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        $Failures.Add("unit-test evidence: $($_.Exception.Message)")
    }

    Remove-ValidatedHostQualificationOutputs @(
        'reports\lint-results-development.xml',
        'reports\lint-results-development.txt',
        'reports\lint-results-development.html'
    )
    try {
        Invoke-Gradle @(':app:lintDevelopment', '--rerun')
    } catch {
        Assert-QualificationOwnership $_
        $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        $Failures.Add("lintDevelopment: $($_.Exception.Message)")
    }
    try {
        $ArchivedLint = Join-Path $Archive 'lint'
        New-Item -ItemType Directory -Path $ArchivedLint -Force | Out-Null
        $Reports = @($LintXml)
        if ($PresentationReports) { $Reports += @($LintText, $LintHtml) }
        foreach ($Report in $Reports) {
            Copy-Item -LiteralPath $Report -Destination $ArchivedLint -Force
        }
        $ArchivedXml = Join-Path $ArchivedLint 'lint-results-development.xml'
        $ArchivedText = Join-Path $ArchivedLint 'lint-results-development.txt'
        $ArchivedHtml = Join-Path $ArchivedLint 'lint-results-development.html'
        $Counts = Read-LintQualification $ArchivedXml
        $script:HostQualification.lint = [ordered]@{
            counts = $Counts
            reportLocations = [ordered]@{
                xml = 'host-qualification/lint/lint-results-development.xml'
            }
        }
        if (($Counts.fatal + $Counts.errors) -gt 0) {
            $Failures.Add(
                "Lint reported $($Counts.fatal) fatal and $($Counts.errors) error issues."
            )
        }
    } catch {
        Assert-QualificationOwnership $_
        $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        $Failures.Add("lint evidence: $($_.Exception.Message)")
    }

    if ($Failures.Count -gt 0) {
        throw (New-QualificationException "Host qualification failures: $($Failures -join ' | ')" $null $FailureKind)
    }
}

function Invoke-HostTests([string[]]$CoveredPcModules = @()) {
    $Arguments = @('-B', (Join-Path $Suite 'host/portable_checks.py'))
    if ($CoveredPcModules.Count) { $Arguments += @('--source-only', '--covered-pc-modules') + $CoveredPcModules }
    Invoke-Checked (Resolve-Python) $Arguments $LiveRoot -Phase 'host-checks'
}

function Invoke-AndroidHostTests {
    if (-not $Class.Count) { throw 'Android requires -Class with exact JVM test class selectors.' }
    $Inventory = Invoke-QualificationProcess (Resolve-Python) @('-B', (Join-Path $Suite 'host/audio_evidence.py'), 'jvm-owners') -WorkingDirectory $LiveRoot -TimeoutSeconds 30
    if ($Inventory.ExitCode -ne 0) { throw "JVM ownership discovery failed: $($Inventory.Stderr)" }
    $Owners = ConvertFrom-Json -InputObject $Inventory.Stdout
    $Groups = @{ 'app' = @(); 'audio-engine' = @() }
    foreach ($Selector in $Class) {
        if ($Selector -cnotmatch '^[A-Za-z_$][A-Za-z0-9_.$]+$') { throw "Invalid JVM selector: $Selector" }
        $Owner = $Owners.PSObject.Properties[$Selector]
        if ($null -eq $Owner -or $Owner.Value -cnotin @('app', 'audio-engine')) { throw "Unknown JVM test owner: $Selector" }
        $Groups[$Owner.Value] += $Selector
    }
    foreach ($Module in @('audio-engine', 'app')) {
        if (-not $Groups[$Module].Count) { continue }
        $Arguments = @(":${Module}:testDevelopmentUnitTest")
        if ($Mode -in @('AudioCheck', 'AudioQualify')) { $Arguments += '--rerun' }
        foreach ($Selector in @($Groups[$Module] | Sort-Object -Unique)) { $Arguments += @('--tests', $Selector) }
        Invoke-Gradle $Arguments
    }
}

function Initialize-SuitePlan {
    $Output = Join-Path $Suite 'work/audio-current'
    [void][IO.Directory]::CreateDirectory($Output)
    if ($Mode -cin @('Full', 'PcSuite', 'AndroidSuite', 'AudioQualify')) {
        $PreviousFull = Join-Path $Suite 'evidence/current-Full/run.json'
        if (Test-Path -LiteralPath $PreviousFull -PathType Leaf) { Remove-Item -LiteralPath $PreviousFull -Force }
    }
    if ($Mode -ne 'AudioPlan') {
        $PreviousCandidate = Join-Path $Output 'candidate.json'
        if (Test-Path -LiteralPath $PreviousCandidate) { Remove-Item -LiteralPath $PreviousCandidate -Force }
    }
    $Inventory = Join-Path $Output 'native-inventory.json'
    $PlanFile = Join-Path $Output 'plan.json'
    $script:PreviousSuiteProfile = $null
    if (-not $DeviceProfile -and (Test-Path -LiteralPath $PlanFile -PathType Leaf)) {
        $Identity = Invoke-QualificationProcess (Resolve-Python) @('-B', (Join-Path $Suite 'host/audio_evidence.py'), 'identity') -WorkingDirectory $LiveRoot -TimeoutSeconds 30
        if ($Identity.ExitCode -ne 0 -or $Identity.Stdout.Trim() -cnotmatch '^[0-9a-f]{64}$') { throw 'Cannot construct the current suite source identity.' }
        try {
            $PreviousPlan = Get-Content -LiteralPath $PlanFile -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($PreviousPlan.sourceSha256 -ceq $Identity.Stdout.Trim() -and $PreviousPlan.deviceProfile.path) {
                $ProfileCandidate = [IO.Path]::GetFullPath((Join-Path $LiveRoot $PreviousPlan.deviceProfile.path))
                if ($ProfileCandidate.StartsWith(([IO.Path]::GetFullPath($LiveRoot).TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase) -and
                        (Test-Path -LiteralPath $ProfileCandidate -PathType Leaf) -and
                        (Get-FileSha256 $ProfileCandidate).ToLowerInvariant() -ceq $PreviousPlan.deviceProfile.sha256) {
                    # Reuse only an unchanged, currently valid project-owned profile.
                    $DeviceProfile = $ProfileCandidate
                    Initialize-ApprovedDeviceProfile
                    $script:PreviousSuiteProfile = $ProfileCandidate
                }
            }
        } catch {
            # An obsolete optional plan/profile cannot block a standalone PC run.
            $script:PreviousSuiteProfile = $null
        } finally { $DeviceProfile = $null }
    }
    if (Test-Path -LiteralPath $PlanFile) { Remove-Item -LiteralPath $PlanFile -Force }
    $Evidence = Join-Path $Suite 'host/audio_evidence.py'
    Invoke-NativeTests -PlanOnly -Report $Inventory
    $ProfileForPlan = $DeviceProfile
    if (-not $ProfileForPlan -and $script:PreviousSuiteProfile) { $ProfileForPlan = $script:PreviousSuiteProfile }
    $PlanArguments = @('-B', $Evidence, 'plan', '--native-inventory', $Inventory, '--output', $PlanFile)
    if ($ProfileForPlan) { $PlanArguments += @('--device-profile', $ProfileForPlan) }
    Invoke-Checked (Resolve-Python) $PlanArguments $LiveRoot -Phase 'audio-plan'
}

function Invoke-AudioChecks([ValidateSet('Audio', 'All')][string]$Scope = 'Audio') {
    $Output = Join-Path $Suite 'work/audio-current'
    $PlanFile = Join-Path $Output 'plan.json'
    $Evidence = Join-Path $Suite 'host/audio_evidence.py'
    $Plan = Get-Content -LiteralPath $PlanFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $NativeReport = Join-Path $Output 'native.xml'
    $HostReport = Join-Path $Output 'host.json'
    $BoundaryReport = Join-Path $Output 'boundary.json'
    $ManagedNegativeReport = Join-Path $Output 'negative-controls-managed.json'
    $HostReceipt = Join-Path $Output 'host-receipt.json'
    foreach ($OldReport in @($NativeReport, $HostReport, $BoundaryReport, $ManagedNegativeReport, $HostReceipt)) {
        if (Test-Path -LiteralPath $OldReport) { Remove-Item -LiteralPath $OldReport -Force }
    }
    # The compiler rejection probes run before tests; their restored build is the tested one.
    Invoke-AudioBoundaryChecks -Report $BoundaryReport
    Invoke-NativeTests -Report $NativeReport
    if ($Scope -ceq 'All') {
        Invoke-HostQualification
        $JvmResults = @((Join-Path $script:DeviceRunEvidenceRoot 'host-qualification/unit-tests/results'))
    } else {
        $Class = @($Plan.required.jvm | ForEach-Object { $_.Split('#')[0] } | Sort-Object -Unique)
        Invoke-AndroidHostTests
        $JvmResults = @((Join-Path $Repo 'app/build/test-results/testDevelopmentUnitTest'),
            (Join-Path $Repo 'audio-engine/build/test-results/testDevelopmentUnitTest'))
    }
    Invoke-AudioManagedNegativeChecks -Report $ManagedNegativeReport
    Invoke-Checked (Resolve-Python) @('-B', $Evidence, 'self-test', '--output', $HostReport) $LiveRoot -Phase 'audio-evidence-self-test'
    $CheckArguments = @('-B', $Evidence, 'check', '--plan', $PlanFile, '--native-report', $NativeReport,
        '--jvm-results') + $JvmResults + @('--jvm-scope', $Scope.ToLowerInvariant(), '--host-report', $HostReport,
        '--boundary-report', $BoundaryReport, '--output', $HostReceipt)
    Invoke-Checked (Resolve-Python) $CheckArguments $LiveRoot -Phase 'audio-host-aggregate'
}

function Invoke-CompletePcChecks([ValidateSet('Audio', 'All')][string]$Scope = 'All') {
    . (Join-Path $PcBuilder 'runtime-bootstrap.ps1')
    $Runtime = Get-StemWiseRuntime -BuilderWindows $PcBuilder -Route main
    $SuiteWsl = Convert-ToWslPath $Suite
    Invoke-Checked 'wsl.exe' (@('-d', $Runtime.Distribution, '--', 'env') + @($Runtime.Environment) +
        @($Runtime.BootstrapPython, '-B', '-m', 'stemwise_engine.runtime_environment', $Runtime.Python,
          '-B', "$SuiteWsl/host/source_timing.py", '--runtime-check')) $LiveRoot -Phase 'timing-calibration-prerequisite'
    Invoke-WithWorkspaceMutex {
        if ($Scope -ceq 'All') {
            $Mode = 'PcSuite'
            Invoke-RecordedDeviceOperation { Invoke-PcPhaseChecks -Scope $Scope } -EvidenceMode 'PcSuite'
        } else { Invoke-PcPhaseChecks -Scope $Scope }
    }
}

function Invoke-PcPhaseChecks([ValidateSet('Audio', 'All')][string]$Scope = 'All') {
    $Output = Join-Path $Suite 'work/audio-current'
    [void][IO.Directory]::CreateDirectory($Output)
    $Failures = [Collections.Generic.List[string]]::new()
    $Causes = [Collections.Generic.List[Exception]]::new()
    $FailureKind = $null
    $PlanFile = Join-Path $Output 'plan.json'
    $NegativeReport = Join-Path $Output 'negative-controls.json'
    $NegativeMemoryReport = Join-Path $Output 'negative-controls-memory.json'
    $ProtocolReport = Join-Path $Output 'protocol.json'
        # A failure in one required lane cannot hide the state of the remaining lanes.
        if ($Scope -ceq 'All') {
            $Plans = @(Get-PcTestPlan -PerModule)
            # The tenth portable module lives under host/, outside the PC inventory.
            Invoke-HostTests -CoveredPcModules (@($Plans | ForEach-Object { $_.Arguments[-1] }) + @('test_native_device_merger'))
            Invoke-Checked (Resolve-Python) @('-B', '-m', 'unittest', 'discover', '-s', (Join-Path $Suite 'host'),
                '-p', 'test_native_device_merger.py') $LiveRoot -Phase 'pc-portable-host-test'
            Invoke-PcTests -Plans $Plans
            $SuiteWsl = Convert-ToWslPath $Suite
            Invoke-Checked 'wsl.exe' @('-d', (Get-TestRuntimeLayout).wslDistribution, '--', '/usr/bin/python3', '-B',
                "$SuiteWsl/pc/bridge_capacity_check.py") $LiveRoot -TimeoutSeconds 1200 -Phase 'bridge-capacity'
        }
        try { Invoke-AudioChecks -Scope $Scope } catch {
            Assert-QualificationOwnership $_
            $Failures.Add("host: $($_.Exception.Message)"); $Causes.Add($_.Exception)
            $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        }
        if (-not (Test-Path -LiteralPath $PlanFile -PathType Leaf)) { throw "Audio plan unavailable: $($Failures -join ' | ')" }
        foreach ($Kind in @('memory', 'thread')) {
            $Report = Join-Path $Output "sanitizer-$Kind.json"
            if (Test-Path -LiteralPath $Report) { Remove-Item -LiteralPath $Report -Force }
            try {
                $SuiteWsl = Convert-ToWslPath $Suite
                Invoke-Checked 'wsl.exe' @('-d', (Get-TestRuntimeLayout).wslDistribution, '--', '/usr/bin/python3', '-B',
                    "$SuiteWsl/native/run_sanitizers.py", '--kind', $Kind,
                    '--build', "$SuiteWsl/work/audio-$Kind-current", '--jobs', '2',
                    '--report', "$SuiteWsl/work/audio-current/sanitizer-$Kind.json") $LiveRoot -TimeoutSeconds 1800 -Phase "audio-sanitizer-$Kind"
            } catch {
                Assert-QualificationOwnership $_
                $Failures.Add("sanitizer-${Kind}: $($_.Exception.Message)"); $Causes.Add($_.Exception)
                $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
            }
        }
        $ProtocolReport = Join-Path $Output 'protocol.json'
        if (Test-Path -LiteralPath $ProtocolReport) { Remove-Item -LiteralPath $ProtocolReport -Force }
        try {
            $SuiteWsl = Convert-ToWslPath $Suite
            $BuilderWsl = Convert-ToWslPath $PcBuilder
            Invoke-Checked 'wsl.exe' @('-d', (Get-TestRuntimeLayout).wslDistribution, '--', "$BuilderWsl/.tools/bootstrap/bin/python", '-B',
                "$SuiteWsl/native/formal/verify.py", '--report', "$SuiteWsl/work/audio-current/protocol.json") $LiveRoot -TimeoutSeconds 1800 -Phase 'audio-protocol'
        } catch {
            Assert-QualificationOwnership $_
            $Failures.Add("protocol: $($_.Exception.Message)"); $Causes.Add($_.Exception)
            $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        }
        $NegativeReport = Join-Path $Output 'negative-controls.json'
        if (Test-Path -LiteralPath $NegativeReport) { Remove-Item -LiteralPath $NegativeReport -Force }
        try {
            $SuiteWsl = Convert-ToWslPath $Suite
            $BuilderWsl = Convert-ToWslPath $PcBuilder
            Invoke-Checked 'wsl.exe' @('-d', (Get-TestRuntimeLayout).wslDistribution, '--', "$BuilderWsl/.tools/bootstrap/bin/python", '-B',
                "$SuiteWsl/native/run_negative_controls.py", '--report', "$SuiteWsl/work/audio-current/negative-controls.json") $LiveRoot -TimeoutSeconds 1800 -Phase 'audio-negative-controls'
        } catch {
            Assert-QualificationOwnership $_
            $Failures.Add("negative-controls: $($_.Exception.Message)"); $Causes.Add($_.Exception)
            $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        }
        $NegativeMemoryReport = Join-Path $Output 'negative-controls-memory.json'
        if (Test-Path -LiteralPath $NegativeMemoryReport) { Remove-Item -LiteralPath $NegativeMemoryReport -Force }
        try {
            $SuiteWsl = Convert-ToWslPath $Suite
            $BuilderWsl = Convert-ToWslPath $PcBuilder
            Invoke-Checked 'wsl.exe' @('-d', (Get-TestRuntimeLayout).wslDistribution, '--', "$BuilderWsl/.tools/bootstrap/bin/python", '-B',
                "$SuiteWsl/native/run_negative_controls.py", '--memory', '--case', 'premature-resource-release',
                '--report', "$SuiteWsl/work/audio-current/negative-controls-memory.json") $LiveRoot -TimeoutSeconds 900 -Phase 'audio-negative-controls-memory'
        } catch {
            Assert-QualificationOwnership $_
            $Failures.Add("negative-controls-memory: $($_.Exception.Message)"); $Causes.Add($_.Exception)
            $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        }
    if ($Failures.Count) {
        throw (New-QualificationException "PC phase failed: $($Failures -join ' | ')" ([AggregateException]::new($Causes.ToArray())) $FailureKind)
    }
}

function Invoke-CompleteAndroidChecks([ValidateSet('Audio', 'All')][string]$Scope = 'All') {
    $Output = Join-Path $Suite 'work/audio-current'
    $PlanFile = Join-Path $Output 'plan.json'
    $Failures = [Collections.Generic.List[string]]::new()
    $Causes = [Collections.Generic.List[Exception]]::new()
    $FailureKind = $null
    $Plan = Get-Content -LiteralPath $PlanFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $DeviceReport = $null
    $PhysicalReport = $null
    $DevelopmentReport = Join-Path $Output 'development.json'
    if (Test-Path -LiteralPath $DevelopmentReport) { Remove-Item -LiteralPath $DevelopmentReport -Force }
    if ($DeviceProfile -and $Serial) {
        if ($Serial -cne $Plan.deviceProfile.identity.serial) { throw 'Audio qualification serial differs from the saved exact device profile.' }
        $DeviceReport = Join-Path $Suite ('evidence/current-' + $(if ($Scope -ceq 'All') { 'AndroidSuite' } else { 'AudioQualify' }) + '/run.json')
        try {
            Invoke-WithWorkspaceMutex {
            if ($Scope -ceq 'All') {
                $Mode = 'Device'; $Class = @(); $DeviceLane = 'All'
                Invoke-RecordedDeviceOperation { Invoke-WithDeviceMutex { Invoke-DeviceTests -RetainDeliverable } } -EvidenceMode 'AndroidSuite'
            } else {
                $Mode = 'Focused'; $Class = @($Plan.required.device)
                Invoke-RecordedDeviceOperation { Invoke-WithDeviceMutex { Invoke-AudioDeviceChecks $Plan } } -EvidenceMode 'AudioQualify'
            }
            }
        } catch {
            Assert-QualificationOwnership $_
            $Failures.Add("device: $($_.Exception.Message)"); $Causes.Add($_.Exception)
            $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        }
        $PhysicalReport = Join-Path $Output 'physical-performance.json'
        try {
            if (Test-Path -LiteralPath $PhysicalReport) { Remove-Item -LiteralPath $PhysicalReport -Force }
            # The profiler's normal Focused child owns both existing mutexes and the Perfetto lifetime.
            Invoke-Checked (Resolve-Python) @('-B', (Join-Path $Suite 'host/audio_physical_evidence.py'),
                '--plan', $PlanFile, '--output', $PhysicalReport) $LiveRoot -TimeoutSeconds 1800 -Phase 'audio-physical-performance'
        } catch {
            Assert-QualificationOwnership $_
            $Failures.Add("physical-performance: $($_.Exception.Message)"); $Causes.Add($_.Exception)
            $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        }
        try {
            if (-not (Get-Command Invoke-AudioDevelopmentChecks -ErrorAction SilentlyContinue)) {
                throw 'Final minified candidate behavioral runner is unavailable.'
            }
            Invoke-WithWorkspaceMutex { Invoke-WithDeviceMutex {
                Invoke-AudioDevelopmentChecks -Plan $Plan -Report $DevelopmentReport -DeviceReport $DeviceReport
            } }
        } catch {
            Assert-QualificationOwnership $_
            $Failures.Add("development: $($_.Exception.Message)"); $Causes.Add($_.Exception)
            $FailureKind = Merge-QualificationFailureKind $FailureKind (Get-QualificationFailureKind $_)
        }
    } else {
        $Failures.Add('Physical target unavailable: -Serial and exact -DeviceProfile are required.')
    }
    if ($Failures.Count) {
        try { if ($DeviceReport -and (Test-Path -LiteralPath $DeviceReport -PathType Leaf)) {
            $FailedPhase = Get-Content -LiteralPath $DeviceReport -Raw -Encoding UTF8 | ConvertFrom-Json
            $FailedPhase.status = 'FAIL'
            $FailedPhase.failure = $Failures -join ' | '
            $FailedPhase.failureKind = if ($FailureKind) { $FailureKind } else { 'test-failure' }
            $FailedPhase.finished = (Get-Date).ToString('o')
            $FailedPhase | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $DeviceReport -Encoding utf8NoBOM
        } } catch {
            Assert-QualificationOwnership $_
            $Failures.Add("Android result publication: $($_.Exception.Message)"); $Causes.Add($_.Exception)
            $FailureKind = Merge-QualificationFailureKind $FailureKind 'infrastructure'
        }
        throw (New-QualificationException "Android phase failed: $($Failures -join ' | ')" ([AggregateException]::new($Causes.ToArray())) $FailureKind)
    }
}

function Invoke-AudioAggregate {
    $Output = Join-Path $Suite 'work/audio-current'
    $PlanFile = Join-Path $Output 'plan.json'
    $Failures = [Collections.Generic.List[string]]::new()
    $Plan = Get-Content -LiteralPath $PlanFile -Raw -Encoding UTF8 | ConvertFrom-Json
    # Full Android receipts have independently complete inventory before audio projection.
    $DeviceReport = @(@('AndroidSuite', 'AudioQualify') | ForEach-Object {
        Get-Item -LiteralPath (Join-Path $Suite "evidence/current-$_/run.json") -ErrorAction SilentlyContinue
    } | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).FullName
    $PhysicalReport = Join-Path $Output 'physical-performance.json'
    $DevelopmentReport = Join-Path $Output 'development.json'
    $ProtocolReport = Join-Path $Output 'protocol.json'
    $NegativeReport = Join-Path $Output 'negative-controls.json'
    $NegativeMemoryReport = Join-Path $Output 'negative-controls-memory.json'
    $Evidence = Join-Path $Suite 'host/audio_evidence.py'
    $AggregateArguments = @('-B', $Evidence, 'candidate', '--plan', $PlanFile,
        '--host', (Join-Path $Output 'host-receipt.json'),
        '--sanitizer-memory', (Join-Path $Output 'sanitizer-memory.json'),
        '--sanitizer-thread', (Join-Path $Output 'sanitizer-thread.json'), '--protocol', $ProtocolReport,
        '--negative-controls', $NegativeReport, '--negative-controls-memory', $NegativeMemoryReport,
        '--negative-controls-managed', (Join-Path $Output 'negative-controls-managed.json'),
        '--development', $DevelopmentReport, '--output', (Join-Path $Output 'candidate.json'))
    if ($DeviceReport) { $AggregateArguments += @('--device', $DeviceReport) }
    if ($PhysicalReport) { $AggregateArguments += @('--physical-performance', $PhysicalReport) }
    try {
        if ($DeviceReport -and (Split-Path (Split-Path $DeviceReport) -Leaf) -ceq 'current-AndroidSuite') {
            $PreviousFull = Join-Path $Suite 'evidence/current-Full/run.json'
            if (Test-Path -LiteralPath $PreviousFull -PathType Leaf) { Remove-Item -LiteralPath $PreviousFull -Force }
        }
        Invoke-Checked (Resolve-Python) $AggregateArguments $LiveRoot -Phase 'audio-candidate-aggregate'
        if ($DeviceReport -and (Split-Path (Split-Path $DeviceReport) -Leaf) -ceq 'current-AndroidSuite') {
            Write-CompleteSuiteReceipt
        }
    } catch { $Failures.Add("aggregate: $($_.Exception.Message)") }
    if ($Failures.Count) {
        $CandidateFile = Join-Path $Output 'candidate.json'
        if (Test-Path -LiteralPath $CandidateFile -PathType Leaf) {
            $Candidate = Get-Content -LiteralPath $CandidateFile -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($Candidate.result -ceq 'passed') { $Candidate.result = 'failed' }
            $Candidate | Add-Member -NotePropertyName attemptFailures -NotePropertyValue @($Failures) -Force
            $Candidate | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $CandidateFile -Encoding utf8NoBOM
        }
        throw "Audio candidate remains unqualified: $($Failures -join ' | ')"
    }
}

function Write-CompleteSuiteReceipt {
    $EvidenceRoot = Join-Path $Suite 'evidence'
    $Phases = [ordered]@{}
    $Reports = @{}
    foreach ($Phase in @('PcSuite', 'AndroidSuite')) {
        $Path = Join-Path $EvidenceRoot "current-$Phase/run.json"
        $Report = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($Report.status -cne 'PASS' -or $Report.failure -or $Report.failureKind) { throw "Complete $Phase evidence did not pass." }
        $Reports[$Phase] = $Report
        $Phases[$Phase] = [ordered]@{ path = "current-$Phase/run.json"; sha256 = (Get-FileSha256 $Path).ToLowerInvariant() }
    }
    if ($Reports.PcSuite.mode -cne 'PcSuite' -or $Reports.AndroidSuite.mode -cne 'Device' -or
            $Reports.PcSuite.qualificationSourceSha256 -cne $Reports.AndroidSuite.qualificationSourceSha256 -or
            $Reports.PcSuite.qualificationSourceSha256 -cne (Get-QualificationSourceSha256) -or
            $Reports.PcSuite.producerSystemSha256 -cne $Reports.AndroidSuite.producerSystemSha256) {
        throw 'Complete phase receipts do not describe the same source and producer.'
    }
    $Target = Join-Path $EvidenceRoot 'current-Full'
    $ExpectedRoot = [IO.Path]::GetFullPath($EvidenceRoot).TrimEnd('\') + '\'
    $Target = [IO.Path]::GetFullPath($Target)
    if (-not $Target.StartsWith($ExpectedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Complete suite receipt escaped its evidence directory.' }
    if (Test-Path -LiteralPath $Target) {
        if ((Get-Item -LiteralPath $Target -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Complete suite receipt cannot use a linked directory.' }
        Remove-Item -LiteralPath $Target -Recurse -Force
    }
    [void][IO.Directory]::CreateDirectory($Target)
    [ordered]@{ schemaVersion = 3; mode = 'Full'; status = 'PASS'; phases = $Phases } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Target 'run.json') -Encoding utf8NoBOM
}

function Save-TargetCrashEvidence([string]$Destination) {
    # This bounded recent-buffer diagnostic may predate the selection. Keep timestamps
    # and complete target event blocks, without treating them as causal test evidence.
    $Result = Invoke-QualificationProcess (Resolve-Adb) @('-s', $Serial, 'logcat', '-b', 'crash', '-d', '-t', '500', '-v', 'long') -TimeoutSeconds 20
    if ($Result.ExitCode -ne 0) { throw 'Target crash buffer could not be read.' }
    $Current = [Collections.Generic.List[string]]::new()
    $Target = [Collections.Generic.List[string]]::new()
    $ProcessName = $null
    foreach ($Entry in [regex]::Split($Result.Stdout, '(?m)(?=^\[\s)')) {
        $Identity = [regex]::Match($Entry, '(?:Process:\s*(?<name>[^,\s]+)|>>>\s*(?<name>[^\s]+)\s*<<<)')
        if ($Entry -match 'FATAL EXCEPTION:|\*\*\* \*\*\* \*\*\*' -or
                ($Identity.Success -and $ProcessName -and $Identity.Groups['name'].Value -cne $ProcessName)) {
            if ($ProcessName -ceq $DevelopmentPackage) { $Target.AddRange($Current) }
            $Current.Clear()
            $ProcessName = $null
        }
        if ($Identity.Success) { $ProcessName = $Identity.Groups['name'].Value }
        $Current.Add($Entry)
    }
    if ($ProcessName -ceq $DevelopmentPackage) { $Target.AddRange($Current) }
    if ($Target.Count) {
        @('Recent target crash-buffer diagnostics; events may predate this selection.', ($Target -join '')) |
            Set-Content -LiteralPath $Destination -Encoding utf8NoBOM
    }
}

function Invoke-CompleteSuites([ValidateSet('Audio', 'All')][string]$Scope = 'All', [switch]$AndroidFirst) {
    # Read-only preflight fails before expensive work when both phases were requested.
    Require-Serial
    Assert-DeviceUnlockPrepared
    Invoke-WithWorkspaceMutex { Initialize-SuitePlan }
    if ($AndroidFirst) {
        Invoke-CompleteAndroidChecks -Scope $Scope
        Invoke-CompletePcChecks -Scope $Scope
    } else {
        Invoke-CompletePcChecks -Scope $Scope
        Invoke-CompleteAndroidChecks -Scope $Scope
    }
    Invoke-AudioAggregate
}

function Test-AdbHostConnectionFailure([string]$Stdout, [string]$Stderr) {
    $Diagnostic = "$Stdout`n$Stderr"
    if ($Diagnostic -match '(?i)\b(?:device(?:\s+''[^''\r\n]*''|\s+"[^"\r\n]*")?\s+(?:offline|unauthorized|not found)|no devices/emulators found|more than one device/emulator)\b') {
        return $false
    }
    $ServerConnectFailure = $Stderr -match '(?im)^(?:(?:adb(?:\.exe)?|error):[ \t]*)?(?:cannot connect to daemon\b|failed to check server version:[ \t]*cannot connect to daemon\b)'
    $ServerHandshakeFailure = ($Stderr -match '(?im)^(?:cannot|could not) read ok from ADB Server[ \t]*\r?$') -and
        ($Stderr -match '(?m)^\* failed to start daemon[ \t]*\r?$')
    return $ServerConnectFailure -or $ServerHandshakeFailure
}

function Invoke-AdbReadOnlyProbe {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $Failures = [Collections.Generic.List[string]]::new()
    for ($Attempt = 1; $Attempt -le 2; $Attempt++) {
        $Result = Invoke-QualificationProcess -FilePath $Adb -Arguments (@('-s', $Serial) + $Arguments) -TimeoutSeconds 180
        $global:LASTEXITCODE = $Result.ExitCode
        $Stdout = [string]$Result.Stdout
        $Stderr = [string]$Result.Stderr
        if ($Stderr) { [Console]::Error.Write($Stderr) }
        if ($Result.ExitCode -eq 0) { return $Stdout.Trim() }

        $Failures.Add("attempt $Attempt (exit code $($Result.ExitCode)): stdout: $Stdout stderr: $Stderr")
        $HostConnectionFailure = Test-AdbHostConnectionFailure $Stdout $Stderr
        if ($Attempt -eq 2 -or -not $HostConnectionFailure) {
            $Message = "Approved-device $Label probe failed: $($Failures -join '; ')"
            if ($HostConnectionFailure) {
                throw (New-QualificationException $Message $null 'infrastructure')
            }
            throw $Message
        }
    }
}

. (Join-Path $PSScriptRoot 'device/Device-Profile.ps1')

function Invoke-WithNonBlockingMutex {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$BusyMessage,
        [Parameter(Mandatory = $true)][scriptblock]$Operation
    )

    $Mutex = [Threading.Mutex]::new($false, $Name)
    $Owned = $false
    $Failure = $null
    $ReleaseFailure = $null
    try {
        try {
            $Owned = $Mutex.WaitOne(0)
        } catch [Threading.AbandonedMutexException] {
            $Owned = $true
        }
        if (-not $Owned) {
            throw $BusyMessage
        }
        & $Operation
    } catch {
        $Failure = $_
    } finally {
        if ($Owned) {
            try {
                $Mutex.ReleaseMutex()
            } catch {
                $ReleaseFailure = $_
            }
        }
        $Mutex.Dispose()
    }

    if ($null -ne $Failure -and $null -ne $ReleaseFailure) {
        throw (New-QualificationException "$($Failure.Exception.Message) | mutex release: $($ReleaseFailure.Exception.Message)" $Failure 'cleanup')
    }
    if ($null -ne $Failure) { throw $Failure }
    if ($null -ne $ReleaseFailure) { throw $ReleaseFailure }
}

function Invoke-WithDeviceMutex([scriptblock]$Operation) {
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        throw "$Mode mode needs -Serial."
    }

    $DeviceTestOperation = $Operation
    $SafeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
    Invoke-WithNonBlockingMutex `
        -Name "Local\StemWise.Tester.Device.$SafeSerial" `
        -BusyMessage "Another StemWise tester run is already using device $Serial." `
        -Operation {
            Require-Serial
            Invoke-WithDeviceTestSettings $DeviceTestOperation
        }
}

function Invoke-WithWorkspaceMutex([scriptblock]$Operation) {
    $Hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $WorkspaceBytes = [Text.Encoding]::UTF8.GetBytes($LiveRoot.ToUpperInvariant())
        $WorkspaceHash = [BitConverter]::ToString(
            $Hasher.ComputeHash($WorkspaceBytes)
        ).Replace('-', '').Substring(0, 24)
    } finally {
        $Hasher.Dispose()
    }
    Invoke-WithNonBlockingMutex `
        -Name "Local\StemWise.Tester.Workspace.$WorkspaceHash" `
        -BusyMessage "Another StemWise tester run is already using workspace $LiveRoot." `
        -Operation $Operation
}

. (Join-Path $PSScriptRoot 'host/Qualification-Evidence.ps1')

. (Join-Path $PSScriptRoot 'host/Qualification-Report.ps1')
. (Join-Path $PSScriptRoot 'host/Audio-Module-Boundary.ps1')
. (Join-Path $PSScriptRoot 'host/Audio-Managed-Negative.ps1')
. (Join-Path $PSScriptRoot 'host/Audio-Development.ps1')

function Copy-SanitizedDeviceEvidenceTree {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination,
        [switch]$JUnitOnly
    )

    $SourceRoot = [IO.Path]::GetFullPath($Source).TrimEnd('\') + '\'
    $DestinationRoot = [IO.Path]::GetFullPath($Destination).TrimEnd('\') + '\'
    New-Item -ItemType Directory -Path $DestinationRoot -Force | Out-Null
    foreach ($File in Get-ChildItem -LiteralPath $SourceRoot -Recurse -File -Force) {
        # UTP's per-test logcat includes unrelated device processes and can retain owner PII.
        if ($File.Name -like 'logcat-*.txt') { continue }
        if ($JUnitOnly -and $File.Name -cnotlike 'TEST-*.xml') { continue }
        $FilePath = [IO.Path]::GetFullPath($File.FullName)
        if (-not $FilePath.StartsWith($SourceRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Device evidence file escaped its source root: $FilePath"
        }
        $Target = [IO.Path]::GetFullPath(
            (Join-Path $DestinationRoot $FilePath.Substring($SourceRoot.Length))
        )
        if (-not $Target.StartsWith($DestinationRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Device evidence file escaped its destination root: $Target"
        }
        New-Item -ItemType Directory -Path (Split-Path -Parent $Target) -Force | Out-Null
        if ($JUnitOnly) {
            $Document = Read-HostQualificationXml $FilePath
            foreach ($Output in @($Document.SelectNodes('//system-out|//system-err'))) {
                $Output.ParentNode.RemoveChild($Output) | Out-Null
            }
            $Settings = [Xml.XmlWriterSettings]::new()
            $Settings.Encoding = [Text.UTF8Encoding]::new($false)
            $Writer = [Xml.XmlWriter]::Create($Target, $Settings)
            try { $Document.Save($Writer) } finally { $Writer.Dispose() }
        } else {
            Copy-Item -LiteralPath $FilePath -Destination $Target -Force
        }
    }
}

function Remove-BroadDeviceLogcatArtifacts {
    param([Parameter(Mandatory = $true)][string]$Source)

    if (-not (Test-Path -LiteralPath $Source -PathType Container)) { return }
    $SourceRoot = [IO.Path]::GetFullPath($Source).TrimEnd('\') + '\'
    foreach ($File in Get-ChildItem -LiteralPath $SourceRoot -Recurse -File -Force `
            -Filter 'logcat-*.txt') {
        $FilePath = [IO.Path]::GetFullPath($File.FullName)
        if (-not $FilePath.StartsWith($SourceRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Device logcat artifact escaped its generated root: $FilePath"
        }
        Remove-Item -LiteralPath $FilePath -Force
    }
}

function Get-UtpInfrastructureRetryEvidence {
    param(
        [Parameter(Mandatory = $true)][Management.Automation.ErrorRecord]$Failure,
        [Parameter(Mandatory = $true)][string[]]$Selectors
    )

    $AfterAllMarker = 'Exception thrown during onAfterAll invocation of plugin AndroidTestApkInstallerPlugin: Connection timed out: connect'
    $BeforeAllMarkers = @('AndroidTestApkInstallerPlugin', 'AndroidTestLogcatPlugin') | ForEach-Object {
        "Exception thrown during onBeforeAll invocation of plugin ${_}: Connection timed out: connect"
    }
    $InstallMarker = "ErrorName: UNKNOWN`nNameSpace: DdmlibAndroidDeviceController"
    if ($Failure.Exception.Message -cne "$Gradle failed with exit code 1." -or
            -not (Test-Path -LiteralPath $ConnectedDeviceReports -PathType Container)) {
        return $null
    }
    $Files = @(
        Get-ChildItem -LiteralPath $ConnectedDeviceResults -Filter 'TEST-*.xml' -Recurse -File
    )
    if ($Files.Count -ne 1) { return $null }

    try { $Document = Read-HostQualificationXml $Files[0].FullName } catch { return $null }
    $Suite = $Document.DocumentElement
    if ($null -eq $Suite -or $Suite.PSBase.Name -cne 'testsuite' -or
            @(@('tests', 'failures', 'errors', 'skipped') |
                Where-Object { -not $Suite.HasAttribute($_) }).Count -ne 0) {
        return $null
    }
    $Cases = @($Suite.SelectNodes('testcase'))
    if ([int]$Suite.tests -ne $Cases.Count -or [int]$Suite.failures -ne 0 -or
            [int]$Suite.errors -ne 0 -or [int]$Suite.skipped -ne 0) {
        return $null
    }
    $SystemError = [string]$Suite.'system-err'
    $AfterAll = $SystemError.StartsWith($AfterAllMarker, [StringComparison]::Ordinal)
    $BeforeAllMarker = $BeforeAllMarkers | Where-Object { $SystemError.StartsWith($_, [StringComparison]::Ordinal) } | Select-Object -First 1
    $BeforeAll = $null -ne $BeforeAllMarker
    # ddmlib discards upload failure causes; retain that exact pre-test installation marker.
    $Install = $SystemError.StartsWith($InstallMarker, [StringComparison]::Ordinal) -and
        $SystemError.Contains('Message: Failed to install') -and
        $SystemError.Contains('com.android.tools.utp.plugins.deviceprovider.ddmlib.DdmlibAndroidDevice.installPackages(') -and
        ($SystemError.Contains('java.net.ConnectException: Connection timed out: connect') -or
         $SystemError.Contains('Failed to install-write all apks'))
    if (-not $AfterAll -and -not $BeforeAll -and -not $Install) { return $null }

    $Rows = @($script:DeviceInventoryAccount.methods)
    $Selected = foreach ($Selector in $Selectors) {
        if ($Selector.Contains('#')) {
            $Rows | Where-Object { [string]$_.id -ceq $Selector }
        } else {
            $Rows | Where-Object {
                [string]$_.class -ceq $Selector -or [string]$_.wrapper -ceq $Selector
            }
        }
    }
    $Expected = @(
        $Selected | Where-Object { -not $_.excluded } |
            ForEach-Object { [string]$_.id } | Sort-Object -Unique
    )
    if ($Expected.Count -eq 0) { return $null }
    if ($BeforeAll -or $Install) {
        if ($Cases.Count -ne 0) { return $null }
        return [ordered]@{
            triggerFailure = $Failure.Exception.Message
            marker = if ($BeforeAll) { $BeforeAllMarker } else { $InstallMarker }
            phase = if ($BeforeAll) {
                'before-all-zero-test'
            } else {
                'install-zero-test'
            }
            passingMethods = @()
        }
    }
    $Observed = @(
        foreach ($Case in $Cases) {
            if ([string]::IsNullOrWhiteSpace([string]$Case.classname) -or
                    [string]::IsNullOrWhiteSpace([string]$Case.name) -or
                    $null -ne $Case.SelectSingleNode('skipped') -or
                    $null -ne $Case.SelectSingleNode('failure') -or
                    $null -ne $Case.SelectSingleNode('error')) {
                return $null
            }
            "$([string]$Case.classname)#$([string]$Case.name)"
        }
    )
    $Observed = @($Observed | Sort-Object -Unique)
    if ($Observed.Count -ne $Cases.Count -or
            @($Expected | Where-Object { $_ -cnotin $Observed }).Count -ne 0 -or
            @($Observed | Where-Object { $_ -cnotin $Expected }).Count -ne 0) {
        return $null
    }
    return [ordered]@{
        triggerFailure = $Failure.Exception.Message
        marker = $AfterAllMarker
        phase = 'after-all-complete-pass'
        passingMethods = $Observed
    }
}

function Invoke-StandardDeviceSelection {
    param(
        [Parameter(Mandatory = $true)][string[]]$Selectors,
        [hashtable]$InstrumentationArguments = @{},
        [string]$EvidenceLabel = 'selection'
    )

    if ($Selectors.Count -eq 0) { throw 'A disjoint Device selection cannot be empty.' }
    Assert-DisposableDevelopmentTarget (Resolve-Adb)
    $RequestedSelectors = if ($InstrumentationArguments.ContainsKey('stemwiseFocusedMethods')) {
        @(([string]$InstrumentationArguments.stemwiseFocusedMethods).Split(','))
    } else { $Selectors }
    Assert-QualificationSourceUnchanged
    Assert-FrozenArtifactsUnchanged
    $ExpectedApplication = Get-FrozenArtifact 'qualification' 'application'
    $ExpectedTest = Get-FrozenArtifact 'qualification' 'androidTest'
    $Variant = 'Development'
    $ArtifactSuffix = 'development'
    $DevelopmentAbi = [regex]::Match($ExpectedApplication.evidence.fileName,
        "-(arm64-v8a|x86_64)-$ArtifactSuffix\.apk$").Groups[1].Value
    if (-not $DevelopmentAbi) { throw 'Frozen Development APK has no supported CPU ABI.' }
    $Arguments = @(
        ":app:connected${Variant}AndroidTest",
        "-Pstemwise.developmentTargetAbi=$DevelopmentAbi",
        '-Pandroid.testInstrumentationRunnerArguments.stemwiseDisposableTarget=true',
        "-Pandroid.testInstrumentationRunnerArguments.class=$($Selectors -join ',')"
    )
    $Arguments += @(
        "-Pandroid.testInstrumentationRunnerArguments.stemwiseExpectedApplicationSha256=$($ExpectedApplication.evidence.sha256)",
        "-Pandroid.testInstrumentationRunnerArguments.stemwiseExpectedTestSha256=$($ExpectedTest.evidence.sha256)")
    foreach ($Name in @($InstrumentationArguments.Keys | Sort-Object)) {
        # The complete protected suite exceeds cmd.exe's batch command-line limit.
        # Gradle reads this exact project property from the scoped child environment below.
        if ($Name -ceq 'stemwiseFocusedMethods') { continue }
        $Arguments += "-Pandroid.testInstrumentationRunnerArguments.$Name=$($InstrumentationArguments[$Name])"
    }
    if ($PressureSeed -ne 0) {
        $Arguments += "-Pandroid.testInstrumentationRunnerArguments.pressureSeed=$PressureSeed"
        $Arguments += "-Pandroid.testInstrumentationRunnerArguments.stemwisePressureSeed=$PressureSeed"
    }
    if ($PressureOperations -ne 0) {
        $Arguments += "-Pandroid.testInstrumentationRunnerArguments.pressureOperations=$PressureOperations"
    }

    $Started = Get-Date
    $script:DeviceSelectionSequence++
    $SafeLabel = $EvidenceLabel -replace '[^A-Za-z0-9._-]', '_'
    $SelectionEvidence = Join-Path $script:DeviceRunEvidenceRoot (
        '{0:D2}-{1}' -f $script:DeviceSelectionSequence, $SafeLabel
    )
    New-Item -ItemType Directory -Path $SelectionEvidence -Force | Out-Null
    $InvocationFailure = $null
    $Artifacts = @()
    $PreviousSerial = $env:ANDROID_SERIAL
    $FocusedMethodsEnvironment = 'ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.stemwiseFocusedMethods'
    $PreviousFocusedMethods = [Environment]::GetEnvironmentVariable($FocusedMethodsEnvironment, 'Process')
    $AttemptOutputsReset = $false
    $AttemptCount = 0
    $Retry = $null
    try {
        if ($InstrumentationArguments.ContainsKey('stemwiseFocusedMethods')) {
            [Environment]::SetEnvironmentVariable($FocusedMethodsEnvironment,
                [string]$InstrumentationArguments.stemwiseFocusedMethods, 'Process')
        } else {
            [Environment]::SetEnvironmentVariable($FocusedMethodsEnvironment, [NullString]::Value, 'Process')
        }
        $BuildOutputRoot = [IO.Path]::GetFullPath(
            (Join-Path $Repo 'app\build')
        ).TrimEnd('\') + '\'
        for ($Attempt = 1; $Attempt -le 2; $Attempt++) {
            $AttemptCount = $Attempt
            foreach ($OutputDirectory in @($ConnectedDeviceResults, $ConnectedDeviceReports)) {
                $ExactOutput = [IO.Path]::GetFullPath($OutputDirectory)
                if (-not $ExactOutput.StartsWith(
                        $BuildOutputRoot,
                        [StringComparison]::OrdinalIgnoreCase
                    )) {
                    throw "Connected-test output escaped the Gradle build directory: $ExactOutput"
                }
                if (Test-Path -LiteralPath $ExactOutput) {
                    Remove-Item -LiteralPath $ExactOutput -Recurse -Force
                }
            }
            $AttemptOutputsReset = $true
            $InvocationFailure = $null
            $Artifacts = @()
            $env:ANDROID_SERIAL = $Serial
            try {
                # A system panel can retain keyboard focus across isolated app processes.
                Invoke-AdbChecked (Resolve-Adb) @('shell', 'cmd', 'statusbar', 'collapse')
                Invoke-WithAudioSelectionIsolation $RequestedSelectors { Invoke-Gradle $Arguments }
                $Artifacts = @(Get-DevelopmentApkPairEvidence)
                $ActualApplication = @($Artifacts | Where-Object kind -eq 'application')
                $ActualTest = @($Artifacts | Where-Object kind -eq 'androidTest')
                if ($ActualApplication.Count -ne 1 -or
                        $ActualApplication[0].sha256 -cne $ExpectedApplication.evidence.sha256 -or
                        $ActualTest.Count -ne 1 -or
                        $ActualTest[0].sha256 -cne $ExpectedTest.evidence.sha256) {
                    throw 'Connected selection did not consume the immutable Development APK pair.'
                }
                $Artifacts | ForEach-Object { Register-ApkEvidence $_ }
                Assert-QualificationSourceUnchanged
                Assert-FrozenArtifactsUnchanged
            } catch {
                Assert-QualificationOwnership $_
                $InvocationFailure = $_
            }
            if ($null -eq $InvocationFailure -or $Attempt -eq 2) { break }

            $Retry = Get-UtpInfrastructureRetryEvidence $InvocationFailure $RequestedSelectors
            if ($null -eq $Retry) { break }
            try {
                Copy-SanitizedDeviceEvidenceTree $ConnectedDeviceResults (
                    Join-Path $SelectionEvidence 'retry-trigger\results'
                )
                Copy-SanitizedDeviceEvidenceTree $ConnectedDeviceReports (
                    Join-Path $SelectionEvidence 'retry-trigger\reports'
                )
                $Retry | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (
                    Join-Path $SelectionEvidence 'retry-trigger\retry.json'
                ) -Encoding UTF8
                Reset-DisposableDevicePackages
                Assert-QualificationSourceUnchanged
                Assert-FrozenArtifactsUnchanged
            } catch {
                Assert-QualificationOwnership $_
                $InvocationFailure = $_
                break
            }
            Write-Host "DEVICE_SELECTION_RETRY exact-utp-connection-timeout $EvidenceLabel"
        }
    } catch {
        Assert-QualificationOwnership $_
        $InvocationFailure = $_
    } finally {
        $env:ANDROID_SERIAL = $PreviousSerial
        if ($null -eq $PreviousFocusedMethods) {
            [Environment]::SetEnvironmentVariable($FocusedMethodsEnvironment, [NullString]::Value, 'Process')
        } else {
            [Environment]::SetEnvironmentVariable($FocusedMethodsEnvironment, $PreviousFocusedMethods, 'Process')
        }
    }
    if ($null -ne $Retry) {
        $Retry['attemptCount'] = $AttemptCount
        $Retry['finalStatus'] = if ($null -eq $InvocationFailure) { 'PASS' } else { 'FAIL' }
    }
    $ArchiveFailures = [Collections.Generic.List[string]]::new()
    $ArchiveFailureKind = 'test-failure'
    $ResultsPresent = $AttemptOutputsReset -and (
        Test-Path -LiteralPath $ConnectedDeviceResults -PathType Container
    )
    $ReportsPresent = $AttemptOutputsReset -and (
        Test-Path -LiteralPath $ConnectedDeviceReports -PathType Container
    )
    $ResultsArchived = $false
    $ReportsArchived = $false
    $PassingMethods = @()
    $SkippedMethods = @()
    $FailedMethods = @()
    try {
        if ($ResultsPresent) {
            # Preserve the actual XML even when parsing fails (for example process
            # death before a testcase is written), before removing broad logcat.
            Copy-SanitizedDeviceEvidenceTree $ConnectedDeviceResults (Join-Path $SelectionEvidence 'results') -JUnitOnly
            $ResultsArchived = $true
            $Observed = Read-InstrumentationMethodResults $ConnectedDeviceResults
            $PassingMethods = @($Observed.passing)
            $SkippedMethods = @($Observed.skipped)
            $FailedMethods = @($Observed.failed)
            if ($InstrumentationArguments.ContainsKey('stemwiseFocusedMethods')) {
                $MissingMethods = @($RequestedSelectors | Where-Object { $_ -cnotin $PassingMethods })
                $UnexpectedMethods = @((@($PassingMethods) + @($SkippedMethods) + @($FailedMethods)) |
                    Where-Object { $_ -cnotin $RequestedSelectors })
                if ($MissingMethods.Count -gt 0 -or $UnexpectedMethods.Count -gt 0 -or $SkippedMethods.Count -gt 0) {
                    $ArchiveFailures.Add("Focused method accounting failed: missing=$($MissingMethods -join ', ') unexpected=$($UnexpectedMethods -join ', ') skipped=$($SkippedMethods -join ', ')")
                }
            }
            if ($FailedMethods.Count -gt 0) {
                $ArchiveFailures.Add(
                    "Connected test XML contains failed methods: $($FailedMethods -join ', ')"
                )
            } elseif ($null -eq $InvocationFailure -and $ArchiveFailures.Count -eq 0) {
                $PassingMethods | ForEach-Object { Register-PassingInstrumentationMethod $_ }
                $SkippedMethods | ForEach-Object { Register-SkippedInstrumentationMethod $_ }
            }
        }
        if ($null -eq $InvocationFailure -and -not $ResultsPresent) {
            $ArchiveFailures.Add('Connected test results were not generated.')
            $ArchiveFailureKind = Merge-QualificationFailureKind $ArchiveFailureKind 'infrastructure'
        }
    } catch {
        Assert-QualificationOwnership $_
        $ArchiveFailures.Add("raw evidence archive: $($_.Exception.Message)")
        $ArchiveFailureKind = Merge-QualificationFailureKind $ArchiveFailureKind 'infrastructure'
    } finally {
        Assert-QualificationOwnership
        if ($null -ne $InvocationFailure -or $ArchiveFailures.Count -gt 0) {
            try {
                Save-TargetCrashEvidence (Join-Path $SelectionEvidence 'target-crash.txt')
            } catch {
                Assert-QualificationOwnership $_
                $ArchiveFailures.Add("target crash capture: $($_.Exception.Message)")
                $ArchiveFailureKind = Merge-QualificationFailureKind $ArchiveFailureKind 'infrastructure'
            }
            try {
                if ($ResultsPresent) {
                    Copy-SanitizedDeviceEvidenceTree $ConnectedDeviceResults (Join-Path $SelectionEvidence 'results')
                    $ResultsArchived = $true
                }
                if ($ReportsPresent -and -not $ReportsArchived) {
                    Copy-SanitizedDeviceEvidenceTree $ConnectedDeviceReports (Join-Path $SelectionEvidence 'reports')
                    $ReportsArchived = $true
                }
            } catch {
                Assert-QualificationOwnership $_
                $ArchiveFailures.Add("target failure evidence: $($_.Exception.Message)")
                $ArchiveFailureKind = Merge-QualificationFailureKind $ArchiveFailureKind 'infrastructure'
            }
        }
        foreach ($RawOutput in @(
                @{ path = $ConnectedDeviceResults; present = $ResultsPresent },
                @{ path = $ConnectedDeviceReports; present = $ReportsPresent }
            )) {
            if ($RawOutput.present) {
                try {
                    Remove-BroadDeviceLogcatArtifacts $RawOutput.path
                } catch {
                    $ArchiveFailures.Add("raw logcat cleanup: $($_.Exception.Message)")
                    $ArchiveFailureKind = Merge-QualificationFailureKind $ArchiveFailureKind 'cleanup'
                }
            }
        }
        if ($null -eq $InvocationFailure) {
            try { Restore-DeviceUnlocked }
            catch {
                $ArchiveFailures.Add("device unlock restoration: $($_.Exception.Message)")
                $ArchiveFailureKind = Merge-QualificationFailureKind $ArchiveFailureKind 'cleanup'
            }
        }
    }

    $SelectionFailures = [Collections.Generic.List[string]]::new()
    if ($null -ne $InvocationFailure) {
        $SelectionFailures.Add($InvocationFailure.Exception.Message)
    }
    foreach ($ArchiveFailure in $ArchiveFailures) {
        $SelectionFailures.Add($ArchiveFailure)
    }
    $SelectionFailureKind = if ($InvocationFailure) { Get-QualificationFailureKind $InvocationFailure } else { $null }
    if ($ArchiveFailures.Count -gt 0) {
        $SelectionFailureKind = Merge-QualificationFailureKind $SelectionFailureKind $ArchiveFailureKind
    }
    try {
        [ordered]@{
            schemaVersion = 4
            label = $EvidenceLabel
            selectors = $RequestedSelectors
            instrumentationSelectors = $Selectors
            gradleArguments = @($Arguments + @('--no-daemon', '--console=plain'))
            started = $Started.ToString('o')
            finished = (Get-Date).ToString('o')
            status = if ($SelectionFailures.Count -eq 0) { 'PASS' } else { 'FAIL' }
            failure = if ($SelectionFailures.Count -eq 0) {
                $null
            } else {
                $SelectionFailures -join ' | '
            }
            failureKind = $SelectionFailureKind
            resultsArchived = $ResultsArchived
            reportsArchived = $ReportsArchived
            passingMethods = $PassingMethods
            skippedMethods = $SkippedMethods
            failedMethods = $FailedMethods
            artifacts = $Artifacts
            retry = $Retry
        } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (
            Join-Path $SelectionEvidence 'selection.json'
        ) -Encoding UTF8
    } catch {
        $ArchiveFailures.Add("selection result archive: $($_.Exception.Message)")
        $SelectionFailureKind = Merge-QualificationFailureKind $SelectionFailureKind 'infrastructure'
    }

    if ($null -ne $InvocationFailure -and $ArchiveFailures.Count -gt 0) {
        throw (New-QualificationException "$($InvocationFailure.Exception.Message) | $($ArchiveFailures -join ' | ')" $InvocationFailure $SelectionFailureKind)
    }
    if ($null -ne $InvocationFailure) { throw $InvocationFailure }
    if ($ArchiveFailures.Count -gt 0) {
        throw (New-QualificationException "Device result archive failed: $($ArchiveFailures -join ' | ')" $null $SelectionFailureKind)
    }
}

. (Join-Path $PSScriptRoot 'device/Device-Inventory.ps1')

. (Join-Path $PSScriptRoot 'device/Device-Lanes.ps1')

function Resolve-Adb {
    $Command = Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $Command) { return $Command.Source }
    $Candidate = Join-Path (
        [Environment]::GetFolderPath('LocalApplicationData')
    ) 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $Candidate -PathType Leaf) { return $Candidate }
    throw 'Android SDK adb.exe was not found.'
}

function Invoke-AdbChecked {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string[]]$AdbArguments
    )
    Invoke-QualificationOutput $Adb -s $Serial @AdbArguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed with exit code ${LASTEXITCODE}: $($AdbArguments -join ' ')"
    }
}

function Test-AdbPathExists {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $PreviousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        Invoke-QualificationOutput $Adb -s $Serial shell test -e $Path 2>$null
        return $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $PreviousPreference
    }
}

function Invoke-ActionSurfaceTests {
    Require-Serial
    Initialize-ImmutableQualificationArtifacts
    Invoke-DeviceInventoryValidation
    $Manifest = Get-Content -LiteralPath $DeviceLaneManifest -Raw | ConvertFrom-Json
    $ActionSuite = @(
        $Manifest.explicitSuites |
            Where-Object { [string]$_.wrapper -eq $ActionSuiteClass }
    )
    if ($ActionSuite.Count -ne 1) {
        throw "Device lane manifest must declare exactly one $ActionSuiteClass suite."
    }
    $Children = @($ActionSuite[0].children | ForEach-Object { [string]$_ })
    if ($Children.Count -eq 0 -or
            @($Children | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
        throw "$ActionSuiteClass has no declared children."
    }
    $ActionFailures = [Collections.Generic.List[string]]::new()
    $ActionCauses = [Collections.Generic.List[Exception]]::new()
    $ActionFailureKind = $null
    try {
        Reset-DisposableDevicePackages
        foreach ($Child in $Children) {
            try {
                Invoke-StandardDeviceSelection @($Child) `
                    -EvidenceLabel ("actions-" + $Child.Split('.')[-1])
            } catch {
                Assert-QualificationOwnership $_
                $ActionFailures.Add("${Child}: $($_.Exception.Message)")
                $ActionCauses.Add($_.Exception)
                $ActionFailureKind = Merge-QualificationFailureKind $ActionFailureKind (Get-QualificationFailureKind $_)
                try { Reset-DisposableDevicePackages } catch {
                    throw (New-QualificationException 'Actions reset failed.' $_ 'cleanup')
                }
            }
        }
        $ExpectedMethods = @(
            $script:DeviceInventoryAccount.methods |
                Where-Object { [string]$_.class -cin $Children -and -not $_.excluded } |
                ForEach-Object { [string]$_.id }
        )
        Assert-InstrumentationMethodAccounting $ExpectedMethods 'actions'
    } catch {
        Assert-QualificationOwnership $_
        $ActionFailures.Add("actions: $($_.Exception.Message)")
        $ActionCauses.Add($_.Exception)
        $ActionFailureKind = Merge-QualificationFailureKind $ActionFailureKind (Get-QualificationFailureKind $_)
    } finally {
        Assert-QualificationOwnership
        try {
            Reset-DisposableDevicePackages
        } catch {
            Assert-QualificationOwnership $_
            $ActionFailures.Add("actions cleanup: $($_.Exception.Message)")
            $ActionCauses.Add($_.Exception)
            $ActionFailureKind = Merge-QualificationFailureKind $ActionFailureKind 'cleanup'
        }
    }
    if ($ActionFailures.Count -gt 0) {
        throw (New-QualificationException "Action surface failures: $($ActionFailures -join ' | ')" (
            [AggregateException]::new([Exception[]]$ActionCauses.ToArray())
        ) $ActionFailureKind)
    }
}

if ($Mode -in @('Pc', 'PcPlan')) {
    if (-not $PcTest.Count) { throw 'Pc/PcPlan requires -PcTest with exact module or module.Class.method selectors.' }
    $Plans = @(Get-PcTestPlan $PcTest)
    if ($Mode -eq 'PcPlan') {
        ConvertTo-Json -InputObject $Plans -Depth 5
    } else {
        foreach ($Plan in $Plans) { Invoke-Checked $Plan.FilePath $Plan.Arguments $LiveRoot -Phase 'pc-runtime-and-tests' }
    }
    return
}

if ($Mode -eq 'CleanCheckout') {
    Invoke-WithWorkspaceMutex {
        $BuilderWsl = Convert-ToWslPath $PcBuilder
        $SuiteWsl = Convert-ToWslPath $Suite
        Invoke-Checked 'wsl.exe' @('-d', (Get-TestRuntimeLayout).wslDistribution, '--', "$BuilderWsl/.tools/bootstrap/bin/python", '-B',
            "$SuiteWsl/pc/clean_checkout.py") $LiveRoot -TimeoutSeconds 300 -Phase 'clean-source-install-recovery'
        if ($CompileAndroid) {
            $Python = (Get-Command python -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
            Invoke-Checked $Python @('-B', (Join-Path $Suite 'pc/clean_checkout.py'), '--android') $LiveRoot `
                -TimeoutSeconds 2100 -Phase 'clean-source-android-native-compile'
        }
    }
    return
}

. (Join-Path $Suite 'device/Focused-Checks.ps1')

function Invoke-TesterMode {
Initialize-ApprovedDeviceProfile
switch ($Mode) {
    { $_ -in @('Focused', 'NativeDevice') } {
        Invoke-WithWorkspaceMutex {
            Invoke-RecordedDeviceOperation {
                Invoke-WithDeviceMutex { Invoke-FocusedChecks }
            }
        }
    }
    'Host' {
        Invoke-WithWorkspaceMutex {
            Invoke-HostTests
        }
    }
    'Native' { Invoke-WithWorkspaceMutex { Invoke-NativeTests } }
    'Android' { Invoke-WithWorkspaceMutex { Invoke-AndroidHostTests } }
    { $_ -in @('AudioPlan', 'AudioCheck') } {
        Invoke-WithWorkspaceMutex {
            Initialize-SuitePlan
            if ($Mode -ceq 'AudioCheck') { Invoke-AudioChecks }
        }
    }
    'AudioQualify' { Invoke-CompleteSuites -Scope 'Audio' }
    'PcSuite' {
        Invoke-WithWorkspaceMutex { Initialize-SuitePlan }
        Invoke-CompletePcChecks -Scope 'All'
    }
    'AndroidSuite' {
        Require-Serial
        Assert-DeviceUnlockPrepared
        Invoke-WithWorkspaceMutex { Initialize-SuitePlan }
        Invoke-CompleteAndroidChecks -Scope 'All'
    }
    'AudioAggregate' { Invoke-WithWorkspaceMutex { Invoke-AudioAggregate } }
    'Lint' { Invoke-WithWorkspaceMutex { Invoke-Gradle @(':app:lintDevelopment') } }
    'Build' {
        Invoke-WithWorkspaceMutex {
            Publish-DevelopmentApk
        }
    }
    'DeviceValidate' { Invoke-DeviceInventoryValidation }
    'Device' {
        Invoke-WithWorkspaceMutex {
            Invoke-RecordedDeviceOperation {
                Invoke-WithDeviceMutex { Invoke-DeviceTests }
            }
        }
    }
    'Actions' {
        Invoke-WithWorkspaceMutex {
            Invoke-RecordedDeviceOperation {
                Invoke-WithDeviceMutex { Invoke-ActionSurfaceTests }
            }
        }
    }
    'Full' { Invoke-CompleteSuites -Scope 'All' -AndroidFirst:$AndroidFirst }
}
}

Invoke-TesterMode

Write-Host "Tester $Mode operation completed."
