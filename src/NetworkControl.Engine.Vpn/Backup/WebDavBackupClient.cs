using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;

namespace NetworkControl.Engine.Vpn.Backup;

/// <summary>
/// WebDAV 端到端加密備份用戶端（與 Kotlin 版 PocketGate 採用相同的 PGWB1 格式，可跨版本互通還原）：
/// 檔頭 PGWB1 (5 bytes) + salt (16 bytes) + iv (12 bytes) + AES-256-GCM 密文與 16-byte tag。
/// </summary>
public static class WebDavBackupClient
{
    private static readonly byte[] Magic = "PGWB1"u8.ToArray();
    private const int SaltBytes = 16;
    private const int IvBytes = 12;
    private const int TagBytes = 16;
    private const int KeyBytes = 32;
    private const int Pbkdf2Iterations = 65_536;
    private const string DefaultFilename = "pocketgate-backup.pgb";

    public static byte[] Encrypt(string plainText, string passphrase)
    {
        ArgumentException.ThrowIfNullOrEmpty(plainText);
        ArgumentException.ThrowIfNullOrEmpty(passphrase);

        var salt = RandomNumberGenerator.GetBytes(SaltBytes);
        var iv = RandomNumberGenerator.GetBytes(IvBytes);
        var key = Rfc2898DeriveBytes.Pbkdf2(
            Encoding.UTF8.GetBytes(passphrase),
            salt,
            Pbkdf2Iterations,
            HashAlgorithmName.SHA256,
            KeyBytes);

        var plainBytes = Encoding.UTF8.GetBytes(plainText);
        var cipherBytes = new byte[plainBytes.Length];
        var tag = new byte[TagBytes];

        using var aes = new AesGcm(key, TagBytes);
        aes.Encrypt(iv, plainBytes, cipherBytes, tag);

        var output = new byte[Magic.Length + SaltBytes + IvBytes + cipherBytes.Length + TagBytes];
        var offset = 0;
        Buffer.BlockCopy(Magic, 0, output, offset, Magic.Length);
        offset += Magic.Length;
        Buffer.BlockCopy(salt, 0, output, offset, SaltBytes);
        offset += SaltBytes;
        Buffer.BlockCopy(iv, 0, output, offset, IvBytes);
        offset += IvBytes;
        Buffer.BlockCopy(cipherBytes, 0, output, offset, cipherBytes.Length);
        offset += cipherBytes.Length;
        Buffer.BlockCopy(tag, 0, output, offset, TagBytes);
        return output;
    }

    public static string Decrypt(byte[] payload, string passphrase)
    {
        ArgumentNullException.ThrowIfNull(payload);
        ArgumentException.ThrowIfNullOrEmpty(passphrase);

        var minSize = Magic.Length + SaltBytes + IvBytes + TagBytes;
        if (payload.Length < minSize)
        {
            throw new InvalidDataException("Invalid backup payload length.");
        }

        if (!payload.AsSpan(0, Magic.Length).SequenceEqual(Magic))
        {
            throw new InvalidDataException("Unsupported backup format.");
        }

        var saltStart = Magic.Length;
        var ivStart = saltStart + SaltBytes;
        var cipherStart = ivStart + IvBytes;
        var cipherLen = payload.Length - cipherStart - TagBytes;
        if (cipherLen < 0)
        {
            throw new InvalidDataException("Corrupted backup payload.");
        }

        var salt = payload.AsSpan(saltStart, SaltBytes);
        var iv = payload.AsSpan(ivStart, IvBytes);
        var cipherBytes = payload.AsSpan(cipherStart, cipherLen);
        var tag = payload.AsSpan(payload.Length - TagBytes, TagBytes);

        var key = Rfc2898DeriveBytes.Pbkdf2(
            Encoding.UTF8.GetBytes(passphrase),
            salt,
            Pbkdf2Iterations,
            HashAlgorithmName.SHA256,
            KeyBytes);

        var plainBytes = new byte[cipherLen];
        using var aes = new AesGcm(key, TagBytes);
        aes.Decrypt(iv, cipherBytes, tag, plainBytes);
        return Encoding.UTF8.GetString(plainBytes);
    }

    public static string ResolveTargetUrl(string baseUrl)
    {
        var trimmed = (baseUrl ?? string.Empty).Trim();
        if (!trimmed.StartsWith("https://", StringComparison.OrdinalIgnoreCase) &&
            !trimmed.StartsWith("http://", StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException("Invalid WebDAV URL.", nameof(baseUrl));
        }

        return trimmed.EndsWith(".pgb", StringComparison.OrdinalIgnoreCase)
            ? trimmed
            : $"{trimmed.TrimEnd('/')}/{DefaultFilename}";
    }

    public static async Task<string> UploadAsync(
        string baseUrl,
        string username,
        string password,
        string passphrase,
        string jsonContent,
        CancellationToken ct = default)
    {
        var targetUrl = ResolveTargetUrl(baseUrl);
        var encrypted = Encrypt(jsonContent, passphrase);

        using var client = CreateClient(username, password);
        using var content = new ByteArrayContent(encrypted);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        var response = await client.PutAsync(targetUrl, content, ct).ConfigureAwait(false);
        if (response.StatusCode is HttpStatusCode.NotFound or HttpStatusCode.Conflict)
        {
            response.Dispose();
            var slash = targetUrl.LastIndexOf('/');
            if (slash > 8)
            {
                var parent = targetUrl[..slash];
                using var mkcol = new HttpRequestMessage(new HttpMethod("MKCOL"), parent);
                using var _ = await client.SendAsync(mkcol, ct).ConfigureAwait(false);
            }

            using var retryContent = new ByteArrayContent(encrypted);
            retryContent.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            response = await client.PutAsync(targetUrl, retryContent, ct).ConfigureAwait(false);
        }

        using (response)
        {
            if (!response.IsSuccessStatusCode)
            {
                throw new HttpRequestException($"WebDAV PUT failed (HTTP {(int)response.StatusCode}).");
            }
        }

        return targetUrl;
    }

    public static async Task<string> DownloadAsync(
        string baseUrl,
        string username,
        string password,
        string passphrase,
        CancellationToken ct = default)
    {
        var targetUrl = ResolveTargetUrl(baseUrl);
        using var client = CreateClient(username, password);
        using var response = await client.GetAsync(targetUrl, ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"WebDAV GET failed (HTTP {(int)response.StatusCode}).");
        }

        var bytes = await response.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
        return Decrypt(bytes, passphrase);
    }

    private static HttpClient CreateClient(string username, string password)
    {
        var client = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        if (!string.IsNullOrWhiteSpace(username) || !string.IsNullOrEmpty(password))
        {
            var token = Convert.ToBase64String(Encoding.UTF8.GetBytes($"{username}:{password}"));
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic", token);
        }

        return client;
    }
}
