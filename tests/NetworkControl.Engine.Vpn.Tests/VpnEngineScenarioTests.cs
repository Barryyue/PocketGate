using System.Threading.Channels;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Hosting;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Core.Tests.Scenarios;
using NetworkControl.Engine.Vpn.Platform;
using static NetworkControl.Engine.Vpn.Tests.TestApps;

namespace NetworkControl.Engine.Vpn.Tests;

/// <summary>完整的核心服務 + VPN 引擎 + 假平台：驗證「阻斷 → 詢問 → 允許 → 重建分流」的完整鏈路。</summary>
public sealed class VpnEngineScenarioTests : IAsyncDisposable
{
    private readonly FakeTunnel _tunnel = new();
    private readonly FakeAppCatalog _catalog = new(Self, Chrome, Firefox, SystemSettings);
    private readonly FakeOwnerResolver _owners = new();
    private readonly InMemoryProtectionSwitch _protection = new();
    private readonly CancellationTokenSource _ui = new();
    private readonly Channel<PromptEvent> _prompts = Channel.CreateUnbounded<PromptEvent>();
    private IHost? _host;

    private IServiceProvider Services => _host!.Services;

    private PromptBroker Prompts => Services.GetRequiredService<PromptBroker>();

    private VpnBlackholeEngine Engine => Services.GetRequiredService<VpnBlackholeEngine>();

    public async ValueTask DisposeAsync()
    {
        await _ui.CancelAsync();
        if (_host is not null)
        {
            await _host.StopAsync(CancellationToken.None);
            _host.Dispose();
        }

        _ui.Dispose();
    }

    private async Task StartAsync(bool protection = true)
    {
        var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings { DisableDefaults = true, Configuration = new ConfigurationManager() });
        builder.Logging.SetMinimumLevel(LogLevel.Warning);
        builder.Services.AddMetrics();
        builder.Services.AddSingleton<TimeProvider>(new FakeTimeProvider(T0));
        builder.Services.AddNetworkControlCore(builder.Configuration);
        builder.Services.AddSingleton<IRuleRepository, InMemoryRuleRepository>();
        builder.Services.AddSingleton<ISettingsRepository, InMemorySettingsRepository>();
        builder.Services.AddSingleton<IAppRepository, InMemoryAppRepository>();
        builder.Services.AddSingleton<IVpnTunnel>(_tunnel);
        builder.Services.AddSingleton<IInstalledAppCatalog>(_catalog);
        builder.Services.AddSingleton<IConnectionOwnerResolver>(_owners);
        builder.Services.AddSingleton<IProtectionSwitch>(_protection);
        builder.Services.AddVpnBlackholeEngine(o =>
        {
            o.SelfPackage = SelfPackage;
            o.ReplanDebounce = TimeSpan.Zero;
        });

        _protection.SetEnabled(protection);
        _host = builder.Build();
        await _host.StartAsync(TestContext.Current.CancellationToken);
        ConnectUi();
    }

    private void ConnectUi()
    {
        var ready = new TaskCompletionSource();
        _ = Task.Run(async () =>
        {
            try
            {
                await foreach (var evt in Prompts.SubscribeAsync(new PromptSubscriber(null, IsAdmin: true), _ui.Token).WithCancellation(_ui.Token))
                {
                    await _prompts.Writer.WriteAsync(evt);
                }
            }
            catch (OperationCanceledException)
            {
                // 會話結束
            }
        });
        SpinWait.SpinUntil(() => Prompts.HasSubscriberFor(null), TimeSpan.FromSeconds(2));
    }

    private async Task<PromptSnapshot> NextPromptAsync()
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));
        while (true)
        {
            var evt = await _prompts.Reader.ReadAsync(timeout.Token);
            if (evt.Kind == PromptEventKind.Added)
            {
                return evt.Prompt;
            }
        }
    }

    [Fact]
    public async Task Blocked_app_prompts_and_is_bypassed_after_allow()
    {
        await StartAsync();
        await Eventually.True(() => _tunnel.State == VpnTunnelState.Active, "保護開啟後建立虛擬網卡");
        Assert.DoesNotContain(Chrome.PackageName, _tunnel.Current!.BypassPackages);
        Assert.Contains(SelfPackage, _tunnel.Current.BypassPackages);

        _owners.UidByLocalPort[40_000] = Chrome.Uid;
        _tunnel.Receive(Packets.Syn("10.111.222.1", 40_000, "140.82.112.3", 443));

        var prompt = await NextPromptAsync();
        Assert.Equal("android-app://com.android.chrome", prompt.App?.Path);
        Assert.Equal(Chrome.Label, prompt.App?.Name);
        Assert.False(prompt.IsConnectionHeld);

        var answer = await Prompts.AnswerAsync(prompt.Id, new PromptDecision { Action = VerdictAction.Allow, Lifetime = RuleLifetime.Permanent }, TestContext.Current.CancellationToken);
        Assert.NotNull(answer.CreatedRuleId);

        await Eventually.True(() => _tunnel.Current!.BypassPackages.Contains(Chrome.PackageName), "允許後 Chrome 被排除在 VPN 外");

        var rule = Services.GetRequiredService<RuleEngine>().Current.Rules.Single(r => r.Id == answer.CreatedRuleId);
        Assert.Equal("c1", rule.App?.RequiredSignerId);
        Assert.True(Vpn.Routing.VpnRoutingPlanner.IsEnforceable(rule));
    }

    [Fact]
    public async Task Protection_switch_opens_and_closes_tunnel()
    {
        await StartAsync(protection: false);
        await Task.Delay(50, TestContext.Current.CancellationToken);
        Assert.Empty(_tunnel.Applied);

        _protection.SetEnabled(true);
        await Eventually.True(() => _tunnel.State == VpnTunnelState.Active, "打開保護");

        _protection.SetEnabled(false);
        await Eventually.True(() => _tunnel.State == VpnTunnelState.Closed, "關閉保護");
    }

    [Fact]
    public async Task Waits_for_vpn_consent()
    {
        _tunnel.IsPrepared = false;
        await StartAsync();
        await Task.Delay(50, TestContext.Current.CancellationToken);
        Assert.Empty(_tunnel.Applied);

        _tunnel.IsPrepared = true;
        Engine.RequestReplan();
        await Eventually.True(() => _tunnel.State == VpnTunnelState.Active, "授權後建立虛擬網卡");
    }

    [Fact]
    public async Task Mode_change_rebuilds_tunnel()
    {
        await StartAsync();
        await Eventually.True(() => _tunnel.State == VpnTunnelState.Active, "建立虛擬網卡");

        var state = Services.GetRequiredService<RuntimeStateService>();
        await state.SetModeAsync(FilterMode.Blacklist, TestContext.Current.CancellationToken);
        await Eventually.True(() => _tunnel.Current!.BypassPackages.Contains(Firefox.PackageName), "黑名單模式放行沒有規則的應用");

        await state.SetModeAsync(FilterMode.BlockAll, TestContext.Current.CancellationToken);
        await Eventually.True(() => !_tunnel.Current!.BypassPackages.Contains(Firefox.PackageName), "全部阻斷");
        Assert.Contains(SystemSettings.PackageName, _tunnel.Current!.BypassPackages);
    }

    [Fact]
    public async Task Unchanged_plan_does_not_rebuild_tunnel()
    {
        await StartAsync();
        await Eventually.True(() => _tunnel.State == VpnTunnelState.Active, "建立虛擬網卡");
        var applied = _tunnel.Applied.Count;

        // 規則集變化但對分流沒有影響（帶端口條件的規則在 Android 上不生效）
        var rules = Services.GetRequiredService<RuleService>();
        await rules.AddAsync(Rule(VerdictAction.Allow, MatcherFor(Chrome), new RuleConditions { RemotePort = PortRange.Single(443) }), TestContext.Current.CancellationToken);
        await Eventually.True(() => Engine.CurrentPlan.SkippedRules.Count == 1, "重新規劃");

        Assert.Equal(applied, _tunnel.Applied.Count);
    }

    [Fact]
    public async Task Newly_installed_app_is_planned()
    {
        await StartAsync();
        var state = Services.GetRequiredService<RuntimeStateService>();
        await state.SetModeAsync(FilterMode.Blacklist, TestContext.Current.CancellationToken);
        await Eventually.True(() => _tunnel.Current?.BypassPackages.Contains(Chrome.PackageName) == true, "黑名單模式");

        _catalog.Install(Telegram);
        await Eventually.True(() => _tunnel.Current!.BypassPackages.Contains(Telegram.PackageName), "新應用加入計劃");
    }

    [Fact]
    public async Task Revoked_tunnel_turns_protection_off_and_reports_fault()
    {
        await StartAsync();
        await Eventually.True(() => _tunnel.State == VpnTunnelState.Active, "建立虛擬網卡");

        _tunnel.Revoke();

        Assert.False(_protection.IsEnabled);
        Assert.Equal(VpnTunnelState.Revoked, Engine.TunnelState);
        // 非致命故障：引擎仍然是運行狀態，用戶可以重新打開保護
        Assert.Equal(EngineState.Running, Services.GetRequiredService<EngineStatus>().State);
    }

    [Fact]
    public async Task Stop_is_idempotent()
    {
        await StartAsync();
        await Eventually.True(() => _tunnel.State == VpnTunnelState.Active, "建立虛擬網卡");

        await Engine.StopAsync(TestContext.Current.CancellationToken);
        await Engine.StopAsync(TestContext.Current.CancellationToken);
        await Engine.DisposeAsync();
        Assert.Equal(VpnTunnelState.Closed, _tunnel.State);
    }
}
