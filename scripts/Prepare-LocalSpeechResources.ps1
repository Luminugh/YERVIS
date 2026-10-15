# Explicit local provisioning only. No Internet calls, scripts, or automatic model downloads.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$manifest = Get-Content -LiteralPath (Join-Path $taskRoot 'app/src/main/assets/stt/resources-manifest.json') -Raw | ConvertFrom-Json
foreach ($resource in $manifest.resources) {
    $source = [IO.Path]::GetFullPath((Join-Path $taskRoot $resource.cachePath))
    $destination = [IO.Path]::GetFullPath((Join-Path $taskRoot $resource.path))
    if (!$source.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar) -or
        !$destination.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar)) {
        throw 'Resource path is outside the workspace'
    }
    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        if ((Get-Item -LiteralPath $destination).Length -eq $resource.bytes -and
            (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -eq $resource.sha256) {
            Write-Output "Verified $($resource.path)"
            continue
        }
        throw "Destination differs from pinned resource: $($resource.path). Review the existing file before replacing it."
    }
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Missing pinned resource: $($resource.cachePath). Restore the inventory explicitly from $($resource.url); verify SHA-256 $($resource.sha256). No automatic download."
    }
    if ((Get-Item -LiteralPath $source).Length -ne $resource.bytes -or
        (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $resource.sha256) {
        throw "Source integrity failure: $($resource.cachePath)"
    }
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
    $temporary = $destination + '.preparing-' + [Guid]::NewGuid().ToString('N')
    try {
        Copy-Item -LiteralPath $source -Destination $temporary
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $resource.sha256) {
            throw "Copy integrity failure: $($resource.path)"
        }
        Move-Item -LiteralPath $temporary -Destination $destination
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
    Write-Output "Prepared $($resource.path)"
}
