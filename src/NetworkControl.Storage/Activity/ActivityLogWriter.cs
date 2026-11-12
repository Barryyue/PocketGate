using System.Threading.Channels;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Settings;
using NetworkControl.Storage.Sqlite;

namespace NetworkControl.Storage.Activity;

/// <summary>
/// 連接日誌寫入器：判決管線把事件放入有界隊列（非阻塞），後台按間隔批量寫入，
/// 同一分鐘內的相同連接合併計數（FR-MON-03）。每小時按保留天數清理舊日誌。
/// </summary>
internal sealed partial class ActivityLogWriter : BackgroundService, IActivityConsumer
{
    private static readonly TimeSpan RetentionInterval = TimeSpan.FromHours(1);

    private readonly Channel<ConnectionEvent> _queue;
    private readonly ActivityDatabase _db;
    private readonly SettingsService _settings;
    private readonly StorageOptions _options;
    private readonly TimeProvider _time;
    private readonly ILogger<ActivityLogWriter> _logger;
    private long _dropped;

    public ActivityLogWriter(
        ActivityDatabase db,
        SettingsService settings,
        IOptions<StorageOptions> options,
        TimeProvider time,
        ILogger<ActivityLogWriter> logger)
    {
        _db = db;
        _settings = settings;
        _options = options.Value;
        _time = time;
        _logger = logger;
        _queue = Channel.CreateBounded<ConnectionEvent>(new BoundedChannelOptions(_options.ActivityQueueCapacity)
        {
            FullMode = BoundedChannelFullMode.DropWrite,
            SingleReader = true,
        });
    }

    public long DroppedCount => Interlocked.Read(ref _dropped);

    public void Consume(ConnectionEvent connectionEvent)
    {
        if (!_queue.Writer.TryWrite(connectionEvent))
        {
            Interlocked.Increment(ref _dropped);
        }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var batch = new List<ConnectionEvent>(1024);
        var lastRetention = DateTimeOffset.MinValue;
        using var timer = new PeriodicTimer(_options.ActivityFlushInterval, _time);

        try
        {
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                await DrainAsync(batch, stoppingToken);

                if (_time.GetUtcNow() - lastRetention >= RetentionInterval)
                {
                    await ApplyRetentionAsync(stoppingToken);
                    lastRetention = _time.GetUtcNow();
                }
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // 停止前把剩餘事件寫完
            await DrainAsync(batch, CancellationToken.None);
        }
    }

    private async Task DrainAsync(List<ConnectionEvent> batch, CancellationToken cancellationToken)
    {
        while (_queue.Reader.TryRead(out var evt))
        {
            batch.Add(evt);
            if (batch.Count >= 5000)
            {
                await WriteAsync(batch, cancellationToken);
            }
        }

        if (batch.Count > 0)
        {
            await WriteAsync(batch, cancellationToken);
        }
    }

    private async Task WriteAsync(List<ConnectionEvent> batch, CancellationToken cancellationToken)
    {
        try
        {
            await using var connection = await _db.OpenAsync(cancellationToken);
            await using var tx = (SqliteTransaction)await connection.BeginTransactionAsync(cancellationToken);
            await using var cmd = connection.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = """
                INSERT INTO connection_log (minute_ts, app_id, app_path, app_name, direction, protocol, remote_ip, remote_port,
                                            hostname, verdict, reason, rule_id, count, first_ts, last_ts)
                VALUES ($minute, $app, $path, $name, $dir, $proto, $ip, $port, $host, $verdict, $reason, $rule, 1, $ts, $ts)
                ON CONFLICT (minute_ts, app_id, direction, protocol, remote_ip, remote_port, verdict, reason) DO UPDATE SET
                    count = count + 1,
                    last_ts = MAX(last_ts, excluded.last_ts),
                    hostname = COALESCE(excluded.hostname, hostname),
                    rule_id = COALESCE(excluded.rule_id, rule_id);
                """;

            var pMinute = cmd.Parameters.Add("$minute", SqliteType.Integer);
            var pApp = cmd.Parameters.Add("$app", SqliteType.Integer);
            var pPath = cmd.Parameters.Add("$path", SqliteType.Text);
            var pName = cmd.Parameters.Add("$name", SqliteType.Text);
            var pDir = cmd.Parameters.Add("$dir", SqliteType.Integer);
            var pProto = cmd.Parameters.Add("$proto", SqliteType.Integer);
            var pIp = cmd.Parameters.Add("$ip", SqliteType.Text);
            var pPort = cmd.Parameters.Add("$port", SqliteType.Integer);
            var pHost = cmd.Parameters.Add("$host", SqliteType.Text);
            var pVerdict = cmd.Parameters.Add("$verdict", SqliteType.Integer);
            var pReason = cmd.Parameters.Add("$reason", SqliteType.Integer);
            var pRule = cmd.Parameters.Add("$rule", SqliteType.Text);
            var pTs = cmd.Parameters.Add("$ts", SqliteType.Integer);
            await cmd.PrepareAsync(cancellationToken);

            foreach (var e in batch)
            {
                var ts = e.Timestamp.ToUnixMs();
                pMinute.Value = ts / 60_000 * 60_000;
                pApp.Value = e.AppId ?? -1;
                pPath.Value = (object?)e.AppPath ?? DBNull.Value;
                pName.Value = (object?)e.AppName ?? DBNull.Value;
                pDir.Value = (int)e.Direction;
                pProto.Value = (int)e.Protocol;
                pIp.Value = e.Remote.Address.ToString();
                pPort.Value = e.Remote.Port;
                pHost.Value = (object?)e.Hostname ?? DBNull.Value;
                pVerdict.Value = (int)e.Verdict.Action;
                pReason.Value = (int)e.Verdict.Reason;
                pRule.Value = (object?)e.Verdict.RuleId?.ToString() ?? DBNull.Value;
                pTs.Value = ts;
                await cmd.ExecuteNonQueryAsync(cancellationToken);
            }

            await tx.CommitAsync(cancellationToken);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            // 日誌寫入失敗不能影響判決：記錄錯誤並丟棄這一批
            LogWriteFailed(_logger, ex, batch.Count);
        }
        finally
        {
            batch.Clear();
        }
    }

    private async Task ApplyRetentionAsync(CancellationToken cancellationToken)
    {
        try
        {
            var cutoff = (_time.GetUtcNow() - TimeSpan.FromDays(_settings.Current.LogRetentionDays)).ToUnixMs();
            await using var connection = await _db.OpenAsync(cancellationToken);
            await using var cmd = connection.CreateCommand();
            cmd.CommandText = "DELETE FROM connection_log WHERE last_ts < $cutoff;";
            cmd.With("$cutoff", cutoff);
            var deleted = await cmd.ExecuteNonQueryAsync(cancellationToken);
            if (deleted > 0)
            {
                LogRetention(_logger, deleted);
            }
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            LogRetentionFailed(_logger, ex);
        }
    }

    [LoggerMessage(Level = LogLevel.Error, Message = "寫入 {Count} 條連接日誌失敗")]
    private static partial void LogWriteFailed(ILogger logger, Exception exception, int count);

    [LoggerMessage(Level = LogLevel.Information, Message = "按保留策略清理了 {Count} 條連接日誌")]
    private static partial void LogRetention(ILogger logger, int count);

    [LoggerMessage(Level = LogLevel.Warning, Message = "清理連接日誌失敗")]
    private static partial void LogRetentionFailed(ILogger logger, Exception exception);
}
