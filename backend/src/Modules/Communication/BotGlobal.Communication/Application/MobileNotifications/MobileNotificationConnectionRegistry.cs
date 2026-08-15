using System.Collections.Concurrent;

namespace BotGlobal.Communication.Application.MobileNotifications;

public interface IMobileNotificationConnectionRegistry
{
    void Connected(Guid deviceId);

    void Disconnected(Guid deviceId);

    bool IsConnected(Guid deviceId);
}

internal sealed class MobileNotificationConnectionRegistry
    : IMobileNotificationConnectionRegistry
{
    private readonly ConcurrentDictionary<Guid, int> _connections =
        new();

    public void Connected(Guid deviceId)
    {
        _connections.AddOrUpdate(
            deviceId,
            1,
            (_, current) => current + 1);
    }

    public void Disconnected(Guid deviceId)
    {
        while (_connections.TryGetValue(
                   deviceId,
                   out var current))
        {
            if (current <= 1)
            {
                if (_connections.TryRemove(
                        deviceId,
                        out _))
                {
                    return;
                }

                continue;
            }

            if (_connections.TryUpdate(
                    deviceId,
                    current - 1,
                    current))
            {
                return;
            }
        }
    }

    public bool IsConnected(Guid deviceId) =>
        _connections.TryGetValue(
            deviceId,
            out var count)
        && count > 0;
}
