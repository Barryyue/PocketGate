using System.Text.Json.Serialization;
using NetworkControl.Core.Domain;

namespace NetworkControl.Storage.Serialization;

// 規則導出文件格式（netcontrol-rules）。與數據庫 JSON 列分開：導出文件會被用戶手工編輯、跨版本傳遞，
// 因此反序列化採用嚴格模式（必填字段、可空註解都要求滿足），格式變化時遞增版本號。

/// <summary>只讀取格式名和版本，先判斷是否支持，再按對應版本嚴格解析。</summary>
internal sealed record RuleExportHeader(string? Format = null, int? Version = null);

internal sealed record RuleExportDocument(string Format, int Version, IReadOnlyList<RuleExportDto> Rules);

/// <summary>
/// 導出文件中的一條規則。不包含 <see cref="Rule.BoundProcess"/>：進程綁定只在本機本次運行有意義，
/// 這類規則（以及 Once / UntilRestart）不會被導出。
/// </summary>
internal sealed record RuleExportDto
{
    public required Guid Id { get; init; }

    public Guid? ProfileId { get; init; }

    public AppMatcherDto? App { get; init; }

    public required RuleConditionsDto Conditions { get; init; }

    public required VerdictAction Action { get; init; }

    public required RuleLifetime Lifetime { get; init; }

    public DateTimeOffset? ExpiresAt { get; init; }

    public required RuleSource Source { get; init; }

    /// <summary>缺省為啟用。用可空類型：源生成會用默認值覆蓋 init 屬性的初始值，缺失字段讀出來會變成 false。</summary>
    public bool? Enabled { get; init; }

    public int ManualPriority { get; init; }

    public string? Note { get; init; }

    public required DateTimeOffset CreatedAt { get; init; }

    public required DateTimeOffset UpdatedAt { get; init; }
}

[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    UseStringEnumConverter = true,
    RespectNullableAnnotations = true,
    RespectRequiredConstructorParameters = true,
    WriteIndented = true)]
[JsonSerializable(typeof(RuleExportHeader))]
[JsonSerializable(typeof(RuleExportDocument))]
[JsonSerializable(typeof(RuleExportDto))]
internal sealed partial class RuleExportJsonContext : JsonSerializerContext;
