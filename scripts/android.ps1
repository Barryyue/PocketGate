<#
.SYNOPSIS
  Android 版構建、安裝、運行（docs/android.md §10）。

.DESCRIPTION
  使用用戶級的獨立工具鏈（.NET SDK + Android 工作負載、JDK 17、Android SDK），不需要管理員權限，也不影響系統的 dotnet。
  工具鏈根目錄默認為 D:\tools\android-dev，可用環境變量 NC_ANDROID_TOOLCHAIN 覆蓋；其下應有 dotnet、jdk、android-sdk 三個目錄。

.EXAMPLE
  .\scripts\android.ps1 build            # Debug APK
  .\scripts\android.ps1 install          # 構建並安裝到已連接的設備 / 模擬器
  .\scripts\android.ps1 run              # 安裝並啟動
  .\scripts\android.ps1 logcat           # 查看應用日誌
  .\scripts\android.ps1 publish          # Release APK（需要 NC_ANDROID_KEYSTORE 等簽名環境變量，否則使用調試簽名）
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('build', 'install', 'run', 'logcat', 'publish', 'env')]
    [string]$Command = 'build',
    [string]$Configuration = '',
    [string]$Device = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$toolchain = if ($env:NC_ANDROID_TOOLCHAIN) { $env:NC_ANDROID_TOOLCHAIN } else { 'D:\tools\android-dev' }
$dotnet = Join-Path $toolchain 'dotnet\dotnet.exe'
$sdk = if ($env:NC_ANDROID_SDK) {
    $env:NC_ANDROID_SDK
} elseif (Test-Path (Join-Path $toolchain 'android-sdk')) {
    Join-Path $toolchain 'android-sdk'
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$jdk = if ($env:NC_ANDROID_JDK) {
    $env:NC_ANDROID_JDK
} elseif (Test-Path (Join-Path $toolchain 'jdk')) {
    Join-Path $toolchain 'jdk'
} elseif (Test-Path (Join-Path $env:USERPROFILE '.jdks\corretto-21.0.5')) {
    Join-Path $env:USERPROFILE '.jdks\corretto-21.0.5'
} elseif (Test-Path (Join-Path $env:USERPROFILE '.jdks\corretto-17.0.13')) {
    Join-Path $env:USERPROFILE '.jdks\corretto-17.0.13'
} else {
    $env:JAVA_HOME
}
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$project = Join-Path $root 'src\NetworkControl.App.Android\NetworkControl.App.Android.csproj'
$packageId = 'com.networkcontrol.android'

foreach ($required in @($dotnet, $sdk, $jdk)) {
    if (-not (Test-Path $required)) {
        throw "找不到 Android 工具鏈：$required。安裝方法見 docs/development.md「Android」一節。"
    }
}

$env:DOTNET_ROOT = Join-Path $toolchain 'dotnet'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$commonArgs = @("-p:AndroidSdkDirectory=$sdk", "-p:JavaSdkDirectory=$jdk", '-nologo')
$adbArgs = @()
if ($Device) { $adbArgs = @('-s', $Device) }

function Invoke-Dotnet([string[]]$arguments) {
    & $dotnet @arguments
    if ($LASTEXITCODE -ne 0) { throw "dotnet $($arguments[0]) 失敗（退出碼 $LASTEXITCODE）" }
}

switch ($Command) {
    'env' {
        Write-Host "DOTNET_ROOT=$env:DOTNET_ROOT"
        Write-Host "JAVA_HOME=$env:JAVA_HOME"
        Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
    }
    'build' {
        $config = if ($Configuration) { $Configuration } else { 'Debug' }
        Invoke-Dotnet (@('build', $project, '-c', $config) + $commonArgs)
    }
    { $_ -in 'install', 'run' } {
        $config = if ($Configuration) { $Configuration } else { 'Debug' }
        # -t:Install 會按已連接設備的 ABI 構建並安裝
        $installArgs = @('build', $project, '-c', $config, '-t:Install') + $commonArgs
        if ($Device) { $installArgs += "-p:AdbTarget=-s $Device" }
        Invoke-Dotnet $installArgs
        if ($Command -eq 'run') {
            & $adb @adbArgs shell monkey -p $packageId -c android.intent.category.LAUNCHER 1 | Out-Null
            Write-Host "已啟動 $packageId"
        }
    }
    'logcat' {
        & $adb @adbArgs logcat -v time -s 'NetworkControl:*' 'AndroidRuntime:E' 'DOTNET:*' 'monodroid:*'
    }
    'publish' {
        $config = if ($Configuration) { $Configuration } else { 'Release' }
        $outDir = Join-Path $root 'artifacts\android'
        $publishArgs = @('publish', $project, '-c', $config, '-o', $outDir) + $commonArgs
        $storePass = if ($env:NC_ANDROID_STORE_PASS) { $env:NC_ANDROID_STORE_PASS } else { $env:NC_ANDROID_KEYSTORE_PASSWORD }
        $keyPass = if ($env:NC_ANDROID_KEY_PASS) { $env:NC_ANDROID_KEY_PASS } else { $env:NC_ANDROID_KEY_PASSWORD }
        if ($env:NC_ANDROID_KEYSTORE) {
            $env:NC_ANDROID_STORE_PASS = $storePass
            $env:NC_ANDROID_KEY_PASS = $keyPass
            $publishArgs += @(
                '-p:AndroidKeyStore=true',
                "-p:AndroidSigningKeyStore=$env:NC_ANDROID_KEYSTORE",
                "-p:AndroidSigningKeyAlias=$env:NC_ANDROID_KEY_ALIAS",
                '-p:AndroidSigningStorePass=env:NC_ANDROID_STORE_PASS',
                '-p:AndroidSigningKeyPass=env:NC_ANDROID_KEY_PASS')
        }
        else {
            Write-Warning '未設置 NC_ANDROID_KEYSTORE：使用調試簽名，只適合測試，不能用於正式分發。'
        }
        Invoke-Dotnet $publishArgs
        $versionMatch = Select-String -Path $project -Pattern '<AtomUiVersion>([^<]+)</AtomUiVersion>' | Select-Object -First 1
        $ver = if ($versionMatch) { $versionMatch.Matches[0].Groups[1].Value } else { '0.3.1' }
        $signedApk = Get-ChildItem $outDir -Filter '*-Signed.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($signedApk) {
            $atomUiTarget = Join-Path $outDir ("PocketGate-{0}-AtomUI.apk" -f $ver)
            Copy-Item $signedApk.FullName $atomUiTarget -Force
            $sha = [System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash([System.IO.File]::ReadAllBytes($atomUiTarget))).Replace('-', '')
            $size = (Get-Item $atomUiTarget).Length
            Write-Host ("AtomUI APK: {0} ({1:N1} MB, {2} bytes)" -f $atomUiTarget, ($size / 1MB), $size)
            Write-Host ("SHA-256: {0}" -f $sha)
        }
    }
}
