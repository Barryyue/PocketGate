using NetworkControl.Core.Domain;
using NetworkControl.Storage.Serialization;

namespace NetworkControl.Storage.Tests;

public sealed class RuleExportTests
{
    private static readonly DateTimeOffset T0 = new(2026, 11, 13, 0, 0, 0, TimeSpan.Zero);

    private static readonly PathPlaceholderMapper Alice = new(
    [
        new(PathPlaceholderMapper.LocalAppData, @"C:\Users\alice\AppData\Local"),
        new(PathPlaceholderMapper.AppData, @"C:\Users\alice\AppData\Roaming"),
        new(PathPlaceholderMapper.ProgramData, @"C:\ProgramData"),
        new(PathPlaceholderMapper.UserProfile, @"C:\Users\alice\"),
    ], StringComparison.OrdinalIgnoreCase);

    private static readonly PathPlaceholderMapper Bob = new(
    [
        new(PathPlaceholderMapper.LocalAppData, @"D:\Profiles\bob\AppData\Local"),
        new(PathPlaceholderMapper.UserProfile, @"D:\Profiles\bob"),
    ], StringComparison.OrdinalIgnoreCase);

    [Fact]
    public void Export_and_import_round_trip_all_portable_fields()
    {
        var rule = NewRule() with
        {
            ProfileId = Guid.NewGuid(),
            App = new AppMatcher
            {
                PathPattern = @"C:\Users\alice\Apps\demo.exe",
                RequiredSignerId = "Example Corp",
                RequiredSha256 = "abc",
                ServiceName = "demo",
                PackageId = "pkg.demo",
            },
            Conditions = new RuleConditions
            {
                Direction = Direction.Inbound,
                Protocol = L4Protocol.Tcp,
                Remote = new DomainTarget("*.example.com"),
                RemotePort = new PortRange(443, 8443),
                LocalPort = PortRange.Single(8080),
            },
            Lifetime = RuleLifetime.Timed,
            ExpiresAt = T0.AddHours(2),
            Source = RuleSource.Prompt,
            Enabled = false,
            ManualPriority = 7,
            Note = "測試",
        };

        var json = RuleExportSerializer.Export([rule], Alice);
        Assert.Contains("\"format\": \"netcontrol-rules\"", json, StringComparison.Ordinal);
        Assert.Contains(@"%USERPROFILE%\\Apps\\demo.exe", json, StringComparison.Ordinal);
        Assert.DoesNotContain("alice", json, StringComparison.OrdinalIgnoreCase);

        var imported = Assert.Single(RuleExportSerializer.Import(json, Alice));
        Assert.Equal(rule, imported);
    }

    [Fact]
    public void Paths_are_expanded_with_the_importing_users_directories()
    {
        var rule = NewRule() with { App = new AppMatcher { PathPattern = @"C:\Users\alice\AppData\Local\Programs\app\*.exe" } };

        var json = RuleExportSerializer.Export([rule], Alice);
        Assert.Contains(@"%LOCALAPPDATA%\\Programs", json, StringComparison.Ordinal);

        var imported = Assert.Single(RuleExportSerializer.Import(json, Bob));
        Assert.Equal(@"D:\Profiles\bob\AppData\Local\Programs\app\*.exe", imported.App!.PathPattern);
    }

    [Theory]
    [InlineData(@"C:\Users\alice\app.exe", @"%USERPROFILE%\app.exe")]
    [InlineData(@"c:\users\ALICE\app.exe", @"%USERPROFILE%\app.exe")]
    [InlineData(@"C:\Users\alice", "%USERPROFILE%")]
    [InlineData(@"C:\Users\alice\AppData\Roaming\x.exe", @"%APPDATA%\x.exe")]
    [InlineData(@"C:\Users\alicebob\app.exe", @"C:\Users\alicebob\app.exe")]
    [InlineData(@"C:\ProgramDataX\app.exe", @"C:\ProgramDataX\app.exe")]
    [InlineData(@"D:\Tools\app.exe", @"D:\Tools\app.exe")]
    public void ToPlaceholder_matches_whole_path_segments_only(string path, string expected)
    {
        Assert.Equal(expected, Alice.ToPlaceholder(path));
    }

    [Theory]
    [InlineData(@"%userprofile%\app.exe", @"D:\Profiles\bob\app.exe")]
    [InlineData(@"%USERPROFILE%x\app.exe", @"%USERPROFILE%x\app.exe")]
    [InlineData(@"%APPDATA%\app.exe", @"%APPDATA%\app.exe")]
    [InlineData(@"%UNKNOWN%\app.exe", @"%UNKNOWN%\app.exe")]
    public void FromPlaceholder_leaves_unknown_or_unmapped_placeholders_untouched(string path, string expected)
    {
        Assert.Equal(expected, Bob.FromPlaceholder(path));
    }

    [Fact]
    public void Temporary_process_bound_and_builtin_rules_are_not_exported()
    {
        var kept = NewRule();
        var rules = new[]
        {
            kept,
            NewRule() with { Lifetime = RuleLifetime.Once },
            NewRule() with { Lifetime = RuleLifetime.UntilRestart },
            NewRule() with { Lifetime = RuleLifetime.UntilProcessExit, BoundProcess = new ProcessRef(42, 1234) },
            NewRule() with { Source = RuleSource.Builtin },
        };

        Assert.Equal([true, false, false, false, false], rules.Select(RuleExportSerializer.IsExportable));
        var json = RuleExportSerializer.Export(rules, Alice);
        Assert.DoesNotContain("boundProcess", json, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(kept.Id, Assert.Single(RuleExportSerializer.Import(json, Alice)).Id);
    }

    [Theory]
    [InlineData("""{ "format": "netcontrol-rules", "version": 99, "rules": [ { "unknown": true } ] }""", "版本")]
    [InlineData("""{ "format": "other", "version": 1, "rules": [] }""", "format")]
    [InlineData("""{ "version": 1, "rules": [] }""", "缺失")]
    [InlineData("""not json""", "格式無效")]
    [InlineData("""null""", "不能為空")]
    public void Import_rejects_unsupported_documents(string json, string message)
    {
        var exception = Assert.Throws<FormatException>(() => RuleExportSerializer.Import(json, Alice));
        Assert.Contains(message, exception.Message, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1 }""")]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1, "rules": null }""")]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1, "rules": [ null ] }""")]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1, "rules": [ { "id": "8f5e4b9e-3f37-4b1c-9d38-0d1f1c2c0a11", "action": "Allow", "lifetime": "Permanent", "source": "User", "createdAt": "2026-11-13T00:00:00Z", "updatedAt": "2026-11-13T00:00:00Z" } ] }""")]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1, "rules": [ { "id": "8f5e4b9e-3f37-4b1c-9d38-0d1f1c2c0a11", "conditions": { "remote": { "pattern": "x.com" } }, "action": "Allow", "lifetime": "Permanent", "source": "User", "createdAt": "2026-11-13T00:00:00Z", "updatedAt": "2026-11-13T00:00:00Z" } ] }""")]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1, "rules": [ { "id": "8f5e4b9e-3f37-4b1c-9d38-0d1f1c2c0a11", "conditions": { "remotePort": { "from": 1, "to": 70000 } }, "action": "Allow", "lifetime": "Permanent", "source": "User", "createdAt": "2026-11-13T00:00:00Z", "updatedAt": "2026-11-13T00:00:00Z" } ] }""")]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1, "rules": [ { "id": "8f5e4b9e-3f37-4b1c-9d38-0d1f1c2c0a11", "conditions": {}, "action": 9, "lifetime": "Permanent", "source": "User", "createdAt": "2026-11-13T00:00:00Z", "updatedAt": "2026-11-13T00:00:00Z" } ] }""")]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1, "rules": [ { "id": "8f5e4b9e-3f37-4b1c-9d38-0d1f1c2c0a11", "conditions": {}, "action": "Allow", "lifetime": "Timed", "source": "User", "createdAt": "2026-11-13T00:00:00Z", "updatedAt": "2026-11-13T00:00:00Z" } ] }""")]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1, "rules": [ { "id": "8f5e4b9e-3f37-4b1c-9d38-0d1f1c2c0a11", "conditions": {}, "action": "Allow", "lifetime": "UntilProcessExit", "source": "User", "createdAt": "2026-11-13T00:00:00Z", "updatedAt": "2026-11-13T00:00:00Z" } ] }""")]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1, "rules": [ { "id": "8f5e4b9e-3f37-4b1c-9d38-0d1f1c2c0a11", "app": { "pathPattern": null }, "conditions": {}, "action": "Allow", "lifetime": "Permanent", "source": "User", "createdAt": "2026-11-13T00:00:00Z", "updatedAt": "2026-11-13T00:00:00Z" } ] }""")]
    [InlineData("""{ "format": "netcontrol-rules", "version": 1, "rules": [ { "id": "8f5e4b9e-3f37-4b1c-9d38-0d1f1c2c0a11", "conditions": { "remote": { "kind": "address", "networks": [ "not-an-ip" ] } }, "action": "Allow", "lifetime": "Permanent", "source": "User", "createdAt": "2026-11-13T00:00:00Z", "updatedAt": "2026-11-13T00:00:00Z" } ] }""")]
    public void Import_reports_invalid_content_as_format_exception(string json)
    {
        Assert.Throws<FormatException>(() => RuleExportSerializer.Import(json, Alice));
    }

    [Fact]
    public void Import_accepts_minimal_rule_with_defaults()
    {
        const string json = """{ "format": "netcontrol-rules", "version": 1, "rules": [ { "id": "8f5e4b9e-3f37-4b1c-9d38-0d1f1c2c0a11", "conditions": {}, "action": "Deny", "lifetime": "Permanent", "source": "User", "createdAt": "2026-11-13T00:00:00Z", "updatedAt": "2026-11-13T00:00:00Z" } ] }""";

        var rule = Assert.Single(RuleExportSerializer.Import(json, Alice));

        Assert.True(rule.Enabled);
        Assert.Null(rule.App);
        Assert.Equal(RuleConditions.Any, rule.Conditions);
    }

    private static Rule NewRule() => new()
    {
        Id = Guid.NewGuid(),
        Conditions = new RuleConditions { Remote = new DomainTarget("example.com") },
        Action = VerdictAction.Allow,
        Lifetime = RuleLifetime.Permanent,
        Source = RuleSource.User,
        CreatedAt = T0,
        UpdatedAt = T0.AddMinutes(1),
    };
}
