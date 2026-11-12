using AtomUI.Controls.Commons;
using Avalonia.Data.Converters;

namespace NetworkControl.App.ViewModels;

/// <summary>XAML 中使用的值轉換器。</summary>
public static class Converters
{
    /// <summary>連接狀態 → 狀態點。</summary>
    public static readonly IValueConverter ConnectionBadge =
        new FuncValueConverter<bool, DotBadgeStatus?>(connected => connected ? DotBadgeStatus.Success : DotBadgeStatus.Error);

    /// <summary>非空字符串 → 可見。</summary>
    public static readonly IValueConverter HasText =
        new FuncValueConverter<string?, bool>(text => !string.IsNullOrEmpty(text));
}
