using Avalonia.Android;
using global::Android.App;
using global::Android.Content;
using global::Android.Content.PM;
using global::Android.OS;
using global::Android.Provider;

namespace NetworkControl.Android;

/// <summary>唯一的 Activity：承載 Avalonia 的主視圖，並提供需要 Activity 的系統交互（VPN 授權、通知權限、打開系統設定）。</summary>
[Activity(
    Label = "@string/app_name",
    Icon = "@mipmap/ic_launcher",
    Theme = "@style/NetworkControl.Theme",
    MainLauncher = true,
    Exported = true,
    LaunchMode = LaunchMode.SingleTask,
    ConfigurationChanges = ConfigChanges.Orientation | ConfigChanges.ScreenSize | ConfigChanges.UiMode | ConfigChanges.Locale | ConfigChanges.LayoutDirection)]
[IntentFilter(["android.service.quicksettings.action.QS_TILE_PREFERENCES"], Categories = [Intent.CategoryDefault])]
public sealed class MainActivity : AvaloniaMainActivity
{
    /// <summary>從詢問通知打開時攜帶的詢問 ID。</summary>
    public const string ExtraPromptId = "prompt_id";

    /// <summary>要直接打開的頁面索引（0 總覽、1 應用程式、2 阻擋記錄、3 設定）。</summary>
    public const string ExtraTab = "tab";

    /// <summary>從勿擾摘要通知打開：進入阻擋記錄並清除摘要。</summary>
    public const string ExtraDismissQuietSummary = "dismiss_quiet_summary";

    private const int VpnConsentRequest = 1001;
    private const int NotificationPermissionRequest = 1002;
    private const int CreateDocumentRequest = 1003;
    private const int OpenDocumentRequest = 1004;
    private const string NotificationPermission = "android.permission.POST_NOTIFICATIONS";

    private TaskCompletionSource<bool>? _vpnConsent;
    private TaskCompletionSource<bool>? _notificationPermission;
    private TaskCompletionSource<global::Android.Net.Uri?>? _documentRequest;

    /// <summary>從通知打開了某個詢問（UI 線程）。</summary>
    public static event EventHandler<Guid>? PromptRequested;

    /// <summary>從通知要求切換頁面；第二個參數為是否清除勿擾摘要（UI 線程）。</summary>
    public static event EventHandler<(int Tab, bool DismissQuietSummary)>? NavigationRequested;

    /// <summary>界面是否在前台（決定詢問通知是否懸浮）。</summary>
    public static event EventHandler<bool>? ForegroundChanged;

    public static MainActivity? Current { get; private set; }

    /// <summary>上一次從通知帶來、但界面尚未處理的詢問。</summary>
    public static Guid? PendingPromptId { get; set; }

    /// <summary>上一次從通知帶來、但界面尚未處理的頁面請求。</summary>
    public static (int Tab, bool DismissQuietSummary)? PendingNavigation { get; set; }

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

    /// <summary>SAF：讓用戶選擇保存位置（導出規則、診斷包）。取消時返回 null。</summary>
    public Task<global::Android.Net.Uri?> RequestCreateDocumentAsync(string fileName, string mimeType, CancellationToken cancellationToken = default)
    {
        var intent = new Intent(Intent.ActionCreateDocument)
            .SetType(mimeType)
            .PutExtra(Intent.ExtraTitle, fileName);
        return RequestDocumentAsync(intent, CreateDocumentRequest, cancellationToken);
    }

    /// <summary>SAF：讓用戶選擇要打開的文件（導入規則）。取消時返回 null。</summary>
    public Task<global::Android.Net.Uri?> RequestOpenDocumentAsync(string[] mimeTypes, CancellationToken cancellationToken = default)
    {
        var intent = new Intent(Intent.ActionOpenDocument)
            .AddCategory(Intent.CategoryOpenable)
            .SetType(mimeTypes.Length == 1 ? mimeTypes[0] : "*/*");
        if (mimeTypes.Length > 1)
        {
            intent.PutExtra(Intent.ExtraMimeTypes, mimeTypes);
        }

        return RequestDocumentAsync(intent, OpenDocumentRequest, cancellationToken);
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
        else if (requestCode is CreateDocumentRequest or OpenDocumentRequest)
        {
            _documentRequest?.TrySetResult(resultCode == Result.Ok ? data?.Data : null);
            _documentRequest = null;
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

    private Task<global::Android.Net.Uri?> RequestDocumentAsync(Intent intent, int requestCode, CancellationToken cancellationToken)
    {
        _documentRequest?.TrySetResult(null);
        var completion = new TaskCompletionSource<global::Android.Net.Uri?>(TaskCreationOptions.RunContinuationsAsynchronously);
        _documentRequest = completion;
        if (cancellationToken.CanBeCanceled)
        {
            cancellationToken.Register(() => completion.TrySetCanceled(cancellationToken));
        }

        StartActivityForResult(intent, requestCode);
        return completion.Task;
    }

    private void HandleIntent(Intent? intent)
    {
        if (intent is null)
        {
            return;
        }

        if (Guid.TryParse(intent.GetStringExtra(ExtraPromptId), out var promptId))
        {
            intent.RemoveExtra(ExtraPromptId);
            PendingPromptId = promptId;
            PromptRequested?.Invoke(this, promptId);
        }

        if (intent.GetIntExtra(ExtraTab, -1) is var tab and >= 0)
        {
            intent.RemoveExtra(ExtraTab);
            var dismiss = intent.GetBooleanExtra(ExtraDismissQuietSummary, false);
            intent.RemoveExtra(ExtraDismissQuietSummary);
            PendingNavigation = (tab, dismiss);
            NavigationRequested?.Invoke(this, (tab, dismiss));
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
