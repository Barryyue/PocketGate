# 進度看板

> 每完成一項任務、或發現新問題時更新本文件（同時更新「最後更新」）。新會話從這裡了解當前狀態。
> 需求見 [requirements.md](requirements.md)，設計見 [design.md](design.md)（§22 記錄了實現與原設計的差異），開發方法見 [development.md](development.md)。

**最後更新：2026-11-12**
**當前階段：M1（Windows MVP）—— 可以發給朋友試用：Native AOT 發布包 + 安裝程序 + 診斷包已完成；安裝版已在開發機上以 SYSTEM 服務跑通**

---

## 🔴 當前阻塞 / 下一步

0. **用戶驗證發布包（2026-11-12 晚）**——`0.2.0` 發布包已從提交 `8141f6f` 生成並核對（程序版本 `0.2.0+8141f6f`，Native AOT；zip 同時支援綠色版和安裝版）：`artifacts\release\NetworkControl-0.2.0-win-x64-setup.exe`（27.9 MB）和 `.zip`（39.7 MB）。已用解壓後的 AOT zip 驗證：界面識別為 `Portable`、使用 `pipe:NetworkControl.portable.v1`、日誌寫入 `<根目錄>\data\logs`、命令行診斷包可正常導出。以下需要管理員權限或需要看界面，由用戶執行：
   - 可攜版：解壓到可寫入的資料夾，雙擊 `start.cmd`，確認 UAC、WFP 攔截、退出界面後服務停止（見第 4 項）。
   - 安裝程序 `artifacts\release\NetworkControl-0.2.0-win-x64-setup.exe`：安裝 / 從 zip 安裝版升級 / 卸載（是否刪除數據的詢問）各走一遍。**尚未運行過**（只驗證了編譯和版本信息；安裝程序不會複製 `portable.txt` / `start.cmd`）。
   - 設定頁「診斷」卡片和服務不可用頁上的「匯出診斷包」按鈕（保存對話框、完成提示、在資源管理器中定位）。**界面效果未驗證**；導出邏輯已通過命令行模式驗證。
   - 安裝版的 DNS 域名規則：TraceEvent 在 AOT 下有分析警告（已評估為不經過我們的調用路徑），需要建一條域名規則確認生效；若無效，診斷包 `service/engine/wfp-state.txt` 中的「DNS (ETW) monitor」和服務日誌會給出線索。
   - 用戶 17:06 用 zip 包安裝後的實際運行：WFP 啟動、7 個彈窗回應、規則重啟後加載 —— **已從服務日誌確認正常**。
1. **用戶確認上一輪修復（2026-11-12 下午）**
   - 規則頁、日誌頁表格現在應該能顯示內容（原因：AtomUI DataGrid 需要 `IDataGridSource`，之前綁定的是普通集合，運行時靜默失敗）。
   - WFP 引擎下不再出現「Service is unimplemented」（原因：切換引擎後模擬器卡片沒有隱藏）。
   - 之前生成的 tipray 兩條允許規則（`ldapprovalex.exe`、`ldtermdaemon.exe`）應該能下發了：`FwpmGetAppIdFromFileName0` 被安全軟件拒絕訪問，現在改用兜底的應用 ID。驗證方法：在日誌中搜索「無法下發」，應該不再出現。
   - 服務停止時的崩潰（同一實例被 Dispose 兩次）已修復。
2. **WFP 驗收結果**：已確認 WFP 引擎啟動、下發 12 個內置過濾器、阻斷事件觸發彈窗、允許後生成規則。尚待確認：允許後應用重試能否連上、域名規則（ETW DNS）是否生效。
3. 托盤右鍵菜單：加回 FluentTheme 後**尚未經用戶確認**（若仍不顯示，改用 Win32 原生托盤菜單）。
4. **可攜版（2026-11-12 新增）**：`start.cmd` 首次啟動的 UAC 提權、真實 WFP 攔截、退出界面後服務停止需要用戶驗證；另需回歸一次安裝版的安裝 / 升級 / 卸載（安裝程序不會複製 `portable.txt`）。

---

## ✅ 已完成

### 文檔
- [x] 需求規格說明書、設計實現文檔、開發指南、README

### M0 骨架（全部完成）
- [x] Core：領域模型、規則引擎（優先級、篡改檢測）、判決管線（階段鏈）、詢問代理（合併 / 排隊 / 超時 / 路由）、規則生命週期、DNS 映射、指標
- [x] Storage：SQLite（config.db / activity.db）、內嵌 SQL 遷移、倉儲、日誌按分鐘聚合寫入
- [x] Ipc：5 個 proto 服務（Control / Prompts / Rules / ActivityLog / Simulator）、端點解析、客戶端工廠（已禁用系統代理）
- [x] Service：Kestrel 只聽命名管道 / UDS、LocalPeer 認證 + Admin/Viewer 策略、內存 DataProtection、Shutdown 接口
- [x] Engine.Fake：7 個虛擬應用，可模擬退出 / 篡改
- [x] 測試：Core（含 US-01/02/03/07 場景）、Storage

### UI（AtomUI 遷移，全部完成）
- [x] 評估並採用 AtomUI 6.2.13（Ant Design 6，LGPL-3.0，NuGet 二進制引用）
- [x] 多語言：zh-CN / zh-TW（台灣用語）/ en-US，AtomUI XLIFF Catalog，可運行時切換，默認跟隨系統
- [x] 主題：跟隨系統 / 淺色 / 深色（AppearanceService 自管跟隨系統）
- [x] 頁面：總覽（模式、斷網、勿擾、引擎切換、模擬器）、規則（搜索 / 篩選 / 新建編輯對話框 / 刪除確認 / 啟停）、連線記錄（篩選 / 實時 / 從記錄建規則）、設定（語言、主題、登錄啟動、服務設置）
- [x] 詢問彈窗、白名單阻斷通知（右下角 Toast）、服務不可用診斷頁
- [x] 托盤：打開各頁面、模式子菜單、一鍵斷網 / 恢復、勿擾、結束介面、完全結束
- [x] 開發體驗：調試構建只啟動界面即可自動拉起開發服務；主窗口底部「結束」按鈕

### M1 Windows 引擎（代碼完成）
- [x] WFP 互操作（結構體 / GUID / 常量逐一對照 SDK 10.0.26100 頭文件，佈局由單元測試斷言）
- [x] RuleCompiler（FWP_UINT64 精確權重、區域補集、通配路徑展開、域名 → IP）
- [x] WfpSession（動態會話、事務差異下發）、WfpEngine（阻斷事件 → 詢問、去重、啟動失敗完整清理）
- [x] ETW DNS-Client 監聽、Windows 進程信息 / Authenticode 校驗 / 進程退出監聽、設備路徑轉換
- [x] 引擎配置 Auto / Fake / Wfp；安裝 / 卸載腳本（scripts/）
- [x] Engine.Windows.Tests（佈局、編譯器、IP 補集、DNS 解析、路徑轉換）

### 發布與診斷（2026-11-12）
- [x] **Native AOT 發布**：`scripts/publish-release.ps1` 生成 zip 發布包（約 40 MB）和 Inno Setup 安裝程序（約 28 MB）；調試符號單獨存放。運行時版本在 AOT 發布時固定為 10.0.15（避開 SDK 自帶 10.0.13 的 Brotli 漏洞），已確認產物中運行時為 10.0.15。
  - 實測：AOT 服務 + 模擬引擎跑通完整鏈路（彈窗 → 回應 → 規則入庫 → 診斷包 → 正常退出）；AOT 服務在無管理員權限時 WFP 啟動失敗但服務存活、故障進入診斷包；用戶安裝的 AOT 版以 SYSTEM 運行 WFP 正常。
  - 修復：Serilog 改為代碼配置（原配置方式在 AOT 下啟動即崩潰）；**安裝版原來不寫日誌文件**，現在默認寫到 `%ProgramData%\NetworkControl\logs`。
  - App / Service 日常構建啟用 AOT 分析器；第三方 AOT 警告白名單（Serilog、TraceEvent），其他新增警告讓發布失敗。
- [x] **版本號**：只在 `Directory.Build.props` 的 `VersionPrefix` 維護；發布包、安裝程序文件名和版本信息、程序內版本（帶提交哈希，未提交時標記 `-dirty`）都從這裡取。安裝程序 `AppId` 固定，支持原地升級。
- [x] **正式版不含模擬引擎**：Release 構建不引用 `Engine.Fake`、不映射模擬器接口（`IncludeFakeEngine`）。
- [x] **安裝**：`install-service.ps1` 支持從發布包安裝（不需要 SDK），安裝後以普通權限啟動界面並設置登錄時啟動（「詢問」模式下沒有界面會斷網）、創建開始菜單快捷方式；發布包帶 `install.cmd` / `uninstall.cmd` / `export-diagnostics.cmd`（自動提權）和繁體中文 README。
- [x] **診斷包**（見 development.md §9）：`IDiagnosticsContributor` 擴展點；服務端（概況、配置、日誌、數據庫一致性副本、有效規則集、WFP 過濾器清單和下發失敗記錄）+ 界面端（界面概況和日誌、`sc` 服務狀態、事件日誌崩潰記錄）；服務不可達時直接讀取服務日誌；文本脫敏；入口為設定頁、服務不可用頁、命令行 `--export-diagnostics`。
  - 實測：命令行模式在服務在線 / 離線兩種情況下導出正確（約 6 秒）；脫敏、GBK 事件日誌解碼、讀取正被寫入的日誌均已驗證。
- [x] **界面日誌**：`%LocalAppData%\NetworkControl\logs\app-*.log`，包括未處理異常、連接變化、Avalonia 綁定警告。
- [x] 連接日誌寫入器停止時總是寫完隊列（之前只在 ExecuteAsync 的取消分支中寫，存在丟失已入隊事件的可能）。觸發這個修改的 `Identical_connections_in_same_minute_are_aggregated` 偶發失敗**未能復現**（單獨 60 次、並行 180 次均通過），根因未確認。

### 可攜版（綠色版，2026-11-12）
- [x] **免安裝運行**：發布包根目錄的 `portable.txt` 作為可攜標識；界面直接拉起同目錄的 `Service\NetworkControl.Service.exe`（Windows 上通過 UAC 提權），數據、日誌和界面偏好放在 `<根目錄>\data`，IPC 使用獨立管道 `NetworkControl.portable.v1`，不會連接或關閉已安裝的系統服務。
  - 安裝程序不複製標識文件，原有安裝 / 升級 / 卸載流程不變；可攜版不提供登錄自啟，退出界面時停止可攜版服務。
  - 驗證：`PortableLayout` 單元測試；Debug 佈局 + Fake 引擎端到端已驗證（服務從 `Service` 啟動、數據寫入 `data`、端點為 `pipe:NetworkControl.portable.v1`、界面診斷包中 `Launcher=Portable` 且成功連上服務）；AOT zip 的離線和在線診斷包均已驗證（`Native AOT=True`、`Launcher=Portable`；非管理員啟動時 WFP 按預期故障、服務存活且界面可連接）。
  - 待用戶驗證：可攜版首次啟動的 UAC 提權和真實 WFP 攔截（需要管理員權限）；從 zip 安裝 / 升級 / 卸載的回歸。

**測試現狀：157 個全部通過；Debug / Release 構建零警告；`dotnet format --verify-no-changes` 通過。**

---

## 📱 Android MVP（M-A，分支 `agent/claude/android-mvp`，2026-11-14）

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

**未驗證 / 已知限制**：
- **允許後應用是否真的能上網**：開發機的網絡需要經過代理，模擬器沒有可用的外網（宿主直連 example.com 也超時），只驗證了應用的 UID 已被排除在 VPN 之外。需要用戶在真機上確認。
- **真機**（尤其 MIUI / HyperOS 的後台限制、VPN 授權對話框、通知權限對話框）：模擬器上用 `appops` / `pm grant` 跳過了兩個授權對話框，對話框本身的流程未在設備上走過。
- 「始終開啟的 VPN」由系統啟動服務的路徑、VPN 被其他應用撤銷的處理：只有單元測試，沒有在設備上觸發過。
- 通知文本跟隨系統語言，不跟隨應用內選擇的語言（`Resources/values*/strings.xml`）。
- 只能按應用控制；按域名 / IP / 端口的規則在 Android 上不生效（界面會提示數量）；允許的應用沒有日誌和流量統計（設計上的取捨，見 android.md §1.2）。
- 被阻斷應用的 TCP SYN 沒有回覆 RST，應用要等到超時才報錯（P1）。
- 模擬器 37.x 在本機 WHPX 下無法啟動，見 AGENTS.md 第 6 節。

---

## 🤝 可分派任務（適合交給其他 agent 獨立完成）

> 認領規則、分支和完成標準見 [AGENTS.md §2](../AGENTS.md#2-協作規則多-agent-並行)。認領時把 `[ ]` 改為 `[~] (agent, 日期)`。
> 這些任務邊界清楚，不需要管理員權限，也不涉及核心設計決策。

- [ ] **A1 config.db 每日備份**（FR-SET-04，小）
  - 範圍：`src/NetworkControl.Storage/Backup/`（新建）、`StorageServiceCollectionExtensions.cs`、`tests/NetworkControl.Storage.Tests/`
  - 做法：用 `BackgroundService` 每天一次，通過 `SqliteConnection.BackupDatabase` 把 config.db 備份到 `<數據目錄>/backups/config-yyyyMMdd.db`，保留最近 7 份；時間通過 `TimeProvider` 注入。
  - 驗收：用 `FakeTimeProvider` 推進時間的測試，覆蓋生成備份、超過 7 份時刪除最舊的、備份可以打開並讀到規則。
  - 不要改：Core、proto、App。
- [ ] **A2 規則導出格式與序列化**（FR-SET-03 第一步，中）
  - 範圍：`src/NetworkControl.Storage/Serialization/`（新增導出 DTO 和序列化器）、對應測試
  - 做法：格式為 `{ "format": "netcontrol-rules", "version": 1, "rules": [...] }`，使用 System.Text.Json 源生成。路徑中的用戶目錄替換為 `%USERPROFILE%` 等佔位符，導入時展開。複用現有的 `DtoMapper`。
  - 驗收：導出再導入的往返測試；佔位符替換測試；格式版本不符時報錯。
  - 不要改：IPC 接口和界面（下一步由主 agent 接入）。
- [ ] **A3 譯文審校**（小）
  - 範圍：只改 `src/NetworkControl.App/Localization/**/zh-CN.xlf`、`zh-TW.xlf` 中的 `<target>`。
  - 要求：zh-TW 使用台灣用語，zh-CN 使用大陸用語；不改 unit id、`<source>` 和 `{0}` 等佔位符；保持 `state="translated"`。
  - 驗收：構建通過（構建會校驗佔位符和完整性），並在提交說明中列出改動。
- [ ] **A4 Windows 引擎測試補充**（小）
  - 範圍：只改 `tests/NetworkControl.Engine.Windows.Tests/`
  - 內容：`PathExpander` 用臨時目錄測試 `*` 和 `**`、上限、不存在的根目錄；`RuleCompiler` 測混合 IPv4/IPv6 網段的拆分、ICMP 與 ICMPv6 的協議轉換、本地網絡區域。
  - 驗收：新測試全部通過；不需要管理員權限。
- [ ] **A5 CI 測試報告**（小）
  - 範圍：只改 `.github/workflows/ci.yml`
  - 內容：測試輸出 TRX 報告，並作為構建產物上傳（三個平台各一份）；失敗時同樣上傳。
  - 驗收：工作流語法正確（可以用 `actionlint` 檢查），不改變現有的構建步驟。

---

## 📋 待辦（按優先級）

### P0 收尾（M1 驗收前）
- [ ] WFP 端到端驗收（見上方阻塞項）
- [ ] 托盤菜單用戶確認
- [ ] WFP 管理員集成測試（xunit，非管理員時 Skip）：開會話、加過濾器、TcpClient 驗證、清理
- [ ] 首次運行引導（NFR-UX-01）：解釋彈窗機制、選擇初始模式
- [ ] 單實例：第二次啟動時喚起已有實例的主窗口（目前直接退出）

### P1
- [ ] 入站連接控制（ALE_AUTH_RECV_ACCEPT 層）
- [ ] 流量統計（ETW Kernel-Network → traffic_* 表 → 統計頁圖表）
- [ ] 實時連接視圖（GetExtendedTcpTable）
- [ ] 配置文件 Profiles + 按網絡自動切換
- [ ] 規則導入導出、每日備份
- [ ] svchost 服務名細分、目錄簽名（catalog）校驗
- [ ] 勿擾期間阻斷彙總通知、全屏自動勿擾
- [ ] 權限分級落地（TreatAllClientsAsAdmin=false 時的界面提示）
- [ ] 代碼簽名（安裝程序和可執行文件；目前會出現 SmartScreen 提示）
- [ ] 安裝程序繁體中文界面（Inno Setup 沒有官方繁體語言文件，目前提供簡體中文和英文）
- [ ] 檢查更新（目前由用戶手動下載新版安裝程序覆蓋安裝）

### P2 / 後續里程碑
- [ ] M2 Linux 引擎（nftables NFQUEUE + eBPF + NFLOG）
- [~] (claude, 2026-11-14) Android 版（M-A）—— 見上方「Android MVP」一節
- [ ] M4 macOS 引擎（NE 系統擴展，Swift）—— 需提前申請 Apple 開發者帳號和 NE 權限
- [ ] 限速、黑名單訂閱、時間段規則、地圖 / ASN、密碼保護、ncctl
- [ ] Windows WFP Callout 驅動（掛起連接，需 EV 證書）

---

## ⚠️ 已知限制（M1 Windows，設計上的取捨）

見 [development.md §7](development.md#7-windows-wfp-引擎)：先阻斷後詢問、只控制出站、只記錄阻斷、svchost 服務 / MSIX 規則不下發、只校驗嵌入式簽名、DoH 下域名規則無效。

## 📝 待決問題（需要用戶決定）

- 開源協議（MIT / Apache-2.0 / GPL-3.0）—— 影響能否復用 GPL 項目代碼
- 正式產品名稱和圖標
- zh-TW 用語：目前用台灣用語（網路、應用程式、設定），如需「繁體字 + 大陸用語」只需改 zh-TW.xlf
- 遠端倉庫：本地已 `git init`（分支 main），尚未配置遠端
- `site/` 目錄：用戶已自行提交（`2b0e59b`、`64bba9e`），工作區中有一批 `site/` 臨時文件的刪除尚未提交，由用戶處理。注意 `2b0e59b` 中同時帶入了本輪診斷功能的前一半代碼。
