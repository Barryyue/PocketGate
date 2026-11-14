using System.Reflection;
using AtomUI.Localization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Domain;

namespace NetworkControl.Android.ViewModels;

/// <summary>設定頁。</summary>
public sealed partial class SettingsViewModel : LocalizedViewModel
{
    private static readonly LanguageTag?[] Languages = [null, LanguageTags.ZhTW, LanguageTags.ZhCN, LanguageTags.EnUS];
    private static readonly int[] TimeoutSeconds = [15, 30, 60, 120];

    private bool _syncing;

    public SettingsViewModel(UiContext context)
        : base(context)
    {
        Refresh();
    }

    /// <summary>0 跟隨系統、1 繁體中文、2 简体中文、3 English。</summary>
    [ObservableProperty]
    public partial int LanguageIndex { get; set; } = -1;

    /// <summary>0 跟隨系統、1 淺色、2 深色。</summary>
    [ObservableProperty]
    public partial int ThemeIndex { get; set; } = -1;

    [ObservableProperty]
    public partial int TimeoutIndex { get; set; } = -1;

    /// <summary>0 拒絕、1 允許。</summary>
    [ObservableProperty]
    public partial int DefaultActionIndex { get; set; } = -1;

    [ObservableProperty]
    public partial bool NotificationsEnabled { get; set; }

    public static IReadOnlyList<string> LanguageNames { get; } = ["", "繁體中文", "简体中文", "English"];

    public string NotificationsText => Loc.Get(NotificationsEnabled ? SettingsLangResourceKind.NotificationsOn : SettingsLangResourceKind.NotificationsOff);

    public string TimeoutText(int index) => Loc.Format(SettingsLangResourceKind.Seconds, TimeoutSeconds[index]);

    public string Timeout15 => TimeoutText(0);

    public string Timeout30 => TimeoutText(1);

    public string Timeout60 => TimeoutText(2);

    public string Timeout120 => TimeoutText(3);

    public static string Version { get; } =
        typeof(SettingsViewModel).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0.0.0";

    public string StatisticsText
    {
        get
        {
            var c = Backend.Counters;
            return Loc.Format(SettingsLangResourceKind.StatisticsFormat, c.Packets, c.Attempts, c.Reported);
        }
    }

    public void Refresh()
    {
        _syncing = true;
        try
        {
            var language = Loc.Preference;
            LanguageIndex = Math.Max(0, Array.FindIndex(Languages, l => l?.ToString() == language));
            ThemeIndex = (int)Context.Appearance.Preference;
            var settings = Backend.Settings;
            var seconds = (int)settings.PromptTimeout.TotalSeconds;
            TimeoutIndex = Array.IndexOf(TimeoutSeconds, seconds) is var i and >= 0 ? i : 1;
            DefaultActionIndex = settings.PromptDefaultAction == VerdictAction.Allow ? 1 : 0;
            NotificationsEnabled = MainActivity.Current?.AreNotificationsEnabled ?? false;
        }
        finally
        {
            _syncing = false;
        }

        OnPropertyChanged(nameof(NotificationsText));
        OnPropertyChanged(nameof(StatisticsText));
    }

    partial void OnLanguageIndexChanged(int value)
    {
        if (!_syncing && value >= 0 && value < Languages.Length)
        {
            Loc.SetPreference(Languages[value]);
        }
    }

    partial void OnThemeIndexChanged(int value)
    {
        if (!_syncing && value >= 0)
        {
            _ = Context.Appearance.SetPreferenceAsync((ThemePreference)value);
        }
    }

    partial void OnTimeoutIndexChanged(int value)
    {
        if (!_syncing && value >= 0 && value < TimeoutSeconds.Length)
        {
            var timeout = TimeSpan.FromSeconds(TimeoutSeconds[value]);
            _ = Backend.UpdateSettingsAsync(s => s with { PromptTimeout = timeout }, CancellationToken.None);
        }
    }

    partial void OnDefaultActionIndexChanged(int value)
    {
        if (!_syncing && value >= 0)
        {
            var action = value == 1 ? VerdictAction.Allow : VerdictAction.Deny;
            _ = Backend.UpdateSettingsAsync(s => s with { PromptDefaultAction = action }, CancellationToken.None);
        }
    }

    [RelayCommand]
    private void OpenNotificationSettings() => MainActivity.Current?.OpenNotificationSettings();

    [RelayCommand]
    private void OpenVpnSettings() => MainActivity.Current?.OpenVpnSettings();

    [RelayCommand]
    private void OpenBatterySettings() => MainActivity.Current?.OpenBatterySettings();
}
