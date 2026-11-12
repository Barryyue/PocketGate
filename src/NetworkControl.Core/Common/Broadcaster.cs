using System.Runtime.CompilerServices;
using System.Threading.Channels;

namespace NetworkControl.Core.Common;

/// <summary>
/// 一對多的事件廣播。每個訂閱者有自己的有界隊列；消費過慢時丟棄最舊的事件，絕不阻塞發布者。
/// 用於把內部事件推送給 IPC 流式訂閱（狀態、規則變更、實時日誌等）。
/// </summary>
public sealed class Broadcaster<T>(int capacityPerSubscriber = 1024)
{
    private readonly Lock _gate = new();
    private Channel<T>[] _subscribers = [];

    public int SubscriberCount => Volatile.Read(ref _subscribers).Length;

    public void Publish(T item)
    {
        foreach (var subscriber in Volatile.Read(ref _subscribers))
        {
            subscriber.Writer.TryWrite(item);
        }
    }

    public async IAsyncEnumerable<T> SubscribeAsync([EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var channel = Channel.CreateBounded<T>(new BoundedChannelOptions(capacityPerSubscriber)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
        });

        lock (_gate)
        {
            _subscribers = [.. _subscribers, channel];
        }

        try
        {
            await foreach (var item in channel.Reader.ReadAllAsync(cancellationToken))
            {
                yield return item;
            }
        }
        finally
        {
            lock (_gate)
            {
                _subscribers = _subscribers.Where(c => c != channel).ToArray();
            }
        }
    }
}
