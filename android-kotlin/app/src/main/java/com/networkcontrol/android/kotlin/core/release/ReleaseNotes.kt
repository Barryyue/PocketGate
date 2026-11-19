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
            version = "0.15.1",
            date = "2026-11-19",
            features = listOf(
                t(
                    "Offline mode: optional switch in Settings (off by default) that stops PocketGate itself from making any outbound requests (update checks, APK downloads, and online blocklist subscriptions) without affecting VPN firewall rules, forwarding, or local file imports",
                    "纯离线模式：在设置中新增「纯离线模式」开关（默认关闭），开启后禁止 PocketGate 自身发起检查更新、下载安装包及拦截列表在线订阅请求，完全不影响现有 VPN 防火墙、逐连接转发或本地导入规则",
                    "純離線模式：在設定中新增「純離線模式」開關（預設關閉），開啟後禁止 PocketGate 自身發起檢查更新、下載安裝包及攔截列表線上訂閱請求，完全不影響現有 VPN 防火牆、逐連線轉發或本機匯入規則",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.15.0",
            date = "2026-11-19",
            features = listOf(
                t(
                    "Added a native auto-tinting status-bar speed icon (SystemUI automatically switches it between black and white over any foreground app), a Live Connections tab with one-tap bidirectional RST disconnect, 3-stage SOCKS5 proxy diagnostics, and a 6-item Permissions & Keep-Alive health card.",
                    "新增原生状态栏网速小图标（由系统原生自动随任意应用变黑/变白）、记录页「实时连接」分栏与单条连接一键双向 RST 断开、代理「三段式精准诊断」以及设置页 6 项「权限与保活体检」卡片。",
                    "新增原生狀態欄網速小圖示（由系統原生自動隨任意應用變黑/變白）、記錄頁「即時連線」分欄與單條連線一鍵雙向 RST 斷開、代理「三段式精準診斷」以及設定頁 6 項「權限與保活體檢」卡片。",
                ),
            ),
            improvements = listOf(
                t(
                    "Refined the PocketGate logo into a cleaner minimalist 3-element mark (including full-color notification largeIcon), unified Apps / Rules / Logs screens with all 6 modern visual themes, and switched release builds to official release keystore signing.",
                    "精简升级 PocketGate 极简护盾 Logo 并为常驻通知补全彩色大图标，全面统一应用、规则与记录三页的 6 套现代主题视觉与状态色条，正式启用独立发布签名。",
                    "精簡升級 PocketGate 極簡護盾 Logo 並為常駐通知補全彩色大圖示，全面統一應用、規則與記錄三頁的 6 套現代主題視覺與狀態色條，正式啟用獨立發布簽章。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.14.4",
            date = "2026-11-19",
            features = emptyList(),
            improvements = listOf(
                t(
                    "Fixed rectangular corner artifacts on the floating glass bottom navigation bar by clipping the Haze glass layer cleanly to its rounded capsule shape and setting transparent Haze backgroundColor with concentric 24dp inner pill corners.",
                    "修复悬浮玻璃底部导航栏两端露出方形边角尾巴的问题：在投影之后精确按圆角胶囊裁切 Haze 玻璃层并将底色设为透明，同时将内部滑动胶囊调整为同心 24dp 圆角。",
                    "修復懸浮玻璃底部導覽列兩端露出方形邊角尾巴的問題：在投影之後精確按圓角膠囊裁切 Haze 玻璃層並將底色設為透明，同時將內部滑動膠囊調整為同心 24dp 圓角。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.14.3",
            date = "2026-11-19",
            features = emptyList(),
            improvements = listOf(
                t(
                    "Fixed multi-user/clone app support (e.g. vivo/MIUI Dual Apps with user 999 UIDs) so cloned apps inherit their primary app's allow/deny rules and prompt properly instead of being silently blocked; removed the bottom-right FAB to prevent accidental protection toggles and restored the top Hero card Switch; improved Speed Island status-bar text color detection using true system dark mode and wallpaper primary luminance.",
                    "修复应用分身（如 vivo / 小米等 user 999 双开分身）被误拒且无弹窗的问题，分身 UID 自动按 appId 继承主应用规则并正常弹窗；移除易误触的右下角悬浮开关，恢复顶部防护卡片右侧独立开关；优化灵动岛「跟随状态栏」文字取色，改用系统真实深色模式与壁纸主色亮度判断。",
                    "修復應用分身（如 vivo / 小米等 user 999 雙開分身）被誤拒且無彈窗的問題，分身 UID 自動依 appId 繼承主應用規則並正常彈窗；移除易誤觸的右下角懸浮開關，恢復頂部防護卡片右側獨立開關；優化靈動島「跟隨狀態欄」文字取色，改用系統真實深色模式與桌布主色亮度判斷。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.14.2",
            date = "2026-11-19",
            features = emptyList(),
            improvements = listOf(
                t(
                    "Eliminated 4 idle background polling timers (TargetAppSessionTracker, FocusController, QuietPeriodTracker, FocusScheduler) by switching to event-driven suspension, cutting idle standby wakeups from ~3,360/hour to near zero; fixed the status-bar/notification PocketGate shield icon missing fillType='evenOdd' so the sliding pocket gate interior renders crisply.",
                    "深度待机省电优化：将仅一次会话追踪、专注模式、勿扰汇总与专注时间表 4 处后台轮询全部改为事件驱动挂起，空闲待机唤醒从约 3,360 次/小时降至接近 0 次；修复状态栏与通知栏 PocketGate 护盾图标缺少 evenOdd 镂空属性导致显示为实心盾牌的问题。",
                    "深度待機省電優化：將僅一次會話追蹤、專注模式、勿擾彙總與專注時間表 4 處背景輪詢全部改為事件驅動掛起，空閒待機喚醒從約 3,360 次/小時降至接近 0 次；修復狀態欄與通知欄 PocketGate 護盾圖示缺少 evenOdd 鏤空屬性導致顯示為實心盾牌的問題。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.14.1",
            date = "2026-11-19",
            features = emptyList(),
            improvements = listOf(
                t(
                    "Upgraded bottom navigation with theme-colored active icons/labels, an elastic spring sliding pill indicator, bouncy icon scale/lift animations with subtle haptic feedback, and directional spring page transitions.",
                    "升级底部导航栏质感与动效：选中图标与文字随主题主色高亮、新增带物理弹性过冲的滑动胶囊指示器、图标 Q 弹缩放上浮与轻微震动反馈，并将页面切换升级为带方向的弹簧滑入动效。",
                    "升級底部導覽列質感與動效：選中圖示與文字隨主題主色高亮、新增帶物理彈性過衝的滑動膠囊指示器、圖示 Q 彈縮放上浮與輕微震動回饋，並將頁面切換升級為帶方向的彈簧滑入動效。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.14.0",
            date = "2026-11-19",
            features = listOf(
                t(
                    "Redesigned the Overview tab into a modern Bento Grid dashboard across all 6 themes: live 36-point smooth Bezier speed waveform chart, 2-column icon Bento tiles (Blocked, Pending Prompts, Traffic, Rules, Focus, Do Not Disturb), proportional traffic bars, bottom-right animated master FAB, ModalBottomSheet profile picker, rounded navigation icons, and smooth Crossfade tab transitions.",
                    "全面重构总览页为现代 Bento 双列仪表盘（兼容全部 6 套视觉主题）：新增实时网速平滑贝塞尔波形图卡片、带图标徽章的双列功能瓷砖（拦截/询问/流量/规则/专注/勿扰）、流量占比进度条、右下角形变悬浮主控开关、配置文件底部抽屉，以及圆角导航图标与平滑页面切换动效。",
                    "全面重構總覽頁為現代 Bento 雙列儀表盤（相容全部 6 套視覺主題）：新增即時網速平滑貝茲波形圖卡片、帶圖示徽章的雙列功能瓷磚（攔截/詢問/流量/規則/專注/勿擾）、流量占比進度條、右下角形變懸浮主控開關、設定檔底部抽屜，以及圓角導覽圖示與平滑頁面切換動效。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.13.9",
            date = "2026-11-19",
            features = emptyList(),
            improvements = listOf(
                t(
                    "Fixed dark theme styles (Obsidian Cyber, AMOLED Black, dark Aurora Glass) rendering dark text on dark cards by explicitly setting LocalContentColor and CardDefaults contentColor.",
                    "修复黑曜石极客、纯黑省电与深色极光等深色主题下文字发暗看不清的问题，统一为画布与卡片显式指定高对比度文字颜色。",
                    "修復黑曜石極客、純黑省電與深色極光等深色主題下文字發暗看不清的問題，統一為畫布與卡片顯式指定高對比度文字顏色。",
                ),
                t(
                    "Optimized per-connection forwarding and external SOCKS5 proxy reliability: event-driven DNS pump (zero idle CPU wakeups), instant UDP toggle without VPN restart, 8-slot UDP fast-path for concurrent QUIC streams, immediate proxy health check with screen-off pause, loopback proxy-app loop warning, detected proxy apps pinned in picker, and one-tap fallback to built-in direct exit.",
                    "优化逐连接转发与外部 SOCKS5 代理体验：DNS 队列改为事件驱动唤醒（空闲零 CPU 唤醒更省电）、切换 UDP 开关无感生效不重建 VPN、多槽位 UDP 快速路径优化多路 QUIC 吞吐、代理切入后立即探活并息屏暂停、本机代理漏选代理应用时醒目预警并置顶常见代理应用、代理异常时总览页支持一键切回直连。",
                    "優化逐連線轉發與外部 SOCKS5 代理體驗：DNS 佇列改為事件驅動喚醒（空閒零 CPU 喚醒更省電）、切換 UDP 開關無感生效不重建 VPN、多槽位 UDP 快速路徑優化多路 QUIC 吞吐、代理切入後立即探活並息屏暫停、本機代理漏選代理應用時醒目預警並置頂常見代理應用、代理異常時總覽頁支援一鍵切回直連。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.13.8",
            date = "2026-11-19",
            features = listOf(
                t(
                    "Added a visual theme style engine with 6 modern styles (Classic, Obsidian Cyber, Frost Minimal, Aurora Glass, AMOLED Black, and Android 12+ Material You) with a live preview card picker in Settings and automatic system-bar icon contrast.",
                    "新增视觉主题引擎与 6 套现代风格（经典、黑曜石极客、极简霜白、极光流光、纯黑省电、系统取色），设置页支持缩略图卡片实时预览切换，并自动同步状态栏与导航栏图标对比度。",
                    "新增視覺主題引擎與 6 套現代風格（經典、黑曜石極客、極簡霜白、極光流光、純黑省電、系統取色），設定頁支援縮略圖卡片即時預覽切換，並自動同步狀態欄與導覽列圖示對比度。",
                ),
            ),
            improvements = listOf(
                t(
                    "Upgraded the Overview protection card into a flagship hero status card with the PocketGate shield icon and state-aware gradient tint, and enabled tabular numerals (tnum) for live speed and metric cards.",
                    "升级总览页顶部防护卡片为旗舰状态卡片（融合 PocketGate 护盾图标与状态渐变光效），并对实时网速与统计指标启用等宽数字（Tabular Numerals）避免跳动。",
                    "升級總覽頁頂部防護卡片為旗艦狀態卡片（融合 PocketGate 護盾圖示與狀態漸層光效），並對即時網速與統計指標啟用等寬數字（Tabular Numerals）避免跳動。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.13.7",
            date = "2026-11-19",
            features = emptyList(),
            improvements = listOf(
                t(
                    "Replaced the app launcher and status-bar shield icons with the new PocketGate Sliding Pocket Gate adaptive vector logo (including Android 13+ monochrome themed icon support).",
                    "全新 PocketGate「滑动暗门护盾」自适应矢量应用图标上线，同步更新通知栏与快捷磁贴图标，并支持 Android 13+ 单色主题图标。",
                    "全新 PocketGate「滑動暗門護盾」自適應矢量應用程式圖示上線，同步更新通知欄與快捷磁貼圖示，並支援 Android 13+ 單色主題圖示。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.13.6",
            date = "2026-11-19",
            features = emptyList(),
            improvements = listOf(
                t(
                    "Added GitHub release mirror acceleration (ghfast.top) and web redirect tag fallback for in-app update downloads, and removed the browser-redirect fallback so updates always stay inside the app.",
                    "为应用内更新下载加入 GitHub 加速镜像（ghfast.top）与网页重定向解析兜底，并移除失败时自动跳转浏览器的行为，确保更新全程在应用内完成。",
                    "為應用程式內更新下載加入 GitHub 加速鏡像（ghfast.top）與網頁重定向解析兜底，並移除失敗時自動跳轉瀏覽器的行為，確保更新全程在應用程式內完成。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.13.5",
            date = "2026-11-19",
            features = emptyList(),
            improvements = listOf(
                t(
                    "Fixed blurry text on the Dynamic Island overlay: removed the unconditional 4px white shadow halo when the capsule background is opaque, and enabled subpixel anti-aliasing for crisp text.",
                    "修复灵动岛文字发虚模糊的问题：在胶囊有底色时移除原本无条件叠加的 4px 白色发光阴影，并开启亚像素抗锯齿，让小字清晰锐利。",
                    "修正靈動島文字發虛模糊的問題：在膠囊有底色時移除原本無條件疊加的 4px 白色發光陰影，並開啟亞像素抗鋸齒，讓小字清晰銳利。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.13.4",
            date = "2026-11-19",
            features = listOf(
                t(
                    "In-app update download and installation: tapping Download after an update check now downloads the APK directly inside PocketGate with a live progress bar (automatically falling back to Gitee if GitHub download fails) and launches the system installer for in-place replacement.",
                    "应用内直接下载并覆盖安装更新：检查到新版本后点击「下载」，直接在 PocketGate 内显示进度条下载 APK（GitHub 下载失败时自动回退到 Gitee），下载完成后自动拉起系统安装器原地覆盖升级。",
                    "應用程式內直接下載並覆蓋安裝更新：檢查到新版本後點擊「下載」，直接在 PocketGate 內顯示進度條下載 APK（GitHub 下載失敗時自動回退到 Gitee），下載完成後自動拉起系統安裝器原地覆蓋升級。",
                ),
            ),
            improvements = listOf(
                t(
                    "Beautified the connection prompt overlay and in-app card: added a top app header with the rounded app icon, bold app name, package subtitle and network badge, highlighted the app name in the prompt message with the theme color, and placed target details inside a rounded container.",
                    "美化悬浮询问面板与应用内询问卡片：顶部新增圆角应用图标、加粗应用名称、包名副标题与网络类型胶囊标签，正文中将应用名称以主题色加粗高亮，并将连接目标放入圆角底色框中。",
                    "美化懸浮詢問面板與應用程式內詢問卡片：頂部新增圓角應用程式圖示、加粗應用程式名稱、套件名稱副標題與網路類型膠囊標籤，內文中將應用程式名稱以主題色加粗高亮，並將連線目標放入圓角底色框中。",
                ),
                t(
                    "Renamed the app and release package to PocketGate, and simplified update checks to try GitHub Releases first and automatically fall back to Gitee Releases without manual source configuration.",
                    "应用与安装包正式定名为 PocketGate；检查更新简化为优先走 GitHub Releases、连不通或下载不可达时自动回退到 Gitee Releases，无需手动配置更新来源。",
                    "應用程式與安裝包正式定名為 PocketGate；檢查更新簡化為優先走 GitHub Releases、連不通或下載不可達時自動回退到 Gitee Releases，無須手動設定更新來源。",
                ),
                t(
                    "Reorganized the Settings screen into six compact cards: prompt and overlay details, per-connection forwarding, and profiles with focus schedules now open in dedicated sub-pages, while notification title and trusted publishers open in dialogs.",
                    "精简设置页面布局：合并为 6 张高内聚卡片，将联网询问与悬浮窗、逐连接转发模式、配置文件与专注时间表抽离为独立二级页面，并将通知标题、可信发布者与工具栏样式改为紧凑弹窗或下拉项。",
                    "精簡設定頁面佈局：合併為 6 張高內聚卡片，將連網詢問與懸浮窗、逐連線轉發模式、配置檔案與專注時間表抽離為獨立二級頁面，並將通知標題、可信發布者與工具列樣式改為緊湊彈窗或下拉項。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.13.3",
            date = "2026-11-18",
            features = listOf(
                t(
                    "The lock-screen allowed apps picker now supports Select all and a filter that shows only apps allowed to connect in currently enabled rules.",
                    "锁屏允许联网的应用选择框新增「全选」与「规则中允许联网」过滤，可一键筛选并勾选当前已启用规则中允许联网的应用。",
                    "鎖屏允許連網的應用程式選擇框新增「全選」與「規則中允許連網」過濾，可一鍵篩選並勾選目前已啟用規則中允許連網的應用程式。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.13.2",
            date = "2026-11-18",
            features = emptyList(),
            improvements = listOf(
                t(
                    "Glass now has a real specular rim and a much lower tint, so the blurred content colours show through instead of looking like a white slab. Transparent uses a light blur with a readable tint, so labels no longer sit on top of sharp scrolling content.",
                    "玻璃工具栏加入真正的镜面边框和内圈高光，底色大幅降低，模糊后的内容颜色能透上来，不再像一块白色板子。透明样式改为轻度模糊加可读的底色，标签不会再直接压在清晰的滚动内容上。",
                    "玻璃工具列加入真正的鏡面邊框與內圈高光，底色大幅降低，模糊後的內容顏色能透上來，不再像一塊白色板子。透明樣式改為輕度模糊加可讀的底色，標籤不會再直接壓在清晰的捲動內容上。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.13.1",
            date = "2026-11-18",
            features = emptyList(),
            improvements = listOf(
                t(
                    "Glass and transparent toolbars now look clearly different: glass gets a much stronger blur, a tinted gradient sheen, a lit top rim and a gradient border, while transparent has no blur at all so the content behind stays sharp.",
                    "玻璃和透明工具栏现在有明显区别：玻璃使用更强的模糊、带色调的渐变高光、顶部反光条和渐变边框；透明完全不模糊，后方内容保持清晰。",
                    "玻璃和透明工具列現在有明顯區別：玻璃使用更強的模糊、帶色調的漸變高光、頂部反光條和漸變邊框；透明完全不模糊，後方內容保持清晰。",
                ),
                t(
                    "Frosted toolbars are more opaque and less blurry, so the three translucent styles are easy to tell apart.",
                    "磨砂工具栏提高不透明度、降低模糊程度，三种半透明样式更容易区分。",
                    "磨砂工具列提高不透明度、降低模糊程度，三種半透明樣式更容易區分。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.13.0",
            date = "2026-11-18",
            features = listOf(
                t(
                    "Frosted and glass toolbars now use real backdrop blur (Haze) on Android 12+, with a specular highlight for the glass styles; content scrolls behind the bar.",
                    "磨砂和玻璃工具栏在 Android 12+ 改用真实背景模糊（Haze），玻璃样式加上高光；内容会从栏后滚动。",
                    "磨砂和玻璃工具列在 Android 12+ 改用真正背景模糊（Haze），玻璃樣式加上高光；內容會從欄後捲動。",
                ),
            ),
            notes = listOf(
                t(
                    "iOS Liquid Glass refraction/lensing is not available on Android; glass here means backdrop blur plus highlights, not optical refraction.",
                    "Android 没有 iOS Liquid Glass 的折射/透镜效果；这里的玻璃是背景模糊加高光，不是光学折射。",
                    "Android 沒有 iOS Liquid Glass 的折射/透鏡效果；這裡的玻璃是背景模糊加高光，不是光學折射。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.12.1",
            date = "2026-11-18",
            features = listOf(
                t(
                    "Floating toolbar styles now overlay the page content, so lists scroll behind the glass / transparent bar instead of stopping above it.",
                    "悬浮工具栏样式现在覆盖在页面内容上，列表会从玻璃 / 透明栏后面滚过，不再停在栏上方。",
                    "懸浮工具列樣式現在覆蓋在頁面內容上，清單會從玻璃 / 透明欄後面滾過，不再停在欄上方。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.12.0",
            date = "2026-11-18",
            features = listOf(
                t(
                    "New toolbar styles: classic solid, classic frosted, floating solid, floating glass, floating frosted and floating transparent.",
                    "新增底部工具栏样式：经典纯色、经典磨砂、悬浮纯色、悬浮玻璃、悬浮磨砂、悬浮透明。",
                    "新增底部工具列樣式：經典純色、經典磨砂、懸浮純色、懸浮玻璃、懸浮磨砂、懸浮透明。",
                ),
            ),
            notes = listOf(
                t(
                    "Frosted and glass are translucent approximations; Android does not provide real backdrop blur for a bottom bar inside the same window.",
                    "磨砂和玻璃是半透明近似效果；Android 不提供同窗口内底部栏的真实背景模糊。",
                    "磨砂和玻璃是半透明近似效果；Android 不提供同視窗內底部列的真正背景模糊。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.11.2",
            date = "2026-11-17",
            features = listOf(
                t(
                    "Block-on-lock now supports a whitelist: selected apps keep network access while the screen is locked.",
                    "锁屏断网新增白名单：选中的应用在锁屏时仍可联网。",
                    "鎖屏斷網新增白名單：選中的應用在鎖屏時仍可聯網。",
                ),
            ),
            notes = listOf(
                t(
                    "The whitelist only applies when block-on-lock is on; focus mode still uses its own allowed apps.",
                    "白名单只在锁屏断网开启时生效；专注模式仍使用自己的允许应用。",
                    "白名單只在鎖屏斷網開啟時生效；專注模式仍使用自己的允許應用。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.11.1",
            date = "2026-11-17",
            features = listOf(
                t(
                    "The persistent notification title can be customized in Settings > Notifications; leaving it empty uses the localized default “Protection is on”.",
                    "常驻通知标题可在设置 → 通知里自定义；留空时使用当前语言的默认「保护已开启」。",
                    "常駐通知標題可在設定 → 通知裡自訂；留空時使用目前語言的預設「保護已開啟」。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.11.0",
            date = "2026-11-17",
            features = listOf(
                t(
                    "Four home screen widgets: Protection (status and an on/off button), Today at a glance (blocks, traffic and pending prompts), Quick controls (protection, mode, block all, quiet and focus in one row) and Traffic ranking.",
                    "新增四款桌面小组件：防护状态（状态和一键开关）、今日概览（拦截、流量、待处理询问）、快捷开关（保护、模式、全部阻止、勿扰、专注一排搞定）和流量排行。",
                    "新增四款桌面小工具：防護狀態（狀態和一鍵開關）、今日概覽（攔截、流量、待處理詢問）、快捷開關（保護、模式、全部阻止、勿擾、專注一排搞定）和流量排行。",
                ),
                t(
                    "New Quick Settings tiles for filter mode, block all, quiet for 1 hour and focus, alongside the protection tile.",
                    "控制中心新增过滤模式、全部阻止、勿扰 1 小时和专注磁贴，与原有的保护磁贴并列。",
                    "控制中心新增過濾模式、全部阻止、勿擾 1 小時和專注圖塊，與原有的保護圖塊並列。",
                ),
                t(
                    "Long-press the app icon for shortcuts that turn protection on or off, switch mode, block all, start quiet or start focus.",
                    "长按应用图标可直接开关保护、切换模式、全部阻止、开启勿扰或开始专注。",
                    "長按應用程式圖示可直接開關保護、切換模式、全部阻止、開啟勿擾或開始專注。",
                ),
            ),
            improvements = listOf(
                t(
                    "With app lock on, actions from widgets, tiles and shortcuts that weaken protection (turning it off, loosening the mode, ending block all or focus) ask you to verify first. The protection tile now follows this rule too.",
                    "开启应用锁时，从小组件、磁贴、快捷方式执行会削弱防护的操作（关闭保护、放宽模式、解除全部阻止或结束专注）需要先验证身份；原有的保护磁贴也一并遵守。",
                    "開啟應用程式鎖時，從小工具、圖塊、捷徑執行會削弱防護的操作（關閉保護、放寬模式、解除全部阻止或結束專注）需要先驗證身分；原有的保護圖塊也一併遵守。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.10.7",
            date = "2026-11-17",
            features = listOf(),
            improvements = listOf(
                t(
                    "Prompt buttons no longer wrap onto two lines when timed allow is shown: the label is shortened to \"60 min\" and button text shrinks to fit when space is tight.",
                    "显示「限时允许」时，询问按钮不再换行：文字缩短为「60分钟」，空间不够时自动缩小字号。",
                    "顯示「限時允許」時，詢問按鈕不再換行：文字縮短為「60分鐘」，空間不夠時自動縮小字號。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.10.6",
            date = "2026-11-17",
            features = listOf(
                t(
                    "Close detection can now be turned on without a computer: tap Grant in settings to authorize it once with Shizuku or root. If neither is available, the app shows how to do it with Shizuku or a computer.",
                    "关闭检测不再需要电脑：在设置中点「授权」，即可用 Shizuku 或 Root 一次开启。两者都没有时，会显示用 Shizuku 或电脑开启的方法。",
                    "關閉偵測不再需要電腦：在設定中點「授權」，即可用 Shizuku 或 Root 一次開啟。兩者都沒有時，會顯示用 Shizuku 或電腦開啟的方法。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.10.5",
            date = "2026-11-17",
            features = listOf(),
            improvements = listOf(
                t(
                    "\"Only once\" and \"Deny\" no longer end the moment you switch away: they now last until the app has stayed in the background for 10 minutes, so returning to it soon does not ask again. The time can be changed in settings (0 restores the old behaviour). After granting the DUMP permission once with adb, they end only when the app is really closed, like on Red Magic.",
                    "「仅一次」和「禁止」不再一切到后台就失效：应用在后台超过 10 分钟才算关闭，期间回到应用不会再询问。时间可在设置中修改（设为 0 恢复旧行为）。用 adb 授予一次 DUMP 权限后，会和红魔一样在应用真正关闭时才失效。",
                    "「僅一次」和「禁止」不再一切到背景就失效：應用程式在背景超過 10 分鐘才算關閉，期間回到應用程式不會再詢問。時間可在設定中修改（設為 0 恢復舊行為）。用 adb 授予一次 DUMP 權限後，會和紅魔一樣在應用程式真正關閉時才失效。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.10.4",
            date = "2026-11-16",
            features = listOf(
                t(
                    "Redesigned the connection prompt after Red Magic's: one clear sentence, a \"Don't ask again after denying\" option and three buttons, Deny | Allow | Only once. By default the prompt now sits at the bottom of the screen. Timed allow can still be added in settings.",
                    "连网询问改为红魔的样式：一句说明、「禁止后不再提示」选项和「禁止 | 允许 | 仅一次」三个按钮，默认显示在屏幕底部。「限时允许」仍可在设置中加上。",
                    "連網詢問改為紅魔的樣式：一句說明、「禁止後不再提示」選項和「禁止 | 允許 | 僅一次」三個按鈕，預設顯示在螢幕底部。「限時允許」仍可在設定中加上。",
                ),
                t(
                    "Deny now only blocks the app until it is closed, and Only once allows it until it is closed; Allow is permanent. Tick \"Don't ask again after denying\" to block permanently, or turn on \"Deny is permanent by default\" to keep the old behaviour. The Later button is gone.",
                    "「禁止」现在只在应用关闭前禁止，「仅一次」只在应用关闭前允许，「允许」为永久允许。勾选「禁止后不再提示」才会永久禁止；想恢复旧行为可开启设置「「禁止」默认为永久」。移除「稍后」按钮。",
                    "「禁止」現在只在應用程式關閉前禁止，「僅一次」只在應用程式關閉前允許，「允許」為永久允許。勾選「禁止後不再提示」才會永久禁止；想恢復舊行為可開啟設定「「禁止」預設為永久」。移除「稍後」按鈕。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.10.3",
            date = "2026-11-16",
            features = listOf(),
            improvements = listOf(
                t(
                    "Fixed the main cause of slow browsing in per-connection mode: when many connections were busy at once, packets between the app and its forwarding engine could be lost, so pages, images and apps such as Telegram waited seconds for retransmissions or never finished loading. 20 parallel page loads now all finish in about 0.2 to 0.6 seconds.",
                    "修正逐连接模式变慢的主要原因：同时有很多连接时，应用与转发引擎之间会丢包，网页、图片和 Telegram 这类应用要等好几秒重传，甚至一直加载不完。现在 20 个网页同时加载都能在约 0.2～0.6 秒完成。",
                    "修正逐連線模式變慢的主要原因：同時有很多連線時，應用程式與轉發引擎之間會遺失封包，網頁、圖片和 Telegram 這類應用程式要等好幾秒重傳，甚至一直載入不完。現在 20 個網頁同時載入都能在約 0.2～0.6 秒完成。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.10.2",
            date = "2026-11-16",
            features = listOf(),
            improvements = listOf(
                t(
                    "Fixed: in per-connection mode, after protection restarted (for example after changing a forwarding setting or switching modes), web pages could keep loading for a long time, because browsers still used cached addresses that the tunnel no longer recognised. Address mappings are now restored when the tunnel restarts, and unrecognised addresses fail immediately so apps look them up again.",
                    "修正：逐连接模式下保护重新启动后（例如修改转发设置、切换模式），网页可能一直加载，因为浏览器仍在使用隧道已不认得的缓存地址。现在隧道重启时会恢复地址映射，不认得的地址会立即失败，让应用重新解析。",
                    "修正：逐連線模式下保護重新啟動後（例如修改轉發設定、切換模式），網頁可能一直載入，因為瀏覽器仍在使用隧道已不認得的快取位址。現在隧道重啟時會恢復位址映射，不認得的位址會立即失敗，讓應用程式重新解析。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.10.1",
            date = "2026-11-16",
            features = listOf(),
            improvements = listOf(
                t(
                    "Fixed: in per-connection mode, when many domain names were resolved at once (for example opening a page that loads from many sites), some lookups were lost and took 5 to 9 seconds. Lookups are now queued and retried automatically, so a burst of 30 names finishes in about 0.2 seconds.",
                    "修正：逐连接模式下同时解析很多域名（例如打开一个要从很多网站加载内容的网页）时，部分解析会丢失，要等 5～9 秒。现在解析会自动排队并重试，30 个域名同时解析约 0.2 秒内完成。",
                    "修正：逐連線模式下同時解析很多網域（例如開啟一個要從很多網站載入內容的網頁）時，部分解析會遺失，要等 5～9 秒。現在解析會自動排隊並重試，30 個網域同時解析約 0.2 秒內完成。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.10.0",
            date = "2026-11-16",
            features = listOf(
                t(
                    "Update checks can now use GitHub, GitLab, Gitee or a custom HTTPS JSON. Set the repository in Settings; APK assets are opened directly when present.",
                    "检查更新现在支持 GitHub、GitLab、Gitee 或自定义 HTTPS JSON；可在设置里填写仓库，有 APK 附件时直接打开下载链接。",
                    "檢查更新現在支援 GitHub、GitLab、Gitee 或自訂 HTTPS JSON；可在設定裡填寫儲存庫，有 APK 附件時直接開啟下載連結。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.9.10",
            date = "2026-11-16",
            features = listOf(),
            improvements = listOf(
                t(
                    "Fixed: in per-connection mode with DNS bypass protection, some DNS lookups were delayed by 3 seconds when many apps resolved names at once, making pages that open many connections (such as Google) feel slow.",
                    "修正：逐连接模式开启防止绕过 DNS 时，多个应用同时解析域名，部分解析会多等 3 秒，导致 Google 这类一次发起很多连接的网页变卡。",
                    "修正：逐連線模式開啟防止繞過 DNS 時，多個應用程式同時解析網域，部分解析會多等 3 秒，導致 Google 這類一次發起很多連線的網頁變卡。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.9.9",
            date = "2026-11-16",
            features = listOf(
                t(
                    "With app lock on, turning protection off, changing the mode, editing rules or app decisions and turning app lock off now ask for the screen lock first (no repeat prompt within 30 seconds); Allow / Deny in notifications on the lock screen require unlocking.",
                    "开启应用锁时，关闭保护、切换模式、修改规则或应用设置、关闭应用锁之前会先验证锁屏（30 秒内不重复验证）；锁屏上点通知里的「允许 / 拒绝」需要先解锁。",
                    "開啟應用程式鎖時，關閉保護、切換模式、修改規則或應用程式設定、關閉應用程式鎖之前會先驗證鎖定畫面（30 秒內不重複驗證）；鎖定畫面上點通知裡的「允許 / 拒絕」需要先解鎖。",
                ),
            ),
            improvements = listOf(
                t(
                    "The installable APK now contains only the arm64 build used by almost all phones, and ships a Baseline Profile so Android can precompile the startup path.",
                    "安装包只包含几乎所有手机使用的 arm64 版本，并附带 Baseline Profile，系统可以预先编译启动路径。",
                    "安裝包只包含幾乎所有手機使用的 arm64 版本，並附帶 Baseline Profile，系統可以預先編譯啟動路徑。",
                ),
                t(
                    "The proxy-app and direct-app pickers load the installed app list in the background instead of freezing the screen.",
                    "「代理应用」和「直连应用」选择框改为在后台加载应用列表，不再卡住界面。",
                    "「代理應用程式」和「直連應用程式」選擇框改為在背景載入應用程式清單，不再卡住畫面。",
                ),
                t(
                    "The app lock description now states plainly what it can and cannot do.",
                    "应用锁的说明改为如实写明它能做什么、不能做什么。",
                    "應用程式鎖的說明改為如實寫明它能做什麼、不能做什麼。",
                ),
            ),
            notes = listOf(
                t(
                    "App lock cannot stop anyone from turning off the VPN in system settings.",
                    "应用锁无法阻止别人在系统设置里关闭 VPN。",
                    "應用程式鎖無法阻止別人在系統設定裡關閉 VPN。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.9.8",
            date = "2026-11-16",
            features = listOf(),
            improvements = listOf(
                t(
                    "After \"Only this time\" ends because you left the app, its background connections are blocked and recorded quietly, without notifications; when you open the app again, prompts resume and appear as the floating panel.",
                    "「仅一次」因离开应用而结束后，该应用在后台的连接会被安静地拦截并记录，不再发通知；重新打开该应用后恢复询问，并以悬浮窗显示。",
                    "「僅限這次」因離開應用程式而結束後，該應用程式在背景的連線會被安靜地攔截並記錄，不再發通知；重新開啟該應用程式後恢復詢問，並以懸浮視窗顯示。",
                ),
            ),
            notes = listOf(
                t(
                    "The floating panel only asks about the app you are using; prompts for other background apps still appear as notifications.",
                    "悬浮窗只询问你正在使用的应用；其他后台应用的询问仍以通知显示。",
                    "懸浮視窗只詢問你正在使用的應用程式；其他背景應用程式的詢問仍以通知顯示。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.9.7",
            date = "2026-11-16",
            features = listOf(
                t(
                    "Tracker identification: with the Disconnect tracker database (downloaded by this device), records show which company a domain belongs to and what it is used for.",
                    "追踪器识别：使用 Disconnect 追踪器数据库（由本机下载），记录中会显示域名属于哪家公司、用途是什么。",
                    "追蹤器識別：使用 Disconnect 追蹤器資料庫（由本機下載），紀錄中會顯示網域屬於哪家公司、用途是什麼。",
                ),
                t(
                    "Block whole tracker categories: advertising, analytics, social networks, fingerprinting, cryptomining and email tracking.",
                    "可以整类拦截追踪器：广告、统计分析、社交网络、设备指纹、加密货币挖矿、邮件追踪。",
                    "可以整類攔截追蹤器：廣告、統計分析、社群網路、裝置指紋、加密貨幣挖礦、郵件追蹤。",
                ),
                t(
                    "When rules change, existing connections that are no longer allowed are cut off (per-connection mode).",
                    "规则改变后，已经建立、但不再允许的连接会被切断（逐连接模式）。",
                    "規則改變後，已經建立、但不再允許的連線會被切斷（逐連線模式）。",
                ),
            ),
            improvements = listOf(
                t(
                    "Fixed: after choosing \"Later\", closing the app and opening it again no longer leaves it offline without a prompt; leaving the app (home, another app, recents) now ends the \"Later\" period, while switching screens inside the app does not.",
                    "修正：选择「稍后」后关闭应用再打开，不再一直断网也不提示；离开应用（回桌面、切到别的应用、从最近任务划掉）才会结束「稍后」，应用内切换页面不会。",
                    "修正：選擇「稍後」後關閉應用程式再開啟，不再一直斷網也不提示；離開應用程式（回桌面、切到別的應用程式、從最近任務滑掉）才會結束「稍後」，應用程式內切換頁面不會。",
                ),
                t(
                    "Fixed: \"Only this time\" could expire after 10 minutes while you were still using the app (when the app had been open for a while, or after a long screen-off).",
                    "修正：「仅一次」在应用已经打开很久、或关屏较久时，可能在 10 分钟后被提前撤销。",
                    "修正：「僅限這次」在應用程式已經開啟很久、或關閉螢幕較久時，可能在 10 分鐘後被提前撤銷。",
                ),
                t(
                    "Fixed: protection could occasionally fail to start the tunnel right after the app launched.",
                    "修正：应用启动后偶尔不会建立隧道的问题。",
                    "修正：應用程式啟動後偶爾不會建立通道的問題。",
                ),
                t(
                    "External SOCKS5 proxies with a username and password are now supported (the proxy test and health check use them too).",
                    "支持需要用户名和密码的外部 SOCKS5 代理（测试代理和健康检查也会使用）。",
                    "支援需要使用者名稱和密碼的外部 SOCKS5 代理（測試代理和健康檢查也會使用）。",
                ),
                t(
                    "In whitelist mode, connections whose owner cannot be identified are now blocked instead of allowed.",
                    "白名单模式下，查不到归属应用的连接改为拒绝，而不是放行。",
                    "白名單模式下，查不到歸屬應用程式的連線改為拒絕，而不是放行。",
                ),
                t(
                    "The built-in exit now relays all connections on a single thread instead of two threads per connection, using much less memory with many connections.",
                    "内置出口改为单线程转发所有连接，不再每条连接两个线程，连接多时内存占用大幅减少。",
                    "內建出口改為單執行緒轉發所有連線，不再每條連線兩個執行緒，連線多時記憶體佔用大幅減少。",
                ),
            ),
            notes = listOf(
                t(
                    "The Disconnect data is licensed CC BY-NC-SA 4.0 and is downloaded directly from Disconnect; it is not bundled with the app.",
                    "Disconnect 数据以 CC BY-NC-SA 4.0 授权，直接从 Disconnect 下载，不随应用打包。",
                    "Disconnect 資料以 CC BY-NC-SA 4.0 授權，直接從 Disconnect 下載，不隨應用程式打包。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.9.6",
            date = "2026-11-16",
            features = listOf(
                t(
                    "Prevent DNS bypass (per-connection mode, on by default): DNS that apps send to their own servers is redirected to this app, and DNS-over-TLS and well-known DNS-over-HTTPS connections are blocked, so blocklists and domain rules also cover apps that do their own DNS.",
                    "防止绕过 DNS（逐连接模式，默认开启）：应用自己发往其他服务器的 DNS 会被导回本应用，DNS-over-TLS 和知名的 DNS-over-HTTPS 连接会被阻止，拦截列表和域名规则对自己做 DNS 的应用同样生效。",
                    "防止繞過 DNS（逐連線模式，預設開啟）：應用程式自己發往其他伺服器的 DNS 會被導回本應用程式，DNS-over-TLS 和知名的 DNS-over-HTTPS 連線會被阻擋，攔截列表和網域規則對自己做 DNS 的應用程式同樣生效。",
                ),
                t(
                    "Encrypted DNS for the built-in exit: choose Cloudflare, Google, Quad9, AliDNS, DNSPod or your own DNS-over-HTTPS address, with optional fallback to the system DNS.",
                    "内置出口支持加密 DNS：可选 Cloudflare、Google、Quad9、阿里 DNS、DNSPod 或自定义 DNS-over-HTTPS 地址，可设置失败时退回系统 DNS。",
                    "內建出口支援加密 DNS：可選 Cloudflare、Google、Quad9、阿里 DNS、DNSPod 或自訂 DNS-over-HTTPS 位址，可設定失敗時退回系統 DNS。",
                ),
            ),
            improvements = listOf(
                t(
                    "Encrypted DNS answers are cached by TTL, IPv4 is tried first, and after repeated failures the exit switches to the fallback for two minutes instead of waiting on every connection.",
                    "加密 DNS 的结果按 TTL 缓存，优先 IPv4；连续失败后两分钟内直接使用备用方式，不再每条连接都等超时。",
                    "加密 DNS 的結果按 TTL 快取，優先 IPv4；連續失敗後兩分鐘內直接使用備用方式，不再每條連線都等逾時。",
                ),
            ),
            notes = listOf(
                t(
                    "If the system Private DNS is set to a specific hostname, the system resolves names itself and this app cannot see them; set Private DNS to Automatic or Off.",
                    "如果系统私人 DNS 设为指定主机名，系统会自己解析域名，本应用看不到；请把私人 DNS 设为「自动」或「关闭」。",
                    "如果系統私人 DNS 設為指定主機名稱，系統會自己解析網域，本應用程式看不到；請把私人 DNS 設為「自動」或「關閉」。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.9.5",
            date = "2026-11-16",
            features = listOf(
                t(
                    "Blocklists: subscribe to community lists (anti-AD, HaGeZi, OISD, AdGuard DNS filter, StevenBlack) to block ads, trackers and malicious domains for every app. Lists are downloaded straight from their authors with mirror fallback, and matching happens on this device.",
                    "拦截列表：订阅社区名单（anti-AD、HaGeZi、OISD、AdGuard DNS filter、StevenBlack），为所有应用拦截广告、追踪和恶意域名。名单直接从作者处下载并自动切换镜像，匹配在本机完成。",
                    "攔截列表：訂閱社群名單（anti-AD、HaGeZi、OISD、AdGuard DNS filter、StevenBlack），為所有應用程式攔截廣告、追蹤和惡意網域。名單直接從作者處下載並自動切換鏡像，比對在本機完成。",
                ),
                t(
                    "Add your own subscription address or import a list file; hosts, plain domains and AdBlock / AdGuard DNS syntax are supported.",
                    "可以添加自己的订阅地址或从文件导入；支持 hosts、纯域名和 AdBlock / AdGuard DNS 语法。",
                    "可以新增自己的訂閱位址或從檔案匯入；支援 hosts、純網域和 AdBlock / AdGuard DNS 語法。",
                ),
                t(
                    "Records can show allowed connections too, can be filtered by blocklist, and a domain blocked by mistake can be allowed for all apps in one tap.",
                    "记录可以查看已允许的连接、按拦截列表筛选，误拦的域名可以一键为所有应用放行。",
                    "紀錄可以查看已允許的連線、按攔截列表篩選，誤攔的網域可以一鍵為所有應用程式放行。",
                ),
            ),
            improvements = listOf(
                t(
                    "Lists update automatically (weekly by default, Wi-Fi only by default) using conditional requests, so unchanged lists are not downloaded again; a failed update keeps the previous list.",
                    "名单自动更新（默认每周、仅 Wi-Fi），使用条件请求，没有变化就不重新下载；更新失败时继续使用上一份。",
                    "名單自動更新（預設每週、僅 Wi-Fi），使用條件請求，沒有變化就不重新下載；更新失敗時繼續使用上一份。",
                ),
                t(
                    "Lists are stored as compact hashes (8 bytes per domain), so large lists load quickly and use little memory.",
                    "名单以紧凑的哈希形式保存（每个域名 8 字节），大名单也能快速加载、占用内存少。",
                    "名單以緊湊的雜湊形式儲存（每個網域 8 位元組），大名單也能快速載入、佔用記憶體少。",
                ),
                t(
                    "Blocklisted domains are rejected without prompting, and your own allow rules always take priority.",
                    "命中名单的域名直接拒绝、不弹询问；你自己的允许规则永远优先。",
                    "命中名單的網域直接拒絕、不跳出詢問；你自己的允許規則永遠優先。",
                ),
                t(
                    "Fixed a possible crash when the built-in exit stops while connections are closing.",
                    "修正内置出口停止时、连接同时关闭可能导致的崩溃。",
                    "修正內建出口停止時、連線同時關閉可能導致的當機。",
                ),
            ),
            notes = listOf(
                t(
                    "Blocklists require per-connection mode; in blackhole mode allowed apps bypass the VPN and cannot be checked.",
                    "拦截列表需要逐连接模式；黑洞模式下被允许的应用不经过 VPN，无法检查。",
                    "攔截列表需要逐連線模式；黑洞模式下被允許的應用程式不經過 VPN，無法檢查。",
                ),
            ),
        ),
        ReleaseNote(
            version = "0.9.4",
            date = "2026-11-16",
            features = listOf(
                t(
                    "Per-connection mode no longer needs another app: choose \"Directly\" and allowed connections go out through a built-in exit. Every app's connections are checked one by one, and domain / port rules apply to all apps.",
                    "逐连接模式不再需要其他应用：选「直接连出」后，允许的连接经内置出口直接发出。所有应用的连接都会逐条判决，域名 / 端口规则对所有应用生效。",
                    "逐連線模式不再需要其他應用程式：選「直接連出」後，允許的連線經內建出口直接發出。所有應用程式的連線都會逐條判決，網域 / 連接埠規則對所有應用程式生效。",
                ),
                t(
                    "Allowed connections are now recorded in per-connection mode, not just blocked ones.",
                    "逐连接模式下，已允许的连接也会写入记录，不再只有被拦的连接。",
                    "逐連線模式下，已允許的連線也會寫入紀錄，不再只有被攔的連線。",
                ),
            ),
            improvements = listOf(
                t(
                    "The built-in exit listens only on the device itself and requires a random password generated at each start, so other apps cannot use it to bypass the firewall.",
                    "内置出口只监听本机，并要求每次启动随机生成的密码，其他应用无法借它绕过防火墙。",
                    "內建出口只監聽本機，並要求每次啟動隨機產生的密碼，其他應用程式無法借它繞過防火牆。",
                ),
                t(
                    "The built-in exit resolves and connects on the physical network, tries IPv4 first and gives up on a bad address after 3 seconds.",
                    "内置出口在实体网络上解析和连接，优先 IPv4，单个地址 3 秒连不上就换下一个。",
                    "內建出口在實體網路上解析和連線，優先 IPv4，單一位址 3 秒連不上就換下一個。",
                ),
            ),
            notes = listOf(
                t(
                    "Still experimental: the built-in exit is written in Kotlin and has not been measured for throughput or battery on real devices.",
                    "仍是实验功能：内置出口以 Kotlin 实现，尚未在真机上测量吞吐和耗电。",
                    "仍是實驗功能：內建出口以 Kotlin 實作，尚未在實機上測量吞吐和耗電。",
                ),
            ),
        ),
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
