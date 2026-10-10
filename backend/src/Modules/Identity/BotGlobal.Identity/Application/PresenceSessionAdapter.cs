using System.Security.Cryptography;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

internal sealed class PresenceSessionAdapter(
    IdentityDbContext db,
    IPlatformClientApplicationResolver applications,
    IServiceProvider services,
    TimeProvider timeProvider) : IPresenceSessionDirectory
{
    public async Task<PresenceValidatedSession?> ValidateConnectionAsync(
        PresenceConnectionCredential credential,
        CancellationToken cancellationToken)
    {
        if (credential.SessionId == Guid.Empty || credential.MembershipId == Guid.Empty ||
            string.IsNullOrWhiteSpace(credential.ApplicationKey) ||
            string.IsNullOrWhiteSpace(credential.CredentialRevision)) return null;
        if (!await credential.ValidateAsync(services, cancellationToken)) return null;

        var session = await LoadBoundSessionAsync(credential, cancellationToken);
        if (session is null) return null;

        var application = await applications.FindByClientKeyAsync(
            credential.ApplicationKey,
            cancellationToken);
        if (application is not { IsActive: true }) return null;

        // The application lookup is an authority-changing await. Re-read the
        // immutable credential/session binding before publishing a capability.
        session = await LoadBoundSessionAsync(credential, cancellationToken);
        if (session is null) return null;

        return new PresenceValidatedSession(
            Authority(session, application.PlatformClientId),
            session.Membership.SubjectId,
            session.Membership.DisplayName,
            session.Membership.IsGuest);
    }

    public async Task<IReadOnlyList<PresenceSessionAuthority>> ListActiveSessionsAsync(
        string applicationKey,
        Guid membershipId,
        int maximumCount,
        CancellationToken cancellationToken)
    {
        if (membershipId == Guid.Empty || string.IsNullOrWhiteSpace(applicationKey) || maximumCount < 1) return [];
        var application = await applications.FindByClientKeyAsync(applicationKey, cancellationToken);
        if (application is not { IsActive: true }) return [];
        var now = timeProvider.GetUtcNow();
        var sessions = await db.MobileApplicationSessions
            .AsNoTracking()
            .Include(item => item.Membership)
            .Where(item => item.MembershipId == membershipId &&
                item.Membership.ApplicationKey == applicationKey &&
                item.Membership.IsActive && !item.Membership.IsGuest &&
                item.RevokedAtUtc == null && item.AccessExpiresAtUtc > now)
            .OrderBy(item => item.Id)
            .Take(maximumCount)
            .ToListAsync(cancellationToken);
        return sessions.Select(item => Authority(item, application.PlatformClientId)).ToArray();
    }

    public async Task<bool> RevalidateAsync(
        PresenceSessionAuthority authority,
        CancellationToken cancellationToken)
    {
        if (authority.SessionId == Guid.Empty || authority.MembershipId == Guid.Empty ||
            string.IsNullOrWhiteSpace(authority.CredentialRevision)) return false;
        var now = timeProvider.GetUtcNow();
        var session = await db.MobileApplicationSessions
            .AsNoTracking()
            .Include(item => item.Membership)
            .SingleOrDefaultAsync(item => item.Id == authority.SessionId, cancellationToken);
        if (session is null || !session.IsAccessValid(now) || !session.Membership.IsActive ||
            session.Membership.IsGuest || session.MembershipId != authority.MembershipId ||
            !string.Equals(session.Membership.ApplicationKey, authority.ApplicationKey, StringComparison.Ordinal) ||
            !string.Equals(Revision(session.AccessTokenHash), authority.CredentialRevision, StringComparison.Ordinal))
            return false;
        var application = await applications.FindByClientKeyAsync(authority.ApplicationKey, cancellationToken);
        return application is { IsActive: true } && application.PlatformClientId == authority.ApplicationId;
    }

    private static PresenceSessionAuthority Authority(
        Domain.MobileApplicationSession session,
        Guid applicationId) => new(
            session.Id,
            session.MembershipId,
            applicationId,
            session.Membership.ApplicationKey,
            session.AccessExpiresAtUtc,
            Revision(session.AccessTokenHash));

    private static string Revision(byte[] accessTokenHash) =>
        Convert.ToHexString(SHA256.HashData(accessTokenHash));

    private async Task<Domain.MobileApplicationSession?> LoadBoundSessionAsync(
        PresenceConnectionCredential credential,
        CancellationToken cancellationToken)
    {
        var now = timeProvider.GetUtcNow();
        var session = await db.MobileApplicationSessions
            .AsNoTracking()
            .Include(item => item.Membership)
            .SingleOrDefaultAsync(item => item.Id == credential.SessionId, cancellationToken);
        return session is not null && session.IsAccessValid(now) &&
            session.Membership.IsActive && !session.Membership.IsGuest &&
            session.MembershipId == credential.MembershipId &&
            string.Equals(session.Membership.ApplicationKey, credential.ApplicationKey, StringComparison.Ordinal) &&
            string.Equals(Revision(session.AccessTokenHash), credential.CredentialRevision, StringComparison.Ordinal)
                ? session
                : null;
    }
}
