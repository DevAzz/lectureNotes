function Get-BuildAsset($Cache, $Name, $Url, $Sha256) {
    $file = Join-Path $Cache $Name
    if ((Test-Path -LiteralPath $file -PathType Leaf) -and
            (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -eq $Sha256) {
        return $file
    }
    $download = "$file.$([guid]::NewGuid().ToString('N')).part"
    try {
        & curl.exe --fail --show-error --silent --location --retry 2 --connect-timeout 15 --max-time 1800 --output $download $Url
        if ($LASTEXITCODE -ne 0) { throw "Could not download $Name (curl exit $LASTEXITCODE)." }
        if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $Sha256) {
            throw "SHA-256 mismatch for $Name."
        }
        Move-Item -LiteralPath $download -Destination $file -Force
    } finally {
        if (Test-Path -LiteralPath $download) { Remove-Item -LiteralPath $download }
    }
    return $file
}
