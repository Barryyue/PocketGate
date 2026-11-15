package com.networkcontrol.android.kotlin.core.release

data class ReleaseNote(
    val version: String,
    val date: String,
    val features: List<String>,
    val improvements: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

object ReleaseNotes {
    val all: List<ReleaseNote> = listOf(
        ReleaseNote(
            version = "0.6.9",
            date = "2026-11-15",
            features = listOf(
                "靈動島水平 / 垂直偏移與背景透明度改為拖動時即時生效，不需要放開滑桿。",
            ),
        ),
        ReleaseNote(
            version = "0.6.8",
            date = "2026-11-15",
            features = listOf(
                "靈動島新增三種樣式：完整（上下行同時）、循環（上下行輪流）、迷你（狀態欄風格小字）。",
                "循環模式每 2 秒在 ↓ 與 ↑ 之間切換；迷你模式縮小內距、字體與綠點。",
            ),
        ),
        ReleaseNote(
            version = "0.6.7",
            date = "2026-11-15",
            features = listOf(
                "靈動島支援水平 / 垂直偏移（-60 至 +60dp），可向上 / 下 / 左 / 右微調，預設回到原本位置。",
                "靈動島新增透明度滑桿，可調整背景濃淡。",
                "專注模式與勿擾合併到相鄰位置，不再被模式切換隔開。",
                "保護關閉時開啟勿擾會自動開啟保護；勿擾期間關閉保護會顯示警告。",
            ),
        ),
        ReleaseNote(
            version = "0.6.6",
            date = "2026-11-15",
            features = listOf(
                "保護關閉時開始專注會自動開啟保護；若中途關閉保護，專注卡片顯示警告與「開啟保護」。",
                "修正專注卡片按鈕擠在一起、文字變形的問題：延長 / 結束專注分兩欄，保存配置文件另起一行。",
            ),
        ),
        ReleaseNote(
            version = "0.6.5",
            date = "2026-11-15",
            features = listOf(
                "設定 → 關於的版本號可點擊查看本版本新增功能與優化內容。",
                "版本說明支援「歷史版本」，可回看過去各版本的變更。",
            ),
        ),
        ReleaseNote(
            version = "0.6.4",
            date = "2026-11-15",
            features = listOf(
                "vivo / iQOO 靈動島相容模式：自動放到系統島下方，可關閉。",
                "靈動島額外頂部偏移 0–48dp 滑桿，真機可微調避免遮擋。",
            ),
            improvements = listOf(
                "詢問面板跟隨靈動島偏移，避免兩個懸浮窗重疊。",
            ),
        ),
        ReleaseNote(
            version = "0.6.3",
            date = "2026-11-15",
            features = listOf(
                "靈動島更名並預設開啟：保護開啟時在頂部顯示上傳 / 下載速度。",
                "設定可關閉靈動島；授予懸浮窗權限後返回設定立即生效。",
            ),
        ),
        ReleaseNote(
            version = "0.6.2",
            date = "2026-11-15",
            features = listOf(
                "新增靈動島（速度顯示），可開關、鎖屏隱藏、點擊開啟主界面。",
                "移除「直到重啟」詢問選項，保留允許一次 / 永久 / 限時。",
                "限時規則時長可在設定配置，例如 30 分鐘。",
            ),
            improvements = listOf(
                "詢問面板在靈動島開啟時自動下移。",
            ),
        ),
        ReleaseNote(
            version = "0.6.1",
            date = "2026-11-15",
            features = listOf(
                "「允許一次」改為應用會話：目標應用離開前台後失效，下次打開重新詢問。",
            ),
            notes = listOf(
                "Android 無法可靠偵測其他應用被滑掉 / 強制停止，目前以離開前台為近似。",
            ),
        ),
        ReleaseNote(
            version = "0.6.0",
            date = "2026-11-15",
            features = listOf(
                "懸浮面板自訂垂直位置，設定頁可用拖動預覽調整。",
                "一鍵建立專注白名單配置文件；進行中的專注可保存為長期配置文件。",
                "專注時長與時間表改用 Android 原生圓盤時間選擇，支援自訂分鐘。",
                "M4 Phase 0 轉發實驗：可把流量轉到本機 SOCKS5（尚未接入規則 / 詢問）。",
            ),
        ),
        ReleaseNote(
            version = "0.5.0",
            date = "2026-11-15",
            features = listOf(
                "新增 AAB 打包、NC_ANDROID_KEYSTORE 正式簽名支援。",
                "設定 → 關於可檢查 GitHub 新版本。",
                "診斷包加入網絡 / SIM / 私人 DNS / IPv6 / 懸浮權限。",
                "新增 adb 冒煙腳本與真機回歸清單。",
            ),
            improvements = listOf(
                "初始化日誌加入耗時；release 維持 R8 與資源壓縮。",
            ),
        ),
        ReleaseNote(
            version = "0.4.0",
            date = "2026-11-15",
            features = listOf(
                "懸浮詢問面板：可在其他應用上方顯示，支援頂部 / 底部 / 自訂位置。",
                "鎖屏斷網、專注模式、專注時間表、允許一次。",
                "詢問內容顯示設定、新應用安裝提醒、私人 DNS 提示、應用鎖。",
            ),
            improvements = listOf(
                "詢問通知改為安靜渠道，避免蓋住懸浮面板。",
                "導航內容描述與平板最大寬度。",
            ),
        ),
        ReleaseNote(
            version = "0.3.0",
            date = "2026-11-15",
            features = listOf(
                "Wi-Fi / 行動網路 / SIM 流量拆分與規則條件。",
                "連線記錄 CSV 匯出、應用詳情、規則去重與按應用分組。",
                "可信發布者 / 系統應用默認允許、批量詢問。",
            ),
            improvements = listOf(
                "通知動作在進程回收後仍可建立規則。",
                "修正匯入 Replace 誤刪規則、CSV 截斷、流量跨日等問題。",
            ),
        ),
        ReleaseNote(
            version = "0.2.0",
            date = "2026-11-15",
            features = listOf(
                "應用列表權限引導、主題色（10 預設 + HSV 自訂）。",
                "按網絡類型 / SSID 自動切換配置文件。",
                "即時上下行網速、勿擾與摘要。",
            ),
            improvements = listOf(
                "應用三態規則按配置文件隔離，修正跨配置文件共享 bug。",
            ),
        ),
        ReleaseNote(
            version = "0.1.0",
            date = "2026-11-14",
            features = listOf(
                "Kotlin + Compose Android 版骨架、SQLite、規則引擎。",
                "黑洞 VPN、假 DNS、詢問、記錄、應用、設定。",
            ),
        ),
    )

    fun current(versionName: String): ReleaseNote? =
        all.firstOrNull { versionName.startsWith(it.version) }
}
