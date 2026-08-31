using System.Buffers;
using System.Security.Cryptography;
using System.Globalization;
using Microsoft.Extensions.Options;
using SentriCam.Application.Common;
using SentriCam.Application.Recordings;
using Microsoft.Extensions.DependencyInjection;
using SentriCam.Application.Hub;

namespace SentriCam.Infrastructure.Storage;

public sealed class LocalFolderRecordingStorageProvider : IRecordingStorageProvider
{
    private readonly string _configuredRootPath;
    private readonly long _maximumUploadBytes;
    private readonly IHubSetupStore? _hubSetupStore;

    public LocalFolderRecordingStorageProvider(IOptions<RecordingStorageOptions> options)
        : this(options, null)
    {
    }

    [ActivatorUtilitiesConstructor]
    public LocalFolderRecordingStorageProvider(
        IOptions<RecordingStorageOptions> options,
        IHubSetupStore? hubSetupStore)
    {
        ArgumentNullException.ThrowIfNull(options);
        _maximumUploadBytes = options.Value.MaximumUploadBytes;
        _configuredRootPath = Path.GetFullPath(options.Value.RootPath, Directory.GetCurrentDirectory());
        _hubSetupStore = hubSetupStore;
        Directory.CreateDirectory(RootPath);
    }

    public async Task<StoredRecordingFile> SaveRecordingAsync(
        RecordingStorageWriteRequest request,
        Stream content,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(content);
        var relativePath = BuildRelativePath(request, ".mp4");
        var destination = Resolve(relativePath);
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        var temporary = destination + $".uploading-{Guid.NewGuid():N}";
        var buffer = ArrayPool<byte>.Shared.Rent(128 * 1024);
        long total = 0;
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        try
        {
            await using (var output = new FileStream(
                temporary,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                buffer.Length,
                FileOptions.Asynchronous | FileOptions.SequentialScan))
            {
                while (true)
                {
                    var count = await content.ReadAsync(buffer.AsMemory(0, buffer.Length), cancellationToken);
                    if (count == 0)
                    {
                        break;
                    }
                    total += count;
                    if (total > _maximumUploadBytes)
                    {
                        throw new PayloadTooLargeException(
                            $"Recording uploads cannot exceed {_maximumUploadBytes} bytes.");
                    }
                    hash.AppendData(buffer, 0, count);
                    await output.WriteAsync(buffer.AsMemory(0, count), cancellationToken);
                }
                await output.FlushAsync(cancellationToken);
            }
            if (total == 0)
            {
                throw new ResourceConflictException("An uploaded recording cannot be empty.");
            }
            File.Move(temporary, destination, overwrite: false);
            return new StoredRecordingFile(
                relativePath,
                total,
                Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant());
        }
        catch
        {
            TryDelete(temporary);
            throw;
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    public async Task<string> SaveThumbnailAsync(
        RecordingStorageWriteRequest request,
        GeneratedRecordingThumbnail thumbnail,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(thumbnail);
        var extension = thumbnail.Extension.StartsWith('.') ? thumbnail.Extension : $".{thumbnail.Extension}";
        if (!extension.Equals(".jpg", StringComparison.OrdinalIgnoreCase)
            || !string.Equals(thumbnail.ContentType, "image/jpeg", StringComparison.OrdinalIgnoreCase))
        {
            throw new ResourceConflictException("The generated thumbnail format is unsupported.");
        }
        var relativePath = BuildRelativePath(request, extension);
        var destination = Resolve(relativePath);
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        var temporary = destination + $".uploading-{Guid.NewGuid():N}";
        try
        {
            await File.WriteAllBytesAsync(temporary, thumbnail.Content, cancellationToken);
            File.Move(temporary, destination, overwrite: true);
            return relativePath;
        }
        catch
        {
            TryDelete(temporary);
            throw;
        }
    }

    public Task<Stream> OpenReadAsync(string relativePath, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var path = Resolve(relativePath);
        if (!File.Exists(path))
        {
            throw new ResourceNotFoundException("Stored recording object", relativePath);
        }
        Stream stream = new FileStream(
            path,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            128 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        return Task.FromResult(stream);
    }

    public Task DeleteIfExistsAsync(string relativePath, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        TryDelete(Resolve(relativePath));
        return Task.CompletedTask;
    }

    internal string ResolveForThumbnailGeneration(string relativePath) => Resolve(relativePath);

    private static string BuildRelativePath(RecordingStorageWriteRequest request, string extension) =>
        Path.Combine(
                request.DeviceId.ToString("N"),
                request.CreatedUtc.UtcDateTime.ToString("yyyy", CultureInfo.InvariantCulture),
                request.CreatedUtc.UtcDateTime.ToString("MM", CultureInfo.InvariantCulture),
                request.CreatedUtc.UtcDateTime.ToString("dd", CultureInfo.InvariantCulture),
                $"{request.RecordingId:N}{extension}")
            .Replace(Path.DirectorySeparatorChar, '/');

    private string Resolve(string relativePath)
    {
        if (string.IsNullOrWhiteSpace(relativePath) || Path.IsPathRooted(relativePath))
        {
            throw new ResourceConflictException("The recording storage key is invalid.");
        }
        var rootPath = RootPath;
        var fullPath = Path.GetFullPath(relativePath, rootPath);
        var prefix = rootPath.EndsWith(Path.DirectorySeparatorChar)
            ? rootPath
            : rootPath + Path.DirectorySeparatorChar;
        var comparison = OperatingSystem.IsWindows()
            ? StringComparison.OrdinalIgnoreCase
            : StringComparison.Ordinal;
        if (!fullPath.StartsWith(prefix, comparison))
        {
            throw new ResourceConflictException("The recording storage key escaped its configured root.");
        }
        return fullPath;
    }

    private string RootPath => _hubSetupStore is null
        ? _configuredRootPath
        : Path.GetFullPath(_hubSetupStore.Current.Draft.RecordingFolder, Directory.GetCurrentDirectory());

    private static void TryDelete(string path)
    {
        if (File.Exists(path))
        {
            File.Delete(path);
        }
    }
}
