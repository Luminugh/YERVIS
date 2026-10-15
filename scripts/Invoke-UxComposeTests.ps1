param(
    [string]$Serial = 'emulator-5554',
    [string]$AppApk = '.gradle/ux-renewal/isolated-app.apk',
    [string]$TestApk = '.gradle/ux-renewal/isolated-tests.apk',
    [string]$Label = 'focused',
    [string]$Classes = 'com.yezvi.assistant.ui.navigation.VisualRenewalTest,com.yezvi.assistant.ui.theme.AppearancePersistenceTest,com.yezvi.assistant.ui.navigation.YezviAppNavigationTest,com.yezvi.assistant.ui.screens.CaptureVoiceUiTest,com.yezvi.assistant.ui.screens.CaptureDraftFlowTest,com.yezvi.assistant.ui.screens.EntryDetailAndAccessibilityTest,com.yezvi.assistant.ui.screens.EntryDetailEditingTest,com.yezvi.assistant.ui.screens.VoiceSettingsSectionTest'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskEvidence = Join-Path $taskRoot '.gradle/ux-renewal'
$taskAdb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$taskAapt = Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/36.0.0/aapt2.exe'
$taskTarget = 'com.yezvi.assistant.persistenceisolated'
function Invoke-UxTestAdb([string[]]$Arguments) {
    $taskOutput = & $taskAdb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'ADB failed; stop without destructive recovery.' }
    return ($taskOutput -join [Environment]::NewLine)
}
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only an explicit emulator serial is accepted.' }
if ((Invoke-UxTestAdb @('emu','avd','name')) -notmatch '(?m)^Pixel_8\r?$') { throw 'Expected Pixel_8.' }
if ((Invoke-UxTestAdb @('shell','getprop','ro.build.version.sdk')).Trim() -ne '35') { throw 'Expected API 35.' }
if ($Label -notmatch '^[a-z0-9-]+$') { throw 'Invalid evidence label.' }
foreach ($taskClass in $Classes.Split(',')) {
    if ($taskClass -notmatch '^com\.yezvi\.assistant\.ui\.[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?$') {
        throw 'Only explicit UI test classes or methods are allowed; never the complete device suite.'
    }
}
foreach ($taskArtifact in @(@($AppApk,$taskTarget),@($TestApk,"$taskTarget.test"))) {
    $taskBadging = & $taskAapt dump badging $taskArtifact[0] 2>&1
    if ($LASTEXITCODE -ne 0 -or ($taskBadging -join "`n") -notmatch "package: name='$([regex]::Escape($taskArtifact[1]))'") {
        throw 'Unexpected APK identity. Personal APK installation is refused.'
    }
}
$taskManifest = & $taskAapt dump xmltree $TestApk --file AndroidManifest.xml 2>&1
if (($taskManifest -join "`n") -notmatch "targetPackage.*$([regex]::Escape($taskTarget))") { throw 'Unexpected test target.' }
$taskPersonalBefore = (Invoke-UxTestAdb @('shell','dumpsys','package','com.yezvi.assistant')) -split "`n" |
    Where-Object { $_ -match '^\s*(userId=|firstInstallTime=|lastUpdateTime=)' }
try {
    Invoke-UxTestAdb @('install','-r','-t',(Resolve-Path $AppApk).Path) | Out-Host
    Invoke-UxTestAdb @('install','-r','-t',(Resolve-Path $TestApk).Path) | Out-Host
    $taskOutput = Invoke-UxTestAdb @('shell','am','instrument','-w','-r','-e','class',$Classes,
        "$taskTarget.test/androidx.test.runner.AndroidJUnitRunner")
    $taskOutput | Set-Content (Join-Path $taskEvidence "compose-$Label.txt")
    [regex]::Match($taskOutput, 'OK \(\d+ tests?\)|Tests run: [^\r\n]+').Value | Out-Host
    if ($taskOutput -notmatch 'OK \(\d+ tests?\)' -or $taskOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "UI tests failed; inspect .gradle/ux-renewal/compose-$Label.txt."
    }
} finally {
    $taskPersonalAfter = (Invoke-UxTestAdb @('shell','dumpsys','package','com.yezvi.assistant')) -split "`n" |
        Where-Object { $_ -match '^\s*(userId=|firstInstallTime=|lastUpdateTime=)' }
    if (($taskPersonalBefore -join "`n") -ne ($taskPersonalAfter -join "`n")) { throw 'Personal package identity changed.' }
}
