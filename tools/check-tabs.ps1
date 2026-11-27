$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build.ps1') | Out-Null

$work = Join-Path $project ('target/tabs-check/' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work -Force | Out-Null
$jar = Join-Path $project 'target/standalone-runtime/lecture-notes.jar'
$libraries = Join-Path $project 'target/standalone-runtime/lib'
$fixture = Join-Path $project 'tools/fixtures/LibraryTabsCheck.java'

& javac --release 25 -encoding UTF-8 --module-path $libraries --add-modules 'javafx.controls,javafx.media' `
    -cp $jar -d $work $fixture
if ($LASTEXITCODE -ne 0) { throw "Tabs check compilation failed ($LASTEXITCODE)." }

& java --module-path $libraries --add-modules 'javafx.controls,javafx.media' `
    '--enable-native-access=javafx.graphics,javafx.media,org.xerial.sqlitejdbc,ALL-UNNAMED' `
    -cp "$jar;$work" org.example.LibraryTabsCheck (Join-Path $work 'library')
if ($LASTEXITCODE -ne 0) { throw "Tabs check failed ($LASTEXITCODE)." }
Write-Output 'Subjects and settings tabs verified'
