param([string]$AppDirectory)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if ($AppDirectory) {
    $app = (Resolve-Path -LiteralPath $AppDirectory).Path
} else {
    & (Join-Path $PSScriptRoot 'build.ps1')
    $app = Join-Path $project 'target/standalone-runtime'
}
$work = Join-Path $project ('target/meeting-check/' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work -Force | Out-Null

$classPath = "$app/lecture-notes.jar;$app/lib/*"
$fixture = Join-Path $PSScriptRoot 'fixtures/MeetingCheck.java'
& javac --release 25 -encoding UTF-8 -cp $classPath -d $work $fixture
if ($LASTEXITCODE -ne 0) { throw "Meeting check compilation failed ($LASTEXITCODE)." }
& java -cp "$classPath;$work" org.example.MeetingCheck $work
if ($LASTEXITCODE -ne 0) { throw "Meeting check failed ($LASTEXITCODE)." }
