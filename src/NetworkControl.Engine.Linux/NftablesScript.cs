namespace NetworkControl.Engine.Linux;

/// <summary>生成 NFQUEUE 引擎使用的 nftables 規則集。</summary>
/// <remarks>
/// 每個腳本都以「add table + delete table」開頭：<c>nft -f</c> 把整個腳本作為一個事務提交，
/// 表存在時先刪除、不存在時先建立再刪除，因此重複應用（例如服務崩潰後重啟）不會追加重複規則，刪除也不會因表不存在而失敗。
/// 隊列語句使用 <c>queue num N [bypass]</c>：舊版和新版 nft 都接受這種寫法（新版會顯示為 <c>queue flags bypass to N</c>）。
/// </remarks>
internal static class NftablesScript
{
    public const string TableName = "netcontrol";

    private const string Reset = $"""
        add table inet {TableName}
        delete table inet {TableName}

        """;

    public static string Build(bool failOpen, ushort queueNumber = NfqueueAdapter.QueueNumber)
    {
        var queue = failOpen ? $"queue num {queueNumber} bypass" : $"queue num {queueNumber}";
        return Reset + $$"""
            table inet {{TableName}} {
                chain output {
                    type filter hook output priority filter - 5; policy accept;
                    oifname "lo" accept
                    ct state established,related accept
                    meta skuid 0 meta l4proto udp udp dport { 67, 547 } accept
                    meta l4proto { tcp, udp } ct state new {{queue}}
                }
                chain input {
                    type filter hook input priority filter - 5; policy accept;
                    iifname "lo" accept
                    ct state established,related accept
                    meta l4proto { tcp, udp } ct state new {{queue}}
                }
            }

            """;
    }

    /// <summary>刪除規則集；表不存在時也成功。</summary>
    public static string Remove() => Reset;
}
