using global::Android.App;
using global::Android.Content;
using global::Android.Net;
using global::Android.Util;
using NetworkControl.Android.Hosting;
using NetworkControl.Core.Domain;
using NetworkControl.Engine.Android;

namespace NetworkControl.Android.Platform;

/// <summary>通知上「允許 / 拒絕」按鈕的回調：生成此應用任意連線的永久規則。</summary>
[BroadcastReceiver(Exported = false)]
public sealed class PromptActionReceiver : BroadcastReceiver
{
    public const string ActionAnswer = "com.networkcontrol.android.action.ANSWER_PROMPT";
    public const string ExtraPromptId = "prompt_id";
    public const string ExtraAllow = "allow";

    public override void OnReceive(Context? context, Intent? intent)
    {
        if (context is null || intent?.Action != ActionAnswer || !Guid.TryParse(intent.GetStringExtra(ExtraPromptId), out var promptId))
        {
            return;
        }

        var allow = intent.GetBooleanExtra(ExtraAllow, false);
        PromptNotifier.Cancel(context, promptId);

        var pending = GoAsync();
        _ = Task.Run(async () =>
        {
            try
            {
                var backend = await BackendHost.GetAsync<IBackend>(context);
                await backend.AnswerPromptAsync(promptId, allow ? VerdictAction.Allow : VerdictAction.Deny, RuleLifetime.Permanent, null, CancellationToken.None);
            }
            catch (Exception ex)
            {
                Log.Error(LogcatLoggerProvider.Tag, $"回應詢問失敗：{ex}");
            }
            finally
            {
                pending?.Finish();
            }
        });
    }
}

/// <summary>開機或應用升級後：上次保護為開啟且仍有 VPN 授權時恢復保護（docs/android.md §5.7）。</summary>
[BroadcastReceiver(Exported = true, Enabled = true)]
[IntentFilter([Intent.ActionBootCompleted, Intent.ActionMyPackageReplaced])]
public sealed class BootReceiver : BroadcastReceiver
{
    public override void OnReceive(Context? context, Intent? intent)
    {
        if (context is null || intent?.Action is not (Intent.ActionBootCompleted or Intent.ActionMyPackageReplaced))
        {
            return;
        }

        if (!new SharedPreferencesProtectionSwitch(context).IsEnabled || VpnService.Prepare(context) is not null)
        {
            return;
        }

        var pending = GoAsync();
        _ = Task.Run(async () =>
        {
            try
            {
                // 核心啟動後引擎讀取保護開關並建立虛擬網卡
                await BackendHost.EnsureStartedAsync(context);
            }
            catch (Exception ex)
            {
                Log.Error(LogcatLoggerProvider.Tag, $"開機恢復保護失敗：{ex}");
            }
            finally
            {
                pending?.Finish();
            }
        });
    }
}
