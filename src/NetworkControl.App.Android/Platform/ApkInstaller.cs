using AndroidX.Core.Content;
using global::Android.Content;
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

        if (!context.PackageManager!.CanRequestPackageInstalls())
        {
            try
            {
                var perm = new Intent(
                    Settings.ActionManageUnknownAppSources,
                    Uri.Parse($"package:{context.PackageName}"))
                    .AddFlags(ActivityFlags.NewTask);
                context.StartActivity(perm);
            }
            catch
            {
            }

            return false;
        }

        try
        {
            var file = new Java.IO.File(apkPath);
            var uri = FileProvider.GetUriForFile(context, $"{context.PackageName}.fileprovider", file);
            var install = new Intent(Intent.ActionView)
                .SetDataAndType(uri, "application/vnd.android.package-archive")
                .AddFlags(ActivityFlags.GrantReadUriPermission | ActivityFlags.NewTask);
            context.StartActivity(install);
            return true;
        }
        catch (Exception ex)
        {
            Toast.MakeText(context, ex.Message, ToastLength.Short)?.Show();
            return false;
        }
    }
}
