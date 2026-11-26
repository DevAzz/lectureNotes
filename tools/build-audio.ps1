$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$sources = Get-ChildItem -LiteralPath (Join-Path $project 'src/main/native') -Filter '*.cs' |
    ForEach-Object FullName
$outputDir = Join-Path $project 'target/native'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
$output = Join-Path $outputDir 'audio-capture.exe'
& $compiler /nologo /target:exe /platform:x64 /optimize+ /reference:System.Security.dll "/out:$output" $sources
if ($LASTEXITCODE -ne 0) { throw "Audio helper compilation failed ($LASTEXITCODE)." }
Get-Item -LiteralPath $output
