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

internal sealed class CallingAccountDirectory(IdentityDbContext db) : ICallingAccountDirectory
{
    public Task<CallingAccountDescriptor?> FindActiveNonGuestAsync(
        string applicationKey,
        Guid membershipId,
        CancellationToken cancellationToken)
    {
        var normalized = applicationKey.Trim().ToLowerInvariant();
        return ActiveNonGuestQuery(normalized)
            .Where(item => item.Id == membershipId)
            .Select(item => new CallingAccountDescriptor(item.Id, item.DisplayName, item.SubjectId))
            .SingleOrDefaultAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<CallingAccountDescriptor>> FindActiveNonGuestAsync(
        string applicationKey,
        IReadOnlyCollection<Guid> membershipIds,
        CancellationToken cancellationToken)
    {
        if (membershipIds.Count == 0)
        {
            return [];
        }

        var normalized = applicationKey.Trim().ToLowerInvariant();
        var ids = membershipIds.Where(item => item != Guid.Empty).ToArray();
        return await ActiveNonGuestQuery(normalized)
            .Where(item => ids.Contains(item.Id))
            .Select(item => new CallingAccountDescriptor(item.Id, item.DisplayName, item.SubjectId))
            .ToListAsync(cancellationToken);
    }

    public async Task<CallingAccountSearchPage> SearchActiveNonGuestsAsync(
        string applicationKey,
        Guid currentMembershipId,
        string query,
        int page,
        int pageSize,
        CancellationToken cancellationToken)
    {
        var normalized = applicationKey.Trim().ToLowerInvariant();
        var search = query.Trim();
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 50);

        var rows = await ActiveNonGuestQuery(normalized)
            .Where(item =>
                item.Id != currentMembershipId &&
                item.DisplayName.Contains(search))
            .OrderBy(item => item.DisplayName)
            .ThenBy(item => item.Id)
            .Skip((page - 1) * pageSize)
            .Take(pageSize + 1)
            .Select(item => new CallingAccountDescriptor(item.Id, item.DisplayName, item.SubjectId))
            .ToListAsync(cancellationToken);

        return new CallingAccountSearchPage(
            rows.Take(pageSize).ToArray(),
            page,
            pageSize,
            rows.Count > pageSize);
    }

    private IQueryable<Domain.ApplicationMembership> ActiveNonGuestQuery(string applicationKey) =>
        db.ApplicationMemberships.AsNoTracking()
            .Where(item =>
                item.ApplicationKey == applicationKey &&
                item.IsActive &&
                !item.IsGuest);
}
