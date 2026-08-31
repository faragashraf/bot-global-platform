using System.Text.Json;
using SentriCam.Contracts.Commands;

namespace SentriCam.Contracts.Monitoring;

public enum RemoteCommandState
{
    Pending = 1,
    Succeeded = 2,
    Failed = 3,
    Timeout = 4,
}

public sealed record DeviceLiveState(
    Guid DeviceId,
    string FriendlyName,
    string Platform,
    string ConnectionState,
    string OperationalState,
    bool Monitoring,
    string Motion,
    string Recording,
    int? BatteryPercent,
    long? AvailableStorageBytes,
    DateTimeOffset? LastHeartbeatAtUtc,
    DateTimeOffset? LastSeenAtUtc,
    long? SnapshotVersion,
    JsonElement? Snapshot,
    DeviceOperationalHealthView Health);

public sealed record SubsystemHealthView(
    string Subsystem,
    string Lifecycle,
    string Health,
    string? RecoveryReason,
    int ReconnectCount,
    DateTimeOffset? LastFailureAtUtc,
    DateTimeOffset? LastRecoveryAtUtc,
    long? RecoveryDurationMilliseconds,
    DateTimeOffset? UpdatedAtUtc);

public sealed record DeviceOperationalHealthView(
    string Overall,
    IReadOnlyList<SubsystemHealthView> Subsystems,
    DateTimeOffset? ReportedAtUtc,
    string? PresenceState,
    bool IsCurrent,
    DateTimeOffset? OutageStartedAtUtc,
    string? OutageCause);

public sealed record RecoveryHistoryEntry(
    string Subsystem,
    string Lifecycle,
    string Health,
    string? RecoveryReason,
    DateTimeOffset OccurredAtUtc,
    long SnapshotVersion);

public sealed record DeviceOperationalHealthReport(
    Guid DeviceId,
    JsonElement Health,
    DateTimeOffset ReportedAtUtc);

public sealed record RemoteCommandView(
    Guid CommandId,
    Guid DeviceId,
    DeviceCommandType CommandType,
    RemoteCommandState State,
    string CorrelationId,
    string? ResultCode,
    DateTimeOffset RequestedAtUtc,
    DateTimeOffset? CompletedAtUtc);

public sealed record DeviceMonitoringDetails(
    DeviceLiveState Device,
    IReadOnlyList<RemoteCommandView> RecentCommands,
    IReadOnlyList<RecoveryHistoryEntry> RecoveryHistory);

public sealed record OperatorTokenResponse(
    string AccessToken,
    DateTimeOffset ExpiresAtUtc,
    string TokenType,
    string OperatorDisplayName);

public sealed record MonitoringUpdate(
    Guid DeviceId,
    DateTimeOffset ServerUtcNow);

public sealed record CommandUpdate(
    Guid DeviceId,
    Guid CommandId,
    RemoteCommandState State,
    DateTimeOffset ServerUtcNow);
