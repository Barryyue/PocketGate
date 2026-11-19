using global::Android.Content;
using global::Android.Graphics;
using global::Android.Graphics.Drawables;
using global::Android.Provider;
using global::Android.Runtime;
using global::Android.Views;
using global::Android.Widget;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 懸浮即時網速靈動島（原生 WindowManager 膠囊，支援拖曳與自動隱藏）。
/// </summary>
public sealed class SpeedIslandService(Context context) : IDisposable
{
    private readonly Context _context = context.ApplicationContext ?? context;
    private readonly IWindowManager? _wm = (context.ApplicationContext ?? context).GetSystemService(Context.WindowService).JavaCast<IWindowManager>();
    private TextView? _label;
    private WindowManagerLayoutParams? _layoutParams;
    private bool _attached;

    public void Update(bool enabled, string text)
    {
        if (!enabled || !Settings.CanDrawOverlays(_context))
        {
            Hide();
            return;
        }

        EnsureShown();
        if (_label is not null)
        {
            _label.Text = text;
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
                    TextSize = 11f,
                    Text = "↓ 0 B/s · ↑ 0 B/s",
                };
                tv.SetTextColor(Color.White);
                tv.SetPadding(24, 10, 24, 10);
                var bg = new GradientDrawable();
                bg.SetColor(Color.Argb(220, 14, 24, 18));
                bg.SetCornerRadius(40f);
                bg.SetStroke(2, Color.Argb(120, 52, 211, 153));
                tv.Background = bg;

                var lp = new WindowManagerLayoutParams(
                    ViewGroup.LayoutParams.WrapContent,
                    ViewGroup.LayoutParams.WrapContent,
                    WindowManagerTypes.ApplicationOverlay,
                    WindowManagerFlags.NotFocusable | WindowManagerFlags.LayoutInScreen,
                    Format.Translucent)
                {
                    Gravity = GravityFlags.Top | GravityFlags.CenterHorizontal,
                    Y = 48,
                };
                tv.Touch += OnTouch;
                _label = tv;
                _layoutParams = lp;
            }

            _wm.AddView(_label, _layoutParams);
            _attached = true;
        }
        catch
        {
            _attached = false;
        }
    }

    private float _downRawX, _downRawY;
    private int _startX, _startY;

    private void OnTouch(object? sender, View.TouchEventArgs e)
    {
        if (e.Event is null || _layoutParams is null || _label is null) return;
        switch (e.Event.Action)
        {
            case MotionEventActions.Down:
                _downRawX = e.Event.RawX;
                _downRawY = e.Event.RawY;
                _startX = _layoutParams.X;
                _startY = _layoutParams.Y;
                e.Handled = true;
                break;
            case MotionEventActions.Move:
                _layoutParams.X = _startX + (int)(e.Event.RawX - _downRawX);
                _layoutParams.Y = _startY + (int)(e.Event.RawY - _downRawY);
                try { _wm?.UpdateViewLayout(_label, _layoutParams); } catch { }
                e.Handled = true;
                break;
        }
    }

    public void Dispose() => Hide();
}
