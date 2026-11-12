using System.Text.Json;
using Microsoft.Data.Sqlite;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Rules;
using NetworkControl.Storage.Serialization;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Repositories;

internal sealed class SqliteRuleRepository(ConfigDatabase db) : IRuleRepository
{
    private const string SelectColumns = """
        SELECT id, profile_id, app_matcher, conditions, action, lifetime, expires_at, bound_pid, bound_start,
               source, enabled, manual_priority, note, created_at, updated_at, last_hit_at, hit_count
        FROM rules
        """;

    public async Task<IReadOnlyList<StoredRule>> LoadAllAsync(CancellationToken cancellationToken)
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = SelectColumns;

        var result = new List<StoredRule>();
        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            result.Add(Read(reader));
        }

        return result;
    }

    public async Task UpsertAsync(Rule rule, CancellationToken cancellationToken)
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = """
            INSERT INTO rules (id, profile_id, app_matcher, conditions, action, lifetime, expires_at, bound_pid, bound_start,
                               source, enabled, manual_priority, note, created_at, updated_at)
            VALUES ($id, $profile, $app, $conditions, $action, $lifetime, $expires, $pid, $start,
                    $source, $enabled, $priority, $note, $created, $updated)
            ON CONFLICT (id) DO UPDATE SET
                profile_id = excluded.profile_id, app_matcher = excluded.app_matcher, conditions = excluded.conditions,
                action = excluded.action, lifetime = excluded.lifetime, expires_at = excluded.expires_at,
                bound_pid = excluded.bound_pid, bound_start = excluded.bound_start, source = excluded.source,
                enabled = excluded.enabled, manual_priority = excluded.manual_priority, note = excluded.note,
                updated_at = excluded.updated_at;
            """;
        cmd.With("$id", rule.Id.ToString())
            .With("$profile", rule.ProfileId?.ToString())
            .With("$app", rule.App is null ? null : JsonSerializer.Serialize(rule.App.ToDto(), StorageJsonContext.Default.AppMatcherDto))
            .With("$conditions", JsonSerializer.Serialize(rule.Conditions.ToDto(), StorageJsonContext.Default.RuleConditionsDto))
            .With("$action", (int)rule.Action)
            .With("$lifetime", (int)rule.Lifetime)
            .With("$expires", rule.ExpiresAt.ToUnixMs())
            .With("$pid", rule.BoundProcess?.Pid)
            .With("$start", rule.BoundProcess?.StartTimeTicks)
            .With("$source", (int)rule.Source)
            .With("$enabled", rule.Enabled)
            .With("$priority", rule.ManualPriority)
            .With("$note", rule.Note)
            .With("$created", rule.CreatedAt.ToUnixMs())
            .With("$updated", rule.UpdatedAt.ToUnixMs());
        await cmd.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task DeleteAsync(IReadOnlyCollection<Rule> rules, RuleEndReason reason, DateTimeOffset endedAt, CancellationToken cancellationToken)
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var tx = (SqliteTransaction)await connection.BeginTransactionAsync(cancellationToken);

        foreach (var rule in rules)
        {
            await using (var history = connection.CreateCommand())
            {
                history.Transaction = tx;
                history.CommandText = "INSERT OR REPLACE INTO rule_history (id, snapshot, ended_at, end_reason) VALUES ($id, $snapshot, $ended, $reason);";
                history.With("$id", rule.Id.ToString())
                    .With("$snapshot", JsonSerializer.Serialize(rule.ToSnapshot(), StorageJsonContext.Default.RuleSnapshotDto))
                    .With("$ended", endedAt.ToUnixMs())
                    .With("$reason", (int)reason);
                await history.ExecuteNonQueryAsync(cancellationToken);
            }

            await using var delete = connection.CreateCommand();
            delete.Transaction = tx;
            delete.CommandText = "DELETE FROM rules WHERE id = $id;";
            delete.With("$id", rule.Id.ToString());
            await delete.ExecuteNonQueryAsync(cancellationToken);
        }

        await tx.CommitAsync(cancellationToken);
    }

    public async Task RecordHitsAsync(IReadOnlyCollection<RuleHitDelta> hits, CancellationToken cancellationToken)
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var tx = (SqliteTransaction)await connection.BeginTransactionAsync(cancellationToken);
        foreach (var hit in hits)
        {
            await using var cmd = connection.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = "UPDATE rules SET hit_count = hit_count + $count, last_hit_at = MAX(COALESCE(last_hit_at, 0), $last) WHERE id = $id;";
            cmd.With("$count", hit.Count).With("$last", hit.LastHitAt.ToUnixMs()).With("$id", hit.RuleId.ToString());
            await cmd.ExecuteNonQueryAsync(cancellationToken);
        }

        await tx.CommitAsync(cancellationToken);
    }

    private static StoredRule Read(SqliteDataReader r)
    {
        var appJson = r.GetNullableString(2);
        var pid = r.GetNullableInt64(7);
        var start = r.GetNullableInt64(8);

        var rule = new Rule
        {
            Id = Guid.Parse(r.GetString(0)),
            ProfileId = r.GetNullableString(1) is { } profile ? Guid.Parse(profile) : null,
            App = appJson is null ? null : JsonSerializer.Deserialize(appJson, StorageJsonContext.Default.AppMatcherDto)!.ToDomain(),
            Conditions = JsonSerializer.Deserialize(r.GetString(3), StorageJsonContext.Default.RuleConditionsDto)!.ToDomain(),
            Action = (VerdictAction)r.GetInt32(4),
            Lifetime = (RuleLifetime)r.GetInt32(5),
            ExpiresAt = r.GetNullableInt64(6) is { } exp ? SqliteExtensions.FromUnixMs(exp) : null,
            BoundProcess = pid is not null && start is not null ? new ProcessRef((int)pid.Value, start.Value) : null,
            Source = (RuleSource)r.GetInt32(9),
            Enabled = r.GetBoolean(10),
            ManualPriority = r.GetInt32(11),
            Note = r.GetNullableString(12),
            CreatedAt = SqliteExtensions.FromUnixMs(r.GetInt64(13)),
            UpdatedAt = SqliteExtensions.FromUnixMs(r.GetInt64(14)),
        };

        var lastHit = r.GetNullableInt64(15);
        return new StoredRule(rule, r.GetInt64(16), lastHit is null ? null : SqliteExtensions.FromUnixMs(lastHit.Value));
    }
}
