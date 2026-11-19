# PocketGate（Android Kotlin 版）

PocketGate 是基於 **Kotlin + Jetpack Compose** 開發的 Android 按應用聯網控制與防火牆工具（免 Root，基於本機 `VpnService`）。

- **應用名稱**：PocketGate（`applicationId` 保持 `com.networkcontrol.android.kotlin` 以確保舊版無縫覆蓋升級；Debug 版帶 `.debug` 後綴）
- **最低版本**：Android 10（API 29）；compile / target SDK 36
- **當前版本**：`v0.13.9`（Release `arm64-v8a` APK 約 5.5 MB）
- **獨立 Gradle 專案**：不屬於 `.NET` 解決方案；規則語義、SQLite schema 與導入導出格式與桌面端對齊

## 構建與測試

工具鏈默認使用 `D:\tools\android-dev`（可透過環境變量覆蓋）：

| 變量 | 用途 |
|---|---|
| `NC_ANDROID_JDK` | JDK 17 |
| `NC_ANDROID_SDK` | Android SDK 36 |
| `NC_ANDROID_AVD_HOME` | 模擬器 AVD 目錄 |

```powershell
# 單元測試
.\scripts\android-kotlin.ps1 test

# 調試 APK 構建與安裝
.\scripts\android-kotlin.ps1 build
.\scripts\android-kotlin.ps1 install
.\scripts\android-kotlin.ps1 logcat

# 發布 Release APK（輸出至 artifacts\kotlin-android\PocketGate-<version>.apk）
.\scripts\android-kotlin.ps1 publish
```

## 核心功能（v0.13.9）

- **雙模式本地 VPN 引擎**：
  - **黑洞分流模式**：按應用排除/攔截、假 DNS、按 UID 歸屬、TCP RST / ICMP 快速失敗
  - **逐連線轉發模式**（`hev-socks5-tunnel` + `AF_UNIX SOCK_SEQPACKET`）：按 UID + 域名 / 位址 / 端口 / 協定逐條判決；出口支援**內建直連出口**（本機隨機帳密 SOCKS5 + DoH DNS）或**外部 SOCKS5 代理**（FlClash / v2rayNG / Clash Meta / Reqable 等，含防回環預警、事件驅動 DNS 佇列、8 槽位 UDP 快速路徑與代理異常一鍵切回直連）
- **攔截清單與追蹤器防護**：內建 7 份公開廣告與追蹤器名單、支援自訂訂閱與檔案匯入、防 DNS 繞過（重定向 UDP:53、攔截 DoT / 知名 DoH）、規則變更時雙向 RST 切斷已建立連線
- **詢問與規則引擎**：應用外懸浮詢問面板 / 應用內卡片 / 通知按鈕；支援允許、拒絕、僅一次、限時、永久；規則按配置文件隔離、自動合併與篡改檢測
- **場景與安全**：5 種全域模式（詢問 / 白名單 / 黑名單 / 全部阻止 / 停用）、按網路類型 / Wi-Fi SSID / SIM 自動切換配置、專注模式與時間表、鎖屏斷網與白名單、系統生物辨識應用鎖
- **靈動島與桌面互動**：頂部即時網速/流量/攔截靈動島（三種樣式、自訂位置與透明度）、Glance 桌面小工具、快速設定磁貼、應用捷徑
- **現代外觀與體驗**：6 套視覺主題風格（經典 / 黑曜石極客 / 極簡霜白 / 極光流光 / 純黑省電 / Android 12+ 動態取色）、Haze 懸浮玻璃底部導覽列、三語界面（簡體 / 繁體 / English）
- **應用內更新與診斷**：GitHub / Gitee / 國內鏡像自動故障轉移、應用內直接下載並拉起系統安裝器覆蓋升級；每日備份 `config.db`、脫敏診斷包匯出與 CSV 日誌匯出

## 目錄結構

```
app/src/main/java/com/networkcontrol/android/kotlin/
├── core/       規則、判決管線、詢問代理、配置文件、專注、更新檢查、版本說明
├── data/       SQLite schema / DAO、導入導出、備份
├── engine/     VPN、報文過濾、hev 轉發、內建直連 SOCKS5、DoH、攔截清單、流量與網路監控
├── platform/   通知、懸浮詢問、靈動島、小工具、磁貼、開機廣播、診斷包與圖示
└── ui/         Jetpack Compose 頁面、現代主題引擎與共用組件
```

完整開發與功能落盤記錄見 [docs/kotlin-feature-log.md](../docs/kotlin-feature-log.md)。
