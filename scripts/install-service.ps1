<#
.SYNOPSIS
    安裝 NetworkControl（Windows）：核心服務註冊為自動啟動的系統服務，界面安裝到同一目錄。

.DESCRIPTION
    兩種用法：
    - 發布包：腳本旁邊有 App\ 和 Service\ 目錄（由 publish-release.ps1 生成的 Native AOT 版本）時直接安裝，不需要 .NET SDK；
    - 倉庫：從源碼以 self-contained 方式構建後安裝（開發用，需要 .NET SDK）。
    服務以 LocalSystem 運行（WFP 需要），失敗後自動重啟；WFP 過濾器全部建在動態會話中，
    服務停止 / 崩潰時由系統自動刪除，網絡恢復暢通（fail-open）。
    安裝完成後以當前用戶身份（非提權）啟動界面，並設置登錄時啟動：「詢問」模式下沒有界面就沒人回應彈窗，
    未知連接會按默認動作被拒絕。
    兼容 Windows PowerShell 5.1（不使用 PowerShell 7 專有語法）。文件以 UTF-8 BOM 保存，否則 5.1 按系統代碼頁讀取會亂碼。

.EXAMPLE
    # 發布包：雙擊 install.cmd（會請求管理員權限）
    # 倉庫：在管理員 PowerShell 中，於倉庫根目錄執行
    .\scripts\install-service.ps1
#>
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string] $InstallDir = (Join-Path $env:ProgramFiles 'NetworkControl'),
    [ValidateSet('Release', 'Debug')]
    [string] $Configuration = 'Release',
    [switch] $NoStart,
    [switch] $NoAutoStart
)

$ErrorActionPreference = 'Stop'
$ServiceName = 'NetworkControl'

function Invoke-Sc([string[]] $Arguments) {
    & sc.exe @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "sc.exe $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

# 1. 準備文件：發布包直接使用，否則從源碼構建
$packaged = (Test-Path (Join-Path $PSScriptRoot 'App\NetworkControl.exe')) -and (Test-Path (Join-Path $PSScriptRoot 'Service\NetworkControl.Service.exe'))
$staging = $null
if ($packaged) {
    $source = $PSScriptRoot
    Write-Host "==> Installing from package $source" -ForegroundColor Cyan
} else {
    $repo = Split-Path -Parent $PSScriptRoot
    $rid = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'win-arm64' } else { 'win-x64' }
    Write-Host "==> Publishing from source ($Configuration, $rid)" -ForegroundColor Cyan
    $staging = Join-Path ([IO.Path]::GetTempPath()) "networkcontrol-publish-$([Guid]::NewGuid().ToString('N'))"
    dotnet publish (Join-Path $repo 'src/NetworkControl.Service/NetworkControl.Service.csproj') -c $Configuration -r $rid --self-contained -o (Join-Path $staging 'Service') -nologo
    if ($LASTEXITCODE -ne 0) { throw 'Publishing the service failed.' }
    dotnet publish (Join-Path $repo 'src/NetworkControl.App/NetworkControl.App.csproj') -c $Configuration -r $rid --self-contained -o (Join-Path $staging 'App') -nologo
    if ($LASTEXITCODE -ne 0) { throw 'Publishing the app failed.' }
    $source = $staging
}

# 2. 停止舊版本
$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "==> Stopping existing service" -ForegroundColor Cyan
    if ($existing.Status -ne 'Stopped') { Stop-Service -Name $ServiceName -Force }
    $existing.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
}

# 界面進程會鎖住自己的文件：升級前關閉
Get-Process -Name 'NetworkControl' -ErrorAction SilentlyContinue | Stop-Process -Force

# 3. 複製文件
Write-Host "==> Copying files to $InstallDir" -ForegroundColor Cyan
New-Item -ItemType Directory -Force $InstallDir | Out-Null
foreach ($part in 'Service', 'App') {
    $target = Join-Path $InstallDir $part
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Copy-Item -Recurse (Join-Path $source $part) $target
}

# 發布包中的維護腳本一併安裝，之後可以從安裝目錄卸載 / 導出診斷包
if ($packaged) {
    foreach ($file in 'uninstall.cmd', 'uninstall-service.ps1', 'export-diagnostics.cmd', 'README.txt') {
        $path = Join-Path $source $file
        if (Test-Path $path) { Copy-Item -Force $path $InstallDir }
    }
}

if ($staging) { Remove-Item -Recurse -Force $staging }

# 4. 註冊服務
$exe = Join-Path $InstallDir 'Service\NetworkControl.Service.exe'
if (-not $existing) {
    Write-Host "==> Registering service" -ForegroundColor Cyan
    Invoke-Sc @('create', $ServiceName, 'binPath=', "`"$exe`"", 'start=', 'auto', 'obj=', 'LocalSystem', 'DisplayName=', 'NetworkControl')
} else {
    Invoke-Sc @('config', $ServiceName, 'binPath=', "`"$exe`"", 'start=', 'auto')
}

Invoke-Sc @('description', $ServiceName, 'NetworkControl core service: per-application network access control.')
# 崩潰後 3 秒重啟，連續失敗時延長到 10 秒；一天後重置失敗計數（NFR-REL-01）
Invoke-Sc @('failure', $ServiceName, 'reset=', '86400', 'actions=', 'restart/3000/restart/3000/restart/10000')

# 5. 界面：開始菜單快捷方式、登錄時啟動
$appExe = Join-Path $InstallDir 'App\NetworkControl.exe'
$shell = New-Object -ComObject WScript.Shell
$programs = [Environment]::GetFolderPath('CommonPrograms')
$shortcut = $shell.CreateShortcut((Join-Path $programs 'NetworkControl.lnk'))
$shortcut.TargetPath = $appExe
$shortcut.WorkingDirectory = Split-Path $appExe
$shortcut.Save()

if (-not $NoAutoStart) {
    # 與界面「登錄時啟動」設置使用同一個註冊表項（AutoStartService）
    Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'NetworkControl' -Value "`"$appExe`" --minimized"
}

# 6. 啟動
if (-not $NoStart) {
    Write-Host "==> Starting service" -ForegroundColor Cyan
    Start-Service -Name $ServiceName
    (Get-Service -Name $ServiceName).WaitForStatus('Running', [TimeSpan]::FromSeconds(30))

    # 通過資源管理器啟動界面：以當前用戶的普通權限運行，而不是繼承本腳本的管理員權限
    Write-Host "==> Starting the interface" -ForegroundColor Cyan
    Start-Process -FilePath 'explorer.exe' -ArgumentList "`"$appExe`""
}

Write-Host ""
Write-Host "NetworkControl installed." -ForegroundColor Green
Write-Host "  Service : $exe"
Write-Host "  App     : $appExe"
Write-Host "  Data    : $(Join-Path $env:ProgramData 'NetworkControl')"
Write-Host "  Logs    : $(Join-Path $env:ProgramData 'NetworkControl\logs')"
