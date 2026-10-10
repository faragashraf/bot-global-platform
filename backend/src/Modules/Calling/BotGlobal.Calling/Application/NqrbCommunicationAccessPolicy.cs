using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;

namespace BotGlobal.Calling.Application;

internal sealed class NqrbCommunicationAccessPolicy(
    INqrbCallEligibilityService eligibility,
    INqrbBlockService blocks) : IChatAccessPolicy
{
    public string ApplicationKey => BotGlobalApplications.Nqrb;

    public async Task<bool> CanStartDirectConversationAsync(ChatActor actor, ChatParticipant actorParticipant,
        ChatParticipant counterpart, CancellationToken cancellationToken)
    {
        if (!Matches(actor.Application) || !Guid.TryParse(actorParticipant.Reference, out var actorMembershipId) ||
            !Guid.TryParse(counterpart.Reference, out var counterpartMembershipId)) return false;
        var result = await eligibility.EvaluateAsync(actorMembershipId, counterpartMembershipId, cancellationToken);
        return result.CanCall;
    }

    public async Task<bool> IsBidirectionallyBlockedAsync(ChatApplication application, ChatParticipant first,
        ChatParticipant second, CancellationToken cancellationToken)
    {
        if (!Matches(application) || !Guid.TryParse(first.Reference, out var firstId) ||
            !Guid.TryParse(second.Reference, out var secondId)) return true;
        return await blocks.IsBlockedAsync(firstId, secondId, cancellationToken) ||
            await blocks.IsBlockedAsync(secondId, firstId, cancellationToken);
    }

    private static bool Matches(ChatApplication application) =>
        application.ApplicationId != Guid.Empty && string.Equals(application.ApplicationKey, BotGlobalApplications.Nqrb, StringComparison.Ordinal);
}
