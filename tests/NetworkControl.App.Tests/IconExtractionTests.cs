using NetworkControl.App.Services;

namespace NetworkControl.App.Tests;

public sealed class IconExtractionTests
{
    private static string SystemExecutable(string name) => Path.Combine(Environment.SystemDirectory, name);

    [Fact]
    public void Extracts_a_visible_icon_from_notepad()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows only.");
        var notepad = SystemExecutable("notepad.exe");
        Assert.SkipUnless(File.Exists(notepad), "notepad.exe is not present.");

        var pixels = AppIconCache.ExtractPixels(notepad);

        Assert.NotNull(pixels);
        var icon = pixels.Value;
        Assert.True(icon.Width > 0 && icon.Height > 0);
        Assert.Equal(icon.Width * icon.Height * 4, icon.Bgra.Length);
        Assert.True(HasAlpha(icon, alpha => alpha > 0), "the icon must contain visible pixels");
    }

    [Fact]
    public void Extracts_a_legacy_icon_without_an_alpha_channel()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows only.");
        var legacy = new[] { "write.exe", "wordpad.exe" }
            .Select(SystemExecutable)
            .FirstOrDefault(File.Exists);
        Assert.SkipWhen(legacy is null, "no legacy icon executable is available");

        var pixels = AppIconCache.ExtractPixels(legacy!);

        Assert.NotNull(pixels);
        var icon = pixels.Value;
        Assert.True(HasAlpha(icon, alpha => alpha > 0), "the mask must make the icon visible");
        Assert.True(HasAlpha(icon, alpha => alpha == 0), "the mask must keep the background transparent");
    }

    [Fact]
    public void Mask_uses_white_for_transparent_and_black_for_opaque()
    {
        byte[] bgra = [0, 0, 0, 0, 0, 0, 0, 0];
        byte[] mask = [255, 255, 255, 0, 0, 0, 0, 0];

        AppIconCache.ApplyMaskAlpha(bgra, mask);

        Assert.Equal(0, bgra[3]);
        Assert.Equal(255, bgra[7]);
    }

    private static bool HasAlpha(IconPixels icon, Func<byte, bool> predicate)
    {
        for (var index = 3; index < icon.Bgra.Length; index += 4)
        {
            if (predicate(icon.Bgra[index]))
            {
                return true;
            }
        }

        return false;
    }
}
