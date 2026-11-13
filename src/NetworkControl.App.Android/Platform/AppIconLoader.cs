using System.Collections.Concurrent;
using global::Android.Content;
using global::Android.Graphics;
using AvaloniaBitmap = Avalonia.Media.Imaging.Bitmap;

namespace NetworkControl.Android.Platform;

/// <summary>按需加載並緩存應用圖標（PackageManager → PNG → Avalonia 位圖）。</summary>
public sealed class AppIconLoader(Context context)
{
    private const int IconSizePx = 96;
    private const int MaxCached = 512;

    private readonly Context _context = context.ApplicationContext ?? context;
    private readonly ConcurrentDictionary<string, Task<AvaloniaBitmap?>> _cache = new(StringComparer.Ordinal);

    public Task<AvaloniaBitmap?> LoadAsync(string packageName)
    {
        if (_cache.Count > MaxCached)
        {
            _cache.Clear();
        }

        return _cache.GetOrAdd(packageName, p => Task.Run(() => Load(p)));
    }

    private AvaloniaBitmap? Load(string packageName)
    {
        try
        {
            using var drawable = _context.PackageManager?.GetApplicationIcon(packageName);
            if (drawable is null)
            {
                return null;
            }

            using var bitmap = Bitmap.CreateBitmap(IconSizePx, IconSizePx, Bitmap.Config.Argb8888!)!;
            using (var canvas = new Canvas(bitmap))
            {
                drawable.SetBounds(0, 0, IconSizePx, IconSizePx);
                drawable.Draw(canvas);
            }

            using var stream = new MemoryStream();
            bitmap.Compress(Bitmap.CompressFormat.Png!, 100, stream);
            stream.Position = 0;
            return new AvaloniaBitmap(stream);
        }
        catch (Java.Lang.Exception)
        {
            return null;
        }
    }
}
