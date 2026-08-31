using SentriCam.Domain.Common;

namespace SentriCam.Domain.Devices;

public sealed class DeviceStatusHistory : AggregateRoot<Guid>
{
    private DeviceStatusHistory()
    {
    }

    private DeviceStatusHistory(
        Guid id,
        DeviceId deviceId,
        DeviceStatus? previousStatus,
        DeviceStatus currentStatus,
        string? reason,
        DateTimeOffset changedAtUtc)
    {
        Id = id;
        DeviceId = deviceId;
        PreviousStatus = previousStatus;
        CurrentStatus = currentStatus;
        Reason = reason;
        ChangedAtUtc = changedAtUtc;
    }

    public DeviceId DeviceId { get; private set; }

    public DeviceStatus? PreviousStatus { get; private set; }

    public DeviceStatus CurrentStatus { get; private set; }

    public string? Reason { get; private set; }

    public DateTimeOffset ChangedAtUtc { get; private set; }

    public static DeviceStatusHistory Record(
        DeviceId deviceId,
        DeviceStatus? previousStatus,
        DeviceStatus currentStatus,
        string? reason,
        DateTimeOffset now) =>
        new(
            Guid.NewGuid(),
            deviceId,
            previousStatus,
            currentStatus,
            DomainGuard.Optional(reason, 500, nameof(Reason)),
            now);
}
