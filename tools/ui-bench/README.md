# UI Benchmark：AtomUI / Fluent / Material

用最小可運行應用隔離「UI 庫 + 主題」對 Android 啟動、APK 大小和記憶體的影響。三個變體的頁面內容、啟動時序、量測方式完全相同，只有 UI 控件和主題不同。

> **重要**：三支 APK 都**不在** `NetworkControl.slnx` / `NetworkControl.Android.slnx` 中，不影響正式構建。預設用 Release、關閉 AOT，方便快速迭代；正式對比請加 `-Aot`，並以真機 arm64 數據為準。

## 變體

| 變體 | 套件 | UI 堆疊 | 頁面 |
|---|---|---|---|
| `UiBench.AtomUI` | `com.networkcontrol.uibench.atomui` | Avalonia + AtomUI（`UseDesktopControls`、Ant Design 令牌） | `atom:Card` / `atom:Button` |
| `UiBench.Fluent` | `com.networkcontrol.uibench.fluent` | Avalonia + FluentTheme | `Border` 卡片 + 基礎控件 |
| `UiBench.Material` | `com.networkcontrol.uibench.material` | Avalonia + Material.Avalonia 3.21 | MaterialTheme + `material:Card` |

三個 APK 可以同時安裝，方便來回切換對比。

## 應用內顯示的指標

- **冷啟動**：進程啟動 → 首幀（`Choreographer` 第一個 frame callback），以及拆分：
  - 運行時初始化（進程啟動 → `Application.OnCreate`）
  - Avalonia 初始化（`Application.Initialize`）
  - **UI 庫配置**（`CustomizeAppBuilder`；AtomUI 的主題 / 控件註冊在這裡）
  - Activity 建立
  - 首幀渲染
- **APK 大小**：已安裝 APK 的實際大小（單 ABI）
- **PSS / 託管堆 / 原生 PSS / 執行緒數**
- **即時 FPS / 最差幀 / 掉幀數**：持續掛在 `Choreographer` 上；下拉或滾動時觀察
- **UI 壓力測試**：
  - 300 行布局：建立 300 個 `TextBlock`，量一次完整 Measure/Arrange
  - 滾動 3 秒：程式化滾動列表，量期間的 FPS、最差幀和掉幀數
- **啟動里程碑**：每個 `BenchMetrics.Mark` 的時間點

## 構建

```powershell
# 快速對比（Release，關閉 AOT，模擬器 x64）
.\tools\ui-bench\build.ps1 -Abi x64

# 真機 / 正式對比（Release + AOT，arm64）
.\tools\ui-bench\build.ps1 -Abi arm64 -Aot

# 只構建其中一個
.\tools\ui-bench\build.ps1 -Variant Fluent -Abi arm64 -Aot
```

APK 輸出在 `tools/ui-bench/artifacts/<variant>-<abi>/`（`artifacts/` 已被 git 忽略）。

脚本使用 `D:\tools\android-dev` 的用戶級工具鏈，可用 `NC_ANDROID_TOOLCHAIN` 覆蓋。

## 自動對比

```powershell
# 安裝三個 APK，各冷啟動 3 次，輸出中位數表
.\tools\ui-bench\bench.ps1 -Abi x64 -Runs 3
```

脚本會：

1. `adb install -r` 三個 APK；
2. 每次 `am force-stop` 後 `am start -W -S`；
3. 從 `am start` 輸出取 `TotalTime`；
4. 從 `logcat -s UiBench:I` 取應用內 `metrics variant=... cold_start_ms=... pss_mb=...`；
5. 輸出每個變體的中位數對比表。

量測時請保持同一台設備、同一 ABI、同一 AOT 設定、相同亮度 / 溫度，並在每次冷啟動前確認應用已停止。

## 初步結果（2026-11-15，模擬器 x86_64，Release，AOT 關閉）

| 變體 | APK | 冷啟動（應用內） | `am TotalTime` | UI 庫配置 | 300 行布局 | 滾動 | PSS | 託管堆 |
|---|---|---|---|---|---|---|---|---|
| AtomUI | 18.72 MB | 10,197 ms | 10,185 ms | 372 ms | 54.7 ms | 60 FPS / 0 掉幀 | 160.3 MB | 17.1 MB |
| Fluent | 13.45 MB | 6,852 ms | 6,809 ms | 0 ms | 38.5 ms | 60 FPS / 0 掉幀 | 109.1 MB | 10.7 MB |
| Material | 14.93 MB | 8,256 ms | 8,196 ms | 0 ms | 51.7 ms | 60 FPS / 1 掉幀 | 119.2 MB | 13.3 MB |

**初步結論**（模擬器 + 關閉 AOT，數字只作相對比較）：

- **Fluent 比 AtomUI 冷啟動快約 33%**（6.85 s vs 10.20 s，少 3.3 s），APK 小 **5.3 MB**，PSS 少 **51 MB**，託管堆少 **6.4 MB**，300 行布局快約 30%。
- **Material 介於兩者之間**：比 AtomUI 快約 19%，APK 小 3.8 MB，PSS 少 41 MB。
- AtomUI 的 `UI 庫配置`（`UseAtomUI` / `UseDesktopControls`）本身約 372 ms，但其程序集體積和主題初始化也拖慢了 Activity 建立與首幀渲染。
- 這是**最小應用**、模擬器、關閉 AOT 的結果；真機和 AOT 下的絕對值會不同，但「去掉 AtomUI 能明顯減少啟動、體積和記憶體」的方向一致。
- **arm64 + AOT 對比包已生成**（2026-11-15）：`artifacts/ui-bench-arm64-aot/UiBench-{AtomUI,Fluent,Material}-arm64-aot.apk`，大小 21.61 / 14.88 / 16.06 MB，均只含 `arm64-v8a`，AOT 原生庫 108 / 64 / 67 個。真機啟動與幀率數據待補。

## 完成記錄

| 日期 | 功能 | 提交 | 驗證 | 未驗證 |
|---|---|---|---|---|
| 2026-11-15 | 共用度量庫 + AtomUI 變體 + 應用內面板 | 本次提交 | Release x64 構建、模擬器啟動、布局 / 滾動測試 | arm64 / AOT、真機 |
| 2026-11-15 | Fluent 變體 | 本次提交 | Release x64 構建、模擬器啟動、布局 / 滾動測試 | arm64 / AOT、真機 |
| 2026-11-15 | Material 變體 | 本次提交 | Release x64 構建、模擬器啟動、布局 / 滾動測試 | arm64 / AOT、真機 |
| 2026-11-15 | 三個變體的 arm64 + AOT APK | 建構輸出（git 忽略） | 建構成功；`arm64-v8a` only；AOT 庫 108 / 64 / 67 個 | 真機啟動、FPS、PSS |
