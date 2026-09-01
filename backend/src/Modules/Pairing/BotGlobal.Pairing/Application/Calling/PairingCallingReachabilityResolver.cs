using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application.Calling;

internal sealed class PairingCallingReachabilityResolver(
    PairingDbContext db,
    IPlatformClientApplicationResolver applications) : ICallingReachabilityResolver
{
    public async Task<IReadOnlySet<Guid>> FindReachableMembershipsAsync(
        string applicationKey,
        IReadOnlyCollection<CallingParticipantDescriptor> participants,
        CancellationToken cancellationToken)
    {
        if (participants.Count == 0) return new HashSet<Guid>();
        var application = await applications.FindByClientKeyAsync(applicationKey, cancellationToken);
        if (application is null || !application.IsActive) return new HashSet<Guid>();

        var membershipBySubject = participants
            .Where(x => x.IsActive && !string.IsNullOrWhiteSpace(x.SubjectId))
            .GroupBy(x => x.SubjectId, StringComparer.Ordinal)
            .ToDictionary(x => x.Key, x => x.Select(p => p.MembershipId).ToArray(), StringComparer.Ordinal);
        var subjects = membershipBySubject.Keys.ToArray();

        var reachableSubjects = await (
                from device in db.Devices.AsNoTracking()
                join registration in db.PushRegistrations.AsNoTracking()
                    on device.Id equals registration.MobileDeviceId
                where device.PlatformClientId == application.PlatformClientId
                      && device.RevokedAtUtc == null
                      && device.ExternalSubjectId != null
                      && subjects.Contains(device.ExternalSubjectId)
                      && registration.InvalidatedAtUtc == null
                select device.ExternalSubjectId!)
            .Distinct()
            .ToListAsync(cancellationToken);

        return reachableSubjects
            .SelectMany(subject => membershipBySubject[subject])
            .ToHashSet();
    }
}
