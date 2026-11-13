using global::Android.Content;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Android;

/// <summary>保存在 SharedPreferences 中的「保護」開關意圖：開機、進程重啟後據此恢復保護。</summary>
public sealed class SharedPreferencesProtectionSwitch : IProtectionSwitch
{
    private const string PreferencesName = "networkcontrol.engine";
    private const string EnabledKey = "protection_enabled";

    private readonly ISharedPreferences _preferences;
    private volatile bool _enabled;

    public SharedPreferencesProtectionSwitch(Context context)
    {
        _preferences = context.GetSharedPreferences(PreferencesName, FileCreationMode.Private)
            ?? throw new InvalidOperationException("SharedPreferences 不可用。");
        _enabled = _preferences.GetBoolean(EnabledKey, false);
    }

    public event EventHandler? Changed;

    public bool IsEnabled => _enabled;

    public void SetEnabled(bool enabled)
    {
        if (_enabled == enabled)
        {
            return;
        }

        _enabled = enabled;
        using (var editor = _preferences.Edit())
        {
            editor?.PutBoolean(EnabledKey, enabled)?.Apply();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }
}
