using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using SentriCam.Application.CameraControl;

namespace SentriCam.Api.CameraControl;

public sealed class CameraControlDispatchWorker(
    ICameraControlDispatcher dispatcher,
    ILogger<CameraControlDispatchWorker> logger) : BackgroundService
{
    private static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(1);
    private static readonly Action<ILogger, Exception?> LogStarted = LoggerMessage.Define(
        LogLevel.Information,
        new EventId(1, "CameraControlDispatcherStarted"),
        "Camera Control dispatcher started");
    private static readonly Action<ILogger, string, Exception?> LogPollFailure =
        LoggerMessage.Define<string>(
            LogLevel.Warning,
            new EventId(2, "CameraControlDispatcherPollFailed"),
            "Camera Control dispatcher poll failed reason={FailureType}");

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        LogStarted(logger, null);
        using var timer = new PeriodicTimer(PollInterval);
        do
        {
            try
            {
                await dispatcher.DispatchPendingAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception exception)
            {
                LogPollFailure(logger, exception.GetType().Name, exception);
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
