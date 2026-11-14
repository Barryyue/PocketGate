using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Microsoft.AspNetCore.Authorization;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Profiles;
using NetworkControl.Core.Rules;
using NetworkControl.Service.Security;
using NetworkControl.Storage.Backup;
using NetworkControl.Storage.Serialization;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

[Authorize(Policy = IpcPolicies.Viewer)]
internal sealed class RulesGrpcService(RuleService rules, ProfileService profiles, ConfigBackup backups, TimeProvider time) : Proto.Rules.RulesBase
{
    public override Task<Proto.ListRulesResponse> List(Proto.ListRulesRequest request, ServerCallContext context)
    {
        Guid? profile = request.HasProfileId ? ProtoMapper.ParseGuid(request.ProfileId, "profile_id") : null;
        var response = new Proto.ListRulesResponse();
        response.Rules.AddRange(rules.All
            .Where(r => profile is null || r.ProfileId is null || r.ProfileId == profile)
            .OrderByDescending(r => r.UpdatedAt)
            .Select(ToInfo));
        return Task.FromResult(response);
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.RuleInfo> Create(Proto.RuleDefinition request, ServerCallContext context)
    {
        var now = time.GetUtcNow();
        var rule = request.ToRule(Guid.CreateVersion7(now), RuleSource.User, now);
        ValidateProfile(rule);
        return ToInfo(await Execute(() => rules.AddAsync(rule, context.CancellationToken)));
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.RuleInfo> Update(Proto.UpdateRuleRequest request, ServerCallContext context)
    {
        var id = ProtoMapper.ParseGuid(request.Id, "id");
        var existing = rules.Find(id) ?? throw NotFound(id);
        var rule = request.Definition.ToRule(id, existing.Source, time.GetUtcNow());
        ValidateProfile(rule);
        return ToInfo(await Execute(() => rules.UpdateAsync(rule, context.CancellationToken)));
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.RuleInfo> SetEnabled(Proto.SetRuleEnabledRequest request, ServerCallContext context)
    {
        var id = ProtoMapper.ParseGuid(request.Id, "id");
        return ToInfo(await Execute(() => rules.SetEnabledAsync(id, request.Enabled, context.CancellationToken)));
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.DeleteRulesResponse> Delete(Proto.DeleteRulesRequest request, ServerCallContext context)
    {
        var ids = request.Ids.Select(i => ProtoMapper.ParseGuid(i, "ids")).ToArray();
        var deleted = await rules.RemoveAsync(ids, RuleEndReason.Deleted, context.CancellationToken);
        return new Proto.DeleteRulesResponse { Deleted = deleted };
    }

    public override Task<Proto.ExportRulesResponse> Export(Proto.ExportRulesRequest request, ServerCallContext context)
    {
        var all = rules.All;
        var exportable = all.Where(RuleExportSerializer.IsExportable).OrderBy(r => r.CreatedAt).ToArray();
        return Task.FromResult(new Proto.ExportRulesResponse
        {
            Content = RuleExportSerializer.Export(exportable, Mapper(request.PathRoots)),
            Exported = exportable.Length,
            Skipped = all.Count - exportable.Length,
        });
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.ImportResult> Import(Proto.ImportRulesRequest request, ServerCallContext context)
    {
        IReadOnlyList<Rule> imported;
        try
        {
            imported = RuleExportSerializer.Import(request.Content, Mapper(request.PathRoots));
        }
        catch (FormatException ex)
        {
            throw ProtoMapper.Invalid(ex.Message);
        }

        var mode = request.Mode == Proto.ImportMode.Replace ? RuleImportMode.Replace : RuleImportMode.Merge;
        return await ApplyAsync(imported, mode, request.PreviewOnly, snapshot: null, markNewAsImported: true, context.CancellationToken);
    }

    public override Task<Proto.ListBackupsResponse> ListBackups(Empty request, ServerCallContext context)
    {
        var response = new Proto.ListBackupsResponse();
        response.Backups.AddRange(backups.List().Select(b => new Proto.BackupInfo
        {
            Name = b.Name,
            CreatedAt = Timestamp.FromDateTime(DateTime.SpecifyKind(b.CreatedAt, DateTimeKind.Utc)),
            Size = b.Size,
            IsRestoreSnapshot = b.IsRestoreSnapshot,
        }));
        return Task.FromResult(response);
    }

    [Authorize(Policy = IpcPolicies.Admin)]
    public override async Task<Proto.ImportResult> RestoreBackup(Proto.RestoreBackupRequest request, ServerCallContext context)
    {
        IReadOnlyList<Rule> restored;
        try
        {
            restored = await backups.LoadRulesAsync(request.Name, context.CancellationToken);
        }
        catch (FileNotFoundException)
        {
            throw new RpcException(new Status(StatusCode.NotFound, $"備份 {request.Name} 不存在。"));
        }

        // 恢復 = 用備份中的規則覆蓋；先建立恢復前快照，恢復錯了還能恢復回來
        var snapshot = request.PreviewOnly ? null : await backups.CreateRestoreSnapshotAsync(context.CancellationToken);
        return await ApplyAsync(restored.Where(RuleExportSerializer.IsExportable).ToArray(), RuleImportMode.Replace, request.PreviewOnly, snapshot, markNewAsImported: false, context.CancellationToken);
    }

    public override async Task Watch(Empty request, IServerStreamWriter<Proto.RuleChangeEvent> responseStream, ServerCallContext context)
    {
        using var streaming = StreamingCancellation.Link(context);
        try
        {
            await foreach (var change in rules.Changes.SubscribeAsync(streaming.Token))
            {
                await responseStream.WriteAsync(new Proto.RuleChangeEvent
                {
                    Kind = (Proto.RuleChangeKind)(int)change.Kind,
                    Rule = ToInfo(change.Rule),
                }, streaming.Token);
            }
        }
        catch (OperationCanceledException) when (streaming.IsCancellationRequested)
        {
            // 客戶端斷開
        }
    }

    private Proto.RuleInfo ToInfo(Rule rule)
    {
        var (count, lastHit) = rules.GetHitStats(rule.Id);
        return rule.ToProto(count, lastHit);
    }

    private static async Task<Rule> Execute(Func<Task<Rule>> action)
    {
        try
        {
            return await action();
        }
        catch (KeyNotFoundException ex)
        {
            throw new RpcException(new Status(StatusCode.NotFound, ex.Message));
        }
        catch (ArgumentException ex)
        {
            throw ProtoMapper.Invalid(ex.Message);
        }
    }

    private void ValidateProfile(Rule rule)
    {
        if (rule.ProfileId is { } profileId && profiles.Find(profileId) is null)
        {
            throw ProtoMapper.Invalid($"配置文件 {profileId} 不存在。");
        }
    }

    /// <summary>計算並（非預覽時）執行導入計劃：先新增和更新，最後刪除，中途出錯時少刪不多刪。</summary>
    private async Task<Proto.ImportResult> ApplyAsync(IReadOnlyList<Rule> imported, RuleImportMode mode, bool previewOnly, string? snapshot, bool markNewAsImported, CancellationToken cancellationToken)
    {
        var now = time.GetUtcNow();
        var knownProfiles = profiles.All.Select(p => p.Id).ToHashSet();
        // 已過期的限時規則沒有導入的意義；目標機器上不存在的配置文件降級為共享規則
        var current = imported
            .Where(r => r.Lifetime != RuleLifetime.Timed || r.ExpiresAt > now)
            .Select(r => r.ProfileId is { } profileId && !knownProfiles.Contains(profileId)
                ? r with { ProfileId = null }
                : r)
            .ToArray();
        var plan = RuleImportPlanner.Plan(rules.All, current, mode, markNewAsImported);
        if (!previewOnly)
        {
            foreach (var rule in plan.ToAdd)
            {
                await Execute(() => rules.AddAsync(rule, cancellationToken));
            }

            foreach (var rule in plan.ToUpdate)
            {
                await Execute(() => rules.UpdateAsync(rule, cancellationToken));
            }

            if (plan.ToRemove.Count > 0)
            {
                await rules.RemoveAsync(plan.ToRemove.Select(r => r.Id).ToArray(), RuleEndReason.Replaced, cancellationToken);
            }
        }

        var result = new Proto.ImportResult
        {
            Added = plan.ToAdd.Count,
            Updated = plan.ToUpdate.Count,
            Removed = plan.ToRemove.Count,
            Unchanged = plan.Unchanged,
        };
        if (snapshot is not null)
        {
            result.Snapshot = snapshot;
        }

        return result;
    }

    private static PathPlaceholderMapper Mapper(IDictionary<string, string> roots) =>
        roots.Count == 0 ? PathPlaceholderMapper.None : new PathPlaceholderMapper(roots.Select(p => new KeyValuePair<string, string?>(p.Key, p.Value)));

    private static RpcException NotFound(Guid id) => new(new Status(StatusCode.NotFound, $"規則 {id} 不存在。"));
}
