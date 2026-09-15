using System.Data;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Infrastructure.Persistence;
using BotGlobal.Games.Realtime;
using BotGlobal.Games.Realtime.Voice;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.SignalR;

namespace BotGlobal.Games.Application;

internal sealed class GamesAccountDeletionHandler(
    GamesDbContext dbContext,
    GameConnectionRegistry games,
    VoiceConnectionRegistry voice,
    VoiceConsentRegistry consent,
    IHubContext<GamesHub> hub,
    IGamesMembershipWriteFence? membershipWriteFence = null,
    TimeProvider? timeProvider = null) : IApplicationAccountDeletionHandler
{
    private const int MaximumPersistenceAttempts = 3;
    private readonly IGamesMembershipWriteFence _membershipWriteFence = membershipWriteFence ??
        new GamesMembershipWriteFence(dbContext, new GamesMembershipFenceLock());
    private readonly TimeProvider _timeProvider = timeProvider ?? TimeProvider.System;

    public string StepName => "games-personal-data";
    public int Order => 110;
    public bool RevokesAccess => true;

    public async Task DeleteAsync(ApplicationAccountDeletionScope scope, CancellationToken cancellationToken)
    {
        if (scope.Identity.ApplicationKey != BotGlobalApplications.FamilyGames) return;
        var membershipId = scope.Identity.MembershipId;
        // Block late joins before the first await; retries repeat revocation safely.
        consent.RevokeMembership(membershipId, [], games);
        voice.RevokeMembership(membershipId, []);
        games.RevokeMembership(membershipId, []);
        await _membershipWriteFence.EstablishDeletionBarrierAsync(
            BotGlobalApplications.FamilyGames,
            membershipId,
            _timeProvider.GetUtcNow(),
            cancellationToken);

        dbContext.ChangeTracker.Clear();
        var affectedSessions = await dbContext.Sessions.AsNoTracking()
            .Where(session => session.ApplicationKey == BotGlobalApplications.FamilyGames &&
                (session.CreatedByMembershipId == membershipId ||
                 session.Players.Any(player => player.MembershipId == membershipId)))
            .Select(session => new { session.Id, session.CreatedByMembershipId })
            .ToListAsync(cancellationToken);
        var ownedIds = affectedSessions.Where(session => session.CreatedByMembershipId == membershipId)
            .Select(session => session.Id).ToArray();
        var revokedConsent = consent.RevokeMembership(membershipId, ownedIds, games);
        var revokedVoice = voice.RevokeMembership(membershipId, ownedIds);
        var revokedPresence = games.RevokeMembership(membershipId, ownedIds);
        foreach (var presence in revokedPresence)
        {
            await hub.Groups.RemoveFromGroupAsync(presence.ConnectionId,
                GamesHub.GroupName(presence.SessionId), cancellationToken);
            games.CompleteRevokedPresence(presence);
        }

        // Acknowledge each successful send separately. Failures propagate before persistence
        // cleanup, retaining the remaining routes across scoped handler/processor retries.
        // This evidence is process-local, not a durable delivery guarantee.
        foreach (var notification in revokedVoice)
        {
            var departed = notification.Departed;
            var receiver = notification.Receiver;
            await hub.Clients.Client(receiver.ConnectionId).SendAsync("VoicePeerLeft",
                new VoicePeerEvent(departed.SessionId, receiver.Generation, departed.MembershipId,
                    departed.ConnectionId, receiver.ConnectionId, departed.Generation, departed.IsInitiator),
                cancellationToken);
            voice.CompleteRevocation(membershipId, notification);
        }
        foreach (var notification in revokedConsent)
        {
            var request = notification.Request;
            await hub.Clients.Client(notification.ReceiverConnectionId).SendAsync("VoiceEnded",
                new VoiceConsentEvent(request.SessionId, request.MatchNumber, request.RequestId,
                    request.RequesterMembershipId, notification.RequesterConnectionId,
                    request.RecipientMembershipId, notification.RecipientConnectionId,
                    request.ExpiresAtUtc, "ended", "account_deleted"), cancellationToken);
            consent.CompleteRevocation(membershipId, notification);
        }

        await DeletePersistentDataAsync(membershipId, cancellationToken);
    }

    private async Task DeletePersistentDataAsync(Guid membershipId, CancellationToken cancellationToken)
    {
        for (var attempt = 1; attempt <= MaximumPersistenceAttempts; attempt++)
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = dbContext.Database.IsRelational()
                ? await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable, cancellationToken)
                : null;
            try
            {
                // Reload every affected aggregate inside the transaction on every
                // attempt. GameSession.AggregateVersion is advanced by every writer
                // and by anonymization, so a stale cross-player write cannot win
                // after this commit without producing a concurrency conflict.
                var sessions = await dbContext.Sessions.Include(session => session.Players)
                    .Where(session => session.ApplicationKey == BotGlobalApplications.FamilyGames &&
                        (session.CreatedByMembershipId == membershipId ||
                         session.Players.Any(player => player.MembershipId == membershipId)))
                    .ToListAsync(cancellationToken);
                var ownedIds = sessions.Where(session => session.CreatedByMembershipId == membershipId)
                    .Select(session => session.Id).ToArray();
                var affectedIds = sessions.Select(session => session.Id).ToArray();
                var states = await dbContext.XoStates
                    .Where(state => affectedIds.Contains(state.SessionId))
                    .ToListAsync(cancellationToken);
                var moves = await dbContext.XoMoves
                    .Where(move => affectedIds.Contains(move.SessionId))
                    .ToListAsync(cancellationToken);

                // Removing invitations revokes their tokens and erases their creator identity.
                var invitations = await dbContext.Invitations.Where(invitation =>
                    invitation.ApplicationKey == BotGlobalApplications.FamilyGames &&
                    (invitation.CreatedByMembershipId == membershipId || ownedIds.Contains(invitation.SessionId)))
                    .ToListAsync(cancellationToken);
                dbContext.Invitations.RemoveRange(invitations);

                foreach (var session in sessions.Where(session => session.CreatedByMembershipId != membershipId))
                {
                    // Random per aggregate: no retained mapping or cross-session pseudonym.
                    var anonymousId = Guid.NewGuid();
                    session.AnonymizeMembership(membershipId, anonymousId);
                    var state = states.SingleOrDefault(state => state.SessionId == session.Id);
                    state?.AnonymizeMembership(membershipId, anonymousId);
                    foreach (var move in moves.Where(move => move.SessionId == session.Id &&
                        move.PlayerMembershipId == membershipId)) move.Anonymize(anonymousId);
                }

                // Loading/removing owned children explicitly gives non-relational tests
                // the same result as SQL Server's database cascades.
                dbContext.XoMoves.RemoveRange(moves.Where(move => ownedIds.Contains(move.SessionId)));
                dbContext.XoStates.RemoveRange(states.Where(state => ownedIds.Contains(state.SessionId)));
                dbContext.Sessions.RemoveRange(sessions.Where(session => session.CreatedByMembershipId == membershipId));
                await dbContext.SaveChangesAsync(cancellationToken);

                var originalMembershipRemains =
                    await dbContext.Sessions.AsNoTracking().AnyAsync(session =>
                        session.ApplicationKey == BotGlobalApplications.FamilyGames &&
                        (session.CreatedByMembershipId == membershipId ||
                         session.RematchRequestedByMembershipId == membershipId), cancellationToken) ||
                    await dbContext.Players.AsNoTracking()
                        .Join(
                            dbContext.Sessions.Where(session =>
                                session.ApplicationKey == BotGlobalApplications.FamilyGames),
                            player => player.SessionId,
                            session => session.Id,
                            (player, _) => player)
                        .AnyAsync(player => player.MembershipId == membershipId, cancellationToken) ||
                    await dbContext.Players.AsNoTracking().AnyAsync(player =>
                        ownedIds.Contains(player.SessionId), cancellationToken) ||
                    await dbContext.XoStates.AsNoTracking()
                        .Join(
                            dbContext.Sessions.Where(session =>
                                session.ApplicationKey == BotGlobalApplications.FamilyGames),
                            state => state.SessionId,
                            session => session.Id,
                            (state, _) => state)
                        .AnyAsync(state => state.ActivePlayerMembershipId == membershipId ||
                            state.WinnerMembershipId == membershipId, cancellationToken) ||
                    await dbContext.XoStates.AsNoTracking().AnyAsync(state =>
                        ownedIds.Contains(state.SessionId), cancellationToken) ||
                    await dbContext.XoMoves.AsNoTracking()
                        .Join(
                            dbContext.Sessions.Where(session =>
                                session.ApplicationKey == BotGlobalApplications.FamilyGames),
                            move => move.SessionId,
                            session => session.Id,
                            (move, _) => move)
                        .AnyAsync(move => move.PlayerMembershipId == membershipId, cancellationToken) ||
                    await dbContext.XoMoves.AsNoTracking().AnyAsync(move =>
                        ownedIds.Contains(move.SessionId), cancellationToken) ||
                    await dbContext.Invitations.AsNoTracking().AnyAsync(invitation =>
                        invitation.ApplicationKey == BotGlobalApplications.FamilyGames &&
                        invitation.CreatedByMembershipId == membershipId, cancellationToken);
                if (originalMembershipRemains)
                    throw new DbUpdateConcurrencyException("Games deletion did not converge on a clean aggregate state.");

                if (transaction is not null) await transaction.CommitAsync(cancellationToken);
                return;
            }
            catch (DbUpdateConcurrencyException) when (attempt < MaximumPersistenceAttempts)
            {
                if (transaction is not null) await transaction.RollbackAsync(cancellationToken);
            }
        }
    }
}
