using NetworkControl.Ipc.Updates;

namespace NetworkControl.Ipc.Tests;

public class UpdateManifestTests
{
    [Fact]
    public void Parses_complete_manifest()
    {
        const string json = """
            {
              "version": "0.4.0",
              "url": "https://example.com/networkcontrol/download",
              "notes": {
                "zh-TW": "修正連線記錄的顯示問題",
                "zh-CN": "修复连接记录的显示问题",
                "en-US": "Fixes the connection log display"
              },
              "minimumOs": "10.0.17763"
            }
            """;

        var manifest = UpdateManifestParser.Parse(json);

        Assert.Equal("0.4.0", manifest.Version);
        Assert.Equal("https://example.com/networkcontrol/download", manifest.Url);
        Assert.Equal("修正連線記錄的顯示問題", manifest.Notes!["zh-TW"]);
        Assert.Equal("10.0.17763", manifest.MinimumOs);
    }

    [Fact]
    public void Parses_minimal_manifest_and_ignores_unknown_fields()
    {
        const string json = """
            {
              "version": "0.4.0+e82f6e4",
              "url": "http://localhost:8080/latest",
              "channel": "stable"
            }
            """;

        var manifest = UpdateManifestParser.Parse(json);

        Assert.Equal("0.4.0+e82f6e4", manifest.Version);
        Assert.Null(manifest.Notes);
        Assert.Null(manifest.MinimumOs);
    }

    [Fact]
    public void Property_names_are_case_insensitive()
    {
        var manifest = UpdateManifestParser.Parse("""{ "Version": "0.4.0", "URL": "https://example.com" }""");

        Assert.Equal("0.4.0", manifest.Version);
        Assert.Equal("https://example.com", manifest.Url);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("not json")]
    [InlineData("[]")]
    [InlineData("{}")]
    [InlineData("""{ "version": "0.4.0" }""")]
    [InlineData("""{ "url": "https://example.com" }""")]
    [InlineData("""{ "version": "next", "url": "https://example.com" }""")]
    [InlineData("""{ "version": "0.4.0-beta", "url": "https://example.com" }""")]
    [InlineData("""{ "version": "0.4.0", "url": "relative/latest" }""")]
    [InlineData("""{ "version": "0.4.0", "url": "ftp://example.com/latest" }""")]
    [InlineData("""{ "version": "0.4.0", "url": "https://example.com", "minimumOs": "Windows 10" }""")]
    public void Rejects_invalid_manifests(string json)
    {
        Assert.Throws<FormatException>(() => UpdateManifestParser.Parse(json));

        Assert.False(UpdateManifestParser.TryParse(json, out var manifest, out var error));
        Assert.Null(manifest);
        Assert.False(string.IsNullOrWhiteSpace(error));
    }

    [Theory]
    [InlineData("https://example.com/latest.json")]
    [InlineData("http://localhost:8080/latest.json")]
    [InlineData("HTTPS://EXAMPLE.COM/LATEST.JSON")]
    public void Accepts_web_urls(string url)
    {
        Assert.True(UpdateManifestParser.TryCreateWebUri(url, out var uri));
        Assert.NotNull(uri);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("latest.json")]
    [InlineData(@"C:\latest.json")]
    [InlineData("file:///C:/latest.json")]
    [InlineData("javascript:alert(1)")]
    public void Rejects_non_web_urls(string? url)
    {
        Assert.False(UpdateManifestParser.TryCreateWebUri(url, out var uri));
        Assert.Null(uri);
    }
}
