using System.Text.Json;
using System.Text.Json.Serialization.Metadata;
using NetworkControl.Core.Settings;
using NetworkControl.Storage.Serialization;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Repositories;

internal sealed class SqliteSettingsRepository(ConfigDatabase db) : ISettingsRepository
{
    private const string UserSettingsKey = "user";
    private const string RuntimeStateKey = "runtime";

    public Task<UserSettings?> LoadUserSettingsAsync(CancellationToken cancellationToken) =>
        LoadAsync(UserSettingsKey, StorageJsonContext.Default.UserSettings, cancellationToken);

    public Task SaveUserSettingsAsync(UserSettings settings, CancellationToken cancellationToken) =>
        SaveAsync(UserSettingsKey, settings, StorageJsonContext.Default.UserSettings, cancellationToken);

    public Task<RuntimeStateRecord?> LoadRuntimeStateAsync(CancellationToken cancellationToken) =>
        LoadAsync(RuntimeStateKey, StorageJsonContext.Default.RuntimeStateRecord, cancellationToken);

    public Task SaveRuntimeStateAsync(RuntimeStateRecord state, CancellationToken cancellationToken) =>
        SaveAsync(RuntimeStateKey, state, StorageJsonContext.Default.RuntimeStateRecord, cancellationToken);

    private async Task<T?> LoadAsync<T>(string key, JsonTypeInfo<T> typeInfo, CancellationToken cancellationToken)
        where T : class
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT value FROM settings WHERE key = $key;";
        cmd.With("$key", key);
        return await cmd.ExecuteScalarAsync(cancellationToken) is string json
            ? JsonSerializer.Deserialize(json, typeInfo)
            : null;
    }

    private async Task SaveAsync<T>(string key, T value, JsonTypeInfo<T> typeInfo, CancellationToken cancellationToken)
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = "INSERT INTO settings (key, value) VALUES ($key, $value) ON CONFLICT (key) DO UPDATE SET value = excluded.value;";
        cmd.With("$key", key).With("$value", JsonSerializer.Serialize(value, typeInfo));
        await cmd.ExecuteNonQueryAsync(cancellationToken);
    }
}
