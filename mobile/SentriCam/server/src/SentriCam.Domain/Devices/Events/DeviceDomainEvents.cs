using SentriCam.Domain.Common.Events;

namespace SentriCam.Domain.Devices.Events;

public sealed record DeviceRegisteredDomainEvent(
    DeviceId DeviceId,
    string InstallationId,
    DateTimeOffset RegisteredAtUtc) : DomainEvent(RegisteredAtUtc)
{
    public override string EventType => "device.registered";
}

public sealed record DeviceConnectedDomainEvent(
    DeviceId DeviceId,
    Guid ConnectionId,
    string TransportConnectionId,
    DateTimeOffset ConnectedAtUtc) : DomainEvent(ConnectedAtUtc)
{
    public override string EventType => "device.connected";
}

public sealed record DeviceDisconnectedDomainEvent(
    DeviceId DeviceId,
    Guid ConnectionId,
    DateTimeOffset DisconnectedAtUtc) : DomainEvent(DisconnectedAtUtc)
{
    public override string EventType => "device.disconnected";
}

public sealed record MonitoringStartedDomainEvent(
    DeviceId DeviceId,
    DateTimeOffset StartedAtUtc) : DomainEvent(StartedAtUtc)
{
    public override string EventType => "device.monitoring.started";
}

public sealed record MonitoringStoppedDomainEvent(
    DeviceId DeviceId,
    DateTimeOffset StoppedAtUtc) : DomainEvent(StoppedAtUtc)
{
    public override string EventType => "device.monitoring.stopped";
}

public sealed record RecordingStartedDomainEvent(
    DeviceId DeviceId,
    DateTimeOffset StartedAtUtc) : DomainEvent(StartedAtUtc)
{
    public override string EventType => "device.recording.started";
}

public sealed record RecordingStoppedDomainEvent(
    DeviceId DeviceId,
    DateTimeOffset StoppedAtUtc) : DomainEvent(StoppedAtUtc)
{
    public override string EventType => "device.recording.stopped";
}

public sealed record MotionDetectedDomainEvent(
    DeviceId DeviceId,
    DateTimeOffset DetectedAtUtc) : DomainEvent(DetectedAtUtc)
{
    public override string EventType => "device.motion.detected";
}
