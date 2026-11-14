# android-kotlin v0.2 代碼審查（2026-11-15）

> 審查範圍：`android-kotlin/`（Kotlin + Jetpack Compose，`com.networkcontrol.android.kotlin`）v0.2 未提交的改動：應用列表權限引導、即時網速、勿擾／勿擾摘要通知、配置文件按網絡自動切換，以及它們依賴的 VPN／詢問／存儲路徑。
> 審查方式：純靜態閱讀（未構建、未在真機或模擬器運行、未修改任何代碼）。所有行號以 2026-11-15 當前工作區內容為準。
> 只列會影響真機行為（功能不正確、崩潰、卡頓、數據錯誤）的問題；純風格問題不收錄。
> 說明：審查期間有其他 agent 在同一個工作區提交 v0.2 的收尾改動（版本號 0.2.0、`docs/android-app-list-permission.md` 等），本文件審查的是同一份快照；若之後代碼再變動，行號需要重新核對。

## 摘要

| 嚴重度 | 數量 | 主題 |
|---|---|---|
| **P0** | 1 | 刪除／重命名配置文件時未捕獲協程異常 → 直接閃退 |
| **P1** | 6 | 隧道重建競態把新隧道標成失敗；`detach`/`revoke` 與 `apply` 競爭並在主線程 `join`；應用列表可見性鏈路（含「已允許的應用仍被攔」與身份缺失時靜默丟包）；網絡監控與 SSID 令自動切換失效；`BlockedEvent` 也建立詢問並計入額度；初始化／重配路徑沒有異常隔離（保護靜默失效） |
| **P2** | 8 | `establish()` 失敗被改寫成「需要授權」；主線程 PackageManager／`loadLabel`；流量重複計數、`connection_log` 無限增長、每筆阻擋同步寫檔；勿擾統計的競態與漏計；通知權限被拒後詢問不可見；通知動作在進程回收後無效；`getConnectionOwnerUid` 回傳 -1 被誤判為系統 UID；每次啟動覆寫系統的 per-app 語言 |

按嚴重度排序如下。

---

## P0-1　在設置頁刪除「當前配置文件」（或建立同名配置文件）會讓 App 閃退

**位置**

- `android-kotlin/app/src/main/java/com/networkcontrol/android/kotlin/ui/settings/SettingsScreen.kt:464-466`（非默認配置文件一律顯示「刪除」，**包含當前啟用中的配置文件**）
- `SettingsScreen.kt:393-396`（確認刪除 → `backend.deleteProfile(...)`）
- `AppBackend.kt:190-192`、`:176-188`（`deleteProfile` / `createProfile` / `updateProfile` 都是 `container.scope.launch { ... }`，沒有任何 catch）
- `core/profiles/ProfileService.kt:136`（`require(id != runtime.activeProfileId)`）、`:181-190`（`require` 校驗名稱與唯一性）
- `AppContainer.kt:41`（`CoroutineScope(SupervisorJob() + Dispatchers.Default)`，沒有 `CoroutineExceptionHandler`）

**問題**

三條使用者很容易走到的路徑都會在協程裡拋出未捕獲異常：

1. 對「當前配置文件」按刪除並確認 → `require(id != runtime.activeProfileId)` 拋 `IllegalArgumentException`；
2. 新增／編輯配置文件時輸入已存在的名稱 → `ensureUniqueName` 拋 `IllegalArgumentException`；
3. 名稱空白或超過 40 字 → `normalizeName` 拋 `IllegalArgumentException`。

`scope` 只有 `SupervisorJob`，`launch` 的異常沒有 `CoroutineExceptionHandler`，會落到執行緒的默認處理器。

**為什麼在真機上會出錯**

Android 的 `KillApplicationHandler` 會把未捕獲異常當成致命錯誤並殺掉進程：使用者看到的是「點一下刪除就閃退」，而且**配置文件沒有被刪除、也沒有任何提示**。重名配置文件是新增配置文件時最常見的誤操作，因此這不是邊緣情況。

**建議修法**

- UI 層：`ProfileRow` 對 `active` 的配置文件不顯示刪除（或顯示但禁用並說明「請先切換到其他配置文件」）。
- 業務層：把 `require` 改成回傳 `Result`／自訂錯誤，或讓 `AppBackend` 的每個 `launch` 都套 `runCatching` 並透過 `Toast`／Snackbar 回報。
- 全局兜底：給 `container.scope` 加 `CoroutineExceptionHandler`（至少記錄 + 不崩潰），這是本倉庫多處 `scope.launch` 的共同風險。

---

## P1-1　重建隧道時，舊 `TunReader` 的錯誤回調會把**新**隧道標成 `Failed`

**位置** `engine/vpn/VpnTunnel.kt:188-209`（`apply`）、`:201-204`（舊 reader 的 `onError`）、`:49-95`（`TunReader`）

**問題**

`apply()` 的順序是：

```kotlin
val descriptor = runCatching { svc.establish(config) } ...   // 188：先建立新 tun
reader?.close()                                             // 198：之後才停舊 reader
pfd = descriptor
reader = TunReader(descriptor, sink) { error ->             // 201
    lastError = "讀取虛擬網卡失敗：${error.message}"
    setState(VpnTunnelState.Failed)
}
...
lastError = null
setState(VpnTunnelState.Active)                             // 209
```

Android 的 `VpnService.Builder.establish()` 在已有隧道時會**替換**現有介面，舊 fd 上的 `read()` 立即失敗。舊 `TunReader` 的 `stopped` 只有在 `close()`（第 198 行）裡才會被設為 `true`，因此「`establish()` 返回」到「`reader.close()` 生效」之間，舊執行緒的 `IOException` 會走到 `onError`，把 `lastError` 與 `state` 設成 `Failed`。

**為什麼在真機上會出錯**

- 只要 `bypassPackages` 有變化就會重建（例如使用者第一次對某個 App 按下「允許」、增刪應用規則、切換白名單／黑名單、安裝新 App 後規則命中），所以這條競態是**常態路徑**而不是罕見路徑。
- 舊 reader 的 `onError` 可能晚於第 209 行執行：UI 顯示「保護失敗」（`ProtectionStatus.Failed`），但隧道其實正常。
- 更糟的是 `VpnEngine.replanNow`（`VpnEngine.kt:109-113`）用 `state == Active` 判斷「是否已套用」；狀態一旦是 `Failed`，下一次 replan 會再 `establish()` 一次，於是又觸發一次競態，形成「每次 replan 都重建一次隧道」的抖動。

**建議修法**

- 先 `reader?.close()` / `pfd?.close()`，再 `svc.establish(config)`（舊介面本來就要被替換，先停讀取執行緒即可）。
- 或用「世代令牌」：`TunReader` 的 `onError` 只在 `reader === currentReader` 時才回報；`apply()` 建立新 reader 前先遞增 `generation`。
- `onError` 內不要直接 `setState(Failed)`，改成回報給 controller 並忽略已過期的 reader。

---

## P1-2　`detach()` / `revoke()` 不經過 `gate`，會關掉剛建立的新 fd；且在服務主線程 `join` 2 秒

**位置**

- `engine/vpn/VpnTunnel.kt:172-215`（`apply`，全程持 `gate`）
- `VpnTunnel.kt:240-252`（`detach`，**不取 `gate`**，直接 `reader.close()` / `pfd.close()`）
- `VpnTunnel.kt:254-265`（`revoke`）
- `VpnTunnel.kt:86-94`（`close()` 內 `thread.join(2_000)`）
- `VpnTunnel.kt:306-308`（`onDestroy` → `detach`）、`:300-304`（`onRevoke` → `revoke`）

**問題**

`detach()` / `revoke()` 都在**服務主執行緒**被呼叫，卻和跑在 `Dispatchers.Default` 的 `apply()` 共用 `reader` / `pfd` / `state` 這些可變欄位，而且沒有 `gate` 保護。典型時序：

1. 使用者關閉保護 → `close()`（持 `gate`）→ `service?.shutdown()` → `stopSelf()`；
2. 使用者立刻再開啟（或 `replan` 觸發）→ `apply()` 拿到 `gate`，`ensureService()` 看到 `service` 還沒被置空，直接 `establish()` 成功，寫入新的 `pfd` / `reader`，狀態設為 `Active`；
3. 此時系統才派送 `onDestroy` → `detach()` → 把**剛建立的新 `pfd` 關掉**、`reader = null`、`state` 改成 `Closed`。

結果：VPN 介面還在（`establish()` 已經生效），但沒有任何執行緒讀它——所有被納入隧道的流量變成黑洞，UI 顯示「已關閉」，而 `protection` 仍是 `true`。此外 `close()` 在 `gate` 內 `join(2_000)`，`apply()` 又要等 `gate`，一次快速切換可以把 replan 卡住數秒。

另外 `onRevoke` / `onDestroy` 裡的 `reader.close()` 會在主執行緒 `join(2_000)`：`pfd.close()` 不一定能打斷另一個執行緒上阻塞的 `read()`，最壞情況主執行緒被卡 2 秒（前景服務／UI 可見時會明顯卡頓甚至 ANR）。

**建議修法**

- `detach()` / `revoke()` 也走 `gate`（`apply`／`close`／`detach`／`revoke` 全部序列化），或把狀態變更 post 到同一個單執行緒 dispatcher。
- `detach()` 只清理「屬於這個 service 實例」的資源（用 generation／instance 比對），不要無條件關閉 `pfd`。
- 停止 reader 改成「先設 `stopped = true`，再 `pfd.close()`」，`join` 移到背景執行緒；主執行緒只做狀態更新。

---

## P1-3　應用列表可見性與身份解析：偵測不可靠，且失效時「已允許的應用仍被攔」「身份缺失時靜默丟包」

**位置**

- `engine/apps/AppCatalog.kt:66-89`（`visibility()`）、`:108-138`（`load()`）
- `engine/vpn/RoutingPlanner.kt:34-44`（`bypass` 由目錄推導）
- `engine/vpn/VpnTunnel.kt:330-332`（`addDisallowedApplication` 被 `runCatching` 吞掉）
- `engine/vpn/BlockedFlowReporter.kt:110-117`（`packageName == null` 直接 return，只加計數器）

**問題**

1. `visibility()` 用 `pm.checkPermission("android.permission.QUERY_ALL_PACKAGES", ...)` 當作判斷依據。`QUERY_ALL_PACKAGES` 是安裝期授予的普通權限，這個檢查幾乎永遠是 `GRANTED`；它**無法反映** MIUI / HyperOS / ColorOS 等 ROM 額外的「取得應用程式列表」開關（該開關關閉時系統仍回報權限已授予，但只回傳過濾後的名單）。
2. 實際生效的判斷退化為 `installed < 20 || visibleKnown == 0`：`installed < 20` 在乾淨設備／工作資料夾上會誤報，而 ROM 過濾後只剩 30–60 個包時又會漏報。`visibleKnown` 清單還包含 `com.android.launcher3`（Pixel／Samsung／小米都不是這個包名），訊號本身很弱。
3. 一旦目錄不完整，`VpnRoutingPlanner.plan()` 就無法把「已允許」的 App 放進 `bypassPackages`。`NcVpnService.establish()` 對這些包呼叫 `addDisallowedApplication()`，套件不可見時會拋 `NameNotFoundException`，而 `runCatching` 把異常吞掉——**使用者明明按了「允許」，該 App 的流量還是進隧道被丟棄**，且沒有任何提示。
4. `BlockedFlowReporter.process()` 在 `routing?.packageName` 與 `catalog.packagesForUid(uid)` 都拿不到包名時直接 `return`：沒有詢問、沒有活動記錄，只有一個計數器。使用者看到的是「某個 App 默默不能上網」。
5. 另外 `SettingsScreen.kt:276` 用 `remember { backend.appListVisibility() }` 只算一次，從系統設定授予權限後回到 App 不會刷新，會一直顯示「受限」。

**為什麼在真機上會出錯**

這是 v0.2「應用列表權限引導」本來要解決的問題，但目前的偵測與降級策略正好在最需要它的 ROM 上失效：名單不完整 → 應用頁看不到部分 App、詢問顯示包名或「未知應用」、已允許的 App 仍被攔、身份解析不到的連線被靜默丟棄（使用者只覺得「網路壞了」）。

**建議修法**

- 偵測改成「與 ROM 無關的實證」：例如同時比較 `getInstalledPackages(0).size` 與 `getInstalledApplications(0)`／已安裝使用者 App 數量的量級，或把 `visibleKnown` 擴充成「所有 launcher／settings 候選包」的多數表決；更穩妥的是把「列表是否完整」做成使用者可見的自我檢測（比對 `queryIntentActivities(MAIN/LAUNCHER)` 的數量）。
- 不要只開 App 詳情頁（`ACTION_APPLICATION_DETAILS_SETTINGS`）；國產 ROM 的「取得應用程式列表」在「特殊權限／其他權限」頁，應提供說明文字或直接開對應的設定頁（可用 `Intent` 探測可用性）。
- 對 `addDisallowedApplication` 的失敗要記錄並回報（至少寫入活動記錄／診斷包），讓「允許了卻仍被攔」可被發現；或在 replan 時把失敗的包從 bypass 集合移除並提示。
- `packageName == null` 時應 fail-open（不攔截）或至少產生一筆 `reason=UnknownApp` 的活動記錄，而不是靜默丟棄。
- `visibility()` 的結果做成 `StateFlow`／在 `ON_RESUME` 重新檢查，不要 `remember` 一次。

> 註：審查期間工作區出現了另一份專門研究（`docs/android-app-list-permission.md`），其結論與本條一致（ROM 另有 `com.android.permission.GET_INSTALLED_APPS`／`GET_APP_LIST` 等權限、`checkPermission` 不可靠、`packageName == null` 時應以 UID 降級顯示）。修法可以合併參考，不要只改 UI 提示。

---

## P1-4　`NetworkMonitor` 與 SSID 讓「按網絡自動切換配置文件」在真機上失效或抖動

**位置**

- `engine/network/NetworkMonitor.kt:23-43`（`registerNetworkCallback` + `onLost` 直接設 `None`）
- `NetworkMonitor.kt:49-72`（`update()` 不區分是哪個 network）
- `NetworkMonitor.kt:74-81`（`currentSsid()` 用 `WifiManager.connectionInfo.ssid`）
- `core/model/Models.kt:118-128`（`AutoActivateCondition.matches`，有 `ssids` 時 SSID 必須相符）
- `core/profiles/ProfileAutoSwitcher.kt:27-50`

**問題**

1. `registerNetworkCallback(NetworkRequest with INTERNET)` 會對**所有**符合條件的網路回調，包括 App 自己的 VPN 網路（`TRANSPORT_VPN`）。`update()` 不看 `network` 是不是當前默認網路、也不檢查 `NET_CAPABILITY_NOT_VPN`，誰最後回調誰就覆寫 `_state`。開啟保護後 VPN 網路建立會把 `kind` 改成 `Vpn`；之後 Wi-Fi 的訊號變化又會改回 `Wifi` —— 兩者來回覆寫。
2. `onLost(network)` 無條件 `_state.value = None`。切換 Wi-Fi → 行動網路時，舊網路的 `onLost` 可能晚於新網路的 `onCapabilitiesChanged`，狀態會變成「無網路」，`ProfileAutoSwitcher` 直接 `return@collect`，該次切換被丟棄。
3. `currentSsid()` 在 `targetSdk 36`、`minSdk 29` 下**沒有** `ACCESS_FINE_LOCATION`（清單只有 `ACCESS_WIFI_STATE`）。Android 10+ 對此類 App 的 `WifiInfo.getSSID()` 固定回傳 `"<unknown ssid>"`，`takeIf` 會把它過濾成 `null`。

**為什麼在真機上會出錯**

- 只要保護是開著的（這是常態），`NetworkContext.kind` 就會在 `Wifi` 與 `Vpn` 之間跳，`ProfileAutoSwitcher` 每次去抖 3 秒後都可能重新 `activate()` 一次，造成配置文件反覆切換（VPN 跟著重建）；反過來也可能永遠匹配不到 Wi-Fi 條件。
- 任何帶 SSID 的自動切換條件**永遠不會成立**：`AutoActivateCondition.matches()` 在有 `ssids` 時要求 `context.ssid` 非 null 且相符，而 `context.ssid` 在沒有定位權限時恆為 `null`，函式直接回 `false`。設定頁的「使用當前網絡」也只會填入空字串；使用者會覺得這個功能「完全沒作用」。

**建議修法**

- 改用 `registerDefaultNetworkCallback()`；`onLost` 只在自己追蹤的 `Network` 被 lost 時才清空；明確忽略 `NET_CAPABILITY_NOT_VPN` 為 false 的網路（自家 VPN）。
- SSID：要嘛申請 `ACCESS_FINE_LOCATION` 並解釋用途，要嘛明確標示「SSID 條件需要定位權限」並在沒有權限時停用 SSID 欄位。若不想申請定位權限，就不要再提供「按 SSID 自動切換」的承諾（可只保留「按網路類型」匹配，或改用 `WifiInfo.getNetworkId()` 僅在本次連線內有效、重開機後失效的替代方案）。
- `ProfileAutoSwitcher` 建議以「默認網路的 fingerprint 穩定 3 秒」為觸發條件（`debounce` + `distinctUntilChanged`），而不是在 `collect` 內 `delay`。

---

## P1-5　`BlockedEvent` 也建立詢問並計入 `pendingFlows`，`deferred.await()` 是永遠到不了的死代碼

**位置**

- `core/pipeline/VerdictPipeline.kt:93-97`
- `core/prompts/PromptBroker.kt:85-88`（`pendingFlows >= maxPendingFlows` 直接 FailSafe）、`:113`（`pendingFlows++`）
- `engine/vpn/RoutingPlanner.kt:116-121`（Ask／Blacklist 下沒有規則的 App 會得到 `BlockAndAsk`，其封包不會被 fast-fail，只會被丟棄並通報）

**問題**

```kotlin
val deferred = prompts.request(flow, flow.app, domains, tampered)   // 先建立／合併詢問
if (flow.origin == FlowOrigin.BlockedEvent) {
    return Verdict.deny(VerdictReason.Mode)                          // 再用 deny 丟掉 deferred
}
return deferred.await()
```

本 App 的引擎只會產生 `FlowOrigin.BlockedEvent`（`BlockedFlowReporter` 固定填這個值），因此 `deferred.await()` 在這份程式碼中**永遠不會被執行**；每個被阻擋的連線都會：

- 建立或合併一個詢問（使用者會看到「X 想要連線」，但該連線其實已經被丟棄、不會因為按下「允許」而恢復）；
- 讓 `pendingFlows++`，即使這個 flow 並沒有被任何東西持有（`isConnectionHeld = false`）；
- 佔用 `maxPendingFlows`（預設 1000）的額度。

另外 `onTimeout()`（`PromptBroker.kt:265-278`）在 `gate` 內呼叫 `removeLocked()`，而 `removeLocked()` 會 `prompt.timer?.cancel()` —— 這等於在計時器自己的協程裡取消自己。目前後面剛好只有非掛起操作（`deferred.complete`），所以還沒爆；但只要之後有人在 `onTimeout` 裡加一個 `emit`／`withContext`／DB 寫入，就會以 `CancellationException` 中斷（若 `_events` 有訂閱者，`emit` 會直接拋出）。

**為什麼在真機上會出錯**

- 被阻擋的 App 若在短時間內對大量目的地重試（瀏覽器、P2P、連線探測），`pendingFlows` 會逼近上限；達到上限後 `request()` 對**所有**新連線直接回 `FailSafe`（預設 `Deny`）且不再建立詢問——使用者會發現「突然不再詢問、全部直接拒絕」，而且沒有任何原因說明。
- 「允許」的語意與 UI 文案不一致（連線已死，只能等下一個連線）。
- `onTimeout` 的自取消是潛在崩潰點。

**建議修法**

- 明確區分「被持有、等待答覆的 flow」與「僅通報的 blocked event」：後者不要呼叫 `prompts.request()`（或給 `PromptBroker` 一個 `holdConnection = false` 的分支，不計入 `pendingFlows`、不建立 `deferred`）。
- `maxPendingFlows` 只計算真正被持有的 flow。
- `onTimeout` 先把 `prompt.timer = null` 再 `removeLocked()`（或讓 `removeLocked` 不取消自己）。

---

## P1-6　初始化與重配路徑沒有異常隔離：保護可能「靜默地沒開」，replan 協程一旦拋異常就永久停止

**位置**

- `AppContainer.kt:110-144`（整個初始化包在一個 `try/catch(Throwable)` 裡，`finally { started.complete(Unit) }`）
- `AppContainer.kt:41`（沒有 `CoroutineExceptionHandler`）
- `VpnEngine.kt:74-80`（`replanSignal.debounce(300).collect { replanNow() }` 沒有 try/catch）
- `VpnEngine.kt:86-122`（`replanNow` 內的 `catalog.getApps()`、`tunnel.apply()` 都可能拋）
- `VpnTunnel.kt:198-204`（`TunReader` 建構、`pfd.close()` 未包 `runCatching`）
- `data/repository/SqliteStores.kt:136-163` → `core/model/Enums.kt` 的 `fromCode`（`entries.first { ... }`，遇到未知 code 直接拋）

**問題**

1. `settings/runtime/profiles/rules` 任一步驟拋異常（DB 損壞、JSON 解析失敗、規則資料裡有未知的 `FilterMode`／`RuleLifetime` code、SQLite 滿），後面的 `vpnEngine.start()`、`promptNotifier.start()`、`network.start()` **全部被跳過**；`started` 仍然 complete，`AppRoot` 照樣顯示主界面，`protection.enabled` 仍可能是 `true`（SharedPreferences 上次的值），於是有「保護開關是開的、但根本沒有引擎在跑」的狀態——流量全部直連，使用者完全看不出來。
2. `replanNow()` 只在 `debounce.collect` 裡呼叫，沒有 try/catch。`TunReader(descriptor, sink) { ... }` 的建構（`FileInputStream(pfd.fileDescriptor)`）、`pfd.close()` 以及 `VpnRoutingPlanner.plan()` 都可能拋；一旦拋出，這個 collect 協程就結束，**之後任何規則／模式／網路變化都不會再重配**（或異常直接冒泡到 scope 的默認處理器而閃退）。
3. `Enums.fromCode` 全部用 `entries.first { }`；任何一筆歷史資料的 code 不在列舉中就會在 `rules.initialize()` 或讀日誌時拋 `NoSuchElementException`。

**為什麼在真機上會出錯**

- 第 1 點是隱私性問題：UI 說「保護中」，實際沒有任何攔截。
- 第 2 點在使用者切換模式、允許應用、插拔網路時都可能觸發：功能「突然不再生效」，只能重啟 App。
- 第 3 點在跨版本升級（或匯入規則檔）後出現一次就足以讓 App 永遠起不來（且錯誤只寫在 logcat）。

**建議修法**

- 初始化改成逐步 `runCatching` + 明確的 `InitFailure` 狀態，透過 UI 顯示「初始化失敗」而不是假裝就緒；`vpnEngine.start()` 失敗時強制 `protection.setEnabled(false)` 並提示。
- `replanNow` 內包 `try/catch`，並在 `collect` 外層加 `catch` 避免協程死亡；`TunReader` 建構與關閉都要 `runCatching`。
- `fromCode` 改成 `entries.firstOrNull { } ?: 預設值` 並記錄一次警告，避免單筆壞資料毀掉整個啟動。

---

## P2-1　`establish()` 失敗一律被改寫成「尚未取得建立 VPN 的授權」

**位置** `engine/vpn/VpnTunnel.kt:188-197`

```kotlin
val descriptor = runCatching { svc.establish(config) }
    .onFailure { lastError = "建立虛擬網卡失敗：${it.message}"; setState(Failed) }
    .getOrNull() ?: run {
        lastError = "尚未取得建立 VPN 的授權。"   // ← 覆寫上面的 Failed
        setState(VpnTunnelState.NotPrepared)
        return
    }
```

**問題** `getOrNull()` 在「拋異常」與「回傳 null」兩種情況下都是 `null`，所以 `onFailure` 設定的 `Failed` 與錯誤訊息**必定**被 `?:` 分支覆寫成 `NotPrepared`。

**為什麼在真機上會出錯** 真正的隧道建立失敗（位址衝突、另一個 VPN 剛接管、服務實例已被銷毀、`establish()` 回 null）都會被顯示成「VPN 權限要求」，`status()`（`VpnEngine.kt:156-157`）也回 `NeedsConsent`。使用者照提示去授權（其實早就授權了）永遠修不好，而 `ProtectionStatus.Failed` 這條路徑實際上不可達，診斷包裡的 `error=` 也是錯的。

**建議修法** 用 `getOrElse`／`try/catch` 區分兩種情況；`establish()` 回 null 時保留「失敗原因」（例如「系統拒絕建立 VPN（可能已被其他 VPN 取代）」），只在 `VpnService.prepare()` 非 null 時才報 `NotPrepared`。

---

## P2-2　主執行緒上的 PackageManager 工作，且目錄載入持鎖會拖住封包管線

**位置**

- `ui/AppRoot.kt:58-62`、`:119`（Compose／`LaunchedEffect` 內呼叫 `visibility()`）
- `ui/settings/SettingsScreen.kt:276`（`remember { appListVisibility() }`，組合時同步執行）
- `ui/settings/SettingsScreen.kt:356`（組合時 `backend.protectionStatus()`；在非 `Active`／`Failed`／`NotPrepared` 狀態下會走到 `tunnel.isPrepared` → `VpnService.prepare()` 的 binder 呼叫）
- `ui/settings/SettingsScreen.kt:358`（組合時 `appCatalog.getApps()`）
- `engine/apps/AppCatalog.kt:104-106`（`current()` 持 `lock` 呼叫 `load()`）、`:122-136`（逐包 `loadLabel`）

**問題** `visibility()` 每次都重新 `getInstalledPackages(0)`；`load()` 在 `synchronized(lock)` 內對每個套件呼叫 `appInfo.loadLabel(pm)`（binder + 資源載入），幾百個套件就是幾百次跨進程呼叫。`AppCatalog.current()` 的鎖是共享的：只要有人在主執行緒觸發載入，`BlockedFlowReporter` 的 `packagesForUid` / `find` 也會被阻塞。

**為什麼在真機上會出錯** 首次啟動、開啟設置頁、安裝／解除安裝 App 後（`PackageChangeReceiver` 會 invalidate）都可能出現 0.2–2 秒的主執行緒卡頓；同時封包處理執行緒被鎖住，`BlockedFlowReporter` 的 512 容量 channel 會滿（`queueFull`），造成「該詢問的連線沒有詢問」。

**建議修法** 把 `visibility()`／`getApps()` 移到 `Dispatchers.IO` 並以 `StateFlow`／`LaunchedEffect` 提供；`load()` 不要把 `loadLabel` 放在鎖內（先在鎖外建好 snapshot，再原子替換）；`protectionStatus()` 的 `prepare()` 結果快取起來，不要讓 `status()` 在組合中被反覆呼叫。

---

## P2-3　流量會重複計數；`connection_log` 永不清理；每筆阻擋都同步寫檔

**位置**

- `engine/traffic/TrafficCollector.kt:45-65`（`collectNow` 沒有互斥）
- `AppContainer.kt:148-155`（每 10 分鐘一次）、`ui/overview/OverviewScreen.kt:81-84`、`ui/apps/AppsScreen.kt:67-70`（畫面效果都會呼叫 `getTrafficToday()` → `collectNow()`）
- `data/repository/SqliteActivityStores.kt:318-322`（`applyRetention` 有實作但**沒有任何呼叫者**；`UserSettings.logRetentionDays` 完全沒被使用）
- `engine/vpn/BlockedFlowReporter.kt:143-146` → `platform/AppLog.kt:47-57`（每次阻擋都 `appendText` 開檔／關檔）

**問題**

1. `collectNow()` 用 `prefs[KEY_LAST_COLLECTED]` 當起點，讀完才寫回。兩個協程（10 分鐘迴圈 + 畫面效果）同時進入時會讀到同一個 `since`，各自把同一段 delta `trafficStore.add(...)` 一次——**流量統計翻倍**。而 Rules 發布（每次回答詢問）都會讓 `AppsScreen`／`OverviewScreen` 的 `LaunchedEffect` 重新執行。
2. `connection_log` 只增不減：`applyRetention` 沒人呼叫，`logRetentionDays` 沒被讀，長期使用後 `activity.db` 會持續膨脹（被阻擋的流量是每 5 秒每個目的地一筆聚合列），查詢與備份都會變慢。
3. `AppLog.i("flow", ...)` 在封包消費迴圈裡對每一筆阻擋做同步檔案 append；在阻擋風暴時會直接限制管線吞吐（channel 滿 → 丟包 → 少詢問）。

**為什麼在真機上會出錯** 「今日流量」數字不穩（重新整理就變多）、長期使用後 App 變慢／佔用空間、被大量阻擋時詢問變少。

**建議修法** `collectNow` 用 `Mutex` 或 `AtomicBoolean` 串行化（並在 `since` 與寫回之間保持原子）；啟動時與每日排程呼叫 `applyRetention(now - logRetentionDays)`；把 `flow` 日誌降級為 `Log.d`（只進 logcat）或改成有界佇列的非同步寫入。

---

## P2-4　勿擾統計有競態與漏計，摘要通知不會在「關閉」時取消

**位置** `core/prompts/QuietPeriodTracker.kt:26-53`、`platform/QuietPeriodNotifier.kt:27-49`、`ui/overview/OverviewScreen.kt:226`（`dismiss`）

**問題**

- `onBlocked()` 由 `VerdictPipeline.decide()` 在 `BlockedFlowReporter` 的 IO 執行緒呼叫；`startedAt` / `count` / `byApp` 由 `QuietPeriodTracker` 的 Default 執行緒每 5 秒讀寫，**完全沒有同步**（`Int` 的 `count++` 也可能丟更新）。
- 開始勿擾後，`startedAt` 要等最多 5 秒的輪詢才會被設定；這 5 秒內被阻擋的連線**不會計入**摘要。
- 勿擾結束後，最多 5 秒內仍會被計入舊視窗。
- `dismiss()` 只清 `_summary`，`QuietPeriodNotifier` 發出的通知（固定 ID 2001）不會被取消，只能等使用者點掉（`setAutoCancel`）。

**為什麼在真機上會出錯** 「勿擾摘要」的數字會少算、重算或對不上活動記錄；使用者關閉摘要後通知還留著，看起來像沒關掉。

**建議修法** 把 `onBlocked` 的計數改成 `AtomicInteger`／`ConcurrentHashMap`，或用 `MutableSharedFlow` 把事件送進單一協程處理；勿擾開始／結束用 `runtime.state.collect` 驅動而不是 5 秒輪詢；`dismiss()` 同時 `NotificationManagerCompat.cancel(2001)`。

---

## P2-5　通知權限被拒後，背景詢問完全不可見（且不會再詢問第二次）

**位置** `MainActivity.kt:35-43`（`requestNotificationPermissionIfNeeded`）、`platform/PromptNotifier.kt:28-49`、`ui/settings/SettingsScreen.kt:229-238`

**問題** `notificationPermissionAsked` 在**發起請求之前**就被設為 `true`，而且沒有處理 `onRequestPermissionsResult`；使用者若滑掉對話框或按「不允許」，App 之後再也不會請求，也不會在任何地方顯示「通知被系統關閉」。設置頁的「通知」開關只改 `preferences.notificationsEnabled`，與系統權限無關，因此可能顯示「已開啟」但實際上無法發通知。

**為什麼在真機上會出錯** 這個 App 在背景時唯一的詢問入口就是通知（`PromptOverlay` 只存在於 Activity）。權限被拒後，被阻擋的 App 只會靜默逾時（預設拒絕），使用者完全不知道發生什麼事——這是核心互動的直接失效。

**建議修法** 把「已詢問」的標記移到取得結果之後；啟動時用 `NotificationManagerCompat.areNotificationsEnabled()` 檢查並在總覽頁顯示警告／引導；或在沒有通知權限時不要只依賴通知（例如提示使用者保持 App 在前台）。

---

## P2-6　通知上的「允許／拒絕」在進程被回收後無效

**位置** `platform/NotificationActionReceiver.kt:15-33`、`platform/PromptNotifier.kt:84-94`、`core/prompts/PromptBroker.kt:124-134`

**問題** `PromptBroker` 的待處理詢問只存在記憶體。`NotificationActionReceiver` 被點擊時會拉起進程、`Application.onCreate` 重建 `AppContainer`，此時 `prompts` 是空的，`answer()` 直接回 `PromptAnswerResult(false, ...)`，**沒有任何提示**；通知本身也不會被取消。

**為什麼在真機上會出錯** 使用者從通知按「允許」，看起來什麼都沒發生，App 仍然被阻擋（而且那條通知還留著，可以重複按）。這種情況在低記憶體設備或長時間待機後很常見。

**建議修法** 通知的 action 不要只帶 prompt id，也帶上 `packagePath`／`action`／`lifetime`，在找不到 prompt 時直接建立規則；或在 `answer()` 回 `false` 時取消通知並提示「該詢問已逾時」。

---

## P2-7　`getConnectionOwnerUid` 回傳 -1 被當成「系統 UID」，`unknownOwner` 計數器永遠不準

**位置** `engine/apps/ConnectionOwnerResolver.kt:13-18`、`engine/vpn/BlockedFlowReporter.kt:87-95`、`engine/vpn/RoutingPlanner.kt:23`

**問題** `ConnectivityManager.getConnectionOwnerUid(...)` 的簽名是 `int`，查不到時回傳 **-1**（`Process.INVALID_UID`），永遠不會是 `null`。`process()` 只判斷 `uid == null`；`-1` 會落到 `VpnRoutingPlanner.isSystemUid(-1)`：Kotlin 的 `-1 % 100_000 == -1 < 10_000` 為 true，於是被當成系統 UID 跳過。`unknownOwner` 只在「包名解析不到」時增加，與真正的「查不到 owner」無關。

**為什麼在真機上會出錯** `getConnectionOwnerUid` 在 VPN 情境下偶爾查不到（連線已關閉、時序競爭），這些連線會被靜默丟棄且被計入 `systemUid`。診斷包與「未知擁有者」統計因此失真，排查「為什麼這個 App 沒被詢問」時會誤導。

**建議修法** `getOwnerUid` 回傳 `Int?`，在 `-1` 時回 `null`；`process()` 分開統計 `unknownOwner` 與 `systemUid`，並考慮在 owner 未知時 fail-open（不丟包）或至少寫一筆活動記錄。

---

## P2-8　每次啟動都會用空值覆寫系統的 per-app 語言設定

**位置** `App.kt:30-37`（`applyLanguagePreference`）、`AndroidManifest.xml`（未宣告 `AppLocalesMetadataHolderService`）

**問題** `languageTag` 為 `null`（預設「跟隨系統」）時，程式呼叫 `AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())`。在 Android 13+，這會**清除**使用者從「系統設定 → 應用程式 → 語言」設定的 per-app 語言；而 App 內的下拉選單又把自己的 `SharedPreferences` 當成唯一真實來源。

**為什麼在真機上會出錯** 使用者在系統設定把 App 語言設成英文，重啟 App 後又被改回系統語言（或反之），且沒有任何提示。多語言是此 App 的賣點之一，這種「設定被吃掉」很容易被回報成 bug。

**建議修法** 只在 `languageTag != null` 時呼叫 `setApplicationLocales`；「跟隨系統」時不要呼叫（或先讀 `LocaleManager.getApplicationLocales()` 判斷）。若要在 API < 33 持久化，補上 `AppLocalesMetadataHolderService` + `autoStoreLocales`，或維持自己存但不要在 null 時覆寫。

---

## 已檢查、未發現問題的部分

以下是本次特別檢查過、目前看起來正確的地方，供後續改動時參考（不佔用上面的條目額度）：

- `IpPacketParser` / `IpPacketBuilder`：IPv4/IPv6 標頭長度與 `totalLength` 邊界檢查完整；IPv6 擴展標頭迴圈有上限；TCP RST 的 `seq=0 / ack=seq+1`、ICMP port unreachable 的引用長度與 checksum 都符合預期。
- `FakeDnsPool` / `DnsWire`：名稱／位址池有鎖、大小固定；TTL=1 與「只回 A 記錄」是設計文件明訂的行為，不是 bug。
- `AppIconLoader`：解析失敗回 `null`，`AppIcon` 有 shield 佔位圖，不會崩潰；載入在 `produceState` 的 IO 執行緒，`LruCache` 本身是執行緒安全的。
- `PromptBroker` 的合併鍵（`userId + appPath + unknownPath + tamper`）與「稍後」的抑制鍵一致，同一 App 的多筆連線會正確合併。
- `SqliteActivityStore.write` 的聚合鍵與 `countBlockedToday` 一致（`minute_ts` 已包含在鍵內，不會跨午夜重複計數）。
- 三種語言的 `strings.xml` 格式參數（`%1$d` / `%2$s`）與呼叫端一致，未發現 `IllegalFormatConversionException` 風險。

## 建議的驗證順序（真機）

1. 建立第二個配置文件 → 在設置頁對「啟用中」的配置文件按刪除 → 應看到提示而不是閃退（對應 P0-1）。
2. 讓一個 App 觸發詢問 → 按「允許」→ 觀察 logcat 是否出現 `tunnel active` 兩次以上、UI 是否閃「保護失敗」（對應 P1-1）。
3. 快速開／關保護開關 20 次，之後確認某個被允許的 App 仍可上網（對應 P1-2）。
4. 在 MIUI / HyperOS 上關閉「取得應用程式列表」，確認應用頁與詢問的降級行為（對應 P1-3）。
5. 開著保護在 Wi-Fi ↔ 行動網路之間切換 5 次，觀察配置文件是否反覆切換；建立一個以 SSID 為條件的配置文件確認是否會生效（對應 P1-4）。
6. 對同一個被阻擋的 App 連續造訪 100+ 個不同目的地，確認詢問是否仍會出現（對應 P1-5）。
