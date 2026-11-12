; NetworkControl 安裝程序（Inno Setup 6）。由 scripts\publish-release.ps1 調用 ISCC 編譯，所有參數通過 /D 傳入：
;   AppVersion      顯示版本，例如 0.1.0
;   NumericVersion  四段數字版本（文件版本信息），例如 0.1.0.0
;   BuildLabel      構建標記（提交哈希，未提交時帶 -dirty）
;   SourceDir       發布目錄（含 App\、Service\ 等，與 zip 發布包內容相同）
;   OutputDir       安裝程序輸出目錄
;   Runtime         win-x64 / win-arm64
;
; 升級：AppId 固定不變，新版本安裝時自動識別舊版本並覆蓋（規則和日誌保存在 ProgramData，不受影響）。
; 此文件以 UTF-8 BOM 保存（Inno Setup 據此識別編碼）。

#ifndef AppVersion
  #error AppVersion is required (pass /DAppVersion=x.y.z)
#endif
#ifndef SourceDir
  #error SourceDir is required
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif
#ifndef NumericVersion
  #define NumericVersion AppVersion + ".0"
#endif
#ifndef BuildLabel
  #define BuildLabel ""
#endif
#ifndef Runtime
  #define Runtime "win-x64"
#endif
#if Runtime == "win-arm64"
  #define Arch "arm64"
#else
  #define Arch "x64compatible"
#endif

#define AppName "NetworkControl"
#define ServiceName "NetworkControl"

[Setup]
; 不要修改 AppId：它是識別已安裝版本（升級 / 卸載）的唯一依據
AppId={{815C6B03-1E54-49CE-A3FC-70C00FC5070E}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=NetworkControl
AppComments=Per-application network access control ({#BuildLabel})
VersionInfoVersion={#NumericVersion}
VersionInfoProductVersion={#NumericVersion}
VersionInfoDescription={#AppName} Setup
DefaultDirName={autopf}\{#AppName}
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=admin
ArchitecturesAllowed={#Arch}
ArchitecturesInstallIn64BitMode={#Arch}
; Windows 10 1809 起（與 .NET 10 的最低要求一致）
MinVersion=10.0.17763
OutputDir={#OutputDir}
OutputBaseFilename={#AppName}-{#AppVersion}-{#Runtime}-setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\App\NetworkControl.exe
UninstallDisplayName={#AppName} {#AppVersion}
; 界面進程由 [Code] 中的 PrepareToInstall 結束，不依賴重啟管理器（托盤程序不一定響應關閉請求）
CloseApplications=no
SetupLogging=yes
; 登錄時啟動寫在安裝者（通過 UAC 提權的同一用戶）的 HKCU 中，與界面中的設置項一致
UsedUserAreasWarning=no

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
chinesesimplified.AutoStartTask=登录时启动界面（推荐：“询问”模式下需要界面回应弹窗）
english.AutoStartTask=Start the interface when I sign in (recommended: prompts need the interface in Ask mode)
chinesesimplified.ExportDiagnostics=导出诊断包
english.ExportDiagnostics=Export diagnostics
chinesesimplified.ServiceFailed=无法注册或启动 NetworkControl 服务（%1）。请导出诊断包发给开发者。
english.ServiceFailed=Could not register or start the NetworkControl service (%1). Please export diagnostics and send them to the developer.
chinesesimplified.RemoveDataPrompt=是否同时删除规则和日志（%1）？%n%n选择“否”可在重新安装后继续使用原来的规则。
english.RemoveDataPrompt=Also delete rules and logs (%1)?%n%nChoose No to keep your rules for a future reinstall.

[Tasks]
Name: "autostart"; Description: "{cm:AutoStartTask}"

[Files]
; 注意：不要加入 portable.txt / start.cmd。安裝版必須走系統服務，不能進入可攜模式。
Source: "{#SourceDir}\App\*"; DestDir: "{app}\App"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourceDir}\Service\*"; DestDir: "{app}\Service"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourceDir}\export-diagnostics.cmd"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\README.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\App\NetworkControl.exe"
Name: "{autoprograms}\{#AppName} - {cm:ExportDiagnostics}"; Filename: "{app}\export-diagnostics.cmd"; IconFilename: "{app}\App\NetworkControl.exe"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "{#AppName}"; ValueData: """{app}\App\NetworkControl.exe"" --minimized"; Tasks: autostart

[Run]
; 以安裝者本人的普通權限啟動界面（不是安裝程序的管理員權限）。不提供取消選項：沒有界面時未知連接會按默認動作被拒絕
Filename: "{app}\App\NetworkControl.exe"; Flags: nowait runasoriginaluser

[Code]
const
  ServiceName = '{#ServiceName}';

function RunHidden(const FileName, Params: String): Integer;
var
  Code: Integer;
begin
  if not Exec(FileName, Params, '', SW_HIDE, ewWaitUntilTerminated, Code) then
    Code := -1;
  Result := Code;
end;

function Sc(const Params: String): Integer;
begin
  Result := RunHidden(ExpandConstant('{sys}\sc.exe'), Params);
end;

function ServiceExists(): Boolean;
begin
  Result := Sc('query ' + ServiceName) = 0;
end;

{ net stop / net start 會等待服務進入目標狀態，sc 不會 }
procedure StopServiceAndInterface();
begin
  RunHidden(ExpandConstant('{sys}\taskkill.exe'), '/IM NetworkControl.exe /F');
  if ServiceExists() then
    RunHidden(ExpandConstant('{sys}\net.exe'), 'stop ' + ServiceName);
end;

{ 文件複製之前停止舊版本，否則可執行文件被鎖住無法覆蓋 }
function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopServiceAndInterface();
  Result := '';
end;

procedure RegisterAndStartService();
var
  Exe, BinPath: String;
  Code: Integer;
begin
  Exe := ExpandConstant('{app}\Service\NetworkControl.Service.exe');
  BinPath := 'binPath= "' + Exe + '"';
  if ServiceExists() then
    Code := Sc('config ' + ServiceName + ' ' + BinPath + ' start= auto')
  else
    Code := Sc('create ' + ServiceName + ' ' + BinPath + ' start= auto obj= LocalSystem DisplayName= ' + ServiceName);
  if Code <> 0 then
  begin
    MsgBox(FmtMessage(CustomMessage('ServiceFailed'), ['sc ' + IntToStr(Code)]), mbError, MB_OK);
    Exit;
  end;

  Sc('description ' + ServiceName + ' "NetworkControl core service: per-application network access control."');
  { 崩潰後 3 秒重啟，連續失敗時延長到 10 秒；一天後重置失敗計數 }
  Sc('failure ' + ServiceName + ' reset= 86400 actions= restart/3000/restart/3000/restart/10000');

  Code := RunHidden(ExpandConstant('{sys}\net.exe'), 'start ' + ServiceName);
  if Code <> 0 then
    MsgBox(FmtMessage(CustomMessage('ServiceFailed'), ['net start ' + IntToStr(Code)]), mbError, MB_OK);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  { 服務必須在 [Run] 啟動界面之前就緒（ssPostInstall 在 [Run] 之前執行） }
  if CurStep = ssPostInstall then
    RegisterAndStartService();
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usUninstall then
  begin
    { 先停服務再刪文件。WFP 過濾器在動態會話中，服務停止時由系統自動刪除，卸載後不殘留攔截 }
    StopServiceAndInterface();
    if ServiceExists() then
      Sc('delete ' + ServiceName);
    { 界面中開啟的「登錄時啟動」也一併清理（安裝時沒有勾選任務時 [Registry] 不會處理它） }
    RegDeleteValue(HKEY_CURRENT_USER, 'Software\Microsoft\Windows\CurrentVersion\Run', '{#AppName}');
  end;

  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{commonappdata}\{#AppName}');
    if DirExists(DataDir) and (not UninstallSilent()) then
      if MsgBox(FmtMessage(CustomMessage('RemoveDataPrompt'), [DataDir]), mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(DataDir, True, True, True);
  end;
end;
