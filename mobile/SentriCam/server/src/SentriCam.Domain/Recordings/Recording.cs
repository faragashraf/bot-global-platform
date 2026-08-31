using System.Text.RegularExpressions;
using SentriCam.Domain.Common;
using SentriCam.Domain.Devices;

namespace SentriCam.Domain.Recordings;

public enum ThumbnailGenerationState
{
    Pending = 0,
    Processing = 1,
    Ready = 2,
    Failed = 3,
}

public sealed partial class Recording : AggregateRoot<Guid>
{
    private Recording()
    {
    }

    private Recording(
        Guid id,
        DeviceId deviceId,
        string clientRecordingId,
        string sessionId,
        string originalFileName,
        string contentType,
        long durationMilliseconds,
        long sizeBytes,
        DateTimeOffset createdUtc,
        DateTimeOffset uploadedUtc,
        bool isMotion,
        bool isManual,
        string checksumSha256,
        string relativePath)
    {
        Id = id;
        DeviceId = deviceId;
        ClientRecordingId = DomainGuard.Required(clientRecordingId, 128, nameof(ClientRecordingId));
        SessionId = DomainGuard.Required(sessionId, 128, nameof(SessionId));
        OriginalFileName = DomainGuard.Required(Path.GetFileName(originalFileName), 255, nameof(OriginalFileName));
        ContentType = DomainGuard.Required(contentType, 100, nameof(ContentType));
        if (durationMilliseconds < 0)
        {
            throw new DomainValidationException("Recording duration cannot be negative.");
        }
        if (sizeBytes <= 0)
        {
            throw new DomainValidationException("Recording size must be positive.");
        }
        if (createdUtc > uploadedUtc.AddMinutes(5))
        {
            throw new DomainValidationException("Recording creation time cannot be after its upload time.");
        }
        if (isMotion == isManual)
        {
            throw new DomainValidationException("A recording must have exactly one V1 trigger classification.");
        }
        var checksum = DomainGuard.Required(checksumSha256, 64, nameof(ChecksumSha256)).ToLowerInvariant();
        if (!Sha256Pattern().IsMatch(checksum))
        {
            throw new DomainValidationException("Recording checksum must be a SHA-256 hexadecimal value.");
        }

        DurationMilliseconds = durationMilliseconds;
        SizeBytes = sizeBytes;
        CreatedUtc = createdUtc;
        UploadedUtc = uploadedUtc;
        IsMotion = isMotion;
        IsManual = isManual;
        ChecksumSha256 = checksum;
        RelativePath = DomainGuard.Required(relativePath, 1024, nameof(RelativePath));
    }

    public DeviceId DeviceId { get; private set; }
    public string ClientRecordingId { get; private set; } = string.Empty;
    public string SessionId { get; private set; } = string.Empty;
    public string OriginalFileName { get; private set; } = string.Empty;
    public string ContentType { get; private set; } = string.Empty;
    public long DurationMilliseconds { get; private set; }
    public long SizeBytes { get; private set; }
    public DateTimeOffset CreatedUtc { get; private set; }
    public DateTimeOffset UploadedUtc { get; private set; }
    public bool IsMotion { get; private set; }
    public bool IsManual { get; private set; }
    public string ChecksumSha256 { get; private set; } = string.Empty;
    public string RelativePath { get; private set; } = string.Empty;
    public string? ThumbnailRelativePath { get; private set; }
    public ThumbnailGenerationState ThumbnailState { get; private set; } = ThumbnailGenerationState.Pending;
    public int ThumbnailGenerationAttempts { get; private set; }
    public string? ThumbnailErrorCode { get; private set; }
    public DateTimeOffset? ThumbnailGeneratedUtc { get; private set; }
    public DateTimeOffset? ThumbnailProcessingStartedUtc { get; private set; }

    public static Recording Create(
        Guid id,
        DeviceId deviceId,
        string clientRecordingId,
        string sessionId,
        string originalFileName,
        string contentType,
        long durationMilliseconds,
        long sizeBytes,
        DateTimeOffset createdUtc,
        DateTimeOffset uploadedUtc,
        bool isMotion,
        bool isManual,
        string checksumSha256,
        string relativePath)
    {
        if (id == Guid.Empty)
        {
            throw new DomainValidationException("Recording id is required.");
        }

        return new Recording(
            id,
            deviceId,
            clientRecordingId,
            sessionId,
            originalFileName,
            contentType,
            durationMilliseconds,
            sizeBytes,
            createdUtc,
            uploadedUtc,
            isMotion,
            isManual,
            checksumSha256,
            relativePath);
    }

    public bool TryBeginThumbnailGeneration(DateTimeOffset startedUtc)
    {
        if (ThumbnailState is ThumbnailGenerationState.Processing or ThumbnailGenerationState.Ready)
        {
            return false;
        }

        ThumbnailState = ThumbnailGenerationState.Processing;
        ThumbnailProcessingStartedUtc = startedUtc;
        ThumbnailErrorCode = null;
        ThumbnailGenerationAttempts++;
        return true;
    }

    public void CompleteThumbnailGeneration(string relativePath, DateTimeOffset generatedUtc)
    {
        ThumbnailRelativePath = DomainGuard.Required(relativePath, 1024, nameof(ThumbnailRelativePath));
        ThumbnailState = ThumbnailGenerationState.Ready;
        ThumbnailGeneratedUtc = generatedUtc;
        ThumbnailProcessingStartedUtc = null;
        ThumbnailErrorCode = null;
    }

    public void FailThumbnailGeneration(string errorCode)
    {
        ThumbnailState = ThumbnailGenerationState.Failed;
        ThumbnailProcessingStartedUtc = null;
        ThumbnailErrorCode = DomainGuard.Required(errorCode, 100, nameof(ThumbnailErrorCode));
    }

    public void RequestThumbnailRegeneration()
    {
        if (ThumbnailState == ThumbnailGenerationState.Processing)
        {
            return;
        }

        ThumbnailState = ThumbnailGenerationState.Pending;
        ThumbnailErrorCode = null;
        ThumbnailProcessingStartedUtc = null;
    }

    public void RecoverStaleThumbnailGeneration()
    {
        if (ThumbnailState == ThumbnailGenerationState.Processing)
        {
            ThumbnailState = ThumbnailGenerationState.Pending;
            ThumbnailProcessingStartedUtc = null;
        }
    }

    [Obsolete("Use CompleteThumbnailGeneration so lifecycle metadata remains consistent.")]
    public void AttachThumbnail(string relativePath)
    {
        ThumbnailRelativePath = DomainGuard.Required(relativePath, 1024, nameof(ThumbnailRelativePath));
        ThumbnailState = ThumbnailGenerationState.Ready;
    }

    [GeneratedRegex("^[0-9a-f]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex Sha256Pattern();
}
