param([Parameter(Mandatory)][string]$Destination, [switch]$Full)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $project 'target/whisper-cache'
New-Item -ItemType Directory -Path $cache,$Destination -Force | Out-Null

. (Join-Path $PSScriptRoot 'build-assets.ps1')

$version = 'v1.8.4'
$cpuArchive = Get-BuildAsset $cache "whisper-$version-win-x64.zip" `
    "https://github.com/ggml-org/whisper.cpp/releases/download/$version/whisper-bin-x64.zip" `
    '74f973345cb52ef5ba3ec9e7e7af8e48cc8c71722d1528603b80588a11f82e3e'
$cudaName = 'whisper-cublas-12.4.0-bin-x64.zip'
$cudaArchive = Get-BuildAsset $cache $cudaName `
    "https://github.com/ggml-org/whisper.cpp/releases/download/$version/$cudaName" `
    'b07cff4e59831b227896018facbb6334907bf324a342c84597c44f087823d252'
if ($Full) {
    $weights = Join-Path $cache 'ggml-large-v3.bin'
    if (!(Test-Path -LiteralPath $weights -PathType Leaf) -or
            (Get-FileHash -LiteralPath $weights -Algorithm SHA256).Hash -ne
            '64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2') {
        throw "Place the verified ggml-large-v3.bin weights in $cache."
    }
} else {
    $model = 'ggml-large-v3-turbo-q5_0.bin'
    $weights = Get-BuildAsset $cache $model "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$model" `
        '394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2'
}
$runtimeLicense = Get-BuildAsset $cache "LICENSE-whisper.cpp-$version.txt" `
    "https://raw.githubusercontent.com/ggml-org/whisper.cpp/$version/LICENSE" `
    '94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d'
$modelLicense = Get-BuildAsset $cache 'LICENSE-Whisper-model.txt' `
    'https://raw.githubusercontent.com/openai/whisper/v20250625/LICENSE' `
    'b5d65a59060e68c4ff940e1eddfa6f94b2d68fdf58ed7f4dd57721c997e35e9d'

function Copy-Runtime($Archive, $Target, $Files) {
    New-Item -ItemType Directory -Path $Target -Force | Out-Null
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        foreach ($file in $Files) {
            $entry = $zip.GetEntry("Release/$file")
            if ($null -eq $entry) { throw "$file is missing from $Archive." }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $Target $file), $true)
        }
    } finally {
        $zip.Dispose()
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$core = @('whisper-cli.exe', 'whisper.dll', 'ggml.dll', 'ggml-base.dll', 'ggml-cpu.dll')
$cuda = $core + @('ggml-cuda.dll', 'cublas64_12.dll', 'cublasLt64_12.dll', 'cudart64_12.dll')
Copy-Runtime $cpuArchive $Destination $core
Copy-Runtime $cudaArchive (Join-Path $Destination 'cuda') $cuda
Copy-Item -LiteralPath $weights,$runtimeLicense,$modelLicense -Destination $Destination -Force
@'
NVIDIA CUDA 12.4 runtime and cuBLAS libraries
Copyright NVIDIA Corporation. All rights reserved.
NVIDIA CUDA Toolkit Software License Agreement and Supplement:
https://docs.nvidia.com/cuda/archive/12.4.0/eula/index.html
'@ | Set-Content -LiteralPath (Join-Path $Destination 'NOTICE-NVIDIA-CUDA-12.4.txt') -Encoding ASCII
