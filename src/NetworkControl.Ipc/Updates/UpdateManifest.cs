using System.Text.Json;
using System.Text.Json.Serialization;

namespace NetworkControl.Ipc.Updates;

/// <summary>
/// 更新清單（<c>latest.json</c>）：由發布方提供的靜態 JSON，界面只讀取，不上傳任何內容。
/// 格式：<c>{ "version": "0.4.0", "url": "https://…", "notes": { "zh-TW": "…", "zh-CN": "…", "en-US": "…" }, "minimumOs": "10.0.17763" }</c>。
/// </summary>
public sealed record UpdateManifest
{
    /// <summary>最新版本，例如 <c>0.4.0</c>；可以帶 <c>+提交哈希</c> 後綴，比較時忽略。</summary>
    public string Version { get; init; } = "";

    /// <summary>下載頁面（網頁，不是安裝包直鏈）。</summary>
    public string Url { get; init; } = "";

    /// <summary>各語言的更新說明，鍵為 BCP 47 語言標籤（zh-TW / zh-CN / en-US）。</summary>
    public Dictionary<string, string>? Notes { get; init; }

    /// <summary>最低作業系統版本，例如 <c>10.0.17763</c>；null 或空表示不限制。</summary>
    public string? MinimumOs { get; init; }
}

[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    PropertyNameCaseInsensitive = true,
    ReadCommentHandling = JsonCommentHandling.Skip,
    AllowTrailingCommas = true)]
[JsonSerializable(typeof(UpdateManifest))]
internal sealed partial class UpdateManifestJsonContext : JsonSerializerContext;
