using System.Net;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Time.Testing;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Dns;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Engine;
using NetworkControl.Core.Rules;

namespace NetworkControl.Engine.Linux.Tests;

public sealed class NftablesScriptTests
{
    [Fact]
    public void Fail_open_script_uses_bypass_and_keeps_loopback_open()
    {
        var script = NftablesScript.Build(failOpen: true);

        Assert.Contains("table inet netcontrol {", script, StringComparison.Ordinal);
        Assert.Contains("oifname \"lo\" accept", script, StringComparison.Ordinal);
        Assert.Contains("ct state new queue num 0 bypass", script, StringComparison.Ordinal);
    }

    [Fact]
    public void Fail_closed_script_omits_bypass()
    {
        var script = NftablesScript.Build(failOpen: false);

        Assert.DoesNotContain("bypass", script, StringComparison.Ordinal);
        Assert.Contains("ct state new queue num 0\n", script.ReplaceLineEndings("\n"), StringComparison.Ordinal);
    }

    [Fact]
    public void Scripts_are_idempotent()
    {
        // 「add + delete」放在最前面：表已存在時不會追加重複規則，刪除時表不存在也不報錯
        foreach (var script in new[] { NftablesScript.Build(failOpen: true), NftablesScript.Remove() })
        {
            Assert.StartsWith("add table inet netcontrol\ndelete table inet netcontrol\n", script.ReplaceLineEndings("\n"), StringComparison.Ordinal);
        }
    }
}

public sealed class NfqueuePacketParserTests
{
    [Fact]
    public void Parses_ipv4_tcp_local_out_packet()
    {
        var packet = Ipv4(protocol: 6, "192.168.1.10", "203.0.113.8", 50000, 443);

        Assert.True(NfqueuePacketParser.TryParse(packet, NfqueuePacketParser.LocalOutHook, out var parsed));
        Assert.Equal(Direction.Outbound, parsed.Direction);
        Assert.Equal(L4Protocol.Tcp, parsed.Protocol);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("192.168.1.10"), 50000), parsed.Local);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("203.0.113.8"), 443), parsed.Remote);
    }

    [Fact]
    public void Inbound_packet_swaps_local_and_remote()
    {
        var packet = Ipv4(protocol: 17, "203.0.113.8", "192.168.1.10", 5353, 8080);

        Assert.True(NfqueuePacketParser.TryParse(packet, NfqueuePacketParser.LocalInHook, out var parsed));
        Assert.Equal(Direction.Inbound, parsed.Direction);
        Assert.Equal(L4Protocol.Udp, parsed.Protocol);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("192.168.1.10"), 8080), parsed.Local);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("203.0.113.8"), 5353), parsed.Remote);
    }

    [Fact]
    public void Rejects_forward_hook_even_for_valid_tcp()
    {
        var packet = Ipv4(protocol: 6, "192.168.1.10", "203.0.113.8", 50000, 443);

        Assert.False(NfqueuePacketParser.TryParse(packet, 2, out _));
    }

    [Fact]
    public void Rejects_non_tcp_udp_and_non_first_fragments()
    {
        Assert.False(NfqueuePacketParser.TryParse(Ipv4(protocol: 1, "10.0.0.1", "10.0.0.2", 0, 0), NfqueuePacketParser.LocalOutHook, out _));

        var fragment = Ipv4(protocol: 6, "10.0.0.1", "10.0.0.2", 1, 2);
        fragment[7] = 0x10; // 分片偏移 16
        Assert.False(NfqueuePacketParser.TryParse(fragment, NfqueuePacketParser.LocalOutHook, out _));
    }

    [Fact]
    public void Parses_ipv6_with_extension_headers()
    {
        // IPv6 → Hop-by-Hop（8 字節）→ Destination Options（16 字節）→ TCP
        var packet = new byte[40 + 8 + 16 + 20];
        packet[0] = 0x60;
        packet[6] = 0;
        IPAddress.Parse("2001:db8::1").GetAddressBytes().CopyTo(packet, 8);
        IPAddress.Parse("2001:db8::2").GetAddressBytes().CopyTo(packet, 24);
        packet[40] = 60;     // 下一個：Destination Options
        packet[41] = 0;      // (0 + 1) * 8 = 8
        packet[48] = 6;      // 下一個：TCP
        packet[49] = 1;      // (1 + 1) * 8 = 16
        packet[64] = 0xC3;
        packet[65] = 0x50;   // 50000
        packet[66] = 0x01;
        packet[67] = 0xBB;   // 443

        Assert.True(NfqueuePacketParser.TryParse(packet, NfqueuePacketParser.LocalOutHook, out var parsed));
        Assert.Equal(L4Protocol.Tcp, parsed.Protocol);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("2001:db8::1"), 50000), parsed.Local);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("2001:db8::2"), 443), parsed.Remote);
    }

    [Fact]
    public void Rejects_ipv6_esp_and_truncated_extension_chain()
    {
        var esp = new byte[60];
        esp[0] = 0x60;
        esp[6] = 50;
        Assert.False(NfqueuePacketParser.TryParse(esp, NfqueuePacketParser.LocalOutHook, out _));

        var truncated = new byte[44];
        truncated[0] = 0x60;
        truncated[6] = 0; // Hop-by-Hop 但報文不足 8 字節
        Assert.False(NfqueuePacketParser.TryParse(truncated, NfqueuePacketParser.LocalOutHook, out _));
    }

    internal static byte[] Ipv4(byte protocol, string source, string destination, ushort sourcePort, ushort destinationPort)
    {
        var packet = new byte[40];
        packet[0] = 0x45;
        packet[9] = protocol;
        IPAddress.Parse(source).GetAddressBytes().CopyTo(packet, 12);
        IPAddress.Parse(destination).GetAddressBytes().CopyTo(packet, 16);
        packet[20] = (byte)(sourcePort >> 8);
        packet[21] = (byte)sourcePort;
        packet[22] = (byte)(destinationPort >> 8);
        packet[23] = (byte)destinationPort;
        return packet;
    }
}

public sealed class NfqueuePacketHandlerTests
{
    private static readonly byte[] Tcp = NfqueuePacketParserTests.Ipv4(6, "192.168.1.10", "203.0.113.8", 50000, 443);

    [Fact]
    public void Completed_decisions_are_applied_immediately()
    {
        var host = new FakeHost { Decide = _ => ValueTask.FromResult(VerdictAction.Deny) };
        var verdicts = new List<(uint Id, bool Accept)>();
        var handler = new NfqueuePacketHandler(host, (id, accept) => verdicts.Add((id, accept)), TimeProvider.System);

        handler.Handle(7, NfqueuePacketParser.LocalOutHook, Tcp);

        Assert.Equal([(7u, false)], verdicts);
        Assert.Equal(0, handler.PendingCount);
    }

    [Fact]
    public async Task Pending_decision_does_not_block_other_packets()
    {
        var waiting = new TaskCompletionSource<VerdictAction>(TaskCreationOptions.RunContinuationsAsynchronously);
        var calls = 0;
        var host = new FakeHost
        {
            Decide = _ => Interlocked.Increment(ref calls) == 1
                ? new ValueTask<VerdictAction>(waiting.Task)
                : ValueTask.FromResult(VerdictAction.Allow),
        };
        var verdicts = new System.Collections.Concurrent.ConcurrentQueue<(uint Id, bool Accept)>();
        var handler = new NfqueuePacketHandler(host, (id, accept) => verdicts.Enqueue((id, accept)), TimeProvider.System);

        handler.Handle(1, NfqueuePacketParser.LocalOutHook, Tcp); // 等待用戶回答
        handler.Handle(2, NfqueuePacketParser.LocalOutHook, Tcp); // 規則命中，立即放行

        Assert.Equal([(2u, true)], verdicts);
        Assert.Equal(1, handler.PendingCount);

        waiting.SetResult(VerdictAction.Allow);
        await WaitUntilAsync(() => verdicts.Count == 2);
        Assert.Contains((1u, true), verdicts);
        Assert.Equal(0, handler.PendingCount);
    }

    [Theory]
    [InlineData(FailPolicy.FailOpen, true)]
    [InlineData(FailPolicy.FailClosed, false)]
    public void Unparseable_packets_follow_fail_policy(FailPolicy policy, bool expected)
    {
        var host = new FakeHost { FailPolicy = policy, Decide = _ => throw new InvalidOperationException("不應被調用") };
        var verdicts = new List<bool>();
        var handler = new NfqueuePacketHandler(host, (_, accept) => verdicts.Add(accept), TimeProvider.System);

        handler.Handle(1, NfqueuePacketParser.LocalOutHook, [0x60, 0, 0]);

        Assert.Equal([expected], verdicts);
    }

    [Fact]
    public async Task Failed_decisions_follow_fail_policy()
    {
        var host = new FakeHost
        {
            FailPolicy = FailPolicy.FailClosed,
            Decide = _ => new ValueTask<VerdictAction>(Task.FromCanceled<VerdictAction>(new CancellationToken(canceled: true))),
        };
        var verdicts = new System.Collections.Concurrent.ConcurrentQueue<bool>();
        var handler = new NfqueuePacketHandler(host, (_, accept) => verdicts.Enqueue(accept), TimeProvider.System);

        handler.Handle(1, NfqueuePacketParser.LocalOutHook, Tcp);

        await WaitUntilAsync(() => !verdicts.IsEmpty);
        Assert.Equal([false], verdicts);
    }

    [Fact]
    public void Too_many_pending_decisions_fall_back_to_policy()
    {
        var never = new TaskCompletionSource<VerdictAction>();
        var host = new FakeHost { FailPolicy = FailPolicy.FailOpen, Decide = _ => new ValueTask<VerdictAction>(never.Task) };
        var verdicts = new List<(uint Id, bool Accept)>();
        var handler = new NfqueuePacketHandler(host, (id, accept) => verdicts.Add((id, accept)), TimeProvider.System, maxPending: 2);

        handler.Handle(1, NfqueuePacketParser.LocalOutHook, Tcp);
        handler.Handle(2, NfqueuePacketParser.LocalOutHook, Tcp);
        handler.Handle(3, NfqueuePacketParser.LocalOutHook, Tcp);

        Assert.Equal([(3u, true)], verdicts);
        Assert.Equal(2, handler.PendingCount);
    }

    [Fact]
    public void Flow_ids_are_unique()
    {
        var ids = new List<ulong>();
        var host = new FakeHost
        {
            Decide = flow =>
            {
                ids.Add(flow.FlowId);
                return ValueTask.FromResult(VerdictAction.Allow);
            },
        };
        var handler = new NfqueuePacketHandler(host, (_, _) => { }, TimeProvider.System);

        // 內核的 packet id 可能重複（隊列重建後從頭計數），FlowId 不能直接用它
        handler.Handle(5, NfqueuePacketParser.LocalOutHook, Tcp);
        handler.Handle(5, NfqueuePacketParser.LocalOutHook, Tcp);

        Assert.Equal(2, ids.Distinct().Count());
    }

    private static async Task WaitUntilAsync(Func<bool> condition)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        while (!condition())
        {
            await Task.Delay(10, timeout.Token);
        }
    }

    private sealed class FakeHost : IEngineHost
    {
        public Func<FlowInfo, ValueTask<VerdictAction>> Decide { get; init; } = _ => ValueTask.FromResult(VerdictAction.Allow);

        public FailPolicy FailPolicy { get; init; } = FailPolicy.FailOpen;

        public RuleEngine Rules => null!;

        public FilterMode CurrentMode => FilterMode.Ask;

        public ValueTask<VerdictAction> DecideAsync(FlowInfo flow) => Decide(flow);

        public void OnFlowBlocked(FlowInfo flow, BlockReason reason)
        {
        }

        public void OnDnsObserved(DnsObservation observation)
        {
        }

        public void OnTraffic(ReadOnlySpan<TrafficSample> samples)
        {
        }

        public void OnEngineFault(EngineFault fault)
        {
        }
    }
}

public sealed class LinuxProcessInfoTests
{
    [Fact]
    public void Parses_uid_from_tab_separated_status()
    {
        string[] status = ["Name:\tcurl", "State:\tS (sleeping)", "Uid:\t1000\t1000\t1000\t1000", "Gid:\t1000\t1000\t1000\t1000"];

        Assert.Equal("1000", LinuxProcessInfoProvider.ParseUid(status));
    }

    [Fact]
    public void Parses_start_time_when_comm_contains_spaces_and_parentheses()
    {
        // 第 22 列 starttime = 987654
        const string stat = "1234 (my (odd) app) S 1 1234 1234 0 -1 4194560 100 0 0 0 1 2 0 0 20 0 1 0 987654 1000000 200 18446744073709551615";

        Assert.Equal(987654L, LinuxProcessInfoProvider.ParseStartTime(stat));
    }

    [Fact]
    public void Reads_snapshot_from_proc_tree()
    {
        var root = Path.Combine(Path.GetTempPath(), "netcontrol-proc-" + Guid.NewGuid().ToString("N"));
        var process = Path.Combine(root, "42");
        Directory.CreateDirectory(process);
        try
        {
            File.WriteAllText(Path.Combine(process, "stat"), "42 (demo) S 1 42 42 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 555 0 0");
            File.WriteAllLines(Path.Combine(process, "status"), ["Name:\tdemo", "Uid:\t0\t0\t0\t0"]);
            var target = Path.Combine(root, "demo-binary");
            File.WriteAllText(target, "binary");
            try
            {
                File.CreateSymbolicLink(Path.Combine(process, "exe"), target);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // Windows 非開發者模式不能建立符號鏈接：這部分只在 Linux CI 上覆蓋
                Assert.Skip("當前環境不能建立符號鏈接。");
            }

            var provider = new LinuxProcessInfoProvider(root);

            Assert.True(provider.TryGetProcess(42, out var snapshot));
            Assert.Equal(new ProcessRef(42, 555), snapshot.Process);
            Assert.Equal(target, snapshot.ExecutablePath);
            Assert.Equal("0", snapshot.UserId);
            Assert.True(provider.IsAlive(new ProcessRef(42, 555)));
            Assert.False(provider.IsAlive(new ProcessRef(42, 556)));
            Assert.False(provider.TryGetProcess(43, out _));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }
}

public sealed class LinuxPlatformServicesTests
{
    [Fact]
    public void Registers_every_platform_service_required_by_core()
    {
        var services = new ServiceCollection().AddLinuxNetworkEngine();

        foreach (var type in new[] { typeof(INetworkEngine), typeof(IProcessInfoProvider), typeof(IAppInspector), typeof(IProcessExitWatcher) })
        {
            Assert.Contains(services, descriptor => descriptor.ServiceType == type);
        }
    }

    [Fact]
    public void Exit_watcher_fires_once_when_process_disappears()
    {
        var processes = new FakeProcesses { Alive = true };
        var time = new FakeTimeProvider();
        var watcher = new LinuxProcessExitWatcher(processes, time);
        var exits = 0;

        using var subscription = watcher.Watch(new ProcessRef(1, 1), () => Interlocked.Increment(ref exits));
        time.Advance(LinuxProcessExitWatcher.PollInterval);
        Assert.Equal(0, exits);

        processes.Alive = false;
        time.Advance(LinuxProcessExitWatcher.PollInterval);
        time.Advance(LinuxProcessExitWatcher.PollInterval);
        Assert.Equal(1, exits);
    }

    [Fact]
    public void Exit_watcher_fires_immediately_for_dead_process()
    {
        var watcher = new LinuxProcessExitWatcher(new FakeProcesses { Alive = false }, new FakeTimeProvider());
        var exits = 0;

        using var subscription = watcher.Watch(new ProcessRef(1, 1), () => exits++);

        Assert.Equal(1, exits);
    }

    [Fact]
    public void Library_names_include_versioned_sonames()
    {
        Assert.Equal("libnetfilter_queue.so.1", NfqueueNative.CandidateNames(NfqueueNative.NetfilterQueue)[0]);
        Assert.Equal("libc.so.6", NfqueueNative.CandidateNames(NfqueueNative.LibC)[0]);
    }

    private sealed class FakeProcesses : IProcessInfoProvider
    {
        public volatile bool Alive;

        public bool TryGetProcess(int pid, out ProcessSnapshot snapshot)
        {
            snapshot = null!;
            return false;
        }

        public bool IsAlive(ProcessRef process) => Alive;
    }
}
