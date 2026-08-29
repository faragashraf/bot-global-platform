using System.Collections.Concurrent;

namespace BotGlobal.Games.Realtime.Voice;

public sealed class VoiceConnectionRegistry
{
    private readonly ConcurrentDictionary<string, Participant> _byConnection = new(StringComparer.Ordinal);
    private readonly object _gate = new();

    public (Participant Current, Participant? Peer) Join(string connectionId, Guid sessionId, Guid membershipId, long generation, bool isInitiator)
    {
        lock (_gate)
        {
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
