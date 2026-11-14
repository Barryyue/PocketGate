# Windows M3 批次交接（M3-17 + M3-15 預研）

> 本文件是子 agent 的交接記錄。按任務約束，**沒有修改** `docs/progress.md`、`docs/m3-plan.md`、`docs/design.md`，也沒有合併 `main` 或 push。主 agent 確認後可把 M3-17 的完成情況補進 `progress.md`，並把 M3-15 的預研摘要整理進 `design.md` §22。

## 分支與提交

- 分支：`agent/deepseek/m3-windows-batch`（從 `main` 的 `7faca6b` 建立）
- 提交：
  1. `25b1e02` `test(windows): add WFP administrator integration tests`（M3-17）
  2. `286572f` `docs(m3-15): research svchost service and MSIX rule delivery`（M3-15 預研）
  3. `aa1f533` `docs(handoff): record M3-17 verification and M3-15 research`
  4. `6c90866` `test(windows): tolerate asynchronous dynamic-session cleanup`（M3-17 穩定性）
  5. 本文件的後續修正（見 `git log`）

## M3-17 WFP 管理員集成測試

### 新增項目

`tests/NetworkControl.Engine.Windows.IntegrationTests/`，已加入 `NetworkControl.slnx`。測試：

| 測試 | 內容 |
|---|---|
| `WfpSessionTests.BlockFilter_BlocksLoopbackConnect_DeletingRestores` | 動態會話 + 阻斷 `127.0.0.2:<隨機端口>` 的 TCP 出站過濾器 → `TcpClient` 連接失敗；刪除過濾器 → 連接成功 |
| `WfpSessionTests.DisposingSession_RemovesFilters` | 會話 Dispose 後用獨立引擎句柄 + `FwpmFilterCreateEnumHandle0` / `FwpmFilterEnum0` 按提供程序 / 子層確認過濾器數量為 0 |
| `WfpSessionTests.FilterEnumerationTemplate_LayoutMatchesSdk` | 測試專用 `FWPM_FILTER_ENUM_TEMPLATE0` 的 x64 佈局為 72 字節 |
| `RulePushTests.IncrementalPush_OnlyAddsAndRemovesChangedFilters` | `RuleCompiler` 編譯兩套規則集，模仿 `WfpEngine` 的差異算法：只增加新規則、刪除消失的規則，未變化的過濾器保留同一個 `filterId` |
| `NetEventTests.BlockedConnection_RaisesClassifyDropEventWithinFiveSeconds` | 訂閱 WFP 網絡事件，阻斷連接後在 5 秒內收到對應 `FilterId` 的 `ClassifyDrop` |

### 安全與隔離

- 每個測試使用 **隨機的獨立 provider / sublayer GUID** 和獨立會話名；子層權重 `0xFFFF`，不碰正式安裝的 `NetworkControl` 子層。
- 只使用 `127.0.0.2` 回環地址；不連接外網、不改 Windows 防火牆規則、不調用正式服務的 IPC 管道。
- 所有會話在 `using` 中打開；事件訂閱在 `Dispose` 中先 `FwpmNetEventUnsubscribe0`，再關閉會話。
- 網絡事件測試會臨時把全局 `FWPM_ENGINE_COLLECT_NET_EVENTS` 設為 1，`finally` 恢復原值。
- 非管理員（或非 Windows）時，**5 個測試全部 `Assert.Skip("需要管理員權限")`，零失敗**。

### 對產品代碼的最小改動

為了讓測試使用獨立子層，`src/NetworkControl.Engine.Windows/Interop/WfpSession.cs` 的 `Open` 追加了可選參數：

```csharp
public static WfpSession Open(
    string displayName,
    Guid? providerKey = null,
    Guid? subLayerKey = null,
    ushort subLayerWeight = 0x8000)
```

原有調用 `WfpSession.Open("NetworkControl")` 行為完全不變（使用原來的 `ProviderKey` / `SubLayerKey` / 權重 `0x8000`）。同時在 `NetworkControl.Engine.Windows.csproj` 追加了最小化的 `InternalsVisibleTo`：

```xml
<InternalsVisibleTo Include="NetworkControl.Engine.Windows.IntegrationTests" />
```

除此之外沒有修改產品代碼。

### 驗證結果

已在本機（非管理員）執行：

- `dotnet build NetworkControl.slnx`：**0 警告、0 錯誤**。
- `dotnet test --solution NetworkControl.slnx`：**371 個測試，365 通過，6 跳過，0 失敗**（其中 5 個是本項目跳過，1 個是原有的 Linux 符號鏈接測試跳過）。
- `dotnet test --project tests/NetworkControl.Engine.Windows.IntegrationTests/...`：**5 個全部 Skip，0 失敗**。
- `dotnet format NetworkControl.slnx --verify-no-changes --exclude src/NetworkControl.App.Android src/NetworkControl.Engine.Android`：通過。

**未驗證（需要管理員）**：實際開 WFP 動態會話、阻斷 / 恢復回環連接、過濾器清理、增量下發和 `ClassifyDrop` 事件。請以管理員身份執行：

```powershell
dotnet test --project tests\NetworkControl.Engine.Windows.IntegrationTests\NetworkControl.Engine.Windows.IntegrationTests.csproj
```

預期 5 個測試全部通過。執行後可用管理員執行 `netsh wfp show filters`，搜索測試會話名（`NetworkControl IntegrationTests`）確認沒有殘留過濾器；動態會話本身也會在進程退出時由 BFE 自動清理。

**可能的風險**：Windows 防火牆或其他安全軟件在回環上的放行過濾器若優先級高於測試子層，測試 1/4 可能失敗。若真的出現，請把失敗輸出（`SocketException` 錯誤碼、`netsh wfp show filters` 中的測試過濾器）保留下來，這是需要調整測試子層權重或改用其他回環方式的信號，而不是產品 bug。目前測試子層已使用最大權重 `0xFFFF`。

## M3-15 預研摘要

完整內容見 [m3-15-research.md](m3-15-research.md)。要點：

1. **`FWPM_CONDITION_ALE_PACKAGE_ID`（FWP_SID）只匹配 AppContainer**。非 AppContainer 的 token 得到 NULL SID `S-1-0-0`。本機可查詢的 26 個 `WindowsApps` 進程中只有 2 個是 AppContainer，其餘 24 個（Windows Terminal、PowerShell、Spotify、ChatGPT 主進程等）都是 full-trust，所以原設計「MSIX 較確定」不成立。套件 AppContainer 的 PFN → SID 派生已實測正確（`Video.UI`），可用；但 full-trust 和自訂 AppContainer profile 不行。
2. **`DeriveAppContainerSidFromAppContainerName` 不驗證輸入**：任意名稱 / `PFN!App` / 不存在的包都會返回合法但錯誤的 SID。`PackageId` 必須嚴格限定為 PFN（`<Name>_<13 字元 PublisherId>`），並且對導入規則檢查包是否存在。
3. **`FWPM_CONDITION_ALE_USER_ID`（安全描述符）可行**：DACL 用 `D:(A;;CC;;;<服務 SID>)`，`CC` 對應 `FWP_ACTRL_MATCH_FILTER`（0x1）；只對 `UNRESTRICTED` / `RESTRICTED` 服務有效，`NONE` 服務（本機 `SamSs`、`Themes`）無法匹配。
4. **共享 svchost 進程是更大的限制**：同一進程承載多個服務時，token 包含全部服務 SID，按其中一個服務的過濾器會匹配整個群組。本機仍有 `BFE` + `mpssvc`（PID 3448）、`RpcSs` + `RpcEptMapper`（PID 1364）等共享群組。
5. **`AppMatcher.PackageId` 目前沒有生產者**：`WindowsProcessInfoProvider` / `WindowsAppInspector` 都不填；建議正式定義為 PFN，並用 `GetPackageFamilyName` 填充。
6. **全信任 MSIX 的替代條件**：`FWPM_CONDITION_ALE_PACKAGE_FAMILY_NAME`（Win10 1703+）或 `FWPM_CONDITION_ALE_SECURITY_ATTRIBUTE_FQBN_VALUE`（Win10+）。兩者的資料類型 / blob 格式都需要管理員實測；`PACKAGE_FAMILY_NAME` 的 Microsoft 文件資料類型（`FWP_SECURITY_DESCRIPTOR_TYPE`）疑為錯誤。
7. **事件歸屬**：`FWPM_NET_EVENT_HEADER1` 沒有 package SID；建議用 `FilterId → FilterSpec` 映射，比解析 token 可靠；若要 package SID 則改用 `FwpmNetEventSubscribe1` + `HEADER2`。
8. **不能區分的服務**：`NONE` 類型、共享進程多服務、服務不存在都應跳過並在界面提示；共享進程可讓用戶明確選擇「控制整個 svchost 群組」，默認不要靜默放寬。

### M3-15 待用戶驗證（管理員）

- 全信任 MSIX 用 `ALE_PACKAGE_ID` 是否確實不匹配（預期不匹配）。
- `ALE_PACKAGE_FAMILY_NAME` 可接受的資料類型與實際匹配行為。
- `ALE_SECURITY_ATTRIBUTE_FQBN_VALUE` 的 blob 格式與匹配行為。
- 服務 SID 阻斷 `wuauserv` / RESTRICTED 服務（`DPS`）的效果，以及共享 svchost 是否被一併影響。
- `netsh wfp show filters` 中 Windows 防火牆服務 / 包規則的實際條件。
- 阻斷事件的 `UserId` / `PackageSid` / flags 語義。
- Windows 11 上的行為是否一致。

## 檔案變更

```text
NetworkControl.slnx
src/NetworkControl.Engine.Windows/Interop/WfpSession.cs
src/NetworkControl.Engine.Windows/NetworkControl.Engine.Windows.csproj
tests/NetworkControl.Engine.Windows.IntegrationTests/*
docs/m3-15-research.md
docs/handoff-windows-m3.md
```

## 備註

- 本批次沒有改動 Core / Storage / proto / 遷移 / 界面文本，因此不需要構建 Android 解決方案。
- `docs/progress.md`、`docs/m3-plan.md`、`docs/design.md` 保持原樣（按任務約束）。
- 工作樹在提交後應該是乾淨的；沒有 push，也沒有合併 `main`。
