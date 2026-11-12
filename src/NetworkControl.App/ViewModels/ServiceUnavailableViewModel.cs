using System.ComponentModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;
using NetworkControl.Ipc;

namespace NetworkControl.App.ViewModels;

/// <summary>
/// 服務不可用時的診斷頁：說明具體原因（未運行 / 無權訪問 / 協議版本不一致），並給出可以直接執行的操作。
/// </summary>
public sealed partial class ServiceUnavailableViewModel : LocalizedViewModel
{
    private readonly ServiceConnection _connection;
    private readonly IServiceLauncher _launcher;

    public ServiceUnavailableViewModel(ServiceConnection connection, IServiceLauncher launcher, DisplayText text)
        : base(text)
    {
        _connection = connection;
        _launcher = launcher;
        connection.PropertyChanged += OnConnectionChanged;
    }

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(StartCommand))]
    public partial bool IsStarting { get; set; }

    [ObservableProperty]
    public partial string? StartError { get; set; }

    public string Reason
    {
        get
        {
            if (_connection.LastFailure is not { } failure)
            {
                return Loc.Get(ShellLangResourceKind.StatusConnecting);
            }

            var endpoint = failure.Endpoint.ToString();
            return failure.Kind switch
            {
                ConnectionFailureKind.NotRunning => Loc.Format(ServiceStatusLangResourceKind.ReasonNotRunning, endpoint),
                ConnectionFailureKind.AccessDenied => Loc.Format(ServiceStatusLangResourceKind.ReasonAccessDenied, endpoint),
                ConnectionFailureKind.ProtocolMismatch => Loc.Format(ServiceStatusLangResourceKind.ReasonProtocolMismatch, failure.ServiceProtocol, IpcEndpoint.ProtocolVersion),
                _ => Loc.Format(ServiceStatusLangResourceKind.ReasonOther, failure.Detail ?? ""),
            };
        }
    }

    public bool CanStartService => _launcher.Kind is ServiceLaunchKind.Development or ServiceLaunchKind.SystemService;

    public string StartButtonText => Loc.Get(_launcher.Kind == ServiceLaunchKind.Development
        ? ServiceStatusLangResourceKind.StartDevService
        : ServiceStatusLangResourceKind.StartService);

    public string Hint => Loc.Get(_launcher.Kind switch
    {
        ServiceLaunchKind.Development => ServiceStatusLangResourceKind.HintDev,
        ServiceLaunchKind.SystemService => ServiceStatusLangResourceKind.HintInstalled,
        _ => ServiceStatusLangResourceKind.HintNotInstalled,
    });

    private bool CanStart() => CanStartService && !IsStarting;

    [RelayCommand(CanExecute = nameof(CanStart))]
    private async Task StartAsync()
    {
        IsStarting = true;
        StartError = null;
        try
        {
            if (await _launcher.StartAsync(null, CancellationToken.None) is { } error)
            {
                StartError = Loc.Format(ServiceStatusLangResourceKind.StartFailed, error);
                return;
            }

            // 給服務一點時間創建 IPC 端點，然後立即重連
            await Task.Delay(TimeSpan.FromSeconds(1.5));
            _connection.RetryNow();
        }
        finally
        {
            IsStarting = false;
        }
    }

    [RelayCommand]
    private void Retry() => _connection.RetryNow();

    private void OnConnectionChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(ServiceConnection.LastFailure))
        {
            OnPropertyChanged(nameof(Reason));
        }
    }
}
