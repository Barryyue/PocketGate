# Android 網路類型、SIM 與流量拆分規劃

> 建立日期：2026-11-15  
> 對象：`android-kotlin/`（Kotlin 版），規則語義與桌面版共用時以 Core 的追加式變更為準。  
> 目的：回答「目前是否區分 Wi-Fi / 行動網路、SIM1 / SIM2」並給出可執行的長期設計。

## 1. 現狀（v0.2）

| 能力 | 現狀 | 結論 |
|---|---|---|
| 按 Wi-Fi / 行動網路切換策略 | **部分有**：`ProfileAutoSwitcher` 可依 `NetworkKind`（Wi-Fi / Cellular / Wired）自動啟用配置文件；配置文件內的規則不同 | 可以用「Wi-Fi 配置文件 / 行動網路配置文件」達到按網路控制，但不是規則層條件 |
| 按 SSID 切換 | 有，但 SSID 需要 `ACCESS_FINE_LOCATION`；沒有權限時只能按類型 | 已加引導，真機待驗證 |
| 按 SIM1 / SIM2 切換或控制 | **沒有**：`NetworkContext` 沒有 subscriptionId / slot；`NetworkMonitor` 只回報類型 | 待做 |
| 規則層的「僅 Wi-Fi / 僅行動網路 / 僅 SIM1」條件 | **沒有**：`RuleConditions` 只有方向、協議、遠端、端口、區域 | 待做 |
| 流量統計區分 Wi-Fi / 行動網路 | **沒有**：`TrafficCollector` 把 TYPE_WIFI + TYPE_MOBILE 加總 | 待做 |
| 流量統計區分 SIM1 / SIM2 | **沒有** | 待做（受 Android 權限限制） |
| 每個套用的流量按網路類型拆分 | **沒有**：只有總量 | 待做 |
| 允許的應用（不經過 VPN）的流量 | 由 `NetworkStatsManager` 按 UID 統計，可取到；但目前不拆網路 | 待做 |

**結論**：目前只能透過「配置文件按網路類型自動切換」間接區分 Wi-Fi / 行動網路，**不能區分 SIM1 / SIM2**，**流量也只有合計**。需要新增任務。

## 2. Android 能力與硬限制

### 2.1 可取得的資訊

- `NetworkCapabilities.hasTransport(TRANSPORT_WIFI / CELLULAR / ETHERNET)`：目前預設網路的類型。
- `NetworkCapabilities.getNetworkSpecifier()`：行動網路可轉成 `TelephonyNetworkSpecifier`，取得 `subscriptionId`（API 29+）。
- `NetworkCapabilities.getSubscriptionIds()`（API 30+，若可用）：一組 subscription id；雙 SIM 時可能有多個。
- `SubscriptionManager.getActiveSubscriptionInfoList()`：SIM 卡資訊（slotIndex、carrierName、displayName），通常需要 `READ_PHONE_STATE`；部分 ROM 對普通應用限制。
- `TelephonyManager.createForSubscriptionId(subId).subscriberId`：用於 `NetworkStatsManager.querySummary(TYPE_MOBILE, subscriberId, ...)` 按 SIM 查流量；可能需要 `READ_PHONE_STATE` / 特權，Android 10+ 常被脫敏。
- `NetworkStatsManager.querySummary(networkType, subscriberId, start, end)`：Wi-Fi 用 `null` subscriberId；行動網路可用 subscriberId 區分 SIM。
- `NetworkStatsManager.querySummaryForDevice` / `NetworkTemplate`：部分 API 是系統/隱藏，不作為公開方案。

### 2.2 不能做的事（重要）

1. **VpnService 不能按「每個應用 × 每個 SIM」轉發**。VPN 的分流名單是 UID 級別的全域名單，沒有「這個套用只走 SIM1」的介面；Android 的 per-app 網路選擇是系統/廠商功能，第三方 VPN 不能直接指定某個 socket 走哪張 SIM。
2. **雙 SIM 同時在線時，只能以「目前預設資料 SIM」為準**。`ConnectivityManager` 的預設網路只有一個；另一個 SIM 可能待機或可切換，但 VPN 無法為不同 UID 指定不同 SIM。
3. 因此「按 SIM 控制」的正確語義是：**依目前預設資料 SIM（或使用者切換後）套用不同配置文件 / 規則，並在 SIM 切換時重建 VPN**；不是「同一時間讓 A 套用走 SIM1、B 套用走 SIM2」。
4. 若使用者同時啟用 Wi-Fi 與行動網路，預設網路通常是 Wi-Fi；行動網路可能仍可用（智慧網路切換）。我們以 `registerDefaultNetworkCallback` 回報的預設網路為策略依據。
5. 部分 ROM 會把 subscriptionId 或 subscriberId 隱藏；必須有降級方案，不能讓功能因權限失敗而崩潰或停止攔截。

## 3. 設計

### 3.1 資料模型（Core，追加式）

`NetworkContext` 追加可空欄位（不改變現有欄位語義）：

```kotlin
data class NetworkContext(
    val kind: NetworkKind = NetworkKind.None,
    val ssid: String? = null,
    val networkId: String? = null,
    val displayName: String? = null,
    // v0.3 追加
    val subscriptionId: Int? = null,
    val simSlotIndex: Int? = null,
    val carrierName: String? = null,
    val isMetered: Boolean? = null,
)
```

`AutoActivateCondition` 追加 `subscriptionIds: List<Int>` / `simSlots: List<Int>`（二選一即可，建議 subscriptionId 為主、slot 為顯示）。`matches()` 在欄位非空時才比對，沒有權限/沒有 SIM 時不匹配，避免誤切換。

`RuleConditions` 追加（第二階段，先不改規則 UI 也可以先加欄位）：

```kotlin
val networkKind: NetworkKind? = null
val subscriptionId: Int? = null
val simSlotIndex: Int? = null
```

`CompiledRule.matchConditions` 增加 `network: NetworkContext` 參數；`RuleSet.evaluate` / `findAppRule` 傳入。沒有 network 條件的規則行為不變。

`VpnRoutingPlanner.plan()` 增加 `network: NetworkContext`；`VpnEngine` 收集 `network.state`，網路變化時 replan（和規則 / 模式變化同一條去抖隊列）。這樣規則層的網路條件在黑洞模式下可用：目前網路符合才 bypass/block，否則按其他規則或模式處理。

`RuleSkipReason` 不需要新增：Android 能依當下網路判斷，條件視為可執行；桌面 / 其他引擎若無法判斷，應在各自的 planner 回報「不支援」。

### 3.2 SIM / 網路偵測

`NetworkMonitor`：

- 保留 `registerDefaultNetworkCallback`、忽略 VPN transport。
- 從 `NetworkCapabilities.networkSpecifier` 取 `TelephonyNetworkSpecifier.subscriptionId`；API 30+ 可用 `subscriptionIds` 的第一個作為 fallback。
- `SubscriptionManager` 取 `simSlotIndex`、`carrierName`、`displayName`；沒有 `READ_PHONE_STATE` 時只回 subscriptionId（若 network specifier 有），否則 `null`。
- `isMetered = !hasCapability(NET_CAPABILITY_NOT_METERED)`。
- 不把 `subscriptionId` 寫入公開日誌；診斷包只記 `simSlot` / 是否有值。

權限：

- 設定 / 流量卡引導 `READ_PHONE_STATE`（只為 SIM 標籤與按 SIM 流量；不授權也能用 Wi-Fi/行動網路類型）。
- 沒有權限時：顯示「行動網路（未區分 SIM）」，不顯示 SIM1/SIM2；不影響攔截和按類型控制。
- Android 13+ 若 `READ_PHONE_STATE` 被限制，保留 `TelephonyNetworkSpecifier` 的 subscriptionId（不需要該權限的部分）並降級標籤。

### 3.3 流量資料

新增 Activity 遷移 `0004_traffic_network.sql`（只追加，不改已有表）：

```sql
CREATE TABLE traffic_network (
    app_id          INTEGER NOT NULL,
    ts              INTEGER NOT NULL,
    network_kind    INTEGER NOT NULL, -- Wifi / Cellular / Wired / Other
    subscription_id INTEGER NOT NULL DEFAULT -1,
    bytes_in        INTEGER NOT NULL,
    bytes_out       INTEGER NOT NULL,
    PRIMARY KEY (app_id, ts, network_kind, subscription_id)
) WITHOUT ROWID;
```

保留現有 `traffic_minute/hour/day` 作為總量，新增表只存拆分；查詢時可以按 `network_kind` / `subscription_id` 聚合。分鐘表保留 3 天，與現有策略一致。

`TrafficCollector`：

- Wi-Fi：`querySummary(TYPE_WIFI, null, since, now)` → `network_kind=Wifi`，`subscription_id=-1`。
- 行動網路：
  - 有 `READ_PHONE_STATE` 且能取得 subscriberId：對每個 active subscription 查 `querySummary(TYPE_MOBILE, subscriberId, ...)`，寫入對應 subscriptionId。
  - 沒有權限或查不到：`querySummary(TYPE_MOBILE, null, ...)`，寫入 `subscription_id=-1`，UI 顯示「行動網路（合計）」。
- 既有 `KEY_LAST_COLLECTED` 的增量邏輯保留，並用 `Mutex` 串行化（v0.2 已加）。
- 寫入總量表 + 拆分表；如果拆分查詢失敗，至少寫總量，不影響原有統計。

查詢 API：

- `TrafficSummary` 追加可選的 `byNetwork: Map<NetworkKey, Bytes>`；`NetworkKey(kind, subscriptionId)`。
- `ActivityLog.GetTraffic` 追加 `network_breakdown`（proto 只新增字段/消息）。
- Android 界面：總覽「今日流量」卡片改為總量 + `Wi-Fi` / `行動網路` / `SIM1` / `SIM2` 小標籤；應用頁可先顯示總量，詳情頁再按網路拆分（後續）。

### 3.4 控制語義

第一階段（推薦先做）：

- **配置文件按網路自動切換**：`AutoActivateCondition` 支援 Wi-Fi / 行動網路 / SIM1 / SIM2 / SSID。使用者建立「公司 Wi-Fi」「SIM1 行動網路」「SIM2 行動網路」配置文件，各自帶不同規則；切換時 `ProfileAutoSwitcher` 啟用對應配置文件，VPN 重建。這是 Android 上最接近「按網路 / SIM 控制」的可行方案。
- 設定頁的配置文件編輯器增加 SIM 選擇（從 `SubscriptionManager` 取目前 SIM；沒有權限時顯示說明）。
- 狀態欄 / 總覽顯示目前網路與 SIM 名稱。

第二階段（規則層）：

- `RuleConditions` 增加網路類型 / subscriptionId；規則編輯器（目前 Kotlin 版還沒有獨立規則編輯器）增加「僅 Wi-Fi / 僅行動網路 / 僅 SIM1」選項。
- 規則優先級：網路條件與其他條件一起計分；「更具體」的規則優先。沒有網路條件的規則維持原行為。
- 引擎能力：`INetworkEngine` 增加 `NetworkConditions` 能力；不支援的引擎（例如 Linux NFQUEUE）在 planner 標記 skipped，界面提示。

**不要承諾**：同一時間讓不同套用走不同 SIM。這是 Android 平台限制；文件中要如實說明。

## 4. 分階段任務

| 階段 | 內容 | 驗收 | 預估 |
|---|---|---|---|
| N1-1 | `NetworkContext` 加 subscriptionId / simSlot / carrier / metered；`NetworkMonitor` 從 `TelephonyNetworkSpecifier` + `SubscriptionManager` 取值；`AutoActivateCondition` 支援 SIM；配置文件編輯器加 SIM 選擇與權限引導 | 單元測試（NetworkContext / 條件匹配 / AutoSwitcher）；模擬器 UI；真機雙 SIM | 2–3 天 |
| N1-2 | `traffic_network` 遷移 + `TrafficCollector` 拆分 Wi-Fi / 行動網路 / SIM；查詢 API + 總覽與應用頁標籤；無權限降級 | 儲存測試（遷移、聚合、查詢）；模擬器流量顯示；真機 SIM1/SIM2 | 2–3 天 |
| N1-3 | `RuleConditions` 網路條件 + `RuleSet` / planner / `VpnEngine` 接入；規則編輯器（如需） | 規則優先級 / planner 測試；模擬器切換網路 | 2–4 天 |
| N1-4 | 真機雙 SIM 回歸：預設資料 SIM 切換、Wi-Fi ↔ 行動網路、限制/權限拒絕、OEM ROM | 真機清單 | 1–2 天 |

總計約 **7–12 個工作日**（不含真機等待）。N1-1 / N1-2 可以並行，N1-3 依賴 N1-1。

## 5. 風險與取捨

| 風險 | 應對 |
|---|---|
| `READ_PHONE_STATE` 被拒絕 / ROM 脫敏 | 降級為「行動網路（合計）」；不影響攔截；在 UI 說明 |
| 雙 SIM 同時在線，無法按 UID 指定 SIM | 只按目前預設資料 SIM 套用策略；文件中明講，不誤導 |
| 訂閱 ID 在重啟 / 換卡後變化 | 配置文件同時保存 subscriptionId 與 simSlot；匹配時優先 subscriptionId，找不到再用 slot |
| 流量拆分重複計數 | 與總量表共用同一 `since` / 寫入游標，Mutex 串行化；拆分表與總量表在同一事務 |
| 規則層網路條件讓 Android 與桌面行為不同 | 在 `engine capabilities` 標記；桌面不支援時 UI 提示，不靜默 |
| 使用者把「Wi-Fi 配置文件」和「規則層網路條件」混用 | 文檔與設定頁說明兩者差別：配置文件切換整個規則集；規則層條件只影響單條規則 |

## 6. 驗收清單（未來）

- [ ] 模擬器：切換 Wi-Fi / 行動網路，配置文件自動切換；VPN 重建不閃退。
- [ ] 模擬器：總覽流量顯示 Wi-Fi / 行動網路拆分；應用頁總量不變。
- [ ] 真機雙 SIM：SIM1 / SIM2 切換時，當前 SIM 標籤和配置文件正確；流量分開累加。
- [ ] 真機：拒絕 `READ_PHONE_STATE` 時，功能降級但不崩潰。
- [ ] 規則層網路條件：Wi-Fi 允許、行動網路拒絕，切換後行為正確。
- [ ] 與 Avalonia 版規則導入導出：網路條件不丟失或明確標記不支援。
