namespace BotGlobal.Communication.Contracts.Calls;

public enum CallKind
{
    Voice = 1,
    Video = 2
}

public enum CallEndReason
{
    Ended = 1,
    Rejected = 2,
    Cancelled = 3,
    Busy = 4,
    CallsDisabled = 5,
    Failed = 6
}

public sealed record CommunicationPreferences(
    bool AllowVoiceCalls,
    bool AllowVideoCalls);

public sealed record StartCallRequest(
    string TargetUserId,
    string ClientCallId,
    CallKind Kind);

public sealed record IncomingCallEvent(
    string CallId,
    string CallerUserId,
    CallKind Kind,
    DateTimeOffset StartedAtUtc);

public sealed record CallAcceptedEvent(
    string CallId,
    string UserId,
    DateTimeOffset AcceptedAtUtc);

public sealed record CallRejectedEvent(
    string CallId,
    string UserId,
    DateTimeOffset RejectedAtUtc);

public sealed record CallEndedEvent(
    string CallId,
    CallEndReason Reason,
    DateTimeOffset EndedAtUtc);

public sealed record WebRtcOfferEvent(
    string CallId,
    string FromUserId,
    string Sdp);

public sealed record WebRtcAnswerEvent(
    string CallId,
    string FromUserId,
    string Sdp);

public sealed record IceCandidateEvent(
    string CallId,
    string FromUserId,
    string Candidate);
