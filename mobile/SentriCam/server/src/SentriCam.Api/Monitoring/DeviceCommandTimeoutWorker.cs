using SentriCam.Application.Monitoring;
using SentriCam.Application.Hub;

namespace SentriCam.Api.Monitoring;

public sealed class DeviceCommandTimeoutWorker(
    IServiceScopeFactory scopeFactory,
    IHubSetupStore hubSetupStore,
    ILogger<DeviceCommandTimeoutWorker> logger) : BackgroundService
{
    private static readonly Action<ILogger, string, Exception?> LogSweepFailed =
        LoggerMessage.Define<string>(
            LogLevel.Error,
            new EventId(1, "CommandTimeoutSweepFailed"),
            "Remote command timeout sweep failed with {FailureType}");

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(2));
        try
        {
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                if (!hubSetupStore.Current.IsConfigured)
                {
                    continue;
                }

                try
                {
                    await using var scope = scopeFactory.CreateAsyncScope();
                    var monitoring = scope.ServiceProvider.GetRequiredService<IRemoteMonitoringService>();
                    await monitoring.ExpireCommandsAsync(stoppingToken);
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
