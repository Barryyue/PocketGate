using Avalonia.Controls;
using Avalonia.Controls.Templates;
using NetworkControl.App.ViewModels;
using NetworkControl.App.Views;

namespace NetworkControl.App.Infrastructure;

/// <summary>
/// 視圖定位器：頁面視圖模型 → 視圖。顯式註冊映射，不使用反射（裁剪 / AOT 安全）。
/// 新增頁面時在 <see cref="Factories"/> 中加一行。
/// </summary>
public sealed class ViewLocator : IDataTemplate
{
    private static readonly Dictionary<Type, Func<Control>> Factories = new()
    {
        [typeof(OverviewViewModel)] = () => new OverviewView(),
        [typeof(AppsViewModel)] = () => new AppsView(),
        [typeof(RulesViewModel)] = () => new RulesView(),
        [typeof(LogViewModel)] = () => new LogView(),
        [typeof(SettingsViewModel)] = () => new SettingsView(),
    };

    public Control? Build(object? param) =>
        param is not null && Factories.TryGetValue(param.GetType(), out var factory)
            ? factory()
            : new TextBlock { Text = $"View not found: {param?.GetType().Name}" };

    public bool Match(object? data) => data is PageViewModel;
}
