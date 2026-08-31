using Microsoft.EntityFrameworkCore;
using System.Globalization;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Recordings;
using SentriCam.Contracts.Recordings;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Recordings;

namespace SentriCam.Infrastructure.Persistence.Repositories;

public sealed class RecordingRepository(SentriCamDbContext dbContext) : IRecordingRepository
{
    public Task<Recording?> GetByIdAsync(Guid id, CancellationToken cancellationToken = default) =>
        dbContext.Recordings.SingleOrDefaultAsync(recording => recording.Id == id, cancellationToken);

    public Task<Recording?> GetByClientRecordingIdAsync(
        DeviceId deviceId,
        string clientRecordingId,
        CancellationToken cancellationToken = default) =>
        dbContext.Recordings.SingleOrDefaultAsync(
            recording => recording.DeviceId == deviceId
                && recording.ClientRecordingId == clientRecordingId,
            cancellationToken);

    public async Task<PagedRecordingProjection> QueryAsync(
        RecordingQuery query,
        CancellationToken cancellationToken = default)
    {
        var filtered = ApplyFilters(BuildRows(), query);
        var total = await filtered.LongCountAsync(cancellationToken);
        var ordered = query.Sort switch
        {
            RecordingSort.Oldest => filtered
                .OrderBy(row => row.Recording.CreatedUtc)
                .ThenBy(row => row.Recording.Id),
            RecordingSort.Longest => filtered
                .OrderByDescending(row => row.Recording.DurationMilliseconds)
                .ThenByDescending(row => row.Recording.CreatedUtc)
                .ThenBy(row => row.Recording.Id),
            RecordingSort.Shortest => filtered
                .OrderBy(row => row.Recording.DurationMilliseconds)
                .ThenByDescending(row => row.Recording.CreatedUtc)
                .ThenBy(row => row.Recording.Id),
            RecordingSort.Largest => filtered
                .OrderByDescending(row => row.Recording.SizeBytes)
                .ThenByDescending(row => row.Recording.CreatedUtc)
                .ThenBy(row => row.Recording.Id),
            RecordingSort.Smallest => filtered
                .OrderBy(row => row.Recording.SizeBytes)
                .ThenByDescending(row => row.Recording.CreatedUtc)
                .ThenBy(row => row.Recording.Id),
            RecordingSort.DeviceName => filtered
                .OrderBy(row => row.DeviceName)
                .ThenByDescending(row => row.Recording.CreatedUtc)
                .ThenBy(row => row.Recording.Id),
            RecordingSort.UploadTime => filtered
                .OrderByDescending(row => row.Recording.UploadedUtc)
                .ThenByDescending(row => row.Recording.CreatedUtc)
                .ThenBy(row => row.Recording.Id),
            RecordingSort.RecordingStartTime or RecordingSort.Newest => filtered
                .OrderByDescending(row => row.Recording.CreatedUtc)
                .ThenBy(row => row.Recording.Id),
            _ => throw new ArgumentOutOfRangeException(nameof(query), query.Sort, "Unsupported recording sort."),
        };

        var items = await ordered
            .Skip((query.Page - 1) * query.PageSize)
            .Take(query.PageSize)
            .Select(row => new RecordingProjection(
                row.Recording.Id,
                row.Recording.DeviceId.Value,
                row.DeviceName,
                row.Recording.ClientRecordingId,
                row.Recording.SessionId,
                row.Recording.OriginalFileName,
                row.Recording.ContentType,
                row.Recording.DurationMilliseconds,
                row.Recording.SizeBytes,
                row.Recording.CreatedUtc,
                row.Recording.UploadedUtc,
                row.Recording.IsMotion,
                row.Recording.IsManual,
                (int)row.Recording.ThumbnailState,
                row.Recording.ThumbnailGenerationAttempts,
                row.Recording.ThumbnailErrorCode,
                row.Recording.ThumbnailGeneratedUtc,
                row.Recording.ChecksumSha256,
                row.Recording.ThumbnailRelativePath != null))
            .ToArrayAsync(cancellationToken);

        return new PagedRecordingProjection(items, total);
    }

    public async Task<IReadOnlyList<RecordingTimeBucket>> AggregateTimeAsync(
        RecordingTimeQuery query,
        CancellationToken cancellationToken = default)
    {
        var rows = ApplyFilters(BuildRows(), query);
        if (query.ParentStartUtc is not null)
        {
            rows = rows.Where(row => row.Recording.CreatedUtc >= query.ParentStartUtc);
        }
        if (query.ParentEndUtc is not null)
        {
            rows = rows.Where(row => row.Recording.CreatedUtc < query.ParentEndUtc);
        }

        if (dbContext.Database.IsSqlite())
        {
            return await AggregateSqliteTimeAsync(rows, query.Level, cancellationToken);
        }

        switch (query.Level)
        {
            case RecordingTimeLevel.Year:
                {
                    var result = await rows
                        .GroupBy(row => row.Recording.CreatedUtc.Year)
                        .Select(group => new { Year = group.Key, Count = group.LongCount(), Size = group.Sum(row => row.Recording.SizeBytes) })
                        .OrderByDescending(bucket => bucket.Year)
                        .ToArrayAsync(cancellationToken);
                    return result.Select(bucket => new RecordingTimeBucket(bucket.Year, null, null, null, bucket.Count, bucket.Size)).ToArray();
                }
            case RecordingTimeLevel.Month:
                {
                    var result = await rows
                        .GroupBy(row => new { row.Recording.CreatedUtc.Year, row.Recording.CreatedUtc.Month })
                        .Select(group => new { group.Key.Year, group.Key.Month, Count = group.LongCount(), Size = group.Sum(row => row.Recording.SizeBytes) })
                        .OrderByDescending(bucket => bucket.Year)
                        .ThenByDescending(bucket => bucket.Month)
                        .ToArrayAsync(cancellationToken);
                    return result.Select(bucket => new RecordingTimeBucket(bucket.Year, bucket.Month, null, null, bucket.Count, bucket.Size)).ToArray();
                }
            case RecordingTimeLevel.Week:
            case RecordingTimeLevel.Day:
                {
                    var result = await rows
                        .GroupBy(row => new { row.Recording.CreatedUtc.Year, row.Recording.CreatedUtc.Month, row.Recording.CreatedUtc.Day })
                        .Select(group => new { group.Key.Year, group.Key.Month, group.Key.Day, Count = group.LongCount(), Size = group.Sum(row => row.Recording.SizeBytes) })
                        .OrderByDescending(bucket => bucket.Year)
                        .ThenByDescending(bucket => bucket.Month)
                        .ThenByDescending(bucket => bucket.Day)
                        .ToArrayAsync(cancellationToken);
                    return result.Select(bucket => new RecordingTimeBucket(bucket.Year, bucket.Month, bucket.Day, null, bucket.Count, bucket.Size)).ToArray();
                }
            case RecordingTimeLevel.Hour:
                {
                    var result = await rows
                        .GroupBy(row => new { row.Recording.CreatedUtc.Year, row.Recording.CreatedUtc.Month, row.Recording.CreatedUtc.Day, row.Recording.CreatedUtc.Hour })
                        .Select(group => new { group.Key.Year, group.Key.Month, group.Key.Day, group.Key.Hour, Count = group.LongCount(), Size = group.Sum(row => row.Recording.SizeBytes) })
                        .OrderByDescending(bucket => bucket.Year)
                        .ThenByDescending(bucket => bucket.Month)
                        .ThenByDescending(bucket => bucket.Day)
                        .ThenByDescending(bucket => bucket.Hour)
                        .ToArrayAsync(cancellationToken);
                    return result.Select(bucket => new RecordingTimeBucket(bucket.Year, bucket.Month, bucket.Day, bucket.Hour, bucket.Count, bucket.Size)).ToArray();
                }
            default:
                throw new ArgumentOutOfRangeException(nameof(query), query.Level, "Unsupported time level.");
        }
    }

    private static async Task<IReadOnlyList<RecordingTimeBucket>> AggregateSqliteTimeAsync(
        IQueryable<RecordingQueryRow> rows,
        RecordingTimeLevel level,
        CancellationToken cancellationToken)
    {
        var format = level switch
        {
            RecordingTimeLevel.Year => "%Y",
            RecordingTimeLevel.Month => "%Y-%m",
            RecordingTimeLevel.Week or RecordingTimeLevel.Day => "%Y-%m-%d",
            RecordingTimeLevel.Hour => "%Y-%m-%d-%H",
            _ => throw new ArgumentOutOfRangeException(nameof(level), level, "Unsupported time level."),
        };
        var result = await rows
            .GroupBy(row => SqliteDbFunctions.Strftime(format, row.Recording.CreatedUtc))
            .Select(group => new
            {
                Key = group.Key,
                Count = group.LongCount(),
                Size = group.Sum(row => row.Recording.SizeBytes),
            })
            .OrderByDescending(bucket => bucket.Key)
            .ToArrayAsync(cancellationToken);
        return result
            .Select(bucket => ParseSqliteTimeBucket(bucket.Key, bucket.Count, bucket.Size))
            .ToArray();
    }

    private static RecordingTimeBucket ParseSqliteTimeBucket(
        string key,
        long count,
        long size)
    {
        var parts = key.Split('-');
        return new RecordingTimeBucket(
            int.Parse(parts[0], NumberStyles.None, CultureInfo.InvariantCulture),
            parts.Length > 1
                ? int.Parse(parts[1], NumberStyles.None, CultureInfo.InvariantCulture)
                : null,
            parts.Length > 2
                ? int.Parse(parts[2], NumberStyles.None, CultureInfo.InvariantCulture)
                : null,
            parts.Length > 3
                ? int.Parse(parts[3], NumberStyles.None, CultureInfo.InvariantCulture)
                : null,
            count,
            size);
    }

    public async Task<IReadOnlyList<Guid>> ListPendingThumbnailIdsAsync(
        int maximumCount,
        DateTimeOffset staleBeforeUtc,
        CancellationToken cancellationToken = default) =>
        await dbContext.Recordings
            .AsNoTracking()
            .Where(recording => recording.ThumbnailState == ThumbnailGenerationState.Pending
                || (recording.ThumbnailState == ThumbnailGenerationState.Processing
                    && recording.ThumbnailProcessingStartedUtc < staleBeforeUtc))
            .OrderBy(recording => recording.UploadedUtc)
            .Select(recording => recording.Id)
            .Take(maximumCount)
            .ToArrayAsync(cancellationToken);

    public void Add(Recording recording) => dbContext.Recordings.Add(recording);

    public void Remove(Recording recording) => dbContext.Recordings.Remove(recording);

    private IQueryable<RecordingQueryRow> BuildRows() =>
        from recording in dbContext.Recordings.AsNoTracking()
        join device in dbContext.Devices.AsNoTracking() on recording.DeviceId equals device.Id
        select new RecordingQueryRow { Recording = recording, DeviceName = device.DisplayName };

    private IQueryable<RecordingQueryRow> ApplyFilters(
        IQueryable<RecordingQueryRow> rows,
        RecordingQuery query) =>
        ApplyFilters(
            rows,
            query.DeviceIds,
            query.Search,
            query.Source,
            query.DateFromUtc,
            query.DateToUtc,
            query.HourFrom,
            query.HourTo,
            query.MinimumDurationMilliseconds,
            query.MaximumDurationMilliseconds,
            query.MinimumSizeBytes,
            query.MaximumSizeBytes,
            query.ThumbnailState);

    private IQueryable<RecordingQueryRow> ApplyFilters(
        IQueryable<RecordingQueryRow> rows,
        RecordingTimeQuery query) =>
        ApplyFilters(
            rows,
            query.DeviceIds,
            query.Search,
            query.Source,
            query.DateFromUtc,
            query.DateToUtc,
            query.HourFrom,
            query.HourTo,
            query.MinimumDurationMilliseconds,
            query.MaximumDurationMilliseconds,
            query.MinimumSizeBytes,
            query.MaximumSizeBytes,
            query.ThumbnailState);

    private IQueryable<RecordingQueryRow> ApplyFilters(
        IQueryable<RecordingQueryRow> rows,
        IReadOnlyCollection<Guid>? deviceIds,
        string? search,
        RecordingSourceFilter source,
        DateTimeOffset? dateFromUtc,
        DateTimeOffset? dateToUtc,
        int? hourFrom,
        int? hourTo,
        long? minimumDurationMilliseconds,
        long? maximumDurationMilliseconds,
        long? minimumSizeBytes,
        long? maximumSizeBytes,
        RecordingThumbnailState? thumbnailState)
    {
        if (deviceIds is { Count: > 0 })
        {
            var mappedDeviceIds = deviceIds.Select(value => new DeviceId(value)).ToArray();
            rows = rows.Where(row => mappedDeviceIds.Contains(row.Recording.DeviceId));
        }
        if (!string.IsNullOrWhiteSpace(search))
        {
            rows = rows.Where(row => row.Recording.OriginalFileName.Contains(search)
                || row.Recording.ClientRecordingId.Contains(search)
                || row.Recording.SessionId.Contains(search)
                || row.DeviceName.Contains(search));
        }
        rows = source switch
        {
            RecordingSourceFilter.Motion => rows.Where(row => row.Recording.IsMotion),
            RecordingSourceFilter.Manual => rows.Where(row => row.Recording.IsManual),
            RecordingSourceFilter.MonitoringSession => rows.Where(row => row.Recording.SessionId != string.Empty),
            _ => rows,
        };
        if (dateFromUtc is not null)
        {
            rows = rows.Where(row => row.Recording.CreatedUtc >= dateFromUtc);
        }
        if (dateToUtc is not null)
        {
            rows = rows.Where(row => row.Recording.CreatedUtc < dateToUtc);
        }
        if (dbContext.Database.IsSqlite() && (hourFrom is not null || hourTo is not null))
        {
            var allowedHours = Enumerable.Range(0, 24)
                .Where(hour => (hourFrom is null || hour >= hourFrom)
                    && (hourTo is null || hour < hourTo))
                .Select(hour => hour.ToString("D2", CultureInfo.InvariantCulture))
                .ToArray();
            rows = rows.Where(row => allowedHours.Contains(
                SqliteDbFunctions.Strftime("%H", row.Recording.CreatedUtc)));
        }
        else if (hourFrom is not null)
        {
            rows = rows.Where(row => row.Recording.CreatedUtc.Hour >= hourFrom);
            if (hourTo is not null)
            {
                rows = rows.Where(row => row.Recording.CreatedUtc.Hour < hourTo);
            }
        }
        else if (hourTo is not null)
        {
            rows = rows.Where(row => row.Recording.CreatedUtc.Hour < hourTo);
        }
        if (minimumDurationMilliseconds is not null)
        {
            rows = rows.Where(row => row.Recording.DurationMilliseconds >= minimumDurationMilliseconds);
        }
        if (maximumDurationMilliseconds is not null)
        {
            rows = rows.Where(row => row.Recording.DurationMilliseconds <= maximumDurationMilliseconds);
        }
        if (minimumSizeBytes is not null)
        {
            rows = rows.Where(row => row.Recording.SizeBytes >= minimumSizeBytes);
        }
        if (maximumSizeBytes is not null)
        {
            rows = rows.Where(row => row.Recording.SizeBytes <= maximumSizeBytes);
        }
        if (thumbnailState is not null)
        {
            var expectedThumbnailState = (ThumbnailGenerationState)(int)thumbnailState.Value;
            rows = rows.Where(row => row.Recording.ThumbnailState == expectedThumbnailState);
        }
        return rows;
    }

    private sealed class RecordingQueryRow
    {
        public required Recording Recording { get; init; }
        public required string DeviceName { get; init; }
    }
}
