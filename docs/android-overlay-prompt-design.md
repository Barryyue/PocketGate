# K-039 懸浮詢問面板設計（Android Kotlin）

> 狀態：設計稿，尚未實作  
> 日期：2026-11-15  
> 分支：`agent/opencode/android-kotlin-v0.3`  
> 目標版本：v0.4 / M2  
> 相關文件：[kotlin-roadmap.md](kotlin-roadmap.md)（K-039）、[progress-android.md](progress-android.md)（Avalonia 版的同名規劃，§P1）、[android-kotlin.md](android-kotlin.md)、[kotlin-feature-log.md](kotlin-feature-log.md)

## 0. 結論摘要

| 項目 | 決定 |
|---|---|
| 權限 | 清單宣告 `SYSTEM_ALERT_WINDOW`；用 `Settings.canDrawOverlays()` 檢查；不新增執行時權限；前台判斷沿用既有的 `PACKAGE_USAGE_STATS`（流量統計已在用） |
| 前景服務 | **不新增 Service**。懸浮窗由 `AppContainer` 持有的 process-scoped controller 顯示；保護開啟時 `NcVpnService` 已是前景服務，進程不會被當成一般快取進程 |
| 視窗 | `TYPE_APPLICATION_OVERLAY`；`FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL`；視窗大小等於面板本身，**預設頂部**、可選底部；不加全螢幕遮罩 |
| 前台判斷 | `UsageStatsManager.queryEvents()` 的增量追蹤器；只在「被詢問應用（同 UID）就是目前前台應用」時顯示；其餘一律通知 |
| 宿主 | `AppContainer` → `PromptRouter` + `PromptOverlayController`。不放在 `NcVpnService`（生命週期與職責都不合），也不放在 `MainActivity`（背景時不存在） |
| UI | **原生 View（XML layout）**，不用 `ComposeView`；文字沿用 `UiStrings`，確保跟隨應用內語言 |
| 通知 | 面板顯示時用新的 `PROMPT_SOFT` 渠道（`IMPORTANCE_DEFAULT`，有聲音、不 heads-up）；只有通知時用現有 `PROMPT`（HIGH）。原因：heads-up 是系統視窗，會蓋住頂部面板；完全靜音又可能在面板被隱藏時看不到 |
| 與應用內卡片 | 以 `AppUiVisibility` 判斷 `MainActivity` 是否在前台；是 → 只顯示 Compose 卡片，懸浮面板一定收起 |
| 超時 | 面板顯示期間每 10 秒 `PromptBroker.pauseTimer()`，最長 5 分鐘（`maxPromptLifetimeSeconds`）；不顯示倒數，避免「一直 hold、倒數永遠不結束」的誤導 |
| 驗證 | 純函數政策單元測試 + 模擬器 adb 清單 + 真機（至少一個 OEM ROM 與微信） |

**不在本任務範圍**：轉發模式、按域名 / IP / 端口放行（FR-PRM-05）、「允許一次」（K-038）、詢問內容顯示設定（K-040）、應用鎖（K-043）。這些只預留接口，不在 v0.4 第一版實現。

---

## 1. 權限與平台限制

### 1.1 `SYSTEM_ALERT_WINDOW`

- 在 `app/src/main/AndroidManifest.xml` 追加：

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

- 這是「特殊應用程式存取權」，不是執行時權限：
  - 檢查：`Settings.canDrawOverlays(context)`
  - 授權頁：`Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))`
  - 部分 OEM 不認得帶 `package:` 的 URI，或該 Action 不存在：依序退回不帶 data 的 `ACTION_MANAGE_OVERLAY_PERMISSION`、`ACTION_APPLICATION_DETAILS_SETTINGS`，全部用 `runCatching` 包住。
- **不要**在開啟保護時一次索要所有權限。建議只在兩個時機引導：
  1. 使用者在設定頁把「詢問顯示方式」改成「自動（懸浮面板）」且尚未授權；
  2. 設定頁的權限狀態列（常駐入口）。
- 第一次真的出現詢問但沒有權限時，**不彈權限對話框**，直接退回通知；同時寫 log `overlay:skip reason=no_permission`，避免打斷使用者。
- `Settings.canDrawOverlays()` 為 true 不代表一定加得上視窗：權限可能在顯示期間被收回，或 OEM 額外限制。`WindowManager.addView()` 的例外一律視為「不可用」，退回通知（§4.6）。

### 1.2 前景服務的關係

- 懸浮窗本身**不需要**前景服務；`WindowManager.addView()` 不是背景啟動 Activity，不受 Android 10+ 的背景 Activity 啟動限制。
- 但程序若沒有前景服務，會是 cached process，隨時可能被回收。本專案在保護開啟時已經有 `NcVpnService`（定義在 `engine/vpn/VpnTunnel.kt`，清單名稱 `.engine.vpn.NcVpnService`）以 `foregroundServiceType="systemExempted"` 常駐，這正好提供了程序存活的基礎。
- **不新增第二個前景服務**，原因：
  - Android 12+ 從背景啟動前景服務受限；若未授權時機不對會拋 `ForegroundServiceStartNotAllowedException`。
  - Android 14+ 前景服務必須宣告型別；`specialUse` 需要額外 manifest property 與商店審查，`systemExempted` 只適用 VPN 類。
  - 會多一條常駐通知，與現有保護通知重複，使用者體驗差。
  - 懸浮窗的顯示/移除不需要 Service 生命週期；`AppContainer` 已經是 process-scoped。
- 保護關閉時 VPN 服務停止，程序可能被回收；此時也不會有新的詢問。已存在的待決詢問由 `PromptBroker` 逾時處理，懸浮面板在偵測到保護關閉時立即收起（§4.5）。

### 1.3 Android 12+ 的限制

| 限制 | 影響 | 對策 |
|---|---|---|
| `WindowManager.LayoutParams#setHideOverlayWindows(true)`（API 31） | 任何應用的視窗都可以要求系統隱藏所有非系統懸浮窗；銀行、支付、部分微信小程序/支付頁可能這樣做 | 無法覆寫。通知永遠保留作為可找回的入口；`View.isShown` / `onWindowVisibilityChanged` 只能盡力而為，不能當唯一依據 |
| 系統對話框 / 權限頁 | 系統在敏感畫面會自動隱藏第三方懸浮窗 | 同上；不要假設面板一定可見 |
| Untrusted touch（Android 12+） | 觸控若「穿過」不信任的懸浮窗，且該窗的遮蔽不透明度超過 0.8，會被系統攔截 | 視窗大小**等於面板**，不加全螢幕透明遮罩，不設 `FLAG_NOT_TOUCHABLE`；面板背景保持不透明 |
| 背景 Activity 啟動限制（Android 10+） | 從背景直接 `startActivity` 可能被拒 | 面板按鈕只回答詢問，不開 Activity；要開應用程式一律走通知的 `PendingIntent`（使用者主動點擊） |
| 通知 trampoline 限制（Android 12+） | 通知的 `PendingIntent` 不能先到 BroadcastReceiver 再開 Activity | 現有 `NotificationActionReceiver` 只回答詢問，不開 Activity，已合規；維持這個做法 |
| `POST_NOTIFICATIONS`（Android 13+） | 通知被拒時，通知兜底不可見 | 懸浮面板與應用內卡片更重要；設定頁顯示通知狀態；全部不可用時只能逾時採預設動作 |
| 前景服務啟動限制（Android 12+） | 見 §1.2 | 不新增 Service |

補充：

- `TYPE_APPLICATION_OVERLAY` 位於 Activity 之上、系統列與輸入法（IME）之下。**IME 會蓋住懸浮窗**，這是本設計把預設位置放在頂部的主要原因（§2.2、§6.2）。
- 應用可以透過 `FLAG_SECURE` 阻止自己的內容被截圖/錄屏，但**不會**隱藏懸浮窗；真正會隱藏的是 `setHideOverlayWindows` 與系統敏感畫面。
- Android 13+ 對側載應用可能把部分特殊權限收在「限制設定」；若授權按鈕不可用，先請使用者到應用程式資訊允許受限設定。此行為依 ROM 而異，必須真機確認。

---

## 2. 視窗設計

### 2.1 型別與 flags

使用 `WindowManager` + `TYPE_APPLICATION_OVERLAY`（API 26+；本專案 minSdk 29）：

| 參數 | 值 | 理由 |
|---|---|---|
| `type` | `TYPE_APPLICATION_OVERLAY` | 第三方應用唯一合法的系統層級視窗型別；`TYPE_PHONE` / `TYPE_SYSTEM_ALERT` 在 API 26+ 需要系統權限，不能用 |
| `flags` | `FLAG_NOT_FOCUSABLE` | 不搶輸入法與鍵盤焦點；代價是收不到返回鍵（§2.4） |
|  | `FLAG_NOT_TOUCH_MODAL` | 面板以外的觸控照常傳給下面的應用 |
|  | `FLAG_HARDWARE_ACCELERATED` | 圓角、陰影、文字渲染較順 |
| `gravity` | `TOP or CENTER_HORIZONTAL`（預設） / `BOTTOM or CENTER_HORIZONTAL`（選項） | 見 §2.2 |
| `width` | `min(screenWidth - 2 * 16dp, 560dp)` | 兩側留 16dp 給系統返回手勢；平板不至於過寬 |
| `height` | `WRAP_CONTENT` | 不覆蓋整個畫面；面板以上/以下的觸控自然穿透 |
| `format` | `PixelFormat.TRANSLUCENT` | 圓角外的透明區域 |
| `alpha` | `1.0f` | 避免 Android 12 untrusted touch 的 0.8 門檻 |
| `title` | `"NetworkControl prompt"` | `dumpsys window` 可辨識；也提供無障礙視窗標題 |
| `packageName` | `context.packageName` | 讓視窗歸屬清楚 |
| `setFitInsetsTypes`（API 30+） | `0`，由 View 自己處理 inset | 避免系統自動 inset 與我們的手動位移疊加；若實測有問題改回預設並簡化 |
| `layoutInDisplayCutoutMode` | `NEVER`（頂部時） | 不侵入瀏海/挖孔區 |
| `softInputMode` | 不設 | 非 focusable 視窗與 IME 無關 |
| `FLAG_DIM_BEHIND` | 不使用 | 會讓下面變暗且增加觸控遮蔽，違反「不影響目前應用」 |
| `FLAG_WATCH_OUTSIDE_TOUCH` | 不使用 | 要接收 outside touch 就得開大視窗，會吞掉下面應用的觸控 |
| `FLAG_SECURE` | 不使用 | 允許使用者截圖回報問題；隱私需求由 K-040 處理 |

`WindowManager.LayoutParams` 由 `PromptOverlayController` 在**主執行緒**建立與更新；`addView` / `removeView` 也必須在主執行緒。

### 2.2 尺寸與位置：建議預設「頂部」

| 方案 | 優點 | 缺點 |
|---|---|---|
| **頂部（建議預設）** | 不會被 IME 蓋住；不擋微信底部輸入欄與發送鍵；不與底部手勢導航衝突；打字時仍看得見 | 單手較難觸及；可能蓋住應用標題列；瀏海/狀態列要處理 inset |
| 底部 | 拇指可及；與系統「底部彈框」直覺一致；參考機（紅魔）就是底部 | `TYPE_APPLICATION_OVERLAY` 在 IME 之下，鍵盤打開時面板直接消失；鍵盤關閉時蓋住微信輸入欄；靠近手勢區 |
| 自動 | 理想 | 目前沒有可靠、免權限的 IME 高度來源；「自動」等於把不確定性留給使用者 |

**決定：第一版預設頂部，設定提供「底部」選項。** 這是對 [progress-android.md](progress-android.md) 中「底部對齊」的刻意偏離，理由就是微信場景：使用者最常在聊天輸入時觸發詢問，底部面板在這兩個狀態都不理想（鍵盤打開 → 被 IME 蓋住；鍵盤關閉 → 蓋住輸入欄）。若產品堅持底部，必須先完成 §2.5 的 IME 位移並在真機驗證，否則不應預設底部。

尺寸：

- 寬度：`screenWidth - 32dp`，最大 `560dp`；兩側各留 16dp，讓 Android 10+ 的左右返回手勢有起點。
- 高度：`WRAP_CONTENT`；內容最多約 40% 螢幕高。目標行最多 2 行、`ellipsize="end"`；連線數多時只顯示前 3 個不同目標 + 「等」。
- 頂部位置：`y` 加上狀態列高度 + 8dp；有瀏海時用 `displayCutout` inset。注意部分版本 / `setFitInsetsTypes` 設定會讓視窗已經從狀態列下方開始，這時 `y` 只需 8dp；用 `WindowInsets` 實測校正，避免重複加狀態列高度。
- 底部位置：`y` 加上導航列高度 + 8dp；**不要把導航列高度做成 root 的 bottom padding**，否則透明 padding 仍屬於視窗範圍，會吞掉系統手勢區的觸控。用 `LayoutParams.y` 或 `translationY` 把整個視窗抬起來。
- 平板/橫向：最大寬度 560dp；橫向時若高度不足，內容可縮成「標題 + 目標 + 按鈕」，時效 chips 允許換行或改為下拉。
- 旋轉/視窗尺寸變化：監聽 `ComponentCallbacks.onConfigurationChanged`（或 `DisplayManager.DisplayListener`），重新計算 `LayoutParams`；不要重新建立整個 controller。

### 2.3 觸控穿透

- 視窗範圍 = 面板範圍；面板以外的座標不屬於這個視窗，搭配 `FLAG_NOT_TOUCH_MODAL`，觸控直接送到下面的應用。
- 不要用「全螢幕透明根佈局 + 中間卡片」的做法：
  - 全螢幕會吞掉面板以外的所有觸控；
  - Android 12+ 會觸發 untrusted touch；
  - 也無法實現「點外面關閉」與「穿透」同時成立。
- 面板左右各留 16dp 在視窗外，是留給系統手勢的；不要為了陰影把視窗擴大到螢幕邊緣。
- 面板內部的 padding 是視窗的一部分，會消耗觸控，這是預期行為（卡片本身是點擊區）。
- 不設 `FLAG_NOT_TOUCHABLE`（那會讓按鈕也收不到觸控）。

### 2.4 返回鍵

- `FLAG_NOT_FOCUSABLE` 的視窗收不到 key event，因此**返回鍵會傳給下面的應用**（例如微信返回上一頁），面板不會關閉。
- 建議接受這個行為：
  - 面板有明確的「稍後」與關閉按鈕；
  - 逾時會自動消失；
  - 不搶焦點才不會打斷微信輸入（FR-PRM-07：不搶奪目前視窗的鍵盤焦點）。
- 若產品要求「返回鍵 = 稍後」，唯一做法是讓視窗可 focus（拿掉 `FLAG_NOT_FOCUSABLE`）。這會：
  - 讓下面的應用失去焦點、鍵盤收起；
  - 可能在微信輸入過程中造成輸入中斷；
  - 讓 IME 與版面行為更複雜。
  **不建議**；若真的要做，應做成設定項並在真機單獨驗證，不能預設開啟。

### 2.5 IME / insets

- `TYPE_APPLICATION_OVERLAY` 在 IME 之下；即使設定 `SOFT_INPUT_ADJUST_RESIZE` 也不會改變這個事實。
- 頂部模式：只需要處理狀態列 / `displayCutout`，不需要處理 IME。
- 底部模式（選項）：
  1. `View.setOnApplyWindowInsetsListener` 監聽 `WindowInsetsCompat.Type.ime()`；
  2. 若 `isVisible(ime)` 且 `getInsets(ime).bottom > 0`，把面板往上移相同的 px（用 `translationY` 或更新 `LayoutParams.y`）；
  3. 同時用 `navigationBars()` 避免壓到導航列。
- 不是所有 ROM / 版本都會把 `ime()` inset 派發給非 focusable 的 overlay window。實作要有兩層 fallback：
  - `rootView.getWindowVisibleDisplayFrame()` 與螢幕高度比較，差距大於 15% 視為鍵盤開啟，往上移；
  - 都拿不到 → 記錄 `overlay:ime_unknown`，底部模式的使用者可能看不到面板；因此**預設頂部**。
- 不要監聽 `WindowInsetsAnimation` 來做複雜動畫；面板只需要一次位移，動畫反而容易抖動。
- 面板顯示期間不改變 `softInputMode`，也不呼叫 `InputMethodManager`；保持下面應用的輸入狀態。

### 2.6 鎖屏與熄屏

- `TYPE_APPLICATION_OVERLAY` 不能顯示在鎖屏之上；不要嘗試 `FLAG_SHOW_WHEN_LOCKED` / `FLAG_TURN_SCREEN_ON`（這些是 Activity 的旗標，且已不建議使用）。
- 顯示前檢查：
  - `PowerManager.isInteractive`
  - `KeyguardManager.isKeyguardLocked`
- 動態監聽（`ContextCompat.registerReceiver`，Android 13+ 用 `RECEIVER_NOT_EXPORTED`）：
  - `ACTION_SCREEN_OFF` → 立即收起面板、取消 hold job；
  - `ACTION_SCREEN_ON` → 不自動顯示，等 `ACTION_USER_PRESENT` 或下一次前景輪詢；
  - `ACTION_USER_PRESENT` → 重新評估：若被詢問應用仍是前台且詢問仍存在，才重新顯示。
- 不要在螢幕關閉時叫醒螢幕；詢問會留在通知與 `PromptBroker`，使用者解鎖後再處理。
- 已知限制：`PromptBroker.pauseTimer()` 只會延長 deadline，沒有「縮短 / 恢復原 deadline」的方法。面板在螢幕關閉前已 hold 過的詢問，可能最長存活到 `maxPromptLifetimeSeconds`（預設 300 秒）。這是可接受的：解鎖後若應用仍在前台，面板會再次出現；否則只剩通知。

### 2.7 生命週期與執行緒

- `PromptOverlayController` 由 `AppContainer` 建立，生命週期 = 程序；不需要 `onDestroy`，但提供 `dispose()` 供測試/未來使用。
- 所有 `WindowManager` 操作在主執行緒（`Dispatchers.Main.immediate` 或 `Handler(Looper.getMainLooper())`）。
- `UsageStatsManager.queryEvents()` 在 `Dispatchers.IO`；不可在主執行緒查詢。
- `addView` / `removeView` 用 `runCatching` 包住，例外寫 log 並回報 router 退回通知：
  - `WindowManager.BadTokenException`
  - `SecurityException`（權限被收回）
  - `IllegalStateException`（view 已有 parent / 重複 add）
- `removeView` 前先確認 `view.parent != null`；移除後把 `view`、`currentPromptId`、hold job 清空，避免 `WindowLeaked`。
- 程序死亡時系統會自動移除視窗，不會留下殘影；但程序存活期間必須自己清理。

---

## 3. 前台應用判斷

### 3.1 候選方案

| 方案 | 權限 | 準確度 | 延遲 | 成本 | 結論 |
|---|---|---|---|---|---|
| `UsageStatsManager.queryEvents()` | `PACKAGE_USAGE_STATS`（已在清單，流量統計已用） | AOSP 好；OEM 可能延遲/缺事件 | 約 0.2–2 秒 | 低（只在有詢問時輪詢） | **建議** |
| `ActivityManager.getRunningAppProcesses()` | 無 | 只能看到自己的程序（Android 5.1+） | — | — | 不可行 |
| `ActivityManager.getRunningTasks()` | 無 | 已 deprecated，第三方只看得到自己的 task | — | — | 不可行 |
| `ActivityTaskManager.getFocusedRootTask()` 等隱藏 API | 系統權限 | 最好 | 即時 | 非 SDK 介面，隨時失效 | 不可用 |
| `AccessibilityService` | `BIND_ACCESSIBILITY_SERVICE` + 使用者手動開啟 | 最好；可看到視窗型別與 IME | 即時 | 權限敏感、耗電、商店政策、OEM 易被殺 | 未來可選，不作為 v0.4 方案 |
| 自家 Activity 的 `onResume` | 無 | 只能知道自己 | 即時 | 無 | 只用來判斷「應用內卡片是否可見」 |
| NotificationListener / MediaSession 推斷 | 使用者授權 | 不可靠 | — | 中 | 不採用 |

`PACKAGE_USAGE_STATS` 與流量統計共用同一個 appop，使用者不需要再授權一次；設定頁的「使用情況存取權」狀態與跳轉可直接重用 `AppBackend.hasTrafficAccess()` / `trafficAccessIntent()`。建議把 `TrafficCollector.isAvailable` 的檢查抽成 `platform/UsageAccess`，兩邊共用，避免邏輯分叉。

### 3.2 建議的追蹤器

新增 `platform/prompt/PromptForegroundTracker.kt`：

```kotlin
data class ForegroundApp(
    val packageName: String,
    val uid: Int,
    val resumedAt: Long,
)

class PromptForegroundTracker(private val context: Context) {
    suspend fun current(): ForegroundApp? = withContext(Dispatchers.IO) { /* queryEvents + 增量狀態 */ }
}
```

實作要點：

1. **增量查詢**：第一次往回查 5 分鐘，之後每次只查 `lastQueryTime - 1s` 到 `now`，避免每秒掃大量事件。只有在 `PromptBroker.pending` 非空時才輪詢（建議 1 秒一次），pending 清空就停止。
2. **事件狀態機**：
   - `ACTIVITY_RESUMED`：記下最新 package / timestamp；
   - `ACTIVITY_PAUSED` / `ACTIVITY_STOPPED`：若是目前追蹤的 package，清除前台狀態，直到下一個 `ACTIVITY_RESUMED`；
   - `SCREEN_NON_INTERACTIVE` / `DEVICE_SHUTDOWN` / `KEYGUARD_SHOWN`：清除；
   - `SCREEN_INTERACTIVE`：不直接視為前台，等 Activity 事件。
3. **應用程式（package）→ UID**：用 `PackageManager.getPackageUid(packageName, 0)` 取得 UID；`UsageEvents` 只給 package。共享 UID 的應用（同一 UID 多個 package）用 UID 比較，任一 package 在前台都算。
4. **排除項**：
   - 自己（`context.packageName`）→ 不顯示懸浮面板；
   - 桌面 launcher：用 `Intent(ACTION_MAIN).addCategory(CATEGORY_HOME)` 查出所有 home package，快取；
   - `com.android.systemui`、`android`、`com.android.permissioncontroller` / `com.google.android.permissioncontroller` → 不顯示；
   - 查不到 UID / package 不在 `AppCatalog` → 不顯示（無法確認身分）。
5. **不確定就回通知**：查不到、事件太舊、權限被收回、OEM 回傳空事件，一律回傳 `null`，由政策退回通知。不要「猜」一個前台應用。
6. **鎖屏 / 解鎖校正**：螢幕關閉或 `KEYGUARD_SHOWN` 時清除追蹤狀態；`ACTION_USER_PRESENT` 後強制重新查詢一次完整窗口（5 分鐘），避免鎖屏期間的舊 `ACTIVITY_RESUMED` 讓面板在解鎖後顯示在錯誤的應用上。

### 3.3 是否只在被詢問應用是前台時顯示

**建議：是，預設只在同 UID 的前台應用顯示。**

理由：

- 使用者在 Chrome/微信/遊戲裡操作時，突然彈出另一個背景應用的詢問，是這類懸浮面板最常被抱怨的情境；
- 背景應用（同步、推送、定位）不應該打斷目前操作；
- 顯示在錯誤的應用上，使用者可能誤點「允許」，造成安全風險；
- 通知本來就是背景詢問的正確通道，且可從通知列找回。

規則表：

| 情境 | 顯示方式 |
|---|---|
| 本應用（`MainActivity`）在前台 | 應用內 Compose 卡片；懸浮面板收起；通知改用 `PROMPT_SOFT` |
| 被詢問應用（同 UID）在前台、螢幕亮著且解鎖、兩個權限都有、模式為自動 | 懸浮面板 |
| 被詢問應用在後台 / 螢幕鎖定或關閉 / 權限不足 / 模式為只用通知 | 通知（現有高重要性渠道） |
| 前台是桌面、SystemUI、權限控制器、本應用 | 通知（或本應用卡片） |
| 前台應用未知（UsageStats 查不到） | 通知 |
| 同一應用有多個詢問（例如簽章變更與一般詢問） | 只顯示最舊的一個；其餘通知 |
| 不同應用都有待決詢問 | 只顯示與目前前台 UID 相符的那一個；其餘通知；切換前台時重新選擇 |

補充：

- 這裡的「前台」是 UID 級別：微信的 `com.tencent.mm:push` 程序在背景跑同步，不會讓微信變成前台；只有它的 Activity `ACTIVITY_RESUMED` 才算。這是我們要的行為。
- 如果使用者在微信裡，微信的背景同步被攔，會顯示面板。這是刻意的：使用者正在看微信，讓他知道「微信正在連哪裡」比默默擋掉更有價值。若未來覺得吵，可以再加「必須在最近 10 秒內有 Activity 事件」的收緊條件，但第一版不做。
- 分屏 / 子母畫面（PiP）下，「前台」的語義不精確；UsageStats 可能回報最後獲得焦點的 Activity。這是已知限制，必要時在設定說明中提示。

---

## 4. 在現有架構中的位置

### 4.1 現況

- `PromptBroker`（`core/prompts/PromptBroker.kt`）是唯一的詢問狀態來源：
  - `pending: StateFlow<List<PromptSnapshot>>`
  - `answer(promptId, PromptDecision)`、`dismiss(promptId)`、`pauseTimer(promptId)`、`cancelAll()`
- `PromptNotifier`（`platform/PromptNotifier.kt`）目前**同時**負責：
  - 訂閱 `broker.pending`；
  - 為每個詢問建立/取消通知；
  - 處理 `notificationsEnabled`。
- `PromptOverlay`（`ui/prompt/PromptOverlay.kt`）是 `MainActivity` 裡的 Compose 底部卡片，直接讀 `AppBackend.prompts`，並每 10 秒 `holdPrompt`。
- `AppContainer` 目前建立並啟動 `promptNotifier`；`MainActivity` 只有 `onResume` 呼叫 `vpnEngine.requestReplan()`，**沒有任何「應用是否在前台」的狀態**。

### 4.2 宿主比較

| 宿主 | 優點 | 缺點 | 結論 |
|---|---|---|---|
| `MainActivity` | 可直接用 Compose、主題/語言現成 | 只有 Activity 在前台時存在；背景詢問完全做不到 | 不可行 |
| `NcVpnService` | 保護開啟時一定存在；是前景服務 | 把 UI 職責塞進引擎；保護關閉 / 服務重啟時要處理視窗與 pending 的競態；`NcVpnService` 在 `VpnTunnel.kt` 內，與引擎耦合；單元測試困難 | 不建議 |
| 新增專用 Service | 生命週期獨立 | 額外前景通知；Android 12+ 背景啟動限制；Android 14+ FGS 型別；OEM 更容易殺；沒有實際好處 | 不建議 |
| `AppContainer` 內的 process-scoped controller | 最簡單；不新增 Service / 通知；`AppContainer` 本來就是程序級；VPN 前景服務提供程序存活；可注入假前台/假時鐘做測試 | 程序被殺時視窗消失（VPN 也一起消失，沒有新詢問）；必須嚴格主執行緒 | **建議** |

`AppContainer` 已經持有 `prompts`、`appCatalog`、`theme`、`preferences`、`protection`、`scope`，在同一個地方組裝 controller 最自然；也不需要改 `App.kt` 的初始化順序（只多註冊一個 `ActivityLifecycleCallbacks`）。

### 4.3 建議的組件拆分

```
PromptBroker.pending
        │
        ▼
  PromptRouter（唯一訂閱者）
   ├── 決定：InApp / Overlay / Notification
   ├── PromptNotifier（通知呈現器，不再自己訂閱）
   └── PromptOverlayController（WindowManager）
```

新增類別（建議放在 `platform/prompt/`）：

| 類別 | 職責 |
|---|---|
| `PromptPresentationPolicy`（**純 Kotlin，不 import android**） | 輸入 `List<PromptCandidate>` + `PresentationEnvironment`，輸出 `PromptSelection`；可單元測試 |
| `PromptForegroundTracker` | `UsageStatsManager` 增量查詢、UID 映射、排除 launcher/SystemUI |
| `AppUiVisibility` | `Application.registerActivityLifecycleCallbacks` 追蹤本應用 Activity 是否 resumed |
| `PromptOverlayController` | 建立/更新/移除 `WindowManager` 視窗；綁定原生 View；hold job；insets/螢幕事件 |
| `PromptRouter` | 收集 `pending`、`uiVisible`、`protection.enabled`、前景輪詢；呼叫 policy；驅動 notifier + overlay；處理 fallback 與去重 |
| `PromptOverlayViewBinder`（可合併到 controller） | 把 `PromptSnapshot` 綁到 View；按鈕/時效/1 秒防誤觸；文字用 `UiStrings` |

修改既有檔案：

| 檔案 | 變更 |
|---|---|
| `platform/PromptNotifier.kt` | 拆出「訂閱」職責；新增 `render(prompt, channel)`、`cancel(promptId)`、`cancelAll()`；保留通知內容與 action；加 `setOnlyAlertOnce(true)` |
| `platform/NotificationChannels.kt` | 新增 `PROMPT_SOFT` 渠道（`IMPORTANCE_DEFAULT`，無 heads-up）；面板/卡片顯示時使用，其餘用 `PROMPT` |
| `platform/AppIconLoader.kt` | 追加 `loadBitmap(context, packageName): Bitmap?`（或 `loadDrawable`）給原生 `ImageView`；Compose 的 `load()` 保持不變 |
| `AppContainer.kt` | 建立 `AppUiVisibility`、`PromptForegroundTracker`、`PromptOverlayController`、`PromptRouter`；init 中啟動 router 取代 `promptNotifier.start()` |
| `App.kt` 或 `AppContainer` | 註冊 `ActivityLifecycleCallbacks`（由 `AppUiVisibility` 自己做，避免 `App.kt` 膨脹） |
| `AppPreferences.kt` | 新增 `promptPresentationMode`（`auto` / `notification`）、`overlayPosition`（`top` / `bottom`） |
| `AppBackend.kt` | 新增模式/位置的 getter/setter、overlay 權限狀態與 intent；`refreshNotifications()` 改呼叫 `router.refresh()`；切換語言/主題/通知開關後刷新 |
| `ui/settings/SettingsScreen.kt` | 新增「詢問顯示」卡片：模式、位置、權限狀態、OEM 說明、授權按鈕 |
| `ui/UiStrings.kt` | 在 `S` 列舉**末尾**追加新鍵，並補 EN / CN / TW 三份 map |
| `AndroidManifest.xml` | 加 `SYSTEM_ALERT_WINDOW` |
| `engine/traffic/TrafficCollector.kt` | （可選重構）改用共用的 `UsageAccess` 檢查；行為不變 |
| `res/layout/prompt_overlay_panel.xml` + drawable/colors | 原生面板版面與樣式 |
| `res/values/strings.xml`、`values-b+zh+Hans/strings.xml`、`values-b+zh+Hant/strings.xml` | 通知渠道名稱等系統可見文字 |

不需要新依賴：`AppUiVisibility` 用 framework 的 `ActivityLifecycleCallbacks`，不引入 `lifecycle-process`；原生 View 不引入 Material Components。

### 4.4 為什麼 `PromptRouter` 要成為唯一訂閱者

目前 `PromptNotifier` 直接訂閱 `pending`，如果再加一個 overlay controller 也訂閱，會出現：

- 同一個詢問同時被兩個呈現器顯示；
- 通知與面板的「已顯示 / 已移除」狀態不同步；
- `notificationsEnabled` 切換時只有通知那邊知道；
- 未來要加第三個呈現器（例如車機/桌面模式）要再改一輪。

`PromptRouter` 是唯一的 `pending` 訂閱者；`PromptNotifier` 與 `PromptOverlayController` 都只接受「渲染某個 prompt / 取消某個 prompt」的命令，不自己讀 `PromptBroker`。應用內 Compose 卡片是例外：它繼續直接讀 `backend.prompts`，因為它屬於 UI 層，且只在 `MainActivity` 可見時運作；router 透過 `AppUiVisibility` 避免與它重複。

### 4.5 生命週期與清理

| 事件 | 動作 |
|---|---|
| `pending` 變空 | overlay 收起；取消 hold job；通知由 notifier 取消 |
| 詢問被回答 / 逾時 / 稍後 | `pending` 更新 → router 重新渲染；若目前顯示的 id 不在清單，overlay 立即收起 |
| `MainActivity` resumed | `AppUiVisibility` = true → overlay 收起；Compose 卡片顯示；通知改用 `PROMPT_SOFT` |
| `MainActivity` paused | `AppUiVisibility` = false → router 重新評估是否顯示面板 |
| 保護關閉 / VPN revoke | 收起 overlay；可選：由 `AppContainer` 監聽 `protection.enabled` 並呼叫 `PromptBroker.cancelAll()`（待確認），避免留下沒有 UI 的 pending |
| 螢幕關閉 / 鎖屏 | 收起 overlay；取消 hold；通知保留 |
| 權限被收回 | 下次 `addView` 失敗 → overlay 退回通知；log；在設定頁顯示未授權 |
| 程序死亡 | 系統移除視窗；沒有殘留；VPN 也一起停止（always-on 例外）。下次啟動時取消所有殘留的 prompt 通知 |
| 語言 / 主題變更 | router `refresh()`；若面板正在顯示，重新綁定文字/顏色，不要重新 addView |
| 通知開關變更 | settings 呼叫 `backend.setNotificationsEnabled()` → `router.refresh()`；overlay 不受通知開關影響 |

`PromptOverlayController.dispose()` 只在測試或未來多容器場景使用；正式程序不需要主動呼叫。

程序死亡後的通知殘留：`PromptNotifier` 的 `shown` map 只在記憶體；建議把 prompt 通知改用固定 tag（例如 `prompt`）+ 持久化 id 集合，App 啟動時取消清單內已不存在的通知，避免使用者看到已逾時的詢問。

### 4.6 AddView 失敗與 fallback

`addView` 可能失敗的原因：

- 權限在顯示期間被使用者收回；
- OEM 的「背景彈出界面」未授權；
- 視窗 token / display 無效（少見）；
- 程序正在切換使用者/工作資料夾。

處理：

1. `runCatching { windowManager.addView(view, params) }`；
2. 失敗 → `AppLog.w("overlay", "add failed: ...")`，設定 `overlayUnavailableUntil = now + 30s`，避免每次詢問都重試；
3. router 改用通知（高重要性）；
4. 若面板已顯示但 `view.isAttachedToWindow == false` 或 `view.isShown == false`（且螢幕亮著），在 500ms 後再檢查一次；仍不可見 → 收起並把該詢問的通知升回高重要性；
5. 不要用 Toast/對話框告知失敗（背景 Toast 在 Android 12+ 也受限）；寫 log 與設定頁狀態即可。

### 4.7 通知整合：避免重複與「面板被蓋住卻靜音」

現有 `PromptNotifier` 對每個 pending 都用 `NotificationChannels.PROMPT`（IMPORTANCE_HIGH，會懸浮）。加入面板後有三種做法：

| 方案 | 行為 | 風險 |
|---|---|---|
| A. 通知永遠高重要性 | 面板 + 一次性 heads-up | heads-up 是系統視窗，**會蓋住頂部面板**；面板被 IME/`setHideOverlayWindows` 蓋住時仍看得到通知 |
| B. 面板顯示時改用完全靜默渠道 | 面板 + 通知列安靜項目 | 面板若被鍵盤或系統隱藏，使用者可能完全看不到 |
| C. 面板顯示時改用 `IMPORTANCE_DEFAULT`（有聲音、不 heads-up） | 面板 + 一次提示音 + 通知列 | 聲音重複，但不會遮住面板，也比 B 安全 |

**建議：預設頂部面板搭配 C（`PROMPT_SOFT`）。**

- 頂部面板不會被 IME 蓋住，所以不需要靠 heads-up 來保證可見；反而是 heads-up 會蓋住面板（兩者都在螢幕上方）。`IMPORTANCE_DEFAULT` 有提示音、不 heads-up，兼顧「不打擾視線」與「面板被 `setHideOverlayWindows` 隱藏時仍有聲音/通知列」。
- **底部模式**（選項）在 IME 位移尚未真機驗證前，先維持 `PROMPT`（HIGH）：因為底部面板可能被鍵盤蓋住，需要 heads-up 提醒。等 §2.5 的 IME 處理驗證通過，再改成 `PROMPT_SOFT`。
- 完全靜默（`IMPORTANCE_LOW`）暫不建議：面板被 `setHideOverlayWindows` 或系統對話框隱藏時，使用者可能完全不知道有詢問。
- Android 8+ 的渠道重要性建立後不可由程式修改；`PROMPT_SOFT` 要一次定好 `IMPORTANCE_DEFAULT`。若之後要調成 LOW，只能換新的 channel id（例如 `prompts_soft_v2`）。
- 從 `PROMPT_SOFT` 切回 `PROMPT`（應用離開前台、只剩通知）時，先 `cancel(id)` 再 `notify(id, highNotification)`，確保 heads-up 真的會出現；同一 id 換渠道的行為要在真機用 `dumpsys notification` 驗證。
- 所有通知都加 `setOnlyAlertOnce(true)`，避免 `Updated` 時反覆響鈴。
- `notificationsEnabled == false`：cancel 所有通知；overlay 仍可運作（通知開關不應該關掉懸浮面板）。若 overlay 也不可用，詢問只能靠應用內卡片或逾時；設定頁要提示這個組合的風險。

---

## 5. 面板 UI 與狀態同步

### 5.1 內容

建議版面（文字為繁體中文示例）：

```
┌──────────────────────────────────────────┐
│ [icon]  已暫時阻止「微信」連線           │
│         目標：example.com:443 · TCP      │
│         共 3 個連線 · 另有 1 個詢問      │
│ 允許後，應用程式重新連線即可上網。       │
│ [永久] [1 小時] [直到重新啟動]           │
│ 稍後                            [拒絕][允許] │
└──────────────────────────────────────────┘
```

| 區塊 | 內容 | 說明 |
|---|---|---|
| 圖示 | `PackageManager.getApplicationIcon`（沿用 `AppIconLoader` 的快取） | 抓不到時用 `ic_shield` |
| 標題 | `已暫時阻止「%1$s」連線`；`isConnectionHeld == false` 時用 `「%1$s」的連線已被阻止` | 不說「WLAN / Wi-Fi」：黑洞方案是按應用（UID）分流，不是按網路類型 |
| 目標 | `flow.domains.first()`，否則 `host:port`；加協議（TCP/UDP） | 域名來自假 DNS 反查，是我們的優勢；IPv6 用 `[addr]:port` |
| 連線數 | `共 %1$d 個連線` | `prompt.flows.size > 1` 才顯示 |
| 佇列 | `另有 %1$d 個詢問` | 只顯示總數，不列其他應用名稱，降低干擾 |
| 說明 | `此連線已被暫時阻止，允許後應用程式重新連線即可上網。` | 沿用 `S.PromptBlockedHint` |
| 簽章警告 | `此應用程式的簽章已變更，請謹慎判斷。` | `isTamperAlert`；紅色/警告樣式；預設時效改成「直到重新啟動」 |
| 時效 | 永久 / 1 小時 / 直到重新啟動 | 與應用內卡片一致；`Timed` 的 `durationSeconds = 3600` |
| 按鈕 | 稍後 / 拒絕 / 允許 | 拒絕在左、允許在右，與現有卡片一致；「稍後」= `dismiss`（拒絕目前連線 + 同一應用 10 分鐘不再詢問） |
| 關閉 | `×`（可選） | 與「稍後」相同；如果空間不足可省略，靠逾時 |

不要顯示：

- 「已封鎖 WLAN」——我們沒有按網路類型攔截；
- 「僅本次」——K-038 尚未實現；第一版不要放灰色按鈕，避免使用者以為壞了；
- 精確倒數——面板顯示期間會 hold，倒數會一直往後跳；可放一個很小的「最多 5 分鐘」說明，或不放。

### 5.2 動作與 lifetime

| 動作 | 呼叫 | 參數 | 結果 |
|---|---|---|---|
| 允許 | `PromptBroker.answer(id, PromptDecision(...))` | `action=Allow`, `scope=AnyConnection`, `lifetime=chip`, `durationSeconds=3600`（Timed 時） | 建立規則、解決相符的 flows；面板依 `pending` 更新或收起 |
| 拒絕 | 同上 | `action=Deny` | 同上 |
| 稍後 / 關閉 | `PromptBroker.dismiss(id)` | — | 拒絕目前 flows；同一應用 10 分鐘內不再詢問 |
| 通知的允許/拒絕 | 既有 `NotificationActionReceiver` | 永久 | 先到先贏；面板收到 `pending` 更新後自行收起 |

實作細節：

- 所有動作都走 `PromptBroker`，**不要**直接寫 `RuleService` 或資料庫；規則生成、覆蓋範圍、生命週期都由 Core 負責。
- `PromptDecision.applyToAllFlows` 保持預設 `true`（與應用內卡片、通知一致）；第一版不提供「只此連線」。
- 按鈕按下後立即禁用全部動作按鈕，避免連點；`PromptBroker.answer` 第二次會回傳 `found=false`，忽略即可。
- **1 秒防誤觸（FR-PRM-07）**：面板第一次綁定後 1000ms 內，`拒絕`/`允許`/`稍後` 都設 `isEnabled=false`；時間到才啟用。用 `view.postDelayed`，收起時 `removeCallbacks`。
- 可選：動作按鈕設 `setFilterTouchesWhenObscured(true)`，避免被其他懸浮窗遮擋時誤觸。
- 若 `answer` 拋例外（例如規則寫入失敗）：log；不要收起面板，讓使用者可以重試；不要彈 Toast（背景限制）。
- 若 `PromptAnswerResult.found == false`：詢問已不存在（另一個通道先回答/逾時），直接收起，不報錯。

### 5.3 佇列與多詢問

`PromptBroker` 已經按 `MergeKey(userId, appPath, unknownPath, tamper)` 合併同一應用的多條連線；所以同一個應用通常只有一個 prompt（簽章變更會另外一個）。

面板層級的規則：

1. **同時最多一個面板**。
2. 候選 = `pending` 中「UID 等於目前前台 UID」的 prompts；選 `createdAt` 最舊的一個。
3. 不同應用的 prompts 不排在面板隊列裡，而是留在通知；使用者切換到那個應用時，1 秒內重新評估並切換面板。
4. 面板顯示期間，若新的連線合併到同一個 prompt（`Updated`）：原地更新連線數/目標，不重新彈出、不重置 1 秒防誤觸、不重置時效 chip。
5. 若同一個前台 UID 有兩個 prompts（例如一般 + 簽章變更）：顯示最舊；回答後若另一個還在，更新面板內容（不要先移除再新增，避免閃爍）。
6. 使用者切換到另一個有待決詢問的應用：舊面板立即收起（1 秒輪詢內），新應用的面板出現；這比「保留舊面板在使用者眼前」合理。
7. 使用者回到桌面 / 鎖屏 / 開啟本應用：面板收起；通知保留。

### 5.4 超時與 hold

- 面板可見期間，每 10 秒呼叫 `PromptBroker.pauseTimer(promptId)`，與應用內卡片一致；這會把 deadline 往後推，最多到 `createdAt + maxPromptLifetimeSeconds`（預設 300 秒）。
- 收起面板（回答、稍後、切換前台、螢幕關閉）時取消 hold job；已延長的 deadline 不會縮短（Core 沒有縮短 API），但不會再往後推。
- 逾時由 `PromptBroker` 的 timer 處理：移除 prompt、以 `promptDefaultAction` 完成 flows、發出 `Removed`；router 收到 `pending` 更新後收起面板。
- 不在面板上顯示倒數；若產品堅持要倒數，必須先改 `PromptBroker` 的 hold 語義（例如「顯示期間暫停 timer，但不改 deadline」），這是 Core 變更，另開任務。
- 使用者在 5 分鐘內不理會：詢問消失，套用預設動作（預設拒絕）；通知取消；連線記錄保留。
- **與應用內卡片的 hold 關係**：`PromptOverlay` 目前的 `LaunchedEffect(prompt.id)` 不會因 Activity pause 而停止；應用退到背景後，它可能仍每 10 秒 `holdPrompt`，與懸浮面板重複延長 deadline。建議把該迴圈改成只在 `Lifecycle.State.STARTED` 執行（`repeatOnLifecycle` 或 lifecycle observer），或由 `PromptRouter` 統一管理 hold（只有目前可見的呈現器可以 hold）。這是 K-039 應一併修正的小問題。

### 5.5 原生 View vs ComposeView

**建議：原生 View（XML layout + 程式綁定）。**

| 比較 | 原生 View | ComposeView |
|---|---|---|
| 生命週期 | 不需要 `LifecycleOwner` | 需要 `ViewTreeLifecycleOwner` + `ViewTreeSavedStateRegistryOwner`；`collectAsStateWithLifecycle` 需要 STARTED，否則不更新 |
| 首次顯示延遲 | inflate + bind，通常 10–30ms | 第一次 composition 可能 50–200ms（冷進程更久）；詢問需要「立刻看到」 |
| WindowManager 相容性 | 直接、成熟 | `ComposeView` 在非 Activity 視窗需要自己處理 `WindowRecomposer`、`ViewCompositionStrategy`、configuration 更新；容易踩坑 |
| 焦點/IME | 我們自己控制 flags，行為可預測 | Compose 的焦點/IME 行為封裝較深，非 focusable 視窗下更難預測 |
| 樣式 | 需要自己寫 drawable/顏色；不能用 Material3 元件（專案沒有 Material Components 依賴） | 可直接用 Material3、現成主題 |
| 與應用內卡片一致性 | 需要手寫一份，但可共用 `UiStrings` 與政策 | 可直接重用 `PromptOverlay` 的 UI |
| APK 體積 | 幾乎不增加 | Compose 已在專案中，體積不是問題 |
| 測試/維護 | 簡單、穩定 | 需要額外的 lifecycle 包裝，單元測試仍只能測政策 |

理由總結：面板很小、生命週期短、必須在任意應用之上立即出現，且不能搶焦點。原生 View 的風險最低；Compose 的 UI 重用優勢不值得為此引入 `WindowRecomposer`/`LifecycleOwner` 的複雜度。

如果未來面板要擴大成完整詢問頁（多目標、域名選擇、批量），再評估 ComposeView；屆時可用 `ComposeView` + 自訂 `LifecycleRegistry`，並用 `setViewCompositionStrategy(DisposeOnDetachedFromWindow)`，但這是另一個任務。

原生版面建議：

- 用 `ContextThemeWrapper(appContext, R.style.Theme_NetworkControlKT)`（或新增一個只給 overlay 的 `Theme.NetworkControlKT.Overlay`）來 inflate；不要用 Activity 的 context，也不要用 `AppCompatButton`（本專案沒有 AppCompat 的 view inflater 包裝，plain `Button` 就夠）。
- `res/layout/prompt_overlay_panel.xml`：`LinearLayout`（vertical），背景 `prompt_overlay_card.xml`（`<shape>` 圓角 16dp、solid surface、1dp stroke）。
- 標題列：`ImageView` 40dp + 兩個 `TextView`（標題、目標）。
- 警告：`TextView` `visibility=gone`，`isTamperAlert` 時顯示，紅色。
- 時效：`RadioGroup`（horizontal）+ 三個 `RadioButton`，`android:button="@null"`，用 background selector 做 chip；`contentDescription` 由文字提供。
- 按鈕列：`TextView`/`Button`「稍後」在左，`Space` 撐開，`Button`「拒絕」「允許」在右；`minHeight=48dp`，`textAllCaps=false`。
- 顏色：`values/colors.xml` + `values-night/colors.xml`（surface / onSurface / outline / warning）；允許按鈕用 `container.theme.color.value.toArgb()` 做 `backgroundTintList`，文字白色；拒絕按鈕用 outline 樣式。
- 字級用 `sp`，支援系統字體縮放；目標行 `maxLines=2`、`ellipsize=end`。
- RTL：用 `start/end` margin，不要寫死 `left/right`。
- 無障礙：`ImageView.contentDescription`、按鈕文字、`RadioButton` 的 checked 語義；面板出現時可 `announceForAccessibility(title)`；`importantForAccessibility=YES`；不要 `requestFocus()`。

### 5.6 狀態同步與競態

- **唯一真相**是 `PromptBroker.pending`。`PromptOverlayController` 只保存「目前顯示的 prompt id」與 UI 暫存（選中的 lifetime），不保存詢問內容的副本。
- 每次 `pending` 更新：
  1. 找到目前顯示的 id；
  2. 若不存在 → 收起（並取消 hold）；
  3. 若存在 → 更新文字/連線數/目標；若 id 相同，保留使用者的 lifetime 選擇與已過的防誤觸狀態；
  4. 若 id 不同 → 用同一個 View 重新綁定（不要 remove + add，避免閃爍與焦點/手勢中斷）。
- `pauseTimer` 也會造成 `pending` 更新（deadline 變長）；綁定時只更新需要的欄位，不要重置 chip 或按鈕。
- 回答競態：
  - 面板按允許 → 立即禁用按鈕 → `broker.answer`；
  - 同時通知按鈕回答 → `broker.answer` 第二次回傳 false；
  - 面板收到 `pending` 更新（prompt 已移除）→ 收起；
  - 不要假設「按下按鈕後 prompt 一定還在」。
- UI 可見性競態：
  - `MainActivity.onResume` 與 `pending` 更新可能同一幀發生；router 每次渲染前都重新讀 `AppUiVisibility.isVisible`；
  - 面板顯示前再檢查一次 `uiVisible` 與 `protection.enabled`，避免剛切回應用還彈出面板。
- 前景競態：
  - 查詢是非同步的；查詢結果回來時 prompt 可能已被回答。渲染前用 `prompt.id` 再確認一次 `pending` 仍包含它。
- 語言/主題競態：
  - 語言切換會重建 Activity；`UiStrings` 重新建立；面板若正在顯示，用新語言重新綁定（或先收起，待下一個 pending 更新再顯示）；不要讓面板停留在舊語言。
- 效能：
  - `AppCatalog.find()` 第一次可能載入套件清單，不要在 main 執行；router 在 IO/Default 先把 `PromptCandidate`（含 UID）準備好。
  - 應用圖示先用 `AppIconLoader` 的快取；首次解碼在 IO，完成後再 `setImageDrawable`，不要讓 `addView` 等待圖示。

### 5.7 文字、語言與主題

- **不要**用 `applicationContext.getString()` 取面板文字。應用內語言由 `AppCompatDelegate.setApplicationLocales()` 控制；在 Android 13 以下，application context 的 `Resources` 不一定跟著切換（AppCompat 主要包裝 Activity context）。這會造成面板文字跟應用內卡片不一致。
- 建議面板文字走現有的 `UiStrings`：
  - `UiStrings` 的建構子是 `internal`，`platform` 同 module 可直接使用；
  - tag 取 `preferences.languageTag ?: Locale.getDefault().toLanguageTag()`；
  - 新增 `S.PromptOverlayTitle`、`S.PromptOverlayQueue`、`S.PromptOverlayA11y`、`S.OverlayPermission*`、`S.PromptDisplay*` 等，追加在 `S` 列舉末尾，並補 EN / CN / TW 三份 map（現有 `S` 沒有順序穩定性要求，但遵循倉庫「追加在末尾」的慣例，降低衝突）。
- `strings.xml` 只用於系統可見、非 Compose 的文字：通知渠道名稱（`NotificationChannels.ensure`）、必要時的通知 fallback 文案。三種語言都要補：`values/strings.xml`、`values-b+zh+Hans/strings.xml`、`values-b+zh+Hant/strings.xml`。
- 主題：原生面板跟隨系統 `DayNight`（`values-night`），不跟隨被覆蓋應用的主題。微信自己的深色模式可能與系統不同；面板用不透明 surface + stroke，確保兩種背景下都可讀。
- 現有通知也使用 `context.getString`，在 Android 13 以下可能已有語言不一致的問題；本次不擴大範圍，若使用者回報再另開任務統一改成 `UiStrings`。
- 主題色：允許按鈕用 `container.theme.color`；若顏色太淺導致白字不清，計算亮度後改用黑字（或固定用深色 surface 上的 accent）。第一版可固定主題色 + 白色文字，真機看效果再調。

---

## 6. OEM / ROM 與微信特定問題

### 6.1 OEM / ROM 額外權限

`Settings.canDrawOverlays()` 只反映 AOSP 的 appop；以下 ROM 還有額外的「背景彈出」或「自啟動」開關，程式無法查詢，只能引導使用者：

| ROM | 常見額外設定 | 備註 |
|---|---|---|
| MIUI / HyperOS | 「其他權限 → 顯示在其他應用上層」；「後台彈出界面」；「自啟動」；「省電策略 → 無限制」 | 「後台彈出界面」可能在權限頁的進階項；名稱依版本不同 |
| ColorOS / realme UI | 「權限管理 → 懸浮窗」；「允許背景彈出」；「自啟動」；「電池 → 允許背景運行」 | 部分版本把懸浮窗與背景彈出分開 |
| EMUI / HarmonyOS | 「權限 → 懸浮窗」；「應用啟動管理 → 手動管理」（自啟動 / 關聯啟動 / 背景活動）；「電池 → 更多電池設定」 | 省電精靈可能定期清理 |
| OriginOS / Funtouch | 「權限 → 懸浮窗」；「背景彈出」；「自啟動」；「電池 → 後台耗電管理」 | vivo/iQOO 常見 |
| One UI | 「在其他應用上顯示」；「電池 → 未休眠應用」 | 相對接近 AOSP |
| nubia / RedMagic | 「懸浮窗」；「後台彈出界面」；「自啟動」；「省電策略」 | 參考機（掌心管家）就是這類提示 |
| AOSP / Pixel | 「特殊應用程式存取 → 顯示在其他應用上層」 | 標準行為 |

做法：

- 設定頁的「詢問顯示」卡片顯示：
  - `SYSTEM_ALERT_WINDOW` 狀態（`Settings.canDrawOverlays`）；
  - 使用情況存取權狀態（沿用 `hasTrafficAccess`）；
  - 通知狀態（沿用 `notificationsAvailable`）；
  - 一段 OEM 說明（可折疊）：「若授權後仍不顯示，請在系統設定中允許『背景彈出界面』與『自啟動』，並關閉電池限制。」
  - 權限狀態要在 `ON_RESUME` 重新查詢（沿用 `SettingsScreen` 既有的 `LifecycleEventEffect` 模式），使用者從系統設定返回後立即更新。
- 提供「開啟應用程式資訊」按鈕（`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`），因為各 ROM 的特殊權限入口不同，硬編碼 OEM Intent 很容易失效。
- 不要在程式裡偵測 ROM 並自動跳轉特定 Activity；除了不可靠，還可能被商店政策或系統更新破壞。必要時用 `Build.MANUFACTURER` 只做文案分支。
- 把 `Build.MANUFACTURER`、`Build.MODEL`、`Build.VERSION.RELEASE`、`Settings.canDrawOverlays`、`UsageAccess`、`notificationsAvailable` 放進診斷包（`DiagnosticsExporter`），方便排查「為什麼不顯示」。

### 6.2 微信（`com.tencent.mm`）特定注意事項

| 問題 | 現象 | 對策 |
|---|---|---|
| 鍵盤（IME） | `TYPE_APPLICATION_OVERLAY` 在 IME 之下；底部面板在聊天輸入時完全被蓋住 | **預設頂部**；底部模式需 IME inset 位移並真機驗證 |
| 底部輸入欄 | 鍵盤關閉時，底部面板蓋住微信的輸入框與發送鍵 | 頂部模式；或把底部面板抬到輸入欄之上（但無法知道輸入欄高度） |
| `setHideOverlayWindows` | 微信本體一般不會；但支付、小程序、內嵌 WebView 或部分廠商 ROM 的「安全鍵盤」可能隱藏第三方懸浮窗 | 無法覆寫；通知保留；`isShown` 盡力檢測；必要時引導使用者用通知 |
| `FLAG_SECURE` | 支付、身分驗證、部分聊天頁可能禁止截圖；**不會**隱藏懸浮窗 | 面板仍可能出現在敏感頁面上；隱私考量：提供「只用通知」選項；不要把域名/目標放到鎖屏通知（K-040） |
| 語音/視訊通話 | 全螢幕通話 UI 上可能出現面板，打斷通話 | 無法可靠偵測通話頁；第一版接受；可在文案/設定說明；未來用 `UsageEvents.className` 做黑名單（不建議硬編碼微信類名） |
| 多程序 / 共享 UID | `com.tencent.mm:push` 等背景程序不代表微信在前台 | 按 UID 比較「最近 resumed 的 Activity」；背景 push 只發通知 |
| 長連線 | 允許後 VPN 重建分流，微信既有長連線可能被斷開，出現「網路不可用」 | 文案明確說「允許後重新連線即可上網」；微信通常會自動重試；這是引擎既有行為，不是面板問題 |
| 深色模式 | 微信可能用自己的深色主題，與系統 `DayNight` 不同 | 面板用不透明 surface + stroke；不依賴被覆蓋應用的背景 |
| 大量域名 | 一次合併很多連線/域名 | 只顯示第一個目標 + 連線數 + 「等」；完整清單在記錄頁（未來應用詳情） |

**結論**：微信場景下，頂部面板 + 通知兜底是最穩的組合；底部面板只有在 IME inset 與 `setHideOverlayWindows` 都真機驗證過才建議提供。

---

## 7. 測試與驗證

### 7.1 單元測試（純政策）

新增 `app/src/test/java/com/networkcontrol/android/kotlin/PromptPresentationPolicyTest.kt`，`PromptPresentationPolicy` 不 import android，測試案例至少：

| # | 情境 | 期望 |
|---|---|---|
| 1 | UI 可見，有 pending | `InApp`（最舊） |
| 2 | UI 不可見、auto、兩個權限都有、螢幕亮/解鎖、前台 UID 符合 | `Overlay(id)` |
| 3 | 同上，但前台是另一個應用 | `Notification` |
| 4 | 被詢問應用在後台 | `Notification` |
| 5 | 螢幕關閉 | `Notification` |
| 6 | 鎖屏（螢幕亮） | `Notification` |
| 7 | 沒有 overlay 權限 | `Notification` |
| 8 | 沒有 usage access | `Notification` |
| 9 | 模式 = `notification` | `Notification` |
| 10 | 前台是桌面 / SystemUI / 自己 | `Notification`（自己且 UI 可見時 `InApp`） |
| 11 | 同一 UID 有多個候選 | 選 `createdAt` 最舊 |
| 12 | `app == null` / `android-app://uid:...` | `Notification` |
| 13 | 簽章變更的 prompt | `Overlay`，且 `isTamperAlert` 保留 |
| 14 | `pending` 空 | `None` |
| 15 | 保護關閉 | `Notification` / `None`（由 router 先收起） |

另外可測：

- `PromptForegroundTracker` 的事件狀態機（把 `UsageEvents` 轉成簡單資料的 parser 抽成純函數，餵假事件序列）；
- 目標文字格式化（域名、IPv6、多連線）；
- 時效 chip 的預設值（一般 = Permanent、簽章變更 = UntilRestart）。

執行：

```powershell
.\scripts\android-kotlin.ps1 test
.\scripts\android-kotlin.ps1 build
```

### 7.2 模擬器 adb 清單

以 Debug 包名 `com.networkcontrol.android.kotlin.debug` 為例（Release 沒有 `.debug`）：

```powershell
$pkg = "com.networkcontrol.android.kotlin.debug"

# 1) 授權（不彈系統對話框）
adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow
adb shell appops set $pkg GET_USAGE_STATS allow
adb shell appops set $pkg ACTIVATE_VPN allow
adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS   # API 33+

# 2) 確認 appop
adb shell appops get $pkg SYSTEM_ALERT_WINDOW
adb shell appops get $pkg GET_USAGE_STATS

# 3) 開保護 / 觸發詢問（先清 log，再開網頁）
adb logcat -c
adb shell am start -a android.intent.action.VIEW -d "https://example.com"
adb logcat -s NetworkControlKT:*

# 4) 視窗檢查
adb shell dumpsys window windows | Select-String -Pattern "NetworkControl" -Context 0,30

# 5) 通知檢查（渠道、文字、是否 heads-up）
adb shell dumpsys notification --noredact | Select-String -Pattern "prompt|NetworkControl"

# 6) 目前前台（輔助判斷）
adb shell dumpsys activity activities | Select-String -Pattern "mResumedActivity"
adb shell dumpsys input_method | Select-String -Pattern "mInputShown|mImeWindowVis"

# 7) 螢幕 / 鎖屏
adb shell input keyevent 26          # POWER
adb shell wm dismiss-keyguard
adb shell dumpsys power | Select-String -Pattern "mWakefulness"

# 8) 返回鍵（非 focusable：應該由下面的應用處理）
adb shell input keyevent 4

# 9) 旋轉 / 尺寸
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
# 還原
adb shell settings put system user_rotation 0

# 10) 權限收回（面板顯示中）
adb shell appops set $pkg SYSTEM_ALERT_WINDOW deny
# 還原
adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow

# 11) 截圖（只截裝置畫面；PowerShell 用 cmd 避免二進位被轉義破壞）
cmd /c "adb exec-out screencap -p > overlay.png"
```

逐項檢查：

| # | 場景 | 步驟 | 預期 |
|---|---|---|---|
| 1 | 面板出現 | Chrome 開新域名（無規則、Ask 模式） | 面板出現；`dumpsys window` 可見 `TYPE_APPLICATION_OVERLAY`、`FLAG_NOT_FOCUSABLE`、`FLAG_NOT_TOUCH_MODAL` |
| 2 | 面板回答 | 點「允許」 | 詢問消失；規則建立；Chrome 重試可上網；通知取消 |
| 3 | 通知回答 | 面板顯示時點通知的「拒絕」 | 面板在 1 秒內消失；不出現第二次詢問 |
| 4 | 稍後 | 點「稍後」 | 目前連線被拒；同應用 10 分鐘內不再詢問 |
| 5 | 背景應用 | 回桌面後讓被詢問應用重試 | 只發通知；`dumpsys window` 沒有 overlay |
| 6 | 切換前台 | 面板顯示時切到另一個有待決詢問的應用 | 舊面板收起；1 秒內出現新應用的面板 |
| 7 | 鎖屏 / 熄屏 | 面板顯示時按 POWER | 面板立即收起；通知保留；解鎖後若應用仍在前台才再顯示 |
| 8 | 權限不足 | `appops ... SYSTEM_ALERT_WINDOW deny` 後觸發 | 不崩潰；只發通知；log `overlay:skip reason=no_permission` |
| 9 | UsageStats 不足 | `appops ... GET_USAGE_STATS deny` 後觸發 | 只發通知；log 有 skip 原因 |
| 10 | 權限中途收回 | 面板顯示時 revoke | `addView` 失敗 / 視窗被移除；通知升回高重要性；不 crash |
| 11 | 觸控穿透 | 面板顯示時在面板外滑動 Chrome | Chrome 正常捲動；邊緣返回手勢可用 |
| 12 | 返回鍵 | 面板顯示時按 BACK | Chrome 返回上一頁；面板不關閉（符合非 focusable 設計） |
| 13 | 鍵盤 | 在 Chrome 網址列輸入（鍵盤打開）後觸發 | 頂部模式：面板不被遮；底部模式：面板上移或至少不消失（記錄實測） |
| 14 | 旋轉 | 面板顯示時旋轉 | 面板重新排版；不超出螢幕；不重複 addView |
| 15 | 多詢問 | 兩個應用先後觸發 | 只顯示前台那個；另一個在通知列；切換前台會換面板 |
| 16 | 通知開關 | 關閉「顯示通知」後觸發 | 面板仍可顯示；通知列沒有新通知；設定頁有風險提示 |
| 17 | 保護關閉 | 面板顯示時關閉保護 | 面板收起；若啟用 `cancelAll`，pending 立即清空；否則現有 pending 會在逾時後消失，不應再有新面板 |
| 18 | 重複顯示 | 快速 show/hide 20 次 | `dumpsys window` 只有一個視窗；無 `WindowLeaked`；記憶體穩定 |
| 19 | 大字形 | `adb shell settings put system font_scale 1.3` | 文字不截斷、按鈕不重疊 |
| 20 | RTL | 開發者選項強制 RTL | 佈局方向正確、文字不鏡像錯亂 |

建議的模擬器矩陣：API 29（Android 10，minSdk）、API 31（Android 12，`setHideOverlayWindows`/untrusted touch）、API 33（Android 13，通知權限/受限設定）、API 36（targetSdk）。若本機只有一個 AVD，至少跑 API 33 + API 36。

### 7.3 真機清單

| 項目 | 裝置 / 場景 | 驗證 |
|---|---|---|
| OEM 懸浮窗 | MIUI/HyperOS、ColorOS、EMUI、OriginOS、One UI、nubia | 授權後面板能出現；額外「後台彈出」未開時的表現 |
| 自啟動 / 保活 | 重開機、長時間背景、省電模式 | VPN 與面板仍可用；診斷包記錄 |
| 微信聊天 | 鍵盤打開/關閉、語音、視訊、支付頁 | 面板是否被 IME 蓋住、是否出現在敏感頁、是否遮住輸入欄 |
| 微信小程序 | 內嵌 WebView、支付 | 是否 `setHideOverlayWindows`；有無回呼 |
| 銀行/支付 App | 任意一家 | 面板是否被隱藏；通知是否仍可見 |
| 手勢導航 | 三鍵 / 手勢 / 側邊返回 | 面板邊緣不吞手勢；底部模式不壓導航列 |
| 鎖屏 | 有 PIN / 無 PIN / AOD | 鎖屏不顯示面板；解鎖後行為 |
| 無障礙 | TalkBack | 面板是否被朗讀；焦點是否被搶 |
| 多使用者 / 工作資料夾 | 次使用者 / 工作設定檔 | 各自授權；不互相顯示 |
| 分屏 / PiP | 分屏、子母畫面 | 前台判斷是否合理；避免同時卡片+面板 |

### 7.4 無法在模擬器驗證的部分

- 各 OEM 的「後台彈出界面」「自啟動」「省電策略」實際行為與選單位置；
- 微信真實聊天/鍵盤/通話/支付頁的 `setHideOverlayWindows`、`FLAG_SECURE`、IME inset 行為；
- `setHideOverlayWindows` 被隱藏時是否有可靠回呼（需要在測試 App 中主動設定並觀察 `isShown`/`onWindowVisibilityChanged`）；
- 各 ROM 的 `UsageStatsManager.queryEvents` 延遲、漏事件、背景限制；
- TalkBack 對非 focusable overlay window 的朗讀/焦點行為；
- Doze / 省電 / 記憶體壓力下的程序存活與面板消失時機；
- 多使用者 / 工作資料夾的 overlay appop 與 VPN 行為；
- 真實瀏海、折疊螢幕、平板、橫向的 inset/位置；
- 通知渠道在不同 ROM 的 heads-up 抑制與重要性覆寫；
- Google Play 對 `SYSTEM_ALERT_WINDOW` + VPN + `QUERY_ALL_PACKAGES` 的政策（本專案目前是側載，仍需留意）。

---

## 8. 風險與分階段實作

### 8.1 風險表

| # | 風險 | 影響 | 機率 | 對策 |
|---|---|---|---|---|
| R1 | OEM 額外權限未開，面板不出現 | 功能等於沒做 | 高 | 設定頁引導、OEM 說明、通知兜底、診斷包記錄 ROM/權限狀態 |
| R2 | `UsageStats` 延遲/漏事件，前台判斷錯誤 | 面板出現在錯誤應用或不出現 | 中高 | 增量追蹤 + 1 秒輪詢；查不到就回通知；不要猜 |
| R3 | 鍵盤/IME 蓋住底部面板 | 使用者完全看不到詢問 | 中高 | 預設頂部；底部模式 IME inset + `getWindowVisibleDisplayFrame` fallback；真機驗證 |
| R4 | `setHideOverlayWindows` / 系統隱藏 | 面板被無聲隱藏 | 中 | 通知永遠保留；`isShown` 盡力檢測；用 `PROMPT_SOFT`（有聲音、不 heads-up），不要完全靜音 |
| R5 | 通知重複/過吵 | 使用者關閉通知 | 中 | `setOnlyAlertOnce`；面板顯示用 `PROMPT_SOFT`；面板與卡片去重 |
| R6 | 觸控/手勢被面板吞掉 | 下面應用難用、返回手勢失效 | 中 | 視窗等於面板、兩側 16dp、不加全螢幕遮罩、`FLAG_NOT_TOUCH_MODAL` |
| R7 | 非 focusable 視窗無障礙不佳 | TalkBack 使用者不知道有詢問 | 中 | `announceForAccessibility`、contentDescription、通知作為可存取入口；K-044 再完善 |
| R8 | 敏感頁面（微信支付、銀行）顯示域名 | 隱私洩漏 | 中 | 1 秒防誤觸；提供「只用通知」；K-040 鎖屏隱藏詳情；不把詳情放公開通知 |
| R9 | 程序被殺 / VPN 關閉 | 面板消失、詢問遺失、通知可能殘留 | 低 | 保護開著時有 FGS；關閉保護就沒有新詢問；App 啟動時清理殘留通知 |
| R10 | 語言不跟隨應用設定（application context） | 面板與卡片語言不一致 | 中 | 使用 `UiStrings`，不用 `context.getString`；三語補齊 |
| R11 | 通知渠道重要性無法修改 | 之後調不了靜音/響鈴 | 中 | 第一版不動渠道；第二版一次定好新 channel id；用 `dumpsys` 驗證 |
| R12 | 分屏/PiP 前台語義不清 | 面板顯示在錯的地方 | 低 | 文件標明限制；必要時在分屏下強制通知（目前無法可靠偵測其他應用的分屏狀態） |
| R13 | 安全：懸浮窗覆蓋其他應用 | 釣魚/誤導風險（使用者自己授權） | 低 | 面板只顯示自訂內容、不模仿系統 UI；1 秒防誤觸；`setFilterTouchesWhenObscured`；不提供任意文字輸入 |
| R14 | Play 政策 | 無法上架 | 低（目前側載） | 保持側載；未來上架前單獨評估 |

### 8.2 分階段實作

#### 階段 0：政策與基礎（0.5–1 天）

- 新增 `PromptPresentationPolicy`（純 Kotlin）+ 單元測試；
- 抽出 `UsageAccess`（可選，先不改 `TrafficCollector` 行為）；
- `AppPreferences` 加 `promptPresentationMode` / `overlayPosition`；
- `UiStrings` 加設定頁/面板文字（三語）；
- 設定頁先放「詢問顯示」卡片（模式 + 權限狀態 + 授權按鈕），即使面板還沒做，選「只用通知」也能運作。

**驗收**：`.\scripts\android-kotlin.ps1 test` 通過；既有通知行為不變；設定可儲存/讀回。

#### 階段 1：最小可用面板（2–3 天）

- `PromptForegroundTracker`（UsageStats 增量）；
- `AppUiVisibility`；
- `PromptOverlayController` + `prompt_overlay_panel.xml` + drawable/colors；
- `PromptRouter` 接管 `PromptNotifier` 的訂閱；
- 面板顯示/更新/收起、允許/拒絕/稍後、1 秒防誤觸；
- 預設頂部；權限不足/加窗失敗自動回通知；
- `PromptNotifier` 加 `setOnlyAlertOnce(true)`；新增 `PROMPT_SOFT` 渠道，面板/卡片顯示時使用，通知-only 用 `PROMPT`；底部模式暫用 `PROMPT` 直到 IME 驗證。
- 寫 log：`overlay:show id=... app=...`、`overlay:hide reason=...`、`overlay:fallback reason=...`。

**驗收**：模擬器 API 33/36 上 Chrome 觸發 → 面板出現 → 允許 → 可上網；背景/鎖屏/無權限只發通知；應用內卡片與面板不重複；`dumpsys window` flags 正確。

#### 階段 2：穩定與邊界（1–2 天）

- 螢幕/鎖屏 receiver、`PowerManager`/`KeyguardManager` 檢查；
- 底部模式 + IME inset / `getWindowVisibleDisplayFrame` fallback；驗證通過後，底部模式也改用 `PROMPT_SOFT`；
- 前台切換 1 秒輪詢、佇列重新選擇；
- `PROMPT_SOFT` / `PROMPT` 渠道切換驗證；
- 權限中途收回、`addView` 失敗、`isShown` 檢測與 30 秒退避；
- 語言/主題變更刷新；
- 診斷包加入 overlay 狀態與 skip 原因。

**驗收**：§7.2 的 20 項清單全部跑過（模擬器）；沒有 `WindowLeaked`；面板不出現在桌面/鎖屏/背景；通知渠道切換符合預期。

#### 階段 3：真機與體驗（1–2 天）

- 至少一個 OEM ROM + 微信實測（鍵盤、聊天、支付頁、通話）；
- TalkBack 基本檢查；
- 設定頁 OEM 說明定稿；
- 更新 `docs/kotlin-feature-log.md` K-039 記錄（功能、日期、提交、驗證、未驗證項）；
- 更新 `docs/android-kotlin.md` 的版本章節與 `docs/kotlin-roadmap.md` 狀態；
- 若發現設計偏差，回寫本文件「實作差異」一節（不要直接改歷史決策）。

**驗收**：真機清單（§7.3）完成；未驗證項如實寫入功能記錄；`.\scripts\android-kotlin.ps1 publish` 可產出 APK；使用者可在設定中切回「只用通知」作為逃生門。

### 8.3 待確認 / 需要使用者決定

1. **預設位置**：本文建議頂部；若產品堅持底部，需先承諾完成 IME 位移與微信真機驗證。
2. **通知安靜程度**：預設用 `IMPORTANCE_DEFAULT`（有聲音、不 heads-up）還是提供 `LOW`（完全安靜）的選項？本文建議預設 DEFAULT，`LOW` 只作為進階設定，等真機證明面板在鍵盤打開時仍可見再考慮。
3. **背景同步**：前台應用的背景同步（同 UID）要不要也顯示面板？本文建議顯示；若覺得吵，再加「最近 10 秒有 Activity 事件」的收緊條件。
4. **返回鍵**：接受「返回鍵給下面的應用」，還是提供可 focus 的模式？本文建議接受。
5. **保護關閉時是否 `cancelAll()`**：建議由 `AppContainer` 監聽 `protection.enabled`，在關閉時呼叫 `PromptBroker.cancelAll()`，避免沒有 UI 的 pending；這會讓「關閉保護」直接以預設動作結束詢問，需確認符合產品語義。
6. **OEM 深層連結**：是否要為 MIUI/ColorOS 等做特定設定頁 Intent？本文建議不做，改用應用程式資訊 + 文字說明。
7. **是否提供「開啟應用」按鈕**：本文建議第一版不做，避免背景 Activity 啟動限制與面板變大；使用者可從通知開啟。

---

## 附錄 A：政策虛擬碼

```kotlin
enum class PromptPresentationMode { Auto, NotificationOnly }

data class PromptCandidate(
    val id: String,
    val uid: Int?,          // null：無法對應套件
    val createdAt: Long,
    val isTamperAlert: Boolean,
)

data class PresentationEnvironment(
    val uiVisible: Boolean,
    val mode: PromptPresentationMode,
    val canDrawOverlays: Boolean,
    val hasUsageAccess: Boolean,
    val screenInteractive: Boolean,
    val keyguardLocked: Boolean,
    val protectionEnabled: Boolean,
    val foregroundUid: Int?,          // null：未知
    val selfUid: Int,
    val foregroundIsExcluded: Boolean, // 桌面 / SystemUI / 權限控制器 / 自己
)

sealed interface PromptSelection {
    data object None : PromptSelection
    data object Notification : PromptSelection
    data class InApp(val promptId: String) : PromptSelection
    data class Overlay(val promptId: String) : PromptSelection
}

fun decide(candidates: List<PromptCandidate>, env: PresentationEnvironment): PromptSelection {
    if (candidates.isEmpty()) return PromptSelection.None
    if (!env.protectionEnabled) return PromptSelection.Notification

    val oldest = candidates.minByOrNull { it.createdAt } ?: return PromptSelection.None
    if (env.uiVisible) return PromptSelection.InApp(oldest.id)

    if (env.mode != PromptPresentationMode.Auto) return PromptSelection.Notification
    if (!env.canDrawOverlays || !env.hasUsageAccess) return PromptSelection.Notification
    if (!env.screenInteractive || env.keyguardLocked) return PromptSelection.Notification
    if (env.foregroundUid == null) return PromptSelection.Notification
    if (env.foregroundIsExcluded) return PromptSelection.Notification
    if (env.foregroundUid == env.selfUid) return PromptSelection.Notification

    val match = candidates
        .filter { it.uid != null && it.uid == env.foregroundUid }
        .minByOrNull { it.createdAt }
        ?: return PromptSelection.Notification

    return PromptSelection.Overlay(match.id)
}
```

`PromptRouter` 再依 `PromptSelection`：

- `InApp` → `overlay.hide()`；所有通知用 `PROMPT_SOFT`；
- `Overlay(id)` → `overlay.show(promptById(id))`；該 id 的通知用 `PROMPT_SOFT`，其餘用 `PROMPT`；
- `Notification` → `overlay.hide()`；所有通知用 `PROMPT`。

## 附錄 B：`WindowManager` 參數示意

```kotlin
val metrics = context.resources.displayMetrics
val width = minOf(metrics.widthPixels - dp(32), dp(560))
val params = WindowManager.LayoutParams(
    width,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    y = statusBarInset + dp(8) // 頂部模式；底部模式改成導航列高度
    title = "NetworkControl prompt"
    packageName = context.packageName
    alpha = 1f
    if (Build.VERSION.SDK_INT >= 30) {
        setFitInsetsTypes(0) // 由 View 自己處理 inset；實測有問題時移除
    }
    layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
}
```

## 附錄 C：與 `progress-android.md` 規劃的差異

| 項目 | Avalonia 版規劃（progress-android.md） | Kotlin 版本設計 | 原因 |
|---|---|---|---|
| 宿主 | 由後端/呈現器管理（`IBackend`、`PromptSubscriber`） | `AppContainer` 的 `PromptRouter` + `PromptOverlayController` | Kotlin 沒有 gRPC 後端；`AppContainer` 就是 process scope |
| UI 框架 | Avalonia 依賴 Activity，不適合懸浮窗，故用原生 View | 同樣用原生 View | 一致；ComposeView 在 non-Activity window 的生命週期成本更高 |
| 位置 | 底部對齊 | **預設頂部**，可選底部 | IME 在 overlay 之上；微信底部是輸入欄 |
| 通知 | 面板顯示時改靜默渠道 | 面板/卡片顯示用 `PROMPT_SOFT`（DEFAULT，不 heads-up）；只有通知時用 HIGH | heads-up 會蓋住頂部面板；完全靜音又可能在面板被隱藏時看不到 |
| 前台偵測 | 最近 `ACTIVITY_RESUMED` | 增量 `UsageStats` 追蹤器 + UID 比較 + 排除 launcher/SystemUI | 減少每秒查詢成本，處理共享 UID 與未知前台 |
| 佇列 | FIFO + 重新選擇 | 只選「目前前台 UID」的最舊詢問；其他留在通知 | 避免在 Chrome 上彈微信的詢問 |
| `IsUiForeground` | 已有 | 新增 `AppUiVisibility`（`ActivityLifecycleCallbacks`） | Kotlin 版目前沒有這個狀態 |
| 文字 | `Resources/` + 三語 `strings.xml` | `UiStrings`（應用內語言）+ `strings.xml` 只用於系統渠道名稱 | Android 13 以下 application context 不一定跟隨 AppCompat 語言 |
| `cancelAll` | 未特別提 | 建議保護關閉時呼叫（待確認） | 避免 pending 沒有 UI |

## 附錄 D：實作檢查清單（給實作者）

- [ ] `SYSTEM_ALERT_WINDOW` 清單權限
- [ ] `PromptPresentationPolicy` 純函數 + 15 個以上單元測試
- [ ] `PromptForegroundTracker`（增量、UID、launcher/SystemUI 排除）
- [ ] `AppUiVisibility`（`ActivityLifecycleCallbacks`）
- [ ] `PromptOverlayController`（主執行緒、add/remove、insets、螢幕事件、hold）
- [ ] `PromptRouter`（唯一訂閱、fallback、30 秒退避、通知渠道選擇）
- [ ] `PromptNotifier` 拆分（render/cancel，不再訂閱）
- [ ] 原生 layout + drawable + values-night colors
- [ ] `UiStrings` 三語 + 設定頁「詢問顯示」卡片
- [ ] `AppPreferences` 模式/位置；`AppBackend` getter/setter/intent
- [ ] 權限引導與 OEM 說明
- [ ] 診斷包加入 overlay 狀態
- [ ] §7.2 模擬器清單、§7.3 真機清單
- [ ] `docs/kotlin-feature-log.md` K-039 記錄
- [ ] 更新 `docs/android-kotlin.md` 版本章節 / `docs/kotlin-roadmap.md` 狀態

## 實作差異（待填）

> 實作完成後，把與本設計不同的地方、實測結果、ROM 差異補在這裡；不要改寫上面的決策理由。

- （尚未實作）

---

> 實作時若發現本文件的假設不成立（例如 overlay 收不到 IME inset、`setHideOverlayWindows` 無法偵測），請把實測結果追加到本文件末尾的「實作差異」章節，不要改寫上面的決策理由；這樣後人才能知道為什麼當初這樣選。
