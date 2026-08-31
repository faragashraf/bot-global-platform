using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Hub;
using SentriCam.Application.Connections;
using SentriCam.Application.Monitoring;
using SentriCam.Contracts.Monitoring;
using SentriCam.Infrastructure.Hub;

namespace SentriCam.Api.Monitoring;

public sealed class DevicePresenceSweepWorker(
    IServiceScopeFactory scopeFactory,
    IHubSetupStore hubSetupStore,
    TimeProvider timeProvider,
    ILogger<DevicePresenceSweepWorker> logger) : BackgroundService
{
    private static readonly Action<ILogger, Guid, DateTimeOffset, string, Exception?> LogPresenceTransition =
        LoggerMessage.Define<Guid, DateTimeOffset, string>(
            LogLevel.Debug,
            new EventId(1, "DevicePresenceTransition"),
            "event=presence_transition source=heartbeat_sweep deviceId={DeviceId} atUtc={AtUtc} state={State}");

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(DevicePresencePolicy.SweepInterval);
        try
        {
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                if (!hubSetupStore.Current.IsConfigured)
                {
                    continue;
                }

                await SweepOnceAsync(stoppingToken);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // Expected during host shutdown.
        }
    }

    private async Task SweepOnceAsync(CancellationToken stoppingToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var deviceRepository = scope.ServiceProvider.GetRequiredService<IDeviceRepository>();
        var connectionManager = scope.ServiceProvider.GetRequiredService<IDeviceConnectionManager>();
        var transitions = scope.ServiceProvider.GetRequiredService<IDevicePresenceTransitionTracker>();
        var publisher = scope.ServiceProvider.GetRequiredService<IMonitoringEventPublisher>();

        var devices = await deviceRepository.ListAsync(stoppingToken);
        var now = timeProvider.GetUtcNow();
        foreach (var device in devices.Where(device => device.IsEnabled))
        {
            var status = connectionManager.GetStatus(device.Id);
            var deviceId = device.Id.Value;
            var state = status?.State ?? DeviceTransportState.Disconnected;
            if (!transitions.TryTransition(device.Id, state))
            {
                continue;
            }

            await publisher.DeviceChangedAsync(new MonitoringUpdate(deviceId, now), stoppingToken);
            LogPresenceTransition(logger, deviceId, now, state.ToString().ToLowerInvariant(), null);
        }
    }
}
