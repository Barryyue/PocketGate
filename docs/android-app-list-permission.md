# Android 應用程式列表權限與「詢問顯示問號」研究

> 研究日期：2026-11-15  
> 對象：`android-kotlin/`（Kotlin 版 Android 應用）  
> 範圍：Android 11（API 30）以上；以中國廠商 ROM 為主，兼顧 AOSP / Pixel / 三星  
> 可信度標記：✅ 官方文件或 AOSP 原始碼可證；⚠️ 有實測或社群報告，但需在目標 ROM 複核；❓ 推測或待驗證

## 0. 結論摘要

1. **「詢問顯示問號」的主因不是 UID 歸屬失敗，而是應用列表被 ROM 額外管控。** Android 11+ 的 `QUERY_ALL_PACKAGES` 只解決 AOSP 的套件可見性；小米 HyperOS/MIUI、OPPO ColorOS、vivo OriginOS、華為 HarmonyOS、榮耀 MagicOS 另外實作了國標 TAF 108-2022 的 `com.android.permission.GET_INSTALLED_APPS` 執行時權限。三星 One UI 則使用自家的 `com.samsung.android.permission.GET_APP_LIST`。只宣告 `QUERY_ALL_PACKAGES` 時，這些 ROM 仍可能讓 `getInstalledPackages()` 只回傳應用自己。✅
2. **沒有這個權限時，`QUERY_ALL_PACKAGES` 的 `checkPermission()` 仍會回傳 `GRANTED`。** 因為它是 normal、安裝時授權的權限；查詢它不能判斷 ROM 是否另外封鎖了應用列表。目前 Kotlin 版的 `AppCatalog.visibility()` 只看這個權限是不夠的。✅
3. **建議同時做兩件事**：在清單宣告 `com.android.permission.GET_INSTALLED_APPS`（三星再加 `GET_APP_LIST`），並在使用者有上下文的時機主動 `requestPermissions`；若系統不彈窗或已被永久拒絕，再引導到設定頁。✅（各 ROM 的彈窗行為仍需實測）
4. **偵測要保守**：以「ROM 權限是否支援且未授予」為第一信號，以「`android` 框架套件是否可見」為第二信號，再輔以安裝數量、launcher 數量與上次成功基線。不要只用 `getInstalledPackages().size < N`，也不要把 `queryIntentActivities()` 的結果當成授權與否的唯一依據。⚠️
5. **列表受限不只影響顯示**：`BlockedFlowReporter` 目前在找不到包名時直接丟棄 flow；`AppIdentity` 為 null 時，現有的按應用規則也不會匹配。保護仍然會攔截，但「按應用允許/拒絕」會退化。這是需要優先修的邏輯，不只是換一個問號圖示。✅（以目前工作區程式碼為準）
6. **沒有權限時仍可盡量還原**：先用 `getConnectionOwnerUid()` 取得 UID，再依序查路由計畫中的包名、`getPackagesForUid()`、launcher 索引（`queryIntentActivities`）、`getNameForUid()`。UI 至少顯示「包名 / UID / 目標」，不要只顯示問號。⚠️
7. **不要使用不存在的 API**：`PackageManager.getNamesForUid(int)` 不是公開 API；AOSP 只有 `getNameForUid(int)`（公開）與 `getNamesForUids(int[])`（`@hide`、`@TestApi`）。本文件不建議用反射呼叫隱藏 API。✅

---

## 1. 背景與目前實作

### 1.1 症狀

使用者回報：Kotlin 版在真機上啟動後，詢問（prompt）都顯示問號。常見伴隨現象：

- 「應用程式」頁只有 NetworkControl 自己，或只有極少數系統應用。
- 詢問卡片有目標（域名/IP:端口），但應用名稱是「未知應用」、空白或問號，圖示是預設圖。
- 通知同樣只顯示「未知應用」。
- 保護與攔截仍然有效；但「允許/拒絕某個應用」的規則無法正確套用，或允許後仍被攔。

### 1.2 目前 Kotlin 版的做法（查閱時的工作區版本）

目前 Kotlin 版的 `targetSdk = 36`、`minSdk = 29`；因此在 Android 11+ 一定會套用套件可見性過濾。以下是查閱時的行為：

| 位置 | 目前行為 | 與本問題的關係 |
|---|---|---|
| `AndroidManifest.xml` | 只宣告 `android.permission.QUERY_ALL_PACKAGES`、`PACKAGE_USAGE_STATS` 等 | 缺少 `com.android.permission.GET_INSTALLED_APPS` 與三星的 `GET_APP_LIST` |
| `AppCatalog.load()` | `getInstalledPackages(...)`，只保留宣告 `INTERNET` 的應用 | 列表被 ROM 封鎖時，只剩自己（自己也有 INTERNET） |
| `AppCatalog.visibility()` | 檢查 `QUERY_ALL_PACKAGES`、`installed < 20`、已知系統套件是否可見 | `QUERY_ALL_PACKAGES` 一定是 GRANTED；已知套件可能因 launcher 查詢仍可見，訊號不夠強 |
| `AppRoot` | 啟動後檢查一次 `visibility()`，受限時顯示對話框 | 從設定回來後不會自動重新檢查；`showAppListPermission` 只設定一次 |
| `SettingsScreen` | 顯示「應用程式列表權限：受限/正常」與「前往設定」 | `remember { backend.appListVisibility() }` 不會因返回而更新 |
| `BlockedFlowReporter.process()` | `packageName == null` 時直接 `return` | 沒有包名就沒有詢問；應該保留 UID 降級詢問 |
| `BlockedFlowReporter.process()` | `catalog.find(packageName)` 找不到時 `app = null` | 即使包名已知，`AppIdentity` 仍為 null，現有按應用規則不會匹配 |
| `PromptOverlay` | 標題依序取 `app.displayName`、`app.name`、`executablePath`、`UnknownApp` | 沒有 UID；`executablePath` 為 null 時只剩「未知應用」 |
| `PromptNotifier` | 同上，最後用 `R.string.unknown_app` | 通知與應用內卡片可能不一致 |

### 1.3 為什麼會變成「問號」

失敗鏈條通常有四層，任何一層都可能造成問號：

1. **列表層**：ROM 只回傳自己 → `AppCatalog` 幾乎是空的。
2. **包名層**：`getPackagesForUid(uid)` 也回傳 null → `BlockedFlowReporter` 直接丟棄 flow，完全沒有詢問。
3. **身份層**：包名有拿到，但 `getApplicationInfo()` / `getPackageInfo()` 被過濾 → `InstalledApp` 為 null → `AppIdentity` 為 null。
4. **顯示層**：`app` 與 `executablePath` 都為 null → UI 只剩「未知應用」或問號。

因此，**只修 UI 的問號沒有用**；要同時處理「取得權限」與「沒有權限時的降級」。

---

## 2. Android 11+ 套件可見性的標準行為

### 2.1 基本規則

當應用 `targetSdk >= 30` 且在 Android 11+ 執行時，`PackageManager` 對其他套件的查詢預設會被過濾。應用永遠可以看到：

- 自己。
- 實作 Android 核心功能的某些系統套件（實際清單依裝置而定，可用 `adb shell dumpsys package queries` 的 `forceQueryable` 查看）。
- 安裝自己的應用。
- 用 `startActivityForResult()` 在自己應用中啟動 activity 的應用。
- 啟動或綁定自己服務的應用。
- 存取自己 content provider 的應用。
- 自己取得 URI 權限的 content provider 所屬應用。
- 對自己輸入的 IME 應用。

其餘應用預設不可見。✅  
來源：Android Developers〈Package visibility filtering on Android〉、〈Know which packages are visible automatically〉。

### 2.2 受影響的 API

| API | 沒有 `QUERY_ALL_PACKAGES` / `<queries>` 時 | 備註 |
|---|---|---|
| `getInstalledPackages()` | 只回傳自己 + 自動可見 + 已宣告的套件 | 不是空清單；常見是幾十個系統套件或只有自己 |
| `getInstalledApplications()` | 同上 | 與 `getInstalledPackages` 一樣被過濾 |
| `getPackageInfo(pkg, ...)` | 不可見時丟 `NameNotFoundException` | 無法區分「未安裝」與「看不到」 |
| `getApplicationInfo(pkg, ...)` | 不可見時丟 `NameNotFoundException` | 文件明寫「no such package is available to the caller」 |
| `queryIntentActivities()` | 只回傳可見套件 | 部分 ROM 在套件列表被鎖時仍可回傳 launcher 應用，見 §5 |
| `queryBroadcastReceivers()` / `queryIntentServices()` | 被過濾 | 連帶影響意圖查詢 |
| `resolveActivity()` | 被過濾 | 可能回 null，但 `startActivity` 仍可能成功 |
| `getLaunchIntentForPackage()` | 不可見時回 null | 不能用來判斷是否安裝 |
| `getPackagesForUid(uid)` | AOSP 實作也會套用 visibility | 官方 API 文件未明說，屬實作細節；見 §5.1 |
| `getPackagesHoldingPermissions()` | 被過濾 | 不適合當完整清單 |

> ⚠️ 注意：`queryIntentActivities()` 在某些 ROM 上即使 `getInstalledPackages()` 被鎖，仍會回傳 launcher 應用。這是 Android 官方過濾規則與廠商實作交錯的結果，不是保證行為。

補充三個容易被誤解的 API：

- **`getPackagesForUid(uid)`**：UID 不存在，或該 UID 的套件全部不可見時，可能回傳 `null`；shared UID 會回傳多個包；OEM 可能額外管控。不要把它當成「一定能拿到被封鎖應用的包名」。
- **`getApplicationInfo(pkg)` / `getPackageInfo(pkg)`**：不可見與未安裝都會丟 `NameNotFoundException`，無法區分。這正是「應用明明裝了，卻查不到」的原因。
- **`getInstalledPackages()`**：不是回傳空清單，而是回傳「自己 + 自動可見 + 已宣告」的子集；所以用 `isEmpty()` 判斷受限會漏掉「只回自己」的情況。

### 2.3 `QUERY_ALL_PACKAGES` 的性質

- 保護等級：`normal`，安裝時自動授予；只要在清單宣告，`checkPermission()` 就會回傳 `GRANTED`。✅
- 只對 `targetSdk >= 30` 且 Android 11+ 的裝置生效。✅
- 會讓 AOSP 的套件可見性過濾繞過；但**不會**繞過廠商額外的應用列表權限。✅
- Google Play 將「已安裝應用清單」視為敏感資料；防火牆/安全類應用通常屬於允許用途，但需要在 Play Console 宣告，否則可能被下架。✅
- 側載（ sideload ）不受 Play 政策限制，但仍受 ROM 權限限制。

### 2.4 其他會讓列表不完整的情況

| 情況 | 影響 | 說明 |
|---|---|---|
| 沒有 `QUERY_ALL_PACKAGES`、也沒有 `<queries>` | 只看到自己 + 自動可見 | targetSdk 30+ 的標準行為 |
| 廠商 TAF 權限未授予 | `getInstalledPackages()` 只回自己，或只回系統應用 | 本文主因 |
| 三星 `GET_APP_LIST` 未授予 | 同上 | One UI 額外一層 |
| 工作資料夾 / 多使用者 | 只看得到目前使用者的應用 | VPN 也是按使用者；另一個 profile 的應用不受目前實例控制 |
| Android 15 私人空間 | 私人空間有獨立 VPN 名額 | 裡面的應用可能完全不受控制 |
| 套件被停用 / 暫停 / 隱藏 | 可能不在清單中 | 不代表未安裝 |
| instant app | 不在一般 `getInstalledPackages()` 清單 | 通常不是防火牆目標 |
| 套件管理器快取 / ROM 異常 | 短時間回傳舊資料或例外 | 榮耀 MagicOS 10 有 `DeadSystemException` 案例，見 §3.3 |

### 2.5 為什麼「列表受限」不是單純顯示問題

以目前 Kotlin 版為例：

- `VpnRoutingPlanner.plan()` 用 `AppCatalog.getApps()` 建 `byUid`；列表為空時，`plan.byUid` 幾乎是空的。
- `VpnTunnel.establish()` 只把 `plan.bypassPackages` 加進 `addDisallowedApplication()`；列表為空時，沒有應用被排除，全部流量進入 VPN。
- `RuleSet.evaluate()` 在 `app == null` 時會跳過所有帶 `AppMatcher` 的規則；即使規則還在資料庫裡，也無法按包名匹配。
- `BlockedFlowReporter` 在 `packageName == null` 時直接丟棄 flow，連詢問都不會出現。
- 沒有 `PackageInfo` 就沒有簽名，永久規則的簽名綁定與竄改偵測也失效。

所以，**應用列表權限是功能權限，不只是美化權限**。沒有它時，保護會變成「幾乎全部攔截 + 無法按應用放行」，而不是原本的「按應用控制」。**不要因為列表受限就停止 VPN 或關閉保護**；正確做法是保留攔截，降級按應用控制並引導授權。

---

## 3. 廠商 ROM 的額外「應用程式列表」權限

### 3.1 國標 TAF 108-2022 與 `GET_INSTALLED_APPS`

中國電信終端產業協會標準 **T/TAF 108-2022《移動終端應用軟件列表權限實施指南》**（配合 YD/T 2407-2021）要求終端提供應用軟件列表的權限管控。實作方式：

- 權限名稱：`com.android.permission.GET_INSTALLED_APPS`。
- `android.Manifest.permission` **沒有**這個常數；請直接使用字串。✅
- 保護等級：多數 ROM 實作為 `dangerous`（執行時權限），需在清單宣告並動態申請。
- 管控 API：`getInstalledPackages`、`getInstalledPackagesAsUser`、`getInstalledApplications`、`getInstalledApplicationsAsUser`。
- 未授予時：只能取得呼叫方自己的資訊，或只回傳部分系統應用。
- 參與廠商：華為、OPPO、vivo、榮耀、小米等。✅

小米與 vivo 的官方文件都明講：這個權限是在 Android 原生 `QUERY_ALL_PACKAGES` 之上**額外**的權限管理。因此「已宣告 `QUERY_ALL_PACKAGES`」不代表「已取得應用列表」。✅

### 3.2 對照表

> 版本與路徑會隨 ROM 更新變動；「預設」是文件或社群報告的描述，實際以真機為準。  
> 「可否 in-app 申請」指 `requestPermissions()` 是否可能出現系統對話框；不保證每個版本都有效。

| 廠商 / 系統 | 適用版本（報告） | 權限名稱 | 預設 | 使用者設定路徑（常見） | 可否 in-app 申請 | 可信度 |
|---|---|---|---|---|---|---|
| 小米 / Redmi MIUI | MIUI 13+ | `com.android.permission.GET_INSTALLED_APPS` | 拒絕或詢問；MIUI 舊版可能預設授予 | 應用程式資訊 → 權限管理 → 取得應用程式列表 → 始終允許；或長按圖示 → 應用程式資訊 → 權限管理 | 可以（官方範例） | ✅ 官方 |
| 小米 / Redmi HyperOS | HyperOS 1.0+；中國版與部分海外版 | 同上 | 中國版預設拒絕；海外/印度版路徑可能不同 | 同上；海外版常見「Permissions → Other permissions → Access list of installed apps」 | 可以，但部分版本不彈窗 | ✅/⚠️ |
| OPPO / OnePlus / Realme | ColorOS 12+ 且 Android 11+；或 ColorOS 11.1+ 且 Android 12+；Realme UI 3.0+ | `com.android.permission.GET_INSTALLED_APPS` | 拒絕 | 設定 → 權限與隱私 → 權限管理 → 讀取應用程式列表；或應用程式資訊 → 權限；部分版本在「特殊權限/其他權限」 | 可以（標準 dangerous 流程） | ⚠️ 第三方整理 + 實測日誌 |
| vivo / iQOO | 中國版 Android 13+；XXPermissions 表記為 OriginOS 4 + Android 14 | `com.android.permission.GET_INSTALLED_APPS` | 拒絕 | 設定 → 應用程式與權限 → 權限管理 → 讀取已安裝應用程式列表；或應用程式資訊 → 權限；i管家 → 軟體權限管理 | 可以（官方範例） | ✅ 官方 |
| 華為 | HarmonyOS 3.0+（Android 相容層）；EMUI 不確定 | `com.android.permission.GET_INSTALLED_APPS` | 拒絕 | 設定 → 應用程式和服務 → 權限管理；或應用程式資訊 → 權限；新版在「隱私和安全 → 應用和元服務權限管理」 | 可以（官方/審核回覆） | ✅/⚠️ |
| 榮耀 | MagicOS 6.0+ | `com.android.permission.GET_INSTALLED_APPS` | 拒絕 | 設定 → 應用程式 → 權限管理（右上角 ⋮ → 關閉「權限精簡顯示」）；應用程式資訊 → 權限 → 查看所有權限；或隱私 → 隱私存取記錄 → 應用程式 | 部分機型會把 `QUERY_ALL_PACKAGES` 自動轉成「讀取裝置應用程式列表」；部分不會 | ⚠️ 使用者回報 |
| 三星 | One UI 5.1.1+ | `com.samsung.android.permission.GET_APP_LIST`（不是 TAF 名稱） | 拒絕/詢問 | 應用程式資訊 → 權限；若沒有，使用 AppOps 詳細頁或「特殊存取權」；沒有統一的「應用程式列表」開關 | `requestPermissions` 可能有效，但 XXPermissions 採 `startActivity` 到 AppOps 頁 | ⚠️ 第三方原始碼 + 部落格 |
| Pixel / AOSP | Android 11+ | 無 | 不適用 | 沒有額外開關；只有 `QUERY_ALL_PACKAGES` / `<queries>` | 不適用 | ✅ 官方 |
| 魅族 Flyme | Flyme 9+ | `com.android.permission.GET_INSTALLED_APPS`（另外的實作） | 拒絕 | 應用程式資訊 → 權限 | 可以（需相容處理） | ⚠️ 第三方 |

### 3.3 各廠商細節

#### 小米 / Redmi（MIUI、HyperOS）

- 官方文件〈獲取應用列表權限的適配說明〉：MIUI 13 起的新版本逐步把權限預設改為拒絕或詢問；未授予時只能取得呼叫方自己的資訊。✅
- 官方範例：
  - 清單宣告 `<uses-permission android:name="com.android.permission.GET_INSTALLED_APPS" />`。
  - 用 `getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)` 判斷是否支援；MIUI 官方文件的權限擁有者是 `com.lbe.security.miui`，但不同版本可能不同，建議以「權限存在 + dangerous」為準，不要硬編碼 owner。⚠️
  - 用 `checkSelfPermission` 判斷；未授予時 `requestPermissions`。
- 有些 ROM 在第一次呼叫 `getInstalledPackages()` 時會自動彈出權限對話框，但**不要依賴**這個行為；應在使用者情境中主動申請，並處理沒有彈窗的情況。⚠️
- **不要**在清單加 `<meta-data android:name="do_not_need_get_installed_apps" android:value="true" />`；官方文件說這等於關閉「取得應用列表」權限。✅
- 設定路徑：
  - 中國版：長按應用圖示 → 應用程式資訊 → 權限管理 → 取得應用程式列表 → 始終允許。✅
  - 海外/印度 HyperOS：App info → Permissions → Other permissions → Access list of installed apps → Always allow（實際名稱依版本）。⚠️
- HyperOS 新政策（2024-11 文件）：預設只可查詢「自己分發的應用」、「系統應用」、「清單內宣告的應用」；要取得完整列表仍需使用者授權應用列表權限。✅
- 同一份文件也明確**禁止**在 `<queries>` 中預置 `MAIN` / `LAUNCHER` 這種「能查詢到大量應用」的 intent filter；不要用這個方式繞過。✅
- HyperOS 3/4 的權限管理頁可能把不常用權限收起，圖示或文字位置與舊版不同；引導文案要寫「找不到時用設定搜尋」。⚠️

#### OPPO / OnePlus / Realme（ColorOS、Realme UI）

- 權限名稱與 TAF 相同：`com.android.permission.GET_INSTALLED_APPS`。⚠️
- AppOps 名稱：`GET_INSTALLED_APPS`；`adb shell appops get <pkg>` 可看到 `GET_INSTALLED_APPS: ignore`。⚠️（單一裝置實測）
- 設定路徑：
  - 設定 → 權限與隱私 → 權限管理 → 讀取應用程式列表。
  - 或長按應用圖示 → 應用程式資訊 → 權限 → 讀取應用程式列表。
  - ColorOS 13/14 之後有些版本把它歸到「特殊權限」或「其他權限」。⚠️
- OPPO 對「應用程式資訊 → 權限」有跳轉優化：可在 `ACTION_APPLICATION_DETAILS_SETTINGS` 的 Intent 加上 `permissionList`（原生權限名稱）與 `isGetPermission=true`，讓頁面直接聚焦受阻權限。這是 OPPO 官方優化文件（OpenAPI id 12983）描述的做法，XXPermissions 也有使用。⚠️
- Realme UI 3.0+、OnePlus 的 ColorOS 版本與 OPPO 類似。⚠️

#### vivo / iQOO（OriginOS、Funtouch）

- 官方文件〈“讀取已安裝應用列表”權限三方適配說明〉：✅
  - 權限：`com.android.permission.GET_INSTALLED_APPS`。
  - 管控 API：`getInstalledApplications`、`getInstalledApplicationsAsUser`、`getInstalledPackages`、`getInstalledPackagesAsUser`。
  - 判斷系統是否支援：`Settings.Secure` 的 `oem_installed_apps_runtime_permission_enable` 大於 0。
  - 未授予時用 `checkSelfPermission` + `requestPermissions`。
  - 若使用者拒絕，官方建議跳到應用程式資訊頁（`ACTION_APPLICATION_DETAILS_SETTINGS`）。
- 官方文件說影響範圍是「Android 13 及以上所有機型」，但海外版目前不支援；XXPermissions 的表寫「OriginOS 4 && Android 14」。兩者不一致，應以真機為準。⚠️
- 設定路徑：設定 → 應用程式與權限 → 權限管理 → 讀取已安裝應用程式列表；或應用程式資訊 → 權限；i管家 → 軟體權限管理。⚠️

#### 華為（HarmonyOS / EMUI）

- 華為是 TAF 標準的起草單位之一；HarmonyOS 3.0+ 的 Android 相容層被回報有 `com.android.permission.GET_INSTALLED_APPS`（XXPermissions、華為開發者論壇）。⚠️
- 華為應用市場審核會把「讀取已安裝應用列表」視為敏感權限：不得在首次啟動、未使用對應功能時提前彈窗；必須在使用者觸發相關功能時申請，且要同步告知目的。⚠️（華為開發者論壇回覆）
- 設定路徑：
  - 設定 → 應用程式和服務 → 權限管理。
  - 或設定 → 隱私和安全 → 應用和元服務權限管理 → 選擇應用。
  - 或應用程式資訊 → 權限。
  - HarmonyOS 4/5 的文案可能叫「應用程式列表」「讀取應用程式列表」。⚠️
- 注意：純 HarmonyOS Next 不執行 APK，使用 `ohos.permission.GET_BUNDLE_INFO`；本文只適用於 Android 相容的 HarmonyOS/EMUI。✅

#### 榮耀（MagicOS）

- 權限名稱同 TAF：`com.android.permission.GET_INSTALLED_APPS`。⚠️
- 使用者回報的兩個重點：
  1. MagicOS 的手機版曾把 `QUERY_ALL_PACKAGES` 自動轉成「讀取裝置應用程式列表」權限；但平板/MagicOS 8 的部分版本沒有這個轉換，套件列表只回自己。⚠️
  2. 應用權限入口可能**看不到**這個權限；要從「設定 → 隱私 → 隱私存取記錄 → 應用程式」進入，或關閉「權限精簡顯示」。⚠️
- 官方支援頁說明：設定 → 應用程式 → 權限管理 → 右上角 ⋮ → 設定 → 關閉「權限精簡顯示」；MagicOS 7.1+ 也可在應用程式資訊 → 權限 → 查看所有權限。✅（針對「找不到權限」的通用說明）
- MagicOS 10 有 `getInstalledPackages` 拋 `DeadSystemException` 的案例（即使已授權）；所有 PackageManager 呼叫都應 `runCatching`，並在失敗時降級。⚠️

#### 三星（One UI）

- 三星沒有採用 TAF 的 `GET_INSTALLED_APPS`；One UI 5.1.1+ 使用自家的 `com.samsung.android.permission.GET_APP_LIST`。⚠️
- 這是執行時權限，`dumpsys package` 可在 runtime permissions 中看到；只宣告 `QUERY_ALL_PACKAGES` 仍可能被要求授予。⚠️
- 設定路徑不統一：
  - 應用程式資訊 → 權限，看看有沒有「應用程式列表」。
  - 若沒有，使用 `com.android.settings/.Settings$AppOpsDetailsActivity` 的 AppOps 詳細頁（XXPermissions 的做法）。
  - 或設定 → 應用程式 → 右上角 ⋮ → 特殊存取權 / 權限管理器。
- **衝突說明**：SD Maid 的 PR 說「Samsung 沒有這個權限」，但它指的是 `GET_INSTALLED_APPS`；三星自家名稱是 `GET_APP_LIST`，兩者不矛盾。實際是否每個 One UI 版本都能用 `requestPermissions` 取得，仍需實測。⚠️
- 有使用者回報 One UI 6 / Android 14 也有「套件列表只回自己」的問題；NekoBox issue #476 同時提到榮耀與三星。⚠️

#### 其他

- 魅族 Flyme 9+ 也有讀取應用列表權限，但實作與 TAF 不完全相同；XXPermissions 有相容處理。⚠️
- Pixel / AOSP 沒有額外的「應用列表」開關；如果列表不完整，先檢查 targetSdk、`QUERY_ALL_PACKAGES`、`<queries>` 與工作資料夾。✅

### 3.4 直接跳轉設定頁的 Intent

#### 原則

1. **沒有一個 100% 通用的「應用列表權限頁」Intent。** 最通用的保底是 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`（應用程式資訊頁）。✅
2. 廠商專用 Intent 一律視為「best effort」：用 `try/catch`，失敗就退回應用程式資訊頁。
3. `resolveActivity()` 可能因套件可見性回傳 null，但 `startActivity()` 仍可能成功；不要只用 `resolveActivity()` 判斷，要實際嘗試並捕捉例外。⚠️
4. 從非 Activity 的 Context 啟動時加 `FLAG_ACTIVITY_NEW_TASK`。
5. 不要使用未經驗證的內部 Component；ROM 更新後很容易壞。

#### 通用：應用程式資訊頁（所有 ROM）

```kotlin
fun appDetailsIntent(context: Context, packageName: String = context.packageName): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
```

這是 vivo 官方文件建議的引導方式，也是所有 ROM 的保底頁。✅

#### 小米 / HyperOS：應用權限編輯器

```kotlin
fun miuiPermissionEditorIntent(context: Context): Intent =
    Intent("miui.intent.action.APP_PERM_EDITOR")
        .putExtra("extra_pkgname", context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
```

- `extra_pkgname` 是 XXPermissions 使用的鍵；小米自己開放 `miui.intent.action.APP_PERM_EDITOR` 讓應用跳到權限編輯器。⚠️
- 沒有指定 Component；由系統的 security center 處理。HyperOS 3/4 是否仍支援、是否直接落在「取得應用程式列表」那一頁，需實測。⚠️
- 失敗時退回 `appDetailsIntent()`。

#### 三星：AppOps 詳細頁

```kotlin
fun samsungAppOpsIntent(context: Context): Intent =
    Intent().apply {
        component = ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$AppOpsDetailsActivity",
        )
        data = Uri.parse("package:${context.packageName}")
        putExtra(
            ":settings:show_fragment_args",
            Bundle().apply { putString("package", context.packageName) },
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
```

- 這是 XXPermissions 的實作；用 `:settings:show_fragment_args` 傳 package。⚠️
- 若頁面不存在或跳轉失敗，退回 `appDetailsIntent()`。
- 也可以先嘗試用 `requestPermissions` 申請 `com.samsung.android.permission.GET_APP_LIST`；若沒有對話框，再開這個頁。⚠️

#### OPPO / OnePlus / Realme：帶權限提示的應用程式資訊頁

```kotlin
fun colorOsAppDetailsIntent(context: Context): Intent =
    appDetailsIntent(context).apply {
        putStringArrayListExtra(
            "permissionList",
            arrayListOf("com.android.permission.GET_INSTALLED_APPS"),
        )
        putExtra("isGetPermission", true)
    }
```

- `permissionList` 放原生權限名稱；`isGetPermission=true` 是 OPPO 的跳轉優化標識。⚠️
- 這是「應用程式資訊 → 權限」頁的優化，不是獨立的應用列表頁；仍然需要使用者自己點「讀取應用程式列表」。⚠️
- 失敗時退回不帶 extras 的 `appDetailsIntent()`。

#### vivo / 華為 / 榮耀

- 沒有找到可靠的專用 Component；官方文件（vivo）也只建議應用程式資訊頁。✅
- 建議直接使用 `appDetailsIntent()`，並在文案中說明：
  - vivo：應用程式資訊 → 權限 → 讀取已安裝應用程式列表。
  - 華為：應用程式資訊 → 權限，或設定 → 應用程式和服務 → 權限管理。
  - 榮耀：應用程式資訊 → 權限 → 查看所有權限；若沒有，關閉「權限精簡顯示」，或到「隱私 → 隱私存取記錄 → 應用程式」。

#### 廠商安全中心（最後手段）

```kotlin
fun securityCenterIntent(context: Context): Intent? =
    listOf(
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.iqoo.secure",
        "com.huawei.systemmanager",
        "com.hihonor.systemmanager",
    ).firstNotNullOfOrNull { pkg ->
        context.packageManager.getLaunchIntentForPackage(pkg)
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
```

- 這只會打開安全中心首頁，不會直達權限頁；只能當作「找不到設定時」的輔助入口。⚠️
- `getLaunchIntentForPackage()` 本身受套件可見性影響；在列表受限時可能回 null。⚠️

#### 建議的跳轉優先級

| ROM | 第一嘗試 | 第二嘗試 | 保底 |
|---|---|---|---|
| 小米 / Redmi | `miui.intent.action.APP_PERM_EDITOR` | 應用程式資訊頁 | 設定首頁 |
| 三星 | `Settings$AppOpsDetailsActivity` | 應用程式資訊頁 | 特殊存取權頁 |
| OPPO / OnePlus / Realme | 帶 `permissionList` 的應用程式資訊頁 | 一般應用程式資訊頁 | 權限管理頁 |
| vivo / iQOO | 應用程式資訊頁 | 權限管理頁 | 設定首頁 |
| 華為 | 應用程式資訊頁 | 權限管理頁 | 設定首頁 |
| 榮耀 | 應用程式資訊頁（權限 → 查看所有權限） | 隱私存取記錄 | 設定首頁 |
| Pixel / AOSP | 應用程式資訊頁 | 無 | 設定首頁 |

> 任何一跳轉都要 `try/catch`；`ActivityNotFoundException`、`SecurityException`、`IllegalArgumentException` 都要視為失敗並退回下一順位。

---

## 4. 如何可靠偵測「列表受限」

### 4.1 方案比較

| 方案 | 能回答什麼 | 優點 | 缺點 / 誤判 | 建議用途 |
|---|---|---|---|---|
| `checkSelfPermission("android.permission.QUERY_ALL_PACKAGES")` | 清單有沒有宣告 | 簡單 | **永遠 GRANTED**（normal 權限）；不能反映 ROM 封鎖 | 只用來確認清單有宣告，不要當授權狀態 |
| `checkSelfPermission("com.android.permission.GET_INSTALLED_APPS")` | ROM 的 TAF 權限是否授予 | 直接、官方 | 權限不存在時回 DENIED；MIUI 舊版可能要靠 AppOps；少數 ROM 名稱/實作不同 | **主要信號之一**（先確認 `getPermissionInfo` 存在） |
| `checkSelfPermission("com.samsung.android.permission.GET_APP_LIST")` | 三星自家權限是否授予 | 三星唯一直接信號 | 只有 One UI 5.1.1+；其他 ROM 不存在 | 三星專用 |
| `getPermissionInfo(name, 0)` | ROM 是否支援這個權限、保護等級 | 可區分「不存在」與「存在但拒絕」 | 權限可能存在但實際由 AppOps 控制 | 決定是否要 `requestPermissions` |
| `getInstalledPackages(0).size` | 目前看得到的套件數 | 簡單、跨 ROM | 工作資料夾/精簡裝置可能天然很少；ROM 可能只回系統應用（仍 >20） | 配合基線與 `android` sentinel，不單獨用 |
| `getApplicationInfo("android", 0)` | 框架套件是否可見 | 很強的「被鎖」信號；SD Maid 也用這個檢查 | 少數 ROM 可能連正常狀態也失敗；需實測 | **主要信號之一** |
| 已知系統套件（Settings/SystemUI/…） | 是否還看得到系統應用 | 可讀性高 | 名稱因 ROM 而異；launcher 查詢可能讓它們仍可見；GMS 在中國 ROM 不存在 | 輔助，不要用 `== 0` 判死 |
| `getPackagesForUid(uid)` | UID 對應的包名 | 可直接從 blocked flow 的 UID 查 | AOSP 也會 visibility 過濾；OEM 可能額外管控；shared UID 多個包 | 降級還原包名 |
| `getApplicationInfo(pkg)` / `getPackageInfo(pkg)` | 單一包名是否存在/標籤/簽名 | 精確 | 不可見時 `NameNotFoundException`，無法區分未安裝 | 已知包名時取詳情；失敗就降級 |
| `queryIntentActivities(MAIN/LAUNCHER)` | launcher 應用清單 | 部分 ROM 在列表被鎖時仍可回傳大量應用；可拿 label/icon/uid | 只涵蓋有 launcher activity 的應用；可能重複；未來可能被鎖 | **降級用**，不要當授權判定 |
| `UsageStatsManager.queryEvents()` | 最近使用過的包名 | 可補一些 PM 看不到的包名 | 需要「使用情況存取權」；不是 socket owner；shared UID 易混淆；R+ 鎖屏時可能 null | 低信心的輔助線索 |
| `AppOpsManager.unsafeCheckOpNoThrow("android:get_installed_apps", …)` | ROM 的 AppOps 狀態 | 可避開 `checkSelfPermission` 的實作差異 | op 名稱/編號是廠商私有；可能拋例外或回錯；不是公開 API 契約 | 診斷/實驗，不要單獨 gating |
| `Settings.Secure "oem_installed_apps_runtime_permission_enable"` | vivo 是否啟用執行時管控 | 官方文件提供 | 只代表「系統支援」，不代表已授權；其他 ROM 可能沒有 | 能力偵測的輔助 |

### 4.2 建議的保守判定

建議回傳四態，而不是 boolean：

```kotlin
sealed interface AppListState {
    data object Ok : AppListState
    data class Restricted(val reason: String) : AppListState
    data class Suspicious(val reason: String) : AppListState
    data class Unknown(val reason: String) : AppListState
}
```

判定順序（建議）：

1. **Android < 30** → `Ok`（沒有 AOSP 過濾；若 ROM 仍有 TAF 權限，另做檢查）。
2. **ROM 權限存在且未授予** → `Restricted("oem permission denied")`。
   - 先查 `com.android.permission.GET_INSTALLED_APPS`；存在且 `PROTECTION_DANGEROUS` 時，`checkSelfPermission` 不是 GRANTED 就判定受限。
   - 再查 `com.samsung.android.permission.GET_APP_LIST`；同上。
   - 若 `getPermissionInfo` 丟 `NameNotFoundException`，代表這個 ROM 沒有此權限，不要拿 `checkSelfPermission` 的 DENIED 當成受限。
   - 若 vivo 的 `oem_installed_apps_runtime_permission_enable` 為 1，但 `GET_INSTALLED_APPS` 查不到，仍可用 `checkSelfPermission` 嘗試；失敗則交給下面。
3. **`getApplicationInfo("android", 0)` 失敗** → `Restricted("framework package hidden")`。
   - 正常 Android 的 `android` 套件應可見；被 ROM 鎖住時常被藏起來（SD Maid 的 sanity check）。
   - 仍要用 `runCatching`；若整個 PackageManager 異常，回 `Unknown`，不要直接判受限。
4. **安裝數量明顯異常**：
   - 有上次成功基線：`installed < baseline * 0.5` 且 baseline >= 30 → 先標記為 `Suspicious("count drop")`。
   - 只有數量下降**不足以**判定受限（使用者可能真的移除大量應用）；要配合 `android` 不可見或 launcher 數量明顯更多，才升級為 `Restricted`。否則回 `Unknown("count drop only")`，只顯示被動提示。
   - 沒有基線：`installed < 5` 先回 `Unknown("small device")`，除非 launcher 數量明顯更多。
5. **Launcher 數量交叉檢查**：
   - `uniqueLauncherPackages > installed + 10` → `Restricted("launcher mismatch")`。
   - 例：`installed = 1`、`launcher = 13`（MIUI）或 `155`（ColorOS）→ 很強的信號。
   - 若 `queryIntentActivities` 也被鎖，這個信號不會出現；回到第 3 步。
6. **其他情況** → `Ok`（或 `Unknown`，如果資料不足）。

> `Suspicious` 與 `Unknown` 都只顯示被動提示與「重新檢查」，不要直接彈權限對話框；只有 `Restricted` 才進入引導流程。這樣可以避免使用者真的移除大量應用、或工作資料夾天然很小時被誤導。

#### 為什麼不用 `installed < 20` 當唯一條件

- 工作資料夾、精簡裝置、模擬器可能天然少於 20 個套件。
- 有些 ROM 會回傳 20–50 個系統應用，但第三方應用全部隱藏；數量門檻會漏判。
- 反之，若 ROM 只回傳 15 個系統應用，`< 20` 會誤判；但 `android` sentinel 與 launcher 交叉檢查可以補強。

#### 建議保留的診斷資訊

在日誌/診斷包中記錄（注意隱私，必要時只記數量）：

- `SDK_INT`、`Build.MANUFACTURER`、`Build.MODEL`、ROM 版本。
- `installedCount`、`internetAppCount`、`launcherCount`、`visibleKnownCount`。
- `oemPermissionSupported`、`oemPermissionGranted`、`samsungPermissionSupported/Granted`。
- `androidPackageVisible`。
- 判定結果與 reason。
- 不要記錄完整包名清單到公開日誌；診斷包要脫敏。

### 4.3 檢查時機與避免誤報

- **啟動後**：`awaitStarted()` 之後檢查一次（目前做法）。
- **回到前景**：`MainActivity.onResume()` 或 Compose lifecycle `ON_RESUME` 重查；目前 `AppRoot` 只查一次，從設定回來不會更新。權限被撤銷/授予通常**不會**觸發 `PACKAGE_*` 廣播，必須靠前景重查。✅
- **套件廣播**：`PACKAGE_ADDED/REMOVED/REPLACED` 後重查（目前 `AppCatalog` 有 receiver）。
- **使用者手動**：設定頁提供「重新檢查」按鈕。
- **第一次顯示詢問前**：若快取超過 30 秒，可重查一次；不要在每個封包上查。
- **避免迴圈**：從設定返回後若仍受限，只顯示一次被動提示（snackbar/卡片），不要立刻再彈同一個對話框。記錄「上次提示時間」，例如一天一次，或直到使用者手動點「重新檢查」。
- **權限可能被自動重置**：Android 11+ 會自動重置長期未使用應用的執行時權限；ROM 的應用列表權限也可能被收回。每次真正需要使用列表時都應重查，不要假設上次授予就一直有效。⚠️
- **工作資料夾 / 多使用者**：基線要按 `UserHandle.myUserId()` 分開存；不要把工作資料夾的小清單和主使用者比較。
- **模擬器**：套件數天然少；用 `android` sentinel 與 launcher 交叉檢查，避免一開始就彈「受限」。

### 4.4 目前 Kotlin 版 `visibility()` 的具體問題

| 問題 | 影響 | 建議 |
|---|---|---|
| `checkPermission("android.permission.QUERY_ALL_PACKAGES")` | 永遠 GRANTED，無法反映 ROM 封鎖 | 改成檢查 `GET_INSTALLED_APPS` / `GET_APP_LIST` |
| `installed < 20` | 工作資料夾/模擬器誤判；只回系統應用時漏判 | 加 `android` sentinel、baseline、launcher 交叉檢查 |
| `visibleKnown == 0` | 已知套件清單不適用所有 ROM；GMS 在中國 ROM 不存在 | 只當輔助；不要單獨判受限 |
| 沒有 `getPermissionInfo` 檢查 | 在 AOSP 上 `checkSelfPermission(GET_INSTALLED_APPS)` 會回 DENIED，可能誤判 | 先確認權限存在且是 dangerous |
| 只查一次 | 從設定回來、換使用者、安裝/移除應用後狀態過期 | `onResume` / 廣播 / 手動重查 |
| `SettingsScreen` 用 `remember { }` | 返回設定頁不會刷新 | 用 lifecycle / `LaunchedEffect` / 每次進入重查 |

---

## 5. 沒有權限時：從 blocked flow 的 UID 還原應用資訊

### 5.1 可用資料來源

| 來源 | 能得到什麼 | 可信度 | 限制 |
|---|---|---|---|
| `ConnectivityManager.getConnectionOwnerUid(protocol, local, remote)` | socket 的 UID | 高 | 只有目前作用中的 VpnService 能觀察；找不到回 `Process.INVALID_UID`；不是包名 |
| 路由計畫 / 既有規則 | 包名（規則建立時保存） | 高 | 只有已建立規則的應用；新應用沒有 |
| `PackageManager.getPackagesForUid(uid)` | UID 對應的包名 | 中 | AOSP 也會 visibility 過濾；OEM 可能額外管控；shared UID 多個 |
| `PackageManager.getNameForUid(uid)` | UID 的官方名稱（可能是 `u0_a123`、`android.uid.system`） | 低 | 不是包名；可能 null |
| Launcher 索引：`queryIntentActivities(MAIN/LAUNCHER)` | 有 launcher 圖示的包名、label、icon、UID | 中高 | 只涵蓋有 launcher activity 的應用；部分 ROM 可能仍過濾 |
| `getApplicationInfo(pkg)` / `getPackageInfo(pkg)` | label、icon、簽名、版本 | 高（若可見） | 不可見時 `NameNotFoundException` |
| `UsageStatsManager.queryEvents()` | 最近使用過的包名 | 低 | 需要「使用情況存取權」；不是 socket owner；shared UID 易混淆 |
| `/proc/net/tcp`、`/proc/<pid>` | socket → UID/process | 不可用 | Android 10+ SELinux 禁止應用讀 `/proc/net/tcp`、`/proc/net/udp`；`/proc/<pid>` 受 hidepid 限制。官方明確建議 VPN 用 `getConnectionOwnerUid()`。✅ |
| `NetworkStatsManager` / `TrafficStats` | 按 UID 的流量 | 中 | 只有 UID，沒有包名；不能做 flow → app 對應 |
| `ActivityManager.getRunningAppProcesses()` | 執行中的 process/UID | 低 | 新版本只回自己的 process；不能當全局清單 |
| `PackageManager.getNamesForUids(int[])` | 多個 UID 的名稱 | 不可用 | `@hide` / `@TestApi`，不是公開 API；不要反射。✅ |

> **重要更正**：`PackageManager.getNamesForUid(int)` 這個單數方法在公開 SDK 中不存在。公開的是 `getNameForUid(int)`；多數版本是 `getNamesForUids(int[])`，且標記 `@hide`/`@TestApi`。如果看到有人建議用 `getNamesForUid`，那是筆誤或隱藏 API。

### 5.2 建議的解析順序

對每個 blocked flow：

1. **取得 UID**：`getConnectionOwnerUid()`；必須在背景執行緒。若回 `Process.INVALID_UID`（-1），記錄 `unknownOwner`，不要當成一般 UID。
2. **過濾系統 UID**：`uid % 100000 < 10000` 視為系統 UID，只計數、不上報（目前做法）。
3. **查路由計畫**：`plan.find(uid)?.packageName`。這是最可靠的包名來源，因為規則建立時已保存。
4. **查 `getPackagesForUid(uid)`**：可能回一個或多個包名；shared UID 取排序後的第一個作為 primary，但 UI 要顯示全部。
5. **查 launcher 索引**：用包名或 UID 找 `ResolveInfo`；可直接拿 `activityInfo.applicationInfo` 的 label/icon/uid，不需要再 `getApplicationInfo()`。
6. **查 `getNameForUid(uid)`**：只作為顯示名稱的最後補充。
7. **只剩 UID**：建立「UID 10xxx」的降級詢問；不要丟棄 flow。

#### 建議的快取

- `packageName -> label/icon/uid`：在列表正常時建立；列表受限時仍可用。
- `uid -> packages`：從 `getPackagesForUid` 與 launcher 索引合併。
- **不要**快取整個應用清單到公開日誌或雲端；這是敏感資料。
- 圖示快取可以是記憶體 LRU； disk 快取要考慮隱私與空間，且套件更新後要失效。

### 5.3 詢問 UI 的降級顯示

| 已知資訊 | 標題 | 副標題 | 圖示 | 動作 |
|---|---|---|---|---|
| label + package + UID | `Chrome 想要連線網路` | `com.android.chrome · UID 10145` + 目標 | 應用圖示 | 允許/拒絕（可永久） |
| package + UID（無 label） | `com.example.app 想要連線網路` | `UID 10xxx` + 目標 | 通用圖示/字母 | 允許/拒絕；永久規則要警告「簽名未知」 |
| 只有 UID | `未知應用（UID 10xxx）想要連線網路` | `目標：example.com:443 · TCP` | 通用圖示 | 只能「本次允許/拒絕」；不建立永久包名規則 |
| shared UID | `Chrome、Chrome Beta 想要連線網路` | `共用 UID 10145；規則對整組生效` | 第一個應用圖示 | 允許/拒絕（整組） |
| 系統 UID | 不上報 | 只記日誌/計數 | — | — |

#### 具體文案建議（繁中）

- 有包名無 label：
  - 標題：「`com.example.app` 想要連線網路」
  - 副標：「無法取得應用程式名稱（套件列表受限）· UID 10xxx」
- 只有 UID：
  - 標題：「未知應用程式（UID 10xxx）想要連線網路」
  - 副標：「目標：example.com:443 · TCP。無法識別應用程式，這次的決定不會永久保存。」
- shared UID：
  - 標題：「`A`、`B` 想要連線網路」
  - 副標：「這些應用程式共用 UID 10xxx，規則會對整組生效。」

#### 絕對不要

- 只顯示「？」或「未知應用」而沒有包名/UID/目標。
- 在只有 UID 時仍提供「永久允許」並默默建立一條無法驗證簽名的規則。
- 把「列表受限」當成「應用不存在」而直接丟棄詢問。

### 5.4 規則建立與安全

- **包名已知、簽名未知**：可以建立以 `android-app://<package>` 為 path 的規則，但不要聲稱已驗證簽名；UI 要標示「簽名未驗證」。目前 `AppMatcherFactory.create()` 會把 `requiredSignerId` 設為 null，代表不檢查簽名；這會讓同包名替換的應用也能匹配。⚠️
- **只有 UID**：不要建立永久包名規則；用「本次允許/拒絕」的臨時規則，或按 UID 的短暫抑制（重啟/時間到期即失效）。目前 Core 的規則模型是包名/路徑導向，沒有 UID 條件；需要另外的臨時機制。
- **shared UID**：規則對整組生效；UI 要明講。目前 `packagesForUid` 取第一個當 primary，其餘只在 `AppRouting.packages` 中；詢問只顯示一個。建議顯示整組。
- **簽名與竄改**：沒有 PackageInfo 就沒有簽名；`PromptOverlay` 的 `isTamperAlert` 不會正確運作。列表受限時應暫停「簽名已變更」的判定，避免誤報。
- **既有規則**：如果規則已存在，`packageName` 可從規則取得；但 `BlockedFlowReporter` 仍需要建立 `AppIdentity` 才能讓 `RuleSet` 匹配。建議用包名構造最小身份：
  ```kotlin
  AppIdentity(
      path = "android-app://$packageName",
      displayName = packageName,
      signature = null,
  )
  ```
  這樣至少 path 匹配可用；簽名匹配仍不可用。

---

## 6. 啟動引導文案與「去設定」

### 6.1 三語文案

#### 簡體中文

**標題**：允許獲取應用列表

**正文**：
> 為了顯示被攔截應用的名稱和圖標，NetworkControl 需要「獲取應用列表」權限。請點「去設置」，在應用信息 → 權限（或權限管理）中，把「獲取應用列表 / 讀取已安裝應用列表 / 應用列表」設為「始終允許」。
>
> 不同品牌的名稱和位置可能不同。如果找不到，請在設置頂部搜索「應用列表」或「權限」，或到「隱私 → 隱私訪問記錄 → 應用」中查找。
>
> 沒有這項權限時，詢問仍會顯示應用的包名和 UID，攔截也會繼續工作，只是無法顯示名稱和圖標。

**按鈕**：去設置 / 稍後

#### 繁體中文

**標題**：允許取得應用程式列表

**正文**：
> 為了顯示被封鎖應用程式的名稱和圖示，NetworkControl 需要「取得應用程式列表」權限。請點「前往設定」，在應用程式資訊 → 權限（或權限管理）中，將「取得應用程式列表 / 讀取已安裝應用程式列表 / 應用程式列表」設為「始終允許」。
>
> 不同品牌的名稱和位置可能不同。如果找不到，請在設定頂部搜尋「應用程式列表」或「權限」，或到「隱私 → 隱私存取記錄 → 應用程式」中尋找。
>
> 沒有這項權限時，詢問仍會顯示應用程式的套件名稱和 UID，封鎖也會繼續運作，只是無法顯示名稱和圖示。

**按鈕**：前往設定 / 稍後

#### English

**Title**: Allow app list access

**Body**:
> To show the names and icons of blocked apps, NetworkControl needs permission to read the app list. Tap “Open settings”, then go to App info → Permissions (or Permission manager) and set “Get app list / Read installed apps / App list” to “Always allow”.
>
> The name and location vary by manufacturer. If you can’t find it, search Settings for “app list” or “permissions”, or look under Privacy → Privacy access records → Apps.
>
> Without this permission, prompts still show the package name and UID, and blocking still works; only the app name and icon are missing.

**Buttons**: Open settings / Later

### 6.2 各 ROM 的補充提示（可放在「了解更多」或折疊區）

| ROM | 補充提示 |
|---|---|
| 小米 / Redmi | 應用程式資訊 → 權限管理 → 取得應用程式列表 → 始終允許。海外版可能在 Permissions → Other permissions。 |
| OPPO / OnePlus / Realme | 設定 → 權限與隱私 → 權限管理 → 讀取應用程式列表；部分版本在「特殊權限」。 |
| vivo / iQOO | 設定 → 應用程式與權限 → 權限管理 → 讀取已安裝應用程式列表。 |
| 華為 | 設定 → 應用程式和服務 → 權限管理；或應用程式資訊 → 權限。 |
| 榮耀 | 權限管理右上角 ⋮ → 關閉「權限精簡顯示」；或隱私 → 隱私存取記錄 → 應用程式。 |
| 三星 | 應用程式資訊 → 權限；若沒有「應用程式列表」，用 AppOps 詳細頁或特殊存取權。 |

### 6.3 「去設定」按鈕應該打開哪個頁

**建議順序**：

1. 如果 ROM 權限存在且尚未永久拒絕：**先在自己的 Activity 裡 `requestPermissions`**，讓系統對話框出現。這是最短路徑，也符合 Google/華為的「在使用者情境中申請」要求。
2. 如果系統不彈窗、回傳拒絕，或使用者已選擇「不再詢問」：開啟設定頁。
3. 設定頁優先級：
   - 小米：`miui.intent.action.APP_PERM_EDITOR` → 應用程式資訊頁。
   - 三星：`Settings$AppOpsDetailsActivity` → 應用程式資訊頁。
   - OPPO/OnePlus/Realme：帶 `permissionList` 的應用程式資訊頁 → 一般應用程式資訊頁。
   - vivo/華為/榮耀：應用程式資訊頁 → 權限管理頁。
   - AOSP/Pixel：應用程式資訊頁。
4. 每一跳都要 `try/catch`；失敗就退下一順位。
5. 如果只能開應用程式資訊頁，文案要寫清楚「往哪裡點」；不要只寫「請去設定開啟權限」。

### 6.4 只能打開應用程式資訊頁時的說明

可以放在對話框的「找不到？」展開區：

> 1. 在應用程式資訊頁點「權限」或「權限管理」。
> 2. 點右上角「⋮」→「顯示所有權限」或「查看所有權限」（部分系統預設隱藏不常用權限）。
> 3. 找到「取得應用程式列表」「讀取已安裝應用程式列表」「應用程式列表」或「查詢所有應用包」，設為「始終允許」。
> 4. 如果沒有這個選項：返回設定首頁，在搜尋框輸入「應用程式列表」或「權限」；榮耀手機可到「隱私 → 隱私存取記錄 → 應用程式」尋找。
> 5. 改完後回到 NetworkControl，設定頁可按「重新檢查」。

### 6.5 引導時機

- **不要在一開機、背景或首次啟動尚未顯示 UI 時就呼叫 `getInstalledPackages()` 並彈權限。** 華為應用市場審核明確反對「未使用對應功能就提前申請」。⚠️（華為開發者論壇回覆）
- 建議時機：
  1. 使用者第一次進入「應用程式」頁，或第一次看到「列表受限」的被動提示時。
  2. 第一次出現無法識別應用的詢問時（使用者正在處理該連線）。
  3. 設定頁的「應用程式列表權限」卡片。
- 先顯示自己的說明（為什麼需要、資料只留在本機），再呼叫系統對話框；不要直接彈系統框。
- 使用者選「稍後」時，不要重複彈；保留被動狀態與手動按鈕。

### 6.6 文案邊界

- 不要說「沒有這個權限就無法保護」；實際上攔截仍有效，只是按應用控制退化。
- 不要承諾「開啟後一定能看到所有應用」；不同 ROM 仍有差異。
- 要說明「應用列表只在本機使用，不會上傳」；這是敏感權限，使用者會在意。

---

## 7. 對 Kotlin 版的具體建議（不改程式碼，僅列出差距）

> 以下為研究結論與建議，本文不修改 `android-kotlin/` 的任何程式碼。

### 7.1 清單（最高優先）

```xml
<!-- 國標 TAF；小米/OPPO/vivo/華為/榮耀等 -->
<uses-permission android:name="com.android.permission.GET_INSTALLED_APPS" />

<!-- 三星 One UI 5.1.1+ -->
<uses-permission android:name="com.samsung.android.permission.GET_APP_LIST" />
```

- Android Lint 可能對非 AOSP 權限給「未知權限」警告；確認後可局部抑制，不要因此移除宣告。
- 不要加 `do_not_need_get_installed_apps` meta-data。
- 保留 `QUERY_ALL_PACKAGES`；它不是替代品，但仍是 AOSP 的基礎。
- 若上架 Google Play，`QUERY_ALL_PACKAGES` 需要用途宣告；OEM 權限不是 Play 權限，但也應在隱私說明中揭露。

### 7.2 權限請求（高優先）

- 以 `getPermissionInfo` 判斷 ROM 是否支援；只在 `PROTECTION_DANGEROUS` 時請求。
- 先請求 TAF 權限；三星裝置改請求 `GET_APP_LIST`。
- 使用 Activity/Compose 的權限 launcher；不要從 Application/Service 請求。
- 處理 `onRequestPermissionsResult`；若沒有回呼或仍拒絕，進入設定引導。使用者拒絕兩次後，系統可能不再彈窗，只能走設定。⚠️
- 不要一次請求多個自訂權限；XXPermissions issue #175 提到，在 AOSP 上與其他危險權限一起請求可能導致 `GET_INSTALLED_APPS` 授權失敗。⚠️
- 請求前先顯示自己的說明；不要在冷啟動/背景直接請求。

Compose 可以用標準的權限 launcher（權限名稱是字串，不是 `Manifest.permission` 常數）：

```kotlin
val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    // 重新檢查 visibility()；granted 可能為 false，仍要處理
}
launcher.launch("com.android.permission.GET_INSTALLED_APPS")
```

- 三星裝置把上面的字串換成 `com.samsung.android.permission.GET_APP_LIST`。
- 不要同時 launch 多個自訂權限；一次一個，並在 callback 後重查。

### 7.3 `AppCatalog.visibility()`（高優先）

建議改成：

```text
1. SDK < 30 → Ok
2. TAF/三星權限存在且未授予 → Restricted
3. getApplicationInfo("android", 0) 失敗 → Restricted
4. 有 baseline 且 installed < baseline * 0.5 → Suspicious（需第二信號）
5. launcherUnique > installed + 10 → Restricted
6. installed < 5 且沒有其他信號 → Unknown
7. 其他 → Ok
```

- 保留 `installedCount`、`launcherCount`、`androidVisible`、`reason`，供設定頁與診斷包使用。
- `visibleKnown` 只當輔助；不要用 `== 0` 判死。
- 把 `QUERY_ALL_PACKAGES` 的檢查移除或降級為「清單有宣告」的建置期檢查。

### 7.4 生命週期（高優先）

- `AppRoot`：從設定返回、`ON_RESUME`、套件廣播後重查；不要只查一次。
- `SettingsScreen`：不要用 `remember { }` 快取 `appListVisibility()`；每次進入或 resume 重查。
- 若仍受限，顯示被動提示，不要立刻重彈對話框。
- 將「上次提示時間」存在 `AppPreferences`，避免每次啟動都打擾。

### 7.5 詢問降級（高優先）

- `BlockedFlowReporter`：
  - `packageName == null` 時不要 `return`；保留 UID，建立降級詢問。
  - `catalog.find(packageName) == null` 時，用包名構造最小 `AppIdentity`，讓 path 規則仍可匹配。
- `PromptOverlay` / `PromptNotifier`：
  - 顯示 `label` → `packageName` → `UID` 的順序；不要把 `executablePath` 當成唯一 fallback。
  - 過濾空字串、`?`、`null` 的 label；退回包名或 UID，不要直接把問號顯示給使用者。
  - 在副標顯示 `packageName · UID`；只有 UID 時顯示「無法識別應用程式」。
  - 對只有 UID 的詢問，停用「永久」或標示「簽名未驗證」。
- `AppIconLoader`：
  - 先試 `getApplicationIcon(packageName)`；失敗時用 launcher 索引的 `ResolveInfo` 圖示；再失敗用通用圖示/字母，不要問號。
- `LogsScreen` / 記錄：
  - 保存 `packageName`、`uid`、`label`（當下快照）；顯示時用同一套 fallback。
- shared UID：
  - 顯示整組包名；規則說明「對整組生效」。

### 7.6 Launcher 索引（中優先）

- 建立 `packageName -> ResolveInfo` 索引：
  ```kotlin
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
  ```
- 去重包名；取 `activityInfo.applicationInfo` 的 `uid`、`loadLabel(pm)`、`loadIcon(pm)`。
- 這可以在 `getInstalledPackages` 被鎖時，仍為大多數使用者可見的應用提供名稱/圖示。⚠️
- 但這不是完整清單：沒有 launcher activity 的系統服務、背景 SDK 不會出現；不要用它取代權限。
- 不要為了這個在 `<queries>` 加 `MAIN/LAUNCHER`；小米 HyperOS 文件明確禁止這種「能查大量應用」的 intent filter。✅

### 7.7 設定跳轉（中優先）

- 將 `AppBackend.openAppSettings()` 改成「先試廠商專用 Intent，再退回應用程式資訊頁」。
- 不要在 UI 層直接 `startActivity`；集中在 backend，方便測試與捕捉例外。
- 針對小米、三星、OPPO 各寫一個 intent builder；vivo/華為/榮耀用通用頁。
- 加 `resolveActivity` 檢查與 `try/catch`；失敗時記錄 reason（不記錄敏感資料）。

### 7.8 診斷（中優先）

- 診斷包加入：
  - ROM/型號/API；
  - `installedCount`、`launcherCount`、`androidVisible`；
  - OEM 權限支援/授予狀態；
  - 最近一次判定 reason；
  - `getPackagesForUid` 對 blocked UID 的結果（是否 null、數量）。
- 脫敏：不要匯出完整應用清單；包名可只保留前幾個或 hash，視支援需求而定。

### 7.9 測試（高優先）

- 單元測試：`visibility()` 的多態判定（用 fake PackageManager/注入介面）。
- 場景測試：列表為空、只有自己、只有系統應用、shared UID、UID 無法解析、PackageManager 拋例外。
- 裝置測試：見 §8。

---

## 8. 實測計劃

### 8.1 優先順序

| 優先級 | 裝置 / ROM | 要驗證的重點 |
|---|---|---|
| P0 | 小米 / Redmi HyperOS 中國版（HyperOS 1/2/3） | 預設是否拒絕；`requestPermissions` 是否彈窗；`APP_PERM_EDITOR` 是否直達；`getPackagesForUid` 是否可用；`queryIntentActivities` 是否仍回 launcher |
| P0 | OPPO / OnePlus ColorOS 13/14/15 | `GET_INSTALLED_APPS` 是否 runtime；AppOps 名稱；應用程式資訊頁是否顯示；`permissionList` extras 是否有效 |
| P0 | vivo / iQOO OriginOS 4/5（中國版） | `oem_installed_apps_runtime_permission_enable`；`requestPermissions`；應用程式資訊頁；海外版是否真的不支援 |
| P0 | 榮耀 MagicOS 7/8/9/10 | 是否自動把 `QUERY_ALL_PACKAGES` 轉成「讀取裝置應用程式列表」；權限是否藏在隱私存取記錄；平板是否例外；`DeadSystemException` |
| P0 | 華為 HarmonyOS 3/4/5（Android 相容） | 權限彈窗時機；應用程式資訊頁；應用市場審核文案；EMUI 是否不同 |
| P1 | 三星 One UI 6/7/8 | `GET_APP_LIST` 是否 runtime；`requestPermissions` 是否有效；AppOps 頁；設定頁名稱 |
| P1 | Pixel / AOSP 模擬器 | 正常基線；沒有 `QUERY_ALL_PACKAGES` 時的行為；`forceQueryable` 清單 |
| P1 | 工作資料夾 / Android 15 私人空間 | 列表是否按使用者隔離；VPN 是否覆蓋；基線是否誤判 |
| P2 | Meizu Flyme 9+ | 權限名稱/實作差異 |
| P2 | 小米 Second Space / 雙開應用 | UID/使用者隔離；列表是否重複 |

### 8.2 每次測試要記錄的數據

```text
ROM / 型號 / Android 版本 / targetSdk
安裝來源（側載/商店）
getInstalledPackages(0).size
getInstalledApplications(0).size
queryIntentActivities(MAIN/LAUNCHER) 去重後數量
getApplicationInfo("android", 0) 是否成功
getPackagesForUid(<被測應用 UID>) 是否 null / 數量
checkSelfPermission(QUERY_ALL_PACKAGES)
checkSelfPermission(GET_INSTALLED_APPS)
checkSelfPermission(GET_APP_LIST)
getPermissionInfo(GET_INSTALLED_APPS) 是否存在 / protectionLevel
Settings.Secure oem_installed_apps_runtime_permission_enable
adb shell appops get <pkg>
設定頁實際路徑與開關名稱（截圖/文字）
requestPermissions 是否彈窗；允許/拒絕後的行為
授予後 getInstalledPackages 數量
從設定返回後 app 內狀態是否更新
```

### 8.3 實用 ADB 指令

```bash
# 目前套件數（shell 可見的完整數量）
adb shell pm list packages | wc -l

# 自動可見的系統套件
adb shell dumpsys package queries | sed -n '/forceQueryable/,/^$/p'

# 應用程式列表權限（小米/OPPO/vivo 等）
adb shell dumpsys package com.networkcontrol.android.kotlin | grep -i -A8 'GET_INSTALLED_APPS\|GET_APP_LIST'

# AppOps 狀態（小米 MIUIOP(10022)、ColorOS GET_INSTALLED_APPS）
adb shell appops get com.networkcontrol.android.kotlin

# vivo 能力開關
adb shell settings get secure oem_installed_apps_runtime_permission_enable

# 套件可見性日誌（測試完記得關閉，會影響效能）
adb shell pm log-visibility --enable com.networkcontrol.android.kotlin
adb logcat -s AppsFilter:V MIUILOG:V ApplicationPackageManagerExtImpl:V

# 測試用授權（可能因 ROM 而失敗；只在自己的測試機上用）
adb shell pm grant com.networkcontrol.android.kotlin com.android.permission.GET_INSTALLED_APPS
adb shell appops set com.networkcontrol.android.kotlin GET_INSTALLED_APPS allow
```

> 不要對正式安裝的服務/應用做 `pm grant`、`appops set` 或 `force-stop`；測試請用 debug 包名（`.debug`）與獨立裝置。

### 8.4 通過標準

- 未授予權限時：詢問仍能顯示包名或 UID、目標、協議；不會完全消失。
- 授予權限後：`getInstalledPackages` 數量恢復到接近 `pm list packages`；Apps 頁可列出應用；按應用規則可匹配。
- 從設定返回：狀態會更新，不會卡在「受限」。
- 拒絕/稍後：只顯示被動提示，不會每次啟動都彈。
- shared UID：詢問與規則說明整組生效。
- 工作資料夾：不誤判為主使用者的「列表受限」。

---

## 9. 推測與不確定性

以下結論不是官方保證，需在目標 ROM 上實測：

| 項目 | 目前的說法 | 需要怎麼驗證 |
|---|---|---|
| `getPackagesForUid` 在 OEM 列表封鎖下是否仍回包名 | TAF 文件只列 `getInstalled*`，理論上不控 `getPackagesForUid`；AOSP 本身有 visibility 過濾 | 在 HyperOS/ColorOS/OriginOS/HarmonyOS/MagicOS 上，對已知 UID 呼叫並比對 `pm list packages` |
| `queryIntentActivities` 是否在列表封鎖下仍回 launcher 應用 | 有小米 13 / OPPO Find X8u Android 15 的實測日誌顯示仍可 | 每個 ROM/版本重測；確認是否回重複 activity |
| `getApplicationInfo("android", 0)` 是否能當 sentinel | SD Maid 用 `android` 套件做 sanity check；部分 ROM 可能不同 | 在正常與受限狀態各測一次 |
| 小米 `APP_PERM_EDITOR` 是否直達「取得應用程式列表」 | XXPermissions 使用；HyperOS 3/4 可能改版 | 在 HyperOS 1/2/3/4 實測落點 |
| 三星 `GET_APP_LIST` 能否用 `requestPermissions` | 部落格說理論上可以；XXPermissions 用 startActivity | One UI 5.1.1/6/7/8 各測一次 |
| 三星 AppOps 頁是否顯示「應用程式列表」 | XXPermissions 使用該頁 | 實測頁面內容與開關名稱 |
| 榮耀自動轉換 `QUERY_ALL_PACKAGES` | 使用者回報手機版會、平板不會 | MagicOS 7/8/9/10 手機與平板各測 |
| OPPO `permissionList`/`isGetPermission` extras | OPPO 官方優化文件與 XXPermissions 使用 | ColorOS 12/13/14/15 實測是否聚焦 |
| AppOps 名稱/編號 | MIUI `MIUIOP(10022)`；ColorOS `GET_INSTALLED_APPS` | 各 ROM `appops get` 實測；不要寫死 |
| `UsageStats` 是否能看到 PM 隱藏的包名 | 未證實；不同 ROM 可能不同 | 授予使用情況存取權後，對照 `getInstalledPackages` |
| 工作資料夾/私人空間的列表與 VPN 行為 | Android 文件說每個使用者有獨立 VPN；實際 ROM 可能有差異 | 建立工作資料夾/私人空間實測 |
| Android 16/17 是否改變 visibility | 本文以 Android 11–16 的文件為準 | 新版本上市後重讀文件與 CTS |

### 9.1 明確不存在的 API / 不要做的事

- ❌ `PackageManager.getNamesForUid(int)`：公開 SDK 沒有這個方法。公開的是 `getNameForUid(int)`；`getNamesForUids(int[])` 是 `@hide`/`@TestApi`。
- ❌ 用 reflection 呼叫 `IPackageManager`、`ServiceManager`、`AppOpsManager` 的私有 op：容易被 hidden API 限制擋下，也不利於上架。
- ❌ 假設 `checkSelfPermission(QUERY_ALL_PACKAGES)` 能反映 ROM 的應用列表開關；它永遠是 GRANTED。
- ❌ 假設 `getPackagesForUid` 一定能繞過可見性；AOSP 本身也會過濾。
- ❌ 用 `/proc/net/tcp` 或 `/proc/<pid>` 在非 root 裝置上做 UID → 套件對應；Android 10+ SELinux 已禁止。
- ❌ 在 `<queries>` 加 `MAIN/LAUNCHER` 來「查所有應用」；小米 HyperOS 文件明確禁止，且不是所有應用都有 launcher。
- ❌ 只把 UI 的「？」換成「未知應用」就算修好；真正的問題是權限與降級資料流。

---

## 10. 參考資料

> 以下連結查閱於 2026-11-15；廠商文件與 ROM 行為可能隨時變更。

| # | 資料 | 用途 | 可信度 |
|---|---|---|---|
| 1 | [Package visibility filtering on Android](https://developer.android.com/training/package-visibility) | Android 11+ 過濾規則 | ✅ 官方 |
| 2 | [Know which packages are visible automatically](https://developer.android.com/training/package-visibility/automatic) | 自動可見清單 | ✅ 官方 |
| 3 | [PackageManager API reference](https://developer.android.com/reference/android/content/pm/PackageManager) | API 行為與例外 | ✅ 官方 |
| 4 | [ConnectivityManager#getConnectionOwnerUid](https://developer.android.com/reference/android/net/ConnectivityManager#getConnectionOwnerUid(int,%20java.net.InetSocketAddress,%20java.net.InetSocketAddress)) | UID 歸屬 | ✅ 官方 |
| 5 | [Use of the broad package (App) visibility permission](https://support.google.com/googleplay/android-developer/answer/10158779) | QUERY_ALL_PACKAGES 政策 | ✅ 官方 |
| 6 | [小米：獲取應用列表權限的適配說明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1619) | MIUI 13+ 權限、申請方式、meta-data | ✅ 官方 |
| 7 | [小米：應用軟件列表信息獲取說明及適配指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1830) | HyperOS 新政策、`<queries>` 限制 | ✅ 官方 |
| 8 | [vivo：“讀取已安裝應用列表”權限三方適配說明](https://dev.vivo.com.cn/documentCenter/doc/747) | vivo 權限、`Settings.Secure`、app details | ✅ 官方 |
| 9 | [T/TAF 108-2022 標準頁](http://www.taf.org.cn/StdDetail.aspx?stdType=TAF&uid=3A7D6656-43B8-4C46-8871-E379A3EA1D48) | 國標背景與參與廠商 | ✅ 官方 |
| 10 | [XXPermissions Details-zh](https://github.com/getActivity/XXPermissions/blob/master/Details-zh.md) | 各廠商版本/權限對照、三星/MIUI 相容 | ⚠️ 第三方 |
| 11 | [XXPermissions `GetInstalledAppsPermission.java`](https://github.com/getActivity/XXPermissions/blob/master/library/src/main/java/com/hjq/permissions/permission/dangerous/GetInstalledAppsPermission.java) | 權限探測、OneUI/小米分支 | ⚠️ 第三方原始碼 |
| 12 | [XXPermissions `PermissionSettingPage.java`](https://github.com/getActivity/XXPermissions/blob/master/library/src/main/java/com/hjq/permissions/tools/PermissionSettingPage.java) | 小米/三星/OPPO 跳轉 | ⚠️ 第三方原始碼 |
| 13 | [SD Maid PR #2311](https://github.com/d4rken-org/sdmaid-se/pull/2311) | 中國 ROM 的 `GET_INSTALLED_APPS`、`android` sanity check | ⚠️ 第三方 |
| 14 | [手機廠商的应用列表权限管控真的起效了嗎](https://wesley666.github.io/article/e6389157/) | MIUI/ColorOS 實測日誌（size 1、launcher 155/279） | ⚠️ 單一實測 |
| 15 | [mihon #476](https://github.com/mihonapp/mihon/issues/476) | HyperOS 預設拒絕、海外/印度路徑差異 | ⚠️ 使用者回報 |
| 16 | [NekoBox #476](https://github.com/MatsuriDayo/NekoBoxForAndroid/issues/476) | 榮耀隱私存取記錄、三星 One UI 6 同問題 | ⚠️ 使用者回報 |
| 17 | [榮耀：應用權限找不到或無法開關](https://www.honor.com/cn/support/content/zh-cn15843433/) | 權限精簡顯示、查看所有權限 | ✅ 官方 |
| 18 | [華為：管理應用訪問權限](https://consumer.huawei.com/cn/support/content/zh-cn16070183/) | 設定路徑 | ✅ 官方 |
| 19 | [三星：管理應用程式通知和權限](https://www.samsung.com.cn/support/mobile-devices/how-to-set-the-notification-and-permission-of-management-application-on-samsung-mobile-phone/) | 應用程式資訊 → 權限 | ✅ 官方 |
| 20 | [AOSP `app.te`（Android 10）](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-10.0.0_r30/public/app.te) | 禁止 `/proc/net/tcp`、`/proc/net/udp` | ✅ 原始碼 |
| 21 | [AOSP `PackageManager.java`](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/content/pm/PackageManager.java) | `getNameForUid` 公開、`getNamesForUids` `@hide` | ✅ 原始碼 |
| 22 | [XXPermissions #175](https://github.com/getActivity/XXPermissions/issues/175) | `GET_INSTALLED_APPS` 請求與 AOSP 相容問題 | ⚠️ 討論串 |
| 23 | [LibChecker #1758](https://github.com/LibChecker/LibChecker/issues/1758) | 榮耀/華為必須宣告權限 | ⚠️ 使用者回報 |
| 24 | [GKD #1195](https://github.com/gkd-kit/gkd/issues/1195) | MagicOS 10 `DeadSystemException` | ⚠️ 使用者回報 |
| 25 | [三星應用列表權限（社群文章）](https://linux.do/t/topic/3022153) | `com.samsung.android.permission.GET_APP_LIST` | ⚠️ 低權威，需實測 |
| 26 | [AOSP `PackageVisibilityDetector.kt`](https://github.com/aospapp/aosp/blob/main/frameworks/base/tools/lint/framework/checks/src/main/java/com/google/android/lint/PackageVisibilityDetector.kt) | `getPackagesForUid` 也在 visibility 過濾的 sink 清單中 | ✅ 原始碼 |
| 27 | [OPPO 應用權限受阻跳轉優化](https://open.oppomobile.com/new/developmentDoc/info?id=12983) | `permissionList` / `isGetPermission` extras | ⚠️ 官方文件（頁面需登入/動態載入，未完整抓取） |

---

## 11. 給後續實作的優先級

| 優先級 | 工作 | 理由 |
|---|---|---|
| P0 | 清單加入 `GET_INSTALLED_APPS` / `GET_APP_LIST` | 沒有這個，其他都無法從根本解決 |
| P0 | `visibility()` 加入 ROM 權限與 `android` sentinel | 避免「QUERY_ALL_PACKAGES 已授予」的假陽性 |
| P0 | `BlockedFlowReporter` 不要丟棄無包名 flow；用 UID 降級 | 保證詢問至少能顯示目標與 UID |
| P0 | 用包名構造最小 `AppIdentity` | 讓既有按應用規則仍可匹配 |
| P1 | 詢問 UI 顯示 package/UID；停用無簽名的永久規則 | 避免誤導與安全風險 |
| P1 | launcher 索引作為 label/icon fallback | 大幅減少問號，且不需要新權限 |
| P1 | 各 ROM 的設定跳轉與文案 | 讓使用者真的能找到開關 |
| P1 | 生命週期重查（onResume/廣播/手動） | 從設定回來要立即生效 |
| P2 | 診斷包加入判定資料 | 真機問題需要可觀測性 |
| P2 | 實測矩陣與回歸腳本 | 避免 ROM 更新後回歸 |

---

## 12. 一句話總結

**先加 `com.android.permission.GET_INSTALLED_APPS`（三星加 `GET_APP_LIST`）並在正確時機申請；再用「ROM 權限 + `android` sentinel + 基線/launcher 交叉檢查」保守判定；最後用「路由包名 → `getPackagesForUid` → launcher 索引 → UID」降級顯示。** 這樣即使沒有權限，詢問也不會只剩問號，保護也不會退化成無法按應用控制。
