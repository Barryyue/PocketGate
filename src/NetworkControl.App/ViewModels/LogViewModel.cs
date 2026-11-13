using System.Collections.ObjectModel;
using AtomUI.Desktop.Controls;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>日誌中的一行（實時事件和歷史查詢共用）。保存原始字段，顯示文本按當前語言計算。</summary>
public sealed class LogItemViewModel : ViewModelBase
{
    private static long _nextKey;

    private readonly DisplayText _text;

    private LogItemViewModel(
        DisplayText text, Timestamp? time, string? appName, string? appPath, Proto.Direction direction, Proto.Protocol protocol,
        Proto.IpEndpoint? remote, string? host, Proto.Verdict verdict, int count)
    {
        _text = text;
        Time = time;
        AppPathValue = appPath ?? "";
        AppNameValue = appName ?? DisplayText.FileName(appPath);
        Direction = direction;
        Protocol = protocol;
        RemoteEndpoint = remote;
        Host = host ?? "";
        Verdict = verdict;
        Count = count;
        Key = Interlocked.Increment(ref _nextKey).ToString(System.Globalization.CultureInfo.InvariantCulture);
    }

    /// <summary>表格行鍵：實時事件沒有持久化 ID，按創建順序生成。</summary>
    public string Key { get; }

    public Timestamp? Time { get; }

    public string AppPathValue { get; }

    public string AppNameValue { get; }

    public Proto.Direction Direction { get; }

    public Proto.Protocol Protocol { get; }

    public Proto.IpEndpoint? RemoteEndpoint { get; }

    public string Host { get; }

    public Proto.Verdict Verdict { get; }

    public int Count { get; }

    public string TimeText => _text.DateTime(Time);

    public string AppName => AppNameValue.Length > 0 ? AppNameValue : _text.Localization.Get(CommonLangResourceKind.UnknownApp);

    public string DirectionText => _text.Direction(Direction);

    public string ProtocolText => DisplayText.Protocol(Protocol);

    public string Remote => DisplayText.Endpoint(RemoteEndpoint);

    public string VerdictText => _text.Action(Verdict.Action);

    public string VerdictColor => Verdict.Action == Proto.VerdictAction.Allow ? "success" : "error";

    public string ReasonText => _text.Reason(Verdict.Reason);

    public bool HasApp => AppPathValue.Length > 0;

    public static LogItemViewModel From(Proto.ConnectionEvent e, DisplayText text) => new(
        text, e.Timestamp, e.HasAppName ? e.AppName : null, e.HasAppPath ? e.AppPath : null, e.Direction, e.Protocol,
        e.Remote, e.HasHostname ? e.Hostname : null, e.Verdict, 1);

    public static LogItemViewModel From(Proto.LogEntry e, DisplayText text) => new(
        text, e.LastSeen, e.HasAppName ? e.AppName : null, e.HasAppPath ? e.AppPath : null, e.Direction, e.Protocol,
        e.Remote, e.HasHostname ? e.Hostname : null, e.Verdict, e.Count);

    public bool Matches(string keyword) =>
        AppNameValue.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || AppPathValue.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || Remote.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || Host.Contains(keyword, StringComparison.OrdinalIgnoreCase);

    /// <summary>從這條記錄預填一條規則：同一應用、同一目標，默認允許。</summary>
    public RulePrefill ToPrefill() => new(
        AppPath: HasApp ? AppPathValue : null,
        Action: Proto.VerdictAction.Allow,
        Domain: Host.Length > 0 ? Host : null,
        Address: Host.Length == 0 ? RemoteEndpoint?.Address : null,
        Port: RemoteEndpoint?.Port,
        Protocol: Protocol,
        Direction: Direction);

    public void Refresh() => OnPropertyChanged(string.Empty);
}

/// <summary>連接日誌頁：打開時查詢歷史（按分鐘聚合），「實時更新」開啟時把新事件插到頂部。</summary>
public sealed partial class LogViewModel : PageViewModel
{
    private const int MaxItems = 1000;

    private readonly ServiceConnection _connection;
    private readonly RuleEditing _editing;
    private readonly Infrastructure.DialogService _dialogs;
    private bool _applyingNavigation;
    private CancellationTokenSource? _watch;

    public LogViewModel(ServiceConnection connection, RuleEditing editing, Infrastructure.DialogService dialogs, DisplayText text)
        : base(text)
    {
        _connection = connection;
        _editing = editing;
        _dialogs = dialogs;
        connection.Connected += (_, clients) => _ = WatchAsync(clients);
    }

    public override string Key => "log";

    private static readonly DataGridLocalSourceDescriptor<LogItemViewModel> RowDescriptor =
        DataGridLocalSourceDescriptor.For<LogItemViewModel>(static row => DataGridRowKey.FromString(row.Key));

    private DataGridLocalSource<LogItemViewModel>? _itemsSource;

    public ObservableCollection<LogItemViewModel> Items { get; } = [];

    /// <summary>表格數據源（AtomUI DataGrid 需要 IDataGridSource，見 RulesViewModel.RulesSource）。</summary>
    public IDataGridSource ItemsSource => _itemsSource ??= DataGridLocalSource.Create(Items, RowDescriptor);

    [ObservableProperty]
    public partial string Keyword { get; set; } = "";

    /// <summary>判決篩選：0 全部，1 允許，2 拒絕。</summary>
    [ObservableProperty]
    public partial int ActionFilter { get; set; }

    [ObservableProperty]
    public partial bool IsLive { get; set; } = true;

    public bool IsEmpty => Items.Count == 0;

    public override Task OnActivatedAsync() => QueryAsync();

    public override bool ApplyNavigation(object parameter)
    {
        if (parameter is not LogNavigation filter)
        {
            return false;
        }

        _applyingNavigation = true;
        Keyword = filter.Keyword;
        ActionFilter = filter.ActionFilter;
        _applyingNavigation = false;
        _ = QueryAsync();
        return true;
    }

    [RelayCommand]
    private Task QueryAsync() => RunAsync(async () =>
    {
        var query = new Proto.LogQuery { Limit = 500 };
        if (!string.IsNullOrWhiteSpace(Keyword))
        {
            query.Keyword = Keyword.Trim();
        }

        if (ActionFilter is 1 or 2)
        {
            query.Action = ActionFilter == 1 ? Proto.VerdictAction.Allow : Proto.VerdictAction.Deny;
        }

        var page = await _connection.RequireClients().Activity.QueryLogAsync(query);
        Items.Clear();
        foreach (var entry in page.Entries)
        {
            Items.Add(LogItemViewModel.From(entry, Text));
        }

        OnPropertyChanged(nameof(IsEmpty));
    });

    [RelayCommand]
    private Task CreateRuleAsync(LogItemViewModel item) => _editing.CreateAsync(item.ToPrefill());

    [RelayCommand]
    private Task ShowDetailsAsync(LogItemViewModel item) =>
        _dialogs.ShowFormAsync<Views.LogDetailView, LogDetailViewModel>(new LogDetailViewModel(item, Text), Loc.Get(LogLangResourceKind.DetailTitle), width: 600, okOnly: true);

    partial void OnActionFilterChanged(int value)
    {
        if (!_applyingNavigation)
        {
            _ = QueryAsync();
        }
    }

    protected override void OnLanguageChanged()
    {
        foreach (var item in Items)
        {
            item.Refresh();
        }

        base.OnLanguageChanged();
    }

    private bool PassesFilter(LogItemViewModel item) =>
        (ActionFilter == 0 || item.Verdict.Action == (ActionFilter == 1 ? Proto.VerdictAction.Allow : Proto.VerdictAction.Deny))
        && (string.IsNullOrWhiteSpace(Keyword) || item.Matches(Keyword.Trim()));

    private async Task WatchAsync(IpcClients clients)
    {
        if (_watch is not null)
        {
            await _watch.CancelAsync();
            _watch.Dispose();
        }

        _watch = new CancellationTokenSource();
        try
        {
            using var call = clients.Activity.WatchConnections(new Google.Protobuf.WellKnownTypes.Empty(), cancellationToken: _watch.Token);
            await foreach (var evt in call.ResponseStream.ReadAllAsync(_watch.Token))
            {
                var item = LogItemViewModel.From(evt, Text);
                if (!IsLive || !PassesFilter(item))
                {
                    continue;
                }

                Items.Insert(0, item);
                if (Items.Count > MaxItems)
                {
                    Items.RemoveAt(Items.Count - 1);
                }

                if (Items.Count == 1)
                {
                    OnPropertyChanged(nameof(IsEmpty));
                }
            }
        }
        catch (Exception ex) when (ex is RpcException or OperationCanceledException or IOException)
        {
            // 連接斷開；重連後會重新訂閱
        }
    }
}
