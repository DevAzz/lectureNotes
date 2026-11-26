param([Parameter(Mandatory)][string]$Destination)

$ErrorActionPreference = 'Stop'
$cache = Join-Path (Split-Path -Parent $PSScriptRoot) 'target/diarization-cache'
New-Item -ItemType Directory -Path $cache,$Destination -Force | Out-Null
. (Join-Path $PSScriptRoot 'build-assets.ps1')

$release = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'
$runtime = 'sherpa-onnx-v2.0.3-win-x64-shared-MD-Release-no-tts'
$segmentation = 'sherpa-onnx-pyannote-segmentation-3-0'
$embedding = '3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx'
$onnx = 'onnxruntime-win-x64-MD-Release-1.28.2'

$runtimeArchive = Get-BuildAsset $cache "$runtime.tar.bz2" "$release/v2.0.3/$runtime.tar.bz2" `
    '88adaac47d3b3fe5b341634fd17f8287340d1b99e23ba1b5a57ffc0a2a38d0bc'
$segmentationArchive = Get-BuildAsset $cache "$segmentation.tar.bz2" `
    "$release/speaker-segmentation-models/$segmentation.tar.bz2" `
    '24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488'
$speakerModel = Get-BuildAsset $cache $embedding "$release/speaker-recongition-models/$embedding" `
    'f1ee61be04000b50392a63442e5dd9feaea7e81dddd494d3647cf1c3d88a1498'
$runtimeLicense = Get-BuildAsset $cache 'LICENSE-sherpa-onnx.txt' `
    'https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v2.0.3/LICENSE' `
    'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'
$embeddingLicense = Get-BuildAsset $cache 'LICENSE-3D-Speaker.txt' `
    'https://raw.githubusercontent.com/modelscope/3D-Speaker/main/LICENSE' `
    'c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4'
$onnxArchive = Get-BuildAsset $cache "$onnx.tar.bz2" `
    "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/$onnx.tar.bz2" `
    'cdb3c68e317db991f85504e0f0ce5959528fba2c240b6c22e93366e80b3d2d58'

$bin = @('sherpa-onnx-offline-speaker-diarization.exe', 'onnxruntime.dll', 'onnxruntime_providers_shared.dll')
$members = $bin | ForEach-Object { "$runtime/bin/$_" }
& tar.exe -xf $runtimeArchive -C $cache @members
if ($LASTEXITCODE -ne 0) { throw "Could not extract sherpa-onnx ($LASTEXITCODE)." }
& tar.exe -xf $segmentationArchive -C $cache "$segmentation/model.onnx" "$segmentation/LICENSE"
if ($LASTEXITCODE -ne 0) { throw "Could not extract the pyannote segmentation model ($LASTEXITCODE)." }
& tar.exe -xf $onnxArchive -C $cache "$onnx/LICENSE" "$onnx/ThirdPartyNotices.txt"
if ($LASTEXITCODE -ne 0) { throw "Could not extract ONNX Runtime licenses ($LASTEXITCODE)." }

$bin | ForEach-Object { Copy-Item -LiteralPath (Join-Path $cache "$runtime/bin/$_") -Destination $Destination -Force }
Copy-Item -LiteralPath $runtimeLicense,$embeddingLicense -Destination $Destination -Force
Copy-Item -LiteralPath $speakerModel -Destination (Join-Path $Destination 'embedding.onnx') -Force
Copy-Item -LiteralPath (Join-Path $cache "$segmentation/model.onnx") -Destination (Join-Path $Destination 'segmentation.onnx') -Force
Copy-Item -LiteralPath (Join-Path $cache "$segmentation/LICENSE") -Destination (Join-Path $Destination 'LICENSE-pyannote.txt') -Force
Copy-Item -LiteralPath (Join-Path $cache "$onnx/LICENSE") -Destination (Join-Path $Destination 'LICENSE-ONNX-Runtime.txt') -Force
Copy-Item -LiteralPath (Join-Path $cache "$onnx/ThirdPartyNotices.txt") -Destination (Join-Path $Destination 'NOTICE-ONNX-Runtime.txt') -Force
