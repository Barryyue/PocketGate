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
    DialogService dialogs,
    FeedbackService feedback,
    DisplayText text)
{
    private LocalizationService Loc => text.Localization;

    public async Task<bool> CreateAsync(RulePrefill? prefill = null)
    {
        var editor = new RuleEditorViewModel(text, prefill: prefill);
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
        var editor = new RuleEditorViewModel(text, existing: rule);
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
