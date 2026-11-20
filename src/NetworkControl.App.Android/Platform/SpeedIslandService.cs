using global::Android.App;
using global::Android.Content;
using global::Android.Graphics;
using global::Android.Graphics.Drawables;
using global::Android.OS;
using global::Android.Provider;
using global::Android.Runtime;
using global::Android.Views;
using global::Android.Widget;
using NetworkControl.Android.Infrastructure;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 懸浮即時網速靈動島（原生 WindowManager 膠囊，支援透明度、字號縮放、XY 偏移、狀態欄疊加、鎖屏自動隱藏與全透明點擊穿透）。
/// </summary>
public sealed class SpeedIslandService : IDisposable
{
    private readonly Context _context;
    private readonly UiPreferences _preferences;
    private readonly IWindowManager? _wm;
    private readonly KeyguardManager? _keyguard;
    private TextView? _label;
    private WindowManagerLayoutParams? _layoutParams;
    private bool _attached;

    public SpeedIslandService(Context context, UiPreferences preferences)
    {
        _context = context.ApplicationContext ?? context;
        _preferences = preferences;
        _wm = _context.GetSystemService(Context.WindowService).JavaCast<IWindowManager>();
        _keyguard = _context.GetSystemService(Context.KeyguardService) as KeyguardManager;
    }

    public void Update(bool enabled, string text)
    {
        var locked = _keyguard?.IsKeyguardLocked == true;
        if (!enabled || locked || !Settings.CanDrawOverlays(_context))
        {
            Hide();
            return;
        }

        EnsureShown();
        ApplyAppearance();
        if (_label is not null)
        {
            _label.Text = _preferences.SpeedIslandMode == "mini"
                ? text.Split('·')[0].Trim()
                : text;
        }
    }

    public void Hide()
    {
        if (!_attached || _label is null) return;
        try
        {
            _wm?.RemoveView(_label);
        }
        catch
        {
        }
        finally
        {
            _attached = false;
        }
    }

    private void EnsureShown()
    {
        if (_attached || _wm is null) return;
        try
        {
            if (_label is null)
            {
                var tv = new TextView(_context)
                {
                    Text = "↓ 0 B/s · ↑ 0 B/s",
                };
                tv.SetPadding(24, 10, 24, 10);
                var startX = 0;
                var startY = 0;
                float touchX = 0;
                float touchY = 0;
                var moved = false;
                tv.Touch += (_, e) =>
                {
                    if (_layoutParams is null || e.Event is null) return;
                    switch (e.Event.Action)
                    {
                        case MotionEventActions.Down:
                            startX = _layoutParams.X;
                            startY = _layoutParams.Y;
                            touchX = e.Event.RawX;
                            touchY = e.Event.RawY;
                            moved = false;
                            break;
                        case MotionEventActions.Move:
                            var dx = (int)(e.Event.RawX - touchX);
                            var dy = (int)(e.Event.RawY - touchY);
                            if (Math.Abs(dx) > 6 || Math.Abs(dy) > 6) moved = true;
                            _layoutParams.X = startX + dx;
                            _layoutParams.Y = startY + dy;
                            try { _wm.UpdateViewLayout(_label, _layoutParams); } catch { }
                            break;
                        case MotionEventActions.Up:
                            if (!moved)
                            {
                                var launch = new Intent(_context, typeof(MainActivity)).AddFlags(ActivityFlags.NewTask | ActivityFlags.SingleTop);
                                try { _context.StartActivity(launch); } catch { }
                            }

                            break;
                    }
                };
                _label = tv;
            }

            _layoutParams = BuildLayoutParams();
            _wm.AddView(_label, _layoutParams);
            _attached = true;
        }
        catch
        {
        }
    }

    private void ApplyAppearance()
    {
        if (_label is null) return;
        var opacity = _preferences.SpeedIslandOpacity;
        var scale = _preferences.SpeedIslandFontScale / 100f;
        var baseSize = _preferences.SpeedIslandMode == "mini" ? 10f : 12f;
        _label.TextSize = baseSize * scale;

        var accent = new Color(unchecked((int)_preferences.ThemeColorArgb));
        _label.SetTextColor(accent);

        var alpha = (int)(opacity * 255);
        var bg = new GradientDrawable();
        bg.SetColor(Color.Argb(alpha, 16, 16, 18));
        bg.SetCornerRadius(40f);
        bg.SetStroke(opacity <= 0.01f ? 0 : 2, opacity <= 0.01f ? Color.Transparent : Color.Argb(96, accent.R, accent.G, accent.B));
        _label.Background = bg;

        if (_attached && _layoutParams is not null && _wm is not null)
        {
            var flags = WindowManagerFlags.NotFocusable | WindowManagerFlags.NotTouchModal;
            if (opacity <= 0.01f) flags |= WindowManagerFlags.NotTouchable;
            if (_preferences.SpeedIslandStatusBarOverlay) flags |= WindowManagerFlags.LayoutInScreen | WindowManagerFlags.LayoutNoLimits;
            _layoutParams.Flags = flags;
            try { _wm.UpdateViewLayout(_label, _layoutParams); } catch { }
        }
    }

    private WindowManagerLayoutParams BuildLayoutParams()
    {
        var flags = WindowManagerFlags.NotFocusable | WindowManagerFlags.NotTouchModal;
        if (_preferences.SpeedIslandOpacity <= 0.01f) flags |= WindowManagerFlags.NotTouchable;
        if (_preferences.SpeedIslandStatusBarOverlay) flags |= WindowManagerFlags.LayoutInScreen | WindowManagerFlags.LayoutNoLimits;

        var density = _context.Resources?.DisplayMetrics?.Density ?? 1f;
        var lp = new WindowManagerLayoutParams(
            ViewGroup.LayoutParams.WrapContent,
            ViewGroup.LayoutParams.WrapContent,
            WindowManagerTypes.ApplicationOverlay,
            flags,
            Format.Translucent)
        {
            Gravity = GravityFlags.Top | GravityFlags.CenterHorizontal,
            X = (int)(_preferences.SpeedIslandOffsetX * density),
            Y = Math.Max(0, (int)(((_preferences.SpeedIslandStatusBarOverlay ? 0 : 48) + _preferences.SpeedIslandOffsetY) * density)),
        };
        if (OperatingSystem.IsAndroidVersionAtLeast(30) && _preferences.SpeedIslandStatusBarOverlay)
        {
            lp.LayoutInDisplayCutoutMode = LayoutInDisplayCutoutMode.Always;
        }

        return lp;
    }

    public void Dispose() => Hide();
}
