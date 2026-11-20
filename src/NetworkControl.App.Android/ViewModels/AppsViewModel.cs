using System.Collections.ObjectModel;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Localization;
using NetworkControl.Engine.Vpn.Routing;

namespace NetworkControl.Android.ViewModels;

/// <summary>應用頁：所有聯網應用及其允許 / 拒絕設定。</summary>
public sealed partial class AppsViewModel : LocalizedViewModel
{
    private readonly Dictionary<string, AppItemViewModel> _items = new(StringComparer.Ordinal);

    public AppsViewModel(UiContext context)
        : base(context)
    {
    }

    public ObservableCollection<AppItemViewModel> Visible { get; } = [];

    [ObservableProperty]
    public partial string? SearchText { get; set; }

    /// <summary>0 全部、1 已允許、2 已拒絕、3 未設定。</summary>
    [ObservableProperty]
    public partial int FilterIndex { get; set; }

    [ObservableProperty]
    public partial bool ShowSystem { get; set; }

    [ObservableProperty]
    public partial AppItemViewModel? DetailApp { get; set; }

    [ObservableProperty]
    public partial string? DetailSignerSha256 { get; set; }

    [ObservableProperty]
    public partial bool IsDetailPublisherTrusted { get; set; }

    public ObservableCollection<string> DetailRecentTargets { get; } = [];

    public bool HasDetailApp => DetailApp is not null;

    partial void OnDetailAppChanged(AppItemViewModel? value) => OnPropertyChanged(nameof(HasDetailApp));

    public string CountText => Loc.Format(AppsLangResourceKind.Count, Visible.Count);

    public bool IsEmpty => Visible.Count == 0;

    /// <summary>讀取今日各應用的用量並填到對應的行（後台查詢，回到 UI 線程賦值）。</summary>
    private async Task LoadUsageAsync()
    {
        if (!Backend.HasTrafficAccess)
        {
            return;
        }

        try
        {
            var today = new DateTimeOffset(DateTimeOffset.Now.Date, DateTimeOffset.Now.Offset);
            var usage = await Backend.GetTrafficAsync(today, today.AddDays(1), CancellationToken.None);
            await Avalonia.Threading.Dispatcher.UIThread.InvokeAsync(() =>
            {
                foreach (var (package, item) in _items)
                {
                    item.UsageText = usage.ByPackage.TryGetValue(package, out var bytes) && bytes.In + bytes.Out > 0
                        ? Loc.Format(AppsLangResourceKind.UsageToday, Infrastructure.ByteSize.Format(bytes.In + bytes.Out))
                        : null;
                }
            });
        }
        catch (Exception ex) when (ex is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            // 用量只是附加信息
        }
    }

    /// <summary>從核心重新讀取應用列表（UI 線程）。</summary>
    public void Refresh()
    {
        var entries = Backend.GetApps();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var entry in entries)
        {
            seen.Add(entry.App.PackageName);
            if (_items.TryGetValue(entry.App.PackageName, out var item))
            {
                item.Update(entry);
            }
            else
            {
                _items[entry.App.PackageName] = new AppItemViewModel(Context, entry, item => _ = OpenDetailAsync(item));
            }
        }

        foreach (var removed in _items.Keys.Where(k => !seen.Contains(k)).ToArray())
        {
            _items.Remove(removed);
        }

        _ = LoadUsageAsync();

        ApplyFilter();
    }

    partial void OnSearchTextChanged(string? value) => ApplyFilter();

    partial void OnFilterIndexChanged(int value) => ApplyFilter();

    [CommunityToolkit.Mvvm.Input.RelayCommand]
    private void CloseDetail() => DetailApp = null;

    [CommunityToolkit.Mvvm.Input.RelayCommand]
    private void TrustDetailPublisher()
    {
        if (string.IsNullOrEmpty(DetailSignerSha256)) return;
        Backend.TrustPublisher(DetailSignerSha256);
        IsDetailPublisherTrusted = true;
    }

    private async Task OpenDetailAsync(AppItemViewModel item)
    {
        DetailApp = item;
        DetailSignerSha256 = Backend.GetAppSignerSha256(item.PackageName);
        IsDetailPublisherTrusted = DetailSignerSha256 is { Length: > 0 } sha &&
            Backend.Preferences.TrustedPublishers.Contains(sha, StringComparer.OrdinalIgnoreCase);
        DetailRecentTargets.Clear();
        var recent = await Backend.GetRecentTargetsAsync(item.PackageName, 10, CancellationToken.None);
        foreach (var r in recent)
        {
            var host = !string.IsNullOrEmpty(r.Hostname) ? r.Hostname : r.RemoteAddress.ToString();
            DetailRecentTargets.Add($"{host}:{r.RemotePort} ({r.Protocol}) · ×{r.Count}");
        }
    }

    partial void OnShowSystemChanged(bool value) => ApplyFilter();

    private void ApplyFilter()
    {
        var search = SearchText?.Trim();
        var filtered = _items.Values
            .Where(i => ShowSystem || !i.Entry.App.IsSystem || i.Entry.Decision != AppDecision.Unset)
            .Where(i => FilterIndex switch
            {
                1 => i.Entry.Decision == AppDecision.Allow,
                2 => i.Entry.Decision == AppDecision.Deny,
                3 => i.Entry.Decision == AppDecision.Unset,
                _ => true,
            })
            .Where(i => string.IsNullOrEmpty(search)
                || i.Label.Contains(search, StringComparison.CurrentCultureIgnoreCase)
                || i.PackageName.Contains(search, StringComparison.OrdinalIgnoreCase))
            .OrderBy(i => i.Label, StringComparer.CurrentCultureIgnoreCase)
            .ToList();

        // 列表內容相同時不重建，避免滾動位置跳動
        if (filtered.SequenceEqual(Visible))
        {
            return;
        }

        Visible.Clear();
        foreach (var item in filtered)
        {
            Visible.Add(item);
        }

        OnPropertyChanged(nameof(CountText));
        OnPropertyChanged(nameof(IsEmpty));
    }
}

/// <summary>應用列表中的一行。</summary>
public sealed partial class AppItemViewModel : LocalizedViewModel
{
    private readonly Action<AppItemViewModel>? _openDetail;
    private bool _syncing;
    private bool _iconRequested;

    public AppItemViewModel(UiContext context, AppEntry entry, Action<AppItemViewModel>? openDetail = null)
        : base(context)
    {
        _openDetail = openDetail;
        Entry = entry;
        Sync();
    }

    [CommunityToolkit.Mvvm.Input.RelayCommand]
    private void Inspect() => _openDetail?.Invoke(this);

    public AppEntry Entry { get; private set; }

    public string PackageName => Entry.App.PackageName;

    public string Label => Entry.App.Label;

    public bool IsSystem => Entry.App.IsSystem;

    public bool IsTampered => Entry.IsTampered;

    /// <summary>今日用量（「今日 12 MB」）；沒有用量或沒有權限時為 null。</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasUsage))]
    public partial string? UsageText { get; set; }

    public bool HasUsage => UsageText is not null;

    /// <summary>0 允許、1 未設定、2 拒絕。</summary>
    [ObservableProperty]
    public partial int DecisionIndex { get; set; } = -1;

    [ObservableProperty]
    public partial bool IsBusy { get; set; }

    /// <summary>應用圖標，首次讀取時異步加載。</summary>
    public Bitmap? Icon
    {
        get
        {
            if (!_iconRequested)
            {
                _iconRequested = true;
                _ = LoadIconAsync();
            }

            return field;
        }

        private set => SetProperty(ref field, value);
    }

    public string StatusText
    {
        get
        {
            var now = Entry.Effective switch
            {
                AppRoutingDecision.Bypass => Loc.Get(AppsLangResourceKind.NowAllowed),
                AppRoutingDecision.BlockAndAsk => Loc.Get(AppsLangResourceKind.NowAsk),
                _ => Loc.Get(AppsLangResourceKind.NowBlocked),
            };

            if (Entry.Decision == AppDecision.Unset)
            {
                return Loc.Format(AppsLangResourceKind.FollowsMode, now);
            }

            if (Entry.ExpiresAt is { } expires)
            {
                return $"{now} · {Loc.Format(AppsLangResourceKind.Until, expires.ToLocalTime().ToString("t", Culture))}";
            }

            return Entry.IsTemporary ? $"{now} · {Loc.Get(AppsLangResourceKind.UntilRestart)}" : now;
        }
    }

    /// <summary>狀態文字顏色的語義：success / error / warning。</summary>
    public string StatusKind => Entry.Effective switch
    {
        AppRoutingDecision.Bypass => "success",
        AppRoutingDecision.BlockAndAsk => "warning",
        _ => "error",
    };

    public void Update(AppEntry entry)
    {
        var changed = entry != Entry;
        Entry = entry;
        if (changed)
        {
            Sync();
            OnPropertyChanged(string.Empty);
        }
    }

    partial void OnDecisionIndexChanged(int value)
    {
        if (_syncing || value < 0 || IndexFor(Entry.Decision) == value)
        {
            return;
        }

        var decision = value switch
        {
            0 => AppDecision.Allow,
            2 => AppDecision.Deny,
            _ => AppDecision.Unset,
        };
        _ = ApplyAsync(decision);
    }

    private async Task ApplyAsync(AppDecision decision)
    {
        IsBusy = true;
        try
        {
            await Backend.SetAppDecisionAsync(PackageName, decision, CancellationToken.None);
        }
        finally
        {
            IsBusy = false;
        }
    }

    private void Sync()
    {
        _syncing = true;
        try
        {
            DecisionIndex = IndexFor(Entry.Decision);
        }
        finally
        {
            _syncing = false;
        }
    }

    private static int IndexFor(AppDecision decision) => decision switch
    {
        AppDecision.Allow => 0,
        AppDecision.Deny => 2,
        _ => 1,
    };

    private async Task LoadIconAsync()
    {
        var icon = await Context.Icons.LoadAsync(PackageName);
        await Dispatcher.UIThread.InvokeAsync(() => Icon = icon);
    }
}
