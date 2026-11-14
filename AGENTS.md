# NetworkControl — AI Agent 工作指南

> 本文件是所有 AI 編碼助手（Claude Code、Codex、Cursor、Copilot 等）共用的項目知識庫，也是唯一的事實來源。
> 各工具的專用文件（如 `CLAUDE.md`）只引用本文件並補充工具相關內容，不要在其他地方複製本文件的內容。

跨平台按應用聯網控制工具（類似 Little Snitch / OpenSnitch）。技術棧：.NET 10、Avalonia 12、AtomUI（Ant Design）、gRPC、SQLite。

## 1. 開始工作前（必讀）

1. **先讀 [docs/progress.md](docs/progress.md)**：當前階段、阻塞項、待辦、可分派任務、待驗證事項都在那裡。Android 版的任務看 [docs/progress-android.md](docs/progress-android.md)。
2. 需要背景時再讀：
   - [docs/requirements.md](docs/requirements.md)：需求，編號為 FR-xxx / NFR-xxx。
   - [docs/design.md](docs/design.md)：設計；§22 記錄了實現與原設計的差異。
   - [docs/development.md](docs/development.md)：擴展點、本地運行方法、WFP 驗證方法。
3. 按任務範圍只讀相關代碼，不要通讀整個倉庫。第 4 節的項目結構可以幫你定位。

## 2. 協作規則（多 agent 並行）

- **認領任務**：在 `docs/progress.md` 中把任務的 `[ ]` 改為 `[~]`，並註明 agent 名稱和日期，例如 `[~] (codex, 2026-11-13)`。已被認領的任務不要碰。
- **分支**：在 `agent/<agent名>/<任務簡稱>` 分支上工作，例如 `agent/codex/backup-config-db`。不要直接提交到 `main`。
- **範圍**：只修改任務說明中列出的文件或目錄。需要越界時，先在 `docs/progress.md` 的任務下記錄原因，或者交還給用戶決定。
- **完成標準**（全部滿足才算完成）：
  1. `dotnet build NetworkControl.slnx` 零錯誤零警告（倉庫開啟了警告即錯誤）。
  2. `dotnet test --solution NetworkControl.slnx` 全部通過，新功能需附帶測試。
  3. `dotnet format NetworkControl.slnx --verify-no-changes --exclude src/NetworkControl.App.Android src/NetworkControl.Engine.Android` 通過（Android 項目沒有工作負載時無法分析，必須排除；它們的格式由 `NetworkControl.Android.slnx` 檢查）。
  4. **改動了 Core 或 Storage**（Android 版共用它們）：本機有 Android 工具鏈時，再構建一次 `NetworkControl.Android.slnx`（見 docs/development.md §11）；沒有工具鏈時在進度文件中註明「未驗證 Android 構建」，CI 的 Android 任務會兜底。盡量以追加的方式修改公開接口（新增方法、可選參數），不要直接改已有的簽名。
  5. 更新 `docs/progress.md`（Android 任務更新 `docs/progress-android.md`）：任務標為 `[x]`，寫明完成內容；有沒驗證到的部分要如實寫明。設計有變化時同步更新 `docs/design.md` §22；發現新的坑就補充到本文件第 6 節。
  6. **完成項落盤**：在 `docs/kotlin-feature-log.md`（Kotlin 版）或對應的功能記錄文件中追加一行，註明功能、日期、提交、驗證方式和未驗證項。沒有落盤的功能不能算完成。
- **提交信息**：使用 Conventional Commits 格式，例如 `feat(storage): daily backup of config.db`，正文可以用中文。
- **禁止事項**：
  - 不要修改已發布的數據庫遷移腳本，也不要修改 proto 中已有的字段編號。
  - 不要給 IPC 客戶端開啟系統代理。
  - 不要刪除 `App.axaml` 中的 `<FluentTheme />`。
  - 不要截屏：這是用戶的日常工作電腦。
  - 不要執行需要管理員權限的操作：WFP 真實攔截的驗證、安裝程序的驗證由用戶本人執行。

## 3. 用戶偏好

- 用戶用中文交流（繁體或簡體），回覆跟隨用戶當前使用的字體；代碼註釋和文檔使用繁體中文。
- 追求最佳實踐和可擴展性。完成後要實際構建、測試、運行驗證，並如實報告沒有驗證到的部分。
- 界面效果請用戶自己看；運行時驗證通過 gRPC 調用、日誌、進程狀態完成。

## 4. 項目結構

| 項目 | 說明 |
|---|---|
| `src/NetworkControl.Core` | 平台無關核心：Domain、Rules、Pipeline（IVerdictStage 階段鏈）、Prompts、Engine 抽象。不依賴其他項目 |
| `src/NetworkControl.Storage` | SQLite；遷移腳本在 `Migrations/<Config\|Activity>/NNNN_*.sql`（只追加，不修改已有腳本） |
| `src/NetworkControl.Ipc` | `Protos/*.proto`（只新增字段）；`IpcEndpoint`、`IpcChannelFactory`；`Diagnostics/`（診斷包寫入與脫敏，界面和服務共用） |
| `src/NetworkControl.Service` | 服務宿主；`Engines/EngineRegistration.cs` 按配置選擇引擎（Auto / Fake / Wfp，Fake 只在 Debug 構建中）；`Diagnostics/`（日誌配置、診斷包） |
| `src/NetworkControl.Engine.Fake` | 模擬引擎（開發和測試用） |
| `src/NetworkControl.Engine.Windows` | WFP 引擎：`Interop/`（P/Invoke）、`Compilation/`（RuleCompiler，純函數）、`WfpEngine.cs` |
| `src/NetworkControl.App` | AtomUI 界面；只依賴 Ipc，不引用 Core |
| `tests/*` | Core（含場景測試 ScenarioHost）、Storage、Ipc（診斷包）、Engine.Windows |
| `scripts/` | `publish-release.ps1`（AOT 發布包 + 安裝程序）、安裝 / 卸載腳本（需要管理員權限）、`package/`（發布包中的 cmd 和 README） |
| `installer/` | Inno Setup 腳本（`AppId` 不能修改） |

## 5. 常用命令

```bash
dotnet build NetworkControl.slnx
dotnet test --solution NetworkControl.slnx            # Microsoft.Testing.Platform 語法，不是 dotnet test xxx.sln
dotnet format NetworkControl.slnx --verify-no-changes --exclude src/NetworkControl.App.Android src/NetworkControl.Engine.Android
dotnet run --project src/NetworkControl.App           # 調試構建會自動拉起開發服務（模擬引擎）
.\scripts\publish-release.ps1                          # 發布包（Native AOT）+ 安裝程序，見 docs/development.md §8
```

- 開發數據放在 `.devdata/`（數據庫、`logs/service-*.log`），刪除即可重置。界面日誌在 `%LocalAppData%\NetworkControl\logs`。
- **版本號只改 `Directory.Build.props` 的 `VersionPrefix`**，發版流程見 [docs/development.md §8](docs/development.md#8-發布版本與安裝包)。
- 構建前先結束運行中的 `NetworkControl*` 進程，否則文件會被鎖住。
- 擴展點的速查表在 [docs/development.md §3](docs/development.md#3-擴展點速查)，涵蓋判決階段、規則條件、平台引擎、IPC 接口、界面頁面、界面文本。

## 6. 踩過的坑（不要重犯）

- **系統代理**：用戶機器運行 Reqable，本地 gRPC 必須設置 `SocketsHttpHandler.UseProxy = false`，否則 HTTP/2 協商失敗。
- **公網請求與本地 IPC 相反**：檢查更新等公網請求必須使用默認的系統代理（`HttpClient` 不要設 `UseProxy = false`），否則只能通過代理上網的用戶會失敗；只有本地 gRPC 才要關閉代理。
- **AtomUI 不為 Avalonia 基礎控件提供主題**：托盤菜單（MenuFlyoutPresenter / MenuItem）和普通 `Window` 依賴 `App.axaml` 中的 `<FluentTheme />`。
- **AtomUI 的 FollowSystem 主題**開啟後會覆蓋用戶手動選擇的明暗，所以跟隨系統由 `AppearanceService` 自己實現。
- **AtomUI 分段選擇器（Segmented）加載時會自動選中第一項並寫回綁定**：視圖模型必須忽略未連接時的變化；不要「清空後再同步」，否則會死循環。
- **AtomUI 界面文本**：新增鍵要在 Catalog 枚舉的**末尾**追加，並同時添加到 `en-US.xlf`、`zh-CN.xlf`、`zh-TW.xlf`，缺任何一個構建就會失敗。`[LanguageCatalog]` 需要 `using AtomUI.Localization;`。zh-TW 使用台灣用語。
- **WFP**：
  - `FWP_VALUE0` 中的 UINT64 字段是指針，不是內聯值。
  - `FwpmEngineSetOption0` 不能在動態會話中調用，要另開臨時的非動態句柄。
  - 結構體中嵌套的聯合必須保持嵌套，攤平會導致對齊錯誤（`FWPM_NET_EVENT_HEADER1` 曾因此少了 8 字節）。
  - 修改互操作結構體前，先查 SDK 頭文件 `C:\Program Files (x86)\Windows Kits\10\Include\10.0.26100.0\shared\fwpmtypes.h`，並同步更新 `InteropLayoutTests`。
- **類級別的 `unsafe` 會讓其中的 async 方法無法 await**：只在需要指針的方法上標 `unsafe`。
- **流式 gRPC 接口必須監聽 ApplicationStopping**（用 `StreamingCancellation.Link`），否則服務關閉要等 30 秒。
- **PowerShell 的 `-replace` 不區分大小寫**：批量替換時會誤傷同名的小寫變量，改用 `.Replace()`。
- **判決緩存鍵必須包含完整的 `AppIdentity`**，否則程序被替換後可以繞過篡改檢測。
- **Avalonia 12 的改名**：`SystemDecorations` 改為 `WindowDecorations`，`Watermark` 改為 `PlaceholderText`；DataGrid 的文本列需要聲明 `x:DataType`。
- **AtomUI DataGrid 的 `ItemsSource` 類型是 `IDataGridSource`**：直接綁定 `ObservableCollection` 能編譯通過，但運行時會靜默失敗，表格為空。必須用 `DataGridLocalSource.Create(collection, DataGridLocalSourceDescriptor.For<T>(row => DataGridRowKey.FromString(key)))` 包裝。排序和篩選需要在描述符中聲明字段（`.Field(...)`），目前沒有聲明，所以關閉了 `CanUserSortColumns`。
- **AtomUI 的 `Empty` 控件**與 protobuf 的 `Empty` 同名：同時引用 `AtomUI.Desktop.Controls` 時，protobuf 的要寫完整類型名。
- **模擬器（Simulator）gRPC 服務只在 Fake 引擎下映射**：其他引擎下調用會得到 `Unimplemented`，界面必須按當前引擎決定是否顯示模擬器。
- **Avalonia Android 的根 `VisualLayerManager` 默認不建立 `OverlayLayer`**：AtomUI 的 `ComboBox` / `PopupConfirm` / 任何 `Popup` 一打開就拋 `InvalidOperationException: Unable to create IPopupImpl and no overlay layer is found`，進程直接閃退。在 `App.axaml.cs` 的 `CreateMainView` 之後用 `Dispatcher.UIThread.Post` 找到 `TopLevel` 下的 `VisualLayerManager` 並設 `EnableOverlayLayer = true`；不要在頁面裡自己套一層 `VisualLayerManager`，會破壞 `ScrollViewer` 的滾動範圍。
- **`FwpmGetAppIdFromFileName0` 需要打開文件**：終端安全或防泄密軟件（例如用戶機器上的 tipray）會在驅動層拒絕訪問自身文件，API 返回「找不到文件」。現在的兜底順序是：先用阻斷事件中觀測到的原始應用 ID，再按「小寫 NT 設備路徑 + NUL，UTF-16LE 編碼」自行構造（`AppIdTests` 驗證過與 API 輸出逐字節一致）。
- **同一實例在 DI 中註冊為兩種類型時，`DisposeAsync` 會被調用兩次**：引擎的停止和釋放必須可以重複調用。
- **Native AOT**：
  - 不要用 `Serilog.Settings.Configuration`（`ReadFrom.Configuration`）：它反射加載程序集，AOT 下服務**啟動即崩潰**。日誌在 `ServiceLogging` 中用代碼配置。
  - AOT 會把運行時鏈接進可執行文件：發布用的運行時版本由 `Directory.Build.props` 固定（本機 SDK 10.0.402 自帶的 10.0.13 有 Brotli 漏洞）。
  - 鏈接步驟需要 `vswhere` 在 PATH 中（VS 18 默認不在），否則報 MSB3073；`publish-release.ps1` 已處理。
  - 第三方程序集的 AOT 警告只接受白名單中的（Serilog、TraceEvent），新出現的要先評估。
- **驗證時不要碰正式安裝的服務**：用戶機器上可能裝著正式版（`pipe:NetworkControl.v1`）。臨時服務和探針一律設置 `NETWORKCONTROL_IPC=pipe:<其他名字>`，不要對默認管道調用 `Shutdown`。
- **Windows 腳本編碼**：`.ps1` 用 UTF-8 BOM 保存（Windows PowerShell 5.1 按系統代碼頁讀取無 BOM 文件，中文亂碼甚至破壞語法），且不用 PowerShell 7 專有語法；`.cmd` 只用 ASCII。
- **讀取系統命令輸出的編碼**：`sc.exe` 跟隨控制台代碼頁（`chcp 65001` 後輸出 UTF-8）；`wevtutil` 不跟隨，總是按系統 ANSI 代碼頁（中文系統 GBK）輸出，要按 `CultureInfo.InstalledUICulture.TextInfo.ANSICodePage` 解碼（需註冊 `CodePagesEncodingProvider`）。通過 `cmd /c` 傳帶引號的參數時用 `/s` 並手工構造命令行，`ArgumentList` 的轉義規則與 cmd 不一致。
- **NuGet 漏洞審計（NU1900）**：Reqable 系統代理會讓 nuget.org 的 TLS 握手失敗，審計警告在「警告即錯誤」下變成構建錯誤。本地臨時用環境變量 `NuGetAudit=false` 繞過，不要提交這個設置。
- **`BackgroundService` 中不要釋放 `ExecuteAsync` 仍在使用的對象**：主機啟動失敗時 `Dispose` 會在 `ExecuteAsync` 的取消分支執行期間被調用（`ActivityLogWriter` 的信號量曾因此拋出 ObjectDisposedException）。
- **PowerShell 中的 `[IO.File]` 相對路徑**按進程當前目錄解析，不是 `Set-Location` 的位置：一律用絕對路徑。
- **proto 生成類型避開常見類型名**：已有 `VerdictAction`、`IpEndpoint`、`ActivityLog`；C# 中的別名統一用 `Proto`（全局命名空間裡已經有 `Pb`）。
- **System.Text.Json 源生成會覆蓋 init 屬性的初始值**：`public bool Enabled { get; init; } = true;` 在 JSON 缺少該字段時反序列化為 `false`。需要「缺省為 true」時用可空類型並在映射時 `?? true`。
- **開發機的透明加密（tipray）**：倉庫中已有的文件對部分進程是密文——Claude Code 的 Read / Edit 工具讀到亂碼並報「NUL bytes」，而 git、dotnet、Git Bash、Python 讀到的是明文。遇到這種情況用 Python（`-X utf8`，保留 BOM 和換行）或 Git Bash 修改；新建文件不受影響。
- **可攜版與安裝版目錄結構相同**：可攜模式只能靠根目錄的 `portable.txt` 判斷，不能靠 `App\` / `Service\` 目錄；安裝程序不得複製這個文件。可攜版使用獨立管道 `NetworkControl.portable.v1` 和 `<根目錄>\data`，不要和安裝版同時運行服務。
- **Android 版**（設計見 docs/android.md，構建見 docs/development.md §11）：
  - Android 項目需要 .NET Android 工作負載。主解決方案 `NetworkControl.slnx` 中的 Android 項目（`/android/` 文件夾）只用於 IDE 瀏覽，標記為不參與解決方案構建（`<Build Project="false" />`），新增 Android 項目時也要這樣標記；`dotnet format` 主解決方案時要用 `--exclude` 排除它們，否則在沒有工作負載的機器上會失敗。Android 的構建、格式檢查用 `NetworkControl.Android.slnx`（或 `scripts/android.ps1`）。IDE 要能編譯和分析這些項目，IDE 使用的 dotnet 必須裝有 android 工作負載。
  - 開發機用的是 `D:\tools\android-dev` 下的用戶級工具鏈；下載工作負載 / NuGet 時用 `HTTPS_PROXY=http://127.0.0.1:9` + `NO_PROXY=*` 臨時繞過 Reqable（只對當前終端）。`-t:InstallAndroidDependencies` 不支持這種繞過，SDK 要手動安裝。
  - 模擬器 37.x 在本機 Windows 10 的 WHPX 下無法啟動（宿主崩潰或客戶機卡住），用 `D:\tools\android-dev\emu34\emulator\emulator.exe`（33.1.24）；adb 只用 SDK 自帶的版本，`D:\tools\platform-tools` 的舊 adb 會讓設備一直顯示 offline。
  - 黑洞式 VPN 必須宣告 DNS 服務器並用假 DNS 回答，否則被阻斷的應用在本機解析失敗、根本不發起連接，引擎無法按 UID 歸屬，也就不會詢問。
  - `getConnectionOwnerUid` 是 Binder 調用，不能在主線程上執行；引擎的後台循環要用 `Task.Run` 啟動，不能繼承調用方（界面線程）的同步上下文。
  - 系統核心 UID（< 10000，例如 DNS 解析服務 1051）沒有軟件包、無法按包排除在 VPN 之外：只計數，不上報、不詢問。
  - Android 的隱式 using 包含 `Android.App`：`Application` 與 Avalonia 的同名，Avalonia 的要寫 `Avalonia.Application`；項目命名空間是 `NetworkControl.Android`，引用 Android SDK 類型一律用頂層 `using global::Android...`。
  - AtomUI 在 Android 上：`Segmented` 不響應觸控（由 `TouchAdapters` 補上）；默認字體沒有中文字形且不回退，必須 `atom.WithDefaultFontFamily("sans-serif")`；ListBox 等基礎控件需要 `FluentTheme`。
  - Avalonia 12.1 在 Android 上已經把內容限制在系統欄內，`InsetsManager.SafeAreaPadding` 還是以物理像素報告：不要再手動按它留邊。
  - 通知文本在 `Resources/values*/strings.xml`（沒有界面時也要可用），界面文本由 `tools/android-strings.py` 生成 XLIFF；兩處都要維護三種語言。
