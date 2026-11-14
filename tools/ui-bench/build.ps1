# UI Benchmark 構建腳本（Windows PowerShell 5.1 兼容）
# 使用 D:\tools\android-dev 的用戶級 Android 工具鏈，不影響主解決方案。
param(
    [ValidateSet('AtomUI', 'Fluent', 'Material', 'All')]
    [string]$Variant = 'All',
    [ValidateSet('x64', 'arm64')]
    [string]$Abi = 'x64',
    [switch]$Aot,
    [string]$Configuration = 'Release'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$toolchain = if ($env:NC_ANDROID_TOOLCHAIN) { $env:NC_ANDROID_TOOLCHAIN } else { 'D:\tools\android-dev' }
$dotnet = Join-Path $toolchain 'dotnet\dotnet.exe'
$sdk = Join-Path $toolchain 'android-sdk'
$jdk = Join-Path $toolchain 'jdk'

foreach ($required in @($dotnet, $sdk, $jdk)) {
    if (-not (Test-Path $required)) {
        throw "找不到 Android 工具鏈：$required"
    }
}

$env:DOTNET_ROOT = Join-Path $toolchain 'dotnet'
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:NuGetAudit = 'false'

$variants = @{
    AtomUI   = 'atomui'
    Fluent   = 'fluent'
    Material = 'material'
}
$selected = if ($Variant -eq 'All') { @('AtomUI', 'Fluent', 'Material') } else { @($Variant) }
$aotValue = if ($Aot) { 'true' } else { 'false' }

foreach ($name in $selected) {
    $project = Join-Path $root ("UiBench.{0}\UiBench.{0}.csproj" -f $name)
    if (-not (Test-Path $project)) {
        Write-Warning "跳過 $name：$project 不存在"
        continue
    }

    $output = Join-Path $root ("artifacts\{0}-{1}" -f $variants[$name], $Abi)
    Write-Host "=== 構建 $name ($Configuration, $Abi, AOT=$aotValue) ==="
    & $dotnet publish $project -c $Configuration `
        -p:BenchAbi=$Abi -p:BenchAot=$aotValue `
        -p:AndroidSdkDirectory=$sdk -p:JavaSdkDirectory=$jdk `
        -o $output -v:m --nologo
    if ($LASTEXITCODE -ne 0) {
        throw "$name 構建失敗（退出碼 $LASTEXITCODE）"
    }

    $apk = Get-ChildItem $output -Filter '*-Signed.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($apk) {
        $sizeMb = [math]::Round($apk.Length / 1MB, 2)
        Write-Host ("APK: {0} ({1} MB)" -f $apk.FullName, $sizeMb)
    }
}
