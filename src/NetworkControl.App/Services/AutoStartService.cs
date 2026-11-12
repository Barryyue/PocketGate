using Microsoft.Win32;
using NetworkControl.Ipc;

namespace NetworkControl.App.Services;

/// <summary>
/// 登錄時啟動界面（FR-SET-01）。Windows 寫入當前用戶的 Run 鍵，以 --minimized 啟動只顯示托盤。
/// 可攜版不提供此功能：文件夾可能被移動，而且會與安裝版共用同一個 Run 鍵。
/// Linux（~/.config/autostart）和 macOS（SMAppService）在對應平台的里程碑中實現。
/// </summary>
public sealed class AutoStartService
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "NetworkControl";

    public bool IsSupported => !PortableLayout.IsActive && OperatingSystem.IsWindows();

    public bool IsEnabled
    {
        get
        {
            // 顯式檢查平台：CA1416 無法從 IsSupported 的表達式推斷
            if (!OperatingSystem.IsWindows() || PortableLayout.IsActive)
            {
                return false;
            }

            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string;
        }
    }

    public void SetEnabled(bool enabled)
    {
        if (!OperatingSystem.IsWindows() || PortableLayout.IsActive || Environment.ProcessPath is not { } exe)
        {
            return;
        }

        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled)
        {
            key.SetValue(ValueName, $"\"{exe}\" --minimized");
        }
        else
        {
            key.DeleteValue(ValueName, throwOnMissingValue: false);
        }
    }
}
