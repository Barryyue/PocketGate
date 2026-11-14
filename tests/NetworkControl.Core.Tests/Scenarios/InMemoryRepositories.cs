using System.Collections.Concurrent;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Profiles;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Tests.Scenarios;

internal sealed class InMemoryRuleRepository : IRuleRepository
{
    public ConcurrentDictionary<Guid, Rule> Rules { get; } = new();

    public ConcurrentDictionary<Guid, RuleEndReason> History { get; } = new();

    public Task<IReadOnlyList<StoredRule>> LoadAllAsync(CancellationToken cancellationToken) =>
        Task.FromResult<IReadOnlyList<StoredRule>>(Rules.Values.Select(r => new StoredRule(r)).ToList());

    public Task UpsertAsync(Rule rule, CancellationToken cancellationToken)
    {
        Rules[rule.Id] = rule;
        return Task.CompletedTask;
    }

    public Task DeleteAsync(IReadOnlyCollection<Rule> rules, RuleEndReason reason, DateTimeOffset endedAt, CancellationToken cancellationToken)
    {
        foreach (var rule in rules)
        {
            Rules.TryRemove(rule.Id, out _);
            History[rule.Id] = reason;
        }

        return Task.CompletedTask;
    }

    public Task RecordHitsAsync(IReadOnlyCollection<RuleHitDelta> hits, CancellationToken cancellationToken) => Task.CompletedTask;
}

internal sealed class InMemoryProfileRepository : IProfileRepository
{
    public ConcurrentDictionary<Guid, Profile> Profiles { get; } = new();

    public Task<IReadOnlyList<Profile>> LoadAllAsync(CancellationToken cancellationToken) =>
        Task.FromResult<IReadOnlyList<Profile>>(Profiles.Values.OrderBy(p => p.SortOrder).ToArray());

    public Task UpsertAsync(Profile profile, CancellationToken cancellationToken)
    {
        Profiles[profile.Id] = profile;
        return Task.CompletedTask;
    }

    public Task DeleteAsync(Guid id, CancellationToken cancellationToken)
    {
        Profiles.TryRemove(id, out _);
        return Task.CompletedTask;
    }
}

internal sealed class InMemorySettingsRepository : ISettingsRepository
{
    public UserSettings? Settings { get; set; }

    public RuntimeStateRecord? State { get; set; }

    public Task<UserSettings?> LoadUserSettingsAsync(CancellationToken cancellationToken) => Task.FromResult(Settings);

    public Task SaveUserSettingsAsync(UserSettings settings, CancellationToken cancellationToken)
    {
        Settings = settings;
        return Task.CompletedTask;
    }

    public Task<RuntimeStateRecord?> LoadRuntimeStateAsync(CancellationToken cancellationToken) => Task.FromResult(State);

    public Task SaveRuntimeStateAsync(RuntimeStateRecord state, CancellationToken cancellationToken)
    {
        State = state;
        return Task.CompletedTask;
    }
}

internal sealed class InMemoryAppRepository : IAppRepository
{
    private readonly ConcurrentDictionary<AppKey, AppIdentity> _apps = new();
    private long _nextId;

    public ValueTask<AppIdentity> UpsertAsync(AppKey key, AppInspection inspection, CancellationToken cancellationToken)
    {
        var app = _apps.AddOrUpdate(
            key,
            k => Create(Interlocked.Increment(ref _nextId), k, inspection),
            (k, existing) => Create(existing.AppId, k, inspection));
        return ValueTask.FromResult(app);
    }

    public ValueTask<AppIdentity?> GetAsync(long appId, CancellationToken cancellationToken) =>
        ValueTask.FromResult(_apps.Values.FirstOrDefault(a => a.AppId == appId));

    private static AppIdentity Create(long id, AppKey key, AppInspection inspection) => new()
    {
        AppId = id,
        Path = key.Path,
        ServiceName = key.ServiceName,
        PackageId = key.PackageId,
        DisplayName = inspection.DisplayName,
        Signature = inspection.Signature,
        Sha256 = inspection.Sha256,
    };
}
