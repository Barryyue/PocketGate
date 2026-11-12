using System.Threading.Channels;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Hosting;
using NetworkControl.Core.Prompts;
using NetworkControl.Core.Rules;
using NetworkControl.Core.Settings;
using NetworkControl.Engine.Fake;

namespace NetworkControl.Core.Tests.Scenarios;

/// <summary>
/// 場景測試宿主：完整的核心服務 + 模擬引擎 + 內存存儲 + 可控時間。
/// 模擬一個在線的 UI 會話：訂閱彈窗事件並記錄下來，測試代碼再決定如何回應。
/// </summary>
internal sealed class ScenarioHost : IAsyncDisposable
{
    private readonly IHost _host;
    private readonly CancellationTokenSource _uiSession = new();
    private readonly Channel<PromptEvent> _promptEvents = Channel.CreateUnbounded<PromptEvent>();

    private ScenarioHost(IHost host)
    {
        _host = host;
    }

    public FakeTimeProvider Time => (FakeTimeProvider)Services.GetRequiredService<TimeProvider>();

    public IServiceProvider Services => _host.Services;

    public INetworkSimulator Simulator => Services.GetRequiredService<INetworkSimulator>();

    public PromptBroker Prompts => Services.GetRequiredService<PromptBroker>();

    public RuleService Rules => Services.GetRequiredService<RuleService>();

    public RuntimeStateService State => Services.GetRequiredService<RuntimeStateService>();

    public InMemoryRuleRepository RuleRepository => (InMemoryRuleRepository)Services.GetRequiredService<IRuleRepository>();

    public List<ConnectionEvent> Activity { get; } = [];

    public static async Task<ScenarioHost> StartAsync(bool withUiSession = true)
    {
        var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings
        {
            DisableDefaults = true,
            Configuration = new ConfigurationManager(),
        });

        builder.Logging.SetMinimumLevel(LogLevel.Warning);
        builder.Services.AddMetrics();
        builder.Services.AddSingleton<TimeProvider>(new FakeTimeProvider(TestData.T0));
        builder.Services.AddNetworkControlCore(builder.Configuration);
        builder.Services.AddSingleton<IRuleRepository, InMemoryRuleRepository>();
        builder.Services.AddSingleton<ISettingsRepository, InMemorySettingsRepository>();
        builder.Services.AddSingleton<IAppRepository, InMemoryAppRepository>();
        builder.Services.AddActivityConsumer<RecordingActivityConsumer>();
        builder.Services.AddFakeNetworkEngine();

        var host = new ScenarioHost(builder.Build());
        await host._host.StartAsync(TestContext.Current.CancellationToken);
        host.Services.GetRequiredService<RecordingActivityConsumer>().Target = host.Activity;

        if (withUiSession)
        {
            host.ConnectUi();
        }

        return host;
    }

    /// <summary>發起一條連接，不等待判決完成（判決可能要等用戶回應彈窗）。</summary>
    public Task<SimulatedFlowResult> Connect(string app, string host = "api.github.com", ushort port = 443) =>
        Simulator.SimulateAsync(new SimulatedFlowRequest { AppKey = app, Hostname = host, Port = port }, TestContext.Current.CancellationToken);

    /// <summary>等待下一個彈窗事件（新增或更新）。</summary>
    public async Task<PromptSnapshot> NextPromptAsync(PromptEventKind kind = PromptEventKind.Added)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));
        while (true)
        {
            var evt = await _promptEvents.Reader.ReadAsync(timeout.Token);
            if (evt.Kind == kind)
            {
                return evt.Prompt;
            }
        }
    }

    public Task<PromptAnswerResult> AnswerAsync(PromptSnapshot prompt, VerdictAction action, RuleLifetime lifetime, TimeSpan? duration = null, PromptScope scope = PromptScope.AnyConnection) =>
        Prompts.AnswerAsync(prompt.Id, new PromptDecision { Action = action, Lifetime = lifetime, Duration = duration, Scope = scope }, TestContext.Current.CancellationToken);

    /// <summary>推進虛擬時間，並讓後台的定時任務有機會運行。</summary>
    public async Task AdvanceAsync(TimeSpan by)
    {
        // 分步推進，讓後台定時任務（規則過期檢查、彈窗超時）在中途得到運行機會；
        // PeriodicTimer 在一步內跨越多個週期時只觸發一次，不影響結果
        var step = TimeSpan.FromSeconds(15);
        for (var elapsed = TimeSpan.Zero; elapsed < by; elapsed += step)
        {
            Time.Advance(step < by - elapsed ? step : by - elapsed);
            await Task.Delay(5, TestContext.Current.CancellationToken);
        }
    }

    public async ValueTask DisposeAsync()
    {
        await _uiSession.CancelAsync();
        await _host.StopAsync(CancellationToken.None);
        _host.Dispose();
        _uiSession.Dispose();
    }

    private void ConnectUi()
    {
        var subscriber = new PromptSubscriber(UserId: null, IsAdmin: true);
        var ready = new TaskCompletionSource();
        _ = Task.Run(async () =>
        {
            var enumerator = Prompts.SubscribeAsync(subscriber, _uiSession.Token).GetAsyncEnumerator(_uiSession.Token);
            try
            {
                var moveNext = enumerator.MoveNextAsync();
                ready.TrySetResult();
                while (await moveNext)
                {
                    await _promptEvents.Writer.WriteAsync(enumerator.Current);
                    moveNext = enumerator.MoveNextAsync();
                }
            }
            catch (OperationCanceledException)
            {
                // 會話結束
            }
            finally
            {
                await enumerator.DisposeAsync();
            }
        });
        ready.Task.Wait(TestContext.Current.CancellationToken);
        SpinWait.SpinUntil(() => Prompts.HasSubscriberFor(null), TimeSpan.FromSeconds(2));
    }

    private sealed class RecordingActivityConsumer : IActivityConsumer
    {
        private readonly Lock _gate = new();

        public List<ConnectionEvent>? Target { get; set; }

        public void Consume(ConnectionEvent connectionEvent)
        {
            lock (_gate)
            {
                Target?.Add(connectionEvent);
            }
        }
    }
}
