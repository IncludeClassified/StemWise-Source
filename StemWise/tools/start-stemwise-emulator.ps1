param(
    [string]$AvdName = "StemWise_API_36",
    [switch]$Headless,
    [ValidateRange(512, 32768)][int]$MemoryMb,
    [ValidateSet('auto', 'host', 'software', 'swiftshader')][string]$Gpu
)

$ErrorActionPreference = "Stop"
$sdk = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$emulator = Join-Path $sdk "emulator\emulator.exe"
$adb = Join-Path $sdk "platform-tools\adb.exe"

if (!(Test-Path -LiteralPath $emulator)) {
    throw "Android Emulator is not installed at $emulator"
}
if (!(Test-Path -LiteralPath $adb)) {
    throw "Android Debug Bridge is not installed at $adb"
}

function Find-RequestedAvdSerial {
    foreach ($device in (& $adb devices 2>$null)) {
        if ($device -notmatch '^(emulator-[0-9]+)\s+device$') {
            continue
        }
        $candidateSerial = $Matches[1]
        $reportedName = @(& $adb -s $candidateSerial emu avd name 2>$null) |
            Where-Object { $_ -and $_.Trim() -ne "OK" } |
            Select-Object -First 1
        if ($reportedName -and $reportedName.Trim() -ceq $AvdName) {
            return $candidateSerial
        }
    }
    return $null
}

$availableAvds = @(& $emulator -list-avds) |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ }
if ($LASTEXITCODE -ne 0) {
    throw "Could not list Android Virtual Devices with $emulator"
}
if (-not ($availableAvds -ccontains $AvdName)) {
    throw "Android Virtual Device '$AvdName' is not installed. Available AVDs: $($availableAvds -join ', ')"
}

$serial = Find-RequestedAvdSerial
if (!$serial) {
    $emulatorArguments = @(
        "-avd", $AvdName,
        "-no-snapshot",
        "-no-boot-anim"
    )
    if ($PSBoundParameters.ContainsKey('MemoryMb')) { $emulatorArguments += @('-memory', [string]$MemoryMb) }
    if ($PSBoundParameters.ContainsKey('Gpu')) { $emulatorArguments += @('-gpu', $Gpu) }
    $windowStyle = "Hidden"
    if ($Headless) {
        $emulatorArguments += "-no-window"
        $windowStyle = "Hidden"
    }
    Start-Process -FilePath $emulator -ArgumentList $emulatorArguments -WindowStyle $windowStyle |
        Out-Null
}

for ($attempt = 0; $attempt -lt 90; $attempt++) {
    $serial = Find-RequestedAvdSerial
    if ($serial -and (& $adb -s $serial shell getprop sys.boot_completed 2>$null) -eq "1") {
        Write-Output $serial
        exit 0
    }
    Start-Sleep -Seconds 2
}

throw "The $AvdName emulator did not finish booting."
