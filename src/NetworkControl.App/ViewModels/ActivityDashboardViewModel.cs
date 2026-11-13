using System.Collections.ObjectModel;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using NetworkControl.App.Controls;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>統計時間範圍：起點對齊到本地時間的整點 / 整日，分桶與坐標軸標籤一致。</summary>
public readonly record struct ActivityRange(DateTimeOffset From, DateTimeOffset To, TimeSpan Bucket, bool Daily)
{
    public int BucketCount => (int)Math.Ceiling((To - From) / Bucket);

    /// <summary>0：最近 24 小時（每小時）；1：最近 7 天（每 6 小時）；2：最近 30 天（每天）。</summary>
    public static ActivityRange For(int index, DateTimeOffset now)
    {
        var local = now.ToLocalTime();
        switch (index)
        {
            case 1:
                {
                    var start = new DateTimeOffset(local.Date, local.Offset).AddDays(-6);
                    return new ActivityRange(start, start.AddDays(7), TimeSpan.FromHours(6), Daily: false);
                }

            case 2:
                {
                    var start = new DateTimeOffset(local.Date, local.Offset).AddDays(-29);
                    return new ActivityRange(start, start.AddDays(30), TimeSpan.FromDays(1), Daily: true);
                }

            default:
                {
                    var hour = new DateTimeOffset(local.Year, local.Month, local.Day, local.Hour, 0, 0, local.Offset);
                    var start = hour.AddHours(-23);
                    return new ActivityRange(start, start.AddHours(24), TimeSpan.FromHours(1), Daily: false);
                }
        }
    }
}

/// <summary>排行榜中的一行。</summary>
public sealed class RankItemViewModel(string label, string? detail, long allowed, long blocked, double maximum, string countText, string tooltip, LogNavigation navigation)
{
    public string Label { get; } = label;

    public string? Detail { get; } = detail;

    public bool HasDetail => !string.IsNullOrEmpty(Detail);

    public double Allowed { get; } = allowed;

    public double Blocked { get; } = blocked;

    public double Maximum { get; } = maximum;

    public string CountText { get; } = countText;

    public string Tooltip { get; } = tooltip;

    public LogNavigation Navigation { get; } = navigation;
}

/// <summary>總覽儀表板：連接活動時間軸、允許 / 拒絕總數和排行榜。頁面可見時每 30 秒刷新一次。</summary>
public sealed partial class ActivityDashboardViewModel : LocalizedViewModel
{
    private static readonly TimeSpan RefreshInterval = TimeSpan.FromSeconds(30);

    private readonly ServiceConnection _connection;
    private readonly NavigationService _navigation;
    private readonly DispatcherTimer _timer;
    private Proto.SummaryReply? _summary;
    private ActivityRange _range;
    private bool _loading;

    public ActivityDashboardViewModel(ServiceConnection connection, NavigationService navigation, DisplayText text)
        : base(text)
    {
        _connection = connection;
        _navigation = navigation;
        _timer = new DispatcherTimer { Interval = RefreshInterval };
        _timer.Tick += (_, _) => _ = LoadAsync();
        connection.Connected += (_, _) =>
        {
            if (_timer.IsEnabled)
            {
                _ = LoadAsync();
            }
        };
    }

    /// <summary>0：24 小時，1：7 天，2：30 天。</summary>
    [ObservableProperty]
    public partial int RangeIndex { get; set; }

    [ObservableProperty]
    public partial IReadOnlyList<ChartPoint> Points { get; private set; } = [];

    public ObservableCollection<RankItemViewModel> TopApps { get; } = [];

    public ObservableCollection<RankItemViewModel> TopDestinations { get; } = [];

    public bool HasData => _summary is not null;

    public bool HasActivity => _summary is { } s && s.Allowed + s.Blocked > 0;

    public bool ShowEmptyLists => _summary is not null && !HasActivity;

    public string AllowedText => Number(_summary?.Allowed);

    public string BlockedText => Number(_summary?.Blocked);

    public string BlockRateText => _summary is { } s && s.Allowed + s.Blocked > 0
        ? Loc.Format(OverviewLangResourceKind.BlockRate, ((double)s.Blocked / (s.Allowed + s.Blocked)).ToString("P0", Loc.Culture))
        : " ";

    public string AppCountText => Number(_summary?.AppCount);

    public string DestinationCountText => _summary is { } s ? Loc.Format(OverviewLangResourceKind.DestinationCount, s.DestinationCount.ToString("N0", Loc.Culture)) : " ";

    public void Activate()
    {
        _timer.Start();
        _ = LoadAsync();
    }

    public void Deactivate() => _timer.Stop();

    [RelayCommand]
    private void Open(RankItemViewModel item) => _navigation.Navigate("log", item.Navigation);

    partial void OnRangeIndexChanged(int value) => _ = LoadAsync();

    protected override void OnLanguageChanged()
    {
        Rebuild();
        base.OnLanguageChanged();
    }

    private async Task LoadAsync()
    {
        if (_loading || _connection.Clients is not { } clients)
        {
            return;
        }

        _loading = true;
        try
        {
            var range = ActivityRange.For(RangeIndex, DateTimeOffset.Now);
            var reply = await clients.Activity.GetSummaryAsync(new Proto.SummaryQuery
            {
                From = Timestamp.FromDateTimeOffset(range.From),
                To = Timestamp.FromDateTimeOffset(range.To),
                Bucket = Duration.FromTimeSpan(range.Bucket),
                TopCount = 6,
            });
            _range = range;
            _summary = reply;
            Rebuild();
        }
        catch (RpcException)
        {
            // 連接斷開時由服務不可用頁提示；舊版服務沒有這個接口時保持空白
        }
        finally
        {
            _loading = false;
        }
    }

    private void Rebuild()
    {
        if (_summary is not { } summary)
        {
            return;
        }

        Points = summary.Timeline.Select(bucket => ToPoint(bucket)).ToArray();
        Fill(TopApps, summary.TopApps, isApp: true);
        Fill(TopDestinations, summary.TopDestinations, isApp: false);
        OnPropertyChanged(nameof(HasData));
        OnPropertyChanged(nameof(HasActivity));
        OnPropertyChanged(nameof(ShowEmptyLists));
        OnPropertyChanged(nameof(AllowedText));
        OnPropertyChanged(nameof(BlockedText));
        OnPropertyChanged(nameof(BlockRateText));
        OnPropertyChanged(nameof(AppCountText));
        OnPropertyChanged(nameof(DestinationCountText));
    }

    private ChartPoint ToPoint(Proto.SummaryBucket bucket)
    {
        var start = bucket.Start.ToDateTimeOffset().ToLocalTime();
        var culture = Loc.Culture;
        string axis;
        string title;
        if (_range.Daily)
        {
            axis = start.ToString("M/d", culture);
            title = start.ToString("d", culture);
        }
        else if (_range.Bucket >= TimeSpan.FromHours(6))
        {
            axis = start.Hour == 0 ? start.ToString("M/d", culture) : "";
            title = $"{start.ToString("d", culture)} {start.ToString("HH:mm", culture)}–{start.Add(_range.Bucket).ToString("HH:mm", culture)}";
        }
        else
        {
            axis = start.ToString("HH:mm", culture);
            title = $"{start.ToString("M/d", culture)} {start.ToString("HH:mm", culture)}–{start.Add(_range.Bucket).ToString("HH:mm", culture)}";
        }

        var tooltip = Loc.Format(OverviewLangResourceKind.ChartTooltip, title, Number(bucket.Allowed), Number(bucket.Blocked));
        return new ChartPoint(bucket.Allowed, bucket.Blocked, axis, tooltip);
    }

    private void Fill(ObservableCollection<RankItemViewModel> target, IEnumerable<Proto.SummaryItem> items, bool isApp)
    {
        var list = items.ToArray();
        var max = list.Length == 0 ? 1 : list.Max(i => i.Allowed + i.Blocked);
        target.Clear();
        foreach (var item in list)
        {
            string label;
            string? detail = null;
            string keyword;
            if (isApp)
            {
                var path = item.HasPath && !string.IsNullOrWhiteSpace(item.Path) ? item.Path : null;
                var name = item.HasLabel && !string.IsNullOrWhiteSpace(item.Label) && item.Label != path ? item.Label : null;
                label = name ?? (path is not null ? FileName(path) : Loc.Get(OverviewLangResourceKind.UnknownApp));
                detail = path;
                keyword = path ?? name ?? "";
            }
            else
            {
                label = item.Key;
                keyword = item.Key;
            }

            target.Add(new RankItemViewModel(
                label,
                detail,
                item.Allowed,
                item.Blocked,
                max,
                Number(item.Allowed + item.Blocked),
                Loc.Format(OverviewLangResourceKind.RankTooltip, Number(item.Allowed), Number(item.Blocked)),
                new LogNavigation(keyword)));
        }
    }

    /// <summary>取路徑的文件名；Windows 和 Unix 分隔符都處理（服務和界面可能不在同一平台）。</summary>
    internal static string FileName(string path)
    {
        var index = path.LastIndexOfAny(['\\', '/']);
        return index >= 0 && index < path.Length - 1 ? path[(index + 1)..] : path;
    }

    private string Number(long? value) => value is { } v ? v.ToString("N0", Loc.Culture) : "—";
}
