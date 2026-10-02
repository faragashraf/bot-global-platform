using BotGlobal.Calling.Application;
using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Calling.Realtime;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallActivityServiceTests
{
    [Fact]
    public async Task Lifecycle_produces_one_application_scoped_user_relative_history_record()
    {
        await using var fixture = new Fixture();
        var session = fixture.NewSession();

        await fixture.Service.StartAsync(session, default);
        session.Status = CallSessionRegistry.CallStatus.Answered;
        await fixture.Service.AnswerAsync(session, fixture.Now.AddSeconds(4), default);
        await fixture.Service.JoinedAsync(session, fixture.CallerId, fixture.Now.AddSeconds(5), default);
        await fixture.Service.JoinedAsync(session, fixture.CalleeId, fixture.Now.AddSeconds(6), default);
        session.Status = CallSessionRegistry.CallStatus.Ended;
        await fixture.Service.FinishAsync(session, fixture.Now.AddMinutes(2), default);

        var callerHistory = await fixture.Service.ListAsync("nqrb", fixture.CallerId, 1, 20, CallHistoryFilter.All, default);
        var calleeHistory = await fixture.Service.ListAsync("nqrb", fixture.CalleeId, 1, 20, CallHistoryFilter.All, default);
        var caller = Assert.Single(callerHistory.Items);
        var callee = Assert.Single(calleeHistory.Items);
        Assert.Equal(session.CallId, caller.CallId);
        Assert.Equal("outgoing", caller.Direction);
        Assert.Equal("Callee", caller.ParticipantDisplayName);
        Assert.Equal("incoming", callee.Direction);
        Assert.Equal("Caller", callee.ParticipantDisplayName);
        Assert.Equal("completed", caller.Outcome);
        Assert.Equal(4, (await fixture.Service.DetailAsync("nqrb", fixture.CallerId, session.CallId, default))!.RingingDurationSeconds);
        Assert.Empty((await fixture.Service.ListAsync("other-app", fixture.CallerId, 1, 20, CallHistoryFilter.All, default)).Items);
        Assert.Empty((await fixture.Service.ListAsync("nqrb", fixture.CallerId, 1, 20, CallHistoryFilter.Incoming, default)).Items);
        Assert.Equal(session.CallId, Assert.Single((await fixture.Service.ListAsync("nqrb", fixture.CallerId, 1, 20, CallHistoryFilter.Outgoing, default)).Items).CallId);
    }

    [Fact]
    public async Task History_reports_saved_status_for_the_viewing_account_even_before_contacts_are_loaded()
    {
        await using var fixture = new Fixture();
        var session = fixture.NewSession();
        await fixture.Service.StartAsync(session, default);

        var before = Assert.Single((await fixture.Service.ListAsync(
            "nqrb", fixture.CallerId, 1, 20, CallHistoryFilter.All, default)).Items);
        Assert.False(before.IsSavedContact);
        Assert.Equal(fixture.CalleeId, before.CounterpartMembershipId);
        Assert.False(before.CanRedial);
        Assert.False(before.CanAddContact);

        var edge = new NqrbContactEdge("nqrb", fixture.CallerId, fixture.CalleeId, fixture.Now);
        fixture.Db.NqrbContactEdges.Add(edge);
        await fixture.Db.SaveChangesAsync();

        var caller = Assert.Single((await fixture.Service.ListAsync(
            "nqrb", fixture.CallerId, 1, 20, CallHistoryFilter.All, default)).Items);
        var callee = Assert.Single((await fixture.Service.ListAsync(
            "nqrb", fixture.CalleeId, 1, 20, CallHistoryFilter.All, default)).Items);
        Assert.True(caller.IsSavedContact);
        Assert.Equal(fixture.CalleeId, caller.CounterpartMembershipId);
        Assert.True(caller.CanRedial);
        Assert.False(caller.CanAddContact);
        Assert.False(callee.IsSavedContact);
        Assert.Equal(fixture.CallerId, callee.CounterpartMembershipId);
        Assert.False(callee.CanRedial);
        Assert.False(callee.CanAddContact);
        Assert.True((await fixture.Service.DetailAsync("nqrb", fixture.CallerId, session.CallId, default))!.IsSavedContact);
        Assert.Equal(fixture.CalleeId, (await fixture.Service.DetailAsync("nqrb", fixture.CallerId, session.CallId, default))!.CounterpartMembershipId);

        fixture.Db.NqrbContactEdges.Remove(edge);
        await fixture.Db.SaveChangesAsync();
        Assert.False((await fixture.Service.DetailAsync("nqrb", fixture.CallerId, session.CallId, default))!.IsSavedContact);
    }

    [Fact]
    public async Task Terminal_unsaved_nqrb_history_reports_redial_and_add_capabilities()
    {
        await using var fixture = new Fixture();
        var session = await fixture.CompletedSessionAsync();

        var caller = Assert.Single((await fixture.Service.ListAsync(
            "nqrb", fixture.CallerId, 1, 20, CallHistoryFilter.All, default)).Items);
        var detail = await fixture.Service.DetailAsync("nqrb", fixture.CallerId, session.CallId, default);

        Assert.Equal(fixture.CalleeId, caller.CounterpartMembershipId);
        Assert.False(caller.IsSavedContact);
        Assert.True(caller.CanRedial);
        Assert.True(caller.CanAddContact);
        Assert.True(detail!.CanRedial);
        Assert.True(detail.CanAddContact);
    }

    [Fact]
    public async Task Guest_call_is_recorded_for_the_host_without_a_guest_usage_period_and_erased_with_the_host()
    {
        var guestId = Guid.NewGuid();
        await using var fixture = new Fixture(inactiveMembershipId: guestId);
        var session = new CallSessionRegistry.Session(
            Guid.NewGuid(), "nqrb", guestId, fixture.CalleeId,
            "guest-call:private", "host-subject", "Browser guest", "Host",
            fixture.Now, fixture.Now.AddSeconds(45), isGuestCall: true, guestInviteId: Guid.NewGuid());

        await fixture.Service.StartAsync(session, default);
        session.Status = CallSessionRegistry.CallStatus.Answered;
        await fixture.Service.AnswerAsync(session, fixture.Now.AddSeconds(4), default);
        await fixture.Service.JoinedAsync(session, guestId, fixture.Now.AddSeconds(5), default);
        await fixture.Service.JoinedAsync(session, fixture.CalleeId, fixture.Now.AddSeconds(5), default);
        session.Status = CallSessionRegistry.CallStatus.Ended;
        session.TerminationReason = "local";
        await fixture.Service.FinishAsync(session, fixture.Now.AddMinutes(1), default);

        var item = Assert.Single((await fixture.Service.ListAsync(
            "nqrb", fixture.CalleeId, 1, 20, CallHistoryFilter.All, default)).Items);
        Assert.Equal("Browser guest", item.ParticipantDisplayName);
        Assert.Equal("incoming", item.Direction);
        Assert.Equal("completed", item.Outcome);
        Assert.True(item.IsGuestCall);
        Assert.Null(item.IsSavedContact);
        Assert.Null(item.CounterpartMembershipId);
        Assert.True((await fixture.Service.DetailAsync("nqrb", fixture.CalleeId, session.CallId, default))!.IsGuestCall);
        Assert.DoesNotContain(fixture.Db.UsagePeriods, period => period.MembershipId == guestId);
        Assert.Empty((await fixture.Service.ListAsync("nqrb", Guid.NewGuid(), 1, 20, CallHistoryFilter.All, default)).Items);

        await new CallingAccountDataEraser(fixture.Db).DeleteAsync("nqrb", fixture.CalleeId, default);
        Assert.Empty(fixture.Db.Calls);
        Assert.Empty(fixture.Db.Participants);
    }

    [Fact]
    public async Task Final_usage_is_participant_scoped_idempotent_and_immutable()
    {
        await using var fixture = new Fixture();
        var session = await fixture.CompletedSessionAsync();
        var usage = new UsageSummary(1_024, 2_048, 60);

        var accepted = await fixture.Service.FinalizeUsageAsync("nqrb", fixture.CallerId, session.CallId, usage, default);
        var repeated = await fixture.Service.FinalizeUsageAsync("nqrb", fixture.CallerId, session.CallId, usage, default);
        var conflict = await fixture.Service.FinalizeUsageAsync("nqrb", fixture.CallerId, session.CallId, new(1_025, 2_048, 60), default);
        var stranger = await fixture.Service.FinalizeUsageAsync("nqrb", Guid.NewGuid(), session.CallId, usage, default);

        Assert.True(accepted.Accepted);
        Assert.True(repeated.Accepted);
        Assert.True(repeated.AlreadyFinalized);
        Assert.True(conflict.Conflict);
        Assert.False(stranger.Accepted);
        Assert.Equal("call_usage_unauthorized", stranger.Error);
        Assert.Single(fixture.Db.UsageReports);
    }

    [Fact]
    public async Task Manual_reset_starts_a_zero_period_without_changing_historical_call_usage()
    {
        await using var fixture = new Fixture();
        var session = await fixture.CompletedSessionAsync();
        await fixture.Service.FinalizeUsageAsync("nqrb", fixture.CallerId, session.CallId, new(3_000, 5_000, 90), default);

        var before = await fixture.Service.CurrentPeriodAsync("nqrb", fixture.CallerId, default);
        fixture.Clock.Advance(TimeSpan.FromMinutes(1));
        var after = await fixture.Service.ResetAsync("nqrb", fixture.CallerId, default);
        var detail = await fixture.Service.DetailAsync("nqrb", fixture.CallerId, session.CallId, default);

        Assert.Equal(3_000, before.BytesSent);
        Assert.Equal(5_000, before.BytesReceived);
        Assert.Equal(0, after.BytesSent);
        Assert.Equal(0, after.BytesReceived);
        Assert.NotEqual(before.PeriodId, after.PeriodId);
        Assert.Equal(3_000, detail!.BytesSent);
        Assert.Equal(5_000, detail.BytesReceived);
    }

    [Fact]
    public async Task Scheduled_reset_preserves_local_time_zone_and_reconciles_at_due_instant()
    {
        await using var fixture = new Fixture();
        await fixture.Service.CurrentPeriodAsync("nqrb", fixture.CallerId, default);
        var localReset = DateTime.SpecifyKind(fixture.Now.AddHours(2).UtcDateTime, DateTimeKind.Unspecified);

        var scheduled = await fixture.Service.ScheduleResetAsync("nqrb", fixture.CallerId, localReset, "UTC", default);
        fixture.Clock.Advance(TimeSpan.FromHours(3));
        var reconciled = await fixture.Service.CurrentPeriodAsync("nqrb", fixture.CallerId, default);

        Assert.Equal("UTC", scheduled.ScheduledTimeZoneId);
        Assert.NotNull(scheduled.ScheduledResetAtUtc);
        Assert.NotEqual(scheduled.PeriodId, reconciled.PeriodId);
        Assert.Equal(scheduled.ScheduledResetAtUtc, reconciled.StartedAtUtc);
        Assert.Null(reconciled.ScheduledResetAtUtc);
    }

    [Fact]
    public async Task Usage_rejects_non_terminal_negative_and_excessive_reports()
    {
        await using var fixture = new Fixture();
        var session = fixture.NewSession();
        await fixture.Service.StartAsync(session, default);

        var nonTerminal = await fixture.Service.FinalizeUsageAsync("nqrb", fixture.CallerId, session.CallId, new(1, 1, 1), default);
        var negative = await fixture.Service.FinalizeUsageAsync("nqrb", fixture.CallerId, session.CallId, new(-1, 0, 0), default);
        var excessive = await fixture.Service.FinalizeUsageAsync("nqrb", fixture.CallerId, session.CallId, new(long.MaxValue, 0, 0), default);

        Assert.Equal("call_usage_not_terminal", nonTerminal.Error);
        Assert.Equal("call_usage_invalid", negative.Error);
        Assert.Equal("call_usage_invalid", excessive.Error);
    }

    [Fact]
    public async Task Client_media_failure_is_not_collapsed_into_completed()
    {
        await using var fixture = new Fixture();
        var session = fixture.NewSession();
        await fixture.Service.StartAsync(session, default);
        session.Status = CallSessionRegistry.CallStatus.Answered;
        await fixture.Service.AnswerAsync(session, fixture.Now.AddSeconds(2), default);
        session.Status = CallSessionRegistry.CallStatus.Ended;
        session.TerminationReason = "failed";

        await fixture.Service.FinishAsync(session, fixture.Now.AddSeconds(5), default);

        var detail = await fixture.Service.DetailAsync("nqrb", fixture.CallerId, session.CallId, default);
        Assert.Equal("failed", detail!.Outcome);
        Assert.Equal("failed", detail.EndReason);
    }

    [Fact]
    public async Task Unanswered_expiry_is_expired_for_caller_and_missed_for_recipient()
    {
        await using var fixture = new Fixture();
        var session = fixture.NewSession();
        await fixture.Service.StartAsync(session, default);
        session.Status = CallSessionRegistry.CallStatus.Expired;
        session.TerminationReason = "expired";
        await fixture.Service.FinishAsync(session, fixture.Now.AddMinutes(1), default);

        var caller = await fixture.Service.DetailAsync("nqrb", fixture.CallerId, session.CallId, default);
        var recipient = await fixture.Service.DetailAsync("nqrb", fixture.CalleeId, session.CallId, default);

        Assert.Equal("expired", caller!.Outcome);
        Assert.Equal("missed", recipient!.Outcome);
    }

    [Fact]
    public async Task Membership_deleted_during_call_start_cannot_reintroduce_direct_call_or_usage_identity()
    {
        await using var fixture = new Fixture(inactiveMembership: true);
        var session = fixture.NewSession();

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            fixture.Service.StartAsync(session, default));

        Assert.DoesNotContain(
            await fixture.Db.Participants.ToListAsync(),
            participant => participant.MembershipId == fixture.CallerId
                || participant.DisplayNameSnapshot == "Caller");
        Assert.DoesNotContain(
            await fixture.Db.UsagePeriods.ToListAsync(),
            period => period.MembershipId == fixture.CallerId);
        Assert.Contains(
            await fixture.Db.Participants.ToListAsync(),
            participant => participant.DisplayNameSnapshot == "Deleted NQRB account");
    }

    private sealed class Fixture : IAsyncDisposable
    {
        public readonly Guid ApplicationId = Guid.NewGuid();
        public readonly Guid CallerId = Guid.NewGuid();
        public readonly Guid CalleeId = Guid.NewGuid();
        public readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-09-01T12:00:00Z");
        public readonly MutableTimeProvider Clock;
        public readonly CallingDbContext Db;
        public readonly CallActivityService Service;

        public Fixture(bool inactiveMembership = false, Guid? inactiveMembershipId = null)
        {
            Clock = new MutableTimeProvider(Now);
            Db = new CallingDbContext(new DbContextOptionsBuilder<CallingDbContext>()
                .UseInMemoryDatabase($"calling-activity-{Guid.NewGuid():N}").Options);
            Service = new CallActivityService(
                Db,
                new Applications(ApplicationId),
                new MembershipActivityReader(inactiveMembershipId ?? (inactiveMembership ? CallerId : null)),
                new NqrbCallEligibilityService(
                    Db,
                    new Applications(ApplicationId),
                    new AccountDirectory([CallerId, CalleeId], inactiveMembershipId ?? (inactiveMembership ? CallerId : null))),
                new CallingAccountDataEraser(Db),
                Clock);
        }

        public CallSessionRegistry.Session NewSession() => new(
            Guid.NewGuid(), "nqrb", CallerId, CalleeId,
            "caller-subject", "callee-subject", "Caller", "Callee", Now, Now.AddSeconds(45));

        public async Task<CallSessionRegistry.Session> CompletedSessionAsync()
        {
            var session = NewSession();
            await Service.StartAsync(session, default);
            session.Status = CallSessionRegistry.CallStatus.Answered;
            await Service.AnswerAsync(session, Now.AddSeconds(2), default);
            session.Status = CallSessionRegistry.CallStatus.Ended;
            await Service.FinishAsync(session, Now.AddMinutes(1), default);
            Clock.Advance(TimeSpan.FromMinutes(2));
            return session;
        }

        public ValueTask DisposeAsync() => Db.DisposeAsync();
    }

    private sealed class Applications(Guid applicationId) : IPlatformClientApplicationResolver
    {
        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(string clientKey, CancellationToken cancellationToken) =>
            Task.FromResult<PlatformClientDescriptor?>(clientKey == "nqrb"
                ? new(applicationId, "nqrb", "NQRB", true)
                : new(Guid.NewGuid(), clientKey, "Other", true));
    }

    private sealed class MembershipActivityReader(Guid? inactiveMembership)
        : IApplicationMembershipActivityReader
    {
        public Task<bool> IsActiveAsync(
            Guid membershipId,
            string applicationKey,
            CancellationToken cancellationToken) =>
            Task.FromResult(membershipId != inactiveMembership);
    }

    private sealed class AccountDirectory(
        IReadOnlyCollection<Guid> activeMemberships,
        Guid? inactiveMembership) : ICallingAccountDirectory
    {
        public Task<CallingAccountDescriptor?> FindActiveNonGuestAsync(
            string applicationKey,
            Guid membershipId,
            CancellationToken cancellationToken) =>
            Task.FromResult<CallingAccountDescriptor?>(IsActive(applicationKey, membershipId)
                ? new(membershipId, membershipId == activeMemberships.First() ? "Caller" : "Callee")
                : null);

        public Task<IReadOnlyList<CallingAccountDescriptor>> FindActiveNonGuestAsync(
            string applicationKey,
            IReadOnlyCollection<Guid> membershipIds,
            CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<CallingAccountDescriptor>>(membershipIds
                .Where(id => IsActive(applicationKey, id))
                .Select(id => new CallingAccountDescriptor(id, id == activeMemberships.First() ? "Caller" : "Callee"))
                .ToArray());

        public Task<CallingAccountSearchPage> SearchActiveNonGuestsAsync(
            string applicationKey,
            Guid currentMembershipId,
            string query,
            int page,
            int pageSize,
            CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        private bool IsActive(string applicationKey, Guid membershipId) =>
            applicationKey == "nqrb" &&
            membershipId != inactiveMembership &&
            activeMemberships.Contains(membershipId);
    }

    private sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset current = now;
        public override DateTimeOffset GetUtcNow() => current;
        public void Advance(TimeSpan by) => current += by;
    }
}
