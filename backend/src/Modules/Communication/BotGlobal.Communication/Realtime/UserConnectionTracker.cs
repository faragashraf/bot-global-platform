using System.Collections.Concurrent;

namespace BotGlobal.Communication.Realtime;

public sealed class UserConnectionTracker
{
    private readonly ConcurrentDictionary<string, ConcurrentDictionary<string, byte>>
        _connections = new(StringComparer.Ordinal);

    public bool Connected(string userId, string connectionId)
    {
        var connections = _connections.GetOrAdd(
            userId,
            _ => new ConcurrentDictionary<string, byte>(StringComparer.Ordinal));

        var wasOffline = connections.IsEmpty;
        connections.TryAdd(connectionId, 0);
        return wasOffline;
    }

    public bool Disconnected(string userId, string connectionId)
    {
        if (!_connections.TryGetValue(userId, out var connections))
            return false;

        connections.TryRemove(connectionId, out _);

        if (!connections.IsEmpty)
            return false;

        _connections.TryRemove(userId, out _);
        return true;
    }

    public bool IsOnline(string userId)
        => _connections.TryGetValue(userId, out var connections)
           && !connections.IsEmpty;
}
