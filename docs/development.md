# 開發指南

## 1. 環境

- .NET SDK 10.0.400 或更高版本（見 `global.json`）。測試使用 Microsoft.Testing.Platform 運行器，命令是 `dotnet test --solution` 或 `dotnet test --project`。
- IDE：Visual Studio 2022 17.14+ / Rider / VS Code + C# Dev Kit。打開 `NetworkControl.slnx`。

## 2. 本地運行

```bash
dotnet run --project src/NetworkControl.Service   # 先啟動服務
dotnet run --project src/NetworkControl.App       # 再啟動 UI（可以隨時重啟，不影響服務）
```

開發模式（`DOTNET_ENVIRONMENT=Development`，`launchSettings.json` 已配置）下：

| 項目 | 位置 / 值 |
|---|---|
| 數據目錄 | 倉庫根目錄的 `.devdata/`（已被 git 忽略），刪除即可重置 |
| 服務日誌 | 控制台 + `.devdata/logs/service-*.log`（配置節點 `NetworkControl:Logging`；安裝版默認寫到 `%ProgramData%\NetworkControl\logs`） |
| 界面日誌 | `%LocalAppData%\NetworkControl\logs\app-*.log`（啟動信息、未處理異常、連接變化、Avalonia 綁定警告） |
| IPC 端點 | Windows：`pipe:NetworkControl.v1`；Linux / macOS 以普通用戶運行時：`$XDG_RUNTIME_DIR/netcontrol/ui.sock`（或 `/tmp/netcontrol-<user>/ui.sock`） |
| 引擎 | `Fake`（模擬引擎，不攔截真實流量；**只存在於 Debug 構建**，Release 需要 `-p:IncludeFakeEngine=true`） |

可以用環境變量 `NETWORKCONTROL_IPC=pipe:<name>` 或 `unix:<path>` 同時覆蓋服務端和 UI 的 IPC 端點，例如並行運行多套實例。

查看核心指標：

```bash
dotnet-counters monitor -n NetworkControl.Service --counters NetworkControl.Core
```

## 3. 擴展點速查

| 我想…… | 做法 | 參考 |
|---|---|---|
| 增加一種判決邏輯（黑名單訂閱、流量配額、時間段規則） | 實現 `IVerdictStage`，選一個 `Order`（`StageOrder` 中的常量之間有間隔），用 `services.AddVerdictStage<T>()` 註冊 | `Core/Pipeline/Stages.cs` |
| 增加一種規則條件 | 在 `RuleConditions` 添加屬性 → `CompiledRule` 中編譯和匹配 → `RuleSpecificity` 中計分 → `Storage/Serialization/StorageDtos.cs` 和 `rules.proto` 中添加字段 | `Core/Rules/*` |
| 接入一個平台引擎 | 新建 `NetworkControl.Engine.<Platform>` 項目：實現 `INetworkEngine`，以及 `IProcessInfoProvider`、`IAppInspector`、`IProcessExitWatcher`；提供 `Add<Platform>NetworkEngine()`；在 `Service/Engines/EngineRegistration.cs` 中添加分支 | `Engine.Fake` |
| 消費連接事件（統計、告警） | 實現 `IActivityConsumer`（必須非阻塞），用 `services.AddActivityConsumer<T>()` 註冊 | `Storage/Activity/ActivityLogWriter.cs` |
| 啟動時執行初始化 | 實現 `IStartupTask`，用 `services.AddStartupTask<T>()` 註冊；它們在引擎啟動前按 `Order` 依次執行 | `Core/Hosting/StartupTasks.cs` |
| 提供內置規則 | 實現 `IBuiltinRuleProvider` 並註冊；內置規則每次啟動時重新生成，不寫入存儲，只能停用不能刪除 | `Core/Rules/RuleService.cs` |
| 修改數據庫結構 | 在 `Storage/Migrations/<Config|Activity>/` 添加下一個編號的 `NNNN_name.sql`。**不要修改已發布的腳本** | `Storage/Sqlite/Migrator.cs` |
| 增加 IPC 接口 | 修改 `Ipc/Protos/*.proto`（只新增字段，不改編號）→ 在 `Service/Grpc/` 中實現 → 在 `ProtoMapper` 中做轉換與輸入校驗 → 寫操作標註 `[Authorize(Policy = IpcPolicies.Admin)]` | `Service/Grpc/*` |
| 增加 UI 頁面 | 新建 `XxxViewModel : PageViewModel`（提供 `Key`）和 `XxxView.axaml`（使用 AtomUI 組件）→ 在 `Infrastructure/ViewLocator.cs` 中註冊映射 → 在 `App.axaml.cs` 中註冊為 `PageViewModel` → 在 `ShellWindow.axaml` 的 `NavMenu` 中加一個 `NavMenuNode`（`ItemKey` 與 `Key` 一致） | `App/ViewModels/*` |
| 增加界面文本 | 在對應功能域的 Catalog 枚舉（`App/Localization/<域>/<域>LangResourceKind.cs`）**末尾**追加成員，並在同目錄的 `en-US.xlf`、`zh-CN.xlf`、`zh-TW.xlf` 中添加同 id 的 unit。XAML 用 `{loc:<域>LangResource Key}`（隨語言切換自動刷新），C# 用 `LocalizationService.Get/Format`。缺翻譯或佔位符不一致會在構建時報錯 | `App/Localization/*` |

## 4. 約定

- **分層依賴**：`Core` 不依賴任何其他項目；`Storage`、`Engine.*` 只依賴 `Core`；`App` 只依賴 `Ipc`（UI 不引用核心，所有數據經 IPC 獲取）。
- **持久化格式與領域模型分離**：存儲用 `Storage/Serialization` 中的 DTO，IPC 用 proto 消息。領域模型可以自由重構，持久化格式和 IPC 契約只做向後兼容的變更。
- **AOT**：正式發布是 Native AOT（§8）。不使用反射式序列化（JSON 用源生成器，配置綁定用源生成器）、不使用反射式視圖定位、不按配置反射加載程序集（Serilog 在代碼中配置）；庫項目標記了 `IsAotCompatible`，`App` 和 `Service` 在日常構建中就啟用 AOT / 裁剪分析器，不兼容的寫法直接構建失敗。
- **時間**：一律通過注入的 `TimeProvider` 獲取，測試中用 `FakeTimeProvider` 推進時間。
- **日誌**：一律使用 `[LoggerMessage]` 源生成器（CA1848 / CA1873 已設為錯誤）。
- **質量門檻**：`TreatWarningsAsErrors`；代碼風格由 `.editorconfig` 約束，構建時強制檢查；包版本集中在 `Directory.Packages.props`。
- **熱路徑**：判決管線中不做 I/O、不做阻塞調用；存儲寫入、簽名校驗等都在後台或緩存之後。

## 5. 測試

```bash
dotnet test --solution NetworkControl.slnx
```

| 項目 | 內容 |
|---|---|
| `NetworkControl.Core.Tests` | 匹配器、規則優先級、決策到規則的轉換；`Scenarios/` 中用完整的核心服務 + 模擬引擎 + `FakeTimeProvider` 跑通 M0 驗收用戶故事（US-01/02/03/07、超時、合併、篡改檢測等） |
| `NetworkControl.Storage.Tests` | 在臨時目錄中對真實 SQLite 做遷移、倉儲往返、日誌聚合、診斷收集（數據庫備份可打開、隱私項默認不收集）等測試 |
| `NetworkControl.Ipc.Tests` | 診斷包：脫敏規則、截取日誌末尾、讀取正被寫入的文件、大小上限、合併、條目路徑規範化 |
| `NetworkControl.Engine.Windows.Tests` | 互操作佈局、規則編譯、IP 補集、DNS 解析、路徑轉換、應用 ID 構造 |

寫新的端到端場景時，複用 `ScenarioHost`：它模擬了一個在線的 UI 會話，`Connect()` 發起連接，`NextPromptAsync()` 等待彈窗，`AnswerAsync()` 回應，`AdvanceAsync()` 推進虛擬時間。

## 6. 界面框架與多語言

- 界面使用 [AtomUI](https://github.com/AtomUI/AtomUI)（Ant Design 6 for Avalonia，LGPL-3.0，以 NuGet 二進制引用，不修改其源碼）。三個包 `AtomUI.Desktop.Controls`、`AtomUI.Desktop.Controls.DataGrid`、`AtomUI.Generator` 的版本必須一致。
- `App` 必須是 `partial`：AtomUI 生成器為它生成啟動掛接和語言 Catalog 註冊。
- 支持語言：簡體中文（zh-CN）、繁體中文（zh-TW，台灣用語）、English（en-US），定義在 `Infrastructure/LocalizationService.cs` 的 `SupportedLanguages`。新增語言時在那裡加標籤，並為**每個** Catalog 補齊 XLIFF，否則構建失敗。
- 顏色和尺寸一律引用 AtomUI 設計令牌（`{atom:SharedTokenResource ColorTextSecondary}` 等），深淺色自動適配。
- 「跟隨系統主題」由 `AppearanceService` 負責，不使用 AtomUI 的 `WithFollowSystemThemes`（它開啟後會覆蓋用戶手動選擇的淺色 / 深色）。

## 7. Windows WFP 引擎

開發時默認使用模擬引擎（`appsettings.Development.json` 中 `Engine = Fake`），不需要管理員權限。

**最簡單的切換方式**：在界面「總覽」頁的「引擎」卡片中選擇「Windows WFP」。界面會讓當前開發服務退出（`Control.Shutdown` 接口），再以管理員權限（UAC）用 WFP 引擎重新啟動它；UAC 被拒絕時自動恢復模擬引擎。界面底部的「結束」按鈕或托盤菜單退出時，會一併關閉開發服務。

也可以手動啟動：

```powershell
# 管理員 PowerShell，倉庫根目錄
dotnet build NetworkControl.slnx
$env:DOTNET_ENVIRONMENT = "Development"; $env:NetworkControl__Service__Engine = "Wfp"
dotnet run --project src/NetworkControl.Service --no-build
# 另一個普通權限的終端
dotnet run --project src/NetworkControl.App --no-build
```

- 所有過濾器建在**動態會話**中：按 Ctrl+C 或服務崩潰時由系統自動刪除，網絡立即恢復。萬一出現異常，結束服務進程即可。
- 查看已下發的過濾器：`netsh wfp show filters`（生成 filters.xml，搜索 `NetworkControl`）。
- 引擎會開啟系統的 WFP 網絡事件收集（`FWPM_ENGINE_COLLECT_NET_EVENTS`），停止時恢復原值。
- 安裝為系統服務：`scripts/install-service.ps1`；卸載：`scripts/uninstall-service.ps1 [-RemoveData]`。

M1 已知限制：

| 限制 | 說明 |
|---|---|
| 先阻斷後詢問 | 用戶態 WFP 無法掛起連接；首次連接失敗，允許後應用重試即可連上（「僅本次」轉為 60 秒臨時規則） |
| 只控制出站 | 入站規則暫不下發（P1） |
| 只記錄阻斷 | 被允許的連接不產生 WFP 事件，連線記錄中只有被阻斷和詢問過的連接 |
| svchost 服務 / MSIX 包 | 帶服務名或包名的規則暫不下發 |
| 簽名 | 只校驗嵌入式 Authenticode 簽名；系統目錄的目錄簽名文件按哈希綁定 |
| 域名規則 | 依賴 ETW DNS-Client 事件；應用自帶 DoH 時無法觀測 |

## 8. 發布、版本與安裝包

**版本號只在一個地方維護**：`Directory.Build.props` 的 `VersionPrefix`。程序集版本、發布包和安裝程序的文件名都從這裡取；程序內部的版本（`AssemblyInformationalVersion`）自動帶提交哈希（例如 `0.1.0+6785a98…`），診斷包裡會記錄，方便對應到代碼。

發新版本：

1. 修改 `VersionPrefix`（語義化版本：修復問題加第三位，新功能加第二位），提交；
2. 在**沒有未提交修改**的工作區運行 `.\scripts\publish-release.ps1`（有未提交修改時版本會標記 `-dirty`）；
3. 把 `artifacts\release\` 下的 `NetworkControl-<版本>-win-x64-setup.exe`（推薦）或 `.zip` 發給用戶，`symbols-*` 目錄自己保留（分析崩潰轉儲用）。

用戶直接運行新版安裝程序即可升級：安裝程序的 `AppId` 固定（**不要修改** `installer/NetworkControl.iss` 中的 AppId），會識別舊版本、停止服務、覆蓋文件、重新啟動；規則和日誌在 `%ProgramData%\NetworkControl`，不受影響。數據庫結構的升級由遷移腳本在服務啟動時自動完成。

| 產物 | 說明 |
|---|---|
| `*-setup.exe` | Inno Setup 6 安裝程序（需要本機安裝 Inno Setup，發布腳本自動查找 `ISCC.exe`，找不到時跳過）：安裝嚮導、「應用和功能」中的卸載入口、原地升級；卸載時詢問是否刪除規則和日誌 |
| `*.zip` | 同樣的內容 + `install.cmd` / `uninstall.cmd` / `export-diagnostics.cmd`（自動請求管理員權限），沒有安裝程序時使用 |

發布要求和注意事項：

- **Native AOT**：目標機器不需要 .NET；需要 Visual Studio 的「使用 C++ 的桌面開發」工作負載（MSVC 鏈接器）。發布腳本會把 VS 安裝程序目錄加入 PATH（AOT 鏈接步驟通過 `vswhere` 定位 MSVC）。
- **運行時版本固定**：AOT 會把運行時靜態鏈接進可執行文件，所以 `Directory.Build.props` 在 `PublishAot=true` 時把 `RuntimeFrameworkVersion` 固定為已修復安全漏洞的版本（目前 10.0.15），不依賴本機 SDK 自帶的版本。出現新的運行時安全更新時同步修改。
- **AOT 警告白名單**：Serilog、TraceEvent 的彙總警告已評估為可接受（原因見 `NetworkControl.Service.csproj`）；其他程序集出現 AOT / 裁剪警告時發布腳本直接失敗，需要先評估。
- **不包含模擬引擎**：Release 構建不引用 `Engine.Fake`、不映射模擬器接口；在沒有管理員權限的機器上驗證 AOT 版本時，用 `-p:IncludeFakeEngine=true` 另外發布一份。
- **安裝後立即啟動界面**：「詢問」模式下沒有界面就沒人回應彈窗，未知連接會按默認動作被拒絕，所以安裝程序和 `install.cmd` 都會以安裝者的普通權限啟動界面並設置登錄時啟動。
- 腳本編碼：`.ps1` 以 **UTF-8 BOM** 保存（Windows PowerShell 5.1 讀取無 BOM 文件時按系統代碼頁解析，中文會亂碼甚至破壞語法），並且不使用 PowerShell 7 專有語法；`.cmd` 只用 ASCII。
- 安裝程序沒有代碼簽名：用戶會看到 SmartScreen 提示（README.txt 中有說明）。

## 9. 診斷包

用於收集用戶機器上的問題。入口：界面「設定 → 診斷」、服務不可用頁上的「匯出診斷包」、命令行 `NetworkControl.exe --export-diagnostics [路徑] [--include-activity] [--no-configuration]`（不啟動界面，界面本身無法啟動時使用；發布包和安裝目錄中的 `export-diagnostics.cmd` 調用它，保存到桌面）。

| 目錄 | 內容 | 來源 |
|---|---|---|
| `app/` | 界面概況（版本、AOT、系統、連接狀態和最近的失敗原因、相關進程）、界面日誌 | 界面 |
| `service/status/` | 服務概況（版本、權限、引擎狀態和故障、模式、設置）、生效的配置（只含本程序的配置節點）、可選：內存中的有效規則集 | 服務 `Diagnostics.Export` |
| `service/logs/` | 最近 6 個服務日誌文件（每個最多保留末尾 8 MB） | 服務 |
| `service/data/` | 數據庫大小、版本、完整性檢查；可選：`config.db` 一致性副本、最近 7 天的連接日誌（TSV） | 存儲 |
| `service/engine/` | WFP：會話 / 事件訂閱 / DNS 監聽狀態、已下發的過濾器清單、跳過的規則、下發失敗記錄 | 引擎 |
| `service-offline/` | 服務不可達時，界面直接讀取的服務日誌（`%ProgramData%\NetworkControl\logs` 默認普通用戶可讀；開發佈局讀 `.devdata/logs`） | 界面 |
| `windows/` | `sc queryex/qc/qfailure`、事件日誌中最近 7 天與本程序相關的崩潰和服務控制管理器記錄 | 界面 |
| `manifest.txt` | 條目清單、收集過程中的說明和錯誤（任何部分失敗都不會中斷導出） | 兩邊各一份 |

- **隱私**：所有文本經 `DiagnosticsRedactor` 處理（用戶目錄中的用戶名、計算機名替換為 `<user>` / `<machine>`）；規則和連接日誌由用戶勾選（規則默認勾選，連接日誌默認不勾選）；不會自動上傳。
- **擴展**：新模塊實現 `Core.Diagnostics.IDiagnosticsContributor`（寫入自己的目錄）並用 `services.AddDiagnosticsContributor<T>()` 註冊；已註冊的單例（例如引擎）用工廠重載，不會創建第二個實例。
- 驗證時用 `NETWORKCONTROL_IPC=pipe:<其他名字>` 讓臨時服務和客戶端使用獨立的管道：本機可能裝著正式版服務（`pipe:NetworkControl.v1`），不要對它調用 `Shutdown`。

---

## 10. 可攜版（綠色版）

發布包（zip）同時是免安裝版：解壓到任意可寫入的資料夾後執行 `start.cmd`（或 `App\NetworkControl.exe`）即可。

| 項目 | 行為 |
|---|---|
| 識別方式 | 根目錄的 `portable.txt` 標識文件；安裝版的 `App\`、`Service\` 目錄結構與可攜版完全相同，**不能按目錄結構判斷**。安裝程序不會複製 `portable.txt`。 |
| 服務啟動 | 界面點「啟動服務」或首次啟動時，直接拉起同目錄的 `Service\NetworkControl.Service.exe`；Windows 上通過 UAC 提權（WFP 需要管理員權限）。 |
| 數據目錄 | `<發布包根目錄>\data`：`config.db`、`activity.db`、`logs\`、`ui-preferences.json`。界面會先以普通權限創建目錄，保證日誌可寫。 |
| IPC 端點 | `pipe:NetworkControl.portable.v1`（Unix 為 `<data>/ui.sock`），與安裝版的 `pipe:NetworkControl.v1` 隔離，避免綠色版連上或關閉已安裝的系統服務。 |
| 界面生命週期 | 退出界面（托盤「結束界面」）時會請求可攜版服務退出；「完全結束」也會停止服務。界面異常退出時可攜版服務可能殘留，必要時在任務管理器結束 `NetworkControl.Service.exe`。 |
| 登錄自啟 | 可攜版不提供（文件夾可能被移動，且會與安裝版共用 Run 鍵）。 |
| 單實例 | 可攜版使用獨立的互斥體，可與安裝版界面同時運行，但**不要同時運行兩個服務**：兩者都會下發 WFP 過濾器。 |
| 安裝 | `install.cmd` / `install-service.ps1` 仍然可用：它會把 `App\`、`Service\` 複製到安裝目錄並註冊系統服務，不會複製 `portable.txt`，因此安裝後回到原來的系統服務模式。 |

驗證可攜版佈局（不需要管理員權限）：用 Debug 構建把 `App`、`Service` 的輸出複製到臨時目錄，創建 `portable.txt`，以 `--NetworkControl:Service:Engine=Fake` 啟動服務，再用 `NetworkControl.exe --export-diagnostics <路徑>` 檢查連接端點和 `data` 目錄。

## 11. Android

設計見 [android.md](android.md)。Android 項目（`Engine.Android`、`App.Android`）需要 .NET Android 工作負載，因此放在獨立的 `NetworkControl.Android.slnx` 中；主解決方案只包含平台無關的 `Engine.Vpn` 及其測試，日常的 `dotnet build / test / format NetworkControl.slnx` 不受影響。

### 11.1 工具鏈（不需要管理員權限）

系統的 dotnet 在 `Program Files`，安裝工作負載需要管理員權限，因此使用一套用戶級工具鏈（默認 `D:\tools\android-dev`，可用環境變量 `NC_ANDROID_TOOLCHAIN` 覆蓋）：

| 目錄 | 內容 | 安裝方法 |
|---|---|---|
| `dotnet` | .NET SDK 10.0.402 + `android` 工作負載 | `dotnet-install.ps1 -Version 10.0.402 -InstallDir <目錄>\dotnet`，然後 `<目錄>\dotnet\dotnet.exe workload install android` |
| `jdk` | Microsoft OpenJDK 17 | 解壓 `https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip` |
| `android-sdk` | `cmdline-tools/latest`、`platform-tools`、`platforms;android-36`、`build-tools;36.0.0` | 解壓 cmdline-tools 後用 `sdkmanager --sdk_root=<目錄>\android-sdk` 安裝（在 PowerShell 中包名要加引號：`'"platforms;android-36"'`，否則 `;` 會被 cmd 拆開） |

開發機上的系統代理（Reqable）會讓工作負載和 NuGet 下載的 TLS 握手失敗；在當前終端臨時繞過即可（不要修改系統設置，也不要提交）：

```powershell
$env:HTTPS_PROXY='http://127.0.0.1:9'; $env:HTTP_PROXY='http://127.0.0.1:9'; $env:NO_PROXY='*'   # 只影響當前終端
```

`dotnet build -t:InstallAndroidDependencies` 不支持 `NO_PROXY=*`（會嘗試連接這個不存在的代理），不要用它安裝 SDK，按上表手動安裝。

### 11.2 構建與運行

```powershell
.\scripts\android.ps1 build      # Debug APK
.\scripts\android.ps1 run        # 安裝到已連接的設備並啟動（adb）
.\scripts\android.ps1 logcat     # 應用日誌（標籤 NetworkControl）
.\scripts\android.ps1 publish    # Release APK → artifacts\android（未設置 NC_ANDROID_KEYSTORE 時使用調試簽名）
```

- 設備上的數據：`/data/data/com.networkcontrol.android/files/{data,logs}`，Debug 構建可用 `adb shell run-as com.networkcontrol.android ls files` 查看。
- 跳過 VPN 授權對話框（測試用）：`adb shell appops set com.networkcontrol.android ACTIVATE_VPN allow`。
- 界面文本：編輯 `tools/android-strings.py` 中的表格後運行 `python -X utf8 tools/android-strings.py`，不要手工修改生成的 `Localization/**`。通知等界面之外顯示的文本在 `Resources/values*/strings.xml`（三種語言）。

### 11.3 模擬器

開發機（Windows 10 19045）上，模擬器 37.x 在 WHPX 下無法啟動虛擬機（`-gpu swiftshader_indirect` 時宿主進程訪問衝突崩潰或客戶機卡住不輸出內核日誌，`-gpu guest` 時靜默退出）。處理過程和結論見 progress.md 的「Android MVP」一節。真機驗證（USB 調試）最可靠。
