# PocketGate（Android Kotlin 版）

PocketGate 是基於 **Kotlin + Jetpack Compose** 開發的 Android 按應用聯網控制與防火牆工具（免 Root，基於本機 `VpnService`）。

- **應用名稱**：PocketGate（`applicationId` 保持 `com.networkcontrol.android.kotlin` 以確保舊版無縫覆蓋升級；Debug 版帶 `.debug` 後綴）
- **最低版本**：Android 10（API 29）；compile / target SDK 36
- **當前版本**：`v0.16.2`（Release `arm64-v8a` APK 約 5.6 MB）
- **開源協議**：[GPL-3.0](../LICENSE)
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

## 核心功能（v0.16.0）

- **雙模式本地 VPN 引擎**：
  - **黑洞分流模式**：按應用排除/攔截、假 DNS、按 UID 歸屬、TCP RST / ICMP 快速失敗
  - **逐連線轉發模式**（`hev-socks5-tunnel` + `AF_UNIX SOCK_SEQPACKET`）：按 UID + 域名 / 位址 / 端口 / 協定逐條判決；支援**應用分身獨立管控**；出口支援**內建直連出口**（本機隨機帳密 SOCKS5 + DoH DNS）或**外部 SOCKS5 代理**（FlClash / v2rayNG / Clash Meta / Reqable 等，含三段式精準診斷、防回環預警與異常一鍵切回直連）
- **攔截清單、追蹤器與即時連線**：內建 7 份公開廣告與追蹤器名單、防 DNS 繞過、規則變更雙向 RST 切斷連線，以及記錄頁「即時活躍連線」一鍵雙向 RST 斷開
- **純離線模式與加密備份同步**：可選徹底禁止 PocketGate 自身發起聯網請求；支援 `WebDAV` 端到端 AES-256-GCM 加密備份同步與本機 `Download/PocketGate/` 留檔一鍵還原
- **詢問與規則引擎**：應用外懸浮詢問面板 / 應用內卡片 / 通知按鈕；支援允許、拒絕、僅一次、限時、永久；規則按配置文件隔離、自動合併與篡改檢測
- **場景與安全**：5 種全域模式（詢問 / 白名單 / 黑名單 / 全部阻止 / 停用）、按網路類型 / Wi-Fi SSID / SIM 自動切換配置、專注模式與時間表、鎖屏斷網與白名單、首次權限引導與 6 項紅綠燈體檢
- **靈動島、原生網速圖示與小工具**：頂部即時網速/流量/攔截靈動島、原生自動反色狀態欄即時網速小圖示、極簡護盾 Glance 桌面小工具、快速設定磁貼、應用捷徑
- **現代外觀與體驗**：6 套視覺主題風格、Bento 雙列儀表盤與即時網速波形圖、彈性自動隱藏導覽列、三語界面（簡體 / 繁體 / English）
- **應用內自動更新閉環**：進入自動檢測並直顯更新日誌、總覽頂部下載進度條（切換導覽不中斷）、GitHub / Gitee / 國內鏡像自動轉移並一鍵原地覆蓋升級；每日備份 `config.db`、脫敏診斷包與 CSV 日誌匯出

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
