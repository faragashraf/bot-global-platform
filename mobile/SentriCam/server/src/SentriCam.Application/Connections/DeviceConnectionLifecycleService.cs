using System.Text.Json;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Common;
using SentriCam.Contracts.SignalR;
using SentriCam.Contracts.Monitoring;
using SentriCam.Domain.Devices;
using SentriCam.Application.Monitoring;
using SentriCam.Contracts.Commands;
using Microsoft.Extensions.Logging;

namespace SentriCam.Application.Connections;

public sealed record DeviceConnectionLifecycleResult(
    DeviceId DeviceId,
    string ConnectionId,
    DateTimeOffset ConnectedAtUtc,
    bool IsReconnect,
    string? ReplacedConnectionId);

public interface IDeviceConnectionLifecycleService
{
    Task<DeviceConnectionLifecycleResult> ConnectedAsync(
        Guid deviceId,
        string connectionId,
        CancellationToken cancellationToken = default);

    Task DisconnectedAsync(
        string connectionId,
        CancellationToken cancellationToken = default);

    Task<DeviceHeartbeatAcknowledgement> HeartbeatAsync(
        DeviceHeartbeat heartbeat,
        string connectionId,
        CancellationToken cancellationToken = default);

    Task TransportPulseAsync(
        Guid deviceId,
        string connectionId,
        CancellationToken cancellationToken = default);
}

public sealed class DeviceConnectionLifecycleService(
    IDeviceRepository deviceRepository,
    IDeviceConnectionRepository connectionRepository,
    IDeviceSnapshotRepository snapshotRepository,
    IDeviceCommandRepository commandRepository,
    IDeviceCommandTransport commandTransport,
    IUnitOfWork unitOfWork,
    IDeviceRequestAuthorizer requestAuthorizer,
    IDeviceConnectionManager connectionManager,
    IDevicePresenceTransitionTracker presenceTransitions,
    IMonitoringEventPublisher eventPublisher,
    TimeProvider timeProvider,
    ILogger<DeviceConnectionLifecycleService> logger) : IDeviceConnectionLifecycleService
{
    private static readonly Action<ILogger, Guid, string, DateTimeOffset, bool, bool, Exception?> LogRealtimeConnected =
        LoggerMessage.Define<Guid, string, DateTimeOffset, bool, bool>(
            LogLevel.Debug,
            new EventId(1, "RealtimeConnected"),
            "event=realtime_connected deviceId={DeviceId} connectionId={ConnectionId} atUtc={AtUtc} reconnect={IsReconnect} presenceEventPublished={PresenceEventPublished}");
    private static readonly Action<ILogger, string, DateTimeOffset, Exception?> LogDuplicateDisconnect =
        LoggerMessage.Define<string, DateTimeOffset>(
            LogLevel.Debug,
            new EventId(2, "DuplicateDisconnectSuppressed"),
            "event=duplicate_disconnect_suppressed connectionId={ConnectionId} atUtc={AtUtc}");
    private static readonly Action<ILogger, Guid, string, DateTimeOffset, DateTimeOffset?, bool, bool, Exception?> LogTransportDisconnected =
        LoggerMessage.Define<Guid, string, DateTimeOffset, DateTimeOffset?, bool, bool>(
            LogLevel.Debug,
            new EventId(3, "TransportDisconnected"),
            "event=transport_lost deviceId={DeviceId} connectionId={ConnectionId} atUtc={AtUtc} lastHeartbeatAtUtc={LastHeartbeatAtUtc} mappingRemoved={MappingRemoved} recoveringEventPublished={RecoveringEventPublished}");
    private static readonly Action<ILogger, Guid, string, DateTimeOffset, long, Exception?> LogHeartbeatReceived =
        LoggerMessage.Define<Guid, string, DateTimeOffset, long>(
            LogLevel.Debug,
            new EventId(4, "HeartbeatReceived"),
            "event=heartbeat_received deviceId={DeviceId} connectionId={ConnectionId} atUtc={AtUtc} snapshotVersion={SnapshotVersion}");

    public async Task<DeviceConnectionLifecycleResult> ConnectedAsync(
        Guid deviceId,
        string connectionId,
        CancellationToken cancellationToken = default)
    {
        var id = DeviceId.From(deviceId);
        requestAuthorizer.EnsureCanAccess(id);
        var device = await deviceRepository.GetByIdAsync(id, cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(Device), deviceId);
        var now = timeProvider.GetUtcNow();
        var active = await connectionRepository.GetActiveAsync(id, cancellationToken);

        if (active is not null
            && !string.Equals(active.TransportConnectionId, connectionId, StringComparison.Ordinal))
        {
            active.Disconnect(now);
            active = null;
        }

        if (active is null)
        {
            connectionRepository.Add(DeviceConnection.Connect(id, connectionId, now));
        }
        else
        {
            active.Heartbeat(now);
        }

        device.MarkSeen(now);
        await unitOfWork.SaveChangesAsync(cancellationToken);
        var tracked = connectionManager.Connected(id, connectionId, now);
        var presenceChanged = presenceTransitions.TryTransition(id, DeviceTransportState.Connected);
        if (presenceChanged || tracked.IsReconnect)
        {
            await eventPublisher.DeviceChangedAsync(
                new MonitoringUpdate(id.Value, now),
                cancellationToken);
        }
        LogRealtimeConnected(
            logger,
            id.Value,
            connectionId,
            now,
            tracked.IsReconnect,
            presenceChanged || tracked.IsReconnect,
            null);
        await DispatchPendingCommandsAsync(id, now, cancellationToken);
        return new DeviceConnectionLifecycleResult(
            id,
            connectionId,
            tracked.Connection.ConnectedAtUtc,
            tracked.IsReconnect,
            tracked.ReplacedConnectionId);
    }

    public async Task DisconnectedAsync(
        string connectionId,
        CancellationToken cancellationToken = default)
    {
        var connection = await connectionRepository.GetByTransportConnectionIdAsync(
            connectionId,
            cancellationToken);
        if (connection is null || !connection.IsActive)
        {
            connectionManager.Disconnected(connectionId, timeProvider.GetUtcNow());
            LogDuplicateDisconnect(
                logger,
                connectionId,
                timeProvider.GetUtcNow(),
                null);
            return;
        }

        var now = timeProvider.GetUtcNow();
        connection.Disconnect(now);
        await unitOfWork.SaveChangesAsync(cancellationToken);
        var disconnected = connectionManager.Disconnected(connectionId, now);
        var presenceChanged = presenceTransitions.TryTransition(
            connection.DeviceId,
            DeviceTransportState.Recovering);
        if (presenceChanged)
        {
            await eventPublisher.DeviceChangedAsync(
                new MonitoringUpdate(connection.DeviceId.Value, now),
                cancellationToken);
        }
        LogTransportDisconnected(
            logger,
            connection.DeviceId.Value,
            connectionId,
            now,
            disconnected?.LastHeartbeatAtUtc,
            disconnected is not null,
            presenceChanged,
            null);
    }

    public async Task<DeviceHeartbeatAcknowledgement> HeartbeatAsync(
        DeviceHeartbeat heartbeat,
        string connectionId,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(heartbeat);
        if (!string.Equals(heartbeat.ConnectionId, connectionId, StringComparison.Ordinal))
        {
            throw new ArgumentException("Heartbeat connection id does not match the active transport.");
        }
        if (heartbeat.SnapshotVersion <= 0)
        {
            throw new ArgumentOutOfRangeException(
                nameof(heartbeat),
                "Heartbeat snapshot version must be greater than zero.");
        }
        if (heartbeat.CurrentDeviceSnapshot.ValueKind != JsonValueKind.Object)
        {
            throw new ArgumentException("Heartbeat device snapshot must be a JSON object.");
        }

        var deviceId = DeviceId.From(heartbeat.DeviceId);
        requestAuthorizer.EnsureCanAccess(deviceId);
        var device = await deviceRepository.GetByIdAsync(deviceId, cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(Device), heartbeat.DeviceId);
        var connection = await connectionRepository.GetByTransportConnectionIdAsync(
            connectionId,
            cancellationToken);
        if (connection is null || !connection.IsActive || connection.DeviceId != deviceId)
        {
            throw new AccessDeniedException("The heartbeat does not belong to an active device connection.");
        }

        var now = timeProvider.GetUtcNow();
        connection.Heartbeat(now);
        device.MarkSeen(now);

        var existingSnapshot = await snapshotRepository.GetByVersionAsync(
            deviceId,
            heartbeat.SnapshotVersion,
            cancellationToken);
        if (existingSnapshot is null)
        {
            var snapshot = MapSnapshot(deviceId, heartbeat, now);
            snapshotRepository.Add(snapshot);
            device.ChangeStatus(snapshot.Status, now);
        }

        await unitOfWork.SaveChangesAsync(cancellationToken);
        connectionManager.Heartbeat(deviceId, connectionId, now);
        presenceTransitions.TryTransition(deviceId, DeviceTransportState.Connected);
        await eventPublisher.DeviceChangedAsync(
            new MonitoringUpdate(deviceId.Value, now),
            cancellationToken);
        LogHeartbeatReceived(
            logger,
            deviceId.Value,
            connectionId,
            now,
            heartbeat.SnapshotVersion,
            null);
        return new DeviceHeartbeatAcknowledgement(
            heartbeat.DeviceId,
            connectionId,
            heartbeat.SnapshotVersion,
            now,
            now,
            (int)TimeZoneInfo.Local.GetUtcOffset(now.UtcDateTime).TotalMinutes,
            TimeZoneInfo.Local.Id);
    }

    public async Task TransportPulseAsync(
        Guid deviceId,
        string connectionId,
        CancellationToken cancellationToken = default)
    {
        var id = DeviceId.From(deviceId);
        requestAuthorizer.EnsureCanAccess(id);
        var now = timeProvider.GetUtcNow();
        var updated = connectionManager.TransportPulse(id, connectionId, now);
        if (updated is null || connectionManager.GetStatus(id)?.State != DeviceTransportState.Connected)
        {
            return;
        }

        if (presenceTransitions.TryTransition(id, DeviceTransportState.Connected))
        {
            await eventPublisher.DeviceChangedAsync(new MonitoringUpdate(deviceId, now), cancellationToken);
        }
    }

    private static DeviceSnapshot MapSnapshot(
        DeviceId deviceId,
        DeviceHeartbeat heartbeat,
        DateTimeOffset now)
    {
        var payload = heartbeat.CurrentDeviceSnapshot;
        var monitoring = ReadBoolean(payload, "monitoringEnabled");
        var recordingState = ReadString(payload, "recordingState");
        var recording = recordingState is not null
            && recordingState is not "IDLE" and not "COMPLETED" and not "FAILED";
        var status = recording
            ? DeviceStatus.Recording
            : monitoring
                ? DeviceStatus.Monitoring
                : DeviceStatus.Online;
        var battery = ReadNestedInt32(payload, "battery", "levelPercent");
        var storage = ReadNestedInt64(payload, "storage", "availableBytes");
        var schemaVersion = ReadInt32(payload, "schemaVersion") ?? 1;
        var metadata = payload.GetRawText();
        if (metadata.Length > 16_000)
        {
            throw new ArgumentException("Heartbeat device snapshot exceeds the supported size.");
        }

        return DeviceSnapshot.Capture(
            deviceId,
            status,
            battery,
            storage,
            monitoring,
            recording,
            metadata,
            heartbeat.SnapshotVersion,
            schemaVersion,
            now,
            heartbeat.Timestamp);
    }

    private async Task DispatchPendingCommandsAsync(
        DeviceId deviceId,
        DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        var commands = await commandRepository.GetActiveAsync(deviceId, cancellationToken);
        foreach (var command in commands.Where(item =>
            item.State == DeviceCommandState.Pending && !item.IsExpiredAt(now)))
        {
            var dispatched = await commandTransport.DispatchAsync(
                new DeviceCommandEnvelope(
                    command.CommandId,
                    command.DeviceId.Value,
                    MapCommandType(command.Kind),
                    command.CorrelationId,
                    command.RequestedAtUtc),
                cancellationToken);
            if (!dispatched)
            {
                continue;
            }

            command.MarkDispatched(now);
            await eventPublisher.CommandChangedAsync(
                new CommandUpdate(
                    command.DeviceId.Value,
                    command.CommandId,
                    RemoteCommandState.Pending,
                    now),
                cancellationToken);
        }

        await unitOfWork.SaveChangesAsync(cancellationToken);
    }

    private static DeviceCommandType MapCommandType(DeviceCommandKind kind) => kind switch
    {
        DeviceCommandKind.StartMonitoring => DeviceCommandType.StartMonitoring,
        DeviceCommandKind.StopMonitoring => DeviceCommandType.StopMonitoring,
        DeviceCommandKind.StartRecording => DeviceCommandType.StartRecording,
        DeviceCommandKind.StopRecording => DeviceCommandType.StopRecording,
        DeviceCommandKind.RestartMonitoring => DeviceCommandType.RestartMonitoring,
        DeviceCommandKind.Ping => DeviceCommandType.Ping,
        DeviceCommandKind.GetStatus => DeviceCommandType.GetStatus,
        _ => throw new ArgumentOutOfRangeException(nameof(kind), kind, "Unknown command kind."),
    };

    private static bool ReadBoolean(JsonElement parent, string propertyName) =>
        parent.TryGetProperty(propertyName, out var value)
        && value.ValueKind is JsonValueKind.True or JsonValueKind.False
        && value.GetBoolean();

    private static string? ReadString(JsonElement parent, string propertyName) =>
        parent.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static int? ReadInt32(JsonElement parent, string propertyName) =>
        parent.TryGetProperty(propertyName, out var value) && value.TryGetInt32(out var parsed)
            ? parsed
            : null;

    private static int? ReadNestedInt32(
        JsonElement parent,
        string objectName,
        string propertyName) =>
        parent.TryGetProperty(objectName, out var nested) && nested.ValueKind == JsonValueKind.Object
            ? ReadInt32(nested, propertyName)
            : null;

    private static long? ReadNestedInt64(
        JsonElement parent,
        string objectName,
        string propertyName) =>
        parent.TryGetProperty(objectName, out var nested)
        && nested.ValueKind == JsonValueKind.Object
        && nested.TryGetProperty(propertyName, out var value)
        && value.TryGetInt64(out var parsed)
            ? parsed
            : null;
}
