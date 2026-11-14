using System.Collections.ObjectModel;
using System.Globalization;
using AtomUI.Desktop.Controls;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using NetworkControl.App.Localization;
using NetworkControl.App.Services;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>規則列表中的一行。顯示文本在 getter 中按當前語言計算。</summary>
public sealed partial class RuleItemViewModel : ViewModelBase
{
    private readonly DisplayText _text;
    private readonly Func<RuleItemViewModel, bool, Task> _setEnabled;
    private bool _suppressToggle;

    private readonly IReadOnlyDictionary<string, string> _profileNames;

    public RuleItemViewModel(Proto.RuleInfo info, DisplayText text, Func<RuleItemViewModel, bool, Task> setEnabled, IReadOnlyDictionary<string, string> profileNames)
    {
        _text = text;
        _setEnabled = setEnabled;
        _profileNames = profileNames;
        Info = info;
        Update(info);
    }

    public Proto.RuleInfo Info { get; private set; }

    public string Id => Info.Id;

    [ObservableProperty]
    public partial bool IsEnabled { get; set; }

    public bool IsBuiltin => Info.Source == Proto.RuleSource.Builtin;

    public bool CanDelete => !IsBuiltin;

    public string AppName => Info.Definition.App is { } app
        ? DisplayText.FileName(app.PathPattern)
        : _text.Localization.Get(CommonLangResourceKind.AnyApp);

    public string AppPath => Info.Definition.App?.PathPattern ?? "";

    public string ActionText => _text.Action(Info.Definition.Action);

    public string ActionColor => Info.Definition.Action == Proto.VerdictAction.Allow ? "success" : "error";

    public string ConditionsText => _text.Conditions(Info.Definition.Conditions);

    public string LifetimeText => _text.Lifetime(Info.Definition.Lifetime, Info.Definition.ExpiresAt);

    public string SourceText => _text.Source(Info.Source);

    public string ProfileText => Info.Definition.ProfileId is { } profileId
        ? _profileNames.TryGetValue(profileId, out var name) ? name : profileId
        : _text.Localization.Get(RulesLangResourceKind.ProfileFilterShared);

    public string HitCount => Info.HitCount.ToString("N0", _text.Localization.Culture);

    public string UpdatedText => _text.DateTime(Info.UpdatedAt, "g");

    public bool Matches(string keyword) =>
        AppPath.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || AppName.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || ConditionsText.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || ProfileText.Contains(keyword, StringComparison.OrdinalIgnoreCase)
        || (Info.Definition.HasNote && Info.Definition.Note.Contains(keyword, StringComparison.OrdinalIgnoreCase));

    public void Update(Proto.RuleInfo info)
    {
        Info = info;
        _suppressToggle = true;
        IsEnabled = info.Definition.Enabled;
        _suppressToggle = false;
        Refresh();
    }

    public void Refresh() => OnPropertyChanged(string.Empty);

    partial void OnIsEnabledChanged(bool value)
    {
        if (!_suppressToggle)
        {
            _ = _setEnabled(this, value);
        }
    }
}

/// <summary>規則管理頁。訂閱服務端的規則變更流，列表實時更新。</summary>
public sealed partial class RulesViewModel : PageViewModel
{
    private readonly ServiceConnection _connection;
    private readonly ProfileCatalog _catalog;
    private readonly RuleEditing _editing;
    private readonly List<RuleItemViewModel> _all = [];
    private readonly Dictionary<string, string> _profileNames = [];
    private CancellationTokenSource? _watch;

    public RulesViewModel(ServiceConnection connection, ProfileCatalog catalog, RuleEditing editing, DisplayText text)
        : base(text)
    {
        _connection = connection;
        _catalog = catalog;
        catalog.Changed += OnProfilesChanged;
        connection.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(ServiceConnection.Status))
            {
                OnPropertyChanged(nameof(CanModify));
            }
        };
        _editing = editing;
        connection.Connected += (_, clients) => _ = WatchAsync(clients);
    }

    public override string Key => "rules";

    public bool CanModify => _connection.CanModify;

    /// <summary>AtomUI 表格的行描述：規則 ID 作為穩定的行鍵（選中、增量更新依賴它）。</summary>
    private static readonly DataGridLocalSourceDescriptor<RuleItemViewModel> RowDescriptor =
        DataGridLocalSourceDescriptor.For<RuleItemViewModel>(static row => DataGridRowKey.FromString(row.Id));

    /// <summary>經過搜索和篩選後顯示的規則。</summary>
    public ObservableCollection<RuleItemViewModel> Rules { get; } = [];

    /// <summary>
    /// 表格數據源。AtomUI 6.2 的 DataGrid.ItemsSource 類型是 IDataGridSource，不能直接綁定普通集合
    /// （綁定會在運行時靜默失敗，表格為空）；本地數據源會跟蹤 <see cref="Rules"/> 的變化。
    /// </summary>
    public IDataGridSource RulesSource => _rulesSource ??= DataGridLocalSource.Create(Rules, RowDescriptor);

    private DataGridLocalSource<RuleItemViewModel>? _rulesSource;

    [ObservableProperty]
    public partial string Keyword { get; set; } = "";

    /// <summary>動作篩選：0 全部，1 允許，2 拒絕。</summary>
    [ObservableProperty]
    public partial int ActionFilter { get; set; }

    public bool IsEmpty => Rules.Count == 0;

    /// <summary>來源篩選選項（null 表示全部來源）。</summary>
    public IReadOnlyList<Choice<Proto.RuleSource?>> Sources => _sources ??=
    [
        new Choice<Proto.RuleSource?>(null, () => Loc.Get(RulesLangResourceKind.SourceAll)),
        .. new[] { Proto.RuleSource.User, Proto.RuleSource.Prompt, Proto.RuleSource.Import, Proto.RuleSource.Builtin }
            .Select(s => new Choice<Proto.RuleSource?>(s, () => Text.Source(s))),
    ];

    private IReadOnlyList<Choice<Proto.RuleSource?>>? _sources;

    [ObservableProperty]
    public partial Choice<Proto.RuleSource?>? SourceFilter { get; set; }

    /// <summary>配置文件篩選：null 全部，空字串共享規則，其餘為配置文件 ID。</summary>
    public ObservableCollection<Choice<string?>> ProfileFilters { get; } = [];

    [ObservableProperty]
    public partial Choice<string?>? ProfileFilter { get; set; }

    public string CountSummary => Loc.Format(RulesLangResourceKind.CountSummary, Rules.Count);

    public string CountText => Rules.Count.ToString(CultureInfo.CurrentCulture);

    /// <summary>服務支持配置文件時才顯示配置文件列和篩選（R2）。</summary>
    public bool ShowProfileFilter => _catalog.IsAvailable;

    public override async Task OnActivatedAsync()
    {
        await _catalog.RefreshAsync();
        RebuildProfileFilters();
        await RefreshAsync();
    }

    public override bool ApplyNavigation(object parameter)
    {
        if (parameter is not RulesNavigation navigation)
        {
            return false;
        }

        ActionFilter = 0;
        SourceFilter = null;
        ProfileFilter = ProfileFilters.FirstOrDefault();
        Keyword = navigation.Keyword;
        return false; // 仍由主窗口調用 OnActivatedAsync 刷新規則列表，刷新後按關鍵字篩選
    }

    [RelayCommand]
    private Task RefreshAsync() => RunAsync(async () =>
    {
        var response = await _connection.RequireClients().Rules.ListAsync(new Proto.ListRulesRequest());
        _all.Clear();
        _all.AddRange(response.Rules.Select(r => new RuleItemViewModel(r, Text, SetEnabledAsync, _profileNames)));
        ApplyFilter();
    });

    [RelayCommand]
    private Task AddAsync() => _editing.CreateAsync();

    [RelayCommand]
    private Task EditAsync(RuleItemViewModel item) => _editing.EditAsync(item.Info);

    [RelayCommand]
    private Task DeleteAsync(RuleItemViewModel item) => _editing.DeleteAsync(item.Id);

    partial void OnKeywordChanged(string value) => ApplyFilter();

    partial void OnActionFilterChanged(int value) => ApplyFilter();

    partial void OnProfileFilterChanged(Choice<string?>? value) => ApplyFilter();

    protected override void OnLanguageChanged()
    {
        foreach (var item in _all)
        {
            item.Refresh();
        }

        foreach (var source in Sources)
        {
            source.Refresh();
        }

        foreach (var profile in ProfileFilters)
        {
            profile.Refresh();
        }

        base.OnLanguageChanged();
    }

    private Task SetEnabledAsync(RuleItemViewModel item, bool enabled) => RunAsync(async () =>
        await _connection.RequireClients().Rules.SetEnabledAsync(new Proto.SetRuleEnabledRequest { Id = item.Id, Enabled = enabled }));

    /// <summary>配置文件列表變化（新增 / 改名 / 刪除、R9 的版本推送）時重建篩選。</summary>
    private void OnProfilesChanged(object? sender, EventArgs e)
    {
        RebuildProfileFilters();
        foreach (var item in _all)
        {
            item.Refresh();
        }

        ApplyFilter();
    }

    private void RebuildProfileFilters()
    {
        _profileNames.Clear();
        foreach (var profile in _catalog.Profiles)
        {
            _profileNames[profile.Id] = profile.Name;
        }

        var selected = ProfileFilter?.Value;
        ProfileFilters.Clear();
        ProfileFilters.Add(new Choice<string?>(null, () => Loc.Get(RulesLangResourceKind.ProfileFilterAll)));
        ProfileFilters.Add(new Choice<string?>("", () => Loc.Get(RulesLangResourceKind.ProfileFilterShared)));
        foreach (var profile in _catalog.Profiles)
        {
            ProfileFilters.Add(new Choice<string?>(profile.Id, () => profile.Name));
        }

        ProfileFilter = _catalog.IsAvailable
            ? ProfileFilters.FirstOrDefault(c => c.Value == selected) ?? ProfileFilters[0]
            : ProfileFilters[0];
        OnPropertyChanged(nameof(ShowProfileFilter));
    }

    private void ApplyFilter()
    {
        var keyword = Keyword.Trim();
        var filtered = _all
            .Where(r => ActionFilter switch
            {
                1 => r.Info.Definition.Action == Proto.VerdictAction.Allow,
                2 => r.Info.Definition.Action == Proto.VerdictAction.Deny,
                _ => true,
            })
            .Where(r => SourceFilter?.Value is not { } source || r.Info.Source == source)
            .Where(r => ProfileFilter?.Value is not { } profile || (profile.Length == 0
                ? !r.Info.Definition.HasProfileId
                : r.Info.Definition.ProfileId == profile))
            .Where(r => keyword.Length == 0 || r.Matches(keyword))
            .OrderBy(r => r.IsBuiltin)
            .ThenByDescending(r => r.Info.UpdatedAt?.ToDateTimeOffset() ?? DateTimeOffset.MinValue)
            .ToList();

        Rules.Clear();
        foreach (var item in filtered)
        {
            Rules.Add(item);
        }

        OnPropertyChanged(nameof(IsEmpty));
        OnPropertyChanged(nameof(CountText));
        OnPropertyChanged(nameof(CountSummary));
    }

    partial void OnSourceFilterChanged(Choice<Proto.RuleSource?>? value) => ApplyFilter();

    private async Task WatchAsync(IpcClients clients)
    {
        if (_watch is not null)
        {
            await _watch.CancelAsync();
            _watch.Dispose();
        }

        _watch = new CancellationTokenSource();
        await RefreshAsync();

        try
        {
            using var call = clients.Rules.Watch(new Google.Protobuf.WellKnownTypes.Empty(), cancellationToken: _watch.Token);
            await foreach (var change in call.ResponseStream.ReadAllAsync(_watch.Token))
            {
                Apply(change);
            }
        }
        catch (Exception ex) when (ex is RpcException or OperationCanceledException or IOException)
        {
            // 連接斷開；重連後會重新訂閱
        }
    }

    private void Apply(Proto.RuleChangeEvent change)
    {
        var existing = _all.FirstOrDefault(r => r.Id == change.Rule.Id);
        switch (change.Kind)
        {
            case Proto.RuleChangeKind.Added when existing is null:
                _all.Add(new RuleItemViewModel(change.Rule, Text, SetEnabledAsync, _profileNames));
                break;
            case Proto.RuleChangeKind.Added or Proto.RuleChangeKind.Updated when existing is not null:
                existing.Update(change.Rule);
                break;
            case Proto.RuleChangeKind.Removed when existing is not null:
                _all.Remove(existing);
                break;
        }

        ApplyFilter();
    }
}
