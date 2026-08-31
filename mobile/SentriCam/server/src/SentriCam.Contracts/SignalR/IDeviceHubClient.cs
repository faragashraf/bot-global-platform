using System.Text.Json;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Monitoring;
using SentriCam.Contracts.LiveView;
using SentriCam.Contracts.CameraControl;

namespace SentriCam.Contracts.SignalR;

public interface IDeviceHubClient
{
    Task ConnectionAccepted(DeviceConnectionAccepted connection);

    Task PairingRevoked(DevicePairingRevoked revocation);

    Task ReceiveCommand(DeviceCommandEnvelope command);

    Task BeginLiveSession(LiveSessionAssignment assignment);

    Task ReceiveLiveOffer(LiveSessionDescription offer);

    Task ReceiveLiveIceCandidate(LiveIceCandidate candidate);

    Task SetLivePreviewVisibility(LivePreviewVisibility visibility);

    Task EndLiveSession(LiveSessionStatusUpdate update);

    Task BrowserLiveStatistics(LiveSessionStatistics statistics);

    Task ReceiveCameraControlCommand(CameraControlCommandEnvelope command);

    Task CancelCameraControlCommand(CameraControlCancellation cancellation);
}

public sealed record DevicePairingRevoked(
    Guid DeviceId,
    DateTimeOffset RevokedAtUtc,
    string Reason);

public interface IDeviceHubServer
{
    Task TransportPulse();

    Task<DeviceHeartbeatAcknowledgement> Heartbeat(DeviceHeartbeat heartbeat);

    Task CommandCompleted(DeviceCommandResultEnvelope result);

    Task ReportLiveCapabilities(LiveDeviceCapabilities capabilities);

    Task SubmitLiveAnswer(LiveSessionDescription answer);

    Task SubmitLiveIceCandidate(LiveIceCandidate candidate);

    Task ReportLiveSessionState(LiveSessionStatusUpdate update);

    Task ReportLiveStatistics(LiveSessionStatistics statistics);

    Task ReportCameraControlState(CameraControlDeviceReport report);

    Task ReportOperationalHealth(DeviceOperationalHealthReport report);

    Task CompleteCameraControlCommand(CameraControlCommandResult result);
}

public interface IMonitoringHubClient
{
    Task DeviceChanged(MonitoringUpdate update);

    Task CommandChanged(CommandUpdate update);

    Task LiveSessionChanged(LiveSessionView session);

    Task ReceiveLiveAnswer(LiveSessionDescription answer);

    Task ReceiveLiveIceCandidate(LiveIceCandidate candidate);

    Task LiveStatisticsUpdated(LiveSessionStatistics statistics);

    Task LiveSessionError(LiveSessionError sessionError);

    Task CameraControlChanged(CameraControlUpdate update);
}

public sealed record DeviceConnectionAccepted(
    Guid DeviceId,
    string ConnectionId,
    DateTimeOffset ConnectedAtUtc,
    DateTimeOffset ServerUtcNow,
    bool IsReconnect,
    int? ServerUtcOffsetMinutes = null,
    string? ServerTimeZoneId = null);

public sealed record DeviceHeartbeat(
    Guid DeviceId,
    string ConnectionId,
    long SnapshotVersion,
    DateTimeOffset Timestamp,
    JsonElement CurrentDeviceSnapshot);

public sealed record DeviceHeartbeatAcknowledgement(
    Guid DeviceId,
    string ConnectionId,
    long SnapshotVersion,
    DateTimeOffset LastHeartbeatAtUtc,
    DateTimeOffset ServerUtcNow,
    int? ServerUtcOffsetMinutes = null,
    string? ServerTimeZoneId = null);
