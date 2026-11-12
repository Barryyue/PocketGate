<#
.SYNOPSIS
    生成 Windows 發布包（Native AOT）：artifacts\release\NetworkControl-<版本>-<rid>.zip。

.DESCRIPTION
    同時生成安裝程序 NetworkControl-<版本>-<rid>-setup.exe（需要 Inno Setup 6，找不到時跳過）和 zip 發布包，
    兩者內容相同，目標機器都不需要 .NET。版本號只在 Directory.Build.props 的 VersionPrefix 中維護。
    zip 發布包結構：
      App\       界面（Native AOT）
      Service\   核心服務（Native AOT）
      install.cmd / uninstall.cmd / export-diagnostics.cmd 及對應的 PowerShell 腳本、README.txt
    調試符號（*.pdb）單獨放在 artifacts\release\symbols-<版本>-<rid>\，不進入發布包，用於分析崩潰轉儲。

    需要：.NET SDK、Visual Studio 的「使用 C++ 的桌面開發」工作負載（Native AOT 需要 MSVC 鏈接器）。
    運行時版本由 Directory.Build.props 在 AOT 發布時固定（避開本機 SDK 自帶的有漏洞版本）。

.EXAMPLE
    .\scripts\publish-release.ps1
    .\scripts\publish-release.ps1 -Runtime win-arm64
#>
[CmdletBinding()]
param(
    [ValidateSet('win-x64', 'win-arm64')]
    [string] $Runtime = 'win-x64',
    [string] $OutputDir,
    # Inno Setup 編譯器路徑；不指定時在常見安裝位置查找，找不到則只生成 zip
    [string] $Iscc,
    [switch] $NoInstaller
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$AcceptedAotWarnings = @('Serilog', 'Microsoft.Diagnostics.Tracing.TraceEvent')
if (-not $OutputDir) { $OutputDir = Join-Path $repo 'artifacts\release' }

# Native AOT 的鏈接步驟通過 vswhere 定位 MSVC；VS 安裝程序目錄默認不在 PATH 中，缺少時鏈接失敗（MSB3073）
$installer = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer'
if ((Test-Path (Join-Path $installer 'vswhere.exe')) -and ($env:PATH -notlike "*$installer*")) {
    $env:PATH = "$installer;$env:PATH"
}

[xml] $props = Get-Content (Join-Path $repo 'Directory.Build.props')
$version = ($props.Project.PropertyGroup | ForEach-Object { $_.VersionPrefix } | Where-Object { $_ } | Select-Object -First 1)
$commit = (git -C $repo rev-parse --short HEAD 2>$null)
# 影響構建產物的文件有未提交的修改時標記出來：這樣的包無法對應到某個確定的提交，不應該發給別人（文檔、網站等不影響產物）
if (git -C $repo status --porcelain -- src installer scripts Directory.Build.props Directory.Packages.props global.json 2>$null) { $commit = "$commit-dirty"; Write-Warning 'The working tree has uncommitted changes; the package will be marked as dirty.' }
$name = "NetworkControl-$version-$Runtime"
$staging = Join-Path $OutputDir $name
$symbols = Join-Path $OutputDir "symbols-$version-$Runtime"

foreach ($dir in $staging, $symbols) {
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
}

foreach ($project in @(@('Service', 'NetworkControl.Service'), @('App', 'NetworkControl.App'))) {
    $part = $project[0]
    Write-Host "==> Publishing $part ($Runtime, Native AOT)" -ForegroundColor Cyan
    $output = dotnet publish (Join-Path $repo "src\$($project[1])\$($project[1]).csproj") -c Release -r $Runtime -p:PublishAot=true -o (Join-Path $staging $part) -nologo 2>&1
    $output | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Publishing $part failed." }

    # 只允許已評估過的第三方程序集產生 AOT / 裁剪彙總警告（見 NetworkControl.Service.csproj），新出現的必須先評估
    $unexpected = $output | Select-String "Assembly '([^']+)' produced (trim|AOT analysis) warnings" | ForEach-Object { $_.Matches[0].Groups[1].Value } |
        Where-Object { $AcceptedAotWarnings -notcontains $_ } | Sort-Object -Unique
    if ($unexpected) { throw "New AOT/trim warnings from: $($unexpected -join ', '). Review them before releasing." }

    # 調試符號另存
    $target = Join-Path $symbols $part
    New-Item -ItemType Directory -Force $target | Out-Null
    Get-ChildItem (Join-Path $staging $part) -Filter *.pdb -Recurse | Move-Item -Destination $target
}

# 清理發布包中不需要的文件
$service = Join-Path $staging 'Service'
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $service 'appsettings.Development.json'), (Join-Path $service '*.staticwebassets.endpoints.json')
# TraceEvent 為每種架構都帶了原生庫，只保留目標架構的
$keepArch = if ($Runtime -eq 'win-arm64') { 'arm64' } else { 'amd64' }
foreach ($arch in 'amd64', 'arm64', 'x86') {
    if ($arch -ne $keepArch) { Remove-Item -Recurse -Force -ErrorAction SilentlyContinue (Join-Path $service $arch) }
}

# 安裝 / 卸載 / 診斷腳本
Copy-Item (Join-Path $PSScriptRoot 'install-service.ps1'), (Join-Path $PSScriptRoot 'uninstall-service.ps1') $staging
Copy-Item (Join-Path $PSScriptRoot 'package\*') $staging
$readme = Join-Path $staging 'README.txt'
# 帶 BOM 的 UTF-8：舊版記事本和 Windows PowerShell 5.1 才能正確識別
[IO.File]::WriteAllText($readme, [IO.File]::ReadAllText($readme).Replace('{{VERSION}}', "$version ($commit)").Replace("`n", "`r`n"), [Text.UTF8Encoding]::new($true))

$zip = Join-Path $OutputDir "$name.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zip
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash

# 安裝程序（Inno Setup）：與 zip 內容相同，額外提供安裝嚮導、「應用和功能」中的卸載入口、原地升級
$setup = $null
if (-not $NoInstaller) {
    if (-not $Iscc) {
        $Iscc = @(
            (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
            (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
            (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe')
        ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    }

    if ($Iscc) {
        Write-Host "==> Building installer (Inno Setup)" -ForegroundColor Cyan
        & $Iscc /Qp "/DAppVersion=$version" "/DNumericVersion=$version.0" "/DBuildLabel=$commit" "/DSourceDir=$staging" "/DOutputDir=$OutputDir" "/DRuntime=$Runtime" (Join-Path $repo 'installer\NetworkControl.iss')
        if ($LASTEXITCODE -ne 0) { throw 'Building the installer failed.' }
        $setup = Join-Path $OutputDir "NetworkControl-$version-$Runtime-setup.exe"
    } else {
        Write-Warning 'Inno Setup (ISCC.exe) was not found; only the zip package was created.'
    }
}

Write-Host ""
Write-Host "Release package created (version $version, $commit)." -ForegroundColor Green
Write-Host "  Package   : $zip ($([Math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"
Write-Host "  SHA256    : $hash"
if ($setup) {
    Write-Host "  Installer : $setup ($([Math]::Round((Get-Item $setup).Length / 1MB, 1)) MB)"
    Write-Host "  SHA256    : $((Get-FileHash $setup -Algorithm SHA256).Hash)"
}
Write-Host "  Symbols   : $symbols"
