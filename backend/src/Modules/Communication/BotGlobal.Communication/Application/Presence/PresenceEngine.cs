using BotGlobal.Contracts.Communication;
using Microsoft.Extensions.Options;

namespace BotGlobal.Communication.Application.Presence;

internal sealed class PresenceEngine(
    IPresenceSessionDirectory sessions,
    IEnumerable<IPresenceAccessPolicy> policies,
    IPresenceProvider provider,
    PresenceLeaseStore leases,
    IOptions<PresenceOptions> options,
    TimeProvider timeProvider) : IPresenceDecisionReader, IPresenceLeaseService
{
    public bool IsEnabled(string applicationKey) => provider.IsEnabled(applicationKey);

    public async Task<PresenceLeaseGrant?> BootstrapAsync(
        PresenceConnectionCredential credential,
        CancellationToken cancellationToken)
    {
        var current = await sessions.ValidateConnectionAsync(credential, cancellationToken);
        if (current is null || current.IsGuest || !provider.IsEnabled(current.Authority.ApplicationKey)) return null;
        var grant = await leases.CreateAsync(current, cancellationToken);
        if (grant is null || await IsStillCurrentAsync(credential, current.Authority, cancellationToken)) return grant;
        await RevokeProvisionalAsync(current.Authority, grant.LeaseId, cancellationToken);
        return null;
    }

    public async Task<PresenceLeaseGrant?> RenewAsync(
        PresenceConnectionCredential credential,
        string leaseId,
        CancellationToken cancellationToken)
    {
        var current = await sessions.ValidateConnectionAsync(credential, cancellationToken);
        if (current is null || current.IsGuest || !provider.IsEnabled(current.Authority.ApplicationKey)) return null;
        var grant = await leases.RenewAsync(current, leaseId, cancellationToken);
        if (grant is null || await IsStillCurrentAsync(credential, current.Authority, cancellationToken)) return grant;
        await RevokeProvisionalAsync(current.Authority, grant.LeaseId, cancellationToken);
        return null;
    }

    public async Task InvalidateAsync(
        PresenceConnectionCredential credential,
        string leaseId,
        CancellationToken cancellationToken)
    {
        var current = await sessions.ValidateConnectionAsync(credential, cancellationToken);
        if (current is null || current.IsGuest) return;
        await leases.InvalidateAsync(current.Authority, leaseId, cancellationToken);
    }

    public async Task<PresenceDecision> ObserveAsync(
        PresenceCounterpartRequest request,
        CancellationToken cancellationToken)
    {
        var actor = await sessions.ValidateConnectionAsync(request.Credential, cancellationToken);
        if (actor is null || actor.IsGuest || request.CounterpartMembershipId == Guid.Empty ||
            request.CounterpartMembershipId == actor.Authority.MembershipId)
            return PresenceDecision.Unknown("authority_unavailable");
        if (!provider.IsEnabled(actor.Authority.ApplicationKey))
            return PresenceDecision.Unknown("presence_disabled");
        var policy = policies.SingleOrDefault(item =>
            string.Equals(item.ApplicationKey, actor.Authority.ApplicationKey, StringComparison.Ordinal));
        if (policy is null || !await policy.CanObserveAsync(
                actor.Authority.MembershipId,
                request.CounterpartMembershipId,
                cancellationToken))
            return PresenceDecision.Unknown("access_denied");

        var eligible = await sessions.ListActiveSessionsAsync(
            actor.Authority.ApplicationKey,
            request.CounterpartMembershipId,
            options.Value.MaxEligibleSessions + 1,
            cancellationToken);
        if (eligible.Count == 0) return PresenceDecision.Unknown("session_inventory_empty");
        if (eligible.Count > options.Value.MaxEligibleSessions)
            return PresenceDecision.Unknown("session_inventory_overflow");

        var now = timeProvider.GetUtcNow();
        var freshnessFloor = now.AddSeconds(-options.Value.FreshnessSeconds);
        DateTimeOffset? latestConnected = null;
        DateTimeOffset? latestDisconnected = null;
        var completeCoverage = true;
        try
        {
            foreach (var authority in eligible)
            {
                if (!await sessions.RevalidateAsync(authority, cancellationToken))
                {
                    completeCoverage = false;
                    continue;
                }
                var snapshot = await provider.ReadSessionAsync(authority, cancellationToken);
                if (!snapshot.ControlFound || !snapshot.Complete || snapshot.Overflow ||
                    snapshot.Connections.Count == 0 ||
                    snapshot.Connections.Count > options.Value.MaxConnectionsPerSession)
                    completeCoverage = false;

                var hasFreshEvidence = false;
                foreach (var connection in snapshot.Connections)
                {
                    if (connection.ObservedAtUtc > now.AddSeconds(5))
                    {
                        completeCoverage = false;
                        continue;
                    }
                    if (connection.ObservedAtUtc < freshnessFloor) continue;
                    hasFreshEvidence = true;
                    if (connection.State == PresenceEvidenceState.Connected)
                        latestConnected = Max(latestConnected, connection.ObservedAtUtc);
                    else if (connection.State == PresenceEvidenceState.Disconnected)
                        latestDisconnected = Max(latestDisconnected, connection.ObservedAtUtc);
                    else
                        completeCoverage = false;
                }
                if (!hasFreshEvidence) completeCoverage = false;
                if (!await sessions.RevalidateAsync(authority, cancellationToken)) completeCoverage = false;
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return PresenceDecision.Unknown("provider_unavailable");
        }

        if (!await IsStillCurrentAsync(request.Credential, actor.Authority, cancellationToken) ||
            !await policy.CanObserveAsync(actor.Authority.MembershipId, request.CounterpartMembershipId, cancellationToken))
            return PresenceDecision.Unknown("authority_changed");
        if (latestConnected.HasValue)
            return new PresenceDecision(PresenceEvidenceState.Connected, latestConnected, completeCoverage, eligible.Count);
        return completeCoverage && latestDisconnected.HasValue
            ? new PresenceDecision(PresenceEvidenceState.Disconnected, latestDisconnected, true, eligible.Count)
            : PresenceDecision.Unknown("coverage_incomplete");
    }

    private async Task<bool> IsStillCurrentAsync(
        PresenceConnectionCredential credential,
        PresenceSessionAuthority expected,
        CancellationToken cancellationToken)
    {
        var current = await sessions.ValidateConnectionAsync(credential, cancellationToken);
        return current is not null && current.Authority == expected &&
            await sessions.RevalidateAsync(expected, cancellationToken);
    }

    private async Task RevokeProvisionalAsync(
        PresenceSessionAuthority authority,
        string leaseId,
        CancellationToken cancellationToken)
    {
        try { await leases.InvalidateAsync(authority, leaseId, cancellationToken); }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
        catch { }
    }

    private static DateTimeOffset Max(DateTimeOffset? current, DateTimeOffset candidate) =>
        !current.HasValue || candidate > current.Value ? candidate : current.Value;
}
