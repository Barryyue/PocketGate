using System.Collections.ObjectModel;
using System.Globalization;
using System.Text;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Domain;
using NetworkControl.Engine.Vpn.Forwarding;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Android.ViewModels;

public sealed partial class ActiveConnectionItemViewModel(
    ActiveForwardingConnection conn,
    IBackend backend,
    Action refresh) : ObservableObject
{
    public string Key => conn.Key;
    public string Destination => $"{conn.Destination}:{conn.RemotePort}";
    public string Subtitle => $"{conn.Protocol} · {conn.Key}";

    [RelayCommand]
    private void Cut()
    {
        backend.CutActiveConnection(Key);
        refresh();
    }
}

/// <summary>阻擋記錄、放行記錄與即時活躍連線頁（與 Kotlin 版 LogsScreen 完全對齊）。</summary>
public sealed partial class LogViewModel(UiContext context) : LocalizedViewModel(context)
{
    private const int PageSize = 500;
    private IReadOnlyList<LogEntry> _allEntries = [];

    public ObservableCollection<LogItemViewModel> Items { get; } = [];
    public ObservableCollection<ActiveConnectionItemViewModel> LiveItems { get; } = [];

    /// <summary>0 已阻擋、1 已放行、2 即時連線。</summary>
    [ObservableProperty]
    public partial int TabIndex { get; set; }

    /// <summary>0 全部、1 規則、2 模式、3 詢問、4 未回應。</summary>
    [ObservableProperty]
    public partial int ReasonFilterIndex { get; set; }

    [ObservableProperty]
    public partial string SearchQuery { get; set; } = string.Empty;

    [ObservableProperty]
    public partial LogItemViewModel? SelectedLog { get; set; }

    [ObservableProperty]
    public partial string? ExportStatus { get; set; }

    public bool IsBlockedTab => TabIndex == 0;
    public bool IsAllowedTab => TabIndex == 1;
    public bool IsLogsTab => TabIndex is 0 or 1;
    public bool IsLiveTab => TabIndex == 2;
    public bool HasSelectedLog => SelectedLog is not null;

    [ObservableProperty]
    public partial bool IsLoading { get; set; }

    public bool IsEmpty => IsLogsTab ? (Items.Count == 0 && !IsLoading) : LiveItems.Count == 0;

    partial void OnTabIndexChanged(int value)
    {
        OnPropertyChanged(nameof(IsBlockedTab));
        OnPropertyChanged(nameof(IsAllowedTab));
        OnPropertyChanged(nameof(IsLogsTab));
        OnPropertyChanged(nameof(IsLiveTab));
        _ = RefreshAsync();
    }

    partial void OnReasonFilterIndexChanged(int value) => ApplyFilter();

    partial void OnSearchQueryChanged(string value) => ApplyFilter();

    partial void OnSelectedLogChanged(LogItemViewModel? value) => OnPropertyChanged(nameof(HasSelectedLog));

    [RelayCommand]
    public async Task RefreshAsync()
    {
        IsLoading = true;
        try
        {
            if (IsLiveTab)
            {
                LiveItems.Clear();
                foreach (var conn in Backend.GetActiveConnections())
                {
                    LiveItems.Add(new ActiveConnectionItemViewModel(conn, Backend, () => _ = RefreshAsync()));
                }
            }
            else
            {
                _allEntries = IsAllowedTab
                    ? await Backend.GetAllowedAsync(PageSize, CancellationToken.None)
                    : await Backend.GetBlockedAsync(PageSize, CancellationToken.None);
                ApplyFilter();
            }
        }
        finally
        {
            IsLoading = false;
            OnPropertyChanged(nameof(IsEmpty));
        }
    }

    [RelayCommand]
    private void OpenDetail(LogItemViewModel? item) => SelectedLog = item;

    [RelayCommand]
    private void CloseDetail() => SelectedLog = null;

    [RelayCommand]
    private async Task ExportCsvAsync()
    {
        if (MainActivity.Current is not { } activity) return;
        var sb = new StringBuilder();
        sb.AppendLine("Timestamp,Action,App,Package,Target,Port,Protocol,Reason,Count");
        foreach (var item in Items)
        {
            var e = item.Entry;
            var host = !string.IsNullOrEmpty(e.Hostname) ? e.Hostname : e.RemoteAddress.ToString();
            sb.AppendLine(string.Join(",",
                Csv(e.LastSeen.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture)),
                Csv(e.Verdict.Action.ToString()),
                Csv(item.AppName),
                Csv(item.PackageName ?? ""),
                Csv(host),
                e.RemotePort.ToString(CultureInfo.InvariantCulture),
                Csv(e.Protocol.ToString()),
                Csv(e.Verdict.Reason.ToString()),
                e.Count.ToString(CultureInfo.InvariantCulture)));
        }

        var fileName = $"pocketgate-logs-{DateTime.Now:yyyy-MM-dd-HHmm}.csv";
        var uri = await activity.RequestCreateDocumentAsync(fileName, "text/csv");
        if (uri is null) return;
        using var stream = activity.ContentResolver?.OpenOutputStream(uri);
        if (stream is null) return;
        await stream.WriteAsync(Encoding.UTF8.GetBytes(sb.ToString()));
        ExportStatus = fileName;
    }

    private static string Csv(string value) => $"\"{value.Replace("\"", "\"\"")}\"";

    private void ApplyFilter()
    {
        Items.Clear();
        var q = SearchQuery.Trim();
        foreach (var entry in _allEntries)
        {
            if (ReasonFilterIndex > 0)
            {
                var matchesReason = ReasonFilterIndex switch
                {
                    1 => entry.Verdict.Reason is VerdictReason.Rule,
                    2 => entry.Verdict.Reason is VerdictReason.Mode,
                    3 => entry.Verdict.Reason is VerdictReason.Prompt,
                    4 => entry.Verdict.Reason is VerdictReason.PromptTimeout,
                    _ => true,
                };
                if (!matchesReason) continue;
            }

            if (q.Length > 0)
            {
                var app = entry.AppName ?? entry.AppPath ?? "";
                var dom = entry.Hostname ?? "";
                var ip = entry.RemoteAddress.ToString();
                if (!app.Contains(q, StringComparison.OrdinalIgnoreCase) &&
                    !dom.Contains(q, StringComparison.OrdinalIgnoreCase) &&
                    !ip.Contains(q, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }
            }

            Items.Add(new LogItemViewModel(Context, entry, item => SelectedLog = item, () => SelectedLog = null));
        }

        OnPropertyChanged(nameof(IsEmpty));
    }
}

/// <summary>一條連線記錄（同一分鐘內的相同連線已合併計數）。</summary>
public sealed partial class LogItemViewModel(
    UiContext context,
    LogEntry entry,
    Action<LogItemViewModel>? openDetail = null,
    Action? closeDetail = null) : LocalizedViewModel(context)
{
    public LogEntry Entry => entry;

    public string AppName => entry.AppName ?? (PackageAppPath.TryGetPackage(entry.AppPath, out var p) ? p : entry.AppPath) ?? "?";

    public string? PackageName => PackageAppPath.TryGetPackage(entry.AppPath, out var p) ? p : null;

    public string Target
    {
        get
        {
            var host = !string.IsNullOrEmpty(entry.Hostname) ? entry.Hostname : entry.RemoteAddress.ToString();
            var protocol = entry.Protocol == L4Protocol.Udp ? "UDP" : "TCP";
            return $"{host}:{entry.RemotePort} {protocol}";
        }
    }

    public string Time => entry.LastSeen.ToLocalTime().ToString("HH:mm:ss", CultureInfo.CurrentCulture);

    public string FullTime => entry.LastSeen.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.CurrentCulture);

    public string CountText => entry.Count > 1 ? Loc.Format(LogLangResourceKind.Times, entry.Count) : "";

    public string ReasonText => Loc.Get(entry.Verdict.Reason switch
    {
        VerdictReason.Rule => LogLangResourceKind.ReasonRule,
        VerdictReason.Mode => LogLangResourceKind.ReasonMode,
        VerdictReason.Prompt => LogLangResourceKind.ReasonPrompt,
        VerdictReason.PromptTimeout => LogLangResourceKind.ReasonTimeout,
        VerdictReason.DoNotDisturb => LogLangResourceKind.ReasonDnd,
        _ => LogLangResourceKind.ReasonOther,
    });

    public string ActionBadge => entry.Verdict.Action == VerdictAction.Allow
        ? Loc.Get(CommonLangResourceKind.Allow)
        : Loc.Get(CommonLangResourceKind.Block);

    public string ProtocolText => entry.Protocol == L4Protocol.Udp ? "UDP" : "TCP";

    public string? Domain => string.IsNullOrWhiteSpace(entry.Hostname) ? null : entry.Hostname;

    public bool CanAllow => entry.Verdict.Action == VerdictAction.Deny && PackageAppPath.TryGetPackage(entry.AppPath, out _);

    public bool CanAllowDomain => entry.Verdict.Action == VerdictAction.Deny && !string.IsNullOrWhiteSpace(entry.Hostname);

    [RelayCommand]
    private void Inspect() => openDetail?.Invoke(this);

    [RelayCommand]
    private async Task AllowAppAsync()
    {
        if (PackageAppPath.TryGetPackage(entry.AppPath, out var package))
        {
            await Backend.SetAppDecisionAsync(package, AppDecision.Allow, CancellationToken.None);
            closeDetail?.Invoke();
        }
    }

    [RelayCommand]
    private async Task AllowDomainAsync()
    {
        if (!string.IsNullOrWhiteSpace(entry.Hostname))
        {
            await Backend.AllowDomainAsync(entry.Hostname, CancellationToken.None);
            closeDetail?.Invoke();
        }
    }
}
