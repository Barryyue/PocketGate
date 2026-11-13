using System.Globalization;
using Avalonia.Data.Converters;

namespace NetworkControl.Android.ViewModels;

/// <summary>XAML 中使用的轉換器。</summary>
public static class Converters
{
    /// <summary>綁定值的字符串形式等於 ConverterParameter 時為 true（用於按語義切換樣式類）。</summary>
    public static readonly IValueConverter IsEqual = new FuncValueConverter<object?, object?, bool>(
        (value, parameter) => string.Equals(Convert.ToString(value, CultureInfo.InvariantCulture), Convert.ToString(parameter, CultureInfo.InvariantCulture), StringComparison.Ordinal));
}
