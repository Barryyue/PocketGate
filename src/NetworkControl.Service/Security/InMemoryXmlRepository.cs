using System.Xml.Linq;
using Microsoft.AspNetCore.DataProtection.Repositories;

namespace NetworkControl.Service.Security;

/// <summary>
/// 只存在內存中的 DataProtection 密鑰倉庫。認證組件會順帶啟用 DataProtection，
/// 但本服務不使用 Cookie / 令牌，不需要（也不應該）把密鑰寫到運行用戶的個人目錄。
/// </summary>
internal sealed class InMemoryXmlRepository : IXmlRepository
{
    private readonly Lock _gate = new();
    private readonly List<XElement> _elements = [];

    public IReadOnlyCollection<XElement> GetAllElements()
    {
        lock (_gate)
        {
            return _elements.Select(e => new XElement(e)).ToArray();
        }
    }

    public void StoreElement(XElement element, string friendlyName)
    {
        lock (_gate)
        {
            _elements.Add(new XElement(element));
        }
    }
}
