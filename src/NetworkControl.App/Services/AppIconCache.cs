using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using NetworkControl.App.Infrastructure;

namespace NetworkControl.App.Services;

/// <summary>
/// 從可執行文件提取圖標並緩存；提取在後台線程進行，失敗時返回 null 且不長期緩存，
/// 讓暫時被安全軟件鎖住的文件可以在下一次刷新時重試。
/// </summary>
public sealed partial class AppIconCache
{
    private const int MaxEntries = 512;
    private readonly TaskResultCache<Bitmap> _cache = new(MaxEntries);

    public Task<Bitmap?> GetAsync(string path)
    {
        if (!OperatingSystem.IsWindows() || string.IsNullOrWhiteSpace(path))
        {
            return Task.FromResult<Bitmap?>(null);
        }

        return _cache.GetAsync(path, static p => Extract(p));
    }

    /// <summary>
    /// 讀取圖標的 BGRA 像素（提取與測試共用）；沒有圖標或讀取失敗時返回 null。
    /// 這裡刻意不建立 Avalonia 位圖，測試不需要初始化圖標平台。
    /// </summary>
    internal static IconPixels? ExtractPixels(string path)
    {
        if (!File.Exists(path) || ExtractIconEx(path, 0, out var icon, IntPtr.Zero, 1) == 0 || icon == IntPtr.Zero)
        {
            return null;
        }

        try
        {
            return ReadPixels(icon);
        }
        finally
        {
            DestroyIcon(icon);
        }
    }

    private static Bitmap? Extract(string path)
    {
        try
        {
            if (ExtractPixels(path) is not { } pixels)
            {
                return null;
            }

            var writeable = new WriteableBitmap(new PixelSize(pixels.Width, pixels.Height), new Vector(96, 96), PixelFormat.Bgra8888, AlphaFormat.Unpremul);
            using (var frame = writeable.Lock())
            {
                for (var y = 0; y < pixels.Height; y++)
                {
                    Marshal.Copy(pixels.Bgra, y * pixels.Width * 4, IntPtr.Add(frame.Address, y * frame.RowBytes), pixels.Width * 4);
                }
            }

            return writeable;
        }
        catch (Exception ex)
        {
            // 安全軟件可能拒絕讀取文件，或圖標格式異常；界面使用默認圖標，下次刷新仍可重試
            AppLog.Warn($"Could not extract an icon from {path}", ex);
            return null;
        }
    }

    private static IconPixels? ReadPixels(IntPtr icon)
    {
        if (!GetIconInfo(icon, out var info))
        {
            return null;
        }

        try
        {
            if (info.ColorBitmap == IntPtr.Zero
                || GetObject(info.ColorBitmap, Marshal.SizeOf<NativeBitmap>(), out var native) == 0
                || native.Width <= 0 || native.Height <= 0)
            {
                return null;
            }

            var dc = CreateCompatibleDC(IntPtr.Zero);
            if (dc == IntPtr.Zero)
            {
                return null;
            }

            try
            {
                if (GetPixels(dc, info.ColorBitmap, native.Width, native.Height) is not { } color)
                {
                    return null;
                }

                if (IsFullyTransparent(color))
                {
                    // 舊式圖標（例如 8 位 / 4 位）沒有 Alpha 通道：GetDIBits 轉成 32 位後 Alpha 全為 0。
                    // 用 AND 掩碼補回透明度：掩碼位 1（白）表示透明，0（黑）表示不透明。
                    if (info.MaskBitmap == IntPtr.Zero || GetPixels(dc, info.MaskBitmap, native.Width, native.Height) is not { } mask)
                    {
                        SetOpaque(color);
                    }
                    else
                    {
                        ApplyMaskAlpha(color, mask);
                    }
                }

                return new IconPixels(native.Width, native.Height, color);
            }
            finally
            {
                DeleteDC(dc);
            }
        }
        finally
        {
            if (info.ColorBitmap != IntPtr.Zero)
            {
                DeleteObject(info.ColorBitmap);
            }

            if (info.MaskBitmap != IntPtr.Zero)
            {
                DeleteObject(info.MaskBitmap);
            }
        }
    }

    private static byte[]? GetPixels(IntPtr dc, IntPtr bitmap, int width, int height)
    {
        var header = new BitmapInfoHeader
        {
            Size = (uint)Marshal.SizeOf<BitmapInfoHeader>(),
            Width = width,
            Height = -height,
            Planes = 1,
            BitCount = 32,
        };
        var info = new BitmapInfo { Header = header };
        var pixels = new byte[width * height * 4];
        var buffer = Marshal.AllocHGlobal(pixels.Length);
        try
        {
            if (GetDIBits(dc, bitmap, 0, (uint)height, buffer, ref info, 0) == 0)
            {
                return null;
            }

            Marshal.Copy(buffer, pixels, 0, pixels.Length);
            return pixels;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    private static bool IsFullyTransparent(byte[] bgra)
    {
        for (var index = 3; index < bgra.Length; index += 4)
        {
            if (bgra[index] != 0)
            {
                return false;
            }
        }

        return true;
    }

    /// <summary>用單色 AND 掩碼生成透明度：白色（1）透明，黑色（0）不透明。</summary>
    internal static void ApplyMaskAlpha(byte[] bgra, byte[] mask)
    {
        var length = Math.Min(bgra.Length, mask.Length);
        for (var index = 0; index + 3 < length; index += 4)
        {
            bgra[index + 3] = mask[index] > 127 ? (byte)0 : (byte)255;
        }
    }

    private static void SetOpaque(byte[] bgra)
    {
        for (var index = 3; index < bgra.Length; index += 4)
        {
            bgra[index] = 255;
        }
    }

    [LibraryImport("shell32.dll", EntryPoint = "ExtractIconExW", StringMarshalling = StringMarshalling.Utf16)]
    private static partial int ExtractIconEx(string file, int index, out IntPtr large, IntPtr small, int count);

    [LibraryImport("user32.dll", EntryPoint = "GetIconInfo")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetIconInfo(IntPtr icon, out IconInfo info);

    [LibraryImport("user32.dll", EntryPoint = "DestroyIcon")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DestroyIcon(IntPtr icon);

    [LibraryImport("gdi32.dll", EntryPoint = "GetObjectW")]
    private static partial int GetObject(IntPtr handle, int size, out NativeBitmap bitmap);

    [LibraryImport("gdi32.dll", EntryPoint = "CreateCompatibleDC")]
    private static partial IntPtr CreateCompatibleDC(IntPtr dc);

    [LibraryImport("gdi32.dll", EntryPoint = "DeleteDC")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DeleteDC(IntPtr dc);

    [LibraryImport("gdi32.dll", EntryPoint = "DeleteObject")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DeleteObject(IntPtr handle);

    [DllImport("gdi32.dll", EntryPoint = "GetDIBits")]
    private static extern int GetDIBits(IntPtr dc, IntPtr bitmap, uint start, uint lines, IntPtr bits, ref BitmapInfo info, uint usage);

    [StructLayout(LayoutKind.Sequential)]
    private struct IconInfo
    {
        public int IsIcon;
        public int XHotspot;
        public int YHotspot;
        public IntPtr MaskBitmap;
        public IntPtr ColorBitmap;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeBitmap
    {
        public int Type;
        public int Width;
        public int Height;
        public int WidthBytes;
        public ushort Planes;
        public ushort BitsPixel;
        public IntPtr Bits;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BitmapInfoHeader
    {
        public uint Size;
        public int Width;
        public int Height;
        public ushort Planes;
        public ushort BitCount;
        public uint Compression;
        public uint SizeImage;
        public int XPelsPerMeter;
        public int YPelsPerMeter;
        public uint ClrUsed;
        public uint ClrImportant;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BitmapInfo
    {
        public BitmapInfoHeader Header;
        public uint Color;
    }
}

/// <summary>提取出來的圖標像素：BGRA、自上而下、每行緊湊排列。</summary>
internal readonly record struct IconPixels(int Width, int Height, byte[] Bgra);
