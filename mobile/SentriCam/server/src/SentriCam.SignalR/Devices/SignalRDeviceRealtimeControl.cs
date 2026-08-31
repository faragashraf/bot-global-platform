using System.Collections.Concurrent;
using Microsoft.AspNetCore.SignalR;
using SentriCam.Application.Devices;
using SentriCam.Contracts.SignalR;
using SentriCam.SignalR.Hubs;

namespace SentriCam.SignalR.Devices;

public sealed class SignalRDeviceSessionRegistry
{
    private readonly ConcurrentDictionary<string, (Guid DeviceId, Action Abort)> _sessions =
        new(StringComparer.Ordinal);

    public void Track(Guid deviceId, string connectionId, Action abort) =>
        _sessions[connectionId] = (deviceId, abort);

    public void Untrack(string connectionId) => _sessions.TryRemove(connectionId, out _);

    public bool Abort(string connectionId)
    {
        if (!_sessions.TryRemove(connectionId, out var session))
        {
            return false;
        }

        session.Abort();
        return true;
    }
}

public sealed class SignalRDeviceRealtimeControl(
    IHubContext<DeviceHub, IDeviceHubClient> hub,
    SignalRDeviceSessionRegistry sessions) : IDeviceRealtimeControl
{
    public async Task<bool> RevokeAndDisconnectAsync(
        string connectionId,
        DevicePairingRevoked revocation,
        CancellationToken cancellationToken = default)
    {
        await hub.Clients.Client(connectionId).PairingRevoked(revocation);
        return sessions.Abort(connectionId);
    }
}
