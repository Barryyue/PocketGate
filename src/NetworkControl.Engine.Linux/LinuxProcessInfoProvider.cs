using System.Globalization;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;

namespace NetworkControl.Engine.Linux;

/// <summary>通過 /proc 讀取 Linux 進程路徑、UID 和啟動時間。</summary>
/// <remarks>
/// <see cref="ProcessRef.StartTimeTicks"/> 在 Linux 上是 /proc/&lt;pid&gt;/stat 第 22 列（開機後的時鐘滴答數），
/// 與 Windows（UTC 時間的 Ticks）含義不同，只在本機內比較，不能跨平台或寫入導出文件。
/// 可執行文件被替換或刪除後，exe 鏈接會帶「 (deleted)」後綴：保留原樣，讓核心把它當作未知應用，而不是誤認成新文件。
/// </remarks>
internal sealed class LinuxProcessInfoProvider(string procRoot = "/proc") : IProcessInfoProvider
{
    public bool TryGetProcess(int pid, out ProcessSnapshot snapshot)
    {
        snapshot = null!;
        if (pid <= 0)
        {
            return false;
        }

        var directory = Path.Combine(procRoot, pid.ToString(CultureInfo.InvariantCulture));
        var executable = ReadLink(Path.Combine(directory, "exe"));
        var start = ReadStartTime(Path.Combine(directory, "stat"));
        if (executable is null || start is null)
        {
            return false;
        }

        snapshot = new ProcessSnapshot(new ProcessRef(pid, start.Value), executable, ReadUid(Path.Combine(directory, "status")));
        return true;
    }

    public bool IsAlive(ProcessRef process) =>
        ReadStartTime(Path.Combine(procRoot, process.Pid.ToString(CultureInfo.InvariantCulture), "stat")) == process.StartTimeTicks;

    private static string? ReadLink(string path)
    {
        try
        {
            return new FileInfo(path).LinkTarget;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>comm 字段（括號內）可以包含空格和括號，所以從最後一個「)」之後開始數列。</summary>
    internal static long? ParseStartTime(string stat)
    {
        var closing = stat.LastIndexOf(')');
        if (closing < 0)
        {
            return null;
        }

        // 「)」之後第一列是第 3 列（state），starttime 是第 22 列
        var fields = stat[(closing + 1)..].Split(' ', StringSplitOptions.RemoveEmptyEntries);
        return fields.Length > 19 && long.TryParse(fields[19], NumberStyles.Integer, CultureInfo.InvariantCulture, out var value)
            ? value
            : null;
    }

    /// <summary>status 文件用 Tab 分隔：<c>Uid:\t1000\t1000\t1000\t1000</c>，第一個值是真實 UID。</summary>
    internal static string? ParseUid(IEnumerable<string> statusLines)
    {
        foreach (var line in statusLines)
        {
            if (line.StartsWith("Uid:", StringComparison.Ordinal))
            {
                var values = line["Uid:".Length..].Split(['\t', ' '], StringSplitOptions.RemoveEmptyEntries);
                return values.Length > 0 ? values[0] : null;
            }
        }

        return null;
    }

    private static long? ReadStartTime(string path)
    {
        try
        {
            return ParseStartTime(File.ReadAllText(path));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static string? ReadUid(string path)
    {
        try
        {
            return ParseUid(File.ReadLines(path));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }
}
