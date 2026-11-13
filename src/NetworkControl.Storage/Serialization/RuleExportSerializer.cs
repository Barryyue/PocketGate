using System.Text.Json;
using NetworkControl.Core.Domain;

namespace NetworkControl.Storage.Serialization;

/// <summary>
/// 規則導出文件（<c>{ "format": "netcontrol-rules", "version": 1, "rules": [...] }</c>）的序列化與校驗（FR-SET-03 第一步）。
/// </summary>
/// <remarks>
/// 這裡只負責格式：導入結果原樣保留 <see cref="Rule.Id"/>、<see cref="Rule.ProfileId"/>、<see cref="Rule.Source"/>，
/// 「合併 / 覆蓋」、Id 衝突、目標機器上不存在的配置文件等策略由接入 IPC 的一方決定。
/// 路徑佔位符必須按發起請求的用戶構造，見 <see cref="PathPlaceholderMapper"/>。
/// </remarks>
public static class RuleExportSerializer
{
    public const string Format = "netcontrol-rules";
    public const int Version = 1;

    /// <summary>
    /// 是否會被導出：內置規則每台機器都有，臨時規則（僅本次、進程退出前、重啟前）離開本機沒有意義。
    /// 界面可以據此提示「有 N 條規則不會被導出」。
    /// </summary>
    public static bool IsExportable(Rule rule)
    {
        ArgumentNullException.ThrowIfNull(rule);
        return rule.Source != RuleSource.Builtin && IsPortableLifetime(rule.Lifetime);
    }

    public static string Export(IEnumerable<Rule> rules, PathPlaceholderMapper paths)
    {
        ArgumentNullException.ThrowIfNull(rules);
        ArgumentNullException.ThrowIfNull(paths);

        var exported = rules.Where(IsExportable).Select(rule => ToExport(rule, paths)).ToArray();
        return JsonSerializer.Serialize(new RuleExportDocument(Format, Version, exported), RuleExportJsonContext.Default.RuleExportDocument);
    }

    /// <exception cref="FormatException">文件不是受支持的格式或版本，或者內容無效。</exception>
    public static IReadOnlyList<Rule> Import(string json, PathPlaceholderMapper paths)
    {
        ArgumentNullException.ThrowIfNull(json);
        ArgumentNullException.ThrowIfNull(paths);

        // 先只看格式名和版本：未來版本的字段結構可能不同，應報「版本不支持」而不是「JSON 無效」
        var header = Parse(json, RuleExportJsonContext.Default.RuleExportHeader);
        if (!string.Equals(header.Format, Format, StringComparison.Ordinal))
        {
            throw new FormatException($"不是 NetworkControl 規則導出文件（format：{header.Format ?? "缺失"}）。");
        }

        if (header.Version != Version)
        {
            throw new FormatException($"不支持的規則導出版本：{header.Version?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "缺失"}（當前支持 {Version}）。");
        }

        var document = Parse(json, RuleExportJsonContext.Default.RuleExportDocument);
        var rules = new Rule[document.Rules.Count];
        for (var i = 0; i < rules.Length; i++)
        {
            try
            {
                rules[i] = ToDomain(document.Rules[i] ?? throw new FormatException("規則不能為 null。"), paths);
            }
            catch (Exception ex) when (ex is FormatException or ArgumentException or OverflowException)
            {
                throw new FormatException($"第 {i + 1} 條規則無效：{ex.Message}", ex);
            }
        }

        return rules;
    }

    /// <summary>
    /// 規則內容的指紋（導出格式的 JSON，不做路徑替換）：用於判斷導入的規則與本機規則是否相同。
    /// 不能用記錄的相等比較：<see cref="AddressTarget"/> 的網段列表按引用比較。
    /// </summary>
    internal static string Fingerprint(Rule rule) =>
        JsonSerializer.Serialize(ToExport(rule, PathPlaceholderMapper.None), RuleExportJsonContext.Default.RuleExportDto);

    private static T Parse<T>(string json, System.Text.Json.Serialization.Metadata.JsonTypeInfo<T> typeInfo)
    {
        try
        {
            return JsonSerializer.Deserialize(json, typeInfo) ?? throw new FormatException("規則導出文件不能為空。");
        }
        catch (Exception ex) when (ex is JsonException or NotSupportedException)
        {
            // NotSupportedException：例如遠端目標缺少 kind，無法確定具體類型
            throw new FormatException($"規則導出文件格式無效：{ex.Message}", ex);
        }
    }

    private static bool IsPortableLifetime(RuleLifetime lifetime) => lifetime is RuleLifetime.Permanent or RuleLifetime.Timed;

    private static RuleExportDto ToExport(Rule rule, PathPlaceholderMapper paths) => new()
    {
        Id = rule.Id,
        ProfileId = rule.ProfileId,
        App = rule.App is { } app ? app.ToDto() with { PathPattern = paths.ToPlaceholder(app.PathPattern) } : null,
        Conditions = rule.Conditions.ToDto(),
        Action = rule.Action,
        Lifetime = rule.Lifetime,
        ExpiresAt = rule.ExpiresAt,
        Source = rule.Source,
        Enabled = rule.Enabled,
        ManualPriority = rule.ManualPriority,
        Note = rule.Note,
        CreatedAt = rule.CreatedAt,
        UpdatedAt = rule.UpdatedAt,
    };

    private static Rule ToDomain(RuleExportDto dto, PathPlaceholderMapper paths)
    {
        RequireDefined(dto.Action, "action");
        RequireDefined(dto.Lifetime, "lifetime");
        RequireDefined(dto.Source, "source");
        if (!IsPortableLifetime(dto.Lifetime))
        {
            throw new FormatException($"導出文件不支持時效 {dto.Lifetime}。");
        }

        if (dto.Lifetime == RuleLifetime.Timed && dto.ExpiresAt is null)
        {
            throw new FormatException("限時規則缺少 expiresAt。");
        }

        ValidateConditions(dto.Conditions);
        if (dto.App is { } app && string.IsNullOrWhiteSpace(app.PathPattern))
        {
            throw new FormatException("應用路徑不能為空。");
        }

        return new Rule
        {
            Id = dto.Id,
            ProfileId = dto.ProfileId,
            App = dto.App is { } matcher ? matcher.ToDomain() with { PathPattern = paths.FromPlaceholder(matcher.PathPattern) } : null,
            Conditions = dto.Conditions.ToDomain(),
            Action = dto.Action,
            Lifetime = dto.Lifetime,
            ExpiresAt = dto.ExpiresAt,
            Source = dto.Source,
            Enabled = dto.Enabled ?? true,
            ManualPriority = dto.ManualPriority,
            Note = dto.Note,
            CreatedAt = dto.CreatedAt,
            UpdatedAt = dto.UpdatedAt,
        };
    }

    private static void ValidateConditions(RuleConditionsDto conditions)
    {
        if (conditions.Direction is { } direction)
        {
            RequireDefined(direction, "direction");
        }

        if (conditions.Protocol is { } protocol)
        {
            RequireDefined(protocol, "protocol");
        }

        ValidatePorts(conditions.RemotePort, "remotePort");
        ValidatePorts(conditions.LocalPort, "localPort");
        switch (conditions.Remote)
        {
            case DomainTargetDto domain when string.IsNullOrWhiteSpace(domain.Pattern):
                throw new FormatException("域名不能為空。");
            case AddressTargetDto address when address.Networks.Length == 0:
                throw new FormatException("地址列表不能為空。");
            case ZoneTargetDto zone:
                RequireDefined(zone.Zone, "zone");
                break;
        }
    }

    // DtoMapper 會把端口直接轉成 ushort，這裡先檢查範圍，避免 70000 之類的值被截斷成另一個端口
    private static void ValidatePorts(PortRangeDto? range, string name)
    {
        if (range is { } r && (r.From is < 0 or > ushort.MaxValue || r.To is < 0 or > ushort.MaxValue || r.From > r.To))
        {
            throw new FormatException($"{name} 無效：{r.From}-{r.To}。");
        }
    }

    private static void RequireDefined<TEnum>(TEnum value, string name)
        where TEnum : struct, Enum
    {
        if (!Enum.IsDefined(value))
        {
            throw new FormatException($"{name} 的值 {value} 無效。");
        }
    }
}
