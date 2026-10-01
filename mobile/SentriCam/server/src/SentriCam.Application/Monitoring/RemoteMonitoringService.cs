using System.Text.Json;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Application.Commands;
using SentriCam.Application.Common;
using SentriCam.Application.Connections;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Monitoring;
using SentriCam.Domain.Common;
using SentriCam.Domain.Devices;

namespace SentriCam.Application.Monitoring;

public sealed class RemoteMonitoringService(
    IDeviceRepository deviceRepository,
    IDeviceSnapshotRepository snapshotRepository,
    IDeviceCommandRepository commandRepository,
    IDeviceControlCommandHandler commandHandler,
    IDeviceCommandTransport commandTransport,
    IDeviceConnectionManager connectionManager,
    IDeviceOperationalHealthRegistry operationalHealth,
    IDeviceRequestAuthorizer requestAuthorizer,
    IMonitoringEventPublisher eventPublisher,
    IUnitOfWork unitOfWork,
    TimeProvider timeProvider) : IRemoteMonitoringService
{
    public async Task<IReadOnlyList<DeviceLiveState>> GetDevicesAsync(
        CancellationToken cancellationToken = default)
    {
        var devices = await deviceRepository.ListAsync(cancellationToken);
        var views = new List<DeviceLiveState>(devices.Count);
        foreach (var device in devices.Where(device => device.IsEnabled))
        {
            requestAuthorizer.EnsureCanAccess(device.Id);
            views.Add(await MapDeviceAsync(device, cancellationToken));
        }

        return views;
    }

    public async Task<DeviceMonitoringDetails> GetDeviceAsync(
        Guid deviceId,
        CancellationToken cancellationToken = default)
    {
        var id = DeviceId.From(deviceId);
        requestAuthorizer.EnsureCanAccess(id);
        var device = await deviceRepository.GetByIdAsync(id, cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(Device), deviceId);
        if (!device.IsEnabled)
        {
            throw new ResourceNotFoundException(nameof(Device), deviceId);
        }
        var commands = await commandRepository.GetRecentAsync(id, 20, cancellationToken);
        var snapshots = await snapshotRepository.GetRecentAsync(id, 80, cancellationToken);
        return new DeviceMonitoringDetails(
            await MapDeviceAsync(device, cancellationToken),
            commands.Select(MapCommand).ToArray(),
            MapRecoveryHistory(snapshots));
    }

    public async Task<RemoteCommandView> SubmitAsync(
        IDeviceControlCommand command,
        CancellationToken cancellationToken = default)
    {
        var submission = await commandHandler.HandleAsync(command, cancellationToken);
        var queued = await commandRepository.GetByIdAsync(submission.CommandId, cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(DeviceCommand), submission.CommandId);
        if (queued.State == DeviceCommandState.Pending)
        {
            var dispatched = await commandTransport.DispatchAsync(
                new DeviceCommandEnvelope(
                    queued.CommandId,
                    queued.DeviceId.Value,
                    submission.CommandType,
                    queued.CorrelationId,
                    queued.RequestedAtUtc),
                cancellationToken);
            if (dispatched)
            {
                queued.MarkDispatched(timeProvider.GetUtcNow());
                await unitOfWork.SaveChangesAsync(cancellationToken);
                await PublishCommandAsync(queued, cancellationToken);
            }
            else
            {
                // Keep a bounded command pending so the authenticated device reconnect path can
                // dispatch it. The normal timeout worker remains the terminal safety boundary.
                await PublishCommandAsync(queued, cancellationToken);
            }
        }

        return MapCommand(queued);
    }

    public async Task CompleteAsync(
        Guid authenticatedDeviceId,
        DeviceCommandResultEnvelope result,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(result);
        var deviceId = DeviceId.From(authenticatedDeviceId);
        requestAuthorizer.EnsureCanAccess(deviceId);
        if (result.DeviceId != authenticatedDeviceId)
        {
            throw new AccessDeniedException("The command result does not belong to the authenticated device.");
        }

        var command = await commandRepository.GetByIdAsync(result.CommandId, cancellationToken)
            ?? throw new ResourceNotFoundException(nameof(DeviceCommand), result.CommandId);
        if (command.DeviceId != deviceId)
        {
            throw new AccessDeniedException("The command result does not belong to its target device.");
        }

        if (command.State is DeviceCommandState.Succeeded
            or DeviceCommandState.Failed
            or DeviceCommandState.TimedOut)
        {
            return;
        }
        if (command.State != DeviceCommandState.Dispatched)
        {
            throw new DomainValidationException("Only a dispatched command can be completed.");
        }

        var now = timeProvider.GetUtcNow();
        if (result.Outcome == DeviceCommandOutcome.Succeeded)
        {
            command.Succeed(
                result.ResultCode,
                result.Snapshot?.GetRawText(),
                now);
        }
        else
        {
            command.Fail(result.ResultCode, now);
        }

        await unitOfWork.SaveChangesAsync(cancellationToken);
        await PublishCommandAsync(command, cancellationToken);
        if (result.Snapshot is not null)
        {
            await eventPublisher.DeviceChangedAsync(
                new MonitoringUpdate(deviceId.Value, now),
                cancellationToken);
        }
    }

    public async Task<int> ExpireCommandsAsync(CancellationToken cancellationToken = default)
    {
        var now = timeProvider.GetUtcNow();
        var commands = await commandRepository.GetExpiredAsync(now, cancellationToken);
        foreach (var command in commands)
        {
            command.Timeout(now);
        }
        if (commands.Count == 0)
        {
            return 0;
        }

        await unitOfWork.SaveChangesAsync(cancellationToken);
        foreach (var command in commands)
        {
            await PublishCommandAsync(command, cancellationToken);
        }
        return commands.Count;
    }

    private async Task<DeviceLiveState> MapDeviceAsync(
        Device device,
        CancellationToken cancellationToken)
    {
        var snapshot = await snapshotRepository.GetLatestAsync(device.Id, cancellationToken);
        var connection = connectionManager.GetStatus(device.Id);
        var snapshotJson = ParseSnapshot(snapshot?.MetadataJson);
        var currentHealth = operationalHealth.GetLatest(device.Id);
        var snapshotHealth = ReadObject(snapshotJson, "subsystemHealth");
        var useRealtimeHealth = currentHealth is not null
            && (snapshot is null || currentHealth.ReportedAtUtc >= snapshot.GeneratedAtUtc);
        var healthJson = useRealtimeHealth ? currentHealth!.Health : snapshotHealth;
        var connectionState = connection?.State switch
        {
            DeviceTransportState.Connected => "connected",
            DeviceTransportState.Recovering => "recovering",
            _ => "offline",
        };
        var now = timeProvider.GetUtcNow();
        var reportedAtUtc = useRealtimeHealth ? currentHealth!.ReportedAtUtc : snapshot?.GeneratedAtUtc;
        return new DeviceLiveState(
            device.Id.Value,
            device.DisplayName,
            device.Identity.Platform,
            connectionState,
            device.Status.ToString().ToLowerInvariant(),
            snapshot?.IsMonitoring ?? false,
            ReadString(snapshotJson, "motionState") ?? "unknown",
            ReadString(snapshotJson, "recordingState") ?? (snapshot?.IsRecording == true ? "recording" : "idle"),
            snapshot?.BatteryPercentage,
            snapshot?.AvailableStorageBytes,
            connection?.LastHeartbeatAtUtc,
            device.LastSeenAtUtc,
            snapshot?.SnapshotVersion,
            snapshotJson,
            MapHealth(healthJson, reportedAtUtc, connection, connectionState, now));
    }

    private async Task PublishCommandAsync(
        DeviceCommand command,
        CancellationToken cancellationToken) =>
        await eventPublisher.CommandChangedAsync(
            new CommandUpdate(
                command.DeviceId.Value,
                command.CommandId,
                MapState(command.State),
                timeProvider.GetUtcNow()),
            cancellationToken);

    private static RemoteCommandView MapCommand(DeviceCommand command) => new(
        command.CommandId,
        command.DeviceId.Value,
        MapType(command.Kind),
        MapState(command.State),
        command.CorrelationId,
        command.ResultCode ?? command.FailureReason,
        command.RequestedAtUtc,
        command.CompletedAtUtc);

    private static DeviceCommandType MapType(DeviceCommandKind kind) => kind switch
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

    private static RemoteCommandState MapState(DeviceCommandState state) => state switch
    {
        DeviceCommandState.Pending or DeviceCommandState.Dispatched => RemoteCommandState.Pending,
        DeviceCommandState.Succeeded => RemoteCommandState.Succeeded,
        DeviceCommandState.Failed => RemoteCommandState.Failed,
        DeviceCommandState.TimedOut => RemoteCommandState.Timeout,
        _ => throw new ArgumentOutOfRangeException(nameof(state), state, "Unknown command state."),
    };

    private static JsonElement? ParseSnapshot(string? json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return null;
        }

        using var document = JsonDocument.Parse(json);
        return document.RootElement.Clone();
    }

    private static string? ReadString(JsonElement? value, string name) =>
        value is { ValueKind: JsonValueKind.Object } element
        && element.TryGetProperty(name, out var property)
        && property.ValueKind == JsonValueKind.String
            ? property.GetString()
            : null;

    private static JsonElement? ReadObject(JsonElement? value, string name) =>
        value is { ValueKind: JsonValueKind.Object } element
        && element.TryGetProperty(name, out var property)
        && property.ValueKind == JsonValueKind.Object
            ? property.Clone()
            : null;

    private static DeviceOperationalHealthView MapHealth(
        JsonElement? health,
        DateTimeOffset? reportedAtUtc,
        DeviceConnectionStatus? connection,
        string connectionState,
        DateTimeOffset nowUtc)
    {
        var isConnected = connection is not null && connection.State == DeviceTransportState.Connected;
        var isRecovering = connection?.State == DeviceTransportState.Recovering;
        var isReportFresh = IsFreshHealth(reportedAtUtc, nowUtc);
        var appliesAfterReconnect = IsAfterReconnect(reportedAtUtc, connection);
        var isCurrent = isConnected && isReportFresh && appliesAfterReconnect;
        var outageReason = isConnected ? null : ResolveOutageReason(connection);
        var outageStartedAtUtc = isConnected ? null : connection?.DisconnectedAtUtc ?? connection?.LastHeartbeatAtUtc;

        var reports = new List<SubsystemHealthView>(SubsystemNames.Length);

        if (health is not { ValueKind: JsonValueKind.Object } root)
        {
            foreach (var name in SubsystemNames)
            {
                reports.Add(CreateDerivedSubsystemHealth(
                    name,
                    isConnected || isRecovering,
                    isCurrent ? null : outageReason));
            }

            return new DeviceOperationalHealthView(
                isConnected
                    ? isCurrent ? "unknown" : "recovering"
                    : isRecovering ? "recovering" : "offline",
                reports,
                reportedAtUtc,
                connectionState,
                isCurrent,
                outageStartedAtUtc,
                outageReason);
        }

        foreach (var name in SubsystemNames)
        {
            if (!root.TryGetProperty(name, out var item) || item.ValueKind != JsonValueKind.Object)
            {
                reports.Add(CreateDerivedSubsystemHealth(
                    name,
                    isConnected || isRecovering,
                    isConnected ? "report_missing" : outageReason));
                continue;
            }

            var healthValue = isCurrent
                ? ReadString(item, "health")?.ToLowerInvariant() ?? "unknown"
                : isRecovering || isConnected ? "recovering" : "unavailable";
            var lifecycleValue = isCurrent
                ? ReadString(item, "lifecycle")?.ToLowerInvariant() ?? "unknown"
                : isRecovering || isConnected ? "recovering" : "unavailable";
            var reason = isCurrent
                ? ReadString(item, "recoveryReason")
                : outageReason ?? "waiting_for_health_report";

            reports.Add(new SubsystemHealthView(
                ReadString(item, "subsystem")?.ToLowerInvariant() ?? name,
                lifecycleValue,
                healthValue,
                reason,
                ReadInt32(item, "reconnectCount") ?? 0,
                ReadInstant(item, "lastFailureAtMillis"),
                ReadInstant(item, "lastRecoveryAtMillis"),
                ReadInt64(item, "recoveryDurationMillis"),
                ReadInstant(item, "updatedAtMillis")));
        }

        var overall = isConnected
            ? isCurrent
                ? ReadString(root, "overall")?.ToLowerInvariant() ?? "unknown"
                : "recovering"
            : isRecovering ? "recovering" : "offline";

        return new DeviceOperationalHealthView(
            overall,
            reports,
            reportedAtUtc,
            connectionState,
            isCurrent,
            outageStartedAtUtc,
            outageReason);
    }

    private static bool IsFreshHealth(DateTimeOffset? reportedAtUtc, DateTimeOffset nowUtc)
    {
        return reportedAtUtc is not null && nowUtc - reportedAtUtc <= DevicePresencePolicy.HealthFreshness;
    }

    private static bool IsAfterReconnect(DateTimeOffset? reportedAtUtc, DeviceConnectionStatus? connection) =>
        connection is not null
            && connection.State == DeviceTransportState.Connected
            && reportedAtUtc is not null
            && reportedAtUtc >= connection.ConnectedAtUtc;

    private static string? ResolveOutageReason(DeviceConnectionStatus? connection)
    {
        if (connection is null)
        {
            return "connection_lost";
        }

        if (connection.State == DeviceTransportState.Connected)
        {
            return null;
        }

        if (connection.State == DeviceTransportState.Disconnected)
        {
            return "heartbeat_expired";
        }

        return "connection_lost";
    }

    private static SubsystemHealthView CreateDerivedSubsystemHealth(
        string name,
        bool isConnected,
        string? reason)
    {
        var lifecycle = isConnected
            ? "recovering"
            : "unavailable";
        return new SubsystemHealthView(
            name,
            lifecycle,
            isConnected ? "recovering" : "unknown",
            reason,
            0,
            null,
            null,
            null,
            null);
    }

    private static DeviceOperationalHealthView MapHealthForHistory(
        JsonElement? health,
        DateTimeOffset reportedAtUtc)
    {
        if (health is not { ValueKind: JsonValueKind.Object } root)
        {
            return new DeviceOperationalHealthView("unknown", [], reportedAtUtc, "unknown", false, null, null);
        }

        var reports = new List<SubsystemHealthView>();
        foreach (var name in SubsystemNames)
        {
            if (!root.TryGetProperty(name, out var item) || item.ValueKind != JsonValueKind.Object)
            {
                continue;
            }

            reports.Add(new SubsystemHealthView(
                ReadString(item, "subsystem")?.ToLowerInvariant() ?? name,
                ReadString(item, "lifecycle")?.ToLowerInvariant() ?? "unknown",
                ReadString(item, "health")?.ToLowerInvariant() ?? "unknown",
                ReadString(item, "recoveryReason"),
                ReadInt32(item, "reconnectCount") ?? 0,
                ReadInstant(item, "lastFailureAtMillis"),
                ReadInstant(item, "lastRecoveryAtMillis"),
                ReadInt64(item, "recoveryDurationMillis"),
                ReadInstant(item, "updatedAtMillis")));
        }

        return new DeviceOperationalHealthView(
            ReadString(root, "overall")?.ToLowerInvariant() ?? "unknown",
            reports,
            reportedAtUtc,
            "offline",
            false,
            null,
            null);
    }

    private static RecoveryHistoryEntry[] MapRecoveryHistory(
        IReadOnlyList<DeviceSnapshot> snapshots)
    {
        var result = new List<RecoveryHistoryEntry>();
        var previous = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var snapshot in snapshots.OrderBy(item => item.SnapshotVersion))
        {
            var health = MapHealthForHistory(
                ReadObject(ParseSnapshot(snapshot.MetadataJson), "subsystemHealth"),
                snapshot.CapturedAtUtc);
            foreach (var report in health.Subsystems)
            {
                var signature = $"{report.Lifecycle}|{report.Health}|{report.RecoveryReason}";
                if (previous.GetValueOrDefault(report.Subsystem) == signature)
                {
                    continue;
                }
                previous[report.Subsystem] = signature;
                result.Add(new RecoveryHistoryEntry(
                    report.Subsystem,
                    report.Lifecycle,
                    report.Health,
                    report.RecoveryReason,
                    report.UpdatedAtUtc ?? snapshot.CapturedAtUtc,
                    snapshot.SnapshotVersion));
            }
        }

        return result.OrderByDescending(item => item.OccurredAtUtc).Take(80).ToArray();
    }

    private static string? ReadString(JsonElement value, string name) =>
        value.TryGetProperty(name, out var property) && property.ValueKind == JsonValueKind.String
            ? property.GetString()
            : null;

    private static int? ReadInt32(JsonElement value, string name) =>
        value.TryGetProperty(name, out var property) && property.TryGetInt32(out var parsed)
            ? parsed
            : null;

    private static long? ReadInt64(JsonElement value, string name) =>
        value.TryGetProperty(name, out var property) && property.TryGetInt64(out var parsed)
            ? parsed
            : null;

    private static DateTimeOffset? ReadInstant(JsonElement value, string name) =>
        ReadInt64(value, name) is { } milliseconds && milliseconds > 0
            ? DateTimeOffset.FromUnixTimeMilliseconds(milliseconds)
            : null;

    private static readonly string[] SubsystemNames =
    [
        "realtime",
        "live",
        "recording",
        "upload",
        "motion",
        "camera",
        "battery",
        "storage",
        "commandQueue",
    ];
}
