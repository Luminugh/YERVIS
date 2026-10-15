#Requires -Version 7.0
<# Explicit local provisioning only. No network, install, staging or baseline replacement. #>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$name = 'sherpa-onnx-asr-only-confidence-1.13.8-yezvi.aar'
$source = Join-Path $workspace ".gradle/yezvi-stt-research/asr-only/confidence/$name"
$receiptFile = $source + '.receipt.json'
if (!(Test-Path -LiteralPath $source) -or !(Test-Path -LiteralPath $receiptFile)) {
    throw 'Build the four-ABI runtime explicitly with Build-SherpaAsrOnly.ps1 -ConfidencePrototype first.'
}
$receipt = Get-Content -LiteralPath $receiptFile -Raw | ConvertFrom-Json
$hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$length = (Get-Item -LiteralPath $source).Length
if (!$receipt.confidencePrototype -or $receipt.sourceCommit -ne '11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf' -or
    $receipt.sha256 -ne $hash -or $receipt.bytes -ne $length -or @($receipt.nativeBuilds).Count -ne 4) {
    throw 'Experimental runtime receipt identity mismatch or incomplete ABI set.'
}
$patchManifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'sherpa-confidence/source-manifest.json') -Raw | ConvertFrom-Json
if ($receipt.confidenceSource.patchSha256 -ne $patchManifest.patch.sha256 -or
    $receipt.confidenceSource.bridgeSha256 -ne $patchManifest.addedSource.sha256) {
    throw 'Runtime was not built from the current reviewed confidence patch/bridge.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($source)
try {
    foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')) {
        foreach ($library in @('libsherpa-onnx-jni.so', 'libonnxruntime.so')) {
            if ($null -eq $archive.GetEntry("jni/$abi/$library")) { throw "Missing runtime library for $abi" }
        }
    }
    if ($null -eq $archive.GetEntry('META-INF/yezvi-stt-confidence/source-manifest.json')) {
        throw 'Experimental source/license packet is missing.'
    }
} finally { $archive.Dispose() }
$target = Join-Path $workspace "app/libs/$name"
$temporary = $target + '.preparing-' + [Guid]::NewGuid().ToString('N')
[IO.File]::Copy($source, $temporary)
if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
    throw 'Prepared runtime copy failed integrity verification.'
}
[IO.File]::Move($temporary, $target, $true)
$manifest = [ordered]@{
    format = 1; sourceCommit = $receipt.sourceCommit; experimental = $true; completeAbiSet = $true
    purpose = 'Debug-only uncalibrated pre-context token scores; release uses the original ASR-only runtime'
    bytes = $length; sha256 = $hash; patchSha256 = $patchManifest.patch.sha256
    bridgeSha256 = $patchManifest.addedSource.sha256
    nativeAbis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
}
$manifestPath = Join-Path $workspace 'app/src/debug/assets/stt/confidence-runtime.json'
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 6) + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "Prepared debug-only runtime; bytes=$length SHA-256=$hash"
