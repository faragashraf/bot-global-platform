using System.Text.Json;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.CameraControl;
using SentriCam.Application.Common;
using SentriCam.Application.Connections;
using SentriCam.Application.LiveView;
using SentriCam.Application.Monitoring;
using SentriCam.Contracts.CameraControl;
using SentriCam.Contracts.Monitoring;
using SentriCam.Contracts.SignalR;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Common;

namespace SentriCam.Application.Devices;

public sealed record DeviceRemovalActor(string Subject);

public interface IDeviceRealtimeControl
{
    Task<bool> RevokeAndDisconnectAsync(
        string connectionId,
        DevicePairingRevoked revocation,
        CancellationToken cancellationToken = default);
}

public sealed class NullDeviceRealtimeControl : IDeviceRealtimeControl
{
    public Task<bool> RevokeAndDisconnectAsync(
        string connectionId,
        DevicePairingRevoked revocation,
        CancellationToken cancellationToken = default) => Task.FromResult(false);
}

public interface IDeviceRemovalService
{
    Task<DeviceRemovalImpact> GetImpactAsync(Guid deviceId, CancellationToken cancellationToken = default);

    Task<DeviceRemovalResult> RemoveAsync(
        Guid deviceId,
        RemoveDeviceRequest request,
        DeviceRemovalActor actor,
        CancellationToken cancellationToken = default);
}

public sealed class DeviceRemovalService(
    IDeviceRepository devices,
    IDeviceCommandRepository deviceCommands,
    IDeviceConnectionRepository connectionRepository,
    IDeviceEventRepository deviceEvents,
    ICameraControlEngine cameraControl,
    ILiveSessionEngine liveSessions,
    IDeviceConnectionManager connections,
    IDeviceOperationalHealthRegistry operationalHealth,
    IDeviceRealtimeControl realtime,
    IMonitoringEventPublisher events,
    IUnitOfWork unitOfWork,
    TimeProvider timeProvider) : IDeviceRemovalService
{
    private static readonly DeviceRemovalRetention Retention = new(
        UploadedRecordingsPreserved: true,
        RecordingMetadataPreserved: true,
        AuditHistoryPreserved: true,
        HistoricalEventsPreserved: true);

    public async Task<DeviceRemovalImpact> GetImpactAsync(
        Guid deviceId,
        CancellationToken cancellationToken = default)
    {
        var id = DeviceId.From(deviceId);
        var device = await devices.GetByIdAsync(id, cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(Device), deviceId);
        if (!device.IsEnabled)
        {
            throw new ResourceNotFoundException(nameof(Device), deviceId);
        }

        var controls = await cameraControl.GetAsync(deviceId, cancellationToken);
        var commands = await deviceCommands.GetActiveAsync(id, cancellationToken);
        var conflicts = new List<DeviceRemovalConflict>();
        if (controls.DeviceState?.Telemetry.Recording == true)
        {
            conflicts.Add(new DeviceRemovalConflict(
                DeviceRemovalConflictCodes.RecordingActive,
                "Stop the active recording before removing this device so its current file can be finalized safely.",
                BlocksRemoval: true));
        }
        if (liveSessions.HasActiveSession(deviceId))
        {
            conflicts.Add(new DeviceRemovalConflict(
                DeviceRemovalConflictCodes.LiveActive,
                "The active Live session will be stopped when this device is removed.",
                BlocksRemoval: false));
        }
        if (commands.Any(command => command.State == DeviceCommandState.Dispatched)
            || controls.RecentCommands.Any(command => command.State == CameraControlCommandState.Executing))
        {
            conflicts.Add(new DeviceRemovalConflict(
                DeviceRemovalConflictCodes.CommandExecuting,
                "Executing commands will be canceled and marked failed when this device is removed.",
                BlocksRemoval: false));
        }

        var transport = connections.GetStatus(id);
        return new DeviceRemovalImpact(
            deviceId,
            device.DisplayName,
            device.Identity.Manufacturer,
            device.Identity.Model,
            transport?.LastHeartbeatAtUtc ?? device.LastSeenAtUtc,
            conflicts.All(conflict => !conflict.BlocksRemoval),
            conflicts,
            Retention);
    }

    public async Task<DeviceRemovalResult> RemoveAsync(
        Guid deviceId,
        RemoveDeviceRequest request,
        DeviceRemovalActor actor,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(actor);
        var impact = await GetImpactAsync(deviceId, cancellationToken);
        if (!request.ConfirmRemoval
            || !string.Equals(request.ExpectedDeviceName?.Trim(), impact.DeviceName, StringComparison.Ordinal))
        {
            throw new DomainValidationException(
                "Confirm removal using the current device name shown by the Hub.");
        }
        if (!impact.CanRemove)
        {
            throw new ResourceConflictException(
                "This device cannot be removed until its active recording is stopped and finalized.");
        }

        var id = DeviceId.From(deviceId);
        var device = await devices.GetByIdAsync(id, cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(Device), deviceId);
        var now = timeProvider.GetUtcNow();
        var connection = connections.GetStatus(id);
        var liveReleased = await liveSessions.CloseForDeviceAsync(deviceId, cancellationToken);
        var cameraCommandsResolved = await cameraControl.ResolveForDeviceRemovalAsync(
            deviceId,
            new CameraControlActor(actor.Subject),
            cancellationToken);
        var activeCommands = await deviceCommands.GetActiveAsync(id, cancellationToken);
        foreach (var command in activeCommands)
        {
            command.Fail("device_removed", now);
        }

        var persistedConnection = await connectionRepository.GetActiveAsync(id, cancellationToken);
        persistedConnection?.Disconnect(now);
        operationalHealth.Remove(id);
        device.Unpair(now);
        var audit = DeviceEvent.Record(
            id,
            "device.unpaired",
            JsonSerializer.Serialize(new
            {
                actor = actor.Subject,
                reason = "administrator_removed",
                recordingsPreserved = true,
                commandsResolved = cameraCommandsResolved + activeCommands.Count,
                liveReleased,
            }),
            now,
            now);
        deviceEvents.Add(audit);
        await unitOfWork.SaveChangesAsync(cancellationToken);

        var disconnected = false;
        if (connection?.State == DeviceTransportState.Connected)
        {
            disconnected = await realtime.RevokeAndDisconnectAsync(
                connection.ConnectionId,
                new DevicePairingRevoked(deviceId, now, "administrator_removed"),
                cancellationToken);
        }
        connections.Remove(id, now);
        await events.DeviceChangedAsync(new MonitoringUpdate(deviceId, now), cancellationToken);

        return new DeviceRemovalResult(
            deviceId,
            now,
            CredentialsRevoked: true,
            RealtimeDisconnected: disconnected || connection is null,
            CommandsResolved: cameraCommandsResolved + activeCommands.Count,
            LiveSessionReleased: liveReleased,
            UploadedRecordingsPreserved: true,
            audit.Id);
    }
}
