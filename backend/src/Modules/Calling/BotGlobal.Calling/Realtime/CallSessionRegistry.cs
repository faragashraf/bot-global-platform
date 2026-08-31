using System.Collections.Concurrent;
using BotGlobal.Contracts.Mobile;

namespace BotGlobal.Calling.Realtime;

public sealed class CallSessionRegistry
{
    private readonly ConcurrentDictionary<string, ConnectedParticipant> _connections = new(StringComparer.Ordinal);
    private readonly Dictionary<Guid, Session> _sessions = [];
    private readonly object _gate = new();

    public ConnectedParticipant Connected(string connectionId, ApplicationIdentityDescriptor identity)
    {
        var participant = new ConnectedParticipant(connectionId, identity.MembershipId,
            identity.ApplicationKey, identity.DisplayName);
        _connections[connectionId] = participant;
        return participant;
    }

    public Started Start(string connectionId, Guid calleeMembershipId)
    {
        lock (_gate)
        {
            var caller = RequireConnection(connectionId);
            if (caller.MembershipId == calleeMembershipId)
                throw new InvalidOperationException("call_self_not_allowed");
            if (_sessions.Values.Any(session => session.IsActive && session.HasParticipant(caller.MembershipId)))
                throw new InvalidOperationException("call_active_exists");
            var callee = _connections.Values
                .Where(candidate => candidate.MembershipId == calleeMembershipId &&
                    string.Equals(candidate.ApplicationKey, caller.ApplicationKey, StringComparison.Ordinal))
                .OrderBy(candidate => candidate.ConnectionId, StringComparer.Ordinal)
                .FirstOrDefault()
                ?? throw new InvalidOperationException("call_peer_unavailable");
            if (_sessions.Values.Any(session => session.IsActive && session.HasParticipant(callee.MembershipId)))
                throw new InvalidOperationException("call_peer_busy");

            var session = new Session(Guid.NewGuid(), caller.ApplicationKey, caller.MembershipId,
                callee.MembershipId, caller.DisplayName, callee.DisplayName);
            _sessions.Add(session.CallId, session);
            return new Started(session, caller, callee);
        }
    }

    public Joined Join(string connectionId, Guid callId, long generation)
    {
        if (generation <= 0) throw new InvalidOperationException("call_generation_invalid");
        lock (_gate)
        {
            var connection = RequireConnection(connectionId);
            var session = RequireSession(callId);
            session.RequireParticipant(connection.MembershipId, connection.ApplicationKey);
            session.Join(connection, generation);
            return new Joined(session, session.RequireCurrent(connectionId, generation), session.PeerOf(connection.MembershipId));
        }
    }

    public void RequireParticipant(string connectionId, Guid callId)
    {
        lock (_gate)
        {
            var connection = RequireConnection(connectionId);
            RequireSession(callId).RequireParticipant(connection.MembershipId, connection.ApplicationKey);
        }
    }

    public JoinedParticipant RequireCurrent(string connectionId, Guid callId, long generation)
    {
        lock (_gate) return RequireSession(callId).RequireCurrent(connectionId, generation);
    }

    public JoinedParticipant? PeerOf(JoinedParticipant participant)
    {
        lock (_gate)
        {
            return _sessions.TryGetValue(participant.CallId, out var session) && session.IsActive
                ? session.PeerOf(participant.MembershipId)
                : null;
        }
    }

    public (Session Session, JoinedParticipant? Peer) End(string connectionId, Guid callId)
    {
        lock (_gate)
        {
            var connection = RequireConnection(connectionId);
            var session = RequireSession(callId);
            session.RequireParticipant(connection.MembershipId, connection.ApplicationKey);
            var peer = session.PeerOf(connection.MembershipId);
            session.IsActive = false;
            _sessions.Remove(callId);
            return (session, peer);
        }
    }

    public IReadOnlyList<JoinedParticipant> Disconnected(string connectionId)
    {
        lock (_gate)
        {
            _connections.TryRemove(connectionId, out _);
            return _sessions.Values
                .Select(session => session.Leave(connectionId))
                .Where(participant => participant is not null)
                .Cast<JoinedParticipant>()
                .ToArray();
        }
    }

    private ConnectedParticipant RequireConnection(string connectionId) =>
        _connections.TryGetValue(connectionId, out var participant)
            ? participant
            : throw new InvalidOperationException("call_connection_unavailable");

    private Session RequireSession(Guid callId) =>
        _sessions.TryGetValue(callId, out var session) && session.IsActive
            ? session
            : throw new InvalidOperationException("call_session_unavailable");

    public sealed record ConnectedParticipant(string ConnectionId, Guid MembershipId, string ApplicationKey, string DisplayName);
    public sealed record JoinedParticipant(string ConnectionId, Guid CallId, Guid MembershipId, long Generation, bool IsInitiator);
    public sealed record Started(Session Session, ConnectedParticipant Caller, ConnectedParticipant Callee);
    public sealed record Joined(Session Session, JoinedParticipant Current, JoinedParticipant? Peer);

    public sealed class Session(
        Guid callId,
        string applicationKey,
        Guid callerMembershipId,
        Guid calleeMembershipId,
        string callerDisplayName,
        string calleeDisplayName)
    {
        private readonly Dictionary<Guid, JoinedParticipant> _participants = [];
        public Guid CallId { get; } = callId;
        public string ApplicationKey { get; } = applicationKey;
        public Guid CallerMembershipId { get; } = callerMembershipId;
        public Guid CalleeMembershipId { get; } = calleeMembershipId;
        public string CallerDisplayName { get; } = callerDisplayName;
        public string CalleeDisplayName { get; } = calleeDisplayName;
        public bool IsActive { get; set; } = true;

        public bool HasParticipant(Guid membershipId) =>
            membershipId == CallerMembershipId || membershipId == CalleeMembershipId;

        public void RequireParticipant(Guid membershipId, string applicationKey)
        {
            if (!IsActive || !HasParticipant(membershipId) ||
                !string.Equals(ApplicationKey, applicationKey, StringComparison.Ordinal))
                throw new InvalidOperationException("call_participant_unauthorized");
        }

        public void Join(ConnectedParticipant participant, long generation)
        {
            _participants[participant.MembershipId] = new JoinedParticipant(
                participant.ConnectionId, CallId, participant.MembershipId, generation,
                participant.MembershipId == CallerMembershipId);
        }

        public JoinedParticipant RequireCurrent(string connectionId, long generation) =>
            _participants.Values.SingleOrDefault(participant =>
                participant.ConnectionId == connectionId && participant.Generation == generation)
            ?? throw new InvalidOperationException("call_generation_stale");

        public JoinedParticipant? PeerOf(Guid membershipId) =>
            _participants.Values.SingleOrDefault(participant => participant.MembershipId != membershipId);

        public JoinedParticipant? Leave(string connectionId)
        {
            var participant = _participants.Values.SingleOrDefault(value => value.ConnectionId == connectionId);
            if (participant is not null) _participants.Remove(participant.MembershipId);
            return participant;
        }
    }
}
