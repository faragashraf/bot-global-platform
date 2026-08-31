using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;
using SentriCam.Application.Connections;
using SentriCam.Application.Monitoring;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Monitoring;
using SentriCam.Contracts.SignalR;
using SentriCam.Domain.Devices;
using SentriCam.SignalR.Hubs;

namespace SentriCam.SignalR.Monitoring;

public sealed class SignalRDeviceCommandTransport(
    IDeviceConnectionManager connectionManager,
    IHubContext<DeviceHub, IDeviceHubClient> hubContext,
    ILogger<SignalRDeviceCommandTransport> logger) : IDeviceCommandTransport
{
    private static readonly Action<ILogger, Guid, Guid, string, Exception?> LogDispatchFailed =
        LoggerMessage.Define<Guid, Guid, string>(
            LogLevel.Warning,
            new EventId(1, "CommandDispatchFailed"),
            "Device command dispatch failed {CommandId} {DeviceId} reason={Reason}");

    public async Task<bool> DispatchAsync(
        DeviceCommandEnvelope command,
        CancellationToken cancellationToken = default)
    {
        var connection = connectionManager.GetStatus(DeviceId.From(command.DeviceId));
        if (connection is null || connection.State != DeviceTransportState.Connected)
        {
            return false;
        }

        try
        {
            await hubContext.Clients
                .Client(connection.ConnectionId)
                .ReceiveCommand(command);
            return true;
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            LogDispatchFailed(
                logger,
                command.CommandId,
                command.DeviceId,
                exception.GetType().Name,
                null);
            return false;
        }
    }
}

public sealed class SignalRMonitoringEventPublisher(
    IHubContext<MonitoringHub, IMonitoringHubClient> hubContext,
    ILogger<SignalRMonitoringEventPublisher> logger) : IMonitoringEventPublisher
{
    private static readonly Action<ILogger, Guid, DateTimeOffset, Exception?> LogDeviceChangedPublished =
        LoggerMessage.Define<Guid, DateTimeOffset>(
            LogLevel.Debug,
            new EventId(2, "DeviceChangedPublished"),
            "event=device_changed_published deviceId={DeviceId} atUtc={AtUtc}");

    public async Task DeviceChangedAsync(
        MonitoringUpdate update,
        CancellationToken cancellationToken = default)
    {
        await hubContext.Clients.All.DeviceChanged(update);
        LogDeviceChangedPublished(
            logger,
            update.DeviceId,
            update.ServerUtcNow,
            null);
    }

    public Task CommandChangedAsync(
        CommandUpdate update,
        CancellationToken cancellationToken = default) =>
        hubContext.Clients.All.CommandChanged(update);
}
