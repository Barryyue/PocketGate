# 進度看板

> 每完成一項任務、或發現新問題時更新本文件（同時更新「最後更新」）。新會話從這裡了解當前狀態。
> 需求見 [requirements.md](requirements.md)，設計見 [design.md](design.md)（§22 記錄了實現與原設計的差異），開發方法見 [development.md](development.md)。

**最後更新：2026-11-12**
**當前階段：M1（Windows MVP）—— WFP 主鏈路已在真實環境跑通（攔截 → 彈窗 → 生成規則），剩餘問題修復後待用戶確認**

---

## 🔴 當前阻塞 / 下一步

1. **用戶確認本輪修復（2026-11-12 下午）**
   - 規則頁、日誌頁表格現在應該能顯示內容（原因：AtomUI DataGrid 需要 `IDataGridSource`，之前綁定的是普通集合，運行時靜默失敗）。
   - WFP 引擎下不再出現「Service is unimplemented」（原因：切換引擎後模擬器卡片沒有隱藏）。
   - 之前生成的 tipray 兩條允許規則（`ldapprovalex.exe`、`ldtermdaemon.exe`）應該能下發了：`FwpmGetAppIdFromFileName0` 被安全軟件拒絕訪問，現在改用兜底的應用 ID。驗證方法：在日誌中搜索「無法下發」，應該不再出現。
   - 服務停止時的崩潰（同一實例被 Dispose 兩次）已修復。
2. **WFP 驗收結果**：已確認 WFP 引擎啟動、下發 12 個內置過濾器、阻斷事件觸發彈窗、允許後生成規則。尚待確認：允許後應用重試能否連上、域名規則（ETW DNS）是否生效。
3. 托盤右鍵菜單：加回 FluentTheme 後**尚未經用戶確認**（若仍不顯示，改用 Win32 原生托盤菜單）。

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

**測試現狀：126 個全部通過；構建零警告；`dotnet format --verify-no-changes` 通過。**

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
- [ ] MSI 安裝包（WiX）、代碼簽名

### P2 / 後續里程碑
- [ ] M2 Linux 引擎（nftables NFQUEUE + eBPF + NFLOG）
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
- `site/` 目錄（由需求文檔生成的官網頁面 + 腳本 + 21 張截圖，非本項目代碼生成）：未跟蹤，等用戶決定是提交、忽略，還是只提交頁面不提交截圖
