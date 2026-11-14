# NetworkControl KT（Android Kotlin 對照版）

這是 Android 版的 **Kotlin + Jetpack Compose** 對照實現，與 `src/NetworkControl.App.Android` 的 Avalonia 版並行維護，用於技術選型比較（APK 體積、冷啟動、內存、開發效率、平台整合成本）。

- applicationId：`com.networkcontrol.android.kotlin`（Debug 加 `.debug` 後綴，可與 Avalonia 版同時安裝）
- 最低版本：Android 10（API 29）；compile / target SDK 36
- 獨立 Gradle 專案，不屬於 `NetworkControl.slnx` / `NetworkControl.Android.slnx`
- 不引用 .NET Core / Storage；規則語義、SQLite schema、導入導出格式以 C# 實現為參考

## 構建

工具鏈默認使用 `D:\tools\android-dev`，可用環境變量覆蓋：

| 變量 | 用途 |
|---|---|
| `NC_ANDROID_JDK` | JDK 17 |
| `NC_ANDROID_SDK` | Android SDK 36 |
| `NC_ANDROID_AVD_HOME` | 模擬器 AVD 目錄 |

```powershell
# 調試 APK
.\scripts\android-kotlin.ps1 build
.\scripts\android-kotlin.ps1 install
.\scripts\android-kotlin.ps1 logcat

# 單元測試
.\scripts\android-kotlin.ps1 test

# Release APK（目前使用調試簽名，僅供對照試用）
.\scripts\android-kotlin.ps1 publish
```

產物：`artifacts\kotlin-android\NetworkControl-KT-0.1.0.apk`

也可以直接使用 Gradle：

```powershell
cd android-kotlin
$env:JAVA_HOME='D:\tools\android-dev\jdk'
$env:ANDROID_HOME='D:\tools\android-dev\android-sdk'
.\gradlew.bat assembleRelease
```

## 功能（v0.1）

已實現：

- 黑洞式分流 VPN（`VpnService` + `addDisallowedApplication`）、假 DNS、按 UID 歸屬
- TCP RST / ICMP 不可達快速失敗
- 規則引擎：應用匹配、條件、優先級、篡改檢測、限時 / 直到重啟 / 永久
- 詢問：合併、排隊、超時、暫停、稍後、通知按鈕 + 應用內底部卡片
- 模式：詢問 / 白名單 / 黑名單 / 全部阻斷 / 停用
- 配置文件：建立、改名、刪除、切換、模式回寫
- 應用頁：搜索、篩選、三態、圖標、今日用量、最近連線
- 記錄頁：阻擋記錄、原因 / 關鍵字篩選、詳情
- 流量：`NetworkStatsManager` 按 UID 增量統計
- 設定：三語、明暗主題、詢問超時 / 默認動作、通知、always-on VPN / 電池優化入口、使用情況授權、配置文件、規則導入導出（SAF）、診斷包（zip + 分享）
- 開機恢復、always-on VPN、被其他 VPN 撤銷處理、快捷設定磁貼
- 每日 config.db 備份（保留 7 份）、文件日誌（7 天）

v0.1 尚未實現 / 已知差距：

- 勿擾模式目前沒有界面入口；`QuietPeriodTracker` 只在勿擾由其他入口設定時統計
- 按網絡自動切換配置文件尚未接入（`NetworkMonitor` 已提供網絡狀態）
- 懸浮詢問面板、專注模式、時間表、鎖屏斷網、應用詳情頁（與 Avalonia 版的後續規劃一致）
- 轉發模式 / 上游代理（Avalonia 版也未實現）
- 沒有在真機上驗證；模擬器網絡本身沒有外網，Chrome 加載測試受環境限制
- Release 使用調試簽名，不可用於正式分發

## 目錄

```
app/src/main/java/com/networkcontrol/android/kotlin/
├── core/       規則、判決、詢問、配置文件、設定（平台無關語義）
├── data/       SQLite schema / DAO、導入導出、備份
├── engine/     VPN、報文、假 DNS、應用目錄、流量、網絡監控
├── platform/   通知、磁貼、開機廣播、診斷、日誌、圖標
└── ui/         Compose 頁面與組件
```

## 與 Avalonia 版的對照

| 指標 | Avalonia 版（0.3.0） | Kotlin 版（v0.1，模擬器） |
|---|---|---|
| Release APK | 約 45.9 MB（雙 ABI）/ 約 24 MB（arm64） | **3.1 MB**（雙 ABI） |
| 冷啟動 | 約 7.5 秒（模擬器軟件渲染） | **約 2.1 秒**（同模擬器，5 次中位數） |
| 共用核心 | Core / Storage（.NET） | 無；Kotlin 重寫，靠測試和格式對齊 |

數字只代表本機模擬器，真機需要另行測量。架構與任務狀態見 [docs/android-kotlin.md](../docs/android-kotlin.md)。
