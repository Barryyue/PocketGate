using System.Security.Principal;

namespace NetworkControl.Engine.Windows.IntegrationTests;

/// <summary>集成測試的運行環境要求。非管理員時動態 Skip，CI 中自然跳過。</summary>
internal static class TestEnvironment
{
    public static bool IsAdministrator { get; } = DetectAdministrator();

    /// <summary>不是管理員時跳過當前測試（xunit v3 動態跳過）。</summary>
    public static void RequireAdministrator() =>
        Assert.SkipUnless(IsAdministrator, "需要管理員權限");

    private static bool DetectAdministrator()
    {
        if (!OperatingSystem.IsWindows())
        {
            return false;
        }

        using var identity = WindowsIdentity.GetCurrent();
        return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
    }
}
