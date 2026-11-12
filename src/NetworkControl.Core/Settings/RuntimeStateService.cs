using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Settings;

/// <summary>
/// 運行時狀態：當前模式、配置文件、勿擾。模式切換即時生效並持久化（服務重啟後保持）。
/// </summary>
public sealed class RuntimeStateService(ISettingsRepository repository, TimeProvider time)
{
    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private RuntimeStateRecord _state = new();

    public event EventHandler? Changed;

    public FilterMode Mode => Volatile.Read(ref _state).Mode;

    public Guid ActiveProfileId => Volatile.Read(ref _state).ActiveProfileId;

    public DateTimeOffset? DoNotDisturbUntil => Volatile.Read(ref _state).DoNotDisturbUntil;

    public bool IsDoNotDisturb => DoNotDisturbUntil is { } until && until > time.GetUtcNow();

    public RuntimeStateRecord Snapshot => Volatile.Read(ref _state);

    public async Task InitializeAsync(CancellationToken cancellationToken)
    {
        var loaded = await repository.LoadRuntimeStateAsync(cancellationToken);
        Volatile.Write(ref _state, loaded ?? new RuntimeStateRecord());
    }

    public Task SetModeAsync(FilterMode mode, CancellationToken cancellationToken) =>
        UpdateAsync(s =>
        {
            if (s.Mode == mode)
            {
                return s;
            }

            return mode == FilterMode.BlockAll
                ? s with { Mode = mode, ModeBeforeBlockAll = s.Mode }
                : s with { Mode = mode, ModeBeforeBlockAll = null };
        }, cancellationToken);

    /// <summary>從「全部阻斷」恢復到之前的模式（FR-MOD-04）。</summary>
    public Task RestoreFromBlockAllAsync(CancellationToken cancellationToken) =>
        UpdateAsync(s => s.Mode == FilterMode.BlockAll
            ? s with { Mode = s.ModeBeforeBlockAll ?? FilterMode.Ask, ModeBeforeBlockAll = null }
            : s, cancellationToken);

    /// <summary>設置勿擾截止時間；傳 null 關閉勿擾。</summary>
    public Task SetDoNotDisturbAsync(DateTimeOffset? until, CancellationToken cancellationToken) =>
        UpdateAsync(s => s with { DoNotDisturbUntil = until }, cancellationToken);

    private async Task UpdateAsync(Func<RuntimeStateRecord, RuntimeStateRecord> update, CancellationToken cancellationToken)
    {
        await _writeGate.WaitAsync(cancellationToken);
        try
        {
            var current = Volatile.Read(ref _state);
            var next = update(current);
            if (next == current)
            {
                return;
            }

            await repository.SaveRuntimeStateAsync(next, cancellationToken);
            Volatile.Write(ref _state, next);
        }
        finally
        {
            _writeGate.Release();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }
}
