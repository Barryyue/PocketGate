using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using NetworkControl.Core.Apps;
using NetworkControl.Core.Domain;

namespace NetworkControl.Engine.Windows.Platform;

/// <summary>
/// 檢查可執行文件：顯示名稱（版本信息）、Authenticode 簽名（WinVerifyTrust 校驗信任鏈），
/// 未簽名或簽名不可信時計算 SHA-256。結果由核心按文件戳緩存。
/// </summary>
/// <remarks>
/// 先校驗嵌入式 Authenticode 簽名；沒有時再查 Windows 目錄（catalog）簽名，
/// 這樣系統更新後目錄簽名文件不會因哈希變化而觸發篡改檢測。
/// </remarks>
internal sealed partial class WindowsAppInspector : IAppInspector
{
    private static readonly string WindowsDirectory = Environment.GetFolderPath(Environment.SpecialFolder.Windows);

    public FileStamp? GetStamp(string path)
    {
        try
        {
            var info = new FileInfo(path);
            return info.Exists ? new FileStamp(info.Length, info.LastWriteTimeUtc.Ticks) : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            return null;
        }
    }

    public async ValueTask<AppInspection> InspectAsync(string path, CancellationToken cancellationToken)
    {
        var stamp = GetStamp(path) ?? new FileStamp(0, 0);
        if (stamp.Size == 0)
        {
            return new AppInspection(stamp, null, null, null);
        }

        var displayName = ReadDisplayName(path);
        var isPlatform = path.StartsWith(WindowsDirectory, StringComparison.OrdinalIgnoreCase);
        var signer = VerifySigner(path) ?? CatalogSignature.VerifySigner(path);
        if (signer is not null)
        {
            return new AppInspection(stamp, displayName, new CodeSignature(signer, IsPlatformBinary: isPlatform), null);
        }

        var hash = await ComputeSha256Async(path, cancellationToken);
        return new AppInspection(stamp, displayName, null, hash);
    }

    private static string? ReadDisplayName(string path)
    {
        try
        {
            var info = FileVersionInfo.GetVersionInfo(path);
            return new[] { info.FileDescription, info.ProductName }.FirstOrDefault(s => !string.IsNullOrWhiteSpace(s))?.Trim();
        }
        catch (FileNotFoundException)
        {
            return null;
        }
    }

    private static async Task<string?> ComputeSha256Async(string path, CancellationToken cancellationToken)
    {
        try
        {
            await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1 << 16, useAsync: true);
            return Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, cancellationToken));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>校驗嵌入式 Authenticode 簽名的信任鏈；可信時返回簽名者名稱，否則返回 null。</summary>
    internal static unsafe string? VerifySigner(string path)
    {
        var action = new Guid("00AAC56B-CD44-11d0-8CC2-00C04FC295EE"); // WINTRUST_ACTION_GENERIC_VERIFY_V2
        var pathPtr = Marshal.StringToHGlobalUni(path);
        try
        {
            var file = new WinTrustFileInfo { CbStruct = (uint)sizeof(WinTrustFileInfo), FilePath = pathPtr };
            var data = new WinTrustData
            {
                CbStruct = (uint)sizeof(WinTrustData),
                UiChoice = 2,               // WTD_UI_NONE
                RevocationChecks = 0,       // WTD_REVOKE_NONE：不聯網查吊銷
                UnionChoice = 1,            // WTD_CHOICE_FILE
                File = (nint)(&file),
                StateAction = 1,            // WTD_STATEACTION_VERIFY
                ProvFlags = 0x00001000,     // WTD_CACHE_ONLY_URL_RETRIEVAL
            };

            var result = WinVerifyTrust(-1, &action, &data);
            data.StateAction = 2;           // WTD_STATEACTION_CLOSE
            WinVerifyTrust(-1, &action, &data);
            if (result != 0)
            {
                return null;
            }

#pragma warning disable SYSLIB0057 // 只讀取已經過 WinVerifyTrust 校驗的簽名證書
            using var certificate = new X509Certificate2(X509Certificate.CreateFromSignedFile(path));
#pragma warning restore SYSLIB0057
            var name = certificate.GetNameInfo(X509NameType.SimpleName, forIssuer: false);
            return string.IsNullOrWhiteSpace(name) ? null : name;
        }
        catch (CryptographicException)
        {
            return null;
        }
        finally
        {
            Marshal.FreeHGlobal(pathPtr);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct WinTrustFileInfo
    {
        public uint CbStruct;
        public nint FilePath;
        public nint File;
        public nint KnownSubject;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct WinTrustData
    {
        public uint CbStruct;
        public nint PolicyCallbackData;
        public nint SipClientData;
        public uint UiChoice;
        public uint RevocationChecks;
        public uint UnionChoice;
        public nint File;
        public uint StateAction;
        public nint StateData;
        public nint UrlReference;
        public uint ProvFlags;
        public uint UiContext;
        public nint SignatureSettings;
    }

    [LibraryImport("wintrust.dll")]
    private static unsafe partial int WinVerifyTrust(nint hwnd, Guid* action, WinTrustData* data);
}
