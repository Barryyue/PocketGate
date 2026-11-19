using System.Text;
using global::Android.Content;
using global::Android.OS;
using global::Android.Provider;
using AndroidEnv = global::Android.OS.Environment;

namespace NetworkControl.Android.Platform;

/// <summary>透過 MediaStore.Downloads 將規則留檔至 Download/PocketGate/，支援重裝後就近一鍵還原。</summary>
public static class LocalRulesBackup
{
    private const string FileName = "pocketgate-rules-backup.json";
    private const string RelativeDir = "PocketGate";

    public static string SaveToDownloads(Context context, string json)
    {
        var privateFile = Path.Combine(context.FilesDir!.AbsolutePath, "backups", FileName);
        Directory.CreateDirectory(Path.GetDirectoryName(privateFile)!);
        File.WriteAllText(privateFile, json, Encoding.UTF8);

        var resolver = context.ContentResolver!;
        var relativePath = $"{AndroidEnv.DirectoryDownloads}/{RelativeDir}";
        var queryUri = MediaStore.Downloads.ExternalContentUri!;
        var selection = $"{MediaStore.Downloads.InterfaceConsts.DisplayName} = ? AND {MediaStore.Downloads.InterfaceConsts.RelativePath} LIKE ?";
        global::Android.Net.Uri? existingUri = null;
        using (var cursor = resolver.Query(queryUri, [MediaStore.Downloads.InterfaceConsts.Id], selection, [FileName, $"{relativePath}%"], null))
        {
            if (cursor is not null && cursor.MoveToFirst())
            {
                existingUri = ContentUris.WithAppendedId(queryUri, cursor.GetLong(0));
            }
        }

        var targetUri = existingUri;
        if (targetUri is null)
        {
            var values = new ContentValues();
            values.Put(MediaStore.Downloads.InterfaceConsts.DisplayName, FileName);
            values.Put(MediaStore.Downloads.InterfaceConsts.MimeType, "application/json");
            values.Put(MediaStore.Downloads.InterfaceConsts.RelativePath, relativePath);
            targetUri = resolver.Insert(queryUri, values) ?? throw new IOException("Unable to create Downloads entry.");
        }

        using var outStream = resolver.OpenOutputStream(targetUri, "wt") ?? throw new IOException("Unable to open Downloads output stream.");
        var bytes = Encoding.UTF8.GetBytes(json);
        outStream.Write(bytes, 0, bytes.Length);
        return $"{relativePath}/{FileName}";
    }

    public static string ReadLatestFromDownloads(Context context)
    {
        var resolver = context.ContentResolver!;
        var relativePath = $"{AndroidEnv.DirectoryDownloads}/{RelativeDir}";
        var queryUri = MediaStore.Downloads.ExternalContentUri!;
        var selection = $"{MediaStore.Downloads.InterfaceConsts.DisplayName} = ? AND {MediaStore.Downloads.InterfaceConsts.RelativePath} LIKE ?";
        global::Android.Net.Uri? uri = null;
        using (var cursor = resolver.Query(
            queryUri,
            [MediaStore.Downloads.InterfaceConsts.Id],
            selection,
            [FileName, $"{relativePath}%"],
            $"{MediaStore.Downloads.InterfaceConsts.DateModified} DESC"))
        {
            if (cursor is not null && cursor.MoveToFirst())
            {
                uri = ContentUris.WithAppendedId(queryUri, cursor.GetLong(0));
            }
        }

        if (uri is not null)
        {
            using var stream = resolver.OpenInputStream(uri) ?? throw new IOException("Unable to read Downloads backup.");
            using var reader = new StreamReader(stream, Encoding.UTF8);
            return reader.ReadToEnd();
        }

        var fallback = Path.Combine(context.FilesDir!.AbsolutePath, "backups", FileName);
        if (File.Exists(fallback))
        {
            return File.ReadAllText(fallback, Encoding.UTF8);
        }

        throw new FileNotFoundException($"No local backup found in Download/{RelativeDir}/{FileName}");
    }
}
