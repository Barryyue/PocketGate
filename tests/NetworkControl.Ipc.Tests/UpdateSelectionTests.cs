using NetworkControl.Ipc.Updates;

namespace NetworkControl.Ipc.Tests;

public class UpdateSelectionTests
{
    private static readonly UpdateManifest Manifest = new()
    {
        Version = "0.4.0",
        Url = "https://example.com/download",
    };

    [Fact]
    public void Offers_a_newer_version() =>
        Assert.True(UpdateSelection.IsUpdateAvailable(Manifest, "0.3.0+e82f6e4", null));

    [Fact]
    public void Does_not_offer_the_current_or_an_older_version()
    {
        Assert.False(UpdateSelection.IsUpdateAvailable(Manifest, "0.4.0+abc123", null));
        Assert.False(UpdateSelection.IsUpdateAvailable(Manifest, "0.4.0.0", null));
        Assert.False(UpdateSelection.IsUpdateAvailable(Manifest, "0.5.0", null));
    }

    [Fact]
    public void Skips_the_ignored_version()
    {
        Assert.False(UpdateSelection.IsUpdateAvailable(Manifest, "0.3.0", "0.4.0"));
        Assert.False(UpdateSelection.IsUpdateAvailable(Manifest, "0.3.0", "0.4"));
        Assert.False(UpdateSelection.IsUpdateAvailable(Manifest, "0.3.0", "0.4.0+abc123"));

        // 忽略的是其他版本時仍然提示
        Assert.True(UpdateSelection.IsUpdateAvailable(Manifest, "0.3.0", "0.4.1"));
        Assert.True(UpdateSelection.IsUpdateAvailable(Manifest, "0.3.0", "not a version"));
    }

    [Fact]
    public void Does_not_offer_unparseable_versions()
    {
        Assert.False(UpdateSelection.IsUpdateAvailable(Manifest, "development", null));
        Assert.False(UpdateSelection.IsUpdateAvailable(Manifest with { Version = "next" }, "0.3.0", null));
    }

    [Theory]
    [InlineData(null, true)]
    [InlineData("", true)]
    [InlineData("10.0.17763", true)]
    [InlineData("10.0.19045", true)]
    [InlineData("10.0.22000", false)]
    [InlineData("11.0.0", false)]
    public void Checks_the_minimum_os(string? minimumOs, bool supported)
    {
        var os = new Version(10, 0, 19045, 0);
        Assert.Equal(supported, UpdateSelection.IsSupportedOn(Manifest with { MinimumOs = minimumOs }, os));
    }

    [Fact]
    public void Invalid_minimum_os_does_not_block_the_update() =>
        Assert.True(UpdateSelection.IsSupportedOn(Manifest with { MinimumOs = "Windows 10" }, new Version(10, 0, 19045)));
}
