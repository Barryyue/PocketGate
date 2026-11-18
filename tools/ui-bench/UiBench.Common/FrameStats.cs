using global::Android.Views;

namespace UiBench.Common;

/// <summary>滾動測試的瞬時結果。</summary>
public readonly record struct FrameSnapshot(double Fps, double WorstMs, int JankFrames);

/// <summary>
/// 持續掛在 Choreographer 上計算幀間隔，提供即時 FPS、最差幀和掉幀數。
/// 只適合 benchmark；正式應用不應長期這樣做。
/// </summary>
public sealed class FrameStats : Java.Lang.Object, Choreographer.IFrameCallback
{
    private const int Window = 120;
    private const double JankThresholdMs = 20.0;
    private readonly long[] _deltas = new long[Window];
    private int _index;
    private int _count;
    private long _lastFrameNanos;
    private int _sinceNotify;
    private bool _running;

    public event EventHandler? Updated;

    public double Fps { get; private set; }

    public double WorstMs { get; private set; }

    public int JankFrames { get; private set; }

    public void Start()
    {
        if (_running)
        {
            return;
        }

        _running = true;
        PostNext();
    }

    public void Stop() => _running = false;

    public void Reset()
    {
        _index = 0;
        _count = 0;
        _lastFrameNanos = 0;
        _sinceNotify = 0;
        Fps = 0;
        WorstMs = 0;
        JankFrames = 0;
    }

    public FrameSnapshot Snapshot() => new(Fps, WorstMs, JankFrames);

    public void DoFrame(long frameTimeNanos)
    {
        if (!_running)
        {
            return;
        }

        if (_lastFrameNanos != 0)
        {
            var deltaMs = (frameTimeNanos - _lastFrameNanos) / 1_000_000.0;
            _deltas[_index] = (long)Math.Round(deltaMs * 1000);
            _index = (_index + 1) % Window;
            _count = Math.Min(_count + 1, Window);
            WorstMs = Math.Max(WorstMs, deltaMs);
            if (deltaMs > JankThresholdMs)
            {
                JankFrames++;
            }

            if (_count > 1)
            {
                long sum = 0;
                for (var i = 0; i < _count; i++)
                {
                    sum += _deltas[i];
                }

                var averageMs = sum / 1000.0 / _count;
                Fps = averageMs > 0 ? 1000.0 / averageMs : 0;
            }

            _sinceNotify++;
            if (_sinceNotify >= 30)
            {
                _sinceNotify = 0;
                Updated?.Invoke(this, EventArgs.Empty);
            }
        }

        _lastFrameNanos = frameTimeNanos;
        PostNext();
    }

    private void PostNext() => Choreographer.Instance!.PostFrameCallback(this);
}
