<#
.SYNOPSIS
    構建並安裝 NetworkControl（Windows）：核心服務註冊為自動啟動的系統服務，界面發佈到同一目錄。

.DESCRIPTION
    - 以 self-contained 方式發佈，目標機器不需要預先安裝 .NET；
    - 服務以 LocalSystem 運行（WFP 需要），失敗後自動重啟；
    - WFP 過濾器全部建在動態會話中：服務停止 / 崩潰時由系統自動刪除，網絡恢復暢通（fail-open）。
    MVP 階段的安裝方式，正式發布時由 MSI 安裝包取代（需求 FR-SYS-01）。

.EXAMPLE
    # 在管理員 PowerShell 中，於倉庫根目錄執行
    .\scripts\install-service.ps1
#>
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string] $InstallDir = (Join-Path $env:ProgramFiles 'NetworkControl'),
    [ValidateSet('Release', 'Debug')]
    [string] $Configuration = 'Release',
    [switch] $NoStart
)

$ErrorActionPreference = 'Stop'
$ServiceName = 'NetworkControl'
$repo = Split-Path -Parent $PSScriptRoot
$rid = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'win-arm64' } else { 'win-x64' }

function Invoke-Sc([string[]] $Arguments) {
    & sc.exe @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "sc.exe $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

Write-Host "==> Publishing ($Configuration, $rid)" -ForegroundColor Cyan
$staging = Join-Path ([IO.Path]::GetTempPath()) "networkcontrol-publish-$([Guid]::NewGuid().ToString('N'))"
dotnet publish (Join-Path $repo 'src/NetworkControl.Service/NetworkControl.Service.csproj') -c $Configuration -r $rid --self-contained -o (Join-Path $staging 'Service') -nologo
if ($LASTEXITCODE -ne 0) { throw 'Publishing the service failed.' }
dotnet publish (Join-Path $repo 'src/NetworkControl.App/NetworkControl.App.csproj') -c $Configuration -r $rid --self-contained -o (Join-Path $staging 'App') -nologo
if ($LASTEXITCODE -ne 0) { throw 'Publishing the app failed.' }

$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "==> Stopping existing service" -ForegroundColor Cyan
    if ($existing.Status -ne 'Stopped') { Stop-Service -Name $ServiceName -Force }
    $existing.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
}

# 界面進程會鎖住自己的文件：升級前關閉
Get-Process -Name 'NetworkControl' -ErrorAction SilentlyContinue | Stop-Process -Force

Write-Host "==> Copying files to $InstallDir" -ForegroundColor Cyan
New-Item -ItemType Directory -Force $InstallDir | Out-Null
foreach ($part in 'Service', 'App') {
    $target = Join-Path $InstallDir $part
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Copy-Item -Recurse (Join-Path $staging $part) $target
}
Remove-Item -Recurse -Force $staging

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

if (-not $NoStart) {
    Write-Host "==> Starting service" -ForegroundColor Cyan
    Start-Service -Name $ServiceName
    (Get-Service -Name $ServiceName).WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
}

Write-Host ""
Write-Host "NetworkControl installed." -ForegroundColor Green
Write-Host "  Service : $exe"
Write-Host "  App     : $(Join-Path $InstallDir 'App\NetworkControl.exe')"
Write-Host "  Data    : $(Join-Path $env:ProgramData 'NetworkControl')"
Write-Host "Start the app, then enable 'Start the interface when I sign in' in Settings if you like."
