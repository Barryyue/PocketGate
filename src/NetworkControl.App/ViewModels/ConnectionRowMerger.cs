namespace NetworkControl.App.ViewModels;

/// <summary>
/// 合併服務端返回的連線與界面上的行對象：同一鍵沿用舊行並更新狀態，新連線才建立新行。
/// 抽成泛型靜態方法，避免定時刷新時丟失行狀態和重複加載圖標。
/// </summary>
internal static class ConnectionRowMerger
{
    public static List<TRow> Merge<TConnection, TRow>(
        IReadOnlyList<TConnection> incoming,
        IReadOnlyList<TRow> existing,
        Func<TConnection, string> connectionKey,
        Func<TRow, string> rowKey,
        Func<TConnection, TRow> create,
        Action<TRow, TConnection> update)
    {
        var rows = new Dictionary<string, TRow>(existing.Count, StringComparer.Ordinal);
        foreach (var row in existing)
        {
            rows[rowKey(row)] = row;
        }

        var result = new List<TRow>(incoming.Count);
        foreach (var connection in incoming)
        {
            if (rows.TryGetValue(connectionKey(connection), out var row))
            {
                update(row, connection);
                result.Add(row);
            }
            else
            {
                result.Add(create(connection));
            }
        }

        return result;
    }
}
