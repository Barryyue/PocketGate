using System.ComponentModel;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using NetworkControl.App.Localization;
using NetworkControl.App.ViewModels;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

/// <summary>勿擾結束後彈出彙總，並在用戶查看後清除服務端的摘要。</summary>
public sealed class QuietPeriodNotifier
{
    private readonly ServiceConnection _connection;
    private readonly ToastService _toasts;
    private readonly ShellActivator _shell;
    private readonly NavigationService _navigation;
    private readonly DisplayText _text;
    private string? _lastId;

    public QuietPeriodNotifier(
        ServiceConnection connection,
        ToastService toasts,
        ShellActivator shell,
        NavigationService navigation,
        DisplayText text)
    {
        _connection = connection;
        _toasts = toasts;
        _shell = shell;
        _navigation = navigation;
        _text = text;
        connection.PropertyChanged += OnConnectionChanged;
    }

    private void OnConnectionChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName != nameof(ServiceConnection.Status))
        {
            return;
        }

        if (_connection.Status?.LastQuietPeriod is not { } summary
            || summary.Id.Length == 0
            || summary.Id == _lastId)
        {
            return;
        }

        _lastId = summary.Id;
        var loc = _text.Localization;
        _toasts.Show(new ToastViewModel(
            loc.Get(NotificationsLangResourceKind.QuietTitle),
            loc.Format(
                NotificationsLangResourceKind.QuietBody,
                summary.Apps.Count,
                summary.Apps.Sum(a => (long)a.Denied)),
            loc.Get(NotificationsLangResourceKind.QuietView),
            async () =>
            {
                _shell.Show();
                _navigation.Navigate("log", new LogNavigation("", Reason: Proto.VerdictReason.DoNotDisturb));
                try
                {
                    await _connection.RequireClients().Control.DismissQuietPeriodAsync(new Empty());
                }
                catch (RpcException)
                {
                    // 下一次狀態推送會重新提供摘要
                }
            }));
    }
}
