using global::Android.Content;

namespace NetworkControl.Android.Infrastructure;

/// <summary>主題明暗偏好。</summary>
public enum ThemePreference
{
    System = 0,
    Light = 1,
    Dark = 2,
}

/// <summary>視覺風格（與 Kotlin 版 PocketGate 的 6 套風格對齊）。</summary>
public enum AppThemeStyle
{
    Classic = 0,
    ObsidianCyber = 1,
    FrostMinimal = 2,
    AuroraGlass = 3,
    AmoledBlack = 4,
    DynamicYou = 5,
}

/// <summary>界面與進階功能偏好，保存在 SharedPreferences 中。</summary>
public sealed class UiPreferences
{
    private const string PreferencesName = "networkcontrol.ui";
    private const string LanguageKey = "language";
    private const string ThemeKey = "theme";
    private const string ThemeStyleKey = "theme_style";
    private const string ThemeColorKey = "theme_color";
    private const string AutoHideBottomBarKey = "auto_hide_bottom_bar";
    private const string OfflineModeKey = "offline_mode";
    private const string CloneAppsInheritKey = "clone_apps_inherit_primary";
    private const string ForwardingEnabledKey = "forwarding_enabled";
    private const string ForwardingUpstreamKey = "forwarding_upstream";
    private const string Socks5HostKey = "socks5_host";
    private const string Socks5PortKey = "socks5_port";
    private const string Socks5UserKey = "socks5_username";
    private const string Socks5PassKey = "socks5_password";
    private const string BlocklistsEnabledKey = "blocklists_enabled";
    private const string BlockOnLockKey = "block_on_lock";
    private const string SpeedIslandKey = "speed_island";
    private const string StatusBarSpeedIconKey = "status_bar_speed_icon";
    private const string PermissionGuideShownKey = "permission_guide_shown";
    private const string PermissionBannerDismissedKey = "permission_banner_dismissed";
    private const string WebDavUrlKey = "webdav_url";
    private const string WebDavUserKey = "webdav_username";
    private const string WebDavPassKey = "webdav_password";
    private const string WebDavPassphraseKey = "webdav_passphrase";
    private const string WebDavLastSyncKey = "webdav_last_sync";
    private const string PromptOverlayEnabledKey = "prompt_overlay_enabled";
    private const string PromptOverlayPositionKey = "prompt_overlay_position";
    private const string PromptShowDetailsKey = "prompt_show_details";
    private const string PromptDenyPermanentKey = "prompt_deny_permanent";
    private const string PromptShowTimedOptionKey = "prompt_show_timed_option";
    private const string PromptTimedMinutesKey = "prompt_timed_minutes";
    private const string AppLockKey = "app_lock_enabled";
    private const string AllowSystemAppsKey = "allow_system_apps";
    private const string NotificationTitleKey = "notification_title";
    private const string DnsBypassProtectionKey = "dns_bypass_protection";
    private const string Socks5UdpKey = "socks5_udp";
    private const string TrustedPublishersKey = "trusted_publishers";

    private readonly ISharedPreferences _preferences;

    public UiPreferences(Context context)
    {
        _preferences = context.GetSharedPreferences(PreferencesName, FileCreationMode.Private)
            ?? throw new InvalidOperationException("SharedPreferences 不可用。");
    }

    public string? Language
    {
        get => _preferences.GetString(LanguageKey, null);
        set => Edit(e => e.PutString(LanguageKey, value));
    }

    public ThemePreference Theme
    {
        get => (ThemePreference)_preferences.GetInt(ThemeKey, (int)ThemePreference.System);
        set => Edit(e => e.PutInt(ThemeKey, (int)value));
    }

    public AppThemeStyle ThemeStyle
    {
        get => (AppThemeStyle)_preferences.GetInt(ThemeStyleKey, (int)AppThemeStyle.Classic);
        set => Edit(e => e.PutInt(ThemeStyleKey, (int)value));
    }

    public uint ThemeColorArgb
    {
        get => unchecked((uint)_preferences.GetInt(ThemeColorKey, unchecked((int)0xFF2E7D32)));
        set => Edit(e => e.PutInt(ThemeColorKey, unchecked((int)value)));
    }

    public bool AutoHideBottomBar
    {
        get => _preferences.GetBoolean(AutoHideBottomBarKey, true);
        set => Edit(e => e.PutBoolean(AutoHideBottomBarKey, value));
    }

    public bool OfflineMode
    {
        get => _preferences.GetBoolean(OfflineModeKey, false);
        set => Edit(e => e.PutBoolean(OfflineModeKey, value));
    }

    public bool CloneAppsInheritPrimary
    {
        get => _preferences.GetBoolean(CloneAppsInheritKey, true);
        set => Edit(e => e.PutBoolean(CloneAppsInheritKey, value));
    }

    public bool ForwardingEnabled
    {
        get => _preferences.GetBoolean(ForwardingEnabledKey, false);
        set => Edit(e => e.PutBoolean(ForwardingEnabledKey, value));
    }

    public string ForwardingUpstream
    {
        get => _preferences.GetString(ForwardingUpstreamKey, "direct") ?? "direct";
        set => Edit(e => e.PutString(ForwardingUpstreamKey, value));
    }

    public string Socks5Host
    {
        get => _preferences.GetString(Socks5HostKey, "127.0.0.1") ?? "127.0.0.1";
        set => Edit(e => e.PutString(Socks5HostKey, value));
    }

    public int Socks5Port
    {
        get => _preferences.GetInt(Socks5PortKey, 7890);
        set => Edit(e => e.PutInt(Socks5PortKey, value));
    }

    public string Socks5Username
    {
        get => _preferences.GetString(Socks5UserKey, string.Empty) ?? string.Empty;
        set => Edit(e => e.PutString(Socks5UserKey, value));
    }

    public string Socks5Password
    {
        get => _preferences.GetString(Socks5PassKey, string.Empty) ?? string.Empty;
        set => Edit(e => e.PutString(Socks5PassKey, value));
    }

    public bool BlocklistsEnabled
    {
        get => _preferences.GetBoolean(BlocklistsEnabledKey, false);
        set => Edit(e => e.PutBoolean(BlocklistsEnabledKey, value));
    }

    public bool BlockOnLockScreen
    {
        get => _preferences.GetBoolean(BlockOnLockKey, false);
        set => Edit(e => e.PutBoolean(BlockOnLockKey, value));
    }

    public bool SpeedIslandEnabled
    {
        get => _preferences.GetBoolean(SpeedIslandKey, false);
        set => Edit(e => e.PutBoolean(SpeedIslandKey, value));
    }

    public bool StatusBarSpeedIcon
    {
        get => _preferences.GetBoolean(StatusBarSpeedIconKey, false);
        set => Edit(e => e.PutBoolean(StatusBarSpeedIconKey, value));
    }

    public bool PermissionGuideShown
    {
        get => _preferences.GetBoolean(PermissionGuideShownKey, false);
        set => Edit(e => e.PutBoolean(PermissionGuideShownKey, value));
    }

    public bool PermissionBannerDismissed
    {
        get => _preferences.GetBoolean(PermissionBannerDismissedKey, false);
        set => Edit(e => e.PutBoolean(PermissionBannerDismissedKey, value));
    }

    public string WebDavUrl
    {
        get => _preferences.GetString(WebDavUrlKey, string.Empty) ?? string.Empty;
        set => Edit(e => e.PutString(WebDavUrlKey, value.Trim()));
    }

    public string WebDavUsername
    {
        get => _preferences.GetString(WebDavUserKey, string.Empty) ?? string.Empty;
        set => Edit(e => e.PutString(WebDavUserKey, value.Trim()));
    }

    public string WebDavPassword
    {
        get => _preferences.GetString(WebDavPassKey, string.Empty) ?? string.Empty;
        set => Edit(e => e.PutString(WebDavPassKey, value));
    }

    public string WebDavPassphrase
    {
        get => _preferences.GetString(WebDavPassphraseKey, string.Empty) ?? string.Empty;
        set => Edit(e => e.PutString(WebDavPassphraseKey, value));
    }

    public long WebDavLastSyncAt
    {
        get => _preferences.GetLong(WebDavLastSyncKey, 0L);
        set => Edit(e => e.PutLong(WebDavLastSyncKey, value));
    }

    public bool PromptOverlayEnabled
    {
        get => _preferences.GetBoolean(PromptOverlayEnabledKey, true);
        set => Edit(e => e.PutBoolean(PromptOverlayEnabledKey, value));
    }

    public string PromptOverlayPosition
    {
        get => _preferences.GetString(PromptOverlayPositionKey, "bottom") ?? "bottom";
        set => Edit(e => e.PutString(PromptOverlayPositionKey, value is "top" ? "top" : "bottom"));
    }

    public bool PromptShowDetails
    {
        get => _preferences.GetBoolean(PromptShowDetailsKey, true);
        set => Edit(e => e.PutBoolean(PromptShowDetailsKey, value));
    }

    public bool PromptDenyPermanent
    {
        get => _preferences.GetBoolean(PromptDenyPermanentKey, false);
        set => Edit(e => e.PutBoolean(PromptDenyPermanentKey, value));
    }

    public bool PromptShowTimedOption
    {
        get => _preferences.GetBoolean(PromptShowTimedOptionKey, false);
        set => Edit(e => e.PutBoolean(PromptShowTimedOptionKey, value));
    }

    public int PromptTimedMinutes
    {
        get => Math.Clamp(_preferences.GetInt(PromptTimedMinutesKey, 60), 1, 1440);
        set => Edit(e => e.PutInt(PromptTimedMinutesKey, Math.Clamp(value, 1, 1440)));
    }

    public bool AppLockEnabled
    {
        get => _preferences.GetBoolean(AppLockKey, false);
        set => Edit(e => e.PutBoolean(AppLockKey, value));
    }

    public bool AllowSystemApps
    {
        get => _preferences.GetBoolean(AllowSystemAppsKey, false);
        set => Edit(e => e.PutBoolean(AllowSystemAppsKey, value));
    }

    public string NotificationTitle
    {
        get => _preferences.GetString(NotificationTitleKey, string.Empty) ?? string.Empty;
        set => Edit(e => e.PutString(NotificationTitleKey, value.Trim()));
    }

    public bool DnsBypassProtection
    {
        get => _preferences.GetBoolean(DnsBypassProtectionKey, true);
        set => Edit(e => e.PutBoolean(DnsBypassProtectionKey, value));
    }

    public bool Socks5Udp
    {
        get => _preferences.GetBoolean(Socks5UdpKey, true);
        set => Edit(e => e.PutBoolean(Socks5UdpKey, value));
    }

    public IReadOnlyCollection<string> TrustedPublishers
    {
        get => (_preferences.GetStringSet(TrustedPublishersKey, null) ?? []).ToList();
        set => Edit(e => e.PutStringSet(TrustedPublishersKey, value.ToHashSet(StringComparer.OrdinalIgnoreCase)));
    }

    private void Edit(Action<ISharedPreferencesEditor> change)
    {
        using var editor = _preferences.Edit() ?? throw new InvalidOperationException("SharedPreferences 不可寫。");
        change(editor);
        editor.Apply();
    }
}
