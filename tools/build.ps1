param([switch]$AppImage, [switch]$FullWhisper)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$output = Join-Path $project 'target'
$appInput = Join-Path $output $(if ($FullWhisper) { 'standalone-app-full' } else { 'standalone-app' })
$libraries = Join-Path $appInput 'lib'
New-Item -ItemType Directory -Path $appInput,$libraries -Force | Out-Null

& (Join-Path $PSScriptRoot 'build-audio.ps1') | Out-Null
& mvn -B -ntp --file (Join-Path $project 'pom.xml') '-DskipTests' package | Out-Host
if ($LASTEXITCODE -ne 0) { throw "Maven build failed ($LASTEXITCODE)." }

$jar = Join-Path $appInput 'lecture-notes.jar'
Copy-Item -LiteralPath (Join-Path $output 'lectureNotes-1.0-SNAPSHOT.jar') -Destination $jar
Get-ChildItem -LiteralPath (Join-Path $output 'lib') -Filter '*.jar' |
    Copy-Item -Destination $libraries
Copy-Item -LiteralPath (Join-Path $output 'native/audio-capture.exe') -Destination $appInput
& (Join-Path $PSScriptRoot 'build-whisper.ps1') -Destination (Join-Path $appInput 'whisper') -Full:$FullWhisper
& (Join-Path $PSScriptRoot 'build-diarization.ps1') -Destination (Join-Path $appInput 'diarization')
$fxModules = 'javafx.controls,javafx.media'
$nativeAccess = '--enable-native-access=javafx.graphics,javafx.media,ALL-UNNAMED'
& java --module-path $libraries --add-modules $fxModules "$nativeAccess,org.xerial.sqlitejdbc" -jar $jar --check
if ($LASTEXITCODE -ne 0) { throw "JAR startup check failed ($LASTEXITCODE)." }

if ($AppImage) {
    $distribution = Join-Path $output ('dist/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $distribution -Force | Out-Null
    $temporary = Join-Path $output ('jpackage/' + [guid]::NewGuid().ToString('N'))
    $javafxJars = Get-ChildItem -LiteralPath $libraries -Filter 'javafx-*-win.jar' | ForEach-Object FullName
    $jdkBin = Split-Path -Parent (Get-Command jpackage).Source
    $jdkModules = Join-Path (Split-Path -Parent $jdkBin) 'jmods'
    $modulePath = (@($jdkModules) + @($javafxJars)) -join [IO.Path]::PathSeparator
    $packageOptions = @('--type', 'app-image', '--name', 'LectureNotes', '--dest', $distribution,
        '--temp', $temporary, '--input', $appInput, '--main-jar', 'lecture-notes.jar',
        '--main-class', 'org.example.Main', '--module-path', $modulePath,
        '--add-modules', "java.desktop,java.net.http,java.sql,jdk.zipfs,$fxModules",
        '--java-options', "--add-modules=$fxModules", '--java-options', $nativeAccess)
    & jpackage @packageOptions
    if ($LASTEXITCODE -ne 0) { throw "Windows packaging failed ($LASTEXITCODE)." }
    $launcher = Join-Path $distribution 'LectureNotes/LectureNotes.exe'
    $check = Start-Process -FilePath $launcher -ArgumentList '--check' -WindowStyle Hidden -Wait -PassThru
    if ($check.ExitCode -ne 0) { throw "Packaged application startup check failed ($($check.ExitCode))." }
    Get-Item -LiteralPath $launcher
}
