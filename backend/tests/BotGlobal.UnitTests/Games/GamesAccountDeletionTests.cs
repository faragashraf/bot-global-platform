using System.Security.Claims;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application;
using BotGlobal.Games.Application.Sessions;
using BotGlobal.Games.Domain.Autobus;
using BotGlobal.Games.Domain.Invitations;
using BotGlobal.Games.Domain.Sessions;
using BotGlobal.Games.Domain.Xo;
using BotGlobal.Games.Infrastructure.Persistence;
using BotGlobal.Games.Realtime;
using BotGlobal.Games.Realtime.Voice;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.SignalR;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Games;

public sealed class GamesAccountDeletionTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-09-15T00:00:00Z");

    [Fact]
    public async Task Deletes_owned_aggregates_anonymizes_all_retained_references_and_is_retry_safe()
    {
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var member = Guid.NewGuid();
        var other = Guid.NewGuid();
        var owned = Session(member, "OWNED");
        var retained = Session(other, "OTHER");
        retained.AddPlayer(member, "Personal display name", Now);
        retained.Complete(Now);
        retained.RequestRematch(member, Now);
        var another = Session(other, "THIRD");
        another.AddPlayer(member, "Personal display name", Now);
        var unrelated = Session(other, "UNRELATED");
        var nqrb = Session(member, "NQRB", BotGlobalApplications.Nqrb);
        db.Sessions.AddRange(owned, retained, another, unrelated, nqrb);
        var state = new XoSessionState(retained.Id, XoRuleset.Classic);
        state.Reset(member);
        db.XoStates.Add(state);
        var autobusRuleset = new AutobusRuleset(
            "autobus-test",
            5,
            60,
            "easy",
            [AutobusCategories.BoyName]);
        var retainedAutobus = new AutobusSessionState(retained.Id, autobusRuleset);
        retainedAutobus.StartFirstRound(autobusRuleset, [other, member], Now);
        retainedAutobus.Submit(
            member,
            new Dictionary<string, string> { [AutobusCategories.BoyName.Key] = "أحمد" },
            Now);
        var ownedAutobus = new AutobusSessionState(owned.Id, autobusRuleset);
        db.AutobusStates.AddRange(retainedAutobus, ownedAutobus);
        var originalCommandId = Guid.NewGuid().ToString("N");
        db.AutobusCommands.AddRange(
            new AutobusCommand(Guid.NewGuid(), retained.Id, originalCommandId, member, "submit", 1, Now),
            new AutobusCommand(Guid.NewGuid(), owned.Id, Guid.NewGuid().ToString("N"), member, "submit", 1, Now));
        db.XoMoves.AddRange(Move(owned, member), Move(retained, member), Move(retained, other), Move(nqrb, member));
        db.Invitations.AddRange(Invitation(owned, other), Invitation(retained, member), Invitation(retained, other), Invitation(nqrb, member));
        await db.SaveChangesAsync();
        var games = new GameConnectionRegistry();
        var voice = new VoiceConnectionRegistry();
        var consent = new VoiceConsentRegistry();
        games.Connected("deleting", member);
        games.Joined("deleting", retained.Id);
        games.Connected("other", other);
        games.Joined("other", retained.Id);
        voice.Join("deleting", retained.Id, member, 1, true);
        voice.Join("other", retained.Id, other, 1, false);
        var request = consent.RequestVoice(retained.Id, 1, member, other, Now, TimeSpan.FromMinutes(1)).Request;
        consent.Accept(request.RequestId, retained.Id, 1, other, Now);
        var handler = new GamesAccountDeletionHandler(db, games, voice, consent, new TestHub());

        await handler.DeleteAsync(Scope(member), default);
        db.ChangeTracker.Clear();
        var result = await db.Sessions.Include(session => session.Players).SingleAsync(session => session.Id == retained.Id);
        var anonymous = result.Players.Single(player => player.MembershipId != other);
        Assert.NotEqual(member, anonymous.MembershipId);
        Assert.NotEqual(Guid.Empty, anonymous.MembershipId);
        Assert.Equal("Deleted player", anonymous.DisplayName);
        Assert.False(anonymous.IsConnected);
        Assert.Null(result.RematchRequestedByMembershipId);
        Assert.Equal(other, result.CreatedByMembershipId);
        Assert.Equal("Owner", result.Players.Single(player => player.MembershipId == other).DisplayName);
        Assert.Equal(anonymous.MembershipId, (await db.XoStates.SingleAsync()).ActivePlayerMembershipId);
        var retainedAutobusAfterDeletion = await db.AutobusStates.SingleAsync();
        Assert.Equal(anonymous.MembershipId, Assert.Single(retainedAutobusAfterDeletion.Answers).PlayerMembershipId);
        Assert.Equal(string.Empty, Assert.Single(retainedAutobusAfterDeletion.Answers).DisplayAnswer);
        var retainedCommand = await db.AutobusCommands.SingleAsync();
        Assert.Equal(anonymous.MembershipId, retainedCommand.PlayerMembershipId);
        Assert.NotEqual(originalCommandId, retainedCommand.CommandId);
        Assert.Equal(anonymous.MembershipId, (await db.XoMoves.SingleAsync(move => move.SessionId == retained.Id && move.PlayerMembershipId != other)).PlayerMembershipId);
        Assert.NotEqual(anonymous.MembershipId, (await db.Players.SingleAsync(player => player.SessionId == another.Id && player.MembershipId != other)).MembershipId);
        Assert.False(await db.Sessions.AnyAsync(session => session.Id == owned.Id));
        Assert.False(await db.XoMoves.AnyAsync(move => move.SessionId == owned.Id));
        Assert.False(await db.AutobusStates.AnyAsync(autobus => autobus.SessionId == owned.Id));
        Assert.False(await db.AutobusCommands.AnyAsync(command => command.SessionId == owned.Id));
        Assert.False(await db.Invitations.AnyAsync(invitation => invitation.SessionId == owned.Id));
        Assert.Single(await db.Invitations.Where(invitation => invitation.SessionId == retained.Id).ToListAsync());
        Assert.True(await db.Players.AnyAsync(player => player.SessionId == nqrb.Id && player.MembershipId == member));
        Assert.Null(games.ResolveParticipantConnection(retained.Id, member));
        Assert.Equal("other", games.ResolveParticipantConnection(retained.Id, other));
        Assert.Throws<InvalidOperationException>(() => voice.RequireCurrent("deleting", retained.Id, member, 1));
        Assert.NotNull(voice.RequireCurrent("other", retained.Id, other, 1));
        Assert.Null(consent.Current(retained.Id, 1, other, Now));
        Assert.Throws<InvalidOperationException>(() => games.Connected("late", member));
        Assert.Throws<InvalidOperationException>(() => voice.Join("late", retained.Id, member, 2, true));
        Assert.Throws<InvalidOperationException>(() => consent.RequestVoice(retained.Id, 1, other, member, Now, TimeSpan.FromMinutes(1)));

        await handler.DeleteAsync(Scope(member), default);
        Assert.Equal(anonymous.MembershipId, (await db.Players.SingleAsync(player => player.Id == anonymous.Id)).MembershipId);
    }

    [Theory]
    [InlineData(BotGlobalApplications.Nqrb)]
    [InlineData("another-application")]
    public async Task Non_family_games_scope_is_a_complete_noop(string applicationKey)
    {
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var member = Guid.NewGuid();
        var session = Session(member, "KEPT");
        db.Sessions.Add(session);
        await db.SaveChangesAsync();
        var games = new GameConnectionRegistry();
        games.Connected("kept", member);
        games.Joined("kept", session.Id);
        var voice = new VoiceConnectionRegistry();
        var consent = new VoiceConsentRegistry();
        var peer = Guid.NewGuid();
        var participant = voice.Join("kept", session.Id, member, 7, true).Current;
        var request = consent.RequestVoice(session.Id, 1, member, peer, Now, TimeSpan.FromMinutes(1)).Request;
        var hub = new TestHub();
        await new GamesAccountDeletionHandler(db, games, voice, consent, hub)
            .DeleteAsync(Scope(member, applicationKey), default);
        Assert.Equal(participant, voice.RequireCurrent("kept", session.Id, member, 7));
        Assert.Equal(request, consent.Current(session.Id, 1, member, Now));
        Assert.Empty(hub.Sent);
        Assert.Empty(hub.Removed);
        Assert.Equal(8, voice.Join("reconnected", session.Id, member, 8, true).Current.Generation);
        Assert.Equal(VoiceConsentRegistry.Status.Accepted,
            consent.Accept(request.RequestId, session.Id, 1, peer, Now).Status);
        Assert.Single(await db.Sessions.ToListAsync());
        Assert.False(games.IsRevoked(member));
        Assert.Equal("kept", games.ResolveParticipantConnection(session.Id, member));
    }

    [Fact]
    public async Task Relational_cascades_delete_unloaded_owned_children_and_preserve_other_sessions()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>().UseSqlite(connection).Options);
        await db.Database.EnsureCreatedAsync();
        var member = Guid.NewGuid();
        var owned = Session(member, "OWNED");
        var other = Session(Guid.NewGuid(), "KEPT");
        db.Sessions.AddRange(owned, other);
        db.XoMoves.Add(Move(owned, member));
        db.Invitations.Add(Invitation(owned, member));
        await db.SaveChangesAsync();
        db.ChangeTracker.Clear();
        var games = new GameConnectionRegistry();
        games.Connected("other", other.CreatedByMembershipId);
        games.Joined("other", owned.Id);
        games.Joined("other", other.Id);
        await new GamesAccountDeletionHandler(db, games, new(), new(), new TestHub()).DeleteAsync(Scope(member), default);
        Assert.Equal(other.Id, (await db.Sessions.SingleAsync()).Id);
        Assert.Empty(await db.XoMoves.ToListAsync());
        Assert.Empty(await db.Invitations.ToListAsync());
        Assert.Single(await db.Players.ToListAsync());
        Assert.Throws<InvalidOperationException>(() => games.Joined("other", owned.Id));
        Assert.Equal("other", games.ResolveParticipantConnection(other.Id, other.CreatedByMembershipId));
    }

    [Fact]
    public async Task Failed_group_removal_keeps_retry_evidence_and_blocks_reentry()
    {
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var member = Guid.NewGuid();
        var session = Session(Guid.NewGuid(), "RETAINED");
        session.AddPlayer(member, "Delete me", Now);
        db.Sessions.Add(session);
        await db.SaveChangesAsync();
        var games = new GameConnectionRegistry();
        games.Connected("deleting", member);
        games.Joined("deleting", session.Id);
        var other = session.CreatedByMembershipId;
        games.Connected("peer", other);
        games.Joined("peer", session.Id);
        var voice = new VoiceConnectionRegistry();
        voice.Join("deleting", session.Id, member, 5, true);
        voice.Join("peer", session.Id, other, 9, false);
        var consent = new VoiceConsentRegistry();
        consent.RequestVoice(session.Id, 1, member, other, Now, TimeSpan.FromMinutes(1));
        var hub = new TestHub { FailRemoval = true };
        var handler = new GamesAccountDeletionHandler(db, games, voice, consent, hub);
        await Assert.ThrowsAsync<InvalidOperationException>(() => handler.DeleteAsync(Scope(member), default));
        Assert.True(games.IsRevoked(member));
        Assert.True(await db.Players.AnyAsync(player => player.MembershipId == member));
        Assert.Empty(hub.Sent);
        Assert.Single(voice.RevokeMembership(member, []));
        Assert.Single(consent.RevokeMembership(member, [], games));
        hub.FailRemoval = false;
        await handler.DeleteAsync(Scope(member), default);
        Assert.Equal(2, hub.Sent.Count);
        Assert.Contains(("deleting", GamesHub.GroupName(session.Id)), hub.Removed);
        Assert.False(await db.Players.AnyAsync(player => player.MembershipId == member));
    }

    [Fact]
    public async Task Partial_group_cleanup_retry_skips_each_successfully_acknowledged_route()
    {
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var member = Guid.NewGuid();
        var first = Session(Guid.NewGuid(), "FIRST");
        first.AddPlayer(member, "Delete me", Now);
        var second = Session(Guid.NewGuid(), "SECOND");
        second.AddPlayer(member, "Delete me", Now);
        db.Sessions.AddRange(first, second);
        await db.SaveChangesAsync();
        var games = new GameConnectionRegistry();
        games.Connected("deleting", member);
        games.Joined("deleting", first.Id);
        games.Joined("deleting", second.Id);
        var hub = new TestHub { FailRemovalAt = 2 };
        var handler = new GamesAccountDeletionHandler(db, games, new(), new(), hub);

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            handler.DeleteAsync(Scope(member), CancellationToken.None));
        await handler.DeleteAsync(Scope(member), CancellationToken.None);

        Assert.Equal(3, hub.RemovalAttempts);
        Assert.Equal(2, hub.Removed.Count);
        Assert.Empty(games.RevokeMembership(member, []));
    }

    [Fact]
    public async Task Delayed_group_add_after_completed_deletion_failed_removal_eventually_drains()
    {
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        await using var identityDb = new IdentityDbContext(new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var member = Guid.NewGuid();
        var participant = Guid.NewGuid();
        var session = Session(member, "LATE");
        session.AddPlayer(participant, "Other player", Now);
        db.Sessions.Add(session);
        await db.SaveChangesAsync();
        var user = new ApplicationUser(Guid.NewGuid(), "late-owner", "late@example.test", "Late owner");
        var membership = new ApplicationMembership(member, BotGlobalApplications.FamilyGames,
            "late-owner", "Late owner", user.Id, false, Now);
        var operation = new ApplicationAccountDeletionRequest(Guid.NewGuid(), member, user.Id,
            membership.ApplicationKey, membership.SubjectId, [], Now);
        identityDb.AddRange(user, membership, operation);
        await identityDb.SaveChangesAsync();
        var games = new GameConnectionRegistry();
        games.Connected("late-rejoin", participant);
        var hub = new TestHub { BlockAdd = true };
        var realtimeHub = new GamesHub(
            new RejoinSessionService(Snapshot(session.Id, participant)),
            games,
            new VoiceConnectionRegistry(),
            new VoiceConsentRegistry(),
            new UnusedVoiceIceConfigurationProvider(),
            Options.Create(new VoiceConsentOptions()),
            TimeProvider.System,
            hub,
            NullLogger<GamesHub>.Instance)
        {
            Context = new TestHubCallerContext("late-rejoin", Principal(participant)),
            Groups = hub,
            Clients = hub,
        };
        var handler = new GamesAccountDeletionHandler(db, games, new(), new(), hub);
        var processor = new ApplicationAccountDeletionProcessor(identityDb, [handler],
            TimeProvider.System, NullLogger<ApplicationAccountDeletionProcessor>.Instance);

        var delayedRejoin = realtimeHub.Rejoin(session.Id);
        await hub.AddStarted.Task;

        Assert.Equal(ApplicationAccountDeletionProcessOutcome.Completed,
            await processor.TryProcessAsync(operation.Id, CancellationToken.None));
        Assert.Empty(await identityDb.AccountDeletionRequests.ToListAsync());
        Assert.Empty(await db.Sessions.ToListAsync());
        Assert.Empty(games.PendingRevokedPresence(10));

        // The actual in-flight Rejoin group add completes after the handler and
        // Identity processor have both completed. Its corrective removal fails.
        hub.FailRemoval = true;
        hub.ReleaseAdd.TrySetResult(true);
        await Assert.ThrowsAsync<InvalidOperationException>(() => delayedRejoin);
        Assert.Single(games.PendingRevokedPresence(10));

        var clock = new MutableTimeProvider(Now);
        var cleaner = new RevokedGamePresenceCleanupService(
            games,
            hub,
            NullLogger<RevokedGamePresenceCleanupService>.Instance,
            clock);
        Assert.Equal(0, await cleaner.DrainOnceAsync(CancellationToken.None));
        Assert.Single(games.PendingRevokedPresence(10));
        hub.FailRemoval = false;
        clock.Advance(TimeSpan.FromSeconds(30));
        Assert.Equal(1, await cleaner.DrainOnceAsync(CancellationToken.None));

        Assert.Equal(4, hub.RemovalAttempts);
        Assert.Single(hub.Removed);
        Assert.Empty(games.PendingRevokedPresence(10));
    }

    [Fact]
    public void Older_route_acknowledgement_cannot_complete_newer_same_tuple_recording()
    {
        var games = new GameConnectionRegistry();
        var owner = Guid.NewGuid();
        var participant = Guid.NewGuid();
        var sessionId = Guid.NewGuid();
        games.RevokeMembership(owner, [sessionId]);

        var older = games.RecordLateRevokedPresence(participant, "same-connection", sessionId);
        var newer = games.RecordLateRevokedPresence(participant, "same-connection", sessionId);
        games.CompleteRevokedPresence(older);

        Assert.Equal(newer, Assert.Single(games.PendingRevokedPresence(10)));
        games.CompleteRevokedPresence(newer);
        Assert.Empty(games.PendingRevokedPresence(10));
    }

    [Fact]
    public async Task Background_route_cleanup_retries_after_exhaustion_and_eventually_drains()
    {
        var games = new GameConnectionRegistry();
        var owner = Guid.NewGuid();
        var sessionId = Guid.NewGuid();
        games.RevokeMembership(owner, [sessionId]);
        games.RecordLateRevokedPresence(Guid.NewGuid(), "failed-connection", sessionId);
        var hub = new TestHub { FailRemoval = true };
        var clock = new MutableTimeProvider(Now);
        var cleaner = new RevokedGamePresenceCleanupService(
            games,
            hub,
            NullLogger<RevokedGamePresenceCleanupService>.Instance,
            clock);

        Assert.Equal(0, await cleaner.DrainOnceAsync(CancellationToken.None));
        for (var hotLoopAttempt = 0; hotLoopAttempt < 4; hotLoopAttempt++)
            Assert.Equal(0, await cleaner.DrainOnceAsync(CancellationToken.None));
        Assert.Equal(1, hub.RemovalAttempts);

        for (var attempt = 1; attempt < 8; attempt++)
        {
            clock.Advance(TimeSpan.FromSeconds(30));
            Assert.Equal(0, await cleaner.DrainOnceAsync(CancellationToken.None));
        }

        Assert.Equal(8, hub.RemovalAttempts);
        Assert.Single(games.PendingRevokedPresence(10));

        // Attempt eight is a backoff boundary, never a terminal exclusion.
        hub.FailRemoval = false;
        Assert.Equal(0, await cleaner.DrainOnceAsync(CancellationToken.None));
        clock.Advance(TimeSpan.FromSeconds(30));
        Assert.Equal(1, await cleaner.DrainOnceAsync(CancellationToken.None));

        Assert.Equal(9, hub.RemovalAttempts);
        Assert.Empty(games.PendingRevokedPresence(10));
    }

    [Theory]
    [InlineData(false, false, false)]
    [InlineData(false, false, true)]
    [InlineData(false, true, false)]
    [InlineData(false, true, true)]
    [InlineData(true, false, false)]
    [InlineData(true, false, true)]
    [InlineData(true, true, false)]
    [InlineData(true, true, true)]
    public async Task Revocation_sends_actionable_peer_left_and_terminal_consent(
        bool owned, bool accepted, bool deletingRequester)
    {
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var member = Guid.NewGuid();
        var other = Guid.NewGuid();
        var session = Session(owned ? member : other, "VOICE");
        session.AddPlayer(owned ? other : member, "Guest", Now);
        db.Sessions.Add(session);
        await db.SaveChangesAsync();
        var games = new GameConnectionRegistry();
        games.Connected("deleting", member);
        games.Joined("deleting", session.Id);
        games.Connected("peer", other);
        games.Joined("peer", session.Id);
        var voice = new VoiceConnectionRegistry();
        voice.Join("deleting", session.Id, member, 17, true);
        voice.Join("peer", session.Id, other, 29, false);
        var consent = new VoiceConsentRegistry();
        var requester = deletingRequester ? member : other;
        var recipient = deletingRequester ? other : member;
        var request = consent.RequestVoice(session.Id, 3, requester, recipient, Now, TimeSpan.FromMinutes(1)).Request;
        if (accepted) consent.Accept(request.RequestId, session.Id, 3, recipient, Now);
        var hub = new TestHub();
        var handler = new GamesAccountDeletionHandler(db, games, voice, consent, hub);

        await handler.DeleteAsync(Scope(member), default);

        var left = Assert.Single(hub.Sent, x => x.Method == "VoicePeerLeft");
        Assert.Equal("peer", left.Connection);
        Assert.Equal(new VoicePeerEvent(session.Id, 29, member, "deleting", "peer", 17, true), left.Payload);
        var ended = Assert.Single(hub.Sent, x => x.Method == "VoiceEnded");
        Assert.Equal("peer", ended.Connection);
        Assert.Equal(new VoiceConsentEvent(session.Id, 3, request.RequestId, requester,
            deletingRequester ? "deleting" : "peer", recipient, deletingRequester ? "peer" : "deleting",
            request.ExpiresAtUtc, "ended", "account_deleted"), ended.Payload);
        Assert.Null(consent.Current(session.Id, 3, other, Now));
        Assert.Throws<InvalidOperationException>(() => consent.RequireAccepted(session.Id, 3, other));
        Assert.Throws<InvalidOperationException>(() => consent.Accept(request.RequestId, session.Id, 3, recipient, Now));
        Assert.Throws<InvalidOperationException>(() => voice.Join("late", session.Id, member, 18, true));
        Assert.Throws<InvalidOperationException>(() => games.Connected("late", member));
        if (owned)
        {
            Assert.Null(games.ResolveParticipantConnection(session.Id, other));
            Assert.Throws<InvalidOperationException>(() => voice.RequireCurrent("peer", session.Id, other, 29));
            Assert.Throws<InvalidOperationException>(() => voice.Join("peer-new", session.Id, other, 30, false));
            Assert.Throws<InvalidOperationException>(() => consent.RequestVoice(session.Id, 4, other,
                Guid.NewGuid(), Now, TimeSpan.FromMinutes(1)));
        }
        await handler.DeleteAsync(Scope(member), default);
        Assert.Equal(2, hub.Sent.Count);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Connected_peer_receives_terminal_consent_without_deleting_game_connection(bool deletingRequester)
    {
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var member = Guid.NewGuid();
        var other = Guid.NewGuid();
        var session = Session(member, "OWNED");
        db.Sessions.Add(session);
        await db.SaveChangesAsync();
        var games = new GameConnectionRegistry();
        games.Connected("peer", other);
        games.Joined("peer", session.Id);
        var consent = new VoiceConsentRegistry();
        var requester = deletingRequester ? member : other;
        var recipient = deletingRequester ? other : member;
        var request = consent.RequestVoice(session.Id, 1, requester, recipient, Now, TimeSpan.FromMinutes(1)).Request;
        var hub = new TestHub();

        await new GamesAccountDeletionHandler(db, games, new(), consent, hub).DeleteAsync(Scope(member), default);

        var sent = Assert.Single(hub.Sent);
        Assert.Equal(("peer", "VoiceEnded"), (sent.Connection, sent.Method));
        Assert.Equal(new VoiceConsentEvent(session.Id, 1, request.RequestId, requester,
            deletingRequester ? "" : "peer", recipient, deletingRequester ? "peer" : "",
            request.ExpiresAtUtc, "ended", "account_deleted"), sent.Payload);
    }

    [Theory]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    public async Task Failed_send_blocks_processor_cleanup_and_retries_only_unacknowledged_notifications(int failedSend)
    {
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        await using var identityDb = new IdentityDbContext(new DbContextOptionsBuilder<IdentityDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var member = Guid.NewGuid();
        var user = new ApplicationUser(Guid.NewGuid(), "test-subject", "test@example.test", "Test");
        var membership = new ApplicationMembership(member, BotGlobalApplications.FamilyGames,
            "test-subject", "Test", user.Id, false, Now);
        var operation = new ApplicationAccountDeletionRequest(Guid.NewGuid(), member, user.Id,
            membership.ApplicationKey, membership.SubjectId, [], Now);
        identityDb.Users.Add(user);
        identityDb.ApplicationMemberships.Add(membership);
        identityDb.AccountDeletionRequests.Add(operation);
        await identityDb.SaveChangesAsync();
        var games = new GameConnectionRegistry();
        var voice = new VoiceConnectionRegistry();
        var consent = new VoiceConsentRegistry();
        for (var i = 0; i < 2; i++)
        {
            var other = Guid.NewGuid();
            var session = Session(i == 0 ? member : other, $"SESSION-{i}");
            session.AddPlayer(i == 0 ? other : member, "Guest", Now);
            db.Sessions.Add(session);
            games.Connected($"deleting-{i}", member);
            games.Joined($"deleting-{i}", session.Id);
            games.Connected($"peer-{i}", other);
            games.Joined($"peer-{i}", session.Id);
            voice.Join($"deleting-{i}", session.Id, member, 11 + i, true);
            voice.Join($"peer-{i}", session.Id, other, 21 + i, false);
            var request = consent.RequestVoice(session.Id, 1, member, other, Now, TimeSpan.FromMinutes(1)).Request;
            if (i == 0) consent.Accept(request.RequestId, session.Id, 1, other, Now);
        }
        await db.SaveChangesAsync();
        var hub = new TestHub { FailSendAt = failedSend };
        var tail = new CleanupProbe();
        var handler = new GamesAccountDeletionHandler(db, games, voice, consent, hub);
        var processor = new ApplicationAccountDeletionProcessor(identityDb, [handler, tail],
            TimeProvider.System, NullLogger<ApplicationAccountDeletionProcessor>.Instance);

        Assert.Equal(ApplicationAccountDeletionProcessOutcome.BusyBeforeAccessRevocation,
            await processor.TryProcessAsync(operation.Id, default));
        Assert.Equal(failedSend - 1, hub.Sent.Count);
        Assert.Equal(0, tail.Calls);
        Assert.Equal(2, await db.Sessions.CountAsync());
        Assert.Equal(2, await db.Players.CountAsync(x => x.MembershipId == member));
        Assert.NotNull(await identityDb.Users.FindAsync(user.Id));
        Assert.NotNull(await identityDb.ApplicationMemberships.FindAsync(member));
        Assert.Null((await identityDb.AccountDeletionRequests.SingleAsync()).AccessRevokedAtUtc);
        Assert.Equal("account-deletion-step-failed", (await identityDb.AccountDeletionRequests.SingleAsync()).LastSafeErrorCode);
        Assert.Equal(4 - hub.Sent.Count,
            voice.RevokeMembership(member, []).Count + consent.RevokeMembership(member, [], games).Count);

        // A fresh scoped executor must use the singleton registries' captured routes,
        // even after owned-session presence was removed from the game registry.
        hub.FailSendAt = null;
        processor = new ApplicationAccountDeletionProcessor(identityDb,
            [new GamesAccountDeletionHandler(db, games, voice, consent, hub), tail],
            TimeProvider.System, NullLogger<ApplicationAccountDeletionProcessor>.Instance);
        Assert.Equal(ApplicationAccountDeletionProcessOutcome.Completed,
            await processor.TryProcessAsync(operation.Id, default));
        Assert.Equal(4, hub.Sent.Count);
        Assert.Equal(4, hub.Sent.Distinct().Count());
        Assert.Equal(5, hub.SendAttempts);
        Assert.Equal(1, tail.Calls);
        Assert.False(await db.Players.AnyAsync(x => x.MembershipId == member));
        Assert.Empty(await identityDb.AccountDeletionRequests.ToListAsync());
        Assert.Null(await identityDb.Users.FindAsync(user.Id));
        Assert.Empty(voice.RevokeMembership(member, []));
        Assert.Empty(consent.RevokeMembership(member, [], games));
    }

    [Fact]
    public async Task Owned_session_revocation_captures_both_peers_before_removal_and_acknowledges_each_consent_receiver()
    {
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var owner = Guid.NewGuid();
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var session = Session(owner, "OWNED");
        db.Sessions.Add(session);
        await db.SaveChangesAsync();
        var games = new GameConnectionRegistry();
        games.Connected("first", first);
        games.Joined("first", session.Id);
        games.Connected("second", second);
        games.Joined("second", session.Id);
        var voice = new VoiceConnectionRegistry();
        voice.Join("first", session.Id, first, 13, true);
        voice.Join("second", session.Id, second, 31, false);
        var consent = new VoiceConsentRegistry();
        var request = consent.RequestVoice(session.Id, 1, first, second, Now, TimeSpan.FromMinutes(1)).Request;
        consent.Accept(request.RequestId, session.Id, 1, second, Now);
        var hub = new TestHub { FailSendAt = 4 };
        var handler = new GamesAccountDeletionHandler(db, games, voice, consent, hub);

        await Assert.ThrowsAsync<InvalidOperationException>(() => handler.DeleteAsync(Scope(owner), default));

        Assert.Null(games.ResolveParticipantConnection(session.Id, first));
        Assert.Null(games.ResolveParticipantConnection(session.Id, second));
        Assert.Throws<InvalidOperationException>(() => voice.RequireCurrent("first", session.Id, first, 13));
        Assert.Throws<InvalidOperationException>(() => voice.RequireCurrent("second", session.Id, second, 31));
        Assert.Contains(hub.Sent, x => x.Connection == "second" && x.Method == "VoicePeerLeft" &&
            Equals(x.Payload, new VoicePeerEvent(session.Id, 31, first, "first", "second", 13, true)));
        Assert.Contains(hub.Sent, x => x.Connection == "first" && x.Method == "VoicePeerLeft" &&
            Equals(x.Payload, new VoicePeerEvent(session.Id, 13, second, "second", "first", 31, false)));
        var pending = Assert.Single(consent.RevokeMembership(owner, [session.Id], games));
        Assert.Equal(VoiceConsentRegistry.Status.Ended, pending.Request.Status);
        Assert.Single(await db.Sessions.ToListAsync());
        hub.FailSendAt = null;
        await handler.DeleteAsync(Scope(owner), default);
        var terminal = hub.Sent.Where(x => x.Method == "VoiceEnded").ToArray();
        Assert.Equal(2, terminal.Length);
        Assert.Equal(2, terminal.Select(x => x.Connection).Distinct().Count());
        Assert.All(terminal, x => Assert.Equal(new VoiceConsentEvent(session.Id, 1, request.RequestId,
            first, "first", second, "second", request.ExpiresAtUtc, "ended", "account_deleted"), x.Payload));
        Assert.Equal(4, hub.Sent.Count);
        Assert.Equal(5, hub.SendAttempts);
        Assert.Empty(await db.Sessions.ToListAsync());
    }

    [Fact]
    public async Task Retry_keeps_original_receiver_generation_and_connection_after_peer_reconnect()
    {
        await using var db = new GamesDbContext(new DbContextOptionsBuilder<GamesDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString()).Options);
        var member = Guid.NewGuid();
        var other = Guid.NewGuid();
        var session = Session(other, "RETAINED");
        session.AddPlayer(member, "Guest", Now);
        db.Sessions.Add(session);
        await db.SaveChangesAsync();
        var games = new GameConnectionRegistry();
        games.Connected("deleting", member);
        games.Joined("deleting", session.Id);
        games.Connected("peer-old", other);
        games.Joined("peer-old", session.Id);
        var voice = new VoiceConnectionRegistry();
        voice.Join("deleting", session.Id, member, 4, true);
        voice.Join("peer-old", session.Id, other, 8, false);
        var consent = new VoiceConsentRegistry();
        consent.RequestVoice(session.Id, 1, member, other, Now, TimeSpan.FromMinutes(1));
        var hub = new TestHub { FailSendAt = 1 };
        var handler = new GamesAccountDeletionHandler(db, games, voice, consent, hub);
        await Assert.ThrowsAsync<InvalidOperationException>(() => handler.DeleteAsync(Scope(member), default));
        games.Disconnected("peer-old");
        games.Connected("peer-new", other);
        games.Joined("peer-new", session.Id);
        voice.Join("peer-new", session.Id, other, 9, false);
        Assert.Throws<InvalidOperationException>(() => voice.RequireCurrent("peer-old", session.Id, other, 8));
        Assert.Throws<InvalidOperationException>(() => consent.RequestVoice(session.Id, 1, member, other, Now, TimeSpan.FromMinutes(1)));

        hub.FailSendAt = null;
        await handler.DeleteAsync(Scope(member), default);

        Assert.All(hub.Sent, x => Assert.Equal("peer-old", x.Connection));
        Assert.Equal(new VoicePeerEvent(session.Id, 8, member, "deleting", "peer-old", 4, true),
            Assert.Single(hub.Sent, x => x.Method == "VoicePeerLeft").Payload);
        Assert.Equal(9, voice.RequireCurrent("peer-new", session.Id, other, 9).Generation);
        Assert.Null(consent.Current(session.Id, 1, other, Now));
    }

    private static GameSessionSnapshot Snapshot(Guid sessionId, Guid membershipId) => new(
        sessionId,
        "LATE",
        "xo",
        "waiting",
        1,
        new("classic", 3, 3, 2, null, true, false, null),
        [new(membershipId, "Other player", 1, "o", false, true)],
        ["", "", "", "", "", "", "", "", ""],
        1,
        null,
        null,
        "waiting",
        null,
        Now,
        1);

    private static ClaimsPrincipal Principal(Guid membershipId) => new(new ClaimsIdentity(
        [
            new(ApplicationIdentityDefaults.MembershipIdClaim, membershipId.ToString()),
            new(ApplicationIdentityDefaults.ApplicationKeyClaim, BotGlobalApplications.FamilyGames),
            new(ApplicationIdentityDefaults.GuestClaim, "true"),
            new(ClaimTypes.NameIdentifier, "late-participant"),
        ],
        ApplicationIdentityDefaults.Scheme));

    private sealed class RejoinSessionService(GameSessionSnapshot snapshot) : IGameSessionService
    {
        public Task<GameCommandResult<GameSessionSnapshot>> RejoinAsync(
            ApplicationIdentityDescriptor identity,
            Guid sessionId,
            CancellationToken cancellationToken) =>
            Task.FromResult(GameCommandResult<GameSessionSnapshot>.Success(snapshot));

        public Task<GameCommandResult<GameSessionSnapshot>> CreateAsync(ApplicationIdentityDescriptor identity,
            CreateGameSessionRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> JoinAsync(ApplicationIdentityDescriptor identity,
            JoinGameSessionRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> ReadyAsync(ApplicationIdentityDescriptor identity,
            Guid sessionId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> GetAsync(ApplicationIdentityDescriptor identity,
            Guid sessionId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> GetActiveAsync(ApplicationIdentityDescriptor identity,
            CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> MoveAsync(ApplicationIdentityDescriptor identity,
            XoMoveRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> SubmitAutobusAnswersAsync(ApplicationIdentityDescriptor identity,
            AutobusSubmitAnswersRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> FinishAutobusRoundAsync(ApplicationIdentityDescriptor identity,
            AutobusFinishRoundRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> RevealAutobusAsync(ApplicationIdentityDescriptor identity,
            AutobusRevealRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> VoteAutobusAsync(ApplicationIdentityDescriptor identity,
            AutobusVoteRequest request, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> SetDisconnectedAsync(Guid membershipId,
            Guid sessionId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> RequestRematchAsync(ApplicationIdentityDescriptor identity,
            Guid sessionId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<GameCommandResult<GameSessionSnapshot>> AcceptRematchAsync(ApplicationIdentityDescriptor identity,
            Guid sessionId, CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class UnusedVoiceIceConfigurationProvider : IVoiceIceConfigurationProvider
    {
        public VoiceIceConfiguration Create(Guid membershipId) => throw new NotSupportedException();
    }

    private sealed class TestHubCallerContext(
        string connectionId,
        ClaimsPrincipal user) : HubCallerContext
    {
        private readonly CancellationTokenSource _aborted = new();
        private readonly IDictionary<object, object?> _items = new Dictionary<object, object?>();
        public override string ConnectionId { get; } = connectionId;
        public override string? UserIdentifier => null;
        public override ClaimsPrincipal User { get; } = user;
        public override IDictionary<object, object?> Items => _items;
        public override IFeatureCollection Features { get; } = new FeatureCollection();
        public override CancellationToken ConnectionAborted => _aborted.Token;
        public override void Abort() => _aborted.Cancel();
    }

    private sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
        public void Advance(TimeSpan elapsed) => now = now.Add(elapsed);
    }

    private sealed class CleanupProbe : IApplicationAccountDeletionHandler
    {
        public string StepName => "cleanup-probe";
        public int Order => 200;
        public int Calls { get; private set; }
        public Task DeleteAsync(ApplicationAccountDeletionScope scope, CancellationToken cancellationToken)
        {
            Calls++;
            return Task.CompletedTask;
        }
    }

    private sealed class TestHub : IHubContext<GamesHub>, IGroupManager, IHubClients, IHubCallerClients
    {
        public bool BlockAdd { get; set; }
        public TaskCompletionSource<bool> AddStarted { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource<bool> ReleaseAdd { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        public bool FailRemoval { get; set; }
        public int? FailRemovalAt { get; set; }
        public int RemovalAttempts { get; private set; }
        public HashSet<(string Connection, string Group)> Removed { get; } = [];
        public int? FailSendAt { get; set; }
        public int SendAttempts { get; private set; }
        public List<(string Connection, string Method, object Payload)> Sent { get; } = [];
        public IHubClients Clients => this;
        public IClientProxy Caller => Client("caller");
        public IClientProxy Others => throw new NotSupportedException();
        public IClientProxy OthersInGroup(string groupName) => throw new NotSupportedException();
        public IClientProxy Client(string connectionId) => new RecordingClient(this, connectionId);
        public IClientProxy All => throw new NotSupportedException();
        public IClientProxy AllExcept(IReadOnlyList<string> excludedConnectionIds) => throw new NotSupportedException();
        IClientProxy IHubClients<IClientProxy>.Clients(IReadOnlyList<string> connectionIds) => throw new NotSupportedException();
        public IClientProxy Group(string groupName) => throw new NotSupportedException();
        public IClientProxy GroupExcept(string groupName, IReadOnlyList<string> excludedConnectionIds) => throw new NotSupportedException();
        IClientProxy IHubClients<IClientProxy>.Groups(IReadOnlyList<string> groupNames) => throw new NotSupportedException();
        public IClientProxy User(string userId) => throw new NotSupportedException();
        public IClientProxy Users(IReadOnlyList<string> userIds) => throw new NotSupportedException();
        private sealed class RecordingClient(TestHub hub, string connectionId) : IClientProxy
        {
            public Task SendCoreAsync(string method, object?[] args, CancellationToken cancellationToken = default)
            {
                if (++hub.SendAttempts == hub.FailSendAt)
                    throw new InvalidOperationException("Simulated send failure.");
                hub.Sent.Add((connectionId, method, Assert.Single(args)!));
                return Task.CompletedTask;
            }
        }
        public IGroupManager Groups => this;
        public async Task AddToGroupAsync(
            string connectionId,
            string groupName,
            CancellationToken cancellationToken = default)
        {
            AddStarted.TrySetResult(true);
            if (BlockAdd) await ReleaseAdd.Task.WaitAsync(cancellationToken);
        }
        public Task RemoveFromGroupAsync(string connectionId, string groupName, CancellationToken cancellationToken = default)
        {
            RemovalAttempts++;
            if (FailRemoval || RemovalAttempts == FailRemovalAt)
                throw new InvalidOperationException("Simulated group cleanup failure.");
            Removed.Add((connectionId, groupName));
            return Task.CompletedTask;
        }
    }

    private static GameSession Session(Guid owner, string code, string app = BotGlobalApplications.FamilyGames)
    {
        var session = new GameSession(Guid.NewGuid(), app, code, "xo", "classic", 2, owner, Now);
        session.AddPlayer(owner, "Owner", Now);
        return session;
    }
    private static XoMove Move(GameSession session, Guid member) =>
        new(Guid.NewGuid(), session.Id, Guid.NewGuid().ToString("N"), member, 0, 0, Random.Shared.Next(1, int.MaxValue), Now);
    private static GameInvitation Invitation(GameSession session, Guid member) =>
        new(Guid.NewGuid(), session.Id, session.ApplicationKey, Guid.NewGuid().ToString("N"), member, Now, Now.AddHours(1));
    private static ApplicationAccountDeletionScope Scope(Guid member, string app = BotGlobalApplications.FamilyGames) =>
        new(new(member, Guid.NewGuid(), "test-subject", app), []);
}
