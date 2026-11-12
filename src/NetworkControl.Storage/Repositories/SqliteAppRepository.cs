using Microsoft.Data.Sqlite;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Repositories;

internal sealed class SqliteAppRepository(ConfigDatabase db, TimeProvider time) : IAppRepository
{
    private const string SelectColumns = "SELECT id, path, service_name, package_id, display_name, signer_id, signing_id, is_platform, sha256 FROM apps";

    public async ValueTask<AppIdentity> UpsertAsync(AppKey key, AppInspection inspection, CancellationToken cancellationToken)
    {
        var now = time.GetUtcNow().ToUnixMs();
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = """
            INSERT INTO apps (path, service_name, package_id, file_size, file_mtime, display_name, signer_id, signing_id, is_platform, sha256, first_seen, last_seen)
            VALUES ($path, $service, $package, $size, $mtime, $name, $signer, $signing, $platform, $sha, $now, $now)
            ON CONFLICT (path, service_name, package_id) DO UPDATE SET
                file_size = excluded.file_size, file_mtime = excluded.file_mtime, display_name = excluded.display_name,
                signer_id = excluded.signer_id, signing_id = excluded.signing_id, is_platform = excluded.is_platform,
                sha256 = excluded.sha256, last_seen = excluded.last_seen
            RETURNING id, path, service_name, package_id, display_name, signer_id, signing_id, is_platform, sha256;
            """;
        cmd.With("$path", key.Path)
            .With("$service", key.ServiceName ?? "")
            .With("$package", key.PackageId ?? "")
            .With("$size", inspection.Stamp.Size)
            .With("$mtime", inspection.Stamp.LastWriteTicks)
            .With("$name", inspection.DisplayName)
            .With("$signer", inspection.Signature?.SignerId)
            .With("$signing", inspection.Signature?.SigningId)
            .With("$platform", inspection.Signature?.IsPlatformBinary ?? false)
            .With("$sha", inspection.Sha256)
            .With("$now", now);

        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
        {
            throw new InvalidOperationException($"寫入應用 {key.Path} 失敗。");
        }

        return Read(reader);
    }

    public async ValueTask<AppIdentity?> GetAsync(long appId, CancellationToken cancellationToken)
    {
        await using var connection = await db.OpenAsync(cancellationToken);
        await using var cmd = connection.CreateCommand();
        cmd.CommandText = $"{SelectColumns} WHERE id = $id;";
        cmd.With("$id", appId);
        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        return await reader.ReadAsync(cancellationToken) ? Read(reader) : null;
    }

    private static AppIdentity Read(SqliteDataReader r)
    {
        var signer = r.GetNullableString(5);
        return new AppIdentity
        {
            AppId = r.GetInt64(0),
            Path = r.GetString(1),
            ServiceName = r.GetString(2).NullIfEmpty(),
            PackageId = r.GetString(3).NullIfEmpty(),
            DisplayName = r.GetNullableString(4),
            Signature = signer is null ? null : new CodeSignature(signer, r.GetNullableString(6), r.GetBoolean(7)),
            Sha256 = r.GetNullableString(8),
        };
    }
}
