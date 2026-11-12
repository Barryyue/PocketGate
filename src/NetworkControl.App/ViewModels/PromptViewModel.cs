using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Google.Protobuf.WellKnownTypes;
using NetworkControl.App.Localization;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>彈窗中的時效選項。</summary>
public sealed record LifetimeValue(string Key, Proto.Lifetime Lifetime, TimeSpan? Duration);

/// <summary>用戶在彈窗中的決策。</summary>
public sealed record PromptAnswer(string PromptId, Proto.AnswerRequest Request);

/// <summary>
/// 詢問彈窗（需求文檔 §6.3.1）。倒計時以服務端的截止時間為準；
/// 彈窗出現後 1 秒內不響應輸入（FR-PRM-07），避免打字時誤觸。
/// </summary>
public sealed partial class PromptViewModel : LocalizedViewModel, IDisposable
{
    /// <summary>防誤觸的輸入屏蔽時長。</summary>
    public static readonly TimeSpan InputGuard = TimeSpan.FromSeconds(1);

    // 記住本次會話中上一次選擇的範圍和時效，作為下一個彈窗的默認值（FR-PRM-10）
    private static Proto.Scope _lastScope = Proto.Scope.AnyConnection;
    private static string _lastLifetimeKey = "permanent";

    private readonly DispatcherTimer _ticker;
    private readonly DispatcherTimer _guard;
    private readonly Action<PromptAnswer> _answer;
    private readonly Action<string> _pauseTimer;
    private Proto.Prompt _prompt;
    private bool _answered;

    public PromptViewModel(
        Proto.Prompt prompt,
        Proto.VerdictAction defaultAction,
        int queueLength,
        DisplayText text,
        Action<PromptAnswer> answer,
        Action<string> pauseTimer)
        : base(text)
    {
        _prompt = prompt;
        _answer = answer;
        _pauseTimer = pauseTimer;
        DefaultAction = defaultAction;
        QueueLength = queueLength;

        LifetimeOptions =
        [
            Lifetime("once", Proto.Lifetime.Once, null, CommonLangResourceKind.LifetimeOnce),
            Lifetime("exit", Proto.Lifetime.UntilProcessExit, null, CommonLangResourceKind.LifetimeUntilExit),
            Lifetime("5m", Proto.Lifetime.Timed, TimeSpan.FromMinutes(5), CommonLangResourceKind.Lifetime5Minutes),
            Lifetime("15m", Proto.Lifetime.Timed, TimeSpan.FromMinutes(15), CommonLangResourceKind.Lifetime15Minutes),
            Lifetime("1h", Proto.Lifetime.Timed, TimeSpan.FromHours(1), CommonLangResourceKind.Lifetime1Hour),
            Lifetime("restart", Proto.Lifetime.UntilRestart, null, CommonLangResourceKind.LifetimeUntilRestart),
            Lifetime("permanent", Proto.Lifetime.Permanent, null, CommonLangResourceKind.LifetimePermanent),
        ];
        SelectedLifetime = LifetimeOptions.FirstOrDefault(o => o.Value.Key == _lastLifetimeKey) ?? LifetimeOptions[^1];
        ScopeOptions = [];
        BuildScopeOptions();

        _ticker = new DispatcherTimer(TimeSpan.FromMilliseconds(250), DispatcherPriority.Normal, (_, _) => OnPropertyChanged(nameof(CountdownText)));
        _ticker.Start();

        _guard = new DispatcherTimer(InputGuard, DispatcherPriority.Normal, (_, _) =>
        {
            IsInputEnabled = true;
            _guard!.Stop();
        });
        _guard.Start();
    }

    /// <summary>彈窗應被關閉（已答覆）。</summary>
    public event EventHandler? CloseRequested;

    public string PromptId => _prompt.Id;

    public Proto.VerdictAction DefaultAction { get; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasQueue), nameof(QueueText))]
    public partial int QueueLength { get; set; }

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(AllowCommand), nameof(DenyCommand))]
    public partial bool IsInputEnabled { get; set; }

    [ObservableProperty]
    public partial bool ShowDetails { get; set; }

    [ObservableProperty]
    public partial bool ApplyToAll { get; set; } = true;

    public IReadOnlyList<Choice<LifetimeValue>> LifetimeOptions { get; }

    [ObservableProperty]
    public partial IReadOnlyList<Choice<Proto.Scope>> ScopeOptions { get; private set; }

    [ObservableProperty]
    public partial Choice<Proto.Scope>? SelectedScope { get; set; }

    [ObservableProperty]
    public partial Choice<LifetimeValue> SelectedLifetime { get; set; }

    [ObservableProperty]
    public partial string? SelectedDomain { get; set; }

    private Proto.PromptFlow? Head => _prompt.Flows.Count > 0 ? _prompt.Flows[0] : null;

    // ---------- 應用 ----------

    public string AppName => _prompt.App?.DisplayName is { Length: > 0 } name ? name
        : DisplayText.FileName(_prompt.ExecutablePath) is { Length: > 0 } file ? file
        : Loc.Get(CommonLangResourceKind.UnknownApp);

    public string AppPath => _prompt.App?.Path ?? _prompt.ExecutablePath;

    public bool IsSigned => _prompt.App?.HasSignerId == true;

    public string SignatureText => IsSigned
        ? Loc.Format(PromptLangResourceKind.Signed, _prompt.App!.SignerId)
        : Loc.Get(PromptLangResourceKind.Unsigned);

    public string SignatureColor => IsSigned ? "success" : "warning";

    public bool IsTamperAlert => _prompt.IsTamperAlert;

    public bool ShowBlockedHint => !_prompt.IsConnectionHeld;

    // ---------- 目標 ----------

    public string VerbText => Loc.Get(Head?.Direction == Proto.Direction.Inbound ? PromptLangResourceKind.WantsToAccept : PromptLangResourceKind.WantsToConnect);

    public IReadOnlyList<string> Domains => Head?.Domains ?? (IReadOnlyList<string>)[];

    public bool HasDomain => Domains.Count > 0;

    public bool HasMultipleDomains => Domains.Count > 1;

    public string TargetText => HasDomain ? Domains[0] : DisplayText.Endpoint(Head?.Remote);

    public string TargetDetail => Head is { } f
        ? string.Join("  ·  ", new[]
        {
            HasDomain ? f.Remote.Address : null,
            $"{DisplayText.Protocol(f.Protocol)} {f.Remote.Port}",
            Text.Direction(f.Direction),
            Text.Zone(f.RemoteZone),
        }.Where(s => !string.IsNullOrEmpty(s)))
        : "";

    public int PendingCount => _prompt.Flows.Count;

    public bool HasMorePending => PendingCount > 1;

    public string MorePendingText => Loc.Format(PromptLangResourceKind.MoreConnections, PendingCount - 1);

    public bool HasQueue => QueueLength > 0;

    public string QueueText => Loc.Format(PromptLangResourceKind.Queue, QueueLength);

    // ---------- 詳情 ----------

    public string PidText => _prompt.HasPid ? _prompt.Pid.ToString(Loc.Culture) : "—";

    public string LocalText => DisplayText.Endpoint(Head?.Local);

    public IReadOnlyList<string> PendingTargets => _prompt.Flows
        .Select(f => $"{(f.Domains.Count > 0 ? f.Domains[0] + " " : "")}{DisplayText.Endpoint(f.Remote)} {DisplayText.Protocol(f.Protocol)}")
        .Distinct()
        .ToArray();

    // ---------- 倒計時 ----------

    public string CountdownText
    {
        get
        {
            var remaining = _prompt.Deadline.ToDateTimeOffset() - DateTimeOffset.UtcNow;
            var seconds = Math.Max(0, (int)Math.Ceiling(remaining.TotalSeconds));
            return Loc.Format(PromptLangResourceKind.Countdown, seconds, Text.Action(DefaultAction));
        }
    }

    /// <summary>服務端推送了更新（合併了新連接、延長了截止時間）。</summary>
    public void Update(Proto.Prompt prompt)
    {
        var hadDomain = HasDomain;
        _prompt = prompt;
        if (hadDomain != HasDomain)
        {
            BuildScopeOptions();
        }

        OnPropertyChanged(string.Empty);
    }

    public void Dispose()
    {
        _ticker.Stop();
        _guard.Stop();
    }

    /// <summary>鼠標懸停時請求暫停倒計時。</summary>
    public void RequestPause() => _pauseTimer(PromptId);

    protected override void OnLanguageChanged()
    {
        foreach (var choice in LifetimeOptions.Concat<Choice>(ScopeOptions))
        {
            choice.Refresh();
        }

        base.OnLanguageChanged();
    }

    [RelayCommand(CanExecute = nameof(IsInputEnabled))]
    private void Allow() => Submit(Proto.VerdictAction.Allow);

    [RelayCommand(CanExecute = nameof(IsInputEnabled))]
    private void Deny() => Submit(Proto.VerdictAction.Deny);

    [RelayCommand]
    private void ToggleDetails()
    {
        ShowDetails = !ShowDetails;
        if (ShowDetails)
        {
            // 查看詳情時暫停倒計時（服務端延長截止時間）
            _pauseTimer(PromptId);
        }
    }

    private void Submit(Proto.VerdictAction action)
    {
        if (_answered || !IsInputEnabled)
        {
            return;
        }

        _answered = true;
        var scope = SelectedScope?.Value ?? Proto.Scope.AnyConnection;
        var lifetime = SelectedLifetime.Value;
        _lastScope = scope;
        _lastLifetimeKey = lifetime.Key;

        var request = new Proto.AnswerRequest
        {
            PromptId = PromptId,
            Action = action,
            Scope = scope,
            Lifetime = lifetime.Lifetime,
            ApplyToAllFlows = ApplyToAll,
        };
        if (lifetime.Duration is { } duration)
        {
            request.Duration = Duration.FromTimeSpan(duration);
        }

        if (scope == Proto.Scope.Domain && (SelectedDomain ?? Domains.FirstOrDefault()) is { } domain)
        {
            request.SelectedDomain = domain;
        }

        _answer(new PromptAnswer(PromptId, request));
        CloseRequested?.Invoke(this, EventArgs.Empty);
    }

    private void BuildScopeOptions()
    {
        var options = new List<Choice<Proto.Scope>> { Scope(Proto.Scope.AnyConnection, PromptLangResourceKind.ScopeAny) };
        if (HasDomain)
        {
            options.Add(Scope(Proto.Scope.Domain, PromptLangResourceKind.ScopeDomain));
        }

        options.Add(Scope(Proto.Scope.Address, PromptLangResourceKind.ScopeAddress));
        options.Add(Scope(Proto.Scope.AddressAndPort, PromptLangResourceKind.ScopeAddressPort));
        options.Add(Scope(Proto.Scope.Port, PromptLangResourceKind.ScopePort));

        ScopeOptions = options;
        SelectedScope = options.FirstOrDefault(o => o.Value == _lastScope) ?? options[0];
        SelectedDomain = Domains.FirstOrDefault();
    }

    private Choice<Proto.Scope> Scope(Proto.Scope scope, PromptLangResourceKind key) => new(scope, () => Loc.Get(key));

    private Choice<LifetimeValue> Lifetime(string key, Proto.Lifetime lifetime, TimeSpan? duration, CommonLangResourceKind title) =>
        new(new LifetimeValue(key, lifetime, duration), () => Loc.Get(title));
}
