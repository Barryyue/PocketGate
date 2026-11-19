# PocketGate

跨平台（Android / Windows）按應用聯網控制工具（原項目代號 `NetworkControl`）。應用首次聯網時彈窗詢問，支援按應用、域名、IP 與端口精細放行或攔截，全程本機執行、零遙測。

- **GitHub**：https://github.com/Barryyue/PocketGate （[下載最新版本](https://github.com/Barryyue/PocketGate/releases/latest)）
- **Gitee 鏡像**：https://gitee.com/yuexiaobai/PocketGate
- **產品介紹頁**：[site/index.html](site/index.html)

---

## Android 版（PocketGate · Kotlin + Jetpack Compose）

當前版本：**v0.13.9**（Android 10+，`arm64-v8a`，Release APK 約 5.5 MB）。

核心特性：
- **雙模式本地 VPN 防護**：黑洞式按應用分流，或基於 `hev-socks5-tunnel` 的**逐連線轉發模式**（支援內建直連出口與外部 SOCKS5 代理，如 FlClash / v2rayNG / Clash Meta / Reqable）
- **即時懸浮詢問與靈動島**：支援應用外懸浮詢問面板、狀態欄即時網速/流量/攔截靈動島（支援三種樣式與透明度自訂）
- **域名攔截清單與追蹤器防護**：內建 7 份公開廣告/追蹤器名單，支援防 DNS 繞過、自訂訂閱與規則變更雙向 RST 斷連
- **場景自動化**：多配置文件隔離、按網路類型 / Wi-Fi SSID / SIM 卡自動切換、專注模式與時間表、鎖屏斷網、桌面小工具與快捷磁貼
- **現代視覺主題**：6 套內建風格（經典 / 黑曜石極客 / 極簡霜白 / 極光流光 / 純黑省電 / Android 12+ 動態取色）、Haze 懸浮玻璃工具欄、三語界面（簡體 / 繁體 / English）
- **應用內自動更新**：支援 GitHub / Gitee / 國內加速鏡像自動切換，應用內直接下載並拉起原地覆蓋升級

詳細構建與目錄說明見 [android-kotlin/README.md](android-kotlin/README.md)。

```powershell
# Android (Kotlin) 常用命令
.\scripts\android-kotlin.ps1 test      # 執行單元測試
.\scripts\android-kotlin.ps1 build     # 構建 Debug APK
.\scripts\android-kotlin.ps1 publish   # 構建 Release APK（輸出至 artifacts\kotlin-android\）
```

---

## Windows 桌面版（.NET 10 + Avalonia 12 + WFP）

當前狀態：**v0.3.0 試用版（M1 Windows MVP）**。

```
┌─────────────────────────────┐   gRPC over 命名管道 / Unix socket   ┌──────────────────────────────────┐
│ NetworkControl.App (Avalonia)│ ◀──────────────────────────────────▶ │ NetworkControl.Service (特權服務)  │
│ 托盤 · 彈窗 · 規則 · 日誌     │      本地對端憑據認證 + 授權策略        │ 判決管線 · 規則引擎 · 詢問代理      │
└─────────────────────────────┘                                      │ SQLite 存儲 · 平台引擎（可替換）    │
                                                                     └──────────────────────────────────┘
```

| 項目 | 說明 |
|---|---|
| `android-kotlin/` | **PocketGate Android 版**（Kotlin + Jetpack Compose + hev-socks5-tunnel） |
| `src/NetworkControl.Core` | 桌面端平台無關核心：領域模型、規則引擎、判決管線、詢問代理 |
| `src/NetworkControl.Storage` | SQLite 存儲（`config.db` / `activity.db`）、遷移、倉儲實現 |
| `src/NetworkControl.Ipc` | `.proto` 契約、IPC 端點與診斷包工具 |
| `src/NetworkControl.Service` | Windows 服務宿主：Kestrel 本機 IPC、gRPC 服務、引擎裝配 |
| `src/NetworkControl.Engine.Windows` | Windows 用戶態 WFP 引擎：過濾器編譯、阻斷事件、ETW DNS |
| `src/NetworkControl.App` | 桌面界面：Avalonia 12 + AtomUI（Ant Design），三語與托盤彈窗 |
| `site/index.html` | 三語產品介紹頁（支援深淺主題切換） |

需要 [.NET SDK 10.0.400+](https://dotnet.microsoft.com/download)：

```bash
dotnet build NetworkControl.slnx
dotnet test --solution NetworkControl.slnx
dotnet run --project src/NetworkControl.App
```

---

## 文檔

- [Android Kotlin 功能清單](docs/kotlin-feature-log.md)
- [需求規格說明書](docs/requirements.md)
- [設計實現文檔](docs/design.md)
- [開發指南](docs/development.md)：本地運行、擴展點、編碼約定
- [進度看板](docs/progress.md)
- [AGENTS.md](AGENTS.md)：AI 編碼助手共用的工作指南
