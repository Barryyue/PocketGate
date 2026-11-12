using System.Net;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;

namespace NetworkControl.Core.Tests;

public class DomainPatternTests
{
    [Theory]
    [InlineData("api.github.com", "api.github.com", true)]
    [InlineData("api.github.com", "github.com", false)]
    [InlineData("*.github.com", "github.com", true)]
    [InlineData("*.github.com", "api.github.com", true)]
    [InlineData("*.github.com", "a.b.github.com", true)]
    [InlineData("*.github.com", "notgithub.com", false)]
    [InlineData("*.github.com", "github.com.evil.com", false)]
    [InlineData("API.GitHub.com.", "api.github.com", true)]
    public void Matches_follows_wildcard_semantics(string pattern, string domain, bool expected) =>
        Assert.Equal(expected, DomainPattern.Parse(pattern).Matches(DomainName.Normalize(domain)));

    [Fact]
    public void Normalize_converts_idn_to_punycode() =>
        Assert.Equal("xn--fiqs8s.example", DomainName.Normalize("中国.example"));
}

public class PathPatternTests
{
    [Theory]
    [InlineData(@"C:\Apps\Discord\app-*\Discord.exe", @"C:\Apps\Discord\app-1.0.9187\Discord.exe", true)]
    [InlineData(@"C:\Apps\Discord\app-*\Discord.exe", @"C:\Apps\Discord\app-1.0\sub\Discord.exe", false)]
    [InlineData(@"C:\Apps\**\tool.exe", @"C:\Apps\a\b\c\tool.exe", true)]
    [InlineData("/usr/bin/curl", "/usr/bin/curl", true)]
    [InlineData("/usr/bin/curl", "/usr/bin/wget", false)]
    public void Matches_globs(string pattern, string path, bool expected) =>
        Assert.Equal(expected, PathPattern.Parse(pattern, ignoreCase: true).Matches(path));

    [Fact]
    public void Case_sensitivity_is_configurable()
    {
        Assert.True(PathPattern.Parse(@"C:\Tools\A.exe", ignoreCase: true).Matches(@"c:\tools\a.EXE"));
        Assert.False(PathPattern.Parse("/opt/App", ignoreCase: false).Matches("/opt/app"));
    }
}

public class NetworkZonesTests
{
    [Theory]
    [InlineData("127.0.0.1", NetworkZone.Loopback)]
    [InlineData("::1", NetworkZone.Loopback)]
    [InlineData("192.168.1.20", NetworkZone.LocalNetwork)]
    [InlineData("10.1.2.3", NetworkZone.LocalNetwork)]
    [InlineData("172.20.0.1", NetworkZone.LocalNetwork)]
    [InlineData("fe80::1", NetworkZone.LocalNetwork)]
    [InlineData("224.0.0.251", NetworkZone.LocalNetwork)]
    [InlineData("8.8.8.8", NetworkZone.Internet)]
    [InlineData("2606:4700::1111", NetworkZone.Internet)]
    [InlineData("::ffff:192.168.0.1", NetworkZone.LocalNetwork)]
    public void Classifies_addresses(string address, NetworkZone expected) =>
        Assert.Equal(expected, NetworkZones.Classify(IPAddress.Parse(address)));
}

public class AppMatcherFactoryTests
{
    [Theory]
    [InlineData(@"C:\Users\me\AppData\Local\Discord\app-1.0.9187\Discord.exe", @"C:\Users\me\AppData\Local\Discord\app-*\Discord.exe")]
    [InlineData(@"C:\Program Files\App\2.4.1\app.exe", @"C:\Program Files\App\*\app.exe")]
    [InlineData("/opt/tool/v3.2/bin/tool", "/opt/tool/*/bin/tool")]
    [InlineData(@"C:\Program Files\Google\Chrome\Application\chrome.exe", @"C:\Program Files\Google\Chrome\Application\chrome.exe")]
    [InlineData("/usr/bin/python3.12", "/usr/bin/python3.12")]
    public void Generalizes_version_directories_only(string path, string expected) =>
        Assert.Equal(expected, AppMatcherFactory.GeneralizeVersionSegments(path));

    [Fact]
    public void Signed_apps_bind_to_signer_unsigned_apps_bind_to_hash()
    {
        var signed = AppMatcherFactory.Create(TestData.App(1, "/opt/a/app-1.2.3/a", signer: "Vendor"));
        Assert.Equal("Vendor", signed.RequiredSignerId);
        Assert.Null(signed.RequiredSha256);
        Assert.Equal("/opt/a/app-*/a", signed.PathPattern);

        var unsigned = AppMatcherFactory.Create(TestData.App(2, "/opt/b/1.0.0/b", sha256: "abc"));
        Assert.Equal("abc", unsigned.RequiredSha256);
        Assert.Null(unsigned.RequiredSignerId);
        Assert.Equal("/opt/b/1.0.0/b", unsigned.PathPattern);
    }
}
