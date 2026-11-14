using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using NetworkControl.App.Infrastructure;

namespace NetworkControl.App.Services;

/// <summary>從可執行文件提取圖標並緩存；提取在後台線程進行，失敗時返回 null。</summary>
public sealed partial class AppIconCache
{
    private const int MaxEntries = 512;
    private readonly ConcurrentDictionary<string, Task<Bitmap?>> _cache = new(StringComparer.OrdinalIgnoreCase);

    public Task<Bitmap?> GetAsync(string path)
    {
        if (!OperatingSystem.IsWindows() || string.IsNullOrWhiteSpace(path))
        {
            return Task.FromResult<Bitmap?>(null);
        }

        if (_cache.Count >= MaxEntries)
        {
            _cache.Clear();
        }

        return _cache.GetOrAdd(path, static p => Task.Run(() => Extract(p)));
    }

    private static Bitmap? Extract(string path)
    {
        try
        {
            if (!File.Exists(path) || ExtractIconEx(path, 0, out var icon, IntPtr.Zero, 1) == 0 || icon == IntPtr.Zero)
            {
                return null;
            }

            try
            {
                return ToBitmap(icon);
            }
            finally
            {
                DestroyIcon(icon);
            }
        }
        catch (Exception ex)
        {
            // 安全軟件可能拒絕讀取文件，或圖標格式異常；界面使用默認圖標
            AppLog.Warn($"Could not extract an icon from {path}", ex);
            return null;
        }
    }

    private static Bitmap? ToBitmap(IntPtr icon)
    {
        if (!GetIconInfo(icon, out var info))
        {
            return null;
        }

        try
        {
            if (info.ColorBitmap == IntPtr.Zero || GetObject(info.ColorBitmap, Marshal.SizeOf<NativeBitmap>(), out var native) == 0
                || native.Width <= 0 || native.Height <= 0)
            {
                return null;
            }

            var width = native.Width;
            var height = native.Height;
            var header = new BitmapInfoHeader
            {
                Size = (uint)Marshal.SizeOf<BitmapInfoHeader>(),
                Width = width,
                Height = -height,
                Planes = 1,
                BitCount = 32,
            };
            var infoBlock = new BitmapInfo { Header = header };
            var pixels = new byte[width * height * 4];
            var buffer = Marshal.AllocHGlobal(pixels.Length);
            var dc = CreateCompatibleDC(IntPtr.Zero);
            try
            {
                if (GetDIBits(dc, info.ColorBitmap, 0, (uint)height, buffer, ref infoBlock, 0) == 0)
                {
                    return null;
                }

                Marshal.Copy(buffer, pixels, 0, pixels.Length);
            }
            finally
            {
                DeleteDC(dc);
                Marshal.FreeHGlobal(buffer);
            }

            var writeable = new WriteableBitmap(new PixelSize(width, height), new Vector(96, 96), PixelFormat.Bgra8888, AlphaFormat.Unpremul);
            using (var frame = writeable.Lock())
            {
                Marshal.Copy(pixels, 0, frame.Address, pixels.Length);
            }

            return writeable;
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
