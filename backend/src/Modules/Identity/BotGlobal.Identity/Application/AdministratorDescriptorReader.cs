using BotGlobal.Contracts.Notifications;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

internal sealed class AdministratorDescriptorReader(
    IdentityDbContext dbContext)
    : IAdministratorDescriptorReader
{
    public Task<AdministratorDescriptor?> FindAsync(
        Guid userId,
        CancellationToken cancellationToken)
    {
        return dbContext.Users
            .AsNoTracking()
            .Where(user => user.Id == userId)
            .Select(user => new AdministratorDescriptor(
                user.Id,
                user.DisplayName,
                user.IsActive))
            .SingleOrDefaultAsync(cancellationToken);
    }
}
