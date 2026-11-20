function Initialize-ApprovedDeviceProfile {
    if ([string]::IsNullOrWhiteSpace($DeviceProfile)) {
        if ($Mode -cin @('Device', 'Actions', 'Full', 'Focused', 'NativeDevice', 'AndroidSuite', 'AudioQualify')) {
            throw "$Mode requires -DeviceProfile with the exact target identity."
        }
        return
    }
    $ProfilePath = [IO.Path]::GetFullPath($DeviceProfile)
    $Profile = Get-Content -LiteralPath $ProfilePath -Raw | ConvertFrom-Json
    $Fields = @('serial', 'model', 'device', 'socModel', 'androidBuildFingerprint', 'api')
    $Names = @($Profile.PSObject.Properties.Name)
    if (@($Fields | Where-Object { $_ -cnotin $Names }).Count -or
            @($Names | Where-Object { $_ -cnotin ($Fields + @('purpose')) }).Count) {
        throw 'Device profile requires the six device identity fields and an optional purpose.'
    }
    if ('purpose' -cin $Names -and $Profile.purpose -cnotin @('everyday', 'dedicated-qualification')) {
        throw 'Device profile purpose must be everyday or dedicated-qualification.'
    }
    $script:ApprovedDevicePurpose = $Profile.purpose
    foreach ($Field in $Fields | Where-Object { $_ -cne 'api' }) {
        if ($Profile.$Field -isnot [string] -or [string]::IsNullOrWhiteSpace($Profile.$Field)) {
            throw "Device profile is missing $Field."
        }
    }
    $Api = 0
    if (($Profile.api -isnot [int] -and $Profile.api -isnot [long]) -or
            -not [int]::TryParse([string]$Profile.api, [ref]$Api) -or $Api -lt 28 -or
            [string]$Profile.serial -cnotmatch '^[A-Za-z0-9._:-]+$') {
        throw 'Device profile has an invalid API or serial.'
    }
    $script:ApprovedDeviceSerial = [string]$Profile.serial
    $script:ApprovedDeviceModel = [string]$Profile.model
    $script:ApprovedDeviceCodeName = [string]$Profile.device
    $script:ApprovedDeviceSocModel = [string]$Profile.socModel
    $script:ApprovedDeviceBuildFingerprint = [string]$Profile.androidBuildFingerprint
    $script:ApprovedDeviceApi = [string]$Api
    $script:CurrentDeviceProfileFile = Get-Item -LiteralPath $ProfilePath
}

function Require-Serial {
    if ([string]::IsNullOrWhiteSpace($Serial) -or $null -eq $script:CurrentDeviceProfileFile) {
        throw "$Mode requires -Serial and -DeviceProfile for the exact target device."
    }
    if ($Serial -cne $ApprovedDeviceSerial) {
        throw "Refusing unapproved device serial $Serial; expected $ApprovedDeviceSerial."
    }

    $Adb = Resolve-Adb
    $State = Invoke-AdbReadOnlyProbe $Adb @('get-state') 'state'
    $ActualSerial = Invoke-AdbReadOnlyProbe $Adb @('get-serialno') 'serial'
    $Model = Invoke-AdbReadOnlyProbe $Adb @('shell', 'getprop', 'ro.product.model') 'model'
    $CodeName = Invoke-AdbReadOnlyProbe $Adb @('shell', 'getprop', 'ro.product.device') 'device'
    $SocModel = Invoke-AdbReadOnlyProbe $Adb @('shell', 'getprop', 'ro.soc.model') 'SoC model'
    $BuildFingerprint = Invoke-AdbReadOnlyProbe $Adb @('shell', 'getprop', 'ro.build.fingerprint') 'build fingerprint'
    $Api = Invoke-AdbReadOnlyProbe $Adb @('shell', 'getprop', 'ro.build.version.sdk') 'API'
    if ($State -cne 'device' -or
            $ActualSerial -cne $ApprovedDeviceSerial -or
            $Model -cne $ApprovedDeviceModel -or
            $CodeName -cne $ApprovedDeviceCodeName -or
            $SocModel -cne $ApprovedDeviceSocModel -or
            $BuildFingerprint -cne $ApprovedDeviceBuildFingerprint -or
            $Api -cne $ApprovedDeviceApi) {
        throw "Refusing device profile state=$State serial=$ActualSerial model=$Model device=$CodeName api=$Api."
    }
    $script:ValidatedDeviceProfile = [ordered]@{
        serial = $ActualSerial; model = $Model; device = $CodeName
        socModel = $SocModel; androidBuildFingerprint = $BuildFingerprint; api = [int]$Api
    }
}

function Get-InstalledDevelopmentApkIdentity {
    param([string]$Adb, [string]$Package)
    $Location = Invoke-AdbReadOnlyProbe $Adb @('shell', 'pm', 'path', $Package) 'installed Development APK path'
    if ($Location -cnotmatch '^package:(/data/app/[^\s]+/base\.apk)$') {
        throw "Refusing an unrecognized installed Development package: $Package"
    }
    $RemotePath = $Matches[1]
    $RemoteHash = Invoke-AdbReadOnlyProbe $Adb @('shell', 'sha256sum', $RemotePath) 'installed Development APK digest'
    if ($RemoteHash -cnotmatch '^([0-9a-f]{64})\s') { throw 'Installed Development digest was not readable.' }
    $ExpectedHash = $Matches[1]
    $Work = [IO.Path]::GetFullPath((Join-Path $Suite 'work'))
    $Scratch = [IO.Path]::GetFullPath((Join-Path $Work ('device-admission-' + [guid]::NewGuid().ToString('N'))))
    if ([IO.Path]::GetDirectoryName($Scratch) -cne $Work) { throw 'Device admission scratch escaped its owner.' }
    New-Item -ItemType Directory -Path $Scratch -Force | Out-Null
    try {
        $Apk = Join-Path $Scratch 'installed.apk'
        Invoke-AdbChecked $Adb @('pull', $RemotePath, $Apk)
        $Evidence = Get-ApkEvidence $Apk 'installed-qualification' 'androidTest'
        if ($Evidence.applicationId -cne $Package -or $Evidence.sha256.ToLowerInvariant() -cne $ExpectedHash) {
            throw 'Installed Development readback changed identity or bytes.'
        }
        return $Evidence
    } catch { Assert-QualificationOwnership $_; throw } finally {
        Assert-QualificationOwnership
        if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Scratch)) -cne $Work) { throw 'Device admission cleanup escaped its owner.' }
        Remove-Item -LiteralPath $Scratch -Recurse -Force -ErrorAction Stop
    }
}

function Assert-DisposableDevelopmentTarget {
    param([string]$Adb)
    Require-Serial
    if ($script:DisposableDevelopmentTargetSerial -ceq $Serial) { return }
    $Users = Invoke-AdbReadOnlyProbe $Adb @('shell', 'pm', 'list', 'users') 'Android users'
    $UserIds = @([regex]::Matches($Users, 'UserInfo\{([0-9]+):') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    if ($UserIds.Count -eq 0 -or '0' -cnotin $UserIds) { throw 'Could not establish the Android user inventory.' }
    foreach ($UserId in $UserIds) {
        foreach ($Package in @($DevelopmentPackage, "$DevelopmentPackage.test")) {
            $Packages = @(Invoke-QualificationOutput $Adb -s $Serial shell pm list packages --user $UserId $Package |
                ForEach-Object { $_.ToString() })
            Assert-AdbPackageProbeSucceeded $LASTEXITCODE $Package $Packages
            if (-not (Test-AdbPackageListed $Package $Packages)) { continue }
            if ($UserId -cne '0') { throw 'Development exists in another Android user; global package replacement is refused.' }
            if ($script:ApprovedDevicePurpose -cne 'dedicated-qualification') {
                throw 'Existing Development data is preserved. Use a declared dedicated-qualification target for destructive tests.'
            }
            Resolve-QualificationSigningConfiguration
            $Installed = Get-InstalledDevelopmentApkIdentity $Adb $Package
            if ($Installed.certificateSha256 -cne $QualificationCertificateSha256 -or
                    $QualificationCertificateSha256 -cnotmatch '^[0-9A-F]{64}$') {
                throw 'Existing Development is not signed by the protected qualification identity; its data is preserved.'
            }
        }
    }
    $script:DisposableDevelopmentTargetSerial = $Serial
}
