using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

/// <summary>托盤和窗口圖標的狀態。</summary>
public enum TrayState
{
    Disconnected,
    Ask,
    Whitelist,
    Blacklist,
    BlockAll,
    Disabled,
}

/// <summary>
/// 運行時繪製盾牌圖標，用顏色區分狀態（FR-TRY-01）。不依賴二進制圖片資源，
/// 新增狀態只需加一個顏色。正式發布前可替換為設計師提供的圖標。
/// </summary>
public static class TrayIconRenderer
{
    private const int Size = 64;

    private static readonly Geometry Shield = Geometry.Parse("M32 4 L56 13 V30 C56 45 46 56 32 61 C18 56 8 45 8 30 V13 Z");
    private static readonly Geometry Check = Geometry.Parse("M21 32 L29 40 L44 24");
    private static readonly Geometry Cross = Geometry.Parse("M23 23 L41 41 M41 23 L23 41");
    private static readonly Dictionary<TrayState, WindowIcon> IconCache = [];
    private static readonly Dictionary<TrayState, Bitmap> BitmapCache = [];

    public static TrayState FromStatus(Proto.ServiceStatus? status) => status?.Mode switch
    {
        null => TrayState.Disconnected,
        Proto.FilterMode.Ask => TrayState.Ask,
        Proto.FilterMode.Whitelist => TrayState.Whitelist,
        Proto.FilterMode.Blacklist => TrayState.Blacklist,
        Proto.FilterMode.BlockAll => TrayState.BlockAll,
        _ => TrayState.Disabled,
    };

    public static WindowIcon Render(TrayState state)
    {
        if (!IconCache.TryGetValue(state, out var icon))
        {
            icon = new WindowIcon(RenderBitmap(state));
            IconCache[state] = icon;
        }

        return icon;
    }

    public static Bitmap RenderBitmap(TrayState state)
    {
        if (BitmapCache.TryGetValue(state, out var cached))
        {
            return cached;
        }

        var (fill, mark) = state switch
        {
            TrayState.Ask => (Color.Parse("#1A73E8"), Check),
            TrayState.Whitelist => (Color.Parse("#188038"), Check),
            TrayState.Blacklist => (Color.Parse("#5F6368"), Check),
            TrayState.BlockAll => (Color.Parse("#D93025"), Cross),
            TrayState.Disabled => (Color.Parse("#9AA0A6"), Cross),
            _ => (Color.Parse("#F29900"), Cross),
        };

        var bitmap = new RenderTargetBitmap(new PixelSize(Size, Size), new Vector(96, 96));
        using (var ctx = bitmap.CreateDrawingContext())
        {
            ctx.DrawGeometry(new SolidColorBrush(fill), new Pen(Brushes.White, 3), Shield);
            ctx.DrawGeometry(null, new Pen(Brushes.White, 6, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round), mark);
        }

        BitmapCache[state] = bitmap;
        return bitmap;
    }
}
