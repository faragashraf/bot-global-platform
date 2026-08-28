using System.Security.Cryptography;
using System.Text;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Games.Application.Entitlements;
using BotGlobal.Games.Application.Sessions;
using BotGlobal.Games.Domain.Invitations;
using BotGlobal.Games.Domain.Sessions;
using BotGlobal.Games.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.Games.Application.Invitations;

internal sealed class GameInvitationService(
    GamesDbContext dbContext,
    IGameSessionService sessions,
    IGameEntitlementAuthorizer entitlements,
    TimeProvider timeProvider,
    IOptions<GameInvitationOptions> options,
    ILogger<GameInvitationService> logger) : IGameInvitationService
{
    public async Task<GameCommandResult<GameInvitationSnapshot>> CreateAsync(
        ApplicationIdentityDescriptor identity,
        Guid sessionId,
        CancellationToken cancellationToken)
    {
        var session = await dbContext.Sessions
            .Include(x => x.Players)
            .SingleOrDefaultAsync(
                x => x.Id == sessionId && x.ApplicationKey == identity.ApplicationKey,
                cancellationToken);
        if (session is null)
        {
            return Fail<GameInvitationSnapshot>("session_not_found", "The game session was not found.", 404);
        }

        var inviter = session.Players.SingleOrDefault(x => x.MembershipId == identity.MembershipId);
        if (inviter is null)
        {
            return Fail<GameInvitationSnapshot>("not_participant", "Only a session participant can invite players.", 403);
        }

        if (!IsJoinable(session))
        {
            return Fail<GameInvitationSnapshot>("session_not_joinable", "The session is no longer accepting players.", 409);
        }

        if (!await entitlements.IsAllowedAsync(identity.MembershipId, session.RequiredEntitlement, cancellationToken))
        {
            return Fail<GameInvitationSnapshot>("entitlement_required", "The game mode is not available to this membership.", 403);
        }

        var now = timeProvider.GetUtcNow();
        var activeInvitations = await dbContext.Invitations
            .Where(x =>
                x.SessionId == sessionId &&
                x.ApplicationKey == identity.ApplicationKey &&
                x.RevokedAtUtc == null &&
                x.ConsumedAtUtc == null)
            .ToArrayAsync(cancellationToken);
        foreach (var activeInvitation in activeInvitations)
        {
            activeInvitation.Revoke(now);
        }

        var rawToken = CreateOpaqueToken();
        var lifetimeMinutes = Math.Clamp(options.Value.LifetimeMinutes, 1, 24 * 60);
        var invitation = new GameInvitation(
            Guid.NewGuid(),
            session.Id,
            identity.ApplicationKey,
            Hash(rawToken),
            identity.MembershipId,
            now,
            now.AddMinutes(lifetimeMinutes));
        dbContext.Invitations.Add(invitation);
        await dbContext.SaveChangesAsync(cancellationToken);

        logger.LogInformation(
            "Game invitation {InvitationId} created for session {SessionId} in application {ApplicationKey}",
            invitation.Id,
            session.Id,
            identity.ApplicationKey);

        return GameCommandResult<GameInvitationSnapshot>.Success(
            new GameInvitationSnapshot(
                invitation.Id,
                session.Id,
                session.GameType,
                rawToken,
                invitation.ExpiresAtUtc,
                inviter.DisplayName,
                BuildDeepLink(options.Value.DeepLinkBase, rawToken),
                session.JoinCode),
            201);
    }

    public async Task<GameCommandResult<ResolvedGameInvitation>> ResolveAsync(
        ApplicationIdentityDescriptor identity,
        ResolveGameInvitationRequest request,
        CancellationToken cancellationToken)
    {
        var token = request.Token?.Trim();
        if (string.IsNullOrWhiteSpace(token) || token.Length > 256)
        {
            return Fail<ResolvedGameInvitation>("invitation_invalid", "The invitation is invalid.", 400);
        }

        var invitation = await dbContext.Invitations
            .SingleOrDefaultAsync(x => x.TokenHash == Hash(token), cancellationToken);
        if (invitation is null)
        {
            return Fail<ResolvedGameInvitation>("invitation_invalid", "The invitation is invalid.", 404);
        }

        if (!string.Equals(invitation.ApplicationKey, identity.ApplicationKey, StringComparison.Ordinal))
        {
            logger.LogWarning(
                "Invitation {InvitationId} was rejected for application {ApplicationKey}",
                invitation.Id,
                identity.ApplicationKey);
            return Fail<ResolvedGameInvitation>("invitation_wrong_application", "The invitation belongs to another application.", 403);
        }

        var now = timeProvider.GetUtcNow();
        if (invitation.ExpiresAtUtc <= now)
        {
            invitation.Revoke(now);
            await dbContext.SaveChangesAsync(cancellationToken);
            return Fail<ResolvedGameInvitation>("invitation_expired", "The invitation has expired.", 410);
        }

        if (!invitation.IsActive(now))
        {
            return Fail<ResolvedGameInvitation>("invitation_inactive", "The invitation is no longer active.", 410);
        }

        var session = await dbContext.Sessions
            .Include(x => x.Players)
            .SingleOrDefaultAsync(
                x => x.Id == invitation.SessionId && x.ApplicationKey == identity.ApplicationKey,
                cancellationToken);
        if (session is null)
        {
            invitation.Revoke(now);
            await dbContext.SaveChangesAsync(cancellationToken);
            return Fail<ResolvedGameInvitation>("session_not_found", "The game session was not found.", 404);
        }

        if (session.Players.Any(x => x.MembershipId == identity.MembershipId))
        {
            return Fail<ResolvedGameInvitation>("already_participant", "You are already a participant in this game.", 409);
        }

        if (!IsJoinable(session))
        {
            invitation.Revoke(now);
            await dbContext.SaveChangesAsync(cancellationToken);
            return Fail<ResolvedGameInvitation>("session_not_joinable", "The session is no longer accepting players.", 409);
        }

        if (!await entitlements.IsAllowedAsync(identity.MembershipId, session.RequiredEntitlement, cancellationToken))
        {
            return Fail<ResolvedGameInvitation>("entitlement_required", "The game mode is not available to this membership.", 403);
        }

        var joined = await sessions.JoinAsync(
            identity,
            new JoinGameSessionRequest(session.JoinCode),
            cancellationToken);
        if (!joined.Succeeded)
        {
            return GameCommandResult<ResolvedGameInvitation>.Failure(
                joined.ErrorCode!,
                joined.ErrorMessage!,
                joined.StatusCode);
        }

        invitation.Consume(now);
        await dbContext.SaveChangesAsync(cancellationToken);
        logger.LogInformation(
            "Game invitation {InvitationId} resolved by membership {MembershipId} for session {SessionId}",
            invitation.Id,
            identity.MembershipId,
            session.Id);

        return GameCommandResult<ResolvedGameInvitation>.Success(
            new ResolvedGameInvitation(invitation.Id, joined.Value!));
    }

    internal static string Hash(string token) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    private static bool IsJoinable(GameSession session) =>
        session.Status == GameSessionStatus.Waiting && session.Players.Count < session.MaximumPlayers;

    private static string CreateOpaqueToken() =>
        Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

    private static string BuildDeepLink(string configuredBase, string token)
    {
        var linkBase = string.IsNullOrWhiteSpace(configuredBase)
            ? "familygames://invite"
            : configuredBase.Trim().TrimEnd('/');
        return $"{linkBase}/{Uri.EscapeDataString(token)}";
    }

    private static GameCommandResult<T> Fail<T>(string code, string message, int statusCode) =>
        GameCommandResult<T>.Failure(code, message, statusCode);
}
