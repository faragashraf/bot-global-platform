using System.Diagnostics;
using BotGlobal.Communication.Domain.Chat;

namespace BotGlobal.Communication.Application.Chat;

// One fixed decoding profile, no shell, URLs, external files, or retained audio output.
internal sealed class ChatAacDecoder
{
    private static readonly SemaphoreSlim Slots = new(2, 2);
    private readonly string _executable;
    private readonly TimeSpan _timeout;
    internal const long MaximumPcmBytes = 300L * 8000 * 2;

    public ChatAacDecoder(string executable = "ffmpeg", TimeSpan? timeout = null)
    {
        if (executable != "ffmpeg" && (!Path.IsPathFullyQualified(executable) ||
            executable.IndexOfAny(['\0', '\r', '\n']) >= 0))
            throw new ArgumentException("Decoder must be ffmpeg on PATH or an absolute executable path.", nameof(executable));
        _executable = executable;
        _timeout = timeout ?? TimeSpan.FromSeconds(20);
        if (_timeout <= TimeSpan.Zero || _timeout > TimeSpan.FromSeconds(20)) throw new ArgumentOutOfRangeException(nameof(timeout));
    }

    public async Task ValidateAsync(string ownedPath, CancellationToken cancellationToken)
    {
        var length = new FileInfo(ownedPath).Length;
        if (length is <= 0 or > ChatLimits.VoiceBytes) throw Invalid();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(_timeout); // Includes queue time: overload cannot accumulate unbounded waits.
        var entered = false;
        try
        {
            await Slots.WaitAsync(deadline.Token);
            entered = true;
            await DecodeAsync(ownedPath, deadline.Token);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { throw Invalid(); }
        catch (System.ComponentModel.Win32Exception) { throw new InvalidDataException("chat_voice_decoder_unavailable"); }
        finally { if (entered) Slots.Release(); }
    }

    private async Task DecodeAsync(string ownedPath, CancellationToken token)
    {
        var start = new ProcessStartInfo(_executable)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true,
        };
        foreach (var argument in new[] { "-hide_banner", "-loglevel", "error", "-nostdin", "-xerror",
            "-max_alloc", "16777216", "-threads", "1", "-err_detect", "explode",
            "-protocol_whitelist", "file,pipe", "-enable_drefs", "0", "-use_absolute_path", "0",
            "-f", "mov", "-i", Path.GetFullPath(ownedPath), "-map", "0:a:0",
            "-vn", "-sn", "-dn", "-threads", "1", "-ac", "1", "-ar", "8000", "-f", "s16le", "pipe:1" })
            start.ArgumentList.Add(argument);
        using var process = new Process { StartInfo = start };
        if (!process.Start()) throw Invalid();
        using var stop = token.Register(() => Kill(process));
        var output = DrainAsync(process.StandardOutput.BaseStream, MaximumPcmBytes, process, token);
        var errors = DrainAsync(process.StandardError.BaseStream, 16 * 1024, process, token);
        try
        {
            await Task.WhenAll(output, errors, process.WaitForExitAsync(token));
            token.ThrowIfCancellationRequested();
            if (process.ExitCode != 0 || output.Result == 0 || errors.Result != 0) throw Invalid();
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception error) when (error is IOException or InvalidOperationException) { throw Invalid(); }
        finally
        {
            Kill(process);
            // Reap and settle pipe tasks before disposing handles; no leaked child or owned temp file.
            await process.WaitForExitAsync(CancellationToken.None);
            try { await Task.WhenAll(output, errors); } catch { }
        }
    }

    private static async Task<long> DrainAsync(Stream stream, long ceiling, Process process, CancellationToken token)
    {
        var buffer = new byte[8192]; long count = 0;
        while (true)
        {
            var read = await stream.ReadAsync(buffer, token);
            if (read == 0) return count;
            count += read;
            if (count > ceiling) { Kill(process); throw Invalid(); }
        }
    }
    private static void Kill(Process process) { try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch (InvalidOperationException) { } }
    private static InvalidDataException Invalid() => new("chat_voice_payload_invalid");
}
