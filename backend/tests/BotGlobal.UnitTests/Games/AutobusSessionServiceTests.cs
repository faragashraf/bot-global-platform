using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application.Entitlements;
using BotGlobal.Games.Application.Sessions;
using BotGlobal.Games.Domain.Autobus;
using BotGlobal.Games.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;

namespace BotGlobal.UnitTests.Games;

public sealed class AutobusSessionServiceTests
{
    [Fact]
    public async Task Third_and_fourth_players_join_and_notify_every_existing_player()
    {
        var host = Identity("Host");
        var second = Identity("Second");
        var third = Identity("Third");
        var fourth = Identity("Fourth");
        var notifications = new RecordingNotifications();
        await using var context = CreateContext();
        var service = CreateService(context, new RecordingRealtime(), TimeProvider.System, notifications);
        var created = (await service.CreateAsync(
            host,
            new CreateGameSessionRequest("autobus-5x60-easy", "autobus", 5, 60, "easy", ["boy_name"]),
            CancellationToken.None)).Value!;

        Assert.True((await service.JoinAsync(second, new JoinGameSessionRequest(created.JoinCode), CancellationToken.None)).Succeeded);
        notifications.Items.Clear();
        Assert.True((await service.JoinAsync(third, new JoinGameSessionRequest(created.JoinCode), CancellationToken.None)).Succeeded);
        Assert.Equal(
            new[] { host.MembershipId, second.MembershipId }.Order().ToArray(),
            notifications.Items.Select(item => item.RecipientMembershipId).Order().ToArray());

        notifications.Items.Clear();
        var fourthJoin = await service.JoinAsync(
            fourth,
            new JoinGameSessionRequest(created.JoinCode),
            CancellationToken.None);
        Assert.True(fourthJoin.Succeeded);
        Assert.Equal(4, fourthJoin.Value!.Players.Count);
        Assert.Equal(
            new[] { host.MembershipId, second.MembershipId, third.MembershipId }.Order().ToArray(),
            notifications.Items.Select(item => item.RecipientMembershipId).Order().ToArray());
    }

    [Fact]
    public async Task Reveal_command_preserves_grace_before_locking_the_round()
    {
        var host = Identity("Host");
        var guest = Identity("Guest");
        var clock = new MutableTimeProvider(new DateTimeOffset(2026, 10, 4, 9, 30, 0, TimeSpan.Zero));
        await using var context = CreateContext();
        var service = CreateService(context, new RecordingRealtime(), clock);
        var created = (await service.CreateAsync(
            host,
            new CreateGameSessionRequest("autobus-5x60-easy", "autobus", 5, 60, "easy", ["boy_name"]),
            CancellationToken.None)).Value!;
        Assert.True((await service.JoinAsync(
            guest,
            new JoinGameSessionRequest(created.JoinCode),
            CancellationToken.None)).Succeeded);
        Assert.True((await service.ReadyAsync(host, created.SessionId, CancellationToken.None)).Succeeded);
        var started = (await service.ReadyAsync(guest, created.SessionId, CancellationToken.None)).Value!;

        var grace = (await service.RevealAutobusAsync(
            host,
            new AutobusRevealRequest(created.SessionId, Guid.NewGuid().ToString("N"), started.Version),
            CancellationToken.None)).Value!;

        Assert.Equal("grace", grace.Autobus!.Phase);
        Assert.Equal(clock.GetUtcNow().AddSeconds(5), grace.Autobus.GraceEndsAtUtc);

        clock.Advance(TimeSpan.FromSeconds(5));
        var reveal = (await service.RevealAutobusAsync(
            guest,
            new AutobusRevealRequest(created.SessionId, Guid.NewGuid().ToString("N"), grace.Version),
            CancellationToken.None)).Value!;
        Assert.Equal("reveal", reveal.Autobus!.Phase);
    }

    [Fact]
    public async Task Full_round_keeps_answers_private_recovers_drafts_and_scores_duplicates()
    {
        var host = Identity("Host");
        var guest = Identity("Guest");
        var realtime = new RecordingRealtime();
        var clock = new MutableTimeProvider(new DateTimeOffset(2026, 10, 4, 9, 0, 0, TimeSpan.Zero));
        await using var context = CreateContext();
        var service = CreateService(context, realtime, clock);

        var created = await service.CreateAsync(
            host,
            new CreateGameSessionRequest(
                "autobus-5x60-easy",
                "autobus",
                5,
                60,
                "easy",
                ["boy_name"]),
            CancellationToken.None);
        Assert.True(created.Succeeded);
        var sessionId = created.Value!.SessionId;

        Assert.True((await service.JoinAsync(
            guest,
            new JoinGameSessionRequest(created.Value.JoinCode),
            CancellationToken.None)).Succeeded);
        Assert.True((await service.ReadyAsync(host, sessionId, CancellationToken.None)).Succeeded);
        var started = await service.ReadyAsync(guest, sessionId, CancellationToken.None);
        Assert.True(started.Succeeded);
        Assert.Equal("active", started.Value!.Autobus!.Phase);
        Assert.Equal("أ", started.Value.Autobus.CurrentLetter);

        var hostCommandId = Guid.NewGuid().ToString("N");
        var hostSubmit = await service.SubmitAutobusAnswersAsync(
            host,
            new AutobusSubmitAnswersRequest(
                sessionId,
                hostCommandId,
                started.Value.Version,
                started.Value.MatchNumber,
                started.Value.Autobus.CurrentRound,
                new Dictionary<string, string> { ["boy_name"] = "أحمد" }),
            CancellationToken.None);
        Assert.True(hostSubmit.Succeeded);
        Assert.Equal("أحمد", Assert.Single(hostSubmit.Value!.Autobus!.Answers).DisplayAnswer);
        Assert.Empty(Assert.Single(realtime.StateUpdates).Autobus!.Answers);

        var guestRecovery = await service.RejoinAsync(guest, sessionId, CancellationToken.None);
        Assert.True(guestRecovery.Succeeded);
        Assert.Empty(guestRecovery.Value!.Autobus!.Answers);
        var hostRecovery = await service.RejoinAsync(host, sessionId, CancellationToken.None);
        Assert.Equal("أحمد", Assert.Single(hostRecovery.Value!.Autobus!.Answers).DisplayAnswer);

        var duplicate = await service.SubmitAutobusAnswersAsync(
            host,
            new AutobusSubmitAnswersRequest(
                sessionId,
                hostCommandId,
                started.Value.Version,
                started.Value.MatchNumber,
                started.Value.Autobus.CurrentRound,
                new Dictionary<string, string> { ["boy_name"] = "أيمن" }),
            CancellationToken.None);
        Assert.True(duplicate.Succeeded);
        Assert.Equal(hostSubmit.Value.Version, duplicate.Value!.Version);
        Assert.Equal("أحمد", Assert.Single(duplicate.Value.Autobus!.Answers).DisplayAnswer);

        var guestSubmit = await service.SubmitAutobusAnswersAsync(
            guest,
            new AutobusSubmitAnswersRequest(
                sessionId,
                Guid.NewGuid().ToString("N"),
                duplicate.Value.Version,
                duplicate.Value.MatchNumber,
                duplicate.Value.Autobus!.CurrentRound,
                new Dictionary<string, string> { ["boy_name"] = "أحمد" }),
            CancellationToken.None);
        Assert.True(guestSubmit.Succeeded);

        var grace = await service.FinishAutobusRoundAsync(
            host,
            new AutobusFinishRoundRequest(sessionId, Guid.NewGuid().ToString("N"), guestSubmit.Value!.Version),
            CancellationToken.None);
        Assert.True(grace.Succeeded);
        Assert.Equal("grace", grace.Value!.Autobus!.Phase);

        clock.Advance(TimeSpan.FromSeconds(5));
        var reveal = await service.FinishAutobusRoundAsync(
            guest,
            new AutobusFinishRoundRequest(sessionId, Guid.NewGuid().ToString("N"), grace.Value.Version),
            CancellationToken.None);

        Assert.True(reveal.Succeeded);
        Assert.Equal("reveal", reveal.Value!.Autobus!.Phase);
        Assert.Equal(2, reveal.Value.Autobus.Answers.Count);
        Assert.All(reveal.Value.Autobus.Answers, answer =>
        {
            Assert.True(answer.Duplicate);
            Assert.Equal(5, answer.Score);
            Assert.True(answer.Scored);
        });
        Assert.All(reveal.Value.Autobus.Scores, score => Assert.Equal(5, score.Score));
    }

    [Fact]
    public async Task Stale_per_player_submission_is_rebased_while_the_round_accepts_answers()
    {
        var host = Identity("Host");
        var guest = Identity("Guest");
        await using var context = CreateContext();
        var service = CreateService(context, new RecordingRealtime(), TimeProvider.System);
        var created = (await service.CreateAsync(
            host,
            new CreateGameSessionRequest("autobus-5x60-easy", "autobus", 5, 60, "easy", ["boy_name"]),
            CancellationToken.None)).Value!;
        Assert.True((await service.JoinAsync(
            guest,
            new JoinGameSessionRequest(created.JoinCode),
            CancellationToken.None)).Succeeded);
        Assert.True((await service.ReadyAsync(host, created.SessionId, CancellationToken.None)).Succeeded);
        var started = (await service.ReadyAsync(guest, created.SessionId, CancellationToken.None)).Value!;

        var hostSubmit = await service.SubmitAutobusAnswersAsync(
            host,
            new AutobusSubmitAnswersRequest(
                created.SessionId,
                Guid.NewGuid().ToString("N"),
                started.Version,
                started.MatchNumber,
                started.Autobus!.CurrentRound,
                new Dictionary<string, string> { ["boy_name"] = "أحمد" }),
            CancellationToken.None);
        var guestSubmit = await service.SubmitAutobusAnswersAsync(
            guest,
            new AutobusSubmitAnswersRequest(
                created.SessionId,
                Guid.NewGuid().ToString("N"),
                started.Version,
                started.MatchNumber,
                started.Autobus!.CurrentRound,
                new Dictionary<string, string> { ["boy_name"] = "أمل" }),
            CancellationToken.None);

        Assert.True(hostSubmit.Succeeded);
        Assert.True(guestSubmit.Succeeded);
        Assert.Equal("أمل", Assert.Single(guestSubmit.Value!.Autobus!.Answers).DisplayAnswer);
        var recoveredHost = await service.RejoinAsync(host, created.SessionId, CancellationToken.None);
        Assert.Equal("أحمد", Assert.Single(recoveredHost.Value!.Autobus!.Answers).DisplayAnswer);

        var delayedEarlierRound = await service.SubmitAutobusAnswersAsync(
            host,
            new AutobusSubmitAnswersRequest(
                created.SessionId,
                Guid.NewGuid().ToString("N"),
                guestSubmit.Value.Version,
                guestSubmit.Value.MatchNumber,
                guestSubmit.Value.Autobus!.CurrentRound - 1,
                new Dictionary<string, string> { ["boy_name"] = "أيمن" }),
            CancellationToken.None);
        var replayFromEarlierMatch = await service.SubmitAutobusAnswersAsync(
            host,
            new AutobusSubmitAnswersRequest(
                created.SessionId,
                Guid.NewGuid().ToString("N"),
                guestSubmit.Value.Version,
                guestSubmit.Value.MatchNumber - 1,
                guestSubmit.Value.Autobus.CurrentRound,
                new Dictionary<string, string> { ["boy_name"] = "أيمن" }),
            CancellationToken.None);
        Assert.False(delayedEarlierRound.Succeeded);
        Assert.False(replayFromEarlierMatch.Succeeded);
    }

    [Fact]
    public async Task Reveal_snapshot_and_votes_are_limited_to_the_current_category()
    {
        var host = Identity("Host");
        var guest = Identity("Guest");
        var clock = new MutableTimeProvider(new DateTimeOffset(2026, 10, 4, 10, 0, 0, TimeSpan.Zero));
        await using var context = CreateContext();
        var service = CreateService(context, new RecordingRealtime(), clock);
        var created = (await service.CreateAsync(
            host,
            new CreateGameSessionRequest(
                "autobus-5x60-easy",
                "autobus",
                5,
                60,
                "easy",
                ["boy_name", "object"]),
            CancellationToken.None)).Value!;
        Assert.True((await service.JoinAsync(
            guest,
            new JoinGameSessionRequest(created.JoinCode),
            CancellationToken.None)).Succeeded);
        Assert.True((await service.ReadyAsync(host, created.SessionId, CancellationToken.None)).Succeeded);
        var started = (await service.ReadyAsync(guest, created.SessionId, CancellationToken.None)).Value!;
        var submitted = (await service.SubmitAutobusAnswersAsync(
            host,
            new AutobusSubmitAnswersRequest(
                created.SessionId,
                Guid.NewGuid().ToString("N"),
                started.Version,
                started.MatchNumber,
                started.Autobus!.CurrentRound,
                new Dictionary<string, string>
                {
                    ["boy_name"] = "أيمن",
                    ["object"] = "أرجوحة"
                }),
            CancellationToken.None)).Value!;
        var grace = (await service.FinishAutobusRoundAsync(
            host,
            new AutobusFinishRoundRequest(created.SessionId, Guid.NewGuid().ToString("N"), submitted.Version),
            CancellationToken.None)).Value!;
        clock.Advance(TimeSpan.FromSeconds(5));
        var reveal = (await service.FinishAutobusRoundAsync(
            guest,
            new AutobusFinishRoundRequest(created.SessionId, Guid.NewGuid().ToString("N"), grace.Version),
            CancellationToken.None)).Value!;

        Assert.Equal("boy_name", reveal.Autobus!.RevealCategoryKey);
        Assert.NotEmpty(reveal.Autobus.Answers);
        Assert.All(reveal.Autobus.Answers, answer => Assert.Equal("boy_name", answer.CategoryKey));
        var hiddenVote = await service.VoteAutobusAsync(
            guest,
            new AutobusVoteRequest(
                created.SessionId,
                Guid.NewGuid().ToString("N"),
                reveal.Version,
                host.MembershipId,
                "object",
                true),
            CancellationToken.None);
        Assert.False(hiddenVote.Succeeded);
        Assert.Equal("autobus_command_invalid", hiddenVote.ErrorCode);
    }

    [Fact]
    public async Task Vote_after_the_authoritative_deadline_is_rejected_without_changing_score()
    {
        var host = Identity("Host");
        var guest = Identity("Guest");
        var clock = new MutableTimeProvider(new DateTimeOffset(2026, 10, 4, 10, 30, 0, TimeSpan.Zero));
        await using var context = CreateContext();
        var service = CreateService(context, new RecordingRealtime(), clock);
        var created = (await service.CreateAsync(
            host,
            new CreateGameSessionRequest("autobus-5x60-easy", "autobus", 5, 60, "easy", ["object"]),
            CancellationToken.None)).Value!;
        Assert.True((await service.JoinAsync(guest, new JoinGameSessionRequest(created.JoinCode), CancellationToken.None)).Succeeded);
        Assert.True((await service.ReadyAsync(host, created.SessionId, CancellationToken.None)).Succeeded);
        var started = (await service.ReadyAsync(guest, created.SessionId, CancellationToken.None)).Value!;
        var submitted = (await service.SubmitAutobusAnswersAsync(
            host,
            new AutobusSubmitAnswersRequest(
                created.SessionId,
                Guid.NewGuid().ToString("N"),
                started.Version,
                started.MatchNumber,
                started.Autobus!.CurrentRound,
                new Dictionary<string, string> { ["object"] = "أرجوحة" }),
            CancellationToken.None)).Value!;
        var grace = (await service.FinishAutobusRoundAsync(
            host,
            new AutobusFinishRoundRequest(created.SessionId, Guid.NewGuid().ToString("N"), submitted.Version),
            CancellationToken.None)).Value!;
        clock.Advance(TimeSpan.FromSeconds(5));
        var reveal = (await service.FinishAutobusRoundAsync(
            guest,
            new AutobusFinishRoundRequest(created.SessionId, Guid.NewGuid().ToString("N"), grace.Version),
            CancellationToken.None)).Value!;
        Assert.True(Assert.Single(
            reveal.Autobus!.Answers,
            answer => answer.PlayerMembershipId == host.MembershipId).NeedsVote);

        clock.Advance(TimeSpan.FromSeconds(AutobusRuleset.VoteSeconds));
        var lateVote = await service.VoteAutobusAsync(
            guest,
            new AutobusVoteRequest(
                created.SessionId,
                Guid.NewGuid().ToString("N"),
                reveal.Version,
                host.MembershipId,
                "object",
                true),
            CancellationToken.None);

        Assert.False(lateVote.Succeeded);
        Assert.Equal("autobus_command_invalid", lateVote.ErrorCode);
        var recovered = (await service.RejoinAsync(host, created.SessionId, CancellationToken.None)).Value!;
        Assert.Equal(0, Assert.Single(
            recovered.Autobus!.Answers,
            answer => answer.PlayerMembershipId == host.MembershipId).Score);
    }

    [Fact]
    public async Task Equal_final_scores_produce_a_draw_without_an_arbitrary_winner()
    {
        var host = Identity("Host");
        var guest = Identity("Guest");
        var clock = new MutableTimeProvider(new DateTimeOffset(2026, 10, 4, 11, 0, 0, TimeSpan.Zero));
        await using var context = CreateContext();
        var service = CreateService(context, new RecordingRealtime(), clock);
        var created = (await service.CreateAsync(
            host,
            new CreateGameSessionRequest("autobus-5x60-easy", "autobus", 5, 60, "easy", ["boy_name"]),
            CancellationToken.None)).Value!;
        Assert.True((await service.JoinAsync(
            guest,
            new JoinGameSessionRequest(created.JoinCode),
            CancellationToken.None)).Succeeded);
        Assert.True((await service.ReadyAsync(host, created.SessionId, CancellationToken.None)).Succeeded);
        var snapshot = (await service.ReadyAsync(guest, created.SessionId, CancellationToken.None)).Value!;

        for (var round = 1; round <= 5; round++)
        {
            var grace = (await service.FinishAutobusRoundAsync(
                host,
                new AutobusFinishRoundRequest(created.SessionId, Guid.NewGuid().ToString("N"), snapshot.Version),
                CancellationToken.None)).Value!;
            clock.Advance(TimeSpan.FromSeconds(5));
            var reveal = (await service.FinishAutobusRoundAsync(
                guest,
                new AutobusFinishRoundRequest(created.SessionId, Guid.NewGuid().ToString("N"), grace.Version),
                CancellationToken.None)).Value!;
            snapshot = (await service.RevealAutobusAsync(
                host,
                new AutobusRevealRequest(created.SessionId, Guid.NewGuid().ToString("N"), reveal.Version),
                CancellationToken.None)).Value!;
        }

        Assert.Equal("completed", snapshot.Status);
        Assert.Equal("draw", snapshot.MatchStatus);
        Assert.Null(snapshot.WinnerMembershipId);
        Assert.All(snapshot.Autobus!.Scores, score => Assert.Equal(0, score.Score));

        var delayedReady = await service.ReadyAsync(host, created.SessionId, CancellationToken.None);

        Assert.False(delayedReady.Succeeded);
        Assert.Equal("session_not_waiting", delayedReady.ErrorCode);
        var recovered = (await service.RejoinAsync(host, created.SessionId, CancellationToken.None)).Value!;
        Assert.Equal("completed", recovered.Status);
        Assert.Equal("draw", recovered.MatchStatus);
    }

    private static GamesDbContext CreateContext() =>
        new(
            new DbContextOptionsBuilder<GamesDbContext>()
                .UseInMemoryDatabase(Guid.NewGuid().ToString("N"))
                .Options);

    private static GameSessionService CreateService(
        GamesDbContext context,
        IGameRealtimeNotifier realtime,
        TimeProvider timeProvider,
        IGameNotificationPublisher? notifications = null) =>
        new(
            context,
            new AllowFreeEntitlements(),
            realtime,
            notifications ?? new SilentNotifications(),
            timeProvider,
            NullLogger<GameSessionService>.Instance);

    private static ApplicationIdentityDescriptor Identity(string name) =>
        new(
            Guid.NewGuid(),
            null,
            $"guest:{Guid.NewGuid():N}",
            BotGlobalApplications.FamilyGames,
            name,
            true);

    private sealed class AllowFreeEntitlements : IGameEntitlementAuthorizer
    {
        public Task<bool> IsAllowedAsync(Guid membershipId, string? requiredEntitlement, CancellationToken cancellationToken) =>
            Task.FromResult(requiredEntitlement is null);
    }

    private sealed class SilentNotifications : IGameNotificationPublisher
    {
        public Task PublishAsync(GameSemanticNotification notification, CancellationToken cancellationToken) =>
            Task.CompletedTask;
    }

    private sealed class RecordingNotifications : IGameNotificationPublisher
    {
        public List<GameSemanticNotification> Items { get; } = [];

        public Task PublishAsync(GameSemanticNotification notification, CancellationToken cancellationToken)
        {
            Items.Add(notification);
            return Task.CompletedTask;
        }
    }

    private sealed class RecordingRealtime : IGameRealtimeNotifier
    {
        public List<GameSessionSnapshot> StateUpdates { get; } = [];

        public Task SessionCreatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerJoinedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerReadyAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task GameStartedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task MoveAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerConnectionChangedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task GameCompletedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RematchRequestedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RematchAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;

        public Task StateUpdatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken)
        {
            StateUpdates.Add(snapshot);
            return Task.CompletedTask;
        }
    }

    private sealed class MutableTimeProvider(DateTimeOffset initial) : TimeProvider
    {
        private DateTimeOffset _now = initial;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan duration) => _now += duration;
    }
}
