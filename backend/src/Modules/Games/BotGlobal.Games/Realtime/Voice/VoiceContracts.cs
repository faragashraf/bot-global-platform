namespace BotGlobal.Games.Realtime.Voice;

public sealed record VoiceJoinRequest(Guid SessionId, long Generation);
public sealed record VoiceJoinResult(Guid SessionId, long Generation, Guid ParticipantId, string ConnectionId,
    bool IsInitiator, bool PeerPresent, Guid? PeerParticipantId, string? PeerConnectionId);
public sealed record VoiceDescriptionRequest(Guid SessionId, long Generation, string SessionDescription);
public sealed record VoiceIceCandidateRequest(Guid SessionId, long Generation, string Candidate, string? SdpMid, int SdpMLineIndex);
public sealed record VoiceMuteRequest(Guid SessionId, long Generation, bool Muted);
public sealed record VoicePeerEvent(Guid SessionId, long ReceiverGeneration, Guid ParticipantId,
    string ParticipantConnectionId, string ReceiverConnectionId, long ParticipantGeneration, bool IsInitiator);
public sealed record VoiceDescriptionEvent(Guid SessionId, long ReceiverGeneration, Guid ParticipantId,
    string ParticipantConnectionId, string ReceiverConnectionId, long ParticipantGeneration, string SessionDescription);
public sealed record VoiceIceCandidateEvent(Guid SessionId, long ReceiverGeneration, Guid ParticipantId,
    string ParticipantConnectionId, string ReceiverConnectionId, long ParticipantGeneration,
    string Candidate, string? SdpMid, int SdpMLineIndex);
public sealed record VoiceMuteEvent(Guid SessionId, long ReceiverGeneration, Guid ParticipantId,
    string ParticipantConnectionId, string ReceiverConnectionId, long ParticipantGeneration, bool Muted);
public sealed record VoiceIceServer(IReadOnlyList<string> Urls, string? Username, string? Credential);
public sealed record VoiceIceConfiguration(IReadOnlyList<VoiceIceServer> Servers, DateTimeOffset ExpiresAtUtc);
