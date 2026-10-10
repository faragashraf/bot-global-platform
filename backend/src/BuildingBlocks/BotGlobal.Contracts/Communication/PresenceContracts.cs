using System.Text.Json.Serialization;

namespace BotGlobal.Contracts.Communication;

/// <summary>
/// Provider-neutral evidence. Unknown is intentionally distinct from disconnected.
/// </summary>
public enum PresenceEvidenceState
{
    Unknown = 0,
    Connected = 1,
    Disconnected = 2
}

public sealed record PresenceDecision(
    PresenceEvidenceState State,
    DateTimeOffset? ObservedAtUtc = null,
    bool FullyCovered = false,
    int EligibleSessionCount = 0,
    string? SafeReason = null)
{
    public static PresenceDecision Unknown(string reason) =>
        new(PresenceEvidenceState.Unknown, SafeReason: reason);
}

public sealed record PresenceSessionAuthority(
    Guid SessionId,
    Guid MembershipId,
    Guid ApplicationId,
    string ApplicationKey,
    DateTimeOffset AccessExpiresAtUtc,
    [property: JsonIgnore] string CredentialRevision);

public sealed record PresenceValidatedSession(
    PresenceSessionAuthority Authority,
    string SubjectId,
    string DisplayName,
    bool IsGuest);

/// <summary>
/// Request-local proof captured by the application-session authentication handler.
/// The credential delegate is deliberately non-serializable and retains the token only
/// inside Identity's validation closure.
/// </summary>
public sealed class PresenceConnectionCredential
{
    private readonly Func<IServiceProvider, CancellationToken, ValueTask<bool>> validator;

    public PresenceConnectionCredential(
        Guid sessionId,
        Guid membershipId,
        string applicationKey,
        string credentialRevision,
        Func<IServiceProvider, CancellationToken, ValueTask<bool>> validator)
    {
        SessionId = sessionId;
        MembershipId = membershipId;
        ApplicationKey = applicationKey;
        CredentialRevision = credentialRevision;
        this.validator = validator;
    }

    // Synthetic callers that do not cross Identity's authority boundary may retain
    // the original shape. Identity rejects this unbound form for real presence work.
    public PresenceConnectionCredential(
        Guid sessionId,
        Func<IServiceProvider, CancellationToken, ValueTask<bool>> validator)
        : this(sessionId, Guid.Empty, string.Empty, string.Empty, validator)
    {
    }

    public static object HttpContextItemKey { get; } = new();

    [JsonIgnore]
    public Guid SessionId { get; }

    [JsonIgnore]
    public Guid MembershipId { get; }

    [JsonIgnore]
    public string ApplicationKey { get; }

    [JsonIgnore]
    public string CredentialRevision { get; }

    public ValueTask<bool> ValidateAsync(
        IServiceProvider services,
        CancellationToken cancellationToken) => validator(services, cancellationToken);
}

public interface IPresenceSessionDirectory
{
    Task<PresenceValidatedSession?> ValidateConnectionAsync(
        PresenceConnectionCredential credential,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<PresenceSessionAuthority>> ListActiveSessionsAsync(
        string applicationKey,
        Guid membershipId,
        int maximumCount,
        CancellationToken cancellationToken);

    Task<bool> RevalidateAsync(
        PresenceSessionAuthority authority,
        CancellationToken cancellationToken);
}

public interface IPresenceAccessPolicy
{
    string ApplicationKey { get; }

    Task<bool> CanObserveAsync(
        Guid actorMembershipId,
        Guid counterpartMembershipId,
        CancellationToken cancellationToken);
}

public sealed record PresenceCounterpartRequest(
    PresenceConnectionCredential Credential,
    Guid CounterpartMembershipId);

public interface IPresenceDecisionReader
{
    bool IsEnabled(string applicationKey);

    Task<PresenceDecision> ObserveAsync(
        PresenceCounterpartRequest request,
        CancellationToken cancellationToken);
}

public sealed record PresenceLeaseGrant(
    string CustomToken,
    string DatabaseUrl,
    string ConnectionPath,
    string LeaseId,
    DateTimeOffset ExpiresAtUtc,
    int HeartbeatSeconds,
    int FreshnessSeconds);

public interface IPresenceLeaseService
{
    Task<PresenceLeaseGrant?> BootstrapAsync(
        PresenceConnectionCredential credential,
        CancellationToken cancellationToken);

    Task<PresenceLeaseGrant?> RenewAsync(
        PresenceConnectionCredential credential,
        string leaseId,
        CancellationToken cancellationToken);

    Task InvalidateAsync(
        PresenceConnectionCredential credential,
        string leaseId,
        CancellationToken cancellationToken);
}
