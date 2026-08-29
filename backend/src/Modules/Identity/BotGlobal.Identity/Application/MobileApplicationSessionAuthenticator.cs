using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

public sealed record AuthenticatedApplicationSession(
    Guid SessionId,
    ApplicationIdentityDescriptor Identity);

public interface IMobileApplicationSessionAuthenticator
{
    Task<AuthenticatedApplicationSession?> AuthenticateAsync(
        string accessToken,
        CancellationToken cancellationToken);
}

internal sealed class MobileApplicationSessionAuthenticator(
    IdentityDbContext dbContext,
    TimeProvider timeProvider) : IMobileApplicationSessionAuthenticator
{
    public async Task<AuthenticatedApplicationSession?> AuthenticateAsync(
        string accessToken,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(accessToken))
        {
            return null;
        }

        var hash = MobileApplicationTokenService.Hash(accessToken);
        var session = await dbContext.MobileApplicationSessions
            .AsNoTracking()
            .Include(x => x.Membership)
            .SingleOrDefaultAsync(x => x.AccessTokenHash.SequenceEqual(hash), cancellationToken);

        if (session is null || !session.IsAccessValid(timeProvider.GetUtcNow()) || !session.Membership.IsActive)
        {
            return null;
        }

        var membership = session.Membership;
        return new AuthenticatedApplicationSession(
            session.Id,
            new ApplicationIdentityDescriptor(
                membership.Id,
                membership.GlobalUserId,
                membership.SubjectId,
                membership.ApplicationKey,
                membership.DisplayName,
                membership.IsGuest));
    }
}
