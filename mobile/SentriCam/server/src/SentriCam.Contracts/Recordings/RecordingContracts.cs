using System.Text.Json.Serialization;

namespace SentriCam.Contracts.Recordings;

[JsonConverter(typeof(JsonStringEnumConverter))]
public enum RecordingThumbnailState
{
    Pending = 0,
    Processing = 1,
    Ready = 2,
    Failed = 3,
}

[JsonConverter(typeof(JsonStringEnumConverter))]
public enum RecordingUploadState
{
    Completed = 0,
}

[JsonConverter(typeof(JsonStringEnumConverter))]
public enum RecordingSort
{
    Newest = 0,
    Oldest = 1,
    Longest = 2,
    Shortest = 3,
    Largest = 4,
    Smallest = 5,
    DeviceName = 6,
    UploadTime = 7,
    RecordingStartTime = 8,
}

[JsonConverter(typeof(JsonStringEnumConverter))]
public enum RecordingSourceFilter
{
    All = 0,
    Motion = 1,
    Manual = 2,
    MonitoringSession = 3,
}

[JsonConverter(typeof(JsonStringEnumConverter))]
public enum RecordingTimeLevel
{
    Year = 0,
    Month = 1,
    Week = 2,
    Day = 3,
    Hour = 4,
}

public sealed record RecordingView(
    Guid RecordingId,
    Guid DeviceId,
    string DeviceName,
    string ClientRecordingId,
    string SessionId,
    string FileName,
    string ContentType,
    long DurationMilliseconds,
    long SizeBytes,
    DateTimeOffset CreatedUtc,
    DateTimeOffset UploadedUtc,
    bool Motion,
    bool Manual,
    bool MonitoringSession,
    RecordingUploadState UploadState,
    RecordingThumbnailState ThumbnailState,
    int ThumbnailGenerationAttempts,
    string? ThumbnailErrorCode,
    DateTimeOffset? ThumbnailGeneratedUtc,
    string ChecksumSha256,
    string ContentUrl,
    string DownloadUrl,
    string? ThumbnailUrl);

public sealed record RecordingUploadResult(RecordingView Recording, bool Duplicate);

public sealed record PagedRecordingResult(
    IReadOnlyList<RecordingView> Items,
    int Page,
    int PageSize,
    long TotalCount,
    int TotalPages,
    bool HasPreviousPage,
    bool HasNextPage);

public sealed record RecordingTimeNode(
    string Key,
    string Label,
    DateTimeOffset StartUtc,
    DateTimeOffset EndUtc,
    long RecordingCount,
    long TotalSizeBytes,
    bool HasChildren,
    RecordingTimeLevel? ChildrenLevel);

[JsonConverter(typeof(JsonStringEnumConverter))]
public enum RecordingTriggerFilter
{
    All = 0,
    Motion = 1,
    Manual = 2,
}
