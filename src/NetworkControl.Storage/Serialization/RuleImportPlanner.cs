using NetworkControl.Core.Domain;

namespace NetworkControl.Storage.Serialization;

/// <summary>導入方式（FR-SET-03）。</summary>
public enum RuleImportMode
{
    /// <summary>合併：新增文件中有而本機沒有的規則，更新 Id 相同但內容不同的規則，不刪除本機規則。</summary>
    Merge = 0,

    /// <summary>覆蓋：在合併的基礎上，刪除本機可導出（永久 / 限時、非內置）但文件中沒有的規則。臨時規則不受影響。</summary>
    Replace = 1,
}

/// <summary>導入計劃：先預覽差異，用戶確認後再執行。</summary>
public sealed record RuleImportPlan(IReadOnlyList<Rule> ToAdd, IReadOnlyList<Rule> ToUpdate, IReadOnlyList<Rule> ToRemove, int Unchanged);

/// <summary>計算導入差異（純函數，不訪問存儲）。</summary>
public static class RuleImportPlanner
{
    /// <param name="markNewAsImported">新增的規則標記為 <see cref="RuleSource.Import"/>（從文件導入）；從本機備份恢復時保留原來源。</param>
    public static RuleImportPlan Plan(IReadOnlyCollection<Rule> existing, IReadOnlyList<Rule> imported, RuleImportMode mode, bool markNewAsImported = true)
    {
        ArgumentNullException.ThrowIfNull(existing);
        ArgumentNullException.ThrowIfNull(imported);

        var local = existing.ToDictionary(r => r.Id);
        var incoming = new Dictionary<Guid, Rule>();
        foreach (var rule in imported)
        {
            incoming[rule.Id] = rule; // 文件中重複的 Id 以最後一條為準
        }

        var toAdd = new List<Rule>();
        var toUpdate = new List<Rule>();
        var unchanged = 0;
        foreach (var rule in incoming.Values)
        {
            if (!local.TryGetValue(rule.Id, out var current))
            {
                // 來自其他機器或已刪除的規則：標記為導入，便於在規則頁區分
                toAdd.Add(rule with { Source = markNewAsImported ? RuleSource.Import : rule.Source, BoundProcess = null });
                continue;
            }

            if (current.Source == RuleSource.Builtin)
            {
                unchanged++; // 內置規則由程序生成，不被文件改寫
                continue;
            }

            // 保留本機的來源和時間戳，只比較規則內容
            var candidate = rule with { Source = current.Source, CreatedAt = current.CreatedAt, UpdatedAt = current.UpdatedAt, BoundProcess = current.BoundProcess };
            if (RuleExportSerializer.Fingerprint(candidate) == RuleExportSerializer.Fingerprint(current))
            {
                unchanged++;
            }
            else
            {
                toUpdate.Add(candidate);
            }
        }

        var toRemove = mode == RuleImportMode.Replace
            ? existing.Where(r => RuleExportSerializer.IsExportable(r) && !incoming.ContainsKey(r.Id)).ToArray()
            : [];

        return new RuleImportPlan(toAdd, toUpdate, toRemove, unchanged);
    }
}
