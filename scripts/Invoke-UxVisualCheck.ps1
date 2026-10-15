param(
    [Parameter(Mandatory)][ValidateSet('before', 'after', 'before-warm', 'after-warm')][string]$Phase,
    [Parameter(Mandatory)][string]$AppApk,
    [string]$Serial = 'emulator-5554',
    [switch]$WarmNavigation
)

# Only an explicitly selected synthetic package on the Pixel AVD is allowed.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskEvidence = Join-Path $taskRoot ".gradle/ux-renewal/$Phase"
$taskAdb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$taskAapt = Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/36.0.0/aapt2.exe'
$taskTarget = 'com.yezvi.assistant.persistenceisolated'
New-Item -ItemType Directory -Force -Path $taskEvidence | Out-Null

function Invoke-UxAdb([string[]]$Arguments) {
    $taskOutput = & $taskAdb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'ADB failed; no reset or destructive recovery is allowed.' }
    return ($taskOutput -join [Environment]::NewLine)
}

function Get-PersonalIdentity {
    $taskPackages = Invoke-UxAdb @('shell', 'pm', 'list', 'packages', 'com.yezvi.assistant')
    if ($taskPackages -notmatch '(?m)^package:com\.yezvi\.assistant\r?$') { return 'absent' }
    $taskInfo = Invoke-UxAdb @('shell', 'dumpsys', 'package', 'com.yezvi.assistant')
    $taskPath = (Invoke-UxAdb @('shell', 'pm', 'path', 'com.yezvi.assistant')).Trim().Split("`n")[0].Trim().Substring(8)
    return (([regex]::Matches($taskInfo, '(?m)^\s*(userId=\d+|firstInstallTime=[^\r\n]+|lastUpdateTime=[^\r\n]+)') |
        ForEach-Object { $_.Value.Trim() }) -join "`n") + "`n" + (Invoke-UxAdb @('shell', 'sha256sum', $taskPath))
}

function Wait-UxRoute([string]$Label) {
    foreach ($taskAttempt in 1..8) {
        Invoke-UxAdb @('shell', 'uiautomator', 'dump', '/data/local/tmp/yervis-ux-layout.xml') | Out-Null
        [xml]$taskCurrentLayout = Invoke-UxAdb @('shell', 'cat', '/data/local/tmp/yervis-ux-layout.xml')
        $taskHeading = $taskCurrentLayout.SelectNodes('//node') | Where-Object {
            if ($_.text -ne $Label -or $_.package -ne $taskTarget) { return $false }
            $taskBounds = [regex]::Match($_.bounds, '\[(\d+),(\d+)\]')
            return $taskBounds.Success -and [int]$taskBounds.Groups[2].Value -lt 700
        }
        if ($taskHeading) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "Expected route was not displayed: $Label. Do not label this as a completed transition."
}

if ($Serial -notmatch '^emulator-\d+$') { throw 'Only an explicit emulator serial is accepted.' }
if ((Invoke-UxAdb @('emu', 'avd', 'name')) -notmatch '(?m)^Pixel_8\r?$') { throw 'Expected Pixel_8 AVD.' }
if ((Invoke-UxAdb @('shell', 'getprop', 'ro.build.version.sdk')).Trim() -ne '35') { throw 'Expected API 35.' }
$taskBadging = & $taskAapt dump badging $AppApk 2>&1
if ($LASTEXITCODE -ne 0 -or ($taskBadging -join "`n") -notmatch "package: name='$taskTarget'") {
    throw 'APK must use the existing isolated test identity. Personal APKs are refused.'
}
$taskBefore = Get-PersonalIdentity
$taskBefore | Set-Content (Join-Path $taskEvidence 'personal-identity-before.txt')
try {
    Invoke-UxAdb @('install', '-r', '-t', (Resolve-Path -LiteralPath $AppApk).Path) | Out-Host
    $taskInstalledPath = (Invoke-UxAdb @('shell', 'pm', 'path', $taskTarget)).Trim().Split("`n")[0].Trim().Substring(8)
    $taskInstalledHash = (Invoke-UxAdb @('shell', 'sha256sum', $taskInstalledPath)).Split(' ')[0].Trim()
    if ($taskInstalledHash -ne (Get-FileHash -LiteralPath $AppApk -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw 'Installed isolated APK hash mismatch.'
    }
    $taskStartups = foreach ($taskTrial in 1..$(if ($WarmNavigation) { 1 } else { 3 })) {
        Invoke-UxAdb @('shell', 'am', 'force-stop', $taskTarget) | Out-Null
        Invoke-UxAdb @('shell', 'am', 'start', '-W', '-n', "$taskTarget/com.yezvi.assistant.MainActivity")
        Start-Sleep -Milliseconds 500
    }
    $taskStartups | Set-Content (Join-Path $taskEvidence 'startup.txt')
    Invoke-UxAdb @('shell', 'uiautomator', 'dump', '/data/local/tmp/yervis-ux-layout.xml') | Out-Null
    [xml]$taskLayout = Invoke-UxAdb @('shell', 'cat', '/data/local/tmp/yervis-ux-layout.xml')
    $taskCoordinates = @{}
    foreach ($taskLabel in @('Inicio', 'Recuerdos', 'Captura', 'Ajustes')) {
        $taskNodes = @($taskLayout.SelectNodes('//node') | Where-Object { $_.text -eq $taskLabel -and $_.package -eq $taskTarget })
        $taskPoint = $taskNodes | ForEach-Object {
            $taskBounds = [regex]::Match($_.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
            if ($taskBounds.Success) {
                @{ x=([int]$taskBounds.Groups[1].Value + [int]$taskBounds.Groups[3].Value)/2;
                   y=([int]$taskBounds.Groups[2].Value + [int]$taskBounds.Groups[4].Value)/2 }
            }
        } | Sort-Object { $_.y } -Descending | Select-Object -First 1
        if (!$taskPoint) { throw "Navigation label missing: $taskLabel" }
        $taskCoordinates[$taskLabel] = $taskPoint
    }
    if ($WarmNavigation) {
        foreach ($taskLabel in @('Recuerdos', 'Captura', 'Ajustes', 'Inicio')) {
            $taskPoint = $taskCoordinates[$taskLabel]
            Invoke-UxAdb @('shell', 'input', 'tap', [string][int]$taskPoint.x, [string][int]$taskPoint.y) | Out-Null
            Wait-UxRoute $taskLabel
        }
    }
    Invoke-UxAdb @('shell', 'dumpsys', 'gfxinfo', $taskTarget, 'reset') | Out-Null
    foreach ($taskCycle in 1..3) {
        foreach ($taskLabel in @('Recuerdos', 'Captura', 'Ajustes', 'Inicio')) {
            $taskPoint = $taskCoordinates[$taskLabel]
            Invoke-UxAdb @('shell', 'input', 'tap', [string][int]$taskPoint.x, [string][int]$taskPoint.y) | Out-Null
            Start-Sleep -Milliseconds $(if ($WarmNavigation) { 1200 } else { 450 })
            if (!$WarmNavigation) { Wait-UxRoute $taskLabel }
            if ($taskCycle -eq 1 -and !$WarmNavigation) {
                $taskRemoteImage = "/data/local/tmp/yervis-ux-$Phase-$taskLabel.png"
                Invoke-UxAdb @('shell', 'screencap', '-p', $taskRemoteImage) | Out-Null
                Invoke-UxAdb @('pull', $taskRemoteImage, (Join-Path $taskEvidence "$taskLabel.png")) | Out-Null
            }
        }
    }
    Invoke-UxAdb @('shell', 'dumpsys', 'gfxinfo', $taskTarget, 'framestats') |
        Set-Content (Join-Path $taskEvidence 'navigation-gfxinfo.txt')
    if ($WarmNavigation) { Wait-UxRoute 'Inicio' }
    if ($WarmNavigation) {
        Write-Host "Captured ${Phase}: one warm-up pass, then 12 tab taps without accessibility dumps during measurement."
    } else {
        Write-Host "Captured ${Phase}: 3 process-start samples, 12 tab taps, 4 synthetic screenshots."
    }
} finally {
    $taskAfter = Get-PersonalIdentity
    $taskAfter | Set-Content (Join-Path $taskEvidence 'personal-identity-after.txt')
    if ($taskAfter -ne $taskBefore) { throw 'Personal package identity changed; stop without modifying it.' }
}
