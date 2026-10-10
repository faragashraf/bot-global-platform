using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

internal sealed class NqrbCommunicationParticipantDirectory(IdentityDbContext db) : IChatParticipantDirectory
{
    public string ApplicationKey => BotGlobalApplications.Nqrb;

    public async Task<ChatParticipant?> FindByReferenceAsync(ChatApplication application, string opaqueReference,
        CancellationToken cancellationToken)
    {
        if (!Matches(application) || !Guid.TryParse(opaqueReference, out var membershipId)) return null;
        return await ActiveMemberships().Where(x => x.Id == membershipId)
            .Select(x => new ChatParticipant(x.SubjectId, x.Id.ToString("D"), x.DisplayName))
            .SingleOrDefaultAsync(cancellationToken);
    }

    public Task<ChatParticipant?> FindBySubjectAsync(ChatApplication application, string subjectId,
        CancellationToken cancellationToken)
    {
        if (!Matches(application) || string.IsNullOrWhiteSpace(subjectId)) return Task.FromResult<ChatParticipant?>(null);
        var normalized = subjectId.Trim();
        return ActiveMemberships().Where(x => x.SubjectId == normalized)
            .Select(x => new ChatParticipant(x.SubjectId, x.Id.ToString("D"), x.DisplayName))
            .SingleOrDefaultAsync(cancellationToken);
    }

    private IQueryable<Domain.ApplicationMembership> ActiveMemberships() =>
        db.ApplicationMemberships.AsNoTracking().Where(x => x.ApplicationKey == BotGlobalApplications.Nqrb && x.IsActive && !x.IsGuest);
    private static bool Matches(ChatApplication application) =>
        application.ApplicationId != Guid.Empty && string.Equals(application.ApplicationKey, BotGlobalApplications.Nqrb, StringComparison.Ordinal);
}
