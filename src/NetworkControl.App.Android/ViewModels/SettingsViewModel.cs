using System.Collections.ObjectModel;
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
    private bool _syncingProfiles;

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

    /// <summary>配置文件列表（只有一個時設定頁仍可用來改名）。</summary>
    public ObservableCollection<ProfileRow> Profiles { get; } = [];

    [ObservableProperty]
    public partial ProfileRow? SelectedProfile { get; set; }

    [ObservableProperty]
    public partial string ProfileName { get; set; } = "";

    /// <summary>0 詢問、1 白名單、2 黑名單。</summary>
    [ObservableProperty]
    public partial int ProfileModeIndex { get; set; } = -1;

    [ObservableProperty]
    public partial string? ProfileError { get; set; }

    public bool HasProfiles => Profiles.Count > 0;

    public bool HasSelectedProfile => SelectedProfile is not null;

    public bool CanDeleteSelected => SelectedProfile?.CanDelete == true;

    public static IReadOnlyList<string> LanguageNames { get; } = ["", "繁體中文", "简体中文", "English"];

    public string NotificationsText => Loc.Get(NotificationsEnabled ? SettingsLangResourceKind.NotificationsOn : SettingsLangResourceKind.NotificationsOff);

    public string TrafficAccessText => Loc.Get(Backend.HasTrafficAccess ? SettingsLangResourceKind.TrafficGranted : SettingsLangResourceKind.TrafficNotGranted);

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

        RefreshProfiles();
        OnPropertyChanged(nameof(NotificationsText));
        OnPropertyChanged(nameof(TrafficAccessText));
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

    partial void OnSelectedProfileChanged(ProfileRow? value)
    {
        if (_syncingProfiles)
        {
            return;
        }

        ProfileName = value?.Name ?? "";
        ProfileModeIndex = value?.ModeIndex ?? -1;
        OnPropertyChanged(nameof(HasSelectedProfile));
        OnPropertyChanged(nameof(CanDeleteSelected));
    }

    partial void OnProfileModeIndexChanged(int value)
    {
        if (!_syncingProfiles)
        {
            ProfileError = null;
        }
    }

    [RelayCommand]
    private async Task AddProfileAsync()
    {
        ProfileError = null;
        try
        {
            await Backend.CreateProfileAsync(ProfileName.Trim(), SelectedMode(), CancellationToken.None);
            ProfileName = "";
            RefreshProfiles();
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException)
        {
            ProfileError = ex.Message;
        }
    }

    [RelayCommand]
    private async Task SaveProfileAsync()
    {
        if (SelectedProfile is not { } selected)
        {
            return;
        }

        ProfileError = null;
        try
        {
            await Backend.UpdateProfileAsync(selected.Id, ProfileName.Trim(), SelectedMode(), CancellationToken.None);
            RefreshProfiles();
        }
        catch (Exception ex) when (ex is ArgumentException or KeyNotFoundException or InvalidOperationException)
        {
            ProfileError = ex.Message;
        }
    }

    [RelayCommand]
    private async Task ActivateProfileAsync()
    {
        if (SelectedProfile is { } selected)
        {
            await Backend.ActivateProfileAsync(selected.Id, CancellationToken.None);
            RefreshProfiles();
        }
    }

    [RelayCommand]
    private async Task DeleteProfileAsync()
    {
        if (SelectedProfile is not { CanDelete: true } selected)
        {
            return;
        }

        ProfileError = null;
        try
        {
            await Backend.DeleteProfileAsync(selected.Id, CancellationToken.None);
            RefreshProfiles();
        }
        catch (Exception ex) when (ex is ArgumentException or KeyNotFoundException or InvalidOperationException)
        {
            ProfileError = ex.Message;
        }
    }

    private void RefreshProfiles()
    {
        var selectedId = SelectedProfile?.Id;
        _syncingProfiles = true;
        Profiles.Clear();
        foreach (var profile in Backend.Profiles.OrderBy(p => p.SortOrder))
        {
            Profiles.Add(new ProfileRow(
                profile.Id,
                profile.Name,
                profile.Mode,
                ModeText(profile.Mode),
                Loc.Format(SettingsLangResourceKind.ProfileRuleCount, Backend.GetRuleCount(profile.Id)),
                profile.Id == Backend.ActiveProfile.Id,
                profile.Id == Profile.DefaultId));
        }

        SelectedProfile = Profiles.FirstOrDefault(p => p.Id == selectedId)
            ?? Profiles.FirstOrDefault(p => p.IsActive)
            ?? Profiles.FirstOrDefault();
        _syncingProfiles = false;
        ProfileName = SelectedProfile?.Name ?? "";
        ProfileModeIndex = SelectedProfile?.ModeIndex ?? -1;
        OnPropertyChanged(nameof(HasProfiles));
        OnPropertyChanged(nameof(HasSelectedProfile));
        OnPropertyChanged(nameof(CanDeleteSelected));
    }

    private FilterMode SelectedMode() => ProfileModeIndex switch
    {
        1 => FilterMode.Whitelist,
        2 => FilterMode.Blacklist,
        _ => FilterMode.Ask,
    };


    [RelayCommand]
    private void OpenNotificationSettings() => MainActivity.Current?.OpenNotificationSettings();

    [RelayCommand]
    private void OpenVpnSettings() => MainActivity.Current?.OpenVpnSettings();

    [RelayCommand]
    private void OpenBatterySettings() => MainActivity.Current?.OpenBatterySettings();

    [RelayCommand]
    private void OpenTrafficSettings() => MainActivity.Current?.StartActivity(Backend.CreateTrafficAccessIntent());
}
