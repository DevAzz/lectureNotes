param([switch]$AppImage)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$output = Join-Path $project 'target'
$classes = Join-Path $output 'standalone-classes'
$appInput = Join-Path $output 'standalone-app'
New-Item -ItemType Directory -Path $classes,$appInput -Force | Out-Null

& (Join-Path $PSScriptRoot 'build-audio.ps1') | Out-Null
$sources = Get-ChildItem -LiteralPath (Join-Path $project 'src/main/java') -Filter '*.java' -Recurse |
    ForEach-Object FullName
& javac --release 25 -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "Java compilation failed ($LASTEXITCODE)." }

$jar = Join-Path $appInput 'lecture-notes.jar'
& jar --create --file $jar --main-class org.example.Main -C $classes . -C (Join-Path $project 'src/main/resources') .
if ($LASTEXITCODE -ne 0) { throw "JAR creation failed ($LASTEXITCODE)." }
Copy-Item -LiteralPath (Join-Path $output 'native/audio-capture.exe') -Destination $appInput
& java -jar $jar --check
if ($LASTEXITCODE -ne 0) { throw "JAR startup check failed ($LASTEXITCODE)." }

if ($AppImage) {
    $distribution = Join-Path $output ('dist/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $distribution -Force | Out-Null
    $temporary = Join-Path $output ('jpackage/' + [guid]::NewGuid().ToString('N'))
    $packageOptions = @('--type', 'app-image', '--name', 'LectureNotes', '--dest', $distribution,
        '--temp', $temporary, '--input', $appInput, '--main-jar', 'lecture-notes.jar',
        '--main-class', 'org.example.Main', '--add-modules', 'java.desktop,java.net.http,jdk.httpserver')
    & jpackage @packageOptions
    if ($LASTEXITCODE -ne 0) { throw "Windows packaging failed ($LASTEXITCODE)." }
    Get-Item -LiteralPath (Join-Path $distribution 'LectureNotes/LectureNotes.exe')
}
