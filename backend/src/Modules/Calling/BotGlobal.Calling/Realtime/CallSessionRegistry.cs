using System.Collections.Concurrent;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;

namespace BotGlobal.Calling.Realtime;

public sealed class CallSessionRegistry
{
    private readonly ConcurrentDictionary<string, ConnectedParticipant> connections = new(StringComparer.Ordinal);
    private readonly Dictionary<Guid, Session> sessions = [];
    private readonly object gate = new();

    public ConnectedParticipant Connected(string connectionId, ApplicationIdentityDescriptor identity)
    {
        var participant = new ConnectedParticipant(connectionId, identity.MembershipId, identity.ApplicationKey, identity.SubjectId, identity.DisplayName);
        connections[connectionId] = participant;
        return participant;
    }

    public Started Start(string connectionId, CallingParticipantDescriptor callee, DateTimeOffset now, TimeSpan lifetime)
    {
        lock (gate)
        {
            ExpireLocked(now);
            var caller = RequireConnection(connectionId);
            if (!callee.IsActive || !string.Equals(callee.ApplicationKey, caller.ApplicationKey, StringComparison.Ordinal)) throw Error("call_peer_unavailable");
            if (caller.MembershipId == callee.MembershipId) throw Error("call_self_not_allowed");
            if (sessions.Values.Any(x => x.IsLive && x.HasParticipant(caller.MembershipId))) throw Error("call_active_exists");
            if (sessions.Values.Any(x => x.IsLive && x.HasParticipant(callee.MembershipId))) throw Error("call_peer_busy");
            var session = new Session(Guid.NewGuid(), caller.ApplicationKey, caller.MembershipId, callee.MembershipId,
                caller.SubjectId, callee.SubjectId, caller.DisplayName, callee.DisplayName, now, now.Add(lifetime));
            sessions.Add(session.CallId, session);
            return new Started(session, caller, ConnectionsFor(callee.MembershipId, caller.ApplicationKey));
        }
    }

    // Compatibility entry point for the original connected-peer calling contract.
    public Started Start(string connectionId, Guid calleeMembershipId)
    {
        var caller = RequireConnection(connectionId);
        var connected = connections.Values.FirstOrDefault(x => x.MembershipId == calleeMembershipId && x.ApplicationKey == caller.ApplicationKey)
            ?? throw Error("call_peer_unavailable");
        var started = Start(connectionId, new CallingParticipantDescriptor(connected.MembershipId, connected.ApplicationKey,
            connected.SubjectId, connected.DisplayName, true), DateTimeOffset.UtcNow, TimeSpan.FromSeconds(45));
        started.Session.Status = CallStatus.Answered;
        return started;
    }

    public Incoming RequireIncoming(string connectionId, Guid callId, DateTimeOffset now)
    {
        lock (gate)
        {
            ExpireLocked(now);
            var connection = RequireConnection(connectionId);
            var session = RequireSession(callId);
            session.RequireCallee(connection.MembershipId, connection.ApplicationKey);
            if (session.Status != CallStatus.Ringing) throw Error("call_offer_stale");
            return new Incoming(session, connection);
        }
    }

    public Transition Answer(string connectionId, Guid callId, DateTimeOffset now) => TransitionIncoming(connectionId, callId, now, CallStatus.Answered, "call_answer_stale");
    public Transition Reject(string connectionId, Guid callId, DateTimeOffset now) => TransitionIncoming(connectionId, callId, now, CallStatus.Rejected, "call_reject_stale");

    private Transition TransitionIncoming(string connectionId, Guid callId, DateTimeOffset now, CallStatus target, string staleError)
    {
        lock (gate)
        {
            ExpireLocked(now);
            var connection = RequireConnection(connectionId);
            var session = RequireSession(callId);
            session.RequireCallee(connection.MembershipId, connection.ApplicationKey);
            if (session.Status == target) return new Transition(session, false, ConnectionsFor(session.CallerMembershipId, session.ApplicationKey));
            if (session.Status != CallStatus.Ringing) throw Error(staleError);
            session.Status = target;
            session.TerminationReason = target.ToString().ToLowerInvariant();
            return new Transition(session, true, ConnectionsFor(session.CallerMembershipId, session.ApplicationKey));
        }
    }

    public Joined Join(string connectionId, Guid callId, long generation)
    {
        if (generation <= 0) throw Error("call_generation_invalid");
        lock (gate)
        {
            var connection = RequireConnection(connectionId);
            var session = RequireSession(callId);
            session.RequireParticipant(connection.MembershipId, connection.ApplicationKey);
            if (connection.MembershipId == session.CalleeMembershipId && session.Status != CallStatus.Answered) throw Error("call_not_answered");
            if (!session.IsLive) throw Error("call_session_unavailable");
            session.Join(connection, generation);
            return new Joined(session, session.RequireCurrent(connectionId, generation), session.PeerOf(connection.MembershipId));
        }
    }

    public void RequireParticipant(string connectionId, Guid callId) { lock (gate) { var c = RequireConnection(connectionId); RequireSession(callId).RequireParticipant(c.MembershipId, c.ApplicationKey); } }
    public JoinedParticipant RequireCurrent(string connectionId, Guid callId, long generation) { lock (gate) return RequireSession(callId).RequireCurrent(connectionId, generation); }
    public JoinedParticipant? PeerOf(JoinedParticipant participant) { lock (gate) return sessions.TryGetValue(participant.CallId, out var s) && s.IsLive ? s.PeerOf(participant.MembershipId) : null; }

    public Transition End(string connectionId, Guid callId, string? reason = null)
    {
        lock (gate)
        {
            var connection = RequireConnection(connectionId);
            var session = RequireSession(callId);
            session.RequireParticipant(connection.MembershipId, connection.ApplicationKey);
            var changed = session.IsLive;
            if (changed)
            {
                session.Status = connection.MembershipId == session.CallerMembershipId && session.Status == CallStatus.Ringing ? CallStatus.Cancelled : CallStatus.Ended;
                session.TerminationReason = NormalizeReason(reason, session.Status);
            }
            var peer = connection.MembershipId == session.CallerMembershipId ? session.CalleeMembershipId : session.CallerMembershipId;
            var peerConnections = ConnectionsFor(peer, session.ApplicationKey);
            if (changed) session.ClearParticipants();
            return new Transition(session, changed, peerConnections);
        }
    }

    public IReadOnlyList<Session> Expire(DateTimeOffset now) { lock (gate) return ExpireLocked(now); }
    public IReadOnlyList<ConnectedParticipant> ConnectedParticipants(Guid membershipId, string applicationKey)
    { lock (gate) return ConnectionsFor(membershipId, applicationKey); }
    public bool IsOnline(Guid membershipId, string applicationKey)
    {
        lock (gate) return connections.Values.Any(x =>
            x.MembershipId == membershipId &&
            string.Equals(x.ApplicationKey, applicationKey, StringComparison.Ordinal));
    }
    private IReadOnlyList<Session> ExpireLocked(DateTimeOffset now)
    {
        var result = sessions.Values.Where(x => x.Status == CallStatus.Ringing && x.ExpiresAtUtc <= now).ToArray();
        foreach (var session in result)
        {
            session.Status = CallStatus.Expired;
            session.TerminationReason = "expired";
        }
        return result;
    }

    public IReadOnlyList<JoinedParticipant> Disconnected(string connectionId)
    {
        lock (gate)
        {
            connections.TryRemove(connectionId, out _);
            return sessions.Values.Select(x => x.Leave(connectionId)).Where(x => x is not null).Cast<JoinedParticipant>().ToArray();
        }
    }

    private ConnectedParticipant RequireConnection(string id) => connections.TryGetValue(id, out var p) ? p : throw Error("call_connection_unavailable");
    private Session RequireSession(Guid id) => sessions.TryGetValue(id, out var s) ? s : throw Error("call_session_unavailable");
    private ConnectedParticipant[] ConnectionsFor(Guid id, string app) => connections.Values.Where(x => x.MembershipId == id && x.ApplicationKey == app).OrderBy(x => x.ConnectionId, StringComparer.Ordinal).ToArray();
    private static InvalidOperationException Error(string code) => new(code);
    private static string NormalizeReason(string? reason, CallStatus status)
    {
        var normalized = reason?.Trim().ToLowerInvariant();
        return normalized is "local" or "remote" or "rejected" or "busy" or "cancelled" or "missed" or "expired" or "failed"
            ? normalized
            : status.ToString().ToLowerInvariant();
    }

    public enum CallStatus { Ringing, Answered, Rejected, Cancelled, Expired, Ended }
    public sealed record ConnectedParticipant(string ConnectionId, Guid MembershipId, string ApplicationKey, string SubjectId, string DisplayName);
    public sealed record JoinedParticipant(string ConnectionId, Guid CallId, Guid MembershipId, long Generation, bool IsInitiator);
    public sealed record Started(Session Session, ConnectedParticipant Caller, IReadOnlyList<ConnectedParticipant> CalleeConnections)
    {
        public ConnectedParticipant Callee => CalleeConnections.First();
    }
    public sealed record Incoming(Session Session, ConnectedParticipant Callee);
    public sealed record Transition(Session Session, bool Changed, IReadOnlyList<ConnectedParticipant> PeerConnections);
    public sealed record Joined(Session Session, JoinedParticipant Current, JoinedParticipant? Peer);

    public sealed class Session(Guid callId, string applicationKey, Guid callerMembershipId, Guid calleeMembershipId,
        string callerSubjectId, string calleeSubjectId, string callerDisplayName, string calleeDisplayName,
        DateTimeOffset createdAtUtc, DateTimeOffset expiresAtUtc)
    {
        private readonly Dictionary<Guid, JoinedParticipant> participants = [];
        public Guid CallId { get; } = callId;
        public string ApplicationKey { get; } = applicationKey;
        public Guid CallerMembershipId { get; } = callerMembershipId;
        public Guid CalleeMembershipId { get; } = calleeMembershipId;
        public string CallerSubjectId { get; } = callerSubjectId;
        public string CalleeSubjectId { get; } = calleeSubjectId;
        public string CallerDisplayName { get; } = callerDisplayName;
        public string CalleeDisplayName { get; } = calleeDisplayName;
        public DateTimeOffset CreatedAtUtc { get; } = createdAtUtc;
        public DateTimeOffset ExpiresAtUtc { get; } = expiresAtUtc;
        public CallStatus Status { get; set; } = CallStatus.Ringing;
        public string? TerminationReason { get; internal set; }
        public bool IsLive => Status is CallStatus.Ringing or CallStatus.Answered;
        public bool HasParticipant(Guid id) => id == CallerMembershipId || id == CalleeMembershipId;
        public void RequireParticipant(Guid id, string app) { if (!HasParticipant(id) || ApplicationKey != app) throw Error("call_participant_unauthorized"); }
        public void RequireCallee(Guid id, string app) { RequireParticipant(id, app); if (id != CalleeMembershipId) throw Error("call_callee_required"); }
        public void Join(ConnectedParticipant p, long generation) => participants[p.MembershipId] = new JoinedParticipant(p.ConnectionId, CallId, p.MembershipId, generation, p.MembershipId == CallerMembershipId);
        public JoinedParticipant RequireCurrent(string id, long generation) => participants.Values.SingleOrDefault(x => x.ConnectionId == id && x.Generation == generation) ?? throw Error("call_generation_stale");
        public JoinedParticipant? PeerOf(Guid id) => participants.Values.SingleOrDefault(x => x.MembershipId != id);
        public JoinedParticipant? Leave(string id) { var p = participants.Values.SingleOrDefault(x => x.ConnectionId == id); if (p is not null) participants.Remove(p.MembershipId); return p; }
        public void ClearParticipants() => participants.Clear();
    }
}
