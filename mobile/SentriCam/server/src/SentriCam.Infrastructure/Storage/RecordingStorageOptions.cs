namespace SentriCam.Infrastructure.Storage;

public sealed class RecordingStorageOptions
{
    public const string SectionName = "RecordingStorage";
    public const long DefaultMaximumUploadBytes = 4L * 1024L * 1024L * 1024L;
    public const long MultipartEnvelopeAllowanceBytes = 1024L * 1024L;

    public string RootPath { get; init; } = "data/recordings";
    public long MaximumUploadBytes { get; init; } = DefaultMaximumUploadBytes;
    public string FfmpegPath { get; init; } = string.Empty;
    public int ThumbnailWidth { get; init; } = 640;
    public int ThumbnailHeight { get; init; } = 360;
    public int ThumbnailJpegQuality { get; init; } = 3;
    public int ThumbnailTimeoutSeconds { get; init; } = 30;
}
