# Android M3 批次交辦記錄（M3-05 / M3-06 / M3-09 / M3-10 / M3-11）

> 分支：`agent/deepseek/m3-android-batch`（從 `main` 建立，未合併、未 push）。
> 本文件代替 `docs/progress.md` / `docs/progress-android.md` 的更新（依派工指示，避免與其他 worktree 衝突）。
> 所有任務均已完成並在桌面構建 / 單元測試 / Android 構建層面驗證；**真機行為一律未驗證**（本機無設備，未啟動模擬器）。

## 提交一覽

| 任務 | 提交 | 說明 |
|---|---|---|
| M3-05 | `d58565a` | 阻斷快速失敗（TCP RST / ICMP 不可達） |
| M3-10 | `7265643` | 通知文本跟隨應用內語言 |
| M3-09 | `e934acc` | 快速設定磁貼 |
| M3-11 | `fc2f929` | 诊断包寫入 / 脫敏移到共享項目（純移動） |
| M3-11 | `f3902ed` | Android 診斷資訊導出 |
| M3-11 | `c6463c9` | 修正移動後的 using 排序（格式檢查） |
| M3-06 | `8b28be3` | Android 規則導入導出 |

## M3-05 阻斷快速失敗

**完成內容**
- `IpPacketBuilder` 追加 `TcpReset4` / `TcpReset6`（源 / 目的互換、seq 0、ack = 收到的 seq + 1、窗口 0、無選項，含 IPv4 / IPv6 偽首部校驗和）和 `IcmpUnreachable4` / `IcmpUnreachable6`（type 3 code 3 / type 1 code 4；IPv4 引用 IP 頭 + 8 字節，IPv6 總長不超過 1280）。
- `BlockedFlowReporter` 在 `OnPacket` 中、入隊之前對**明確拒絕**（`AppRoutingDecision.Block`：規則拒絕、白名單未允許、全部封鎖）回 RST / ICMP；`BlockAndAsk`（等待詢問）、未知 UID、系統 UID 保持黑洞。
- 每個（UID, 遠端 IPv4/IPv6 地址）每秒最多回應一次（不區分端口，避免同一目標的重試風暴）。
- `VpnEngineCounters` 追加 `ResetsSent` / `UnreachablesSent`；`VpnEngineOptions.FastFail` 默認 true。
- `PacketInfo` 追加可選的 `TcpSequence`（只追加，不影響現有調用方）。

**測試**
- `FastFailPacketTests`：RST v4/v6 的端點、標誌、ack、窗口、IP / TCP 校驗和；ICMP v4 引用長度與校驗和；ICMPv6 1280 上限與校驗和。
- `FastFailReporterTests`：規則拒絕 / 白名單 / 全部封鎖回 RST；等待詢問不回應；每秒限速；UDP 回 ICMP；IPv6；關閉開關；未知 / 系統 UID 不回應。

**未驗證 / 限制**
- **待真機驗證**：Chrome 打開被拒絕的網站應立刻顯示錯誤頁而不是一直轉圈。
- 快速失敗的 UID 查詢在 VPN 讀線程上執行（`getConnectionOwnerUid`，不在主線程），每次新嘗試多一次 Binder 調用；真機上需觀察大量連線時的讀取延遲。
- Android 會把收到的 RST 當作對端拒絕；如果應用對 RST 的處理是「不重試」，用戶允許後需要應用自己重新發起（設計已接受，等待詢問時不回應就是為了避免這一點）。

## M3-10 通知文本跟隨應用內語言

**完成內容**
- 新增 `Infrastructure/NotificationText`：直接解析 `tools/android-strings.py` 生成的 XLIFF（以 `EmbeddedResource` 打入程序集），與 AtomUI Catalog 同源，但**不依賴 Avalonia 初始化**；語言取 `UiPreferences`（未設置時跟隨系統），找不到鍵時退回 en-US。
- `PromptNotifier`、`VpnForegroundNotifier`、`NotificationChannels` 全部改用 Catalog 文本；`strings.xml` 只保留 `app_name` 和磁貼的 `tile_label`（系統在進程外讀取）。
- `LocalizationService.SetPreference` / `AndroidApp` 在 Android 13+ 調用 `LocaleManager.ApplicationLocales`（`AndroidApplicationLocales`），API 29–32 跳過。
- 語言切換後：`Backend.RefreshNotifications()` 重新建立通知頻道（更新名稱 / 說明）、刷新前台通知、重繪已顯示的詢問通知。
- 新增文本鍵（追加在 Catalog 末尾）：`Common.UnknownApp`、`Prompt.NotificationMultiple`、`Prompt.Channel*`。

**測試 / 驗證**
- Android Debug 構建通過；確認 APK 中存在 18 個 `NetworkControl.Android.Localization.*.xlf` 嵌入資源；用 Python 核對三個語言的 XLIFF 鍵值和 XML 格式。
- `dotnet format NetworkControl.Android.slnx --verify-no-changes` 通過。

**未驗證 / 限制**
- **待真機驗證**：系統英文、應用內繁中 → 詢問通知繁中；切換簡中後新通知簡中；通知頻道名稱即時更新（部分 ROM 的通知設定頁有快取，可能需要重開設定頁）。
- API 29–32 沒有「應用程式語言」機制，系統設定中不會顯示本應用的語言；進程內通知仍跟隨應用內語言。
- 開機啟動時後端只使用 `NotificationText`，不建立 `LocalizationService`；`LocalizationService` 本身仍需要 AtomUI / Avalonia 初始化（只在界面建立時使用）。

## M3-09 快速設定磁貼

**完成內容**
- 新增 `Platform/ProtectionTileService`（`TileService`，`BIND_QUICK_SETTINGS_TILE`，導出，`QS_TILE` IntentFilter，`TOGGLEABLE_TILE` metadata）。
- `OnStartListening` 顯示保護狀態（Active / Inactive）和副標題（模式、需要授權、被系統停止、故障等），核心未啟動時顯示關閉並在背景啟動後用 `RequestListeningState` 刷新。
- `OnClick`：保護已開 / 正在開啟 / 需要授權 → `SetProtection(false)`；未授權 VPN → 打開應用由界面引導（不在磁貼中彈系統對話框）；已授權 → `SetProtection(true)`。
- API 34+ 使用 `PendingIntent` 版的 `StartActivityAndCollapse`，舊版本用 `Intent` 重載並抑制過時警告。
- `Backend.Changed` → `RequestListeningState`，以 `Stopwatch` 節流到每秒一次；`OnStopListening` 退訂。
- `MainActivity` 追加 `android.service.quicksettings.action.QS_TILE_PREFERENCES` IntentFilter（長按打開應用）；磁貼標籤 `tile_label` 已加入三語 `strings.xml`。

**測試 / 驗證**
- Android Debug 構建通過；已核對生成的 `AndroidManifest.xml` 包含 service、權限、metadata、IntentFilter 和 MainActivity 的長按 filter。
- Android 格式檢查通過。

**未驗證 / 限制**
- **待真機驗證**：添加磁貼、點按開關、狀態與應用內一致、首次未授權時點按打開應用、長按打開應用。
- 「需要授權」時磁貼顯示為 Active（表示保護意圖已開啟），點按會關閉保護意圖；這是刻意的取捨，需真機確認是否符合直覺。
- 部分 ROM 的快速設定面板會自行緩存圖標 / 副標題，刷新可能有延遲。

## M3-11 診斷資訊導出

**完成內容**
- **工程前置（純移動）**：`DiagnosticsArchive` / `DiagnosticsRedactor` 從 `NetworkControl.Ipc` 移到新項目 `NetworkControl.Diagnostics`（不依賴 gRPC / Avalonia / Core），桌面界面、服務、Android 共同引用；`Ipc.Tests` 改為引用新項目。桌面行為不變。
- `DiagnosticsRedactor` 追加可選參數 `redactAddresses`（默認 false，桌面行為不變）；Android 導出時設為 true，把 IPv4 / IPv6 替換為 `<ip>`（IPv6 候選串再用 `IPAddress.TryParse` 驗證，避免誤傷時間）。
- 新增 `Platform/DiagnosticsExporter`：在快取目錄生成 zip，內含
  - `summary.txt`：版本、Android 版本、設備型號 / 代號、ABI、保護狀態、模式、當前配置文件、VPN 授權、通知授權、使用情況授權、引擎計數器（含 RST / ICMP）、路由計劃摘要（允許 / 拒絕 / 詢問應用數、跳過規則、待決詢問）；
  - `logs/`：`files/logs` 下最近 3 個日誌（經脫敏和大小上限）；
  - `rules-stats.txt`：按動作 / 來源 / 時效統計、Android 不生效原因統計；默認不含包名，勾選「包含應用程式清單」後才列出包名；
  - `traffic-access.txt`：使用情況存取權狀態。
- 設定頁「關於」卡片新增說明、勾選框和「導出診斷資訊」按鈕；結果 / 取消 / 錯誤以本地化文本顯示。
- `MainActivity` 新增通用的 SAF 封裝 `RequestCreateDocumentAsync` / `RequestOpenDocumentAsync`（M3-06 共用）。

**測試 / 驗證**
- `DiagnosticsRedactorTests.Addresses_are_redacted_only_when_enabled`：默認不脫敏、開啟後 IPv4 / IPv6 都替換、時間 `17:12:34` 不被誤傷。
- 主解決方案構建、測試通過；Android Debug 構建通過；Android 格式檢查通過。

**未驗證 / 限制**
- **待真機驗證**：導出的 zip 可打開、日誌中的 IP 已脫敏、SAF 保存流程、不同 ROM 的 `CacheDir` / `ContentResolver` 行為。
- 日誌文件由背景寫入器持續打開；`DiagnosticsArchive` 以 `FileShare.ReadWrite` 讀取，理論上可讀到正在寫入的當天日誌，真機需確認不會漏掉最後幾行。
- 未包含私人 DNS（Private DNS）狀態：目前沒有讀取 `LinkProperties` 的現成代碼，為避免引入未驗證的平台調用，本次未加入；如需要可在後續任務補上。
- 沒有把 zip 直接分享（`Intent.ActionSend` + `FileProvider`）；目前只做「保存」，與設計的推薦順序一致。

## M3-06 Android 規則導入導出

**完成內容**
- `Engine.Vpn/Routing/AndroidRuleCompatibility`（純函數）：判斷規則在 Android 上是否生效（不支持的連線條件、入站規則、非 `android-app://` 的桌面應用路徑），並提供 `CountIneffective`。
- `IBackend` 追加：
  - `ExportRulesAsync`：`RuleExportSerializer.Export(_ruleService.All, PathPlaceholderMapper.None)`；
  - `PreviewImport`：`RuleExportSerializer.Import` → 丟棄已過期限時規則 → 本機不存在的配置文件按現有服務行為降級為共享規則 → `RuleImportPlanner.Plan`；
  - `ApplyImportAsync`：**先新增、再更新、最後刪除**（與 `RulesGrpcService.ApplyAsync` 一致）。
- 設定頁新增「資料」卡片：導出 / 導入按鈕、狀態文本；導入經 SAF 打開 JSON，顯示應用內覆蓋層對話框，可選合併 / 覆寫並即時更新「新增 / 更新 / 刪除 / 不變」數量，以及「N 條規則在 Android 上不生效（仍會導入，以便再導出到桌面）」提示。
- 導出檔名 `networkcontrol-rules-yyyyMMdd.json`，UTF-8 無 BOM，MIME `application/json`。

**測試 / 驗證**
- `AndroidRuleCompatibilityTests`：Android 包名 / 萬用字元路徑 / 全局規則生效；桌面路徑、`*chrome*`、純包名不生效；端口 / 域名條件和入站規則的分類；混合計數。
- 主解決方案測試通過（`NetworkControl.Engine.Vpn.Tests` 76 個）；Android 構建和格式檢查通過。

**未驗證 / 限制**
- **待真機驗證**：手機導出 → 桌面導入預覽；桌面導出 → 手機導入並顯示不生效數量；SAF 在 Google 檔案 / 廠商文件管理器中的行為；大文件（>20 MB）會被拒絕。
- 「本機不存在的配置文件降級為共享規則」沿用當前 `main` 的服務行為。若 M3-03R（R8：跳過未知配置文件）先合併，Android 這段需要同步改為跳過並回報 `SkippedUnknownProfile`。
- 導入後的規則會由 `RuleService` 自動重新發佈，VPN 引擎據此重建分流，無額外處理。
- 導入 / 導出在 UI 線程發起，文件讀寫使用異步流；真機上大文件仍需觀察是否卡頓。

## 全體驗證結果

| 命令 | 結果 |
|---|---|
| `dotnet build NetworkControl.slnx` | 成功，0 警告 0 錯誤 |
| `dotnet test --solution NetworkControl.slnx` | 387 個測試，386 通過、1 跳過（`LinuxProcessInfoTests` 的符號鏈接，環境限制）、0 失敗 |
| `dotnet format NetworkControl.slnx --verify-no-changes --exclude src/NetworkControl.App.Android src/NetworkControl.Engine.Android` | 通過 |
| `.\scripts\android.ps1 build`（每個任務後） | 成功；僅有既有的 `XA0033`（JDK 版本字串解析）警告 |
| `dotnet format NetworkControl.Android.slnx --verify-no-changes`（帶 AndroidSdkDirectory / JavaSdkDirectory） | 通過 |

## 範圍與設計偏差

- 新增項目 `src/NetworkControl.Diagnostics`：為避免 Android 為了診斷引用 `NetworkControl.Ipc`（會帶入 gRPC），把打包 / 脫敏代碼移到無依賴的共享項目；桌面界面、服務、Android 和 Ipc.Tests 同步改引用。這是 `docs/android.md §8.1` 已寫明的工程前置。
- `DiagnosticsRedactor` 追加可選的 `redactAddresses` 參數（默認 false）：只為滿足 Android 診斷包的 IP 脫敏驗收，桌面行為不變。
- `RuleExportSerializer` / `RuleImportPlanner` 未修改，Android 只調用其公開 API。
- `MainActivity` 新增 SAF 封裝，M3-11 與 M3-06 共用；`QS_TILE_PREFERENCES` filter 也是 M3-09 的一部分。
- 未修改 `docs/progress.md`、`docs/progress-android.md`、`docs/m3-plan.md`（依派工指示）。
