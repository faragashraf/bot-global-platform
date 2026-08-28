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

    public (Guid MembershipId, IReadOnlyList<Guid> SessionIds)? Disconnected(string connectionId)
    {
        if (!_connections.TryRemove(connectionId, out var state))
        {
            return null;
        }

        var disconnectedSessions = new List<Guid>();
        foreach (var sessionId in state.SessionIds.Keys)
        {
            var key = (state.MembershipId, sessionId);
            var remaining = _sessionConnections.AddOrUpdate(
                key,
                0,
                (_, count) => Math.Max(0, count - 1));
            if (remaining == 0)
            {
                _sessionConnections.TryRemove(key, out _);
                disconnectedSessions.Add(sessionId);
            }
        }

        return (state.MembershipId, disconnectedSessions);
    }

    private sealed class ConnectionState(Guid membershipId)
    {
        public Guid MembershipId { get; } = membershipId;
        public ConcurrentDictionary<Guid, byte> SessionIds { get; } = new();
    }
}
