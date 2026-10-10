using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;

namespace BotGlobal.Calling.Application;

internal sealed class NqrbPresenceAccessPolicy(
    INqrbCallEligibilityService eligibility,
    INqrbBlockService blocks) : IPresenceAccessPolicy
{
    public string ApplicationKey => BotGlobalApplications.Nqrb;

    public async Task<bool> CanObserveAsync(
        Guid actorMembershipId,
        Guid counterpartMembershipId,
        CancellationToken cancellationToken)
    {
        if (actorMembershipId == Guid.Empty || counterpartMembershipId == Guid.Empty ||
            actorMembershipId == counterpartMembershipId) return false;
        var decision = await eligibility.EvaluateAsync(actorMembershipId, counterpartMembershipId, cancellationToken);
        if (!decision.CanCall) return false;
        return !await blocks.IsBlockedAsync(actorMembershipId, counterpartMembershipId, cancellationToken) &&
            !await blocks.IsBlockedAsync(counterpartMembershipId, actorMembershipId, cancellationToken);
    }
}
