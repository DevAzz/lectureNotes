param([switch]$LiveModel)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build.ps1')
$fixture = & (Join-Path $PSScriptRoot 'check-audio.ps1')
$work = Join-Path $project ('target/processing-check/' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work -Force | Out-Null

$jar = Join-Path $project 'target/standalone-app/lecture-notes.jar'
$sources = Get-ChildItem -LiteralPath (Join-Path $project 'src/main/java') -Filter '*.java' -Recurse |
    ForEach-Object FullName
$fixtureSource = Join-Path $project 'tools/fixtures/ProcessingCheck.java'
& javac --release 25 -encoding UTF-8 -d $work $sources $fixtureSource
if ($LASTEXITCODE -ne 0) { throw "Processing check compilation failed ($LASTEXITCODE)." }
$appParams = @($work, $fixture.Recording)
if ($LiveModel) { $appParams += 'http://127.0.0.1:47831/v1' }
& java -cp "$jar;$work" org.example.ProcessingCheck @appParams
if ($LASTEXITCODE -ne 0) { throw "Processing check failed ($LASTEXITCODE)." }
