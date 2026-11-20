[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Request)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
. (Join-Path $PSScriptRoot 'Owned-Process.ps1')
try {
    $InputObject=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Request)) | ConvertFrom-Json
    $Result=Invoke-QualificationProcess -FilePath $InputObject.command[0] -Arguments @($InputObject.command | Select-Object -Skip 1) -WorkingDirectory $InputObject.cwd -TimeoutSeconds $InputObject.timeout -OutputLog $InputObject.outputLog
    [ordered]@{exitCode=$Result.ExitCode;stdout=$Result.Stdout;stderr=$Result.Stderr;failureKind=$null} | ConvertTo-Json -Compress
} catch {
    [ordered]@{exitCode=$null;stdout=[string]$_.Exception.Data['stdout'];stderr=[string]$_.Exception.Data['stderr'];failureKind=$(if ($_.Exception.Data['failureKind']) {$_.Exception.Data['failureKind']} else {'infrastructure'});message=$_.Exception.Message} | ConvertTo-Json -Compress
    exit 1
}
