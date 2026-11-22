using System.Net.Sockets;
using global::Android.Content;
using global::Android.Content.Res;
using global::Android.Graphics;
using global::Android.Graphics.Drawables;
using global::Android.Net;
using global::Android.OS;
using global::Android.Provider;
using global::Android.Runtime;
using global::Android.Text;
using global::Android.Text.Style;
using global::Android.Util;
using global::Android.Views;
using global::Android.Widget;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Android.Localization;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Prompts;
using NetworkControl.Engine.Vpn.Platform;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 系統級懸浮詢問面板（仿紅魔底部 / 頂部面板，與 Kotlin 版 PromptOverlayController 對齊）。
/// 只使用原生 Android View 並統一在主執行緒操作 WindowManager。
/// </summary>
public sealed class PromptOverlayService : IDisposable
{
    private readonly Context _context;
    private readonly UiPreferences _preferences;
    private readonly PromptBroker _prompts;
    private readonly IWindowManager? _windowManager;
    private readonly Handler _handler = new(Looper.MainLooper!);

    private View? _view;
    private Guid? _currentId;
    private Guid? _dismissedId;
    private Java.Lang.Runnable? _holdRunnable;

    public PromptOverlayService(Context context, UiPreferences preferences, PromptBroker prompts)
    {
        _context = context.ApplicationContext ?? context;
        _preferences = preferences;
        _prompts = prompts;
        _windowManager = _context.GetSystemService(Context.WindowService).JavaCast<IWindowManager>();
    }

    public bool CanDrawOverlays() => Settings.CanDrawOverlays(_context);

    public void Show(PromptSnapshot prompt)
    {
        if (!_preferences.PromptOverlayEnabled || !CanDrawOverlays() || _windowManager is null)
        {
            Hide();
            return;
        }

        _handler.Post(() =>
        {
            if (_dismissedId == prompt.Id) return;
            if (_currentId == prompt.Id && _view is not null) return;

            RemoveView();
            var panel = BuildPanel(prompt);
            var layoutParams = BuildLayoutParams();
            try
            {
                _windowManager.AddView(panel, layoutParams);
                _view = panel;
                _currentId = prompt.Id;
                StartHold(prompt.Id);
            }
            catch
            {
                // 權限剛撤銷或窗口添加被系統拒絕時靜默回退至通知
            }
        });
    }

    public void Hide()
    {
        _handler.Post(RemoveView);
    }

    public void HideIfCurrent(Guid promptId)
    {
        _handler.Post(() =>
        {
            if (_currentId == promptId)
            {
                RemoveView();
            }
        });
    }

    public void Dispose()
    {
        Hide();
        _handler.Dispose();
    }

    private void RemoveView()
    {
        if (_holdRunnable is not null)
        {
            _handler.RemoveCallbacks(_holdRunnable);
            _holdRunnable.Dispose();
            _holdRunnable = null;
        }

        if (_view is not null)
        {
            try
            {
                _windowManager?.RemoveView(_view);
            }
            catch
            {
            }

            _view = null;
        }

        _currentId = null;
    }

    private void StartHold(Guid promptId)
    {
        _holdRunnable = new Java.Lang.Runnable(() =>
        {
            if (_currentId != promptId) return;
            _prompts.PauseTimer(promptId);
            if (_holdRunnable is not null)
            {
                _handler.PostDelayed(_holdRunnable, 10_000);
            }
        });
        _handler.PostDelayed(_holdRunnable, 10_000);
    }

    private WindowManagerLayoutParams BuildLayoutParams()
    {
        var isTop = _preferences.PromptOverlayPosition == "top";
        var layoutParams = new WindowManagerLayoutParams(
            ViewGroup.LayoutParams.MatchParent,
            ViewGroup.LayoutParams.WrapContent,
            WindowManagerTypes.ApplicationOverlay,
            WindowManagerFlags.NotTouchModal | WindowManagerFlags.LayoutInScreen,
            Format.Translucent)
        {
            Gravity = (isTop ? GravityFlags.Top : GravityFlags.Bottom) | GravityFlags.FillHorizontal,
            Title = "PocketGate prompt",
            PackageName = _context.PackageName,
        };
        if (isTop)
        {
            layoutParams.X = Dp(14);
            layoutParams.Y = Dp(48);
        }

        if (Build.VERSION.SdkInt >= BuildVersionCodes.P)
        {
            layoutParams.LayoutInDisplayCutoutMode = LayoutInDisplayCutoutMode.Never;
        }

        return layoutParams;
    }

    private LinearLayout BuildPanel(PromptSnapshot prompt)
    {
        var localizer = NotificationText.Current;
        var night = ((_context.Resources?.Configuration?.UiMode ?? 0) & UiMode.NightMask) == UiMode.NightYes;
        var accent = new Color(unchecked((int)_preferences.ThemeColorArgb));
        var surface = night ? Color.Rgb(0x2B, 0x29, 0x30) : Color.White;
        var surfaceContainer = night ? Color.Rgb(0x38, 0x35, 0x3E) : Color.Rgb(0xF3, 0xF4, 0xF6);
        var onSurface = night ? Color.Rgb(0xE6, 0xE1, 0xE5) : Color.Rgb(0x1C, 0x1B, 0x1F);
        var onSurfaceVariant = night ? Color.Rgb(0xCA, 0xC4, 0xD0) : Color.Rgb(0x49, 0x45, 0x4F);
        var divider = night ? Color.Rgb(0x49, 0x45, 0x4F) : Color.Rgb(0xE0, 0xE0, 0xE0);
        var error = night ? Color.Rgb(0xF2, 0xB8, 0xB5) : Color.Rgb(0xB3, 0x26, 0x1E);
        var isSheet = _preferences.PromptOverlayPosition != "top";

        var root = new LinearLayout(_context)
        {
            Orientation = global::Android.Widget.Orientation.Vertical,
            Elevation = Dp(12),
        };
        root.SetPadding(Dp(22), Dp(20), Dp(22), Dp(8));

        var radius = (float)Dp(22);
        var bg = new GradientDrawable();
        bg.SetColor(surface);
        bg.SetCornerRadii(isSheet
            ? [radius, radius, radius, radius, 0f, 0f, 0f, 0f]
            : [radius, radius, radius, radius, radius, radius, radius, radius]);
        if (!isSheet)
        {
            bg.SetStroke(Dp(1), divider);
        }

        root.Background = bg;

        var appName = prompt.App?.Name ?? prompt.ExecutablePath ?? localizer.Get(_context, CommonLangResourceKind.UnknownApp);
        var pkgName = PackageAppPath.TryGetPackage(prompt.App?.Path ?? prompt.ExecutablePath, out var pkg) ? pkg : null;
        var networkKind = DetectNetworkKind();
        var networkLabel = localizer.Get(_context, networkKind switch
        {
            NetworkKind.Wifi => PromptLangResourceKind.NetWlan,
            NetworkKind.Cellular => PromptLangResourceKind.NetMobile,
            _ => PromptLangResourceKind.NetAny,
        });

        // 頂部應用識別列：圓角應用圖示 + 加粗應用名 + 包名 + 網路膠囊標籤
        var header = new LinearLayout(_context)
        {
            Orientation = global::Android.Widget.Orientation.Horizontal,
        };
        header.SetGravity(GravityFlags.CenterVertical);

        var iconView = new ImageView(_context);
        var appIcon = LoadAppIcon(pkgName);
        if (appIcon is not null)
        {
            iconView.SetImageDrawable(appIcon);
            iconView.SetScaleType(ImageView.ScaleType.FitCenter);
            iconView.ClipToOutline = true;
            iconView.OutlineProvider = ViewOutlineProvider.Background;
            var iconBg = new GradientDrawable();
            iconBg.SetColor(surfaceContainer);
            iconBg.SetCornerRadius(Dp(12));
            iconView.Background = iconBg;
        }
        else
        {
            iconView.SetImageResource(Resource.Drawable.ic_stat_shield);
            iconView.ImageTintList = ColorStateList.ValueOf(accent);
            iconView.SetScaleType(ImageView.ScaleType.CenterInside);
            iconView.SetPadding(Dp(10), Dp(10), Dp(10), Dp(10));
            var iconBg = new GradientDrawable();
            iconBg.SetColor(Color.Argb(0x1F, accent.R, accent.G, accent.B));
            iconBg.SetCornerRadius(Dp(12));
            iconView.Background = iconBg;
        }

        header.AddView(iconView, new LinearLayout.LayoutParams(Dp(46), Dp(46)));

        var titleCol = new LinearLayout(_context)
        {
            Orientation = global::Android.Widget.Orientation.Vertical,
        };
        titleCol.SetPadding(Dp(12), 0, Dp(8), 0);
        titleCol.AddView(CreateText(appName, 18f, onSurface, bold: true, maxLines: 1));
        if (!string.IsNullOrWhiteSpace(pkgName) && !string.Equals(pkgName, appName, StringComparison.Ordinal))
        {
            titleCol.AddView(CreateText(pkgName, 12f, onSurfaceVariant, bold: false, maxLines: 1));
        }

        header.AddView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WrapContent, 1f));

        var netBadge = CreateText(networkLabel, 12f, accent, bold: true, maxLines: 1);
        netBadge.SetPadding(Dp(10), Dp(4), Dp(10), Dp(4));
        var badgeBg = new GradientDrawable();
        badgeBg.SetColor(Color.Argb(0x22, accent.R, accent.G, accent.B));
        badgeBg.SetCornerRadius(Dp(999));
        badgeBg.SetStroke(Dp(1), Color.Argb(0x55, accent.R, accent.G, accent.B));
        netBadge.Background = badgeBg;
        header.AddView(netBadge);
        root.AddView(header);

        // 主詢問文案：將應用名稱加粗並以主題色高亮
        var ownName = _context.ApplicationInfo?.LoadLabel(_context.PackageManager!).ToString() ?? "PocketGate";
        var rawMessage = localizer.Format(_context, PromptLangResourceKind.RequestMessage, ownName, appName, networkLabel);
        var spannable = new SpannableString(rawMessage);
        var appStart = rawMessage.IndexOf(appName, ownName.Length, StringComparison.Ordinal);
        if (appStart < 0)
        {
            appStart = rawMessage.IndexOf(appName, StringComparison.Ordinal);
        }

        if (appStart >= 0)
        {
            var appEnd = appStart + appName.Length;
            spannable.SetSpan(new StyleSpan(TypefaceStyle.Bold), appStart, appEnd, SpanTypes.ExclusiveExclusive);
            spannable.SetSpan(new ForegroundColorSpan(accent), appStart, appEnd, SpanTypes.ExclusiveExclusive);
        }

        var messageView = new TextView(_context)
        {
            TextFormatted = spannable,
            TextSize = 16f,
        };
        messageView.SetTextColor(onSurface);
        messageView.SetMaxLines(4);
        messageView.SetLineSpacing(Dp(4), 1f);
        messageView.SetPadding(0, Dp(12), 0, 0);
        root.AddView(messageView);

        if (_preferences.PromptShowDetails && prompt.Flows.Count > 0)
        {
            var flow = prompt.Flows[^1];
            var target = flow.Domains.Count > 0
                ? flow.Domains[0]
                : flow.Remote.AddressFamily == AddressFamily.InterNetworkV6
                    ? $"[{flow.Remote.Address}]:{flow.Remote.Port}"
                    : $"{flow.Remote.Address}:{flow.Remote.Port}";
            var proto = flow.Protocol == L4Protocol.Udp ? "UDP" : "TCP";
            var more = prompt.Flows.Count > 1
                ? " · " + localizer.Format(_context, PromptLangResourceKind.MoreConnections, prompt.Flows.Count)
                : "";
            var detailView = CreateText($"{localizer.Get(_context, PromptLangResourceKind.Target)}：{target} · {proto}{more}", 12.5f, onSurfaceVariant, bold: false, maxLines: 2);
            detailView.SetPadding(Dp(12), Dp(8), Dp(12), Dp(8));
            var detailBg = new GradientDrawable();
            detailBg.SetColor(surfaceContainer);
            detailBg.SetCornerRadius(Dp(10));
            detailView.Background = detailBg;
            var detailLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MatchParent, ViewGroup.LayoutParams.WrapContent)
            {
                TopMargin = Dp(10),
            };
            root.AddView(detailView, detailLp);
        }

        if (prompt.IsTamperAlert)
        {
            var tamperView = CreateText(localizer.Get(_context, PromptLangResourceKind.TamperHint), 13f, error, bold: true, maxLines: 3);
            tamperView.SetPadding(0, Dp(8), 0, 0);
            root.AddView(tamperView);
        }

        var denyPermanent = _preferences.PromptDenyPermanent;
        var dontAskAgain = false;
        var disabledActionColor = Color.Argb(0x61, onSurface.R, onSurface.G, onSurface.B);
        var allowButtons = new List<TextView>();
        void UpdateAllowButtonsEnabled(bool enabled)
        {
            foreach (var button in allowButtons)
            {
                button.Enabled = enabled;
                button.Clickable = enabled;
                button.SetTextColor(enabled ? accent : disabledActionColor);
                button.Background = enabled
                    ? new RippleDrawable(
                        ColorStateList.ValueOf(Color.Argb(0x33, accent.R, accent.G, accent.B)),
                        null,
                        new ColorDrawable(Color.White))
                    : null;
            }
        }

        if (!denyPermanent)
        {
            var dontAsk = new RadioButton(_context)
            {
                Text = localizer.Get(_context, PromptLangResourceKind.DontAskAgain),
                TextSize = 15f,
            };
            dontAsk.SetTextColor(onSurface);
            dontAsk.ButtonTintList = new ColorStateList(
                [[global::Android.Resource.Attribute.StateChecked], []],
                [accent.ToArgb(), onSurfaceVariant.ToArgb()]);
            dontAsk.Click += (_, _) =>
            {
                dontAskAgain = !dontAskAgain;
                dontAsk.Checked = dontAskAgain;
                UpdateAllowButtonsEnabled(!dontAskAgain);
            };
            var dontAskLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WrapContent, ViewGroup.LayoutParams.WrapContent)
            {
                TopMargin = Dp(8),
                MarginStart = -Dp(6),
            };
            root.AddView(dontAsk, dontAskLp);
        }

        // 橫向操作列：分隔線隔開的等寬單行按鈕（禁止 | 允許 | 僅本次 | 限時）
        var actions = new LinearLayout(_context)
        {
            Orientation = global::Android.Widget.Orientation.Horizontal,
        };
        actions.SetGravity(GravityFlags.CenterVertical);
        actions.SetPadding(0, Dp(8), 0, 0);

        void AddAction(string label, Action onClick, bool isAllowAction = true)
        {
            if (actions.ChildCount > 0)
            {
                var sep = new View(_context);
                sep.SetBackgroundColor(divider);
                actions.AddView(sep, new LinearLayout.LayoutParams(Dp(1), Dp(22)));
            }

            var btn = CreateText(label, 17f, accent, bold: true, maxLines: 1);
            btn.Gravity = GravityFlags.Center;
            btn.SetPadding(Dp(4), 0, Dp(4), 0);
            btn.SetAutoSizeTextTypeUniformWithConfiguration(11, 17, 1, (int)ComplexUnitType.Sp);
            btn.Background = new RippleDrawable(
                ColorStateList.ValueOf(Color.Argb(0x33, accent.R, accent.G, accent.B)),
                null,
                new ColorDrawable(Color.White));
            btn.Click += (_, _) =>
            {
                if (!btn.Enabled) return;
                _dismissedId = prompt.Id;
                onClick();
                RemoveView();
            };
            if (isAllowAction)
            {
                allowButtons.Add(btn);
            }

            actions.AddView(btn, new LinearLayout.LayoutParams(0, Dp(52), 1f));
        }

        AddAction(localizer.Get(_context, PromptLangResourceKind.Deny), () =>
        {
            var lifetime = denyPermanent || dontAskAgain ? RuleLifetime.Permanent : RuleLifetime.UntilRestart;
            SubmitAnswer(prompt.Id, VerdictAction.Deny, lifetime, null);
        }, isAllowAction: false);
        AddAction(localizer.Get(_context, CommonLangResourceKind.Allow), () =>
            SubmitAnswer(prompt.Id, VerdictAction.Allow, RuleLifetime.Permanent, null));
        AddAction(localizer.Get(_context, PromptLangResourceKind.Once), () =>
            SubmitAnswer(prompt.Id, VerdictAction.Allow, RuleLifetime.UntilRestart, null));

        if (_preferences.PromptShowTimedOption)
        {
            var minutes = _preferences.PromptTimedMinutes;
            AddAction(localizer.Format(_context, PromptLangResourceKind.TimedShort, minutes), () =>
                SubmitAnswer(prompt.Id, VerdictAction.Allow, RuleLifetime.Timed, TimeSpan.FromMinutes(minutes)));
        }

        root.AddView(actions);
        return root;
    }

    private void SubmitAnswer(Guid promptId, VerdictAction action, RuleLifetime lifetime, TimeSpan? duration)
    {
        _ = Task.Run(async () =>
        {
            await _prompts.AnswerAsync(promptId, new PromptDecision
            {
                Action = action,
                Lifetime = lifetime,
                Duration = duration,
                Scope = PromptScope.AnyConnection,
            }, CancellationToken.None).ConfigureAwait(false);
            PromptNotifier.Cancel(_context, promptId);
        });
    }

    private Drawable? LoadAppIcon(string? packageName)
    {
        if (string.IsNullOrWhiteSpace(packageName)) return null;
        try
        {
            return _context.PackageManager?.GetApplicationIcon(packageName);
        }
        catch
        {
            return null;
        }
    }

    private NetworkKind DetectNetworkKind()
    {
        try
        {
            var cm = _context.GetSystemService(Context.ConnectivityService) as ConnectivityManager;
            var caps = cm?.GetNetworkCapabilities(cm.ActiveNetwork);
            if (caps is null) return NetworkKind.Any;
            if (caps.HasTransport(TransportType.Wifi) || caps.HasTransport(TransportType.Ethernet)) return NetworkKind.Wifi;
            if (caps.HasTransport(TransportType.Cellular)) return NetworkKind.Cellular;
        }
        catch
        {
        }

        return NetworkKind.Any;
    }

    private TextView CreateText(string text, float sizeSp, Color color, bool bold, int maxLines)
    {
        var tv = new TextView(_context)
        {
            Text = text,
            TextSize = sizeSp,
        };
        tv.SetTextColor(color);
        if (bold)
        {
            tv.SetTypeface(tv.Typeface, TypefaceStyle.Bold);
        }

        tv.SetMaxLines(maxLines);
        tv.Ellipsize = TextUtils.TruncateAt.End;
        return tv;
    }

    private int Dp(int value) => (int)(value * (_context.Resources?.DisplayMetrics?.Density ?? 1f));

    private enum NetworkKind
    {
        Any,
        Wifi,
        Cellular,
    }
}
