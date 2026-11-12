namespace NetworkControl.Service;

/// <summary>服務部署配置（配置節點 NetworkControl:Service）。</summary>
public sealed class ServiceOptions
{
    public const string SectionName = "NetworkControl:Service";

    /// <summary>
    /// 網絡引擎：Auto（按平台選擇）、Fake（模擬，不攔截真實流量）、Wfp（Windows，需要管理員 / SYSTEM 權限）。
    /// 後續增加 NfQueue（Linux）、NetworkExtension（macOS）。
    /// </summary>
    public string Engine { get; set; } = EngineNames.Auto;

    /// <summary>IPC 端點（pipe:&lt;name&gt; / unix:&lt;path&gt;）；為空時按運行權限選擇默認值。</summary>
    public string? IpcEndpoint { get; set; }

    /// <summary>
    /// 把所有通過身份識別的本地客戶端都視為管理員（單用戶個人電腦、開發環境）。
    /// 為 false 時只有本機管理員（Windows Administrators 組 / root）可以修改規則和設置。
    /// </summary>
    public bool TreatAllClientsAsAdmin { get; set; } = true;
}

public static class EngineNames
{
    public const string Auto = "Auto";

    public const string Fake = "Fake";

    public const string Wfp = "Wfp";
}
