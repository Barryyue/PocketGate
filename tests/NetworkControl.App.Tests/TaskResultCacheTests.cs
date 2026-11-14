using NetworkControl.App.Services;

namespace NetworkControl.App.Tests;

public sealed class TaskResultCacheTests
{
    [Fact]
    public async Task Failed_result_is_not_cached_and_is_retried()
    {
        var cache = new TaskResultCache<object>(16);
        var calls = 0;

        var first = await cache.GetAsync("app", _ =>
        {
            calls++;
            return null;
        });
        var second = await cache.GetAsync("app", _ =>
        {
            calls++;
            return null;
        });

        Assert.Null(first);
        Assert.Null(second);
        Assert.Equal(2, calls);
    }

    [Fact]
    public async Task Successful_result_is_cached()
    {
        var cache = new TaskResultCache<object>(16);
        var value = new object();
        var calls = 0;

        var first = await cache.GetAsync("app", _ =>
        {
            calls++;
            return value;
        });
        var second = await cache.GetAsync("app", _ =>
        {
            calls++;
            return value;
        });

        Assert.Same(value, first);
        Assert.Same(value, second);
        Assert.Equal(1, calls);
    }

    [Fact]
    public async Task Throwing_extraction_is_removed_so_it_can_be_retried()
    {
        var cache = new TaskResultCache<object>(16);
        var calls = 0;

        await Assert.ThrowsAsync<InvalidOperationException>(() => cache.GetAsync("app", _ =>
        {
            calls++;
            throw new InvalidOperationException("boom");
        }));

        var value = new object();
        var recovered = await cache.GetAsync("app", _ =>
        {
            calls++;
            return value;
        });

        Assert.Same(value, recovered);
        Assert.Equal(2, calls);
    }
}
