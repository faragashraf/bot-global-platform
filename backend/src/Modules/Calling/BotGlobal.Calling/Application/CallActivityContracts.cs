namespace BotGlobal.Calling.Application;

public sealed record CallHistoryPage(IReadOnlyList<CallHistoryItem> Items, int Page, int PageSize, bool HasMore);
public sealed record CallHistoryItem(Guid CallId, string Direction, string ParticipantDisplayName,
    string? Outcome, DateTimeOffset StartedAtUtc, long? ConnectedDurationSeconds, long? TotalBytes);
public sealed record CallHistoryDetail(Guid CallId, string Direction, IReadOnlyList<string> ParticipantDisplayNames,
    string? Outcome, string? EndReason, DateTimeOffset StartedAtUtc, DateTimeOffset? AnsweredAtUtc,
    DateTimeOffset? EndedAtUtc, long? RingingDurationSeconds, long? ConnectedDurationSeconds, long? BytesSent, long? BytesReceived);
public sealed record UsageSummary(long BytesSent, long BytesReceived, long ConnectedDurationSeconds);
public sealed record UsagePeriodView(Guid PeriodId, DateTimeOffset StartedAtUtc, DateTimeOffset? EndedAtUtc,
    long BytesSent, long BytesReceived, DateTimeOffset? ScheduledResetAtUtc, string? ScheduledTimeZoneId);
public sealed record FinalizeUsageResult(bool Accepted, bool AlreadyFinalized, bool Conflict, string? Error);

public interface ICallActivityService
{
    Task StartAsync(Realtime.CallSessionRegistry.Session session, CancellationToken cancellationToken);
    Task AnswerAsync(Realtime.CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken);
    Task JoinedAsync(Realtime.CallSessionRegistry.Session session, Guid membershipId, DateTimeOffset at, CancellationToken cancellationToken);
    Task FinishAsync(Realtime.CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken);
    Task<CallHistoryPage> ListAsync(string applicationKey, Guid membershipId, int page, int pageSize, CancellationToken cancellationToken);
    Task<CallHistoryDetail?> DetailAsync(string applicationKey, Guid membershipId, Guid callId, CancellationToken cancellationToken);
    Task<FinalizeUsageResult> FinalizeUsageAsync(string applicationKey, Guid membershipId, Guid callId, UsageSummary usage, CancellationToken cancellationToken);
    Task<UsagePeriodView> CurrentPeriodAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken);
    Task<UsagePeriodView> ResetAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken);
    Task<UsagePeriodView> ScheduleResetAsync(string applicationKey, Guid membershipId, DateTime localDateTime, string timeZoneId, CancellationToken cancellationToken);
}
