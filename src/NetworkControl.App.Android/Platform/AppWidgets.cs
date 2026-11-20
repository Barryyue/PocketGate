using global::Android.App;
using global::Android.Appwidget;
using global::Android.Content;
using global::Android.Widget;
using Microsoft.Extensions.DependencyInjection;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;

namespace NetworkControl.Android.Platform;

internal static class WidgetRenderer
{
    public static void UpdateAll(Context context, AppWidgetManager mgr, int[] ids, string title, string subtitle, string? action = null)
    {
        foreach (var id in ids)
        {
            var views = new RemoteViews(context.PackageName, Resource.Layout.widget_card);
            views.SetTextViewText(Resource.Id.widget_title, title);
            views.SetTextViewText(Resource.Id.widget_subtitle, subtitle);
            var intent = action is not null
                ? new Intent(action).SetPackage(context.PackageName)
                : new Intent(context, typeof(MainActivity)).AddFlags(ActivityFlags.NewTask | ActivityFlags.SingleTop);
            var flags = PendingIntentFlags.UpdateCurrent | PendingIntentFlags.Immutable;
            var pi = action is not null
                ? PendingIntent.GetBroadcast(context, id, intent, flags)
                : PendingIntent.GetActivity(context, id, intent, flags);
            views.SetOnClickPendingIntent(Resource.Id.widget_root, pi);
            mgr.UpdateAppWidget(id, views);
        }
    }
}

[BroadcastReceiver(Label = "PocketGate Status", Exported = true)]
[IntentFilter([AppWidgetManager.ActionAppwidgetUpdate])]
[MetaData(AppWidgetManager.MetaDataAppwidgetProvider, Resource = "@xml/widget_status_info")]
public sealed class StatusWidgetProvider : AppWidgetProvider
{
    public override void OnUpdate(Context? context, AppWidgetManager? appWidgetManager, int[]? appWidgetIds)
    {
        if (context is null || appWidgetManager is null || appWidgetIds is null) return;
        var backend = BackendHost.Current?.Services.GetService<IBackend>();
        var loc = NotificationText.Current;
        var status = backend?.Protection == ProtectionStatus.On
            ? loc.Get(context, OverviewLangResourceKind.StatusOn)
            : loc.Get(context, OverviewLangResourceKind.StatusOff);
        var sub = backend?.ActiveProfile.Name ?? "PocketGate";
        WidgetRenderer.UpdateAll(context, appWidgetManager, appWidgetIds, status, sub);
    }
}

[BroadcastReceiver(Label = "PocketGate Overview", Exported = true)]
[IntentFilter([AppWidgetManager.ActionAppwidgetUpdate])]
[MetaData(AppWidgetManager.MetaDataAppwidgetProvider, Resource = "@xml/widget_wide_info")]
public sealed class OverviewWidgetProvider : AppWidgetProvider
{
    public override void OnUpdate(Context? context, AppWidgetManager? appWidgetManager, int[]? appWidgetIds)
    {
        if (context is null || appWidgetManager is null || appWidgetIds is null) return;
        var backend = BackendHost.Current?.Services.GetService<IBackend>();
        var loc = NotificationText.Current;
        var title = backend?.Protection == ProtectionStatus.On
            ? $"{loc.Get(context, OverviewLangResourceKind.StatusOn)} · {backend.ActiveProfile.Name}"
            : loc.Get(context, OverviewLangResourceKind.StatusOff);
        var rulesCount = backend?.Rules.Count(r => r.Enabled) ?? 0;
        var sub = $"{loc.Get(context, OverviewLangResourceKind.RulesTile)}: {rulesCount}";
        WidgetRenderer.UpdateAll(context, appWidgetManager, appWidgetIds, title, sub);
    }
}

[BroadcastReceiver(Label = "PocketGate Controls", Exported = true)]
[IntentFilter([AppWidgetManager.ActionAppwidgetUpdate])]
[MetaData(AppWidgetManager.MetaDataAppwidgetProvider, Resource = "@xml/widget_wide_info")]
public sealed class ControlsWidgetProvider : AppWidgetProvider
{
    public override void OnUpdate(Context? context, AppWidgetManager? appWidgetManager, int[]? appWidgetIds)
    {
        if (context is null || appWidgetManager is null || appWidgetIds is null) return;
        var backend = BackendHost.Current?.Services.GetService<IBackend>();
        var sub = backend is null ? "PocketGate" : $"{backend.Mode} · {(backend.IsFocusActive ? "Focus ON" : "Focus OFF")}";
        WidgetRenderer.UpdateAll(context, appWidgetManager, appWidgetIds, "PocketGate Controls", sub);
    }
}

[BroadcastReceiver(Label = "PocketGate Traffic", Exported = true)]
[IntentFilter([AppWidgetManager.ActionAppwidgetUpdate])]
[MetaData(AppWidgetManager.MetaDataAppwidgetProvider, Resource = "@xml/widget_status_info")]
public sealed class TrafficWidgetProvider : AppWidgetProvider
{
    public override void OnUpdate(Context? context, AppWidgetManager? appWidgetManager, int[]? appWidgetIds)
    {
        if (context is null || appWidgetManager is null || appWidgetIds is null) return;
        var backend = BackendHost.Current?.Services.GetService<IBackend>();
        var sub = backend?.HasTrafficAccess == true
            ? "Tap to view today's usage"
            : "Usage access needed";
        WidgetRenderer.UpdateAll(context, appWidgetManager, appWidgetIds, "PocketGate Traffic", sub);
    }
}
