using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Microsoft.AspNetCore.Authorization;
using NetworkControl.Core.Activity;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Matching;
using NetworkControl.Core.Rules;
using NetworkControl.Service.Security;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.Service.Grpc;

/// <summary>應用視圖：連接統計（activity.db）+ 應用信息（config.db）+ 當前規則集中指定該應用的規則數。</summary>
[Authorize(Policy = IpcPolicies.Viewer)]
internal sealed class AppsGrpcService(IActivityQuery activity, IAppRepository apps, RuleEngine rules, TimeProvider time) : Proto.Apps.AppsBase
{
    public override async Task<Proto.ListAppsResponse> List(Proto.ListAppsRequest request, ServerCallContext context)
    {
        var to = request.To?.ToDateTimeOffset() ?? time.GetUtcNow();
        var from = request.From?.ToDateTimeOffset() ?? to.AddDays(-7);
        if (from >= to)
        {
            throw new RpcException(new Status(StatusCode.InvalidArgument, "時間範圍無效。"));
        }

        var entries = await activity.ListAppActivityAsync(from, to, request.Limit > 0 ? request.Limit : 500, context.CancellationToken);
        var appRules = rules.Current.Rules
            .Where(r => r.Enabled && r.App is not null)
            .Select(r => (Pattern: PathPattern.Parse(r.App!.PathPattern), r.Action))
            .ToArray();

        var response = new Proto.ListAppsResponse();
        foreach (var entry in entries)
        {
            var identity = entry.AppId is { } id ? await apps.GetAsync(id, context.CancellationToken) : null;
            var path = identity?.Path ?? entry.Path ?? "";
            var summary = new Proto.AppSummary
            {
                Key = entry.AppId is { } appId ? $"app:{appId}" : $"path:{path}",
                Path = path,
                DisplayName = identity?.Name ?? entry.Name ?? (path.Length > 0 ? Path.GetFileNameWithoutExtension(path.Replace('\\', '/').Split('/')[^1]) : ""),
                IsPlatform = identity?.Signature?.IsPlatformBinary ?? false,
                Allowed = entry.Allowed,
                Blocked = entry.Blocked,
                LastSeen = Timestamp.FromDateTimeOffset(entry.LastSeen),
            };
            if (entry.AppId is { } known)
            {
                summary.AppId = known;
            }

            if (identity?.Signature?.SignerId is { } signer)
            {
                summary.Signer = signer;
            }

            if (path.Length > 0)
            {
                foreach (var (pattern, action) in appRules)
                {
                    if (pattern.Matches(path))
                    {
                        if (action == VerdictAction.Allow)
                        {
                            summary.AllowRules++;
                        }
                        else
                        {
                            summary.DenyRules++;
                        }
                    }
                }
            }

            response.Apps.Add(summary);
        }

        return response;
    }
}
