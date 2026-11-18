# Android 版：APK 大小與冷啟動性能

> 2026-11-14 建立。記錄現狀測量、在現有技術棧內可以做的優化、現有技術棧做不到的部分，以及更進一步的架構方案。
> 可執行的任務在 [progress-android.md](progress-android.md)「性能與體積」一節；本文是它們的背景和依據。
> 與 [android.md](android.md) 的關係：android.md 描述功能設計，本文只討論體積和啟動性能。

## 1. 現狀（2026-11-14 測量）

數據來自 `artifacts/android/com.networkcontrol.android-Signed.apk`（Release，arm64-v8a + x86_64，調試簽名）和 `obj/Release/net10.0-android/android-arm64/linked`（裁剪後的程序集）。

| 指標 | 數值 | 說明 |
|---|---|---|
| APK 大小 | 45.9 MB | 兩種 ABI 各約 22 MB |
| 冷啟動到首幀 | 約 7.5 秒 | **模擬器 + 軟件渲染**，不代表真機；真機數據待測 |
| 裁剪後的託管程序集 | 31.5 MB，153 個 | 壓縮後放在 `libassembly-store.so`（12.4 MB，APK 中 10 MB） |

### 1.1 單個 ABI（arm64-v8a）的組成

| 內容 | APK 中（壓縮後） | 解壓後 | 能否優化 |
|---|---|---|---|
| 託管程序集（assembly store） | 9.96 MB | 12.42 MB | **可以**：見 §2.2、§2.3 |
| AOT 預編譯代碼（153 個 `libaot-*.so`） | 3.82 MB | 13.53 MB | **可以取捨**：見 §2.4 |
| SkiaSharp（Avalonia 的渲染引擎） | 3.92 MB | 9.03 MB | 現有技術棧下不能 |
| Mono 運行時（`libmonosgen` + `libmonodroid`） | 1.81 MB | 4.53 MB | 現有技術棧下不能 |
| HarfBuzzSharp（文字排版） | 0.93 MB | 2.78 MB | 現有技術棧下不能 |
| SQLite（`libe_sqlite3`） | 0.89 MB | 1.77 MB | 不能：系統的 SQLite 不對 NDK 應用開放 |
| `System.IO.Compression.Native` | 0.37 MB | 0.76 MB | 診斷導出 / 備份需要 zip，保留 |
| 其他原生庫 | 約 0.2 MB | — | — |

兩種 ABI 共用的部分：

| 內容 | APK 中 | 解壓後 | 能否優化 |
|---|---|---|---|
| `classes.dex`（Java / Kotlin 代碼，主要來自 AndroidX） | 2.69 MB | 7.83 MB | **可以**：R8 壓縮，見 §2.5 |
| Kotlin 元數據目錄（`commonMain`、`kotlin` 等） | 約 0.2 MB | 約 0.5 MB | **可以**：打包時排除 |
| 資源（`res`、`resources.arsc`） | 約 0.4 MB | — | 很小 |

### 1.2 最大的託管程序集（裁剪後，arm64）

| 程序集 | 大小 | 說明 |
|---|---|---|
| `AtomUI.Desktop.Controls` | 6.58 MB | 桌面組件庫，Android 只用到其中一部分 |
| `System.Private.CoreLib` | 2.28 MB | 運行時基礎庫 |
| `Avalonia.Base` | 1.72 MB | |
| `System.Private.Xml` | 1.54 MB | **需要查明誰在用**（疑似 SVG 庫或 XLIFF 解析） |
| `NetworkControl.Android` | 1.42 MB | 本應用（含界面） |
| `Mono.Android` | 1.40 MB | |
| `AtomUI.Icons.AntDesign` | 1.24 MB | 整套 Ant Design 圖標，實際只用了幾十個 |
| `DynamicData` + `System.Reactive` + `ReactiveUI` | 1.28 MB | AtomUI 的傳遞依賴 |
| `Svg.Custom` + `Svg.SceneGraph` + `Svg.Model` + `ExCSS` | 1.79 MB | AtomUI 的傳遞依賴（SVG 渲染） |
| **`NWayland` + `Avalonia.Wayland`** | **0.66 MB** | **Linux 桌面專用，在 Android 上完全無用**，經 `AtomUI.Desktop.Controls` → `Avalonia.Desktop` 引入 |
| `System.Data.Common` | 0.56 MB | `Microsoft.Data.Sqlite` 需要 |

`project.assets.json` 中還能看到 `Avalonia.Desktop`、`Avalonia.Native`（macOS）、`Avalonia.FreeDesktop`、`Avalonia.Angle.Windows.Natives`、`AtomUI.Native`、`AtomUI.Fonts.AlibabaSans` 等桌面依賴。其中大部分已被裁剪掉或很小，但 NWayland 保留了下來，說明**目前的裁剪沒有剔除整個無用的程序集**（見 §2.2）。

## 2. 現有技術棧內可以做的優化

收益是**估計值**，每一項做完都要重新測量並記錄到本文 §5。

### 2.1 只發布 arm64（收益最大，成本最低）

- 側載 APK 只打 `android-arm64`，大小約減半：45.9 MB → 約 24 MB。現在的真機基本都是 arm64。
- `android-x64` 只用於模擬器調試，保留在 Debug 構建或單獨的構建參數中。
- 上架 Google Play 時用 AAB，商店會按設備的 ABI 和屏幕密度自動拆分下發。

### 2.2 完整裁剪，剔除桌面專用依賴

- 確認 Release 的 `TrimMode`。如果是 `partial`（只裁剪標記為可裁剪的程序集），改為 `full`，並逐一處理裁剪警告。
- 驗收標準：NWayland、`Avalonia.Wayland` 等桌面專用程序集從 `linked` 目錄中消失。
- 完整裁剪後，用到反射的地方（AtomUI 主題、編譯綁定以外的綁定、`System.Text.Json`）可能在運行時才出錯，必須在設備上跑完整的冒煙測試。需要時用 `TrimmerRootDescriptor` 保留。
- 如果完整裁剪仍剔除不掉，評估在項目中用 `PackageReference` 的 `ExcludeAssets` 排除這些傳遞依賴，或者請 AtomUI 上游把桌面平台的依賴拆出去。

### 2.3 查明並縮小其他大塊依賴

- **`System.Private.Xml`（1.54 MB）**：查出引用來源（`ILLink` 的依賴分析，或 `--dump-dependencies`）。如果來自運行時解析 XLIFF，評估改為構建時生成；如果來自 SVG 庫，歸入 §3。
- **`AtomUI.Icons.AntDesign`（1.24 MB）**：只用了幾十個圖標。確認完整裁剪後能否只保留用到的圖標；不行的話，評估把用到的圖標導出為 Android 矢量資源或 Avalonia 的 `PathGeometry`。
- **功能開關**：`UseSystemResourceKeys=true`（去掉異常消息文本）、`EventSourceSupport=false`、`MetricsSupport=false` 等，每項收益幾十到幾百 KB。`UseSystemResourceKeys` 會讓異常消息變成資源鍵，影響日誌可讀性，需要權衡。

### 2.4 AOT 策略

現狀：Release 默認使用 **Profiled AOT**，但用的是 .NET 自帶的**通用**啟動配置文件，不是本應用的。153 個程序集都生成了 `libaot-*.so`（每個 ABI 3.82 MB 壓縮 / 13.53 MB 解壓）。

| 方案 | 大小 | 啟動 | 說明 |
|---|---|---|---|
| 不用 AOT（純 JIT） | 每個 ABI 少約 3.8 MB | 最慢 | 只適合對比測量 |
| **默認 Profiled AOT**（現狀） | 基準 | 中等 | 通用配置文件不覆蓋我們自己的啟動路徑 |
| **自定義 Profiled AOT**（推薦） | 與現狀相近 | 較快 | 在真機上錄製本應用的啟動配置文件（`.aprof`），只預編譯啟動時真正執行的方法 |
| 完整 AOT | 明顯更大 | 最快 | 體積代價大，不推薦 |

錄製方法：.NET Android 提供 AOT 配置文件錄製工具（`dotnet-trace` 或 `Mono.Profiler.Android`）。具體命令依 .NET 10 的工具鏈為準，實施時先查官方文檔並寫入 development.md §11。

### 2.5 Java / Kotlin 部分

- 開啟 R8（`<AndroidLinkTool>r8</AndroidLinkTool>`），壓縮和精簡 `classes.dex`（現在 7.83 MB，主要來自 AndroidX 和 Kotlin 標準庫）。需要補充 ProGuard 保留規則，避免 Avalonia.Android 和 AndroidX 用到的類被誤刪。
- 用 `AndroidPackagingOptionsExclude` 排除 Kotlin 元數據目錄（`commonMain`、`nativeMain`、`kotlin/*.kotlin_builtins` 等），約 0.2 MB。

### 2.6 冷啟動

**先測量，再優化。** 沒有真機數據時，下面的優化順序只是推測。

1. **加性能指標日誌**（設計見 §7）：記錄啟動各階段和運行時關鍵操作的耗時。配合 `adb shell am start -W -S` 的 `TotalTime`，以及 Perfetto 的系統跟蹤。
2. **後台路徑不初始化界面**（需要預研）：`AndroidApp` 繼承自 `AvaloniaAndroidApplication`，Avalonia 和 AtomUI 很可能在進程啟動時（`Application.OnCreate`）就初始化。這樣一來，開機廣播、系統拉起 VPN 服務、進程被殺後重啟這些**沒有界面**的路徑也要付出界面初始化的成本。而這些路徑決定了**保護恢復前的空窗期**有多長。預研 Avalonia 12 是否支持把初始化延遲到 `MainActivity` 創建時；不支持的話見 §4 方案 C。
3. **啟動畫面**：使用 Android 12 的 SplashScreen API，點擊圖標後立即顯示品牌畫面，改善體感，但不縮短實際時間。
4. **推遲非關鍵工作**：流量統計採集、應用圖標加載，以及**應用目錄全量掃描**（`PackageManager` 讀取所有應用並計算簽名 SHA-256，應用多時很慢）。應用目錄改為增量：用 `lastUpdateTime` 判斷，只重新計算有變化的應用，結果緩存。
5. **並行**：`BackendHost`（SQLite、遷移、規則加載、VPN）和界面初始化並行進行；界面先顯示骨架，數據到了再填充。
6. **AtomUI 主題初始化**：`UseDesktopControls()` 會為所有控件註冊主題和設計令牌。測量這部分的耗時；如果佔比大，評估只註冊用到的控件（取決於 AtomUI 是否支持，見 §3）。

### 2.7 防止回退

- CI 的 Android 任務記錄每次構建的 APK 大小（總大小和各 ABI 的組成），超過基準一定比例（例如 5%）時發出警告。
- 冒煙測試腳本記錄冷啟動時間，與基準比較。

## 3. 現有技術棧做不到的部分

這些是 .NET + Avalonia + AtomUI 本身帶來的成本，在不換技術的前提下無法消除，只能接受或等待上游改進。

| 限制 | 成本（單個 ABI） | 原因 | 能等到的改進 |
|---|---|---|---|
| **Skia + HarfBuzz 渲染引擎** | 約 4.9 MB 壓縮 / 11.8 MB 解壓 | Avalonia 自己繪製界面，不使用 Android 的原生控件和系統渲染器 | 無；這是自繪框架的固有成本 |
| **Mono 運行時 + 基礎類庫** | 約 2 MB 運行時 + 數 MB 的基礎庫 | .NET Android 目前默認使用 Mono | .NET 10 / 11 正在試驗 Android 上的 CoreCLR 和 Native AOT（**實驗性**，Avalonia 和 AtomUI 的兼容性未知，需確認當時的狀態） |
| **運行時啟動的固定成本** | — | 加載運行時、初始化 GC、加載程序集 | 同上；Native AOT 能明顯縮短 |
| **SQLite 原生庫** | 0.9 MB | Android 7 起禁止 NDK 應用鏈接系統私有庫，系統自帶的 SQLite 只能從 Java 層使用 | 無；改用 Java 層的 `SQLiteDatabase` 需要重寫 Storage，不值得 |
| **AtomUI 是桌面組件庫** | 主程序集 6.6 MB，另外帶來 DynamicData、Reactive、SVG、XML 等數 MB 的依賴 | 為桌面設計，沒有針對移動端拆分；主題系統在啟動時一次性初始化全部控件 | 向上游反饋：拆分桌面平台依賴、按需註冊控件；或者在 Android 上換用更輕的控件庫（§4 方案 B） |
| **進程被系統殺掉後的重啟成本** | — | Android 會回收後台進程；每次重啟都要再走一遍冷啟動 | 無法避免，只能縮短冷啟動（§2.6），並讓後台路徑不依賴界面 |

**結論**：在現有技術棧內，通過 §2 的優化，單個 ABI 的 APK 估計能從約 24 MB 降到 15–20 MB。冷啟動的下限由運行時和渲染引擎決定，即使優化後，中端機上也很可能仍在 1–2 秒左右（**估計值，待真機驗證**）。第一梯隊的原生應用（例如 NetGuard）大約是 5–10 MB、1 秒以內，這部分差距在現有技術棧內無法完全追上。

## 4. 更進一步的方案（架構級）

| 方案 | 做法 | APK 大小 | 冷啟動 | 成本與風險 |
|---|---|---|---|---|
| **A. 繼續優化現有技術棧**（§2） | 只改構建配置和啟動流程 | 約 15–20 MB / ABI | 改善，但有下限 | 低；**應該先做** |
| **B. Android 界面不用 AtomUI** | 改用 Avalonia 自帶的 Fluent 主題，或者自繪一套輕量的移動端控件；桌面版不變 | 再少約 4–6 MB（AtomUI 及其依賴） | 主題初始化更快 | 中：重寫 Android 的全部視圖；失去與桌面版一致的 Ant Design 風格（android.md 已說明兩邊界面本來就不共用視圖模型） |
| **C. 後台與界面分離** | 後台路徑完全不初始化 Avalonia：延遲初始化，或者把 VPN 和規則引擎放到獨立進程（`:vpn`） | 不變 | **開機和服務重啟路徑**大幅縮短；點擊圖標的冷啟動不變 | 中：獨立進程需要進程間通信（現在 `IBackend` 是進程內調用）；只做延遲初始化則成本低 |
| **D. 原生界面 + .NET 核心** | 界面用 Kotlin + Jetpack Compose（或 Android View），Core / Storage / Engine 仍然是 .NET，通過 JNI 調用 | 去掉 Skia、HarfBuzz、AtomUI、Avalonia，估計 10 MB 左右（仍需要 Mono 和基礎類庫） | 界面秒開；運行時的固定成本仍在 | 高：界面全部用 Kotlin 重寫，團隊要維護兩種語言；JNI 橋接的設計和調試成本 |
| **E. Native AOT（等待 .NET）** | Android 上的 Native AOT 成熟後切換 | 運行時更小 | 明顯更快 | 依賴上游進度；Avalonia 和 AtomUI 的反射用法需要逐一適配 |
| **F. 全部用 Kotlin 重寫** | 引擎、規則、存儲全部原生實現 | 約 5 MB | 最快 | 最高：失去與桌面版共用的 Core 和 Storage，兩套實現長期分別維護；**不推薦** |

## 5. 建議路線

1. **先測量**：真機性能基準 + 啟動埋點（progress-android.md 已有「真機性能基準」）。沒有真機數據之前，不做架構級決定。
2. **做 §2 的全部優化**（方案 A），同時做方案 C 中成本低的部分（後台路徑延遲初始化界面）。每做完一項，把前後數據記錄到下面的表中。
3. **根據真機數據再決定**：
   - 如果 A + C 之後，APK 在 20 MB 以內、冷啟動在 2 秒以內，維持現有技術棧，持續跟進 Native AOT（方案 E）；
   - 如果仍然明顯落後，在方案 B（成本中，收益中）和方案 D（成本高，收益大）之間選擇。這需要用戶決定。

## 6. 測量記錄

每次優化後追加一行，**註明設備型號和構建方式**。

| 日期 | 改動 | 設備 | APK（arm64） | 冷啟動（`am start -W` TotalTime） | 首幀 | 內存（PSS） |
|---|---|---|---|---|---|---|
| 2026-11-14 | 基準（雙 ABI，默認 Profiled AOT） | 模擬器 x86_64，軟件渲染 | 約 24 MB（估計，雙 ABI 共 45.9 MB） | — | 約 7.5 秒 | — |

## 7. 性能指標日誌

目的：不依賴猜測，從日誌中直接看出時間花在哪裡。任務見 progress-android.md P-1。

### 7.1 記錄什麼

| 類別 | 內容 | 頻率 |
|---|---|---|
| 啟動階段 | 進程啟動時刻（`Process.getStartElapsedRealtime()`，包括運行時初始化）、`Application.OnCreate`、Avalonia 初始化、AtomUI 主題、`MainActivity.OnCreate`、首幀、`BackendHost` 各階段（SQLite、遷移、規則、應用目錄、引擎）、VPN 建立；最後一行彙總並註明啟動路徑 | 每次啟動一組，約 15 條 |
| 運行時操作 | VPN 重建、規則發布、詢問延遲（攔截 → 顯示）、應用目錄掃描、頁面切換 | 每次發生時一條 |
| 慢操作 | 超過閾值的操作（例如界面線程上超過 100 ms） | 只在超過時 |
| 週期性指標 | 報文數、詢問數、託管內存、GC 次數 | 每 10 分鐘和進入後台時一條 |

格式：日誌分類 `NetworkControl.Perf`，每條一行 `key=value`，例如：

```
perf startup path=launcher phase=avalonia_init elapsed_ms=812 since_process_ms=1430
perf startup path=launcher summary total_ms=3120 first_frame_ms=2950 backend_ms=1210 vpn_ms=1480
perf op name=vpn_rebuild elapsed_ms=46 apps_excluded=37
```

### 7.2 會不會拖慢應用

**按下面的方式做，開銷可以忽略。**

| 操作 | 單次開銷（量級） | 說明 |
|---|---|---|
| 讀時鐘（`Stopwatch.GetTimestamp`） | 幾十納秒 | 可以放心使用 |
| 一條日誌（源生成，寫 logcat + 文件） | 幾十微秒 | 啟動時約 15 條，合計不到 1 毫秒，相對於秒級的啟動時間可以忽略 |
| `Trace.BeginSection` / `EndSection` | 微秒級（經 JNI） | 沒有在錄製跟蹤時幾乎不做事；只用於粗粒度的階段 |
| `Debug.getMemoryInfo`（PSS） | 幾十毫秒 | **開銷大**，只在診斷導出時調用 |

會拖慢應用的做法，必須避免：

- **在熱路徑上逐條記日誌**：報文處理每秒可能有上千次，逐條記日誌會明顯增加 CPU 和耗電。熱路徑只累加計數器（引擎已經有計數器），週期性輸出彙總。
- **在日誌參數中做昂貴的計算**（例如拼接大字符串、序列化對象）：用 `[LoggerMessage]` 源生成，並在計算前判斷日誌級別是否啟用。
- **同步寫文件阻塞界面線程**：文件日誌要異步寫入（確認現有的文件日誌實現）。

分級：

- 里程碑和慢操作警告**默認開啟**：開銷極小，用戶回報問題時診斷包裡直接就有數據；
- 詳細計時（例如每次重建 VPN 的分段耗時）由設定中的「詳細性能日誌」控制，**默認關閉**。

驗收時，用 Release 構建分別測量開啟和關閉指標日誌時的冷啟動，確認差異在測量誤差以內（progress-android.md P-1）。

## 8. UI 庫對比：AtomUI / Fluent / Material.Avalonia

任務見 progress-android.md P-11，結果填入本節。

### 8.1 背景

- Android 的 5 個頁面只用了 11 種 AtomUI 控件：`Button`、`Card`、`Segmented`、`IconPresenter`、`Alert`、`Tag`、`ToggleSwitch`、`Statistic`、`ComboBox`、`Spin`、`LineEdit`；其餘是 Avalonia 的基礎控件。
- `App.axaml` 同時加載了 `FluentTheme`（`ListBox` 等基礎控件需要），現在是兩套主題並存。
- AtomUI 及其依賴（DynamicData、Reactive、SVG 庫，可能還有 XML 庫）約佔裁剪後託管代碼 31.5 MB 中的 14 MB。
- AtomUI 為桌面設計：`Segmented` 不響應觸控（由 `TouchAdapters` 補上）、默認字體沒有中文且不回退、控件高度 32px，小於 Android 建議的 48dp 觸控目標。

### 8.2 遷移成本（如果決定換）

- 11 種控件：`Card`、`Tag`、`Alert`、`Statistic` 用 `Border` 加樣式；`Segmented` 用橫向 `ListBox` 或 `RadioButton`；`Spin` 用 `ProgressBar`；`Button`、`ComboBox`、`ToggleSwitch`、`LineEdit` 用 Fluent 自帶的。
- 界面文本：從 AtomUI 的 XLIFF 目錄遷移到別的機制（例如 Android 的 `strings.xml`）。
- 明暗主題切換：`AppearanceService` 建立在 AtomUI 的主題管理之上，需要重寫。
- 圖標：Ant Design 圖標改為矢量路徑。
- 與桌面版的風格不再一致：android.md 已確定兩邊不共用視圖，影響不大；可以沿用 Ant Design 的配色保持品牌感。

### 8.3 結果

| 方案 | APK（x64 模擬器） | 託管代碼 | 冷啟動中位數 | 首幀 | PSS | 滾動掉幀 | 結論 |
|---|---|---|---|---|---|---|---|
| AtomUI | 18.72 MB | 17.1 MB | 10,197 ms | 3,141 ms | 160.3 MB | 0（60 FPS） | 基準 |
| Fluent + 自訂樣式 | 13.45 MB | 10.7 MB | **6,852 ms** | 2,888 ms | **109.1 MB** | 0（60 FPS） | **明顯較優** |
| Material.Avalonia | 14.93 MB | 13.3 MB | 8,256 ms | 2,531 ms | 119.2 MB | 1（60 FPS） | 介於兩者之間 |

測量條件：2026-11-15，模擬器 x86_64（`sdk_gphone64_x86_64`，Android 15），Release，關閉 AOT；三個變體使用相同頁面、相同啟動時序；`tools/ui-bench/`。冷啟動為進程啟動 → 首幀（應用內 `Choreographer`），`am start -W -S` 的 TotalTime 中位數與應用內數字相差不到 100 ms。

- 去掉 AtomUI、改用 FluentTheme + 基礎控件：**冷啟動快約 33%（少 3.3 秒）、APK 小 5.3 MB、PSS 少 51 MB、託管堆少 6.4 MB、300 行布局快約 30%**。
- AtomUI 的 `UseAtomUI` / `UseDesktopControls` 本身約 372 ms；其餘差距來自程序集體積和主題初始化拖慢 Activity 建立與首幀。
- 這是模擬器、關閉 AOT 的相對比較；真機與 AOT 的絕對值待測。正式對比用 `tools/ui-bench/build.ps1 -Abi arm64 -Aot`。

## 9. 用 Kotlin 完整重寫 Android 版的評估（2026-11-14）

即 §4 方案 F：不改現有的 Android 版，而是按全部功能用 Kotlin 另做一個。**是否這樣做取決於 Android 版的產品定位，需要用戶決定**（progress.md「待決問題」）。

### 9.1 要重寫的範圍（2026-11-14 統計）

| 部分 | 行數 | 是否和桌面版共用 |
|---|---|---|
| Core（規則模型、判決管線、詢問代理、配置文件、篡改檢測、規則時效） | 約 5000 | 共用 |
| Storage（SQLite、遷移、導入導出格式） | 約 2200 | 共用 |
| Engine.Vpn（報文解析、假 DNS、路由規劃） | 約 2100 | Android 專用 |
| Engine.Android | 約 1100 | Android 專用 |
| App.Android（界面、宿主、通知） | 約 4500 + 600 行 XAML | Android 專用 |
| 測試 | 約 5000 | — |

### 9.2 優點

- APK 估計 5–10 MB、冷啟動 1 秒以內；開機恢復和進程重啟更快，保護恢復前的空窗期更短。§3 中「現有技術棧做不到」的各項全部消失。
- 原生界面（Jetpack Compose + Material 3）：觸控、無障礙、大字體、平板和折疊屏、動態配色都是現成的，不再需要 `TouchAdapters`、字體回退等補丁。
- 新系統的 API 當天可用；懸浮窗、磁貼、小工具、WorkManager、協程都是一等公民。
- 轉發模式的生態更成熟（例如 firestack、hev-socks5-tunnel 等用戶態協議棧可以借鑒或集成；注意許可證，NetGuard 是 GPLv3）。
- 工具鏈：Baseline Profiles、R8、Macrobenchmark；16 KB 內存頁、AAB 由 Android Gradle 插件處理；上架 F-Droid 是標準路徑（.NET Android 項目在 F-Droid 構建服務器上很難構建，具體限制待確認）。
- 崩潰堆棧是原生的，排查更直接。

### 9.3 缺點

- **失去和桌面版共用的約 7000 行核心邏輯**，以後每個跨平台功能都要做兩次（可信發布者規則、詢問批量處理、規則去重、專注模式等）。
- **行為漂移**：兩套實現遲早會不一致。需要語言無關的規則語義規範、兩邊共用的測試數據（輸入規則 + 連接 → 期望判決），以及版本化的導入導出格式和數據庫結構規範。
- 兩套工具鏈；CI 增加 Gradle 構建；AGENTS.md 的協作規則和完成標準要為 Kotlin 項目補一份。
- 工作量估計：一個熟練的開發者約 2–3 個月才能和現有功能相當（估計值）；期間轉發模式、DNS 防火牆等拉開差距的功能會推遲。
- 已在模擬器上驗證過的流程要重新驗證（坑都記錄在 AGENTS.md 和 android.md 中，可以參考）。

### 9.4 時機與建議

- 現在是做決定的最佳時機：代碼量小；APK 還在用調試簽名，沒有真實用戶。只要正式發布前定下應用 ID 和簽名密鑰，新版本就能覆蓋安裝；數據庫可以沿用同一套表結構（例如用 Room 打開現有數據庫），遷移用戶數據。
- 定位為**獨立產品、與 NetGuard / RethinkDNS 競爭** → 值得用 Kotlin 重寫，越早越好，同時建立規則語義規範和共用測試數據。
- 定位為**桌面版的配套** → 保留 .NET，按本文 §5 的路線優化。
- 不推薦「Kotlin 界面 + .NET 核心」（§4 方案 D）：Mono 運行時的成本還在，又多了 JNI 橋接的複雜度。
- 拿不定主意時，先做低成本的 P-1（性能指標日誌）和 P-11（UI 庫對比實驗）拿到真機數據。「規則語義規範 + 共用測試數據」無論選哪條路都有價值，可以先做。
