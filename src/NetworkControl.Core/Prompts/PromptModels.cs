using System.Net;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Prompts;

/// <summary>彈窗中一條待決連接的快照。</summary>
public sealed record PromptFlowSnapshot(
    ulong FlowId,
    Direction Direction,
    L4Protocol Protocol,
    IPEndPoint Local,
    IPEndPoint Remote,
    IReadOnlyList<string> Domains,
    DateTimeOffset Timestamp);

/// <summary>彈窗快照，推送給 UI。</summary>
public sealed record PromptSnapshot(
    Guid Id,
    AppIdentity? App,
    string? ExecutablePath,
    ProcessRef? Process,
    string? TargetUserId,
    IReadOnlyList<PromptFlowSnapshot> Flows,
    DateTimeOffset CreatedAt,
    DateTimeOffset Deadline,
    bool IsTamperAlert,
    bool IsConnectionHeld);

public enum PromptEventKind : byte
{
    Added = 0,
    Updated = 1,
    Removed = 2,
}

public sealed record PromptEvent(PromptEventKind Kind, PromptSnapshot Prompt);

/// <summary>彈窗訂閱者（一個 UI 會話）。</summary>
public sealed record PromptSubscriber(string? UserId, bool IsAdmin);

/// <summary>回應彈窗的結果。</summary>
public sealed record PromptAnswerResult(bool Found, Guid? CreatedRuleId, int ResolvedFlows, int RemainingFlows);
