using SentriCam.Domain.Common;

namespace SentriCam.Domain.Devices;

public sealed class DeviceSnapshot : AggregateRoot<Guid>
{
    private DeviceSnapshot()
    {
    }

    private DeviceSnapshot(
        Guid id,
        DeviceId deviceId,
        DeviceStatus status,
        int? batteryPercentage,
        long? availableStorageBytes,
        bool isMonitoring,
        bool isRecording,
        string? metadataJson,
        long snapshotVersion,
        int schemaVersion,
        DateTimeOffset generatedAtUtc,
        DateTimeOffset capturedAtUtc)
    {
        Id = id;
        DeviceId = deviceId;
        Status = status;
        BatteryPercentage = batteryPercentage;
        AvailableStorageBytes = availableStorageBytes;
        IsMonitoring = isMonitoring;
        IsRecording = isRecording;
        MetadataJson = metadataJson;
        SnapshotVersion = snapshotVersion;
        SchemaVersion = schemaVersion;
        GeneratedAtUtc = generatedAtUtc;
        CapturedAtUtc = capturedAtUtc;
    }

    public DeviceId DeviceId { get; private set; }

    public DeviceStatus Status { get; private set; }

    public int? BatteryPercentage { get; private set; }

    public long? AvailableStorageBytes { get; private set; }

    public bool IsMonitoring { get; private set; }

    public bool IsRecording { get; private set; }

    public string? MetadataJson { get; private set; }

    public long SnapshotVersion { get; private set; }

    public int SchemaVersion { get; private set; }

    public DateTimeOffset GeneratedAtUtc { get; private set; }

    public DateTimeOffset CapturedAtUtc { get; private set; }

    public static DeviceSnapshot Capture(
        DeviceId deviceId,
        DeviceStatus status,
        int? batteryPercentage,
        long? availableStorageBytes,
        bool isMonitoring,
        bool isRecording,
        string? metadataJson,
        DateTimeOffset now) =>
        Capture(
            deviceId,
            status,
            batteryPercentage,
            availableStorageBytes,
            isMonitoring,
            isRecording,
            metadataJson,
            snapshotVersion: 1,
            schemaVersion: 1,
            generatedAtUtc: now,
            capturedAtUtc: now);

    public static DeviceSnapshot Capture(
        DeviceId deviceId,
        DeviceStatus status,
        int? batteryPercentage,
        long? availableStorageBytes,
        bool isMonitoring,
        bool isRecording,
        string? metadataJson,
        long snapshotVersion,
        int schemaVersion,
        DateTimeOffset generatedAtUtc,
        DateTimeOffset capturedAtUtc)
    {
        DomainGuard.Percentage(batteryPercentage, nameof(BatteryPercentage));
        if (availableStorageBytes < 0)
        {
            throw new DomainValidationException("Available storage cannot be negative.");
        }

        if (snapshotVersion <= 0)
        {
            throw new DomainValidationException("Snapshot version must be greater than zero.");
        }

        if (schemaVersion <= 0)
        {
            throw new DomainValidationException("Schema version must be greater than zero.");
        }

        return new DeviceSnapshot(
            Guid.NewGuid(),
            deviceId,
            status,
            batteryPercentage,
            availableStorageBytes,
            isMonitoring,
            isRecording,
            DomainGuard.Optional(metadataJson, 16_000, nameof(MetadataJson)),
            snapshotVersion,
            schemaVersion,
            generatedAtUtc,
            capturedAtUtc);
    }
}
