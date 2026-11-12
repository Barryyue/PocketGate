namespace NetworkControl.Ipc.Tests;

public sealed class PortableLayoutTests : IDisposable
{
    private static readonly string AppName = OperatingSystem.IsWindows() ? "NetworkControl.exe" : "NetworkControl";

    private static readonly string ServiceName =
        OperatingSystem.IsWindows() ? "NetworkControl.Service.exe" : "NetworkControl.Service";

    private readonly string _root = Path.Combine(
        Path.GetTempPath(),
        "networkcontrol-portable-tests",
        Guid.NewGuid().ToString("N"));

    public PortableLayoutTests()
    {
        Directory.CreateDirectory(Path.Combine(_root, PortableLayout.AppDirectoryName));
        Directory.CreateDirectory(Path.Combine(_root, PortableLayout.ServiceDirectoryName));
    }

    [Fact]
    public void Detects_layout_from_app_and_service_directories()
    {
        CreateLayout(marker: true, app: true, service: true);

        Assert.Equal(_root, PortableLayout.Detect(Path.Combine(_root, PortableLayout.AppDirectoryName)));
        Assert.Equal(_root, PortableLayout.Detect(Path.Combine(_root, PortableLayout.ServiceDirectoryName)));
    }

    [Fact]
    public void Requires_marker_file()
    {
        CreateLayout(marker: false, app: true, service: true);

        Assert.Null(PortableLayout.Detect(Path.Combine(_root, PortableLayout.AppDirectoryName)));
    }

    [Theory]
    [InlineData(false, true)]
    [InlineData(true, false)]
    public void Requires_both_executables(bool app, bool service)
    {
        CreateLayout(marker: true, app, service);

        Assert.Null(PortableLayout.Detect(Path.Combine(_root, PortableLayout.AppDirectoryName)));
    }

    [Fact]
    public void Ignores_directory_that_is_not_app_or_service()
    {
        CreateLayout(marker: true, app: true, service: true);

        Assert.Null(PortableLayout.Detect(_root));
    }

    [Fact]
    public void Portable_endpoint_is_isolated_from_the_installed_service()
    {
        Assert.NotEqual(IpcEndpoint.System, IpcEndpoint.Portable);
        if (OperatingSystem.IsWindows())
        {
            Assert.Equal("pipe:NetworkControl.portable.v1", IpcEndpoint.Portable.ToString());
        }
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_root, recursive: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // 測試結束後清理失敗不影響結果
        }
    }

    private void CreateLayout(bool marker, bool app, bool service)
    {
        if (marker)
        {
            File.WriteAllText(Path.Combine(_root, PortableLayout.MarkerFileName), "portable");
        }

        if (app)
        {
            File.WriteAllText(Path.Combine(_root, PortableLayout.AppDirectoryName, AppName), string.Empty);
        }

        if (service)
        {
            File.WriteAllText(Path.Combine(_root, PortableLayout.ServiceDirectoryName, ServiceName), string.Empty);
        }
    }
}
