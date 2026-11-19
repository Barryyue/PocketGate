# Kotlin Android 版功能完成記錄

> 用途：每個已完成的功能都在這裡留一條可追溯的記錄，方便後期驗證、回歸和同步。  
> 規則：功能合併或交付時，**必須**在「完成項」追加一行；未在真機驗證的項目要如實寫明，不能只寫「完成」。  
> 相關文件：[android-kotlin.md](android-kotlin.md)（計劃與階段）、[progress.md](progress.md)（整體看板）、[progress-android.md](progress-android.md)（Avalonia 版）。

## 1. 記錄格式

| 欄位 | 說明 |
|---|---|
| ID | `K-xxx`，按完成順序遞增 |
| 功能 | 使用者可見的行為，不是單個類名 |
| 版本 / 日期 | 首次完成的版本和日期 |
| 實作位置 | 主要文件或目錄，方便定位 |
| 驗證 | 自動化測試、模擬器、真機、待驗證 |
| 提交 | 首次完成的提交（後續修正另註明） |

## 2. 完成項

### v0.1（2026-11-14，提交 `65e01e0`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-001 | 獨立 Kotlin Gradle 專案、Compose 骨架、可與 Avalonia 版並存 | `android-kotlin/`、`scripts/android-kotlin.ps1` | 模擬器啟動 | applicationId `com.networkcontrol.android.kotlin` |
| K-002 | SQLite schema / 遷移 / 規則、設定、配置文件、應用、日誌、流量倉儲 | `data/db/`、`data/repository/` | JVM 測試；模擬器 | 與 C# schema 對齊 |
| K-003 | 規則模型、匹配器、條件、具體度、優先級、篡改檢測、時效 | `core/model/`、`core/rules/` | 規則單元測試 | 30 個測試的一部分 |
| K-004 | 判決階段鏈、詢問合併 / 排隊 / 超時 / 暫停 / 稍後、規則生成 | `core/pipeline/`、`core/prompts/` | 模擬器詢問流程 | BlockedEvent 只通報並返回 Deny |
| K-005 | 黑洞式 VpnService、假 DNS、UID 歸屬、路由規劃 | `engine/vpn/`、`engine/apps/` | 模擬器 + `dumpsys connectivity` | 允許的應用排除在 VPN 外 |
| K-006 | TCP RST / ICMP 不可達快速失敗 | `IpPacketBuilder`、`BlockedFlowReporter` | 封包單元測試；模擬器 | 每目標 1 秒限流 |
| K-007 | 總覽、應用、記錄、設定、詢問卡片 | `ui/` | 模擬器逐頁操作 | 三語、明暗主題 |
| K-008 | 應用三態、圖標、今日用量、最近連線 | `ui/apps/`、`AppBackend` | 模擬器 | — |
| K-009 | 阻擋記錄聚合、原因 / 關鍵字篩選、詳情 | `ui/logs/`、`SqliteActivityStore` | 模擬器 | — |
| K-010 | 流量總量（Wi-Fi + 行動網路合計） | `TrafficCollector`、`SqliteTrafficStore` | 模擬器授權後顯示 | **不區分網路類型** |
| K-011 | 配置文件建立 / 改名 / 刪除 / 切換、模式回寫 | `ProfileService`、`SettingsScreen` | 模擬器 | v0.2 修正規則隔離 |
| K-012 | 規則導入導出（`netcontrol-rules` v1，SAF） | `RuleTransferService`、`SettingsScreen` | JVM 測試 | 設備往返待驗證 |
| K-013 | 診斷包 zip + 分享 | `DiagnosticsExporter` | 模擬器 | 資料庫 / 日誌待最終回歸 |
| K-014 | 快捷設定磁貼、開機恢復、always-on VPN、撤銷處理 | `platform/`、`NcVpnService` | 代碼 / 模擬器部分 | 真機路徑待驗證 |
| K-015 | 三語、明暗主題、通知渠道、前景通知 | `UiStrings`、`NotificationChannels` | 模擬器切換繁中 | 主題色在 v0.2 擴展 |
| K-016 | 每日 config.db 備份、文件日誌 | `ConfigBackup`、`AppLog` | 代碼 / 模擬器 | 保留 7 份 / 7 天 |

### v0.2（2026-11-15，提交 `f0e61c2`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-017 | 應用列表權限引導：Manifest 宣告 `GET_INSTALLED_APPS` / `GET_APP_LIST`，啟動偵測、`requestPermissions`、設定頁狀態、降級顯示包名 / UID | `AppCatalog`、`AppRoot`、`SettingsScreen`、`AndroidManifest.xml` | 模擬器設定頁；真機待驗證 | 不同 ROM 的設定路徑見 [android-app-list-permission.md](android-app-list-permission.md) |
| K-018 | 主題色：10 個預設 + HSV 自訂選色盤，全局即時生效、持久化 | `Theme.kt`、`ThemeColorDialog.kt`、`ThemeController.kt` | 模擬器切換紫色 | 自訂色盤可拖動 |
| K-019 | 應用三態規則按配置文件隔離；舊共享應用規則遷移到默認配置文件 | `AppBackend.setAppDecision`、`AppContainer.migrateLegacySharedAppRules` | 模擬器：默認 2 條、Work 0 條 | 修正用戶回報的規則共享 bug |
| K-020 | 即時上下行網速（總覽 + 前景通知，TrafficStats 差值） | `SpeedMonitor`、`OverviewScreen`、`VpnForegroundNotifier` | 模擬器顯示 | 2 秒取樣 |
| K-021 | 勿擾入口（1 小時 / 關閉）+ 摘要通知 + 摘要卡片 | `OverviewScreen`、`QuietPeriodTracker`、`QuietPeriodNotifier` | 模擬器入口 / 代碼 | 摘要通知真機待驗證 |
| K-022 | 按網絡類型 / SSID 自動切換配置文件；NetworkMonitor 改為默認網路回調、忽略自身 VPN | `ProfileAutoSwitcher`、`NetworkMonitor`、`SettingsScreen` | 模擬器 UI；自動切換待真機 | SSID 需定位權限 |
| K-023 | P0/P1 審查修正：配置文件操作閃退、隧道重建競態、detach/revoke 競爭、BlockedEvent 待決額度、初始化/重配異常隔離、流量重複計數、日誌保留、通知權限回呼、UID -1、per-app 語言覆寫 | `VpnTunnel`、`PromptBroker`、`AppContainer`、`TrafficCollector` 等 | JVM 測試 + 構建 + 模擬器 | 詳見 [kotlin-audit-2026-11-15.md](kotlin-audit-2026-11-15.md) |

### v0.3（2026-11-15，提交 `6beed8e`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-024 | 網路類型 / SIM 感知：`NetworkContext` 記錄訂閱、卡槽、電信商、計費狀態；配置文件自動切換支援 SIM；`traffic_network` 按 Wi-Fi / 行動網路 / SIM 拆分；規則條件可按網路類型 / SIM 限定，網路變化自動重新規劃路由；應用頁新增網路條件規則編輯器 | `Models.kt`、`NetworkMonitor.kt`、`TrafficCollector.kt`、`Databases.kt`、`RoutingPlanner.kt`、`AppsScreen.kt` | JVM 測試（`NetworkProfileTest`、`RoutingPlannerTest`、`RulesTest`）+ 模擬器建立 Wi-Fi 規則並顯示 | 雙 SIM / 真機流量拆分待驗證 |
| K-029 | 連線記錄 CSV 匯出：目前篩選 / 全部、SAF 保存、RFC 4180、UTF-8 BOM、公式注入防護、分頁匯出避開查詢上限 | `LogCsv.kt`、`LogsScreen.kt`、`AppBackend.getAllLogsForExport` | JVM 測試（`LogCsvTest`）+ 模擬器匯出選單 | 真機 SAF 保存待驗證 |
| K-030 | 應用詳情：應用資訊、UID、簽章者、目前生效規則、全部規則、最近目標 / 次數 / 時間、今日流量；可一鍵信任發布者 | `AppsScreen.kt`、`AppBackend.appRecentLogs` | 模擬器開啟詳情 | 最近活動需有記錄 |
| K-031 | 規則語意去重與分組：`RuleIdentity` 正規化應用路徑 / 條件 / 網路範圍；新增規則時更新同語意規則；啟動合併重複規則並保留命中數；新增「規則」分頁按應用分組、啟停 / 刪除 | `RuleIdentity.kt`、`RuleService.kt`、`RuleTransferService.kt`、`RulesScreen.kt` | JVM 測試（`RuleIdentityTest`、`RuleTransferTest`）+ 模擬器規則分頁 | 匯入 Replace 的跨裝置 ID 差異已修正 |
| K-032 | 可信發布者 / 系統應用默認允許：設定頁開關與信任清單；應用詳情可信任簽章者；明確規則優先於信任默認值；簽章不符不會被信任 | `RoutingPlanner.kt`、`UserSettings`、`JsonCodec.kt`、`SettingsScreen.kt`、`AppsScreen.kt` | JVM 測試（`RoutingPlannerTest` 信任 / 系統應用 / 篡改案例）+ 模擬器詳情頁按鈕 | 真機系統應用範圍待確認 |
| K-033 | 批量詢問：待決列表多選，允許 / 拒絕 / 稍後一次處理；規則用 `addRange` 只發布一次規則集 | `PromptBroker.answerMany`、`OverviewScreen.kt`、`AppBackend.answerPrompts` | 構建 + 模擬器入口 | 批量流程待真機 / 多詢問場景驗證 |
| K-034 | 通知動作在進程回收後仍有效：通知 action 帶應用路徑；詢問不存在時等待核心初始化後直接建立應用規則；無有效應用時提示已過期 | `PromptNotifier.kt`、`NotificationActionReceiver.kt` | 構建 + 代碼審查 | 需真機「殺進程後點通知」驗證 |

### v0.4（2026-11-15，提交 `b66956d`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-037 | 全域鎖屏斷網：螢幕關閉 / 鎖屏時以 BlockAll 重新規劃；系統 UID 仍保留 | `ScreenStateMonitor`、`VpnEngine`、`SettingsScreen` | 構建 + 代碼審查 | 按應用鎖屏開關尚未做 |
| K-038 | 「允許一次」：詢問卡片 / 懸浮面板可只放行目前連線，不建立持久規則 | `PromptOverlay`、`PromptOverlayController`、`PromptBroker` | 模擬器詢問卡片顯示 | v0.6.1 改為應用會話語義，見 K-038b |
| K-038b | 「允許一次」改為應用會話：建立應用級暫存規則，目標應用離開前台（UsageStats `ACTIVITY_STOPPED`）2 秒後移除；下次打開重新詢問；未取得 UsageStats 時 10 分鐘兜底 | `RuleLifetime.UntilAppExit`、`PromptRuleFactory`、`TargetAppSessionTracker`、`PromptOverlay` | JVM 測試 + 構建 | 無法可靠偵測「滑掉 / 強制停止」；以離開前台為近似 |
| K-039 | 懸浮詢問面板：`SYSTEM_ALERT_WINDOW`；`PromptRouter` 統一決定應用內卡片 / 懸浮面板 / 通知；UsageStats 前景 UID 追蹤；原生 View 面板、頂部 / 底部、hold 計時、安靜通知渠道、addView fallback | `platform/prompt/`、`PromptNotifier`、`NotificationChannels`、`SettingsScreen`、`AndroidManifest.xml` | `PromptPresentationPolicyTest`；模擬器在 Chrome 上顯示面板並按允許成功 | 微信 / OEM ROM / IME 遮擋待真機 |
| K-040 | 詢問內容顯示設定：連線詳情、說明文字、鎖屏隱藏詳情；通知與懸浮面板同步 | `AppPreferences`、`PromptNotifier`、`PromptOverlayController`、`PromptOverlay`、`SettingsScreen` | 構建 + 模擬器設定頁 | 真機鎖屏待驗證 |
| K-035 | 專注模式：時長、允許應用選擇、剩餘時間、延長 15 分鐘、結束確認；專注期間 BlockAll 並只放行所選應用 | `FocusController`、`FocusSchedule`、`VpnEngine`、`RoutingPlanner`、`OverviewScreen` | 模擬器開始 15 分鐘、允許 Chrome、剩餘時間顯示 | 結束小結 / 通知尚未做 |
| K-036 | 專注時間表：按星期與時段自動進入專注；跨午夜時段；設定頁新增 / 啟停 / 刪除；每 30 秒檢查 | `FocusScheduler`、`SettingsScreen`、`AppPreferences` | 模擬器新增週一至週五 09:00–18:00 | 重啟後校正、AlarmManager 待做 |
| K-041 | 新應用安裝提醒：`PACKAGE_ADDED` 通知，點擊開啟應用決定 | `PackageChangeReceiver`、`NotificationChannels` | 構建 + 代碼審查 | 真機安裝流程待驗證 |
| K-042 | 私人 DNS / DoH 提示：偵測 `private_dns_mode=hostname`，總覽提示域名規則可能失效 | `AppBackend.privateDnsMode`、`OverviewScreen` | 構建 + 代碼審查 | DoH 應用內檢測待做 |
| K-043 | 應用鎖（第一版）：使用系統鎖屏憑證解鎖，離開超過 1 分鐘重新鎖定；未設定鎖屏時阻止啟用 | `AppLockGate`、`AppPreferences`、`SettingsScreen`、`MainActivity` | 構建 + 代碼審查 | 修改規則 / 模式前的即時驗證尚未做 |
| K-044 | 無障礙與平板：導航圖示加內容描述；內容最大寬度 760dp 居中，改善平板與大字體佈局 | `AppRoot` | 構建 + 模擬器 | TalkBack 全流程待真機 |

### v0.5（2026-11-15，提交 `1db37d1`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-045 | 啟動初始化耗時日誌；release 維持 R8 + 資源壓縮；AAB 打包 | `AppContainer`、`build.gradle.kts`、`scripts/android-kotlin.ps1` | Release APK / AAB 構建 | Baseline Profiles、arm64-only、CI 大小基線尚未做 |
| K-046 | 正式簽名環境變量（`NC_ANDROID_KEYSTORE` 等）、AAB、GitHub 更新檢查（跟隨系統代理） | `build.gradle.kts`、`UpdateChecker`、`SettingsScreen`、腳本 | 更新版本比較測試；AAB 4.1 MB | 正式 keystore 需用戶提供 |
| K-047 | 工作資料夾 / 多使用者限制提示 | `AppBackend.isWorkProfile`、`SettingsScreen` | 代碼審查 | 只提示，不支援跨使用者控制 |
| K-048 | 診斷加入網絡 / SIM / 私人 DNS / IPv6 / 懸浮權限 / 專注狀態；新增真機回歸清單 | `DiagnosticsExporter`、[android-regression-checklist.md](android-regression-checklist.md) | 構建 | 真機 IPv6 / OEM 回歸待做 |
| K-049 | adb 冒煙腳本：安裝、授權 appops、啟動、進程存活、FATAL 檢查 | `scripts/android-kotlin-smoke.ps1` | 模擬器執行通過 | 需連接裝置 |
| K-050 | 內置 WireGuard 決策：不內置，避免與單一 VpnService 衝突；未來作為轉發模式上游 | [kotlin-roadmap.md](kotlin-roadmap.md) | 設計決策 | 無代碼改動 |

### v0.6-m4spike（2026-11-15，提交 `fec8da0`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-035c | 一鍵建立「專注」白名單配置文件（複製目前規則）；進行中的專注可保存為長期配置文件（只寫入勾選的允許應用） | `AppBackend.createFocusProfile`、`SettingsScreen`、`OverviewScreen` | 模擬器建立「專注」並切換為 Active | 真機需驗證規則複製與切換 |
| K-035d / K-036b | 專注時長與時間表改用 Android 原生圓盤 `TimePickerDialog`；可自訂任意分鐘；移除 15/30/60/120 晶片換行問題 | `OverviewScreen`、`SettingsScreen` | 模擬器：專注時長 00:30 圓盤、拖動預覽 | 真機時鐘樣式依 ROM |
| K-039b | 懸浮面板自訂垂直位置：0–100%，設定頁提供可上下拖動的預覽面板 | `AppPreferences`、`PromptOverlayController`、`SettingsScreen` | 模擬器：15%→82% 拖動並保存 | 真機需驗證實際面板高度/insets |
| K-051 | **M4 Phase 0 轉發 spike**：內建 `hev-socks5-tunnel`（MIT，arm64-v8a / x86_64），VpnService TUN fd 交給原生隧道；設定頁可啟用實驗轉發、指定本機 SOCKS5 host/port/UDP | `VpnForwardingTransport`、`VpnTunnel`、`VpnEngine`、`SettingsScreen`、`jniLibs/` | 模擬器：原生庫載入、隧道啟動、SOCKS5 握手；實際上網因 Reqable + adb reverse 逾時未通過 | **規則 / 詢問 / 按域名放行尚未接入轉發層**；需真機 + 本機 FlClash/SOCKS5 驗證 |

### v0.6.2-m4spike（2026-11-15，提交 `6189f3e`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-035e | 詢問壽命精簡：移除「直到重啟」選項，保留允許一次 / 永久 / 限時；限時時長可在設定中心配置（預設 60 分鐘，可改 30 等） | `PromptOverlay`、`PromptOverlayController`、`OverviewScreen`、`UserSettings.promptTimedMinutes`、`SettingsScreen` | 構建 + 模擬器 | `UntilRestart` 枚舉保留以載入舊規則，但不再提供新選擇 |
| K-052 | 靈動島（速度）：保護開啟後在螢幕頂部顯示上傳/下載速度；預設開啟，可在設定關閉；鎖屏隱藏、點擊開啟主界面；vivo / iQOO 相容模式（系統島下方 + 0–48dp 偏移）；詢問面板自動下移避免重疊 | `SpeedIslandController`、`AppContainer`、`SettingsScreen`、`PromptOverlayController` | 模擬器 `dumpsys window`：預設 frame y=84，vivo 相容後 y=126 | 真機位置 / 遮擋 / 耗電待驗證 |

### v0.6.5-m4spike（2026-11-15，提交 `c29b25c`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-053 | 版本說明：設定 → 關於點擊版本號，查看目前版本新增功能 / 優化，並可切換歷史版本 | `ReleaseNotes.kt`、`SettingsScreen` | 模擬器點擊版本顯示 0.6.4 / 0.6.5 內容與歷史按鈕 | 內容為內建版本記錄，未接遠端更新說明 |

### v0.6.6-m4spike（2026-11-15，提交 `c2b1a4e`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-054 | 專注與保護聯動：保護關閉時開始專注會自動開啟保護；保護中途關閉時顯示警告與「開啟保護」；專注卡片按鈕改為兩欄，修正「結束專注」文字變形 | `OverviewScreen`、`ProfileAutoSwitcher` 無關 | 模擬器：開始專注後保護自動 On，延長 / 結束專注並排、保存配置文件另起一行 | 真機 VPN 授權流程待驗證 |

### v0.6.7-m4spike（2026-11-15，提交 `712ebe7`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-055 | 靈動島位置與透明度：水平 / 垂直偏移 -60..+60dp、背景透明度 20–100%、重設；文字固定白色加陰影不透明；專注與勿擾相鄰排列；勿擾保護聯動 | `AppPreferences`、`SpeedIslandController`、`SettingsScreen`、`OverviewScreen` | 構建 + 模擬器版面 | 真機偏移與透明度效果待驗證 |

### v0.6.8-m4spike（2026-11-15，提交 `d94823c`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-056 | 靈動島樣式：完整（上下行同時）/ 循環（每 2 秒輪流顯示上傳或下載）/ 迷你（狀態欄風格小字、縮小內距與綠點）；設定頁可切換 | `SpeedIslandMode`、`SpeedIslandController`、`SettingsScreen` | 構建 + 模擬器設定頁 | 真機文字寬度 / 循環節奏待驗證 |

### v0.6.9-m4spike（2026-11-15，提交 `09bf843`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-057 | 靈動島水平 / 垂直偏移與背景透明度改為拖動即時生效 | `SpeedIslandController.applyPosition`、`SettingsScreen` | 模擬器拖動水平滑桿 frame x 由 335 變 499，即時顯示 | 真機拖動手勢待驗證 |

### v0.6.10-m4spike（2026-11-15，提交 `2ef6dbc`）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-058 | 靈動島「嘗試放進狀態欄」：`FLAG_LAYOUT_IN_SCREEN` / `NO_LIMITS`、`fitInsetsTypes=0`、cutout ALWAYS，垂直偏移可負值 | `SpeedIslandController`、`AppPreferences`、`SettingsScreen` | 構建 + 代碼審查 | 第三方懸浮窗仍在系統狀態欄之下，部分 ROM 會遮住；需真機確認 |

### v0.7.0-m4spike（2026-11-15）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-059 | 靈動島透明度可到 0：背景、邊框、elevation 全消失；全透明時加 `FLAG_NOT_TOUCHABLE`，文字用亮度對比陰影 | `AppPreferences`、`SpeedIslandController`、`SettingsScreen` | 模擬器截圖：0% 時只剩文字且不擋觸控 | 淺色桌布可讀性待真機 |
| K-060 | 文字顏色三模式：跟隨主題（`ThemeController.color` 即時刷新）、跟隨狀態欄（日夜黑白近似）、自訂（預設色盤 + HSV） | `AppPreferences`、`AppBackend`、`SpeedIslandController`、`SettingsScreen`、`ThemeColorDialog` | 模擬器：主題改藍色文字同步變藍、狀態欄模式黑字、自訂紫色即時生效 | ROM 狀態欄圖示色無法直接讀取，日夜近似 |
| K-061 | 狀態欄模式與文字色聯動：開啟時 `theme` 自動切 `statusbar`，關閉時 `statusbar` 切回 `theme` | `AppBackend.setSpeedIslandStatusBarMode` | 代碼審查 + 設定頁狀態同步 | 手動選過的模式不被覆蓋 |
| K-062 | 綠色狀態點可顯示 / 隱藏，並可自訂顏色 | `AppPreferences`、`SpeedIslandController`、`SettingsScreen` | 模擬器：隱藏 / 顯示、紅色自訂均正常 | — |
| K-063 | 上傳 / 下載順序互換，套用完整、循環、迷你；循環起始方向一併反轉 | `AppPreferences`、`SpeedIslandController`、`SettingsScreen` | 模擬器：順序即時切換 | — |
| K-064 | 設定頁顏色籌片改用 `FlowRow`，窄屏自動換行 | `SettingsScreen` | 模擬器 1080×2400 設定頁 | — |

### v0.8.0-m4spike（2026-11-15）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-065 | 靈動島設定獨立成頁：頂部即時預覽 + 顯示 / 外觀 / 位置分組，設定頁只保留入口與摘要 | `SpeedIslandSettingsScreen`、`AppRoot`、`SettingsScreen` | 模擬器打開頁面、預覽與設定即時生效 | 後續新選項都放此頁 |
| K-066 | 顯示內容可切換：速度 / 今日流量 / 今日攔截 / 待處理詢問 / 目前網路 | `SpeedIslandContent`、`SpeedIslandFormatter`、`SpeedIslandController` | JVM 測試 + 模擬器頁面 | 流量與攔截每 60 秒刷新 |
| K-067 | 字體大小 80–160%、字體粗細常規 / 中等 / 粗體 | `AppPreferences`、`SpeedIslandController`、`SpeedIslandSettingsScreen` | 模擬器預覽與實際懸浮窗 | 迷你樣式同步縮放 |
| K-068 | 懸浮詢問可隱藏「限時允許」選項 | `AppPreferences`、`PromptOverlayController`、`PromptOverlay`、`SettingsScreen` | 代碼審查 + 頁面開關 | 關閉後只留允許一次 / 永久 / 拒絕 |
| K-069 | 修復轉發模式：hev 隧道 ipv4/ipv6 改用 VPN 介面實際地址（10.111.222.1 / fd00:6e63::1）、關閉 TSO/USO/jumbo、加大任務堆疊 | `VpnForwardingTransport` | 模擬器 + Reqable SOCKS5：HTTP 請求和回應完整通過 tun0 | 先前位址不一致導致回包被丟棄 |
| K-070 | 轉發設定新增「測試代理」與 DNS 提示 | `Socks5Probe`、`SettingsScreen` | 代碼審查 | DNS 仍需代理支援 UDP |

### v0.8.1-m4spike（2026-11-15）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-071 | 轉發模式可選擇「代理應用」，把它加入 VPN 的 `addDisallowedApplication`，避免代理自身流量回環；設定頁顯示代理應用與 VPN 共存提示 | `AppPreferences`、`AppBackend`、`VpnEngine`、`SettingsScreen`、`UiStrings` | 構建 + 設定頁代碼審查 | 代理需關閉 TUN、保持 SOCKS 埠 |

### v0.8.2-m4spike（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-072 | 速度 / 流量單位改為完整寫法：B/s、KB/s、MB/s、GB/s、TB/s，迷你樣式不再縮寫 | `SpeedIslandFormatter`、`SpeedIslandFormatterTest` | JVM 測試 | — |
| K-073 | 狀態欄文字色改為參考系統深色模式 + 桌布 `WallpaperColors` 色調，並在桌布 / 配置變化時刷新 | `SpeedIslandController` | 代碼審查 | Android 無公開 API 讀取狀態欄圖示色，仍屬近似 |
| K-074 | 詢問面板「允許一次」改為「僅一次」 | `UiStrings` | 構建 | 只改文案，行為不變 |

### v0.8.3-m4spike（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-075 | 詢問面板改為一鍵決策：禁止 / 僅一次 / 允許；限時允許移到第二行入口，使用設定的時長；關閉「顯示限時允許」後即為三個按鈕 | `PromptOverlayController`、`PromptOverlay` | 構建 + 代碼審查 | 禁止與允許為永久；僅一次為 UntilAppExit |

### v0.8.4-m4spike（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-076 | 「稍後」的靜默期由 10 分鐘縮短為 1 分鐘，之後同一應用再次聯網會重新詢問 | `PromptBroker.suppressAfterLaterMs` | 構建 | 避免頻繁騷擾，同時修正「稍後再也不提醒」 |

### v0.8.5-m4spike（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-077 | 「稍後」改為按前台會話判斷：同一應用本次前台不再詢問，切出再回到前台後重新詢問；無使用情況權限時 1 分鐘兜底 | `PromptBroker`、`PromptRouter`、`PromptForegroundTracker` | 模擬器：點稍後 → 回桌面 → 再開 Chrome，詢問重新出現 | 需要「使用情況訪問」權限 |

### v0.9.0-m4p1（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-078 | 轉發模式接入防火牆：hev 改讀 socketpair，`ForwardingPacketFilter` 在新連線（TCP SYN / UDP 新五元組）上查 UID，按路由計畫轉發 / 拒絕（RST、ICMP）/ 拒絕並詢問；判決按五元組緩存，既有 TCP 連線走快速路徑 | `ForwardingPacketFilter`、`ForwardingRelay`、`ForwardingPolicy`、`VpnTunnel`、`VpnEngine` | JVM 測試；模擬器：Chrome 在詢問模式被攔並彈出懸浮詢問，點允許後經 Reqable SOCKS5 載入 example.org | 規則或計畫改變時清空緩存；已建立的連線不會被新規則切斷 |
| K-079 | 啟用 hev mapdns：系統 DNS 指向 `10.111.222.3`，A 記錄映射到 `100.64.0.0/10`，連線以域名送給 SOCKS5；過濾層監聽 DNS 回覆，詢問 / 記錄可顯示域名 | `VpnForwardingTransport`、`ForwardingPacketFilter`、`BlockedFlowReporter` | JVM 測試；模擬器：`ping example.org` 解析為 `100.64.0.5`，`nc example.org 80` 經 Reqable 回 200，詢問記錄帶 `beacons5.gvt3.com` 等域名 | 只支援 IPv4（AAAA 回空）；應用自帶 DNS（如 Chrome 直連 `10.0.2.3:53`）仍按普通 UDP 處理 |
| K-080 | UDP 開關改語義：一律使用標準 UDP ASSOCIATE；關閉時由過濾層拒絕 UDP（ICMP 不可達），不再寫入代理不支援的 `udp: 'tcp'` | `VpnForwardingTransport`、`ForwardingPacketFilter` | JVM 測試 | 映射 DNS 不受開關影響 |
| K-081 | 轉發啟動失敗時按路由計畫回退黑洞模式（排除名單 + 假 DNS），保留錯誤提示並每 2 分鐘重試；先前的回退實際是整機斷網且清掉錯誤 | `VpnTunnelController.apply`、`VpnEngine.checkForwarding` | 代碼審查 | 未在模擬器觸發真實的 hev 啟動失敗 |
| K-082 | 上游 SOCKS5 健康檢查（每 30 秒只做方法協商），失敗時總覽顯示提示；設定頁顯示「正在轉發 / 未在轉發」、FlClash 設定指引 | `Socks5Probe.handshake`、`VpnEngine`、`OverviewScreen`、`SettingsScreen` | 模擬器：移除 `adb reverse` 後 30 秒內記錄 `upstream 127.0.0.1:9000 unavailable` | 上游斷開時連線失敗（不會直連） |
| K-083 | 「直連應用」：選中的應用排除在 VPN 之外，不經代理也不受防火牆管控 | `AppPreferences.forwardingDirectPackages`、`VpnEngine`、`SettingsScreen` | 構建 | — |
| K-084 | 判決管線跟隨實際生效模式：專注 / 鎖屏斷網時按「全部阻止」判決，不再彈詢問、記錄不再寫成允許 | `VerdictPipeline.effectiveMode`、`AppContainer` | 構建 + 代碼審查 | 黑洞與轉發模式都受益 |
| K-085 | hev 日誌改為 `warn`，超過 1 MB 時下次啟動清空 | `writeForwardingConfig` | JVM 測試 | 先前 debug 級別在模擬器累積到 3.7 MB |

### v0.9.1-m4p2（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-086 | 轉發模式逐連線執行帶域名 / 位址 / 端口 / 協定條件的規則（應用級規則仍由路由計畫處理）；映射位址按公網分區比對 | `ForwardingPolicy.decide(flowRule)`、`ForwardingPolicy.conditionalAction`、`RuleSet.hasFlowConditions`、`VpnEngine.decideForwarding` | JVM 測試；模擬器：Chrome 已有應用級允許，加入「Chrome 拒絕 example.org」後 example.org 被拒（記錄 Deny / Rule / 規則 id），baidu.com 照常經 Reqable 轉發 | 位址條件無法比對映射位址（真實 IP 由代理解析）；專注、全部阻止、停用模式不套用條件規則 |
| K-087 | 「僅一次」改按 Activity 追蹤前台：應用內切換頁面不再被當成離開；沒有目標時重置狀態，不沿用過期前台 | `AppForegroundState`、`TargetAppSessionTracker` | JVM 測試 | 真機上的 UsageStats 事件順序未逐一驗證 |
| K-088 | 審查修復：通知動作的 Toast 改在主執行緒（原本必崩）；快速設定磁貼鎖屏時先 `unlockAndRun`；舊版共享規則遷移只做一次；`RuleIdentity.merge` 對臨時規則採用新建立時間 | `NotificationActionReceiver`、`ProtectionTileService`、`AppContainer`、`AppPreferences.legacyRulesMigrated`、`RuleIdentity` | JVM 測試（merge）；模擬器：升級後 `legacy_rules_migrated=true` | 磁貼與通知動作未在模擬器手動觸發 |
| K-089 | 黑洞模式下被丟棄的連線即使規則判定允許也記錄為拒絕（只放行部分域名的應用無法按應用排除） | `VerdictPipeline` | 構建 + 代碼審查 | — |
| K-090 | 被拦連線只做一次 `getConnectionOwnerUid`（快速失敗查到的 UID 直接交給背景處理）；應用身分緩存鍵含簽名與更新時間，防止替換應用後沿用舊身分 | `BlockedFlowReporter` | 構建 + 模擬器黑洞模式記錄正常 | — |
| K-091 | 不持有連線的詢問：相同目標去重，最多 50 條流，避免快照無限增長 | `PromptBroker.isRedundant` | 構建 | — |
| K-092 | 省電：過期規則按下一個到期時間喚醒（規則變更時重算），不再每秒輪詢；關屏時暫停網速取樣；沒有詢問 / 抑制時 PromptRouter 掛起等待 | `RuleLifetimeManager`、`SpeedMonitor`、`ScreenStateMonitor.interactive`、`PromptBroker.busy`、`PromptRouter` | 構建 + 模擬器啟動 | 未量測真機耗電 |
| K-093 | 轉發性能：同一條已放行 UDP 流的後續封包走快速路徑（比對原始位址 / 端口位元組，2 秒有效，規則變更即失效）；過濾層例外不再結束轉發執行緒 | `ForwardingPacketFilter`、`ForwardingRelay` | JVM 測試 | 未在真機量測吞吐 |
| K-094 | VPN 服務被系統結束（非撤銷）而防護仍開啟時自動重建隧道（10 秒內最多一次） | `VpnTunnelController.onDetached`、`VpnEngine` | 代碼審查 | 未在模擬器觸發服務被殺 |

### v0.9.2-m4p3（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-095 | 版本說明三語化：每則內容都有英文 / 簡體 / 繁體（台灣用語），跟隨應用界面語言（與 `UiStrings` 相同的語言判斷）；補齊 0.1.0～0.9.1 全部歷史條目 | `NoteText`、`NoteLanguage`、`ReleaseNotes`、`UiStrings.noteLanguage`、`SettingsScreen.ReleaseNoteSection` | JVM 測試（三語不缺、簡繁常用字不混用、語言標籤對應）；模擬器 Release：`cmd locale set-app-locales` 切到 en / zh-TW / zh-CN，本版說明與歷史版本都顯示對應語言 | 新增版本說明時三種語言都必須填寫，否則測試失敗 |

### v0.9.3-m4p4（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-096 | 轉發中繼 / 虛擬網卡讀取失敗而防護仍開啟時，自動重建隧道（10 秒內最多一次），避免 VPN 顯示 Active 但實際已斷 | `VpnEngine.onStateChanged` | 構建 + 代碼審查 | 未模擬執行緒失敗 |
| K-097 | 「稍後」的會話抑制在應用內 Activity 暫時為 null 時保留，不把換頁誤判成重新進入 | `PromptRouter` | 構建 | 與 K-087 的「僅一次」Activity 追蹤互補 |

### v0.9.4-m4p5（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-098 | 內建直連出口：逐連線模式可選「直接連出」，hev 把已判決的連線交給本進程的 SOCKS5（只監聽 127.0.0.1、每次啟動隨機帳密、拒絕無驗證），支援 CONNECT 與 UDP ASSOCIATE、IPv4 / IPv6 / 域名；不再依賴 FlClash 等外部代理 | `DirectSocksServer`、`Socks5Wire`、`VpnEngine`、`VpnTunnelConfig.upstreamUsername/Password`、`writeForwardingConfig` | JVM 測試（真 socket：拒絕無密碼 / 錯密碼、按 IP / 域名 CONNECT、半關閉後下行完整、連線被拒、UDP 關聯、hev 設定帳密）；模擬器：hev debug 日誌確認帳密驗證通過，shell 與 Chrome 按 IP 經內建出口連到 Akamai 並回應 | 本機模擬器的系統 DNS 本身不可用（關閉 VPN 時 `ping example.org` 也是 unknown host），按域名的直連只做了 JVM 測試；未量測真機吞吐 / 耗電 |
| K-099 | 內建出口在實體網路上解析與連線（`Network.getAllByName` / `bindSocket`，結果緩存 2 秒），IPv4 優先，多個位址時單一位址 3 秒逾時 | `UnderlyingNetwork`、`DirectSocksServer` | 構建 + 模擬器 | — |
| K-100 | 逐連線模式記錄已允許的連線：過濾層在轉發新連線時通知回報器，判決管線只寫記錄（有允許規則時記規則 id 並計命中） | `ForwardingPacketFilter.onForwarded`、`BlockedFlowReporter.reportAllowed`、`VerdictPipeline.recordAllowed` | 模擬器：Chrome 經內建出口的連線寫入 connection_log（Allow / Rule / Chrome 規則 id） | 同一目標 5 秒內去重 |
| K-101 | 設定頁「逐連線模式」：出口可選「直接連出 / 經 SOCKS5 代理」；直連時隱藏代理相關設定。升級前已開啟轉發的使用者保留外部代理 | `AppPreferences.forwardingUpstream`、`SettingsScreen`、`UiStrings`（三語） | 構建 | 界面未截圖檢查，請用戶目視確認 |
| K-102 | 診斷：`files/hev-debug` 存在時 hev 以 debug 級別記錄；JVM 測試啟用 `isReturnDefaultValues` | `VpnTunnelController.startForwarding`、`build.gradle.kts` | 模擬器 | — |

### v0.9.5-m4p6（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-103 | 攔截列表引擎：解析 hosts / 純域名 / AdBlock・AdGuard DNS 語法（`||d^`、`*.d`、`@@||d^` 例外，略過正規表示式與改寫類修飾符）；編譯成排序的 64 位元 FNV-1a 雜湊陣列（每域名 8 bytes），存 `files/blocklists/<id>.bin`，啟動時直接載入 | `DomainBlocklist`、`DomainHash`、`BlocklistParser`、`BlocklistMatcher` | JVM 測試：子域名 / 精確匹配語意、例外、略過不支援規則、TLD 與 IP 不當域名、檔案往返 | hosts 與純域名只攔精確域名（與 Pi-hole 一致），`||` 與 `*.` 攔子域名 |
| K-104 | 訂閱管理：內建 7 份名單（anti-AD、HaGeZi NORMAL / PRO / TIF mini、OISD small、AdGuard DNS filter、StevenBlack），每份含鏡像；條件請求（ETag / Last-Modified）、64 MB 上限、失敗保留舊名單、6 小時後重試；自動更新預設每週且僅 Wi-Fi（使用者手動操作不受限）；第一次開啟才套用預設勾選（anti-AD + HaGeZi NORMAL）；自訂 https 訂閱與檔案匯入；下載走實體網路 | `BlocklistManager`、`BlocklistCatalog`、`BlocklistStateCodec`、`UnderlyingNetwork.openConnection/isUnmetered` | JVM 測試（本機 HTTP 伺服器）：下載、304 不重下、重啟後從編譯檔載入、伺服器錯誤保留舊名單、拒絕遠端 http、檔案匯入、預設勾選只套用一次；2026-11-16 從開發機確認 anti-AD、HaGeZi、AdGuard、StevenBlack 地址可下載 | OISD 在開發機的 curl 上證書吊銷檢查失敗（Windows schannel），未在真機確認 |
| K-105 | 逐連線判決接入名單：順序為「使用者條件規則 → 名單 → 路由計畫」；名單只作用於本來會放行或詢問的連線，命中時直接拒絕不詢問；系統 UID、停用與全部阻止模式不套用；使用者對域名的允許規則（即使被應用級規則遮蔽）一律作為例外 | `ForwardingPolicy.decide(blocklisted)`、`ForwardingDecision.Blocklisted`、`RuleSet.allowsDomain`、`VpnEngine.decideForwarding` | JVM 測試；模擬器：本機 HTTP 服務經 `adb reverse` 提供測試名單，Chrome 訪問 `blocked-ads.example` 與子域名被拒、記錄 Deny / 攔截列表 / `blocklist:custom-local`，未列入的域名照常允許 | 黑洞模式不生效（被允許的應用不經過 VPN） |
| K-106 | 界面：設定頁「攔截列表」卡片與獨立頁面（總開關、名單勾選與狀態、立即更新、新增訂閱、檔案匯入、僅 Wi-Fi、更新間隔）；記錄頁新增「已攔截 / 已允許」切換、攔截列表篩選、詳情顯示名單名稱與「一律允許這個網域」；`VerdictReason.Blocklist`；三語文字 | `BlocklistSettingsScreen`、`AppRoot`、`SettingsScreen`、`LogsScreen`、`AppBackend`、`UiStrings` | 模擬器（透過 UI 樹操作，未截圖）：開啟頁面、「立即更新」得到無變化（304）、記錄頁看到攔截列表條目，按「一律允許」後建立全域允許規則，再次訪問變為允許 | 外觀請用戶目視確認 |
| K-107 | 修正：記錄頁原本只查詢拒絕的記錄，0.9.4 寫入的「已允許」記錄在界面上看不到（已補切換）；內建出口關閉時並發移除連線可能拋 `NoSuchElementException` | `LogsScreen`、`AppBackend.getLogs(action)`、`DirectSocksServer.close` | JVM 測試（連續兩輪） | — |
| K-108 | 網路安全設定：維持禁止明文 HTTP，只對 `127.0.0.1` / `localhost` 開放（本機測試名單） | `res/xml/network_security_config.xml`、`AndroidManifest.xml` | 模擬器 | — |

### v0.9.6-m4p7（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-109 | DNS 導回：逐連線模式下，應用發往其他伺服器的 IPv4 UDP DNS 由過濾層改寫到映射 DNS（NAT 表記住原伺服器，回覆時改回來源並重算 IP / UDP 檢查碼）；發往其他伺服器的 TCP 53 回 RST、IPv6 DNS 回 ICMP 不可達，讓解析器改走可改寫的路徑 | `DnsRedirect`、`ForwardingPacketFilter.redirectDns`、`onHevPacket` | JVM 測試（改寫與還原、檢查碼驗證、過濾層端到端）；模擬器：Chrome 自己的 DNS（直連 `10.0.2.3:53`）被導回，連線全部帶域名（含 QUIC），可以打開 example.org、wikipedia.org | 預設開啟（`dns_bypass_protection`） |
| K-110 | 阻擋加密 DNS 繞過：一般應用的 DoT / DoQ（853）與連到知名 DoH 網域 / IP 的 443 連線視為命中名單（記錄名單為 `dns-bypass`，界面顯示「加密 DNS 繞過」）；系統核心 UID 例外；使用者的域名允許規則仍然優先 | `EncryptedDnsTargets`、`VpnEngine.decideForwarding` | JVM 測試 | 私人 DNS 設為指定主機名時由系統解析，本應用看不到，界面提示改為自動 / 關閉 |
| K-111 | 內建出口的加密 DNS：RFC 8484 DoH 用戶端（POST、IP 地址免引導），內建 Cloudflare / Google / Quad9 / 阿里 / DNSPod 與自訂地址；TTL 快取（30 秒～1 小時）、IPv4 優先、NXDOMAIN 負快取、連續 3 次失敗後 2 分鐘內改用系統 DNS（可關閉退回） | `DnsMessage`、`DohResolver`、`DohProviders`、`VpnEngine.exitResolver`、`AppPreferences.exitDns*`、`SettingsScreen` | JVM 測試（快取、NXDOMAIN、AAAA 退回、斷路器、不允許退回時失敗）；模擬器：選阿里 DNS 後 shell `nc example.org 80` 經內建出口回 `HTTP/1.1 200 OK`（先前模擬器系統 DNS 不可用而無法按域名直連） | 預設仍為系統 DNS |

### v0.9.7-m4p8（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-112 | 規則改變後切斷已建立的連線：過濾層記住已轉發的 TCP 連線（判決輸入），`invalidate()` 時重新判決；已不允許的連線在下一個封包經過（任一方向）時雙向送出精確序號的 RST（給應用：序號 = 它送出的 ACK；給 hev：序號 = 應用的序號），並丟棄原封包；新隧道 `reset()` 清空 | `ForwardingPacketFilter.invalidate/reset/cutFromClient/cutFromHev`、`IpPacketBuilder.tcpReset(sequence)`、`ForwardingRelay.hevWriter` | JVM 測試（兩個方向、序號、只切一次、仍允許的不動、reset 後遺忘） | 只追蹤 6 小時內的連線；UDP 由緩存清空自然重新判決 |
| K-113 | 追蹤器識別：Disconnect `services.json`（CC BY-NC-SA 4.0，裝置直接下載、不隨安裝包散佈）解析成「域名 → 公司 + 類別集合」，子域名逐層查詢；記錄頁每列與詳情顯示「追蹤器：公司 · 類別」；可整類攔截廣告 / 統計 / 社群 / 指紋 / 挖礦 / 郵件追蹤（命中任一被攔類別即攔截，記為 `tracker:<類別>`）；資料庫可單獨開啟只做識別 | `TrackerDatabase`、`TrackerCategory`、`BlocklistCatalog.trackers`、`BlocklistMatcher`、`BlocklistManager`、`BlocklistSettingsScreen.TrackerCard`、`LogsScreen` | JVM 測試（真實格式節錄、子域名、多類別、整類攔截、下載與重載）；模擬器（資料檔以 run-as 放入，模擬器系統 DNS 不可用無法自行下載）：開啟「統計分析」後 Chrome 訪問 hotjar、google-analytics 被攔並記為 `tracker:Analytics`，記錄頁顯示「追蹤器：ContentSquare · 統計分析」 | 同一域名常屬多類（google-analytics 同時是統計、郵件、指紋），最初只取一類導致漏攔，已改為類別集合 |
| K-114 | 內建出口改用 NIO：握手後的連線交給單一 Selector 執行緒轉送（背壓、半關閉、半關閉閒置 2 分鐘回收），不再每條連線兩個執行緒 | `TcpRelay`、`DirectSocksServer` | JVM 負載測試：64 條並發連線各 512 KB 完整回傳（0.33 秒）、計數歸零、執行緒不隨連線數倍增；模擬器瀏覽正常 | UDP 關聯仍為執行緒模式 |
| K-115 | 修正「稍後」：原本以「前台換成別的應用再換回來」判斷，回桌面時前台為空，0.9.3 為容忍應用內換頁而保留上一個應用，導致離開後永遠不清除（關掉應用再開仍斷網且不詢問）。改為與「僅一次」相同的 Activity 判斷：抑制開始後，該應用所有頁面在亮屏時都 STOPPED 才清除 | `PromptForegroundTracker.leftAt`、`PromptBroker.sessionSuppressions`、`PromptRouter` | 模擬器：Chrome 詢問點「稍後」→ 應用內繼續瀏覽不再詢問 → 回桌面 → 日誌「later cleared … left foreground」→ 重新開啟再次詢問 | 只為真正建立的詢問記錄 request 日誌 |
| K-116 | 修正「僅一次」：首次建立前台狀態只回看 5 分鐘，若使用者已在應用中停留更久，10 分鐘後規則被當成「從未開啟」回收；關屏超過 10 分鐘同樣誤收。改為首次回看 6 小時，出現在前台過的應用只在真正離開時撤銷 | `TargetAppSessionTracker` | 代碼審查 + 既有 JVM 測試 | 未在模擬器重現長時間場景 |
| K-117 | 修正啟動競態：重新規劃信號原為無 replay 的 SharedFlow，收集端開始前發出的信號會遺失，啟動後可能不建立隧道；改為 CONFLATED Channel | `VpnEngine.replanSignal` | 模擬器：重現時保護開啟但無 `tunnel active`；修正後每次啟動都建立隧道 | — |
| K-118 | 白名單模式下查不到連線擁有者時改為拒絕（原本除「全部阻止」外都放行）；其他模式仍放行，避免誤殺系統元件 | `ForwardingPolicy.decide` | JVM 測試 | 對應待辦 K-051b |
| K-119 | 外部 SOCKS5 帳號密碼（RFC 1929）：設定頁新增欄位，隧道設定、測試代理與健康檢查都會使用 | `AppPreferences.socks5Username/Password`、`Socks5Probe.negotiate`、`VpnEngine`、`SettingsScreen` | JVM 測試（以內建出口驗證：正確、缺少、錯誤的帳密） | 對應待辦 K-051d；密碼存於應用私有的偏好設定 |

### v0.9.8-m4p9（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-120 | 「僅一次」因離開前台而撤銷後進入背景靜默：該應用的背景連線直接拒絕並記錄，不建立詢問（不發通知、不彈懸浮窗），已存在的該應用詢問一併移除；應用回到前台時解除，之後的連線照常以懸浮窗詢問。沒有使用情況權限時不靜默 | `TargetAppSessionTracker.onRevokedAfterLeaving`、`PromptBroker.quietInBackground/endBackgroundQuiet`、`PromptForegroundTracker.isForeground`、`PromptRouter`、`AppContainer` | 模擬器：Chrome 點「僅一次」→ 回桌面 → 日誌「allow-once ended … quiet」→ 約 55 秒內 Chrome 無詢問、無通知，背景連線記錄為拒絕 → 重新開啟 → 日誌「back in foreground; prompts resume」並彈出懸浮窗 | 使用者回饋：選僅一次後應用到後台，通知很多但懸浮窗很少（懸浮窗只詢問前台應用，屬設計）；改為背景靜默 |

### v0.9.9-m4p10（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-121 | 敏感操作再驗證（K-043b）：開啟應用鎖時，關閉保護、切換模式、修改規則 / 應用決定、開關逐連線模式、改直連應用、關閉 DNS 防繞過、關閉攔截列表、關閉應用鎖前要求系統鎖屏憑證，驗證後 30 秒寬限；鎖屏上的通知「允許 / 拒絕」設為需要解鎖（Android 12+）；應用鎖說明改為如實描述能力與限制 | `AppLockGate.SensitiveActionGuard / LocalSensitiveAction`、各界面、`PromptNotifier.lockedAction`、`UiStrings.AppLockHint` | 模擬器（臨時設 PIN，測後清除）：解鎖 30 秒後切換模式出現「請驗證身分」，輸入 PIN 後繼續；寬限內第二次修改不再詢問 | 用戶決定：本版之後應用鎖相關功能降低優先級，暫不再做（無法阻止在系統設定關閉 VPN，目前場景用不到） |
| K-122 | 主執行緒 PackageManager（K-026 剩餘部分）：代理應用 / 直連應用選擇框、「關於」中的應用數改為背景載入 | `SettingsScreen` | 構建 | `establish()` 失敗的顯示已在 v0.9.0 起於總覽顯示 tunnelError |
| K-123 | 清理全部編譯警告（完整重新編譯為零警告）：桌布變化改用 `WallpaperManager.OnColorsChangedListener`；`onLowMemory`、`createConfirmDeviceCredentialIntent`、NetworkStats 的舊網路類型常數加註說明後壓下；`arrayOf<Any>` 明確型別；多餘條件 | `SpeedIslandController`、`AppLockGate`、`TrafficCollector`、`Databases`、`BlocklistSettingsScreen` | 刪除編譯快取後完整重編 | — |
| K-124 | K-045b：Release 按 ABI 拆分（arm64 為主安裝包，另附 x86_64 模擬器包；Debug 與 AAB 不變）；Baseline Profile（`:baselineprofile` 模組 + `profileinstaller`，冷啟動後依序打開各分頁），生成檔提交於 `app/src/release/generated`；腳本新增 `baseline`、`startup` 命令；CI 新增 `android-kotlin.yml`（單元測試、Release、體積上限 5.5 MB、檢查 baseline.prof） | `app/build.gradle.kts`、`baselineprofile/`、`scripts/android-kotlin.ps1`、`.github/workflows/android-kotlin.yml` | 模擬器生成 Profile 成功；安裝包含 `assets/dexopt/baseline.prof`；arm64 包 4,287,546 bytes（原通用包 4,450,329）；冷啟動在模擬器上量不出差異（全新安裝中位數 526 / 493 ms，強制編譯後 902 / 892 ms，均在雜訊內） | 側載安裝時配置要等首次啟動後由系統背景編譯才生效；CI 工作流未在 GitHub 上實際執行過 |

### v0.9.10-m4p11（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-125 | 修正 DNS 導回的 NAT 鍵：原本只用「用戶端位址 + 埠」（所有應用共用虛擬網卡位址，等於只有埠），系統解析器之後用到同一個埠直接查映射 DNS 時，回覆被誤改成來自別的伺服器而被丟棄，3 秒後重送。改為「位址 + 埠 + DNS 事務 ID」，回覆還原後立即移除 | `DnsRedirect` | 新增回歸測試（舊代碼會失敗）；實機（vivo V2509A，純 IPv6 行動網路，逐連線 + FlClash）修正前：20 條並發連線 3 條失敗、其餘 0.34～9.4 秒，直接走 FlClash 全部 0.29～0.98 秒；單獨並發解析每批有 5～20% 多等 3 秒；本應用轉發寫入無錯誤 | 修正版已安裝到實機，但 adb 無線連線中斷，修正後的實機複測未完成 |

### v0.10.0-m4p12（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-126 | 更新檢查支援 GitHub / GitLab / Gitee / 自訂 HTTPS JSON；設定頁可選來源與倉庫；有 APK 附件時直接開啟下載連結 | `UpdateChecker`、`AppPreferences`、`AppBackend`、`SettingsScreen`、`UiStrings` | JVM 測試（各來源 JSON 解析、URL 編碼、HTTPS 限制） | Gitee 未登入 API 有速率限制；GitLab 預設 gitlab.com |

### v0.10.1-m4p12（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-127 | 映射 DNS 排隊與自動重送（`DnsPacer`）：逐包追蹤發現 hev 的映射 DNS 同時收到多個查詢只回覆第一個、剛回覆的同一毫秒內收到的也丟，間隔數毫秒仍有約 4% 沒有回覆；應用要等 Android 解析器 5 秒後重送。現在一次只放一個查詢在途、回覆後間隔 2 ms 放行下一個，25 ms 未回覆自己重送（最多 3 次），回覆按「用戶端埠 + 事務 ID」對應；新增放行執行緒 `DnsPump`。release 0.10.1 | `DnsPacer`、`ForwardingPacketFilter`、`ForwardingRelay` | `DnsPacerTest`（5 項）；模擬器 30 個不同網域同時解析：修正前每輪 7～18 個要 5～9 秒，修正後 6 輪全部在 0.2 秒內（中位數 80～130 ms） | 修正版（當時編號 0.9.11）已安裝到實機且隧道正常，但實機上 adb shell（uid 2000）目前屬於繞過 VPN 的應用，無法從 shell 量測；需用戶實際瀏覽確認 |

### v0.10.2-m4p12（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-128 | hev 重啟後恢復假位址映射（`FakeIpMappings`）：隧道每次重建 hev 都會清空映射、從 100.64.0.0 起重新分配，瀏覽器（系統解析器結果固定快取 60 秒）與既有連線仍用舊假位址，hev 不認得或已分給別的網域，連線卡住或連錯主機（實機症狀：Chrome 開百度一直載入、ClientHello 517 位元組一直沒被確認）。hev 按查詢順序從 .0 依序分配，所以 hev 啟動時按原位址順序重放已知網域（排在所有應用查詢之前）；重放中暫時丟棄發往未恢復位址的新連線，結束後 hev 不認得的假位址直接拒絕（TCP RST / ICMP），應用立即失敗並重新解析 | `FakeIpMappings`、`ForwardingPacketFilter`、`DnsPacer`、`ForwardingRelay` | `FakeIpReplayTest`（6 項，含過濾層端到端）；模擬器：修正前 hev 重啟後舊位址連到別的主機（HTTP 530 / 404），修正後連續重啟兩次舊位址仍 200 OK、新網域接續分配 | 應用進程重啟（更新安裝）後映射不保留（只在記憶體），此時舊位址改為立即拒絕而不是卡住；實機需用戶確認 |

### v0.10.3-m4p12（2026-11-16）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-129 | 轉送 socket 對改為真正的 SOCK_SEQPACKET：`createReliableSocketPair()` 其實是 SOCK_STREAM，突發時封包黏包遺失（hev 寫出 233 / 轉送層讀到 134），造成 DNS、SYN-ACK、資料段遺失與數秒重傳。改用 `Os.socketpair(AF_UNIX, SOCK_SEQPACKET)`；移除只為繞過此問題的 `SynGate`，`DnsPacer` 改為在途 64 個 + 100 ms 重送 | `ForwardingRelay`、`DnsPacer`、`ForwardingPacketFilter` | 包數比對 1316 = 1316；模擬器 20 條並發 HTTP 0.19～0.58 秒零失敗（修正前長尾 2～7 秒、每輪 1～3 條失敗）、30 條並發建連 ≤0.13 秒、30 個並發 DNS ≤0.08 秒、映射重放正常；`DnsPacerTest` 更新 | 實機未量測（adb 中斷），需用戶確認 |
| K-130 | hev 原生庫補丁：寫入遇 EAGAIN 時 poll 等待後重試（最多約 100 ms）而不是丟包；轉送執行緒提高優先級；socket 緩衝要求 8 MB | `android-kotlin/native/hev-socks5-tunnel/`、`jniLibs/*/libhev-socks5-tunnel.so`、`ForwardingRelay` | 以 NDK 30 重新編譯 arm64-v8a / x86_64，APK 內的庫與編譯產物雜湊一致 | 升級 hev 時要重新套用補丁 |
| K-131 | 詢問改為紅魔邏輯與樣式：禁止 = 到應用關閉、勾選「禁止後不再提示」= 永久禁止、允許 = 永久、僅一次 = 到應用關閉，可選限時；移除「稍後」；新增「「禁止」預設為永久」設定；懸浮窗預設貼底、全寬、背景變暗 | `PromptOverlayController`、`PromptOverlay`、`PromptPresentationPolicy`（`PromptDenyPolicy` / `PromptSheetText`）、`AppPreferences`、`SettingsScreen`、`UiStrings` | JVM 測試；模擬器：懸浮窗外觀、觸控穿透、禁止 → UntilAppExit 並在離開後回收、勾選後 → 永久 | 實機（紅魔）外觀待用戶確認 |
| K-132 | 「僅一次」「禁止」持續到應用真正關閉：授予 DUMP 後以 `getHistoricalProcessExitReasons` 偵測目標主進程結束；未授權時以背景停留時間近似（預設 10 分鐘，可設定，0 = 舊行為） | `ProcessExitWatcher`、`TargetAppSessionTracker`、`AppPreferences`、`SettingsScreen`、`AndroidManifest.xml` | JVM 測試；模擬器：背景 20 秒回來不再詢問、劃掉 Chrome 後約 2 秒移除規則 | 實機 DUMP 授權待驗證 |
| K-133 | 關閉偵測一鍵授權：設定頁「授權」按鈕先用 Shizuku（`IShizukuService.newProcess` 執行 `pm grant`），再試 root，都沒有時顯示引導與可複製的 adb 命令 | `DumpPermissionGranter`、`SettingsScreen`、`AndroidManifest.xml`（ShizukuProvider）、`build.gradle.kts` | 模擬器：無工具時的引導、複製、授權後狀態即時更新 | Shizuku / Root 實際授權待實機驗證 |
| K-134 | 詢問按鈕單行：限時按鈕改為「60分鐘」，按鈕文字放不下時自動縮小 | `PromptOverlayController`、`PromptOverlay`、`UiStrings` | 模擬器 zh-TW 畫面確認 | 實機待確認 |
| K-135 | 桌面掛件（防護狀態、今日概覽、快捷開關、流量排行）、控制中心磁貼（模式、全部阻止、勿擾、專注，加上原有保護）、長按捷徑；共用 `QuickActions`，削弱防護的操作在應用鎖下先驗證（含修正原保護磁貼） | `platform/quick/*`、`widget/NcWidgets.kt`、`AndroidManifest.xml`、`res/xml/widget_*.xml`、`proguard-rules.pro` | JVM `QuickLogicTest`；模擬器：四款掛件上桌與操作、應用鎖驗證、捷徑、磁貼 | 實機啟動器外觀待確認 |

### v0.11.1-m4p13（2026-11-17）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-136 | 常駐通知標題可自訂；留空時使用目前語言的預設「保護已開啟」，儲存後立即刷新通知 | `AppPreferences`、`AppBackend`、`VpnForegroundNotifier`、`SettingsScreen`、`UiStrings` | JVM `NotificationTitleTest`；Debug / Release / AAB 構建 | 自訂只套用到「保護已開啟」，啟動中仍用預設「保護正在啟動」 |

### v0.11.2-m4p14（2026-11-17）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-137 | 鎖屏斷網白名單：勾選的應用在鎖屏時仍可聯網，解鎖後回到正常路由 | `AppPreferences.lockScreenWhitelist`、`VpnEngine.replanNow`、`RoutingPlanner.plan`、`AppRoutingReason.LockScreen`、`SettingsScreen` | JVM 測試 / Debug / Release 構建 | 白名單只在鎖屏斷網開啟時生效；專注模式仍用自己的允許清單 |

### v0.12.0-m4p15（2026-11-18）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-138 | 底部工具欄六種樣式：經典純色 / 經典磨砂 / 懸浮純色 / 懸浮玻璃 / 懸浮磨砂 / 懸浮透明；設定頁可切換並即時生效 | `ToolbarStyle`、`AppPreferences.toolbarStyle`、`ThemeController`、`AppRoot.AppBottomBar`、`SettingsScreen` | JVM 測試 / Debug / Release 構建 | 磨砂與玻璃為半透明近似，Android 同視窗沒有真正的背景模糊 API |

### v0.12.1-m4p16（2026-11-18）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-139 | 懸浮工具欄改為覆蓋在頁面內容上，列表可從欄後滾過；各主頁 LazyColumn 底部自動增加 104dp 間距，最後一項仍可完整滾出 | `AppRoot`、`BottomBarInsets`、`Overview/Apps/Rules/Logs/SettingsScreen` | JVM 測試 / Debug / Release 構建 | 仍未做真正的背景模糊 |

### v0.13.0-m4p17（2026-11-18）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-140 | 磨砂 / 玻璃工具欄接入 Haze 1.6：`hazeSource` 擷取內容、`hazeEffect` 做真實背景模糊；玻璃樣式加高光與邊框；經典磨砂也改為覆蓋式全寬模糊 | `AppRoot.AppBottomBar`、`build.gradle.kts`（Haze 1.6.0） | JVM 測試 / Debug / Release 構建 | Android 12+ 才有真實模糊；沒有 iOS Liquid Glass 的折射透鏡 |

### v0.13.1-m4p18（2026-11-18）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-141 | 三種半透明工具欄材質改為各自獨立：磨砂（高不透明度 + 26dp 模糊、霧面無高光）、玻璃（36dp 大模糊 + 低不透明度 + 垂直漸變高光 + 頂部反光條 + 受光漸變邊框 + 18dp 陰影）、透明（完全不模糊，只留 20% 底色，後方內容清晰）。修正 `clip` 必須在 `hazeEffect` 之前，避免模糊圖層畫出方角 | `AppRoot.AppBottomBar` | JVM 測試 / Debug / Release 構建 | 真機視覺未驗證；Android 12 以下走 `fallbackTint` 遮罩 |

### v0.13.2-m4p19（2026-11-18）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-142 | 玻璃質感重做：模糊層改用 `BlurredEdgeTreatment(30dp)` 自行裁圓角；玻璃底色降到 0.08 / 0.18，加內圈白光、頂部鏡面反光與底部內陰影；透明改為 10dp 輕度模糊 + 44% / 52% 底色，標籤不再壓在清晰內容上；圓角 30dp、外距 16/10dp | `AppRoot.AppBottomBar` | API 35 模擬器淺色 / 深色視覺對比、JVM 測試、Release 構建 | 真機（Android 12）未驗證；Haze 2 Glass 需要 API 33+ 且要升 AGP 9.1 / compileSdk 37，暫不採用，詳見 android-kotlin.md §59.3 |

### v0.13.3-m4p20（2026-11-18）

| ID | 功能 | 實作位置 | 驗證 | 備註 |
|---|---|---|---|---|
| K-143 | 鎖屏允許聯網應用選擇框新增「全選 / 清除選擇」與「規則中允許連網」過濾：可只顯示目前已啟用規則中允許聯網的應用列表，並一鍵全選或取消當前篩選結果 | `VpnRoutingPlanner.allowedByRulePackages/filterPickerApps/selectAllVisible/clearVisibleOrAll`、`RuleSet.findAppRule`、`AppBackend.allowedByRulePackages`、`SettingsScreen.DirectAppsDialog`、`UiStrings` | JVM 測試（`RoutingPlannerTest`、`ReleaseNotesTest`）+ API 35 模擬器（`nc35`）端到端驗證（允許 2 個應用 → 彈框顯示 `全部 (60)` / `規則中允許連網 (2)` → 過濾後僅顯示 2 個應用 → 點全選變為 `已選 2 個` 並儲存顯示 `2 個應用`） | 真機待驗證 |
| K-144 | 項目內置固定共享簽名密鑰（`android-kotlin/keystore/shared-release.jks`，解決跨機器 / CI 打包簽名衝突問題）與 GitHub Actions / Gitee Go 在線 APK 自動打包及 GitHub Releases 發布 | `android-kotlin/keystore/shared-release.jks`、`app/build.gradle.kts`、`.github/workflows/android-kotlin.yml`、`.gitee/workflows/android-kotlin.yml` | `keytool` 證書指紋驗證（SHA-256 `CF:66:52:C5...`）、Release 構建通過 | 待推送至 GitHub / Gitee 觸發雲端流水線 |
| K-145 | 設定頁面佈局精簡、品牌定名 `PocketGate` 與自動雙源更新（2026-11-19）：應用與打包產物定名為 `PocketGate`（`PocketGate-<version>.apk`）；檢查更新改為 `UpdateChecker.checkAuto`（優先走 GitHub Releases 並探測下載連通性，失敗或不可達時自動回退至 Gitee Releases，移除設定頁手動選擇更新源）；主設定頁由 13 張卡片整合為 6 張高內聚卡片，新增 `PromptSettingsScreen`、`ForwardingSettingsScreen`、`ProfileScheduleSettingsScreen` 三個二級頁，工具列樣式改為下拉選單，通知標題與可信發布者改為點擊彈窗 | `SettingsScreen.kt`、`PromptSettingsScreen.kt`、`ForwardingSettingsScreen.kt`、`ProfileScheduleSettingsScreen.kt`、`AppRoot.kt`、`UpdateChecker.kt`、`UiStrings.kt`、`strings.xml`、`.github/workflows/android-kotlin.yml`、`.gitee/workflows/android-kotlin.yml`、`scripts/android-kotlin.ps1` | JVM 測試（`UpdateCheckerTest` 雙源自動回退、`ReleaseNotesTest` 等全部通過）、Debug APK 構建通過 | 真機與模擬器界面效果待用戶驗證 |
| K-146 | 懸浮詢問面板與應用內詢問卡片美化（2026-11-19）：頂部新增應用識別列（46dp 圓角應用圖示 + 加粗突出應用名稱 + 包名副標題 + 右側網路類型膠囊標籤），詢問正文中將目標應用名稱加粗並以主題色高亮（`SpannableString` / `AnnotatedString`），目標域名與連線數放入圓角底色框中 | `PromptOverlayController.kt`、`PromptOverlay.kt`、`ReleaseNotes.kt` | JVM 測試（`ReleaseNotesTest`、`PromptDenyPolicyTest` 等全部通過）、Debug APK 構建通過 | 真機與模擬器界面效果待用戶驗證 |
| K-147 | 應用內直接下載更新並拉起原地覆蓋安裝（2026-11-19）：`UpdateChecker.downloadApk` 支援應用內下載 APK、即時回報 0–100% 進度、校驗 ZIP/APK 魔數（`PK\x03\x04`）並在主連結失敗時自動回退至 Gitee 備用連結；下載完成後透過 `FileProvider`（`cache/updates/`）與 `REQUEST_INSTALL_PACKAGES` 自動拉起系統安裝器原地覆蓋升級（未授予安裝未知應用權限時引導至設定並在返回時自動繼續安裝） | `UpdateChecker.kt`、`SettingsScreen.kt`、`AndroidManifest.xml`、`res/xml/file_paths.xml`、`UpdateCheckerTest.kt` | JVM 測試（`UpdateCheckerTest.downloadsApkInAppAndFallsBackToMirrorOnFailure`、`ReleaseNotesTest` 通過）、Release APK 構建通過 | 真機下載與覆蓋安裝流程待用戶驗證 |
| K-148 | 修正靈動島文字發虛模糊問題（2026-11-19，v0.13.5）：原本 `SpeedIslandController` 無條件開啟 `setShadowLayer(4f, 0f, 1f, contrastShadow)`，且預設主題綠的亮度 `luma=92.8 <= 140` 觸發了 `Color.WHITE` 的 4px 純白高斯模糊光暈，導致深色膠囊上的小字嚴重發虛重影；改為有底色（`opacity > 0.15f`）時完全關閉陰影並啟用 `SUBPIXEL_TEXT_FLAG`，僅在全透明背景時保留 1.5px 半透明微陰影 | `SpeedIslandController.kt`、`ReleaseNotes.kt` | JVM 測試（`ReleaseNotesTest` 等全部通過）、Release APK 構建通過 | 真機視覺待用戶驗證 |
| K-149 | 應用內更新下載加入 GitHub 國內加速鏡像（`ghfast.top`）與 302 重定向解析兜底（2026-11-19，v0.13.6）：`isUrlReachable` 追蹤重定向確認 `release-assets.githubusercontent.com` 可達性；當 GitHub 直連與 Gitee 均不可用時自動透過 `https://ghfast.top/` 在應用內高速下載 APK，並移除失敗時自動跳轉瀏覽器的行為 | `UpdateChecker.kt`、`SettingsScreen.kt`、`ReleaseNotes.kt` | JVM 測試（`UpdateCheckerTest`、`ReleaseNotesTest` 通過）、Release APK 構建通過 | 真機免代理應用內下載待用戶驗證 |
| K-150 | 全新 PocketGate「滑動暗門護盾」自適應矢量 Logo 上線（2026-11-19，v0.13.7）：採用方案 A 設計，黑曜石綠漸層底色 + 護盾外框內左右錯位滑動暗門葉片 + 垂直數據流與 `#C9F45A` 判決核心；同步新增 Android 13+ `<monochrome>` 單色主題圖示並更新 `ic_shield` 通知欄/磁貼圖示 | `ic_launcher.xml`、`ic_launcher_background.xml`、`ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml`、`ic_shield.xml`、`colors.xml`、`ReleaseNotes.kt` | JVM 測試（`ReleaseNotesTest` 通過）、Release APK 構建與 aapt2 資源編譯通過 | 真機桌面圖示與單色主題效果待用戶驗證 |
| K-151 | 現代視覺主題引擎、總覽旗艦狀態卡片與主題預覽選擇器（2026-11-19，v0.13.8）：新增 `AppThemeStyle` 與 `AppVisualTokens`（`LocalAppVisualTokens`），提供經典 / 黑曜石極客 / 極簡霜白 / 極光流光 / 純黑省電 / 系統取色（Android 12+ Material You）6 套風格與全域環境極光光暈；`MainActivity` 自動依畫布明暗同步狀態欄與導覽列圖示對比度；升級總覽頁頂部防護卡片為帶 `ic_shield` 護盾與狀態漸層的旗艦卡片；指標與即時網速啟用等寬數字（`tnum`）；設定頁新增橫向縮略圖卡片選擇器 | `Enums.kt`、`AppPreferences.kt`、`ThemeController.kt`、`AppBackend.kt`、`Theme.kt`、`MainActivity.kt`、`AppRoot.kt`、`Components.kt`、`OverviewScreen.kt`、`SettingsScreen.kt`、`UiStrings.kt`、`ReleaseNotes.kt` | JVM 測試、Release APK 構建通過 | 真機各主題風格與 Haze 玻璃折射視覺效果待用戶驗證 |
| K-152 | 逐連線轉發與外部 SOCKS5 代理深度優化、深色主題文字對比修復及 Gitee 流水線修復（2026-11-19，v0.13.9）：修復 `Scaffold(Color.Transparent)` 與自訂卡片底色在深色主題下因 `contentColorFor` 退回 `Color.Black` 導致文字看不清的問題；`DnsPacer` / `ForwardingRelay` 改為事件驅動休眠喚醒（空閒零 CPU 喚醒）；修正 `startForwarding` 中 `filter.reset()` 時序並同步清空 `DnsPacer`；`VpnTunnelConfig.equivalentTo` 忽略 `upstreamUdp` 使 UDP 開關無感即時生效；UDP 快速路徑擴充為 8 槽位扁平陣列並改用單調時鐘；外部代理切入立即探活、息屏暫停、本機代理漏選代理應用時防回環預警、代理選擇器置頂常見代理、總覽頁新增異常時一鍵切回直連；修復 `.gitee/workflows/android-kotlin.yml` 的 `SIGPIPE` 與 4Gi 容器 OOM 問題 | `Theme.kt`、`AppRoot.kt`、`Components.kt`、`OverviewScreen.kt`、`DnsPacer.kt`、`ForwardingRelay.kt`、`ForwardingPacketFilter.kt`、`VpnTunnel.kt`、`VpnEngine.kt`、`VpnForwardingTransport.kt`、`AppBackend.kt`、`ForwardingSettingsScreen.kt`、`SettingsScreen.kt`、`UiStrings.kt`、`DnsPacerTest.kt`、`ForwardingTest.kt`、`.gitee/workflows/android-kotlin.yml` | JVM 測試（含新增多槽位 UDP、事件喚醒、回環偵測測試）、Release APK 構建通過 | 真機深色主題文字可讀性、外部代理穩定性與 Gitee 雲端流水線待驗證 |
| K-153 | FlClash 級現代 Bento 雙列儀表盤、即時網速平滑波形圖、懸浮主控按鈕與平滑頁面切換（2026-11-19，v0.14.0）：新增 `SpeedChartCard`（36 點滾動上下行貝茲曲線 + 漸層填充 + 峰值標註）與 `BentoTile`（圓角圖示徽章 + 等寬大數字 + 互動高亮）；重構總覽頁為雙列 Bento 儀表盤 + 右下角形變懸浮主控 FAB + 配置文件 `ModalBottomSheet` 底部抽屜 + Top 3 流量進度條；底部導覽列換用 `Icons.Rounded` 並為 5 個主 Tab 加入 `Crossfade` 切換動效，完整相容 6 套視覺主題 | `SpeedChartCard.kt`、`Components.kt`、`OverviewScreen.kt`、`AppRoot.kt`、`ReleaseNotes.kt` | JVM 測試、Release APK 構建通過 | 真機 6 套主題下的波形圖、Bento 卡片與 FAB 視覺手感待用戶驗證 |
| K-154 | 底部導覽列主題高亮與物理彈簧動效升級（2026-11-19，v0.14.1）：選中圖示與標籤以主題 `primary` 高亮；以自訂滑動膠囊取代僵硬 `NavigationBar` 指示器（`spring(dampingRatio = 0.62f, stiffness = 420f)` 彈性滑動）、圖示 Q 彈縮放上浮（`dampingRatio = 0.45f`）與輕微觸覺回饋；5 個主頁切換改為帶方向的彈性 `AnimatedContent` 滑入縮放過渡 | `AppRoot.kt`、`ReleaseNotes.kt` | JVM 測試、Release APK 構建通過 | 真機滑動膠囊與彈簧手感待用戶驗證 |
| K-155 | 深度待機省電優化與通知欄護盾圖示鏤空修復（2026-11-19，v0.14.2）：將 `TargetAppSessionTracker`（2s）、`FocusController`（5s）、`QuietPeriodTracker`（5s）與 `FocusScheduler`（30s）4 處常駐輪詢全部改為事件驅動掛起（空閒零喚醒、息屏降頻），待機喚醒從 ~3,360 次/小時降至接近 0；為 `ic_shield.xml` 補上 `android:fillType="evenOdd"` 修復狀態欄與通知欄圖示被填成實心白盾而看不見內部滑動暗門的問題 | `TargetAppSessionTracker.kt`、`FocusController.kt`、`QuietPeriodTracker.kt`、`FocusScheduler.kt`、`ic_shield.xml`、`ReleaseNotes.kt` | JVM 測試、Release APK 構建通過 | 真機待機耗電與狀態欄/通知欄新圖示顯示待用戶驗證 |
| K-156 | 修復應用分身（vivo / MIUI user 999）規則繼承與識別、移除易誤觸 FAB 並修復靈動島狀態欄取色（2026-11-19，v0.14.3）：`VpnRoutingPlan.find(uid)` 與 `AppCatalog.packagesForUid(uid)` 對多使用者 UID（`>= 100000`）自動回退 `uid % 100000` 匹配主應用 `appId`；總覽頁移除右下角懸浮開關、恢復頂部旗艦防護卡片右側獨立 `Switch` 防誤觸；`SpeedIslandController` 改用系統真實深色模式與 `WallpaperColors.primaryColor.luminance()` 兜底判斷暗/亮桌布文字顏色 | `Models.kt`、`AppCatalog.kt`、`OverviewScreen.kt`、`SpeedIslandController.kt`、`ForwardingTest.kt`、`ReleaseNotes.kt` | JVM 測試（含 `cloneAppUidInheritsPrimaryUserRoutingDecision`）、Release APK 構建通過 | 真機 vivo 微信分身放行與靈動島黑/白桌布變色待用戶驗證 |
| K-157 | 修復懸浮玻璃底部導覽列兩端方形尾巴（2026-11-19，v0.14.4）：在 `shadow(..., clip = false)` 之後對懸浮導覽列加上 `Modifier.clip(shape)` 並將玻璃 `HazeStyle.backgroundColor` 設為 `Color.Transparent`，消除 Haze 著色與模糊層在圓角外露邊，並將滑動指示器改為同心 `24dp` 圓角 | `AppRoot.kt`、`ReleaseNotes.kt` | JVM 測試、Release APK 構建通過 | 真機懸浮玻璃圓角效果待用戶驗證 |
| K-158 | 極簡新版 PocketGate Logo + 通知欄彩色大圖示 + 應用/規則/記錄三頁全面對齊 6 套現代主題（2026-11-19，v0.15.0）：4 套矢量 Logo 精簡為極簡 3 元素護盾雙膠囊閘門；`VpnForegroundNotifier` 新增 `setLargeIcon`；`AppsScreen` / `RulesScreen` / `LogsScreen` 全面接入 `LocalAppVisualTokens`、圓角搜尋列、狀態圓點/色條與等寬膠囊標籤 | `ic_launcher_*.xml`、`ic_shield.xml`、`VpnTunnel.kt`、`AppsScreen.kt`、`RulesScreen.kt`、`LogsScreen.kt` | JVM 測試、Release APK 構建通過 | 待真機視覺驗證 |
| K-159 | 原生狀態欄即時網速小圖示模式（2026-11-19，v0.15.0）：新增 `StatusBarSpeedIcon` 以 `IconCompat.createWithBitmap` 繪製 24×24dp 雙行單色網速位圖交由 SystemUI 原生自動隨前景 App 變黑/變白；於靈動島設定頁提供獨立開關 | `StatusBarSpeedIcon.kt`、`VpnTunnel.kt`、`AppPreferences.kt`、`AppBackend.kt`、`SpeedIslandSettingsScreen.kt`、`UiStrings.kt`、`ForwardingTest.kt` | JVM 測試（含 `splitSpeed` 格式測試）、Release APK 構建通過 | 待真機狀態欄自動變色驗證 |
| K-160 | 「測試代理」三段式精準診斷（2026-11-19，v0.15.0）：`Socks5Probe.diagnose` 拆分 ① 本機埠連通 → ② SOCKS5 握手認證 → ③ 經代理存取外網與延遲，並在連上埠但出網逾時時顯示針對性排障提示 | `Socks5Probe.kt`、`ForwardingSettingsScreen.kt`、`UiStrings.kt` | JVM 測試、Release APK 構建通過 | 待真機 FlClash 診斷驗證 |
| K-161 | 6 項權限與保活紅綠燈體檢卡（2026-11-19，v0.15.0）：於設定頁整合 VPN 授權、通知、使用情況、應用列表、懸浮窗、忽略電池最佳化 6 項即時狀態與一鍵跳轉，頂部顯示 `X / 6` 就緒數 | `SettingsScreen.kt`、`AppBackend.kt`、`UiStrings.kt` | JVM 測試、Release APK 構建通過 | 待真機驗證 |
| K-162 | 「即時活躍連線」檢視與單條連線一鍵雙向 RST 斷開（2026-11-19，v0.15.0）：`ForwardingPacketFilter` 暴露 `activeConnections()` 與 `cutConnection()`，記錄頁新增「即時連線」分頁（每 2 秒自動刷新、支援關鍵字篩選與單條一鍵下發雙向 RST） | `ForwardingPacketFilter.kt`、`AppBackend.kt`、`LogsScreen.kt`、`UiStrings.kt`、`ForwardingTest.kt` | JVM 測試（含 `activeConnectionsCanBeListedAndManuallyCut`）、Release APK 構建通過 | 待真機逐連線模式實測 |
| K-046b | 正式 Release Keystore 簽章與 GitHub Actions 自動解碼簽章接入（2026-11-19，v0.15.0）：本機於倉庫外產生 25 年 2048-bit RSA PKCS12 金鑰，CI 自動解碼 `NC_ANDROID_KEYSTORE_BASE64` Secret 進行正式簽章 | `.github/workflows/android-kotlin.yml`、`build.gradle.kts` | CI 腳本與 Release 構建驗證 | 首次正式簽章安裝需先匯出規則、卸載舊版再安裝匯入 |
| K-165 | 純離線模式（禁止本應用主動聯網，2026-11-19，v0.15.1）：設定頁安全權限卡片新增獨立可選開關（預設關閉），開啟後攔截 `UpdateChecker` 檢查更新與 APK 下載，並在 `BlocklistManager.update` 入口直接短路所有定時/手動線上名單訂閱下載，保留本機檔案匯入與既有 VPN 防火牆/逐連線轉發行為不受影響 | `AppPreferences.kt`、`AppBackend.kt`、`AppContainer.kt`、`BlocklistManager.kt`、`SettingsScreen.kt`、`BlocklistSettingsScreen.kt`、`UiStrings.kt`、`BlocklistTest.kt`、`ReleaseNotes.kt` | JVM 測試（含 `BlocklistTest.offlineModeBlocksOnlineDownloadsButAllowsLocalImport`、`ReleaseNotesTest`）、正式簽章 Release APK 構建通過 | 真機開關交互與提示待用戶確認 |
| K-166 | 首次啟動分級權限初始化引導 + 總覽輕量待辦橫幅（2026-11-19，v0.15.2）：首次啟動或重裝檢測到核心/進階權限缺失時彈出一站式分組引導彈窗（核心保活：通知/電池最佳化/應用列表；進階體驗：懸浮窗/使用情況），切回頁面自動刷新狀態打勾；略過後在總覽頁頂部保留可手動關閉的精簡橫幅，開啟靈動島缺少懸浮窗權限時同步自動跳轉授權頁 | `PermissionGuide.kt`、`OverviewScreen.kt`、`SpeedIslandSettingsScreen.kt`、`AppPreferences.kt`、`AppBackend.kt`、`UiStrings.kt`、`ReleaseNotes.kt` | JVM 測試、正式簽章 Release APK 構建通過 | 真機首次啟動彈窗與授權返回自動刷新待用戶確認 |
| K-167 | 修復設定頁「顯示通知」開關點擊無即時重繪（2026-11-19，v0.15.3）：原本直接將 `SwitchRow.checked` 綁定至非可觀察的 `SharedPreferences` 屬性 `preferences.notificationsEnabled`，改用 `remember(resumeKey) { mutableStateOf(...) }` 同步狀態後點擊立即回饋 | `SettingsScreen.kt`、`ReleaseNotes.kt` | JVM 測試、Release APK 構建通過 | 真機秒響應待用戶確認 |
| K-168 | 底部導覽列全域下滑自動隱藏與上滑恢復（2026-11-19，v0.15.4；v0.15.5 修正）：於 `AppRoot` 以 `NestedScrollConnection.onPostScroll(consumed.y)` 監聽 5 個主頁實際滾動量（28dp 下滑死區 / 14dp 上滑喚回，短列表不誤隱、切頁自動復位），以 `graphicsLayer` 彈簧滑出淡出；v0.15.5 統一移除 `Scaffold(bottomBar)` 改為全高邊到邊畫布 + 浮層疊加，徹底消除隱藏後底部空白佔位；於設定 → 外觀新增開關（預設開啟） | `AppRoot.kt`、`ThemeController.kt`、`AppPreferences.kt`、`AppBackend.kt`、`SettingsScreen.kt`、`UiStrings.kt`、`ReleaseNotes.kt` | JVM 測試、正式簽章 Release APK 構建通過 | 真機滑動手感與 6 套主題視覺待用戶確認 |
| K-146 | 自動化發版與檢查更新閉環（2026-11-19）：`main` 分支推送即自動依 `versionName` 發布 GitHub Release（並支援配置 `GITEE_TOKEN` 自動同步 APK 至 Gitee Releases）；`UpdateChecker` 相容 `kt-v` / `v` 標籤前綴並在多 APK 附件時優先選擇 arm64 主安裝包；修正 Gitee Go 流水線 `version: '1.0'`、JDK 17 與騰訊雲 Gradle 鏡像 | `.github/workflows/android-kotlin.yml`、`.gitee/workflows/android-kotlin.yml`、`UpdateChecker.kt`、`UpdateCheckerTest.kt` | JVM 測試（`UpdateCheckerTest` 全部通過） | 推送至 GitHub / Gitee 後的雲端流水線與真機更新下載待驗證 |

### 尚未完成（待辦，不屬於完成項）

| ID | 功能 | 狀態 / 計劃 |
|---|---|---|
| K-026 | 主執行緒 PackageManager、`establish()` 失敗顯示等 P2 | 已完成（K-122） |
| K-043b | 應用鎖在修改規則 / 模式前的即時驗證 | 已完成（K-121）；之後應用鎖相關功能降低優先級，暫不再做 |
| K-045b | Baseline Profiles、arm64-only、CI 大小 / 啟動基線 | 已完成（K-124）；啟動時間未進 CI（模擬器在 CI 上耗時且雜訊大），改為本機 `scripts/android-kotlin.ps1 startup` |
| K-046b | 正式 keystore 簽名與側載更新流程實際演練 | 待用戶提供 keystore |
| K-048b | 純 IPv6 / 私人 DNS / OEM ROM 真機回歸 | 待真機 |
| K-051 | 轉發 / 逐連線模式總覽：v0.9.0 按應用判決與 mapdns；v0.9.1 條件規則；v0.9.4 內建直連出口；v0.9.5 攔截列表；v0.9.6 DNS 防繞過與 DoH；v0.9.7 切斷連線、追蹤器、NIO 出口 | M4，見 [android-forwarding-research.md](android-forwarding-research.md)；剩餘見 K-051b、K-051f |
| K-051a | mapdns 只支援 IPv4；IPv6 AAAA 回空 | 不做：應用會改用 IPv4，純 IPv6 網路一般有 464XLAT，內建出口也會在 IPv4 失敗後改用 IPv6（見 android-kotlin.md §39） |
| K-051b | 安全加固：查不到 UID 時放行；ICMP / 非 TCP-UDP / 解析失敗封包直接轉發 | 部分完成：白名單與全部阻止模式改為拒絕（K-118）；其他模式仍放行，避免誤殺系統元件 |
| K-051c | 應用自帶 DNS / DoH 不經 mapdns | 已完成（K-109～K-111：DNS 導回、阻擋 DoT / 知名 DoH）；映射位址仍無法比對遠端位址條件 |
| K-051d | 上游 SOCKS5 帳號密碼認證 | 已完成（K-119） |
| K-051e | 規則變更不會切斷既有 TCP 連線 | 已完成（K-112） |
| K-051f | 真機 FlClash 吞吐 / 耗電 / UDP / QUIC / 長時間穩定性；firestack / gVisor 評估 | 待真機 |
| K-163 | 逐連線模式按應用分流出口（同時支援內建直連與外部 SOCKS5，兼顧分流與防火牆/廣告攔截/日誌） | 待辦（P1；受限於 `libhev-socks5-tunnel.so` 單一上游全域單例且不透傳 UID，需後續評估多上游用戶態協議棧） |
| K-164 | WebDAV 加密備份與跨機規則同步；應用分身可選獨立規則管控 | 待辦（P2） |

## 3. 維護規則

1. 每完成一個使用者可見功能，追加一行 `K-xxx`；純重構若影響行為也要記錄。
2. 驗證欄位必須具體：`JVM 測試`、`模擬器`、`真機`、`待驗證`，不能只寫「完成」。
3. 每次發布 APK 時，在 `android-kotlin.md` 的版本章節記錄路徑、大小、SHA-256 和未驗證項。
4. 功能被後續版本修正時，保留原記錄，新增「修正」備註和提交號，不要覆寫歷史。
