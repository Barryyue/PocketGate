# Kotlin Android 版長期路線圖（v0.3 → v1.0）

> 建立日期：2026-11-15  
> 目標：以 Kotlin 版為主線，完成目前待辦與 Avalonia 版尚未實現的功能，最終產出可安裝的成品 APK。  
> 原則：每完成一項，必須在 [kotlin-feature-log.md](kotlin-feature-log.md) 追加記錄；Avalonia 版後續補全時，以本文件的差異表為同步依據。  
> 相關文件：[android-kotlin.md](android-kotlin.md)（歷史與階段）、[android-network-sim-plan.md](android-network-sim-plan.md)、[android-forwarding-research.md](android-forwarding-research.md)。

## 1. 目前基線

- v0.2 已合併 `main`（`f0e61c2`），APK `NetworkControl-KT-0.2.0.apk`（3.17 MB）。
- 已完成：VPN / 假 DNS / 規則 / 詢問 / 配置文件 / 應用 / 記錄 / 流量總量 / 導入導出 / 診斷 / 主題色 / 應用列表權限 / 即時網速 / 勿擾 / 自動切換 / P0/P1 審查修正。
- 尚未完成：見第 3 節；其中網路/SIM 拆分、CSV、應用詳情、去重、可信發布者、批量詢問、專注/時間表、鎖屏、允許一次、懸浮面板、轉發模式等。

## 2. 里程碑

### M1 — v0.3：網路/SIM、資料導出與規則品質（約 1–2 週）

> **狀態：已完成（2026-11-15，提交 `6beed8e`，APK `NetworkControl-KT-0.3.0.apk`）。** 功能記錄見 [kotlin-feature-log.md](kotlin-feature-log.md) 的 K-024、K-029～K-034。

1. **N1 網路類型 / SIM / 流量拆分**（詳細見 [android-network-sim-plan.md](android-network-sim-plan.md)）
   - N1-1：NetworkContext 加 subscriptionId / simSlot / carrier / metered；NetworkMonitor 取 SIM；配置文件自動切換支援 SIM；編輯器與權限引導。
   - N1-2：`traffic_network` 遷移；TrafficCollector 按 Wi-Fi / 行動網路 / SIM 拆分；總覽與應用頁顯示。
   - N1-3：規則層網路條件 + planner + 網路變化 replan + 規則編輯器。
2. **K-029 連線記錄 CSV 導出**：按目前篩選 / 全部，SAF 保存；桌面格式共用（RFC 4180，UTF-8 BOM）。
3. **K-030 應用詳情頁**：應用資訊、當前規則、最近域名 / 目標 / 次數 / 時間；從應用頁進入。
4. **K-031 規則與應用記錄去重 / 分組**：路徑比較鍵、完全相同規則更新而非新增、啟動合併舊資料、規則頁按應用分組。
5. **K-032 可信發布者 / 系統應用默認允許**：`AppMatcher` 追加 publisher/platform 條件；設定頁與首次引導勾選；規則具體度低於指定路徑。
6. **K-033 詢問批量處理**：待決詢問列表，多選允許 / 拒絕 / 稍後，只發布一次規則集。
7. **K-034 通知動作在進程回收後仍有效**：通知 action 帶 package/action/lifetime；prompt 不存在時直接建規則或提示已逾時。

### M2 — v0.4：自律與詢問體驗（約 2–3 週）

> **狀態：第一版已完成（2026-11-15，提交 `b66956d`，APK `NetworkControl-KT-0.4.0.apk`）。** K-043 的即時驗證、專注結束小結、AlarmManager 精確排程列為後續補強；功能記錄見 K-035～K-044。

1. **K-035 專注模式**：一鍵進入、時長、允許應用、剩餘時間、提前結束確認 + 倒計時、結束小結；與 Core 情境能力共用。
2. **K-036 時間表 / 情境**：按星期與時段自動進入 / 退出情境；重啟後按當前時間校正；Android 用 AlarmManager/WorkManager。
3. **K-037 鎖屏時禁止聯網**：全局與按應用開關；亮屏 / 鎖屏重新規劃。
4. **K-038 「允許一次」會話綁定**：鎖屏 / 10 分鐘兜底；使用情況存取權時離開應用 1 分鐘失效；通知與詢問卡片第三個按鈕。
5. **K-039 懸浮詢問面板**：SYSTEM_ALERT_WINDOW + 原生 View；前台應用判斷、通知兜底、隊列 / 超時 / 稍後；不影響現有通知。
6. **K-040 詢問內容顯示設定**：連線詳情 / 說明文字 / 鎖屏隱藏詳情。
7. **K-041 新應用安裝提醒**：PACKAGE_ADDED 通知，首次打開前決定。
8. **K-042 私人 DNS / DoH 提示**：偵測嚴格模式或已知 DoH，總覽提示域名規則可能不生效。
9. **K-043 應用鎖**：密碼 / 生物辨識，修改規則 / 模式 / 退出時驗證。
10. **K-044 無障礙、平板、大字體、TalkBack**：基本自適應與語義標籤；不追求完整認證。

### M3 — v0.5：性能、發布與平台深化（約 1–2 週）

> **狀態：第一版已完成（2026-11-15，提交 `1db37d1`，APK `NetworkControl-KT-0.5.0.apk` / AAB）。** Baseline Profiles、arm64-only、CI 基線、正式 keystore 演練、純 IPv6 / OEM 真機回歸列為後續；功能記錄見 K-045～K-050。

1. **K-045 性能 P-1…P-9**：啟動埋點、只打 arm64、完整裁剪、R8、Baseline Profiles、啟動畫面、應用目錄增量、並行初始化、CI 大小 / 啟動基線。
2. **K-046 正式簽名 / AAB / 更新渠道**：用戶提供密鑰庫；`NC_ANDROID_KEYSTORE`；AAB + Play 用途宣告；側載更新檢查。
3. **K-047 工作資料夾 / 多使用者**：明確限制、狀態提示；可行時支援。
4. **K-048 純 IPv6 / 私人 DNS / OEM ROM 回歸**：真機清單與診斷。
5. **K-049 冒煙測試腳本**：adb 安裝、授權、開保護、Chrome / 通知 / 記錄檢查。
6. **K-050 內置 WireGuard（可選）**：預研後決定。**決定：不內置 WireGuard VPN**。Android 同時只能有一個 `VpnService`，內置 WireGuard 會與本應用的黑洞 / 轉發隧道衝突；若未來需要 WireGuard，應作為轉發模式的上游代理（SOCKS5 / WireGuard 出口），由使用者自行安裝的客戶端提供，而不是替換本應用的 VpnService。

### M4 — v1.0：轉發模式 + 上游代理（約 2–2.5 個月）

> **狀態：未開始（2026-11-15）。** Phase 0 需要 Go 1.22+、gomobile、Android NDK（r27+）與真機吞吐 / 耗電測試；目前開發機沒有 Go 與 NDK，且模擬器不能代替真機驗證。Phase 0 的驗收門檻與步驟見 [android-forwarding-research.md](android-forwarding-research.md) §0、§5；在工具鏈與真機就緒前不以任何「假轉發」代替。

1. **K-051 Phase 0 spike**：gVisor netstack + 最小 TCP 轉發 + SOCKS5；判定點；真機吞吐 / 耗電 / FlClash 共存。
2. **K-052 轉發引擎**：TCP/UDP、域名、上游排除、防環路、上游不可用提示。
3. **K-053 黑名單 / 域名 / IP / 端口規則在轉發模式生效**；允許連接日誌與流量。
4. **K-054 上游代理切換與診斷**；與黑洞模式並存。
5. **K-055 效能 / 耗電 / 兼容性回歸**，達標後作為可選「進階模式」。

## 3. Avalonia 版差異矩陣

| 功能 | Avalonia Android 現狀 | Kotlin 目標 | 處理 |
|---|---|---|---|
| 應用列表權限引導 | 無 | v0.2 完成 | Kotlin 先行；Avalonia 後續可移植 |
| 主題色（預設 + 自訂） | 無 | v0.2 完成 | Kotlin 先行 |
| 規則按配置文件隔離 | 應用三態規則目前共享（同一 bug） | v0.2 修正 + 遷移 | **Avalonia 需要同步修正** |
| 即時網速 | 無 | v0.2 完成 | Kotlin 先行 |
| 勿擾入口 / 摘要 | 有摘要，入口視版本 | v0.2 完成 | 對齊 |
| 按網路類型 / SSID 自動切換 | M3-04 已有 | v0.2 完成 | 對齊 |
| Wi-Fi / 行動網路 / SIM 流量拆分 | 無 | v0.3 N1 | Kotlin 先行 |
| 連線記錄 CSV | 無 | v0.3 | Kotlin 先行 |
| 應用詳情 | 無 | v0.3 | Kotlin 先行 |
| 規則去重 / 分組 | 無（P1） | v0.3 | 兩邊共用設計，Kotlin 先行 |
| 可信發布者 / 系統應用默認允許 | 無（P1） | v0.3 | 兩邊共用 Core 設計 |
| 詢問批量處理 | 無（P1） | v0.3 | 兩邊共用 Core 設計 |
| 通知動作進程回收 | 未處理 | v0.3 | Kotlin 先行 |
| 專注模式 / 時間表 | 規劃 | v0.4 | 共用 Core 情境 |
| 鎖屏斷網 / 允許一次 | 規劃 | v0.4 | Kotlin 先行 |
| 懸浮詢問面板 | 規劃 | v0.4 | Kotlin 先行 |
| 新應用提醒 / DoH 提示 / 應用鎖 | 規劃或無 | v0.4 | Kotlin 先行 |
| 性能 / AAB / 簽名 | 部分（P-1…P-11 在 progress-android） | v0.5 | 兩邊共享測量方法 |
| 轉發模式 | 規劃（預研） | v1.0 | 兩邊共用原生核心評估 |

> 差異記錄規則：Avalonia 版後續補全時，先查本表；Kotlin 先行的功能若有語義差異，更新本表並在功能記錄中註明。

## 4. 完成定義

- 功能記錄 `K-xxx` 已追加（功能、日期、提交、驗證方式、未驗證項）。
- Kotlin：`.\scripts\android-kotlin.ps1 test` + `publish` 通過；有設備時做對應真機驗證。
- 涉及 Core/Storage 的共用語義：同步更新 Avalonia 差異表，必要時在 `docs/android-kotlin.md` 記錄。
- 版本章節記錄 APK 路徑、大小、SHA-256、未驗證項。

## 5. 執行順序

1. v0.3 先做 N1-1 / N1-2（可並行），再做 N1-3、CSV、應用詳情、去重、可信發布者、批量詢問、通知修正。
2. v0.4 做專注 / 時間表 / 鎖屏 / 允許一次 / 懸浮面板 / 提醒 / 應用鎖 / 無障礙。
3. v0.5 做性能、簽名、AAB、更新、工作資料夾、真機回歸。
4. v1.0 做轉發模式 Phase 0 → 全量，達標才合入。
