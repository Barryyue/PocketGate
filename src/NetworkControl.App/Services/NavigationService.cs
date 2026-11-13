namespace NetworkControl.App.Services;

/// <summary>
/// 頁面之間的跳轉（例如從總覽排行榜跳到篩選好的連線記錄）。頁面不直接依賴主窗口視圖模型，
/// 避免循環依賴；主窗口訂閱 <see cref="Requested"/> 切換頁面並把參數交給目標頁。
/// </summary>
public sealed class NavigationService
{
    public event EventHandler<NavigationRequest>? Requested;

    public void Navigate(string page, object? parameter = null) =>
        Requested?.Invoke(this, new NavigationRequest(page, parameter));
}

public sealed record NavigationRequest(string Page, object? Parameter);

/// <summary>跳轉到規則頁時的搜索關鍵字（例如應用路徑）。</summary>
public sealed record RulesNavigation(string Keyword);

/// <summary>跳轉到連線記錄頁時的篩選條件。</summary>
/// <param name="ActionFilter">0 全部，1 允許，2 阻斷。</param>
public sealed record LogNavigation(string Keyword, int ActionFilter = 0);
