namespace NetworkControl.Core.Domain;

/// <summary>
/// 規則中的應用匹配器。規則不直接引用 AppId，這樣應用升級、路徑帶版本號時規則仍然有效。
/// </summary>
public sealed record AppMatcher
{
    /// <summary>
    /// 可執行文件路徑模式：<c>*</c> 匹配單個路徑段內的任意字符，<c>**</c> 匹配任意層級。
    /// 例如 <c>C:\Users\me\AppData\Local\Discord\app-*\Discord.exe</c>。
    /// </summary>
    public required string PathPattern { get; init; }

    /// <summary>有值時要求簽名者一致，否則視為程序被篡改。</summary>
    public string? RequiredSignerId { get; init; }

    /// <summary>未簽名程序：有值時要求哈希一致，否則視為程序被篡改。</summary>
    public string? RequiredSha256 { get; init; }

    public string? ServiceName { get; init; }

    public string? PackageId { get; init; }

    public bool HasWildcard => PathPattern.Contains('*', StringComparison.Ordinal);
}
