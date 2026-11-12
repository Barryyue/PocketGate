using System.Text.RegularExpressions;
using NetworkControl.Core.Domain;

namespace NetworkControl.Core.Apps;

/// <summary>
/// 為彈窗生成的規則創建應用匹配器（設計文檔 §4.3）：
/// 已簽名 → 路徑中的版本號目錄替換為 *，並要求簽名者一致；
/// 未簽名 → 精確路徑，並要求哈希一致。
/// </summary>
public static partial class AppMatcherFactory
{
    public static AppMatcher Create(AppIdentity app)
    {
        if (app.Signature is { } signature)
        {
            return new AppMatcher
            {
                PathPattern = GeneralizeVersionSegments(app.Path),
                RequiredSignerId = signature.SignerId,
                ServiceName = app.ServiceName,
                PackageId = app.PackageId,
            };
        }

        return new AppMatcher
        {
            PathPattern = app.Path,
            RequiredSha256 = app.Sha256,
            ServiceName = app.ServiceName,
            PackageId = app.PackageId,
        };
    }

    /// <summary>把形如 app-1.0.9、1.2.3.4、v2.0 的路徑段替換為通配符（文件名本身保持不變）。</summary>
    public static string GeneralizeVersionSegments(string path)
    {
        var separator = path.Contains('\\', StringComparison.Ordinal) ? '\\' : '/';
        var segments = path.Split(separator);
        for (var i = 0; i < segments.Length - 1; i++)
        {
            var match = VersionSegment().Match(segments[i]);
            if (match.Success)
            {
                segments[i] = match.Groups["prefix"].Value + "*";
            }
        }

        return string.Join(separator, segments);
    }

    [GeneratedRegex(@"^(?<prefix>[A-Za-z_\-]*?[\-_]?)v?\d+(\.\d+){1,3}([\-_+][0-9A-Za-z.]+)?$", RegexOptions.CultureInvariant)]
    private static partial Regex VersionSegment();
}
