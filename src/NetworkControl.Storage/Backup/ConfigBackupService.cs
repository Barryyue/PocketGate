using Microsoft.Extensions.Hosting;
using NetworkControl.Core.Hosting;

namespace NetworkControl.Storage.Backup;

/// <summary>在數據庫遷移之前備份一次，保證升級出問題時有遷移前的副本。</summary>
internal sealed class ConfigBackupStartupTask(ConfigBackup backup) : IStartupTask
{
    public int Order => StartupOrder.Storage - 10;

    public Task ExecuteAsync(CancellationToken cancellationToken) => backup.EnsureTodayAsync(cancellationToken);
}

/// <summary>
/// 運行期間每小時檢查一次，跨過本地日期後建立當天的備份。
/// 按小時檢查而不是固定每 24 小時，這樣備份跟隨日曆日期，與服務的啟動時刻無關。
/// </summary>
internal sealed class ConfigBackupService(ConfigBackup backup, TimeProvider time) : BackgroundService
{
    internal static readonly TimeSpan CheckInterval = TimeSpan.FromHours(1);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // 先建立計時器再做首次檢查：測試推進虛擬時間時計時器已經存在
        using var timer = new PeriodicTimer(CheckInterval, time);
        try
        {
            do
            {
                await backup.EnsureTodayAsync(stoppingToken);
            }
            while (await timer.WaitForNextTickAsync(stoppingToken));
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // 正常停止
        }
    }
}
