# M3-14R 應用圖標修復交接

- 分支：`agent/deepseek/m3-14r-icons`（從 `main` 的 `c2899da` 建立）
- 未合併 main、未 push。
- 未修改 `docs/progress.md`、`docs/m3-plan.md`。

## 提交

| 提交 | 內容 |
|---|---|
| `771f73a` | `fix(icons): use the native bitmap layout when extracting icons`：修正 `Marshal.SizeOf<Bitmap>()`，提取異常寫入日誌。 |
| `423f5c8` | `fix(icons): rebuild legacy icon transparency from the AND mask`：舊式圖標用 `hbmMask` 補透明度。 |
| `9fc3778` | `fix(icons): retry failed icon extraction instead of caching null`：`TaskResultCache`，失敗結果移除並可重試；新增 App 測試項目。 |
| `7fe7e6b` | `refactor(icons): share icon loading between app and connection rows`：抽出 `IconRowViewModel`。 |
| `1cbb5f7` | `fix(icons): keep connection rows in sync across refreshes`：連線行按鍵合併、同步狀態、只為新行載入圖標。 |
| `f9d419c` | `feat(icons): show app icons in rankings and prompts`：排行榜與詢問彈窗接入圖標。 |

## 五項審查缺陷

1. **圖標永遠提取不出來**
   - `GetObject` 改用 `Marshal.SizeOf<NativeBitmap>()`。
   - `Extract` 捕捉所有例外，寫 `AppLog.Warn` 後返回 null。
   - 失敗的 `Task` 不再永久留在緩存：`TaskResultCache` 在結果為 null 或拋出例外時移除該鍵，下一次調用會重新提取。

2. **舊式圖標全透明**
   - `GetDIBits` 得到 32 位 BGRA 後，如果 Alpha 全為 0，讀取 `hbmMask` 生成透明度。
   - `ApplyMaskAlpha`：掩碼白色（1）透明、黑色（0）不透明。
   - 注意：`docs/progress.md` 條目的括號「黑=透明，白=不透明」與 Windows `hbmMask` 實測相反。用 `C:\Windows\System32\write.exe` 驗證：掩碼白色像素對應背景（顏色為黑色），黑色像素對應圖標內容；若反過來會得到反相、幾乎不可見的圖標。實作與測試採用 Windows 語義。
   - 沒有 `hbmMask` 時退化為全部不透明，避免圖標完全消失。

3. **失敗結果被永久緩存**
   - 新增 `Services/TaskResultCache.cs`，成功結果緩存，null / 例外立即移除。
   - 保留原本 512 項上限的清理策略。

4. **連線頁行對象不同步**
   - 新增 `ConnectionRowMerger.Merge`：按 `ConnectionItemViewModel.KeyOf` 復用 `_all` 中的行對象，同一鍵只調用 `Update`。
   - `ConnectionItemViewModel.Update` 同步 `Connection` 與 `SetKillSupported`；`Connection` setter 通知應用名稱、協定、狀態、位址與 `CanKill`。
   - `ApplyFilter` 復用可見行時同樣調用 `Update`。
   - 語言切換時 `_all` 中的行都會 `RefreshText`，而可見行現在就是 `_all` 的同一批對象。
   - 只有 `ConnectionRowMerger` 真正新建的行才呼叫 `LoadIconAsync`，避免每 2 秒對所有連線重複提取圖標。

5. **圖標加載邏輯重複**
   - 新增 `IconRowViewModel`（`Icon`、`HasIcon`、`LoadIconAsync`）。
   - `AppItemViewModel`、`ConnectionItemViewModel`、`RankItemViewModel` 都繼承它。
   - `PromptViewModel` 因已繼承 `LocalizedViewModel`，保留自己的 `Icon` 屬性，但使用同一個 `AppIconCache`；路徑變化時清空舊圖標並重新載入。

## 其餘界面

- **排行榜**：`RankItemViewModel` 接收 `AppPath` 並在建立時載入；`TopApps` / `TopTraffic` 傳入應用路徑，`TopDestinations` 沒有路徑，保持無圖標。總覽的兩個排行榜模板都加了 16×16 圖標。
- **詢問彈窗**：`PromptCoordinator` 注入 `AppIconCache`，`PromptViewModel` 在 UI 線程建立後非同步載入 `Icon`；`PromptWindow.axaml` 的 `Image.Source` 綁定 `Icon`。服務端 `Updated` 改變應用路徑時會清空並重載。
- **即時連線**：原本已有圖標列，按第 4 項修正行對象與載入時機。

## 測試與驗證

新增 `tests/NetworkControl.App.Tests`（xUnit v3），共 9 個測試：

- `IconExtractionTests`
  - `notepad.exe` 提取出非空、非全透明的 BGRA 像素。
  - `write.exe` 舊式圖標經掩碼處理後同時有透明與不透明像素。
  - `ApplyMaskAlpha` 的白色透明 / 黑色不透明語義。
- `TaskResultCacheTests`
  - 失敗結果不緩存、下一次重試。
  - 成功結果緩存。
  - 提取拋出例外時移除，下一次可恢復。
- `ConnectionRowSyncTests`
  - `Update` 同步 `Connection`、文字屬性通知與 `CanKill`。
  - 合併器復用舊行並更新，不建立新行。
  - 新連線才建立新行。

執行結果：

- `dotnet build NetworkControl.slnx`：0 錯誤、0 警告。
- `dotnet test --solution NetworkControl.slnx`：總計 429，成功 428，跳過 1（Linux 符號鏈接測試，與本任務無關）；`NetworkControl.App.Tests` 9/9 通過。
- `dotnet format NetworkControl.slnx --verify-no-changes --exclude src/NetworkControl.App.Android src/NetworkControl.Engine.Android`：通過。

補充驗證（未提交）：

- 用臨時控制台初始化 Avalonia（`AppBuilder.Configure<Application>().UsePlatformDetect().SetupWithoutStarting()`）後調用 `AppIconCache.GetAsync`：
  - `C:\Windows\System32\notepad.exe` → 32×32 位圖。
  - `C:\Windows\System32\write.exe` → 32×32 位圖（舊式掩碼路徑）。
- 這驗證了 `WriteableBitmap` 建立與圖標像素複製，補足單元測試只測 BGRA 像素、不初始化 Avalonia 的限制。

## 未驗證 / 待用戶確認

- 實際界面效果：應用頁、即時連線、排行榜與詢問彈窗中的圖標顯示（需要看界面）。
- 詢問彈窗只在 XAML 編譯與綁定層面驗證，沒有在真實服務推送下打開彈窗確認圖標。
- 沒有修改 Core / Storage / Service / Android，因此未執行 Android 構建。
- 沒有在正式安裝版服務或 WFP 引擎下驗證；本任務只涉及界面進程。
