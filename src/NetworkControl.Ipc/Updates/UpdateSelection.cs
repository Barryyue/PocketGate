namespace NetworkControl.Ipc.Updates;

/// <summary>決定是否向用戶提示更新。純函數，便於單元測試。</summary>
public static class UpdateSelection
{
    /// <summary>
    /// 清單中的版本比目前版本新、且不是用戶已忽略的版本時返回 true。
    /// 目前版本帶 <c>+提交哈希</c> 後綴不影響比較；無法解析的版本一律不提示。
    /// </summary>
    public static bool IsUpdateAvailable(UpdateManifest manifest, string currentVersion, string? ignoredVersion)
    {
        if (!UpdateVersion.TryParse(manifest.Version, out var remote) ||
            !UpdateVersion.TryParse(currentVersion, out var current) ||
            remote <= current)
        {
            return false;
        }

        return !SameVersion(ignoredVersion, remote);
    }

    /// <summary>清單要求的最低系統版本高於目前系統時返回 false（避免提示無法安裝的版本）。</summary>
    public static bool IsSupportedOn(UpdateManifest manifest, Version currentOs)
    {
        if (string.IsNullOrWhiteSpace(manifest.MinimumOs) || !Version.TryParse(manifest.MinimumOs, out var minimum))
        {
            return true;
        }

        return Normalize(currentOs) >= Normalize(minimum);
    }

    private static bool SameVersion(string? ignoredVersion, UpdateVersion version) =>
        UpdateVersion.TryParse(ignoredVersion, out var ignored) && ignored == version;

    // Version 把缺少的段報告為 -1；補 0 後再比較，與 UpdateVersion 的規則一致
    private static Version Normalize(Version version) =>
        new(version.Major, version.Minor, Math.Max(version.Build, 0), Math.Max(version.Revision, 0));
}
