using System.Collections.ObjectModel;
using AtomUI.Desktop.Controls;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Google.Protobuf.WellKnownTypes;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>應用頁中的一行。</summary>
public sealed class AppItemViewModel(Proto.AppSummary summary, DisplayText text, Func<DateTimeOffset> now)
{
    private LocalizationService Loc => text.Localization;

    public Proto.AppSummary Summary { get; } = summary;

    public string Key => Summary.Key;

    public string Name => Summary.DisplayName.Length > 0 ? Summary.DisplayName : Loc.Get(AppsLangResourceKind.TrustUnknown);

    public string Path => Summary.Path;

    public bool HasPath => Summary.Path.Length > 0;

    public string TrustText => Summary.HasSigner
        ? Summary.Signer
        : Loc.Get(Summary.IsPlatform ? AppsLangResourceKind.TrustSystem
            : Summary.HasAppId ? AppsLangResourceKind.TrustUnsigned : AppsLangResourceKind.TrustUnknown);

    /// <summary>AtomUI Tag 的預設顏色：已簽名綠色、系統藍色、未簽名橙色、未識別灰色。</summary>
    public string TrustColor => Summary.IsPlatform ? "processing"
        : Summary.HasSigner ? "success"
        : Summary.HasAppId ? "warning" : "default";

    public string AllowedText => Summary.Allowed.ToString("N0", Loc.Culture);

    public string BlockedText => Summary.Blocked.ToString("N0", Loc.Culture);

    public bool HasBlocked => Summary.Blocked > 0;

    public string RulesText => Summary.AllowRules + Summary.DenyRules == 0
        ? Loc.Get(AppsLangResourceKind.RulesNone)
        : Loc.Format(AppsLangResourceKind.RulesSummary, Summary.AllowRules, Summary.DenyRules);

    public string LastSeenText => Relative(Summary.LastSeen.ToDateTimeOffset());

    public string UsageText => Summary.BytesIn + Summary.BytesOut == 0
        ? "—"
        : Loc.Format(AppsLangResourceKind.UsageText, Controls.ActivityChart.Bytes(Summary.BytesIn), Controls.ActivityChart.Bytes(Summary.BytesOut));

    public bool Matches(string keyword) =>
        keyword.Length == 0
        || Name.Contains(keyword, StringComparison.CurrentCultureIgnoreCase)
        || Path.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || (Summary.HasSigner && Summary.Signer.Contains(keyword, StringComparison.CurrentCultureIgnoreCase));

    private string Relative(DateTimeOffset at)
    {
        var elapsed = now() - at;
        if (elapsed < TimeSpan.FromMinutes(1))
        {
            return Loc.Get(AppsLangResourceKind.LastSeenJustNow);
        }

        if (elapsed < TimeSpan.FromHours(1))
        {
            return Loc.Format(AppsLangResourceKind.LastSeenMinutes, (int)elapsed.TotalMinutes);
        }

        return elapsed < TimeSpan.FromHours(24)
            ? Loc.Format(AppsLangResourceKind.LastSeenHours, (int)elapsed.TotalHours)
            : at.ToLocalTime().ToString("g", Loc.Culture);
    }
}

/// <summary>應用頁：每個連過網路的應用、它的簽名、允許 / 拒絕次數和相關規則；可以直接為應用建規則或跳轉到記錄。</summary>
public sealed partial class AppsViewModel : PageViewModel
{
    private static readonly DataGridLocalSourceDescriptor<AppItemViewModel> RowDescriptor =
        DataGridLocalSourceDescriptor.For<AppItemViewModel>(static row => DataGridRowKey.FromString(row.Key));

    private readonly ServiceConnection _connection;
    private readonly RuleEditing _editing;
    private readonly NavigationService _navigation;
    private readonly List<AppItemViewModel> _all = [];
    private DataGridLocalSource<AppItemViewModel>? _source;

    public AppsViewModel(ServiceConnection connection, RuleEditing editing, NavigationService navigation, DisplayText text)
        : base(text)
    {
        _connection = connection;
        connection.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(ServiceConnection.Status))
            {
                OnPropertyChanged(nameof(CanModify));
            }
        };
        _editing = editing;
        _navigation = navigation;
    }

    public override string Key => "apps";

    public bool CanModify => _connection.CanModify;

    public ObservableCollection<AppItemViewModel> Items { get; } = [];

    /// <summary>AtomUI DataGrid 需要 IDataGridSource（見 AGENTS.md 第 6 節）。</summary>
    public IDataGridSource ItemsSource => _source ??= DataGridLocalSource.Create(Items, RowDescriptor);

    [ObservableProperty]
    public partial string Keyword { get; set; } = "";

    /// <summary>0：24 小時，1：7 天，2：30 天。默認 7 天。</summary>
    [ObservableProperty]
    public partial int RangeIndex { get; set; } = 1;

    [ObservableProperty]
    public partial bool IsLoading { get; set; }

    public bool IsEmpty => Items.Count == 0 && !IsLoading;

    public string CountText => Loc.Format(AppsLangResourceKind.CountSummary, Items.Count);

    public override Task OnActivatedAsync() => RefreshAsync();

    [RelayCommand]
    private Task RefreshAsync() => RunAsync(async () =>
    {
        IsLoading = true;
        try
        {
            var range = ActivityRange.For(RangeIndex, DateTimeOffset.Now);
            var response = await _connection.RequireClients().Apps.ListAsync(new Proto.ListAppsRequest
            {
                From = Timestamp.FromDateTimeOffset(range.From),
                To = Timestamp.FromDateTimeOffset(range.To),
                Limit = 1000,
            });
            _all.Clear();
            _all.AddRange(response.Apps.Select(a => new AppItemViewModel(a, Text, () => DateTimeOffset.Now)));
        }
        finally
        {
            IsLoading = false;
            ApplyFilter();
        }
    });

    [RelayCommand]
    private Task AllowAsync(AppItemViewModel item) => CreateRuleAsync(item, Proto.VerdictAction.Allow);

    [RelayCommand]
    private Task DenyAsync(AppItemViewModel item) => CreateRuleAsync(item, Proto.VerdictAction.Deny);

    [RelayCommand]
    private void ViewLog(AppItemViewModel item) => _navigation.Navigate("log", new LogNavigation(item.HasPath ? item.Path : item.Name));

    [RelayCommand]
    private void ViewRules(AppItemViewModel item) => _navigation.Navigate("rules", new RulesNavigation(item.HasPath ? item.Path : item.Name));

    partial void OnKeywordChanged(string value) => ApplyFilter();

    partial void OnRangeIndexChanged(int value) => _ = RefreshAsync();

    partial void OnIsLoadingChanged(bool value) => OnPropertyChanged(nameof(IsEmpty));

    protected override void OnLanguageChanged()
    {
        // 行中的文本都在 getter 中按當前語言計算：重建行對象即可刷新
        ApplyFilter();
        base.OnLanguageChanged();
    }

    private async Task CreateRuleAsync(AppItemViewModel item, Proto.VerdictAction action)
    {
        if (!item.HasPath)
        {
            return;
        }

        // 打開預填好的規則編輯器，讓用戶確認範圍（默認任意目標、永久）後再提交
        if (await _editing.CreateAsync(new RulePrefill(AppPath: item.Path, Action: action)))
        {
            await RefreshAsync();
        }
    }

    private void ApplyFilter()
    {
        var keyword = Keyword.Trim();
        Items.Clear();
        foreach (var item in _all.Where(a => a.Matches(keyword)))
        {
            Items.Add(new AppItemViewModel(item.Summary, Text, () => DateTimeOffset.Now));
        }

        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(CountText));
    }
}
