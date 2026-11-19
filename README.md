# PocketGate

跨平台（Android / Windows）按應用聯網控制工具（原項目代號 `NetworkControl`）。應用首次聯網時彈窗詢問，支援按應用、域名、IP 與端口精細放行或攔截，全程本機執行、零遙測。

- **GitHub**：https://github.com/Barryyue/PocketGate （[下載最新版本](https://github.com/Barryyue/PocketGate/releases/latest)）
- **Gitee 鏡像**：https://gitee.com/yuexiaobai/PocketGate
- **產品介紹頁**：[site/index.html](site/index.html)

---

## Android 版（PocketGate · Kotlin + Jetpack Compose）

當前版本：**v0.16.0**（Android 10+，`arm64-v8a`，Release APK 約 5.6 MB）。

核心特性：
- **雙模式本地 VPN 防護**：黑洞式按應用分流，或基於 `hev-socks5-tunnel` 的**逐連線轉發模式**（支援內建直連出口與外部 SOCKS5 代理，如 FlClash / v2rayNG / Clash Meta / Reqable，並支援應用分身獨立管控）
- **即時懸浮詢問、靈動島與原生狀態欄網速**：支援應用外懸浮詢問面板、狀態欄即時網速/流量/攔截靈動島，以及原生自動反色狀態欄即時網速小圖示
- **域名攔截清單與即時連線斷開**：內建 7 份公開廣告/追蹤器名單，支援防 DNS 繞過、自訂訂閱、規則變更雙向 RST 斷連與即時活躍連線檢視一鍵斷開
- **隱私安全與加密備份**：純離線模式（禁止本應用主動聯網）、`WebDAV` 端到端 AES-256-GCM 加密備份同步、本機 `Download/PocketGate/` 留檔一鍵還原、6 項權限體檢與首次引導
- **場景自動化與現代視覺**：6 套視覺主題風格、Bento 雙列儀表盤與即時網速波形圖、彈性自動隱藏導覽列、桌面小工具與快捷磁貼、三語界面（簡體 / 繁體 / English）
- **應用內自動更新閉環**：進入自動檢測並直顯更新日誌、總覽頂部下載進度條（切換導覽不中斷）、支援 GitHub / Gitee / 國內加速鏡像自動切換並一鍵原地覆蓋升級

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

---

## 開源協議

本項目採用 [GNU General Public License v2.0 (GPL-2.0)](LICENSE) 開源發布。
