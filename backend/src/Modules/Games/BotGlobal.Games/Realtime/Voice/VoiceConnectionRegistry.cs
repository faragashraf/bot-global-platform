using System.Collections.Concurrent;

namespace BotGlobal.Games.Realtime.Voice;

public sealed class VoiceConnectionRegistry
{
    private readonly ConcurrentDictionary<string, Participant> _byConnection = new(StringComparer.Ordinal);
    private readonly object _gate = new();

    private readonly HashSet<Guid> _revokedMemberships = [];
    private readonly HashSet<Guid> _revokedSessions = [];

    private readonly Dictionary<Guid, HashSet<RevokedPeer>> _pendingRevocations = [];

    public sealed record RevokedPeer(Participant Departed, Participant Receiver);

    public void CompleteRevocation(Guid membershipId, RevokedPeer notification)
    {
        lock (_gate)
        {
            if (!_pendingRevocations.TryGetValue(membershipId, out var pending)) return;
            pending.Remove(notification);
            if (pending.Count == 0) _pendingRevocations.Remove(membershipId);
        }
    }

    public IReadOnlyList<RevokedPeer> RevokeMembership(Guid membershipId, IReadOnlyCollection<Guid> ownedSessionIds)
    {
        lock (_gate)
        {
            _revokedMemberships.Add(membershipId);
            _revokedSessions.UnionWith(ownedSessionIds);
            if (!_pendingRevocations.TryGetValue(membershipId, out var pending))
                _pendingRevocations[membershipId] = pending = [];
            var departed = _byConnection.Values.Where(participant =>
                participant.MembershipId == membershipId || ownedSessionIds.Contains(participant.SessionId)).ToArray();
            // Snapshot both sides before removing anyone, including both sides of an owned session.
            foreach (var participant in departed)
            {
                var peer = ResolvePeer(participant);
                if (peer is not null && peer.MembershipId != membershipId)
                    pending.Add(new RevokedPeer(participant, peer));
            }
            foreach (var participant in departed)
                _byConnection.TryRemove(participant.ConnectionId, out _);
            return pending.ToArray();
        }
    }

    public (Participant Current, Participant? Peer) Join(string connectionId, Guid sessionId, Guid membershipId, long generation, bool isInitiator)
    {
        lock (_gate)
        {
            if (_revokedMemberships.Contains(membershipId) || _revokedSessions.Contains(sessionId))
                throw new InvalidOperationException("The voice membership or session has been revoked.");
            foreach (var stale in _byConnection.Values.Where(x => x.SessionId == sessionId && x.MembershipId == membershipId && x.ConnectionId != connectionId).ToArray())
                _byConnection.TryRemove(stale.ConnectionId, out _);
            var current = new Participant(connectionId, sessionId, membershipId, generation, isInitiator);
            _byConnection[connectionId] = current;
            return (current, ResolvePeer(current));
        }
    }

    public Participant RequireCurrent(string connectionId, Guid sessionId, Guid membershipId, long generation)
    {
        if (!_byConnection.TryGetValue(connectionId, out var participant) || participant.SessionId != sessionId ||
            participant.MembershipId != membershipId || participant.Generation != generation)
            throw new InvalidOperationException("The voice signaling generation is stale or not joined.");
        return participant;
    }

    public Participant? PeerOf(Participant participant)
    {
        lock (_gate) return ResolvePeer(participant);
    }

    private Participant? ResolvePeer(Participant participant) =>
        _byConnection.Values.SingleOrDefault(x =>
            x.SessionId == participant.SessionId &&
            !string.Equals(x.ConnectionId, participant.ConnectionId, StringComparison.Ordinal) &&
            x.MembershipId != participant.MembershipId);

    public Participant? Leave(string connectionId)
    {
        lock (_gate) return _byConnection.TryRemove(connectionId, out var participant) ? participant : null;
    }

    public sealed record Participant(string ConnectionId, Guid SessionId, Guid MembershipId, long Generation, bool IsInitiator);
}
