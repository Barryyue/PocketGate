# Android Kotlin comparison build helper.
# Usage:
#   .\scripts\android-kotlin.ps1 build
#   .\scripts\android-kotlin.ps1 test
#   .\scripts\android-kotlin.ps1 install
#   .\scripts\android-kotlin.ps1 logcat
#   .\scripts\android-kotlin.ps1 publish
#   .\scripts\android-kotlin.ps1 aab
#   .\scripts\android-kotlin.ps1 emulator
#   .\scripts\android-kotlin.ps1 baseline   # 重新生成 Baseline Profile（只用模擬器）
#   .\scripts\android-kotlin.ps1 startup    # 量測冷啟動
param(
    [Parameter(Position = 0)]
    [ValidateSet('build', 'test', 'install', 'logcat', 'publish', 'aab', 'emulator', 'baseline', 'startup')]
    [string]$Command = 'build'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root 'android-kotlin'
$jdk = if ($env:NC_ANDROID_JDK) {
    $env:NC_ANDROID_JDK
} elseif (Test-Path 'D:\tools\android-dev\jdk') {
    'D:\tools\android-dev\jdk'
} elseif (Test-Path (Join-Path $env:USERPROFILE '.jdks\corretto-21.0.5')) {
    Join-Path $env:USERPROFILE '.jdks\corretto-21.0.5'
} elseif (Test-Path (Join-Path $env:USERPROFILE '.jdks\corretto-17.0.13')) {
    Join-Path $env:USERPROFILE '.jdks\corretto-17.0.13'
} else {
    $env:JAVA_HOME
}
$sdk = if ($env:NC_ANDROID_SDK) {
    $env:NC_ANDROID_SDK
} elseif (Test-Path 'D:\tools\android-dev\android-sdk') {
    'D:\tools\android-dev\android-sdk'
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$avdHome = if ($env:NC_ANDROID_AVD_HOME) {
    $env:NC_ANDROID_AVD_HOME
} elseif (Test-Path 'D:\tools\android-dev\avd') {
    'D:\tools\android-dev\avd'
} else {
    Join-Path $env:USERPROFILE '.android\avd'
}
$adb = if (Test-Path (Join-Path $sdk 'platform-tools\adb.exe')) {
    Join-Path $sdk 'platform-tools\adb.exe'
} elseif (Test-Path 'D:\tools\platform-tools\adb.exe') {
    'D:\tools\platform-tools\adb.exe'
} else {
    'adb'
}
$emulator = if (Test-Path 'D:\tools\android-dev\emu34\emulator\emulator.exe') {
    'D:\tools\android-dev\emu34\emulator\emulator.exe'
} else {
    Join-Path $sdk 'emulator\emulator.exe'
}
$package = 'com.networkcontrol.android.kotlin.debug'
$version = '0.15.5'

function Get-Sha256([string]$FilePath) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.IO.File]::ReadAllBytes($FilePath)
        return [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '')
    }
    finally {
        $sha.Dispose()
    }
}

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
        # Release 按 ABI 拆分：arm64 是給一般使用者的主安裝包，x86_64 只給模擬器。
        $outDir = Join-Path $root 'artifacts\kotlin-android'
        New-Item -ItemType Directory -Force $outDir | Out-Null
        $variants = @(
            @{ Abi = 'arm64-v8a'; Name = ("PocketGate-{0}.apk" -f $version) },
            @{ Abi = 'x86_64'; Name = ("PocketGate-{0}-x86_64-emulator.apk" -f $version) }
        )
        foreach ($variant in $variants) {
            $source = Join-Path $project ("app\build\outputs\apk\release\app-{0}-release.apk" -f $variant.Abi)
            if (-not (Test-Path $source)) { throw "Release APK not found: $source" }
            $target = Join-Path $outDir $variant.Name
            Copy-Item $source $target -Force
            $size = (Get-Item $target).Length
            $hash = Get-Sha256 $target
            Write-Host ("Release APK ({0}): {1} ({2:N1} MB)" -f $variant.Abi, $target, ($size / 1MB))
            Write-Host ("Size: {0} bytes" -f $size)
            Write-Host ("SHA-256: {0}" -f $hash)
        }
    }
    'baseline' {
        # 重新生成 Baseline Profile（需要模擬器；開發機可能同時連著實機，只用模擬器）。
        if (-not $env:ANDROID_SERIAL) { $env:ANDROID_SERIAL = 'emulator-5554' }
        Invoke-Gradle @(':app:generateReleaseBaselineProfile')
        Write-Host ("Baseline profile: {0}" -f (Join-Path $project 'app\src\release\generated\baselineProfiles\baseline-prof.txt'))
    }
    'startup' {
        # 量測冷啟動（am start -W 的 TotalTime，取 5 次）。用法：.\scripts\android-kotlin.ps1 startup
        if (-not $env:ANDROID_SERIAL) { $env:ANDROID_SERIAL = 'emulator-5554' }
        $releasePackage = 'com.networkcontrol.android.kotlin'
        $times = @()
        foreach ($i in 1..5) {
            & $adb shell am force-stop $releasePackage | Out-Null
            Start-Sleep -Seconds 2
            $output = & $adb shell am start -W -n "$releasePackage/com.networkcontrol.android.kotlin.MainActivity"
            $line = $output | Where-Object { $_ -match 'TotalTime' }
            if ($line -match '(\d+)') { $times += [int]$Matches[1] }
            Start-Sleep -Seconds 3
        }
        $sorted = $times | Sort-Object
        Write-Host ("Cold start TotalTime (ms): {0}" -f ($times -join ', '))
        Write-Host ("Median: {0} ms" -f $sorted[[int]($sorted.Count / 2)])
    }
    'aab' {
        Invoke-Gradle @('bundleRelease')
        $source = Join-Path $project 'app\build\outputs\bundle\release\app-release.aab'
        if (-not (Test-Path $source)) { throw "Release AAB not found: $source" }
        $outDir = Join-Path $root 'artifacts\kotlin-android'
        New-Item -ItemType Directory -Force $outDir | Out-Null
        $target = Join-Path $outDir ("PocketGate-{0}.aab" -f $version)
        Copy-Item $source $target -Force
        $size = (Get-Item $target).Length
        $hash = Get-Sha256 $target
        Write-Host ("Release AAB: {0} ({1:N1} MB)" -f $target, ($size / 1MB))
        Write-Host ("Size: {0} bytes" -f $size)
        Write-Host ("SHA-256: {0}" -f $hash)
    }
    'emulator' {
        $env:ANDROID_AVD_HOME = $avdHome
        & $emulator -avd nc35 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -no-boot-anim
    }
}
