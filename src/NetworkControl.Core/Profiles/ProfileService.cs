using Microsoft.Extensions.Logging;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;

namespace NetworkControl.Core.Profiles;

/// <summary>【存儲實現】配置文件的持久化。</summary>
public interface IProfileRepository
{
    Task<IReadOnlyList<Profile>> LoadAllAsync(CancellationToken cancellationToken);

    Task UpsertAsync(Profile profile, CancellationToken cancellationToken);

    /// <summary>刪除配置文件；專屬規則由外鍵 ON DELETE CASCADE 一併刪除。</summary>
    Task DeleteAsync(Guid id, CancellationToken cancellationToken);
}

/// <summary>切換配置文件的原因。</summary>
public enum ProfileSwitchReason : byte
{
    User = 0,
    Network = 1,
}

/// <summary>
/// 配置文件的應用服務：維護配置文件列表、切換當前配置文件、複製專屬規則。
/// 切換會寫入 <see cref="RuntimeStateService"/>，由它通知規則集重新發布。
/// </summary>
public sealed partial class ProfileService(
    IProfileRepository repository,
    RuntimeStateService state,
    RuleService rules,
    TimeProvider time,
    ILogger<ProfileService> logger)
{
    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private Profile[] _profiles = [];

    public event EventHandler? Changed;

    public IReadOnlyList<Profile> All => Volatile.Read(ref _profiles);

    public Profile Active =>
        All.FirstOrDefault(p => p.Id == state.ActiveProfileId)
        ?? All.FirstOrDefault(p => p.Id == Profile.DefaultId)
        ?? All[0];

    public async Task InitializeAsync(CancellationToken cancellationToken)
    {
        var loaded = await repository.LoadAllAsync(cancellationToken);
        var profiles = loaded.ToList();
        if (profiles.Count == 0)
        {
            var fallback = new Profile
            {
                Id = Profile.DefaultId,
                Name = "默認",
                Mode = FilterMode.Ask,
                SortOrder = 0,
            };
            await repository.UpsertAsync(fallback, cancellationToken);
            profiles.Add(fallback);
        }

        Volatile.Write(ref _profiles, Sort(profiles));
        if (All.All(p => p.Id != state.ActiveProfileId))
        {
            var active = Active;
            await state.ActivateProfileAsync(active.Id, active.Mode, cancellationToken);
        }
    }

    public async Task<Profile> CreateAsync(string name, FilterMode mode, Guid? copyRulesFrom, CancellationToken cancellationToken)
    {
        var normalized = NormalizeName(name);
        var validatedMode = ValidateMode(mode);
        Profile profile;
        await _writeGate.WaitAsync(cancellationToken);
        try
        {
            EnsureUniqueName(normalized, except: null);
            var sortOrder = All.Count == 0 ? 0 : All.Max(p => p.SortOrder) + 1;
            profile = new Profile
            {
                Id = Guid.CreateVersion7(time.GetUtcNow()),
                Name = normalized,
                Mode = validatedMode,
                SortOrder = sortOrder,
            };
            await repository.UpsertAsync(profile, cancellationToken);
            Volatile.Write(ref _profiles, Sort([.. All, profile]));
        }
        finally
        {
            _writeGate.Release();
        }

        if (copyRulesFrom is { } source)
        {
            var copies = rules.All
                .Where(r => r.ProfileId == source && r.Lifetime is RuleLifetime.Permanent or RuleLifetime.Timed)
                .Select(r => r with
                {
                    Id = Guid.CreateVersion7(time.GetUtcNow()),
                    ProfileId = profile.Id,
                    BoundProcess = null,
                    CreatedAt = time.GetUtcNow(),
                    UpdatedAt = time.GetUtcNow(),
                })
                .ToArray();
            foreach (var copy in copies)
            {
                await rules.AddAsync(copy, cancellationToken);
            }

            if (copies.Length > 0)
            {
                LogCopiedRules(logger, copies.Length, profile.Id);
            }
        }

        Changed?.Invoke(this, EventArgs.Empty);
        return profile;
    }

    public async Task<Profile> UpdateAsync(Profile profile, CancellationToken cancellationToken)
    {
        var normalized = NormalizeName(profile.Name);
        var validatedMode = ValidateMode(profile.Mode);
        Profile updated;
        await _writeGate.WaitAsync(cancellationToken);
        try
        {
            var existing = Find(profile.Id) ?? throw new KeyNotFoundException($"配置文件 {profile.Id} 不存在。");
            EnsureUniqueName(normalized, existing.Id);
            updated = existing with
            {
                Name = normalized,
                Mode = validatedMode,
                SortOrder = Math.Max(0, profile.SortOrder),
            };
            await repository.UpsertAsync(updated, cancellationToken);
            Volatile.Write(ref _profiles, Sort(All.Select(p => p.Id == updated.Id ? updated : p)));
        }
        finally
        {
            _writeGate.Release();
        }

        if (updated.Id == state.ActiveProfileId && state.Mode != FilterMode.BlockAll)
        {
            await state.SetModeAsync(updated.Mode, cancellationToken);
        }

        Changed?.Invoke(this, EventArgs.Empty);
        return updated;
    }

    public async Task DeleteAsync(Guid id, CancellationToken cancellationToken)
    {
        if (id == Profile.DefaultId)
        {
            throw new ArgumentException("默認配置文件不能刪除。", nameof(id));
        }

        if (id == state.ActiveProfileId)
        {
            throw new ArgumentException("不能刪除當前配置文件，請先切換到其他配置文件。", nameof(id));
        }

        await _writeGate.WaitAsync(cancellationToken);
        try
        {
            var existing = Find(id) ?? throw new KeyNotFoundException($"配置文件 {id} 不存在。");
            var ruleIds = rules.All.Where(r => r.ProfileId == id).Select(r => r.Id).ToArray();
            if (ruleIds.Length > 0)
            {
                await rules.RemoveAsync(ruleIds, RuleEndReason.Deleted, cancellationToken);
            }

            await repository.DeleteAsync(id, cancellationToken);
            Volatile.Write(ref _profiles, All.Where(p => p.Id != id).ToArray());
        }
        finally
        {
            _writeGate.Release();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public async Task ActivateAsync(Guid id, ProfileSwitchReason reason, CancellationToken cancellationToken)
    {
        var profile = Find(id) ?? throw new KeyNotFoundException($"配置文件 {id} 不存在。");
        await state.ActivateProfileAsync(profile.Id, profile.Mode, cancellationToken);
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public Profile? Find(Guid id) => All.FirstOrDefault(p => p.Id == id);

    private static Profile[] Sort(IEnumerable<Profile> profiles) =>
        profiles.OrderBy(p => p.SortOrder).ThenBy(p => p.Name, StringComparer.CurrentCulture).ToArray();

    private void EnsureUniqueName(string name, Guid? except)
    {
        if (All.Any(p => p.Id != except && string.Equals(p.Name, name, StringComparison.OrdinalIgnoreCase)))
        {
            throw new ArgumentException($"配置文件「{name}」已存在。", nameof(name));
        }
    }

    private static string NormalizeName(string name)
    {
        var trimmed = name.Trim();
        if (trimmed.Length is 0 or > 40)
        {
            throw new ArgumentException("配置文件名稱必須是 1–40 個字元。", nameof(name));
        }

        return trimmed;
    }

    private static FilterMode ValidateMode(FilterMode mode) => mode switch
    {
        FilterMode.Ask or FilterMode.Whitelist or FilterMode.Blacklist => mode,
        _ => throw new ArgumentException("配置文件模式只能是詢問、白名單或黑名單。", nameof(mode)),
    };

    [LoggerMessage(Level = LogLevel.Information, Message = "已從來源配置文件複製 {Count} 條規則到新配置文件 {ProfileId}")]
    private static partial void LogCopiedRules(ILogger logger, int count, Guid profileId);
}
