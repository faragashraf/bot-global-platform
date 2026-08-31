using SentriCam.Domain.Common;

namespace SentriCam.Domain.Devices;

public sealed class DeviceEvent : AggregateRoot<Guid>
{
    private DeviceEvent()
    {
    }

    private DeviceEvent(
        Guid id,
        DeviceId deviceId,
        string eventType,
        string? payloadJson,
        DateTimeOffset occurredAtUtc,
        DateTimeOffset receivedAtUtc)
    {
        Id = id;
        DeviceId = deviceId;
        EventType = eventType;
        PayloadJson = payloadJson;
        OccurredAtUtc = occurredAtUtc;
        ReceivedAtUtc = receivedAtUtc;
    }

    public DeviceId DeviceId { get; private set; }

    public string EventType { get; private set; } = string.Empty;

    public string? PayloadJson { get; private set; }

    public DateTimeOffset OccurredAtUtc { get; private set; }

    public DateTimeOffset ReceivedAtUtc { get; private set; }

    public static DeviceEvent Record(
        DeviceId deviceId,
        string eventType,
        string? payloadJson,
        DateTimeOffset occurredAtUtc,
        DateTimeOffset receivedAtUtc) =>
        new(
            Guid.NewGuid(),
            deviceId,
            DomainGuard.Required(eventType, 100, nameof(EventType)),
            DomainGuard.Optional(payloadJson, 32_000, nameof(PayloadJson)),
            occurredAtUtc,
            receivedAtUtc);
}
