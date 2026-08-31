using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Domain.Recordings;

namespace SentriCam.Application.Recordings;

public sealed class RecordingThumbnailProcessor(
    IRecordingRepository recordingRepository,
    IRecordingStorageProvider storageProvider,
    IRecordingThumbnailGenerator thumbnailGenerator,
    IUnitOfWork unitOfWork,
    TimeProvider timeProvider) : IRecordingThumbnailProcessor
{
    public async Task ProcessAsync(Guid recordingId, CancellationToken cancellationToken = default)
    {
        var recording = await recordingRepository.GetByIdAsync(recordingId, cancellationToken);
        if (recording is null)
        {
            return;
        }
        if (recording.ThumbnailState == ThumbnailGenerationState.Processing
            && recording.ThumbnailProcessingStartedUtc < timeProvider.GetUtcNow().AddMinutes(-10))
        {
            recording.RecoverStaleThumbnailGeneration();
        }
        if (!recording.TryBeginThumbnailGeneration(timeProvider.GetUtcNow()))
        {
            return;
        }
        await unitOfWork.SaveChangesAsync(cancellationToken);

        try
        {
            var generated = await thumbnailGenerator.GenerateAsync(
                new RecordingThumbnailRequest(
                    recording.Id,
                    recording.RelativePath,
                    recording.CreatedUtc,
                    recording.DurationMilliseconds,
                    recording.IsMotion),
                cancellationToken);
            ValidateGeneratedThumbnail(generated);
            var relativePath = await storageProvider.SaveThumbnailAsync(
                new RecordingStorageWriteRequest(
                    recording.Id,
                    recording.DeviceId.Value,
                    recording.OriginalFileName,
                    recording.CreatedUtc),
                generated,
                cancellationToken);
            recording.CompleteThumbnailGeneration(relativePath, timeProvider.GetUtcNow());
            await unitOfWork.SaveChangesAsync(cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            recording.RecoverStaleThumbnailGeneration();
            await unitOfWork.SaveChangesAsync(CancellationToken.None);
            throw;
        }
        catch (Exception exception)
        {
            var errorCode = exception is RecordingThumbnailGenerationException generationException
                ? generationException.ErrorCode
                : "thumbnail_generation_failed";
            recording.FailThumbnailGeneration(errorCode);
            await unitOfWork.SaveChangesAsync(CancellationToken.None);
        }
    }

    private static void ValidateGeneratedThumbnail(GeneratedRecordingThumbnail thumbnail)
    {
        if (!string.Equals(thumbnail.Extension, ".jpg", StringComparison.OrdinalIgnoreCase)
            || !string.Equals(thumbnail.ContentType, "image/jpeg", StringComparison.OrdinalIgnoreCase)
            || thumbnail.Content.Length < 4
            || thumbnail.Content[0] != 0xff
            || thumbnail.Content[1] != 0xd8
            || thumbnail.Content[^2] != 0xff
            || thumbnail.Content[^1] != 0xd9)
        {
            throw new RecordingThumbnailGenerationException(
                "invalid_thumbnail_output",
                "The media processor returned an invalid JPEG thumbnail.");
        }
    }
}
