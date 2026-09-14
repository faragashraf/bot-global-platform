using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

public interface IMobileIdentityProfileReader
{
    Task<MobileIdentityProfileResponse?> ReadAsync(
        ApplicationIdentityDescriptor identity,
        CancellationToken cancellationToken);
}

internal sealed class MobileIdentityProfileReader(IdentityDbContext dbContext)
    : IMobileIdentityProfileReader
{
    public Task<MobileIdentityProfileResponse?> ReadAsync(
        ApplicationIdentityDescriptor identity,
        CancellationToken cancellationToken)
    {
        if (identity.GlobalUserId is not { } globalUserId)
        {
            return Task.FromResult<MobileIdentityProfileResponse?>(null);
        }

        return (
            from membership in dbContext.ApplicationMemberships.AsNoTracking()
            join user in dbContext.Users.AsNoTracking()
                on membership.GlobalUserId equals user.Id
            where membership.Id == identity.MembershipId
                && membership.ApplicationKey == identity.ApplicationKey
                && membership.GlobalUserId == globalUserId
                && membership.IsActive
                && user.IsActive
            select new MobileIdentityProfileResponse(
                user.DisplayName,
                user.Email ?? string.Empty))
            .SingleOrDefaultAsync(cancellationToken);
    }
}
