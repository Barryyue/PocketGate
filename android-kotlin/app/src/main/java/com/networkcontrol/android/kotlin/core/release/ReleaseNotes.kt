package com.networkcontrol.android.kotlin.core.release

/** 版本說明使用的語言，跟隨應用界面語言（見 `UiStrings.noteLanguage`）。 */
enum class NoteLanguage {
    English,
    SimplifiedChinese,
    TraditionalChinese,
    ;

    companion object {
        /** 與 `UiStrings` 相同的判斷：zh-TW / zh-Hant 為繁體，其他 zh 為簡體，其餘為英文。 */
        fun fromTag(languageTag: String): NoteLanguage = when {
            languageTag.startsWith("zh-TW", ignoreCase = true) ||
                languageTag.startsWith("zh-Hant", ignoreCase = true) -> TraditionalChinese
            languageTag.startsWith("zh", ignoreCase = true) -> SimplifiedChinese
            else -> English
        }
    }
}

/** 一則三語文字；新增版本說明時三種語言都必須填寫。 */
data class NoteText(val en: String, val zhHans: String, val zhHant: String) {
    operator fun get(language: NoteLanguage): String = when (language) {
        NoteLanguage.English -> en
        NoteLanguage.SimplifiedChinese -> zhHans
        NoteLanguage.TraditionalChinese -> zhHant
    }
}

data class ReleaseNote(
    val version: String,
    val date: String,
    val features: List<NoteText>,
    val improvements: List<NoteText> = emptyList(),
    val notes: List<NoteText> = emptyList(),
)

private fun t(en: String, zhHans: String, zhHant: String) = NoteText(en, zhHans, zhHant)

object ReleaseNotes {
    val all: List<ReleaseNote> = listOf(
        ReleaseNote(
            version = "0.9.3",
            date = "2026-11-16",
            features = listOf(
                t(
                    "If the forwarding relay or the virtual network reader fails while protection is on, the tunnel is rebuilt automatically.",
                    "转发中继或虚拟网卡读取失败时，只要保护仍开启，会自动重建隧道。",
                    "轉發中繼或虛擬網卡讀取失敗時，只要保護仍開啟，會自動重建通道。",
                ),
                t(
                    "The \"Later\" suppression now survives in-app screen switches; it is only cleared after the app really leaves the foreground.",
                    "「稍后」抑制在应用内切换页面时不再被误清除，只有真正离开前台后才重新询问。",
                    "「稍後」抑制在應用程式內切換頁面時不再被誤清除，只有真正離開前景後才重新詢問。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.9.2",
            date = "2026-11-16",
            features = listOf(
                t(
                    "Release notes now follow the app language (English / Simplified Chinese / Traditional Chinese).",
                    "版本说明跟随应用语言显示（英文 / 简体中文 / 繁体中文）。",
                    "版本說明跟隨應用程式語言顯示（英文 / 簡體中文 / 繁體中文）。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.9.1",
            date = "2026-11-16",
            features = listOf(
                t(
                    "In forwarding mode, rules with domain, port or protocol conditions now apply to each connection (for example, allow an app to reach only certain domains, or block port 25 for everyone).",
                    "转发模式下，带域名、端口、协议条件的规则也会逐条连接生效（例如只允许某应用访问指定域名、全局禁止 25 端口）。",
                    "轉發模式下，帶網域、連接埠、協定條件的規則也會逐條連線生效（例如只允許某應用程式存取指定網域、全域禁止 25 連接埠）。",
                ),
                t(
                    "If the system stops the VPN service while protection is still on, the tunnel is rebuilt automatically.",
                    "VPN 服务被系统结束而防护仍开启时，会自动重建隧道。",
                    "VPN 服務被系統結束而防護仍開啟時，會自動重建通道。",
                ),
            ),
            improvements = listOf(
                t(
                    "\"Only this time\" is no longer revoked early when you switch screens inside the app.",
                    "「仅一次」不再因为应用内切换页面而被提前撤销。",
                    "「僅限這次」不再因為在應用程式內切換頁面而被提前撤銷。",
                ),
                t(
                    "Toggling protection from Quick Settings on the lock screen now requires unlocking first.",
                    "锁屏时从快速设置切换防护需要先解锁。",
                    "鎖定畫面時從快速設定切換防護需要先解鎖。",
                ),
                t(
                    "Tapping Allow / Deny in a notification no longer crashes when the prompt has already expired.",
                    "通知里的「允许 / 拒绝」在询问已过期时不再闪退。",
                    "通知中的「允許 / 拒絕」在詢問已過期時不再閃退。",
                ),
                t(
                    "In blackhole mode, connections that a rule allows but are still dropped (apps allowed only for some domains) are now recorded as denied.",
                    "黑洞模式下只对部分域名放行、实际仍被丢弃的连接，记录改为如实显示为拒绝。",
                    "黑洞模式下只對部分網域放行、實際仍被丟棄的連線，紀錄改為如實顯示為拒絕。",
                ),
                t(
                    "The legacy shared-rule migration now runs only once instead of moving global rules into the default profile on every start.",
                    "旧版共享规则迁移只执行一次，不再每次启动都把全局规则移到默认配置文件。",
                    "舊版共用規則遷移只執行一次，不再每次啟動都把全域規則移到預設設定檔。",
                ),
                t(
                    "Battery: expired-rule checks wake only when a rule is due; speed sampling pauses while the screen is off; the foreground app is no longer checked every second when there are no prompts.",
                    "省电：过期规则检查改为按到期时间唤醒；关屏时暂停网速取样；没有询问时不再每秒检查前台应用。",
                    "省電：過期規則檢查改為按到期時間喚醒；關閉螢幕時暫停網速取樣；沒有詢問時不再每秒檢查前景應用程式。",
                ),
                t(
                    "Forwarding performance: later packets of the same UDP flow (QUIC, video) take a fast path; blocked connections look up their owner only once.",
                    "转发性能：同一条 UDP 流（QUIC、视频）的后续数据包走快速路径；被拦连接只查询一次连接归属。",
                    "轉發效能：同一條 UDP 流（QUIC、影片）的後續封包走快速路徑；被攔連線只查詢一次連線歸屬。",
                ),
                t(
                    "Repeated blocked connections to the same target no longer pile up in a single prompt.",
                    "同一目标的重复被拦连接不再无限累积在同一个询问里。",
                    "同一目標的重複被攔連線不再無限累積在同一個詢問裡。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.9.0",
            date = "2026-11-16",
            features = listOf(
                t(
                    "Forwarding mode now works together with the firewall: each new connection is judged by rules, prompts, Focus and lock-screen blocking, and only allowed ones are forwarded to the local SOCKS5 proxy (for example FlClash with its VPN off).",
                    "转发模式可以和防火墙同时工作：每条新连接先按规则、询问、专注模式和锁屏断网判决，只有允许的才转发到本机 SOCKS5（例如关闭 VPN 的 FlClash）。",
                    "轉發模式可以和防火牆同時運作：每條新連線先按規則、詢問、專注模式和鎖定畫面斷網判決，只有允許的才轉發到本機 SOCKS5（例如關閉 VPN 的 FlClash）。",
                ),
                t(
                    "DNS is now mapped locally: connections reach the proxy by domain name, so the proxy's domain routing rules work and DNS no longer needs UDP support from the proxy; prompts and records show domains.",
                    "DNS 改为本地映射：连接以域名发给代理，代理的域名分流规则能生效，DNS 也不再依赖代理支持 UDP；询问和记录里能看到域名。",
                    "DNS 改為本機對應：連線以網域名稱送給代理，代理的網域分流規則能生效，DNS 也不再依賴代理支援 UDP；詢問和紀錄裡能看到網域。",
                ),
                t(
                    "New \"Direct apps\": selected apps bypass the VPN entirely.",
                    "新增「直连应用」：选中的应用完全不经过 VPN。",
                    "新增「直連應用程式」：選取的應用程式完全不經過 VPN。",
                ),
                t(
                    "The upstream proxy is checked every 30 seconds and a warning appears on Overview when it is unreachable; if forwarding cannot start, the app falls back to blackhole mode and retries periodically.",
                    "上游代理每 30 秒检查一次，连不上时在概览显示提示；转发无法启动时回到黑洞模式并定期重试。",
                    "上游代理每 30 秒檢查一次，連不上時在總覽顯示提示；轉發無法啟動時回到黑洞模式並定期重試。",
                ),
            ),
            improvements = listOf(
                t(
                    "Turning off \"Forward UDP\" now rejects UDP so apps fall back to TCP, instead of switching to a UDP-over-TCP mode that proxies do not support.",
                    "关闭「转发 UDP」现在会拒绝 UDP 让应用改用 TCP，不再切换到代理不支持的 UDP-over-TCP。",
                    "關閉「轉發 UDP」現在會拒絕 UDP 讓應用程式改用 TCP，不再切換到代理不支援的 UDP-over-TCP。",
                ),
                t(
                    "During Focus and lock-screen blocking, blocked apps no longer trigger prompts and records no longer show them as allowed.",
                    "专注模式和锁屏断网期间不再对被拦的应用弹出询问，记录也不再显示为允许。",
                    "專注模式和鎖定畫面斷網期間不再對被攔的應用程式跳出詢問，紀錄也不再顯示為允許。",
                ),
                t(
                    "The forwarding component now logs warnings only, and its log is cleared at the next start once it exceeds 1 MB.",
                    "转发组件日志改为只记警告，超过 1 MB 会在下次启动时清空。",
                    "轉發元件日誌改為只記警告，超過 1 MB 會在下次啟動時清空。",
                ),
            ),
            notes = listOf(
                t(
                    "Domain mapping currently supports IPv4 only; apps with their own DNS (not using system DNS) are still handled as ordinary UDP connections.",
                    "映射域名目前只支持 IPv4；应用自带的 DNS（不走系统 DNS）仍按普通 UDP 连接处理。",
                    "網域對應目前只支援 IPv4；應用程式自帶的 DNS（不走系統 DNS）仍按一般 UDP 連線處理。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.8.5",
            date = "2026-11-16",
            features = listOf(
                t(
                    "\"Later\" now follows the foreground session: no more prompts during this session of the app; you are asked again after it goes to the background and comes back.",
                    "「稍后」改为按前台会话判断：本次应用会话内不再提醒，切到后台再回到该应用后会重新询问。",
                    "「稍後」改為按前景工作階段判斷：本次應用程式工作階段內不再提醒，切到背景再回到該應用程式後會重新詢問。",
                ),
            ),
            notes = listOf(
                t(
                    "Usage access is required to detect the foreground app; without it a short cooldown is used instead.",
                    "需要「使用情况访问」权限来判断前台应用；未授权时退回短时间冷却。",
                    "需要「使用情況存取權」來判斷前景應用程式；未授權時退回短時間冷卻。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.8.4",
            date = "2026-11-16",
            features = listOf(
                t(
                    "The \"Later\" quiet period is shortened from 10 minutes to 1 minute; the app is asked again the next time it connects.",
                    "「稍后」的静默时间从 10 分钟缩短为 1 分钟；之后该应用再次联网会重新询问。",
                    "「稍後」的靜默時間從 10 分鐘縮短為 1 分鐘；之後該應用程式再次連網會重新詢問。",
                ),
            ),
            notes = listOf(
                t(
                    "Avoids frequent interruptions without going silent for a long time after tapping Later.",
                    "避免频繁骚扰的同时，不再出现点了稍后很久都不提醒的情况。",
                    "避免頻繁打擾的同時，不再出現按了稍後很久都不提醒的情況。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.8.3",
            date = "2026-11-16",
            features = listOf(
                t(
                    "The prompt panel now decides in one tap: Deny / Only this time / Allow, without choosing a duration first.",
                    "询问面板改为一键决策：禁止 / 仅一次 / 允许，不再需要先选时长再点按钮。",
                    "詢問面板改為一鍵決定：禁止 / 僅限這次 / 允許，不再需要先選時長再按按鈕。",
                ),
                t(
                    "Timed allow moves to a shortcut on the second row using the duration from Settings; turning off \"Show timed allow\" leaves just three buttons.",
                    "限时允许改为第二行的快捷入口，使用设置里的限时时长；关闭「显示限时允许」后就是红魔式三个按钮。",
                    "限時允許改為第二列的快捷入口，使用設定裡的限時時長；關閉「顯示限時允許」後就只剩三個按鈕。",
                ),
            ),
            notes = listOf(
                t(
                    "Deny and Allow take effect immediately in one tap; Later still just closes the panel without creating a rule.",
                    "禁止和允许都是一次点击立即生效；稍后仍然只关闭弹窗、不产生规则。",
                    "禁止和允許都是一次點擊立即生效；稍後仍然只關閉彈出視窗、不產生規則。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.8.2",
            date = "2026-11-16",
            features = listOf(
                t(
                    "Speed units are now written in full: B/s, KB/s, MB/s, GB/s, TB/s, including the mini style.",
                    "速度单位改为完整写法：B/s、KB/s、MB/s、GB/s、TB/s，迷你样式也不再缩写。",
                    "速度單位改為完整寫法：B/s、KB/s、MB/s、GB/s、TB/s，迷你樣式也不再縮寫。",
                ),
                t(
                    "Size units are written in full as well: B, KB, MB, GB, TB.",
                    "体积单位同样使用完整写法：B、KB、MB、GB、TB。",
                    "容量單位同樣使用完整寫法：B、KB、MB、GB、TB。",
                ),
                t(
                    "Status bar text color now follows the system light/dark setting and wallpaper tone; dark wallpapers use white text automatically.",
                    "状态栏文字颜色改为参考系统深浅色和桌布色调，深色桌布自动使用白字。",
                    "狀態列文字顏色改為參考系統深淺色和桌布色調，深色桌布自動使用白字。",
                ),
                t(
                    "\"Allow once\" in the prompt panel is renamed \"Only this time\", matching Android permission dialogs.",
                    "询问面板的「允许一次」改为「仅一次」，与 Android 权限风格一致。",
                    "詢問面板的「允許一次」改為「僅限這次」，與 Android 權限風格一致。",
                ),
            ),
            notes = listOf(
                t(
                    "Android has no API to read status bar icon colors, so the wallpaper approximation may still mismatch in some apps; use a custom color to override.",
                    "Android 不提供读取状态栏图示颜色的 API，桌布近似在部分应用内仍可能不一致；可用自定义颜色覆盖。",
                    "Android 不提供讀取狀態列圖示顏色的 API，桌布近似在部分應用程式內仍可能不一致；可用自訂顏色覆蓋。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.8.1",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Forwarding settings add a \"Proxy app\" choice: the selected app bypasses this VPN so the proxy's own connections do not loop back.",
                    "转发设置新增「代理应用」选择：被选中的应用流量绕过本 VPN，避免代理自身的连接回环。",
                    "轉發設定新增「代理應用程式」選擇：選取的應用程式流量繞過本 VPN，避免代理自身的連線迴圈。",
                ),
                t(
                    "Forwarding settings explain VPN coexistence: Android allows only one VPN at a time, so turn off the proxy app's TUN/VPN mode and keep only its SOCKS5 port.",
                    "转发设置补充 VPN 共存提示：Android 一次只允许一个 VPN，请关闭代理应用的 TUN/VPN 模式，只保留 SOCKS5 端口。",
                    "轉發設定補充 VPN 共存提示：Android 一次只允許一個 VPN，請關閉代理應用程式的 TUN/VPN 模式，只保留 SOCKS5 連接埠。",
                ),
            ),
            notes = listOf(
                t(
                    "The proxy app must keep running; turn off its TUN mode before enabling protection here.",
                    "代理应用需要保持运行；开启本应用的保护前，先在代理应用里关闭 TUN。",
                    "代理應用程式需要保持執行；開啟本應用程式的保護前，先在代理應用程式裡關閉 TUN。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.8.0",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Speed island settings get their own page: preview, content, style, font, color, status dot and position in one place.",
                    "灵动岛设置独立成页：预览、显示内容、样式、字体、颜色、状态点和位置集中管理。",
                    "動態島設定獨立成頁：預覽、顯示內容、樣式、字型、顏色、狀態點和位置集中管理。",
                ),
                t(
                    "Selectable content: upload/download speed, today's traffic, today's blocks, pending prompts, current network.",
                    "显示内容可切换：上下行速度、今日流量、今日拦截、待处理询问、当前网络。",
                    "顯示內容可切換：上下行速度、今日流量、今日攔截、待處理詢問、目前網路。",
                ),
                t(
                    "New font size (80%–160%) and weight (regular / medium / bold).",
                    "新增字体大小（80%–160%）与字体粗细（常规 / 中等 / 粗体）。",
                    "新增字型大小（80%–160%）與字重（一般 / 中等 / 粗體）。",
                ),
                t(
                    "The floating prompt can hide the \"Timed allow\" option, keeping only allow once, permanent and deny.",
                    "悬浮询问可关闭「限时允许」选项，只保留允许一次、永久、拒绝。",
                    "懸浮詢問可關閉「限時允許」選項，只保留允許一次、永久、拒絕。",
                ),
                t(
                    "Forwarding fix: the hev tunnel address now matches the VPN interface and incompatible offload is disabled, so HTTP/HTTPS passes through SOCKS5.",
                    "转发模式修复：hev 隧道地址与 VPN 接口一致、关闭不兼容的 offload，HTTP/HTTPS 流量可正常通过 SOCKS5。",
                    "轉發模式修正：hev 通道位址與 VPN 介面一致、關閉不相容的 offload，HTTP/HTTPS 流量可正常通過 SOCKS5。",
                ),
                t(
                    "Forwarding settings add \"Test proxy\" and a DNS hint to help check whether the proxy works.",
                    "转发设置新增「测试代理」和 DNS 提示，便于排查代理是否可用。",
                    "轉發設定新增「測試代理」和 DNS 提示，方便排查代理是否可用。",
                ),
            ),
            notes = listOf(
                t(
                    "Proxy forwarding is still experimental (Phase 0: all traffic forwarded, no per-app verdicts yet).",
                    "代理转发仍是实验功能（Phase 0，全流量转发，未接入按应用判决）。",
                    "代理轉發仍是實驗功能（Phase 0，全流量轉發，尚未接入按應用程式判決）。",
                ),
                t(
                    "DNS needs UDP support from the proxy; if pages fail to load but IPs connect, enable UDP forwarding or set a system Private DNS.",
                    "DNS 需要代理支持 UDP；若网页打不开但 IP 可连，请开启 UDP 转发或配置系统私人 DNS。",
                    "DNS 需要代理支援 UDP；若網頁打不開但 IP 可連，請開啟 UDP 轉發或設定系統私人 DNS。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.7.0",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Speed island background opacity can go down to 0: background and border become fully transparent, leaving only text; a shadow keeps it readable and touches pass through so the status bar can still be pulled down.",
                    "灵动岛背景透明度可调到 0：背景与边框完全透明，只留文字；全透明时自动用阴影保持可读性，并暂停拦截触控以免挡住状态栏下拉。",
                    "動態島背景透明度可調到 0：背景與邊框完全透明，只留文字；全透明時自動用陰影保持可讀性，並暫停攔截觸控以免擋住狀態列下拉。",
                ),
                t(
                    "Speed island text color can follow the theme, follow the status bar, or use a custom color from presets or an HSV picker.",
                    "灵动岛文字颜色新增跟随主题、跟随状态栏、自定义颜色三种模式，自定义色可用预设色盘或 HSV 调色。",
                    "動態島文字顏色新增跟隨主題、跟隨狀態列、自訂顏色三種模式，自訂色可用預設色盤或 HSV 調色。",
                ),
                t(
                    "Status bar text color switches between black and white with day/night mode; enabling \"Try placing in status bar\" defaults to following the status bar.",
                    "状态栏文字色会依日夜模式自动选黑或白；开启「尝试放进状态栏」时默认切到跟随状态栏。",
                    "狀態列文字色會依日夜模式自動選黑或白；開啟「嘗試放進狀態列」時預設切到跟隨狀態列。",
                ),
                t(
                    "The green status dot can be shown or hidden, with a custom color.",
                    "绿色状态点可显示 / 隐藏，并可自定义颜色。",
                    "綠色狀態點可顯示 / 隱藏，並可自訂顏色。",
                ),
                t(
                    "Upload / download order can be swapped in the full, cycling and mini styles.",
                    "上传 / 下载的显示顺序可以互换，套用到完整、循环、迷你三种样式。",
                    "上傳 / 下載的顯示順序可以互換，套用到完整、循環、迷你三種樣式。",
                ),
            ),
            notes = listOf(
                t(
                    "Status bar mode is still limited by Android: third-party overlays sit below the system status bar and some ROMs may cover them.",
                    "状态栏模式仍受 Android 限制：第三方悬浮窗在系统状态栏之下，部分 ROM 可能遮住。",
                    "狀態列模式仍受 Android 限制：第三方懸浮視窗在系統狀態列之下，部分 ROM 可能遮住。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.10",
            date = "2026-11-15",
            features = listOf(
                t(
                    "New \"Try placing in status bar\" mode for the speed island: the window may enter the status bar area and the vertical offset can be negative.",
                    "灵动岛新增「尝试放进状态栏」模式：允许窗口进入状态栏坐标区，垂直偏移可为负值。",
                    "動態島新增「嘗試放進狀態列」模式：允許視窗進入狀態列座標區，垂直偏移可為負值。",
                ),
            ),
            notes = listOf(
                t(
                    "Android draws the system status bar above third-party overlays and some ROMs still cover it; turn this mode off if you cannot see the island.",
                    "Android 会把系统状态栏画在第三方悬浮窗之上，部分 ROM 仍会遮住；若看不到请关闭此模式。",
                    "Android 會把系統狀態列畫在第三方懸浮視窗之上，部分 ROM 仍會遮住；若看不到請關閉此模式。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.9",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Speed island horizontal/vertical offset and background opacity now apply live while dragging, without releasing the slider.",
                    "灵动岛水平 / 垂直偏移与背景透明度改为拖动时即时生效，不需要放开滑杆。",
                    "動態島水平 / 垂直偏移與背景透明度改為拖動時即時生效，不需要放開滑桿。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.8",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Three speed island styles: full (upload and download together), cycling (alternating) and mini (small status-bar-style text).",
                    "灵动岛新增三种样式：完整（上下行同时）、循环（上下行轮流）、迷你（状态栏风格小字）。",
                    "動態島新增三種樣式：完整（上下行同時）、循環（上下行輪流）、迷你（狀態列風格小字）。",
                ),
                t(
                    "Cycling mode switches between ↓ and ↑ every 2 seconds; mini mode shrinks padding, font and the green dot.",
                    "循环模式每 2 秒在 ↓ 与 ↑ 之间切换；迷你模式缩小内距、字体与绿点。",
                    "循環模式每 2 秒在 ↓ 與 ↑ 之間切換；迷你模式縮小內距、字型與綠點。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.7",
            date = "2026-11-15",
            features = listOf(
                t(
                    "The speed island supports horizontal/vertical offsets (-60 to +60 dp) for fine positioning; the default is the original position.",
                    "灵动岛支持水平 / 垂直偏移（-60 至 +60dp），可向上 / 下 / 左 / 右微调，默认回到原本位置。",
                    "動態島支援水平 / 垂直偏移（-60 至 +60dp），可向上 / 下 / 左 / 右微調，預設回到原本位置。",
                ),
                t(
                    "New opacity slider for the speed island background.",
                    "灵动岛新增透明度滑杆，可调整背景浓淡。",
                    "動態島新增透明度滑桿，可調整背景濃淡。",
                ),
                t(
                    "Focus and Do Not Disturb are now next to each other instead of being separated by the mode switch.",
                    "专注模式与勿扰合并到相邻位置，不再被模式切换隔开。",
                    "專注模式與勿擾合併到相鄰位置，不再被模式切換隔開。",
                ),
                t(
                    "Turning on Do Not Disturb while protection is off turns protection on; turning protection off during Do Not Disturb shows a warning.",
                    "保护关闭时开启勿扰会自动开启保护；勿扰期间关闭保护会显示警告。",
                    "保護關閉時開啟勿擾會自動開啟保護；勿擾期間關閉保護會顯示警告。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.6",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Starting Focus while protection is off turns protection on; if protection is turned off midway, the Focus card shows a warning and \"Turn on protection\".",
                    "保护关闭时开始专注会自动开启保护；若中途关闭保护，专注卡片显示警告与「开启保护」。",
                    "保護關閉時開始專注會自動開啟保護；若中途關閉保護，專注卡片顯示警告與「開啟保護」。",
                ),
                t(
                    "Fixed crowded Focus card buttons and distorted text: extend / end Focus in two columns, save profile on its own row.",
                    "修正专注卡片按钮挤在一起、文字变形的问题：延长 / 结束专注分两栏，保存配置文件另起一行。",
                    "修正專注卡片按鈕擠在一起、文字變形的問題：延長 / 結束專注分兩欄，儲存設定檔另起一列。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.5",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Tap the version number in Settings → About to see what is new in this version.",
                    "设置 → 关于的版本号可点击查看本版本新增功能与优化内容。",
                    "設定 → 關於的版本號可點擊查看本版本新增功能與最佳化內容。",
                ),
                t(
                    "Release notes include \"Version history\" to review changes in past versions.",
                    "版本说明支持「历史版本」，可回看过去各版本的变更。",
                    "版本說明支援「歷史版本」，可回看過去各版本的變更。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.4",
            date = "2026-11-15",
            features = listOf(
                t(
                    "vivo / iQOO compatibility mode for the speed island: placed below the system island automatically, can be turned off.",
                    "vivo / iQOO 灵动岛兼容模式：自动放到系统岛下方，可关闭。",
                    "vivo / iQOO 動態島相容模式：自動放到系統島下方，可關閉。",
                ),
                t(
                    "Extra top offset slider (0–48 dp) for the speed island to avoid overlaps on real devices.",
                    "灵动岛额外顶部偏移 0–48dp 滑杆，真机可微调避免遮挡。",
                    "動態島額外頂部偏移 0–48dp 滑桿，實機可微調避免遮擋。",
                ),
            ),
            improvements = listOf(
                t(
                    "The prompt panel follows the speed island offset so the two overlays do not overlap.",
                    "询问面板跟随灵动岛偏移，避免两个悬浮窗重叠。",
                    "詢問面板跟隨動態島偏移，避免兩個懸浮視窗重疊。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.3",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Renamed to speed island and on by default: shows upload/download speed at the top while protection is on.",
                    "灵动岛更名并默认开启：保护开启时在顶部显示上传 / 下载速度。",
                    "動態島更名並預設開啟：保護開啟時在頂部顯示上傳 / 下載速度。",
                ),
                t(
                    "The speed island can be turned off in Settings; after granting overlay permission it applies as soon as you return.",
                    "设置可关闭灵动岛；授予悬浮窗权限后返回设置立即生效。",
                    "設定可關閉動態島；授予懸浮視窗權限後返回設定立即生效。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.2",
            date = "2026-11-15",
            features = listOf(
                t(
                    "New speed island (speed display): can be toggled, hidden on the lock screen, and tapped to open the app.",
                    "新增灵动岛（速度显示），可开关、锁屏隐藏、点击打开主界面。",
                    "新增動態島（速度顯示），可開關、鎖定畫面隱藏、點擊開啟主畫面。",
                ),
                t(
                    "Removed the \"Until restart\" prompt option, keeping allow once / permanent / timed.",
                    "移除「直到重启」询问选项，保留允许一次 / 永久 / 限时。",
                    "移除「直到重新啟動」詢問選項，保留允許一次 / 永久 / 限時。",
                ),
                t(
                    "The duration of timed rules can be configured in Settings, for example 30 minutes.",
                    "限时规则时长可在设置配置，例如 30 分钟。",
                    "限時規則時長可在設定調整，例如 30 分鐘。",
                ),
            ),
            improvements = listOf(
                t(
                    "The prompt panel moves down automatically when the speed island is on.",
                    "询问面板在灵动岛开启时自动下移。",
                    "詢問面板在動態島開啟時自動下移。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.1",
            date = "2026-11-15",
            features = listOf(
                t(
                    "\"Allow once\" now lasts for the app session: it ends when the app leaves the foreground and you are asked again next time.",
                    "「允许一次」改为应用会话：目标应用离开前台后失效，下次打开重新询问。",
                    "「允許一次」改為應用程式工作階段：目標應用程式離開前景後失效，下次開啟重新詢問。",
                ),
            ),
            notes = listOf(
                t(
                    "Android cannot reliably detect another app being swiped away or force-stopped, so leaving the foreground is used as an approximation.",
                    "Android 无法可靠检测其他应用被划掉 / 强制停止，目前以离开前台为近似。",
                    "Android 無法可靠偵測其他應用程式被滑掉 / 強制停止，目前以離開前景為近似。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.6.0",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Custom vertical position for the floating panel, adjustable with a drag preview in Settings.",
                    "悬浮面板自定义垂直位置，设置页可用拖动预览调整。",
                    "懸浮面板自訂垂直位置，設定頁可用拖動預覽調整。",
                ),
                t(
                    "Create a Focus allowlist profile in one tap; an active Focus session can be saved as a long-term profile.",
                    "一键建立专注白名单配置文件；进行中的专注可保存为长期配置文件。",
                    "一鍵建立專注白名單設定檔；進行中的專注可儲存為長期設定檔。",
                ),
                t(
                    "Focus duration and schedules use Android's native clock picker, with custom minutes.",
                    "专注时长与时间表改用 Android 原生圆盘时间选择，支持自定义分钟。",
                    "專注時長與時間表改用 Android 原生圓盤時間選擇，支援自訂分鐘。",
                ),
                t(
                    "M4 Phase 0 forwarding experiment: traffic can be forwarded to a local SOCKS5 proxy (not yet connected to rules / prompts).",
                    "M4 Phase 0 转发实验：可把流量转到本机 SOCKS5（尚未接入规则 / 询问）。",
                    "M4 Phase 0 轉發實驗：可把流量轉到本機 SOCKS5（尚未接入規則 / 詢問）。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.5.0",
            date = "2026-11-15",
            features = listOf(
                t(
                    "AAB packaging and release signing via NC_ANDROID_KEYSTORE.",
                    "新增 AAB 打包、NC_ANDROID_KEYSTORE 正式签名支持。",
                    "新增 AAB 打包、NC_ANDROID_KEYSTORE 正式簽署支援。",
                ),
                t(
                    "Settings → About can check GitHub for new versions.",
                    "设置 → 关于可检查 GitHub 新版本。",
                    "設定 → 關於可檢查 GitHub 新版本。",
                ),
                t(
                    "Diagnostics bundle includes network / SIM / Private DNS / IPv6 / overlay permission.",
                    "诊断包加入网络 / SIM / 私人 DNS / IPv6 / 悬浮权限。",
                    "診斷包加入網路 / SIM / 私人 DNS / IPv6 / 懸浮權限。",
                ),
                t(
                    "New adb smoke-test script and real-device regression checklist.",
                    "新增 adb 冒烟脚本与真机回归清单。",
                    "新增 adb 冒煙指令碼與實機回歸清單。",
                ),
            ),
            improvements = listOf(
                t(
                    "Startup log includes elapsed time; release builds keep R8 and resource shrinking.",
                    "初始化日志加入耗时；release 维持 R8 与资源压缩。",
                    "初始化日誌加入耗時；release 維持 R8 與資源壓縮。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.4.0",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Floating prompt panel: shown above other apps, at the top, bottom or a custom position.",
                    "悬浮询问面板：可在其他应用上方显示，支持顶部 / 底部 / 自定义位置。",
                    "懸浮詢問面板：可在其他應用程式上方顯示，支援頂部 / 底部 / 自訂位置。",
                ),
                t(
                    "Lock-screen blocking, Focus mode, Focus schedules, allow once.",
                    "锁屏断网、专注模式、专注时间表、允许一次。",
                    "鎖定畫面斷網、專注模式、專注時間表、允許一次。",
                ),
                t(
                    "Prompt content settings, new-app install alerts, Private DNS hint, app lock.",
                    "询问内容显示设置、新应用安装提醒、私人 DNS 提示、应用锁。",
                    "詢問內容顯示設定、新應用程式安裝提醒、私人 DNS 提示、應用程式鎖。",
                ),
            ),
            improvements = listOf(
                t(
                    "Prompt notifications use a quiet channel so they do not cover the floating panel.",
                    "询问通知改为安静渠道，避免盖住悬浮面板。",
                    "詢問通知改為安靜頻道，避免蓋住懸浮面板。",
                ),
                t(
                    "Navigation content descriptions and a maximum width on tablets.",
                    "导航内容描述与平板最大宽度。",
                    "導覽內容描述與平板最大寬度。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.3.0",
            date = "2026-11-15",
            features = listOf(
                t(
                    "Traffic split by Wi-Fi / mobile / SIM, also usable as rule conditions.",
                    "Wi-Fi / 移动网络 / SIM 流量拆分与规则条件。",
                    "Wi-Fi / 行動網路 / SIM 流量拆分與規則條件。",
                ),
                t(
                    "Connection log CSV export, app details, rule de-duplication and grouping by app.",
                    "连接记录 CSV 导出、应用详情、规则去重与按应用分组。",
                    "連線紀錄 CSV 匯出、應用程式詳細資料、規則去重與按應用程式分組。",
                ),
                t(
                    "Trusted publishers / system apps allowed by default, batch prompts.",
                    "可信发布者 / 系统应用默认允许、批量询问。",
                    "可信發布者 / 系統應用程式預設允許、批次詢問。",
                ),
            ),
            improvements = listOf(
                t(
                    "Notification actions can still create rules after the process is reclaimed.",
                    "通知动作在进程回收后仍可建立规则。",
                    "通知動作在程序回收後仍可建立規則。",
                ),
                t(
                    "Fixed import Replace deleting rules by mistake, CSV truncation, traffic across midnight and more.",
                    "修正导入 Replace 误删规则、CSV 截断、流量跨日等问题。",
                    "修正匯入 Replace 誤刪規則、CSV 截斷、流量跨日等問題。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.2.0",
            date = "2026-11-15",
            features = listOf(
                t(
                    "App list permission guide, theme colors (10 presets + custom HSV).",
                    "应用列表权限引导、主题色（10 预设 + HSV 自定义）。",
                    "應用程式清單權限引導、主題色（10 種預設 + HSV 自訂）。",
                ),
                t(
                    "Automatic profile switching by network type / SSID.",
                    "按网络类型 / SSID 自动切换配置文件。",
                    "按網路類型 / SSID 自動切換設定檔。",
                ),
                t(
                    "Live upload/download speed, Do Not Disturb and summary.",
                    "实时上下行网速、勿扰与摘要。",
                    "即時上下行網速、勿擾與摘要。",
                ),
            ),
            improvements = listOf(
                t(
                    "Per-app three-state rules are isolated per profile, fixing a bug where they were shared across profiles.",
                    "应用三态规则按配置文件隔离，修正跨配置文件共享 bug。",
                    "應用程式三態規則按設定檔隔離，修正跨設定檔共用的錯誤。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.1.0",
            date = "2026-11-14",
            features = listOf(
                t(
                    "Kotlin + Compose Android app skeleton, SQLite, rule engine.",
                    "Kotlin + Compose Android 版骨架、SQLite、规则引擎。",
                    "Kotlin + Compose Android 版骨架、SQLite、規則引擎。",
                ),
                t(
                    "Blackhole VPN, fake DNS, prompts, records, apps, settings.",
                    "黑洞 VPN、假 DNS、询问、记录、应用、设置。",
                    "黑洞 VPN、假 DNS、詢問、紀錄、應用程式、設定。",
                ),
            ),
        ),
    )

    fun current(versionName: String): ReleaseNote? =
        all.firstOrNull { versionName.startsWith(it.version) }
}
