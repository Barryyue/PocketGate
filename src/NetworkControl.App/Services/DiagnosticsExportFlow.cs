using System.Diagnostics;
using Avalonia.Platform.Storage;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;

namespace NetworkControl.App.Services;

/// <summary>界面中的導出流程：選擇保存位置 → 生成診斷包 → 提示結果並在文件管理器中定位。設置頁和「服務不可用」頁共用。</summary>
public sealed class DiagnosticsExportFlow(
    DiagnosticsExporter exporter,
    ServiceConnection connection,
    WindowHost host,
    FeedbackService feedback,
    LocalizationService loc)
{
    /// <summary>返回 true 表示已生成診斷包（用戶取消保存對話框返回 false）。</summary>
    public async Task<bool> ExportAsync(DiagnosticsExportOptions options)
    {
        if (host.Current is not { } top)
        {
            return false;
        }

        var file = await top.StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = loc.Get(SettingsLangResourceKind.DiagnosticsPickerTitle),
            SuggestedFileName = DiagnosticsExporter.SuggestedFileName(DateTime.Now),
            DefaultExtension = "zip",
            ShowOverwritePrompt = true,
            FileTypeChoices = [new FilePickerFileType("Zip") { Patterns = ["*.zip"], MimeTypes = ["application/zip"] }],
            SuggestedStartLocation = await top.StorageProvider.TryGetWellKnownFolderAsync(WellKnownFolder.Desktop),
        });
        if (file is null)
        {
            return false;
        }

        var path = file.TryGetLocalPath() ?? file.Name;
        try
        {
            DiagnosticsExportResult result;
            await using (var stream = await file.OpenWriteAsync())
            {
                result = await exporter.ExportAsync(stream, options, DiagnosticsContext.From(connection), CancellationToken.None);
            }

            if (result.ServiceIncluded)
            {
                feedback.Success(loc.Format(SettingsLangResourceKind.DiagnosticsExported, path));
            }
            else
            {
                feedback.Info(loc.Format(SettingsLangResourceKind.DiagnosticsExportedPartial, result.ServiceError ?? ""));
            }

            RevealInFileManager(file.TryGetLocalPath());
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            AppLog.Error($"Diagnostics export failed: {path}", ex);
            feedback.Error(loc.Format(SettingsLangResourceKind.DiagnosticsExportFailed, ex.Message));
            return false;
        }
    }

    public async Task OpenLogFolderAsync()
    {
        Directory.CreateDirectory(AppLog.Directory);
        if (host.Current is { } top)
        {
            await top.Launcher.LaunchDirectoryInfoAsync(new DirectoryInfo(AppLog.Directory));
        }
    }

    private static void RevealInFileManager(string? path)
    {
        if (path is null || !OperatingSystem.IsWindows())
        {
            return;
        }

        try
        {
            // explorer 的 /select 參數必須和路徑寫在同一個參數裡
            using var _ = Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"") { UseShellExecute = false });
        }
        catch (System.ComponentModel.Win32Exception ex)
        {
            AppLog.Warn("Cannot open the file manager", ex);
        }
    }
}
