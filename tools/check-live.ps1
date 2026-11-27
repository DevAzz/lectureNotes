param(
    [Parameter(Mandatory)][string]$SpeechWav,
    [string]$AppDirectory = 'target/standalone-runtime',
    [string]$SilentWav
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$app = (Resolve-Path -LiteralPath $AppDirectory).Path
$audio = (Resolve-Path -LiteralPath $SpeechWav).Path
$work = Join-Path $project ('target/live-check/' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work -Force | Out-Null

$jar = Join-Path $app 'lecture-notes.jar'
$fixture = Join-Path $PSScriptRoot 'fixtures/LiveCaptureCheck.java'
& javac --release 25 -encoding UTF-8 -cp $jar -d $work $fixture
if ($LASTEXITCODE -ne 0) { throw "Live capture check compilation failed ($LASTEXITCODE)." }
$fixtureArguments = @($work, $audio)
if ($SilentWav) { $fixtureArguments += (Resolve-Path -LiteralPath $SilentWav).Path }
& java '--enable-native-access=ALL-UNNAMED' -cp "$jar;$work" org.example.LiveCaptureCheck @fixtureArguments
if ($LASTEXITCODE -ne 0) { throw "Live capture check failed ($LASTEXITCODE)." }
