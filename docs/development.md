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
| 服務日誌 | 控制台 + `.devdata/logs/service-*.log` |
| IPC 端點 | Windows：`pipe:NetworkControl.v1`；Linux / macOS 以普通用戶運行時：`$XDG_RUNTIME_DIR/netcontrol/ui.sock`（或 `/tmp/netcontrol-<user>/ui.sock`） |
| 引擎 | `Fake`（模擬引擎，不攔截真實流量） |

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
- **AOT 友好**：不使用反射式序列化（JSON 用源生成器，配置綁定用源生成器）、不使用反射式視圖定位；`Core`、`Storage`、`Ipc`、`Engine.Fake` 標記了 `IsAotCompatible`。
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
| `NetworkControl.Storage.Tests` | 在臨時目錄中對真實 SQLite 做遷移、倉儲往返、日誌聚合等測試 |

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