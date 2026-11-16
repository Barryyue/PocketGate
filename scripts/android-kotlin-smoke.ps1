# Android Kotlin smoke test (adb). Requires a connected device/emulator.
# Usage:
#   .\scripts\android-kotlin-smoke.ps1
#   .\scripts\android-kotlin-smoke.ps1 -Serial emulator-5554 -Install
param(
    [string]$Serial = '',
    [string]$Package = 'com.networkcontrol.android.kotlin.debug',
    [string]$Apk = '',
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:NC_ANDROID_SDK) { $env:NC_ANDROID_SDK } else { 'D:\tools\android-dev\android-sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }

$adbArgs = @()
if ($Serial) { $adbArgs = @('-s', $Serial) }

function Invoke-Adb {
    param([string[]]$Arguments)
    $output = & $adb @adbArgs @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ("adb failed: {0}`n{1}" -f ($Arguments -join ' '), ($output -join "`n"))
    }
    return $output
}

if ($Install) {
    if (-not $Apk) { $Apk = Join-Path $root 'android-kotlin\app\build\outputs\apk\debug\app-debug.apk' }
    if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
    Invoke-Adb @('install', '-r', $Apk) | Out-Null
}

Invoke-Adb @('shell', 'appops', 'set', $Package, 'GET_USAGE_STATS', 'allow') | Out-Null
Invoke-Adb @('shell', 'appops', 'set', $Package, 'SYSTEM_ALERT_WINDOW', 'allow') | Out-Null
Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
Invoke-Adb @('shell', 'am', 'start', '-n', ("{0}/com.networkcontrol.android.kotlin.MainActivity" -f $Package)) | Out-Null
Start-Sleep -Seconds 6

$processId = (Invoke-Adb @('shell', 'pidof', $Package) | Out-String).Trim()
if (-not $processId) { throw "process did not start: $Package" }

$log = (Invoke-Adb @('logcat', '-d', '-t', '800') | Out-String)
if ($log -match 'FATAL EXCEPTION') {
    throw "FATAL EXCEPTION found in logcat.`n$log"
}

Write-Host ("Smoke OK: package={0} pid={1}" -f $Package, $processId)
Write-Host "Checks: process alive, no FATAL EXCEPTION, usage stats + overlay appops set."
Write-Host "Next: open the app, enable protection, browse with Chrome, verify prompt/overlay and records."
