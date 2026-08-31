using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;
using SentriCam.Application.Connections;
using SentriCam.BuildingBlocks.Authentication;
using SentriCam.Contracts.SignalR;
using SentriCam.Contracts.Commands;
using SentriCam.Application.Monitoring;
using SentriCam.Application.LiveView;
using SentriCam.Contracts.LiveView;
using SentriCam.Application.CameraControl;
using SentriCam.Contracts.CameraControl;
using SentriCam.Contracts.Monitoring;
using SentriCam.Domain.Devices;
using SentriCam.SignalR.Devices;

namespace SentriCam.SignalR.Hubs;

[Authorize(Policy = AuthorizationPolicies.Device)]
public sealed class DeviceHub(
    IDeviceConnectionLifecycleService lifecycle,
    IRemoteMonitoringService remoteMonitoring,
    ILiveSessionEngine liveSessions,
    ICameraControlEngine cameraControl,
    IDeviceOperationalHealthRegistry operationalHealth,
    IMonitoringEventPublisher monitoringEvents,
    SignalRDeviceSessionRegistry sessions,
    TimeProvider timeProvider,
    ILogger<DeviceHub> logger) : Hub<IDeviceHubClient>, IDeviceHubServer
{
    private static readonly Action<ILogger, Guid, string, bool, Exception?> LogConnected =
        LoggerMessage.Define<Guid, string, bool>(
            LogLevel.Information,
            new EventId(1, "DeviceConnected"),
            "Device connection established {DeviceId} {ConnectionId} reconnect={IsReconnect}");

    private static readonly Action<ILogger, string?, string, string, Exception?> LogDisconnected =
        LoggerMessage.Define<string?, string, string>(
            LogLevel.Information,
            new EventId(2, "DeviceDisconnected"),
            "Device disconnected {DeviceId} {ConnectionId} reason={Reason}");

    private static readonly Action<ILogger, Guid, string, long, Exception?> LogHeartbeat =
        LoggerMessage.Define<Guid, string, long>(
            LogLevel.Debug,
            new EventId(3, "DeviceHeartbeat"),
            "Device heartbeat {DeviceId} {ConnectionId} snapshot={SnapshotVersion}");

    private static readonly Action<ILogger, Guid, string, string, Exception?> LogHeartbeatRejected =
        LoggerMessage.Define<Guid, string, string>(
            LogLevel.Warning,
            new EventId(4, "DeviceHeartbeatRejected"),
            "Device heartbeat rejected {DeviceId} {ConnectionId} reason={Reason}");
    private static readonly Action<ILogger, Guid, DateTimeOffset, DateTimeOffset, Exception?> LogFreshHealthReceived =
        LoggerMessage.Define<Guid, DateTimeOffset, DateTimeOffset>(
            LogLevel.Debug,
            new EventId(5, "FreshHealthReceived"),
            "event=fresh_health_received deviceId={DeviceId} atUtc={AtUtc} clientReportedAtUtc={ClientReportedAtUtc}");
    private static readonly Action<ILogger, Guid, DateTimeOffset, Exception?> LogHealthEventPublished =
        LoggerMessage.Define<Guid, DateTimeOffset>(
            LogLevel.Debug,
            new EventId(6, "HealthEventPublished"),
            "event=health_event_published deviceId={DeviceId} atUtc={AtUtc}");

    public override async Task OnConnectedAsync()
    {
        var deviceId = RequireDeviceId();
        sessions.Track(deviceId, Context.ConnectionId, Context.Abort);
        DeviceConnectionLifecycleResult result;
        try
        {
            result = await lifecycle.ConnectedAsync(
                deviceId,
                Context.ConnectionId,
                Context.ConnectionAborted);
        }
        catch
        {
            sessions.Untrack(Context.ConnectionId);
            throw;
        }
        await Clients.Caller.ConnectionAccepted(new DeviceConnectionAccepted(
            result.DeviceId.Value,
            result.ConnectionId,
            result.ConnectedAtUtc,
            result.ConnectedAtUtc,
            result.IsReconnect,
            (int)TimeZoneInfo.Local.GetUtcOffset(result.ConnectedAtUtc.UtcDateTime).TotalMinutes,
            TimeZoneInfo.Local.Id));
        await liveSessions.RecoverForDeviceConnectionAsync(
            result.DeviceId.Value,
            result.ConnectionId,
            Context.ConnectionAborted);
        if (!string.IsNullOrWhiteSpace(result.ReplacedConnectionId))
        {
            sessions.Abort(result.ReplacedConnectionId);
        }
        LogConnected(
            logger,
            result.DeviceId.Value,
            result.ConnectionId,
            result.IsReconnect,
            null);
        await base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        sessions.Untrack(Context.ConnectionId);
        if (Guid.TryParse(Context.UserIdentifier, out var liveDeviceId))
        {
            await liveSessions.CloseForDeviceConnectionAsync(
                liveDeviceId,
                Context.ConnectionId,
                CancellationToken.None);
        }
        await lifecycle.DisconnectedAsync(Context.ConnectionId, CancellationToken.None);
        LogDisconnected(
            logger,
            Context.UserIdentifier,
            Context.ConnectionId,
            exception?.GetType().Name ?? "client_closed",
            null);
        await base.OnDisconnectedAsync(exception);
    }

    [HubMethodName("Heartbeat")]
    public async Task<DeviceHeartbeatAcknowledgement> Heartbeat(DeviceHeartbeat heartbeat)
    {
        try
        {
            var acknowledgement = await lifecycle.HeartbeatAsync(
                heartbeat,
                Context.ConnectionId,
                Context.ConnectionAborted);
            LogHeartbeat(
                logger,
                acknowledgement.DeviceId,
                acknowledgement.ConnectionId,
                acknowledgement.SnapshotVersion,
                null);
            return acknowledgement;
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            LogHeartbeatRejected(
                logger,
                heartbeat.DeviceId,
                Context.ConnectionId,
                exception.GetType().Name,
                null);
            throw;
        }
    }

    [HubMethodName("TransportPulse")]
    public Task TransportPulse() =>
        lifecycle.TransportPulseAsync(
            RequireDeviceId(),
            Context.ConnectionId,
            Context.ConnectionAborted);

    [HubMethodName("CommandCompleted")]
    public Task CommandCompleted(DeviceCommandResultEnvelope result) =>
        remoteMonitoring.CompleteAsync(
            RequireDeviceId(),
            result,
            Context.ConnectionAborted);

    public Task ReportLiveCapabilities(LiveDeviceCapabilities capabilities) =>
        Execute(() =>
        {
            liveSessions.ReportCapabilities(RequireDeviceId(), Context.ConnectionId, capabilities);
            return Task.CompletedTask;
        });

    public Task SubmitLiveAnswer(LiveSessionDescription answer) =>
        Execute(() => liveSessions.SubmitAnswerAsync(
            RequireDeviceId(),
            Context.ConnectionId,
            answer,
            Context.ConnectionAborted));

    public Task SubmitLiveIceCandidate(LiveIceCandidate candidate) =>
        Execute(() => liveSessions.SubmitDeviceCandidateAsync(
            RequireDeviceId(),
            Context.ConnectionId,
            candidate,
            Context.ConnectionAborted));

    public Task ReportLiveSessionState(LiveSessionStatusUpdate update) =>
        Execute(() => liveSessions.ReportDeviceStateAsync(
            RequireDeviceId(),
            Context.ConnectionId,
            update,
            Context.ConnectionAborted));

    public Task ReportLiveStatistics(LiveSessionStatistics statistics) =>
        Execute(() => liveSessions.ReportStatisticsAsync(
            RequireDeviceId(),
            Context.ConnectionId,
            statistics,
            Context.ConnectionAborted));

    public Task ReportCameraControlState(CameraControlDeviceReport report) =>
        Execute(() => cameraControl.ReportAsync(
            RequireDeviceId(),
            Context.ConnectionId,
            report,
            Context.ConnectionAborted));

    public Task CompleteCameraControlCommand(CameraControlCommandResult result) =>
        Execute(() => cameraControl.CompleteAsync(
            RequireDeviceId(),
            Context.ConnectionId,
            result,
            Context.ConnectionAborted));

    public async Task ReportOperationalHealth(DeviceOperationalHealthReport report)
    {
        var deviceId = DeviceId.From(RequireDeviceId());
        var receivedAtUtc = timeProvider.GetUtcNow();
        operationalHealth.Report(deviceId, report with { ReportedAtUtc = receivedAtUtc });
        LogFreshHealthReceived(
            logger,
            deviceId.Value,
            receivedAtUtc,
            report.ReportedAtUtc,
            null);
        await monitoringEvents.DeviceChangedAsync(
            new MonitoringUpdate(deviceId.Value, receivedAtUtc),
            Context.ConnectionAborted);
        LogHealthEventPublished(
            logger,
            deviceId.Value,
            receivedAtUtc,
            null);
    }

    private Guid RequireDeviceId()
    {
        if (!Guid.TryParse(Context.UserIdentifier, out var deviceId))
        {
            throw new HubException("The authenticated device identifier is unavailable.");
        }

        return deviceId;
    }

    private static async Task Execute(Func<Task> action)
    {
        try
        {
            await action();
        }
        catch (LiveSessionException exception)
        {
            throw new HubException($"{exception.Code}:{exception.Message}");
        }
    }
}
