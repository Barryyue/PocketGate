using Grpc.Core;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using NetworkControl.App.ViewModels;
using NetworkControl.App.Views;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

/// <summary>首次打開主窗口時顯示一次引導（NFR-UX-01）。完成或關閉後記錄在界面偏好中，不再顯示。</summary>
public sealed class OnboardingFlow(
    PreferencesStore preferences,
    ServiceConnection connection,
    DialogService dialogs,
    AutoStartService autoStart,
    DisplayText text)
{
    /// <summary>引導內容有重大變化時遞增，讓老用戶再看一次。</summary>
    public const int CurrentVersion = 1;

    private static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(8);

    private bool _running;

    public bool IsNeeded => preferences.Current.OnboardingVersion < CurrentVersion;

    public async Task RunIfNeededAsync()
    {
        if (!IsNeeded || _running)
        {
            return;
        }

        _running = true;
        try
        {
            // 等待服務連接（首次啟動時開發服務 / 可攜版服務可能還在啟動），以便選擇初始模式
            await WaitForConnectionAsync();
            var viewModel = new OnboardingViewModel(text, connection.IsConnected, autoStart.IsSupported, autoStart.IsEnabled);
            var accepted = await dialogs.ShowFormAsync<OnboardingView, OnboardingViewModel>(
                viewModel,
                text.Localization.Get(OnboardingLangResourceKind.Title),
                width: 640);

            // 無論確認還是關閉都不再顯示
            preferences.Update(p => p with { OnboardingVersion = CurrentVersion });
            if (!accepted)
            {
                return;
            }

            if (autoStart.IsSupported && viewModel.LaunchAtLogin != autoStart.IsEnabled)
            {
                autoStart.SetEnabled(viewModel.LaunchAtLogin);
            }

            if (viewModel.IsConnected && connection.Clients is { } clients && connection.Status?.Mode != viewModel.SelectedMode)
            {
                await clients.Control.SetModeAsync(new Proto.SetModeRequest { Mode = viewModel.SelectedMode });
            }
        }
        catch (RpcException ex)
        {
            AppLog.Warn($"Onboarding could not apply the initial mode: {ex.Status.Detail}");
        }
        finally
        {
            _running = false;
        }
    }

    private async Task WaitForConnectionAsync()
    {
        if (connection.IsConnected)
        {
            return;
        }

        var connected = new TaskCompletionSource();
        void OnConnected(object? sender, IpcClients clients) => connected.TrySetResult();
        connection.Connected += OnConnected;
        try
        {
            await Task.WhenAny(connected.Task, Task.Delay(ConnectTimeout));
        }
        finally
        {
            connection.Connected -= OnConnected;
        }
    }
}
