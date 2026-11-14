using NetworkControl.Engine.Windows.Platform;

namespace NetworkControl.Engine.Windows.Tests;

public sealed class CatalogSignatureTests
{
    [Fact]
    public void Detects_catalog_signature_for_notepad()
    {
        var path = Path.Combine(Environment.SystemDirectory, "notepad.exe");
        Assert.SkipUnless(File.Exists(path), "notepad.exe 不存在");

        var signer = CatalogSignature.VerifySigner(path);
        Assert.SkipWhen(signer is null, "notepad.exe 在此系統沒有目錄簽名");
        Assert.Contains("Microsoft", signer, StringComparison.OrdinalIgnoreCase);
    }
}
