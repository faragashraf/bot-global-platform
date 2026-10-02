using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Calling.Application;

public sealed record NqrbCallEligibilityResult(
    bool CanCall,
    bool IsSavedContact,
    bool CanAddContact);

public interface INqrbCallEligibilityService
{
    Task<NqrbCallEligibilityResult> EvaluateAsync(
        Guid callerMembershipId,
        Guid counterpartMembershipId,
        CancellationToken cancellationToken);

    Task<IReadOnlyDictionary<Guid, NqrbCallEligibilityResult>> EvaluateManyAsync(
        Guid callerMembershipId,
        IReadOnlyCollection<Guid> counterpartMembershipIds,
        CancellationToken cancellationToken);
}

internal sealed class NqrbCallEligibilityService(
    CallingDbContext db,
    IPlatformClientApplicationResolver applications,
    ICallingAccountDirectory accounts) : INqrbCallEligibilityService
{
    public async Task<NqrbCallEligibilityResult> EvaluateAsync(
        Guid callerMembershipId,
        Guid counterpartMembershipId,
        CancellationToken cancellationToken)
    {
        var results = await EvaluateManyAsync(callerMembershipId, [counterpartMembershipId], cancellationToken);
        return results.GetValueOrDefault(counterpartMembershipId) ?? new(false, false, false);
    }

    public async Task<IReadOnlyDictionary<Guid, NqrbCallEligibilityResult>> EvaluateManyAsync(
        Guid callerMembershipId,
        IReadOnlyCollection<Guid> counterpartMembershipIds,
        CancellationToken cancellationToken)
    {
        var counterparts = counterpartMembershipIds
            .Where(id => id != Guid.Empty && id != callerMembershipId)
            .Distinct()
            .ToArray();
        if (callerMembershipId == Guid.Empty || counterparts.Length == 0)
            return new Dictionary<Guid, NqrbCallEligibilityResult>();

        var application = await applications.FindByClientKeyAsync(
            BotGlobalApplications.Nqrb,
            cancellationToken);
        if (application is not { IsActive: true })
            return new Dictionary<Guid, NqrbCallEligibilityResult>();

        var activeAccounts = await accounts.FindActiveNonGuestAsync(
            BotGlobalApplications.Nqrb,
            [callerMembershipId, .. counterparts],
            cancellationToken);
        var activeIds = activeAccounts.Select(account => account.MembershipId).ToHashSet();
        if (!activeIds.Contains(callerMembershipId))
            return new Dictionary<Guid, NqrbCallEligibilityResult>();

        var validIds = counterparts.Where(activeIds.Contains).ToArray();
        if (validIds.Length == 0)
            return new Dictionary<Guid, NqrbCallEligibilityResult>();

        var blockedRows = await db.NqrbBlockedAccounts.AsNoTracking().Where(block =>
            block.ApplicationKey == BotGlobalApplications.Nqrb &&
            ((block.OwnerMembershipId == callerMembershipId && validIds.Contains(block.BlockedMembershipId)) ||
             (validIds.Contains(block.OwnerMembershipId) && block.BlockedMembershipId == callerMembershipId)))
            .Select(block => block.OwnerMembershipId == callerMembershipId
                ? block.BlockedMembershipId : block.OwnerMembershipId)
            .ToListAsync(cancellationToken);
        var blockedIds = blockedRows.ToHashSet();
        validIds = validIds.Where(id => !blockedIds.Contains(id)).ToArray();
        if (validIds.Length == 0)
            return new Dictionary<Guid, NqrbCallEligibilityResult>();

        var savedIds = (await db.NqrbContactEdges.AsNoTracking().Where(edge =>
            edge.ApplicationKey == BotGlobalApplications.Nqrb &&
            edge.OwnerMembershipId == callerMembershipId &&
            validIds.Contains(edge.ContactMembershipId))
            .Select(edge => edge.ContactMembershipId)
            .ToListAsync(cancellationToken)).ToHashSet();

        var unsavedIds = validIds.Where(id => !savedIds.Contains(id)).ToArray();
        var historicalIds = unsavedIds.Length == 0
            ? new HashSet<Guid>()
            : (await db.Calls.AsNoTracking().Where(call =>
                call.ApplicationId == application.PlatformClientId &&
                call.ApplicationKey == BotGlobalApplications.Nqrb &&
                !call.IsGuestCall &&
                call.State == DurableCallState.Terminal &&
                call.Outcome != null &&
                call.Participants.Count == 2 &&
                ((call.Participants.Any(participant =>
                    participant.MembershipId == callerMembershipId &&
                    participant.Role == CallParticipantRole.Initiator) &&
                  call.Participants.Any(participant =>
                    unsavedIds.Contains(participant.MembershipId) &&
                    participant.Role == CallParticipantRole.Recipient)) ||
                 (call.Participants.Any(participant =>
                    participant.MembershipId == callerMembershipId &&
                    participant.Role == CallParticipantRole.Recipient) &&
                  call.Participants.Any(participant =>
                    unsavedIds.Contains(participant.MembershipId) &&
                    participant.Role == CallParticipantRole.Initiator))))
                .SelectMany(call => call.Participants
                    .Where(participant => unsavedIds.Contains(participant.MembershipId))
                    .Select(participant => participant.MembershipId))
                .Distinct()
                .ToListAsync(cancellationToken)).ToHashSet();

        return validIds.ToDictionary(id => id, id =>
            savedIds.Contains(id)
                ? new NqrbCallEligibilityResult(true, true, false)
                : new NqrbCallEligibilityResult(historicalIds.Contains(id), false, historicalIds.Contains(id)));
    }
}
