using System.Diagnostics.CodeAnalysis;
using System.Text.Json;

namespace NetworkControl.Ipc.Updates;

/// <summary>
/// 解析並校驗更新清單。缺字段、版本或地址格式錯誤、最低系統版本無法解析時一律視為無效，
/// 調用方只需記錄日誌，不要拿錯誤的清單去提示用戶。
/// </summary>
public static class UpdateManifestParser
{
    /// <summary>解析清單；格式錯誤時拋出 <see cref="FormatException"/>。</summary>
    public static UpdateManifest Parse(string json) =>
        TryParse(json, out var manifest, out var error) ? manifest : throw new FormatException(error);

    /// <summary>解析清單；失敗時返回 false，<paramref name="error"/> 為英文的技術原因（只寫日誌）。</summary>
    public static bool TryParse(string? json, [NotNullWhen(true)] out UpdateManifest? manifest, out string? error)
    {
        manifest = null;
        error = null;
        if (string.IsNullOrWhiteSpace(json))
        {
            error = "The update manifest is empty.";
            return false;
        }

        try
        {
            manifest = JsonSerializer.Deserialize(json, UpdateManifestJsonContext.Default.UpdateManifest);
        }
        catch (JsonException ex)
        {
            error = $"The update manifest is not valid JSON: {ex.Message}";
            manifest = null;
            return false;
        }

        if (manifest is null)
        {
            error = "The update manifest is empty.";
            return false;
        }

        if (!UpdateVersion.TryParse(manifest.Version, out _))
        {
            error = $"The update manifest has an invalid version: '{manifest.Version}'.";
            manifest = null;
            return false;
        }

        if (!TryCreateWebUri(manifest.Url, out _))
        {
            error = $"The update manifest has an invalid URL: '{manifest.Url}'.";
            manifest = null;
            return false;
        }

        if (!string.IsNullOrWhiteSpace(manifest.MinimumOs) && !Version.TryParse(manifest.MinimumOs, out _))
        {
            error = $"The update manifest has an invalid minimumOs: '{manifest.MinimumOs}'.";
            manifest = null;
            return false;
        }

        return true;
    }

    /// <summary>只接受 http / https 的絕對地址（打開瀏覽器時不執行本機文件或自訂協議）。</summary>
    public static bool TryCreateWebUri(string? url, [NotNullWhen(true)] out Uri? uri)
    {
        uri = null;
        if (!Uri.TryCreate(url, UriKind.Absolute, out var parsed))
        {
            return false;
        }

        if (parsed.Scheme != Uri.UriSchemeHttps && parsed.Scheme != Uri.UriSchemeHttp)
        {
            return false;
        }

        uri = parsed;
        return true;
    }
}
