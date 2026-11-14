using NetworkControl.Ipc.Updates;

namespace NetworkControl.Ipc.Tests;

public class UpdateVersionTests
{
    [Theory]
    [InlineData("0.4.0", "0.4.0")]
    [InlineData(" 0.4.0 ", "0.4.0")]
    [InlineData("0.4.0+e82f6e4", "0.4.0")]
    [InlineData("0.4", "0.4.0")]
    [InlineData("0.4.0.1", "0.4.0.1")]
    [InlineData("10.0.17763", "10.0.17763")]
    public void Parses_versions_and_ignores_build_metadata(string input, string expected)
    {
        Assert.True(UpdateVersion.TryParse(input, out var version));
        Assert.Equal(expected, version.ToString());
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("0.4.0-beta")]
    [InlineData("v0.4.0")]
    [InlineData("0.4.0.0.1")]
    [InlineData("next")]
    public void Rejects_invalid_versions(string? input)
    {
        Assert.False(UpdateVersion.TryParse(input, out _));
    }

    [Fact]
    public void Missing_parts_equal_zero()
    {
        Assert.True(UpdateVersion.TryParse("0.4", out var shortVersion));
        Assert.True(UpdateVersion.TryParse("0.4.0.0", out var fullVersion));

        Assert.Equal(fullVersion, shortVersion);
    }

    [Theory]
    [InlineData("0.4.0", "0.3.9", 1)]
    [InlineData("0.3.10", "0.3.9", 1)]
    [InlineData("1.0.0", "0.99.99", 1)]
    [InlineData("0.3.9", "0.4.0", -1)]
    [InlineData("0.4.0+e82f6e4", "0.4.0", 0)]
    [InlineData("0.4", "0.4.0", 0)]
    public void Compares_versions(string left, string right, int expectedSign)
    {
        Assert.True(UpdateVersion.TryParse(left, out var a));
        Assert.True(UpdateVersion.TryParse(right, out var b));

        Assert.Equal(expectedSign, Math.Sign(a.CompareTo(b)));
        Assert.Equal(expectedSign < 0, a < b);
        Assert.Equal(expectedSign <= 0, a <= b);
        Assert.Equal(expectedSign > 0, a > b);
        Assert.Equal(expectedSign >= 0, a >= b);
    }
}
