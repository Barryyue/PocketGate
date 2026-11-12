namespace NetworkControl.Core.Domain;

/// <summary>連接方向。</summary>
public enum Direction : byte
{
    Outbound = 0,
    Inbound = 1,
}

/// <summary>傳輸層協議，取值與 IANA 協議號一致。</summary>
public enum L4Protocol : byte
{
    Icmp = 1,
    Tcp = 6,
    Udp = 17,
    IcmpV6 = 58,
    Other = 255,
}

/// <summary>判決動作。</summary>
public enum VerdictAction : byte
{
    Allow = 0,
    Deny = 1,
}

/// <summary>
/// 進程引用。PID 會被系統複用，因此必須同時帶上進程啟動時間才能唯一標識一個進程。
/// </summary>
public readonly record struct ProcessRef(int Pid, long StartTimeTicks)
{
    public override string ToString() => $"{Pid}@{StartTimeTicks}";
}

/// <summary>端口範圍（閉區間）。</summary>
public readonly record struct PortRange
{
    public PortRange(ushort from, ushort to)
    {
        if (from > to)
        {
            throw new ArgumentOutOfRangeException(nameof(to), "端口範圍的結束值不能小於起始值。");
        }

        From = from;
        To = to;
    }

    public ushort From { get; }

    public ushort To { get; }

    public bool IsSingle => From == To;

    public static PortRange Single(ushort port) => new(port, port);

    public bool Contains(int port) => port >= From && port <= To;

    public override string ToString() => IsSingle ? From.ToString(System.Globalization.CultureInfo.InvariantCulture) : $"{From}-{To}";
}
