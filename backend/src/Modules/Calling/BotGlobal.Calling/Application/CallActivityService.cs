using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Calling.Realtime;
using BotGlobal.Contracts.Notifications;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Calling.Application;

internal sealed class CallActivityService(
    CallingDbContext db,
    IPlatformClientApplicationResolver applications,
    TimeProvider clock) : ICallActivityService
{
    private const long MaximumBytes = 10L * 1024 * 1024 * 1024 * 1024;
    private const long MaximumDurationSeconds = 31L * 24 * 60 * 60;

    public async Task StartAsync(CallSessionRegistry.Session session, CancellationToken cancellationToken)
    {
        if (await db.Calls.AnyAsync(x => x.Id == session.CallId, cancellationToken)) return;
        var application = await RequireApplicationAsync(session.ApplicationKey, cancellationToken);
        var call = new CallRecord(session.CallId, application.PlatformClientId, session.ApplicationKey, session.CreatedAtUtc);
        call.Participants.Add(new CallParticipantRecord(call.Id, session.CallerMembershipId, CallParticipantRole.Initiator, session.CallerDisplayName));
        call.Participants.Add(new CallParticipantRecord(call.Id, session.CalleeMembershipId, CallParticipantRole.Recipient, session.CalleeDisplayName));
        db.Calls.Add(call);
        await db.SaveChangesAsync(cancellationToken);
        await RequireCurrentPeriodAsync(application.PlatformClientId, session.CallerMembershipId, cancellationToken);
        await RequireCurrentPeriodAsync(application.PlatformClientId, session.CalleeMembershipId, cancellationToken);
    }

    public async Task AnswerAsync(CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken)
    {
        var call = await RequireCallAsync(session.CallId, cancellationToken);
        call.Answer(at);
        foreach (var participant in call.Participants) participant.MarkAnswered(at);
        await db.SaveChangesAsync(cancellationToken);
    }

    public async Task JoinedAsync(CallSessionRegistry.Session session, Guid membershipId, DateTimeOffset at, CancellationToken cancellationToken)
    {
        var participant = await db.Participants.SingleAsync(x => x.CallId == session.CallId && x.MembershipId == membershipId, cancellationToken);
        participant.MarkJoined(at);
        await db.SaveChangesAsync(cancellationToken);
    }

    public async Task FinishAsync(CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken)
    {
        var call = await RequireCallAsync(session.CallId, cancellationToken);
        var outcome = session.Status switch
        {
            CallSessionRegistry.CallStatus.Rejected => DurableCallOutcome.Rejected,
            CallSessionRegistry.CallStatus.Cancelled => DurableCallOutcome.Cancelled,
            CallSessionRegistry.CallStatus.Expired => DurableCallOutcome.Expired,
            CallSessionRegistry.CallStatus.Ended when session.TerminationReason is "failed" or "busy" => DurableCallOutcome.Failed,
            CallSessionRegistry.CallStatus.Ended when session.TerminationReason == "missed" => DurableCallOutcome.Missed,
            CallSessionRegistry.CallStatus.Ended when call.AnsweredAtUtc is not null => DurableCallOutcome.Completed,
            CallSessionRegistry.CallStatus.Ended => DurableCallOutcome.Missed,
            _ => DurableCallOutcome.Failed
        };
        call.Finish(outcome, session.TerminationReason ?? session.Status.ToString(), at);
        await db.SaveChangesAsync(cancellationToken);
    }

    public async Task<CallHistoryPage> ListAsync(string applicationKey, Guid membershipId, int page, int pageSize, CancellationToken cancellationToken)
    {
        var application = await RequireApplicationAsync(applicationKey, cancellationToken);
        page = Math.Max(1, page); pageSize = Math.Clamp(pageSize, 1, 50);
        var query = db.Calls.AsNoTracking()
            .Where(call => call.ApplicationId == application.PlatformClientId && call.Participants.Any(p => p.MembershipId == membershipId))
            .OrderByDescending(call => call.CreatedAtUtc)
            .ThenByDescending(call => call.Id);
        var rows = await query.Skip((page - 1) * pageSize).Take(pageSize + 1)
            .Select(call => new
            {
                Call = call,
                Self = call.Participants.Single(p => p.MembershipId == membershipId),
                Other = call.Participants.Where(p => p.MembershipId != membershipId).OrderBy(p => p.Id).First(),
                Usage = call.UsageReports.SingleOrDefault(u => u.MembershipId == membershipId)
            }).ToListAsync(cancellationToken);
        return new CallHistoryPage(rows.Take(pageSize).Select(x => new CallHistoryItem(
            x.Call.Id, x.Self.Role == CallParticipantRole.Initiator ? "outgoing" : "incoming",
            x.Other.DisplayNameSnapshot, OutcomeName(x.Call.Outcome, x.Self.Role), x.Call.CreatedAtUtc,
            x.Usage == null ? null : x.Usage.ConnectedDurationSeconds,
            x.Usage == null ? null : x.Usage.BytesSent + x.Usage.BytesReceived)).ToArray(), page, pageSize, rows.Count > pageSize);
    }

    public async Task<CallHistoryDetail?> DetailAsync(string applicationKey, Guid membershipId, Guid callId, CancellationToken cancellationToken)
    {
        var application = await RequireApplicationAsync(applicationKey, cancellationToken);
        var call = await db.Calls.AsNoTracking().Include(x => x.Participants).Include(x => x.UsageReports)
            .SingleOrDefaultAsync(x => x.Id == callId && x.ApplicationId == application.PlatformClientId && x.Participants.Any(p => p.MembershipId == membershipId), cancellationToken);
        if (call is null) return null;
        var self = call.Participants.Single(x => x.MembershipId == membershipId);
        var usage = call.UsageReports.SingleOrDefault(x => x.MembershipId == membershipId);
        return new CallHistoryDetail(call.Id, self.Role == CallParticipantRole.Initiator ? "outgoing" : "incoming",
            call.Participants.Where(x => x.MembershipId != membershipId).Select(x => x.DisplayNameSnapshot).ToArray(),
            OutcomeName(call.Outcome, self.Role), call.EndReason, call.CreatedAtUtc, call.AnsweredAtUtc, call.EndedAtUtc,
            RingingDurationSeconds(call),
            usage?.ConnectedDurationSeconds, usage?.BytesSent, usage?.BytesReceived);
    }

    public async Task<FinalizeUsageResult> FinalizeUsageAsync(string applicationKey, Guid membershipId, Guid callId, UsageSummary usage, CancellationToken cancellationToken)
    {
        if (usage.BytesSent < 0 || usage.BytesReceived < 0 || usage.ConnectedDurationSeconds < 0 ||
            usage.BytesSent > MaximumBytes || usage.BytesReceived > MaximumBytes || usage.ConnectedDurationSeconds > MaximumDurationSeconds)
            return new(false, false, false, "call_usage_invalid");
        var application = await RequireApplicationAsync(applicationKey, cancellationToken);
        var call = await db.Calls.Include(x => x.Participants).Include(x => x.UsageReports)
            .SingleOrDefaultAsync(x => x.Id == callId && x.ApplicationId == application.PlatformClientId, cancellationToken);
        if (call is null || call.Participants.All(x => x.MembershipId != membershipId)) return new(false, false, false, "call_usage_unauthorized");
        if (call.State != DurableCallState.Terminal) return new(false, false, false, "call_usage_not_terminal");
        var existing = call.UsageReports.SingleOrDefault(x => x.MembershipId == membershipId);
        if (existing is not null)
        {
            var identical = existing.BytesSent == usage.BytesSent && existing.BytesReceived == usage.BytesReceived && existing.ConnectedDurationSeconds == usage.ConnectedDurationSeconds;
            return identical ? new(true, true, false, null) : new(false, true, true, "call_usage_already_finalized");
        }
        var report = new CallUsageReport(callId, membershipId, usage.BytesSent, usage.BytesReceived,
            usage.ConnectedDurationSeconds, clock.GetUtcNow());
        call.UsageReports.Add(report);
        db.UsageReports.Add(report);
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return new(true, false, false, null);
        }
        catch (DbUpdateException)
        {
            db.Entry(report).State = EntityState.Detached;
            var accepted = await db.UsageReports.AsNoTracking()
                .SingleOrDefaultAsync(x => x.CallId == callId && x.MembershipId == membershipId, cancellationToken);
            if (accepted is null) throw;
            var identical = accepted.BytesSent == usage.BytesSent && accepted.BytesReceived == usage.BytesReceived &&
                accepted.ConnectedDurationSeconds == usage.ConnectedDurationSeconds;
            return identical ? new(true, true, false, null) : new(false, true, true, "call_usage_already_finalized");
        }
    }

    public Task<UsagePeriodView> CurrentPeriodAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken) =>
        GetPeriodAsync(applicationKey, membershipId, null, cancellationToken);
    public Task<UsagePeriodView> ResetAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken) =>
        GetPeriodAsync(applicationKey, membershipId, UsagePeriodResetReason.Manual, cancellationToken);

    public async Task<UsagePeriodView> ScheduleResetAsync(string applicationKey, Guid membershipId, DateTime localDateTime, string timeZoneId, CancellationToken cancellationToken)
    {
        var application = await RequireApplicationAsync(applicationKey, cancellationToken);
        var period = await RequireCurrentPeriodAsync(application.PlatformClientId, membershipId, cancellationToken);
        TimeZoneInfo zone;
        try { zone = TimeZoneInfo.FindSystemTimeZoneById(timeZoneId); }
        catch (TimeZoneNotFoundException) { throw new ArgumentException("usage_timezone_invalid"); }
        var local = DateTime.SpecifyKind(localDateTime, DateTimeKind.Unspecified);
        var utc = new DateTimeOffset(TimeZoneInfo.ConvertTimeToUtc(local, zone), TimeSpan.Zero);
        if (utc <= clock.GetUtcNow()) throw new ArgumentException("usage_reset_must_be_future");
        period.Schedule(local, timeZoneId, utc);
        await db.SaveChangesAsync(cancellationToken);
        return await ProjectPeriodAsync(period, cancellationToken);
    }

    private async Task<UsagePeriodView> GetPeriodAsync(string applicationKey, Guid membershipId, UsagePeriodResetReason? reset, CancellationToken cancellationToken)
    {
        var application = await RequireApplicationAsync(applicationKey, cancellationToken);
        var period = await RequireCurrentPeriodAsync(application.PlatformClientId, membershipId, cancellationToken);
        if (reset is not null)
        {
            var now = clock.GetUtcNow(); period.Close(now);
            period = new UsageCounterPeriod(application.PlatformClientId, membershipId, now, reset.Value);
            db.UsagePeriods.Add(period); await db.SaveChangesAsync(cancellationToken);
        }
        return await ProjectPeriodAsync(period, cancellationToken);
    }

    private async Task<UsageCounterPeriod> RequireCurrentPeriodAsync(Guid applicationId, Guid membershipId, CancellationToken cancellationToken)
    {
        var period = await db.UsagePeriods.SingleOrDefaultAsync(x => x.ApplicationId == applicationId && x.MembershipId == membershipId && x.EndedAtUtc == null, cancellationToken);
        if (period is null)
        {
            period = new UsageCounterPeriod(applicationId, membershipId, clock.GetUtcNow(), UsagePeriodResetReason.Initial);
            db.UsagePeriods.Add(period);
            try { await db.SaveChangesAsync(cancellationToken); }
            catch (DbUpdateException)
            {
                db.Entry(period).State = EntityState.Detached;
                var concurrent = await db.UsagePeriods.SingleOrDefaultAsync(x => x.ApplicationId == applicationId && x.MembershipId == membershipId && x.EndedAtUtc == null, cancellationToken);
                if (concurrent is null) throw;
                period = concurrent;
            }
        }
        if (period.ScheduledResetAtUtc is { } due && due <= clock.GetUtcNow())
        {
            period.Close(due);
            period = new UsageCounterPeriod(applicationId, membershipId, due, UsagePeriodResetReason.Scheduled);
            db.UsagePeriods.Add(period); await db.SaveChangesAsync(cancellationToken);
        }
        return period;
    }

    private async Task<UsagePeriodView> ProjectPeriodAsync(UsageCounterPeriod period, CancellationToken cancellationToken)
    {
        var totals = await (from usage in db.UsageReports.AsNoTracking()
            join call in db.Calls.AsNoTracking() on usage.CallId equals call.Id
            where call.ApplicationId == period.ApplicationId && usage.MembershipId == period.MembershipId
                && call.EndedAtUtc >= period.StartedAtUtc && (period.EndedAtUtc == null || call.EndedAtUtc < period.EndedAtUtc)
            group usage by 1 into grouped
            select new { Sent = grouped.Sum(x => x.BytesSent), Received = grouped.Sum(x => x.BytesReceived) })
            .SingleOrDefaultAsync(cancellationToken);
        return new UsagePeriodView(period.Id, period.StartedAtUtc, period.EndedAtUtc, totals?.Sent ?? 0, totals?.Received ?? 0,
            period.ScheduledResetAtUtc, period.ScheduledTimeZoneId);
    }

    private async Task<PlatformClientDescriptor> RequireApplicationAsync(string applicationKey, CancellationToken cancellationToken)
    {
        var application = await applications.FindByClientKeyAsync(applicationKey, cancellationToken);
        return application is { IsActive: true } ? application : throw new InvalidOperationException("calling_application_unavailable");
    }
    private Task<CallRecord> RequireCallAsync(Guid callId, CancellationToken cancellationToken) =>
        db.Calls.Include(x => x.Participants).SingleAsync(x => x.Id == callId, cancellationToken);
    private static string? OutcomeName(DurableCallOutcome? outcome, CallParticipantRole role) =>
        role == CallParticipantRole.Recipient && outcome is DurableCallOutcome.Cancelled or DurableCallOutcome.Expired
            ? "missed"
            : outcome?.ToString().ToLowerInvariant();
    private static long? RingingDurationSeconds(CallRecord call)
    {
        var ringingEndedAt = call.AnsweredAtUtc ?? call.EndedAtUtc;
        return ringingEndedAt is null ? null : Math.Max(0, (long)(ringingEndedAt.Value - call.CreatedAtUtc).TotalSeconds);
    }
}
