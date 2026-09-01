using BotGlobal.Contracts.Calling;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

internal sealed class CallingParticipantDirectory(IdentityDbContext db) : ICallingParticipantDirectory
{
    public async Task<IReadOnlyList<CallingParticipantDescriptor>> ListCallableAsync(
        string applicationKey,
        Guid currentMembershipId,
        CancellationToken cancellationToken)
    {
        var normalized = applicationKey.Trim().ToLowerInvariant();
        return await db.ApplicationMemberships.AsNoTracking()
            .Where(x =>
                x.ApplicationKey == normalized &&
                x.IsActive &&
                x.Id != currentMembershipId)
            .OrderBy(x => x.DisplayName)
            .ThenBy(x => x.Id)
            .Select(x => new CallingParticipantDescriptor(
                x.Id, x.ApplicationKey, x.SubjectId, x.DisplayName, x.IsActive))
            .ToListAsync(cancellationToken);
    }

    public Task<CallingParticipantDescriptor?> FindAsync(
        string applicationKey,
        Guid membershipId,
        CancellationToken cancellationToken)
    {
        var normalized = applicationKey.Trim().ToLowerInvariant();
        return db.ApplicationMemberships.AsNoTracking()
            .Where(x =>
                x.Id == membershipId &&
                x.ApplicationKey == normalized &&
                x.IsActive)
            .Select(x => new CallingParticipantDescriptor(
                x.Id, x.ApplicationKey, x.SubjectId, x.DisplayName, x.IsActive))
            .SingleOrDefaultAsync(cancellationToken);
    }
}
