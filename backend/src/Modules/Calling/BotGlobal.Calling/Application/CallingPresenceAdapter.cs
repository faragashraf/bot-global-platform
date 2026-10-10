using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;

namespace BotGlobal.Calling.Application;

public sealed record CallingPresenceDecision(
    PresenceDecision Evidence,
    bool AuthoritativelyUnavailable);

public sealed class CallingPresenceAdapter(
    IPresenceDecisionReader presence,
    IPresenceSessionDirectory sessions,
    IPresenceAccessPolicy accessPolicy,
    IEnumerable<ICallingReachabilityResolver> reachabilityResolvers)
{
    public Task<PresenceValidatedSession?> ValidateConnectionAsync(
        PresenceConnectionCredential credential,
        CancellationToken cancellationToken) =>
        sessions.ValidateConnectionAsync(credential, cancellationToken);

    public async Task<CallingPresenceDecision> EvaluateAsync(
        PresenceConnectionCredential credential,
        ApplicationIdentityDescriptor caller,
        CallingParticipantDescriptor callee,
        CancellationToken cancellationToken)
    {
        if (!string.Equals(caller.ApplicationKey, BotGlobalApplications.Nqrb, StringComparison.Ordinal) ||
            !string.Equals(callee.ApplicationKey, caller.ApplicationKey, StringComparison.Ordinal) ||
            !await accessPolicy.CanObserveAsync(caller.MembershipId, callee.MembershipId, cancellationToken))
            return new(PresenceDecision.Unknown("access_denied"), false);

        PresenceDecision evidence;
        using (var lookupBudget = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
        {
            lookupBudget.CancelAfter(TimeSpan.FromSeconds(1));
            try
            {
                evidence = await presence.ObserveAsync(
                    new PresenceCounterpartRequest(credential, callee.MembershipId),
                    lookupBudget.Token);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                evidence = PresenceDecision.Unknown("presence_timeout");
            }
            catch (Exception error) when (error is not OperationCanceledException)
            {
                evidence = PresenceDecision.Unknown("presence_unavailable");
            }
        }

        if (!await accessPolicy.CanObserveAsync(caller.MembershipId, callee.MembershipId, cancellationToken))
            return new(PresenceDecision.Unknown("access_changed"), false);
        if (evidence.State != PresenceEvidenceState.Disconnected || !evidence.FullyCovered)
            return new(evidence, false);

        var resolver = reachabilityResolvers.FirstOrDefault();
        if (resolver is null)
            return new(PresenceDecision.Unknown("wake_route_unknown"), false);
        IReadOnlySet<Guid> reachable;
        try
        {
            reachable = await resolver.FindReachableMembershipsAsync(
                caller.ApplicationKey,
                [callee],
                cancellationToken);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return new(PresenceDecision.Unknown("wake_route_unknown"), false);
        }
        return reachable.Contains(callee.MembershipId)
            ? new(PresenceDecision.Unknown("background_wake_possible"), false)
            : new(evidence, true);
    }
}
