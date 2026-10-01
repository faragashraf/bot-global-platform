using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;
using SentriCam.Application.CameraControl;
using SentriCam.Contracts.CameraControl;
using SentriCam.Contracts.SignalR;
using SentriCam.SignalR.Hubs;

namespace SentriCam.SignalR.CameraControl;

public sealed class SignalRCameraControlTransport(
    IHubContext<DeviceHub, IDeviceHubClient> hub,
    ILogger<SignalRCameraControlTransport> logger) : ICameraControlTransport
{
    private static readonly Action<ILogger, Guid, string, Exception?> LogDispatchFailure =
        LoggerMessage.Define<Guid, string>(
            LogLevel.Warning,
            new EventId(1, "CameraControlDispatchFailed"),
            "Camera Control dispatch failed {CommandId} reason={FailureType}");

    public async Task<bool> DispatchAsync(
        string deviceConnectionId,
        CameraControlCommandEnvelope command,
        CancellationToken cancellationToken = default)
    {
        try
        {
            await hub.Clients.Client(deviceConnectionId).ReceiveCameraControlCommand(command);
            return true;
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            LogDispatchFailure(logger, command.CommandId, exception.GetType().Name, exception);
            return false;
        }
    }

    public Task CancelAsync(
        string deviceConnectionId,
        CameraControlCancellation cancellation,
        CancellationToken cancellationToken = default) =>
        hub.Clients.Client(deviceConnectionId).CancelCameraControlCommand(cancellation);
}

public sealed class SignalRCameraControlEventPublisher(
    IHubContext<MonitoringHub, IMonitoringHubClient> hub) : ICameraControlEventPublisher
{
    public Task ChangedAsync(
        CameraControlUpdate update,
        CancellationToken cancellationToken = default) =>
        hub.Clients.All.CameraControlChanged(update);
}
