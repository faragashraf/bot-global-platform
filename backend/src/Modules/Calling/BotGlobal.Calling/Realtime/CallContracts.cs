namespace BotGlobal.Calling.Realtime;

public sealed record StartOutgoingCallRequest(Guid CalleeMembershipId);
public sealed record StartedCallResult(Guid CallId, Guid CalleeMembershipId, string CalleeDisplayName);
public sealed record CallableParticipantResult(Guid MembershipId, string DisplayName);
public sealed record CallOfferedEvent(Guid CallId, string ApplicationContext, Guid CallerMembershipId, string CallerDisplayName);
public sealed record IncomingCallLookupRequest(Guid CallId);
public sealed record IncomingCallResult(Guid CallId, string ApplicationContext, Guid CallerMembershipId, string CallerDisplayName, DateTimeOffset ExpiresAtUtc);
public sealed record AnswerCallRequest(Guid CallId);
public sealed record RejectCallRequest(Guid CallId);
public sealed record CallStateEvent(Guid CallId, string State);
public sealed record JoinCallRequest(Guid CallId, long Generation);
public sealed record JoinCallResult(Guid CallId, long Generation, Guid ParticipantId, string ConnectionId,
    bool IsInitiator, bool PeerPresent, Guid? PeerParticipantId, string? PeerConnectionId);
public sealed record CallDescriptionRequest(Guid CallId, long Generation, string SessionDescription);
public sealed record CallIceCandidateRequest(Guid CallId, long Generation, string Candidate, string? SdpMid, int SdpMLineIndex);
public sealed record CallMuteRequest(Guid CallId, long Generation, bool Muted);
public sealed record EndCallRequest(Guid CallId, string Reason);
public sealed record CallPeerEvent(Guid CallId, long ReceiverGeneration, Guid ParticipantId,
    string ParticipantConnectionId, string ReceiverConnectionId, long ParticipantGeneration, bool IsInitiator);
public sealed record CallDescriptionEvent(Guid CallId, long ReceiverGeneration, Guid ParticipantId,
    string ParticipantConnectionId, string ReceiverConnectionId, long ParticipantGeneration, string SessionDescription);
public sealed record CallIceCandidateEvent(Guid CallId, long ReceiverGeneration, Guid ParticipantId,
    string ParticipantConnectionId, string ReceiverConnectionId, long ParticipantGeneration,
    string Candidate, string? SdpMid, int SdpMLineIndex);
public sealed record CallMuteEvent(Guid CallId, long ReceiverGeneration, Guid ParticipantId,
    string ParticipantConnectionId, string ReceiverConnectionId, long ParticipantGeneration, bool Muted);
public sealed record CallEndedEvent(Guid CallId, string Reason);
public sealed record CallingIceServer(IReadOnlyList<string> Urls, string? Username, string? Credential);
public sealed record CallingIceConfiguration(IReadOnlyList<CallingIceServer> Servers, DateTimeOffset ExpiresAtUtc);
