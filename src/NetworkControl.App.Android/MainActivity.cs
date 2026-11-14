using Avalonia.Android;
using global::Android.App;
using global::Android.Content;
using global::Android.Content.PM;
using global::Android.OS;
using global::Android.Provider;

namespace NetworkControl.Android;

/// <summary>唯一的 Activity：承載 Avalonia 的主視圖，並提供需要 Activity 的系統交互（VPN 授權、通知權限、打開系統設定）。</summary>
[Activity(
    Label = "NetworkControl",
    Icon = "@mipmap/ic_launcher",
    Theme = "@style/NetworkControl.Theme",
    MainLauncher = true,
    Exported = true,
    LaunchMode = LaunchMode.SingleTask,
    ConfigurationChanges = ConfigChanges.Orientation | ConfigChanges.ScreenSize | ConfigChanges.UiMode | ConfigChanges.Locale | ConfigChanges.LayoutDirection)]
public sealed class MainActivity : AvaloniaMainActivity
{
    /// <summary>從詢問通知打開時攜帶的詢問 ID。</summary>
    public const string ExtraPromptId = "prompt_id";

    private const int VpnConsentRequest = 1001;
    private const int NotificationPermissionRequest = 1002;
    private const string NotificationPermission = "android.permission.POST_NOTIFICATIONS";

    private TaskCompletionSource<bool>? _vpnConsent;
    private TaskCompletionSource<bool>? _notificationPermission;

    /// <summary>從通知打開了某個詢問（UI 線程）。</summary>
    public static event EventHandler<Guid>? PromptRequested;

    /// <summary>界面是否在前台（決定詢問通知是否懸浮）。</summary>
    public static event EventHandler<bool>? ForegroundChanged;

    public static MainActivity? Current { get; private set; }

    /// <summary>上一次從通知帶來、但界面尚未處理的詢問。</summary>
    public static Guid? PendingPromptId { get; set; }

    public bool AreNotificationsEnabled =>
        GetSystemService(NotificationService) is NotificationManager manager && manager.AreNotificationsEnabled();

    /// <summary>顯示系統的 VPN 授權對話框，返回用戶是否同意。</summary>
    public Task<bool> RequestVpnConsentAsync(Intent consentIntent)
    {
        _vpnConsent?.TrySetResult(false);
        _vpnConsent = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        StartActivityForResult(consentIntent, VpnConsentRequest);
        return _vpnConsent.Task;
    }

    /// <summary>Android 13+ 請求通知權限；更低版本或已授權時直接返回當前狀態。</summary>
    public Task<bool> RequestNotificationPermissionAsync()
    {
        if (!OperatingSystem.IsAndroidVersionAtLeast(33) || CheckSelfPermission(NotificationPermission) == Permission.Granted)
        {
            return Task.FromResult(AreNotificationsEnabled);
        }

        _notificationPermission?.TrySetResult(false);
        _notificationPermission = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        RequestPermissions([NotificationPermission], NotificationPermissionRequest);
        return _notificationPermission.Task;
    }

    public void OpenNotificationSettings() =>
        TryStart(new Intent(Settings.ActionAppNotificationSettings).PutExtra(Settings.ExtraAppPackage, PackageName));

    public void OpenVpnSettings() => TryStart(new Intent(Settings.ActionVpnSettings));

    public void OpenBatterySettings() => TryStart(new Intent(Settings.ActionIgnoreBatteryOptimizationSettings));

    protected override void OnCreate(Bundle? savedInstanceState)
    {
        Current = this;
        base.OnCreate(savedInstanceState);
        HandleIntent(Intent);
    }

    protected override void OnNewIntent(Intent? intent)
    {
        base.OnNewIntent(intent);
        HandleIntent(intent);
    }

    protected override void OnResume()
    {
        base.OnResume();
        Current = this;
        ForegroundChanged?.Invoke(this, true);
    }

    protected override void OnStop()
    {
        ForegroundChanged?.Invoke(this, false);
        base.OnStop();
    }

    protected override void OnDestroy()
    {
        if (ReferenceEquals(Current, this))
        {
            Current = null;
        }

        base.OnDestroy();
    }

    protected override void OnActivityResult(int requestCode, Result resultCode, Intent? data)
    {
        base.OnActivityResult(requestCode, resultCode, data);
        if (requestCode == VpnConsentRequest)
        {
            _vpnConsent?.TrySetResult(resultCode == Result.Ok);
            _vpnConsent = null;
        }
    }

    public override void OnRequestPermissionsResult(int requestCode, string[] permissions, Permission[] grantResults)
    {
        base.OnRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NotificationPermissionRequest)
        {
            _notificationPermission?.TrySetResult(grantResults.Length > 0 && grantResults[0] == Permission.Granted);
            _notificationPermission = null;
        }
    }

    private void HandleIntent(Intent? intent)
    {
        if (Guid.TryParse(intent?.GetStringExtra(ExtraPromptId), out var promptId))
        {
            intent!.RemoveExtra(ExtraPromptId);
            PendingPromptId = promptId;
            PromptRequested?.Invoke(this, promptId);
        }
    }

    private void TryStart(Intent intent)
    {
        try
        {
            StartActivity(intent);
        }
        catch (ActivityNotFoundException)
        {
            StartActivity(new Intent(Settings.ActionSettings));
        }
    }
}
