using BotGlobal.Contracts.Communication;

namespace BotGlobal.Communication.Application.Presence;

internal sealed record PresenceProviderLease(
    string LeaseId,
    string OpaqueUid,
    string ConnectionId,
    string CustomToken,
    string DatabaseUrl,
    string ConnectionPath,
    DateTimeOffset ExpiresAtUtc);

internal sealed record PresenceProviderConnection(
    PresenceEvidenceState State,
    DateTimeOffset ObservedAtUtc);

internal sealed record PresenceProviderSessionSnapshot(
    bool ControlFound,
    bool Complete,
    bool Overflow,
    IReadOnlyList<PresenceProviderConnection> Connections);

internal interface IPresenceProvider
{
    bool IsEnabled(string applicationKey);

    Task<PresenceProviderLease?> CreateLeaseAsync(
        PresenceValidatedSession session,
        DateTimeOffset expiresAtUtc,
        CancellationToken cancellationToken);

    Task<PresenceProviderLease?> RenewLeaseAsync(
        PresenceValidatedSession session,
        string leaseId,
        DateTimeOffset expiresAtUtc,
        CancellationToken cancellationToken);

    Task<PresenceProviderSessionSnapshot> ReadSessionAsync(
        PresenceSessionAuthority authority,
        CancellationToken cancellationToken);

    Task InvalidateAsync(
        PresenceSessionAuthority authority,
        string leaseId,
        CancellationToken cancellationToken);
}

internal interface IFirebasePresenceTokenIssuer
{
    Task<string> IssueAsync(
        string uid,
        string leaseId,
        string connectionId,
        DateTimeOffset expiresAtUtc,
        CancellationToken cancellationToken);
}

internal interface IFirebasePresenceAdminTokenSource
{
    Task<string> GetAsync(CancellationToken cancellationToken);
}
