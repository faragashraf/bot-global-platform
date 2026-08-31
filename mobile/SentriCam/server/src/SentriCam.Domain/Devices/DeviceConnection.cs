using SentriCam.Domain.Common;
using SentriCam.Domain.Devices.Events;

namespace SentriCam.Domain.Devices;

public sealed class DeviceConnection : AggregateRoot<Guid>
{
    private DeviceConnection()
    {
    }

    private DeviceConnection(
        Guid id,
        DeviceId deviceId,
        string transportConnectionId,
        DateTimeOffset connectedAtUtc)
    {
        Id = id;
        DeviceId = deviceId;
        TransportConnectionId = transportConnectionId;
        ConnectedAtUtc = connectedAtUtc;
        LastSeenAtUtc = connectedAtUtc;
        IsActive = true;
    }

    public DeviceId DeviceId { get; private set; }

    public string TransportConnectionId { get; private set; } = string.Empty;

    public DateTimeOffset ConnectedAtUtc { get; private set; }

    public DateTimeOffset LastSeenAtUtc { get; private set; }

    public DateTimeOffset? DisconnectedAtUtc { get; private set; }

    public bool IsActive { get; private set; }

    public static DeviceConnection Connect(
        DeviceId deviceId,
        string transportConnectionId,
        DateTimeOffset now)
    {
        var connection = new DeviceConnection(
            Guid.NewGuid(),
            deviceId,
            DomainGuard.Required(transportConnectionId, 200, nameof(TransportConnectionId)),
            now);
        connection.RaiseDomainEvent(new DeviceConnectedDomainEvent(
            connection.DeviceId,
            connection.Id,
            connection.TransportConnectionId,
            now));
        return connection;
    }

    public void Heartbeat(DateTimeOffset now)
    {
        if (!IsActive)
        {
            throw new DomainValidationException("A disconnected device connection cannot heartbeat.");
        }

        LastSeenAtUtc = now;
    }

    public void Disconnect(DateTimeOffset now)
    {
        if (!IsActive)
        {
            return;
        }

        LastSeenAtUtc = now;
        DisconnectedAtUtc = now;
        IsActive = false;
        RaiseDomainEvent(new DeviceDisconnectedDomainEvent(DeviceId, Id, now));
    }
}
