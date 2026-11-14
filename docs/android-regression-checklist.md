# Android 回歸清單（Kotlin 版）

> 用途：真機 / 模擬器回歸的固定清單。每次發版至少跑「冒煙」一節；標 `[真機]` 的項目必須在實機確認。
> 相關指令：`.\scripts\android-kotlin-smoke.ps1`、`.\scripts\android-kotlin.ps1 publish`。
> 最後更新：2026-11-15（v0.4.0）

## 1. 冒煙（模擬器可跑）

- [ ] `.\scripts\android-kotlin-smoke.ps1 -Serial emulator-5554 -Install` 通過，無 `FATAL EXCEPTION`。
- [ ] 首次啟動完成初始化，五個分頁（總覽 / 應用 / 規則 / 記錄 / 設定）可切換。
- [ ] 開啟保護，VpnService 前景通知出現；Chrome 首次連線觸發詢問。
- [ ] 允許後 Chrome 可上網；拒絕後連線失敗；記錄頁可見對應原因。
- [ ] 規則頁按應用分組；刪除規則後重新詢問。
- [ ] 應用詳情可開啟，顯示規則與最近活動。
- [ ] CSV 匯出可開啟 SAF；匯出文件有 UTF-8 BOM 與 CRLF。
- [ ] 匯入 JSON 預覽（合併 / 取代）數字正確；取代不會誤刪同語意規則。
- [ ] 專注模式 15 分鐘：開始、剩餘時間、延長、結束。
- [ ] 專注時間表：新增 / 啟停 / 刪除；時段內自動進入專注。
- [ ] 懸浮面板：授予「顯示在其他應用程式上層」後，Chrome 前台觸發時顯示；按允許 / 拒絕 / 稍後可收起。
- [ ] 通知「僅使用通知」模式：不顯示懸浮面板，仍有通知。
- [ ] 鎖屏斷網：開啟後鎖屏，日誌出現 `replan mode=BlockAll`；解鎖恢復。
- [ ] 應用鎖：設定系統鎖屏後啟用；重開應用要求解鎖；取消解鎖不會進入主界面。
- [ ] 新應用安裝：`adb install` 一個測試 APK，收到「已安裝」通知。
- [ ] 私人 DNS 提示：把私人 DNS 設為指定主機名稱，總覽出現提示。

## 2. 真機 / 網絡

- [ ] `[真機]` Wi-Fi / 行動網路切換時，配置文件自動切換正確。
- [ ] `[真機]` 雙 SIM：切換預設資料 SIM 後，SIM 條件規則與流量拆分正確。
- [ ] `[真機]` 無 `READ_PHONE_STATE` 時，SIM 選擇顯示引導，流量降級為行動網路合計。
- [ ] `[真機]` 私人 DNS：關閉 / 自動 / 指定主機名稱三種模式的域名規則行為。
- [ ] `[真機]` 純 IPv6 或 IPv6-only 網絡：DNS、TCP、詢問與記錄。
- [ ] `[真機]` always-on VPN、開機恢復、Tile 開關。
- [ ] `[真機]` 懸浮面板在微信聊天頁、微信支付頁、銀行 App 的顯示 / 隱藏行為。
- [ ] `[真機]` 鍵盤打開時懸浮面板不被輸入法遮擋（預設頂部）；底部模式的行為。
- [ ] `[真機]` 鎖屏 / 息屏時只顯示通知，不顯示懸浮面板與連線詳情。
- [ ] `[真機]` 通知動作：殺掉進程後點「允許 / 拒絕」，仍建立規則或提示過期。
- [ ] `[真機]` 批量詢問：多個套用待決時，全選允許 / 拒絕 / 稍後。
- [ ] `[真機]` 應用鎖：PIN / 圖案 / 密碼 / 生物辨識；背景 1 分鐘後重新鎖定。

## 3. OEM ROM

- [ ] MIUI / HyperOS：應用列表權限、背景彈出界面、自啟動、電池白名單。
- [ ] ColorOS / realme UI：應用列表權限、懸浮窗、背景限制。
- [ ] OriginOS / Funtouch：同上。
- [ ] EMUI / HarmonyOS：同上。
- [ ] One UI：應用列表權限（`com.samsung.android.permission.GET_APP_LIST`）、雙 SIM。
- [ ] Pixel / AOSP：基準行為。

## 4. 發版檢查

- [ ] `dotnet`/Android 無關；`.\gradlew.bat testDebugUnitTest` 全綠。
- [ ] `.\scripts\android-kotlin.ps1 publish` 成功，記錄 APK 大小與 SHA-256。
- [ ] `.\scripts\android-kotlin.ps1 aab` 成功（正式簽名時設定 `NC_ANDROID_KEYSTORE` 等環境變量）。
- [ ] `docs/kotlin-feature-log.md` 追加 K-xxx。
- [ ] `docs/android-kotlin.md` 版本章節記錄 APK / 大小 / SHA-256 / 未驗證項。
- [ ] 版本號只改 `android-kotlin/app/build.gradle.kts` 與 `scripts/android-kotlin.ps1`。

## 5. 已知限制

- 懸浮面板可能被 `setHideOverlayWindows(true)`、系統敏感畫面或 OEM 額外權限隱藏；通知永遠保留。
- `TYPE_APPLICATION_OVERLAY` 在輸入法之下；預設頂部是為了微信輸入場景。
- 應用鎖第一版只鎖啟動與背景超過 1 分鐘，尚未在修改規則 / 模式時即時驗證。
- 轉發模式尚未實現；黑洞模式仍是預設。
