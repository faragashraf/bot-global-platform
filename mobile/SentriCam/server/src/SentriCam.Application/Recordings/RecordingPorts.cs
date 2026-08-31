using SentriCam.Contracts.Recordings;

namespace SentriCam.Application.Recordings;

public sealed record UploadRecording(
    Guid DeviceId,
    string ClientRecordingId,
    string SessionId,
    string OriginalFileName,
    string ContentType,
    long DurationMilliseconds,
    long DeclaredSizeBytes,
    DateTimeOffset CreatedUtc,
    bool Motion,
    bool Manual,
    string ChecksumSha256,
    Stream Content);

public sealed record RecordingQuery(
    IReadOnlyCollection<Guid>? DeviceIds = null,
    string? Search = null,
    RecordingSourceFilter Source = RecordingSourceFilter.All,
    DateTimeOffset? DateFromUtc = null,
    DateTimeOffset? DateToUtc = null,
    DateOnly? ExactDayUtc = null,
    int? HourFrom = null,
    int? HourTo = null,
    long? MinimumDurationMilliseconds = null,
    long? MaximumDurationMilliseconds = null,
    long? MinimumSizeBytes = null,
    long? MaximumSizeBytes = null,
    RecordingUploadState? UploadState = null,
    RecordingThumbnailState? ThumbnailState = null,
    RecordingSort Sort = RecordingSort.Newest,
    int Page = 1,
    int PageSize = 24);

public sealed record RecordingTimeQuery(
    RecordingTimeLevel Level,
    DateTimeOffset? ParentStartUtc = null,
    DateTimeOffset? ParentEndUtc = null,
    IReadOnlyCollection<Guid>? DeviceIds = null,
    string? Search = null,
    RecordingSourceFilter Source = RecordingSourceFilter.All,
    DateTimeOffset? DateFromUtc = null,
    DateTimeOffset? DateToUtc = null,
    int? HourFrom = null,
    int? HourTo = null,
    long? MinimumDurationMilliseconds = null,
    long? MaximumDurationMilliseconds = null,
    long? MinimumSizeBytes = null,
    long? MaximumSizeBytes = null,
    RecordingThumbnailState? ThumbnailState = null);

public sealed record RecordingProjection(
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
    int ThumbnailState,
    int ThumbnailGenerationAttempts,
    string? ThumbnailErrorCode,
    DateTimeOffset? ThumbnailGeneratedUtc,
    string ChecksumSha256,
    bool HasThumbnail);

public sealed record PagedRecordingProjection(
    IReadOnlyList<RecordingProjection> Items,
    long TotalCount);

public sealed record RecordingTimeBucket(
    int Year,
    int? Month,
    int? Day,
    int? Hour,
    long RecordingCount,
    long TotalSizeBytes);

public sealed record RecordingStorageWriteRequest(
    Guid RecordingId,
    Guid DeviceId,
    string OriginalFileName,
    DateTimeOffset CreatedUtc);

public sealed record StoredRecordingFile(string RelativePath, long SizeBytes, string ChecksumSha256);

public sealed record RecordingThumbnailRequest(
    Guid RecordingId,
    string RecordingRelativePath,
    DateTimeOffset CreatedUtc,
    long DurationMilliseconds,
    bool Motion);

public sealed record GeneratedRecordingThumbnail(byte[] Content, string Extension, string ContentType);

public sealed class RecordingThumbnailGenerationException(string errorCode, string message, Exception? innerException = null)
    : Exception(message, innerException)
{
    public string ErrorCode { get; } = errorCode;
}

public sealed record RecordingContent(
    Stream Content,
    string ContentType,
    string FileName,
    long SizeBytes,
    DateTimeOffset? LastModifiedUtc = null,
    string? EntityTag = null);

public interface IRecordingThumbnailScheduler
{
    bool Enqueue(Guid recordingId);
    IAsyncEnumerable<Guid> ReadAllAsync(CancellationToken cancellationToken = default);
    void Complete(Guid recordingId);
}

public interface IRecordingThumbnailProcessor
{
    Task ProcessAsync(Guid recordingId, CancellationToken cancellationToken = default);
}

public interface IRecordingStorageProvider
{
    Task<StoredRecordingFile> SaveRecordingAsync(
        RecordingStorageWriteRequest request,
        Stream content,
        CancellationToken cancellationToken = default);
    Task<string> SaveThumbnailAsync(
        RecordingStorageWriteRequest request,
        GeneratedRecordingThumbnail thumbnail,
        CancellationToken cancellationToken = default);
    Task<Stream> OpenReadAsync(string relativePath, CancellationToken cancellationToken = default);
    Task DeleteIfExistsAsync(string relativePath, CancellationToken cancellationToken = default);
}

public interface IRecordingThumbnailGenerator
{
    Task<GeneratedRecordingThumbnail> GenerateAsync(
        RecordingThumbnailRequest request,
        CancellationToken cancellationToken = default);
}

public interface IRecordingService
{
    Task<RecordingUploadResult> UploadAsync(
        UploadRecording upload,
        CancellationToken cancellationToken = default);
    Task<RecordingView?> FindUploadAsync(
        Guid deviceId,
        string clientRecordingId,
        CancellationToken cancellationToken = default);
    Task<PagedRecordingResult> ListAsync(
        RecordingQuery query,
        CancellationToken cancellationToken = default);
    Task<IReadOnlyList<RecordingTimeNode>> AggregateTimeAsync(
        RecordingTimeQuery query,
        CancellationToken cancellationToken = default);
    Task RequestThumbnailRegenerationAsync(
        Guid recordingId,
        CancellationToken cancellationToken = default);
    Task<RecordingContent> OpenContentAsync(Guid recordingId, CancellationToken cancellationToken = default);
    Task<RecordingContent> OpenThumbnailAsync(Guid recordingId, CancellationToken cancellationToken = default);
    Task DeleteAsync(Guid recordingId, CancellationToken cancellationToken = default);
}
