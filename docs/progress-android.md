# Android 版進度看板

> Android 版的進度單獨記錄在這裡，避免和桌面版的 progress.md 互相衝突。協作規則同 [AGENTS.md §2](../AGENTS.md#2-協作規則多-agent-並行)。
> 設計見 [android.md](android.md)，構建和工具鏈見 [development.md §11](development.md#11-android)。
> **Kotlin 對照版**（2026-11-14 起，長期）：獨立專案 `android-kotlin/`，功能和進度見 **[android-kotlin.md](android-kotlin.md)**；與本文件的 Avalonia 版並行維護，互不影響。

**最後更新：2026-11-14**

## M-A：Android MVP（2026-11-14 完成，已合併到 main）

設計見 [android.md](android.md)，構建見 [development.md §11](development.md#11-android)。方案：不需要 root 的**黑洞式分流 VPN**（允許的應用被排除在 VPN 外直接上網，其餘應用的流量進入虛擬網卡後丟棄並觸發詢問）+ 假 DNS；界面為 Avalonia 12 + AtomUI；核心（Core / Storage）原樣在同一進程中運行。

- [x] (claude, 2026-11-14) **A-0 設計與工具鏈**：`docs/android.md`；用戶級工具鏈 `D:\tools\android-dev`（.NET SDK 10.0.402 + android 工作負載 36.1、JDK 17、Android SDK 36、模擬器）；`scripts/android.ps1`；`NetworkControl.Android.slnx`。
- [x] (claude, 2026-11-14) **A-1 預研 R-A1**：AtomUI 可以在 Android 上運行，結論見 android.md §11（Segmented 觸控、中文字體、安全區域三個坑已處理）。
- [x] (claude, 2026-11-14) **A-2 Core**：`RuleSet.FindAppRule`；Android 上路徑匹配區分大小寫。
- [x] (claude, 2026-11-14) **A-3 Engine.Vpn**（net10.0，在主解決方案中）：報文解析、路由規劃、阻斷上報（去重、系統 UID 過濾）、假 DNS（DnsWire / FakeDnsPool / IpPacketBuilder）、`VpnBlackholeEngine`。55 個單元 / 場景測試。
- [x] (claude, 2026-11-14) **A-4 Engine.Android**：`NcVpnService`、`AndroidVpnTunnel`（poll 讀寫線程）、PackageManager 應用目錄（簽名 SHA-256、軟件包廣播）、`getConnectionOwnerUid`、保護開關。
- [x] (claude, 2026-11-14) **A-5 App.Android 核心宿主**：`BackendHost`（Generic Host，進程級單例）、`IBackend` 門面、logcat + 文件日誌。
- [x] (claude, 2026-11-14) **A-6 界面**：總覽、應用程式（搜索 / 篩選 / 三態設定 / 圖標）、阻擋記錄、設定（語言、外觀、詢問超時和默認動作、通知、永久連線 VPN、電池最佳化、版本和引擎計數）。
- [x] (claude, 2026-11-14) **A-7 詢問**：應用內底部卡片（防誤觸 1 秒、前台時推遲超時、「稍後」按應用擱置）+ 系統通知（允許 / 拒絕按鈕）。
- [x] (claude, 2026-11-14) **A-8 開機恢復、始終開啟的 VPN、撤銷處理**。
- [x] (claude, 2026-11-14) **A-9 Release APK**：`scripts/android.ps1 publish` → `artifacts/android/com.networkcontrol.android-Signed.apk`（45.8 MB，arm64-v8a + x86_64，裁剪；**目前用調試簽名**，正式分發前需要用戶生成自己的密鑰庫並設置 `NC_ANDROID_KEYSTORE` 等環境變量）。已在模擬器上從 Debug 版原地升級並驗證：設定、規則、保護狀態保留，裁剪後界面和完整詢問流程正常；冷啟動到首幀約 7.5 秒（模擬器軟件渲染，真機待測）。

**模擬器上已驗證（Android 15 / API 35，x86_64，emulator 33.1.24）**：核心在 Android 上啟動（SQLite 遷移、規則、引擎）；應用目錄（61 個聯網應用）；保護開關 → VPN 授權 → 虛擬網卡建立；Chrome 被阻斷 → 假 DNS → 按 UID 歸屬 → 詢問顯示「Chrome 想要連線網路 · example.com:443」並合併多個連線；應用內「允許（1 小時）」→ 限時規則 → 300 ms 內重建 VPN，`dumpsys connectivity` 確認 Chrome 的 UID 10145 已不在 VPN 範圍內；拒絕 → 永久拒絕規則；應用程式頁搜尋和三態設定；阻擋記錄頁（顯示域名、原因、次數）；全部封鎖 / 恢復、詢問 / 黑名單模式切換（分流隨之重建）；通知上的「拒絕」按鈕；重新啟動後無界面自動恢復保護；繁中 / 簡中 / 英文即時切換；淺色 / 深色。

**用戶真機確認（2026-11-14）**：用戶在自己的手機上安裝 Release APK 試用，確認功能正常，MVP 驗收。

**未驗證 / 已知限制**：
- 真機上的具體覆蓋範圍以用戶反饋為準；廠商 ROM（MIUI / HyperOS 等）的後台限制尚未專門測試。
- 「始終開啟的 VPN」由系統啟動服務的路徑、VPN 被其他應用撤銷的處理：只有單元測試，沒有在設備上觸發過。
- 通知文本跟隨系統語言，不跟隨應用內選擇的語言（`Resources/values*/strings.xml`）。
- 只能按應用控制；按域名 / IP / 端口的規則在 Android 上不生效（界面會提示數量）；允許的應用沒有日誌和流量統計（設計上的取捨，見 android.md §1.2）。
- 被阻斷應用的 TCP SYN 沒有回覆 RST，應用要等到超時才報錯（P1）。
- 模擬器 37.x 在本機 WHPX 下無法啟動，見 AGENTS.md 第 6 節。

## 待辦（按優先級）

> 2026-11-14 與用戶討論後排定的順序：先解決真實用戶第一天就會遇到的問題（P0），再做手機上最有價值、與現有架構契合的控制能力（P1），依賴「使用情況存取權」的功能打包一起做（P2），進階方向單獨評估（P3）。

**M3 任務對應**：M3-05 = 阻斷快速失敗；M3-10 = 通知語言；M3-09 = 快速設定磁貼；M3-11 = 診斷導出；M3-06 = 規則導入導出（與下方「備份與恢復」一起做）；M3-03 + M3-04 = 配置文件與按網絡自動切換（含 Wi-Fi / 行動網路分別允許）。詳細設計見 [m3-tasks.md](m3-tasks.md)，長期執行計劃見 [m3-plan.md](m3-plan.md)；本表的優先級高於 M3 編號順序。

### M3（與桌面版同步，見 progress.md「第二階段」）
- [x] (claude, 2026-11-14) **流量統計**：`AndroidUidTrafficSource`（NetworkStatsManager，需要「使用情況存取權」PACKAGE_USAGE_STATS，清單已聲明）+ `UidTrafficCollector`（每 10 分鐘、打開頁面時立即採集；只上報增量，狀態存 `files/data/uid-traffic.json`）。總覽新增「今日用量」卡片（下載 / 上傳、前三名應用；未授權時顯示授權按鈕），應用列表每行顯示今日用量，設定頁新增「數據用量」卡片（授權狀態 + 打開設定）。`IBackend` 新增 `HasTrafficAccess` / `CreateTrafficAccessIntent` / `GetTrafficAsync`。
  - 驗證：Debug APK 構建通過，`dotnet format` 通過；`UidTrafficCollector` 的單元測試。**未在設備上運行過**（本機無設備、模擬器在本機無法啟動）：授權跳轉、NetworkStatsManager 返回的分桶形態、各廠商 ROM 的差異都需要真機確認。

### P0 首次體驗與兼容性（真實用戶第一天就會遇到）
- [ ] **首次使用引導**：解釋原理（本機 VPN、流量不離開手機）、選擇初始模式、引導 VPN 授權和通知權限。
- [ ] **「系統應用默認允許」選項**（與桌面版「按可信發布者批量允許」共用 Core 實現，見 [design.md §24](design.md#24-減輕首次使用的詢問負擔可信發布者規則與詢問批量處理規劃2026-11-14)）：開啟時為系統應用生成允許規則，只對用戶自己安裝的應用詢問。模擬器上一打開保護，Google Play 服務、Google、Gboard、遠端佈建等系統組件就接連彈出詢問。
- [ ] **詢問隊列批量處理**（Core 部分與桌面版共用，見 [design.md §24](design.md#24-減輕首次使用的詢問負擔可信發布者規則與詢問批量處理規劃2026-11-14)）：查看全部待決詢問，支持「全部允許 / 全部稍後」。
- [ ] **私人 DNS（Private DNS）兼容性**（風險見 [android.md §14](android.md#14-風險)）：真機實測系統「私人 DNS」設為指定主機時被阻斷應用能否觸發詢問；偵測到嚴格模式時在總覽頁提示並說明處理方法。
- [ ] 被阻斷應用的 TCP SYN 回覆 RST、UDP 回覆 ICMP 不可達，讓應用快速失敗而不是等到超時。→ **M3-05**
- [ ] 通知文本跟隨應用內選擇的語言（AppCompatDelegate.setApplicationLocales）。→ **M3-10**
- [ ] 廠商 ROM 實測與保活引導（MIUI / HyperOS 自啟動、後台限制、電池最佳化白名單）。

### P1 手機上最有價值的控制能力
- [ ] **與其他 VPN 友好共處**（設計見 [android.md §3.3](android.md#33-與其他-vpn梯子公司-vpn共存2026-11-14)）：被其他 VPN（梯子、公司 VPN）取代時，通知說明「已被 XX 取代，防火牆暫停」；偵測到其他 VPN 斷開後通知「點此恢復保護」；說明中介紹工作資料夾方案。
- [ ] **轉發模式預研**（[android.md §3.3](android.md#33-與其他-vpn梯子公司-vpn共存2026-11-14)）：用戶態 TCP/IP 協議棧選型（性能、耗電、許可證、與 .NET 的互操作）、判決點的插入方式、按域名轉發與 DNS 協調、UDP 轉發；在真機上與 FlClash（僅代理模式）實際搭配驗證。產出選型結論和實施計劃。
- [ ] **專注模式**（設計見 [android.md §7.5](android.md#75-專注模式與時間表規劃) 和 [design.md §23](design.md#23-情境時間表與專注模式規劃2026-11-14)）：一鍵進入、選時長和允許的應用、到期自動恢復；提前結束需要確認 + 倒計時；專注期間不彈詢問，結束後給出小結。依賴 Core 的「情境」能力（與桌面版共用，見 progress.md）。
- [ ] **時間表**：按星期和時段自動進入 / 退出情境；本應用恢復運行時按當前時間校正。
- [ ] **按網絡類型（Wi-Fi / 行動網路）分別允許**：網絡變化時按當前網絡類型重新規劃分流；規則模型追加「網絡類型」條件（桌面版以後也可使用，對應需求 FR-MOD-03）。→ **M3-03 + M3-04**
- [ ] **鎖屏時禁止聯網**：全局開關，也可以按應用設定；亮屏、鎖屏時各重新規劃一次分流。
- [ ] **新應用安裝提醒**：監聽到安裝廣播時通知「XX 已安裝，是否允許聯網？」，在應用首次打開前就讓用戶決定。
- [ ] **「允許一次」第一步**（設計見 [android.md §6.4](android.md#64-允許一次設計未實現)）：允許到鎖屏或 10 分鐘後失效（以先到者為準），不需要額外權限。Core 追加會話綁定（不改現有接口），引擎上報會話標識，鎖屏監聽和計時，詢問卡片和通知增加「允許一次」；附帶測試。
- [ ] **懸浮詢問面板**（2026-11-14 討論確定，參考紅魔系統「掌心管家已禁止 XX 使用 WLAN 網絡，是否允許？」的底部彈框）：用戶正在使用的應用聯網被攔時，直接在當前畫面底部彈出詢問面板，不再只靠幾秒就收起的懸浮通知。仍然是 VPN 黑洞模式，只改詢問的呈現方式；Core 的詢問合併、超時、規則生成都不用改。
  - **權限**：清單聲明 `SYSTEM_ALERT_WINDOW`；用 `Settings.canDrawOverlays()` 檢查，用 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`（帶 `package:` URI）跳到系統設定授權。判斷前台應用需要「使用情況存取權」（`PACKAGE_USAGE_STATS`，流量統計已在用）。授權引導放在第一次出現詢問時，或在設定裡選擇「自動」時，不在開啟保護時一次索要所有權限。小米、紅魔等 ROM 可能還有「後台彈出界面」等額外權限，要在說明中提到並真機實測。
  - **呈現器拆分**：把 `PromptNotifier`（目前同時負責訂閱 `PromptBroker` 和發通知）拆成「詢問路由器 + 多個呈現器」：
    - 路由器：唯一的 `PromptBroker` 訂閱者，沿用 `PromptSubscriber(UserId: null, IsAdmin: true)`；
    - 通知呈現器：現有的通知邏輯；
    - 懸浮面板呈現器：新增；
    - 應用內卡片：界面已有。
  - **選擇規則**（每次詢問新增或更新時判斷；寫成不依賴 Android 的純函數，附單元測試）：
    1. 本應用界面在前台（現有的 `IsUiForeground`）→ 應用內卡片；
    2. 詢問方式為「自動」+ 有懸浮窗權限 + 有使用情況存取權 + 屏幕亮著且已解鎖（`PowerManager.isInteractive`、`KeyguardManager.isKeyguardLocked`）+ 被攔的應用就是當前前台應用 → 懸浮面板。前台應用用 `UsageStatsManager.queryEvents` 中最近的 `ACTIVITY_RESUMED` 判斷，與詢問應用的包名比對；共享 UID 時，UID 下任一軟件包在前台都算；
    3. 其他情況（沒有權限、後台應用聯網、鎖屏、選了「只用通知」）→ 懸浮通知（現有行為）。
    即使有權限，後台應用聯網也只發通知，避免後台同步頻繁打斷用戶。
  - **不和通知衝突**：每個詢問**始終保留一條通知**作為可以找回的記錄，只是渠道不同：
    - 由應用內卡片或懸浮面板顯示時，通知放在靜默渠道，不懸浮（與現有的 `IsUiForeground` 處理方式相同）；
    - 只有通知時，用「聯網詢問」渠道懸浮顯示。
    所有通道都用詢問 ID 回答（`AnswerPromptAsync`），先到的生效；`Removed` 事件同時關掉通知和懸浮面板；後到的回答（詢問已不存在）靜默忽略，不能報錯。
  - **懸浮面板**：
    - 窗口：`WindowManager.AddView`，`TYPE_APPLICATION_OVERLAY`，底部對齊，寬度鋪滿、高度自適應，`FLAG_NOT_TOUCH_MODAL`（面板以外的觸摸照常傳給下面的應用），不加全屏遮罩。用原生 Android View 繪製（Avalonia 依賴 Activity，不適合放進懸浮窗），佈局和文本放在 `Resources/`（values / values-night，三種語言的 `strings.xml`），在主線程（`Handler(Looper.MainLooper)`）增刪。
    - 內容：應用圖標（`AppIconLoader`）+「已暫時阻止 XX 聯網，是否允許？」+ 目標（域名:端口）+「允許後應用重新連線即可上網」。黑洞方案只能按應用分流，**不寫「WLAN 網絡」**。篡改提示（`IsTamperAlert`）用警告樣式和對應文案。
    - 按鈕：「禁止」「允許」，以及勾選框「禁止後不再提示」。勾選 = 寫入永久拒絕規則；不勾選 = 這次不放行，按應用擱置 10 分鐘（與應用內卡片的「稍後」相同）。「允許一次」任務完成後增加第三個按鈕「僅一次」。按鈕出現後 1 秒內不可點擊（FR-PRM-07）。
    - 返回鍵、面板上的關閉按鈕 = 「稍後」。
  - **隊列與超時**：
    - 同一時間只顯示一個面板，其餘排隊。前一個關閉後，對下一個詢問**重新做選擇**：被攔的應用可能已經不在前台，這時把它的通知改回懸浮渠道。
    - 面板顯示期間每 10 秒調用 `HoldPrompt` 推遲超時，與應用內卡片相同，避免 §6.2 中「超時後下一個詢問頂替到同一位置、點錯應用」的問題。
    - 詢問合併了新連線（Updated）時，原地更新面板內容，不重新彈出。
  - **失敗時退回通知**：
    - `AddView` 拋異常（權限剛被收回、BadToken）→ 記日誌，把通知改回懸浮渠道；
    - 被攔的應用離開前台 → 收起面板，詢問退回通知；
    - 系統隱藏第三方懸浮窗時（Android 12 起，例如權限對話框上），因為通知始終存在，詢問不會丟。能不能偵測到被隱藏、並及時把通知改回懸浮，需要實測。
  - **設定**：「詢問方式：自動（默認）/ 只用通知」，旁邊顯示懸浮窗和使用情況存取權的授權狀態，並提供授權按鈕。存放位置與現有的通知相關設定一致。
  - **驗證**：
    - 選擇規則的單元測試：純函數放在主解決方案中有測試的 net10.0 項目裡；
    - 設備驗證：用 `adb shell appops set <包名> SYSTEM_ALERT_WINDOW allow` 和 `GET_USAGE_STATS allow` 授權；在瀏覽器裡觸發詢問，確認面板出現且通知在靜默渠道；在面板上回答後通知消失，在通知上回答後面板消失；讓應用在後台聯網，確認只發通知；鎖屏時確認只發通知；用 `dumpsys window` 檢查懸浮窗口。界面效果用設備截屏（`adb exec-out screencap`），不截電腦桌面；
    - 完成後在 [android.md §6](android.md#6-詢問交互) 補一節「懸浮詢問面板」，在 §14 的風險中補充廠商 ROM 的權限差異。
- [ ] **詢問內容的顯示設定**（2026-11-14 用戶提出）：設定頁的通知卡片中可以配置詢問通知（以及懸浮詢問面板）顯示哪些內容。目前通知標題「XX 想要聯網」下面固定顯示兩行：
  - 連線詳情：目標「域名 / IP:端口 協議」和合併的連線數，例如「stsdk.vivo.com.cn:443 TCP，共 2 個連接」；
  - 說明文字：「此連接已被暫時阻止，允許後應用重新連接即可上網。」
  - **定位**：紅魔系統自帶的提示只有「已禁止 XX 使用網絡」，不顯示詳情；我們借助假 DNS 反查出的域名（`FlowInfo.EngineHostname`），能告訴用戶應用想連哪裡（例如 Smart Launcher 連的是 vivo 的統計服務），這是我們的優勢。所以做成**可配置**：想看細節的用戶保留詳情，再決定是否放行；只想快速點選的用戶關掉詳情，和系統提示一樣簡潔。
  - **設定項**：兩個開關「顯示連線詳情」「顯示說明文字」，都默認開啟（默認展示優勢）。兩個都關閉時只顯示標題和「拒絕 / 允許」按鈕。
  - **簡潔模式不丟信息**：通知上不顯示的內容，仍然記錄在連線記錄中，以後在 P3 的「應用詳情頁」中可以查到該應用連過的域名、次數、時間；設定項的說明文字中提示這一點。
  - **實現**：`PromptNotifier.Show`（拆分後為通知呈現器）按設定組裝 `SetContentText` 和 `BigTextStyle`；懸浮詢問面板共用同一份設定。設定改變後刷新已顯示的詢問通知。設定存放位置與現有的通知相關設定一致；設定頁文本由 `tools/android-strings.py` 生成，三種語言都要維護。
  - **可選擴展**：鎖屏時隱藏連線詳情。給通知設置 `SetPublicVersion`，鎖屏上只顯示「XX 想要聯網」，不暴露訪問的域名；可以做成第三個開關「鎖屏時隱藏詳情」。
  - **驗證**：在模擬器上切換開關後觸發詢問，用 `dumpsys notification --noredact` 檢查通知的文本字段。
- [ ] 真機實測：應用被放回 VPN 時，已建立的長連接是否被系統斷開（決定「允許一次」界面說明的寫法）。
- [ ] 正式簽名：用戶生成密鑰庫，`NC_ANDROID_KEYSTORE` 等環境變量；版本發布流程寫入 development.md。**密鑰的保管和備份**要一併定下來：密鑰丟失後就再也發不了能覆蓋安裝的更新，用戶只能卸載重裝、規則全部歸零。
- [ ] **更新渠道**：側載 APK 的「檢查更新」（只檢查版本號，與桌面版 P1 同一思路），或通過 GitHub Releases / F-Droid 分發；如果以後上架 Google Play，提前了解 VPN 應用的專門聲明和「查詢所有應用」權限的用途說明。
- [ ] **真機性能基準**：冷啟動時間、內存、全天耗電（VPN 類應用最常見的差評原因）；真機只打 arm64-v8a 的 APK（大小約減半，目前雙 ABI 為 45.8 MB）。
- [ ] **回歸冒煙測試腳本**：把 2026-11-14 手工完成的設備驗證整理成 adb 腳本（安裝、`appops` 授權、開保護、觸發 Chrome、檢查 `dumpsys connectivity` 的 VPN 範圍、讀日誌），每次改動後可以快速跑一遍。
- [ ] **確認連線記錄的清理在 Android 上生效**（保留天數設定），並在設定頁顯示數據佔用：被阻斷的應用重試頻繁，數據庫增長比桌面快。
- [ ] **應用換簽名密鑰時被誤報為篡改**：目前只比對當前的簽名證書；改為參考簽名歷史（`SigningInfo.getSigningCertificateHistory`），新證書在歷史鏈上時不算篡改。
- [ ] **純 IPv6 行動網路實測**：假 DNS 目前只回答 IPv4（A 記錄）；部分運營商是純 IPv6（經 464XLAT 訪問 IPv4），確認詢問機制在這種網絡下仍能觸發。
- [ ] **備份與恢復**（設計見 [android.md §8.1](android.md#81-備份與恢復診斷導出規劃)）：設定頁「資料」卡片導出 / 導入規則和設定，用系統文件選擇器保存到下載目錄或雲盤（不需要存儲權限）；導入前預覽（共幾條、幾條對應的應用尚未安裝、幾條簽名不同）並選擇合併或覆蓋；只導出永久規則和設定，不導出臨時規則和連線記錄。復用 Storage 的 netcontrol-rules 格式、序列化器和導入計劃（`RuleImportPlanner`，桌面版已在用）。**價值**：本應用關閉了 Android 自動備份，卸載重裝、換手機、恢復出廠設置都會讓規則歸零；Storage 的每日自動備份在應用私有目錄中，卸載時一起被刪除。定位是手機之間的備份與恢復，**不宣傳與桌面版互通**（桌面規則綁定可執行文件路徑、手機規則綁定包名，兩邊基本匹配不上）。→ **M3-06**（第一階段只做規則導入導出）
- [ ] **診斷導出**（設計見 [android.md §8.1](android.md#81-備份與恢復診斷導出規劃)）：設定頁「資料」卡片一鍵導出 zip，經系統分享面板發送或保存為文件。內容：日誌、當前路由計劃（含此平台不生效的規則）、引擎計數器（報文、聯網嘗試、假 DNS 回答、查不到 UID、系統 UID）、VPN 狀態、**私人 DNS 狀態**、通知和電池最佳化授權狀態、設備型號 / Android 版本 / ROM、Storage 收集器提供的數據庫一致性副本；文本統一脫敏。復用桌面版的診斷包機制（`IDiagnosticsContributor`、`DiagnosticsArchive`、`DiagnosticsRedactor`），Android 補一個自己的收集器。**工程前置**：打包和脫敏代碼目前在 `NetworkControl.Ipc`，Android 不使用 gRPC，要先把它們移到不依賴 gRPC 的共享位置（只移動、不改行為）。→ **M3-11**

- [~] (claude, 2026-11-14) **配置文件（M3-03）**：`IBackend` 追加 `Profiles` / `ActiveProfile` / `ActivateProfileAsync` / `CreateProfileAsync` / `UpdateProfileAsync` / `DeleteProfileAsync` / `GetRuleCount`；總覽頁模式卡片上方配置文件下拉（只有一個時隱藏）；設定頁新增配置文件卡片（列表、設為目前、新增 / 改名 / 刪除、模式選擇）；文本由 `tools/android-strings.py` 生成。Debug APK 構建通過，**未在設備上運行**；切換後 VPN 分流重建需真機確認。

### 對標第一梯隊補充的待辦（2026-11-14，對照 NetGuard、RethinkDNS、TrackerControl、PCAPdroid、AFWall+）
只列原有待辦中沒有的部分；性能與體積見下一節。每項開頭標註建議的優先級。

**可靠性與安全兜底**
- [ ] **【P0】故障時默認斷網（lockdown）與開機空窗期**：我們的進程崩潰或被系統殺掉時 VPN 隨之消失，所有被攔的應用立刻恢復聯網，用戶也不知道；開機後到服務啟動前也有一段空窗期。領先者的做法是引導用戶開啟系統的「始終開啟的 VPN」和「**阻止未使用 VPN 的連線**」。實現：用 `Settings.Secure` 中的 `always_on_vpn_app` / `always_on_vpn_lockdown`（讀取方式以真機為準，讀不到時改為引導用戶自行確認）檢測這兩項；首次使用引導和設定頁顯示狀態，沒開就持續提示並提供跳轉（`Settings.ACTION_VPN_SETTINGS`）。真機驗證：開啟 lockdown 後強制停止本應用（`adb shell am force-stop`），確認被攔的應用無法聯網，且保護能自動恢復；未開啟時在總覽頁提示風險。
- [ ] **【P1】共享 UID 的應用**：多個包共用一個 UID 時（例如 `android.uid.system`、廠商套件），對其中一個設置規則實際作用於整組，目前界面沒有說明。應用列表把同一 UID 的包合併為一組顯示，標註「這些應用共用網絡權限，規則對整組生效」；詢問中也顯示同組的其他應用。
- [ ] **【P1】合併 VPN 重建**：每次改規則都重建 VPN，批量操作（全部允許、切換配置文件、導入規則）時會反覆閃斷。規則變化後延遲一小段時間（例如 300 ms）再重建，期間的變化合併為一次；用 P-1 的指標記錄重建次數和耗時。
- [ ] **【P2】私人空間（Android 15）**：私人空間和工作資料夾一樣有獨立的 VPN 名額，其中的應用不受本應用控制。檢測到私人空間時在說明中提示（與「工作資料夾（多用戶）支持」一起考慮）。

**黑洞模式下就能做的控制能力**
- [ ] **【P1】允許局域網**：VPN 路由是 `0.0.0.0/0`，被攔的應用連局域網設備（投屏、NAS、打印機）也會被攔。全局開關「允許局域網」：開啟時 VPN 路由排除私有地址段（`10/8`、`172.16/12`、`192.168/16`、`169.254/16`、IPv6 的 `fc00::/7`、`fe80::/10`），用 `VpnService.Builder.excludeRoute`（Android 13+）；更低版本改為把 `0.0.0.0/0` 拆成不含私有地址段的若干路由。以後可以按應用設置。附帶路由計算的單元測試。
- [ ] **【P2】漫遊時禁止聯網**：按應用設置「漫遊時禁止」，並入「按網絡類型分別允許」的規則條件；雙卡手機要能區分 SIM 卡。

**依賴轉發模式的能力**（轉發模式見「中期主線」；建議把轉發模式定為進入第一梯隊的必要條件，排在專注模式、時間表之前，**需要用戶確認排序**）
- [ ] **DNS 防火牆與攔截列表訂閱**：所有 DNS 查詢經過本應用，按訂閱列表（廣告、追蹤、惡意網站）攔截，支持按應用例外和手動添加域名；顯示每個應用被攔的域名。
- [ ] **加密 DNS 上游**：轉發模式下可選 DoH / DoT 上游，與 DNS 防火牆一起做。
- [ ] **追蹤器識別**（擴展 P3 的「應用詳情頁」）：接入開源的追蹤器數據庫（例如 Exodus Privacy、Disconnect 的列表，注意許可證），把域名標記為公司和類別（統計、廣告）；總覽頁顯示「今天攔了 N 個追蹤器」。黑洞模式下對被攔的應用（假 DNS 能拿到域名）就能先做。

**平台合規**（上架和新系統的硬性要求）
- [ ] **【P1】16 KB 內存頁**：Android 15 起有設備使用 16 KB 頁，Google Play 要求原生庫按 16 KB 對齊。確認 .NET 運行時、SkiaSharp、HarfBuzzSharp、e_sqlite3 的版本是否支持，在 16 KB 頁的模擬器鏡像上驗證能否啟動。
- [ ] **【P1】targetSdk 升級檢查**：targetSdk 35+ 強制全屏顯示（edge-to-edge），升級後重新驗證安全區域；Android 14 起前台服務必須聲明類型，確認 VPN 服務和通知的聲明符合要求。
- [ ] **【P2】Google Play 的 VPN 應用聲明**：如果上架，需要填寫 VpnService 的專門聲明，說明「流量不離開設備」，並提供隱私政策頁面（與「AAB 打包與上架準備」一起做）。

**信任與生態**
- [ ] **【需要用戶決定】開源、可復現構建、F-Droid**：防火牆類應用拿到全部網絡權限，用戶最擔心應用本身作惡；第一梯隊幾乎都開源並上架 F-Droid。需要用戶決定開源策略。
- [ ] **【P1】零遙測聲明**：設定頁和說明中寫明「不收集任何數據；除檢查更新（可以關閉）外不聯網」；崩潰信息只存本地，由用戶通過診斷導出主動提供。
- [ ] **【P2】從其他防火牆導入規則**：支持導入 NetGuard、AFWall+ 的導出文件，降低遷移成本（擴展「備份與恢復」的導入流程，復用 `RuleImportPlanner`）。
- [ ] **【P2】自動化接口**：帶權限保護的廣播 Intent（開關保護、允許 / 拒絕某應用、切換配置文件），供 Tasker、MacroDroid 使用；與快速設定磁貼（M3-09）一起做。
- [ ] **【P3】更多語言**：接入社區翻譯平台（例如 Weblate），界面文本和 `strings.xml` 都要覆蓋。

### P1 性能與體積（背景、測量數據和方案比較見 [android-performance.md](android-performance.md)）
現狀（2026-11-14）：APK 45.9 MB（arm64 + x86_64 各約 22 MB），模擬器冷啟動約 7.5 秒。順序：先做 P-1（測量），每完成一項都把前後數據記入 android-performance.md §6。

- [ ] **P-1 性能指標日誌與測量方法**（其他各項的前提，配合上面的「真機性能基準」；設計和開銷分析見 [android-performance.md §7](android-performance.md#7-性能指標日誌)）：
  - **啟動階段**：用 `Process.getStartElapsedRealtime()` 取得進程真正的啟動時刻（包括我們的代碼運行之前、運行時初始化的時間），之後在這些時間點記錄耗時：`Application.OnCreate` 開始、Avalonia 初始化完成、AtomUI 主題加載完成、`MainActivity.OnCreate`、首幀、`BackendHost` 的各階段（打開 SQLite、遷移、加載規則、應用目錄掃描、引擎啟動）、VPN 建立。啟動結束時輸出一行彙總，註明啟動路徑（點擊圖標 / 開機恢復 / 系統拉起 VPN 服務）。
  - **運行時**：VPN 重建耗時、規則發布耗時、詢問延遲（攔截到報文 → 通知或彈窗顯示）、應用目錄掃描耗時和應用數、頁面切換到渲染完成的耗時；超過閾值的操作記一條「慢操作」警告（例如界面線程上超過 100 ms）。
  - **週期性指標**：每 10 分鐘（以及進入後台時）輸出一行：報文數、詢問數、託管內存（`GC.GetTotalMemory`）、GC 次數。PSS 用 `Debug.getMemoryInfo`，開銷較大，只在診斷導出時採集一次。
  - **格式與輸出**：單獨的日誌分類 `NetworkControl.Perf`，每條一行 `key=value`，方便腳本解析；寫入 logcat 和現有的文件日誌，**診斷導出時一併打包**。在 `scripts/` 中提供提取腳本，把 logcat 或日誌文件中的指標整理成表格。
  - **系統跟蹤**：同樣的階段用 `Android.OS.Trace.BeginSection` / `EndSection` 標記，在 Perfetto 中可以看到與系統事件對齊的時間線。只標記粗粒度的階段，不標記每個報文。
  - **開銷控制**：啟動和運行時的里程碑默認開啟（每次只是讀時鐘加一條日誌）；詳細計時（例如每次重建 VPN 的分段耗時）由設定中的「詳細性能日誌」開關控制，默認關閉。報文處理等熱路徑**只累加計數器**，不逐條記日誌。日誌一律用 `[LoggerMessage]` 源生成，並先判斷日誌級別。
  - **測量方法**寫入 development.md §11：`adb shell am start -W -S` 的 `TotalTime`、Perfetto 系統跟蹤、`dumpsys meminfo` 的 PSS；分別測點擊圖標、開機自動恢復、進程被殺後系統重新拉起 VPN 服務三條路徑。
  - **驗證**：用 Release 構建分別測量開啟和關閉指標日誌時的冷啟動時間，確認差異在測量誤差以內，結果記入 android-performance.md §6。
- [ ] **P-2 只發布 arm64**（收益最大，成本最低）：`scripts/android.ps1 publish` 默認只打 `android-arm64`，APK 約減半（45.9 MB → 約 24 MB）；`android-x64` 只用於模擬器調試（Debug 構建或單獨的參數）。上架 Google Play 時改用 AAB，由商店按 ABI 拆分。
- [ ] **P-3 完整裁剪，剔除桌面專用依賴**：確認 Release 的 `TrimMode`，如果是 `partial` 就改為 `full`，並處理裁剪警告。驗收標準是 `obj/Release/net10.0-android/android-arm64/linked` 中不再有 `NWayland`、`Avalonia.Wayland` 等 Linux 桌面程序集（目前共 0.66 MB，經 `AtomUI.Desktop.Controls` → `Avalonia.Desktop` 引入）。完整裁剪後反射相關的問題只會在運行時暴露，必須在設備上跑完整的冒煙測試（詢問、規則、設定、配置文件、語言和主題切換）；需要時用 `TrimmerRootDescriptor` 保留。剔除不掉時，評估用 `ExcludeAssets` 排除傳遞依賴，或向 AtomUI 上游反饋。
- [ ] **P-4 查明並縮小其他大塊依賴**：
  - `System.Private.Xml`（1.54 MB）：查出引用來源。來自運行時解析 XLIFF 的話，評估改為構建時生成；來自 SVG 庫的話，記入 android-performance.md §3。
  - `AtomUI.Icons.AntDesign`（1.24 MB，實際只用了幾十個圖標）：確認完整裁剪後能否只保留用到的圖標；不能的話，評估改用 Android 矢量資源或 Avalonia 的 `PathGeometry`。
  - 功能開關：`EventSourceSupport=false`、`MetricsSupport=false` 等；`UseSystemResourceKeys=true` 會讓異常消息變成資源鍵、影響日誌可讀性，權衡後決定。
- [ ] **P-5 Java / Kotlin 部分**：開啟 R8（`<AndroidLinkTool>r8</AndroidLinkTool>`）壓縮 `classes.dex`（現在解壓後 7.83 MB），補充 ProGuard 保留規則，避免 Avalonia.Android 和 AndroidX 用到的類被誤刪；用 `AndroidPackagingOptionsExclude` 排除 Kotlin 元數據目錄（`commonMain`、`nativeMain`、`kotlin` 等，約 0.2 MB）。開啟後在設備上跑完整的冒煙測試。
- [ ] **P-6 自定義 AOT 配置文件**：現在用的是 .NET 自帶的通用 Profiled AOT 配置文件（每個 ABI 有 153 個 `libaot-*.so`，壓縮後 3.82 MB），不覆蓋本應用的啟動路徑。在真機上錄製本應用的啟動配置文件（`.aprof`，錄製工具和命令以 .NET 10 官方文檔為準），加入項目後重新測量；同時測一次「不用 AOT」作為對比，最後根據大小和啟動時間的權衡選定方案。錄製步驟寫入 development.md §11，啟動路徑有大的變化時重新錄製。
- [ ] **P-7 後台路徑不初始化界面**（需要預研）：`AndroidApp` 繼承自 `AvaloniaAndroidApplication`，Avalonia 和 AtomUI 很可能在 `Application.OnCreate` 時就初始化，所以開機廣播、系統拉起 VPN 服務、進程被殺後重啟這些沒有界面的路徑也要付出界面初始化的成本，而這決定了**保護恢復前的空窗期**有多長。先用 P-1 的埋點確認；再預研 Avalonia 12 能否把初始化延遲到 `MainActivity` 創建時（例如改用普通的 `Application`，在 Activity 中初始化 Avalonia）。做不到的話，把「VPN 放到獨立進程」的方案（android-performance.md §4 方案 C）交給用戶決定。
- [ ] **P-8 冷啟動優化**（依據 P-1 的數據決定先做哪些）：
  - 啟動畫面：使用 Android 12 的 SplashScreen API，點擊圖標後立即顯示；
  - 推遲非關鍵工作：流量統計採集、應用圖標加載推遲到首幀之後；
  - 應用目錄改為增量：不在啟動時全量讀取所有應用並計算簽名 SHA-256，按 `lastUpdateTime` 只處理有變化的應用，結果緩存到本地；
  - 並行：`BackendHost`（SQLite、遷移、規則加載、VPN）與界面初始化並行，界面先顯示骨架，數據到了再填充；
  - 測量 AtomUI 主題初始化（`UseDesktopControls()` 為所有控件註冊主題）的耗時；佔比大的話，評估只註冊用到的控件（取決於 AtomUI 是否支持，不支持就向上游反饋）。
- [ ] **P-9 防止回退**：CI 的 Android 任務輸出 APK 大小及各部分組成（託管程序集、AOT、原生庫、dex），超過基準 5% 時發出警告；冒煙測試腳本記錄冷啟動時間，與 android-performance.md §6 的最新數據比較。
- [ ] **P-10 根據真機數據決定是否做架構級調整**（**需要用戶決定**）：P-1 到 P-8 完成後，如果 APK（arm64）仍大於 20 MB 或冷啟動仍超過 2 秒，在 android-performance.md §4 的方案 B（Android 界面不用 AtomUI）和方案 D（原生界面 + .NET 核心）之間選擇；同時持續關注 .NET Android 上的 Native AOT 進展（方案 E）。
- [ ] **P-11 移動端 UI 庫對比實驗**（2026-11-14 用戶確認要做；P-10 決定的依據；背景見 [android-performance.md §4](android-performance.md#4-更進一步的方案架構級) 方案 B 和 [§8](android-performance.md#8-ui-庫對比atomui--fluent--materialavalonia)）：
  - **目的**：用數據回答「Android 界面繼續用 AtomUI，還是換成更輕的方案」。Android 只用了 11 種 AtomUI 控件（`Button`、`Card`、`Segmented`、`IconPresenter`、`Alert`、`Tag`、`ToggleSwitch`、`Statistic`、`ComboBox`、`Spin`、`LineEdit`），卻要承擔 AtomUI 及其依賴約 14 MB 的託管代碼，以及啟動時為所有控件生成主題的成本；而且 `FluentTheme` 本來就在加載（基礎控件需要），現在是兩套主題並存。
  - **做法**：在 `tools/ui-bench/`（或 scratchpad，不進主解決方案）建三個最小的 Avalonia Android 應用，內容相同（一個頁面：卡片 ×3、分段選擇、按鈕、開關、輸入框、50 行的列表、幾個圖標、中文文本），Avalonia 版本與正式項目一致：
    1. **AtomUI**：與正式項目相同的初始化方式（`UseDesktopControls()`、`sans-serif` 字體）；
    2. **Fluent + 自訂樣式**：`Card`、`Tag`、`Alert`、`Statistic` 用 `Border` 加樣式，`Segmented` 用橫向 `ListBox` 或 `RadioButton`，`Spin` 用不確定進度的 `ProgressBar`，圖標用 `PathGeometry`；
    3. **Material.Avalonia**（社區的 Material Design 主題，符合 Android 的使用習慣）。
  - **指標**：都用 Release + 只打 arm64 + 默認 AOT 配置構建，比較 APK 大小、裁剪後託管代碼大小、冷啟動（`am start -W -S` 的 `TotalTime`，每個測 10 次取中位數）、首幀時間、PSS 內存、列表滾動是否流暢（Perfetto 的掉幀數）。模擬器和真機各測一輪，以真機為準。
  - **同時評估遷移成本**：界面文本要從 AtomUI 的 XLIFF 目錄遷移到別的機制（例如直接用 Android 的 `strings.xml`，通知文本本來就在用）；`AppearanceService` 的明暗切換建立在 AtomUI 的主題管理之上，需要重寫；`TouchAdapters` 和字體的特殊處理可能不再需要；觸控目標按 Android 建議不小於 48dp。
  - **產出**：結果和遷移工作量估計記入 android-performance.md §8，給出建議。**是否遷移由用戶決定**；如果遷移，另開任務，桌面版不受影響。

### P2 依賴「使用情況存取權」的功能（打包一起做，一次完成授權引導）
- [ ] 使用情況存取權（`PACKAGE_USAGE_STATS`）的授權引導，設定頁顯示授權狀態。
- [ ] **「允許一次」第二步**：離開應用 1 分鐘後失效（有前台服務時不算離開）；設定頁顯示當前生效的方式。
- [ ] **只在前台時允許聯網**：按應用設定，應用切到後台（超過寬限期、沒有前台服務）就斷網，阻擋後台的數據上報和推送；與「允許一次」共用前後台判斷。
- [ ] **每個應用的流量統計**：用 `NetworkStatsManager` 按應用顯示 Wi-Fi / 行動網路用量，允許的應用（不經過 VPN）也能統計。

### 中期主線：轉發模式 + 上游代理（預研後排期）
- [ ] **轉發模式**（[android.md §3.3](android.md#33-與其他-vpn梯子公司-vpn共存2026-11-14)）：新引擎實現，與黑洞模式並存（黑洞為默認的「省電模式」，轉發為可選的「進階模式」）；允許的流量轉發到上游 SOCKS5 / HTTP 代理（配合 FlClash、v2rayNG、sing-box 等的僅代理模式），按域名轉發、支持 UDP、自動排除上游代理應用、上游不可用時明確提示。同時帶來：真正掛起連接等待判決、按域名 / IP / 端口的規則、允許連接的日誌和流量統計、黑名單訂閱。

### P3 進階方向與其他
- [ ] **應用詳情頁**：展示被阻斷應用嘗試連接的域名、次數、時間（來自假 DNS），便於識別廣告和追蹤服務。
- [ ] 快速設定磁貼（開關保護、一鍵斷網）、桌面小工具。→ **M3-09**（磁貼）
- [ ] 應用鎖（密碼 / 指紋），防止他人借用手機時修改規則（FR-SET-06）。**不做家長管控 / 兒童模式**（需求 §1.3，2026-11-14 確定）。
- [ ] 工作資料夾（多用戶）支持。
- [ ] **無障礙與不同屏幕**：平板、橫屏、系統大字體、讀屏（TalkBack）。目前只驗證過豎屏手機，AtomUI 作為桌面組件庫在這些方面可能有問題。
- [ ] 內置 WireGuard 客戶端（可選，[android.md §3.3](android.md#33-與其他-vpn梯子公司-vpn共存2026-11-14) 方案 C）：覆蓋只能以 VPN 方式連接的公司網絡。
- [ ] AAB 打包與上架準備。
