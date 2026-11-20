using global::Android.App;
using global::Android.Content;
using global::Android.Service.QuickSettings;
using Microsoft.Extensions.DependencyInjection;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Domain;

namespace NetworkControl.Android.Platform;

internal static class TileHelpers
{
    public static IBackend? GetBackend() => BackendHost.Current?.Services.GetService<IBackend>();

    public static void SetTile(Tile? tile, TileState state, string? subtitle)
    {
        if (tile is null) return;
        tile.State = state;
        tile.Subtitle = subtitle;
        tile.UpdateTile();
    }
}

[Service(
    Name = "com.networkcontrol.android.ModeTileService",
    Permission = "android.permission.BIND_QUICK_SETTINGS_TILE",
    Exported = true,
    Label = "PocketGate Mode",
    Icon = "@drawable/ic_stat_shield")]
[IntentFilter(["android.service.quicksettings.action.QS_TILE"])]
public sealed class ModeTileService : TileService
{
    public override void OnStartListening()
    {
        base.OnStartListening();
        var backend = TileHelpers.GetBackend();
        if (backend is null)
        {
            TileHelpers.SetTile(QsTile, TileState.Unavailable, null);
            return;
        }

        var loc = NotificationText.Current;
        var label = backend.Mode switch
        {
            FilterMode.Whitelist => loc.Get(this, CommonLangResourceKind.ModeWhitelist),
            FilterMode.Blacklist => loc.Get(this, CommonLangResourceKind.ModeBlacklist),
            FilterMode.BlockAll => loc.Get(this, OverviewLangResourceKind.BlockAll),
            _ => loc.Get(this, CommonLangResourceKind.ModeAsk),
        };
        TileHelpers.SetTile(QsTile, TileState.Active, label);
    }

    public override void OnClick()
    {
        base.OnClick();
        var backend = TileHelpers.GetBackend();
        if (backend is null) return;
        var next = backend.Mode switch
        {
            FilterMode.Ask => FilterMode.Whitelist,
            FilterMode.Whitelist => FilterMode.Blacklist,
            _ => FilterMode.Ask,
        };
        _ = Task.Run(async () =>
        {
            await backend.SetModeAsync(next, CancellationToken.None).ConfigureAwait(false);
            OnStartListening();
        });
    }
}

[Service(
    Name = "com.networkcontrol.android.BlockAllTileService",
    Permission = "android.permission.BIND_QUICK_SETTINGS_TILE",
    Exported = true,
    Label = "Block All",
    Icon = "@drawable/ic_stat_shield")]
[IntentFilter(["android.service.quicksettings.action.QS_TILE"])]
public sealed class BlockAllTileService : TileService
{
    public override void OnStartListening()
    {
        base.OnStartListening();
        var backend = TileHelpers.GetBackend();
        if (backend is null)
        {
            TileHelpers.SetTile(QsTile, TileState.Unavailable, null);
            return;
        }

        var isBlockAll = backend.Mode == FilterMode.BlockAll;
        TileHelpers.SetTile(QsTile, isBlockAll ? TileState.Active : TileState.Inactive, null);
    }

    public override void OnClick()
    {
        base.OnClick();
        var backend = TileHelpers.GetBackend();
        if (backend is null) return;
        var next = backend.Mode != FilterMode.BlockAll;
        _ = Task.Run(async () =>
        {
            await backend.SetBlockAllAsync(next, CancellationToken.None).ConfigureAwait(false);
            OnStartListening();
        });
    }
}

[Service(
    Name = "com.networkcontrol.android.DndTileService",
    Permission = "android.permission.BIND_QUICK_SETTINGS_TILE",
    Exported = true,
    Label = "Do Not Disturb",
    Icon = "@drawable/ic_stat_shield")]
[IntentFilter(["android.service.quicksettings.action.QS_TILE"])]
public sealed class DndTileService : TileService
{
    public override void OnStartListening()
    {
        base.OnStartListening();
        var backend = TileHelpers.GetBackend();
        if (backend is null)
        {
            TileHelpers.SetTile(QsTile, TileState.Unavailable, null);
            return;
        }

        var isDnd = backend.Mode == FilterMode.Blacklist;
        TileHelpers.SetTile(QsTile, isDnd ? TileState.Active : TileState.Inactive, null);
    }

    public override void OnClick()
    {
        base.OnClick();
        var backend = TileHelpers.GetBackend();
        if (backend is null) return;
        var next = backend.Mode == FilterMode.Blacklist ? FilterMode.Ask : FilterMode.Blacklist;
        _ = Task.Run(async () =>
        {
            if (next == FilterMode.Blacklist && backend.Protection != Engine.Vpn.ProtectionStatus.On)
            {
                backend.SetProtection(true);
            }

            await backend.SetModeAsync(next, CancellationToken.None).ConfigureAwait(false);
            OnStartListening();
        });
    }
}

[Service(
    Name = "com.networkcontrol.android.FocusTileService",
    Permission = "android.permission.BIND_QUICK_SETTINGS_TILE",
    Exported = true,
    Label = "Focus Mode",
    Icon = "@drawable/ic_stat_shield")]
[IntentFilter(["android.service.quicksettings.action.QS_TILE"])]
public sealed class FocusTileService : TileService
{
    public override void OnStartListening()
    {
        base.OnStartListening();
        var backend = TileHelpers.GetBackend();
        if (backend is null)
        {
            TileHelpers.SetTile(QsTile, TileState.Unavailable, null);
            return;
        }

        TileHelpers.SetTile(QsTile, backend.IsFocusActive ? TileState.Active : TileState.Inactive, null);
    }

    public override void OnClick()
    {
        base.OnClick();
        var backend = TileHelpers.GetBackend();
        if (backend is null) return;
        _ = Task.Run(async () =>
        {
            if (backend.IsFocusActive)
            {
                await backend.StopFocusAsync(CancellationToken.None).ConfigureAwait(false);
            }
            else
            {
                await backend.StartFocusAsync(30, [], CancellationToken.None).ConfigureAwait(false);
            }

            OnStartListening();
        });
    }
}
