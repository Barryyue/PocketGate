# Avalonia + AtomUI Android 版對齊 Kotlin 版（v0.16.0）長期路線圖

> 目標：將 `src/NetworkControl.App.Android` + `src/NetworkControl.Engine.Android` + `src/NetworkControl.Engine.Vpn` 在功能與 UI 體驗上全面對齊 PocketGate Kotlin 版 `v0.16.0`，最終產出正式簽章並明確標識為 AtomUI 版本的 `PocketGate-<version>-AtomUI.apk`。

## 1. 執行階段與目前進度

- [~] **M0 — 工具鏈與基線 APK**：
  - [x] `scripts/android.ps1` 已適配本機 JDK / Android SDK 與正式 Keystore 簽章，並輸出 `PocketGate-<version>-AtomUI.apk`
  - [x] `NetworkControl.App.Android` 標識改為 `PocketGate (AtomUI)`
  - [~] 正在下載 `.NET SDK 10.0.402` 至 `D:\tools\android-dev\dotnet`（完成後安裝 `android` 工作負載並執行基線編譯）
- [x] **M1 — 品牌圖示、6 套主題引擎與現代導覽體驗（代碼已就緒，待編譯驗證）**：
  - [x] 移植極簡 PocketGate 自適應圖示與通知欄單色護盾圖示
  - [x] `AppearanceService` 支援 6 套視覺風格（Classic / ObsidianCyber / FrostMinimal / AuroraGlass / AmoledBlack / DynamicYou）
  - [x] `SpeedChartControl` 36 點平滑貝茲即時網速波形圖 + `OverviewView` Bento 雙列儀表盤
  - [x] `ShellView` 升級為 5-Tab 懸浮膠囊底部導覽列（含獨立 `RulesView` 與滾動自動隱藏/彈回）
- [x] **M2 — 規則管理、權限體檢與本地備份（代碼已就緒，待編譯驗證）**：
  - [x] 獨立 `RulesView` / `RulesViewModel`（搜尋、開關、刪除、清除過期）
  - [x] `AndroidPermissionHelper`（6 項權限體檢 `X / 6`）+ 首次權限引導彈窗 + 總覽待辦橫幅
  - [x] `LocalRulesBackup`（`MediaStore.Downloads` 留檔至 `Download/PocketGate/` 並支援一鍵還原）
- [x] **M3 — 逐連線轉發引擎、攔截清單與隱私同步（代碼已就緒，待編譯驗證）**：
  - [x] 接入 `libhev-socks5-tunnel.so`（`arm64-v8a` / `x86_64`）與 `HevTunnelInterop`
  - [x] `DirectSocksServer`（內建直連 SOCKS5）、`Socks5Probe`（三段式代理診斷）、`ForwardingConnectionTracker`（活躍連線追蹤與雙向 RST 切斷）
  - [x] `DomainBlocklist` / `BlocklistParser` / `BlocklistCatalog`（7 份內建攔截清單 + 加密 DNS 網域清單）
  - [x] `VpnRoutingPlan` 新增 `CloneAppsInheritPrimary`（應用分身獨立管控）
  - [x] 純離線模式（`OfflineMode`）+ `WebDavBackupClient`（AES-256-GCM 端到端加密同步）
- [x] **M4 — 自動檢查更新閉環與 CI 工作流（代碼已就緒，待編譯驗證）**：
  - [x] `AppUpdateChecker` + `AppUpdateManager`（進入自動檢查、更新日誌直顯、總覽頂部進度條、切頁不中斷下載、完成彈窗提醒安裝）+ `ApkInstaller`
  - [x] 新增 `.github/workflows/android-atomui.yml`
- [x] **M5 — 編譯、單元測試、格式檢查與正式簽章 `PocketGate-<version>-AtomUI.apk` 產出**（2026-11-20 完成）：
  - `dotnet build NetworkControl.slnx` 零錯誤零警告
  - `dotnet test --solution NetworkControl.slnx` 462 通過 / 6 略過（Windows 管理員權限整合測試），`NetworkControl.Engine.Vpn.Tests` 82 通過（含 `ParityFeatureTests` 6 項新測試）
  - `dotnet format NetworkControl.slnx --verify-no-changes --exclude src/NetworkControl.App.Android src/NetworkControl.Engine.Android` 與 `dotnet format NetworkControl.Android.slnx --verify-no-changes` 通過
  - 正式簽章產物：`artifacts/android/PocketGate-0.3.0-AtomUI.apk`（24.8 MB，26,044,386 bytes，SHA-256 `CC136BDB64C92ACE8841BFF6CC22771EA6313E56A487260E1505F662E8A93128`）
  - 待真機驗證：AtomUI 6 套主題視覺、5-Tab 懸浮導覽列滾動隱藏手勢、`SpeedChartControl` 實時波形與 `hev` 逐連線轉發真機實測
