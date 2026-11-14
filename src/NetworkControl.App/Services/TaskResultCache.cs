using System.Collections.Concurrent;

namespace NetworkControl.App.Services;

/// <summary>
/// 以鍵緩存非同步提取結果：成功結果保留，失敗（null 或異常）立即移除，下一次調用會重試。
/// 抽出來是為了讓圖標提取的緩存策略可以脫離 Avalonia 位圖單獨測試。
/// </summary>
internal sealed class TaskResultCache<TValue>(int maxEntries)
    where TValue : class
{
    private readonly ConcurrentDictionary<string, Task<TValue?>> _entries = new(StringComparer.OrdinalIgnoreCase);

    public async Task<TValue?> GetAsync(string key, Func<string, TValue?> factory)
    {
        if (_entries.Count >= maxEntries)
        {
            _entries.Clear();
        }

        var task = _entries.GetOrAdd(key, k => Task.Run(() => factory(k)));
        TValue? value;
        try
        {
            value = await task.ConfigureAwait(false);
        }
        catch
        {
            Remove(key, task);
            throw;
        }

        if (value is null)
        {
            Remove(key, task);
        }

        return value;
    }

    private void Remove(string key, Task<TValue?> task) =>
        _entries.TryRemove(new KeyValuePair<string, Task<TValue?>>(key, task));
}
