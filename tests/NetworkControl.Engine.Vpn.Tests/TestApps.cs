using NetworkControl.Core.Domain;
using NetworkControl.Core.Rules;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Engine.Vpn.Tests;

internal static class TestApps
{
    public const string SelfPackage = "com.networkcontrol.android";

    public static readonly DateTimeOffset T0 = new(2026, 11, 13, 8, 0, 0, TimeSpan.Zero);

    public static readonly InstalledApp Self = App(SelfPackage, 10_050);
    public static readonly InstalledApp Chrome = App("com.android.chrome", 10_100, signer: "c1");
    public static readonly InstalledApp Firefox = App("org.mozilla.firefox", 10_101, signer: "f1");
    public static readonly InstalledApp Telegram = App("org.telegram.messenger", 10_102, signer: "t1");
    public static readonly InstalledApp SystemSettings = App("com.android.settings", 1000, signer: "platform", isSystem: true);
    public static readonly InstalledApp SystemShell = App("android", 1000, signer: "platform", isSystem: true);

    public static InstalledApp App(string package, int uid, string? signer = "sig", bool isSystem = false, string? label = null) =>
        new(package, uid, label ?? package, isSystem, VersionCode: 1, LastUpdated: T0, SignerSha256: signer);

    public static AppMatcher MatcherFor(InstalledApp app, string? signer = null) => new()
    {
        PathPattern = PackageAppPath.ForPackage(app.PackageName),
        RequiredSignerId = signer ?? app.SignerSha256,
    };

    public static Rule Rule(VerdictAction action, AppMatcher? app = null, RuleConditions? conditions = null, int priority = 0) => new()
    {
        Id = Guid.NewGuid(),
        App = app,
        Conditions = conditions ?? new RuleConditions { Direction = Direction.Outbound },
        Action = action,
        Lifetime = RuleLifetime.Permanent,
        Source = RuleSource.User,
        ManualPriority = priority,
        CreatedAt = T0,
        UpdatedAt = T0,
    };

    public static RuleSet RuleSetOf(params Rule[] rules) => new RuleEngine().Publish(rules);
}
