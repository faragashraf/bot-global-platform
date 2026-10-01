using SentriCam.Domain.Devices;

namespace SentriCam.Application.Connections;

public enum DeviceTransportState
{
    Connected = 1,
    Disconnected = 2,
    Recovering = 3,
}

public sealed record DeviceConnectionStatus(
    DeviceId DeviceId,
    string ConnectionId,
    DeviceTransportState State,
    DateTimeOffset ConnectedAtUtc,
    DateTimeOffset LastHeartbeatAtUtc,
    DateTimeOffset? DisconnectedAtUtc);

public sealed record DeviceConnectedResult(
    DeviceConnectionStatus Connection,
    string? ReplacedConnectionId)
{
    public bool IsReconnect => ReplacedConnectionId is not null;
}

public interface IDeviceConnectionManager
{
    DeviceConnectedResult Connected(
        DeviceId deviceId,
        string connectionId,
        DateTimeOffset connectedAtUtc);

    DeviceConnectionStatus? Heartbeat(
        DeviceId deviceId,
        string connectionId,
        DateTimeOffset heartbeatAtUtc);

    DeviceConnectionStatus? TransportPulse(
        DeviceId deviceId,
        string connectionId,
        DateTimeOffset pulseAtUtc);

    DeviceConnectionStatus? Disconnected(
        string connectionId,
        DateTimeOffset disconnectedAtUtc);

    DeviceConnectionStatus? GetStatus(DeviceId deviceId);

    DeviceConnectionStatus? Remove(
        DeviceId deviceId,
        DateTimeOffset disconnectedAtUtc);
}

public sealed class DeviceConnectionManager(
    TimeProvider? timeProvider = null,
    TimeSpan? offlineAfter = null) : IDeviceConnectionManager
{
    public static readonly TimeSpan DefaultOfflineAfter = DevicePresencePolicy.OfflineAfter;

    private readonly TimeProvider _timeProvider = timeProvider ?? TimeProvider.System;
    private readonly TimeSpan _offlineAfter = offlineAfter ?? DefaultOfflineAfter;
    private readonly object _gate = new();
    private readonly Dictionary<DeviceId, DeviceConnectionStatus> _connectionsByDevice = [];
    private readonly Dictionary<string, DeviceId> _devicesByConnection =
        new(StringComparer.Ordinal);
    private readonly Dictionary<string, DateTimeOffset> _transportPulsesByConnection =
        new(StringComparer.Ordinal);

    public DeviceConnectedResult Connected(
        DeviceId deviceId,
        string connectionId,
        DateTimeOffset connectedAtUtc)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(connectionId);

        lock (_gate)
        {
            string? replacedConnectionId = null;
            if (_connectionsByDevice.TryGetValue(deviceId, out var existing))
            {
                if (existing.State == DeviceTransportState.Connected
                    && string.Equals(existing.ConnectionId, connectionId, StringComparison.Ordinal))
                {
                    var refreshed = existing with { LastHeartbeatAtUtc = connectedAtUtc };
                    _connectionsByDevice[deviceId] = refreshed;
                    return new DeviceConnectedResult(refreshed, null);
                }

                replacedConnectionId = existing.ConnectionId;
                _devicesByConnection.Remove(existing.ConnectionId);
                _transportPulsesByConnection.Remove(existing.ConnectionId);
            }

            var current = new DeviceConnectionStatus(
                deviceId,
                connectionId,
                DeviceTransportState.Connected,
                connectedAtUtc,
                connectedAtUtc,
                null);
            _connectionsByDevice[deviceId] = current;
            _devicesByConnection[connectionId] = deviceId;
            return new DeviceConnectedResult(current, replacedConnectionId);
        }
    }

    public DeviceConnectionStatus? Heartbeat(
        DeviceId deviceId,
        string connectionId,
        DateTimeOffset heartbeatAtUtc)
    {
        lock (_gate)
        {
            if (!_connectionsByDevice.TryGetValue(deviceId, out var existing)
                || existing.State != DeviceTransportState.Connected
                || !string.Equals(existing.ConnectionId, connectionId, StringComparison.Ordinal))
            {
                return null;
            }

            var current = existing with { LastHeartbeatAtUtc = heartbeatAtUtc };
            _connectionsByDevice[deviceId] = current;
            return current;
        }
    }

    public DeviceConnectionStatus? Disconnected(
        string connectionId,
        DateTimeOffset disconnectedAtUtc)
    {
        lock (_gate)
        {
            _transportPulsesByConnection.Remove(connectionId);
            if (!_devicesByConnection.Remove(connectionId, out var deviceId)
                || !_connectionsByDevice.TryGetValue(deviceId, out var existing)
                || !string.Equals(existing.ConnectionId, connectionId, StringComparison.Ordinal))
            {
                return null;
            }

            var disconnected = existing with
            {
                State = DeviceTransportState.Recovering,
                DisconnectedAtUtc = disconnectedAtUtc,
            };
            _connectionsByDevice[deviceId] = disconnected;
            return disconnected;
        }
    }

    public DeviceConnectionStatus? TransportPulse(
        DeviceId deviceId,
        string connectionId,
        DateTimeOffset pulseAtUtc)
    {
        lock (_gate)
        {
            if (!_connectionsByDevice.TryGetValue(deviceId, out var existing)
                || existing.State != DeviceTransportState.Connected
                || !string.Equals(existing.ConnectionId, connectionId, StringComparison.Ordinal))
            {
                return null;
            }

            _transportPulsesByConnection[connectionId] = pulseAtUtc;
            return existing;
        }
    }

    public DeviceConnectionStatus? GetStatus(DeviceId deviceId)
    {
        lock (_gate)
        {
            var current = _connectionsByDevice.GetValueOrDefault(deviceId);
            if (current is null || current.State == DeviceTransportState.Disconnected)
            {
                return current;
            }

            var now = _timeProvider.GetUtcNow();
            if (now - current.LastHeartbeatAtUtc < _offlineAfter)
            {
                if (current.State == DeviceTransportState.Connected
                    && _transportPulsesByConnection.TryGetValue(current.ConnectionId, out var lastPulse)
                    && now - lastPulse >= DevicePresencePolicy.TransportLostAfter)
                {
                    return current with
                    {
                        State = DeviceTransportState.Recovering,
                        DisconnectedAtUtc = lastPulse.Add(DevicePresencePolicy.TransportLostAfter),
                    };
                }

                return current;
            }

            // Keep the transport entry intact so a delayed heartbeat can make it healthy again.
            // Consumers nevertheless stop treating a connection with two missed heartbeats as live.
            return current with
            {
                State = DeviceTransportState.Disconnected,
                DisconnectedAtUtc = current.DisconnectedAtUtc
                    ?? current.LastHeartbeatAtUtc.Add(_offlineAfter),
            };
        }
    }

    public DeviceConnectionStatus? Remove(
        DeviceId deviceId,
        DateTimeOffset disconnectedAtUtc)
    {
        lock (_gate)
        {
            if (!_connectionsByDevice.Remove(deviceId, out var existing))
            {
                return null;
            }

            _devicesByConnection.Remove(existing.ConnectionId);
            _transportPulsesByConnection.Remove(existing.ConnectionId);
            return existing with
            {
                State = DeviceTransportState.Disconnected,
                DisconnectedAtUtc = disconnectedAtUtc,
            };
        }
    }
}
