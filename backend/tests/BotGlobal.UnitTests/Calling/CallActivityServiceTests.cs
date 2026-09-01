using BotGlobal.Calling.Application;
using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Calling.Realtime;
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

        var callerHistory = await fixture.Service.ListAsync("nqrb", fixture.CallerId, 1, 20, default);
        var calleeHistory = await fixture.Service.ListAsync("nqrb", fixture.CalleeId, 1, 20, default);
        var caller = Assert.Single(callerHistory.Items);
        var callee = Assert.Single(calleeHistory.Items);
        Assert.Equal(session.CallId, caller.CallId);
        Assert.Equal("outgoing", caller.Direction);
        Assert.Equal("Callee", caller.ParticipantDisplayName);
        Assert.Equal("incoming", callee.Direction);
        Assert.Equal("Caller", callee.ParticipantDisplayName);
        Assert.Equal("completed", caller.Outcome);
        Assert.Equal(4, (await fixture.Service.DetailAsync("nqrb", fixture.CallerId, session.CallId, default))!.RingingDurationSeconds);
        Assert.Empty((await fixture.Service.ListAsync("other-app", fixture.CallerId, 1, 20, default)).Items);
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

    private sealed class Fixture : IAsyncDisposable
    {
        public readonly Guid ApplicationId = Guid.NewGuid();
        public readonly Guid CallerId = Guid.NewGuid();
        public readonly Guid CalleeId = Guid.NewGuid();
        public readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-09-01T12:00:00Z");
        public readonly MutableTimeProvider Clock;
        public readonly CallingDbContext Db;
        public readonly CallActivityService Service;

        public Fixture()
        {
            Clock = new MutableTimeProvider(Now);
            Db = new CallingDbContext(new DbContextOptionsBuilder<CallingDbContext>()
                .UseInMemoryDatabase($"calling-activity-{Guid.NewGuid():N}").Options);
            Service = new CallActivityService(Db, new Applications(ApplicationId), Clock);
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

    private sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset current = now;
        public override DateTimeOffset GetUtcNow() => current;
        public void Advance(TimeSpan by) => current += by;
    }
}
