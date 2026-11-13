using AtomUI.Desktop.Controls;
using Avalonia.Controls;

namespace NetworkControl.App.Infrastructure;

/// <summary>
/// 記錄當前的主窗口。界面是托盤應用，主窗口可能被關閉後重建，
/// 對話框、消息提示等需要宿主窗口的功能都通過它取得當前窗口。
/// </summary>
public sealed class WindowHost
{
    public TopLevel? Current { get; private set; }

    public event EventHandler? Changed;

    public void Attach(TopLevel topLevel)
    {
        Current = topLevel;
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void Detach(TopLevel topLevel)
    {
        if (ReferenceEquals(Current, topLevel))
        {
            Current = null;
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }
}

/// <summary>
/// 頁面頂部的全局消息提示（Ant Design Message）。主窗口未打開時靜默忽略：
/// 操作結果已經體現在數據上，提示只是錦上添花。
/// </summary>
public sealed class FeedbackService
{
    private readonly WindowHost _host;
    private WindowMessageManager? _manager;
    private TopLevel? _managerHost;

    public FeedbackService(WindowHost host)
    {
        _host = host;
        host.Changed += (_, _) => Reset();
    }

    public void Success(string text) => Show(text, MessageType.Success);

    public void Error(string text) => Show(text, MessageType.Error);

    public void Info(string text) => Show(text, MessageType.Information);

    private void Show(string text, MessageType type)
    {
        var host = _host.Current;
        if (host is null)
        {
            return;
        }

        if (_manager is null || !ReferenceEquals(_managerHost, host))
        {
            Reset();
            _manager = new WindowMessageManager(host);
            _managerHost = host;
        }

        _manager.Show(new Message(text, type, expiration: TimeSpan.FromSeconds(3)));
    }

    private void Reset()
    {
        _manager?.Dispose();
        _manager = null;
        _managerHost = null;
    }
}

/// <summary>可以在對話框「確定」時做校驗的內容。校驗失敗時對話框保持打開。</summary>
public interface IValidatingDialogContent
{
    bool Validate();
}

/// <summary>模態表單對話框（Ant Design Modal）。</summary>
public sealed class DialogService(WindowHost host)
{
    /// <summary>顯示表單；用戶點「確定」且校驗通過時返回 true。<paramref name="okOnly"/> 為 true 時只有「確定」按鈕（純信息對話框）。</summary>
    public async Task<bool> ShowFormAsync<TView, TViewModel>(TViewModel viewModel, string title, double width = 560, bool okOnly = false)
        where TView : Control, new()
        where TViewModel : class, IValidatingDialogContent
    {
        if (host.Current is not { } owner)
        {
            return false;
        }

        var accepted = false;
        var options = new DialogOptions
        {
            Title = title,
            HostWidth = width,
            IsResizable = false,
            IsMaximizable = false,
            IsMinimizable = false,
            IsMaskClosable = false,
            StandardButtons = okOnly ? DialogStandardButton.Ok : DialogStandardButton.Ok | DialogStandardButton.Cancel,
            DefaultStandardButton = DialogStandardButton.Ok,
            BeforeCloseAsync = context =>
            {
                if (context.DialogCode == DialogCode.Accepted)
                {
                    if (!viewModel.Validate())
                    {
                        return ValueTask.FromResult(false);
                    }

                    accepted = true;
                }

                return ValueTask.FromResult(true);
            },
        };

        await Dialog.ShowDialogModalAsync<TView, TViewModel>(viewModel, options, owner);
        return accepted;
    }
}
