using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Rules;

/// <summary>
/// 規則具體度計分（設計文檔 §6.2）。條件越具體分數越高，排序時優先。
/// </summary>
public static class RuleSpecificity
{
    public static int Compute(Rule rule)
    {
        var score = 0;

        if (rule.App is { } app)
        {
            score += app.ServiceName is not null || app.PackageId is not null ? 2000 : 1000;
        }

        var c = rule.Conditions;
        score += c.Remote switch
        {
            AddressTarget { Networks: [var single] } when IsHost(single) => 400,
            DomainTarget { Pattern: var p } when !p.StartsWith("*.", StringComparison.Ordinal) => 400,
            AddressTarget or DomainTarget => 300,
            ZoneTarget => 200,
            _ => 0,
        };

        score += c.RemotePort switch
        {
            { IsSingle: true } => 40,
            not null => 30,
            null => 0,
        };

        score += c.LocalPort is not null ? 20 : 0;
        score += c.Protocol is not null ? 10 : 0;
        score += c.Direction is not null ? 5 : 0;

        return score;
    }

    private static bool IsHost(System.Net.IPNetwork network) =>
        network.PrefixLength == (network.BaseAddress.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork ? 32 : 128);
}
