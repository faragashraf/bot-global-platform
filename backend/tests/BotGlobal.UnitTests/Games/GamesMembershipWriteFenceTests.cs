using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application;
using BotGlobal.Games.Application.Entitlements;
using BotGlobal.Games.Application.Invitations;
using BotGlobal.Games.Application.Sessions;
using BotGlobal.Games.Domain.Invitations;
using BotGlobal.Games.Domain.Sessions;
using BotGlobal.Games.Domain.Xo;
using BotGlobal.Games.Infrastructure.Persistence;
using BotGlobal.Games.Realtime;
using BotGlobal.Games.Realtime.Voice;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Games;

public sealed class GamesMembershipWriteFenceTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-09-15T00:00:00Z");

    [Fact]
    public async Task Mutation_that_enters_first_commits_before_barrier_and_is_then_deleted()
    {
        var root = new InMemoryDatabaseRoot();
        var database = Guid.NewGuid().ToString("N");
        var processLock = new GamesMembershipFenceLock();
        var member = Guid.NewGuid();
        await using var mutationDb = Context(database, root);
        await using var deletionDb = Context(database, root);
        var mutationFence = new GamesMembershipWriteFence(mutationDb, processLock);
        var deletionFence = new GamesMembershipWriteFence(deletionDb, processLock);
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        var mutation = mutationFence.ExecuteAsync(
            BotGlobalApplications.FamilyGames,
            member,
            async () =>
            {
                entered.SetResult();
                await release.Task;
                mutationDb.Sessions.Add(Session(member, "BEFORE"));
                await mutationDb.SaveChangesAsync();
                return true;
            },
            () => false,
            CancellationToken.None);
        await entered.Task;

        var deletion = new GamesAccountDeletionHandler(
            deletionDb,
            new GameConnectionRegistry(),
            new VoiceConnectionRegistry(),
            new VoiceConsentRegistry(),
            new SilentHub(),
            deletionFence,
            new FixedTimeProvider()).DeleteAsync(Scope(member), CancellationToken.None);
        Assert.False(deletion.IsCompleted);

        release.SetResult();
        Assert.True(await mutation);
        await deletion;

        Assert.Empty(await deletionDb.Sessions.ToListAsync());
        Assert.True(await deletionDb.MembershipDeletionFences.AnyAsync(x => x.MembershipId == member));
    }

    [Fact]
    public async Task Other_player_stale_move_cannot_restore_deleted_membership_after_deletion_succeeds()
    {
        var root = new InMemoryDatabaseRoot();
        var database = Guid.NewGuid().ToString("N");
        var processLock = new GamesMembershipFenceLock();
        var owner = Guid.NewGuid();
        var deleted = Guid.NewGuid();
        var session = Session(owner, "CROSS-RACE");
        session.AddPlayer(deleted, "Delete me", Now);
        session.SetReady(owner, Now);
        session.SetReady(deleted, Now);
        var state = new XoSessionState(session.Id, XoRuleset.Classic);
        state.Reset(owner);
        await using (var seed = Context(database, root))
        {
            seed.AddRange(session, state, new GameInvitation(
                Guid.NewGuid(),
                session.Id,
                BotGlobalApplications.FamilyGames,
                GameInvitationService.Hash("race-invitation"),
                deleted,
                Now,
                Now.AddHours(1)));
            await seed.SaveChangesAsync();
        }

        var saveGate = new BlockingSaveChangesInterceptor();
        await using var writerDb = Context(database, root, saveGate);
        await using var deletionDb = Context(database, root);
        var writer = SessionService(writerDb, new GamesMembershipWriteFence(writerDb, processLock));
        var staleMove = writer.MoveAsync(
            Identity(owner),
            new XoMoveRequest(session.Id, "cross-player-race", 0, 0, 0),
            CancellationToken.None);
        await saveGate.Entered.Task;

        await new GamesAccountDeletionHandler(
            deletionDb,
            new GameConnectionRegistry(),
            new VoiceConnectionRegistry(),
            new VoiceConsentRegistry(),
            new SilentHub(),
            new GamesMembershipWriteFence(deletionDb, processLock),
            new FixedTimeProvider()).DeleteAsync(Scope(deleted), CancellationToken.None);

        saveGate.Release.SetResult();
        var moveResult = await staleMove;
        Assert.Equal("concurrent_move", moveResult.ErrorCode);

        await using var verification = Context(database, root);
        Assert.False(await verification.Sessions.AnyAsync(value =>
            value.CreatedByMembershipId == deleted || value.RematchRequestedByMembershipId == deleted));
        Assert.False(await verification.Players.AnyAsync(value => value.MembershipId == deleted));
        Assert.False(await verification.XoStates.AnyAsync(value =>
            value.ActivePlayerMembershipId == deleted || value.WinnerMembershipId == deleted));
        Assert.False(await verification.XoMoves.AnyAsync(value => value.PlayerMembershipId == deleted));
        Assert.False(await verification.Invitations.AnyAsync(value => value.CreatedByMembershipId == deleted));
    }

    [Fact]
    public async Task Committed_barrier_rejects_late_write_after_service_and_lock_restart()
    {
        var root = new InMemoryDatabaseRoot();
        var database = Guid.NewGuid().ToString("N");
        var member = Guid.NewGuid();
        await using (var first = Context(database, root))
        {
            await new GamesMembershipWriteFence(first, new GamesMembershipFenceLock())
                .EstablishDeletionBarrierAsync(
                    BotGlobalApplications.FamilyGames,
                    member,
                    Now,
                    CancellationToken.None);
        }

        await using var restarted = Context(database, root);
        var service = SessionService(
            restarted,
            new GamesMembershipWriteFence(restarted, new GamesMembershipFenceLock()));
        var result = await service.CreateAsync(Identity(member), new("classic-3x3"), CancellationToken.None);

        Assert.Equal("account_deleted", result.ErrorCode);
        Assert.Equal(410, result.StatusCode);
        Assert.Empty(await restarted.Sessions.ToListAsync());
    }

    [Fact]
    public async Task Every_membership_owned_command_is_rejected_after_barrier()
    {
        var root = new InMemoryDatabaseRoot();
        var database = Guid.NewGuid().ToString("N");
        var member = Guid.NewGuid();
        var owner = Guid.NewGuid();
        await using var db = Context(database, root);
        var session = Session(owner, "FENCED");
        var state = new XoSessionState(session.Id, XoRuleset.Classic);
        var invitation = new GameInvitation(
            Guid.NewGuid(),
            session.Id,
            BotGlobalApplications.FamilyGames,
            GameInvitationService.Hash("valid-token"),
            owner,
            Now,
            Now.AddHours(1));
        db.AddRange(session, state, invitation);
        await db.SaveChangesAsync();
        var processLock = new GamesMembershipFenceLock();
        var fence = new GamesMembershipWriteFence(db, processLock);
        await fence.EstablishDeletionBarrierAsync(
            BotGlobalApplications.FamilyGames,
            member,
            Now,
            CancellationToken.None);
        var identity = Identity(member);
        var sessions = SessionService(db, fence);
        var invitations = new GameInvitationService(
            db,
            sessions,
            new FreeEntitlements(),
            new FixedTimeProvider(),
            Options.Create(new GameInvitationOptions
            {
                LifetimeMinutes = 10,
                DeepLinkBase = "familygames://invite"
            }),
            NullLogger<GameInvitationService>.Instance,
            fence);

        var results = new[]
        {
            await sessions.CreateAsync(identity, new("classic-3x3"), CancellationToken.None),
            await sessions.JoinAsync(identity, new("FENCED"), CancellationToken.None),
            await sessions.ReadyAsync(identity, session.Id, CancellationToken.None),
            await sessions.MoveAsync(identity, new(session.Id, "move", 0, 0, 0), CancellationToken.None),
            await sessions.RejoinAsync(identity, session.Id, CancellationToken.None),
            await sessions.SetDisconnectedAsync(member, session.Id, CancellationToken.None),
            await sessions.RequestRematchAsync(identity, session.Id, CancellationToken.None),
            await sessions.AcceptRematchAsync(identity, session.Id, CancellationToken.None),
        };

        Assert.All(results, result => Assert.Equal("account_deleted", result.ErrorCode));
        Assert.Equal("account_deleted", (await invitations.CreateAsync(identity, session.Id, CancellationToken.None)).ErrorCode);
        Assert.Equal(
            "account_deleted",
            (await invitations.ResolveAsync(identity, new("valid-token"), CancellationToken.None)).ErrorCode);
        Assert.DoesNotContain(await db.Players.ToListAsync(), player => player.MembershipId == member);
    }

    [Fact]
    public async Task Expired_invitation_write_is_rejected_without_mutating_after_barrier()
    {
        var root = new InMemoryDatabaseRoot();
        var database = Guid.NewGuid().ToString("N");
        var member = Guid.NewGuid();
        var owner = Guid.NewGuid();
        await using var db = Context(database, root);
        var session = Session(owner, "EXPIRED");
        var invitation = new GameInvitation(
            Guid.NewGuid(),
            session.Id,
            BotGlobalApplications.FamilyGames,
            GameInvitationService.Hash("expired-token"),
            owner,
            Now.AddHours(-2),
            Now.AddHours(-1));
        db.AddRange(session, new XoSessionState(session.Id, XoRuleset.Classic), invitation);
        await db.SaveChangesAsync();
        var fence = new GamesMembershipWriteFence(db, new GamesMembershipFenceLock());
        await fence.EstablishDeletionBarrierAsync(
            BotGlobalApplications.FamilyGames,
            member,
            Now,
            CancellationToken.None);
        var sessions = SessionService(db, fence);
        var invitations = new GameInvitationService(
            db,
            sessions,
            new FreeEntitlements(),
            new FixedTimeProvider(),
            Options.Create(new GameInvitationOptions
            {
                LifetimeMinutes = 10,
                DeepLinkBase = "familygames://invite"
            }),
            NullLogger<GameInvitationService>.Instance,
            fence);

        var result = await invitations.ResolveAsync(
            Identity(member),
            new("expired-token"),
            CancellationToken.None);

        Assert.Equal("account_deleted", result.ErrorCode);
        Assert.Null((await db.Invitations.SingleAsync()).RevokedAtUtc);
    }

    private static GamesDbContext Context(string database, InMemoryDatabaseRoot root) =>
        new(new DbContextOptionsBuilder<GamesDbContext>().UseInMemoryDatabase(database, root).Options);

    private static GamesDbContext Context(
        string database,
        InMemoryDatabaseRoot root,
        SaveChangesInterceptor interceptor) =>
        new(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(database, root)
            .AddInterceptors(interceptor)
            .Options);

    private static GameSessionService SessionService(GamesDbContext db, IGamesMembershipWriteFence fence) =>
        new(
            db,
            new FreeEntitlements(),
            new SilentRealtime(),
            new SilentNotifications(),
            new FixedTimeProvider(),
            NullLogger<GameSessionService>.Instance,
            fence);

    private static GameSession Session(Guid owner, string code)
    {
        var session = new GameSession(
            Guid.NewGuid(),
            BotGlobalApplications.FamilyGames,
            code,
            "xo",
            "classic-3x3",
            2,
            owner,
            Now);
        session.AddPlayer(owner, "Owner", Now);
        return session;
    }

    private static ApplicationIdentityDescriptor Identity(Guid membershipId) =>
        new(membershipId, null, "guest:test", BotGlobalApplications.FamilyGames, "Player", true);

    private static ApplicationAccountDeletionScope Scope(Guid member) =>
        new(new(member, Guid.NewGuid(), "test-subject", BotGlobalApplications.FamilyGames), []);

    private sealed class FixedTimeProvider : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private sealed class BlockingSaveChangesInterceptor : SaveChangesInterceptor
    {
        private int _blocked;
        public TaskCompletionSource Entered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource Release { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public override async ValueTask<InterceptionResult<int>> SavingChangesAsync(
            DbContextEventData eventData,
            InterceptionResult<int> result,
            CancellationToken cancellationToken = default)
        {
            if (Interlocked.Exchange(ref _blocked, 1) == 0)
            {
                Entered.SetResult();
                await Release.Task.WaitAsync(cancellationToken);
            }
            return result;
        }
    }

    private sealed class FreeEntitlements : IGameEntitlementAuthorizer
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

    private sealed class SilentHub : IHubContext<GamesHub>
    {
        public IHubClients Clients => throw new NotSupportedException();
        public IGroupManager Groups => new SilentGroups();

        private sealed class SilentGroups : IGroupManager
        {
            public Task AddToGroupAsync(string connectionId, string groupName, CancellationToken cancellationToken = default) =>
                Task.CompletedTask;
            public Task RemoveFromGroupAsync(string connectionId, string groupName, CancellationToken cancellationToken = default) =>
                Task.CompletedTask;
        }
    }
}
