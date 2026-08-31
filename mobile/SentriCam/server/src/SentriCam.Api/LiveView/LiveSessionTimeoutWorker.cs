using SentriCam.Application.LiveView;
using SentriCam.Application.Hub;

namespace SentriCam.Api.LiveView;

public sealed class LiveSessionTimeoutWorker(
    IServiceScopeFactory scopeFactory,
    IHubSetupStore hubSetupStore,
    ILogger<LiveSessionTimeoutWorker> logger) : BackgroundService
{
    private static readonly Action<ILogger, string, Exception?> LogSweepFailed =
        LoggerMessage.Define<string>(
            LogLevel.Warning,
            new EventId(1, "LiveSessionTimeoutSweepFailed"),
            "Live View timeout sweep failed with {FailureType}");

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5));
        try
        {
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                if (!hubSetupStore.Current.IsConfigured) continue;
                try
                {
                    await using var scope = scopeFactory.CreateAsyncScope();
                    await scope.ServiceProvider.GetRequiredService<ILiveSessionEngine>()
                        .ExpireAsync(stoppingToken);
                }
                catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
                {
                    return;
                }
                catch (Exception exception)
                {
                    LogSweepFailed(logger, exception.GetType().Name, exception);
                }
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // Graceful host shutdown while waiting for the next sweep.
        }
    }
}
