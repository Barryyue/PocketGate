using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Net;
using System.Net.Sockets;
using AtomUI.Desktop.Controls;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

public sealed partial class ConnectionItemViewModel : IconRowViewModel
{
    private readonly DisplayText _text;
    private readonly AppIconCache _icons;
    private Proto.LiveConnection _connection;
    private bool _killSupported;

    public ConnectionItemViewModel(Proto.LiveConnection connection, DisplayText text, bool killSupported, AppIconCache icons)
        : base(icons)
    {
        _connection = connection;
        _text = text;
        _killSupported = killSupported;
        _icons = icons;
    }

    public Task LoadIconAsync() => LoadIconAsync(AppPath);

    public Proto.LiveConnection Connection
    {
        get => _connection;
        set
        {
            if (ReferenceEquals(_connection, value))
            {
                return;
            }

            _connection = value;
            OnPropertyChanged(nameof(Connection));
            OnPropertyChanged(nameof(Key));
            OnPropertyChanged(nameof(AppName));
            OnPropertyChanged(nameof(AppPath));
            OnPropertyChanged(nameof(ProtocolText));
            OnPropertyChanged(nameof(Local));
            OnPropertyChanged(nameof(Remote));
            OnPropertyChanged(nameof(RemoteDisplay));
            OnPropertyChanged(nameof(RemoteTooltip));
            OnPropertyChanged(nameof(StateText));
            OnPropertyChanged(nameof(CanKill));
        }
    }

    public string Key => KeyOf(Connection);

    /// <summary>連線的合併鍵；定時刷新時用它復用界面上的行對象。</summary>
    internal static string KeyOf(Proto.LiveConnection connection) =>
        $"{connection.Protocol}|{DisplayText.Endpoint(connection.Local)}|{RemoteKey(connection.Remote)}|{connection.Pid}";

    private static string RemoteKey(Proto.IpEndpoint? remote) =>
        remote is { Address.Length: > 0 } ? DisplayText.Endpoint(remote) : "—";

    public string AppName => Connection.AppName.Length > 0 ? Connection.AppName : _text.Localization.Get(CommonLangResourceKind.UnknownApp);

    public string AppPath => Connection.AppPath;

    public string ProtocolText => DisplayText.Protocol(Connection.Protocol);

    public string Local => DisplayText.Endpoint(Connection.Local);

    public string Remote => Connection.Remote is { Address.Length: > 0 } ? DisplayText.Endpoint(Connection.Remote) : "—";

    public string RemoteDisplay => Connection.Hostname.Length > 0 ? Connection.Hostname : Remote;

    public string RemoteTooltip => Connection.Hostname.Length > 0 ? $"{Connection.Hostname} ({Remote})" : Remote;

    public string StateText => _text.Localization.Get(Connection.State switch
    {
        Proto.ConnectionStateKind.Listen => ConnectionsLangResourceKind.StateListen,
        Proto.ConnectionStateKind.Established => ConnectionsLangResourceKind.StateEstablished,
        Proto.ConnectionStateKind.SynSent => ConnectionsLangResourceKind.StateSynSent,
        Proto.ConnectionStateKind.SynReceived => ConnectionsLangResourceKind.StateSynReceived,
        Proto.ConnectionStateKind.Closing => ConnectionsLangResourceKind.StateClosing,
        Proto.ConnectionStateKind.TimeWait => ConnectionsLangResourceKind.StateTimeWait,
        Proto.ConnectionStateKind.Udp => ConnectionsLangResourceKind.StateUdp,
        _ => ConnectionsLangResourceKind.StateClosed,
    });

    public bool CanKill => _killSupported
        && Connection.Protocol == Proto.Protocol.Tcp
        && Connection.State == Proto.ConnectionStateKind.Established
        && Connection.Remote is { Address.Length: > 0 } remote
        && IPAddress.TryParse(remote.Address, out var address)
        && address.AddressFamily == AddressFamily.InterNetwork;

    public bool Matches(string keyword) => keyword.Length == 0
        || AppName.Contains(keyword, StringComparison.CurrentCultureIgnoreCase)
        || AppPath.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || Local.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || Remote.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || Connection.Hostname.Contains(keyword, StringComparison.OrdinalIgnoreCase);

    public void SetKillSupported(bool supported)
    {
        if (_killSupported == supported)
        {
            return;
        }

        _killSupported = supported;
        OnPropertyChanged(nameof(CanKill));
    }

    /// <summary>更新連線數據和「能否斷開」；語言文本的 getter 會讀取新的 Connection。</summary>
    public void Update(Proto.LiveConnection connection, bool killSupported)
    {
        Connection = connection;
        SetKillSupported(killSupported);
    }

    public void RefreshText()
    {
        OnPropertyChanged(nameof(AppName));
        OnPropertyChanged(nameof(ProtocolText));
        OnPropertyChanged(nameof(StateText));
        OnPropertyChanged(nameof(RemoteDisplay));
        OnPropertyChanged(nameof(RemoteTooltip));
    }
}

public sealed partial class ConnectionsViewModel : PageViewModel
{
    private static readonly DataGridLocalSourceDescriptor<ConnectionItemViewModel> RowDescriptor =
        DataGridLocalSourceDescriptor.For<ConnectionItemViewModel>(static row => DataGridRowKey.FromString(row.Key));

    private readonly ServiceConnection _connection;
    private readonly RuleEditing _editing;
    private readonly AppIconCache _icons;
    private readonly NavigationService _navigation;
    private readonly FeedbackService _feedback;
    private readonly DispatcherTimer _timer;
    private readonly List<ConnectionItemViewModel> _all = [];
    private DataGridLocalSource<ConnectionItemViewModel>? _source;
    private bool _killSupported;

    public ConnectionsViewModel(ServiceConnection connection, RuleEditing editing, NavigationService navigation, FeedbackService feedback, AppIconCache icons, DisplayText text) : base(text)
    {
        _connection = connection;
        _editing = editing;
        _navigation = navigation;
        _feedback = feedback;
        _icons = icons;
        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        _timer.Tick += (_, _) => _ = RefreshAsync();
        _connection.PropertyChanged += OnConnectionPropertyChanged;
        UpdateKillSupported();
    }

    public override string Key => "connections";

    public ObservableCollection<ConnectionItemViewModel> Items { get; } = [];

    public IDataGridSource ItemsSource => _source ??= DataGridLocalSource.Create(Items, RowDescriptor);

    [ObservableProperty]
    public partial string Keyword { get; set; } = "";

    [ObservableProperty]
    public partial bool IncludeListening { get; set; }

    [ObservableProperty]
    public partial bool IncludeLoopback { get; set; }

    [ObservableProperty]
    public partial bool IsLoading { get; set; }

    [ObservableProperty]
    public partial bool IsSupported { get; set; }

    public bool IsEmpty => IsSupported && !IsLoading && Items.Count == 0;

    public bool ShowUnsupported => !IsLoading && !IsSupported;

    public override Task OnActivatedAsync()
    {
        _timer.Start();
        return RefreshAsync();
    }

    public override void OnDeactivated() => _timer.Stop();

    [RelayCommand]
    private Task RefreshAsync() => RunAsync(async () =>
    {
        IsLoading = true;
        try
        {
            var response = await _connection.RequireClients().Connections.ListAsync(new Proto.ListConnectionsRequest
            {
                IncludeListening = IncludeListening,
                IncludeLoopback = IncludeLoopback,
            });
            IsSupported = response.Supported;
            var created = new List<ConnectionItemViewModel>();
            var items = ConnectionRowMerger.Merge(
                response.Connections,
                _all,
                static c => ConnectionItemViewModel.KeyOf(c),
                static item => item.Key,
                c =>
                {
                    var item = new ConnectionItemViewModel(c, Text, _killSupported, _icons);
                    created.Add(item);
                    return item;
                },
                (item, c) => item.Update(c, _killSupported));
            _all.Clear();
            _all.AddRange(items);

            // 只有新插入的行才需要提取圖標；已有行沿用同一個對象，避免每 2 秒重複請求
            foreach (var item in created)
            {
                _ = item.LoadIconAsync();
            }
        }
        finally
        {
            IsLoading = false;
            ApplyFilter();
        }
    });

    [RelayCommand]
    private void ViewLog(ConnectionItemViewModel item) =>
        _navigation.Navigate("log", new LogNavigation(
            item.Connection.Hostname.Length > 0
                ? item.Connection.Hostname
                : item.AppPath.Length > 0 ? item.AppPath : item.Remote));

    [RelayCommand]
    private Task CreateRuleAsync(ConnectionItemViewModel item) =>
        _editing.CreateAsync(new RulePrefill(
            AppPath: item.AppPath.Length > 0 ? item.AppPath : null,
            Address: item.Connection.Remote is { Address.Length: > 0 } ? item.Connection.Remote.Address : null,
            Port: item.Connection.Remote is { Port: > 0 } ? item.Connection.Remote.Port : null,
            Protocol: item.Connection.Protocol,
            Direction: null));

    [RelayCommand]
    private Task KillAsync(ConnectionItemViewModel item) => RunAsync(async () =>
    {
        if (!item.CanKill || item.Connection.Remote is not { } remote)
        {
            return;
        }

        var response = await _connection.RequireClients().Connections.KillAsync(new Proto.KillConnectionRequest
        {
            Protocol = item.Connection.Protocol,
            Local = item.Connection.Local,
            Remote = remote,
        });
        if (response.Killed)
        {
            _feedback.Success(response.Message);
            await RefreshAsync();
        }
        else
        {
            _feedback.Error(response.Message);
        }
    });

    [RelayCommand]
    private async Task KillAndDenyAsync(ConnectionItemViewModel item)
    {
        if (!item.CanKill)
        {
            return;
        }

        var created = await _editing.CreateAsync(new RulePrefill(
            AppPath: item.AppPath.Length > 0 ? item.AppPath : null,
            Action: Proto.VerdictAction.Deny,
            Address: item.Connection.Remote is { Address.Length: > 0 } ? item.Connection.Remote.Address : null,
            Port: item.Connection.Remote is { Port: > 0 } ? item.Connection.Remote.Port : null,
            Protocol: item.Connection.Protocol,
            Direction: null));
        if (created)
        {
            await KillAsync(item);
        }
    }

    partial void OnKeywordChanged(string value) => ApplyFilter();

    partial void OnIncludeListeningChanged(bool value) => _ = RefreshAsync();

    partial void OnIncludeLoopbackChanged(bool value) => _ = RefreshAsync();

    partial void OnIsLoadingChanged(bool value)
    {
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(ShowUnsupported));
    }

    partial void OnIsSupportedChanged(bool value)
    {
        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(ShowUnsupported));
    }

    protected override void OnLanguageChanged()
    {
        foreach (var item in _all)
        {
            item.RefreshText();
        }

        ApplyFilter();
        base.OnLanguageChanged();
    }

    private void OnConnectionPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(ServiceConnection.Status))
        {
            UpdateKillSupported();
        }
    }

    private void UpdateKillSupported()
    {
        var supported = _connection.Status?.Engine?.Capabilities.Contains("KillConnection") == true;
        if (_killSupported == supported)
        {
            return;
        }

        _killSupported = supported;
        foreach (var item in _all)
        {
            item.SetKillSupported(supported);
        }
    }

    private void ApplyFilter()
    {
        var keyword = Keyword.Trim();
        var desired = _all.Where(c => c.Matches(keyword)).ToList();
        var existing = new Dictionary<string, ConnectionItemViewModel>(StringComparer.Ordinal);
        foreach (var item in Items)
        {
            existing[item.Key] = item;
        }

        var result = new List<ConnectionItemViewModel>(desired.Count);
        foreach (var candidate in desired)
        {
            if (existing.TryGetValue(candidate.Key, out var current))
            {
                current.Update(candidate.Connection, _killSupported);
                result.Add(current);
            }
            else
            {
                result.Add(candidate);
            }
        }

        for (var index = 0; index < result.Count; index++)
        {
            if (index < Items.Count && Items[index].Key == result[index].Key)
            {
                continue;
            }

            var existingIndex = IndexOfKey(result[index].Key, index + 1);
            if (existingIndex >= 0)
            {
                Items.Move(existingIndex, index);
            }
            else
            {
                Items.Insert(index, result[index]);
            }
        }

        while (Items.Count > result.Count)
        {
            Items.RemoveAt(Items.Count - 1);
        }

        OnPropertyChanged(nameof(IsEmpty));
    }

    private int IndexOfKey(string key, int start)
    {
        for (var index = start; index < Items.Count; index++)
        {
            if (Items[index].Key == key)
            {
                return index;
            }
        }

        return -1;
    }
}
