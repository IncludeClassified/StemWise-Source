function Write-StemWiseJsonAtomic {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )

    $FullPath = [IO.Path]::GetFullPath($Path)
    $Parent = Split-Path -Parent $FullPath
    if (-not (Test-Path -LiteralPath $Parent -PathType Container)) {
        New-Item -ItemType Directory -Force -Path $Parent | Out-Null
    }
    $Temporary = "$FullPath.$PID.$([Guid]::NewGuid().ToString('N')).tmp"
    $Utf8NoBom = New-Object Text.UTF8Encoding($false)
    $Json = $Value | ConvertTo-Json -Depth 6
    try {
        $Bytes = $Utf8NoBom.GetBytes($Json)
        $Stream = [IO.FileStream]::new(
            $Temporary,
            [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write,
            [IO.FileShare]::None,
            4096,
            [IO.FileOptions]::WriteThrough
        )
        try {
            $Stream.Write($Bytes, 0, $Bytes.Length)
            $Stream.Flush($true)
        } finally {
            $Stream.Dispose()
        }
        if (Test-Path -LiteralPath $FullPath -PathType Leaf) {
            $Backup = "$FullPath.$PID.$([Guid]::NewGuid().ToString('N')).bak"
            try {
                [IO.File]::Replace($Temporary, $FullPath, $Backup, $true)
            } finally {
                if (Test-Path -LiteralPath $Backup -PathType Leaf) {
                    Remove-Item -LiteralPath $Backup -Force -ErrorAction SilentlyContinue
                }
            }
        } else {
            [IO.File]::Move($Temporary, $FullPath)
        }
    } finally {
        if (Test-Path -LiteralPath $Temporary -PathType Leaf) {
            Remove-Item -LiteralPath $Temporary -Force -ErrorAction SilentlyContinue
        }
    }
}
