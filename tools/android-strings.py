# -*- coding: utf-8 -*-
"""
Android 版界面文本的唯一來源：生成 src/NetworkControl.App.Android/Localization/<Catalog>/ 下的
<Catalog>LangResourceKind.cs 和 en-US / zh-TW / zh-CN 三個 XLIFF 文件。

修改文本：編輯下面的表格後運行  python -X utf8 tools/android-strings.py
新增鍵只能在每個 Catalog 的末尾追加（AtomUI 枚舉值的順序不能改變，見 AGENTS.md 第 6 節）。
zh-TW 使用台灣用語，zh-CN 使用大陸用語。
"""
import os
from xml.sax.saxutils import escape

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'NetworkControl.App.Android', 'Localization')
NAMESPACE = 'NetworkControl.Android.Localization'

# (鍵, en-US, zh-TW, zh-CN)
CATALOGS = {
    'Shell': [
        ('AppTitle', 'NetworkControl', 'NetworkControl', 'NetworkControl'),
        ('NavOverview', 'Overview', '總覽', '总览'),
        ('NavApps', 'Apps', '應用程式', '应用'),
        ('NavLog', 'Blocked', '阻擋記錄', '拦截记录'),
        ('NavSettings', 'Settings', '設定', '设置'),
        ('Starting', 'Starting…', '正在啟動…', '正在启动…'),
        ('StartFailed', 'Failed to start: {0}', '啟動失敗：{0}', '启动失败：{0}'),
    ],
    'Common': [
        ('ModeAsk', 'Ask', '詢問', '询问'),
        ('ModeWhitelist', 'Allowlist', '白名單', '白名单'),
        ('ModeBlacklist', 'Blocklist', '黑名單', '黑名单'),
        ('ModeBlockAll', 'Block all', '全部封鎖', '全部阻断'),
        ('ModeDisabled', 'Filtering off', '停用過濾', '停用过滤'),
        ('ModeAskDescription', 'Ask when an app without a rule connects', '沒有規則的應用程式連線時詢問', '没有规则的应用联网时询问'),
        ('ModeWhitelistDescription', 'Only allowed apps can connect', '只有已允許的應用程式可以連線', '只有已允许的应用可以联网'),
        ('ModeBlacklistDescription', 'All apps can connect except blocked ones', '除已拒絕的應用程式外都可以連線', '除已拒绝的应用外都可以联网'),
        ('ModeBlockAllDescription', 'No app can connect', '所有應用程式都無法連線', '所有应用都无法联网'),
        ('ModeDisabledDescription', 'All apps can connect', '所有應用程式都可以連線', '所有应用都可以联网'),
        ('Allow', 'Allow', '允許', '允许'),
        ('Block', 'Block', '拒絕', '拒绝'),
        ('Unset', 'Not set', '未設定', '未设置'),
        ('Cancel', 'Cancel', '取消', '取消'),
        ('Retry', 'Retry', '重試', '重试'),
        ('Refresh', 'Refresh', '重新整理', '刷新'),
    ],
    'Overview': [
        ('Protection', 'Protection', '保護', '保护'),
        ('StatusOn', 'Protection is on', '保護已開啟', '保护已开启'),
        ('StatusOff', 'Protection is off', '保護已關閉', '保护已关闭'),
        ('StatusStarting', 'Starting protection…', '正在開啟保護…', '正在开启保护…'),
        ('StatusNeedsConsent', 'Permission needed', '需要授權', '需要授权'),
        ('StatusRevoked', 'Stopped by the system', '已被系統停止', '已被系统停止'),
        ('StatusFailed', 'Protection failed', '保護發生錯誤', '保护出错'),
        ('HintOn', 'Apps without permission cannot connect to the network.', '未獲允許的應用程式無法連線網路。', '未获允许的应用无法联网。'),
        ('HintOff', 'All apps can connect to the network.', '所有應用程式都可以連線網路。', '所有应用都可以联网。'),
        ('HintNeedsConsent', 'Allow NetworkControl to create a local VPN. Traffic never leaves your device.', '請允許 NetworkControl 建立本機 VPN。流量不會離開你的裝置。', '请允许 NetworkControl 建立本机 VPN。流量不会离开你的设备。'),
        ('HintRevoked', 'Another VPN may have been turned on. Turn protection on again to resume.', '可能啟用了其他 VPN。重新開啟保護即可恢復。', '可能启用了其他 VPN。重新开启保护即可恢复。'),
        ('Mode', 'Mode', '模式', '模式'),
        ('BlockAll', 'Block all apps', '封鎖所有應用程式', '阻断所有应用'),
        ('Restore', 'Restore', '恢復', '恢复'),
        ('BlockAllActive', 'All apps are blocked. Tap Restore to return to the previous mode.', '所有應用程式都已封鎖。點「恢復」回到之前的模式。', '所有应用都已阻断。点“恢复”回到之前的模式。'),
        ('BlockedToday', 'Blocked today', '今天阻擋', '今天拦截'),
        ('PendingPrompts', 'Waiting for you', '等待你決定', '等待你决定'),
        ('ShowPrompt', 'Review', '查看', '查看'),
        ('SkippedRules', '{0} rules have no effect on Android (they use domain, IP or port conditions).', '{0} 條規則在 Android 上不生效（帶有網域、IP 或連接埠條件）。', '{0} 条规则在 Android 上不生效（带有域名、IP 或端口条件）。'),
        ('NotificationsOff', 'Notifications are off: connection requests are only shown inside the app.', '通知已關閉：連線詢問只會在應用程式內顯示。', '通知已关闭：联网询问只会在应用内显示。'),
        ('EnableNotifications', 'Turn on', '開啟', '开启'),
        ('UsageToday', 'Data used today', '今日用量', '今日用量'),
        ('Download', 'Download', '下載', '下载'),
        ('Upload', 'Upload', '上傳', '上传'),
        ('UsageAccessNeeded', 'Allow usage access so NetworkControl can show how much data each app uses.', '允許「使用情況存取權」後，即可查看每個應用程式的數據用量。', '允许“使用情况访问权限”后，即可查看每个应用的数据用量。'),
        ('GrantUsageAccess', 'Allow usage access', '允許存取', '允许访问'),
        ('NoUsage', 'No data used today', '今天還沒有數據用量', '今天还没有数据用量'),
        ('Profiles', 'Profile', '設定檔', '配置文件'),
        ('QuietPeriod', '{0} apps, {1} connections were blocked while Do Not Disturb was on.', '勿擾期間有 {0} 個應用程式的 {1} 次連線被拒絕。', '勿扰期间有 {0} 个应用的 {1} 次连线被拒绝。'),
        ('Dismiss', 'Dismiss', '關閉', '关闭'),
    ],
    'Apps': [
        ('Search', 'Search apps', '搜尋應用程式', '搜索应用'),
        ('FilterAll', 'All', '全部', '全部'),
        ('FilterAllowed', 'Allowed', '已允許', '已允许'),
        ('FilterBlocked', 'Blocked', '已拒絕', '已拒绝'),
        ('FilterUnset', 'Not set', '未設定', '未设置'),
        ('ShowSystem', 'System apps', '系統應用程式', '系统应用'),
        ('FollowsMode', 'Not set · {0}', '未設定・{0}', '未设置·{0}'),
        ('NowAllowed', 'can connect', '可以連線', '可以联网'),
        ('NowBlocked', 'blocked', '已阻擋', '已拦截'),
        ('NowAsk', 'will ask', '將會詢問', '将会询问'),
        ('Until', 'until {0}', '至 {0}', '至 {0}'),
        ('UntilRestart', 'until restart', '直到重新啟動', '直到重启'),
        ('Tampered', 'Signature changed', '簽章已變更', '签名已更改'),
        ('System', 'System', '系統', '系统'),
        ('Count', '{0} apps', '{0} 個應用程式', '{0} 个应用'),
        ('Empty', 'No apps', '沒有應用程式', '没有应用'),
        ('UsageToday', 'Today {0}', '今日 {0}', '今日 {0}'),
    ],
    'Log': [
        ('Empty', 'Nothing has been blocked yet', '尚未阻擋任何連線', '还没有拦截任何连接'),
        ('ReasonRule', 'Rule', '規則', '规则'),
        ('ReasonMode', 'Mode', '模式', '模式'),
        ('ReasonPrompt', 'Your choice', '你的選擇', '你的选择'),
        ('ReasonTimeout', 'No answer', '未回應', '未回应'),
        ('ReasonOther', 'Default', '預設', '默认'),
        ('Times', '×{0}', '×{0}', '×{0}'),
        ('AllowApp', 'Allow this app', '允許此應用程式', '允许此应用'),
    ],
    'Prompt': [
        ('Title', '{0} wants to connect', '{0} 想要連線網路', '{0} 想要联网'),
        ('TamperTitle', '{0} has changed', '{0} 已被變更', '{0} 已被更改'),
        ('TamperHint', 'The app signature differs from the one you approved. It may have been replaced.', '應用程式的簽章與你允許時不同，可能已被替換。', '应用的签名与你允许时不同，可能已被替换。'),
        ('Target', 'Destination', '目的地', '目标'),
        ('MoreConnections', '{0} connections', '共 {0} 個連線', '共 {0} 个连接'),
        ('Hint', 'The connection was blocked for now. After you allow it, the app can connect when it retries.', '此連線已被暫時阻止，允許後應用程式重新連線即可上網。', '此连接已被暂时阻止，允许后应用重新连接即可上网。'),
        ('Duration', 'Remember', '有效期', '有效期'),
        ('Forever', 'Always', '永久', '永久'),
        ('OneHour', '1 hour', '1 小時', '1 小时'),
        ('UntilRestart', 'Until restart', '直到重新啟動', '直到重启'),
        ('Later', 'Later', '稍後', '稍后'),
        ('Queue', '{0} more waiting', '還有 {0} 個詢問', '还有 {0} 个询问'),
    ],
    'Settings': [
        ('Language', 'Language', '語言', '语言'),
        ('FollowSystem', 'System', '跟隨系統', '跟随系统'),
        ('Theme', 'Appearance', '外觀', '外观'),
        ('ThemeLight', 'Light', '淺色', '浅色'),
        ('ThemeDark', 'Dark', '深色', '深色'),
        ('Prompts', 'Connection requests', '連線詢問', '联网询问'),
        ('PromptTimeout', 'Answer within', '回應時限', '回应时限'),
        ('Seconds', '{0} s', '{0} 秒', '{0} 秒'),
        ('DefaultAction', 'If not answered', '未回應時', '未回应时'),
        ('Notifications', 'Notifications', '通知', '通知'),
        ('NotificationsOn', 'On', '已開啟', '已开启'),
        ('NotificationsOff', 'Off', '已關閉', '已关闭'),
        ('Open', 'Open', '開啟', '打开'),
        ('AlwaysOn', 'Always-on VPN', '永久連線的 VPN', '始终开启的 VPN'),
        ('AlwaysOnHint', 'To keep apps blocked even if NetworkControl stops, turn on "Always-on VPN" and "Block connections without VPN" for NetworkControl in system settings.', '若要在 NetworkControl 停止時仍阻擋應用程式，請在系統設定中為 NetworkControl 開啟「永久連線的 VPN」和「封鎖未使用 VPN 的連線」。', '若要在 NetworkControl 停止时仍阻断应用，请在系统设置中为 NetworkControl 开启“始终开启的 VPN”和“屏蔽未使用 VPN 的连接”。'),
        ('Battery', 'Battery optimization', '電池最佳化', '电池优化'),
        ('BatteryHint', 'Some phones stop background apps aggressively. Exclude NetworkControl from battery optimization if protection turns off by itself.', '部分手機會積極停止背景應用程式。若保護會自行關閉，請將 NetworkControl 排除在電池最佳化之外。', '部分手机会积极停止后台应用。如果保护会自行关闭，请将 NetworkControl 排除在电池优化之外。'),
        ('About', 'About', '關於', '关于'),
        ('Version', 'Version', '版本', '版本'),
        ('Statistics', 'Engine', '攔截引擎', '拦截引擎'),
        ('StatisticsFormat', '{0} packets, {1} attempts, {2} reported', '{0} 個封包，{1} 次連線嘗試，{2} 次回報', '{0} 个数据包，{1} 次联网尝试，{2} 次上报'),
        ('Traffic', 'Data usage', '數據用量', '数据用量'),
        ('TrafficHint', "Per-app data usage is read from Android's network statistics, which requires usage access.", '各應用程式的數據用量來自 Android 的網路統計，需要「使用情況存取權」。', '各应用的数据用量来自 Android 的网络统计，需要“使用情况访问权限”。'),
        ('TrafficGranted', 'Usage access granted', '已允許使用情況存取權', '已允许使用情况访问权限'),
        ('TrafficNotGranted', 'Usage access not granted', '尚未允許使用情況存取權', '尚未允许使用情况访问权限'),
        ('Profiles', 'Profiles', '設定檔', '配置文件'),
        ('ProfileHint', 'Each profile has its own mode and rules. Shared rules apply to every profile.', '每個設定檔有自己的模式和規則；共用規則對所有設定檔生效。', '每个配置文件有自己的模式和规则；共享规则对所有配置文件生效。'),
        ('ProfileName', 'Name', '名稱', '名称'),
        ('ProfileNamePlaceholder', 'e.g. Office, Home', '例如：公司、家裡', '例如：公司、家里'),
        ('ProfileAdd', 'Add', '新增', '添加'),
        ('ProfileSave', 'Save', '儲存', '保存'),
        ('ProfileDelete', 'Delete', '刪除', '删除'),
        ('ProfileActivate', 'Set current', '設為目前', '设为当前'),
        ('ProfileCurrent', 'Current', '目前', '当前'),
        ('ProfileDefault', 'Default', '預設', '默认'),
        ('ProfileRuleCount', '{0} rules', '{0} 條規則', '{0} 条规则'),
        ('ProfileDeleteConfirmTitle', 'Delete this profile?', '刪除這個設定檔？', '删除这个配置文件？'),
        ('ProfileDeleteConfirmContent', 'Its dedicated rules will also be deleted. Shared rules are not affected.', '它的專屬規則也會被刪除；共用規則不受影響。', '它的专属规则也会被删除；共享规则不受影响。'),
    ],
}

LANGS = [('en-US', 1), ('zh-TW', 2), ('zh-CN', 3)]


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


for name, rows in CATALOGS.items():
    folder = os.path.join(ROOT, name)
    os.makedirs(folder, exist_ok=True)
    keys = [r[0] for r in rows]
    assert len(keys) == len(set(keys)), name
    cs = [
        'using AtomUI.Localization;',
        '',
        f'namespace {NAMESPACE};',
        '',
        f'/// <summary>{name} 界面文本。由 tools/android-strings.py 生成，不要手工修改；只能在末尾追加成員。</summary>',
        '[LanguageCatalog]',
        f'public enum {name}LangResourceKind',
        '{',
    ] + [f'    {k},' for k in keys] + ['}', '']
    write(os.path.join(folder, f'{name}LangResourceKind.cs'), '\n'.join(cs))
    for lang, idx in LANGS:
        trg = '' if lang == 'en-US' else f' trgLang="{lang}"'
        out = ['<?xml version="1.0" encoding="utf-8"?>',
               f'<xliff version="2.1" srcLang="en-US"{trg} xmlns="urn:oasis:names:tc:xliff:document:2.0">',
               f'  <file id="{NAMESPACE}.{name}LangResourceKind">']
        for r in sorted(rows, key=lambda r: r[0]):
            out += [f'    <unit id="{r[0]}">', '      <segment>', f'        <source>{escape(r[1])}</source>']
            if lang != 'en-US':
                out.append(f'        <target state="translated">{escape(r[idx])}</target>')
            out += ['      </segment>', '    </unit>']
        out += ['  </file>', '</xliff>', '']
        write(os.path.join(folder, f'{lang}.xlf'), '\n'.join(out))

print('generated', ', '.join(CATALOGS))
