namespace NetworkControl.Ipc;

public enum IpcTransport
{
    NamedPipe = 0,
    UnixSocket = 1,
}

/// <summary>
/// IPC 端點。字符串形式：<c>pipe:&lt;name&gt;</c> 或 <c>unix:&lt;path&gt;</c>。
/// 可通過環境變量 <see cref="EnvironmentVariable"/> 覆蓋（開發、測試時使用）。
/// </summary>
public sealed record IpcEndpoint(IpcTransport Transport, string Address)
{
    /// <summary>覆蓋默認端點的環境變量。</summary>
    public const string EnvironmentVariable = "NETWORKCONTROL_IPC";

    /// <summary>IPC 協議主版本，與 proto 包名中的 v1 對應。</summary>
    public const uint ProtocolVersion = 1;

    private const string PipeName = "NetworkControl.v1";

    private const string PortablePipeName = "NetworkControl.portable.v1";

    /// <summary>以系統服務身份運行時使用的端點。</summary>
    public static IpcEndpoint System { get; } = OperatingSystem.IsWindows()
        ? new(IpcTransport.NamedPipe, PipeName)
        : new(IpcTransport.UnixSocket, OperatingSystem.IsMacOS() ? "/var/run/netcontrol/ui.sock" : "/run/netcontrol/ui.sock");

    /// <summary>
    /// 可攜版使用的端點。故意與安裝版不同，避免綠色版連上（或關閉）本機已安裝的系統服務。
    /// </summary>
    public static IpcEndpoint Portable { get; } = OperatingSystem.IsWindows()
        ? new(IpcTransport.NamedPipe, PortablePipeName)
        : new(
            IpcTransport.UnixSocket,
            Path.Combine(PortableLayout.DataDirectory ?? Path.Combine(Path.GetTempPath(), "netcontrol-portable"), "ui.sock"));

    /// <summary>以普通用戶身份運行服務（開發模式）時使用的端點。</summary>
    public static IpcEndpoint User
    {
        get
        {
            if (OperatingSystem.IsWindows())
            {
                return System;
            }

            var runtimeDir = Environment.GetEnvironmentVariable("XDG_RUNTIME_DIR");
            var dir = string.IsNullOrEmpty(runtimeDir)
                ? Path.Combine(Path.GetTempPath(), $"netcontrol-{Environment.UserName}")
                : Path.Combine(runtimeDir, "netcontrol");
            return new IpcEndpoint(IpcTransport.UnixSocket, Path.Combine(dir, "ui.sock"));
        }
    }

    public static IpcEndpoint Parse(string value)
    {
        var separator = value.IndexOf(':', StringComparison.Ordinal);
        if (separator <= 0)
        {
            throw new FormatException($"無效的 IPC 端點「{value}」，格式應為 pipe:<name> 或 unix:<path>。");
        }

        var scheme = value[..separator];
        var address = value[(separator + 1)..];
        return scheme.ToLowerInvariant() switch
        {
            "pipe" => new IpcEndpoint(IpcTransport.NamedPipe, address),
            "unix" => new IpcEndpoint(IpcTransport.UnixSocket, address),
            _ => throw new FormatException($"未知的 IPC 傳輸類型「{scheme}」。"),
        };
    }

    /// <summary>服務端：顯式配置 → 環境變量 → 可攜版 → 按權限選擇默認端點。</summary>
    public static IpcEndpoint ResolveForServer(string? configured)
    {
        if (!string.IsNullOrWhiteSpace(configured))
        {
            return Parse(configured);
        }

        if (Environment.GetEnvironmentVariable(EnvironmentVariable) is { Length: > 0 } fromEnv)
        {
            return Parse(fromEnv);
        }

        if (PortableLayout.IsActive)
        {
            return Portable;
        }

        return Environment.IsPrivilegedProcess ? System : User;
    }

    /// <summary>客戶端：環境變量優先；可攜版只連接自己的端點；否則按順序嘗試系統端點和用戶端點。</summary>
    public static IReadOnlyList<IpcEndpoint> ResolveForClient()
    {
        if (Environment.GetEnvironmentVariable(EnvironmentVariable) is { Length: > 0 } fromEnv)
        {
            return [Parse(fromEnv)];
        }

        if (PortableLayout.IsActive)
        {
            return [Portable];
        }

        return System == User ? [System] : [System, User];
    }

    /// <summary>
    /// 端點是否可能在監聽：Unix socket 檢查文件，Windows 命名管道檢查 <c>\\.\pipe\</c> 下是否存在同名管道。
    /// 返回 false 時可以確定服務沒有運行，不必嘗試連接。
    /// </summary>
    public bool MayBeAvailable
    {
        get
        {
            if (Transport == IpcTransport.UnixSocket)
            {
                return File.Exists(Address);
            }

            if (!OperatingSystem.IsWindows())
            {
                return true;
            }

            try
            {
                return Directory.EnumerateFiles(@"\\.\pipe\")
                    .Any(p => string.Equals(Path.GetFileName(p), Address, StringComparison.OrdinalIgnoreCase));
            }
            catch (IOException)
            {
                // 無法枚舉時不做判斷，交給實際連接
                return true;
            }
        }
    }

    public override string ToString() => $"{(Transport == IpcTransport.NamedPipe ? "pipe" : "unix")}:{Address}";
}
