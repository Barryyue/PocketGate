# UI Benchmark 自動對比腳本（Windows PowerShell 5.1 兼容）
# 安裝三個變體，各自冷啟動 N 次，收集 am start -W 的 TotalTime 與應用內 logged metrics。
param(
    [ValidateSet('x64', 'arm64')]
    [string]$Abi = 'x64',
    [int]$Runs = 3,
    [string]$Adb = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$toolchain = if ($env:NC_ANDROID_TOOLCHAIN) { $env:NC_ANDROID_TOOLCHAIN } else { 'D:\tools\android-dev' }
if (-not $Adb) {
    $Adb = Join-Path $toolchain 'android-sdk\platform-tools\adb.exe'
}
if (-not (Test-Path $Adb)) {
    throw "找不到 adb：$Adb"
}

$variants = @(
    @{ Name = 'AtomUI'; Package = 'com.networkcontrol.uibench.atomui'; Dir = 'atomui' },
    @{ Name = 'Fluent'; Package = 'com.networkcontrol.uibench.fluent'; Dir = 'fluent' },
    @{ Name = 'Material'; Package = 'com.networkcontrol.uibench.material'; Dir = 'material' }
)

function Get-InstalledActivity([string]$package) {
    $line = & $Adb shell cmd package resolve-activity --brief $package 2>$null | Select-Object -Last 1
    if ($line -match '^([^/]+)/(.+)$') {
        return $Matches[2]
    }
    throw "找不到 $package 的 Activity（是否已安裝？）"
}

$rows = @()
foreach ($variant in $variants) {
    $apk = Get-ChildItem (Join-Path $root ("artifacts\{0}-{1}" -f $variant.Dir, $Abi)) -Filter '*-Signed.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) {
        Write-Warning "跳過 $($variant.Name)：沒有 $Abi APK，先執行 build.ps1 -Abi $Abi"
        continue
    }

    Write-Host "=== 安裝 $($variant.Name) ==="
    & $Adb install -r $apk.FullName 2>&1 | Select-Object -Last 2
    if ($LASTEXITCODE -ne 0) {
        throw "$($variant.Name) 安裝失敗"
    }

    $activity = Get-InstalledActivity $variant.Package
    $times = @()
    $details = @()

    for ($i = 1; $i -le $Runs; $i++) {
        & $Adb logcat -c 2>$null
        & $Adb shell am force-stop $variant.Package 2>$null
        $startOutput = & $Adb shell am start -W -S -n ("{0}/{1}" -f $variant.Package, $activity) 2>&1
        $totalLine = $startOutput | Where-Object { $_ -match '^TotalTime:\s*(\d+)' } | Select-Object -First 1
        if (-not $totalLine) {
            Write-Warning "$($variant.Name) 第 $i 次啟動失敗：$($startOutput -join ' ')"
            continue
        }
        $total = [int]([regex]::Match($totalLine, '\d+').Value)
        $times += $total

        Start-Sleep -Seconds 1
        $logs = & $Adb logcat -d -s UiBench:I 2>$null
        $metricsLine = $logs | Where-Object { $_ -match 'metrics variant=' } | Select-Object -Last 1
        $cold = ''
        $pss = ''
        $apkMb = ''
        if ($metricsLine) {
            $coldMatch = [regex]::Match($metricsLine, 'cold_start_ms=([0-9.]+)')
            $pssMatch = [regex]::Match($metricsLine, 'pss_mb=([0-9.]+)')
            $apkMatch = [regex]::Match($metricsLine, 'apk_mb=([0-9.]+)')
            if ($coldMatch.Success) { $cold = [double]$coldMatch.Groups[1].Value }
            if ($pssMatch.Success) { $pss = [double]$pssMatch.Groups[1].Value }
            if ($apkMatch.Success) { $apkMb = [double]$apkMatch.Groups[1].Value }
        }
        $details += [pscustomobject]@{ TotalTime = $total; ColdStart = $cold; Pss = $pss; ApkMb = $apkMb }
        Write-Host ("  run {0}: TotalTime={1} ms, cold_start={2} ms, PSS={3} MB" -f $i, $total, $cold, $pss)
    }

    if ($times.Count -gt 0) {
        $sorted = $times | Sort-Object
        $median = $sorted[[int][math]::Floor($sorted.Count / 2)]
        $coldValues = $details | Where-Object { $_.ColdStart -ne '' } | ForEach-Object { $_.ColdStart } | Sort-Object
        $coldMedian = if ($coldValues.Count -gt 0) { $coldValues[[int][math]::Floor($coldValues.Count / 2)] } else { '' }
        $pssValues = $details | Where-Object { $_.Pss -ne '' } | ForEach-Object { $_.Pss } | Sort-Object
        $pssMedian = if ($pssValues.Count -gt 0) { $pssValues[[int][math]::Floor($pssValues.Count / 2)] } else { '' }
        $apkValue = if ($details.Count -gt 0) { $details[0].ApkMb } else { '' }
        $rows += [pscustomobject]@{
            Variant = $variant.Name
            'am TotalTime (median)' = "$median ms"
            'App cold start (median)' = if ($coldMedian -ne '') { "$coldMedian ms" } else { '—' }
            'PSS' = if ($pssMedian -ne '') { "$pssMedian MB" } else { '—' }
            'APK' = if ($apkValue -ne '') { "$apkValue MB" } else { '—' }
        }
    }
}

Write-Host ''
Write-Host '=== 對比結果（同一台設備、同一 Abi、相同 AOT 設定）==='
$rows | Format-Table -AutoSize
