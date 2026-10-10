using BotGlobal.Contracts.Communication;
using Microsoft.Extensions.Options;

namespace BotGlobal.Communication.Application.Presence;

internal sealed class PresenceLeaseStore(
    IPresenceProvider provider,
    IOptions<PresenceOptions> options,
    TimeProvider timeProvider)
{
    public async Task<PresenceLeaseGrant?> CreateAsync(
        PresenceValidatedSession session,
        CancellationToken cancellationToken)
    {
        if (!provider.IsEnabled(session.Authority.ApplicationKey)) return null;
        var now = timeProvider.GetUtcNow();
        var configuredExpiry = now.AddSeconds(options.Value.LeaseSeconds);
        var expiresAt = configuredExpiry < session.Authority.AccessExpiresAtUtc
            ? configuredExpiry
            : session.Authority.AccessExpiresAtUtc;
        if (expiresAt <= now) return null;
        var created = await provider.CreateLeaseAsync(session, expiresAt, cancellationToken);
        return created is null ? null : new PresenceLeaseGrant(
            created.CustomToken,
            created.DatabaseUrl,
            created.ConnectionPath,
            created.LeaseId,
            created.ExpiresAtUtc,
            options.Value.HeartbeatSeconds,
            options.Value.FreshnessSeconds);
    }

    public Task InvalidateAsync(
        PresenceSessionAuthority authority,
        string leaseId,
        CancellationToken cancellationToken) =>
        provider.InvalidateAsync(authority, NormalizeLeaseId(leaseId), cancellationToken);

    public async Task<PresenceLeaseGrant?> RenewAsync(
        PresenceValidatedSession session,
        string leaseId,
        CancellationToken cancellationToken)
    {
        if (!provider.IsEnabled(session.Authority.ApplicationKey)) return null;
        var now = timeProvider.GetUtcNow();
        var configuredExpiry = now.AddSeconds(options.Value.LeaseSeconds);
        var expiresAt = configuredExpiry < session.Authority.AccessExpiresAtUtc
            ? configuredExpiry
            : session.Authority.AccessExpiresAtUtc;
        if (expiresAt <= now) return null;
        var created = await provider.RenewLeaseAsync(
            session, NormalizeLeaseId(leaseId), expiresAt, cancellationToken);
        return created is null ? null : new PresenceLeaseGrant(
            created.CustomToken,
            created.DatabaseUrl,
            created.ConnectionPath,
            created.LeaseId,
            created.ExpiresAtUtc,
            options.Value.HeartbeatSeconds,
            options.Value.FreshnessSeconds);
    }

    private static string NormalizeLeaseId(string value)
    {
        var normalized = value?.Trim();
        if (string.IsNullOrWhiteSpace(normalized) || normalized.Length > 80 ||
            normalized.Any(character => !char.IsLetterOrDigit(character) && character is not '-' and not '_'))
            throw new ArgumentException("Presence lease id is invalid.", nameof(value));
        return normalized;
    }
}
