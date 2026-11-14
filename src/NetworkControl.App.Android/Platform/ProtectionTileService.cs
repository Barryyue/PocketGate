using global::Android.App;
using global::Android.Content;
using global::Android.Graphics.Drawables;
using global::Android.Service.QuickSettings;
using global::Android.Util;
using Microsoft.Extensions.DependencyInjection;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 下拉通知欄的快速設定磁貼（docs/m3-tasks.md M3-09）：點按切換保護；磁貼狀態跟隨應用內狀態。
/// 未授權 VPN 時不能在磁貼中彈系統對話框，改為打開應用由界面引導授權。
/// 長按打開應用由 MainActivity 的 <c>QS_TILE_PREFERENCES</c> IntentFilter 處理。
/// </summary>
[Service(
    Name = JavaName,
    Permission = "android.permission.BIND_QUICK_SETTINGS_TILE",
    Exported = true,
    Label = "@string/tile_label",
    Icon = "@drawable/ic_stat_shield")]
[IntentFilter([TileAction])]
[MetaData("android.service.quicksettings.TOGGLEABLE_TILE", Value = "true")]
public sealed class ProtectionTileService : TileService
{
    public const string JavaName = "com.networkcontrol.android.ProtectionTileService";

    private const string TileAction = "android.service.quicksettings.action.QS_TILE";

    /// <summary>系統刷新磁貼的節流：後端狀態可能連續變化（規則、模式、網卡重建）。</summary>
    private static readonly long RefreshIntervalTicks = System.Diagnostics.Stopwatch.Frequency;

    private static long _lastRefreshRequest;

    private IBackend? _backend;

    public override void OnStartListening()
    {
        base.OnStartListening();
        if (BackendHost.Current?.Services.GetService<IBackend>() is { } backend)
        {
            Bind(backend);
            UpdateTile(backend);
            return;
        }

        // 核心尚未啟動（例如剛開機）：先顯示關閉，啟動完成後讓系統再詢問一次
        ShowInactive();
        _ = StartBackendAsync();
    }

    public override void OnStopListening()
    {
        Unbind();
        base.OnStopListening();
    }

    public override void OnClick()
    {
        var backend = BackendHost.Current?.Services.GetService<IBackend>();
        if (backend is null)
        {
            OpenApp();
            _ = StartBackendAsync();
            return;
        }

        Bind(backend);
        if (backend.Protection is ProtectionStatus.On or ProtectionStatus.Starting or ProtectionStatus.NeedsConsent)
        {
            backend.SetProtection(false);
        }
        else if (backend.CreateVpnConsentIntent() is not null)
        {
            // 系統的 VPN 授權對話框只能在 Activity 中顯示
            OpenApp();
            return;
        }
        else
        {
            backend.SetProtection(true);
        }

        UpdateTile(backend);
    }

    private static ComponentName Component => new(global::Android.App.Application.Context.PackageName!, JavaName);

    private async Task StartBackendAsync()
    {
        try
        {
            await BackendHost.EnsureStartedAsync(this);
            RequestListeningState(this, Component);
        }
        catch (Exception ex)
        {
            Log.Warn(LogcatLoggerProvider.Tag, $"快速設定磁貼啟動核心失敗：{ex.Message}");
        }
    }

    private void Bind(IBackend backend)
    {
        if (ReferenceEquals(_backend, backend))
        {
            return;
        }

        Unbind();
        _backend = backend;
        backend.Changed += OnBackendChanged;
    }

    private void Unbind()
    {
        if (_backend is not null)
        {
            _backend.Changed -= OnBackendChanged;
            _backend = null;
        }
    }

    /// <summary>後端狀態變化：節流到每秒一次，讓系統重新調用 <see cref="OnStartListening"/>。</summary>
    private void OnBackendChanged(object? sender, EventArgs e)
    {
        var now = System.Diagnostics.Stopwatch.GetTimestamp();
        var last = Interlocked.Read(ref _lastRefreshRequest);
        if (now - last < RefreshIntervalTicks)
        {
            return;
        }

        Interlocked.Exchange(ref _lastRefreshRequest, now);
        RequestListeningState(this, Component);
    }

    private void UpdateTile(IBackend backend)
    {
        if (QsTile is not { } tile)
        {
            return;
        }

        var text = NotificationText.Current;
        tile.Label = GetString(Resource.String.tile_label) ?? "NetworkControl";
        tile.State = backend.Protection is ProtectionStatus.On or ProtectionStatus.Starting or ProtectionStatus.NeedsConsent
            ? TileState.Active
            : TileState.Inactive;
        if (OperatingSystem.IsAndroidVersionAtLeast(29))
        {
            tile.Subtitle = Subtitle(backend, text);
        }

        tile.Icon = Icon.CreateWithResource(this, Resource.Drawable.ic_stat_shield);
        tile.UpdateTile();
    }

    private void ShowInactive()
    {
        if (QsTile is not { } tile)
        {
            return;
        }

        var text = NotificationText.Current;
        tile.Label = GetString(Resource.String.tile_label) ?? "NetworkControl";
        tile.State = TileState.Inactive;
        if (OperatingSystem.IsAndroidVersionAtLeast(29))
        {
            tile.Subtitle = text.Get(this, OverviewLangResourceKind.StatusOff);
        }

        tile.Icon = Icon.CreateWithResource(this, Resource.Drawable.ic_stat_shield);
        tile.UpdateTile();
    }

    private string Subtitle(IBackend backend, NotificationText text) => backend.Protection switch
    {
        ProtectionStatus.On => ModeText(backend.Mode, text),
        ProtectionStatus.Starting => text.Get(this, OverviewLangResourceKind.StatusStarting),
        ProtectionStatus.NeedsConsent => text.Get(this, OverviewLangResourceKind.StatusNeedsConsent),
        ProtectionStatus.Revoked => text.Get(this, OverviewLangResourceKind.StatusRevoked),
        ProtectionStatus.Failed => text.Get(this, OverviewLangResourceKind.StatusFailed),
        _ => text.Get(this, OverviewLangResourceKind.StatusOff),
    };

    private string ModeText(Core.Domain.FilterMode mode, NotificationText text) => mode switch
    {
        Core.Domain.FilterMode.Whitelist => text.Get(this, CommonLangResourceKind.ModeWhitelist),
        Core.Domain.FilterMode.Blacklist => text.Get(this, CommonLangResourceKind.ModeBlacklist),
        Core.Domain.FilterMode.BlockAll => text.Get(this, CommonLangResourceKind.ModeBlockAll),
        Core.Domain.FilterMode.Disabled => text.Get(this, CommonLangResourceKind.ModeDisabled),
        _ => text.Get(this, CommonLangResourceKind.ModeAsk),
    };

    private void OpenApp()
    {
        var intent = new Intent(this, typeof(MainActivity))
            .SetFlags(ActivityFlags.NewTask | ActivityFlags.SingleTop);
        if (OperatingSystem.IsAndroidVersionAtLeast(34))
        {
            var pending = PendingIntent.GetActivity(this, 0, intent, PendingIntentFlags.Immutable | PendingIntentFlags.UpdateCurrent)!;
            StartActivityAndCollapse(pending);
        }
        else
        {
#pragma warning disable CA1422, CS0618 // API 34 起改用 PendingIntent 重載
            StartActivityAndCollapse(intent);
#pragma warning restore CA1422, CS0618
        }
    }
}
