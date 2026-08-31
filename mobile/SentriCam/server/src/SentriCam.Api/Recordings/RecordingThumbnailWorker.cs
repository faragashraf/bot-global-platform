using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Hub;
using SentriCam.Application.Recordings;

namespace SentriCam.Api.Recordings;

public sealed class RecordingThumbnailWorker(
    IRecordingThumbnailScheduler queue,
    IServiceScopeFactory scopeFactory,
    IHubSetupStore hubSetupStore,
    TimeProvider timeProvider,
    ILogger<RecordingThumbnailWorker> logger) : BackgroundService
{
    private static readonly Action<ILogger, Guid, Exception?> LogUnexpectedFailure =
        LoggerMessage.Define<Guid>(
            LogLevel.Error,
            new EventId(1, "RecordingThumbnailWorkerFailed"),
            "Unexpected thumbnail worker failure for recording {RecordingId}.");

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            while (!hubSetupStore.Current.IsConfigured)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(500), stoppingToken);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            return;
        }

        await SeedPendingAsync(stoppingToken);
        var processedSinceScan = 0;
        await foreach (var recordingId in queue.ReadAllAsync(stoppingToken))
        {
            try
            {
                await using var scope = scopeFactory.CreateAsyncScope();
                var processor = scope.ServiceProvider.GetRequiredService<IRecordingThumbnailProcessor>();
                await processor.ProcessAsync(recordingId, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                LogUnexpectedFailure(logger, recordingId, exception);
            }
            finally
            {
                queue.Complete(recordingId);
            }

            processedSinceScan++;
            if (processedSinceScan >= 25)
            {
                processedSinceScan = 0;
                await SeedPendingAsync(stoppingToken);
            }
        }
    }

    private async Task SeedPendingAsync(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var repository = scope.ServiceProvider.GetRequiredService<IRecordingRepository>();
        var ids = await repository.ListPendingThumbnailIdsAsync(
            100,
            timeProvider.GetUtcNow().AddMinutes(-10),
            cancellationToken);
        foreach (var id in ids)
        {
            queue.Enqueue(id);
        }
    }
}
