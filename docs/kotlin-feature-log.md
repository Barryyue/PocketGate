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

### 尚未完成（待辦，不屬於完成項）

| ID | 功能 | 狀態 / 計劃 |
|---|---|---|
| K-026 | 主執行緒 PackageManager、`establish()` 失敗顯示等 P2 | 部分完成（應用列表的 SIM 查詢已提升），其餘待做 |
| K-043b | 應用鎖在修改規則 / 模式前的即時驗證 | 待做（第一版只鎖啟動與背景超過 1 分鐘） |
| K-045b | Baseline Profiles、arm64-only、CI 大小 / 啟動基線 | 待做 |
| K-046b | 正式 keystore 簽名與側載更新流程實際演練 | 待用戶提供 keystore |
| K-048b | 純 IPv6 / 私人 DNS / OEM ROM 真機回歸 | 待真機 |
| K-051 | 轉發模式 + 上游代理：Phase 0 spike 已打包（實驗）；Phase 1 起需接入 per-flow 判決、UDP/域名、真機性能 | M4，見 [android-forwarding-research.md](android-forwarding-research.md) |

## 3. 維護規則

1. 每完成一個使用者可見功能，追加一行 `K-xxx`；純重構若影響行為也要記錄。
2. 驗證欄位必須具體：`JVM 測試`、`模擬器`、`真機`、`待驗證`，不能只寫「完成」。
3. 每次發布 APK 時，在 `android-kotlin.md` 的版本章節記錄路徑、大小、SHA-256 和未驗證項。
4. 功能被後續版本修正時，保留原記錄，新增「修正」備註和提交號，不要覆寫歷史。
