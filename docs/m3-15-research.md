# M3-15 預研：svchost 服務與 MSIX 應用的規則下發

> 狀態：**只做預研，未修改產品代碼**。結論待主 agent / 用戶確認後再實作；確認後由主 agent 把摘要整理進 `docs/design.md` §22。
> 驗證環境：Windows 10 22H2（10.0.19045），Windows SDK 10.0.26100；.NET 10。
> 驗證方式：非管理員可完成的部分都在本機實際執行；需要管理員的部分標為 **「待用戶驗證」**，本文件沒有嘗試提權，也沒有修改防火牆或服務設定。

---

## 0. 結論摘要

1. **服務 SID（`FWPM_CONDITION_ALE_USER_ID`）技術上可行**，但只對「服務 SID 類型為 `UNRESTRICTED` / `RESTRICTED`」且「能確定連線屬於哪一個服務」的情況有效。`NONE` 的服務（例如本機的 `SamSs`、`Themes`）token 中沒有服務 SID，無法用這個條件匹配。
2. **svchost 共享進程是比 SID 類型更大的限制**：同一個進程承載多個服務時，進程 token 會包含這些服務的全部 SID；因此針對其中一個服務的 `ALE_USER_ID` 過濾器會同時匹配同一進程中所有服務的連線。Windows 10 1703 之後大多數服務已拆成獨立進程，但仍有例外（本機實測 `BFE` + `mpssvc` 共用 PID 3448，`RpcSs` + `RpcEptMapper` 共用 PID 1364）。
3. **原設計「MSIX / 商店應用較確定」的判斷不成立**。本機執行中的 27 個 `C:\Program Files\WindowsApps` 進程中，可查詢 token 的 26 個**全部不是 AppContainer**（`TokenIsAppContainer == false`），包括 Windows Terminal、PowerShell、Spotify、ChatGPT/Codex；剩下 1 個是 SYSTEM 服務（`MSPCManagerService.exe`），非管理員無法查詢 token。依 Microsoft 與 Project Zero 的文件，非 AppContainer 進程的 `FWPM_CONDITION_ALE_PACKAGE_ID` 是 **NULL SID `S-1-0-0`**，只用 `ALE_PACKAGE_ID` 的規則對這些應用永遠不會匹配。
4. 全信任（full-trust / `mediumIL`）MSIX 應用需要改用其他條件，候選是 `FWPM_CONDITION_ALE_PACKAGE_FAMILY_NAME` 或 `FWPM_CONDITION_ALE_SECURITY_ATTRIBUTE_FQBN_VALUE`。兩者的實際資料類型和匹配行為**需要用管理員在 WFP 上實測**（見 §8）。
5. **`AppMatcher.PackageId` 目前沒有任何 Windows 代碼會填值**：`WindowsProcessInfoProvider` 只填 PID / 路徑，`WindowsAppInspector` 只處理簽名。建議把 `PackageId` 正式定義為 **Package Family Name（PFN）**，例如 `Microsoft.WindowsTerminal_8wekyb3d8bbwe`，並用 `GetPackageFamilyName` API 填充；不要存完整包名或 AUMID（`PFN!App`）。
6. 建議的實作順序：
   - **A. 先做風險較低的部分**：進程識別填 `ServiceName` / `PackageId`；服務 SID 規則（`UNRESTRICTED` / `RESTRICTED`）；AppContainer 應用用 `ALE_PACKAGE_ID`；不能區分的服務明確跳過並在界面說明。
   - **B. 管理員驗證後再做**：全信任 MSIX 的 `PACKAGE_FAMILY_NAME` / FQBN 條件、`FWPM_NET_EVENT_HEADER2` 的 package SID、阻斷事件歸屬。

---

## 1. 目前實現與資料流

### 1.1 規則編譯

`src/NetworkControl.Engine.Windows/Compilation/RuleCompiler.cs` 現在直接跳過帶服務名或包名的規則：

```csharp
if (rule.App is { ServiceName: not null } or { PackageId: not null })
{
    skipped.Add(new SkippedRule(rule.Id, "service / package matching is not supported by the user-mode engine"));
    return;
}
```

所以在彈窗中對 `wuauserv` 或商店應用建立的規則會保存到數據庫，但不會下發到 WFP。

### 1.2 `AppMatcher.PackageId`

- `AppMatcher.PackageId` 是 `string?`（`src/NetworkControl.Core/Domain/AppMatcher.cs`）。
- `AppMatcherFactory.Create(app)` 會把 `AppIdentity.PackageId` 原樣複製到規則中；`PromptRuleFactory.CreateRule` 用的就是 `AppMatcherFactory.Create`。
- `CompiledRule` 在 `PackageId is not null` 時要求 `matcher.PackageId == app.PackageId`（`OrdinalIgnoreCase`）；`RuleSpecificity` 給帶服務名 / 包名的規則額外分數。
- `AppKey` / `ProcessSnapshot` / `AppIdentity` / proto / 存儲層都有 `PackageId` 字段，序列化鏈路是完整的。

**但目前沒有生產者**：

- `WindowsProcessInfoProvider.TryGetProcess` 只返回 `new ProcessSnapshot(new ProcessRef(...), path)`，`ServiceName` 和 `PackageId` 都是默認 `null`。
- `WindowsAppInspector` 只做 Authenticode / 目錄簽名，不查包身份。
- `StorageDtos` 會持久化非空值，但實際上不會出現。

所以 M3-15 不只改 `RuleCompiler`，還要先補「進程 → 服務 / 包身份」的識別。

### 1.3 `AppMatcher.PackageId` 建議格式

用 **Package Family Name（PFN）**：

```text
<PackageName>_<PublisherId>
例如：Microsoft.WindowsTerminal_8wekyb3d8bbwe
```

理由：

1. `DeriveAppContainerSidFromAppContainerName` 的參數是 AppContainer profile 名稱；對 MSIX 套件而言就是 PFN，不是完整包名，也不是 AUMID。
2. PFN 在套件版本升級時保持不變，適合寫進規則。
3. `GetPackageFamilyName(processHandle, ...)` 直接可用，無需自己從完整包名解析。
4. 不要存 `Microsoft.WindowsCalculator_8wekyb3d8bbwe!App`：`!` 後面是 application ID，`DeriveAppContainerSidFromAppContainerName` 會把它當成名稱的一部分，算出**另一個** SID（本機實測見 §2.3）。

本機用 `GetPackageFamilyName` 實測（非管理員、同用戶進程）：

```text
PID 20748  SpotifyLauncher.exe  -> SpotifyAB.SpotifyMusic_zpdnekdrzrea0
PID 19608  ChatGPT.exe           -> OpenAI.Codex_2p2nqsd0c76g0
PID  9808  WindowsTerminal.exe   -> Microsoft.WindowsTerminal_8wekyb3d8bbwe
```

未打包進程會返回 `APPMODEL_ERROR_NO_PACKAGE`（15700），此時 `PackageId` 保持 `null`。

---

## 2. `FWPM_CONDITION_ALE_PACKAGE_ID`

### 2.1 定義

SDK `Include/10.0.26100.0/um/fwpmu.h`（約第 1230 行）：

```text
// 71BC78FA-F17C-4997-A602-6ABB261F351C
FWPM_CONDITION_ALE_PACKAGE_ID
```

- 值類型：`FWP_SID`（`FWP_VALUE0` / `FWP_CONDITION_VALUE0` 的 `sid` 成員，指向 `SID`）。
- 可用層：`FWPM_LAYER_ALE_AUTH_CONNECT_V4/V6`、`FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4/V6` 等 ALE 層，Windows 8 / Server 2012 起。
- 語義：**socket 建立時捕獲的 AppContainer token 的 package SID**。如果 token 不是 AppContainer，WFP 把 package SID 設為 **NULL SID `S-1-0-0`**（Microsoft 的 UWP 防火牆排錯文件、Project Zero 的文章、Windows 防火牆自身的 `Block Outbound Default Rule` 都使用 `FWPM_CONDITION_ALE_PACKAGE_ID NOT_EQUAL S-1-0-0` 這個寫法）。
- 因此：**`ALE_PACKAGE_ID` 不是「MSIX 包身份」的通用條件，只是「AppContainer package SID」條件。**

### 2.2 值怎麼來

```c
HRESULT DeriveAppContainerSidFromAppContainerName(
    PCWSTR pszAppContainerName,
    PSID*  ppsid);
```

- DLL：`userenv.dll`。
- 參數是 AppContainer profile 名稱；MSIX 套件用 **PFN**。
- 返回的 SID 由 `FreeSid` 釋放（`FreeSid` 在 `advapi32` / `kernel32`）。
- **API 不驗證套件是否存在**。本機實測：

```text
Microsoft.WindowsCalculator_8wekyb3d8bbwe
  -> S-1-15-2-466767348-3739614953-2700836392-1801644223-4227750657-1087833535-2488631167
Microsoft.WindowsCalculator_8wekyb3d8bbwe!App
  -> S-1-15-2-755413700-2163660951-2989968181-1871812904-4095745090-713558074-536293459
nonexistent_1234567890
  -> S-1-15-2-2486650755-2606660380-1520624440-2942696107-1178795955-2010897646-3158039345
```

也就是說：**輸入錯的 PFN、AUMID、甚至不存在的名稱，都會得到一個合法但錯誤的 SID**，規則會「看起來下發成功」但永遠不匹配。實作時必須：

1. 只接受 PFN 格式（最後一個 `_` 後面是 13 字元 PublisherId；不含 `!`、`\`、`/`）。
2. 對來自彈窗的規則，PFN 由 `GetPackageFamilyName` 得到，天然可信。
3. 對導入的規則，建議用 `GetPackageFullName` / `PackageManager` 或 `Get-AppxPackage` 等價 API 檢查套件存在；不存在就跳過並提示（不要默默產生假 SID）。
4. 單元測試要覆蓋「輸入 AUMID 會被拒絕」。

### 2.3 全信任 MSIX 的實測風險

本機（Windows 10 19045）執行中的 WindowsApps 進程：

| 進程 | 是否 AppContainer | 備註 |
|---|---|---|
| WindowsTerminal.exe | 否 | `TokenIsAppContainer == false` |
| pwsh.exe | 否 | 同上 |
| SpotifyLauncher.exe | 否 | 同上 |
| ChatGPT.exe（Codex） | 否 | 同上 |
| 其他 21 個可查詢的 WindowsApps 進程 | 否 | 合計可查詢 26 個，AppContainer 為 0 |
| MSPCManagerService.exe | 無法查詢 | SYSTEM 服務，非管理員無權打開 token |

這是正常的：MSIX 桌面應用（`packagedClassicApp`、`mediumIL`）有 package identity，但**不在 AppContainer 中執行**；`WIN://SYSAPPID` 屬性存在，但 `TokenIsAppContainer` 為 false。依 §2.1 的語義，`ALE_PACKAGE_ID` 對它們是 `S-1-0-0`。

**結論：若只實作 `ALE_PACKAGE_ID`，對 Windows Terminal / Spotify / ChatGPT 這類最常見的商店應用完全無效。** 需要下列替代方案之一（優先順序待管理員實測後決定）：

1. **`FWPM_CONDITION_ALE_PACKAGE_FAMILY_NAME`**
   - GUID：`81BC78FB-F28D-4886-A604-6ACC261F261B`（SDK `fwpmu.h`，`NTDDI_WIN10_CU` / Windows 10 1703 起）。
   - 文件說「The package family name of the app」，**但資料類型寫成 `FWP_SECURITY_DESCRIPTOR_TYPE`**，與「family name 是字串」矛盾，疑似文件錯誤。可能的實際型別是 `FWP_UNICODE_STRING_TYPE` 或 `FWP_BYTE_BLOB`（UTF-16）。**不要直接照文件實作，先做 §8 的實測。**
   - 若驗證成功，這是最直接的解法：同一條規則可同時覆蓋 AppContainer 與 full-trust 的 MSIX 應用。
2. **`FWPM_CONDITION_ALE_SECURITY_ATTRIBUTE_FQBN_VALUE`**
   - GUID：`37A57699-5883-4963-92B8-3E704688B0AD`（Windows 10 起）。
   - 值類型：`FWP_BYTE_BLOB`。
   - 針對 token 的 security attribute；套件進程的 `WIN://SYSAPPID` 屬性包含 PFN / application id / 完整包名（例如 `Microsoft.WindowsTerminal_1.24.11911.0_x64__8wekyb3d8bbwe`、`App`、`Microsoft.WindowsTerminal_8wekyb3d8bbwe`）。這個屬性對 AppContainer 與 full-trust 套件進程都存在。
   - 需要確認 blob 的精確格式（是 attribute 名稱 `WIN://SYSAPPID`、屬性的某個 value，還是 `CLAIM_SECURITY_ATTRIBUTE_FQBN_VALUE` 結構），以及 match 時是「任一 value 相等」還是別的行為。**待管理員實測。**
3. **退回按可執行文件路徑控制**：對 full-trust MSIX 而言，現有的 `ALE_APP_ID` 路徑規則完全有效；缺點是套件更新可能改變路徑（`WindowsApps\<Name>_<Version>_...`），但 `PathExpander` / 版本通配符已經能處理一部分。

### 2.4 阻斷事件中的包身份

產品目前用 `FWPM_NET_EVENT1`（`FwpmNetEventSubscribe0`）和 `FWPM_NET_EVENT_HEADER1`；HEADER1 **沒有 package SID**。SDK 中：

- `FWPM_NET_EVENT_HEADER2` 多了 `packageSid`，Windows 8 起；
- 對應的 `FWPM_NET_EVENT2` / `FwpmNetEventSubscribe1`；
- header flag `FWPM_NET_EVENT_FLAG_PACKAGE_ID_SET`（`0x00000400`）表示 package SID 有效。

若需要從事件直接知道包身份，要改用 `FwpmNetEventSubscribe1` + HEADER2，並同步更新 `InteropLayoutTests`。**但更推薦用「filterId → FilterSpec」映射**：本產品的 `_installedSnapshot` 已經保存每個過濾器的 `FilterSpec`（含 `RuleId`）；事件中的 `FilterId` 可以確定是哪條規則、哪個服務 / 包攔下的，比解析 token 更可靠，尤其在共享 svchost 的情況下。

---

## 3. `FWPM_CONDITION_ALE_USER_ID` 與服務 SID

### 3.1 定義與匹配語義

SDK `fwpmu.h`（約第 1880 行）：

```text
// AF043A0A-B34D-4F86-979C-C90371AF6E66
FWPM_CONDITION_ALE_USER_ID
```

- 值類型：`FWP_SECURITY_DESCRIPTOR_TYPE`，`FWP_VALUE0.sd` 指向一個 `FWP_BYTE_BLOB`，內容是**自相對（self-relative）格式的 `SECURITY_DESCRIPTOR`**。
- 可用層：`ALE_AUTH_CONNECT` / `ALE_AUTH_RECV_ACCEPT` 等，Windows 7 起。
- 匹配方式：WFP 用 socket 建立時捕獲的 token 對這個安全描述符做存取檢查，檢查的權限是 **`FWP_ACTRL_MATCH_FILTER`（0x00000001）**。DACL 授予該權限 → 條件成立；拒絕 → 條件不成立。**這只是條件匹配，不代表允許流量**；真正的放行 / 阻斷由 filter action 決定。
- 因此安全描述符只需要一個 ACE：

```text
D:(A;;CC;;;<SID>)
```

`CC` 對應的 access mask 就是 `0x1`（`FWP_ACTRL_MATCH_FILTER`）。Windows 防火牆自己的服務規則使用類似 `O:SYG:SYD:(A;;CCRC;;;S-1-5-80-...)` 的形式（多一個 `RC` = READ_CONTROL），但對條件匹配而言 `CC` 已經足夠。

### 3.2 服務 SID

- 服務 SID 是 `NT SERVICE\<服務短名>`，例如 `NT SERVICE\wuauserv`。
- 可用 `LookupAccountNameW(null, L"NT SERVICE\\wuauserv", ...)` 取得；本機實測成功。
- **不要用 `sc showsid` 驗證服務存在**：本機實測 `sc showsid NoSuchServiceXyz` 也會返回一個合法 SID。`LookupAccountNameW` 對不存在的服務會失敗，較適合驗證。
- `SERVICE_SID_TYPE`（`sc qsidtype <name>`，`QueryServiceConfig2(SERVICE_CONFIG_SERVICE_SID_INFO)`）：
  - `UNRESTRICTED`（0x1）：服務 SID 加入進程 token 的普通 group 清單。
  - `RESTRICTED`（0x3）：包含 `UNRESTRICTED` 位；服務 SID 同時加入普通與 restricted SID 清單，另外加入 World、service logon SID、WRITE_RESTRICTED。
  - `NONE`（0x0）：token 中沒有服務 SID，`ALE_USER_ID` 無法匹配。

`RESTRICTED` 的服務 SID 也在 restricted SID 清單中，所以 `D:(A;;CC;;;<服務 SID>)` 的 DACL 對普通和 restricted 兩種存取檢查都授予相同 SID，理論上可以匹配。**這點需要在真實 RESTRICTED 服務上驗證**（本機 `DPS` 是 `RESTRICTED`，但沒有在管理員下做 WFP 過濾器實驗）。

### 3.3 本機 `sc qsidtype` 實測

在 Windows 10 19045、非管理員執行（`sc qsidtype` 不需要提權）：

| 服務 | SID 類型 |
|---|---|
| `wuauserv`（Windows Update） | UNRESTRICTED |
| `Dnscache`（DNS Client） | UNRESTRICTED |
| `Dhcp` | UNRESTRICTED |
| `Schedule`（Task Scheduler） | UNRESTRICTED |
| `EventLog` | UNRESTRICTED |
| `Winmgmt`（WMI） | UNRESTRICTED |
| `BITS` | UNRESTRICTED |
| `W32Time` | UNRESTRICTED |
| `LanmanServer` / `LanmanWorkstation` | UNRESTRICTED |
| `Spooler` | UNRESTRICTED |
| `CryptSvc` | UNRESTRICTED |
| `TrustedInstaller` | UNRESTRICTED |
| `RpcSs`、`DcomLaunch`、`BrokerInfrastructure`、`CDPSvc`、`NlaSvc`、`netprofm`、`iphlpsvc`、`Audiosrv`、`WdiServiceHost`、`WdiSystemHost` | UNRESTRICTED |
| `DPS`（Diagnostic Policy Service） | RESTRICTED |
| `SamSs`（Security Accounts Manager） | NONE |
| `Themes` | NONE |

結論：**大多數常見 svchost 服務是 UNRESTRICTED，服務 SID 方案對 `wuauserv`、`Dnscache` 等主要目標是可行的**；`SamSs`、`Themes` 這類 NONE 服務不行。`DPS` 這種 RESTRICTED 服務理論上可行，但要實測。

### 3.4 共享 svchost 進程的限制（重要）

- 服務進程的 token 由 SCM 建立，**同一進程承載的所有服務的 SID 都會加入 token**。所以：

  ```text
  svchost.exe (PID X) 同時承載 A、B 兩個 UNRESTRICTED 服務
  token = { ..., NT SERVICE\A, NT SERVICE\B, ... }
  filter: ALE_USER_ID = D:(A;;CC;;;NT SERVICE\A)
  → A 和 B 的連線都會匹配
  ```

- 這不是理論問題：本機實測（非管理員，`Win32_Service`）：

```text
PID 1240  BrokerInfrastructure, DcomLaunch, PlugPlay, Power, SystemEventsBroker
PID 1108  EFS, KeyIso, SamSs, VaultSvc
PID 3448  BFE, mpssvc
PID 1364  RpcEptMapper, RpcSs
PID 6464  NetTcpActivator, NetTcpPortSharing
PID 6316  W3SVC, WAS
```

- Windows 10 1703 起，記憶體大於 3.5 GB 的用戶端會把多數服務拆成獨立進程，但：
  - `SvcHostSplitDisable=1` 的服務仍會共享；
  - 記憶體 ≤ 3.5 GB 或 Server SKU 可能維持共享；
  - 系统关键服务（BFE + mpssvc、RPC）仍是共享。
- 因此「服務 SID 匹配」≠「精確識別服務」：只有在**該 PID 只有這一個服務**（或使用者的規則明確接受整個共享群組）時才精確。

### 3.5 連線歸屬（事件 → 服務）

- 產品目前從 `FWPM_NET_EVENT_HEADER1.UserId` 取一個 SID 字串。對啟用服務 SID 的進程，這個欄位**可能**是服務 SID，但共享進程的 token 含多個服務 SID，WFP 只會填其中一個（哪一個沒有文件保證）；不能據此斷定「就是這個服務」。
- **建議：用 `FilterId` 反查 `FilterSpec`。** 被阻斷的事件是由某個具體過濾器產生的；`_installedSnapshot`（或改成 `Dictionary<ulong, FilterSpec>`）可以直接給出 `RuleId`、`ServiceName`、`PackageId`。這在共享 svchost 下是唯一可靠的歸屬方式。
- PID → 服務枚舉仍可作為補充：
  - `EnumServicesStatusEx` / `QueryServiceStatusEx` 或 SCM API 枚舉同一 PID 的服務；
  - 只有一個 → 可確定；
  - 多個 → 記為「svchost（多個服務）」，不要猜。
- 顯示名：`QueryServiceConfig` 的 `lpDisplayName`（例如「Windows Update」）；`FlowInfo` 不用改，填 `AppIdentity.DisplayName`。

---

## 4. Windows 防火牆「服務」規則的做法

### 4.1 可觀察到的行為

非管理員可以讀 `netsh advfirewall firewall show rule`（本機實測）：

```text
netsh advfirewall firewall show rule name=all verbose
```

- 輸出中的 `服务:` 字段就是服務短名（大小寫不敏感），例如 `sharedaccess`、`dnscache`、`schedule`、`policyagent`、`RpcSs`。
- 本機共有 **409 條**帶服務字段的規則，去重後 **44 個**服務短名；這證明 Windows 防火牆確實按服務短名建模。
- `netsh` 的規則輸出**沒有包（package）字段**；`Get-NetFirewallRule` / `Get-NetFirewallServiceFilter` 在非管理員下返回 `Access denied`（本機實測），所以結構化查詢和 WFP 過濾器細節需要管理員。

### 4.2 官方文件

Microsoft「Configure Firewall Rules With Group Policy」明確寫：

- 服務規則的「Apply to this service」要求服務的 SID 類型是 **RESTRICTED 或 UNRESTRICTED**；
- `sc qsidtype` 結果為 `NONE` 時，**防火牆規則無法套用到該服務**；
- 可以用 `sc sidtype <service> <UNRESTRICTED|RESTRICTED>` 修改，但需要重啟且可能影響服務啟動。

這與 §3 的結論一致。

### 4.3 底層 WFP 形式（待驗證）

- Windows 防火牆的服務規則一般建在 `FWPM_SUBLAYER_MPSSVC_WF` 子層，條件包含：
  - `FWPM_CONDITION_ALE_APP_ID` = 服務宿主（通常是 `%SystemRoot%\System32\svchost.exe`）；
  - `FWPM_CONDITION_ALE_USER_ID` = `FWP_SECURITY_DESCRIPTOR_TYPE`，SD 類似 `O:SYG:SYD:(A;;CCRC;;;S-1-5-80-...)`（Cisco Secure Workload 的 WFP 文檔給出了實際範例）。
- **待用戶驗證**：用管理員執行 `netsh wfp show filters`，搜索 `FWPM_CONDITION_ALE_USER_ID` / `FWPM_SUBLAYER_MPSSVC_WF` / `S-1-5-80-`，確認服務規則的實際條件組合和 SD 格式。

---

## 5. 建議的實現方案（待確認後再做）

### 5.1 第一階段：服務 SID + AppContainer 包 + 進程識別

1. **`WindowsProcessInfoProvider`**
   - 用 `GetPackageFamilyName`（`kernel32`，process handle）填 `ProcessSnapshot.PackageId`；`APPMODEL_ERROR_NO_PACKAGE` → `null`。
   - 對 `svchost.exe`：枚舉同一 PID 的服務；只有一個 → `ServiceName = <短名>`；多個 → `ServiceName = null`，另外記錄一個「多服務」標記供界面 / 提示使用（不要用假的服務名污染 `AppKey`）。
   - 其他進程不填服務名。
   - 加兩個純函數 / 單元測試（PID → 服務清單的格式化、PFN 校驗）。
2. **`RuleCompiler` / `FilterSpec`**
   - `FilterSpec` 追加可選字段（要參與 `Key`）：
     - `byte[]? PackageSid`（或 `string? PackageFamilyName` + `byte[]? ServiceSid`）；
     - `string? ServiceName`（僅用於診斷 / 事件映射）。
   - 服務規則：
     - 若 `ServiceName` 為空或 `sc` 查不到 / SID 類型 `NONE` → `SkippedRule`，原因代碼要能讓界面顯示「此服務不支持單獨控制」。
     - 否則用 `LookupAccountNameW("NT SERVICE\\" + name)` 取得 SID，構造自相對 SD（`ConvertStringSecurityDescriptorToSecurityDescriptorW("D:(A;;CC;;;<SID>)")` 或 `BuildSecurityDescriptorW`），加入 `ALE_USER_ID` 條件；同時保留 `ALE_APP_ID = svchost.exe`（規則的 `App.PathPattern` 或 `Environment.SystemDirectory`）。
     - `FWP_VALUE0` 的 `Type = FWP_SECURITY_DESCRIPTOR_TYPE(14)`；`Pointer` 指向 `FWP_BYTE_BLOB { size, data }`，blob 的生命週期只需覆蓋 `FwpmFilterAdd0`。
   - 包規則（AppContainer）：
     - 用 PFN 校驗後 `DeriveAppContainerSidFromAppContainerName`；`FilterSpec` 加 `ALE_PACKAGE_ID`（`FWP_SID(13)`）。
     - 不再加 `ALE_APP_ID`（同一包可能有多個 exe），符合原設計。
   - `WfpNative`：追加 `ConditionAlePackageId`、`ConditionAleUserId`、`ConditionAlePackageFamilyName`、`ConditionAleSecurityAttributeFqbnValue` 的 GUID；`FwpDataType` 追加 `Sid = 13`、`SecurityDescriptor = 14`。
   - **`InteropLayoutTests` 不用改結構體大小**（`FwpValue0` 大小不變）；但要為新 GUID / 條件值寫純函數 / 序列化測試。
3. **不可區分的服務**
   - SID 類型 `NONE`：跳過，界面提示「此服務未啟用服務 SID，無法單獨控制；可改為控制整個 `svchost.exe`」。
   - 共享進程多服務：跳過或在用戶明確確認後改成「作用於共用 svchost 的 N 個服務」的群組規則；默認不要靜默放寬。
   - 服務不存在 / 未安裝：跳過並提示。
4. **事件歸屬**
   - `_installed` / `_installedSnapshot` 追加 `FilterId → FilterSpec` 映射；`HandleDrop` 先用 `FilterId` 找規則，找不到再用 header 的 AppId / UserId。
   - 這樣即使共享 svchost，也能知道是哪一條規則命中（但不代表能知道是哪個服務主動發起，除非規則本身已限定單服務）。

### 5.2 第二階段（管理員實測後）

1. 驗證 `FWPM_CONDITION_ALE_PACKAGE_FAMILY_NAME` 的資料類型和匹配行為；如果能用，作為全信任 MSIX 的首選，與 `ALE_PACKAGE_ID` 二選一或同時產生兩種過濾器。
2. 或驗證 `FWPM_CONDITION_ALE_SECURITY_ATTRIBUTE_FQBN_VALUE` 的 blob 格式；用它匹配 `WIN://SYSAPPID`。
3. 如需從事件直接取 package SID，改用 `FwpmNetEventSubscribe1` + `FWPM_NET_EVENT_HEADER2`。
4. 在界面中顯示「包身份」和「服務身份」的來源與限制；導入規則時對不存在的包 / 服務給出明確結果。

---

## 6. 不能區分的服務如何處理（建議）

| 情況 | 能否精確控制 | 建議 |
|---|---|---|
| 服務 SID 類型 `UNRESTRICTED`，且進程只有這一個服務 | 可以 | 正常下發 `ALE_USER_ID` 條件 |
| 服務 SID 類型 `RESTRICTED`，且進程只有這一個服務 | 理論上可以（待實測） | 先實作；用 `D:(A;;CC;;;<SID>)`，在測試中驗證 |
| 服務 SID 類型 `NONE` | 不可以 | 跳過；界面提示，提供「控制整個 svchost」的替代選項（需用戶確認） |
| 同一 svchost 承載多個服務 | 不可以（規則會作用到整個進程） | 默認跳過；或用戶明確選擇「整個群組」後才下發，界面列出受影響的服務 |
| 服務已停止 / 未安裝 | 不確定 | 跳過並提示；不要留下無效規則 |
| 服務 SID 類型在規則建立後被修改 | 規則可能失效 | 編譯時重新查詢；界面 / 日誌提示需要重新檢查；不要依賴緩存的 SID 類型 |

---

## 7. 風險

1. **假 SID**：`DeriveAppContainerSidFromAppContainerName` 和 `sc showsid` 都接受任意名稱並返回合法 SID；不校驗會導致規則「成功但永不匹配」。這是最容易被忽略的風險。
2. **全信任 MSIX**：只做 `ALE_PACKAGE_ID` 對本機可查詢的 26/26 個商店應用進程無效；必須有 PFN / FQBN 或路徑方案。
3. **共享 svchost 誤傷**：按服務 SID 的允許規則可能放行同一進程中其他服務的流量；阻斷規則也可能阻斷整個群組。必須在界面和文檔中說明。
4. **Windows 版本差異**：`ALE_PACKAGE_ID` 是 Win8+，`PACKAGE_FAMILY_NAME` 是 Win10 1703+，`FQBN` 是 Win10+；產品要按 `OperatingSystem.IsWindowsVersionAtLeast` 分級，避免在舊系統下發不支持的條件。
5. **服務 SID 變更需要重啟**：`sc sidtype` 的變更在下次啟動才生效；測試和用戶提示要考慮。
6. **事件歸屬**：`FWPM_NET_EVENT_HEADER1.UserId` 在共享 svchost 下不能確定具體服務；必須用 filterId 映射或 HEADER2。
7. **文檔錯誤**：`PACKAGE_FAMILY_NAME` 的資料類型在 Microsoft 文件中標為 `FWP_SECURITY_DESCRIPTOR_TYPE`，與名稱矛盾；`FQBN` 的 blob 格式也沒有範例。兩者都必須先實測再實作。

---

## 8. 「待用戶驗證」清單（需要管理員）

以下實驗可以用本任務新增的 `tests/NetworkControl.Engine.Windows.IntegrationTests` 作為骨架（它是獨立子層、獨立會話，不會碰正式安裝的服務）：

1. **全信任 MSIX + `ALE_PACKAGE_ID`**：對一個 full-trust 商店應用（例如 Windows Terminal / ChatGPT）的 PID 用 `GetPackageFamilyName` 取 PFN，派生 SID，下發 `ALE_PACKAGE_ID` 阻斷規則，確認連線**不被阻斷**（預期是 S-1-0-0，不匹配）。
2. **`ALE_PACKAGE_FAMILY_NAME`**：用 `FWP_UNICODE_STRING_TYPE`、`FWP_BYTE_BLOB`（UTF-16LE + NUL）等候選型別分別嘗試 `FwpmFilterAdd0`；記錄哪個被接受、哪個能真正匹配。同時用 `netsh wfp show filters` 觀察 Windows 防火牆自身的 package 規則（如果有）。
3. **`ALE_SECURITY_ATTRIBUTE_FQBN_VALUE`**：構造 `WIN://SYSAPPID` 的候選 blob（attribute 名稱、value[0] / value[2] 等），確認匹配行為。
4. **服務 SID 阻斷**：以獨立子層 + `ALE_APP_ID=svchost.exe` + `ALE_USER_ID`（`wuauserv` 的 SID）阻斷，啟動 Windows Update 或手動觸發更新，確認：
   - `wuauserv` 的連線被阻斷；
   - 同一 PID 的其他服務（若有）是否也被阻斷；
   - `DPS`（RESTRICTED）是否如預期匹配；
   - `SamSs` / `Themes`（NONE）是否完全不受影響。
5. **共享 svchost**：找一組共享 PID（本機的 `BFE` + `mpssvc`，PID 3448；或 `RpcSs` + `RpcEptMapper`，PID 1364），確認針對其中一個服務的過濾器是否同時匹配另一個。**不要對 BFE / mpssvc 做阻斷實驗，會斷網；用允許型過濾器 + 日誌 / 計數觀察即可。**
6. **事件 header**：在服務阻斷事件中打印 `UserId` / `Flags`；在包阻斷事件中用 `FwpmNetEventSubscribe1` 檢查 `Header2.packageSid` 和 `FWPM_NET_EVENT_FLAG_PACKAGE_ID_SET`。
7. **`netsh wfp show filters`**：確認 Windows 防火牆服務規則的 `ALE_USER_ID` SD 形式，以及是否存在 `FWPM_CONDITION_ALE_PACKAGE_FAMILY_NAME` / `FQBN` 的內建規則可參考。
8. **Windows 11**：重複 1–3（Windows 11 對 package / AppContainer 的處理可能不同）。

---

## 9. 參考資料

- SDK `Include/10.0.26100.0/um/fwpmu.h`：`FWPM_CONDITION_ALE_PACKAGE_ID`（71BC78FA-…）、`FWPM_CONDITION_ALE_USER_ID`（AF043A0A-…）、`FWPM_CONDITION_ALE_PACKAGE_FAMILY_NAME`（81BC78FB-…）、`FWPM_CONDITION_ALE_SECURITY_ATTRIBUTE_FQBN_VALUE`（37A57699-…）、`FwpmFilterAdd0` / `FwpmFilterEnum0`。
- SDK `Include/10.0.26100.0/shared/fwptypes.h`：`FWP_DATA_TYPE`（`FWP_SID = 13`、`FWP_SECURITY_DESCRIPTOR_TYPE = 14`）、`FWP_ACTRL_MATCH_FILTER = 0x1`。
- SDK `Include/10.0.26100.0/shared/fwpmtypes.h`：`FWPM_NET_EVENT_HEADER1/2`、`FWPM_NET_EVENT_FLAG_PACKAGE_ID_SET = 0x400`、`FWPM_FILTER_ENUM_TEMPLATE0`。
- Microsoft Learn：
  - [Filtering condition identifiers](https://learn.microsoft.com/windows/win32/fwp/filtering-condition-identifiers-)
  - [Filtering conditions available at each filtering layer](https://learn.microsoft.com/windows/win32/fwp/filtering-conditions-available-at-each-filtering-layer)
  - [Permitting and Blocking Applications and Users](https://learn.microsoft.com/windows/win32/fwp/permitting-and-blocking-applications-and-users)
  - [Configure Firewall Rules With Group Policy](https://learn.microsoft.com/windows/security/operating-system-security/network-security/windows-firewall/configure)
  - [SERVICE_SID_INFO](https://learn.microsoft.com/windows/win32/api/winsvc/ns-winsvc-service_sid_info)
  - [Troubleshooting UWP App Connectivity Issues in Windows Firewall](https://learn.microsoft.com/windows/security/operating-system-security/network-security/windows-firewall/troubleshooting-uwp-firewall)
- Project Zero, [Understanding Network Access in Windows AppContainers](https://googleprojectzero.blogspot.com/2021/08/understanding-network-access-windows-app.html)：非 AppContainer 的 `ALE_PACKAGE_ID` 為 NULL SID。
- Cisco Secure Workload 文檔中的 WFP 過濾器範例：`ALE_USER_ID` 的 `FWP_SECURITY_DESCRIPTOR_TYPE` / `O:SYG:SYD:(A;;CCRC;;;S-1-5-80-...)`。

---

## 10. 本機驗證記錄（摘要）

| 項目 | 結果 | 是否需要管理員 |
|---|---|---|
| `sc qsidtype` 常見服務 | 見 §3.3；多數 UNRESTRICTED，`DPS` RESTRICTED，`SamSs` / `Themes` NONE | 否 |
| 執行中的 WindowsApps 進程 | 27 個；可查詢的 26 個全是非 AppContainer（另 1 個 SYSTEM 進程無法查詢） | 否 |
| `DeriveAppContainerSidFromAppContainerName` | 對不存在名稱 / AUMID 也返回合法 SID | 否 |
| `GetPackageFamilyName` | 可從進程 handle 取得 PFN；未打包返回 15700 | 否 |
| `netsh advfirewall firewall show rule` | 可讀；409 條服務規則、44 個服務短名 | 否 |
| `Get-NetFirewallRule` / `Get-NetFirewallServiceFilter` | Access denied | 是 |
| `netsh wfp show filters` | Access denied（ERROR_ACCESS_DENIED） | 是 |
| WFP 條件值 / 匹配實驗 | 未執行 | 是 |

> 再次強調：本預研沒有修改產品代碼、沒有修改 `docs/design.md` §22、沒有改動防火牆或服務設定。等主 agent / 用戶確認方案後，再把 §0 / §5 / §6 的結論整理進 `design.md` §22 並開始實作。
