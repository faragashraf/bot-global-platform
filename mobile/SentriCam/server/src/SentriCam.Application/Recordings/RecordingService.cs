using System.Globalization;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Common;
using SentriCam.Contracts.Recordings;
using SentriCam.Domain.Common;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Recordings;
using DomainThumbnailState = SentriCam.Domain.Recordings.ThumbnailGenerationState;

namespace SentriCam.Application.Recordings;

public sealed class RecordingService(
    IRecordingRepository recordingRepository,
    IDeviceRepository deviceRepository,
    IRecordingStorageProvider storageProvider,
    IRecordingThumbnailScheduler thumbnailQueue,
    IDeviceRequestAuthorizer requestAuthorizer,
    IUnitOfWork unitOfWork,
    TimeProvider timeProvider) : IRecordingService
{
    public const int MaximumPageSize = 100;
    public const int MaximumSearchLength = 100;

    public async Task<RecordingUploadResult> UploadAsync(
        UploadRecording upload,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(upload);
        ArgumentNullException.ThrowIfNull(upload.Content);
        ValidateUploadFormat(upload);
        var deviceId = DeviceId.From(upload.DeviceId);
        requestAuthorizer.EnsureCanAccess(deviceId);

        var existing = await recordingRepository.GetByClientRecordingIdAsync(
            deviceId,
            upload.ClientRecordingId,
            cancellationToken);
        if (existing is not null)
        {
            EnsureDuplicateMatches(existing, upload);
            var existingDevice = await RequireDeviceAsync(deviceId, cancellationToken);
            return new RecordingUploadResult(Map(existing, existingDevice.DisplayName), true);
        }

        var device = await RequireDeviceAsync(deviceId, cancellationToken);
        var recordingId = Guid.NewGuid();
        var storageRequest = new RecordingStorageWriteRequest(
            recordingId,
            deviceId.Value,
            upload.OriginalFileName,
            upload.CreatedUtc);
        var stored = await storageProvider.SaveRecordingAsync(storageRequest, upload.Content, cancellationToken);
        try
        {
            if (stored.SizeBytes != upload.DeclaredSizeBytes)
            {
                throw new ResourceConflictException("The uploaded recording size does not match its metadata.");
            }
            if (!string.Equals(stored.ChecksumSha256, upload.ChecksumSha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new ResourceConflictException("The uploaded recording checksum does not match its metadata.");
            }

            var uploadedAtUtc = timeProvider.GetUtcNow();
            var recording = Recording.Create(
                recordingId,
                deviceId,
                upload.ClientRecordingId,
                upload.SessionId,
                upload.OriginalFileName,
                upload.ContentType,
                upload.DurationMilliseconds,
                stored.SizeBytes,
                upload.CreatedUtc,
                uploadedAtUtc,
                upload.Motion,
                upload.Manual,
                stored.ChecksumSha256,
                stored.RelativePath);
            recordingRepository.Add(recording);
            await unitOfWork.SaveChangesAsync(cancellationToken);
            thumbnailQueue.Enqueue(recordingId);
            return new RecordingUploadResult(Map(recording, device.DisplayName), false);
        }
        catch
        {
            await storageProvider.DeleteIfExistsAsync(stored.RelativePath, CancellationToken.None);
            throw;
        }
    }

    public async Task<RecordingView?> FindUploadAsync(
        Guid deviceId,
        string clientRecordingId,
        CancellationToken cancellationToken = default)
    {
        var id = DeviceId.From(deviceId);
        requestAuthorizer.EnsureCanAccess(id);
        var recording = await recordingRepository.GetByClientRecordingIdAsync(id, clientRecordingId, cancellationToken);
        if (recording is null)
        {
            return null;
        }
        var device = await RequireDeviceAsync(id, cancellationToken);
        return Map(recording, device.DisplayName);
    }

    public async Task<PagedRecordingResult> ListAsync(
        RecordingQuery query,
        CancellationToken cancellationToken = default)
    {
        var normalized = ValidateAndNormalize(query);
        var result = await recordingRepository.QueryAsync(normalized, cancellationToken);
        var totalPages = result.TotalCount == 0
            ? 0
            : (int)Math.Ceiling(result.TotalCount / (double)normalized.PageSize);
        return new PagedRecordingResult(
            result.Items.Select(Map).ToArray(),
            normalized.Page,
            normalized.PageSize,
            result.TotalCount,
            totalPages,
            normalized.Page > 1,
            normalized.Page < totalPages);
    }

    public async Task<IReadOnlyList<RecordingTimeNode>> AggregateTimeAsync(
        RecordingTimeQuery query,
        CancellationToken cancellationToken = default)
    {
        var normalized = ValidateAndNormalize(query);
        var buckets = await recordingRepository.AggregateTimeAsync(normalized, cancellationToken);
        return normalized.Level == RecordingTimeLevel.Week
            ? BuildWeekNodes(buckets, normalized)
            : buckets.Select(bucket => BuildTimeNode(bucket, normalized.Level)).ToArray();
    }

    public async Task RequestThumbnailRegenerationAsync(
        Guid recordingId,
        CancellationToken cancellationToken = default)
    {
        var recording = await RequireRecordingAsync(recordingId, cancellationToken);
        recording.RequestThumbnailRegeneration();
        await unitOfWork.SaveChangesAsync(cancellationToken);
        thumbnailQueue.Enqueue(recordingId);
    }

    public async Task<RecordingContent> OpenContentAsync(
        Guid recordingId,
        CancellationToken cancellationToken = default)
    {
        var recording = await RequireRecordingAsync(recordingId, cancellationToken);
        var stream = await storageProvider.OpenReadAsync(recording.RelativePath, cancellationToken);
        return new RecordingContent(
            stream,
            recording.ContentType,
            recording.OriginalFileName,
            recording.SizeBytes,
            recording.UploadedUtc,
            $"\"{recording.ChecksumSha256}\"");
    }

    public async Task<RecordingContent> OpenThumbnailAsync(
        Guid recordingId,
        CancellationToken cancellationToken = default)
    {
        var recording = await RequireRecordingAsync(recordingId, cancellationToken);
        if (recording.ThumbnailRelativePath is null)
        {
            throw new ResourceNotFoundException("Recording thumbnail", recordingId);
        }
        var stream = await storageProvider.OpenReadAsync(recording.ThumbnailRelativePath, cancellationToken);
        var contentType = Path.GetExtension(recording.ThumbnailRelativePath).Equals(".jpg", StringComparison.OrdinalIgnoreCase)
            ? "image/jpeg"
            : "image/svg+xml";
        var modified = recording.ThumbnailGeneratedUtc ?? recording.UploadedUtc;
        return new RecordingContent(
            stream,
            contentType,
            $"{recording.Id}-thumbnail{Path.GetExtension(recording.ThumbnailRelativePath)}",
            stream.Length,
            modified,
            $"\"thumbnail-{recording.Id:N}-{modified.UtcTicks}\"");
    }

    public async Task DeleteAsync(Guid recordingId, CancellationToken cancellationToken = default)
    {
        var recording = await RequireRecordingAsync(recordingId, cancellationToken);
        var storedObjects = new[] { recording.RelativePath, recording.ThumbnailRelativePath }
            .Where(path => path is not null)
            .Cast<string>()
            .ToArray();
        recordingRepository.Remove(recording);
        await unitOfWork.SaveChangesAsync(cancellationToken);

        List<Exception>? cleanupFailures = null;
        foreach (var storedObject in storedObjects)
        {
            try
            {
                await storageProvider.DeleteIfExistsAsync(storedObject, CancellationToken.None);
            }
            catch (Exception exception)
            {
                cleanupFailures ??= [];
                cleanupFailures.Add(exception);
            }
        }

        if (cleanupFailures is not null)
        {
            throw new AggregateException(
                "Recording metadata was deleted, but one or more stored objects could not be removed.",
                cleanupFailures);
        }
    }

    private async Task<Device> RequireDeviceAsync(DeviceId id, CancellationToken cancellationToken) =>
        await deviceRepository.GetByIdAsync(id, cancellationToken)
        ?? throw new ResourceNotFoundException(nameof(Device), id.Value);

    private async Task<Recording> RequireRecordingAsync(Guid id, CancellationToken cancellationToken)
    {
        var recording = await recordingRepository.GetByIdAsync(id, cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(Recording), id);
        requestAuthorizer.EnsureCanAccess(recording.DeviceId);
        return recording;
    }

    private static RecordingQuery ValidateAndNormalize(RecordingQuery query)
    {
        ArgumentNullException.ThrowIfNull(query);
        ValidateCommon(
            query.DeviceIds,
            query.Search,
            query.DateFromUtc,
            query.DateToUtc,
            query.HourFrom,
            query.HourTo,
            query.MinimumDurationMilliseconds,
            query.MaximumDurationMilliseconds,
            query.MinimumSizeBytes,
            query.MaximumSizeBytes);
        if (!Enum.IsDefined(query.Sort))
        {
            throw new DomainValidationException("The recording sort is invalid.");
        }
        if (query.Page < 1)
        {
            throw new DomainValidationException("Recording page must be at least 1.");
        }
        if (query.PageSize is < 1 or > MaximumPageSize)
        {
            throw new DomainValidationException($"Recording page size must be between 1 and {MaximumPageSize}.");
        }
        if (query.UploadState is not null && query.UploadState != RecordingUploadState.Completed)
        {
            throw new DomainValidationException("The recording upload state is invalid.");
        }

        var normalized = query with
        {
            DeviceIds = query.DeviceIds?.Distinct().ToArray(),
            Search = NormalizeSearch(query.Search),
        };
        if (query.ExactDayUtc is not null)
        {
            var start = new DateTimeOffset(query.ExactDayUtc.Value.ToDateTime(TimeOnly.MinValue), TimeSpan.Zero);
            normalized = normalized with { DateFromUtc = start, DateToUtc = start.AddDays(1) };
        }
        return normalized;
    }

    private static RecordingTimeQuery ValidateAndNormalize(RecordingTimeQuery query)
    {
        ArgumentNullException.ThrowIfNull(query);
        ValidateCommon(
            query.DeviceIds,
            query.Search,
            query.DateFromUtc,
            query.DateToUtc,
            query.HourFrom,
            query.HourTo,
            query.MinimumDurationMilliseconds,
            query.MaximumDurationMilliseconds,
            query.MinimumSizeBytes,
            query.MaximumSizeBytes);
        if (!Enum.IsDefined(query.Level))
        {
            throw new DomainValidationException("The time aggregation level is invalid.");
        }
        if (query.Level != RecordingTimeLevel.Year
            && (query.ParentStartUtc is null || query.ParentEndUtc is null))
        {
            throw new DomainValidationException("A parent UTC range is required below the year level.");
        }
        if (query.ParentStartUtc >= query.ParentEndUtc)
        {
            throw new DomainValidationException("The time aggregation parent range is invalid.");
        }
        return query with
        {
            DeviceIds = query.DeviceIds?.Distinct().ToArray(),
            Search = NormalizeSearch(query.Search),
        };
    }

    private static void ValidateCommon(
        IReadOnlyCollection<Guid>? deviceIds,
        string? search,
        DateTimeOffset? dateFromUtc,
        DateTimeOffset? dateToUtc,
        int? hourFrom,
        int? hourTo,
        long? minimumDurationMilliseconds,
        long? maximumDurationMilliseconds,
        long? minimumSizeBytes,
        long? maximumSizeBytes)
    {
        if (deviceIds is { Count: > 100 } || deviceIds?.Any(id => id == Guid.Empty) == true)
        {
            throw new DomainValidationException("Between 1 and 100 valid device ids may be queried.");
        }
        if (search?.Trim().Length > MaximumSearchLength)
        {
            throw new DomainValidationException($"Recording search cannot exceed {MaximumSearchLength} characters.");
        }
        if (dateFromUtc >= dateToUtc)
        {
            throw new DomainValidationException("Recording date range is invalid.");
        }
        if (hourFrom is < 0 or > 23 || hourTo is < 1 or > 24 || hourFrom >= hourTo)
        {
            throw new DomainValidationException("Recording hour range must be between 0 and 24 with the start before the end.");
        }
        if (minimumDurationMilliseconds is < 0 || maximumDurationMilliseconds is < 0
            || minimumDurationMilliseconds > maximumDurationMilliseconds)
        {
            throw new DomainValidationException("Recording duration range is invalid.");
        }
        if (minimumSizeBytes is < 0 || maximumSizeBytes is < 0 || minimumSizeBytes > maximumSizeBytes)
        {
            throw new DomainValidationException("Recording size range is invalid.");
        }
    }

    private static string? NormalizeSearch(string? search) =>
        string.IsNullOrWhiteSpace(search) ? null : search.Trim();

    private static RecordingTimeNode[] BuildWeekNodes(
        IReadOnlyList<RecordingTimeBucket> dailyBuckets,
        RecordingTimeQuery query)
    {
        var parentStart = query.ParentStartUtc!.Value;
        var parentEnd = query.ParentEndUtc!.Value;
        return dailyBuckets
            .GroupBy(bucket =>
            {
                var date = new DateOnly(bucket.Year, bucket.Month!.Value, bucket.Day!.Value);
                return date.AddDays(-(((int)date.DayOfWeek + 6) % 7));
            })
            .Select(group =>
            {
                var monday = group.Key;
                var fullStart = new DateTimeOffset(monday.ToDateTime(TimeOnly.MinValue), TimeSpan.Zero);
                var start = fullStart < parentStart ? parentStart : fullStart;
                var end = fullStart.AddDays(7) > parentEnd ? parentEnd : fullStart.AddDays(7);
                var isoWeek = ISOWeek.GetWeekOfYear(monday.ToDateTime(TimeOnly.MinValue));
                return new RecordingTimeNode(
                    $"week:{start:yyyy-MM-ddTHH:mm:ssZ}",
                    $"Week {isoWeek}",
                    start,
                    end,
                    group.Sum(bucket => bucket.RecordingCount),
                    group.Sum(bucket => bucket.TotalSizeBytes),
                    true,
                    RecordingTimeLevel.Day);
            })
            .OrderByDescending(node => node.StartUtc)
            .ToArray();
    }

    private static RecordingTimeNode BuildTimeNode(RecordingTimeBucket bucket, RecordingTimeLevel level)
    {
        DateTimeOffset start;
        string label;
        switch (level)
        {
            case RecordingTimeLevel.Year:
                start = new DateTimeOffset(bucket.Year, 1, 1, 0, 0, 0, TimeSpan.Zero);
                label = bucket.Year.ToString(CultureInfo.InvariantCulture);
                break;
            case RecordingTimeLevel.Month:
                start = new DateTimeOffset(bucket.Year, bucket.Month!.Value, 1, 0, 0, 0, TimeSpan.Zero);
                label = start.ToString("MMMM", CultureInfo.InvariantCulture);
                break;
            case RecordingTimeLevel.Day:
                start = new DateTimeOffset(bucket.Year, bucket.Month!.Value, bucket.Day!.Value, 0, 0, 0, TimeSpan.Zero);
                label = start.ToString("dddd d MMM", CultureInfo.InvariantCulture);
                break;
            case RecordingTimeLevel.Hour:
                start = new DateTimeOffset(bucket.Year, bucket.Month!.Value, bucket.Day!.Value, bucket.Hour!.Value, 0, 0, TimeSpan.Zero);
                label = start.ToString("HH':00'", CultureInfo.InvariantCulture);
                break;
            default:
                throw new ArgumentOutOfRangeException(nameof(level), level, null);
        }
        var end = level switch
        {
            RecordingTimeLevel.Year => start.AddYears(1),
            RecordingTimeLevel.Month => start.AddMonths(1),
            RecordingTimeLevel.Day => start.AddDays(1),
            RecordingTimeLevel.Hour => start.AddHours(1),
            _ => start,
        };
        RecordingTimeLevel? child = level == RecordingTimeLevel.Hour
            ? null
            : (RecordingTimeLevel)((int)level + 1);
        return new RecordingTimeNode(
            $"{level.ToString().ToLowerInvariant()}:{start:yyyy-MM-ddTHH:mm:ssZ}",
            label,
            start,
            end,
            bucket.RecordingCount,
            bucket.TotalSizeBytes,
            child is not null,
            child);
    }

    private static void EnsureDuplicateMatches(Recording existing, UploadRecording upload)
    {
        if (!string.Equals(existing.ChecksumSha256, upload.ChecksumSha256, StringComparison.OrdinalIgnoreCase)
            || existing.SizeBytes != upload.DeclaredSizeBytes)
        {
            throw new ResourceConflictException(
                "The client recording id is already associated with different content.");
        }
    }

    private static void ValidateUploadFormat(UploadRecording upload)
    {
        if (!Path.GetExtension(upload.OriginalFileName).Equals(".mp4", StringComparison.OrdinalIgnoreCase)
            || !string.Equals(upload.ContentType, "video/mp4", StringComparison.OrdinalIgnoreCase))
        {
            throw new DomainValidationException("Recording Upload V1 accepts MP4 video only.");
        }
    }

    private static RecordingView Map(RecordingProjection recording) => new(
        recording.RecordingId,
        recording.DeviceId,
        recording.DeviceName,
        recording.ClientRecordingId,
        recording.SessionId,
        recording.FileName,
        recording.ContentType,
        recording.DurationMilliseconds,
        recording.SizeBytes,
        recording.CreatedUtc,
        recording.UploadedUtc,
        recording.Motion,
        recording.Manual,
        !string.IsNullOrWhiteSpace(recording.SessionId),
        RecordingUploadState.Completed,
        (RecordingThumbnailState)recording.ThumbnailState,
        recording.ThumbnailGenerationAttempts,
        recording.ThumbnailErrorCode,
        recording.ThumbnailGeneratedUtc,
        recording.ChecksumSha256,
        $"/api/v1/recordings/{recording.RecordingId}/content",
        $"/api/v1/recordings/{recording.RecordingId}/download",
        recording.HasThumbnail ? $"/api/v1/recordings/{recording.RecordingId}/thumbnail" : null);

    private static RecordingView Map(Recording recording, string deviceName) => new(
        recording.Id,
        recording.DeviceId.Value,
        deviceName,
        recording.ClientRecordingId,
        recording.SessionId,
        recording.OriginalFileName,
        recording.ContentType,
        recording.DurationMilliseconds,
        recording.SizeBytes,
        recording.CreatedUtc,
        recording.UploadedUtc,
        recording.IsMotion,
        recording.IsManual,
        !string.IsNullOrWhiteSpace(recording.SessionId),
        RecordingUploadState.Completed,
        (RecordingThumbnailState)recording.ThumbnailState,
        recording.ThumbnailGenerationAttempts,
        recording.ThumbnailErrorCode,
        recording.ThumbnailGeneratedUtc,
        recording.ChecksumSha256,
        $"/api/v1/recordings/{recording.Id}/content",
        $"/api/v1/recordings/{recording.Id}/download",
        recording.ThumbnailRelativePath is null ? null : $"/api/v1/recordings/{recording.Id}/thumbnail");
}
