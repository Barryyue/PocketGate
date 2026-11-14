# Android 版進度看板

> Android 版的進度單獨記錄在這裡，避免和桌面版的 progress.md 互相衝突。協作規則同 [AGENTS.md §2](../AGENTS.md#2-協作規則多-agent-並行)。
> 設計見 [android.md](android.md)，構建和工具鏈見 [development.md §11](development.md#11-android)。

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

### P1
- [ ] 被阻斷應用的 TCP SYN 回覆 RST、UDP 回覆 ICMP 不可達，讓應用快速失敗而不是等到超時
- [ ] 按網絡類型（Wi-Fi / 行動網路）分別允許，網絡變化時重建分流
- [ ] 快速設定磁貼（開關保護、一鍵斷網）
- [ ] 通知文本跟隨應用內選擇的語言（AppCompatDelegate.setApplicationLocales）
- [ ] 規則導入導出（復用 Storage 的 netcontrol-rules 格式），與桌面版互通
- [ ] 正式簽名：用戶生成密鑰庫，`NC_ANDROID_KEYSTORE` 等環境變量；版本發布流程寫入 development.md
- [ ] 診斷資訊導出（日誌 + 路由計劃 + 計數器）
- [ ] 廠商 ROM 實測（MIUI / HyperOS 後台限制、電池最佳化）

### M3（與桌面版同步，見 progress.md「第二階段」）
- [x] (claude, 2026-11-14) **流量統計**：`AndroidUidTrafficSource`（NetworkStatsManager，需要「使用情況存取權」PACKAGE_USAGE_STATS，清單已聲明）+ `UidTrafficCollector`（每 10 分鐘、打開頁面時立即採集；只上報增量，狀態存 `files/data/uid-traffic.json`）。總覽新增「今日用量」卡片（下載 / 上傳、前三名應用；未授權時顯示授權按鈕），應用列表每行顯示今日用量，設定頁新增「數據用量」卡片（授權狀態 + 打開設定）。`IBackend` 新增 `HasTrafficAccess` / `CreateTrafficAccessIntent` / `GetTrafficAsync`。
  - 驗證：Debug APK 構建通過，`dotnet format` 通過；`UidTrafficCollector` 的單元測試。**未在設備上運行過**（本機無設備、模擬器在本機無法啟動）：授權跳轉、NetworkStatsManager 返回的分桶形態、各廠商 ROM 的差異都需要真機確認。

### P2
- [ ] 轉發模式（用戶態 TCP/IP 協議棧）：按域名 / IP / 端口控制、允許連接的日誌和流量統計、真正掛起等待判決
- [ ] 桌面小工具、工作資料夾（多用戶）
- [ ] AAB 打包與上架準備
