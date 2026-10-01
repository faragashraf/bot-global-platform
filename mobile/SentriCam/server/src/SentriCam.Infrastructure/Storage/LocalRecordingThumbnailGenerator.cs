using System.Diagnostics;
using System.Globalization;
using Microsoft.Extensions.Options;
using SentriCam.Application.Recordings;
using Microsoft.Extensions.DependencyInjection;
using SentriCam.Application.Hub;

namespace SentriCam.Infrastructure.Storage;

public sealed class LocalRecordingThumbnailGenerator : IRecordingThumbnailGenerator
{
    private readonly LocalFolderRecordingStorageProvider _storage;
    private readonly IOptions<RecordingStorageOptions> _options;
    private readonly IMediaEngineProbe? _mediaEngineProbe;
    private readonly IHubSetupStore? _hubSetupStore;

    public LocalRecordingThumbnailGenerator(
        LocalFolderRecordingStorageProvider storage,
        IOptions<RecordingStorageOptions> options)
        : this(storage, options, null, null)
    {
    }

    [ActivatorUtilitiesConstructor]
    public LocalRecordingThumbnailGenerator(
        LocalFolderRecordingStorageProvider storage,
        IOptions<RecordingStorageOptions> options,
        IMediaEngineProbe? mediaEngineProbe,
        IHubSetupStore? hubSetupStore)
    {
        _storage = storage;
        _options = options;
        _mediaEngineProbe = mediaEngineProbe;
        _hubSetupStore = hubSetupStore;
    }

    public async Task<GeneratedRecordingThumbnail> GenerateAsync(
        RecordingThumbnailRequest request,
        CancellationToken cancellationToken = default)
    {
        var executable = ResolveExecutable();
        if (string.IsNullOrWhiteSpace(executable))
        {
            throw new RecordingThumbnailGenerationException(
                "ffmpeg_not_configured",
                "A media processor is not configured for recording thumbnails.");
        }

        var source = _storage.ResolveForThumbnailGeneration(request.RecordingRelativePath);
        var temporary = Path.Combine(
            Path.GetTempPath(),
            $"sentricam-thumb-{request.RecordingId:N}-{Guid.NewGuid():N}.jpg");
        try
        {
            using var process = CreateProcess(request, source, temporary, executable);
            try
            {
                if (!process.Start())
                {
                    throw new RecordingThumbnailGenerationException(
                        "ffmpeg_unavailable",
                        "The configured media processor could not be started.");
                }
            }
            catch (Exception exception) when (exception is not RecordingThumbnailGenerationException)
            {
                throw new RecordingThumbnailGenerationException(
                    "ffmpeg_unavailable",
                    "The configured media processor could not be started.",
                    exception);
            }

            var stderr = process.StandardError.ReadToEndAsync(cancellationToken);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(_options.Value.ThumbnailTimeoutSeconds));
            try
            {
                await process.WaitForExitAsync(timeout.Token);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                TryKill(process);
                throw new RecordingThumbnailGenerationException(
                    "ffmpeg_timeout",
                    "The media processor exceeded the thumbnail generation timeout.");
            }
            _ = await stderr;

            if (process.ExitCode != 0 || !File.Exists(temporary))
            {
                throw new RecordingThumbnailGenerationException(
                    "ffmpeg_failed",
                    "The media processor could not extract a representative video frame.");
            }
            var info = new FileInfo(temporary);
            if (info.Length is <= 0 or > 2_097_152)
            {
                throw new RecordingThumbnailGenerationException(
                    "invalid_thumbnail_output",
                    "The generated thumbnail file size is invalid.");
            }
            return new GeneratedRecordingThumbnail(
                await File.ReadAllBytesAsync(temporary, cancellationToken),
                ".jpg",
                "image/jpeg");
        }
        finally
        {
            if (File.Exists(temporary))
            {
                File.Delete(temporary);
            }
        }
    }

    internal Process CreateProcess(
        RecordingThumbnailRequest request,
        string source,
        string destination,
        string? executable = null)
    {
        var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = executable ?? _options.Value.FfmpegPath,
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            },
        };
        var seekSeconds = GetRepresentativeSeekSeconds(request.DurationMilliseconds);
        var filter = string.Create(
            CultureInfo.InvariantCulture,
            $"thumbnail=30,scale={_options.Value.ThumbnailWidth}:{_options.Value.ThumbnailHeight}:force_original_aspect_ratio=decrease,pad={_options.Value.ThumbnailWidth}:{_options.Value.ThumbnailHeight}:(ow-iw)/2:(oh-ih)/2");
        AddArguments(
            process.StartInfo,
            "-hide_banner",
            "-loglevel", "error",
            "-nostdin",
            "-ss", seekSeconds.ToString("0.###", CultureInfo.InvariantCulture),
            "-i", source,
            "-frames:v", "1",
            "-vf", filter,
            "-q:v", _options.Value.ThumbnailJpegQuality.ToString(CultureInfo.InvariantCulture),
            "-y",
            destination);
        return process;
    }

    private string? ResolveExecutable()
    {
        if (_mediaEngineProbe is not null && _hubSetupStore is not null)
        {
            return _mediaEngineProbe.ResolveExecutable(_hubSetupStore.Current.Draft.Media);
        }
        return _options.Value.FfmpegPath;
    }

    internal static double GetRepresentativeSeekSeconds(long durationMilliseconds)
    {
        var durationSeconds = Math.Max(0, durationMilliseconds / 1000d);
        if (durationSeconds <= 1)
        {
            return Math.Max(0, durationSeconds * 0.25);
        }
        return Math.Clamp(durationSeconds * 0.1, 1, Math.Min(30, Math.Max(1, durationSeconds - 0.5)));
    }

    private static void AddArguments(ProcessStartInfo startInfo, params string[] arguments)
    {
        foreach (var argument in arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }
    }

    private static void TryKill(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (InvalidOperationException)
        {
        }
    }
}
