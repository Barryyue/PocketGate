# Android Kotlin comparison build helper.
# Usage:
#   .\scripts\android-kotlin.ps1 build
#   .\scripts\android-kotlin.ps1 test
#   .\scripts\android-kotlin.ps1 install
#   .\scripts\android-kotlin.ps1 logcat
#   .\scripts\android-kotlin.ps1 publish
#   .\scripts\android-kotlin.ps1 aab
#   .\scripts\android-kotlin.ps1 emulator
param(
    [Parameter(Position = 0)]
    [ValidateSet('build', 'test', 'install', 'logcat', 'publish', 'aab', 'emulator')]
    [string]$Command = 'build'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root 'android-kotlin'
$jdk = if ($env:NC_ANDROID_JDK) { $env:NC_ANDROID_JDK } else { 'D:\tools\android-dev\jdk' }
$sdk = if ($env:NC_ANDROID_SDK) { $env:NC_ANDROID_SDK } else { 'D:\tools\android-dev\android-sdk' }
$avdHome = if ($env:NC_ANDROID_AVD_HOME) { $env:NC_ANDROID_AVD_HOME } else { 'D:\tools\android-dev\avd' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = 'D:\tools\android-dev\emu34\emulator\emulator.exe'
$package = 'com.networkcontrol.android.kotlin.debug'
$version = '0.9.6-m4p7'

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_AVD_HOME = $avdHome
$env:PATH = (Join-Path $jdk 'bin') + ';' + $env:PATH

function Invoke-Gradle([string[]]$GradleArgs) {
    Push-Location $project
    try {
        & (Join-Path $project 'gradlew.bat') @GradleArgs --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    }
    finally {
        Pop-Location
    }
}

switch ($Command) {
    'build' {
        Invoke-Gradle @('assembleDebug')
        $apk = Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'
        Write-Host "APK: $apk"
    }
    'test' {
        Invoke-Gradle @('testDebugUnitTest')
    }
    'install' {
        $apk = Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'
        if (-not (Test-Path $apk)) { throw "Build the APK first: $apk" }
        & $adb install -r $apk
        & $adb shell am start -n "$package/com.networkcontrol.android.kotlin.MainActivity"
    }
    'logcat' {
        & $adb logcat -v time NetworkControlKT:V AndroidRuntime:E *:S
    }
    'publish' {
        Invoke-Gradle @('assembleRelease')
        $source = Join-Path $project 'app\build\outputs\apk\release\app-release.apk'
        if (-not (Test-Path $source)) { throw "Release APK not found: $source" }
        $outDir = Join-Path $root 'artifacts\kotlin-android'
        New-Item -ItemType Directory -Force $outDir | Out-Null
        $target = Join-Path $outDir ("NetworkControl-KT-{0}.apk" -f $version)
        Copy-Item $source $target -Force
        $size = (Get-Item $target).Length
        $hash = (Get-FileHash $target -Algorithm SHA256).Hash
        Write-Host ("Release APK: {0} ({1:N1} MB)" -f $target, ($size / 1MB))
        Write-Host ("Size: {0} bytes" -f $size)
        Write-Host ("SHA-256: {0}" -f $hash)
    }
    'aab' {
        Invoke-Gradle @('bundleRelease')
        $source = Join-Path $project 'app\build\outputs\bundle\release\app-release.aab'
        if (-not (Test-Path $source)) { throw "Release AAB not found: $source" }
        $outDir = Join-Path $root 'artifacts\kotlin-android'
        New-Item -ItemType Directory -Force $outDir | Out-Null
        $target = Join-Path $outDir ("NetworkControl-KT-{0}.aab" -f $version)
        Copy-Item $source $target -Force
        $size = (Get-Item $target).Length
        $hash = (Get-FileHash $target -Algorithm SHA256).Hash
        Write-Host ("Release AAB: {0} ({1:N1} MB)" -f $target, ($size / 1MB))
        Write-Host ("Size: {0} bytes" -f $size)
        Write-Host ("SHA-256: {0}" -f $hash)
    }
    'emulator' {
        $env:ANDROID_AVD_HOME = $avdHome
        & $emulator -avd nc35 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -no-boot-anim
    }
}
