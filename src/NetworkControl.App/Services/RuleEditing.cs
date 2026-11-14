using Grpc.Core;
using NetworkControl.App.Infrastructure;
using NetworkControl.App.Localization;
using NetworkControl.App.ViewModels;
using NetworkControl.App.Views;
using Proto = NetworkControl.Ipc.V1;

namespace NetworkControl.App.Services;

/// <summary>
/// 規則的新建、編輯、刪除流程（對話框 + 提交 + 結果提示）。規則頁、日誌頁、阻斷通知共用。
/// </summary>
public sealed class RuleEditing(
    ServiceConnection connection,
    ProfileCatalog catalog,
    DialogService dialogs,
    FeedbackService feedback,
    DisplayText text)
{
    private LocalizationService Loc => text.Localization;

    public async Task<bool> CreateAsync(RulePrefill? prefill = null)
    {
        var editor = new RuleEditorViewModel(text, prefill: prefill, profiles: await LoadProfilesAsync());
        if (!await dialogs.ShowFormAsync<RuleEditorView, RuleEditorViewModel>(editor, Loc.Get(RulesLangResourceKind.EditorCreateTitle)))
        {
            return false;
        }

        return await SubmitAsync(
            clients => clients.Rules.CreateAsync(editor.ToDefinition()).ResponseAsync,
            RulesLangResourceKind.Created);
    }

    public async Task<bool> EditAsync(Proto.RuleInfo rule)
    {
        var editor = new RuleEditorViewModel(text, existing: rule, profiles: await LoadProfilesAsync());
        if (!await dialogs.ShowFormAsync<RuleEditorView, RuleEditorViewModel>(editor, Loc.Get(RulesLangResourceKind.EditorEditTitle)))
        {
            return false;
        }

        return await SubmitAsync(
            clients => clients.Rules.UpdateAsync(new Proto.UpdateRuleRequest { Id = rule.Id, Definition = editor.ToDefinition() }).ResponseAsync,
            RulesLangResourceKind.Updated);
    }

    public Task<bool> DeleteAsync(string ruleId)
    {
        var request = new Proto.DeleteRulesRequest();
        request.Ids.Add(ruleId);
        return SubmitAsync(clients => clients.Rules.DeleteAsync(request).ResponseAsync, RulesLangResourceKind.Deleted);
    }

    /// <summary>
    /// 編輯器的配置文件選項。null 表示無法使用（舊版服務，或從未成功載入過）：
    /// 編輯器會隱藏「適用於」並原樣保留規則的 ProfileId（R3）。
    /// </summary>
    private async Task<IReadOnlyList<Proto.ProfileInfo>?> LoadProfilesAsync()
    {
        await catalog.RefreshAsync();
        return catalog.IsAvailable && !(catalog.LastLoadFailed && catalog.Profiles.Count == 0)
            ? catalog.Profiles
            : null;
    }

    private async Task<bool> SubmitAsync<T>(Func<IpcClients, Task<T>> call, RulesLangResourceKind successMessage)
    {
        try
        {
            await call(connection.RequireClients());
            feedback.Success(Loc.Get(successMessage));
            return true;
        }
        catch (RpcException ex)
        {
            feedback.Error(Loc.Format(CommonLangResourceKind.ErrorFormat, ex.Status.Detail));
        }
        catch (InvalidOperationException ex)
        {
            feedback.Error(Loc.Format(CommonLangResourceKind.ErrorFormat, ex.Message));
        }

        return false;
    }
}
