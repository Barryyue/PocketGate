# NetworkControl

跨平台（Windows / Linux / macOS）的按應用聯網控制工具。應用首次聯網時彈窗詢問，可以選擇「允許 / 拒絕」×「僅本次 / 直到應用退出 / 限時 / 直到重啟 / 永久」，並按域名、IP、端口細化規則。

> **當前狀態：M1（Windows MVP）待驗收**。核心服務、規則引擎、詢問流程、存儲、IPC、AtomUI 界面（簡體 / 繁體 / English）和 Windows WFP 引擎已完成；WFP 引擎需要在管理員權限下做最終驗收，見[開發指南 §7](docs/development.md#7-windows-wfp-引擎)。Linux / macOS 引擎見[設計文檔 §20](docs/design.md#20-里程碑與任務分解)。

## 架構

```
┌─────────────────────────────┐   gRPC over 命名管道 / Unix socket   ┌──────────────────────────────────┐
│ NetworkControl.App (Avalonia)│ ◀──────────────────────────────────▶ │ NetworkControl.Service (特權服務)  │
│ 托盤 · 彈窗 · 規則 · 日誌     │      本地對端憑據認證 + 授權策略        │ 判決管線 · 規則引擎 · 詢問代理      │
└─────────────────────────────┘                                      │ SQLite 存儲 · 平台引擎（可替換）    │
                                                                     └──────────────────────────────────┘
```

| 項目 | 說明 |
|---|---|
| `src/NetworkControl.Core` | 平台無關的核心：領域模型、規則引擎、判決管線、詢問代理、引擎抽象 |
| `src/NetworkControl.Storage` | SQLite 存儲（`config.db` / `activity.db`）、遷移、倉儲實現 |
| `src/NetworkControl.Ipc` | `.proto` 契約、生成代碼、IPC 端點與客戶端連接工廠 |
| `src/NetworkControl.Service` | 服務宿主：Kestrel 監聽本地 IPC、認證授權、gRPC 服務、引擎裝配 |
| `src/NetworkControl.Engine.Fake` | 模擬引擎：開發和測試用的虛擬應用與連接 |
| `src/NetworkControl.Engine.Windows` | Windows 用戶態 WFP 引擎：規則編譯為過濾器、阻斷事件觸發詢問、ETW DNS 域名規則 |
| `src/NetworkControl.App` | 桌面界面：Avalonia 12 + AtomUI（Ant Design），三種語言，托盤與詢問彈窗 |
| `tests/*` | 單元測試、場景測試（M0 驗收用戶故事）、存儲集成測試 |

## 快速開始

需要 [.NET SDK 10.0.400+](https://dotnet.microsoft.com/download)。

```bash
# 構建與測試
dotnet build NetworkControl.slnx
dotnet test --solution NetworkControl.slnx

# 啟動界面（調試構建會自動拉起開發模式的核心服務：模擬引擎，數據寫入 ./.devdata）
dotnet run --project src/NetworkControl.App

# 在 Windows 上安裝為系統服務並啟用 WFP 攔截（管理員 PowerShell）
.\scripts\install-service.ps1
```

在 UI 的「概覽」頁，用「模擬連接」讓虛擬應用發起連接，就能看到詢問彈窗、規則生成和連接日誌。

## 文檔

- [需求規格說明書](docs/requirements.md)
- [設計實現文檔](docs/design.md)
- [開發指南](docs/development.md)：本地運行、擴展點、編碼約定
- [進度看板](docs/progress.md)：已完成、待辦、待驗證事項、可分派任務
- [AGENTS.md](AGENTS.md)：AI 編碼助手（Claude Code、Codex 等）共用的工作指南
