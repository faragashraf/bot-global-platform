using System.Security.Cryptography;
using BotGlobal.Communication.Domain.Chat;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.Hosting;

namespace BotGlobal.Communication.Application.Chat;

public sealed class ChatVoiceOptions
{
    public const string SectionName = "Communication:ChatVoice";
    public string StoragePath { get; set; } = "App_Data/chat-voice";
    public int PublishedRetentionDays { get; set; } = 7;
    public int SweepMinutes { get; set; } = 15;
    public string DecoderPath { get; set; } = "ffmpeg";
}

internal interface IChatVoiceStorage
{
    Task<ChatVoiceUpload> PublishAsync(Stream source, string contentType, CancellationToken cancellationToken);
    Stream OpenRead(string fileKey);
    bool Delete(string fileKey);
    IReadOnlyCollection<string> EnumerateFileKeys();
    DateTimeOffset? LastWriteTimeUtc(string fileKey);
}

internal sealed class PrivateChatVoiceStorage : IChatVoiceStorage
{
    private readonly string _root;
    private readonly ChatAacDecoder _decoder;
    public PrivateChatVoiceStorage(IOptions<ChatVoiceOptions> options, IHostEnvironment environment)
    {
        var configured = options.Value.StoragePath.Trim();
        _root = Path.GetFullPath(Path.IsPathRooted(configured) ? configured : Path.Combine(environment.ContentRootPath, configured));
        Directory.CreateDirectory(_root);
        _decoder = new ChatAacDecoder(options.Value.DecoderPath);
    }

    public async Task<ChatVoiceUpload> PublishAsync(Stream source, string contentType, CancellationToken cancellationToken)
    {
        if (contentType.Split(';', 2)[0].Trim().ToLowerInvariant() is not ("audio/mp4" or "audio/x-m4a"))
            throw new InvalidDataException("chat_voice_content_type_invalid");
        var id = Guid.NewGuid().ToString("N");
        var partial = PathFor(id + ".partial");
        var fileKey = id + ".m4a";
        var final = PathFor(fileKey);
        try
        {
            await using var target = new FileStream(partial, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                64 * 1024, FileOptions.Asynchronous | FileOptions.WriteThrough);
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[64 * 1024];
            long length = 0;
            while (true)
            {
                var read = await source.ReadAsync(buffer, cancellationToken);
                if (read == 0) break;
                length += read;
                if (length > ChatLimits.VoiceBytes) throw new InvalidDataException("chat_voice_too_large");
                hash.AppendData(buffer, 0, read);
                await target.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            }
            await target.FlushAsync(cancellationToken);
            target.Flush(flushToDisk: true);
            await target.DisposeAsync(); // Windows cannot rename an open FileShare.None handle.
            var duration = ChatAacContainer.Validate(await File.ReadAllBytesAsync(partial, cancellationToken));
            await _decoder.ValidateAsync(partial, cancellationToken);
            File.Move(partial, final);
            return new ChatVoiceUpload(fileKey, Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant(), length, duration);
        }
        catch
        {
            TryDelete(partial); TryDelete(final); throw;
        }
    }

    public Stream OpenRead(string fileKey) => new FileStream(PathFor(fileKey), FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
    public bool Delete(string fileKey) { var path = PathFor(fileKey); if (!File.Exists(path)) return true; File.Delete(path); return !File.Exists(path); }
    public IReadOnlyCollection<string> EnumerateFileKeys() => Directory.EnumerateFiles(_root).Select(Path.GetFileName).Where(x => x is not null).Cast<string>().ToArray();
    public DateTimeOffset? LastWriteTimeUtc(string fileKey) { var path = PathFor(fileKey); return File.Exists(path) ? File.GetLastWriteTimeUtc(path) : null; }
    private string PathFor(string fileKey)
    {
        if (string.IsNullOrWhiteSpace(fileKey) || fileKey != Path.GetFileName(fileKey)) throw new InvalidDataException("chat_voice_key_invalid");
        var path = Path.GetFullPath(Path.Combine(_root, fileKey));
        if (!path.StartsWith(_root + Path.DirectorySeparatorChar, StringComparison.Ordinal)) throw new InvalidDataException("chat_voice_key_invalid");
        return path;
    }
    private static void TryDelete(string path) { try { if (File.Exists(path)) File.Delete(path); } catch { } }
}
