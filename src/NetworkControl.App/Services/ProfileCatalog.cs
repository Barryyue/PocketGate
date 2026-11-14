using System.ComponentModel;
using Avalonia.Threading;
using Grpc.Core;
using NetworkControl.App.Infrastructure;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

/// <summary>
/// 桌面端配置文件列表的單一來源。規則頁、設定頁、總覽和托盤共用，避免各自呼叫
/// <c>Profiles.List</c>、各自處理錯誤。
/// 舊版服務沒有 Profiles 接口時（Unimplemented）只把 <see cref="IsAvailable"/> 設為 false，
/// 不讓規則頁 / 設定頁因此載入失敗；短暫錯誤保留上一次的列表。
/// </summary>
public sealed class ProfileCatalog
{
    private readonly ServiceConnection _connection;
    private readonly SemaphoreSlim _refreshGate = new(1, 1);
    private Proto.ProfileInfo[] _profiles = [];
    private long _revision = -1;
    private bool _unavailableLogged;
    private bool _changeQueued;

    public ProfileCatalog(ServiceConnection connection)
    {
        _connection = connection;
        connection.Connected += (_, _) => _ = RefreshAsync();
        connection.PropertyChanged += OnConnectionChanged;
        if (connection.IsConnected)
        {
            _ = RefreshAsync();
        }
    }

    /// <summary>列表或可用性變化；總是在界面線程觸發。</summary>
    public event EventHandler? Changed;

    public IReadOnlyList<Proto.ProfileInfo> Profiles => Volatile.Read(ref _profiles);

    /// <summary>服務支持配置文件接口。舊版服務（Unimplemented）為 false。</summary>
    public bool IsAvailable { get; private set; } = true;

    /// <summary>上一次重新整理是否失敗（短暫錯誤）。界面據此決定是否還敢用快取的列表。</summary>
    public bool LastLoadFailed { get; private set; }

    public bool HasMultiple => Profiles.Count > 1;

    /// <summary>重新載入列表；不會往外拋異常。</summary>
    public async Task RefreshAsync()
    {
        // 串行化：狀態推送、連線建立和本地修改可能同時要求刷新
        await _refreshGate.WaitAsync();
        try
        {
            if (_connection.Clients is not { } clients)
            {
                return;
            }

            var response = await clients.Profiles.ListAsync(new Google.Protobuf.WellKnownTypes.Empty());
            var next = response.Profiles.ToArray();
            LastLoadFailed = false;
            _revision = _connection.Status?.ProfilesRevision ?? _revision;
            if (_profiles.SequenceEqual(next))
            {
                if (!IsAvailable)
                {
                    IsAvailable = true;
                    QueueChanged();
                }

                return;
            }

            _profiles = next;
            IsAvailable = true;
            QueueChanged();
        }
        catch (RpcException ex) when (ex.StatusCode == StatusCode.Unimplemented)
        {
            LastLoadFailed = false;
            _profiles = [];
            IsAvailable = false;
            if (!_unavailableLogged)
            {
                _unavailableLogged = true;
                AppLog.Info("The service does not provide profile management; profile UI is hidden.");
            }

            QueueChanged();
        }
        catch (RpcException ex)
        {
            // 短暫斷線或暫時錯誤：保留上一次的列表，不讓調用方失敗
            LastLoadFailed = true;
            AppLog.Warn($"Could not load profiles: {ex.Status.Detail}");
        }
        catch (InvalidOperationException)
        {
            // 服務尚未連上；Connected 時會再刷新
            LastLoadFailed = true;
        }
        finally
        {
            _refreshGate.Release();
        }
    }

    private void OnConnectionChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName != nameof(ServiceConnection.Status))
        {
            return;
        }

        var revision = _connection.Status?.ProfilesRevision;
        if (revision is { } value && value != _revision)
        {
            _revision = value;
            _ = RefreshAsync();
        }
    }

    /// <summary>狀態推送和 RPC 回覆都在後台線程；合併成一次界面線程通知。</summary>
    private void QueueChanged()
    {
        if (_changeQueued)
        {
            return;
        }

        _changeQueued = true;
        Dispatcher.UIThread.Post(
            () =>
            {
                _changeQueued = false;
                Changed?.Invoke(this, EventArgs.Empty);
            },
            DispatcherPriority.Background);
    }
}
