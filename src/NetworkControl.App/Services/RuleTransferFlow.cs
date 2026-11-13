using System.Globalization;
using Avalonia.Platform.Storage;
using Grpc.Core;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using NetworkControl.App.ViewModels;
using NetworkControl.App.Views;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

/// <summary>
/// 規則導出 / 導入 / 從備份恢復的界面流程（文件對話框 → 預覽 → 確認 → 執行 → 提示）。
/// 路徑佔位符按<b>界面進程的用戶</b>目錄替換：服務以 SYSTEM 運行，自己的用戶目錄不是用戶的。
/// </summary>
public sealed class RuleTransferFlow(
    ServiceConnection connection,
    WindowHost host,
    DialogService dialogs,
    FeedbackService feedback,
    DisplayText text)
{
    private static readonly FilePickerFileType RulesFileType = new("NetworkControl rules")
    {
        Patterns = ["*.json"],
        MimeTypes = ["application/json"],
    };

    private LocalizationService Loc => text.Localization;

    /// <summary>佔位符 → 當前用戶的目錄（與服務端 <c>PathPlaceholderMapper</c> 的佔位符一致）。</summary>
    public static Dictionary<string, string> UserPathRoots() => new Dictionary<string, string>
    {
        ["%LOCALAPPDATA%"] = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        ["%APPDATA%"] = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        ["%PROGRAMDATA%"] = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
        ["%USERPROFILE%"] = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
    }.Where(p => !string.IsNullOrEmpty(p.Value)).ToDictionary();

    public async Task ExportAsync()
    {
        if (host.Current is not { } top)
        {
            return;
        }

        var file = await top.StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = Loc.Get(SettingsLangResourceKind.ExportPickerTitle),
            SuggestedFileName = $"networkcontrol-rules-{DateTime.Now.ToString("yyyyMMdd", CultureInfo.InvariantCulture)}.json",
            DefaultExtension = "json",
            ShowOverwritePrompt = true,
            FileTypeChoices = [RulesFileType],
            SuggestedStartLocation = await top.StorageProvider.TryGetWellKnownFolderAsync(WellKnownFolder.Documents),
        });
        if (file is null)
        {
            return;
        }

        await RunAsync(async () =>
        {
            var request = new Proto.ExportRulesRequest();
            request.PathRoots.Add(UserPathRoots());
            var response = await connection.RequireClients().Rules.ExportAsync(request);
            await using (var stream = await file.OpenWriteAsync())
            await using (var writer = new StreamWriter(stream, new System.Text.UTF8Encoding(encoderShouldEmitUTF8Identifier: false)))
            {
                await writer.WriteAsync(response.Content);
            }

            var message = Loc.Format(SettingsLangResourceKind.Exported, response.Exported, file.TryGetLocalPath() ?? file.Name);
            if (response.Skipped > 0)
            {
                message += " " + Loc.Format(SettingsLangResourceKind.ExportSkippedNote, response.Skipped);
            }

            feedback.Success(message);
        });
    }

    public async Task ImportAsync()
    {
        if (host.Current is not { } top)
        {
            return;
        }

        var files = await top.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = Loc.Get(SettingsLangResourceKind.ImportPickerTitle),
            AllowMultiple = false,
            FileTypeFilter = [RulesFileType, FilePickerFileTypes.All],
        });
        if (files.Count == 0)
        {
            return;
        }

        var file = files[0];
        await RunAsync(async () =>
        {
            string content;
            await using (var stream = await file.OpenReadAsync())
            using (var reader = new StreamReader(stream))
            {
                content = await reader.ReadToEndAsync();
            }

            var rules = connection.RequireClients().Rules;
            var merge = await rules.ImportAsync(Request(content, Proto.ImportMode.Merge, preview: true));
            var replace = await rules.ImportAsync(Request(content, Proto.ImportMode.Replace, preview: true));
            var preview = new ImportPreviewViewModel(text, file.TryGetLocalPath() ?? file.Name, merge, replace);
            if (!await dialogs.ShowFormAsync<ImportPreviewView, ImportPreviewViewModel>(preview, Loc.Get(SettingsLangResourceKind.ImportTitle)))
            {
                return;
            }

            var result = await rules.ImportAsync(Request(content, preview.Mode, preview: false));
            feedback.Success(Loc.Format(SettingsLangResourceKind.Imported, RuleTransferText.Summary(Loc, result)));
        }, importing: true);
    }

    /// <returns>是否執行了恢復。</returns>
    public async Task<bool> RestoreAsync(string backupName, string backupTitle)
    {
        var restored = false;
        await RunAsync(async () =>
        {
            var rules = connection.RequireClients().Rules;
            var preview = await rules.RestoreBackupAsync(new Proto.RestoreBackupRequest { Name = backupName, PreviewOnly = true });
            var confirm = new RestoreConfirmViewModel(text, backupTitle, preview);
            if (!await dialogs.ShowFormAsync<RestoreConfirmView, RestoreConfirmViewModel>(confirm, Loc.Get(SettingsLangResourceKind.RestoreTitle)))
            {
                return;
            }

            var result = await rules.RestoreBackupAsync(new Proto.RestoreBackupRequest { Name = backupName });
            feedback.Success(Loc.Format(SettingsLangResourceKind.Restored, RuleTransferText.Summary(Loc, result)));
            restored = true;
        });
        return restored;
    }

    private static Proto.ImportRulesRequest Request(string content, Proto.ImportMode mode, bool preview)
    {
        var request = new Proto.ImportRulesRequest { Content = content, Mode = mode, PreviewOnly = preview };
        request.PathRoots.Add(UserPathRoots());
        return request;
    }

    private async Task RunAsync(Func<Task> action, bool importing = false)
    {
        try
        {
            await action();
        }
        catch (RpcException ex)
        {
            Fail(ex.Status.Detail, importing);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            Fail(ex.Message, importing);
        }
    }

    private void Fail(string detail, bool importing) => feedback.Error(importing
        ? Loc.Format(SettingsLangResourceKind.ImportFailed, detail)
        : Loc.Format(CommonLangResourceKind.ErrorFormat, detail));
}
