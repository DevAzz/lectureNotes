param([switch]$Chrome)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$helper = Join-Path $project 'target/native/audio-capture.exe'
if (-not (Test-Path -LiteralPath $helper)) { & (Join-Path $PSScriptRoot 'build-audio.ps1') | Out-Null }
$work = Join-Path $project ('target/audio-check/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null

function New-Tone($Path, $Frequency) {
    $writer = New-Object IO.BinaryWriter ([IO.File]::Create($Path))
    try {
        $rate = 44100
        $samples = $rate
        $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF'))
        $writer.Write([int](36 + 2 * $samples))
        $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
        $writer.Write([int]16)
        $writer.Write([int16]1)
        $writer.Write([int16]1)
        $writer.Write([int]$rate)
        $writer.Write([int](2 * $rate))
        $writer.Write([int16]2)
        $writer.Write([int16]16)
        $writer.Write([Text.Encoding]::ASCII.GetBytes('data'))
        $writer.Write([int](2 * $samples))
        for ($index = 0; $index -lt $samples; $index++) {
            $writer.Write([int16](12000 * [Math]::Sin(2 * [Math]::PI * $Frequency * $index / $rate)))
        }
    } finally { $writer.Dispose() }
}

function Start-Player($Path) {
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = Join-Path $PSHOME 'powershell.exe'
    $start.Arguments = "-NoProfile -NonInteractive -File `"$work/player.ps1`" `"$Path`""
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    [Diagnostics.Process]::Start($start)
}

function Wait-ChromePlayback($Process, $LogPath) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $logLines = @()
    do {
        if (Test-Path -LiteralPath $LogPath) {
            $logLines = @(Get-Content -LiteralPath $LogPath -ErrorAction SilentlyContinue)
            $failure = $logLines | Select-String -SimpleMatch 'LECTURENOTES_AUDIO_FAILED'
            if ($failure) { throw "Chrome could not play the test tone. $failure" }
            if ($logLines | Select-String -SimpleMatch 'LECTURENOTES_AUDIO_PLAYING') { return }
        }
        if ($Process.HasExited) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    $reason = if ($Process.HasExited) {
        "Chrome exited ($($Process.ExitCode))"
    } elseif ($logLines | Select-String -SimpleMatch 'LECTURENOTES_AUDIO_PAGE_LOADED') {
        'Chrome loaded the page but did not start playback within 15 seconds'
    } else {
        'Chrome did not load the test page within 15 seconds'
    }
    throw "$reason. Browser log: $LogPath"
}

Set-Content -LiteralPath (Join-Path $work 'player.ps1') -Encoding ASCII -Value @'
param([string]$Wave)
$player = New-Object Media.SoundPlayer $Wave
while ($true) { $player.PlaySync() }
'@
New-Tone (Join-Path $work 'in.wav') 440
New-Tone (Join-Path $work 'out.wav') 997

$target = $null
$external = $null
$capture = $null
try {
    if ($Chrome) {
        $chromePath = Join-Path $env:ProgramFiles 'Google/Chrome/Application/chrome.exe'
        $page = Join-Path $work 'tone.html'
        $chromeLog = Join-Path $work 'chrome.log'
        Set-Content -LiteralPath $page -Encoding ASCII -Value @'
<!doctype html>
<title>LectureNotes audio test</title>
<audio loop></audio>
<script>
console.info('LECTURENOTES_AUDIO_PAGE_LOADED');
const audio = document.querySelector('audio');
audio.addEventListener('playing', () => console.info('LECTURENOTES_AUDIO_PLAYING'), {once: true});
audio.addEventListener('error', () => console.error('LECTURENOTES_AUDIO_FAILED', audio.error.code), {once: true});
audio.src = 'in.wav';
audio.play().catch(error => console.error('LECTURENOTES_AUDIO_FAILED', error.name));
</script>
'@
        $target = Start-Process -FilePath $chromePath -PassThru -WindowStyle Hidden -ArgumentList @(
            "--user-data-dir=`"$work/chrome`"", '--no-first-run', '--no-default-browser-check', '--disable-background-mode',
            '--enable-logging', "--log-file=`"$chromeLog`"",
            '--autoplay-policy=no-user-gesture-required', '--new-window', ([Uri]$page).AbsoluteUri)
        Wait-ChromePlayback $target $chromeLog
    } else {
        $target = Start-Player (Join-Path $work 'in.wav')
    }
    $external = Start-Player (Join-Path $work 'out.wav')
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $helper
    $remainder = 0L
    $fileTimeMillis = [Math]::DivRem($target.StartTime.ToFileTimeUtc(), 10000L, [ref]$remainder)
    $start.Arguments = "capture-ms $($target.Id) $fileTimeMillis `"$work`""
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $capture = [Diagnostics.Process]::Start($start)
    Start-Sleep -Seconds 6
    if (-not $capture.HasExited) { $capture.StandardInput.Close() }
    if (-not $capture.WaitForExit(15000)) { throw 'Capture did not stop.' }
    $eventLog = $capture.StandardOutput.ReadToEnd()
    if ($capture.ExitCode -ne 0) { throw "Capture failed ($($capture.ExitCode)) $eventLog" }
    $wave = Get-ChildItem -LiteralPath $work -Filter '?????-*.wav' | Sort-Object Name | Select-Object -Last 1
    if ($null -eq $wave) { throw "No chunks written. $eventLog" }
    $samples = [IO.File]::ReadAllBytes($wave.FullName)
    if ($samples.Length -lt (44 + 44100 * 4)) { throw 'Captured less than one second.' }
    $format = [BitConverter]::ToUInt16($samples, 20)
    $channels = [BitConverter]::ToUInt16($samples, 22)
    $rate = [BitConverter]::ToUInt32($samples, 24)
    $bits = [BitConverter]::ToUInt16($samples, 34)
    if ($format -ne 1 -or $channels -ne 2 -or $rate -ne 44100 -or $bits -ne 16) {
        throw "Unexpected WAV format (tag $format, $channels channels, $rate Hz, $bits bits)."
    }

    # DFT at the two known frequencies over the final second of the chunk.
    function Measure-Tone($Frequency) {
        $real = $imaginary = 0.0
        $offset = $samples.Length - 44100 * 4
        for ($index = 0; $index -lt 44100; $index++) {
            $sample = [BitConverter]::ToInt16($samples, [int]($offset + 4 * $index))
            $angle = 2 * [Math]::PI * $Frequency * $index / 44100
            $real += $sample * [Math]::Cos($angle)
            $imaginary += $sample * [Math]::Sin($angle)
        }
        2 * [Math]::Sqrt($real * $real + $imaginary * $imaginary) / 44100
    }
    $included = Measure-Tone 440
    $excluded = Measure-Tone 997
    if ($included -lt 100 -or $excluded -gt ($included / 50)) {
        throw "Unexpected signal levels (440 Hz = $included, 997 Hz = $excluded). $eventLog"
    }
    if ($external.HasExited) { throw 'The control process exited during capture.' }

    $recoveryDir = Join-Path $work 'recovery'
    New-Item -ItemType Directory -Path $recoveryDir | Out-Null
    $recovered = Join-Path $recoveryDir $wave.Name
    $part = $recovered + '.part'
    $partialWriter = [IO.File]::Open($part, [IO.FileMode]::CreateNew)
    try {
        $partialWriter.Write($samples, 0, $samples.Length)
        $partialWriter.WriteByte(255)
    } finally { $partialWriter.Dispose() }
    & $helper 'recover' $recoveryDir
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $recovered).Hash -ne (Get-FileHash -LiteralPath $wave.FullName).Hash) {
        throw 'WAV recovery failed to restore the intact frames.'
    }
    $windows = Get-ItemProperty 'HKLM:/SOFTWARE/Microsoft/Windows NT/CurrentVersion'
    [pscustomobject]@{
        WindowsBuild = "$($windows.CurrentBuild).$($windows.UBR)"
        Browser = if ($Chrome) { (Get-Item -LiteralPath $chromePath).VersionInfo.ProductVersion } else { 'PowerShell' }
        Pid = $target.Id
        Format = "PCM $bits-bit, $rate Hz, $channels channels"
        Included440Hz = [Math]::Round($included, 2)
        Excluded997Hz = [Math]::Round($excluded, 2)
        Recovery = 'Frame-aligned WAV verified'
        Recording = $wave.FullName
    }
} finally {
    foreach ($process in @($capture, $target, $external)) {
        if ($null -ne $process -and -not $process.HasExited) { $process.Kill() }
        if ($null -ne $process) { $process.Dispose() }
    }
}
