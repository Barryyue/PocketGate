using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace NetworkControl.Engine.Windows.Platform;

/// <summary>
/// 校驗 Windows 目錄（catalog）簽名。嵌入式簽名不存在的系統文件通常由目錄文件簽名；
/// 先按 SHA-256 查目錄，找不到再按 SHA-1 查（舊目錄只有 SHA-1）。
/// </summary>
internal static unsafe class CatalogSignature
{
    private const uint ErrorInsufficientBuffer = 122;
    private const uint WtdUiNone = 2;
    private const uint WtdRevokeNone = 0;
    private const uint WtdChoiceCatalog = 2;
    private const uint WtdStateActionVerify = 1;
    private const uint WtdStateActionClose = 2;
    private const uint WtdCacheOnlyUrlRetrieval = 0x00001000;

    private static readonly Guid DriverActionVerify = new("F750E6C3-38EE-11D1-85E5-00C04FC295EE");

    public static string? VerifySigner(string path)
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            var fileHandle = stream.SafeFileHandle.DangerousGetHandle();
            return VerifyWithAlgorithm(path, fileHandle, "SHA256") ?? VerifyWithAlgorithm(path, fileHandle, "SHA1");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or CryptographicException or ExternalException)
        {
            return null;
        }
    }

    private static string? VerifyWithAlgorithm(string path, IntPtr fileHandle, string algorithm)
    {
        var subsystem = DriverActionVerify;
        if (!CryptCATAdminAcquireContext2(out var admin, ref subsystem, algorithm, IntPtr.Zero, 0))
        {
            return null;
        }

        try
        {
            uint hashSize = 0;
            _ = CryptCATAdminCalcHashFromFileHandle2(admin, fileHandle, ref hashSize, null, 0);
            if (hashSize == 0 || hashSize > 128)
            {
                return null;
            }

            var hash = new byte[hashSize];
            if (!CryptCATAdminCalcHashFromFileHandle2(admin, fileHandle, ref hashSize, hash, 0))
            {
                return null;
            }

            var catalog = CryptCATAdminEnumCatalogFromHash(admin, hash, hashSize, 0, IntPtr.Zero);
            if (catalog == IntPtr.Zero)
            {
                return null;
            }

            try
            {
                var info = Marshal.AllocHGlobal(4 + 260 * 2);
                try
                {
                    Marshal.WriteInt32(info, 0, 4 + 260 * 2);
                    if (!CryptCATCatalogInfoFromContext(catalog, info, 0))
                    {
                        return null;
                    }

                    var catalogPath = Marshal.PtrToStringUni(info + 4);
                    if (string.IsNullOrWhiteSpace(catalogPath))
                    {
                        return null;
                    }

                    return VerifyCatalog(path, fileHandle, catalogPath, hash, admin);
                }
                finally
                {
                    Marshal.FreeHGlobal(info);
                }
            }
            finally
            {
                _ = CryptCATAdminReleaseCatalogContext(admin, catalog, 0);
            }
        }
        finally
        {
            _ = CryptCATAdminReleaseContext(admin, 0);
        }
    }

    private static string? VerifyCatalog(string path, IntPtr fileHandle, string catalogPath, byte[] hash, IntPtr admin)
    {
        var catalogPathPtr = Marshal.StringToHGlobalUni(catalogPath);
        var memberPathPtr = Marshal.StringToHGlobalUni(path);
        var tagPtr = Marshal.StringToHGlobalUni(Convert.ToHexString(hash));
        var hashPtr = Marshal.AllocHGlobal(hash.Length);
        try
        {
            Marshal.Copy(hash, 0, hashPtr, hash.Length);
            var catalog = new WinTrustCatalogInfo
            {
                Size = (uint)sizeof(WinTrustCatalogInfo),
                CatalogPath = catalogPathPtr,
                MemberTag = tagPtr,
                MemberFilePath = memberPathPtr,
                MemberFile = fileHandle,
                Hash = hashPtr,
                HashSize = (uint)hash.Length,
                Admin = admin,
            };
            var action = new Guid("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");
            var data = new WinTrustData
            {
                Size = (uint)sizeof(WinTrustData),
                UiChoice = WtdUiNone,
                RevocationChecks = WtdRevokeNone,
                UnionChoice = WtdChoiceCatalog,
                Catalog = (nint)(&catalog),
                StateAction = WtdStateActionVerify,
                ProvFlags = WtdCacheOnlyUrlRetrieval,
            };

            var result = WinVerifyTrust(-1, &action, &data);
            data.StateAction = WtdStateActionClose;
            _ = WinVerifyTrust(-1, &action, &data);
            if (result != 0)
            {
                return null;
            }

#pragma warning disable SYSLIB0057 // 目錄已由 WinVerifyTrust 校驗
            using var certificate = new X509Certificate2(X509Certificate.CreateFromSignedFile(catalogPath));
#pragma warning restore SYSLIB0057
            var name = certificate.GetNameInfo(X509NameType.SimpleName, forIssuer: false);
            return string.IsNullOrWhiteSpace(name) ? null : name;
        }
        finally
        {
            Marshal.FreeHGlobal(hashPtr);
            Marshal.FreeHGlobal(tagPtr);
            Marshal.FreeHGlobal(memberPathPtr);
            Marshal.FreeHGlobal(catalogPathPtr);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct WinTrustCatalogInfo
    {
        public uint Size;
        public uint CatalogVersion;
        public nint CatalogPath;
        public nint MemberTag;
        public nint MemberFilePath;
        public nint MemberFile;
        public nint Hash;
        public uint HashSize;
        public nint CatalogContext;
        public nint Admin;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct WinTrustData
    {
        public uint Size;
        public nint PolicyCallbackData;
        public nint SipClientData;
        public uint UiChoice;
        public uint RevocationChecks;
        public uint UnionChoice;
        public nint Catalog;
        public uint StateAction;
        public nint StateData;
        public nint UrlReference;
        public uint ProvFlags;
        public uint UiContext;
        public nint SignatureSettings;
    }

    [DllImport("wintrust.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptCATAdminAcquireContext2(
        out IntPtr admin,
        ref Guid subsystem,
        string hashAlgorithm,
        IntPtr strongHashPolicy,
        uint flags);

    [DllImport("wintrust.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptCATAdminCalcHashFromFileHandle2(
        IntPtr admin,
        IntPtr file,
        ref uint hashSize,
        [Out] byte[]? hash,
        uint flags);

    [DllImport("wintrust.dll")]
    private static extern IntPtr CryptCATAdminEnumCatalogFromHash(
        IntPtr admin,
        [In] byte[] hash,
        uint hashSize,
        uint flags,
        IntPtr previous);

    [DllImport("wintrust.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptCATCatalogInfoFromContext(IntPtr catalog, IntPtr info, uint flags);

    [DllImport("wintrust.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptCATAdminReleaseCatalogContext(IntPtr admin, IntPtr catalog, uint flags);

    [DllImport("wintrust.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptCATAdminReleaseContext(IntPtr admin, uint flags);

    [DllImport("wintrust.dll")]
    private static extern int WinVerifyTrust(nint hwnd, Guid* action, WinTrustData* data);
}
