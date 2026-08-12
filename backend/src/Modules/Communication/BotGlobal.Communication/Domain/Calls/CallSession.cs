using BotGlobal.Communication.Domain.Identity;
namespace BotGlobal.Communication.Domain.Calls;

public enum CommunicationCallKind
{
    Voice = 1,
    Video = 2
}

public enum CallSessionStatus
{
    Ringing = 1,
    Active = 2,
    Ended = 3
}

public enum CallSessionEndReason
{
    Ended = 1,
    Rejected = 2,
    Cancelled = 3,
    Busy = 4,
    CallsDisabled = 5,
    Failed = 6
}

public sealed class CallSession
{
    public const int ClientCallIdMaxLength = 100;

    private CallSession()
    {
    }

    private CallSession(
        Guid id,
        Guid? conversationId,
        string callerUserId,
        string calleeUserId,
        string clientCallId,
        CommunicationCallKind kind,
        DateTimeOffset startedAtUtc)
    {
        if (id == Guid.Empty)
        {
            throw new ArgumentException(
                "Call id is required.",
                nameof(id));
        }

        var normalizedCallerUserId = ExternalUserId.Normalize(
            callerUserId,
            nameof(callerUserId));
        var normalizedCalleeUserId = ExternalUserId.Normalize(
            calleeUserId,
            nameof(calleeUserId));

        if (string.Equals(
                normalizedCallerUserId,
                normalizedCalleeUserId,
                StringComparison.Ordinal))
        {
            throw new ArgumentException(
                "Caller and callee must be different users.",
                nameof(calleeUserId));
        }

        ArgumentException.ThrowIfNullOrWhiteSpace(
            clientCallId);

        var normalizedClientCallId = clientCallId.Trim();

        if (normalizedClientCallId.Length > ClientCallIdMaxLength)
        {
            throw new ArgumentException(
                $"Client call id cannot exceed {ClientCallIdMaxLength} characters.",
                nameof(clientCallId));
        }

        Id = id;
        ConversationId = conversationId;
        CallerUserId = normalizedCallerUserId;
        CalleeUserId = normalizedCalleeUserId;
        ClientCallId = normalizedClientCallId;
        Kind = kind;
        Status = CallSessionStatus.Ringing;
        StartedAtUtc = startedAtUtc;
    }

    public Guid Id { get; private set; }

    public Guid? ConversationId { get; private set; }

    public string CallerUserId { get; private set; } = string.Empty;

    public string CalleeUserId { get; private set; } = string.Empty;

    public string ClientCallId { get; private set; } = string.Empty;

    public CommunicationCallKind Kind { get; private set; }

    public CallSessionStatus Status { get; private set; }

    public CallSessionEndReason? EndReason { get; private set; }

    public DateTimeOffset StartedAtUtc { get; private set; }

    public DateTimeOffset? AnsweredAtUtc { get; private set; }

    public DateTimeOffset? EndedAtUtc { get; private set; }

    public static CallSession Start(
        Guid? conversationId,
        string callerUserId,
        string calleeUserId,
        string clientCallId,
        CommunicationCallKind kind,
        DateTimeOffset startedAtUtc)
    {
        return new CallSession(
            Guid.NewGuid(),
            conversationId,
            callerUserId,
            calleeUserId,
            clientCallId,
            kind,
            startedAtUtc);
    }

    public void Accept(DateTimeOffset answeredAtUtc)
    {
        if (Status != CallSessionStatus.Ringing)
        {
            throw new InvalidOperationException(
                "Only a ringing call can be accepted.");
        }

        if (answeredAtUtc < StartedAtUtc)
        {
            throw new ArgumentOutOfRangeException(
                nameof(answeredAtUtc),
                "Answer time cannot predate call start.");
        }

        Status = CallSessionStatus.Active;
        AnsweredAtUtc = answeredAtUtc;
    }

    public void End(
        CallSessionEndReason reason,
        DateTimeOffset endedAtUtc)
    {
        if (Status == CallSessionStatus.Ended)
        {
            return;
        }

        if (endedAtUtc < StartedAtUtc)
        {
            throw new ArgumentOutOfRangeException(
                nameof(endedAtUtc),
                "End time cannot predate call start.");
        }

        if (AnsweredAtUtc is not null
            && endedAtUtc < AnsweredAtUtc.Value)
        {
            throw new ArgumentOutOfRangeException(
                nameof(endedAtUtc),
                "End time cannot predate answer time.");
        }

        Status = CallSessionStatus.Ended;
        EndReason = reason;
        EndedAtUtc = endedAtUtc;
    }
}
