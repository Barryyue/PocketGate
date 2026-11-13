using CommunityToolkit.Mvvm.ComponentModel;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.ViewModels;

/// <summary>導入預覽對話框：選擇合併或覆蓋，並顯示各自會產生的變化。</summary>
public sealed partial class ImportPreviewViewModel(DisplayText text, string fileName, Proto.ImportResult merge, Proto.ImportResult replace)
    : LocalizedViewModel(text), IValidatingDialogContent
{
    /// <summary>0 合併，1 覆蓋。</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Summary), nameof(ModeHint), nameof(HasRemovals))]
    public partial int ModeIndex { get; set; }

    public Proto.ImportMode Mode => ModeIndex == 1 ? Proto.ImportMode.Replace : Proto.ImportMode.Merge;

    public string FileText => Loc.Format(SettingsLangResourceKind.ImportFileName, fileName);

    public string ModeHint => Loc.Get(ModeIndex == 1 ? SettingsLangResourceKind.ImportModeReplaceHint : SettingsLangResourceKind.ImportModeMergeHint);

    public string Summary => RuleTransferText.Summary(Loc, Current);

    /// <summary>覆蓋會刪除規則時用警告樣式提示。</summary>
    public bool HasRemovals => Current.Removed > 0;

    private Proto.ImportResult Current => ModeIndex == 1 ? replace : merge;

    public bool Validate() => true;
}

/// <summary>恢復備份的確認對話框。</summary>
public sealed class RestoreConfirmViewModel(DisplayText text, string backupTitle, Proto.ImportResult preview)
    : LocalizedViewModel(text), IValidatingDialogContent
{
    public string Message => Loc.Format(SettingsLangResourceKind.RestoreHint, backupTitle);

    public string Summary => RuleTransferText.Summary(Loc, preview);

    public bool HasRemovals => preview.Removed > 0;

    public bool Validate() => true;
}

internal static class RuleTransferText
{
    public static string Summary(LocalizationService loc, Proto.ImportResult result) =>
        result.Added + result.Updated + result.Removed == 0
            ? loc.Get(SettingsLangResourceKind.NothingToChange)
            : loc.Format(SettingsLangResourceKind.ImportSummary, result.Added, result.Updated, result.Removed, result.Unchanged);
}
