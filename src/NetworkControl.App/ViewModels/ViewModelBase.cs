using CommunityToolkit.Mvvm.ComponentModel;
using Grpc.Core;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;

namespace NetworkControl.App.ViewModels;

public abstract class ViewModelBase : ObservableObject;

/// <summary>
/// 帶界面文本的視圖模型。語言切換時默認重新通知所有屬性，
/// 讓 getter 中通過 <see cref="DisplayText"/> 計算的文本刷新為新語言。
/// </summary>
public abstract class LocalizedViewModel : ViewModelBase
{
    protected LocalizedViewModel(DisplayText text)
    {
        Text = text;
        text.Localization.LanguageChanged += (_, _) => OnLanguageChanged();
    }

    protected DisplayText Text { get; }

    protected LocalizationService Loc => Text.Localization;

    protected virtual void OnLanguageChanged() => OnPropertyChanged(string.Empty);
}

/// <summary>主窗口中可導航的頁面。</summary>
public abstract partial class PageViewModel(DisplayText text) : LocalizedViewModel(text)
{
    /// <summary>導航鍵，對應導航菜單項的 ItemKey。</summary>
    public abstract string Key { get; }

    /// <summary>最近一次操作的錯誤信息，顯示在頁面頂部。</summary>
    [ObservableProperty]
    public partial string? ErrorMessage { get; set; }

    /// <summary>頁面變為當前頁時調用，用於加載數據。</summary>
    public virtual Task OnActivatedAsync() => Task.CompletedTask;

    /// <summary>離開頁面時調用，用於停止定時刷新等。</summary>
    public virtual void OnDeactivated()
    {
    }

    /// <summary>
    /// 從其他頁面跳轉過來時帶的參數（見 <see cref="Services.NavigationService"/>），在 <see cref="OnActivatedAsync"/> 之前調用。
    /// 返回 true 表示頁面會自行加載數據，主窗口不再調用 <see cref="OnActivatedAsync"/>。
    /// </summary>
    public virtual bool ApplyNavigation(object parameter) => false;

    /// <summary>執行一個遠程操作，把 gRPC 錯誤轉成用戶可讀的提示。返回是否成功。</summary>
    protected async Task<bool> RunAsync(Func<Task> action)
    {
        try
        {
            ErrorMessage = null;
            await action();
            return true;
        }
        catch (RpcException ex) when (ex.StatusCode == StatusCode.PermissionDenied)
        {
            ErrorMessage = Loc.Get(CommonLangResourceKind.PermissionDenied);
        }
        catch (RpcException ex)
        {
            ErrorMessage = Loc.Format(CommonLangResourceKind.ErrorFormat, ex.Status.Detail);
        }
        catch (InvalidOperationException ex)
        {
            ErrorMessage = Loc.Format(CommonLangResourceKind.ErrorFormat, ex.Message);
        }

        return false;
    }
}

/// <summary>
/// 下拉框 / 分段選擇器中的一個選項。標題按當前語言計算，語言切換後調用 <see cref="Refresh"/>。
/// XAML 中以非泛型基類作為 x:DataType。
/// </summary>
public abstract class Choice(Func<string> title) : ObservableObject
{
    public string Title => title();

    public abstract object? BoxedValue { get; }

    public void Refresh() => OnPropertyChanged(nameof(Title));
}

public sealed class Choice<T>(T value, Func<string> title) : Choice(title)
{
    public T Value { get; } = value;

    public override object? BoxedValue => Value;
}
