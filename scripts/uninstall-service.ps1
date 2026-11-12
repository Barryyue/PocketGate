<#
.SYNOPSIS
    卸載 NetworkControl（Windows）。

.DESCRIPTION
    停止並刪除系統服務。WFP 過濾器全部建在動態會話中，服務停止時已由系統自動刪除，
    卸載後不會殘留任何網絡攔截（FR-SYS-03）。默認保留用戶數據（規則、日誌），加 -RemoveData 一併刪除。
    兼容 Windows PowerShell 5.1；文件以 UTF-8 BOM 保存。

.EXAMPLE
    # 發布包 / 安裝目錄：雙擊 uninstall.cmd
    .\scripts\uninstall-service.ps1 -RemoveData
#>
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string] $InstallDir = (Join-Path $env:ProgramFiles 'NetworkControl'),
    [switch] $RemoveData
)

$ErrorActionPreference = 'Stop'
$ServiceName = 'NetworkControl'

$service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($service) {
    if ($service.Status -ne 'Stopped') {
        Write-Host "==> Stopping service" -ForegroundColor Cyan
        Stop-Service -Name $ServiceName -Force
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    }

    Write-Host "==> Deleting service" -ForegroundColor Cyan
    & sc.exe delete $ServiceName | Out-Host
}

Get-Process -Name 'NetworkControl' -ErrorAction SilentlyContinue | Stop-Process -Force

# 開始菜單快捷方式
Remove-Item -Force (Join-Path ([Environment]::GetFolderPath('CommonPrograms')) 'NetworkControl.lnk') -ErrorAction SilentlyContinue

# 登錄時啟動的註冊表項（當前用戶）
Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'NetworkControl' -ErrorAction SilentlyContinue

if (Test-Path $InstallDir) {
    Write-Host "==> Removing $InstallDir" -ForegroundColor Cyan
    Remove-Item -Recurse -Force $InstallDir
}

if ($RemoveData) {
    $data = Join-Path $env:ProgramData 'NetworkControl'
    if (Test-Path $data) {
        Write-Host "==> Removing data $data" -ForegroundColor Cyan
        Remove-Item -Recurse -Force $data
    }
}

Write-Host "NetworkControl uninstalled." -ForegroundColor Green
