using System.Text.Json;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Profiles;
using NetworkControl.Storage.Serialization;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Repositories;

internal sealed class SqliteProfileRepository(ConfigDatabase db) : IProfileRepository
{
    public async Task<IReadOnlyList<Profile>> LoadAllAsync(CancellationToken cancellationToken)
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = "SELECT id, name, mode, auto_activate, sort_order FROM profiles ORDER BY sort_order, name;";

        var result = new List<Profile>();
        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            result.Add(new Profile
            {
                Id = Guid.Parse(reader.GetString(0)),
                Name = reader.GetString(1),
                Mode = (FilterMode)reader.GetInt32(2),
                AutoActivate = reader.GetNullableString(3) is { } auto
                    ? JsonSerializer.Deserialize(auto, StorageJsonContext.Default.AutoActivateDto)!.ToDomain()
                    : null,
                SortOrder = reader.GetInt32(4),
            });
        }

        return result;
    }

    public async Task UpsertAsync(Profile profile, CancellationToken cancellationToken)
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = """
            INSERT INTO profiles (id, name, mode, auto_activate, sort_order)
            VALUES ($id, $name, $mode, $auto, $sort)
            ON CONFLICT (id) DO UPDATE SET
                name = excluded.name,
                mode = excluded.mode,
                auto_activate = excluded.auto_activate,
                sort_order = excluded.sort_order;
            """;
        cmd.With("$id", profile.Id.ToString())
            .With("$name", profile.Name)
            .With("$mode", (int)profile.Mode)
            .With("$auto", profile.AutoActivate is null
                ? null
                : JsonSerializer.Serialize(profile.AutoActivate.ToDto(), StorageJsonContext.Default.AutoActivateDto))
            .With("$sort", profile.SortOrder);
        await cmd.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task DeleteAsync(Guid id, CancellationToken cancellationToken)
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = "DELETE FROM profiles WHERE id = $id;";
        cmd.With("$id", id.ToString());
        await cmd.ExecuteNonQueryAsync(cancellationToken);
    }
}
