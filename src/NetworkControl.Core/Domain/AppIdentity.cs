namespace NetworkControl.Core.Domain;

/// <summary>代碼簽名信息。</summary>
/// <param name="SignerId">Windows：證書主體；macOS：Team ID；Linux：所屬軟件包名。</param>
/// <param name="SigningId">macOS 的 Signing Identifier，例如 com.google.Chrome。</param>
/// <param name="IsPlatformBinary">是否為操作系統自帶的程序。</param>
public sealed record CodeSignature(string SignerId, string? SigningId = null, bool IsPlatformBinary = false);

/// <summary>
/// 應用身份的查找鍵：同一路徑下，svchost 托管的不同服務、不同的 MSIX 包都視為不同的應用。
/// </summary>
public readonly record struct AppKey(string Path, string? ServiceName = null, string? PackageId = null);

/// <summary>解析後的應用身份。</summary>
public sealed record AppIdentity
{
    /// <summary>持久化的應用 ID（apps 表主鍵）。</summary>
    public required long AppId { get; init; }

    /// <summary>規範化後的可執行文件完整路徑。</summary>
    public required string Path { get; init; }

    public string? DisplayName { get; init; }

    /// <summary>為 null 表示未簽名或無法校驗。</summary>
    public CodeSignature? Signature { get; init; }

    /// <summary>僅對未簽名程序計算。</summary>
    public string? Sha256 { get; init; }

    public string? PackageId { get; init; }

    public string? ServiceName { get; init; }

    /// <summary>僅對解釋器類進程採集。</summary>
    public string? CommandLine { get; init; }

    public AppKey Key => new(Path, ServiceName, PackageId);

    public bool IsSigned => Signature is not null;

    public string Name => DisplayName ?? System.IO.Path.GetFileNameWithoutExtension(Path);
}
