using System.Collections.ObjectModel;
using System.ComponentModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>總覽：服務狀態、模式切換、一鍵斷網、勿擾，以及模擬引擎下的連接模擬器。</summary>
public sealed partial class OverviewViewModel : PageViewModel
{
    /// <summary>分段選擇器中可直接切換的模式（「全部封鎖」單獨用醒目按鈕）。</summary>
    private static readonly Proto.FilterMode[] SelectableModes =
        [Proto.FilterMode.Ask, Proto.FilterMode.Whitelist, Proto.FilterMode.Blacklist, Proto.FilterMode.Disabled];

    private readonly ServiceConnection _connection;
    private readonly ServiceLifecycle _lifecycle;
    private bool _syncingMode;
    private bool _syncingProfile;

    public OverviewViewModel(ServiceConnection connection, ServiceLifecycle lifecycle, ActivityDashboardViewModel dashboard, DisplayText text)
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
        _lifecycle = lifecycle;
        Dashboard = dashboard;
        connection.PropertyChanged += OnConnectionChanged;
        // 每次重新連接都重新判斷：引擎可能已經從模擬切換為 WFP，模擬器接口只在模擬引擎下存在
        connection.Connected += (_, _) =>
        {
            _ = LoadSimulatorAppsAsync(reload: true);
            _ = LoadProfilesAsync();
        };
    }

    public override string Key => "overview";

    public bool CanModify => _connection.CanModify;

    /// <summary>連接活動統計（時間軸、排行榜）。</summary>
    public ActivityDashboardViewModel Dashboard { get; }

    public ObservableCollection<Proto.SimulatorApp> SimulatorApps { get; } = [];

    public ObservableCollection<string> SimulationResults { get; } = [];

    /// <summary>配置文件列表；只有一個時界面隱藏選擇器。</summary>
    public ObservableCollection<Proto.ProfileInfo> Profiles { get; } = [];

    public bool ShowProfiles => Profiles.Count > 1;

    [ObservableProperty]
    public partial Proto.ProfileInfo? SelectedProfile { get; set; }

    /// <summary>分段選擇器的選中項；-1 表示當前模式不在可選列表中（全部封鎖）。</summary>
    [ObservableProperty]
    public partial int SelectedModeIndex { get; set; } = -1;

    [ObservableProperty]
    public partial bool IsSimulatorAvailable { get; set; }

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(SimulateCommand), nameof(ExitAppCommand))]
    public partial Proto.SimulatorApp? SelectedApp { get; set; }

    [ObservableProperty]
    public partial string SimHost { get; set; } = "api.github.com";

    [ObservableProperty]
    public partial decimal? SimPort { get; set; } = 443;

    /// <summary>0 TCP，1 UDP。</summary>
    [ObservableProperty]
    public partial int SimProtocolIndex { get; set; }

    /// <summary>引擎選擇：0 模擬，1 Windows WFP；-1 表示未連接。</summary>
    [ObservableProperty]
    public partial int SelectedEngineIndex { get; set; } = -1;

    [ObservableProperty]
    public partial bool IsSwitchingEngine { get; set; }

    /// <summary>開發佈局下可以從界面切換引擎（Windows 上才有 WFP 選項）。</summary>
    public bool CanSwitchEngine => _lifecycle.CanSwitchEngine && OperatingSystem.IsWindows();

    public bool ShowInstalledEngineHint => _lifecycle.LaunchKind == ServiceLaunchKind.SystemService;

    public bool ShowEngineCard => CanSwitchEngine || ShowInstalledEngineHint;

    private string CurrentEngine => EngineKinds.FromStatusName(Status?.Engine?.Name);

    private Proto.ServiceStatus? Status => _connection.Status;

    public bool IsConnected => _connection.IsConnected;

    public string ModeText => Status is { } s ? Text.Mode(s.Mode) : "—";

    public string ModeDescription => Status is { } s ? Text.ModeDescription(s.Mode) : "";

    public bool IsBlockAll => Status?.Mode == Proto.FilterMode.BlockAll;

    public string EngineName => Status?.Engine?.Name ?? "—";

    public string EngineBehavior => Status?.Engine is { } e
        ? Loc.Get(e.HoldsConnections ? OverviewLangResourceKind.EngineHoldsConnections : OverviewLangResourceKind.EngineBlocksFirst)
        : "";

    public string? EngineFault => Status?.Engine?.HasFaultMessage == true ? Status.Engine.FaultMessage : null;

    public bool HasEngineFault => EngineFault is not null;

    public int ActiveRuleCount => Status?.ActiveRuleCount ?? 0;

    public int PendingPromptFlows => Status?.PendingPromptFlows ?? 0;

    public bool IsDoNotDisturb => Status?.DoNotDisturbUntil is not null;

    public string DoNotDisturbText => Status?.DoNotDisturbUntil is { } until
        ? Loc.Format(OverviewLangResourceKind.DoNotDisturbUntil, until.ToDateTimeOffset().ToLocalTime().DateTime)
        : "";

    /// <summary>防護模式卡片的說明行：引擎名稱與攔截方式。</summary>
    public string EngineCaption => Status?.Engine is { } e
        ? $"{Loc.Format(OverviewLangResourceKind.EngineCaption, e.Name)} · {EngineBehavior}"
        : "";

    public string PendingPromptsCaption => PendingPromptFlows > 0
        ? Loc.Format(OverviewLangResourceKind.PendingPromptsCaption, PendingPromptFlows)
        : Loc.Get(OverviewLangResourceKind.NoPendingPrompts);

    public override Task OnActivatedAsync()
    {
        Dashboard.Activate();
        _ = LoadProfilesAsync();
        return LoadSimulatorAppsAsync(reload: false);
    }

    public override void OnDeactivated() => Dashboard.Deactivate();

    partial void OnSelectedModeIndexChanged(int value)
    {
        // 分段選擇器在沒有選中項時會自動選中第一項並寫回：未連接時這不是用戶操作，必須忽略
        if (_syncingMode || Status is null || value < 0 || value >= SelectableModes.Length || Status.Mode == SelectableModes[value])
        {
            return;
        }

        var mode = SelectableModes[value];
        _ = RunAsync(async () =>
        {
            var control = _connection.RequireClients().Control;
            await control.SetModeAsync(new Proto.SetModeRequest { Mode = mode });
        });
    }

    partial void OnSelectedEngineIndexChanged(int value)
    {
        if (_syncingMode || value is not (0 or 1) || IsSwitchingEngine)
        {
            return;
        }

        // 未連接時不知道當前引擎，也可能只是控件加載時的自動選中：不切換，連上後按真實狀態同步
        if (!_connection.IsConnected)
        {
            return;
        }

        var engine = value == 1 ? EngineKinds.Wfp : EngineKinds.Fake;
        if (engine == CurrentEngine)
        {
            return;
        }

        _ = SwitchEngineAsync(engine, CurrentEngine);
    }

    /// <summary>把兩個分段選擇器同步為服務的真實狀態（未連接時清空選中）。</summary>
    private void SyncSelectors()
    {
        _syncingMode = true;
        SelectedModeIndex = Status is { } s ? Array.IndexOf(SelectableModes, s.Mode) : -1;
        _syncingMode = false;
        if (!IsSwitchingEngine)
        {
            SyncEngineIndex();
        }
    }

    private async Task SwitchEngineAsync(string engine, string previous)
    {
        IsSwitchingEngine = true;
        ErrorMessage = null;
        try
        {
            if (await _lifecycle.RestartWithEngineAsync(engine, previous, CancellationToken.None) is { } error)
            {
                ErrorMessage = Loc.Format(OverviewLangResourceKind.EngineSwitchFailed, error);
            }
        }
        finally
        {
            IsSwitchingEngine = false;
            SyncEngineIndex();
        }
    }

    private void SyncEngineIndex()
    {
        _syncingMode = true;
        SelectedEngineIndex = Status is null ? -1 : (CurrentEngine == EngineKinds.Wfp ? 1 : 0);
        _syncingMode = false;
    }

    [RelayCommand]
    private Task ToggleBlockAllAsync() => RunAsync(async () =>
    {
        var control = _connection.RequireClients().Control;
        if (IsBlockAll)
        {
            await control.RestoreFromBlockAllAsync(new Empty());
        }
        else
        {
            await control.SetModeAsync(new Proto.SetModeRequest { Mode = Proto.FilterMode.BlockAll });
        }
    });

    [RelayCommand]
    private Task ToggleDoNotDisturbAsync() => RunAsync(async () =>
    {
        var request = new Proto.SetDoNotDisturbRequest();
        if (!IsDoNotDisturb)
        {
            request.Duration = Duration.FromTimeSpan(TimeSpan.FromHours(1));
        }

        await _connection.RequireClients().Control.SetDoNotDisturbAsync(request);
    });

    private bool CanUseSelectedApp() => SelectedApp is not null;

    [RelayCommand(CanExecute = nameof(CanUseSelectedApp))]
    private Task SimulateAsync() => RunAsync(async () =>
    {
        var app = SelectedApp!;
        var protocol = SimProtocolIndex == 1 ? Proto.Protocol.Udp : Proto.Protocol.Tcp;
        var request = new Proto.SimulateRequest
        {
            AppKey = app.Key,
            Port = (uint)Math.Clamp(SimPort ?? 443, 1, 65535),
            Protocol = protocol,
            Direction = Proto.Direction.Outbound,
        };
        var host = SimHost.Trim();
        if (host.Length > 0)
        {
            request.Hostname = host;
        }

        // 調用會阻塞到判決完成（可能需要等用戶回應彈窗），不阻塞界面
        var response = await _connection.RequireClients().Simulator.SimulateAsync(request);
        var target = host.Length > 0 ? $"{host} ({DisplayText.Endpoint(response.Remote)})" : DisplayText.Endpoint(response.Remote);
        SimulationResults.Insert(0, $"{DateTime.Now.ToString("T", Loc.Culture)}  {app.DisplayName} → {target} {DisplayText.Protocol(protocol)}  {Text.Action(response.Action)}");
        while (SimulationResults.Count > 20)
        {
            SimulationResults.RemoveAt(SimulationResults.Count - 1);
        }
    });

    [RelayCommand(CanExecute = nameof(CanUseSelectedApp))]
    private Task ExitAppAsync() => RunAsync(async () =>
        await _connection.RequireClients().Simulator.ExitAppAsync(new Proto.ExitAppRequest { AppKey = SelectedApp!.Key }));

    partial void OnSelectedProfileChanged(Proto.ProfileInfo? value)
    {
        if (_syncingProfile || value is null || !_connection.IsConnected)
        {
            return;
        }

        if (_connection.Status?.ActiveProfileId == value.Id)
        {
            return;
        }

        _ = RunAsync(async () =>
        {
            await _connection.RequireClients().Profiles.ActivateAsync(
                new Proto.ActivateProfileRequest { Id = value.Id });
            await LoadProfilesAsync();
        });
    }

    private async Task LoadProfilesAsync()
    {
        if (_connection.Clients is not { } clients)
        {
            return;
        }

        try
        {
            var response = await clients.Profiles.ListAsync(new Empty());
            Profiles.Clear();
            foreach (var profile in response.Profiles)
            {
                Profiles.Add(profile);
            }

            OnPropertyChanged(nameof(ShowProfiles));
            SyncProfile();
        }
        catch (RpcException)
        {
            // 舊版服務沒有配置文件接口；保持空列表並隱藏選擇器
        }
    }

    private void SyncProfile()
    {
        _syncingProfile = true;
        SelectedProfile = Profiles.FirstOrDefault(p => p.Id == Status?.ActiveProfileId)
            ?? Profiles.FirstOrDefault(p => p.IsActive)
            ?? Profiles.FirstOrDefault();
        _syncingProfile = false;
    }

    private async Task LoadSimulatorAppsAsync(bool reload)
    {
        if (reload)
        {
            SimulatorApps.Clear();
            SelectedApp = null;
            IsSimulatorAvailable = false;
        }

        // 只有模擬引擎提供模擬器；其他引擎下調用會得到 Unimplemented
        if (_connection.Clients is not { } clients || SimulatorApps.Count > 0 || CurrentEngine != EngineKinds.Fake)
        {
            return;
        }

        try
        {
            var apps = await clients.Simulator.ListAppsAsync(new Empty());
            foreach (var app in apps.Apps)
            {
                SimulatorApps.Add(app);
            }

            SelectedApp = SimulatorApps.FirstOrDefault();
            IsSimulatorAvailable = SimulatorApps.Count > 0;
        }
        catch (RpcException)
        {
            // 非模擬引擎沒有模擬器服務
            IsSimulatorAvailable = false;
        }
    }

    private void OnConnectionChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is not (nameof(ServiceConnection.Status) or nameof(ServiceConnection.State)))
        {
            return;
        }

        SyncSelectors();
        SyncProfile();
        OnPropertyChanged(string.Empty);
    }
}
