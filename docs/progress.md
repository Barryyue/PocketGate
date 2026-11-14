# 進度看板

> 每完成一項任務、或發現新問題時更新本文件（同時更新「最後更新」）。新會話從這裡了解當前狀態。
> 需求見 [requirements.md](requirements.md)，設計見 [design.md](design.md)（§22 記錄了實現與原設計的差異），開發方法見 [development.md](development.md)。

**最後更新：2026-11-14**
**當前階段：M1（Windows MVP）已完成並可試用；M3（v1.0，桌面 + Android）推進中。M3 的長期執行計劃見 [m3-plan.md](m3-plan.md)。**

---

## 🔴 當前阻塞 / 下一步

**0.3.0 試用包（2026-11-14，分支 `agent/claude/m3-traffic` 提交 `faddb94`）**，等待用戶驗證：
- Windows：`artifacts\release\NetworkControl-0.3.0-win-x64-setup.exe`（28.2 MB）、`NetworkControl-0.3.0-win-x64.zip`（40.2 MB，可攜 / 安裝兩用）。Native AOT，只有白名單內的 AOT 警告。已用 AOT 服務（非管理員，WFP 按預期故障）驗證：統計、流量、應用、規則導出 / 導入 / 備份恢復接口在 AOT 下正常。
- Android：`artifacts\android\com.networkcontrol.android-Signed.apk`（45.9 MB，versionCode 300，**調試簽名**，可以從 0.2.x 原地升級），清單包含 `PACKAGE_USAGE_STATS`。未在設備上運行過。
- 驗證重點：總覽儀表板和網路用量、應用頁、規則匯入 / 匯出與還原、首次引導、再次雙擊喚起視窗；Android 今日用量（需要在設定中允許「使用情況存取權」）。

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

**合併 main（Android 版）後（2026-11-14）：Windows 解決方案 292 個測試，291 通過、1 個跳過；Android Debug APK 用用戶級工具鏈（`D:\tools\android-dev`，`scripts/android.ps1 build`）構建通過（0 錯誤；3 個 XA0033 是工具鏈解析 JDK 版本號的既有警告），**未在設備上回歸**。本輪改動了 Core（`IActivityQuery` 新增兩個方法、判決後補充解析應用身份）和 Storage（遷移 `Activity/0002`、備份 / 導入導出），均為追加式修改。**

**測試現狀（2026-11-14）：362 個，361 通過、1 個跳過（Linux /proc 符號鏈接測試，Windows 非開發者模式不能建立符號鏈接）；完整解決方案構建零警告零錯誤；`dotnet format --verify-no-changes` 通過；Android Debug 構建通過（3 個既有 XA0033 警告），未在設備上回歸。Release / AOT 發布包本輪未重新生成。**

---

## 🚀 長期規劃：對標一流產品（2026-11-13 起，claude 自主推進）

目標：功能完整度和界面質感對標 Little Snitch / Portmaster / GlassWire。按「用戶每天都會看到的價值」排序，逐項完成並在此記錄；每項都要求構建、測試、格式檢查通過，並通過 gRPC 探針做運行時驗證（不截屏）。**界面視覺效果需要用戶親自確認。**

- [x] **L1 總覽儀表板**（2026-11-13）
  - 服務：`IActivityQuery.SummarizeAsync`（按分鐘聚合表分桶統計：時間軸、允許 / 拒絕總數、活躍應用數、目標數、應用 / 目標排行榜）；IPC `ActivityLog.GetSummary`（只新增消息和字段）。
  - 界面：總覽頁重做——標題 + 時間範圍（24 小時 / 7 天 / 30 天，按本地整點 / 整日對齊）；四張指標卡（防護模式 + 引擎、已允許、已拒絕 + 拒絕率、活躍應用 + 待回應連線）；自繪堆疊柱狀圖 `Controls/ActivityChart`（懸停高亮 + 提示、坐標軸取整、顏色全部用 AtomUI 令牌，深淺色自動適配）；「最常連線的應用 / 目標」排行榜（佔比條，點擊跳轉到已篩選的連線記錄）；快速操作卡（模式、斷網、勿擾、生效規則數）。頁面可見時每 30 秒刷新。
  - 基礎設施：`NavigationService`（頁面間帶參數跳轉，避免頁面依賴主窗口）；`PageViewModel.OnDeactivated` / `ApplyNavigation`。
  - **修復既有缺陷**：連線記錄的聚合鍵不含應用路徑，未識別的應用（app_id = -1）連接同一目標時被合併記到第一個程序名下。新增遷移 `Activity/0002_log_aggregation_by_path.sql`（唯一索引加入 `ifnull(app_path, '')`），已在舊數據庫上驗證升級。
  - 驗證：存儲層統計測試（分桶、排行、空範圍補零）；隔離管道 + 模擬引擎探針：14 次模擬連接後 `GetSummary` 返回的總數、排行、非法範圍（InvalidArgument）均正確。**界面效果未經人眼確認。**
- [x] **L2 應用頁**（2026-11-13）：新導航項「應用程式」（總覽與規則之間）。
  - 服務：`IActivityQuery.ListAppActivityAsync`（按應用聚合，最近連線倒序）；新 proto `apps.proto` / `Apps.List`：合併 config.db 中的應用信息（顯示名、簽名者、平台程序）和當前規則集中**路徑模式匹配**該應用的已啟用規則數（允許 / 拒絕分開計，全局規則不計）。
  - 界面：`AppsView`——標題 + 時間範圍 + 刷新；搜索（名稱、路徑、簽名者）；表格列：應用（名稱 + 路徑）、簽章標籤（已簽名 / 系統 / 未簽署 / 未識別）、允許、拒絕（非零標紅）、規則摘要、最近連線（相對時間）、操作（允許 / 拒絕：打開預填好的規則編輯器確認後提交；記錄 / 規則：帶關鍵字跳轉）。
  - 驗證：存儲測試；探針——`Apps.List` 返回 6 個應用，新增通配規則 `D:\Games\SomeGame\*.exe` 後該應用的拒絕規則數變為 1。**界面效果未經人眼確認。**
  - 已知：模擬引擎在「停用」模式下不解析應用身份（`DisabledModeStage` 在身份解析之前短路），這些連線顯示為未識別的應用。
- [x] **L3 規則備份與移轉**（2026-11-14，FR-SET-03 / 04）
  - 服務：`Rules.Export` / `Import`（`preview_only` 預覽）/ `ListBackups` / `RestoreBackup`。路徑佔位符由界面按**當前用戶目錄**傳入（`path_roots`）。`RuleImportPlanner`（純函數）計算新增 / 更新 / 刪除 / 不變：合併只增改；覆蓋另外刪除文件中沒有的可導出規則（內置、臨時規則不動）；內容比較用導出格式指紋（`AddressTarget` 的列表按引用比較，不能用記錄相等）。執行順序先增改後刪除，出錯時少刪不多刪。
  - 恢復 = 用備份中的規則覆蓋：備份先複製到臨時目錄並遷移到當前結構再讀取（不改動備份）；恢復前自動建立 `config-restore-yyyyMMdd-HHmmss.db` 快照（保留 3 份），可以用它撤銷；只接受本程序生成的備份文件名（防路徑穿越）；恢復保留規則原來源，從文件導入的新規則標記為「導入」。
  - 界面：設定頁新增「規則備份與移轉」卡片——匯出（保存對話框，提示未導出的內置 / 臨時規則數）、匯入（打開文件 → 預覽對話框可切換合併 / 覆寫並顯示各自的增改刪數量，有刪除時用警告樣式 → 確認執行）、自動備份下拉 + 還原（預覽差異確認後執行）。
  - 驗證：計劃器、備份列表 / 讀取 / 快照保留 / 非法名稱的單元測試；探針——導出 1 條（路徑替換為 `%USERPROFILE%`）、原樣重新導入預覽為「不變 1」、空文件覆蓋預覽「刪除 1」、錯誤格式返回 InvalidArgument、從前一天的備份恢復刪除 1 條並生成快照、用快照撤銷恢復 1 條、`..\config.db` 返回 NotFound。**界面效果未經人眼確認。**
- [x] **L4 首次運行引導與單實例喚起**（2026-11-14）
  - 單實例：第一個實例在本地命名管道 `NetworkControl.App.<用戶名>.<Installed|Portable>.activate`（`CurrentUserOnly`）上監聽；第二次啟動時發送「顯示主窗口」後退出（`--minimized` 開機自啟不喚起）。管道只接受這一個請求。
  - 首次運行引導（NFR-UX-01）：首次打開主窗口時（等待服務連接最多 8 秒）彈出對話框——三個要點（彈窗詢問、總覽 / 應用頁、托盤）、初始模式（詢問（推薦）/ 白名單 / 黑名單，附說明）、登錄時啟動開關；確定後應用模式。看過的版本記在界面偏好 `OnboardingVersion`，內容大改時遞增 `OnboardingFlow.CurrentVersion` 讓老用戶再看一次。**升級的老用戶也會看到一次**。
  - 驗證：以隔離管道啟動 `--minimized` 的 Debug 界面，確認激活管道已建立；第二個實例 2.7 秒內退出（碼 0）。**沒有實際觸發窗口喚起和引導對話框**（會在桌面彈窗），需要用戶確認。
- [~] **L5 界面打磨**（2026-11-14 進行中）
  - 已完成：各頁統一「標題 + 一句說明 + 右側操作」頁頭；托盤菜單新增「應用程式」；狀態欄在有待回應連線時顯示橙色標籤；規則頁按來源篩選（使用者 / 彈窗 / 導入 / 內置）並顯示條數；連線記錄「詳情」對話框（全部字段可選中複製，並用一句話解釋判決原因）；信息類對話框只顯示「確定」；**修復**停用 / 全部阻斷等模式下連線記錄沒有應用信息（判決後補充解析，見 design §22）。
  - 待做：應用圖標（從可執行文件提取，需要 Win32 互操作）、窄窗口下指標卡自動換行、連線記錄的本地端點（目前 `LogEntry` 沒有該字段）。
- [ ] **L6 後續候選**（按價值排序）：流量統計（ETW Kernel-Network → `traffic_*` 表，已有表結構）；實時連線視圖；配置文件 Profiles 及按網絡自動切換；勿擾期間阻斷彙總通知；黑名單訂閱。

---

## 📱 Android 版

MVP 已完成（2026-11-14，用戶已在真機上安裝確認）。進度、驗證記錄和待辦見 [progress-android.md](progress-android.md)，設計見 [android.md](android.md)。

---

## 🧭 第二階段（M3 v1.0，桌面 + Android，2026-11-14 起）

原則：功能落在 Core / Storage（兩個平台共用），平台只提供數據源和界面；每項同時給出桌面和 Android 的實現或明確的「此平台不適用」說明。Android 進度細節記在 [progress-android.md](progress-android.md)。

**長期執行計劃：[m3-plan.md](m3-plan.md)**（階段順序、檢查點、用戶驗證隊列、風險與交接方式）。日常認領仍按下面的任務清單標記。

- [x] **T1 流量統計**（FR-STA-01，2026-11-14）
  - Core：`Traffic/TrafficAggregator`（引擎樣本只入隊；後台解析應用身份、按（應用, 分鐘）累加，每 15 秒寫入；停止時寫完）；`ITrafficRepository`。Storage：`SqliteTrafficRepository` 同時累加分鐘 / 小時 / 天三張表（表結構在 0001 中已有），分鐘表保留 3 天；查詢跨度不超過 2 天用分鐘表，否則用小時表。
  - 數據源：**模擬引擎**（允許連接時和每 10 秒為活躍進程生成流量）；**Windows**：`Traffic/NetworkTrafficMonitor`，ETW Microsoft-Windows-Kernel-Network 的 TCP / UDP 收發事件按 PID 累加，每 10 秒上報（失敗時報非致命故障，診斷包中有事件計數）；**Android**：`AndroidUidTrafficSource`（NetworkStatsManager，Wi-Fi + 行動網路）+ `Engine.Vpn` 中平台無關的 `UidTrafficCollector`（系統分桶持續增長，記住每個（UID, 分桶起點）上次的值只上報增量；狀態存文件，進程重啟不重複計數）。
  - IPC：`ActivityLog.GetTraffic`；`Apps.List` 新增 `bytes_in / bytes_out`。桌面界面：總覽「網路用量」卡片（下載 / 上傳總量、堆疊柱狀圖、用量排行），應用頁「用量」列。
  - 驗證：聚合器、倉儲、ETW 事件分類 / 按進程聚合、UID 增量與重啟恢復的單元測試；模擬引擎探針：`GetTraffic` 返回 4 個應用的下載 / 上傳和時間軸，`Apps.List` 帶用量。**未驗證**：真實 WFP 下的 ETW 流量（需要管理員）、Android 設備上的 NetworkStatsManager（本機無設備）、界面效果。
- [ ] **其餘任務已拆分並寫好詳細設計，見 [m3-tasks.md](m3-tasks.md)**，可以分派給外部 agent（Codex、DeepSeek 等）實現，由 claude 審查。認領時在下面對應行標 `[~] (agent, 日期)`：
  - [~] (codex → claude, 2026-11-14) M3-01 實時連線視圖（桌面）
    - 已完成 Core `IConnectionTable` 抽象、Windows iphlpapi TCP/UDP 連線表讀取、連接狀態 / 端口轉換測試、`connections.proto`、Viewer gRPC 列表服務、Fake 連線表和桌面即時連線頁（搜尋、監聽 / 回環篩選、2 秒刷新、不支持提示、記錄跳轉、建規則預填）。
    - **Phase 0 已完成（claude, 2026-11-14，提交 `0b273f2`）**：修正 `TcpRow6` 字段順序並為四種行結構加字段偏移測試；補 DnsMapper 域名反查；界面改為按行鍵增量更新；導航項移到「連線記錄」之前；`ConnectionResolver` 抽出並加 Core 測試。隔離管道 + Fake 引擎探針確認 `Connections.List` 返回應用名稱 / 路徑 / 域名。**待用戶確認界面刷新、跳轉和建規則。**
  - [~] (codex → claude, 2026-11-14) M3-02 斷開連線（桌面，依賴 M3-01）
    - 已完成 Windows `SetTcpEntry` IPv4 TCP 断开后端、Connections Admin RPC 和能力测试。
    - **Phase 0 已完成（claude, 2026-11-14，提交 `0b273f2`）**：按 `ServiceStatus.engine.capabilities` 判斷 `KillConnection`；限 TCP / IPv4 / Established；新增確認氣泡和「斷開並拒絕」；Fake 引擎探針確認不支持時返回明確提示。**待用戶以管理員權限實機驗證。**
  - [~] (claude, 2026-11-14) M3-03 配置文件管理（Core + 桌面 + Android）
    - Core：`Profile` 追加 `AutoActivate` 佔位、`IProfileRepository`、`ProfileService`（建立 / 改名 / 模式、複製專屬規則、刪除約束）、`RuntimeStateService.ActivateProfileAsync`（BlockAll 下只記錄新配置文件的模式）、啟動任務 Order 120。
    - Storage：`SqliteProfileRepository`（讀寫 `auto_activate`，外鍵級聯刪除）、測試。
    - IPC / Service：`profiles.proto`、`ProfilesGrpcService`、`ServiceStatus.active_profile_name`、`ServiceStatusBuilder` 共用；導入時不存在的配置文件降級為共享規則。
    - 桌面：設定頁配置文件卡片（新增 / 編輯 / 刪除 / 設為當前）、總覽下拉、托盤子選單、狀態欄、規則編輯器「適用於」、規則頁配置文件列與篩選、三語文本。
    - Android：`IBackend` 配置文件 API、總覽下拉、設定頁增刪改、`tools/android-strings.py` 生成文本；Debug 構建通過。
    - **驗證**：356 個測試 355 通過、1 跳過；隔離管道 + Fake 引擎探針驗證建立 / 切換 / 改名 / 刪除、專屬規則切換後生效（生效規則 16→1）、刪除後規則歸零。**未驗證**：桌面和 Android 界面效果、Android 真機。
  - [~] (claude, 2026-11-14) M3-04 按網絡自動切換配置文件（依賴 M3-03）
    - Core：`NetworkContext` / `INetworkMonitor` / `AutoActivateCondition`；`ProfileAutoSwitcher` 去抖 3 秒、按 SortOrder 匹配、無匹配保持當前、用戶手動切換後同一網絡不再自動切換。
    - 平台：Fake 網絡監視器 + `Simulator.SimulateNetwork`；Windows 用 `NetworkInterface` 判斷有線 / Wi-Fi / 蜂窩和網絡 ID（SSID 暫未接入 WlanAPI）；Android `registerDefaultNetworkCallback`，SSID 需要定位權限。
    - 界面：桌面配置文件編輯器加入自動切換類型 / SSID；`Profiles.GetNetwork` 和 `ServiceStatus.network_display_name`。
    - **驗證**：單元測試和 Fake 探針（Wi-Fi CorpNet 自動切到公司、無匹配保持、手動覆蓋）。**未驗證**：Windows 真實 Wi-Fi 切換、Android 真機。
  - [ ] M3-05 Android 阻斷快速失敗（TCP RST / ICMP 不可達）
  - [ ] M3-06 Android 規則導入導出
  - [~] (claude, 2026-11-14) M3-07 勿擾期間阻斷彙總通知（Core + 桌面 + Android）
    - Core：`QuietPeriodTracker` 只統計 `DoNotDisturb` 判決，手動關閉和到期兩條結束路徑，`SummaryReady` 事件；無事件不生成。
    - IPC / Service：`QuietPeriodSummary`、`ServiceStatus.last_quiet_period`、`DismissQuietPeriod`、`LogQuery.reason` 篩選。
    - 桌面：`QuietPeriodNotifier` Toast，查看時跳轉到按原因篩選的連線記錄並清除摘要；Android 總覽卡片顯示摘要。
    - **驗證**：單元測試、存儲 reason 篩選測試、Fake 探針（勿擾中拒絕 1 條 → 摘要和原因篩選正確 → Dismiss 清除）。**未驗證**：桌面 Toast / Android 界面。
  - [ ] M3-08 入站連線控制（Windows WFP）
  - [ ] M3-09 Android 快速設定磁貼
  - [ ] M3-10 Android 通知文本跟隨應用內語言
  - [ ] M3-11 Android 診斷資訊導出
  - [~] (claude, 2026-11-14) M3-12 權限分級提示（桌面）
    - `ServiceConnection.CanModify`；`PageViewModel.RunAsync` 把 `PermissionDenied` 轉為本地化提示；規則 / 應用 / 配置文件 / 服務設置的寫操作禁用，頁頭顯示說明。
    - **未驗證**：`TreatAllClientsAsAdmin=false` + 普通用戶的界面效果（需要用戶確認）。
  - [ ] M3-13 檢查更新（桌面，**需要用戶先決定發布源**）
  - [~] (claude, 2026-11-14) M3-14 應用圖標（桌面）
    - `AppIconCache` 用 shell32 / gdi32 在後台提取大圖標，轉成 `WriteableBitmap`，按路徑緩存最多 512 項；應用頁顯示圖標；失敗或非 Windows 返回 null。
    - **驗證**：構建通過；**未驗證**：界面圖標效果（需要用戶確認）。目前只接入應用頁，即時連線 / 排行榜 / 詢問彈窗尚未接入。
  - [ ] M3-15 svchost 服務與 MSIX 應用的規則下發（Windows，含預研）
  - [ ] M3-16 目錄簽名（catalog）校驗（Windows）
  - [ ] M3-17 WFP 管理員集成測試（Windows）
  - [ ] M3-18 安裝程序繁體中文界面（Windows）
  - [~] (codex, 2026-11-14) M3-19 界面小修：指標卡換行、連線記錄的本地端點（桌面）
    - 完成連線記錄本地端點：追加 Activity 0003 遷移、`LogEntry.local` proto 字段、最近一次本地端點寫入 / 查詢，以及日誌表格和詳情顯示；聚合鍵保持不變。
    - 完成窄窗口響應式布局：總覽指標卡在寬度不足 1000 時切換為 2×2，網路用量和排行榜切換為上下排列。
    - **Phase 0 已完成（claude, 2026-11-14，提交 `7cb38f1`）**：已審查並補存儲層測試，代碼已合入；**待用戶確認窄窗口布局和詳情中的「本地端點（最近一次）」。**
- 用戶事項（不需要編碼）：Windows 代碼簽名證書、Android 正式密鑰庫（`NC_ANDROID_KEYSTORE`）。

---

## 🤝 可分派任務（適合交給其他 agent 獨立完成）

> 認領規則、分支和完成標準見 [AGENTS.md §2](../AGENTS.md#2-協作規則多-agent-並行)。認領時把 `[ ]` 改為 `[~] (agent, 日期)`。
> 這些任務邊界清楚，不需要管理員權限，也不涉及核心設計決策。

- [x] (codex, 2026-11-13) **A1 config.db 每日備份**（FR-SET-04，小）
  - 範圍：`src/NetworkControl.Storage/Backup/`（新建）、`StorageServiceCollectionExtensions.cs`、`tests/NetworkControl.Storage.Tests/`
  - 做法：用 `BackgroundService` 每天一次，通過 `SqliteConnection.BackupDatabase` 把 config.db 備份到 `<數據目錄>/backups/config-yyyyMMdd.db`，保留最近 7 份；時間通過 `TimeProvider` 注入。
  - 驗收：用 `FakeTimeProvider` 推進時間的測試，覆蓋生成備份、超過 7 份時刪除最舊的、備份可以打開並讀到規則。
  - 完成（codex 初版，claude 審查後修正）：`Backup/ConfigBackup`（核心邏輯）+ `ConfigBackupStartupTask`（Order 在數據庫遷移之前）+ `ConfigBackupService`（每小時檢查，跨過本地日期才建立）。按本地日期命名，**當天已有備份就不覆蓋**（升級當天遷移前的副本得以保留）；首次安裝數據庫不存在時跳過；先寫臨時文件再改名；只清理 `config-yyyyMMdd.db` 格式的文件；刪除失敗單獨記警告。新增 `SqliteDatabase.BackupToAsync`，診斷包的一致性副本也改用它。
  - 測試：生成備份可讀到規則、同日不覆蓋、保留 7 份且不刪用戶文件、數據庫不存在時跳過、啟動任務先於遷移、後台服務跨日期生成新備份（FakeTimeProvider）。
  - 未做：一鍵恢復（FR-SET-04 後半）需要 IPC + 界面，列入長期規劃。
  - 不要改：Core、proto、App。
- [x] (codex, 2026-11-13) **A2 規則導出格式與序列化**（FR-SET-03 第一步，中）
  - 範圍：`src/NetworkControl.Storage/Serialization/`（新增導出 DTO 和序列化器）、對應測試
  - 做法：格式為 `{ "format": "netcontrol-rules", "version": 1, "rules": [...] }`，使用 System.Text.Json 源生成。路徑中的用戶目錄替換為 `%USERPROFILE%` 等佔位符，導入時展開。複用現有的 `DtoMapper`。
  - 驗收：導出再導入的往返測試；佔位符替換測試；格式版本不符時報錯。
  - 不要改：IPC 接口和界面（下一步由主 agent 接入）。
  - 完成（codex 初版，claude 審查後修正）：`RuleExportFormat.cs`（獨立的嚴格源生成上下文：`RespectNullableAnnotations` / `RespectRequiredConstructorParameters`，不影響數據庫 JSON 列）、`RuleExportSerializer`、`PathPlaceholderMapper`。
    - **佔位符目錄由調用方傳入**（服務以 SYSTEM 運行，不能用服務進程自己的目錄）；`ForCurrentUser()` 只用於以用戶身份運行的進程。前綴按路徑段匹配（`C:\Users\bob` 不匹配 `C:\Users\bobby`），按目錄長度從長到短匹配。
    - 只導出永久 / 限時規則，跳過內置規則和臨時規則（僅本次、進程退出前、重啟前），格式中沒有 `BoundProcess`；`IsExportable` 供界面提示「N 條不會導出」。
    - 導入先讀格式名和版本再嚴格解析；缺字段、null、未知枚舉值、端口越界、限時規則缺過期時間、無效 IP 等一律報 `FormatException`（指明第幾條）。
    - 導入結果原樣保留 Id / ProfileId / Source：合併或覆蓋、Id 衝突、配置文件不存在等策略由接入 IPC 的一方決定。
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
- [ ] **按可信發布者批量允許**（設計見 [design.md §24](design.md#24-減輕首次使用的詢問負擔可信發布者規則與詢問批量處理規劃2026-11-14)，2026-11-14 從 Android 待辦借鑒）：例如「Microsoft Corporation 簽名的程序一律允許」，減輕首次使用的詢問負擔；首次運行引導（L4）中提供勾選項。Core 追加「發布者條件」（不能復用篡改檢測用的 `RequiredSignerId`），發布者規則的具體度低於指定路徑的規則；Android 的「系統應用默認允許」共用這套實現。
- [ ] **詢問隊列批量處理**（同上 §24）：「待決詢問」列表，多選後一次允許 / 拒絕 / 稍後；Core 的 `PromptBroker` 追加批量回應（只發布一次規則集），`prompts.proto` 追加對應接口。Android 共用 Core 部分。
- [ ] 入站連接控制（ALE_AUTH_RECV_ACCEPT 層）
- [ ] 流量統計（ETW Kernel-Network → traffic_* 表 → 統計頁圖表）
- [ ] 實時連接視圖（GetExtendedTcpTable）
- [ ] 配置文件 Profiles + 按網絡自動切換
  - 首要場景（2026-11-14，對應 Android 的「Wi-Fi / 行動網路分別允許」和需求中的「流量敏感用戶」）：**連上計量網絡或手機熱點時自動進入「省流量」情境**，只放行必要的應用；Windows 可以讀到網絡是否按流量計費。作為 Core「情境」能力（design.md §23）的第一批用例。
- [ ] **Core：情境 + 時間表**（設計見 [design.md §23](design.md#23-情境時間表與專注模式規劃2026-11-14)，需求 FR-MOD-05 / 06）：在 Profile 上擴展情境（帶到期恢復）、運行時狀態記錄當前情境、調度組件按時段切換；桌面版和 Android 共用。與上一項「配置文件 Profiles」一起實現。
- [ ] **專注模式（桌面界面）**：總覽頁和托盤菜單一鍵進入，提前結束需要確認 + 倒計時，結束小結。產品定位是自律工具，不做家長管控（需求 §1.3）。
- [ ] 規則導入導出、每日備份
- [ ] svchost 服務名細分、目錄簽名（catalog）校驗
- [ ] 勿擾期間阻斷彙總通知、全屏自動勿擾
- [ ] 權限分級落地（TreatAllClientsAsAdmin=false 時的界面提示）
- [ ] 代碼簽名（安裝程序和可執行文件；目前會出現 SmartScreen 提示）
- [ ] 安裝程序繁體中文界面（Inno Setup 沒有官方繁體語言文件，目前提供簡體中文和英文）
- [ ] 檢查更新（目前由用戶手動下載新版安裝程序覆蓋安裝）

### P2 / 後續里程碑
- [~] (codex → claude, 2026-11-13) M2 Linux 引擎（nftables NFQUEUE + eBPF + NFLOG）——基礎層，**尚未接入服務，從未在 Linux 上運行過**
  - 已有：`NetworkControl.Engine.Linux`——nftables 腳本（冪等：先 add 再 delete 表；`queue num 0 [bypass]`）、`NfqueueAdapter`（專用收包線程，poll + eventfd 停止，先等線程退出再釋放句柄；ENOBUFS / EINTR 繼續，致命錯誤上報 `OnEngineFault` 並銷毀隊列讓 bypass 生效；`LibraryImport` + `UnmanagedCallersOnly`；按 `.so.1` 解析庫名）、`NfqueuePacketHandler`（判決異步完成，掛起的彈窗不阻塞其他報文；解析失敗、判決失敗、掛起過多都按失效策略處理；64 位 FlowId）、IPv6 擴展頭和分片解析、`/proc` 進程信息（UID 按 Tab 解析）、`LinuxAppInspector`（SHA-256）、`LinuxProcessExitWatcher`（輪詢啟動時間）。
  - 2026-11-13 審查：codex 版本把引擎接入了服務的 Auto，但缺 `IAppInspector` / `IProcessExitWatcher` 註冊（Linux 上服務啟動即失敗）、收包線程同步等待彈窗（全機斷網）等，已修正；**服務接入已撤回**。
  - 待做：eBPF 進程歸屬（沒有 PID 時應用規則無法匹配）、DNS 採集（域名規則）、運行時切換失效策略後重新下發、服務接入（只允許顯式配置 `NfQueue`，完成歸屬前不進入 Auto）、在 Linux 虛擬機上用 root 驗證 `nft -c -f` 和真實攔截。
- [x] (claude, 2026-11-14) Android 版 MVP —— 見 [progress-android.md](progress-android.md)
- [ ] M4 macOS 引擎（NE 系統擴展，Swift）—— 需提前申請 Apple 開發者帳號和 NE 權限
- [ ] 限速、黑名單訂閱、時間段規則、地圖 / ASN、密碼保護、ncctl
- [ ] **應用頁：程序連過 / 嘗試過的域名列表**（2026-11-14 從 Android 待辦借鑒）：在 L2 應用頁的詳情中按域名聚合顯示次數和時間，便於識別遙測和廣告服務。
- [ ] **DoH 提示**（2026-11-14 從 Android 待辦借鑒）：偵測到系統或瀏覽器啟用加密 DNS（DoH）導致域名規則不生效時，在界面上提示（目前只寫在「已知限制」中）。
- [ ] Windows WFP Callout 驅動（掛起連接，需 EV 證書）

---

## ⚠️ 已知限制（M1 Windows，設計上的取捨）

見 [development.md §7](development.md#7-windows-wfp-引擎)：先阻斷後詢問、只控制出站、只記錄阻斷、svchost 服務 / MSIX 規則不下發、只校驗嵌入式簽名、DoH 下域名規則無效。

## 📝 待決問題（需要用戶決定）

- 開源協議（MIT / Apache-2.0 / GPL-3.0）—— 影響能否復用 GPL 項目代碼。**正式分發前必須確定**（2026-11-14 補充）：
  - 防火牆類軟件開源有助於建立信任。
  - **AtomUI 是 LGPL-3.0**：LGPL 一般要求用戶能夠替換該庫，而桌面版用 Native AOT 把它靜態編譯進可執行文件、Android Release 做了裁剪，是否滿足要求需要評估（非法律意見），必要時調整發布方式或通過開源本項目滿足。桌面版和 Android 都受影響。
- 正式產品名稱和圖標，以及 Android 應用 ID。**正式分發前必須確定**（2026-11-14 補充）：
  - Android 應用 ID 發出後不能修改（改了等於另一個應用：不能原地升級，規則、VPN 授權、「永久連線的 VPN」設定都要重來）；目前的 `com.networkcontrol.android` 是臨時值，`NcVpnService` 的組件名中也寫死了它。
  - 建議格式 `<自有域名倒寫>.<應用名>`，沒有域名時用 `io.github.<GitHub 用戶名>.<應用名>`；不帶 `.android` 後綴；Debug 構建加 `.debug` 後綴，與正式版並存。
  - 命名方向：體現「守門 / 安靜 / 自主」，避開「控制、監管」的感覺（產品定位是隱私 + 自律，見 requirements.md §1.3）；中英文都好讀；避開 Little Snitch、NetGuard、GlassWire、Portmaster、OpenSnitch、RethinkDNS、LuLu。
  - 候選（均未查重，確定前要查應用商店、GitHub 和商標）：**靜網 / QuietNet**（推薦：讓後台「噪音」安靜下來，貼合隱私 + 專注）、守門 / Warden（略偏管控）、網哨 / NetSentry（Sentry 撞名風險高）、保留 NetworkControl（太通用，像管控工具）。
  - 只改用戶可見的名稱、應用 ID、圖標和發布物文件名；代碼中的項目名和命名空間（`NetworkControl.*`）不需要改；桌面安裝程序的 `AppId` 不能改（AGENTS.md），顯示名稱可以改。
- zh-TW 用語：目前用台灣用語（網路、應用程式、設定），如需「繁體字 + 大陸用語」只需改 zh-TW.xlf
- 遠端倉庫：本地已 `git init`（分支 main），尚未配置遠端
- `site/` 目錄：用戶已自行提交（`2b0e59b`、`64bba9e`），工作區中有一批 `site/` 臨時文件的刪除尚未提交，由用戶處理。注意 `2b0e59b` 中同時帶入了本輪診斷功能的前一半代碼。
