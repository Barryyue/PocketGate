using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Domain;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Android.ViewModels;

/// <summary>阻擋記錄頁：最近被阻擋的聯網嘗試。</summary>
public sealed partial class LogViewModel(UiContext context) : LocalizedViewModel(context)
{
    private const int PageSize = 300;

    public ObservableCollection<LogItemViewModel> Items { get; } = [];

    [ObservableProperty]
    public partial bool IsLoading { get; set; }

    public bool IsEmpty => Items.Count == 0 && !IsLoading;

    [RelayCommand]
    public async Task RefreshAsync()
    {
        IsLoading = true;
        try
        {
            var entries = await Backend.GetBlockedAsync(PageSize, CancellationToken.None);
            Items.Clear();
            foreach (var entry in entries)
            {
                Items.Add(new LogItemViewModel(Context, entry));
            }
        }
        finally
        {
            IsLoading = false;
            OnPropertyChanged(nameof(IsEmpty));
        }
    }
}

/// <summary>一條阻擋記錄（同一分鐘內的相同連線已合併計數）。</summary>
public sealed partial class LogItemViewModel(UiContext context, LogEntry entry) : LocalizedViewModel(context)
{
    public LogEntry Entry => entry;

    public string AppName => entry.AppName ?? entry.AppPath ?? "?";

    public string Target => entry.RemoteAddress.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6
        ? $"[{entry.RemoteAddress}]:{entry.RemotePort} {Protocol}"
        : $"{entry.RemoteAddress}:{entry.RemotePort} {Protocol}";

    public string Time => entry.LastSeen.ToLocalTime().ToString("g", Culture);

    public string CountText => entry.Count > 1 ? Loc.Format(LogLangResourceKind.Times, entry.Count) : "";

    public string ReasonText => Loc.Get(entry.Verdict.Reason switch
    {
        VerdictReason.Rule => LogLangResourceKind.ReasonRule,
        VerdictReason.Mode => LogLangResourceKind.ReasonMode,
        VerdictReason.Prompt => LogLangResourceKind.ReasonPrompt,
        VerdictReason.PromptTimeout => LogLangResourceKind.ReasonTimeout,
        _ => LogLangResourceKind.ReasonOther,
    });

    public bool CanAllow => PackageAppPath.TryGetPackage(entry.AppPath, out _);

    private string Protocol => entry.Protocol switch
    {
        L4Protocol.Tcp => "TCP",
        L4Protocol.Udp => "UDP",
        _ => entry.Protocol.ToString().ToUpperInvariant(),
    };

    [RelayCommand]
    private Task AllowAppAsync() => PackageAppPath.TryGetPackage(entry.AppPath, out var package)
        ? Backend.SetAppDecisionAsync(package, AppDecision.Allow, CancellationToken.None)
        : Task.CompletedTask;
}
