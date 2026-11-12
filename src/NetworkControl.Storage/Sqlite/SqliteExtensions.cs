using Microsoft.Data.Sqlite;

namespace NetworkControl.Storage.Sqlite;

/// <summary>ADO.NET 的小工具：參數綁定和時間編碼。不使用反射，保持 AOT 兼容。</summary>
internal static class SqliteExtensions
{
    public static SqliteCommand With(this SqliteCommand command, string name, object? value)
    {
        command.Parameters.AddWithValue(name, value ?? DBNull.Value);
        return command;
    }

    /// <summary>時間統一存為 Unix 毫秒。</summary>
    public static long ToUnixMs(this DateTimeOffset value) => value.ToUnixTimeMilliseconds();

    public static long? ToUnixMs(this DateTimeOffset? value) => value?.ToUnixTimeMilliseconds();

    public static DateTimeOffset FromUnixMs(long value) => DateTimeOffset.FromUnixTimeMilliseconds(value);

    public static string? GetNullableString(this SqliteDataReader reader, int ordinal) =>
        reader.IsDBNull(ordinal) ? null : reader.GetString(ordinal);

    public static long? GetNullableInt64(this SqliteDataReader reader, int ordinal) =>
        reader.IsDBNull(ordinal) ? null : reader.GetInt64(ordinal);

    public static string? NullIfEmpty(this string? value) => string.IsNullOrEmpty(value) ? null : value;
}
