using NetworkControl.Diagnostics;

namespace NetworkControl.Ipc.Tests;

public class DiagnosticsRedactorTests
{
    private static readonly DiagnosticsRedactor Redactor = new(["zhangsan"], "DESKTOP-ABC123");

    [Theory]
    [InlineData(@"C:\Users\zhangsan\AppData\Local\x.log", @"C:\Users\<user>\AppData\Local\x.log")]
    [InlineData(@"c:\users\Li Si\Desktop\a.exe", @"c:\users\<user>\Desktop\a.exe")]
    [InlineData(@"\device\harddiskvolume3\users\王五\app.exe", @"\device\harddiskvolume3\users\<user>\app.exe")]
    [InlineData("/home/alice/.config/x", "/home/<user>/.config/x")]
    [InlineData(@"profile C:\Users\bob", @"profile C:\Users\<user>")]
    [InlineData(@"C:\Users\bob is gone", @"C:\Users\<user> is gone")]
    public void Replaces_user_names_in_profile_paths(string input, string expected) =>
        Assert.Equal(expected, Redactor.Redact(input));

    [Theory]
    [InlineData(@"C:\Users\Public\Documents\a.txt")]
    [InlineData(@"C:\Users\Default\NTUSER.DAT")]
    [InlineData(@"C:\Program Files\NetworkControl\Service\NetworkControl.Service.exe")]
    public void Keeps_shared_profile_and_program_paths(string input) =>
        Assert.Equal(input, Redactor.Redact(input));

    [Fact]
    public void Replaces_machine_and_user_names_as_whole_words()
    {
        Assert.Equal(@"Account <machine>\<user>", Redactor.Redact(@"Account DESKTOP-ABC123\zhangsan"));
        Assert.Equal("Computer: <machine>", Redactor.Redact("Computer: desktop-abc123"));

        // 名字作為其他單詞的一部分時不替換
        Assert.Equal("zhangsanfeng DESKTOP-ABC1234", Redactor.Redact("zhangsanfeng DESKTOP-ABC1234"));
    }

    [Fact]
    public void Ignores_short_and_well_known_account_names()
    {
        var redactor = new DiagnosticsRedactor(["SYSTEM", "ab"], "PC");
        Assert.Equal("SYSTEM ab PC", redactor.Redact("SYSTEM ab PC"));
    }

    [Fact]
    public void Addresses_are_redacted_only_when_enabled()
    {
        const string text = "connect 140.82.112.3:443 and [2606:4700::1111]:443 at 17:12:34";
        Assert.Equal(text, Redactor.Redact(text));

        var redactor = new DiagnosticsRedactor(redactAddresses: true);
        var redacted = redactor.Redact(text);
        Assert.DoesNotContain("140.82.112.3", redacted);
        Assert.DoesNotContain("2606:4700::1111", redacted);
        Assert.Contains(DiagnosticsRedactor.AddressPlaceholder, redacted);
        Assert.Contains("17:12:34", redacted); // 時間不是 IP，不能誤傷
    }
}
