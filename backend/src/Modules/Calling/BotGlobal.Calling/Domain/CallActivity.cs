namespace BotGlobal.Calling.Domain;

public enum DurableCallState { Ringing = 1, Active = 2, Terminal = 3 }
public enum DurableCallOutcome { Completed = 1, Rejected = 2, Missed = 3, Cancelled = 4, Expired = 5, Failed = 6 }
public enum CallParticipantRole { Initiator = 1, Recipient = 2 }
public enum UsagePeriodResetReason { Initial = 1, Manual = 2, Scheduled = 3 }

public sealed class CallRecord
{
    private CallRecord() { }
    public CallRecord(Guid callId, Guid applicationId, string applicationKey, DateTimeOffset createdAtUtc)
    {
        if (callId == Guid.Empty || applicationId == Guid.Empty) throw new ArgumentException("Call and application identifiers are required.");
        Id = callId;
        ApplicationId = applicationId;
        ApplicationKey = applicationKey.Trim().ToLowerInvariant();
        CreatedAtUtc = createdAtUtc;
    }
    public Guid Id { get; private set; }
    public Guid ApplicationId { get; private set; }
    public string ApplicationKey { get; private set; } = string.Empty;
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset? AnsweredAtUtc { get; private set; }
    public DateTimeOffset? EndedAtUtc { get; private set; }
    public DurableCallState State { get; private set; } = DurableCallState.Ringing;
    public DurableCallOutcome? Outcome { get; private set; }
    public string? EndReason { get; private set; }
    public ICollection<CallParticipantRecord> Participants { get; } = new List<CallParticipantRecord>();
    public ICollection<CallUsageReport> UsageReports { get; } = new List<CallUsageReport>();

    public void Answer(DateTimeOffset at)
    {
        if (State == DurableCallState.Terminal) return;
        AnsweredAtUtc ??= at;
        State = DurableCallState.Active;
    }
    public void Finish(DurableCallOutcome outcome, string reason, DateTimeOffset at)
    {
        if (State == DurableCallState.Terminal) return;
        State = DurableCallState.Terminal;
        Outcome = outcome;
        EndReason = reason.Trim().ToLowerInvariant();
        EndedAtUtc = at;
    }
}

public sealed class CallParticipantRecord
{
    private CallParticipantRecord() { }
    public CallParticipantRecord(Guid callId, Guid membershipId, CallParticipantRole role, string displayNameSnapshot)
    {
        Id = Guid.NewGuid();
        CallId = callId;
        MembershipId = membershipId;
        Role = role;
        DisplayNameSnapshot = displayNameSnapshot.Trim();
    }
    public Guid Id { get; private set; }
    public Guid CallId { get; private set; }
    public Guid MembershipId { get; private set; }
    public CallParticipantRole Role { get; private set; }
    public string DisplayNameSnapshot { get; private set; } = string.Empty;
    public DateTimeOffset? JoinedAtUtc { get; private set; }
    public DateTimeOffset? AnsweredAtUtc { get; private set; }
    public void MarkAnswered(DateTimeOffset at) { AnsweredAtUtc ??= at; JoinedAtUtc ??= at; }
    public void MarkJoined(DateTimeOffset at) => JoinedAtUtc ??= at;
}

public sealed class CallUsageReport
{
    private CallUsageReport() { }
    public CallUsageReport(Guid callId, Guid membershipId, long bytesSent, long bytesReceived, long connectedDurationSeconds, DateTimeOffset finalizedAtUtc)
    {
        Id = Guid.NewGuid(); CallId = callId; MembershipId = membershipId;
        BytesSent = bytesSent; BytesReceived = bytesReceived;
        ConnectedDurationSeconds = connectedDurationSeconds; FinalizedAtUtc = finalizedAtUtc;
    }
    public Guid Id { get; private set; }
    public Guid CallId { get; private set; }
    public Guid MembershipId { get; private set; }
    public long BytesSent { get; private set; }
    public long BytesReceived { get; private set; }
    public long ConnectedDurationSeconds { get; private set; }
    public DateTimeOffset FinalizedAtUtc { get; private set; }
}

public sealed class UsageCounterPeriod
{
    private UsageCounterPeriod() { }
    public UsageCounterPeriod(Guid applicationId, Guid membershipId, DateTimeOffset startedAtUtc, UsagePeriodResetReason reason)
    {
        Id = Guid.NewGuid(); ApplicationId = applicationId; MembershipId = membershipId;
        StartedAtUtc = startedAtUtc; ResetReason = reason;
    }
    public Guid Id { get; private set; }
    public Guid ApplicationId { get; private set; }
    public Guid MembershipId { get; private set; }
    public DateTimeOffset StartedAtUtc { get; private set; }
    public DateTimeOffset? EndedAtUtc { get; private set; }
    public UsagePeriodResetReason ResetReason { get; private set; }
    public DateTimeOffset? ScheduledResetAtUtc { get; private set; }
    public DateTime? ScheduledLocalDateTime { get; private set; }
    public string? ScheduledTimeZoneId { get; private set; }
    public void Close(DateTimeOffset at) => EndedAtUtc ??= at;
    public void Schedule(DateTime localDateTime, string timeZoneId, DateTimeOffset atUtc)
    { ScheduledLocalDateTime = DateTime.SpecifyKind(localDateTime, DateTimeKind.Unspecified); ScheduledTimeZoneId = timeZoneId; ScheduledResetAtUtc = atUtc; }
    public void ClearSchedule() { ScheduledLocalDateTime = null; ScheduledTimeZoneId = null; ScheduledResetAtUtc = null; }
}
