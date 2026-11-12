# NetworkControl — AI Agent 工作指南

> 本文件是所有 AI 編碼助手（Claude Code、Codex、Cursor、Copilot 等）共用的項目知識庫，也是唯一的事實來源。
> 各工具的專用文件（如 `CLAUDE.md`）只引用本文件並補充工具相關內容，不要在其他地方複製本文件的內容。

跨平台按應用聯網控制工具（類似 Little Snitch / OpenSnitch）。技術棧：.NET 10、Avalonia 12、AtomUI（Ant Design）、gRPC、SQLite。

## 1. 開始工作前（必讀）

1. **先讀 [docs/progress.md](docs/progress.md)**：當前階段、阻塞項、待辦、可分派任務、待驗證事項都在那裡。
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
  3. `dotnet format NetworkControl.slnx --verify-no-changes` 通過。
  4. 更新 `docs/progress.md`：任務標為 `[x]`，寫明完成內容；有沒驗證到的部分要如實寫明。設計有變化時同步更新 `docs/design.md` §22；發現新的坑就補充到本文件第 6 節。
- **提交信息**：使用 Conventional Commits 格式，例如 `feat(storage): daily backup of config.db`，正文可以用中文。
- **禁止事項**：
  - 不要修改已發布的數據庫遷移腳本，也不要修改 proto 中已有的字段編號。
  - 不要給 IPC 客戶端開啟系統代理。
  - 不要刪除 `App.axaml` 中的 `<FluentTheme />`。
  - 不要截屏：這是用戶的日常工作電腦。
  - 不要執行需要管理員權限的操作：WFP 真實攔截的驗證由用戶本人執行。

## 3. 用戶偏好

- 用戶用中文交流（繁體或簡體），回覆跟隨用戶當前使用的字體；代碼註釋和文檔使用繁體中文。
- 追求最佳實踐和可擴展性。完成後要實際構建、測試、運行驗證，並如實報告沒有驗證到的部分。
- 界面效果請用戶自己看；運行時驗證通過 gRPC 調用、日誌、進程狀態完成。

## 4. 項目結構

| 項目 | 說明 |
|---|---|
| `src/NetworkControl.Core` | 平台無關核心：Domain、Rules、Pipeline（IVerdictStage 階段鏈）、Prompts、Engine 抽象。不依賴其他項目 |
| `src/NetworkControl.Storage` | SQLite；遷移腳本在 `Migrations/<Config\|Activity>/NNNN_*.sql`（只追加，不修改已有腳本） |
| `src/NetworkControl.Ipc` | `Protos/*.proto`（只新增字段）；`IpcEndpoint`、`IpcChannelFactory` |
| `src/NetworkControl.Service` | 服務宿主；`Engines/EngineRegistration.cs` 按配置選擇引擎（Auto / Fake / Wfp） |
| `src/NetworkControl.Engine.Fake` | 模擬引擎（開發和測試用） |
| `src/NetworkControl.Engine.Windows` | WFP 引擎：`Interop/`（P/Invoke）、`Compilation/`（RuleCompiler，純函數）、`WfpEngine.cs` |
| `src/NetworkControl.App` | AtomUI 界面；只依賴 Ipc，不引用 Core |
| `tests/*` | Core（含場景測試 ScenarioHost）、Storage、Engine.Windows |
| `scripts/` | Windows 服務的安裝和卸載腳本（需要管理員權限） |

## 5. 常用命令

```bash
dotnet build NetworkControl.slnx
dotnet test --solution NetworkControl.slnx            # Microsoft.Testing.Platform 語法，不是 dotnet test xxx.sln
dotnet format NetworkControl.slnx --verify-no-changes
dotnet run --project src/NetworkControl.App           # 調試構建會自動拉起開發服務（模擬引擎）
```

- 開發數據放在 `.devdata/`（數據庫、`logs/service-*.log`），刪除即可重置。
- 構建前先結束運行中的 `NetworkControl*` 進程，否則文件會被鎖住。
- 擴展點的速查表在 [docs/development.md §3](docs/development.md#3-擴展點速查)，涵蓋判決階段、規則條件、平台引擎、IPC 接口、界面頁面、界面文本。

## 6. 踩過的坑（不要重犯）

- **系統代理**：用戶機器運行 Reqable，本地 gRPC 必須設置 `SocketsHttpHandler.UseProxy = false`，否則 HTTP/2 協商失敗。
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
- **`FwpmGetAppIdFromFileName0` 需要打開文件**：終端安全或防泄密軟件（例如用戶機器上的 tipray）會在驅動層拒絕訪問自身文件，API 返回「找不到文件」。現在的兜底順序是：先用阻斷事件中觀測到的原始應用 ID，再按「小寫 NT 設備路徑 + NUL，UTF-16LE 編碼」自行構造（`AppIdTests` 驗證過與 API 輸出逐字節一致）。
- **同一實例在 DI 中註冊為兩種類型時，`DisposeAsync` 會被調用兩次**：引擎的停止和釋放必須可以重複調用。
- **proto 生成類型避開常見類型名**：已有 `VerdictAction`、`IpEndpoint`、`ActivityLog`；C# 中的別名統一用 `Proto`（全局命名空間裡已經有 `Pb`）。
