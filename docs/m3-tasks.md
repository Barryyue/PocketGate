# 第二階段（M3）待辦詳細設計

> 本文給外部編碼 agent（Codex、DeepSeek 等）直接實現用，由主 agent（claude）審查代碼。
> 每個任務自成一體：**範圍**（允許修改的文件）、**設計**（接口、數據、流程）、**測試**、**驗收**。超出範圍的修改先在任務下記錄原因，或交還給用戶決定。
> 背景：[progress.md](progress.md)（當前狀態）、[design.md](design.md)（§22 是實現與原設計的差異）、[android.md](android.md)、[development.md](development.md)。協作規則見 [AGENTS.md](../AGENTS.md) 第 2 節，**踩過的坑見第 6 節，動手前必讀**。

---

## 0. 給實現者的通用要求

### 0.1 流程

1. 在 `docs/progress.md`「第二階段」中把任務標為 `[~] (agent 名, 日期)`；Android 任務同時在 `docs/progress-android.md` 標記。
2. 從 `main` 建分支 `agent/<agent名>/<任務 ID 小寫>`，例如 `agent/codex/m3-01-live-connections`。**一個任務一個分支**，不要把多個任務混在一起。
3. 完成後按 0.3 自檢，在進度文件中寫明完成內容和**沒有驗證到的部分**，然後交給主 agent 審查。審查意見修完之前不要合入 `main`。

### 0.2 硬性約束（審查時逐條檢查）

| 約束 | 說明 |
|---|---|
| proto 只追加 | 不修改、不複用已有字段編號；新字段編號按本文給出的值。新 proto 類型避開常見類型名（已有 `VerdictAction`、`IpEndpoint`、`ActivityLog`）。 |
| 遷移只追加 | `Migrations/<Config\|Activity>/NNNN_*.sql` 不能修改已有腳本；新腳本編號接著現有最大值。 |
| 界面文本 | 桌面：鍵追加在 Catalog 枚舉**末尾**，同時加到 `en-US.xlf`、`zh-CN.xlf`、`zh-TW.xlf`（台灣用語）。Android：只改 `tools/android-strings.py` 的表格再運行生成，不要手改生成的文件。 |
| Core / Storage 改動 | Android 版共用它們：只做追加式修改（新增方法、可選參數），不要改已有簽名；改完必須構建 Android（`scripts/android.ps1 build`）。 |
| 桌面界面只依賴 Ipc | `NetworkControl.App` 不能引用 Core。 |
| AtomUI DataGrid | `ItemsSource` 必須用 `DataGridLocalSource.Create(...)` 包裝，直接綁定集合會靜默失敗。 |
| 不需要管理員權限的驗證 | 驗證時用 `NETWORKCONTROL_IPC=pipe:<其他名字>` 起臨時服務，不要碰正式安裝的服務（`pipe:NetworkControl.v1`），不要截屏。需要管理員權限的步驟寫明「待用戶驗證」。 |
| AOT | 不引入反射序列化（JSON 一律源生成）、不用 `Serilog.Settings.Configuration`；新增的第三方庫要先說明 AOT 兼容性。 |
| 注釋和文檔 | 繁體中文。 |

### 0.3 完成前自檢

```bash
dotnet build NetworkControl.slnx                     # 零警告零錯誤（警告即錯誤）
dotnet test --solution NetworkControl.slnx           # 全部通過，新功能附測試
dotnet format NetworkControl.slnx --verify-no-changes
# 涉及 Core / Storage / Engine.Vpn / Android 時：
.\scripts\android.ps1 build
# Android 項目的格式檢查（dotnet format 不接受 -p:，用環境變量傳 SDK 路徑）：
$env:AndroidSdkDirectory='D:\tools\android-dev\android-sdk'; $env:JavaSdkDirectory='D:\tools\android-dev\jdk'
D:\tools\android-dev\dotnet\dotnet.exe format NetworkControl.Android.slnx --verify-no-changes
```

本機有系統代理（Reqable）：NuGet 審計失敗時臨時設置 `NuGetAudit=false`；Android 工具鏈下載時臨時設置 `HTTPS_PROXY=http://127.0.0.1:9`、`NO_PROXY=*`。**都不要提交**。

### 0.4 任務一覽與依賴

| ID | 任務 | 平台 | 規模 | 依賴 |
|---|---|---|---|---|
| M3-01 | 實時連線視圖 | 桌面 | 中 | — |
| M3-02 | 斷開連線 | 桌面 | 小 | M3-01 |
| M3-03 | 配置文件管理 | Core + 桌面 + Android | 大 | — |
| M3-04 | 按網絡自動切換配置文件 | Core + 桌面 + Android | 中 | M3-03 |
| M3-05 | 阻斷快速失敗（TCP RST / ICMP 不可達） | Android | 小 | — |
| M3-06 | 規則導入導出 | Android | 小 | — |
| M3-07 | 勿擾期間阻斷彙總通知 | Core + 桌面 + Android | 中 | — |
| M3-08 | 入站連線控制 | Windows WFP | 中 | — |
| M3-09 | 快速設定磁貼 | Android | 小 | — |
| M3-10 | 通知文本跟隨應用內語言 | Android | 小 | — |
| M3-11 | 診斷資訊導出 | Android | 小 | — |
| M3-12 | 權限分級提示 | 桌面 | 小 | — |
| M3-13 | 檢查更新 | 桌面 | 中 | 需要用戶決定發布源 |
| M3-14 | 應用圖標 | 桌面 | 小 | — |

不需要編碼、由用戶完成：代碼簽名證書（Windows 安裝程序 / 可執行文件、Android 正式密鑰庫），見 progress.md。

---

## M3-01 實時連線視圖（桌面）

**目標**：像 Little Snitch 的網絡監視器一樣，列出本機當前所有 TCP 連線和 UDP 端點，帶所屬應用。Android 10+ 不允許讀取其他應用的連線表，**不適用**。

### 範圍

- `src/NetworkControl.Core/Engine/`：新增 `Connections.cs`（平台抽象，見下）
- `src/NetworkControl.Engine.Windows/Platform/WindowsConnectionTable.cs`（新建）、`WindowsEngineServiceCollectionExtensions.cs`
- `src/NetworkControl.Engine.Fake/`：模擬實現（開發環境要能看到數據）
- `src/NetworkControl.Ipc/Protos/connections.proto`（新建）
- `src/NetworkControl.Service/Grpc/ConnectionsGrpcService.cs`（新建）、`Program.cs`（`MapGrpcService`）
- `src/NetworkControl.App/`：新頁面 `ConnectionsViewModel` / `ConnectionsView`、導航項、`ViewLocator`、`App.axaml.cs` 註冊、界面文本（新 Catalog `Connections`）
- 測試：`tests/NetworkControl.Engine.Windows.Tests/`、`tests/NetworkControl.Core.Tests/`

### 設計

**Core 抽象**（`NetworkControl.Core.Engine`）：

```csharp
/// <summary>一條當前存在的連線（或 UDP 端點）。</summary>
public sealed record ActiveConnection(
    L4Protocol Protocol,
    IPEndPoint Local,
    IPEndPoint? Remote,          // UDP 端點和 TCP 監聽為 null
    ConnectionState State,       // 見下
    int ProcessId);

public enum ConnectionState : byte { Listen, Established, SynSent, SynReceived, Closing, TimeWait, Closed, Udp }

/// <summary>【平台實現】讀取系統連線表。拿不到時返回空列表，不拋出。</summary>
public interface IConnectionTable
{
    IReadOnlyList<ActiveConnection> Snapshot();
}
```

Core 不註冊默認實現；服務層在沒有註冊時返回 `Unimplemented`（Linux / 未來平台）。

**Windows 實現**：`iphlpapi.dll` 的 `GetExtendedTcpTable` / `GetExtendedUdpTable`（`TCP_TABLE_OWNER_PID_ALL`、`UDP_TABLE_OWNER_PID`），IPv4 + IPv6 各調用一次。
- 用 `[LibraryImport]`；先傳 0 長度拿到所需大小，`NativeMemory.Alloc` 分配，返回 `ERROR_INSUFFICIENT_BUFFER` 時重試（表在兩次調用之間會變大，最多重試 3 次）。
- 結構體：`MIB_TCPROW_OWNER_PID`（v4，端口是網絡字節序的低 16 位）、`MIB_TCP6ROW_OWNER_PID`（v6，含 scope id）、對應的 UDP 結構。**按 SDK 頭文件 `tcpmib.h` / `udpmib.h` 核對字段和大小，並在 `InteropLayoutTests` 中斷言大小和偏移**（AGENTS.md 第 6 節的要求）。
- `State` 從 `MIB_TCP_STATE` 映射（1 Closed … 12 DeleteTcb）。
- 不需要管理員權限；服務以 SYSTEM 運行，所有進程的 PID 都可見。

**proto**（新文件 `connections.proto`，`package netcontrol.v1`）：

```proto
service Connections {
  rpc List (ListConnectionsRequest) returns (ListConnectionsResponse);
}
message ListConnectionsRequest {
  bool include_listening = 1;   // 默認只返回已建立的連線和 UDP 端點
  bool include_loopback = 2;
}
enum ConnectionStateKind {
  CONNECTION_STATE_KIND_LISTEN = 0;
  CONNECTION_STATE_KIND_ESTABLISHED = 1;
  CONNECTION_STATE_KIND_SYN_SENT = 2;
  CONNECTION_STATE_KIND_SYN_RECEIVED = 3;
  CONNECTION_STATE_KIND_CLOSING = 4;
  CONNECTION_STATE_KIND_TIME_WAIT = 5;
  CONNECTION_STATE_KIND_CLOSED = 6;
  CONNECTION_STATE_KIND_UDP = 7;
}
message LiveConnection {
  Protocol protocol = 1;
  IpEndpoint local = 2;
  IpEndpoint remote = 3;              // 未設置表示沒有遠端
  ConnectionStateKind state = 4;
  int32 pid = 5;
  optional int64 app_id = 6;
  string app_name = 7;
  string app_path = 8;
  optional string hostname = 9;       // DnsMapper 反查（可能沒有）
}
message ListConnectionsResponse {
  repeated LiveConnection connections = 1;
  bool supported = 2;                 // 當前平台是否能讀取連線表
}
```

**服務**：`[Authorize(Policy = IpcPolicies.Viewer)]`。流程：`IConnectionTable.Snapshot()` → 過濾（監聽、回環）→ 按 PID 分組，每個 PID 用 `IProcessInfoProvider.TryGetProcess` + `IAppIdentityResolver.ResolveAsync`（構造只帶 `Process` / `ExecutablePath` 的 `FlowInfo`，做法參考 `Core/Traffic/Traffic.cs` 的 `AccumulateAsync`）得到應用；遠端地址用 `DnsMapper` 反查域名（看 `DnsMapper` 現有的按 IP 查詢方法，沒有就只追加一個只讀方法）。單次最多返回 5000 條。

**界面**（新導航項「即時連線」，放在「連線記錄」之前，圖標 `ApiOutlined`）：
- 頁頭：標題 + 說明 + 右側「顯示監聽端口」「顯示本機回環」兩個開關 + 刷新按鈕；頁面可見時每 2 秒刷新（`OnActivatedAsync` 啟動 `DispatcherTimer`，`OnDeactivated` 停止，參考 `ActivityDashboardViewModel`）。
- 搜索框（應用、地址、域名）。
- DataGrid 列：應用（名稱 + 路徑兩行）、協議、本地端點、遠端（域名優先，懸停顯示 IP）、狀態（Tag）、PID、操作（「記錄」跳到連線記錄，`NavigationService` + `LogNavigation`；「建規則」打開預填的規則編輯器 `RuleEditing.CreateAsync(new RulePrefill(...))`）。
- 行鍵用 `協議|本地|遠端|PID`，刷新時增量更新（保持選中和滾動位置）：按行鍵對比，新增插入、消失刪除、存在的更新屬性。
- `supported == false` 時顯示 `atom:Empty` + 「此平台不支持」。

**模擬引擎**：`FakeConnectionTable` 返回當前「運行中」的模擬進程各 1–3 條 Established 連線（遠端用模擬時用過的地址）。

### 邊界

- 進程在快照和解析之間退出：應用顯示為「未識別」，不報錯。
- 表很大（數千條）：服務端過濾後再解析；界面增量更新，不要每次清空重建。
- IPv4 映射的 IPv6 地址顯示為 IPv4。

### 測試

- `InteropLayoutTests`：四種行結構的大小和字段偏移。
- 端口字節序轉換、狀態映射的單元測試（純函數提出來測）。
- 服務層過濾和分組：用假的 `IConnectionTable`。
- 運行時：模擬引擎下用 gRPC 探針調用 `Connections.List`，確認返回模擬進程的連線；Windows 引擎下（不需要管理員也能讀連線表）確認能列出本機真實連線。

### 驗收

- 打開「即時連線」頁能看到瀏覽器等程序的連線，2 秒內更新；切走後停止刷新（日誌 / 斷點確認）。
- 從連線建規則、跳轉到記錄都正確。

---

## M3-02 斷開連線（桌面，依賴 M3-01）

**目標**：在即時連線頁中「斷開」一條 TCP 連線（FR：KillConnection 能力已在 `EngineCapabilities` 中預留）。

### 範圍

`Engine.Windows/WfpEngine.cs`（實現 `KillConnectionAsync`）、`Engine.Windows/Platform/WindowsConnectionTable.cs`、`connections.proto`（追加 rpc）、`ConnectionsGrpcService.cs`、即時連線頁、測試。

### 設計

- Windows：`SetTcpEntry`（`MIB_TCPROW`，`dwState = MIB_TCP_STATE_DELETE_TCB = 12`）。**只支持 IPv4**（系統沒有 IPv6 對應接口）；需要管理員權限（服務是 SYSTEM，可以）。成功返回 true；`WfpEngine.Capabilities` 加上 `KillConnection`。
- proto 追加：

```proto
// service Connections 中追加
rpc Kill (KillConnectionRequest) returns (KillConnectionResponse);

message KillConnectionRequest {
  Protocol protocol = 1;
  IpEndpoint local = 2;
  IpEndpoint remote = 3;
}
message KillConnectionResponse {
  bool killed = 1;
  string message = 2;   // 失敗原因（不支持 IPv6、連線已不存在等）
}
```

- 服務：`[Authorize(Policy = IpcPolicies.Admin)]`，調用 `INetworkEngine.KillConnectionAsync(new ConnectionKey(...))`。
- 界面：行操作中增加「斷開」（`PopupConfirm` 確認）；只在 TCP、IPv4、狀態為 Established、且引擎能力包含 `KillConnection`（`ServiceStatus.engine.capabilities` 中有 `"KillConnection"`）時可用；結果用 `FeedbackService` 提示。
- 「斷開並拒絕」：確認後先打開預填拒絕規則的編輯器，用戶確定後再斷開。

### 測試 / 驗收

- `MIB_TCPROW` 佈局測試；端點到行結構的轉換測試。
- **待用戶驗證**（需要管理員）：斷開瀏覽器的一條連線，頁面刷新後該連線消失。

---

## M3-03 配置文件管理（Core + 桌面 + Android）

**目標**：FR 中的 Profiles——多套「模式 + 規則」，例如「家裡」「公司」「咖啡店」。數據表 `profiles` 和 `rules.profile_id` 在 0001 遷移中已存在，`Rule.ProfileId`、`RuntimeState.ActiveProfileId` 已經在用，`RuleService.Republish` 已經按當前配置文件篩選規則（`ProfileId is null || == active`），**缺的是配置文件的增刪改查和切換**。

### 範圍

- Core：`Domain/Modes.cs`（`Profile` 追加 `AutoActivate` 字段，見 M3-04，這裡先放 `string?` 佔位不使用）、新建 `Profiles/ProfileService.cs`、`Hosting/CoreServiceCollectionExtensions.cs`
- Storage：新建 `Repositories/SqliteProfileRepository.cs`、`StorageServiceCollectionExtensions.cs`
- Ipc：新建 `profiles.proto`；`control.proto` 的 `ServiceStatus` 追加字段
- Service：新建 `Grpc/ProfilesGrpcService.cs`、`ControlGrpcService`（狀態中帶上配置文件名）、`Program.cs`
- 桌面：設定頁新卡片或新頁面（見下）、規則編輯器的「配置文件」下拉、托盤菜單、狀態欄、界面文本
- Android：`IBackend` 追加方法、總覽頁、`tools/android-strings.py`
- 導入導出：`RuleExportFormat` 已帶 `ProfileId`，**本任務不改導出格式**；導入時目標機器沒有該配置文件的規則改為共享規則（`ProfileId = null`），在 `RulesGrpcService.ApplyAsync` 中處理並計入結果說明。

### 設計

**Core**：

```csharp
public interface IProfileRepository   // 【存儲實現】
{
    Task<IReadOnlyList<Profile>> LoadAllAsync(CancellationToken ct);
    Task UpsertAsync(Profile profile, CancellationToken ct);
    Task DeleteAsync(Guid id, CancellationToken ct);   // 規則由外鍵 ON DELETE CASCADE 一併刪除
}

public sealed class ProfileService   // 單例；啟動任務 Order = StartupOrder.Rules - 10 加載
{
    IReadOnlyList<Profile> All { get; }
    Profile Active { get; }
    event EventHandler? Changed;
    Task<Profile> CreateAsync(string name, FilterMode mode, Guid? copyRulesFrom, CancellationToken ct);
    Task<Profile> UpdateAsync(Profile profile, CancellationToken ct);         // 改名、改模式、排序
    Task DeleteAsync(Guid id, CancellationToken ct);                          // 默認配置文件和當前配置文件不能刪
    Task ActivateAsync(Guid id, ProfileSwitchReason reason, CancellationToken ct);
}
public enum ProfileSwitchReason : byte { User, Network }
```

- **切換**＝`RuntimeStateService` 中寫入 `ActiveProfileId` 並把 `Mode` 設為該配置文件的模式（追加一個方法 `ActivateProfileAsync(Guid id, FilterMode mode)`，一次原子更新兩個字段）→ `RuleService.Republish()`（已有，會按新的配置文件篩選）。`BlockAll` 狀態下切換：只記錄配置文件，保持 BlockAll，恢復時回到新配置文件的模式。
- **模式跟隨配置文件**：用戶在總覽切換模式時，同時更新當前配置文件的 `Mode`（`ProfileService` 訂閱 `RuntimeStateService.Changed` 回寫；BlockAll 和 Disabled 不回寫，它們是臨時狀態）。
- 新建配置文件可選「複製某個配置文件的專屬規則」（複製 `ProfileId == source` 的規則，生成新 Id）；共享規則（`ProfileId == null`）不需要複製。
- 新規則的默認歸屬：詢問彈窗生成的規則仍按現有邏輯（`PromptRuleFactory` 已帶 `ActiveProfileId`）；規則編輯器默認「所有配置文件」（null）。

**Storage**：`SqliteProfileRepository` 讀寫 `profiles` 表（`auto_activate` 列 M3-04 使用，本任務原樣讀寫）。不需要新遷移。

**proto**（新文件 `profiles.proto`）：

```proto
service Profiles {
  rpc List (google.protobuf.Empty) returns (ListProfilesResponse);
  rpc Create (CreateProfileRequest) returns (ProfileInfo);          // Admin
  rpc Update (ProfileInfo) returns (ProfileInfo);                   // Admin
  rpc Delete (DeleteProfileRequest) returns (google.protobuf.Empty); // Admin
  rpc Activate (ActivateProfileRequest) returns (ServiceStatus);    // Admin
}
message ProfileInfo {
  string id = 1;
  string name = 2;
  FilterMode mode = 3;
  int32 sort_order = 4;
  int32 rule_count = 5;          // 專屬規則數（只讀）
  bool is_default = 6;           // 只讀
  bool is_active = 7;            // 只讀
}
message ListProfilesResponse { repeated ProfileInfo profiles = 1; }
message CreateProfileRequest { string name = 1; FilterMode mode = 2; optional string copy_rules_from = 3; }
message DeleteProfileRequest { string id = 1; }
message ActivateProfileRequest { string id = 1; }
```

`control.proto` 的 `ServiceStatus` 追加：`string active_profile_name = 10;`（字段 4 `active_profile_id` 已有）。

**桌面界面**：
- 設定頁新卡片「配置文件」：列表（名稱、模式、規則數、當前標記），新增 / 改名 / 刪除（`PopupConfirm`，提示會刪除 N 條專屬規則）/ 設為當前。名稱 1–40 字符、不能重名。
- 總覽「快速操作」卡片：模式選擇器上方加配置文件下拉（切換即 `Activate`）。
- 托盤：新增「配置文件」子菜單（單選）。狀態欄顯示「配置文件：公司」。
- 規則編輯器：新增「適用於」下拉（所有配置文件 / 各配置文件），對應 `RuleDefinition.profile_id`（已有字段 1）。規則頁增加一列「配置文件」並可按配置文件篩選。

**Android**：`IBackend` 追加 `IReadOnlyList<Profile> Profiles`、`Profile ActiveProfile`、`Task ActivateProfileAsync(Guid, CancellationToken)`、`Task<Profile> CreateProfileAsync(...)`、`Task DeleteProfileAsync(...)`；總覽頁模式卡片上方加配置文件選擇（`atom:ComboBox` 或 Segmented，超過 3 個用 ComboBox）；設定頁「配置文件」卡片做增刪改。VPN 引擎在配置文件切換後會通過 `RuleSetChanged` 自動重建分流，不需要額外處理（確認 `VpnBlackholeEngine` 訂閱了規則集變化）。

### 邊界

- 刪除當前配置文件：拒絕（先切到別的）。刪除默認配置文件：拒絕。
- 只有一個配置文件時，界面隱藏所有配置文件相關的選擇器（不給沒有用到這個功能的用戶增加負擔）。
- 規則的 `ProfileId` 指向不存在的配置文件（舊數據或導入）：視為共享規則並在日誌中警告。

### 測試

- `ProfileService`：創建（含複製規則）、切換後 `RuleService` 生效規則集變化、刪除約束、BlockAll 下切換、模式回寫。用 `ScenarioHost`（`tests/NetworkControl.Core.Tests/Scenarios`）寫一個場景：公司配置文件拒絕 curl、家裡允許 → 切換後模擬連接結果相反。
- `SqliteProfileRepository` 的讀寫和級聯刪除。
- 探針：`Profiles.Create / Activate / List`，`GetStatus` 中的配置文件名。

### 驗收

- 新建「公司」，在其中建一條拒絕規則；切到「默認」後該規則不生效（模擬器驗證），切回後生效。
- Android 構建通過；切換配置文件後分流重建（日誌）。

---

## M3-04 按網絡自動切換配置文件（依賴 M3-03）

**目標**：連到指定的 Wi-Fi / 網絡時自動切換配置文件（同時完成 Android P1「按網絡類型分別允許」的主要訴求：家裡 Wi-Fi 與行動網路使用不同配置）。

### 範圍

- Core：新建 `Profiles/NetworkContext.cs`（抽象 + 匹配邏輯）、`Profiles/ProfileAutoSwitcher.cs`；`Profile` 的 `AutoActivate` 改為結構化條件（見下）
- Windows：`Engine.Windows/Platform/WindowsNetworkMonitor.cs`（新建）
- Android：`Engine.Android/AndroidNetworkMonitor.cs`（新建）；需要的權限見下
- Fake：模擬實現 + 模擬器接口追加「模擬切換網絡」（`simulator.proto` 追加 rpc）
- `profiles.proto` 追加字段；桌面和 Android 的配置文件編輯界面

### 設計

**網絡上下文**（平台無關）：

```csharp
public enum NetworkKind : byte { None, Wired, Wifi, Cellular, Vpn, Other }

/// <summary>當前主要網絡。字段拿不到時為 null（例如沒有定位權限時 Android 讀不到 SSID）。</summary>
public sealed record NetworkContext(NetworkKind Kind, string? Ssid, string? NetworkId, string? DisplayName);

public interface INetworkMonitor   // 【平台實現】
{
    NetworkContext Current { get; }
    event EventHandler? Changed;    // 去抖由 ProfileAutoSwitcher 負責
}

/// <summary>自動激活條件，序列化為 JSON 存在 profiles.auto_activate 列（源生成上下文）。</summary>
public sealed record AutoActivateCondition(NetworkKind? Kind, IReadOnlyList<string> Ssids, IReadOnlyList<string> NetworkIds);
```

- `NetworkId`：Windows 用 NLM（Network List Manager）的網絡 ID（`INetworkListManager` → `INetwork.GetNetworkId`，COM；**Native AOT 下用 `ComWrappers` 源生成 `[GeneratedComInterface]`，不要用 `dynamic` / 反射 COM**），可以識別有線網絡；Android 用不到，填 null。
- `ProfileAutoSwitcher`（BackgroundService）：網絡變化後去抖 3 秒；按配置文件 `SortOrder` 找第一個條件匹配的配置文件（SSID 不區分大小寫精確匹配；`Kind` 匹配；條件為空的配置文件不參與自動切換）；與當前不同就 `ActivateAsync(id, ProfileSwitchReason.Network)`。沒有匹配時**保持當前**，不回到默認。
- 用戶手動切換後，在同一個網絡上不再自動切換（記住「網絡指紋 → 手動選擇」直到網絡變化）。
- 切換時發通知：桌面 Toast「已切換到配置文件『公司』（Wi-Fi：CorpNet）」；Android 普通通知（低優先級頻道）。

**Windows 網絡監聽**：`NetworkChange.NetworkAddressChanged` 觸發後，用 NLM 取連通的網絡（`GetNetworks(NLM_ENUM_NETWORK_CONNECTED)`）；SSID 用 `WlanAPI`（`WlanEnumInterfaces` + `WlanQueryInterface(wlan_intf_opcode_current_connection)`）。服務以 SYSTEM 運行，兩者都可用。

**Android 網絡監聽**：`ConnectivityManager.registerDefaultNetworkCallback`；類型取 `NetworkCapabilities.hasTransport`；SSID 從 `NetworkCapabilities.getTransportInfo()` 的 `WifiInfo.getSSID()`（API 31+），**需要 `ACCESS_FINE_LOCATION` 且定位開啟**，否則返回 `<unknown ssid>`。設計上：只按類型（Wi-Fi / 行動網路）切換不需要任何新權限；按 SSID 切換時在界面上說明並請求定位權限（`ACCESS_FINE_LOCATION`，清單追加），用戶拒絕則 SSID 條件不可用。注意 VPN 自身也是一個網絡：取**底層**網絡（`NetworkCapabilities` 中排除 `TRANSPORT_VPN`）。

**proto**：`ProfileInfo` 追加

```proto
  repeated string auto_ssids = 8;
  optional NetworkKindValue auto_kind = 9;
  repeated string auto_network_ids = 10;
```
新枚舉 `NetworkKindValue { NETWORK_KIND_VALUE_NONE = 0; ..._WIRED = 1; ..._WIFI = 2; ..._CELLULAR = 3; ..._VPN = 4; ..._OTHER = 5; }`；`ServiceStatus` 追加 `string network_display_name = 11;`。新增 `rpc GetNetwork (google.protobuf.Empty) returns (NetworkInfo);`（`NetworkInfo { NetworkKindValue kind = 1; optional string ssid = 2; optional string network_id = 3; string display_name = 4; }`），界面用它實現「使用目前的網絡」按鈕。

**界面**：配置文件編輯對話框增加「自動切換」區：網絡類型（不限 / 有線 / Wi-Fi / 行動網路）、SSID 列表（可輸入多個，旁邊有「使用目前的 Wi-Fi」按鈕）。

### 測試

- 條件匹配（純函數）：類型、SSID 大小寫、多配置文件按順序、空條件。
- `ProfileAutoSwitcher`：假 `INetworkMonitor` + `FakeTimeProvider`，驗證去抖、無匹配保持、手動覆蓋。
- 模擬器：`SimulateNetwork` rpc 切換網絡 → 配置文件變化。
- **待用戶驗證**：Windows 真實切換 Wi-Fi；Android 真機 Wi-Fi ↔ 行動網路。

---

## M3-05 Android 阻斷快速失敗

**目標**：被阻斷應用的 TCP SYN 立即收到 RST、UDP 收到 ICMP 端口不可達，應用立刻報「無法連線」而不是等到超時（progress-android.md P1）。

### 範圍

`src/NetworkControl.Engine.Vpn/Packets/IpPacketBuilder.cs`（追加 TCP RST、ICMPv4 / ICMPv6 不可達構造）、`BlockedFlowReporter.cs`（`OnPacket` 中回應）、`VpnEngineOptions`（開關）、測試 `tests/NetworkControl.Engine.Vpn.Tests/`。

### 設計

- 在 `BlockedFlowReporter.OnPacket` 中，`IsConnectionAttempt` 之後、入隊之前，用 `IPacketWriter` 寫回應（與假 DNS 回答同一條路徑）：
  - **TCP SYN** → 構造 RST+ACK：源 / 目的地址和端口互換，`seq = 0`，`ack = 收到的 seq + 1`，窗口 0，無選項；IPv4 和 IPv6 都要（IPv6 偽首部校驗和）。
  - **UDP**（非 DNS）→ ICMPv4 type 3 code 3（端口不可達）/ ICMPv6 type 1 code 4，載荷為原報文的 IP 首部 + 前 8 字節（IPv4）或盡量多但總長不超過 1280（IPv6）。
- **詢問中的連線不能回 RST**：應用收到 RST 就放棄了，用戶點「允許」後應用不會自動重試。規則：只有當這個應用（UID）當前的分流決策是**明確拒絕**（規則拒絕、白名單模式下未允許、全部封鎖）時才回 RST；決策是「等待詢問」時保持現在的黑洞行為（讓 SYN 重傳，用戶允許後重建 VPN 時連線自然恢復）。決策從當前路由計劃 `VpnRoutingPlan` 中按 UID 查詢（看 `VpnBlackholeEngine.CurrentPlan` 的現有結構，必要時追加只讀查詢方法）。
- 每個（UID, 遠端）每秒最多回應一次，防止應用重試風暴把 CPU 打滿；計數器 `VpnEngineCounters` 追加 `ResetsSent`、`UnreachablesSent`。
- `VpnEngineOptions.FastFail`（默認 true），方便出問題時關掉。

### 測試

- 報文構造：RST / ICMP 的字段、長度和校驗和（用 `IpPacketParser` 解析回來斷言，並與手算的校驗和比較）；IPv4 和 IPv6。
- `BlockedFlowReporter`：明確拒絕時寫出 RST；等待詢問時不寫；限速生效（`VpnEngineScenarioTests` 中已有假的 tunnel / writer，可以復用）。
- **待用戶在真機驗證**：在 Chrome 打開被拒絕的網站，應立刻顯示錯誤頁而不是轉圈。

---

## M3-06 Android 規則導入導出

**目標**：與桌面版互通的 `netcontrol-rules` 文件導入導出（progress-android.md P1）。

### 範圍

`src/NetworkControl.App.Android/`：`Hosting/Backend.cs`（`IBackend` 追加方法）、`MainActivity.cs`（文件選擇）、設定頁、`tools/android-strings.py`；如需共享導入計劃邏輯，只能**調用** Storage 現有的公開 API（`RuleExportSerializer`、`RuleImportPlanner`），不要改它們。

### 設計

- 導出：`RuleExportSerializer.Export(rules, PathPlaceholderMapper.None)`；Android 的規則路徑都是 `android-app://<包名>`，不需要佔位符。文件用 SAF 保存：`Intent.ActionCreateDocument`（`application/json`，默認文件名 `networkcontrol-rules-yyyyMMdd.json`），在 `MainActivity` 中參考現有的 `RequestVpnConsentAsync` 做法把 `OnActivityResult` 包成 `Task<Android.Net.Uri?>`，用 `ContentResolver.OpenOutputStream` 寫入。
- 導入：`Intent.ActionOpenDocument`（`application/json`、`*/*`）→ 讀全文 → `RuleExportSerializer.Import(json, PathPlaceholderMapper.None)` → `RuleImportPlanner.Plan(...)` 預覽 → 用戶在對話框中選「合併 / 覆寫」並看到增改刪數量（Android 上用應用內覆蓋層對話框，參考詢問卡片 `PromptOverlay` 的實現方式）→ 按計劃調用 `RuleService` 執行（**先增改後刪除**，與 `RulesGrpcService.ApplyAsync` 一致；可以把那段邏輯抽到 Storage 的公開靜態方法中共用，但這屬於越界，需在任務下說明）。
- **跨平台規則**：桌面導出的文件含 Windows 路徑規則和按域名 / IP 的規則，在 Android 上不生效。預覽時單獨統計並提示：「N 條規則在 Android 上不生效（桌面應用路徑或帶連線條件），仍會導入，以便再導出到桌面」。判斷方法：`PackageAppPath.TryGetPackage(rule.App?.PathPattern)` 失敗，或 `RuleSet.FindAppRule` 會跳過的條件（參考 `VpnRoutingPlanner` 中的跳過邏輯）。
- 不做備份恢復界面（Android 上 `ConfigBackup` 也在運行，後續再做）。

### 測試 / 驗收

- `IBackend` 新方法的單元測試不強求（Android 項目沒有測試工程）；把「統計不生效規則」寫成 `Engine.Vpn` 中的純函數並在 `NetworkControl.Engine.Vpn.Tests` 中測試。
- **待用戶在真機驗證**：從手機導出 → 在桌面導入預覽可見；桌面導出 → 手機導入，提示不生效數量。

---

## M3-07 勿擾期間阻斷彙總通知（Core + 桌面 + Android）

**目標**：勿擾模式下不彈窗，按默認動作處理（`FallbackStage` 已實現，原因 `VerdictReason.DoNotDisturb`）。勿擾結束時給一條彙總：「勿擾期間有 5 個應用的 23 次連線被拒絕」，點擊查看記錄並可批量處理。

### 範圍

Core 新建 `Prompts/QuietPeriodTracker.cs`、註冊；`control.proto` 追加；`ControlGrpcService`；桌面 `Services/Notifications.cs`（`BlockedNotifier` 旁新增）；Android `Platform/PromptNotifier.cs` 或新通知類、`IBackend`；界面文本。

### 設計

- `QuietPeriodTracker`（`IActivityConsumer`，非阻塞）：只統計 `Verdict.Reason == DoNotDisturb` 的事件，按應用（AppId / 路徑）聚合次數和動作；同時訂閱 `RuntimeStateService.Changed`，當 `IsDoNotDisturb` 從 true 變為 false（手動關閉）時、或定時器發現已過期時（`DoNotDisturbUntil` 到期**不會觸發** Changed，需要用 `TimeProvider.CreateTimer` 在到期時間點檢查）生成一份 `QuietPeriodSummary(DateTimeOffset From, DateTimeOffset To, IReadOnlyList<(long? AppId, string? Path, string? Name, int Allowed, int Denied)> Apps)` 並清空。沒有任何事件時不生成。
- 摘要保存在內存中最近 1 份（服務重啟丟失可以接受），並通過狀態推送讓界面知道：

```proto
// control.proto
message QuietPeriodApp { optional int64 app_id = 1; string name = 2; string path = 3; int32 allowed = 4; int32 denied = 5; }
message QuietPeriodSummary {
  google.protobuf.Timestamp from = 1;
  google.protobuf.Timestamp to = 2;
  repeated QuietPeriodApp apps = 3;
  string id = 4;    // 界面據此去重，避免重連後重複提示
}
// ServiceStatus 追加
  QuietPeriodSummary last_quiet_period = 12;
// service Control 追加
  rpc DismissQuietPeriod (google.protobuf.Empty) returns (google.protobuf.Empty);
```

- 桌面：`ServiceConnection` 收到帶新 `id` 的摘要時彈 Toast（`ToastService`，參考 `BlockedNotifier`）：標題「勿擾已結束」，正文「5 個應用的 23 次連線被拒絕」，按鈕「查看」→ 打開連線記錄並篩選（`LogNavigation` 追加可選字段 `Reason`，連線記錄頁支持按原因篩選——需要 `LogQuery` 追加 `optional VerdictReason reason = 8;` 並在 `SqliteActivityQuery` 中實現）。關閉或查看後調用 `DismissQuietPeriod`。
- Android：同樣的摘要通過 `IBackend` 暴露（`QuietPeriodSummary? LastQuietPeriod`、`DismissQuietPeriod()`），以普通通知發出（新頻道「勿擾摘要」，低優先級），點擊打開阻擋記錄頁。
- 用戶在勿擾期間被拒絕的應用，摘要對話框中每行提供「允許」快捷操作（桌面：打開預填允許規則的編輯器；Android：直接設為允許）。

### 測試

- Tracker：只統計 DoNotDisturb、按應用聚合、手動關閉和到期兩種結束方式、無事件不生成（`FakeTimeProvider`）。
- 場景測試：開勿擾 → 模擬 3 次連線 → 關勿擾 → `ServiceStatus` 中有摘要 → Dismiss 後消失。
- `LogQuery.reason` 的存儲層測試。

---

## M3-08 入站連線控制（Windows WFP）

**目標**：讓 `Direction = Inbound` 的規則真正生效（目前 `RuleCompiler.CompileRule` 直接跳過並記為 "inbound rules are not enforced yet"），並提供「默認阻止入站」選項。

### 範圍

`Engine.Windows/Compilation/RuleCompiler.cs`、`FilterSpec.cs`、`WfpEngine.cs`（網絡事件中識別入站阻斷）、`Core/Settings`（`UserSettings` 追加字段）、`control.proto`、`SettingsService` 序列化（Storage 的 `StorageDtos`/設置 JSON 是只追加的可選字段）、桌面設定頁、測試 `tests/NetworkControl.Engine.Windows.Tests/`。

### 設計

- 層：`FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4 / V6`（GUID 已在 `WfpNative.cs` 中定義：`LayerAleAuthRecvAcceptV4/V6`）。條件字段與 CONNECT 層相同（`ALE_APP_ID`、`IP_REMOTE_ADDRESS`、`IP_REMOTE_PORT`、`IP_LOCAL_PORT`、`IP_PROTOCOL`），**但含義是對端**：入站時 remote 是發起方，local port 是本機監聽端口。核對 SDK `fwpmu.h` 中這一層可用的條件字段後再寫。
- 規則編譯：`Direction == Inbound` 的規則編到 RECV_ACCEPT 層；`Direction == null`（雙向）的規則**兩層都編**；權重計算與現有出站一致（同一個 `ulong weight`）。
- 默認入站策略：`UserSettings` 追加 `InboundDefault`（`Allow`（默認，保持現狀）| `Deny`）。`Deny` 時在 RECV_ACCEPT 層加一條最低權重的阻斷過濾器，並加內置允許：回環、DHCP 客戶端（UDP 68）、ICMP（受 `AllowIcmpByDefault` 控制）、IPv6 鄰居發現。**入站默認阻止不觸發詢問**（入站連線無法掛起，詢問意義不大），只記錄日誌；在連線記錄中可以「從記錄建規則」放行。
- proto：`UserSettings` 追加 `InboundDefault inbound_default = 7;`，新枚舉 `InboundDefault { INBOUND_DEFAULT_ALLOW = 0; INBOUND_DEFAULT_DENY = 1; }`。
- 網絡事件：阻斷事件頭中有方向（`FWP_DIRECTION_INBOUND`），入站阻斷上報時 `FlowInfo.Direction = Inbound`；默認入站阻斷的事件**不**走詢問（`BlockReason` 追加 `InboundDefault = 2`，`EngineHost.OnFlowBlocked` 中直接記錄）。注意 `BlockReason` 是 Core 公開枚舉：只追加值。
- 規則編輯器已能選方向；規則頁對入站規則不再顯示「此平台不生效」。

### 測試 / 驗收

- `RuleCompiler`：入站規則只生成 RECV_ACCEPT 層、雙向規則生成兩層、出站規則不變；默認入站阻止時的過濾器集合和內置允許。
- **待用戶驗證**（需要管理員）：打開「默認阻止入站」後，局域網另一台機器 ping / 訪問本機共享被拒，加一條入站允許規則後恢復。

---

## M3-09 Android 快速設定磁貼

**目標**：下拉通知欄中的磁貼：點按切換保護開關；長按打開應用（progress-android.md P1）。

### 範圍

`src/NetworkControl.App.Android/Platform/ProtectionTileService.cs`（新建）、圖標資源、`tools/android-strings.py`、`Resources/values*/strings.xml`（磁貼標籤顯示在系統界面中）。

### 設計

- `[Service(Permission = "android.permission.BIND_QUICK_SETTINGS_TILE", Exported = true)]` + `[IntentFilter(["android.service.quicksettings.action.QS_TILE"])]`，繼承 `TileService`。
- `OnStartListening`：通過 `BackendHost.EnsureStartedAsync` 拿到 `IBackend`，按 `Protection` 設置 `Tile.State`（Active / Inactive）、副標題（API 29+ `Tile.Subtitle`：「詢問模式」「已全部封鎖」等），`UpdateTile()`。
- `OnClick`：保護已開 → `SetProtection(false)`；保護已關 → 需要 VPN 授權時（`CreateVpnConsentIntent() != null`）不能在磁貼中彈系統對話框，改為 `StartActivityAndCollapse` 打開 `MainActivity`（API 34+ 需要 `PendingIntent` 版本的重載）並提示授權；已授權則 `SetProtection(true)`。
- 狀態變化時調用 `TileService.RequestListeningState(context, componentName)` 讓系統刷新（在 `Backend.Changed` 中觸發，節流到每秒一次）。
- 長按行為由系統處理：清單中為 `MainActivity` 加 `android.service.quicksettings.action.QS_TILE_PREFERENCES` 的 IntentFilter。

### 驗收

**待用戶在真機驗證**：添加磁貼、點按開關保護、狀態與應用內一致；首次未授權時點按會打開應用。

---

## M3-10 Android 通知文本跟隨應用內語言

**目標**：詢問通知、前台服務通知目前跟隨系統語言（`Resources/values*/strings.xml`），應用內選了其他語言時不一致（progress-android.md 已知限制）。

### 範圍

`Infrastructure/LocalizationService.cs`、`Platform/PromptNotifier.cs`、`Platform/VpnForegroundNotifier.cs`、`Platform/NotificationChannels.cs`、`MainActivity.cs`。

### 設計

- 推薦方案：通知文本也從 AtomUI Catalog 取（與界面同源，已有三種語言），`PromptNotifier` 通過 `LocalizationService` 取當前語言文本；`strings.xml` 只保留系統在應用進程外直接讀取的內容（應用名、磁貼標籤）。
- 同時調用 `LocaleManager.SetApplicationLocales`（API 33+）把應用內選擇同步給系統，讓系統設定中的「應用程式語言」一致；API 29–32 跳過。
- 語言切換時：更新通知頻道名稱（`NotificationChannel.Name` 可以修改）並刷新當前顯示的前台服務通知。
- 沒有界面時（開機啟動）`LocalizationService` 也要能工作：確認它不依賴 Avalonia 已初始化；如果依賴，抽出一個只讀 Catalog 的輕量實現。

### 驗收

系統語言英文、應用內選繁中 → 詢問通知為繁中；切換到簡中後新通知為簡中。**待真機驗證。**

---

## M3-11 Android 診斷資訊導出

**目標**：與桌面診斷包對應，方便用戶反饋問題（progress-android.md P1）。

### 範圍

`App.Android` 新建 `Platform/DiagnosticsExporter.cs`、設定頁「關於」卡片加按鈕、`tools/android-strings.py`。可以復用 `NetworkControl.Ipc/Diagnostics/` 中與平台無關的寫入和脫敏代碼（確認它不依賴 gRPC 類型；如需修改屬於越界，先說明）。

### 設計

zip 內容：`summary.txt`（版本、Android 版本、設備型號、保護狀態、模式、引擎計數器 `VpnEngineCounters`、路由計劃摘要：允許 / 拒絕 / 詢問的應用數和跳過的規則數）、`logs/`（`files/logs` 下最近 3 個日誌文件，按現有脫敏規則處理）、`rules-stats.txt`（規則數按來源 / 動作統計，不含具體包名，除非用戶勾選「包含應用清單」）、`traffic-access.txt`（使用情況存取權狀態）。用 `ActionCreateDocument` 保存（同 M3-06 的 SAF 封裝），或 `Intent.ActionSend` 分享（`FileProvider`，需要在清單中聲明 provider）。推薦先做「保存」，分享作為後續。

### 驗收

**待真機驗證**：導出的 zip 能打開，日誌中的 IP 已脫敏。

---

## M3-12 權限分級提示（桌面）

**目標**：FR-SET-05 已在服務端實現（`IpcPolicies.Admin / Viewer`，`ClientInfo.is_admin`），但 `TreatAllClientsAsAdmin=false` 時普通用戶點「刪除規則」只會得到 gRPC `PermissionDenied` 的錯誤文本。改為界面主動提示。

### 範圍

`src/NetworkControl.App/`：`ServiceConnection`（暴露 `IsAdmin`）、各頁面視圖模型（規則、應用、設定、總覽的寫操作）、`ViewModelBase.RunAsync`（`PermissionDenied` 的友好文本）、界面文本。

### 設計

- `ServiceStatus.client.is_admin`（已有字段 9 → `ClientInfo.is_admin`）。`ServiceConnection` 暴露 `bool CanModify => Status?.Client?.IsAdmin ?? false`。
- 不可修改時：寫操作按鈕禁用並帶 ToolTip「需要管理員權限：以系統管理員身分執行，或在設定中…」；頁面頂部顯示一次 `atom:Alert`（Info）。**回應詢問彈窗不受影響**（服務端本來就允許）。
- `RunAsync` 捕獲 `StatusCode.PermissionDenied` 時顯示本地化文本，而不是服務端的英文詳情。
- 不要在界面中自行提權；提權的入口（以管理員身分重新啟動界面）作為後續。

### 驗收

服務設置 `NetworkControl:Service:TreatAllClientsAsAdmin=false`，以普通用戶啟動界面：寫操作禁用並有說明，詢問彈窗仍可回答（模擬引擎下可驗證，不需要管理員）。

---

## M3-13 檢查更新（桌面，**需要用戶先決定發布源**）

**目標**：啟動時和每天檢查一次新版本，有新版時在設定頁和托盤提示，點擊打開下載頁。**不做自動下載安裝**（需要簽名和提權，後續再做）。

### 待用戶決定

發布源：GitHub Releases（需要公開倉庫）或自建的靜態 JSON（例如 `https://<域名>/networkcontrol/latest.json`）。本設計按「可配置的 JSON 地址」實現，兩種都支持。

### 範圍

`src/NetworkControl.App/Services/UpdateChecker.cs`（新建）、設定頁「關於 / 更新」卡片、托盤菜單、`UiPreferences`（記錄「忽略此版本」）、界面文本。**檢查在界面進程中做**（服務以 SYSTEM 運行，不應該主動聯網）。

### 設計

- 清單格式（源生成 JSON）：`{ "version": "0.4.0", "url": "https://…/download", "notes": { "zh-TW": "…", "zh-CN": "…", "en-US": "…" }, "minimumOs": "10.0.17763" }`。GitHub Releases 時從 `https://api.github.com/repos/<owner>/<repo>/releases/latest` 讀 `tag_name` 和 `html_url`。
- `HttpClient`：**必須尊重系統代理**（與本地 IPC 相反：檢查更新是公網請求，用戶可能只能通過代理上網），User-Agent 用通用的 `NetworkControl/<版本>`，不帶任何用戶信息；超時 10 秒；失敗靜默記日誌。
- 版本比較：`Version.TryParse`，忽略 `+提交哈希` 後綴；開發構建（版本帶 `-dirty` 或 Debug）不檢查。
- 地址配置：`Directory.Build.props` 中新增屬性 `UpdateFeedUrl`（為空則整個功能隱藏），通過 `AssemblyMetadata` 寫進程序集。
- 頻率：啟動後 30 秒檢查一次，之後每 24 小時；設定頁有「立即檢查」按鈕和「自動檢查更新」開關（默認開）。

### 測試

版本比較、清單解析（含缺字段）的單元測試——`NetworkControl.App` 目前沒有測試工程，把純函數放到 `NetworkControl.Ipc`（界面和服務共用的項目）或新建 `tests/NetworkControl.App.Tests`（需要在任務下說明）。

---

## M3-14 應用圖標（桌面）

**目標**：應用頁、即時連線、詢問彈窗、排行榜中顯示程序圖標（目前只有文字）。

### 範圍

`src/NetworkControl.App/Services/AppIconCache.cs`（新建）、使用圖標的視圖和視圖模型、`Infrastructure`。圖標**在界面進程中提取**（界面以用戶身份運行，能讀的文件服務也能讀，但 SYSTEM 下提取 Shell 圖標不可靠）。

### 設計

- Windows：`SHGetFileInfo(path, 0, ref info, size, SHGFI_ICON | SHGFI_LARGEICON)` 拿 `HICON` → `GetIconInfo` + `GetDIBits` 轉成 BGRA 像素 → `Avalonia.Media.Imaging.WriteableBitmap`（32×32）；`DestroyIcon` 釋放。用 `[LibraryImport]`，在後台線程提取，結果回到界面線程。
- 緩存：`ConcurrentDictionary<string, Task<Bitmap?>>`，鍵為不區分大小寫的路徑；最多 512 項（LRU 或簡單清空）。文件不存在、權限不足（tipray 這類安全軟件會拒絕訪問自身文件，見 AGENTS.md 第 6 節）時返回 null，界面顯示默認圖標（AntDesign `AppstoreOutlined`）。
- 綁定：行視圖模型上加 `Bitmap? Icon`，初始為 null，異步加載完成後通知屬性變化；DataGrid 單元格中 `Image Width=20 Height=20`。
- 非 Windows 平台：返回 null（Linux 後續用 freedesktop 圖標主題）。

### 驗收

應用頁中 Chrome、VS Code 等顯示正確圖標；系統目錄程序顯示其圖標；快速滾動不卡頓（提取在後台）。

---

## 附：審查清單（主 agent 使用）

1. 是否只改了範圍內的文件；越界是否有說明。
2. 0.2 硬性約束逐條核對（proto / 遷移只追加、三語文本、DataGrid 數據源、AOT、Core 追加式修改）。
3. 新功能有測試，且測試確實覆蓋了設計中的邊界條件。
4. 0.3 自檢命令在審查機器上重跑一遍（包括 Android 構建）。
5. 用隔離管道 + 模擬引擎跑一次運行時驗證；需要管理員 / 真機的部分在進度文件中如實標為「待用戶驗證」。
6. 進度文件、design.md §22（設計有變化時）、AGENTS.md 第 6 節（發現新坑時）已更新。
