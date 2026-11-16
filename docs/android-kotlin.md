# Android Kotlin 對照版：長期任務規劃

> 建立日期：2026-11-14
> 目標：用 Kotlin + Jetpack Compose 完整重寫 Android 版，先追平 2026-11-14 的 Avalonia 版功能，再產出可安裝的 **v0.1 APK**。
> 用途：保留兩套 Android 實現並行維護，作為後期技術選型（APK 體積、冷啟動、流暢度、內存、耗電、開發效率、平台整合成本）的實測對照。
> 本文件是執行計劃和狀態看板；`docs/progress.md` / `docs/progress-android.md` 只保留入口連結，避免三處狀態不一致。
> **完成項記錄：** [kotlin-feature-log.md](kotlin-feature-log.md)（每個功能必須落盤，見 AGENTS.md 完成標準第 6 條）。
> **網絡 / SIM 規劃：** [android-network-sim-plan.md](android-network-sim-plan.md)。
>
> **目前狀態（2026-11-15）：v0.2 已完成並打包。** 在 v0.1 基礎上新增應用列表權限引導、主題色（預設 + HSV 自訂）、規則按配置文件隔離、即時網速、勿擾入口與摘要通知、按網絡自動切換配置文件，並修正代碼審查發現的 P0/P1 問題。Release APK 約 3.2 MB（雙 ABI，Debug 簽名），模擬器冷啟動約 2.1 秒。真機仍需回歸 OEM 應用列表權限、自動切換、主題色與通知路徑，詳見 §11。

---

## 1. 邊界與硬性約定

| 項目 | 約定 |
|---|---|
| 代碼位置 | 倉庫根目錄的 `android-kotlin/`，獨立 Gradle 構建，**不加入** `NetworkControl.slnx` / `NetworkControl.Android.slnx` |
| 應用 ID | `com.networkcontrol.android.kotlin`，可與 Avalonia 版同時安裝、同時授權 VPN（系統一次只允許一個 VPN 生效，切換時會撤銷另一個，屬預期） |
| 顯示名稱 | `NetworkControl KT`（暫定，正式名稱確定後統一改） |
| 共用邊界 | **不引用** .NET Core / Storage；規則語義、SQLite schema、導入導出格式以現有 C# 實作為參考，兩邊透過測試向量和格式保持對齊 |
| 功能範圍 | 先追平 Avalonia 版**已實現**功能（MVP + M3 Android 批次）；Avalonia 版尚未實現的（轉發模式、懸浮詢問面板、專注模式、時間表等）不列入 v0.1 |
| 最低版本 | Android 10（API 29）；compileSdk / targetSdk 36 |
| 語言 | 界面文本跟隨系統，支援 zh-TW / zh-CN / en-US；應用內切換語言 |
| 版本 | Kotlin 版獨立版本號，起點 `0.1.0`；產物在 `artifacts/kotlin-android/` |
| 完成標準 | 每階段：Gradle build 通過 + 單元測試通過；最終：Release APK 可安裝、核心流程在模擬器或真機上可運行並記錄驗證結果 |
| 不影響現有版本 | 不改 `src/NetworkControl.*`、不改現有 Android 專案；需要參考時只讀 |

### 1.1 為什麼用獨立 applicationId

兩套實現要能並排安裝、並排比較。VPN 授權、always-on、通知權限、使用情況權限都按包名授予，獨立包名可以避免互相覆蓋；代價是兩邊的規則/設定不能直接共享，導入導出用 JSON 格式互通。

---

## 2. 功能對齊清單（v0.1 的 Definition of Done）

以下以 2026-11-14 的 Avalonia Android 版為基準。每項在 Kotlin 版完成後打勾；「對照」列記錄驗證方式。

### 2.1 核心與存儲

| 功能 | Avalonia 版現狀 | Kotlin v0.1 目標 |
|---|---|---|
| SQLite `config.db` / `activity.db` | 與桌面同一套 schema / 遷移 | 相同 schema 與 `PRAGMA user_version`，移植 4 個遷移腳本 |
| 規則模型 | 應用匹配器、條件、動作、時效、來源、配置文件、啟用狀態、命中統計 | 等價 Kotlin data model |
| 規則優先級 | 具體度計分；同具體度拒絕優先 | 相同計分規則與測試向量 |
| 篡改檢測 | 簽名者 / 平台程序 / 路徑匹配 | 等價；Android 用簽名證書 SHA-256 |
| 規則時效 | 永久 / 限時 / 直到重啟 / 直到進程退出（Once） | 等價；Android 的「直到進程退出」映射為應用會話（v0.1 先支援永久 / 限時 / 直到重啟） |
| 判決管線 | 停用 → 內置放行 → 全部阻斷 → 規則 → 兜底 | 等價階段鏈 |
| 詢問代理 | 合併、排隊、超時、暫停、稍後、撤銷 | 等價；通知 + 應用內卡片 |
| 模式 | 詢問 / 白名單 / 黑名單 / 全部阻斷 / 停用 | 等價 |
| 配置文件 | 建立 / 改名 / 刪除 / 切換 / 專屬規則 / 模式回寫 | 等價 |
| 運行時狀態 | 當前模式、全部阻斷恢復、勿擾摘要 | 等價（勿擾摘要 v0.1 可只保留資料與總覽卡片） |
| 設置 | 語言、主題、詢問超時 / 默認動作、通知、always-on 入口 | 等價 |
| 連接記錄 | 阻擋記錄聚合、篩選、詳情、原因 | 等價 |
| 流量統計 | NetworkStatsManager + UID 增量，今日 / 應用用量 | 等價 |
| 規則導入導出 | `netcontrol-rules` v1，SAF，合併 / 覆蓋預覽 | 等價；與 Avalonia 版格式互通 |
| 診斷導出 | zip：概況、日誌、規則統計、授權狀態、脫敏 | 等價（可先簡化條目，格式固定） |
| 每日備份 | 與桌面共用 Storage 備份 | v0.1 做 config.db 每日備份（保留 7 份） |

### 2.2 VPN 引擎

| 功能 | Avalonia 版現狀 | Kotlin v0.1 目標 |
|---|---|---|
| 黑洞式分流 | `VpnService` + `addDisallowedApplication` | 等價 |
| 假 DNS | 198.18.0.0/15、A 記錄、TTL 1、反查域名 | 等價 |
| 報文解析 | IPv4 / IPv6、TCP / UDP、SYN / UDP 去重 | 等價 |
| UID 歸屬 | `getConnectionOwnerUid` 後台執行 | 等價 |
| 系統 UID | < 10000 只計數、不上報 | 等價 |
| 快速失敗 | TCP RST / ICMP 不可達，每目標限流 | 等價 |
| 保護開關 | 用戶意圖與系統模式分離 | 等價 |
| 開機恢復 / always-on | BOOT_COMPLETED + 系統啟動服務 | 等價 |
| 撤銷處理 | `onRevoke` 通知 + 狀態 | 等價 |
| 網絡監控 | 默認網絡回調、Wi-Fi / 蜂窩、按網絡自動切換配置文件 | 等價（SSID 需定位權限，沒有權限時按類型） |
| 應用目錄 | 有 INTERNET 權限的應用、簽名 SHA-256、安裝廣播 | 等價 |
| 上游代理 / 轉發模式 | 未實現 | **不做**（v0.2+） |

### 2.3 平台與界面

| 功能 | Avalonia 版現狀 | Kotlin v0.1 目標 |
|---|---|---|
| 總覽 | 保護開關、模式、配置文件、一鍵斷網、勿擾摘要、今日流量、待決詢問 | 等價（Compose） |
| 應用頁 | 搜索、篩選、三態、圖標、規則摘要、用量 | 等價 |
| 記錄頁 | 阻擋記錄、篩選、詳情 | 等價 |
| 設定頁 | 語言、主題、詢問、通知、always-on、電池、數據用量、配置文件、導入導出、診斷、關於 | 等價 |
| 詢問 | 系統通知（允許 / 拒絕）、應用內底部卡片、合併、超時、稍後 | 等價；懸浮窗面板不做 |
| 快捷設定磁貼 | TileService 開關保護 / 一鍵斷網 | 等價 |
| 通知語言 | 跟隨應用內語言 | 等價 |
| 前台服務通知 | 保護中狀態、斷開入口 | 等價 |
| 三語 | zh-TW / zh-CN / en-US | 等價 |
| 明暗主題 | 跟隨系統 / 淺色 / 深色 | 等價 |
| 應用圖標 | PackageManager + 緩存 | 等價 |
| 平板 / 大字體 / TalkBack | 未專門驗證 | v0.1 做基本自適應和語義標籤，不做完整驗收 |

---

## 3. 技術架構

```
android-kotlin/app/src/main/java/com/networkcontrol/android/kotlin/
├── App.kt                     # Application：AppContainer 初始化、語言/主題、通知渠道
├── MainActivity.kt            # 單 Activity，Compose 導航
├── core/
│   ├── model/                 # Rule、AppIdentity、Profile、Settings、Verdict…
│   ├── rules/                 # RuleSet、匹配器、具體度、篡改檢測、生命週期
│   ├── pipeline/              # 判決階段鏈
│   ├── prompt/                # PromptBroker、合併、超時、會話
│   └── profile/               # ProfileService、自動切換
├── data/
│   ├── db/                    # SQLiteOpenHelper、遷移、DAO
│   ├── repository/            # RuleRepository、ActivityRepository、TrafficRepository…
│   └── transfer/              # netcontrol-rules 導入導出
├── engine/
│   ├── vpn/                   # VpnService、Tunnel、PacketParser、FakeDns、FastFail、Planner
│   ├── apps/                  # AppCatalog、UID 解析
│   ├── network/               # NetworkMonitor、TrafficSource
│   └── protection/            # ProtectionController（保護狀態機）
├── platform/                  # 通知、Tile、開機廣播、前台服務、診斷、語言
└── ui/
    ├── theme/                 # Material3 主題、顏色、字體
    ├── components/            # 共用卡片、指標、空狀態、開關
    ├── overview/              # 總覽
    ├── apps/                  # 應用
    ├── logs/                  # 記錄
    ├── settings/              # 設定
    └── prompt/                # 詢問卡片
```

- **語言/框架**：Kotlin 2.4.x、Jetpack Compose（BOM 2026.09）、Material 3、Coroutines + Flow。
- **存儲**：原生 `SQLiteOpenHelper`（不用 Room），直接沿用現有 schema / `user_version`，降低遷移和對照成本。
- **依賴注入**：手動 `AppContainer`（Application 級單例），不引入 Hilt，減少依賴和啟動成本。
- **偏好**：`SharedPreferences`（少量鍵），不引入 DataStore。
- **導航**：單 Activity + Compose 狀態導航（4 個底部頁），避免 navigation-compose 的額外依賴；需要深連結時再引入。
- **日誌**：`android.util.Log` 包裝 + 文件日誌（私有目錄，7 天輪轉）。
- **測試**：JUnit 4 + `kotlin.test`；純邏輯（規則、報文、DNS、計劃器）全部可在 JVM 測試；平台代碼用模擬器/真機驗證。

---

## 4. 階段計劃

> 時間是單人投入的粗略估計，實際以構建和驗證結果為準。每個階段都是可獨立提交的檢查點。

### K0 工具鏈與骨架（0.5 天）
- [ ] Gradle wrapper（8.14.4）、AGP 8.13.2、Kotlin 2.4.21、compileSdk 36、minSdk 29
- [ ] `android-kotlin/` 專案、Manifest、主題、空白 Compose Activity
- [ ] `scripts/android-kotlin.ps1`（build / install / logcat / publish）
- [ ] 首次 `assembleDebug` 通過
- 驗收：APK 能安裝啟動，顯示佔位頁

### K1 資料層（2 天）
- [ ] 移植 `Config/0001`、`Activity/0001~0003` schema 與 `user_version`
- [ ] DAO：profiles、settings、apps、rules、rule_history、activity、traffic
- [ ] 遷移與往返測試
- 驗收：JVM 測試用臨時 SQLite 建庫、寫入、讀取、升級

### K2 規則引擎（2 天）
- [ ] 領域模型、應用匹配器、條件、具體度、優先級
- [ ] 篡改檢測、生命週期（永久 / 限時 / 直到重啟）
- [ ] `RuleSet` 不可變快照與 `FindAppRule`
- [ ] 測試向量（與 C# 行為對照）
- 驗收：規則衝突、優先級、篡改、過期測試全部通過

### K3 判決管線與詢問代理（2 天）
- [ ] 階段鏈（停用 / 內置 / 全部阻斷 / 規則 / 兜底）
- [ ] PromptBroker：合併、排隊、超時、暫停、稍後、撤銷
- [ ] 規則生成（詢問回答 → 規則）
- [ ] 模式與運行時狀態
- 驗收：場景測試（首次聯網、允許、拒絕、超時、合併、勿擾）

### K4 VPN 引擎（3 天）
- [ ] `NcVpnService`、`VpnTunnel`、前台通知
- [ ] 報文解析、假 DNS、UID 歸屬、阻斷上報去重
- [ ] 路由規劃、模式 / 規則 / 應用變化時重建（300 ms 防抖）
- [ ] 快速失敗 RST / ICMP
- [ ] 撤銷、開機、always-on
- 驗收：模擬器/真機上 Chrome 被攔截 → 詢問 → 允許 → 恢復

### K5 應用目錄、流量、網絡、配置文件（2 天）
- [ ] AppCatalog（INTERNET 權限、簽名、安裝廣播）
- [ ] NetworkStatsManager 流量 + UID 增量
- [ ] NetworkMonitor（Wi-Fi / 蜂窩 / 以太網）
- [ ] ProfileService + 按網絡自動切換
- 驗收：應用列表、用量、配置文件切換

### K6 界面（4 天）
- [ ] 總覽、應用、記錄、設定、詢問卡片
- [ ] 三語、明暗主題、圖標、空狀態
- [ ] 導入導出預覽、診斷導出入口
- 驗收：五個頁面可操作，數據與核心一致

### K7 平台整合（2 天）
- [ ] 通知渠道、詢問通知（允許 / 拒絕）、前台服務通知
- [ ] TileService、BOOT_COMPLETED、PACKAGE_ADDED/REMOVED
- [ ] 應用內語言、主題持久化
- 驗收：通知、磁貼、重啟恢復

### K8 診斷、導入導出、備份（1.5 天）
- [ ] `netcontrol-rules` v1 導入導出（SAF）
- [ ] 診斷 zip（概況、日誌、規則統計、脫敏）
- [ ] config.db 每日備份（7 份）
- 驗收：導入導出往返、zip 可打開

### K9 測試、打包 v0.1（1.5 天）
- [ ] 單元測試全綠、Release 構建、R8 規則
- [ ] `artifacts/kotlin-android/NetworkControl-KT-0.1.0.apk`
- [ ] 對照記錄：APK 大小、冷啟動、PSS（模擬器/真機）
- [ ] 文檔：功能對照、已知限制、後續迭代

---

## 5. 驗證與對照方法

1. **功能對照**：第 2 節清單逐項打勾；Avalonia 版有、Kotlin 版沒有的列為 v0.1 已知缺口（原則上不允許核心功能缺口）。
2. **行為對照**：同一組規則 / 應用 / 模式，兩個版本在模擬器上執行相同操作，比較詢問、規則、記錄、模式結果。
3. **性能對照**：Release + 只打 arm64；`am start -W -S` 冷啟動 10 次取中位數；`dumpsys meminfo` PSS；APK 大小；列表滾動用 Macrobenchmark/Perfetto（有時間再做）。
4. **設備**：本機目前沒有連接真機；模擬器 33.1.24 可嘗試。若模擬器無法啟動，至少完成構建、JVM 測試和靜態檢查，並在進度中如實註明「未在設備運行」。
5. **不截電腦桌面**；設備驗證如需截圖用 `adb exec-out screencap`。

---

## 6. 風險與應對

| 風險 | 影響 | 應對 |
|---|---|---|
| 沒有真機 / 模擬器無法啟動 | VPN、通知、磁貼無法端到端驗證 | 先完成純邏輯測試和構建；設備項目標記待驗證，交給用戶 |
| 兩套規則語義漂移 | 對照結果不可信 | 以 C# 測試向量為基準寫 Kotlin 等價測試；導入導出格式互通 |
| Gradle / AGP / Kotlin 版本不相容 | 構建失敗 | 固定版本並提交 wrapper；先用最小模板驗證，再逐層加依賴 |
| Compose 啟動優化不足 | 達不到對照目的 | v0.1 先保證功能；基線配置、Baseline Profiles 留到 v0.2 |
| 重複實現工作量大 | 周期長 | 嚴格按階段提交；界面和平台代碼可在核心穩定後並行 |
| 與 Avalonia 版同時運行兩個 VPN | 互相撤銷 | 文檔和 App 內提示；對照測試時只開一個 |

---

## 7. 狀態看板

| 階段 | 狀態 | 提交 | 備註 |
|---|---|---|---|
| K0 工具鏈與骨架 | 完成 | — | Gradle 8.14.4 / AGP 8.13.2 / Kotlin 2.2.21；Debug / Release 均可構建 |
| K1 資料層 | 完成 | — | 相同 schema / `user_version`；規則、設定、配置文件、應用、日誌、流量倉儲；每日備份 |
| K2 規則引擎 | 完成 | — | 匹配器、條件、具體度、優先級、篡改、時效；30+ 單元測試 |
| K3 判決與詢問 | 完成 | — | 階段鏈、PromptBroker 合併/超時/暫停/稍後、規則生成 |
| K4 VPN 引擎 | 完成 | — | 黑洞分流、假 DNS、UID、快速失敗、開機/always-on/撤銷 |
| K5 應用 / 流量 / 網絡 / 配置文件 | 完成 | — | AppCatalog、NetworkStatsManager、NetworkMonitor、ProfileService |
| K6 界面 | 完成 | — | 總覽 / 應用 / 記錄 / 設定 / 詢問卡片；三語、明暗主題 |
| K7 平台整合 | 完成 | — | 通知渠道、通知按鈕、前景服務、Tile、開機廣播、應用安裝廣播、通知權限 |
| K8 診斷 / 導入導出 / 備份 | 完成 | — | `netcontrol-rules` v1、SAF、zip 診斷包、7 份每日備份 |
| K9 測試與 v0.1 | 完成 | — | 30 個 JVM 測試；Release 3.1 MB；冷啟動約 2.1 秒（模擬器） |
| v0.2 收尾 | 完成 | — | 應用列表權限、主題色、規則按配置文件隔離、即時網速、勿擾、自動切換、P0/P1 審查修正；Release 3.2 MB |

---

## 8. 每階段執行模板

1. 更新本文件狀態為「進行中」，在 `docs/progress.md` 的入口任務下寫明分支和下一步。
2. 從最新 `main` 建分支 `agent/opencode/android-kotlin-<階段>`（或在當前長期分支上按階段提交）。
3. 先寫純邏輯和測試，再接平台和界面。
4. 跑 `android-kotlin/gradlew assembleDebug` + `testDebugUnitTest`；有設備時跑 `scripts/android-kotlin.ps1 install`。
5. 更新本文件清單和狀態表；提交（Conventional Commits）。
6. 階段完成後把 `main` 保持可構建；未完成工作留在分支並寫明下一步。

---

## 9. v0.1 之後的迭代方向（不在本次範圍）

- 轉發模式 + 上游 SOCKS5/HTTP 代理（與 Avalonia 版中期主線對齊）
- 懸浮詢問面板、專注模式、時間表、鎖屏斷網、新應用提醒
- 「允許一次」會話綁定（UsageStats）
- 應用詳情頁、CSV 導出、工作資料夾、應用鎖
- Baseline Profiles、AAB、簽名與更新渠道
- 與 Avalonia 版的差分測試和性能對照報告

---

## 10. v0.1 執行結果（2026-11-14）

### 10.1 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.1.0.apk` |
| 大小 | 3,124,646 bytes（約 3.0 MB，雙 ABI；R8 + 資源壓縮） |
| SHA-256 | `1CDDE077C78246F1C4AE619561395CF6687417C37538E448FF54654A6155654C` |
| applicationId | `com.networkcontrol.android.kotlin` |
| versionName / Code | `0.1.0` / `1` |
| min / target / compile SDK | 29 / 36 / 36 |
| 簽名 | Android Debug（與 Avalonia 0.3.0 的試用包一致；正式分發前需換正式密鑰） |
| 構建命令 | `.\scripts\android-kotlin.ps1 publish` |

Debug 包使用 `.debug` 後綴，可與 Avalonia 版、Release 版並存：

```powershell
# Debug 包
.\scripts\android-kotlin.ps1 build
.\scripts\android-kotlin.ps1 install

# Release 包（目前調試簽名）
.\scripts\android-kotlin.ps1 publish
```

### 10.2 驗證記錄

環境：Android 15（API 35）模擬器 `nc35`，x86_64，軟件渲染，Android SDK 36。

| 項目 | 結果 |
|---|---|
| Debug / Release 構建 | 通過；Release R8 + 資源壓縮後 3.0 MB |
| JVM 單元測試 | 30 個通過（規則、報文、DNS、路由規劃、導入導出） |
| 首次啟動 | Release 冷啟動 5 次：2197 / 2061 / 2108 / 2090 / 2156 ms（`am start -W -S`，總時間中位數約 2.1 秒） |
| VPN 建立 | `dumpsys connectivity` 確認 `tun0`、`10.111.222.1/32`、`fd00:6e63::1/128`、DNS `10.111.222.2`、`1.0.0.0/0` / `::/0` 路由 |
| 分流名單 | `Uids` 排除自身、系統 UID 與已允許應用；Google Play 服務允許後從名單消失並重建 VPN |
| 假 DNS + 應用識別 | 被阻斷的 Google Play 服務 / Chrome 觸發詢問，目標顯示 `android.googleapis.com` / `example.com`（而不是假 IP） |
| 詢問 | 通知（允許/拒絕）+ 應用內底部卡片；合併顯示「113 個連線」；「稍後」與永久 / 1 小時 / 直到重啟選項可用 |
| 允許後行為 | 生成 1 條永久規則；規則數 1 → 2；VPN 重建；待決詢問清空；被允許 UID 離開 VPN |
| 應用頁 | 63 個應用，搜索 / 篩選 / 三態 / 圖標 / 今日用量 / 最近連線；系統應用標記 |
| 記錄頁 | 阻擋記錄按應用 / 目標 / 協議 / 原因聚合，詳情可查看遠端與本地端點 |
| 流量 | 今日下載 / 上傳總量與前三名應用；`GET_USAGE_STATS` 授權後可用 |
| 語言 | 系統英文 → 設定切換繁體中文，Activity 重建後界面即時變為台灣用語 |
| 配置檔案 | 設定頁顯示默認配置、模式與當前標記；新增 / 編輯 / 刪除入口 |
| 診斷 | 設定頁導出 zip 並打開系統分享面板；`summary.txt` / `rules.json` / `plan.txt` 已確認 |
| 三語資源 | 通知/App 名稱使用 `values` / `values-b+zh+Hans` / `values-b+zh+Hant`；界面文案由 `UiStrings` 提供 |

**未驗證 / 環境限制**：

- **Chrome 網頁加載**：模擬器的宿主網絡沒有可用的外網 DNS（關閉 NetworkControl 後 Chrome 仍 `DNS_PROBE_FINISHED_BAD_CONFIG`），因此不能把 Chrome 加載失敗歸因於本應用；VPN/DNS/詢問鏈路本身已由提示中的 `example.com` 目標和記錄證明。
- **真機**：沒有連接真機；OEM ROM、always-on VPN、開機恢復、Tile、通知權限實際彈窗、私人 DNS 需要真機回歸。
- **界面視覺**：作者只做功能截圖檢查；正式驗收請用戶看界面。
- **規則導入導出 UI**：SAF 文件選擇與預覽對話框已編譯並有單元測試，但未在模擬器上完成一次文件往返。
- **勿擾摘要**：Tracker 已接入，但沒有勿擾入口，暫無端到端路徑。

### 10.3 與 Avalonia 版的量化對照

| 指標 | Avalonia 版（0.3.0） | Kotlin v0.1 | 備註 |
|---|---|---|---|
| Release APK | 45.9 MB（雙 ABI）/ 24 MB（arm64） | **3.0 MB**（雙 ABI） | Kotlin 版未含 Avalonia / AtomUI / Skia / HarfBuzz / .NET 運行時 |
| 冷啟動（模擬器） | 約 7.5 秒 | **約 2.1 秒** | 同一模擬器、軟件渲染；真機待測 |
| 共用核心 | Core + Storage（一份 C#） | 無，Kotlin 重寫 | 規則/存儲語義靠測試向量與導入導出格式對齊 |
| 平台整合 | 需要 TouchAdapters、字體/安全區補丁 | Compose 原生 | 通知、Tile、懸浮窗、語言等更直接 |
| 維護成本 | 一套核心 + 兩個外殼 | 兩套完整實現 | 長期最大代價 |

### 10.4 下一輪建議

1. 在真機上回歸：VPN 授權、always-on、開機恢復、Tile、通知權限、OEM ROM 保活、私人 DNS、純 IPv6。
2. 補完 Avalonia 版已規劃但兩邊都未做的：懸浮詢問面板、專注模式 / 時間表、鎖屏斷網、按網絡自動切換、應用詳情頁、CSV 導出。
3. 規則導入導出做一次端到端設備驗證，並與 Avalonia 版的 `netcontrol-rules` 文件互導。
4. 若正式採用 Kotlin：換正式簽名、加 Baseline Profiles、補 Macrobenchmark / 耗電測試、建立 CI APK 大小與冷啟動基線。
5. 維持與 Avalonia 版的 conformance fixtures（規則優先級、超時、導入導出邊界），避免語義漂移。

---

## 11. v0.2（2026-11-15）

### 11.1 本輪改動

| 主題 | 內容 |
|---|---|
| 應用程式列表權限 | Manifest 宣告 `com.android.permission.GET_INSTALLED_APPS`、`com.samsung.android.permission.GET_APP_LIST`；啟動偵測到受限時彈出說明並在可用時直接 `requestPermissions`，設定頁顯示狀態並可重查；沒有權限時用包名 / UID 降級顯示，不再只顯示問號。研究與 ROM 差異見 [android-app-list-permission.md](android-app-list-permission.md)。 |
| 主題色 | 設定 → 外觀新增「主題色」：10 個預設色 + HSV 自訂選色盤（色相條、飽和度/明度面板、預覽與 HEX）；持久化，MaterialTheme 全局套用（頁面、卡片、按鈕、開關、圖表）。 |
| 配置文件規則隔離 | 修正應用頁三態規則未寫 `profile_id`、變成全域共享的 bug；現在規則寫入當前配置文件，切換配置文件會切換規則集；舊的共享應用規則啟動時遷移到默認配置文件。 |
| 即時網速 | `SpeedMonitor` 每 2 秒取 `TrafficStats` 差值；總覽顯示下載/上傳即時速率，前景通知同步顯示。 |
| 勿擾 | 總覽新增 1 小時 / 關閉入口；`QuietPeriodTracker` 改為狀態驅動 + 同步計數；結束時發摘要通知，關閉摘要會取消通知。 |
| 自動切換配置文件 | `ProfileAutoSwitcher` 以默認網絡去抖 3 秒；配置文件編輯器可選 Wi-Fi / 行動網路 / 有線 + SSID；SSID 需要定位權限，沒有權限時在編輯器引導授予。`NetworkMonitor` 改為 `registerDefaultNetworkCallback` 並忽略自家 VPN 網路。 |
| 審查修正 | 修正 P0（刪除/重命名配置文件未捕獲異常會閃退）、隧道重建競態、`detach`/`revoke` 與 `apply` 競爭、PromptBroker 對 BlockedEvent 計入待決額度、初始化/重配異常隔離、流量重複計數、日誌保留、通知權限回呼、`getConnectionOwnerUid` 的 -1、per-app 語言覆寫等。完整報告見 [kotlin-audit-2026-11-15.md](kotlin-audit-2026-11-15.md)。 |

### 11.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.2.0.apk` |
| 大小 | 3,175,494 bytes（約 3.0 MB，雙 ABI） |
| SHA-256 | `116C8AFEAE4C7E300B25703BF2E026182967DF7F1472E7B07B3AB4D4F8040955` |
| versionName / Code | `0.2.0` / `2` |
| 簽名 | Android Debug（僅供對照試用） |

### 11.3 驗證

- 30 個 JVM 測試通過；Debug / Release 構建通過；R8 + 資源壓縮正常。
- 模擬器：主題色改為紫色後全局生效；建立第二個配置文件後，默認配置文件規則數 2、切換到新配置文件規則數 0，證明規則已按配置文件隔離；勿擾入口、應用列表權限卡片、設定頁狀態均顯示正常。
- 真機：v0.1 基本功能已由用戶確認；v0.2 新增的應用列表權限引導、主題色、配置文件隔離、即時網速、勿擾與自動切換仍需用戶在真機上回歸。

### 11.4 仍待處理

- 應用列表權限在 MIUI / HyperOS / ColorOS / OriginOS / MagicOS / One UI 的實際彈窗與設定路徑需真機確認；研究文件已列出各 ROM 的權限名稱與頁面。
- 通知動作在進程被回收後失效（P2-6）；主執行緒 PackageManager、`establish()` 失敗顯示等 P2 項目見審查報告。
- 轉發模式 / 上游代理仍未實現；預研見 [android-forwarding-research.md](android-forwarding-research.md)，建議先做 Phase 0 spike。
- 真機未驗證：OEM ROM、always-on、開機恢復、Tile、私人 DNS、純 IPv6、規則導入導出設備往返。

---

## 12. v0.3（2026-11-15）

### 12.1 本輪改動

| 主題 | 內容 |
|---|---|
| 網路類型 / SIM | `NetworkContext` 增加 `subscriptionId`、`simSlotIndex`、`carrierName`、`isMetered`；`SimInfo` 與配置文件編輯器支援 SIM 選擇；`NetworkMonitor` 從 `TelephonyNetworkSpecifier` / `SubscriptionManager` 取得 SIM，忽略自家 VPN。 |
| 流量拆分 | Activity DB v4 新增 `traffic_network`；`TrafficCollector` 按 Wi-Fi / 行動網路 / SIM 查詢；無 `READ_PHONE_STATE` 時降級為行動網路合計；總覽顯示 Wi-Fi / SIM 拆分；修正跨日收集把過去流量記到今天。 |
| 網路感知規則 | `RuleConditions` 加入 `networkKinds` / `subscriptionIds` / `simSlots`；SIM 訂閱優先、卡槽作後備；`VpnRoutingPlanner` 與判決管線按目前網路套用；網路變化自動 replan；應用頁新增網路條件規則編輯器。 |
| 規則品質 | `RuleIdentity` 以應用路徑、簽章、連線條件、網路範圍建立語意鍵；新增同語意規則改為更新；啟動合併重複規則並保留命中數；新增「規則」分頁按應用分組、啟停 / 刪除；修正跨裝置匯入 Replace 會誤刪規則。 |
| 可信發布者 | 設定頁可默認允許系統應用、管理可信簽章者；應用詳情可一鍵信任發布者；明確規則優先，簽章不符不會被信任。 |
| 記錄與詳情 | 連線記錄 CSV 匯出（目前篩選 / 全部、RFC 4180、UTF-8 BOM、公式注入防護、分頁）；應用詳情顯示規則、最近目標、次數、時間與今日流量。 |
| 詢問 | 待決詢問可批量允許 / 拒絕 / 稍後，規則只發布一次；通知動作帶應用路徑，進程被回收後仍可直接建立規則。 |

### 12.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.3.0.apk` |
| 大小 | 3,274,474 bytes（約 3.1 MB，雙 ABI） |
| SHA-256 | `AC520CC1D1D432C71501652C7C5F26A44245D9F215D502D2F72D0ED8BD07C07D` |
| versionName / Code | `0.3.0` / `3` |
| 簽名 | Android Debug（僅供對照試用） |

### 12.3 驗證

- 50 個 JVM 測試通過；Debug / Release 構建通過；R8 + 資源壓縮正常。
- 模擬器：建立 Wi-Fi 條件的允許規則後，應用頁顯示「網路規則：Wi-Fi → 允許」，規則分頁按應用分組顯示；應用詳情可開啟；CSV 匯出選單可開啟 SAF；五個分頁（總覽 / 應用 / 規則 / 記錄 / 設定）正常切換，無閃退。
- 模擬器網路為有線 / 非 Wi-Fi，因此 Wi-Fi 條件規則在應用頁的「目前生效」仍顯示詢問，符合預期。
- 真機未驗證：雙 SIM 拆分、SIM 切換後 replan、CSV 保存、批量詢問、殺進程後通知動作、可信發布者對系統應用的實際範圍。

### 12.4 仍待處理

- 懸浮詢問面板（K-039）設計已完成，見 [android-overlay-prompt-design.md](android-overlay-prompt-design.md)；尚未實作。
- 專注模式 / 時間表、鎖屏斷網、允許一次、詢問內容設定、新應用提醒、DoH 提示、應用鎖、無障礙回歸。
- 性能（Baseline Profiles、啟動埋點）、正式簽名 / AAB、工作資料夾、純 IPv6 / OEM ROM 回歸、冒煙腳本。
- 轉發模式 / 上游代理仍未實現；預研見 [android-forwarding-research.md](android-forwarding-research.md)。

---

## 13. v0.4（2026-11-15）

### 13.1 本輪改動

| 主題 | 內容 |
|---|---|
| 懸浮詢問面板 | `SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY`；`PromptRouter` 成為 `PromptBroker.pending` 的唯一訂閱者，統一決定應用內卡片 / 懸浮面板 / 通知；UsageStats 增量追蹤前台 UID（Activity 級別），只在被詢問應用同 UID 前台時顯示；原生 View 面板（頂部 / 底部、時效 chips、允許一次、稍後 / 拒絕 / 允許）、hold 計時、addView 失敗退回通知。 |
| 詢問內容 | 連線詳情、說明文字、鎖屏隱藏詳情三個開關，通知與懸浮面板同步；通知改為 `PROMPT_SOFT` 安靜渠道，避免 heads-up 蓋住頂部面板。 |
| 鎖屏斷網 | `ScreenStateMonitor` 監聽螢幕 / 鎖屏；開啟後鎖屏時以 BlockAll 重新規劃，系統 UID 仍保留。 |
| 專注模式 | 時長、允許應用選擇、剩餘時間、延長 15 分鐘、結束確認；專注期間 BlockAll，只放行所選應用；狀態持久化，重啟後按結束時間校正。 |
| 專注時間表 | 按星期與時段自動進入專注（支援跨午夜）；設定頁新增 / 啟停 / 刪除；每 30 秒檢查，手動專注不被覆蓋。 |
| 新應用提醒 | `PACKAGE_ADDED` 發送通知，點擊開啟應用做決定。 |
| 私人 DNS | 偵測 `private_dns_mode=hostname`，在總覽提示域名規則可能失效。 |
| 應用鎖 | 使用系統鎖屏憑證解鎖；離開超過 1 分鐘重新鎖定；未設定鎖屏時阻止啟用。 |
| 無障礙 / 平板 | 導航圖示加內容描述；內容最大寬度 760dp 居中。 |

### 13.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.4.0.apk` |
| 大小 | 3,345,990 bytes（約 3.2 MB，雙 ABI） |
| SHA-256 | `463597974C4CB536979750817F4F1143BA4A839446A294285A53686E72863C1F` |
| versionName / Code | `0.4.0` / `4` |
| 簽名 | Android Debug（僅供對照試用） |

### 13.3 驗證

- JVM 測試通過（含 `PromptPresentationPolicyTest`、`FocusScheduleTest`、既有規則 / 網路 / CSV 測試）；Debug / Release 構建通過。
- 模擬器：Chrome 前景時懸浮面板顯示於頂部，按「允許」成功建立規則並收起；面板高度約 458px（修正文字大小誤用 dp 的問題）；設定頁顯示懸浮權限、位置、詢問內容與專注時間表；新增週一至週五 09:00–18:00 時間表成功；專注模式可開始並顯示剩餘時間與允許應用數。
- 真機未驗證：微信 / OEM ROM 的懸浮窗、IME 遮擋、鎖屏斷網、應用鎖憑證、新應用通知、私人 DNS 提示、批量詢問與通知動作進程回收。

### 13.4 仍待處理

- 應用鎖目前只鎖啟動與背景超過 1 分鐘，尚未在修改規則 / 模式 / 退出時即時驗證。
- 專注模式結束小結、AlarmManager 精確排程、重啟後時間表校正待補強。
- 批量詢問與通知動作進程回收仍需真機場景驗證。
- M3 性能、正式簽名 / AAB、工作資料夾、IPv6 / OEM 回歸、冒煙腳本；M4 轉發模式。

---

## 14. v0.5（2026-11-15）

### 14.1 本輪改動

| 主題 | 內容 |
|---|---|
| 啟動與建置 | 初始化日誌加入耗時；release 維持 R8 + 資源壓縮；新增 AAB 打包命令。 |
| 簽名 | `NC_ANDROID_KEYSTORE` / `NC_ANDROID_KEYSTORE_PASSWORD` / `NC_ANDROID_KEY_ALIAS` / `NC_ANDROID_KEY_PASSWORD` 存在時使用正式簽名，否則沿用 debug。 |
| 更新檢查 | 設定 → 關於新增「檢查更新」；讀取 GitHub latest release，版本比較，跟隨系統代理；有新版本可開啟下載頁。 |
| 工作資料夾 | 設定 → 關於顯示工作資料夾提示：VPN 只作用於目前使用者。 |
| 診斷 | summary 加入網絡類型 / SSID / SIM / 私人 DNS / IPv6 / 鎖屏斷網 / 專注 / 懸浮權限 / UsageStats。 |
| 回歸 | 新增 [android-regression-checklist.md](android-regression-checklist.md)：模擬器冒煙、真機網絡、OEM ROM、發版檢查。 |
| 冒煙腳本 | `scripts/android-kotlin-smoke.ps1`：安裝、授權 appops、啟動、進程存活、FATAL 檢查。 |
| WireGuard | 決策：不內置 WireGuard VPN；未來作為轉發模式的上游出口。 |

### 14.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.5.0.apk` |
| APK 大小 | 3,354,142 bytes（約 3.2 MB） |
| APK SHA-256 | `9E133E782C1A39CA5675424CCAB31698BEE66CCCE11CC90A142D7ABA7315FE9E` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.5.0.aab` |
| AAB 大小 | 4,298,273 bytes（約 4.1 MB） |
| AAB SHA-256 | `AADF6170D1CF5D1F6178B9C169FBAF528733893A0BD3F94BAF5A718C9D027EE3` |
| versionName / Code | `0.5.0` / `5` |
| 簽名 | Android Debug（正式簽名需設定 `NC_ANDROID_KEYSTORE`） |

### 14.3 驗證

- 60 個 JVM 測試通過；Release APK 與 AAB 構建通過。
- 模擬器冒煙腳本通過：進程存活、無 FATAL、UsageStats / 懸浮窗 appops 設定成功。
- Release APK（R8 + 資源壓縮）在模擬器安裝並啟動成功，五個分頁與專注模式卡片正常渲染。
- 模擬器：專注模式、專注時間表、懸浮面板、詢問內容、應用鎖入口、工作資料夾提示均無閃退。
- 真機未驗證：正式簽名、純 IPv6、私人 DNS、OEM ROM、微信懸浮面板、鎖屏斷網、應用鎖憑證、新應用通知、更新檢查網絡。

### 14.4 仍待處理

- Baseline Profiles、arm64-only、CI 大小 / 啟動基線。
- 正式 keystore 簽名與側載更新流程演練（需用戶提供密鑰）。
- 純 IPv6 / 私人 DNS / OEM ROM 真機回歸（清單已備）。
- 應用鎖在修改規則 / 模式 / 退出時的即時驗證。
- 轉發模式 Phase 0 尚未開始；需要 Go / NDK 工具鏈與真機吞吐 / 耗電測試。

---

## 15. v0.6.0-m4spike（2026-11-15）

### 15.1 本輪改動

| 主題 | 內容 |
|---|---|
| 專注配置文件 | 設定 → 配置文件新增「建立專注配置文件」：以白名單模式建立並複製目前規則；專注進行中可「保存為配置文件」，只把勾選的允許應用寫入新配置文件。 |
| 時間選擇 | 專注時長與時間表改用 Android 原生圓盤 `TimePickerDialog`，可自訂任意分鐘；不再使用會換行的 15/30/60/120 晶片。 |
| 懸浮面板位置 | 位置新增「自訂」：0–100% 垂直比例；設定頁提供可上下拖動的面板預覽，儲存後實際懸浮面板按比例定位。 |
| M4 Phase 0 | 內建 `hev-socks5-tunnel`（MIT）原生庫（arm64-v8a / x86_64）；VpnService TUN fd 可交給原生隧道；設定頁可啟用實驗轉發模式，指定本機 SOCKS5 host/port/UDP。 |

### 15.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.0-m4spike.apk` |
| APK 大小 | 4,105,809 bytes（約 3.9 MB） |
| APK SHA-256 | `3C5DE1BAF063258BF36AA972D178C279A0CE6E2CDE6D27170E33F68BD5C59009` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.0-m4spike.aab` |
| AAB 大小 | 4,644,928 bytes（約 4.4 MB） |
| AAB SHA-256 | `FC423C2D2E019A45616AD9E5D35C462CE9552810F5787E3FE9EFB722DC661812` |
| versionName / Code | `0.6.0-m4spike` / `6` |
| 簽名 | Android Debug（正式簽名需 `NC_ANDROID_KEYSTORE`） |

### 15.3 驗證

- 60 個 JVM 測試通過；Release APK / AAB 構建通過。
- 模擬器：自訂懸浮位置拖動 15% → 82% 並保存；專注時長按鈕打開原生圓盤（00:30）；轉發設定頁顯示「原生隧道可用」；一鍵建立「專注」白名單配置文件並切換為 Active。
- 模擬器轉發：`hev-socks5-tunnel` 載入成功、TUN fd 啟動成功、SOCKS5 握手與會話建立；但經 Reqable + `adb reverse` 的實際 TCP/UDP 數據在 3 秒後 `io timeout`，Chrome 回 `ERR_CONNECTION_CLOSED`。**未通過端到端轉發驗證。**
- 真機未驗證：本機 FlClash/SOCKS5 轉發、UDP/QUIC、吞吐、耗電、與其他 VPN 共存、懸浮自訂位置、專注配置文件。

### 15.4 重要限制

- Phase 0 **不執行規則與詢問**：所有被捕獲的應用流量都會轉發到 SOCKS5；需要規則控制的用戶暫不要開啟。
- 目前是「所有流量走 SOCKS5」，不是最終 M4；Phase 1 才接入 per-flow 判決、域名傳遞、UDP/QUIC 與按應用控制。
- 上游建議使用裝置本機的 SOCKS5（例如 FlClash 的 127.0.0.1:7890）；遠端/LAN 上游可能被 VPN 路由環路。
- 若原生庫載入或啟動失敗，會自動回到黑洞模式並在日誌記錄。

---

## 16. v0.6.1-m4spike（2026-11-15）

### 16.1 「允許一次」語義

- 舊版：`RuleLifetime.Once` 只放行目前詢問批次中的連線（BlockedEvent 另有 60 秒兜底），不是應用會話。
- 新版：新增 `RuleLifetime.UntilAppExit`，按「允許一次」會建立**應用級暫存規則**；由 `TargetAppSessionTracker` 以 UsageStats `ACTIVITY_STOPPED` 判斷目標應用離開前台，2 秒後移除規則，下次打開會重新詢問。
- 未取得 UsageStats 權限時，規則最多保留 10 分鐘，之後回收；NetworkControl 重啟時也會消失。
- `直到重啟` 的語義保持不變：指 **NetworkControl 自身進程 / 服務重啟**，不是目標應用重啟。
- 限制：Android 一般應用無法可靠觀察其他應用的「滑掉 / 強制停止」，目前以「離開前台」近似；若使用者在應用內快速切換 Activity，2 秒寬限與 RESUMED 事件可避免誤刪。

### 16.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.1-m4spike.apk` |
| APK 大小 | 4,105,805 bytes |
| APK SHA-256 | `7C2D6E51225DE43AB1CEC9D8A441F39932D95652C427917CC1ED2128EF100DFA` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.1-m4spike.aab` |
| AAB 大小 | 4,647,953 bytes |
| AAB SHA-256 | `10CDDD91606D4C709958DA784BD993974DDDE0F25EA669ECCFBECE8A2ED96159` |
| versionName / Code | `0.6.1-m4spike` / `7` |

### 16.3 驗證

- JVM 測試新增 `UntilAppExit` 規則建立與非持久化檢查；全部測試通過。
- Debug / Release / AAB 構建通過。
- 真機未驗證：允許一次 → 離開應用 → 再次打開是否重新詢問；UsageStats 在 OEM ROM 上的 ACTIVE_STOPPED 事件差異。

---

## 17. v0.6.2-m4spike（2026-11-15）

### 17.1 本輪改動

| 主題 | 內容 |
|---|---|
| 詢問壽命 | 移除「直到重啟」選項，保留「允許一次 / 永久 / 限時」；限時時長在設定 → 詢問 → 「限時規則時長（分鐘）」配置，預設 60，可改 30 等。`UntilRestart` 僅保留給舊資料載入，不再提供新選擇。 |
| 允許一次 | 維持應用會話：目標應用離開前台 2 秒後失效；下次打開重新詢問。 |
| 速度島 | 保護開啟且螢幕解鎖時，在螢幕頂部顯示黑色膠囊，預設 `↓ 下載 / ↑ 上傳`；點擊開啟 NetworkControl；鎖屏 / 關閉保護時隱藏。 |
| 詢問面板 | 速度島開啟時，頂部懸浮詢問面板下移 50dp，避免與速度島重疊。 |

### 17.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.2-m4spike.apk` |
| APK 大小 | 4,122,189 bytes |
| APK SHA-256 | `88C2C3AAF52632BCB908068145BFF3D5AD557CEFD3E637299C9F9EE0C2ABBEF4` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.2-m4spike.aab` |
| AAB 大小 | 4,667,698 bytes |
| AAB SHA-256 | `52648343836BBC6C5D2B8BDEDB97CA04B584E16315D5CDCAD8CB0860DE789C4F` |
| versionName / Code | `0.6.2-m4spike` / `8` |

### 17.3 驗證

- JVM 測試通過；Debug / Release / AAB 構建通過。
- 模擬器：設定頁可開啟速度島；`dumpsys window` 可見 `NetworkControl speed` 視窗，overlay 權限與保護狀態正確。
- 真機未驗證：速度島在瀏海 / 挖孔 / 狀態欄下的位置、與鍵盤和其他懸浮窗的遮擋、常駐耗電、OEM ROM 限制。

### 17.4 已知限制

- 速度島與詢問面板都使用 `TYPE_APPLICATION_OVERLAY`；部分系統敏感頁面或 `setHideOverlayWindows(true)` 會隱藏它們。
- 速度島是系統懸浮窗，關閉保護或鎖屏時隱藏；若使用者撤銷懸浮窗權限，會自動消失。
- 「直到重啟」已從 UI 移除，但舊版建立過的 `UntilRestart` 規則仍會在當前 NetworkControl 進程內生效，直到進程重啟。

---

## 18. v0.6.3-m4spike（2026-11-15）

### 18.1 本輪改動

| 主題 | 內容 |
|---|---|
| 靈動島 | 原名「速度島」更名為「靈動島（速度）」；**預設開啟**，保護開啟且螢幕解鎖時顯示上傳/下載速度；設定 → 詢問顯示方式 →「靈動島（速度）」可隨時關閉。 |
| 權限刷新 | 授予「顯示在其他應用程式上層」後返回設定頁會立即刷新靈動島，不需要重啟。 |
| 位置 | 靈動島固定在螢幕頂部中央；開啟時詢問面板自動下移 50dp。 |

### 18.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.3-m4spike.apk` |
| APK 大小 | 4,122,189 bytes |
| APK SHA-256 | `B506D4A7B6F22FC68367FA2106622DCC67B1D8129BD17C54FDD9B3AB94C4C3C8` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.3-m4spike.aab` |
| AAB 大小 | 4,667,910 bytes |
| AAB SHA-256 | `E341F39E6921D09B8E63201A9DBD0E6C1A41D424A9B652711FBD8339DA95AD50` |
| versionName / Code | `0.6.3-m4spike` / `9` |

### 18.3 驗證

- JVM 測試通過；Debug / Release / AAB 構建通過。
- 模擬器：設定頁可切換靈動島；保護開啟且 overlay 權限授予時，`dumpsys window` 可見 `NetworkControl speed` 視窗；關閉設定後視窗移除（代碼路徑）。
- 真機未驗證：瀏海 / 挖孔位置、狀態欄高度、與鍵盤 / 其他懸浮窗遮擋、待機耗電、OEM ROM 限制。

---

## 19. v0.6.4-m4spike（2026-11-15）

### 19.1 vivo / iQOO 相容模式

- 偵測 `Build.MANUFACTURER` / `Build.BRAND` 含 `vivo` 或 `iqoo`，預設開啟相容模式。
- 相容模式把靈動島額外下移 16dp，放在 vivo 原子島 / 狀態欄下方，減少重疊。
- 設定 → 詢問顯示方式 → 靈動島開啟後，可看到「vivo / iQOO 相容模式」開關與「額外頂部偏移 0–48dp」滑桿。
- 詢問面板在靈動島開啟時同步下移，偏移量與速度島一致。
- **限制**：第三方應用無法接入 vivo 原子島 API；相容模式只是位置與視覺避讓，不會把內容放進系統島。

### 19.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.4-m4spike.apk` |
| APK 大小 | 4,154,961 bytes |
| APK SHA-256 | `6833810590ED314259DDCC24C07C45684FB54B58F35443B605F0EABB894CA092` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.4-m4spike.aab` |
| AAB 大小 | 4,704,855 bytes |
| AAB SHA-256 | `DBC2F354935B435D56B363AB52B0A2C28359151522AA80C2C303ECA7C4933DD3` |
| versionName / Code | `0.6.4-m4spike` / `10` |

### 19.3 驗證

- JVM 測試通過；Debug / Release / AAB 構建通過。
- 模擬器：速度島預設 frame y=84；開啟 vivo 相容後 y=126（+16dp × density），偏移滑桿可調。
- 真機未驗證：vivo / iQOO 實際原子島高度、挖孔位置、系統島展開時的遮擋；建議在真機上把「額外頂部偏移」調到不重疊的位置。

---

## 20. v0.6.5-m4spike（2026-11-15）

### 20.1 版本說明

- 設定 → 關於的版本號列可點擊，打開「版本說明」對話框。
- 顯示目前版本的新增功能、優化與修復，以及注意事項。
- 對話框可切換「歷史版本」，回看 0.1.0 起各版本的變更。
- 版本內容內建在 `ReleaseNotes`，不依賴網路；後續發版時追加一條記錄即可。

### 20.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.5-m4spike.apk` |
| APK 大小 | 4,154,961 bytes |
| APK SHA-256 | `191E38D674C4F41F3C8795D107D5F3433ED436594529DD85EDA94661DC2C0659` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.5-m4spike.aab` |
| AAB 大小 | 4,720,327 bytes |
| AAB SHA-256 | `38572E7B5B368CBC12E7F0E80358E95AC399BBBF7CF9D02AC5C4EB58AC90E6A2` |
| versionName / Code | `0.6.5-m4spike` / `11` |

### 20.3 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 模擬器：設定 → 關於點擊版本，顯示「版本說明 · 0.6.4」與新增功能 / 優化，並有「歷史版本」按鈕。
- 真機未驗證：小屏 / 大字體下的滾動與排版、TalkBack 朗讀。

---

## 21. v0.6.6-m4spike（2026-11-15）

### 21.1 本輪改動

| 主題 | 內容 |
|---|---|
| 專注與保護 | 保護關閉時按「開始專注」會先自動開啟保護（必要時彈 VPN 授權）；若使用者在專注期間關閉保護，卡片顯示「保護已關閉，專注模式不會阻止任何聯網」與「開啟保護」按鈕。 |
| 專注卡片排版 | 「延長 15 分鐘」與「結束專注」改為同一行兩欄；「保存為配置文件」另起一行全寬，修正原本三個按鈕擠在一起造成文字變形。 |

### 21.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.6-m4spike.apk` |
| APK 大小 | 4,171,341 bytes |
| APK SHA-256 | `1D9D260AA89EC0CEC87F0B8697C626BA45A53A7081F1A695625EF2F3A6F8455F` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.6-m4spike.aab` |
| AAB 大小 | 4,722,862 bytes |
| AAB SHA-256 | `7B6A69A8540FCFF910C26E8A1E8232F53E5C9808709E1EBB5E854844FF275BE4` |
| versionName / Code | `0.6.6-m4spike` / `12` |

### 21.3 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 模擬器：開始專注後保護自動切換為 On；專注卡片顯示「Remaining / Extend 15 minutes / End focus」並排，`Save as profile` 另起一行，文字不再變形。
- 真機未驗證：VPN 授權彈窗流程、不同字體縮放下的按鈕寬度。

---

## 22. v0.6.7-m4spike（2026-11-15）

### 22.1 本輪改動

| 主題 | 內容 |
|---|---|
| 靈動島位置 | 原本只有向下 0–48dp；改為水平 / 垂直各 -60..+60dp，負值向左 / 上，正值向右 / 下；預設 0 回到原本位置；提供「重設」。 |
| 靈動島透明度 | 只調整**背景**透明度（20–100%）；速度文字固定白色並加陰影，不跟隨透明度；邊框保持可見。 |
| 版面 | 專注模式移到模式切換下方，與勿擾相鄰，不再被模式切換隔開。 |
| 勿擾聯動 | 保護關閉時開啟勿擾會自動開啟保護；勿擾期間關閉保護時顯示警告與「開啟保護」。 |

### 22.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.7-m4spike.apk` |
| APK 大小 | 4,171,345 bytes |
| APK SHA-256 | `23D746F9DCA1EE4519CE1B5501649A442661CC2B0708138893572F939C1FEB8C` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.7-m4spike.aab` |
| AAB 大小 | 4,729,365 bytes |
| AAB SHA-256 | `8130F8E72CE97260F2874E99A1D80D21284251D293BDB95AAC639C04F6D0F358` |
| versionName / Code | `0.6.7-m4spike` / `13` |

### 22.3 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 模擬器：專注與勿擾已在模式切換下方相鄰；設定頁可調整水平 / 垂直偏移與背景透明度。
- 真機未驗證：負偏移是否會碰到瀏海 / 狀態欄、低透明度下的可讀性、OEM 系統島遮擋。

---

## 23. v0.6.8-m4spike（2026-11-15）

### 23.1 靈動島樣式

| 樣式 | 顯示 |
|---|---|
| 完整（Full） | `↓ 下載/s   ↑ 上傳/s`，同時顯示 |
| 循環（Cycle） | 每次速度更新輪流顯示 `↓ 下載/s` 或 `↑ 上傳/s` |
| 迷你（Mini） | 狀態欄風格：`↓1.2M ↑30K`，字體 10sp、內距更小、小綠點 |

- 設定 → 詢問顯示方式 → 靈動島開啟後可選「完整 / 循環 / 迷你」。
- 透明度只影響背景；速度文字固定白色加陰影，保持不透明。
- 水平 / 垂直偏移與 vivo / iQOO 相容模式對三種樣式都生效。

### 23.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.8-m4spike.apk` |
| APK 大小 | 4,171,345 bytes |
| APK SHA-256 | `D8B97C5306BB86CAE2E05BCDC3F7A130D09D45079EF95611801B19ED9E33D31D` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.8-m4spike.aab` |
| AAB 大小 | 4,733,409 bytes |
| AAB SHA-256 | `459DCA606903A83F19BBD6D71F1649FF9EFC74619630507239988C83D207FBD3` |
| versionName / Code | `0.6.8-m4spike` / `14` |

### 23.3 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 模擬器：設定頁可切換三種樣式；模式變更會移除並重建懸浮窗。
- 真機未驗證：循環節奏是否足夠、迷你模式在窄屏 / 大字體下的寬度、透明背景上的文字可讀性。

---

## 24. v0.6.9-m4spike（2026-11-15）

### 24.1 即時偏移與透明度

- 水平 / 垂直偏移與背景透明度改為拖動滑桿時**即時更新懸浮窗**，不需要放開或重啟。
- `SpeedIslandController` 在每次刷新時更新 `WindowManager.LayoutParams` 的 `x` / `y`，並呼叫 `updateViewLayout`。
- 設定頁的 `Slider` 使用 `onValueChange` 直接寫入並刷新；不再等 `onValueChangeFinished`。
- 文字仍固定白色加陰影，不跟隨背景透明度。

### 24.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.9-m4spike.apk` |
| APK 大小 | 4,171,345 bytes |
| APK SHA-256 | `5DD74569409BEFAF6963FE6F919B76A5CFD5DD0D99B9076048FE886578FE8905` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.9-m4spike.aab` |
| AAB 大小 | 4,733,459 bytes |
| AAB SHA-256 | `6392CE2903869DF4A2CF43CF66A8D7DCF2558E78A04A7D21703F2E3210A2666C` |
| versionName / Code | `0.6.9-m4spike` / `15` |

### 24.3 驗證

- 模擬器：拖動水平偏移滑桿，靈動島 frame x 由 335 即時變為 499，不需要放開。
- 背景透明度滑桿同樣在拖動中更新。
- 真機未驗證：實際拖動手勢、負偏移邊界與低透明度可讀性。

---

## 25. v0.6.10-m4spike（2026-11-15）

### 25.1 狀態欄模式

- 新增設定「嘗試放進狀態欄」：開啟後靈動島使用 `FLAG_LAYOUT_IN_SCREEN`、`FLAG_LAYOUT_NO_LIMITS`、`fitInsetsTypes=0`、cutout `ALWAYS`，可把 `y` 設為負值，移到狀態欄座標區。
- 垂直偏移範圍仍為 -60..+60dp；狀態欄模式下以 0 為狀態欄頂部，負值再往上。
- **系統限制**：`TYPE_APPLICATION_OVERLAY` 的層級在系統狀態欄之下，Android 通常會用狀態欄蓋住第三方懸浮窗；vivo 原子島同樣不開放給第三方。部分 ROM 在透明狀態欄或全螢幕應用下可能看得到，屬盡力而為。
- 若看不到，請關閉「嘗試放進狀態欄」，使用一般懸浮位置。

### 25.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.6.10-m4spike.apk` |
| APK 大小 | 4,171,341 bytes |
| APK SHA-256 | `A2746376061B263F6D2A20E94EFB270B0129AEC536620239C308BDA8FAD7D773` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.6.10-m4spike.aab` |
| AAB 大小 | 4,734,088 bytes |
| AAB SHA-256 | `40D4BA5524515C62CC5A5ED2E512DFE175CF9156EC488500F5FFA4F5E01E7AEA` |
| versionName / Code | `0.6.10-m4spike` / `16` |

### 25.3 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 真機未驗證：狀態欄模式在不同 ROM 的可見性；這是 Android 層級限制，不是位置計算問題。

---

## 26. v0.7.0-m4spike（2026-11-15）

### 26.1 靈動島外觀配置

- 背景透明度下限改為 0：`alpha=0` 時背景與邊框完全透明，`elevation` 也歸零，只留文字與狀態點；此時加上 `FLAG_NOT_TOUCHABLE`，不攔截狀態欄下拉與桌面手勢。
- 全透明時文字依亮度自動配黑 / 白陰影（`contrastShadow`），避免亮色桌布上看不清；狀態欄模式下文字每秒跟隨日夜模式重算。
- 文字顏色三種模式：
  - `theme`（預設）：跟隨 `ThemeController.color`，主題色改變時透過 `StateFlow` 即時刷新。
  - `statusbar`：夜間白、日間黑，近似狀態欄文字；開啟「嘗試放進狀態欄」時預設切到此模式，關閉時切回 `theme`。
  - `custom`：`ThemeColorDialog` 提供預設色盤 + HSV 自訂，套用後即時生效並持久化。
- 綠色狀態點可顯示 / 隱藏，並可自訂顏色。
- 上傳 / 下載顯示順序可互換，套用完整、循環、迷你三種樣式；循環模式的起始方向也一併反轉。
- 文字色、圓點色、顯示圓點、互換順序改動都走 `SpeedIslandController.refresh()` → 重建 / 更新視圖。

### 26.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.7.0-m4spike.apk` |
| APK 大小 | 4,204,113 bytes |
| APK SHA-256 | `A35D669326C9802A0F35CD59269FD40FBD9F54F59CCFB39DFA5DED727896473B` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.7.0-m4spike.aab` |
| AAB 大小 | 4,765,526 bytes |
| AAB SHA-256 | `7CBD0C8D820AAE2140EF8CCF8CC18BA63AA8051EFE09FA008CABAC381D468C69` |
| versionName / Code | `0.7.0-m4spike` / `17` |

### 26.3 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 模擬器（1080×2400）實測：
  - 透明度 0：背景、邊框、陰影全部消失，只剩文字。
  - 跟隨主題：主題色改為藍色後靈動島文字同步變藍。
  - 跟隨狀態欄：淺色系統下為黑字，位於狀態欄座標區可見。
  - 自訂色：調色盤選紫色後即時生效；圓點設為紅色、隱藏與顯示均正常。
  - 互換順序：`↑ … ↓ …` 與 `↓ … ↑ …` 即時切換。
  - 設定頁籌片改為 `FlowRow`，窄屏不再把「Custom color」擠成兩行。
- 真機未驗證：不同 ROM 狀態欄遮擋、全透明在淺色桌布上的實際可讀性、vivo 機型。

### 26.4 限制

- 狀態欄模式仍是盡力而為：第三方 `TYPE_APPLICATION_OVERLAY` 在系統狀態欄之下。
- 全透明時會加上 `FLAG_NOT_TOUCHABLE`，避免看不見的懸浮窗擋住狀態欄下拉與桌面手勢；此時點擊靈動島不會開啟應用，調高透明度即可恢復點擊。

---

## 27. v0.8.0-m4spike（2026-11-15）

### 27.1 靈動島獨立設定頁

- 設定 → 詢問顯示方式 → 靈動島，只保留啟用開關和「靈動島設定」入口；所有細節移到獨立頁面。
- 獨立頁面分為預覽、顯示內容、外觀、位置四組，後續新增選項直接往頁面加。
- 預覽使用與懸浮窗相同的排版（`SpeedIslandFormatter`），即時反映內容、樣式、透明度、字體、顏色與狀態點。
- 新增顯示內容：速度、今日流量、今日攔截、待處理詢問、目前網路；流量與攔截數每 60 秒從資料庫刷新。
- 新增字體大小 80%–160%（1% 步進）與字體粗細：常規（sans-serif）、中等（sans-serif-medium）、粗體。
- 網路名稱太長時用 `maxWidth=320dp` + ellipsize 收尾。

### 27.2 詢問面板

- 新增「顯示『限時允許』選項」開關（預設開）。關閉後，懸浮詢問與應用內詢問都不再顯示限時選項。

### 27.3 代理轉發修復

- **根因**：hev 設定裡的隧道位址是 198.18.0.1 / fc00::1，但 VPN 介面實際位址是 10.111.222.1 / fd00:6e63::1；回包被送到不存在的位址，造成 TCP 握手後逾時。
- 修正為使用 `FakeDnsPool` 的介面位址，並關閉 TSO / USO / jumbo offload、把 task-stack-size 提高到 86016。
- 模擬器以 Reqable SOCKS5（127.0.0.1:9000 + `adb reverse`）驗證：tcpdump 可看到完整 HTTP 請求與 Cloudflare 403 回應，證明上下行資料都經過 tun0。
- 新增「測試代理」按鈕：5 秒內完成 SOCKS5 握手並 CONNECT `www.gstatic.com:443`。
- **DNS 限制**：`adb reverse` 只轉發 TCP，SOCKS5 UDP ASSOCIATE 的 relay 位址在 PC 上時裝置無法回送；真機使用本機或區網代理時才具備 UDP DNS。若網頁打不開但 IP 能連，請開啟 UDP 轉發或配置系統私人 DNS。

### 27.4 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.8.0-m4spike.apk` |
| APK 大小 | 4,236,881 bytes |
| APK SHA-256 | `77876238B49FE4F1A166F1AF222780F640DC9070F45B565CDB5EFAF95DAC9821` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.8.0-m4spike.aab` |
| AAB 大小 | 4,819,110 bytes |
| AAB SHA-256 | `F80D9398C577006F7B101F14D487B7827286B0F591BCFE13DDEC627D86ED7CD1` |
| versionName / Code | `0.8.0-m4spike` / `18` |

### 27.5 驗證

- JVM 測試（含新增 `SpeedIslandFormatterTest`）/ Debug / Release / AAB 構建通過。
- 模擬器：獨立設定頁、預覽、內容籌片、字體滑桿、詢問限時開關均可操作。
- 轉發：模擬器 + Reqable 的 HTTP 流量已能完整往返；HTTPS/域名受 `adb reverse` 無 UDP 限制影響，真機需再驗證。
- 真機未驗證：代理 UDP DNS、不同 ROM 的懸浮窗、字體粗細渲染差異。

---

## 28. v0.8.1-m4spike（2026-11-15）

### 28.1 代理應用繞過 VPN

- **問題**：同時開 NetworkControl VPN 和代理應用時，代理應用的上游流量也會進入本 VPN，再被轉發回代理的 SOCKS 埠，形成回環；代理應用也可能因 VPN 被頂掉而暫停。
- 轉發設定新增「代理應用」選擇器（可搜尋套件），選中的應用會加入 `VpnTunnelConfig.bypassPackages`，最終呼叫 `Builder.addDisallowedApplication`。
- 設定頁顯示「Android 一次只允許一個 VPN」提示，要求代理應用關閉 TUN/VPN，只保留 SOCKS5 埠。
- 清除選擇後恢復只繞過本應用。

### 28.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.8.1-m4spike.apk` |
| APK 大小 | 4,236,877 bytes |
| APK SHA-256 | `852002378EC9EF6F8ED250AEF498B571FE2BF9CE57CBC2D651D15C68BD28802E` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.8.1-m4spike.aab` |
| AAB 大小 | 4,831,242 bytes |
| AAB SHA-256 | `2096B26492B56DB81A7B18B674FB244D2ADB195C68DB48A90C3361F81E844F46` |
| versionName / Code | `0.8.1-m4spike` / `19` |

### 28.3 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 真機未驗證：代理應用繞過後 SOCKS 埠是否保持可用、代理應用是否需要額外保活設定。

---

## 29. v0.8.2-m4spike（2026-11-16）

### 29.1 單位與文案

- 速度單位改為完整形式：`B/s`、`KB/s`、`MB/s`、`GB/s`、`TB/s`；迷你樣式也使用完整單位，不再顯示 `K` / `M` / `G`。
- 流量總量使用 `B`、`KB`、`MB`、`GB`、`TB`。
- 詢問面板的「允許一次」改為「僅一次」；`UntilAppExit` 的行為不變。

### 29.2 狀態欄文字色

- Android 沒有查詢狀態欄圖示顏色的公開 API。現在用「系統深色模式 + 桌布色調」近似：
  - 深色模式 → 白字。
  - 淺色模式 + 桌布支援深色文字 → 黑字。
  - 淺色模式 + 桌布支援深色主題 → 白字。
  - 無桌布資訊 → 依深色模式。
- 桌布變更與系統配置變更時重新計算；其餘時間每 60 秒最多查詢一次。
- 部分應用會自行把狀態欄圖示切換為深色或淺色，第三方懸浮窗無法得知；不一致時可在「文字顏色」選自訂色覆蓋。

### 29.3 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.8.2-m4spike.apk` |
| APK 大小 | 4,236,881 bytes |
| APK SHA-256 | `751A1D103B42D7CE10510CE2623F632F1E167C248B7717171C2C7E450A24D027` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.8.2-m4spike.aab` |
| AAB 大小 | 4,832,470 bytes |
| AAB SHA-256 | `F70EA2F04AADA16E857C3E0BB0A6FDC79B7FE7CD0B3F69006FAC46B069322EE8` |
| versionName / Code | `0.8.2-m4spike` / `20` |

### 29.4 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 狀態欄顏色近似需真機確認；桌布 API 在部分 ROM 可能回傳 null，此時退回深色模式判斷。

---

## 30. v0.8.3-m4spike（2026-11-16）

### 30.1 一鍵式詢問面板

- 移除「先選時長、再點允許 / 拒絕」的兩段式操作。
- 主按鈕改為三個，按下立即生效：
  - **禁止** → 永久拒絕
  - **僅一次** → 允許到應用離開前台
  - **允許** → 永久允許
- 第二行保留「稍後」；若設定開啟「顯示限時允許」，右側多一個「限時允許（60 分鐘）」，直接使用 `promptTimedMinutes`。
- 關閉「顯示限時允許」後，面板就是紅魔式三個按鈕 + 稍後。
- 應用內詢問面板與系統懸浮面板同步改成相同邏輯；`PromptOnceHint` 不再顯示。

### 30.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.8.3-m4spike.apk` |
| APK 大小 | 4,236,877 bytes |
| APK SHA-256 | `EB1CFA47C72B70D63C970A8DF4C3E8CA544558586BD4256DF303867C4EC635BA` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.8.3-m4spike.aab` |
| AAB 大小 | 4,830,314 bytes |
| AAB SHA-256 | `44997A480A00E2BD9E0394D94ECA6378A72080F8B74B8DC32D0AECA3BB5BEB04` |
| versionName / Code | `0.8.3-m4spike` / `21` |

### 30.3 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 真機未驗證：三個按鈕在窄屏 / 大字體下的換行、限時入口的點擊區域。

---

## 31. v0.8.4-m4spike（2026-11-16）

### 31.1 「稍後」靜默期

- 原因：`PromptBroker.dismiss()` 原本會把同一應用加入 10 分鐘的抑制表，期間新連線直接套用默認動作，不再彈窗。
- 改為 1 分鐘（`suppressAfterLaterMs = 60_000`）；1 分鐘後同一應用再次聯網會重新詢問。
- 保留抑制表是為了避免應用立刻重試造成彈窗連發。

### 31.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.8.4-m4spike.apk` |
| APK 大小 | 4,236,881 bytes |
| APK SHA-256 | `A0B74BA30A88450B610E989F2307162D934CFEE744626CE479E2F03C514AE521` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.8.4-m4spike.aab` |
| AAB 大小 | 4,830,516 bytes |
| AAB SHA-256 | `B29E8E205298E0AEE4500C7E6E8DC9E58156A8553BED2CA3DFB0BF6B132DA7C2` |
| versionName / Code | `0.8.4-m4spike` / `22` |

### 31.3 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 真機未驗證：稍後 1 分鐘後是否能按預期重新彈窗（取決於應用是否重試連線）。

---

## 32. v0.8.5-m4spike（2026-11-16）

### 32.1 「稍後」按前台會話判斷

- `PromptBroker.dismiss()` 不再寫入固定時間的抑制表，而是把應用加入 `sessionSuppressed`。
- `PromptRouter` 每秒檢查（僅在有會話抑制時查 UsageStats）目前前台套件：
  - 使用者按稍後時，應用仍在前台 → 保持抑制。
  - 切到桌面或其他應用 → 更新前台記錄。
  - 再次回到該應用 → `clearSessionSuppression()`，下一次連線重新詢問。
- 無「使用情況訪問」權限時無法判斷前台，退回 60 秒後自動清空抑制。
- 抑制鍵統一去掉 `android-app://` 前綴，現在是套件名稱。

### 32.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.8.5-m4spike.apk` |
| APK 大小 | 4,253,265 bytes |
| APK SHA-256 | `7D108749227FB52DD92B49F46237CE9AB5B00D6BC3F73C6BBB10B1A26EF6B301` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.8.5-m4spike.aab` |
| AAB 大小 | 4,831,759 bytes |
| AAB SHA-256 | `7CB627DDA8944292E7FA1843E8144EA6263E0ADBC68212742AC0CD3A09D697D4` |
| versionName / Code | `0.8.5-m4spike` / `23` |

### 32.3 驗證

- JVM 測試 / Debug / Release / AAB 構建通過。
- 模擬器實測：Chrome 觸發詢問 → 點稍後 → 回桌面 → 再打開 Chrome，詢問重新出現。
- 真機未驗證：UsageStats 在 OEM ROM 的前台判斷延遲。

---

## 34a. v0.9.3-m4p4（2026-11-16）：審查修补

### 34.1 修復

- **轉發失敗自動重建**：`VpnTunnel` 在轉送執行緒或虛擬網卡讀取失敗時只把狀態設為 `Failed`，而 `VpnEngine.checkForwarding()` 原本會因為「不是 Active」直接返回，導致 VPN 顯示失敗但不會重試。現在 `onStateChanged` 偵測到 `Failed` 且保護仍開啟時，會清空 `applied` 並要求重新規劃（10 秒最多一次）。
- **稍後抑制與應用內換頁**：`PromptRouter` 在前台套件暫時為 null（Activity 切換）時不再把 `lastPackage` 清空，避免把同一應用內換頁誤判成「重新進入」而撤銷稍後抑制。

### 34.2 審查結論（未修，列入待辦）

- mapdns 只支援 IPv4；IPv6 AAAA 回空，IPv6 擴展頭未解析。
- 查不到 UID 時除「全部阻止」外放行；ICMP、非 TCP/UDP、解析失敗封包直接轉發，屬安全加固空間。
- 應用自帶 DNS / DoH 不經 mapdns，域名條件與記錄拿不到域名；映射位址無法比對遠端位址條件。
- 上游 SOCKS5 只支援無認證握手，沒有帳號密碼。
- 規則變更不會切斷既有 TCP 連線（設計取捨）。
- 代理應用選擇器仍在主執行緒呼叫 `installedApps()`（K-026）。

### 34.3 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.9.3-m4p4.apk` |
| APK 大小 | 4,302,413 bytes |
| APK SHA-256 | `E0349569851516576774200BE5D2FC2C5F310F469FF694AD0BAF64A5D06137F2` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.9.3-m4p4.aab` |
| AAB 大小 | 4,885,688 bytes |
| AAB SHA-256 | `6D6C13EAE35CCF986652032D8527A73C55A0CC4A5DC926177513762973FC1E41` |
| versionName / Code | `0.9.3-m4p4` / `27` |

### 34.4 驗證

- JVM 測試（含 `ReleaseNotesTest`、`ReviewFixesTest`、`ForwardingTest`）/ Debug / Release / AAB 構建通過。
- 失敗重建與應用內換頁只做代碼審查，未在真機模擬執行緒中斷。

---

## 33. v0.9.0-m4p1（2026-11-16）：轉發模式接入防火牆

### 33.1 架構

按「只有一個 VPN 擁有者、逐連線判決、代理只是出口」的做法調整轉發模式：

```
應用 → VPN 虛擬網卡 → ForwardingRelay ─ ForwardingPacketFilter ─┬─ 允許 → socketpair → hev（lwIP）→ 本機 SOCKS5（FlClash 僅代理）
                                                               ├─ 拒絕 → RST / ICMP 不可達，記錄
                                                               └─ 詢問 → 拒絕 + 建立詢問（允許後下一次連線轉發）
```

- hev 讀寫的是 AF_UNIX SOCK_SEQPACKET socket 對（`Os.socketpair`）的一端；hev 對非 TUN fd 的 `TUNGETIFF` 失敗後使用一般 read/write，封包邊界由 SEQPACKET 保留。（v0.10.3 之前誤用 `createReliableSocketPair()`，它其實是 SOCK_STREAM，見 §46。）
- 判決沿用黑洞模式的 `VpnRoutingPlanner` 結果（規則、模式、專注、鎖屏、可信應用）：`Bypass` → 轉發，`Block` → 拒絕，`BlockAndAsk` → 拒絕並詢問。系統 UID 放行；查不到擁有者時除「全部阻止」外放行並計數。
- 只在 TCP SYN 與新 UDP 五元組上查 UID（Binder），結果緩存 10 秒（TCP）/ 60 秒滑動（UDP）；路由計畫每次重算時清空。
- DNS：VPN 的 DNS 指向 hev 映射 DNS `10.111.222.3`，A 記錄映射到 `100.64.0.0/10`，hev 以域名向 SOCKS5 發 CONNECT；過濾層監聽 hev 回覆的 DNS，把映射位址還原成域名給詢問與記錄。
- 排除名單：本應用、「代理應用」（避免回環）、「直連應用」。
- 失敗處理：hev 無法啟動 → 按路由計畫回退黑洞模式並保留提示，每 2 分鐘重試；上游 SOCKS5 每 30 秒協商一次，不可用時總覽提示（連線失敗，不會直連）。

### 33.2 交付物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.9.0-m4p1.apk` |
| APK 大小 | 4,269,645 bytes |
| APK SHA-256 | `0BC47BA255BCA1067EC3BA97FBB14CBFF3418BD1388BC3C2FB9B0F9B645C482C` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.9.0-m4p1.aab` |
| AAB 大小 | 4,860,217 bytes |
| AAB SHA-256 | `F80ACD61C0F3959E6B9B98FD28825DACA31EC88887B26FAF1EA665673F358AB3` |
| versionName / Code | `0.9.0-m4p1` / `24` |

### 33.3 驗證

- JVM 測試 83 個全部通過（新增 `ForwardingTest` 15 個）；Debug / Release / AAB 構建通過；Release APK 在模擬器啟動無崩潰。
- 模擬器（x86_64，上游為電腦上的 Reqable SOCKS5 經 `adb reverse tcp:9000`）：
  - `ping example.org` 解析為 `100.64.0.5`（mapdns 生效）；`nc example.org 80`、`nc www.baidu.com 80` 經代理回 `HTTP/1.1 200 OK`。
  - Chrome（詢問模式、無規則）連線被拒並彈出懸浮詢問，記錄帶域名；點「允許」後 Chrome 經代理載入 example.org。
  - 移除 `adb reverse` 後，健康檢查在 30 秒內記錄上游不可用。
- 未驗證：
  - 真機 + FlClash（僅代理）的吞吐、耗電、UDP / QUIC、長時間穩定性；`adb reverse` 不轉發 UDP，模擬器上無法驗證 UDP ASSOCIATE。
  - hev 啟動失敗時的黑洞回退（只做了代碼審查）。
  - 鎖屏斷網 / 專注模式在轉發模式下的實際阻擋（只有 `ForwardingPolicy` 的 JVM 測試）。
- 已知限制：mapdns 只支援 IPv4；應用自帶的 DNS（不走系統 DNS）按普通 UDP 處理；帶域名 / 端口條件的規則仍只用於記錄，轉發模式下尚未逐連線執行（v0.9.1 已補上，見 §34）；已建立的連線不會被新規則切斷。

## 34. v0.9.1-m4p2（2026-11-16）：審查修復、轉發條件規則、省電

### 34.1 改動

- **轉發模式的條件規則**：過濾層在新連線上把 UID、封包與映射域名交給 `VpnEngine.decideForwarding`。規則集含帶連線條件的規則時，用 `RuleSet.evaluate` 比對（映射位址按公網分區），第一條符合的是條件規則才覆寫路由計畫；應用級規則、可信應用、篡改檢測仍由路由計畫決定。專注、全部阻止、停用模式與系統 UID 不套用條件規則。
- **「僅一次」**：`AppForegroundState` 按 Activity 記錄 RESUMED，只有應用的最後一個 Activity STOPPED（且亮屏）才算離開；應用內切換頁面（舊頁 PAUSED → 新頁 RESUMED → 舊頁 STOPPED）不再撤銷規則。
- **審查修復**：通知動作 Toast 改在主執行緒；快速設定磁貼鎖屏時要求解鎖；舊版共享規則遷移只執行一次（`legacy_rules_migrated`）；`RuleIdentity.merge` 對臨時規則用新建立時間；黑洞模式下被丟棄的連線不再記成允許；被拦連線只查一次連線歸屬；身分緩存鍵含簽名與更新時間；不持有連線的詢問去重並限 50 條流；`PromptForegroundTracker` 不緩存查不到的 UID。
- **省電**：`RuleLifetimeManager` 睡到下一條限時規則到期（最多 60 秒寫回命中），規則變更時提前醒來；`SpeedMonitor` 關屏暫停；`PromptRouter` 沒有詢問 / 抑制時掛起在 `PromptBroker.busy`。
- **轉發性能與穩定**：已放行 UDP 流的快速路徑（不解析、不配置物件、不取鎖；2 秒有效，`invalidate()` 透過 epoch 立即失效）；過濾層例外只記錄並轉發該封包；VPN 服務被系統結束而防護仍開啟時自動重建（10 秒內最多一次）。

### 34.2 產物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.9.1-m4p2.apk` |
| APK 大小 | 4,269,645 bytes |
| APK SHA-256 | `C2CBD1823F137D59167DFB7D3A6ED2C67ABC1E9A726E20E79BFDD88790E756F0` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.9.1-m4p2.aab` |
| AAB 大小 | 4,873,091 bytes |
| AAB SHA-256 | `C0CFA864BBD32C600A842CC1882BAF1786935D166CC55AFECF231D27BDC7F482` |
| versionName / Code | `0.9.1-m4p2` / `25` |

### 34.3 驗證

- JVM 測試 93 個全部通過（新增 `ReviewFixesTest` 10 個）；Debug / Release / AAB 構建通過；Release APK 在模擬器升級安裝後啟動無崩潰。
- 模擬器：Debug 版黑洞模式啟動正常、詢問與記錄正常；升級後 `legacy_rules_migrated=true`。轉發模式（上游 Reqable）下以 sqlite 插入「Chrome 拒絕 example.org」：example.org 被拒、記錄為 Deny / Rule / 該規則 id，同時 baidu.com 經代理連線成功（Reqable 的 MITM 憑證頁）。測試規則與轉發開關已還原。
- 未驗證：真機耗電與吞吐；通知動作、快速設定磁貼、服務被殺後重建都沒有手動觸發；UsageStats 在各 ROM 上的事件順序。

## 35. v0.9.2-m4p3（2026-11-16）：版本說明多語言

- `ReleaseNote` 的每一則改為 `NoteText(en, zhHans, zhHant)`，界面用 `UiStrings.noteLanguage`（由語言標籤推導，與界面文字同一規則）取對應語言；0.1.0～0.9.1 的歷史條目全部補齊三語，原本簡繁混雜的條目已分開。
- `ReleaseNotesTest` 保證三語都有內容、英文不含中文、簡繁常用對應字不混用；以後新增版本說明時缺任何一種語言都會測試失敗。
- 產物：`artifacts\kotlin-android\NetworkControl-KT-0.9.2-m4p3.apk`（4,302,413 bytes，SHA-256 `232546CBBB2D0C75B91AFD0DA247D8FEB12D5F85BC352443C4609E0A82C2E2D7`）、同名 `.aab`（4,884,736 bytes，SHA-256 `AF44E30539DD322F95187928F10F7EDDE3861171B09E3A279A1CEA5FF33B1727`）；versionName / Code `0.9.2-m4p3` / `26`。
- 驗證：JVM 測試 97 個通過；模擬器 Release 版用 `cmd locale set-app-locales` 依次切到 en、zh-TW、zh-CN，透過 UI 樹確認本版說明與歷史版本的文字語言正確，驗證後已還原應用語言。

## 36. v0.9.4-m4p5（2026-11-16）：內建直連出口

### 36.1 設計

- 逐連線模式（原「轉發模式」）的資料路徑：虛擬網卡 → `ForwardingPacketFilter`（逐連線判決）→ socketpair → hev（lwIP + mapdns）→ SOCKS5 出口。出口現在有兩種：
  - **直接連出（預設）**：`DirectSocksServer`，在本進程內監聽 `127.0.0.1` 的隨機埠。本應用被排除在 VPN 之外，對外連線不會回到虛擬網卡；socket 與域名解析綁定到實體網路（`UnderlyingNetwork`）。
  - **經 SOCKS5 代理**：維持原本的外部代理（FlClash 等）。
- 安全：本機任何應用都能連 `127.0.0.1`，出口若不驗證就成了繞過防火牆的後門，所以一律要求 RFC 1929 帳號密碼（每次啟動隨機 128 位元，只寫入 hev 設定檔），拒絕無驗證的用戶端。
- 已允許的連線也寫入記錄（K-100），這是黑洞模式做不到的。

### 36.2 產物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.9.4-m4p5.apk`（4,318,797 bytes） |
| APK SHA-256 | `8238F00FF8411DFAE817D1BF65C93D552281360E6C5F086985E9B83E2443A4C1` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.9.4-m4p5.aab`（4,912,185 bytes） |
| AAB SHA-256 | `05F175D0CDC80CB16757EA7807C70C787B7D49354A54978343B1BE5555ED6742` |
| versionName / Code | `0.9.4-m4p5` / `28` |

### 36.3 驗證與限制

- JVM 測試 105 個通過（新增 `DirectSocksServerTest` 8 個，以真 socket 測試）；Release APK 在模擬器啟動無崩潰。
- 模擬器 Debug：開啟逐連線模式 + 直接連出，hev debug 日誌確認對內建出口的帳密驗證通過；shell 與 Chrome 按 IP 經內建出口取得 Akamai 回應，Chrome 的連線以「允許 / 規則」寫入記錄。測試後已還原設定。
- **本機模擬器的系統 DNS 不可用**（關閉 VPN 也解析不了任何域名，是開發機網路環境的問題），所以按域名的直連只有 JVM 測試，真機需要用戶驗證。
- 未驗證：真機吞吐、耗電與大量並發連線（每條 TCP 連線兩個執行緒，上限 768 個執行緒）；長期方案仍是 firestack / gVisor 或原生出口。

## 37. v0.9.5-m4p6（2026-11-16）：攔截列表

### 37.1 設計

- **來源**：社群名單由作者託管，應用在使用者裝置上直接下載，不打包進安裝包（部分名單是 GPL，轉散佈要遵守授權）。每份內建名單含鏡像（anti-AD 官網 / jsDelivr / GitHub；HaGeZi GitHub / jsDelivr），主地址失敗自動換下一個。
- **儲存與匹配**：文字名單解析後只保留域名的 64 位元 FNV-1a 雜湊，排序後二分搜尋。數十萬個域名只佔幾 MB；子域名匹配逐層去掉最左邊的標籤再查（不查頂級域名本身）。編譯結果寫成 `<id>.bin`，重啟後直接載入。
- **更新**：每小時檢查是否到期（預設 7 天）；條件請求避免重複下載；失敗保留舊名單並在 6 小時後重試。「僅 Wi-Fi」只限制自動更新，使用者手動操作（立即更新、剛勾選的名單）照常下載。
- **判決順序**（逐連線模式）：使用者的條件規則 → 攔截列表 → 路由計畫。名單只作用於本來會放行或詢問的連線，命中時直接拒絕、不詢問；使用者對該域名建立的任何允許規則都視為例外。系統核心 UID、停用模式不套用。
- **記錄**：命中名單記為 `VerdictReason.Blocklist`，規則欄寫 `blocklist:<名單 id>`；記錄頁可以一鍵為所有應用放行誤攔的域名（建立全域允許規則，屬於目前配置文件）。

### 37.2 產物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.9.5-m4p6.apk`（4,384,789 bytes） |
| APK SHA-256 | `673D196C94296402A41EB0839530945C2035C0592458EB2425BA2FEF52379872` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.9.5-m4p6.aab`（4,996,204 bytes） |
| AAB SHA-256 | `9DF1A08B9C12C6055C0E67BBE8CAB8C9711BB01C38AF6651BCCC6128BFA543A4` |
| versionName / Code | `0.9.5-m4p6` / `29` |

### 37.3 驗證與限制

- JVM 測試 120 個通過（新增 `BlocklistTest` 15 個，其中下載相關用測試內的極簡 HTTP 伺服器）；Release APK 在模擬器啟動無崩潰。
- 模擬器 Debug（逐連線 + 直接連出，名單由電腦上的本機 HTTP 服務經 `adb reverse tcp:8765` 提供）：自動下載、「立即更新」得到 304、Chrome 訪問名單域名及子域名被拒並記錄、記錄頁一鍵放行後再次訪問變為允許。測試後已刪除測試規則、名單資料、反向埠並還原設定。
- 未驗證：真機上下載內建名單（模擬器的系統 DNS 不可用，且本機到 GitHub 的連線時好時壞）；大名單（HaGeZi PRO 約 5 MB）在真機上的解析耗時與記憶體；HaGeZi / OISD 等名單在國內網路下的可用性。

## 38. v0.9.6-m4p7（2026-11-16）：DNS 防繞過與加密 DNS

### 38.1 設計

- **為什麼需要**：攔截列表與域名規則靠「連線帶有域名」才能判斷，而域名來自 hev 的映射 DNS。應用若自己解析（直連 8.8.8.8:53、DoT、DoH），連線就只剩 IP。
- **DNS 導回**：過濾層看到發往其他伺服器的 IPv4 UDP 53，就把目的位址改成映射 DNS 並記住原伺服器（以用戶端位址 + 埠為鍵，60 秒）；hev 的回覆在寫回虛擬網卡前把來源改回原伺服器。兩處都重算 IPv4 標頭與 UDP 檢查碼。TCP 53 與 IPv6 DNS 無法改寫，直接拒絕，解析器會改用其他方式。
- **加密 DNS 繞過**：853 埠一律視為 DoT / DoQ；443 埠連到知名 DoH 網域（含子域名，例如 NextDNS 的個人網址）或 IP 視為 DoH。命中時按攔截列表處理（不詢問、可用域名允許規則例外）。系統核心 UID 不套用，避免影響使用者的私人 DNS 設定。
- **內建出口的 DNS**：預設系統 DNS；可選 DoH。DoH 地址都用 IP（證書含 IP SAN），不需要先解析 DoH 伺服器。斷路器避免 DoH 被封鎖時每條新連線都等 5 秒逾時。

### 38.2 產物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.9.6-m4p7.apk`（4,417,561 bytes） |
| APK SHA-256 | `F59994D3F77DED9AF5325E10F09F6F94CB997242FA92BD4D1171E056693044E6` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.9.6-m4p7.aab`（5,016,365 bytes） |
| AAB SHA-256 | `7E5079D39AE21B611EF715A1EF460B8A5B1201C051EDEF1E51A35A7EFE1F27E0` |
| versionName / Code | `0.9.6-m4p7` / `30` |

### 38.3 驗證與限制

- JVM 測試 127 個通過（新增 `DnsProtectionTest` 7 個）；Release APK 在模擬器啟動無崩潰。
- 模擬器 Debug（逐連線 + 直接連出 + 阿里 DNS）：shell 按域名經內建出口取得 HTTP 200；Chrome 自己的 DNS 被導回，打開 example.org、wikipedia.org，記錄中 Chrome 的連線都帶域名。Google 系域名在開發機網路下解析到被污染的位址（例如 104.244.42.197）而連不上，屬於網路環境，與本應用無關。測試後已還原設定。
- 未驗證：DoT / DoH 阻擋在真機上的效果（例如 Firefox 自帶 DoH 時是否正確退回）；IPv6 網路下的 DNS 行為；各 DoH 服務在國內網路的穩定性。

## 39. v0.9.7-m4p8（2026-11-16）：切斷連線、追蹤器識別、NIO 出口與「稍後 / 僅一次」修正

### 39.1 改動

- **規則改變後切斷連線**：過濾層記住已轉發 TCP 連線的判決輸入；規則或計畫改變時重新判決，已不允許的連線標記為待切斷。待切斷集合非空時，既有連線的快速路徑才解析封包；下一個經過的封包（任一方向）觸發雙向 RST，序號取自該封包，符合 RFC 5961 的精確序號要求。
- **追蹤器識別**：Disconnect 資料由裝置下載（授權 CC BY-NC-SA 4.0，不打包）；同一域名保留所有類別，顯示時以用途為主（廣告、統計優先於指紋、郵件追蹤）。資料庫可只做識別；整類攔截需要開啟攔截列表。
- **NIO 出口**：`TcpRelay` 以單一 Selector 執行緒轉送所有握手完成的連線，每條連線只佔兩個 32 KB 緩衝區。
- **外部 SOCKS5 帳密**、**白名單查不到擁有者時拒絕**：見 K-118、K-119。
- **「稍後」**：見 K-115；**「僅一次」**：見 K-116；**啟動競態**：見 K-117。

### 39.2 產物

| 項目 | 值 |
|---|---|
| APK | `artifacts\kotlin-android\NetworkControl-KT-0.9.7-m4p8.apk`（4,433,945 bytes） |
| APK SHA-256 | `21770344FF4A07EB5BE7E28250D25417CBE8752C20549CB19080B17CA4BD6165` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.9.7-m4p8.aab`（5,052,453 bytes） |
| AAB SHA-256 | `FFE9CDE69EB52A35DDB4D3D0FC71C0BF8E6958D4BE5012341A45E2424655DA46` |
| versionName / Code | `0.9.7-m4p8` / `31` |

### 39.3 驗證與限制

- JVM 測試 138 個通過（新增 `ConnectionCutTest` 3、`TrackerTest` 5、`DirectRelayLoadTest` 1、`Socks5AuthTest` 2）；Release APK 在模擬器啟動無崩潰。
- 模擬器 Debug：每次啟動都建立隧道；按域名經內建出口（阿里 DoH）取得 HTTP 200；Chrome 瀏覽正常；追蹤器整類攔截與標註；「稍後」在應用內保持、離開後清除並重新詢問。測試後已還原 Chrome 規則、設定與名單資料。
- 未驗證：真機吞吐與耗電；「僅一次」的長時間場景（使用超過 10 分鐘、關屏超過 10 分鐘）只做了代碼審查；外部 SOCKS5 帳密只用內建出口驗證，未接真正需要帳密的代理。
- **不做的項目與理由**：
  - IPv6 域名映射：hev 的 mapdns 只支援 IPv4，AAAA 查詢回空，應用會改用 IPv4；純 IPv6 網路一般有 464XLAT 提供 IPv4，內建出口也會在 IPv4 失敗後改用 IPv6。目前沒有實際需要。
  - firestack / gVisor：會取代 hev、過濾層與出口三者，是重寫等級的工作；在真機吞吐 / 耗電量測顯示現有架構不足之前不做。

## 40. v0.9.8-m4p9（2026-11-16）：「僅一次」結束後的背景靜默

- **詢問的呈現方式**（`PromptPresentationPolicy`）：每個待決詢問都有通知；懸浮窗只在「自動模式、有懸浮窗權限、亮屏未鎖、發起連線的應用正在前台」時出現，避免後台應用的詢問擋住正在使用的應用。
- **「僅一次」的範圍**：從回答起到應用離開前台為止（所有頁面在亮屏時 STOPPED，2 秒緩衝）；應用內換頁、權限對話框、關屏 / 鎖屏都不算離開。
- **新增背景靜默**：「僅一次」因離開而撤銷後，該應用的背景連線直接拒絕並記錄、不產生詢問；回到前台即解除，下一次連線以懸浮窗詢問。這避免了「選了僅一次，應用到後台後通知不斷」。
- 產物：`artifacts\kotlin-android\NetworkControl-KT-0.9.8-m4p9.apk`（4,450,329 bytes，SHA-256 `DD5D86FA353AB748CDB9EA687428D5C47B60306E1F107B4C23D02A7460F858CB`）、同名 `.aab`（5,056,733 bytes，SHA-256 `1222F901FDF188EA28CB91096295D2C4BEA89128C050C0C42F0B0FB7AACD6981`）；versionName / Code `0.9.8-m4p9` / `32`。
- 驗證：JVM 測試 138 個通過；模擬器流程見 K-120；Release APK 啟動無崩潰。未在真機驗證（例如 Telegram 的背景推送經由 Google Play 服務，不受該應用的「僅一次」影響）。

## 41. v0.9.9-m4p10（2026-11-16）：敏感操作再驗證、背景載入、零警告、arm64 與 Baseline Profile

- **應用鎖**：敏感操作再驗證見 K-121。它只能防止別人在本應用內隨手修改、查看記錄，**無法阻止在系統設定中關閉 VPN**，所以不適合用來做兒童管控（真正強制需要裝置管理員 / MDM 的 `DISALLOW_CONFIG_VPN`）。用戶決定之後應用鎖相關功能降低優先級，暫不再做。
- **安裝包**：Release 按 ABI 拆分，`NetworkControl-KT-<版本>.apk` 為 arm64（給一般使用者），`-x86_64-emulator.apk` 只給模擬器；AAB 不拆分（商店自行下發）。
- **Baseline Profile**：`:baselineprofile` 模組以 UiAutomator 走冷啟動與各分頁；`scripts/android-kotlin.ps1 baseline` 重新生成（只用模擬器）。模擬器上量不出冷啟動差異，見 K-124；真機效果未量測。
- **CI**：`.github/workflows/android-kotlin.yml`（只在 `android-kotlin/` 變更時執行）：單元測試、Release、arm64 體積上限 5.5 MB、檢查 `baseline.prof`。尚未在 GitHub 上實際執行過。
- 本章的 34a 為 v0.9.3 一節（原與 v0.9.1 一節同為 34）。

## 42. v0.9.10-m4p11（2026-11-16）：實機效能診斷與 DNS 導回修正

- **實機量測**（vivo V2509A、Android 17、純 IPv6 行動網路、逐連線模式 + FlClash 0.9.3）：
  - 單條連線首位元組時間：經本應用 313～401 ms，直接走 FlClash 370～467 ms，沒有額外延遲。
  - 20 條並發：經本應用 3 條失敗、其餘 0.34～9.4 秒；直接走 FlClash 全部 0.29～0.98 秒。
  - 並發時沒有連線卡在 SYN-SENT，卡的是解析：每批 5～20% 的解析多等 3 秒（Android 解析器重送間隔），即回覆遺失。
  - 另外觀察到 FlClash 曾停止接受連線（監聽埠 Recv-Q 129），推測被 vivo 後台管理凍結，需把 FlClash 設為允許後台運行。
- **原因與修正**：見 K-125。
- 量測方法：shell 的 `$(( ))` 是 32 位整數，`date +%s%N` 相減超過約 2.1 秒會溢出成負數；改用 `/proc/uptime`（10 ms 精度）。
- 修正版已安裝到實機，複測待 adb 重新連線後進行。

## 43. v0.10.0-m4p12（2026-11-16）：更新來源可配置

- 更新檢查不再寫死 GitHub：設定 → 關於可選 GitHub / GitLab / Gitee / 自訂 HTTPS JSON。
- GitHub / Gitee 填 `owner/repo`，GitLab 填 `group/project`（API 會做 URL 編碼），自訂填回傳 `version`、`url`、可選 `apk` 的 HTTPS JSON。
- 有 APK 附件時優先打開 APK 下載連結，否則打開 release 頁面；只允許 HTTPS，公開請求仍跟隨系統代理。
- 未實作自動下載 / 安裝；Gitee 未登入 API 有速率限制，GitLab 目前固定 gitlab.com。

### 43.1 交付物

| 項目 | 值 |
|---|---|
| APK (arm64) | `artifacts\kotlin-android\NetworkControl-KT-0.10.0-m4p12.apk` |
| APK 大小 | 4,303,934 bytes |
| APK SHA-256 | `07E68E7A570D79DAC9DA9334BCD80D3A8C0B4A7E2709685A9888D8FADC6BCED5` |
| APK (x86_64) | `artifacts\kotlin-android\NetworkControl-KT-0.10.0-m4p12-x86_64-emulator.apk` |
| AAB | `artifacts\kotlin-android\NetworkControl-KT-0.10.0-m4p12.aab` |
| AAB 大小 | 5,156,339 bytes |
| AAB SHA-256 | `C93FCB953B16B664A637D2B459C3A9B0A94069081411F883AACC4AEA76666DE3` |
| versionName / Code | `0.10.0-m4p12` / `35` |

### 43.2 驗證

- JVM 測試（UpdateChecker 各來源解析、URL 編碼、HTTPS 限制）/ Debug / Release / AAB 構建通過。
- 真機未驗證：GitLab / Gitee 實際 release JSON、公司內網 GitLab、Gitee 速率限制。

## 44. v0.10.1-m4p12（2026-11-16）：映射 DNS 排隊與重送

- **現象**：0.9.10 後同名並發解析正常，但很多不同網域同時解析（打開從很多網站載入內容的網頁）時仍有部分要 5～9 秒。
- **定位**：在調試版逐包記錄「讀到的查詢 / 送給 hev 的查詢 / hev 的回覆」：
  - 本應用從虛擬網卡讀到的查詢全部寫進了 socketpair，沒有在本應用丟失；
  - hev 同時收到多個查詢時只回覆第一個（同一毫秒內到達的 4 個只有 1 個有回覆），剛送出回覆的同一毫秒內收到的查詢也會丟；
  - 即使逐一放行、間隔數毫秒，仍有約 4% 的查詢沒有回覆。
- **修正**（`DnsPacer`）：映射 DNS 查詢一次只放一個在途，回覆後間隔 2 ms 再放行下一個；在途查詢 25 ms 沒有回覆就自己重送，最多 3 次。hev 正常 1～5 ms 就回覆，排隊與重送的代價很小。回覆按「用戶端埠 + DNS 事務 ID」對應到在途查詢。由轉送層的 `DnsPump` 執行緒放行（有排隊時每 1 ms、空閒時每 5 ms 檢查）。
- **模擬器結果**（30 個不同網域同時解析）：修正前每輪 7～18 個要 5～9 秒；修正後連續 6 輪全部在 0.2 秒內，中位數 80～130 ms。
- **實機**：修正版（當時編號 0.9.11）已安裝且隧道正常；但實機上 adb shell（uid 2000）目前屬於繞過 VPN 的應用，shell 的解析不經過本應用，無法用相同方法量測，需用戶實際瀏覽確認。

## 45. v0.10.2-m4p12（2026-11-16）：hev 重啟後恢復假位址映射

- **現象**（實機，Chrome + FlClash）：逐連線模式下百度首頁一直載入；關閉逐連線模式或改用 FlClash 自己的 TUN 都很快。FlClash 的 SOCKS 出口對百度各網域都是 60～90 ms，DNS 也正常。
- **定位**：Chrome 有一條到 `100.64.0.80:443` 的連線已建立，但 517 位元組（TLS ClientHello）一直未被確認：hev 接受了連線，卻無法完成到代理的連線。每次重建隧道（修改「允許 UDP」、切換模式、更新安裝）hev 都會重啟、映射表清空並從 `100.64.0.0` 重新分配；應用快取（Chrome 使用系統解析器時固定 60 秒）與既有連線仍用舊假位址，hev 不認得或已分給別的網域。模擬器重現：重啟後用舊的百度假位址連線，回應來自別的主機（HTTP 530 / 404）。
- **修正**（`FakeIpMappings`）：hev 按查詢順序從網段第一個位址依序分配，所以 hev 啟動時把已知網域按原位址順序重新查詢一遍（經 `DnsPacer` 的優先佇列，排在所有應用查詢之前），得到完全相同的映射。重放中發往尚未恢復位址的新連線先丟棄（應用約 1 秒後重送 SYN）；重放結束後，hev 不認得的假位址直接拒絕，應用立即失敗並重新解析，而不是卡住。重放的回覆位址與預期不同、或查詢多次無回覆時中止重放。
- **驗證**：`FakeIpReplayTest`；模擬器連續重啟 hev 兩次，舊位址仍 200 OK，新網域接續分配。
- **限制**：映射只存在記憶體，應用進程重啟（更新安裝）後無法恢復，此時舊位址立即被拒絕（不會卡住）。

## 46. v0.10.3-m4p12（2026-11-16）：轉送 socket 對改為真正的 SOCK_SEQPACKET

- **現象**（實機 Chrome / Telegram + FlClash）：逐連線模式下網頁、Telegram 明顯比 FlClash 自己的 TUN 慢；單條連線延遲相同，並發時才慢。實機 20 條並發：純建連恰好 16 條 30～40 ms、其餘 1～3 秒（SYN 重傳）；HTTP 首位元組 0.15～3.5 秒。
- **定位過程**：
  - 先後懷疑過 hev 的映射 DNS、lwIP 監聽佇列、hev 寫入 EAGAIN 丟包，並據此加過 DNS 逐一放行、SYN 限流、hev 寫入重試，都只改善一部分。
  - 逐包追蹤一條慢連線：hev 送出 seq=6527 後，轉送層讀到的下一段是 seq=10907，中間兩段 1460 位元組根本沒有讀到，lwIP 約 10 秒後才重傳。
  - 比對包數：hev 寫出 233 包、轉送層只讀到 134；轉送層寫入 195 包、hev 只讀到 156。兩個方向都在 socket 對裡「消失」。
  - 原因：`ParcelFileDescriptor.createReliableSocketPair()` 是 **SOCK_STREAM**（"Reliable" 指另有關閉偵測通道），不保留封包邊界；突發時多個封包在一次讀取中黏在一起，讀取端只把它當成一個封包處理，其餘遺失。
- **修正**：用 `Os.socketpair(AF_UNIX, SOCK_SEQPACKET, 0, …)` 建立 socket 對（`ForwardingRelay.createPacketSocketPair`）。修正後包數完全一致（hev 寫出 1316 = 轉送層讀到 1316）。
- **清理**：移除只為繞過此問題而加的 SYN 限流（`SynGate`）；`DnsPacer` 不再一次只放一個（在途上限 64、100 ms 未回覆重送），保留 hev 重啟後映射重放的優先順序。
- **保留的改進**：
  - hev 原生庫加上寫入 EAGAIN 時最多等約 100 ms 的補丁（`android-kotlin/native/hev-socks5-tunnel/`），SEQPACKET 的接收佇列滿時不再直接丟包；
  - 轉送執行緒提高優先級（`THREAD_PRIORITY_URGENT_DISPLAY`）；socket 緩衝要求 8 MB（系統上限內）。
- **模擬器結果**（內建直連出口，修正前 → 修正後）：
  - 20 條並發 HTTP：長尾 2～7 秒、每輪 1～3 條失敗 → 全部 0.19～0.58 秒、零失敗；
  - 30 條並發建連：部分 1～3 秒 → 全部 ≤ 0.13 秒；
  - 30 個不同網域並發解析：全部 ≤ 0.08 秒；
  - hev 重啟後映射重放仍正常（舊位址 200 OK）。
- **實機**：修正版未在實機量測（adb 無線連線已中斷），需用戶安裝 0.10.3 後確認。

## 47. v0.10.4-m4p12（2026-11-16）：詢問改為紅魔的按鈕邏輯與樣式

- **需求**：用戶希望懸浮詢問與紅魔手機自帶的連網詢問一致（附截圖）：一句說明「X 已禁止 Y 使用 WLAN 網路。是否允許此應用使用網路？」、圓形勾選「禁止後不再提示」、底部一排文字按鈕「禁止 | 允許 | 僅一次」；另外保留可設定的「限時允許」。
- **按鈕邏輯**（`PromptDenyPolicy`）：
  - 禁止 → 「禁止 + 直到應用關閉」（`UntilAppExit`，與「僅一次」共用 `TargetAppSessionTracker` 回收；回收後背景連線按既有的背景靜默直接拒絕）；
  - 勾選「禁止後不再提示」再點禁止 → 永久禁止；
  - 允許 → 永久允許；僅一次 → 允許到應用關閉；限時（設定開啟時）→ 限時允許。
  - 新設定「「禁止」預設為永久」（預設關閉）恢復舊行為，此時不顯示勾選框。
  - 移除「稍後」按鈕（`PromptBroker.dismiss` 仍保留，其他入口可用）；移除不再顯示的「顯示說明文字」設定。
- **樣式**：系統懸浮窗預設改為貼底（`promptOverlayPosition` 預設 `bottom`），全寬、上方圓角、白底、背景變暗（`FLAG_DIM_BEHIND`，仍是 `FLAG_NOT_TOUCH_MODAL`，懸浮窗外的觸控照常傳給下方應用）；置頂 / 自訂位置時為四角圓角卡片。應用內卡片（`PromptOverlay`）同樣的內容與按鈕。說明句中的網路類型取自 `NetworkMonitor`（Wi-Fi / 行動數據 / 其他）。
- **驗證**：JVM 測試 `PromptDenyPolicyTest`、`PromptSheetTextTest`、`RulesTest.denyOnceRuleIsAppLevelAndNotPersistent`；模擬器：Chrome 觸發的系統懸浮窗貼底顯示（說明、目標、勾選、四個按鈕）；點懸浮窗外的 Chrome 網址列可正常彈出鍵盤；點「禁止」建立 `Deny UntilAppExit`，離開 Chrome 後被回收；應用內卡片勾選後點「禁止」建立永久禁止規則。
- **未驗證**：實機（紅魔）上的外觀與觸控；中文介面下的排版只在 JVM 測試檢查了文字。

## 48. v0.10.5-m4p12（2026-11-17）：「僅一次」「禁止」持續到應用真正關閉

- **現象**（實機）：點「僅一次」後，應用切到背景再回來又被詢問。原設計以「所有 Activity 離開前台 2 秒」當作關閉，與紅魔的「直到應用關閉」不符。
- **限制**：一般應用拿不到其他應用的關閉事件。UsageStats 中系統把 ACTIVITY_DESTROYED 轉成 ACTIVITY_STOPPED，模擬器上把 Chrome 從最近任務劃掉也只留下 STOPPED；其他進程不可見。紅魔的掌心管家是平台簽名的系統應用，可以直接監聽進程死亡。
- **做法**：
  - 使用者用 adb 授予一次 `android.permission.DUMP`（development 權限，manifest 已宣告）後，`ProcessExitWatcher` 每 2 秒查 `getHistoricalProcessExitReasons(目標套件)`，**主進程**（`processName == packageName`，忽略渲染等子進程）在規則建立後結束才移除規則；切到背景多久都不影響。不需要使用情況權限。
  - 未授權時退回近似：離開前台且在背景停留超過「「僅一次」「禁止」在背景保留（分鐘）」才移除，預設 10 分鐘，0 = 舊行為（2 秒）。
  - 設定頁顯示是否已啟用關閉偵測，未啟用時附上授權指令 `adb shell pm grant com.networkcontrol.android.kotlin android.permission.DUMP`。
- **驗證**：JVM 測試 `OnceSessionGraceTest`；模擬器（已授予 DUMP）：Chrome 點「僅一次」→ 回桌面 20 秒再打開不再詢問 → 從最近任務移除 Chrome，主進程結束後約 2 秒規則被移除（日誌 `removed 1 until-app-exit rules after the app process ended`）。
- **未驗證**：實機（紅魔 / Android 版本）上 `pm grant DUMP` 與進程結束記錄是否同樣可用；未授權時 10 分鐘近似的實際體驗。

## 49. v0.10.6-m4p12（2026-11-17）：用 Shizuku / Root 一鍵開啟關閉偵測

- **目的**：給別人安裝時不必接電腦就能授予 DUMP（§48 的關閉偵測）。
- **做法**（`DumpPermissionGranter`）：
  - 設定頁「關閉偵測」一行加「授權」按鈕；Shizuku 正在執行 → 請求 Shizuku 授權（已授權則略過）→ 以 shell 身分執行 `pm grant <本應用> android.permission.DUMP`；
  - 沒有 Shizuku → 嘗試 `su -c pm grant …`；沒有 `su` → 顯示引導（Shizuku 無線偵錯啟動，或電腦 adb 命令，可一鍵複製）。
  - Shizuku 13 的 `Shizuku.newProcess` 是私有方法，改用公開的 `Shizuku.getBinder()` + `IShizukuService.newProcess`；manifest 宣告 `rikka.shizuku.ShizukuProvider` 接收 binder。依賴 `dev.rikka.shizuku:api` / `provider` 13.1.5（Apache-2.0），APK 增加約 16 KB。
  - 授權後 `checkSelfPermission` 立即生效，不必重啟應用。
- **驗證**：模擬器：沒有 Shizuku / root 時點「授權」顯示引導並可複製命令；用 adb 授權後回到設定頁顯示「已啟用關閉偵測」。
- **未驗證**：Shizuku 一鍵授權（模擬器上啟動 Shizuku 服務的操作未獲允許，待用戶在實機測試）；Root 授權。
