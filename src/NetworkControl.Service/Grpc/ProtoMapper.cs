using System.Net;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Settings;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

/// <summary>
/// 領域模型與 IPC 消息之間的轉換。兩邊的枚舉保持相同的數值，可以直接轉換；
/// 輸入消息在這裡統一校驗，非法輸入轉成 InvalidArgument。
/// </summary>
internal static class ProtoMapper
{
    // ---------- 基本類型 ----------

    public static Timestamp? ToProto(this DateTimeOffset? value) => value is { } v ? Timestamp.FromDateTimeOffset(v) : null;

    public static Timestamp ToProto(this DateTimeOffset value) => Timestamp.FromDateTimeOffset(value);

    public static Proto.VerdictAction ToProto(this VerdictAction value) => (Proto.VerdictAction)(int)value;

    public static VerdictAction ToDomain(this Proto.VerdictAction value) => System.Enum.IsDefined(value)
        ? (VerdictAction)(int)value
        : throw Invalid($"未知的動作 {value}。");

    public static Proto.Direction ToProto(this Direction value) => (Proto.Direction)(int)value;

    public static Direction ToDomain(this Proto.Direction value) => (Direction)(int)value;

    public static Proto.Protocol ToProto(this L4Protocol value) => (Proto.Protocol)(int)value;

    public static L4Protocol? ToDomain(this Proto.Protocol value) => value == Proto.Protocol.Unspecified ? null : (L4Protocol)(int)value;

    public static Proto.FilterMode ToProto(this FilterMode value) => (Proto.FilterMode)(int)value;

    public static FilterMode ToDomain(this Proto.FilterMode value) => System.Enum.IsDefined(value)
        ? (FilterMode)(int)value
        : throw Invalid($"未知的模式 {value}。");

    public static Proto.Lifetime ToProto(this RuleLifetime value) => (Proto.Lifetime)(int)value;

    public static RuleLifetime ToDomain(this Proto.Lifetime value) => System.Enum.IsDefined(value)
        ? (RuleLifetime)(int)value
        : throw Invalid($"未知的時效 {value}。");

    public static PromptScope ToDomain(this Proto.Scope value) => System.Enum.IsDefined(value)
        ? (PromptScope)(int)value
        : throw Invalid($"未知的範圍 {value}。");

    public static Proto.NetworkZone ToProto(this NetworkZone value) => (Proto.NetworkZone)(int)value;

    public static Proto.IpEndpoint ToProto(this IPEndPoint endpoint) => new() { Address = endpoint.Address.ToString(), Port = (uint)endpoint.Port };

    public static Proto.Verdict ToProto(this Verdict verdict)
    {
        var msg = new Proto.Verdict { Action = verdict.Action.ToProto(), Reason = (Proto.VerdictReason)(int)verdict.Reason };
        if (verdict.RuleId is { } ruleId)
        {
            msg.RuleId = ruleId.ToString();
        }

        return msg;
    }

    public static Proto.AppInfo ToProto(this AppIdentity app)
    {
        var msg = new Proto.AppInfo
        {
            AppId = app.AppId,
            Path = app.Path,
            DisplayName = app.Name,
            IsPlatform = app.Signature?.IsPlatformBinary ?? false,
        };
        if (app.Signature?.SignerId is { } signer)
        {
            msg.SignerId = signer;
        }

        if (app.Sha256 is { } sha)
        {
            msg.Sha256 = sha;
        }

        if (app.ServiceName is { } service)
        {
            msg.ServiceName = service;
        }

        if (app.PackageId is { } package)
        {
            msg.PackageId = package;
        }

        return msg;
    }

    // ---------- 規則 ----------

    public static Proto.RuleInfo ToProto(this Rule rule, long hitCount, DateTimeOffset? lastHitAt)
    {
        var msg = new Proto.RuleInfo
        {
            Id = rule.Id.ToString(),
            Definition = rule.ToDefinition(),
            Source = (Proto.RuleSource)(int)rule.Source,
            CreatedAt = rule.CreatedAt.ToProto(),
            UpdatedAt = rule.UpdatedAt.ToProto(),
            HitCount = hitCount,
            LastHitAt = lastHitAt.ToProto(),
        };
        if (rule.BoundProcess is { } process)
        {
            msg.BoundPid = process.Pid;
        }

        return msg;
    }

    private static Proto.RuleDefinition ToDefinition(this Rule rule)
    {
        var msg = new Proto.RuleDefinition
        {
            Conditions = rule.Conditions.ToProto(),
            Action = rule.Action.ToProto(),
            Lifetime = rule.Lifetime.ToProto(),
            ExpiresAt = rule.ExpiresAt.ToProto(),
            Enabled = rule.Enabled,
            ManualPriority = rule.ManualPriority,
        };
        if (rule.ProfileId is { } profile)
        {
            msg.ProfileId = profile.ToString();
        }

        if (rule.Note is { } note)
        {
            msg.Note = note;
        }

        if (rule.App is { } app)
        {
            var matcher = new Proto.AppMatcher { PathPattern = app.PathPattern };
            if (app.RequiredSignerId is { } signer)
            {
                matcher.RequiredSignerId = signer;
            }

            if (app.RequiredSha256 is { } sha)
            {
                matcher.RequiredSha256 = sha;
            }

            if (app.ServiceName is { } service)
            {
                matcher.ServiceName = service;
            }

            if (app.PackageId is { } package)
            {
                matcher.PackageId = package;
            }

            msg.App = matcher;
        }

        return msg;
    }

    private static Proto.RuleConditions ToProto(this RuleConditions c)
    {
        var msg = new Proto.RuleConditions();
        if (c.Direction is { } direction)
        {
            msg.Direction = direction.ToProto();
        }

        if (c.Protocol is { } protocol)
        {
            msg.Protocol = protocol.ToProto();
        }

        switch (c.Remote)
        {
            case DomainTarget d:
                msg.Domain = d.Pattern;
                break;
            case AddressTarget a:
                msg.Addresses = new Proto.AddressList();
                msg.Addresses.Networks.AddRange(a.Networks.Select(n => n.ToString()));
                break;
            case ZoneTarget z:
                msg.Zone = z.Zone.ToProto();
                break;
        }

        if (c.RemotePort is { } rp)
        {
            msg.RemotePort = new Proto.PortRange { From = rp.From, To = rp.To };
        }

        if (c.LocalPort is { } lp)
        {
            msg.LocalPort = new Proto.PortRange { From = lp.From, To = lp.To };
        }

        return msg;
    }

    /// <summary>從 IPC 輸入創建規則（用戶在界面上手動創建或編輯）。</summary>
    public static Rule ToRule(this Proto.RuleDefinition d, Guid id, RuleSource source, DateTimeOffset now)
    {
        var lifetime = d.Lifetime.ToDomain();
        if (lifetime is RuleLifetime.Once or RuleLifetime.UntilProcessExit)
        {
            throw Invalid("手動創建的規則不支持「僅本次」和「直到應用退出」時效。");
        }

        var expiresAt = d.ExpiresAt?.ToDateTimeOffset();
        if (lifetime == RuleLifetime.Timed && (expiresAt is null || expiresAt <= now))
        {
            throw Invalid("限時規則必須設置一個未來的過期時間。");
        }

        return new Rule
        {
            Id = id,
            ProfileId = d.HasProfileId ? ParseGuid(d.ProfileId, "profile_id") : null,
            App = d.App is null ? null : new AppMatcher
            {
                PathPattern = string.IsNullOrWhiteSpace(d.App.PathPattern) ? throw Invalid("應用路徑不能為空。") : d.App.PathPattern,
                RequiredSignerId = d.App.HasRequiredSignerId ? d.App.RequiredSignerId : null,
                RequiredSha256 = d.App.HasRequiredSha256 ? d.App.RequiredSha256 : null,
                ServiceName = d.App.HasServiceName ? d.App.ServiceName : null,
                PackageId = d.App.HasPackageId ? d.App.PackageId : null,
            },
            Conditions = (d.Conditions ?? new Proto.RuleConditions()).ToDomain(),
            Action = d.Action.ToDomain(),
            Lifetime = lifetime,
            ExpiresAt = lifetime == RuleLifetime.Timed ? expiresAt : null,
            Source = source,
            Enabled = d.Enabled,
            ManualPriority = Math.Clamp(d.ManualPriority, 0, 1000),
            Note = d.HasNote ? d.Note : null,
            CreatedAt = now,
            UpdatedAt = now,
        };
    }

    private static RuleConditions ToDomain(this Proto.RuleConditions c) => new()
    {
        Direction = c.HasDirection ? c.Direction.ToDomain() : null,
        Protocol = c.HasProtocol ? c.Protocol.ToDomain() : null,
        Remote = c.RemoteCase switch
        {
            Proto.RuleConditions.RemoteOneofCase.Domain => new DomainTarget(ValidateDomain(c.Domain)),
            Proto.RuleConditions.RemoteOneofCase.Addresses => new AddressTarget(c.Addresses.Networks.Select(ParseNetwork).ToArray()),
            Proto.RuleConditions.RemoteOneofCase.Zone => new ZoneTarget((NetworkZone)(int)c.Zone),
            _ => null,
        },
        RemotePort = c.RemotePort is { } rp ? ToPortRange(rp) : null,
        LocalPort = c.LocalPort is { } lp ? ToPortRange(lp) : null,
    };

    // ---------- 彈窗 ----------

    public static Proto.PromptEvent ToProto(this PromptEvent evt) => new()
    {
        Kind = (Proto.PromptEventKind)(int)evt.Kind,
        Prompt = evt.Prompt.ToProto(),
    };

    public static Proto.Prompt ToProto(this PromptSnapshot p)
    {
        var msg = new Proto.Prompt
        {
            Id = p.Id.ToString(),
            App = p.App?.ToProto(),
            ExecutablePath = p.ExecutablePath ?? "",
            CreatedAt = p.CreatedAt.ToProto(),
            Deadline = p.Deadline.ToProto(),
            IsTamperAlert = p.IsTamperAlert,
            IsConnectionHeld = p.IsConnectionHeld,
        };
        if (p.Process is { } process)
        {
            msg.Pid = process.Pid;
        }

        foreach (var f in p.Flows)
        {
            var flow = new Proto.PromptFlow
            {
                FlowId = f.FlowId,
                Direction = f.Direction.ToProto(),
                Protocol = f.Protocol.ToProto(),
                Local = f.Local.ToProto(),
                Remote = f.Remote.ToProto(),
                Timestamp = f.Timestamp.ToProto(),
                RemoteZone = NetworkZones.Classify(f.Remote.Address).ToProto(),
            };
            flow.Domains.AddRange(f.Domains);
            msg.Flows.Add(flow);
        }

        return msg;
    }

    public static PromptDecision ToDomain(this Proto.AnswerRequest request) => new()
    {
        Action = request.Action.ToDomain(),
        Scope = request.Scope.ToDomain(),
        Lifetime = request.Lifetime.ToDomain(),
        Duration = request.Duration?.ToTimeSpan() is { } d && d > TimeSpan.Zero ? d : null,
        SelectedDomain = request.HasSelectedDomain && !string.IsNullOrWhiteSpace(request.SelectedDomain) ? request.SelectedDomain : null,
        ApplyToAllFlows = request.ApplyToAllFlows,
    };

    // ---------- 活動 ----------

    public static Proto.ConnectionEvent ToProto(this ConnectionEvent e)
    {
        var msg = new Proto.ConnectionEvent
        {
            Timestamp = e.Timestamp.ToProto(),
            Direction = e.Direction.ToProto(),
            Protocol = e.Protocol.ToProto(),
            Local = e.Local.ToProto(),
            Remote = e.Remote.ToProto(),
            Verdict = e.Verdict.ToProto(),
        };
        if (e.AppId is { } appId)
        {
            msg.AppId = appId;
        }

        if (e.AppPath is { } path)
        {
            msg.AppPath = path;
        }

        if (e.AppName is { } name)
        {
            msg.AppName = name;
        }

        if (e.Hostname is { } host)
        {
            msg.Hostname = host;
        }

        return msg;
    }

    public static Proto.LogEntry ToProto(this LogEntry e)
    {
        var msg = new Proto.LogEntry
        {
            Id = e.Id,
            FirstSeen = e.FirstSeen.ToProto(),
            LastSeen = e.LastSeen.ToProto(),
            Direction = e.Direction.ToProto(),
            Protocol = e.Protocol.ToProto(),
            Local = e.Local?.ToProto(),
            Remote = new Proto.IpEndpoint { Address = e.RemoteAddress.ToString(), Port = (uint)e.RemotePort },
            Verdict = e.Verdict.ToProto(),
            Count = e.Count,
        };
        if (e.AppId is { } appId)
        {
            msg.AppId = appId;
        }

        if (e.AppPath is { } path)
        {
            msg.AppPath = path;
        }

        if (e.AppName is { } name)
        {
            msg.AppName = name;
        }

        if (e.Hostname is { } host)
        {
            msg.Hostname = host;
        }

        return msg;
    }

    public static LogQuery ToDomain(this Proto.LogQuery q) => new()
    {
        From = q.From?.ToDateTimeOffset(),
        To = q.To?.ToDateTimeOffset(),
        AppId = q.HasAppId ? q.AppId : null,
        Action = q.HasAction ? q.Action.ToDomain() : null,
        Reason = q.HasReason ? (VerdictReason)(int)q.Reason : null,
        Keyword = q.HasKeyword ? q.Keyword : null,
        Limit = q.Limit > 0 ? q.Limit : 200,
        BeforeId = q.HasBeforeId ? q.BeforeId : null,
    };

    // ---------- 設置 ----------

    public static Proto.UserSettings ToProto(this UserSettings s) => new()
    {
        PromptTimeout = Duration.FromTimeSpan(s.PromptTimeout),
        PromptDefaultAction = s.PromptDefaultAction.ToProto(),
        FailPolicy = (Proto.FailPolicy)(int)s.FailPolicy,
        AllowIcmpByDefault = s.AllowIcmpByDefault,
        LogRetentionDays = s.LogRetentionDays,
        OnceFallbackDuration = Duration.FromTimeSpan(s.OnceFallbackDuration),
    };

    public static UserSettings ApplyTo(this Proto.UserSettings msg, UserSettings current) => current with
    {
        PromptTimeout = msg.PromptTimeout?.ToTimeSpan() ?? current.PromptTimeout,
        PromptDefaultAction = msg.PromptDefaultAction.ToDomain(),
        FailPolicy = System.Enum.IsDefined(msg.FailPolicy) ? (FailPolicy)(int)msg.FailPolicy : throw Invalid("未知的失效策略。"),
        AllowIcmpByDefault = msg.AllowIcmpByDefault,
        LogRetentionDays = msg.LogRetentionDays > 0 ? msg.LogRetentionDays : current.LogRetentionDays,
        OnceFallbackDuration = msg.OnceFallbackDuration?.ToTimeSpan() ?? current.OnceFallbackDuration,
    };

    // ---------- 校驗輔助 ----------

    public static Guid ParseGuid(string value, string field) =>
        Guid.TryParse(value, out var id) ? id : throw Invalid($"{field} 不是有效的 GUID：{value}");

    public static RpcException Invalid(string message) => new(new Status(StatusCode.InvalidArgument, message));

    private static PortRange ToPortRange(Proto.PortRange range)
    {
        if (range.From > ushort.MaxValue || range.To > ushort.MaxValue || range.From > range.To)
        {
            throw Invalid($"無效的端口範圍 {range.From}-{range.To}。");
        }

        return new PortRange((ushort)range.From, (ushort)range.To);
    }

    private static IPNetwork ParseNetwork(string value)
    {
        if (IPNetwork.TryParse(value, out var network))
        {
            return network;
        }

        if (IPAddress.TryParse(value, out var address))
        {
            return AddressTarget.Single(address).Networks[0];
        }

        throw Invalid($"無效的地址或網段：{value}");
    }

    private static string ValidateDomain(string domain)
    {
        var trimmed = domain.Trim();
        var bare = trimmed.StartsWith("*.", StringComparison.Ordinal) ? trimmed[2..] : trimmed;
        if (bare.Length == 0 || bare.Length > 253 || bare.Contains('*', StringComparison.Ordinal) || bare.Contains(' ', StringComparison.Ordinal))
        {
            throw Invalid($"無效的域名：{domain}");
        }

        return trimmed;
    }
}
