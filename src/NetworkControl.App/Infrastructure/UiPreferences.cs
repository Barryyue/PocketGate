using System.Text.Json;
using System.Text.Json.Serialization;
using NetworkControl.Ipc;

namespace NetworkControl.App.Infrastructure;

public enum ThemePreference
{
    System = 0,
    Light = 1,
    Dark = 2,
}

/// <summary>
/// 界面偏好（每個用戶各自一份，保存在用戶目錄）。與服務端的 UserSettings 不同：
/// 這裡只放純界面相關、不影響攔截行為的設置。
/// </summary>
public sealed record UiPreferences
{
    /// <summary>BCP 47 語言標籤（zh-CN / zh-TW / en-US）；為 null 表示跟隨系統。</summary>
    public string? Language { get; init; }

    public ThemePreference Theme { get; init; } = ThemePreference.System;

    /// <summary>已看過的首次運行引導版本；0 表示從未顯示（見 <c>OnboardingFlow.CurrentVersion</c>）。</summary>
    public int OnboardingVersion { get; init; }

    /// <summary>
    /// 自動檢查更新；null 表示尚未設置（默認開啟）。
    /// 用可空類型而不是 <c>= true</c> 的初始值：源生成 JSON 在缺少字段時會把 bool 反序列化為 false（見 AGENTS.md 第 6 節）。
    /// </summary>
    public bool? AutoCheckUpdates { get; init; }

    /// <summary>用戶選擇「忽略此版本」的版本號；null 表示沒有忽略任何版本。</summary>
    public string? IgnoredUpdateVersion { get; init; }
}

[JsonSourceGenerationOptions(WriteIndented = true, UseStringEnumConverter = true, DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull)]
[JsonSerializable(typeof(UiPreferences))]
internal sealed partial class UiPreferencesJsonContext : JsonSerializerContext;

/// <summary>讀寫界面偏好文件。讀取失敗時返回默認值，絕不阻止界面啟動。</summary>
public sealed class PreferencesStore
{
    private readonly string _path;
    private UiPreferences _current;

    public PreferencesStore()
        : this(PortableLayout.IsActive
            ? Path.Combine(PortableLayout.DataDirectory!, "ui-preferences.json")
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "NetworkControl", "ui-preferences.json"))
    {
    }

    public PreferencesStore(string path)
    {
        _path = path;
        _current = Load(path);
    }

    public UiPreferences Current => _current;

    public void Update(Func<UiPreferences, UiPreferences> update)
    {
        var next = update(_current);
        if (next == _current)
        {
            return;
        }

        _current = next;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            File.WriteAllText(_path, JsonSerializer.Serialize(next, UiPreferencesJsonContext.Default.UiPreferences));
        }
        catch (IOException)
        {
            // 偏好寫入失敗只影響下次啟動，不影響當前會話
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private static UiPreferences Load(string path)
    {
        try
        {
            return File.Exists(path)
                ? JsonSerializer.Deserialize(File.ReadAllText(path), UiPreferencesJsonContext.Default.UiPreferences) ?? new UiPreferences()
                : new UiPreferences();
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            return new UiPreferences();
        }
    }
}
