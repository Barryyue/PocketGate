using AndroidX.Core.Content;
using global::Android.Content;
using global::Android.OS;
using global::Android.Provider;
using global::Android.Widget;
using Uri = global::Android.Net.Uri;

namespace NetworkControl.Android.Platform;

/// <summary>統一拉起系統 APK 安裝器；未授予安裝未知應用權限時導向系統設定頁。</summary>
public static class ApkInstaller
{
    public static bool Launch(Context context, string apkPath)
    {
        if (string.IsNullOrEmpty(apkPath) || !File.Exists(apkPath)) return false;

        var hostContext = MainActivity.Current ?? context;
        try
        {
            if (!hostContext.PackageManager!.CanRequestPackageInstalls())
            {
                var perm = new Intent(
                    Settings.ActionManageUnknownAppSources,
                    Uri.Parse($"package:{hostContext.PackageName}"))
                    .AddFlags(ActivityFlags.NewTask);
                hostContext.StartActivity(perm);
                return false;
            }
        }
        catch
        {
            // 部分機型無需單獨授權或查詢失敗，繼續嘗試拉起系統安裝器
        }

        try
        {
            var file = new Java.IO.File(apkPath);
            var uri = FileProvider.GetUriForFile(hostContext, $"{hostContext.PackageName}.fileprovider", file);
            var install = new Intent(Intent.ActionView)
                .SetDataAndType(uri, "application/vnd.android.package-archive")
                .AddFlags(ActivityFlags.GrantReadUriPermission | ActivityFlags.NewTask);
            hostContext.StartActivity(install);
            return true;
        }
        catch (Java.Lang.Throwable ex)
        {
            ShowError(hostContext, ex.Message);
            return false;
        }
        catch (Exception ex)
        {
            ShowError(hostContext, ex.Message);
            return false;
        }
    }

    private static void ShowError(Context context, string? message)
    {
        if (string.IsNullOrWhiteSpace(message)) return;
        new Handler(Looper.MainLooper!).Post(() =>
            Toast.MakeText(context, message, ToastLength.Short)?.Show());
    }
}
