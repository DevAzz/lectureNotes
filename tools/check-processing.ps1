param([switch]$LiveModel, [string]$SpeechWav)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build.ps1')
$fixture = & (Join-Path $PSScriptRoot 'check-audio.ps1')
$work = Join-Path $project ('target/processing-check/' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work -Force | Out-Null

$jar = Join-Path $project 'target/standalone-app/lecture-notes.jar'
$fixtureSource = Join-Path $project 'tools/fixtures/ProcessingCheck.java'
& javac --release 25 -encoding UTF-8 -cp $jar -d $work $fixtureSource
if ($LASTEXITCODE -ne 0) { throw "Processing check compilation failed ($LASTEXITCODE)." }
$appParams = @($work, $fixture.Recording)
if ($LiveModel) { $appParams += @('--live-model', 'http://127.0.0.1:47831/v1') }
if ($SpeechWav) { $appParams += @('--speech-wav', (Resolve-Path -LiteralPath $SpeechWav).Path) }
& java '--enable-native-access=ALL-UNNAMED' -cp "$jar;$work" org.example.ProcessingCheck @appParams
if ($LASTEXITCODE -ne 0) { throw "Processing check failed ($LASTEXITCODE)." }
