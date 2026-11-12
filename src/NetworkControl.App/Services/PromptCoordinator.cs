using Avalonia.Threading;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using NetworkControl.App.Localization;
using NetworkControl.App.ViewModels;
using NetworkControl.App.Views;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

/// <summary>
/// 彈窗協調器：訂閱服務端的彈窗事件流，按隊列逐個顯示彈窗（FR-PRM-09）。
/// 同一時刻只顯示一個彈窗；用戶答覆後由服務端推送 Removed / Updated 事件驅動下一個。
/// </summary>
public sealed class PromptCoordinator
{
    private readonly ServiceConnection _connection;
    private readonly DisplayText _text;
    private readonly List<Proto.Prompt> _queue = [];
    private PromptWindow? _window;
    private PromptViewModel? _current;
    private CancellationTokenSource? _subscription;
    private Proto.VerdictAction _defaultAction = Proto.VerdictAction.Deny;

    public PromptCoordinator(ServiceConnection connection, DisplayText text)
    {
        _connection = connection;
        _text = text;
        connection.Connected += (_, clients) => _ = SubscribeAsync(clients);
    }

    public int PendingCount => _queue.Count;

    private async Task SubscribeAsync(IpcClients clients)
    {
        if (_subscription is not null)
        {
            await _subscription.CancelAsync();
            _subscription.Dispose();
        }

        _subscription = new CancellationTokenSource();
        var ct = _subscription.Token;
        ClearAll();

        try
        {
            _defaultAction = (await clients.Control.GetSettingsAsync(new Empty(), cancellationToken: ct)).PromptDefaultAction;

            using var call = clients.Prompts.Subscribe(new Empty(), cancellationToken: ct);
            await foreach (var evt in call.ResponseStream.ReadAllAsync(ct))
            {
                Handle(evt);
            }
        }
        catch (Exception ex) when (ex is RpcException or OperationCanceledException or IOException)
        {
            // 連接斷開：服務端會按默認動作處理待決連接，本地彈窗全部關閉
        }
        finally
        {
            if (!ct.IsCancellationRequested)
            {
                ClearAll();
            }
        }
    }

    private void Handle(Proto.PromptEvent evt)
    {
        var index = _queue.FindIndex(p => p.Id == evt.Prompt.Id);
        switch (evt.Kind)
        {
            case Proto.PromptEventKind.Added when index < 0:
                _queue.Add(evt.Prompt);
                break;
            case Proto.PromptEventKind.Added or Proto.PromptEventKind.Updated when index >= 0:
                _queue[index] = evt.Prompt;
                if (_current?.PromptId == evt.Prompt.Id)
                {
                    _current.Update(evt.Prompt);
                }

                break;
            case Proto.PromptEventKind.Removed when index >= 0:
                _queue.RemoveAt(index);
                if (_current?.PromptId == evt.Prompt.Id)
                {
                    CloseCurrent();
                }

                break;
        }

        ShowNextIfIdle();
        if (_current is not null)
        {
            _current.QueueLength = Math.Max(0, _queue.Count - 1);
        }
    }

    private void ShowNextIfIdle()
    {
        if (_current is not null || _queue.Count == 0)
        {
            return;
        }

        var prompt = _queue[0];
        _current = new PromptViewModel(prompt, _defaultAction, _queue.Count - 1, _text, OnAnswered, OnPauseRequested);
        _current.CloseRequested += (_, _) => CloseCurrent();
        _window = new PromptWindow { DataContext = _current };
        _window.Show();
    }

    private void OnAnswered(PromptAnswer answer)
    {
        // 先從本地隊列移除，立即顯示下一個；服務端的 Removed 事件到達時會被忽略
        _queue.RemoveAll(p => p.Id == answer.PromptId);
        _ = SendAsync(async clients => await clients.Prompts.AnswerAsync(answer.Request));
        Dispatcher.UIThread.Post(ShowNextIfIdle);
    }

    private void OnPauseRequested(string promptId) =>
        _ = SendAsync(async clients => await clients.Prompts.PauseTimerAsync(new Proto.PromptRef { PromptId = promptId }));

    private async Task SendAsync(Func<IpcClients, Task> action)
    {
        if (_connection.Clients is not { } clients)
        {
            return;
        }

        try
        {
            await action(clients);
        }
        catch (RpcException)
        {
            // 彈窗可能已在服務端超時；服務端以自己的狀態為準
        }
    }

    private void CloseCurrent()
    {
        var window = _window;
        _current?.Dispose();
        _current = null;
        _window = null;
        window?.Close();
    }

    private void ClearAll()
    {
        _queue.Clear();
        CloseCurrent();
    }
}
