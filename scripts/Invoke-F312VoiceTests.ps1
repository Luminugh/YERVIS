param([Parameter(Mandatory = $true)][string]$Serial, [switch]$SelectionOnly)

# F3.12: direct AndroidJUnitRunner only. Never invokes a Gradle device installer,
# orchestrator, uninstall, pm clear, model removal or normal-database writes.
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$evidence = Join-Path $workspace '.gradle/f312-validation'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$package = 'com.yezvi.assistant'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
function Invoke-Adb([string[]]$AdbArguments) {
    $result = & adb -s $Serial @AdbArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($result -join [Environment]::NewLine)" }
    return ($result -join [Environment]::NewLine)
}
function Get-PackageIdentity {
    $path = Invoke-Adb @('shell', 'pm', 'path', $package)
    if ($path -notmatch '^package:') { throw 'Target missing. Stop; do not install a replacement or assume data survived.' }
    $uid = Invoke-Adb @('shell', 'run-as', $package, 'id', '-u')
    $info = Invoke-Adb @('shell', 'dumpsys', 'package', $package)
    $first = [regex]::Match($info, 'firstInstallTime=[^\r\n]+').Value
    if (!$first) { throw 'Cannot establish package identity.' }
    return "$uid $first"
}
function Invoke-Test([string]$Classes, [string]$Label) {
    $output = Invoke-Adb @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $Classes, $runner)
    $output | Set-Content -LiteralPath (Join-Path $evidence "$Label.txt") -Encoding utf8
    if ($output -notmatch 'OK \(\d+ tests?\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Instrumentation did not pass; inspect $Label.txt. No cleanup/reinstall fallback is allowed."
    }
    return $output
}
function Get-DatabaseFingerprint([string]$Label) {
    $output = Invoke-Test "$package.data.speech.VoiceDataIntegrityProbeTest" $Label
    $digest = [regex]::Match($output, 'f312Digest=([a-f0-9]{64})').Groups[1].Value
    if (!$digest) { throw 'Missing read-only integrity evidence.' }
    return $digest
}

$beforeIdentity = Get-PackageIdentity
$beforeIdentity | Set-Content (Join-Path $evidence 'package-before.txt')
$installedPath = (Invoke-Adb @('shell', 'pm', 'path', $package)).Trim().Substring(8)
$installedHash = (Invoke-Adb @('shell', 'sha256sum', $installedPath)).Split(' ')[0].Trim()
$builtApk = Join-Path $workspace 'app/build/outputs/apk/debug/app-debug.apk'
if ($installedHash -ne (Get-FileHash -LiteralPath $builtApk -Algorithm SHA256).Hash.ToLowerInvariant()) {
    throw 'Installed APK differs from the reviewed build. Stop without replacing/uninstalling the target.'
}
$ops = Invoke-Adb @('shell', 'cmd', 'appops', 'get', $package, 'RECORD_AUDIO')
$ops | Set-Content (Join-Path $evidence 'microphone-before.txt')
if ($ops -match 'running') { throw 'A microphone session is active; stop before testing.' }

# Only the separate test APK is installed/replaced. The target APK must already match.
Invoke-Adb @('install', '-r', '-t', (Join-Path $workspace 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) | Out-Host
if ((Get-PackageIdentity) -ne $beforeIdentity) { throw 'Package identity changed during test-APK installation.' }
Invoke-Adb @('shell', 'am', 'force-stop', $package) | Out-Null
$beforeDigest = Get-DatabaseFingerprint 'integrity-before'
Invoke-Adb @('shell', 'run-as', $package, 'sha256sum', 'databases/yezvi.db') |
    Set-Content (Join-Path $evidence 'database-file-before.txt')
$classes = @(
    "$package.data.speech.NativeModelCancellationTest",
    "$package.data.speech.SherpaOfflineSpeechToTextEngineTest",
    "$package.data.speech.LocalConfirmationSpeechTest",
    "$package.data.speech.AndroidLocalSpeechPreferencesStoreTest",
    "$package.ui.screens.CaptureVoiceUiTest",
    "$package.ui.screens.VoiceSettingsSectionTest"
) -join ','
if ($SelectionOnly) {
    $classes = "$package.data.speech.LocalConfirmationSpeechTest#localVoiceSelectionAndZeroVolumeRemainSilent"
}
try {
    $label = if ($SelectionOnly) { 'local-selection' } else { 'focused-suite' }
    $output = Invoke-Test $classes $label
    [regex]::Match($output, 'OK \(\d+ tests?\)').Value | Out-Host
} finally {
    $afterIdentity = Get-PackageIdentity
    Invoke-Adb @('shell', 'am', 'force-stop', $package) | Out-Null
    $afterDigest = Get-DatabaseFingerprint 'integrity-after'
    $afterIdentity | Set-Content (Join-Path $evidence 'package-after.txt')
    Invoke-Adb @('shell', 'run-as', $package, 'sha256sum', 'databases/yezvi.db') |
        Set-Content (Join-Path $evidence 'database-file-after.txt')
    Invoke-Adb @('shell', 'cmd', 'appops', 'get', $package, 'RECORD_AUDIO') |
        Set-Content (Join-Path $evidence 'microphone-after.txt')
    if ($afterIdentity -ne $beforeIdentity -or $afterDigest -ne $beforeDigest) {
        throw 'Integrity mismatch. Stop; do not clear, uninstall, recreate or repair the normal database.'
    }
    "Package identity and aggregate database fingerprint unchanged: $afterDigest" | Out-Host
}
