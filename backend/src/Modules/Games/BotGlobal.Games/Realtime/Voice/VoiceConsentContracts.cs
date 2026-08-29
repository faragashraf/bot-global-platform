namespace BotGlobal.Games.Realtime.Voice;

public sealed record VoiceConsentRequest(Guid SessionId, int MatchNumber);
public sealed record VoiceConsentAction(Guid SessionId, int MatchNumber, Guid RequestId);
public sealed record VoiceConsentResult(Guid SessionId, int MatchNumber, Guid RequestId,
    Guid RequesterMembershipId, Guid RecipientMembershipId, DateTimeOffset ExpiresAtUtc, bool Created);
public sealed record VoiceConsentStateResult(bool Active, Guid SessionId, int MatchNumber, Guid RequestId,
    Guid RequesterMembershipId, Guid RecipientMembershipId, DateTimeOffset ExpiresAtUtc, string State);
public sealed record VoiceConsentEvent(Guid SessionId, int MatchNumber, Guid RequestId,
    Guid RequesterMembershipId, string RequesterConnectionId, Guid RecipientMembershipId,
    string RecipientConnectionId, DateTimeOffset ExpiresAtUtc, string State, string? Reason = null);
public sealed record VoiceUnavailableRequest(Guid SessionId, int MatchNumber, Guid RequestId, string Reason);
