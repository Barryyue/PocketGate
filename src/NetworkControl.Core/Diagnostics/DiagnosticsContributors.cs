using System.Diagnostics.CodeAnalysis;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace NetworkControl.Core.Diagnostics;

/// <summary>診斷包的收集選項。涉及隱私的內容默認不收集，由用戶在導出時勾選。</summary>
public sealed record DiagnosticsCollectOptions
{
    /// <summary>包含規則、應用列表和設置（config.db 副本；含程序路徑、域名）。</summary>
    public bool IncludeConfiguration { get; init; }

    /// <summary>包含最近的連接日誌（訪問過的地址和域名）。</summary>
    public bool IncludeActivity { get; init; }
}

/// <summary>
/// 診斷包的寫入目標。路徑相對於當前收集者的目錄，使用正斜槓。
/// 文本內容在寫入時統一脫敏（用戶名、計算機名）；二進制文件（數據庫副本）原樣寫入。
/// </summary>
public interface IDiagnosticsSink
{
    void AddText(string path, string content);

    /// <summary>複製一個文件（允許文件正被其他進程寫入）；超過大小上限時只保留末尾部分。</summary>
    Task AddFileAsync(string path, string sourcePath, CancellationToken cancellationToken);

    /// <summary>原樣寫入二進制內容（不脫敏、不截斷）。</summary>
    Task AddBinaryAsync(string path, Stream content, CancellationToken cancellationToken);

    /// <summary>記錄一條說明（例如「某項因權限不足未能收集」），彙總到診斷包的清單中。</summary>
    void AddNote(string message);
}

/// <summary>
/// 診斷信息的提供者。服務、存儲、平台引擎各自實現並註冊，導出時依次調用；
/// 單個收集者失敗只記錄錯誤，不影響其他部分。
/// </summary>
public interface IDiagnosticsContributor
{
    /// <summary>在診斷包中的目錄名，例如 <c>engine</c>。</summary>
    string Name { get; }

    Task CollectAsync(IDiagnosticsSink sink, DiagnosticsCollectOptions options, CancellationToken cancellationToken);
}

public static class DiagnosticsServiceCollectionExtensions
{
    public static IServiceCollection AddDiagnosticsContributor<[DynamicallyAccessedMembers(DynamicallyAccessedMemberTypes.PublicConstructors)] T>(this IServiceCollection services)
        where T : class, IDiagnosticsContributor
    {
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IDiagnosticsContributor, T>());
        return services;
    }

    /// <summary>把已註冊的單例（例如引擎）同時作為收集者，不創建第二個實例。</summary>
    public static IServiceCollection AddDiagnosticsContributor<T>(this IServiceCollection services, Func<IServiceProvider, T> factory)
        where T : class, IDiagnosticsContributor
    {
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IDiagnosticsContributor, T>(factory));
        return services;
    }
}
