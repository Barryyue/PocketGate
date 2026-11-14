using System.Globalization;
using CommunityToolkit.Mvvm.ComponentModel;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Android.Platform;
using NetworkControl.Core.Domain;

namespace NetworkControl.Android.ViewModels;

/// <summary>視圖模型共用的服務。</summary>
public sealed record UiContext(
    IBackend Backend,
    LocalizationService Localization,
    AppearanceService Appearance,
    AppIconLoader Icons,
    PromptNotifier Notifier);

/// <summary>
/// 帶界面文本的視圖模型。語言切換時重新通知所有屬性，讓 getter 中計算的文本刷新為新語言。
/// </summary>
public abstract class LocalizedViewModel : ObservableObject
{
    protected LocalizedViewModel(UiContext context)
    {
        Context = context;
        context.Localization.LanguageChanged += (_, _) => OnLanguageChanged();
    }

    protected UiContext Context { get; }

    protected IBackend Backend => Context.Backend;

    protected LocalizationService Loc => Context.Localization;

    protected CultureInfo Culture => Loc.Culture;

    protected virtual void OnLanguageChanged() => OnPropertyChanged(string.Empty);

    protected string ModeText(FilterMode mode) => Loc.Get(mode switch
    {
        FilterMode.Whitelist => CommonLangResourceKind.ModeWhitelist,
        FilterMode.Blacklist => CommonLangResourceKind.ModeBlacklist,
        FilterMode.BlockAll => CommonLangResourceKind.ModeBlockAll,
        FilterMode.Disabled => CommonLangResourceKind.ModeDisabled,
        _ => CommonLangResourceKind.ModeAsk,
    });

    protected string ModeDescription(FilterMode mode) => Loc.Get(mode switch
    {
        FilterMode.Whitelist => CommonLangResourceKind.ModeWhitelistDescription,
        FilterMode.Blacklist => CommonLangResourceKind.ModeBlacklistDescription,
        FilterMode.BlockAll => CommonLangResourceKind.ModeBlockAllDescription,
        FilterMode.Disabled => CommonLangResourceKind.ModeDisabledDescription,
        _ => CommonLangResourceKind.ModeAskDescription,
    });
}
