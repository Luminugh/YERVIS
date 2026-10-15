#Requires -Version 7.0
<#
.SYNOPSIS
Build the pinned sherpa ASR/VAD JNI AAR using verified, local prerequisites only.
.DESCRIPTION
No download, git checkout, app installation, or production resource replacement occurs.
Prepare the pinned checkout, toolchain ZIPs/extractions, ORT ZIP/extraction and eight
dependency archives locally first. CMake FetchContent is fully disconnected and each
ABI gets its own extracted dependency sources (upstream patches some of these).
Examples:
  ./scripts/Build-SherpaAsrOnly.ps1 -Abis arm64-v8a -ConfigureOnly
  ./scripts/Build-SherpaAsrOnly.ps1 -Abis x86_64 -SkipPackage
  ./scripts/Build-SherpaAsrOnly.ps1 -SkipBuild
  ./scripts/Build-SherpaAsrOnly.ps1 -ConfidencePrototype -Abis x86_64 -SkipPackage
Build caches, unstripped JNI libraries, compile_commands.json and link maps are retained.
Packaging uses only classes.jar from the official full AAR; no original native code.
Byte-identical packaging is supported for identical native inputs; compiler output
reproducibility across hosts/absolute checkout paths is not asserted.
#>
[CmdletBinding()]
param(
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')]
    [string[]] $Abis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'),
    [ValidateRange(1, 64)] [int] $Jobs = 4,
    [switch] $ConfigureOnly,
    [switch] $SkipBuild,
    [switch] $SkipPackage,
    [switch] $ConfidencePrototype
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ($ConfigureOnly -and $SkipBuild) { throw 'ConfigureOnly and SkipBuild are mutually exclusive.' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$cache = Join-Path $workspace '.gradle/yezvi-stt-research/asr-only'
$source = Join-Path $cache 'source/sherpa-onnx'
$pinnedSource = $source
$buildRoot = Join-Path $cache 'build'
$preparedRoot = Join-Path $cache 'prepared-deps'
$outputRoot = $cache
$confidenceManifest = $null
$confidenceSourceReceipt = $null
$cmake = Join-Path $cache 'toolchain/cmake/bin/cmake.exe'
$ninja = Join-Path $cache 'toolchain/cmake/bin/ninja.exe'
$ndk = Join-Path $cache 'toolchain/ndk/android-ndk-r28c'
$strip = Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
$ort = Join-Path $cache 'ort'
$officialAar = Join-Path $workspace '.gradle/yezvi-stt-research/sherpa-onnx-static-link-onnxruntime-1.13.8.aar'
$commit = '11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf'
$allAbis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
$utf8 = [Text.UTF8Encoding]::new($false)

function Require-File([string] $Path) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing local prerequisite: $Path. No automatic download." }
}
function Get-Sha256([string] $Path) {
    Require-File $Path
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Assert-Hash([string] $Path, [string] $Expected) {
    if ((Get-Sha256 $Path) -ne $Expected) { throw "SHA-256 mismatch: $Path" }
}
function Assert-CachePath([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (!$full.StartsWith([IO.Path]::GetFullPath($cache) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Output path is outside ASR cache: $Path"
    }
    # Refuse junctions/symlinks in existing path components, including the cache root.
    $cursor = $full
    while ($cursor -and $cursor.Length -ge $workspace.Length) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Reparse point is not allowed for ASR build output: $cursor"
            }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    $full
}
function Invoke-Checked([string] $Tool, [string[]] $Arguments, [string] $Log) {
    & $Tool @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $Log | Write-Host
    if ($LASTEXITCODE -ne 0) { throw "Native command failed ($LASTEXITCODE); see $Log" }
}
function Write-Json([string] $Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12) + "`n", $utf8)
}
function Get-TreeReceipt([string] $Directory) {
    # Paths and content hashes make local upstream patches visible in the receipt.
    $items = @(Get-ChildItem -LiteralPath $Directory -Recurse -File | Sort-Object FullName | ForEach-Object {
        [ordered]@{ path = [IO.Path]::GetRelativePath($Directory, $_.FullName).Replace([char]92, [char]47); sha256 = Get-Sha256 $_.FullName }
    })
    $text = ($items | ForEach-Object { $_.path + "`t" + $_.sha256 }) -join "`n"
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($algorithm.ComputeHash($utf8.GetBytes($text))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
    [ordered]@{ directory = $Directory; fileCount = $items.Count; treeSha256 = $hash }
}

foreach ($tool in @($cmake, $ninja, $strip, (Join-Path $ndk 'build/cmake/android.toolchain.cmake'))) { Require-File $tool }
$archivePins = @(
    @{ path = (Join-Path $cache 'toolchain/android-ndk-r28c-windows.zip'); sha256 = '6bec98ac2354d8a919760889a1a41d020132e5e8cfa1b1fe51610a72c36a466b' },
    @{ path = (Join-Path $cache 'toolchain/cmake-3.22.1-windows.zip'); sha256 = 'c9a9d568452a20cf27d703cb21ef9529dd67bda6be048c4d4b884acb3ac3a2b8' },
    @{ path = (Join-Path $cache 'onnxruntime-android-1.28.2.zip'); sha256 = '01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d' },
    @{ path = $officialAar; sha256 = 'b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471' }
)
foreach ($pin in $archivePins) { Assert-Hash $pin.path $pin.sha256 }
if ((Get-Content -LiteralPath (Join-Path $ndk 'source.properties') -Raw) -notmatch '(?m)^Pkg.Revision\s*=\s*28\.2\.13676358\s*$') {
    throw 'Extracted NDK must be 28.2.13676358 (r28c).'
}
$head = & git -C $source rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $head.Trim() -ne $commit) { throw "Sherpa checkout must be commit $commit" }
$trackedChanges = & git -C $source status --porcelain --untracked-files=no
if ($LASTEXITCODE -ne 0 -or $trackedChanges) { throw 'Pinned sherpa tracked sources must be unmodified.' }
Require-File (Join-Path $ort 'headers/onnxruntime_cxx_api.h')
foreach ($abi in $allAbis) { Require-File (Join-Path $ort "jni/$abi/libonnxruntime.so") }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$ortZip = [IO.Compression.ZipFile]::OpenRead((Join-Path $cache 'onnxruntime-android-1.28.2.zip'))
try {
    # Verify extracted headers and imported shared libraries against the pinned ZIP itself.
    foreach ($entry in $ortZip.Entries) {
        if ($entry.FullName -notmatch '^headers/[^/]+\.h$|^jni/(arm64-v8a|armeabi-v7a|x86|x86_64)/libonnxruntime\.so$') { continue }
        $algorithm = [Security.Cryptography.SHA256]::Create()
        $stream = $entry.Open()
        try { $expected = [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
        finally { $stream.Dispose(); $algorithm.Dispose() }
        Assert-Hash (Join-Path $ort $entry.FullName) $expected
    }
} finally { $ortZip.Dispose() }

# Versions/hashes come from the pinned root CMake and nested dependency CMake files.
# Archive files must already exist under the checkout; these are never URLs to fetch.
$dependencies = @(
    @{ name = 'eigen'; archive = 'eigen-5.0.1.tar.gz'; sha256 = 'e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec' },
    @{ name = 'openfst'; archive = 'openfst-1.8.5-2026-07-09.tar.gz'; sha256 = '2ff712a32952fcb01d351121a6bc8ccf4fdc6b2aa06ce8df2b3095dedd518c0e' },
    @{ name = 'kaldi_decoder'; archive = 'kaldi-decoder-0.3.0.tar.gz'; sha256 = 'b9f34cfb4fd3b1344100eead79ef4d37aa15962274b9e3056de345021f76a1b0' },
    @{ name = 'kaldifst'; archive = 'kaldifst-1.8.0.tar.gz'; sha256 = '3f247b7e5a2409071202f5e2bc6200060f66728c0a3443c03923ad2723e040b3' },
    @{ name = 'kaldi_native_fbank'; archive = 'kaldi-native-fbank-1.22.3.tar.gz'; sha256 = '9176cc66fc7ce1edf85cf355b06e320c57db6297df74277f575183468893cf61' },
    @{ name = 'kissfft'; archive = 'kissfft-febd4caeed32e33ad8b2e0bb5ea77542c40f18ec.zip'; sha256 = '497103e664168ebe39580b757adbe616f6cf85a16572af581ca7bc42d0ab13fd' },
    @{ name = 'json'; archive = 'json-3.12.0.tar.gz'; sha256 = '4b92eb0c06d10683f7447ce9406cb97cd4b453be18d7279320f7b2f025c10187' },
    @{ name = 'simple-sentencepiece'; archive = 'simple-sentencepiece-0.7.tar.gz'; sha256 = '1748a822060a35baa9f6609f84efc8eb54dc0e74b9ece3d82367b7119fdc75af' }
)
foreach ($dependency in $dependencies) { Assert-Hash (Join-Path $source $dependency.archive) $dependency.sha256 }

# Experimental metadata is applied only to a verified copy of the pinned tree.
# The baseline checkout, prepared dependencies, builds, AAR and receipts remain intact.
if ($ConfidencePrototype) {
    $prototypeRoot = Assert-CachePath (Join-Path $cache 'confidence')
    $source = Assert-CachePath (Join-Path $prototypeRoot 'source/sherpa-onnx')
    $buildRoot = Join-Path $prototypeRoot 'build'
    $preparedRoot = Join-Path $prototypeRoot 'prepared-deps'
    $outputRoot = $prototypeRoot
    New-Item -ItemType Directory -Path $prototypeRoot -Force | Out-Null
    $patchRoot = Join-Path $PSScriptRoot 'sherpa-confidence'
    $manifestPath = Join-Path $patchRoot 'source-manifest.json'
    Require-File $manifestPath
    $confidenceManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($confidenceManifest.upstreamCommit -ne $commit -or $confidenceManifest.license -ne 'Apache-2.0') {
        throw 'The confidence patch must identify the pinned Sherpa commit and Apache-2.0.'
    }
    $patchPath = Join-Path $patchRoot $confidenceManifest.patch.path
    $bridgePath = Join-Path $patchRoot $confidenceManifest.addedSource.path
    Assert-Hash $patchPath $confidenceManifest.patch.sha256
    Assert-Hash $bridgePath $confidenceManifest.addedSource.sha256
    $marker = Join-Path $prototypeRoot 'source-overlay.json'
    if (!(Test-Path -LiteralPath $source)) {
        New-Item -ItemType Directory -Path $source -Force | Out-Null
        # The upstream checkout is sparse/promisor. git archive tries unrelated
        # missing objects; copy only clean, already-present tracked native inputs.
        $trackedInputs = @(& git -C $pinnedSource ls-files -- 'CMakeLists.txt' 'cmake' 'sherpa-onnx' 'LICENSE')
        if ($LASTEXITCODE -ne 0 -or $trackedInputs.Count -lt 100) { throw 'Cannot inventory pinned native source inputs.' }
        $snapshotFiles = @()
        foreach ($relativePath in $trackedInputs) {
            $inputFile = Join-Path $pinnedSource $relativePath
            Require-File $inputFile
            $targetFile = Join-Path $source $relativePath
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($targetFile)) -Force | Out-Null
            Copy-Item -LiteralPath $inputFile -Destination $targetFile
            $snapshotFiles += [ordered]@{ path = $relativePath; sha256 = Get-Sha256 $targetFile }
        }
        $snapshotPath = Join-Path $prototypeRoot 'pinned-source-snapshot.json'
        Write-Json $snapshotPath ([ordered]@{ sourceCommit = $commit; inputs = $snapshotFiles })
        # Preserve the checkout's exact bytes elsewhere; canonical LF is needed
        # only for the three versioned patch preimage/postimage hashes.
        foreach ($change in $confidenceManifest.modifiedUpstreamFiles) {
            $targetFile = Join-Path $source $change.path
            $text = [IO.File]::ReadAllText($targetFile).Replace("`r`n", "`n")
            [IO.File]::WriteAllText($targetFile, $text, $utf8)
        }
        Push-Location -LiteralPath $source
        try {
            # A dedicated cache repository avoids applying a patch relative to YEZVI.
            Invoke-Checked 'git' @('init', '--quiet') (Join-Path $prototypeRoot 'overlay-init.log')
            Invoke-Checked 'git' @('config', 'core.autocrlf', 'false') (Join-Path $prototypeRoot 'overlay-config.log')
            foreach ($change in $confidenceManifest.modifiedUpstreamFiles) {
                Assert-Hash (Join-Path $source $change.path) $change.upstreamLfSha256
            }
            Invoke-Checked 'git' @('apply', '--check', '--whitespace=error-all', $patchPath) (Join-Path $prototypeRoot 'patch-check.log')
            Invoke-Checked 'git' @('apply', '--whitespace=error-all', $patchPath) (Join-Path $prototypeRoot 'patch-apply.log')
            Copy-Item -LiteralPath $bridgePath -Destination (Join-Path $source $confidenceManifest.addedSource.destination)
        } finally { Pop-Location }
        Write-Json $marker ([ordered]@{
            upstreamCommit = $commit; sourceSnapshotSha256 = Get-Sha256 $snapshotPath; sourceSnapshotFiles = $snapshotFiles.Count
            patchSha256 = $confidenceManifest.patch.sha256; bridgeSha256 = $confidenceManifest.addedSource.sha256
            sourceManifestSha256 = Get-Sha256 $manifestPath
        })
    }
    Require-File $marker
    $confidenceSourceReceipt = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
    if ($confidenceSourceReceipt.upstreamCommit -ne $commit -or
        $confidenceSourceReceipt.patchSha256 -ne $confidenceManifest.patch.sha256 -or
        $confidenceSourceReceipt.bridgeSha256 -ne $confidenceManifest.addedSource.sha256 -or
        $confidenceSourceReceipt.sourceManifestSha256 -ne (Get-Sha256 $manifestPath)) {
        throw 'Existing confidence overlay has different inputs. Inspect it; no automatic deletion or overwrite.'
    }
    $snapshotPath = Join-Path $prototypeRoot 'pinned-source-snapshot.json'
    Assert-Hash $snapshotPath $confidenceSourceReceipt.sourceSnapshotSha256
    $snapshot = Get-Content -LiteralPath $snapshotPath -Raw | ConvertFrom-Json
    if ($snapshot.sourceCommit -ne $commit -or @($snapshot.inputs).Count -ne $confidenceSourceReceipt.sourceSnapshotFiles) {
        throw 'Confidence source snapshot metadata mismatch.'
    }
    $modifiedPaths = @($confidenceManifest.modifiedUpstreamFiles | ForEach-Object { $_.path })
    foreach ($input in $snapshot.inputs) {
        if ($modifiedPaths -notcontains $input.path) { Assert-Hash (Join-Path $source $input.path) $input.sha256 }
    }
    foreach ($change in $confidenceManifest.modifiedUpstreamFiles) {
        Assert-Hash (Join-Path $source $change.path) $change.patchedLfSha256
    }
    Assert-Hash (Join-Path $source $confidenceManifest.addedSource.destination) $confidenceManifest.addedSource.sha256
    # Upstream root CMake references these local archives even with disconnected overrides.
    foreach ($dependency in $dependencies) {
        $target = Join-Path $source $dependency.archive
        if (!(Test-Path -LiteralPath $target)) {
            Copy-Item -LiteralPath (Join-Path $pinnedSource $dependency.archive) -Destination $target
        }
        Assert-Hash $target $dependency.sha256
    }
}

$commonFlags = @(
    '-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake",
    '-DCMAKE_BUILD_TYPE=Release', '-DANDROID_PLATFORM=android-24', '-DANDROID_STL=c++_static',
    '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
    '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-Map,sherpa-jni.map',
    '-DBUILD_SHARED_LIBS=OFF', '-DSHERPA_ONNX_ENABLE_JNI=ON',
    '-DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON',
    '-DFETCHCONTENT_FULLY_DISCONNECTED=ON', '-DFETCHCONTENT_UPDATES_DISCONNECTED=ON'
)
foreach ($feature in @('TTS', 'SPEAKER_DIARIZATION', 'PORTAUDIO', 'WEBSOCKET', 'BINARY', 'C_API', 'PYTHON',
        'TESTS', 'GPU', 'DIRECTML', 'WASM', 'RKNN', 'AXERA', 'AXCL', 'ASCEND_NPU', 'QNN', 'SPACEMIT')) {
    $commonFlags += "-DSHERPA_ONNX_ENABLE_$feature=OFF"
}
$commonFlags += '-DSHERPA_ONNX_BUILD_C_API_EXAMPLES=OFF'
$originalInclude = [Environment]::GetEnvironmentVariable('SHERPA_ONNXRUNTIME_INCLUDE_DIR', 'Process')
$originalLib = [Environment]::GetEnvironmentVariable('SHERPA_ONNXRUNTIME_LIB_DIR', 'Process')
try {
    $env:SHERPA_ONNXRUNTIME_INCLUDE_DIR = Join-Path $ort 'headers'
    foreach ($abi in ($Abis | Select-Object -Unique)) {
        $build = Assert-CachePath (Join-Path $buildRoot $abi)
        New-Item -ItemType Directory -Path $build -Force | Out-Null
        if (!$SkipBuild) {
            $overrides = @()
            $depReceipts = @()
            foreach ($dependency in $dependencies) {
                $destination = Assert-CachePath (Join-Path $preparedRoot "$abi/$($dependency.name)")
                $marker = Join-Path $destination 'yezvi-extraction.json'
                if (!(Test-Path -LiteralPath $destination)) {
                    New-Item -ItemType Directory -Path $destination | Out-Null
                    Push-Location -LiteralPath $destination
                    try {
                        Invoke-Checked $cmake @('-E', 'tar', 'xf', (Join-Path $source $dependency.archive)) (Join-Path $build "extract-$($dependency.name).log")
                    } finally { Pop-Location }
                    $roots = @(Get-ChildItem -LiteralPath $destination -Directory)
                    if ($roots.Count -ne 1) { throw "Expected one archive root: $destination. Inspect incomplete extraction manually." }
                    Write-Json $marker @{ archiveSha256 = $dependency.sha256; sourceRoot = $roots[0].Name }
                }
                Require-File $marker
                $extracted = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
                if ($extracted.archiveSha256 -ne $dependency.sha256) { throw "Different archive already extracted: $destination" }
                $depSource = Assert-CachePath (Join-Path $destination $extracted.sourceRoot)
                Require-File (Join-Path $depSource 'CMakeLists.txt')
                $overrides += "-DFETCHCONTENT_SOURCE_DIR_$($dependency.name.ToUpperInvariant())=$depSource"
                $depReceipts += @{ name = $dependency.name; archive = $dependency.archive; archiveSha256 = $dependency.sha256; source = $depSource }
            }
            $env:SHERPA_ONNXRUNTIME_LIB_DIR = Join-Path $ort "jni/$abi"
            $configureArguments = @('-S', $source, '-B', $build, "-DANDROID_ABI=$abi") + $commonFlags + $overrides
            Invoke-Checked $cmake $configureArguments (Join-Path $build 'yezvi-configure.log')
            foreach ($dep in $depReceipts) { $dep.sourceTree = Get-TreeReceipt $dep.source }
            Write-Json (Join-Path $build 'yezvi-build-inputs.json') ([ordered]@{
                sourceCommit = $commit; abi = $abi; configureArguments = $configureArguments
                dependencySources = $depReceipts; archivePins = $archivePins
                ortLibrarySha256 = Get-Sha256 (Join-Path $ort "jni/$abi/libonnxruntime.so")
                ortHeaders = Get-TreeReceipt (Join-Path $ort 'headers')
            })
            if (!$ConfigureOnly) {
                Invoke-Checked $cmake @('--build', $build, '--target', 'sherpa-onnx-jni', '--parallel', "$Jobs") (Join-Path $build 'yezvi-build.log')
            }
        }
    }
} finally {
    [Environment]::SetEnvironmentVariable('SHERPA_ONNXRUNTIME_INCLUDE_DIR', $originalInclude, 'Process')
    [Environment]::SetEnvironmentVariable('SHERPA_ONNXRUNTIME_LIB_DIR', $originalLib, 'Process')
}
if ($ConfigureOnly -or $SkipPackage) { Write-Output 'Requested local configuration/build complete; packaging skipped.'; return }

# Package only complete four-ABI builds. No partial package or old native binaries.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$artifactName = if ($ConfidencePrototype) { 'sherpa-onnx-asr-only-confidence-1.13.8-yezvi.aar' } else { 'sherpa-onnx-asr-only-1.13.8-yezvi.aar' }
$output = Assert-CachePath (Join-Path $outputRoot $artifactName)
$stage = Assert-CachePath (Join-Path $outputRoot ('package-' + [Guid]::NewGuid().ToString('N')))
New-Item -ItemType Directory -Path $stage | Out-Null
$official = [IO.Compression.ZipFile]::OpenRead($officialAar)
try {
    $classes = $official.GetEntry('classes.jar')
    if ($null -eq $classes) { throw 'Pinned official AAR contains no classes.jar.' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($classes, (Join-Path $stage 'classes.jar'), $false)
} finally { $official.Dispose() }
[IO.File]::WriteAllText((Join-Path $stage 'AndroidManifest.xml'), '<?xml version="1.0" encoding="utf-8"?><manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.k2fsa.sherpa.onnx"><uses-sdk android:minSdkVersion="24" /></manifest>', $utf8)
[IO.File]::WriteAllText((Join-Path $stage 'R.txt'), '', $utf8)

# Bundle the ASR packet itself so the standalone AAR retains its notices and
# corresponding-source instructions. This is not a technical or legal release certificate.
$packetRoot = Join-Path $workspace 'docs/INVESTIGACION/F3.06-asr-only'
$packetDestination = Join-Path $stage 'META-INF/yezvi-stt'
$packetManifestPath = Join-Path $packetRoot 'licenses-manifest.json'
Require-File $packetManifestPath
$packetManifest = Get-Content -LiteralPath $packetManifestPath -Raw | ConvertFrom-Json
if ($packetManifest.source_inventory -ne 'sources.json') { throw 'ASR packet must identify sources.json.' }
if ($packetManifest.source_availability.url -ne 'https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz' -or
    $packetManifest.source_availability.sha256 -ne 'e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec') {
    throw 'ASR packet must retain the exact Eigen 5.0.1 corresponding-source URL and SHA-256.'
}
New-Item -ItemType Directory -Path (Join-Path $packetDestination 'licenses') -Force | Out-Null
$packetFiles = @()
function Copy-AsrPacketFile([string] $RelativePath, [string] $ExpectedHash) {
    $inputPath = [IO.Path]::GetFullPath((Join-Path $packetRoot $RelativePath))
    if (!$inputPath.StartsWith([IO.Path]::GetFullPath($packetRoot) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Notice path is outside ASR packet: $RelativePath"
    }
    Require-File $inputPath
    $cursor = $inputPath
    while ($cursor -and $cursor.Length -ge $workspace.Length) {
        if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Notice path contains a reparse point: $cursor"
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    $hash = Get-Sha256 $inputPath
    if ($ExpectedHash -and $hash -ne $ExpectedHash) { throw "ASR notice integrity mismatch: $RelativePath" }
    $outputPath = Join-Path $packetDestination $RelativePath
    Copy-Item -LiteralPath $inputPath -Destination $outputPath
    Assert-Hash $outputPath $hash
    [ordered]@{ path = 'META-INF/yezvi-stt/' + $RelativePath.Replace([char]92, [char]47); sha256 = $hash; bytes = (Get-Item -LiteralPath $outputPath).Length }
}
$noticeNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
if (@($packetManifest.notices).Count -eq 0) { throw 'ASR notice manifest must not be empty.' }
foreach ($notice in $packetManifest.notices) {
    $name = [string]$notice.file
    if ($name -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$' -or !$noticeNames.Add($name)) {
        throw "Unsafe or duplicate notice basename: $name"
    }
    if ($name -match '(?i)espeak|(^|[._-])(ucd|gpl)([._-]|$)') {
        throw "Full-runtime GPL notice cannot be imported into the ASR packet: $name"
    }
    if ([string]$notice.sha256 -notmatch '^[0-9a-fA-F]{64}$') { throw "Missing notice SHA-256: $name" }
    $noticePath = Join-Path $packetRoot "licenses/$name"
    Require-File $noticePath
    if ((Get-Item -LiteralPath $noticePath).Length -ne [long]$notice.bytes) { throw "ASR notice size mismatch: $name" }
    $packetFiles += Copy-AsrPacketFile "licenses/$name" ([string]$notice.sha256)
}
# Metadata has no self-referential manifest digest: record its current bytes and
# verify every copy; the immutable per-notice digests above remain the trust anchors.
foreach ($metadata in @('licenses-manifest.json', 'README.md', 'sources.json')) {
    $packetFiles += Copy-AsrPacketFile $metadata ''
}
$confidencePacket = @()
if ($ConfidencePrototype) {
    $destination = Join-Path $stage 'META-INF/yezvi-stt-confidence'
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    foreach ($name in @('README.md', 'LICENSE', 'source-manifest.json', 'sherpa-1.13.8-confidence.patch', 'yezvi-token-scores.cc')) {
        $input = Join-Path $patchRoot $name
        Require-File $input
        $target = Join-Path $destination $name
        Copy-Item -LiteralPath $input -Destination $target
        Assert-Hash $target (Get-Sha256 $input)
        $confidencePacket += [ordered]@{ path = "META-INF/yezvi-stt-confidence/$name"; sha256 = Get-Sha256 $target; bytes = (Get-Item -LiteralPath $target).Length }
    }
    Write-Json (Join-Path $destination 'source-overlay-receipt.json') $confidenceSourceReceipt
    $confidencePacket += [ordered]@{
        path = 'META-INF/yezvi-stt-confidence/source-overlay-receipt.json'
        sha256 = Get-Sha256 (Join-Path $destination 'source-overlay-receipt.json')
        bytes = (Get-Item -LiteralPath (Join-Path $destination 'source-overlay-receipt.json')).Length
    }
}
$nativeReceipts = @()
foreach ($abi in $allAbis) {
    $build = Assert-CachePath (Join-Path $buildRoot $abi)
    $cacheFile = Join-Path $build 'CMakeCache.txt'
    Require-File $cacheFile
    $cacheText = Get-Content -LiteralPath $cacheFile -Raw
    foreach ($pattern in @('BUILD_SHARED_LIBS:BOOL=OFF', 'SHERPA_ONNX_ENABLE_TTS:BOOL=OFF',
            'SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION:BOOL=OFF', 'SHERPA_ONNX_ENABLE_JNI:BOOL=ON',
            'ANDROID_PLATFORM:[^=]+=android-24', 'ANDROID_STL:[^=]+=c\+\+_static',
            'max-page-size=16384', 'common-page-size=16384')) {
        if ($cacheText -notmatch $pattern) { throw "Unverified build configuration ($pattern): $build" }
    }
    foreach ($flag in $commonFlags) {
        if ($flag -match '^-D(SHERPA_ONNX_[^=]+)=(ON|OFF)$') {
            $flagPattern = '(?m)^' + [Regex]::Escape($Matches[1]) + ':[^=]+=' + $Matches[2] + '\r?$'
            if ($cacheText -notmatch $flagPattern) { throw "Unverified cached feature flag ($flag): $build" }
        }
    }
    if ($cacheText -notmatch "ANDROID_ABI:[^=]+=$([Regex]::Escape($abi))") { throw "Wrong cached ABI: $build" }
    $actualFlags = [ordered]@{}
    foreach ($line in ($cacheText -split "`n")) {
        if ($line -match '^(SHERPA_ONNX_[^:]+|ANDROID_[^:]+|BUILD_SHARED_LIBS|CMAKE_SHARED_LINKER_FLAGS|FETCHCONTENT_[^:]+):[^=]+=(.*)') {
            $actualFlags[$Matches[1]] = $Matches[2].TrimEnd("`r")
        }
    }
    $jni = Join-Path $build 'lib/libsherpa-onnx-jni.so'
    Require-File $jni
    Require-File (Join-Path $build 'compile_commands.json')
    if ($ConfidencePrototype) {
        $normalizedSource = $source.Replace([char]92, [char]47)
        if ($cacheText -notmatch ('(?m)^CMAKE_HOME_DIRECTORY:INTERNAL=' + [Regex]::Escape($normalizedSource) + '\r?$')) {
            throw "Confidence build points at a different source tree: $build"
        }
        $compileCommands = Get-Content -LiteralPath (Join-Path $build 'compile_commands.json') -Raw
        if (!$compileCommands.Contains('yezvi-token-scores.cc')) { throw "Confidence bridge was not compiled: $build" }
        $nm = Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe'
        Require-File $nm
        $exports = & $nm --dynamic --defined-only $jni
        if ($LASTEXITCODE -ne 0 -or !($exports -match '\bJava_com_yezvi_assistant_data_speech_SherpaTokenScores_readTokenLogProbabilities$')) {
            throw "Confidence JNI export is missing: $jni"
        }
    }
    $maps = @(Get-ChildItem -LiteralPath $build -Recurse -File -Filter 'sherpa-jni.map')
    if ($maps.Count -ne 1) { throw "Expected one retained JNI linker map for $abi; found $($maps.Count)." }
    $nativeDir = Join-Path $stage "jni/$abi"
    New-Item -ItemType Directory -Path $nativeDir -Force | Out-Null
    $packagedJni = Join-Path $nativeDir 'libsherpa-onnx-jni.so'
    Copy-Item -LiteralPath $jni -Destination $packagedJni
    Invoke-Checked $strip @('--strip-unneeded', $packagedJni) (Join-Path $build 'yezvi-strip.log')
    $ortLibrary = Join-Path $ort "jni/$abi/libonnxruntime.so"
    Copy-Item -LiteralPath $ortLibrary -Destination (Join-Path $nativeDir 'libonnxruntime.so')
    $nativeReceipts += [ordered]@{
        abi = $abi; jniUnstrippedSha256 = Get-Sha256 $jni; jniPackagedSha256 = Get-Sha256 $packagedJni
        ortSha256 = Get-Sha256 $ortLibrary; cmakeCacheSha256 = Get-Sha256 $cacheFile
        compileCommandsSha256 = Get-Sha256 (Join-Path $build 'compile_commands.json')
        linkMap = $maps[0].FullName; linkMapSha256 = Get-Sha256 $maps[0].FullName
        buildInputReceipt = Join-Path $build 'yezvi-build-inputs.json'
        buildInputReceiptExists = [IO.File]::Exists((Join-Path $build 'yezvi-build-inputs.json'))
        invocationEvidence = if ([IO.File]::Exists((Join-Path $build 'yezvi-build-inputs.json'))) {
            'Script input receipt plus effective cache, commands and map'
        } else {
            'Initial manual invocation; effective cache, commands and map retained, no script input receipt claimed'
        }
        actualCachedFlags = $actualFlags
    }
}
$temporaryAar = $output + '.preparing-' + [Guid]::NewGuid().ToString('N')
$archive = [IO.Compression.ZipFile]::Open($temporaryAar, [IO.Compression.ZipArchiveMode]::Create)
try {
    $paths = [string[]]@(Get-ChildItem -LiteralPath $stage -Recurse -File | ForEach-Object { [IO.Path]::GetRelativePath($stage, $_.FullName).Replace([char]92, [char]47) })
    [Array]::Sort($paths, [StringComparer]::Ordinal)
    foreach ($path in $paths) {
        $entry = $archive.CreateEntry($path, [IO.Compression.CompressionLevel]::Optimal)
        $entry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
        $entry.ExternalAttributes = 0
        $inputStream = [IO.File]::OpenRead((Join-Path $stage $path))
        $outputStream = $entry.Open()
        try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose(); $inputStream.Dispose() }
    }
} finally { $archive.Dispose() }
$receipt = [ordered]@{
    format = 1; confidencePrototype = [bool]$ConfidencePrototype; confidenceSource = $confidenceSourceReceipt
    confidencePacket = $confidencePacket
    scriptSha256 = Get-Sha256 $PSCommandPath; sourceCommit = $commit; androidApi = 24; stl = 'c++_static'
    core = 'static'; jni = 'shared'; onnxruntime = '1.28.2 shared'; allAbis = $allAbis
    archivePins = $archivePins; dependencyArchives = $dependencies; commonConfigureFlags = $commonFlags
    toolHashes = @{ cmake = Get-Sha256 $cmake; ninja = Get-Sha256 $ninja; strip = Get-Sha256 $strip }
    packagingRuntime = @{
        powerShell = $PSVersionTable.PSVersion.ToString()
        dotNet = [Runtime.InteropServices.RuntimeInformation]::FrameworkDescription
    }
    classesJarSha256 = Get-Sha256 (Join-Path $stage 'classes.jar'); nativeBuilds = $nativeReceipts
    noticePacket = $packetFiles; sourceAvailability = $packetManifest.source_availability
    packaging = 'Ordinal ZIP entries, fixed .NET deflate settings, 1980 UTC timestamps; original native libraries excluded. Byte identity scoped to identical inputs and packaging runtime.'
    output = $output; bytes = (Get-Item -LiteralPath $temporaryAar).Length; sha256 = Get-Sha256 $temporaryAar
    retainedPackagingDirectory = $stage
}
# Only these individual output files are replaced; no recursive deletion/move occurs.
[IO.File]::Move($temporaryAar, $output, $true)
Write-Json ($output + '.receipt.json') $receipt
Write-Output "Prepared $output"
Write-Output "SHA-256 $($receipt.sha256)"
