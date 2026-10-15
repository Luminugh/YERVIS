param([string]$Serial = 'emulator-5554', [switch]$SkipInstall)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskEvidence = Join-Path $taskRoot '.gradle/f409-validation'
$taskAdb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$taskAapt = Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/36.0.0/aapt2.exe'
$taskTarget = 'com.yezvi.assistant.persistenceisolated'
$taskTest = "$taskTarget.test"
$taskClass = 'com.yezvi.assistant.data.repository.ProposalDeduplicationPersistenceTest'
New-Item -ItemType Directory -Force -Path $taskEvidence | Out-Null

function Invoke-TestAdb([string[]]$Arguments) {
    $taskOutput = & $taskAdb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'ADB command failed; no destructive recovery is allowed.' }
    return ($taskOutput -join [Environment]::NewLine)
}

function Get-PersonalPackageState {
    $taskPackages = Invoke-TestAdb @('shell', 'pm', 'list', 'packages', 'com.yezvi.assistant')
    if ($taskPackages -notmatch '(?m)^package:com\.yezvi\.assistant\r?$') { return 'absent' }
    $taskInfo = Invoke-TestAdb @('shell', 'dumpsys', 'package', 'com.yezvi.assistant')
    $taskPath = (Invoke-TestAdb @('shell', 'pm', 'path', 'com.yezvi.assistant')).Trim().Split("`n")[0].Trim().Substring(8)
    $taskHash = Invoke-TestAdb @('shell', 'sha256sum', $taskPath)
    return (([regex]::Matches($taskInfo, '(?m)^\s*(userId=\d+|firstInstallTime=[^\r\n]+|lastUpdateTime=[^\r\n]+)') |
        ForEach-Object { $_.Value.Trim() }) -join "`n") + "`n" + $taskHash
}

function Invoke-IsolatedClass([string]$Classes, [string]$Label) {
    $taskOutput = Invoke-TestAdb @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $Classes,
        "$taskTest/androidx.test.runner.AndroidJUnitRunner")
    $taskOutput | Set-Content -LiteralPath (Join-Path $taskEvidence "$Label.txt") -Encoding utf8
    if ($taskOutput -notmatch 'OK \(\d+ tests?\)' -or $taskOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Isolated tests failed; inspect .gradle/f409-validation/$Label.txt. No reset or uninstall is allowed."
    }
    [regex]::Match($taskOutput, 'OK \(\d+ tests?\)').Value | Out-Host
}

if ($Serial -notmatch '^emulator-\d+$') { throw 'Only an explicitly selected emulator is allowed.' }
if ((Invoke-TestAdb @('emu', 'avd', 'name')) -notmatch '(?m)^Pixel_8\r?$') { throw 'Expected Pixel_8 AVD.' }
if ((Invoke-TestAdb @('shell', 'getprop', 'sys.boot_completed')).Trim() -ne '1') { throw 'Wait for Pixel boot.' }
if ((Invoke-TestAdb @('shell', 'getprop', 'ro.build.version.sdk')).Trim() -ne '35') { throw 'Expected API 35.' }

$taskAppApk = Join-Path $taskEvidence 'isolated-app.apk'
$taskTestApk = Join-Path $taskEvidence 'isolated-tests.apk'
foreach ($taskArtifact in @(@($taskAppApk, $taskTarget), @($taskTestApk, $taskTest))) {
    $taskBadging = & $taskAapt dump badging $taskArtifact[0] 2>&1
    if ($LASTEXITCODE -ne 0 -or ($taskBadging -join "`n") -notmatch "package: name='$([regex]::Escape($taskArtifact[1]))'") {
        throw 'APK identity is not isolated. Stop before installation.'
    }
}
$taskManifest = & $taskAapt dump xmltree $taskTestApk --file AndroidManifest.xml 2>&1
if (($taskManifest -join "`n") -notmatch "targetPackage.*$([regex]::Escape($taskTarget))") {
    throw 'Instrumentation targets an unexpected package.'
}
$taskPermissions = & $taskAapt dump permissions $taskAppApk 2>&1
if (($taskPermissions -join "`n") -match 'android.permission.INTERNET') { throw 'Unexpected Internet permission.' }

$taskBefore = Get-PersonalPackageState
$taskBefore | Set-Content -LiteralPath (Join-Path $taskEvidence 'personal-package-before.txt') -Encoding utf8
try {
    if (!$SkipInstall) {
        Invoke-TestAdb @('install', '-r', '-t', $taskAppApk) | Out-Host
        Invoke-TestAdb @('install', '-r', '-t', $taskTestApk) | Out-Host
    }
    foreach ($taskArtifact in @(@($taskAppApk, $taskTarget), @($taskTestApk, $taskTest))) {
        $taskInstalledPath = (Invoke-TestAdb @('shell', 'pm', 'path', $taskArtifact[1])).Trim().Split("`n")[0].Trim().Substring(8)
        $taskInstalledHash = (Invoke-TestAdb @('shell', 'sha256sum', $taskInstalledPath)).Split(' ')[0].Trim()
        if ($taskInstalledHash -ne (Get-FileHash -LiteralPath $taskArtifact[0] -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw 'Installed isolated APK differs from the reviewed build.'
        }
    }
    if ((Get-PersonalPackageState) -ne $taskBefore) { throw 'Personal package identity changed.' }
    $taskSource = Join-Path $taskRoot 'app/src/androidTest/java/com/yezvi/assistant/data/repository/ProposalDeduplicationPersistenceTest.kt'
    $taskMethods = [regex]::Matches((Get-Content -Raw -LiteralPath $taskSource), '@Test fun (\w+)') |
        ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ -notlike 'processRestart*' }
    $taskClasses = (@('com.yezvi.assistant.data.repository.InformationProposalPersistenceTest') +
        @($taskMethods | ForEach-Object { "$taskClass#$_" })) -join ','
    Invoke-IsolatedClass $taskClasses 'focused-suite'
    Invoke-IsolatedClass "$taskClass#processRestartWrite" 'process-write'
    Invoke-TestAdb @('shell', 'am', 'force-stop', $taskTarget) | Out-Null
    Invoke-IsolatedClass "$taskClass#processRestartReadAndRetry" 'process-read'
    $taskWritePid = [regex]::Match((Get-Content -Raw -LiteralPath (Join-Path $taskEvidence 'process-write.txt')), 'f409ProcessPid=(\d+)').Groups[1].Value
    $taskReadPid = [regex]::Match((Get-Content -Raw -LiteralPath (Join-Path $taskEvidence 'process-read.txt')), 'f409ProcessPid=(\d+)').Groups[1].Value
    if (!$taskWritePid -or !$taskReadPid -or $taskWritePid -eq $taskReadPid) { throw 'Process restart was not verified.' }
} finally {
    $taskAfter = Get-PersonalPackageState
    $taskAfter | Set-Content -LiteralPath (Join-Path $taskEvidence 'personal-package-after.txt') -Encoding utf8
    if ($taskAfter -ne $taskBefore) { throw 'Personal package integrity mismatch; stop without modifying it.' }
}
