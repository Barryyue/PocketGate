using NetworkControl.Core.Apps;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Rules;

/// <summary>把彈窗決策轉換成規則（設計文檔 §8.3）。</summary>
public static class PromptRuleFactory
{
    /// <summary>默認的限時時長。</summary>
    public static readonly TimeSpan DefaultTimedDuration = TimeSpan.FromMinutes(15);

    /// <summary>
    /// 根據決策和觸發彈窗的連接生成條件。即使決策是「僅本次」，也用這些條件判斷合併的其他連接是否被這次決策覆蓋。
    /// </summary>
    public static RuleConditions CreateConditions(PromptDecision decision, FlowInfo flow, DomainCandidates domains)
    {
        var scope = decision.Scope;
        if (scope == PromptScope.Domain && string.IsNullOrEmpty(decision.SelectedDomain) && domains.IsEmpty)
        {
            // 沒有可用域名：退化為按 IP
            scope = PromptScope.Address;
        }

        var baseline = new RuleConditions { Direction = flow.Direction };
        return scope switch
        {
            PromptScope.AnyConnection => baseline,
            PromptScope.Domain => baseline with { Remote = new DomainTarget(decision.SelectedDomain ?? domains.Primary!) },
            PromptScope.Address => baseline with { Remote = AddressTarget.Single(flow.Remote.Address) },
            PromptScope.AddressAndPort => baseline with
            {
                Remote = AddressTarget.Single(flow.Remote.Address),
                RemotePort = PortRange.Single((ushort)flow.Remote.Port),
                Protocol = flow.Protocol,
            },
            PromptScope.Port => baseline with
            {
                RemotePort = PortRange.Single((ushort)flow.Remote.Port),
                Protocol = flow.Protocol,
            },
            _ => throw new ArgumentOutOfRangeException(nameof(decision), scope, "未知的決策範圍。"),
        };
    }

    /// <summary>
    /// 生成要保存的規則。以下情況返回 null（決策只作用於當前彈窗中的連接，不產生規則）：
    /// 支持掛起的引擎上選擇「僅本次」；無法識別發起連接的應用。
    /// </summary>
    public static Rule? CreateRule(
        PromptDecision decision,
        FlowInfo flow,
        AppIdentity? app,
        DomainCandidates domains,
        Guid? profileId,
        DateTimeOffset now,
        TimeSpan onceFallbackDuration)
    {
        if (app is null)
        {
            return null;
        }

        var conditions = CreateConditions(decision, flow, domains);
        var lifetime = decision.Lifetime;
        DateTimeOffset? expiresAt = null;
        ProcessRef? boundProcess = null;

        switch (lifetime)
        {
            case RuleLifetime.Once when flow.Origin == FlowOrigin.Pending:
                return null;

            case RuleLifetime.Once:
                // 連接已被阻斷（Windows 用戶態）：轉成限定 IP + 端口的短時規則，讓應用重試時能連上
                lifetime = RuleLifetime.Timed;
                expiresAt = now + onceFallbackDuration;
                conditions = conditions with
                {
                    Remote = AddressTarget.Single(flow.Remote.Address),
                    RemotePort = PortRange.Single((ushort)flow.Remote.Port),
                    Protocol = flow.Protocol,
                };
                break;

            case RuleLifetime.Timed:
                expiresAt = now + (decision.Duration ?? DefaultTimedDuration);
                break;

            case RuleLifetime.UntilProcessExit when flow.Process is { } process:
                boundProcess = process;
                break;

            case RuleLifetime.UntilProcessExit:
                // 拿不到進程信息時無法跟蹤退出，退化為「直到重啟」
                lifetime = RuleLifetime.UntilRestart;
                break;
        }

        return new Rule
        {
            Id = Guid.CreateVersion7(now),
            ProfileId = profileId,
            App = AppMatcherFactory.Create(app),
            Conditions = conditions,
            Action = decision.Action,
            Lifetime = lifetime,
            ExpiresAt = expiresAt,
            BoundProcess = boundProcess,
            Source = RuleSource.Prompt,
            CreatedAt = now,
            UpdatedAt = now,
        };
    }
}
