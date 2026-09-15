using System.Collections.Concurrent;

namespace BotGlobal.Games.Realtime;

public sealed class GameConnectionRegistry
{
    private readonly ConcurrentDictionary<string, ConnectionState> _connections = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<(Guid MembershipId, Guid SessionId), int> _sessionConnections = new();
    private long _sequence;
    private long _revocationSequence;
    private readonly object _gate = new();
    private readonly HashSet<Guid> _revokedMemberships = [];
    private readonly HashSet<Guid> _revokedSessions = [];

    public bool IsRevoked(Guid membershipId)
    {
        lock (_gate) return _revokedMemberships.Contains(membershipId);
    }

    public bool IsRevoked(Guid membershipId, Guid sessionId)
    {
        lock (_gate) return _revokedMemberships.Contains(membershipId) || _revokedSessions.Contains(sessionId);
    }

    private readonly HashSet<RevokedPresence> _pendingRevocations = [];
    private readonly Dictionary<RevokedPresence, RevokedPresenceRetry> _revocationRetries = [];

    public sealed record RevokedPresence(
        long RecordId,
        Guid MembershipId,
        string ConnectionId,
        Guid SessionId);

    public RevokedPresence RecordLateRevokedPresence(
        Guid membershipId,
        string connectionId,
        Guid sessionId)
    {
        lock (_gate)
        {
            if (!_revokedMemberships.Contains(membershipId) && !_revokedSessions.Contains(sessionId))
                throw new InvalidOperationException("The game membership or session is not revoked.");
            var route = NewRevokedPresence(membershipId, connectionId, sessionId);
            _pendingRevocations.Add(route);
            _revocationRetries[route] = new(0, DateTimeOffset.MinValue);
            return route;
        }
    }

    public void CompleteRevokedPresence(RevokedPresence route)
    {
        lock (_gate)
        {
            _pendingRevocations.Remove(route);
            _revocationRetries.Remove(route);
        }
    }

    public IReadOnlyList<RevokedPresence> PendingRevokedPresence(int maximumCount)
    {
        if (maximumCount <= 0) return [];
        lock (_gate)
            return _pendingRevocations
                .OrderBy(route => route.RecordId)
                .Take(maximumCount)
                .ToArray();
    }

    public IReadOnlyList<RevokedPresence> PendingRevokedPresenceDue(
        int maximumCount,
        DateTimeOffset now)
    {
        if (maximumCount <= 0) return [];
        lock (_gate)
            return _pendingRevocations
                .Where(route => _revocationRetries.GetValueOrDefault(route).NextAttemptAtUtc <= now)
                .OrderBy(route => route.RecordId)
                .Take(maximumCount)
                .ToArray();
    }

    public void RecordRevokedPresenceFailure(
        RevokedPresence route,
        DateTimeOffset now)
    {
        lock (_gate)
            if (_pendingRevocations.Contains(route))
            {
                var retry = _revocationRetries.GetValueOrDefault(route);
                // Eight is a saturated backoff tier, not a terminal state.
                var failedAttempts = Math.Min(retry.FailedAttempts + 1, 8);
                _revocationRetries[route] = new(
                    failedAttempts,
                    now.Add(RetryDelay(failedAttempts)));
            }
    }

    public IReadOnlyList<RevokedPresence> RevokeMembership(Guid membershipId, IReadOnlyCollection<Guid> ownedSessionIds)
    {
        lock (_gate)
        {
            _revokedMemberships.Add(membershipId);
            _revokedSessions.UnionWith(ownedSessionIds);
            foreach (var entry in _connections.ToArray())
            {
                foreach (var sessionId in entry.Value.SessionIds.Keys.Where(sessionId =>
                    entry.Value.MembershipId == membershipId || _revokedSessions.Contains(sessionId)))
                {
                    var route = NewRevokedPresence(entry.Value.MembershipId, entry.Key, sessionId);
                    _pendingRevocations.Add(route);
                    _revocationRetries[route] = new(0, DateTimeOffset.MinValue);
                }
                if (entry.Value.MembershipId == membershipId) Disconnected(entry.Key);
                else foreach (var sessionId in ownedSessionIds) Unjoined(entry.Key, sessionId);
            }
            // Keep removals until acknowledged so failed SignalR cleanup is retryable.
            return _pendingRevocations.Where(route =>
                route.MembershipId == membershipId || ownedSessionIds.Contains(route.SessionId)).ToArray();
        }
    }

    public void Connected(string connectionId, Guid membershipId)
    {
        lock (_gate)
        {
            if (_revokedMemberships.Contains(membershipId))
                throw new InvalidOperationException("The game membership has been revoked.");
            _connections[connectionId] = new ConnectionState(membershipId, Interlocked.Increment(ref _sequence));
        }
    }

    public string? ResolveOpponentConnection(string senderConnectionId, Guid sessionId,
        Guid senderMembershipId, Guid opponentMembershipId) =>
        _connections
            .Where(x =>
                !string.Equals(x.Key, senderConnectionId, StringComparison.Ordinal) &&
                x.Value.MembershipId != senderMembershipId &&
                x.Value.MembershipId == opponentMembershipId &&
                x.Value.SessionIds.ContainsKey(sessionId))
            .OrderByDescending(x => x.Value.Sequence)
            .Select(x => x.Key)
            .FirstOrDefault();

    public string? ResolveParticipantConnection(Guid sessionId, Guid membershipId) =>
        _connections
            .Where(x => x.Value.MembershipId == membershipId && x.Value.SessionIds.ContainsKey(sessionId))
            .OrderByDescending(x => x.Value.Sequence)
            .Select(x => x.Key)
            .FirstOrDefault();

    public void Joined(string connectionId, Guid sessionId)
    {
        lock (_gate)
        {
            if (_revokedSessions.Contains(sessionId))
                throw new InvalidOperationException("The game session has been deleted.");
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
    }

    public bool Unjoined(string connectionId, Guid sessionId)
    {
        lock (_gate)
        {
            if (!_connections.TryGetValue(connectionId, out var state) ||
                !state.SessionIds.TryRemove(sessionId, out _))
            {
                return false;
            }

            return RemoveSessionConnection(state.MembershipId, sessionId);

        }
    }

    public (Guid MembershipId, IReadOnlyList<Guid> SessionIds)? Disconnected(string connectionId)
    {
        lock (_gate)
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

    private RevokedPresence NewRevokedPresence(Guid membershipId, string connectionId, Guid sessionId) =>
        new(++_revocationSequence, membershipId, connectionId, sessionId);

    private static TimeSpan RetryDelay(int failedAttempts) => TimeSpan.FromSeconds(
        Math.Min(30, 1 << Math.Min(failedAttempts, 5)));

    private readonly record struct RevokedPresenceRetry(int FailedAttempts, DateTimeOffset NextAttemptAtUtc);

    private sealed class ConnectionState(Guid membershipId, long sequence)
    {
        public Guid MembershipId { get; } = membershipId;
        public long Sequence { get; } = sequence;
        public ConcurrentDictionary<Guid, byte> SessionIds { get; } = new();
    }
}
