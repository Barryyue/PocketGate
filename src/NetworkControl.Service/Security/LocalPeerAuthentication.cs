using System.Globalization;
using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Connections.Features;
using Microsoft.Extensions.Options;

namespace NetworkControl.Service.Security;

/// <summary>
/// 本地對端認證：IPC 只在本機開放，用操作系統提供的對端進程憑據（而不是密碼或令牌）確定客戶端身份。
/// 結果轉成標準的 ClaimsPrincipal，授權沿用 ASP.NET Core 的 [Authorize] 策略。
/// </summary>
internal sealed class LocalPeerAuthenticationHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    IOptions<ServiceOptions> serviceOptions) : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "LocalPeer";

    protected override Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var peer = ResolvePeer();
        if (peer is null)
        {
            return Task.FromResult(AuthenticateResult.Fail("無法識別本地 IPC 對端。"));
        }

        var claims = new List<Claim>();
        if (peer.Pid is { } pid)
        {
            claims.Add(new Claim(LocalPeerClaims.ProcessId, pid.ToString(CultureInfo.InvariantCulture)));
        }

        if (peer.UserId is { } userId)
        {
            claims.Add(new Claim(ClaimTypes.NameIdentifier, userId));
        }

        if (peer.IsAdmin || serviceOptions.Value.TreatAllClientsAsAdmin)
        {
            claims.Add(new Claim(ClaimTypes.Role, LocalPeerClaims.AdminRole));
        }

        var principal = new ClaimsPrincipal(new ClaimsIdentity(claims, SchemeName));
        return Task.FromResult(AuthenticateResult.Success(new AuthenticationTicket(principal, SchemeName)));
    }

    private PeerIdentity? ResolvePeer()
    {
        if (OperatingSystem.IsWindows() && Context.Features.Get<IConnectionNamedPipeFeature>() is { } pipeFeature)
        {
            return PeerCredentials.FromNamedPipe(pipeFeature.NamedPipe);
        }

        if (Context.Features.Get<IConnectionSocketFeature>() is { Socket.AddressFamily: System.Net.Sockets.AddressFamily.Unix } socketFeature)
        {
            return PeerCredentials.FromUnixSocket(socketFeature.Socket);
        }

        return null;
    }
}

public static class LocalPeerClaims
{
    public const string ProcessId = "netcontrol:pid";
    public const string AdminRole = "admin";
}

/// <summary>授權策略名稱。</summary>
public static class IpcPolicies
{
    /// <summary>任何通過身份識別的本地客戶端：查看狀態、日誌、規則；回應發給自己的彈窗。</summary>
    public const string Viewer = "Viewer";

    /// <summary>管理員：修改規則、模式、設置。</summary>
    public const string Admin = "Admin";

    public static void Configure(AuthorizationOptions options)
    {
        options.AddPolicy(Viewer, p => p.RequireAuthenticatedUser());
        options.AddPolicy(Admin, p => p.RequireAuthenticatedUser().RequireRole(LocalPeerClaims.AdminRole));
        // 未標註的端點默認需要 Viewer 權限
        options.FallbackPolicy = options.GetPolicy(Viewer);
    }
}

internal static class ClaimsPrincipalExtensions
{
    public static string? GetPeerUserId(this ClaimsPrincipal user) => user.FindFirstValue(ClaimTypes.NameIdentifier);

    public static int? GetPeerProcessId(this ClaimsPrincipal user) =>
        int.TryParse(user.FindFirstValue(LocalPeerClaims.ProcessId), CultureInfo.InvariantCulture, out var pid) ? pid : null;

    public static bool IsPeerAdmin(this ClaimsPrincipal user) => user.IsInRole(LocalPeerClaims.AdminRole);
}
