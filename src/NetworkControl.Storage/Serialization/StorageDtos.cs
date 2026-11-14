using System.Net;
using System.Text.Json.Serialization;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Profiles;
using NetworkControl.Core.Settings;

namespace NetworkControl.Storage.Serialization;

// 持久化格式（JSON 列）。與領域模型分離，保證領域模型重構時存儲格式穩定。
// 修改規則：只能新增可選字段；刪除或重命名字段必須通過遷移腳本處理舊數據。

internal sealed record AppMatcherDto(
    string PathPattern,
    string? RequiredSignerId = null,
    string? RequiredSha256 = null,
    string? ServiceName = null,
    string? PackageId = null);

internal sealed record PortRangeDto(int From, int To);

internal sealed record AutoActivateDto(NetworkKind? Kind, string[] Ssids, string[] NetworkIds);

[JsonPolymorphic(TypeDiscriminatorPropertyName = "kind")]
[JsonDerivedType(typeof(DomainTargetDto), "domain")]
[JsonDerivedType(typeof(AddressTargetDto), "address")]
[JsonDerivedType(typeof(ZoneTargetDto), "zone")]
internal abstract record RemoteTargetDto;

internal sealed record DomainTargetDto(string Pattern) : RemoteTargetDto;

internal sealed record AddressTargetDto(string[] Networks) : RemoteTargetDto;

internal sealed record ZoneTargetDto(NetworkZone Zone) : RemoteTargetDto;

internal sealed record RuleConditionsDto(
    Direction? Direction = null,
    L4Protocol? Protocol = null,
    RemoteTargetDto? Remote = null,
    PortRangeDto? RemotePort = null,
    PortRangeDto? LocalPort = null);

/// <summary>rule_history 中保存的完整規則快照。</summary>
internal sealed record RuleSnapshotDto(
    Guid Id,
    Guid? ProfileId,
    AppMatcherDto? App,
    RuleConditionsDto Conditions,
    VerdictAction Action,
    RuleLifetime Lifetime,
    DateTimeOffset? ExpiresAt,
    RuleSource Source,
    bool Enabled,
    string? Note,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    UseStringEnumConverter = true)]
[JsonSerializable(typeof(AppMatcherDto))]
[JsonSerializable(typeof(AutoActivateDto))]
[JsonSerializable(typeof(RuleConditionsDto))]
[JsonSerializable(typeof(RuleSnapshotDto))]
[JsonSerializable(typeof(UserSettings))]
[JsonSerializable(typeof(RuntimeStateRecord))]
internal sealed partial class StorageJsonContext : JsonSerializerContext;

/// <summary>領域模型與持久化 DTO 之間的轉換。</summary>
internal static class DtoMapper
{
    public static AppMatcherDto ToDto(this AppMatcher m) =>
        new(m.PathPattern, m.RequiredSignerId, m.RequiredSha256, m.ServiceName, m.PackageId);

    public static AppMatcher ToDomain(this AppMatcherDto d) => new()
    {
        PathPattern = d.PathPattern,
        RequiredSignerId = d.RequiredSignerId,
        RequiredSha256 = d.RequiredSha256,
        ServiceName = d.ServiceName,
        PackageId = d.PackageId,
    };

    public static RuleConditionsDto ToDto(this RuleConditions c) => new(
        c.Direction,
        c.Protocol,
        c.Remote switch
        {
            DomainTarget d => new DomainTargetDto(d.Pattern),
            AddressTarget a => new AddressTargetDto(a.Networks.Select(n => n.ToString()).ToArray()),
            ZoneTarget z => new ZoneTargetDto(z.Zone),
            null => null,
            _ => throw new NotSupportedException($"不支持持久化的遠端目標類型 {c.Remote.GetType().Name}。"),
        },
        c.RemotePort is { } rp ? new PortRangeDto(rp.From, rp.To) : null,
        c.LocalPort is { } lp ? new PortRangeDto(lp.From, lp.To) : null);

    public static RuleConditions ToDomain(this RuleConditionsDto d) => new()
    {
        Direction = d.Direction,
        Protocol = d.Protocol,
        Remote = d.Remote switch
        {
            DomainTargetDto t => new DomainTarget(t.Pattern),
            AddressTargetDto t => new AddressTarget(t.Networks.Select(IPNetwork.Parse).ToArray()),
            ZoneTargetDto t => new ZoneTarget(t.Zone),
            _ => null,
        },
        RemotePort = d.RemotePort is { } rp ? new PortRange((ushort)rp.From, (ushort)rp.To) : null,
        LocalPort = d.LocalPort is { } lp ? new PortRange((ushort)lp.From, (ushort)lp.To) : null,
    };

    public static AutoActivateDto? ToDto(this AutoActivateCondition? condition) => condition is null
        ? null
        : new AutoActivateDto(condition.Kind, condition.Ssids.ToArray(), condition.NetworkIds.ToArray());

    public static AutoActivateCondition? ToDomain(this AutoActivateDto? dto) => dto is null
        ? null
        : new AutoActivateCondition(dto.Kind, dto.Ssids, dto.NetworkIds);

    public static RuleSnapshotDto ToSnapshot(this Rule r) => new(
        r.Id, r.ProfileId, r.App?.ToDto(), r.Conditions.ToDto(), r.Action, r.Lifetime, r.ExpiresAt, r.Source, r.Enabled, r.Note, r.CreatedAt, r.UpdatedAt);
}
