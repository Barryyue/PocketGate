using System.Diagnostics;

namespace NetworkControl.Engine.Linux;

/// <summary>通過 <c>nft -f -</c> 原子地應用 / 刪除規則集。</summary>
internal sealed class NftablesController
{
    public Task ApplyAsync(bool failOpen, CancellationToken cancellationToken) =>
        ExecuteAsync(NftablesScript.Build(failOpen), cancellationToken);

    public Task RemoveAsync(CancellationToken cancellationToken) =>
        ExecuteAsync(NftablesScript.Remove(), cancellationToken);

    private static async Task ExecuteAsync(string script, CancellationToken cancellationToken)
    {
        if (!OperatingSystem.IsLinux())
        {
            throw new PlatformNotSupportedException("nftables 只能在 Linux 上使用。");
        }

        using var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = "nft",
                ArgumentList = { "-f", "-" },
                RedirectStandardInput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            },
        };
        if (!process.Start())
        {
            throw new InvalidOperationException("無法啟動 nft 命令（是否已安裝 nftables？）。");
        }

        // 先開始讀 stderr 再寫 stdin：避免輸出緩衝區寫滿時互相等待
        var error = process.StandardError.ReadToEndAsync(cancellationToken);
        await process.StandardInput.WriteAsync(script.AsMemory(), cancellationToken);
        process.StandardInput.Close();
        await process.WaitForExitAsync(cancellationToken);
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException($"nft 命令失敗（{process.ExitCode}）：{(await error).Trim()}");
        }
    }
}
