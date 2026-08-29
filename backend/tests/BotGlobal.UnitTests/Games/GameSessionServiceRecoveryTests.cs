using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application.Entitlements;
using BotGlobal.Games.Application.Sessions;
using BotGlobal.Games.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;

namespace BotGlobal.UnitTests.Games;

public sealed class GameSessionServiceRecoveryTests
{
    [Fact]
    public async Task Rejoin_discards_client_state_and_returns_persisted_authoritative_state()
    {
        var databaseName = Guid.NewGuid().ToString("N");
        var first = Identity("One");
        var second = Identity("Two");
        Guid sessionId;
        string commandId;

        await using (var context = CreateContext(databaseName))
        {
            var service = CreateService(context);
            var created = await service.CreateAsync(
                first,
                new CreateGameSessionRequest("classic-3x3"),
                CancellationToken.None);
            sessionId = created.Value!.SessionId;
            await service.JoinAsync(second, new JoinGameSessionRequest(created.Value.JoinCode), CancellationToken.None);
            await service.ReadyAsync(first, sessionId, CancellationToken.None);
            await service.ReadyAsync(second, sessionId, CancellationToken.None);
            commandId = Guid.NewGuid().ToString("N");
            var move = await service.MoveAsync(
                first,
                new XoMoveRequest(sessionId, commandId, 0, 0, 0),
                CancellationToken.None);
            Assert.True(move.Succeeded);
        }

        await using (var recoveredContext = CreateContext(databaseName))
        {
            var recoveredService = CreateService(recoveredContext);
            var recovery = await recoveredService.RejoinAsync(first, sessionId, CancellationToken.None);

            Assert.True(recovery.Succeeded);
            Assert.Equal(1, recovery.Value!.Version);
            Assert.Equal("x", recovery.Value.Board[0]);
            Assert.Equal(second.MembershipId, recovery.Value.ActivePlayerMembershipId);

            var duplicate = await recoveredService.MoveAsync(
                first,
                new XoMoveRequest(sessionId, commandId, 0, 0, 1),
                CancellationToken.None);
            Assert.Equal("duplicate_command", duplicate.ErrorCode);
        }
    }

    [Fact]
    public async Task Application_scope_is_derived_from_identity_and_cannot_cross_read_sessions()
    {
        var databaseName = Guid.NewGuid().ToString("N");
        var member = Identity("One");
        await using var context = CreateContext(databaseName);
        var service = CreateService(context);
        var created = await service.CreateAsync(
            member,
            new CreateGameSessionRequest("classic-3x3"),
            CancellationToken.None);
        var wrongApplication = member with { ApplicationKey = "another-application" };

        var result = await service.GetAsync(
            wrongApplication,
            created.Value!.SessionId,
            CancellationToken.None);

        Assert.Equal("session_not_found", result.ErrorCode);
    }

    [Fact]
    public async Task Disconnect_and_rejoin_publish_authoritative_generic_presence_snapshots()
    {
        var first = Identity("One");
        var second = Identity("Two");
        var realtime = new RecordingRealtime();
        var clock = new MutableTimeProvider(new DateTimeOffset(2026, 8, 29, 10, 0, 0, TimeSpan.Zero));
        await using var context = CreateContext(Guid.NewGuid().ToString("N"));
        var service = CreateService(context, realtime, clock);
        var created = await service.CreateAsync(
            first,
            new CreateGameSessionRequest("classic-3x3"),
            CancellationToken.None);
        var sessionId = created.Value!.SessionId;
        await service.JoinAsync(second, new JoinGameSessionRequest(created.Value.JoinCode), CancellationToken.None);
        realtime.ConnectionChanges.Clear();

        clock.Advance(TimeSpan.FromSeconds(1));
        var disconnected = await service.SetDisconnectedAsync(second.MembershipId, sessionId, CancellationToken.None);
        clock.Advance(TimeSpan.FromSeconds(1));
        var rejoined = await service.RejoinAsync(second, sessionId, CancellationToken.None);

        Assert.True(disconnected.Succeeded);
        Assert.True(rejoined.Succeeded);
        Assert.Collection(
            realtime.ConnectionChanges,
            snapshot => Assert.False(snapshot.Players.Single(x => x.MembershipId == second.MembershipId).IsConnected),
            snapshot => Assert.True(snapshot.Players.Single(x => x.MembershipId == second.MembershipId).IsConnected));
        Assert.True(realtime.ConnectionChanges[1].Revision > realtime.ConnectionChanges[0].Revision);
    }

    [Fact]
    public void Fast_transport_replacement_does_not_report_participant_disconnected()
    {
        var registry = new BotGlobal.Games.Realtime.GameConnectionRegistry();
        var member = Guid.NewGuid();
        var session = Guid.NewGuid();
        registry.Connected("old", member);
        registry.Joined("old", session);
        registry.Connected("new", member);
        registry.Joined("new", session);

        var oldTransportClosed = registry.Disconnected("old");
        var newTransportClosed = registry.Disconnected("new");

        Assert.NotNull(oldTransportClosed);
        Assert.Empty(oldTransportClosed.Value.SessionIds);
        Assert.NotNull(newTransportClosed);
        Assert.Equal(session, Assert.Single(newTransportClosed.Value.SessionIds));
    }

    private static GamesDbContext CreateContext(string databaseName) =>
        new(
            new DbContextOptionsBuilder<GamesDbContext>()
                .UseInMemoryDatabase(databaseName)
                .Options);

    private static GameSessionService CreateService(
        GamesDbContext context,
        IGameRealtimeNotifier? realtime = null,
        TimeProvider? timeProvider = null) =>
        new(
            context,
            new AllowFreeEntitlements(),
            realtime ?? new SilentRealtime(),
            new SilentNotifications(),
            timeProvider ?? TimeProvider.System,
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

    private sealed class SilentRealtime : IGameRealtimeNotifier
    {
        public Task SessionCreatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerJoinedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerReadyAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task GameStartedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task StateUpdatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task MoveAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerConnectionChangedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task GameCompletedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RematchRequestedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RematchAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
    }

    private sealed class RecordingRealtime : IGameRealtimeNotifier
    {
        public List<GameSessionSnapshot> ConnectionChanges { get; } = [];
        public Task SessionCreatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerJoinedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerReadyAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task GameStartedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task StateUpdatedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task MoveAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task PlayerConnectionChangedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken)
        {
            ConnectionChanges.Add(snapshot);
            return Task.CompletedTask;
        }
        public Task GameCompletedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RematchRequestedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RematchAcceptedAsync(GameSessionSnapshot snapshot, CancellationToken cancellationToken) => Task.CompletedTask;
    }

    private sealed class MutableTimeProvider(DateTimeOffset initial) : TimeProvider
    {
        private DateTimeOffset _now = initial;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan duration) => _now += duration;
    }
}
