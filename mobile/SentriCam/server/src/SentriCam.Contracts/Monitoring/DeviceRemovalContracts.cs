namespace SentriCam.Contracts.Monitoring;

public static class DeviceRemovalConflictCodes
{
    public const string RecordingActive = "recording_active";
    public const string LiveActive = "live_active";
    public const string CommandExecuting = "command_executing";
}

public sealed record DeviceRemovalConflict(
    string Code,
    string Message,
    bool BlocksRemoval);

public sealed record DeviceRemovalRetention(
    bool UploadedRecordingsPreserved,
    bool RecordingMetadataPreserved,
    bool AuditHistoryPreserved,
    bool HistoricalEventsPreserved);

public sealed record DeviceRemovalImpact(
    Guid DeviceId,
    string DeviceName,
    string Manufacturer,
    string Model,
    DateTimeOffset? LastConnectionAtUtc,
    bool CanRemove,
    IReadOnlyList<DeviceRemovalConflict> Conflicts,
    DeviceRemovalRetention Retention);

public sealed record RemoveDeviceRequest(
    bool ConfirmRemoval,
    string ExpectedDeviceName);

public sealed record DeviceRemovalResult(
    Guid DeviceId,
    DateTimeOffset RemovedAtUtc,
    bool CredentialsRevoked,
    bool RealtimeDisconnected,
    int CommandsResolved,
    bool LiveSessionReleased,
    bool UploadedRecordingsPreserved,
    Guid AuditId);
