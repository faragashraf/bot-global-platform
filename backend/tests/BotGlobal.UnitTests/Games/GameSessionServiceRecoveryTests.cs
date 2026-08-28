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

    private static GamesDbContext CreateContext(string databaseName) =>
        new(
            new DbContextOptionsBuilder<GamesDbContext>()
                .UseInMemoryDatabase(databaseName)
                .Options);

    private static GameSessionService CreateService(GamesDbContext context) =>
        new(
            context,
            new AllowFreeEntitlements(),
            new SilentRealtime(),
            new SilentNotifications(),
            TimeProvider.System,
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
}
