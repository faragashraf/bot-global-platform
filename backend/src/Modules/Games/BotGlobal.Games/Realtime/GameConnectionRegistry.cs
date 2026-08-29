using System.Collections.Concurrent;

namespace BotGlobal.Games.Realtime;

public sealed class GameConnectionRegistry
{
    private readonly ConcurrentDictionary<string, ConnectionState> _connections = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<(Guid MembershipId, Guid SessionId), int> _sessionConnections = new();

    public void Connected(string connectionId, Guid membershipId) =>
        _connections[connectionId] = new ConnectionState(membershipId);

    public void Joined(string connectionId, Guid sessionId)
    {
        if (_connections.TryGetValue(connectionId, out var state))
        {
            if (state.SessionIds.TryAdd(sessionId, 0))
            {
                _sessionConnections.AddOrUpdate(
                    (state.MembershipId, sessionId),
                    1,
                    (_, count) => count + 1);
            }
        }
    }

    public bool Unjoined(string connectionId, Guid sessionId)
    {
        if (!_connections.TryGetValue(connectionId, out var state) ||
            !state.SessionIds.TryRemove(sessionId, out _))
        {
            return false;
        }

        return RemoveSessionConnection(state.MembershipId, sessionId);
    }

    public (Guid MembershipId, IReadOnlyList<Guid> SessionIds)? Disconnected(string connectionId)
    {
        if (!_connections.TryRemove(connectionId, out var state))
        {
            return null;
        }

        var disconnectedSessions = new List<Guid>();
        foreach (var sessionId in state.SessionIds.Keys)
        {
            if (RemoveSessionConnection(state.MembershipId, sessionId))
            {
                disconnectedSessions.Add(sessionId);
            }
        }

        return (state.MembershipId, disconnectedSessions);
    }

    private bool RemoveSessionConnection(Guid membershipId, Guid sessionId)
    {
        var key = (membershipId, sessionId);
        var remaining = _sessionConnections.AddOrUpdate(
            key,
            0,
            (_, count) => Math.Max(0, count - 1));
        if (remaining != 0)
        {
            return false;
        }

        _sessionConnections.TryRemove(key, out _);
        return true;
    }

    private sealed class ConnectionState(Guid membershipId)
    {
        public Guid MembershipId { get; } = membershipId;
        public ConcurrentDictionary<Guid, byte> SessionIds { get; } = new();
    }
}
