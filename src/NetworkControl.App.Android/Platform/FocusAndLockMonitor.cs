using global::Android.App;
using global::Android.Content;
using Microsoft.Extensions.Hosting;
using NetworkControl.Android.Hosting;
using NetworkControl.Android.Infrastructure;
using NetworkControl.Core.Domain;
using NetworkControl.Core.Settings;

namespace NetworkControl.Android.Platform;

/// <summary>
/// 定時專注模式排程器與鎖屏斷網守衛（每 30 秒檢查一次時段與 Keyguard 鎖屏狀態）。
/// </summary>
internal sealed class FocusAndLockMonitor(
    Context context,
    UiPreferences preferences,
    IBackend backend,
    RuntimeStateService state) : BackgroundService
{
    private readonly Context _context = context.ApplicationContext ?? context;
    private bool _scheduleStartedFocus;
    private bool _wasLocked;
    private FilterMode? _preLockMode;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await CheckFocusScheduleAsync(stoppingToken).ConfigureAwait(false);
                await CheckScreenLockAsync(stoppingToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch
            {
            }

            await Task.Delay(TimeSpan.FromSeconds(30), stoppingToken).ConfigureAwait(false);
        }
    }

    private async Task CheckFocusScheduleAsync(CancellationToken ct)
    {
        if (!preferences.FocusScheduleEnabled)
        {
            if (_scheduleStartedFocus && backend.IsFocusActive)
            {
                _scheduleStartedFocus = false;
                await backend.StopFocusAsync(ct).ConfigureAwait(false);
            }

            return;
        }

        if (!TimeOnly.TryParse(preferences.FocusScheduleStart, out var start) ||
            !TimeOnly.TryParse(preferences.FocusScheduleEnd, out var end))
        {
            return;
        }

        var now = DateTime.Now;
        var day = ((int)now.DayOfWeek == 0 ? 7 : (int)now.DayOfWeek).ToString();
        var allowedDays = preferences.FocusScheduleDays.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        var currentTime = TimeOnly.FromDateTime(now);
        var inWindow = allowedDays.Contains(day) && (start <= end
            ? currentTime >= start && currentTime < end
            : currentTime >= start || currentTime < end);

        if (inWindow && !backend.IsFocusActive)
        {
            var remainingMinutes = start <= end
                ? Math.Max(5, (int)(end - currentTime).TotalMinutes)
                : 60;
            _scheduleStartedFocus = true;
            await backend.StartFocusAsync(remainingMinutes, [], ct).ConfigureAwait(false);
        }
        else if (!inWindow && _scheduleStartedFocus && backend.IsFocusActive)
        {
            _scheduleStartedFocus = false;
            await backend.StopFocusAsync(ct).ConfigureAwait(false);
        }
    }

    private async Task CheckScreenLockAsync(CancellationToken ct)
    {
        if (!preferences.BlockOnLockScreen)
        {
            if (_wasLocked && _preLockMode is { } prev)
            {
                _wasLocked = false;
                _preLockMode = null;
                await state.SetModeAsync(prev, ct).ConfigureAwait(false);
            }

            return;
        }

        var km = _context.GetSystemService(Context.KeyguardService) as KeyguardManager;
        var locked = km?.IsKeyguardLocked == true;
        if (locked && !_wasLocked)
        {
            _wasLocked = true;
            _preLockMode = state.Mode;
            await state.SetModeAsync(FilterMode.BlockAll, ct).ConfigureAwait(false);
        }
        else if (!locked && _wasLocked)
        {
            _wasLocked = false;
            if (_preLockMode is { } prev)
            {
                _preLockMode = null;
                await state.SetModeAsync(prev, ct).ConfigureAwait(false);
            }
        }
    }
}
