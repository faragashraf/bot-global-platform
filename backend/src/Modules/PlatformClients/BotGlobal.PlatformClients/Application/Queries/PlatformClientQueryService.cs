using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.PlatformClients.Application.Queries;

public sealed record PlatformClientCredentialListItem(
    Guid Id,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset? ExpiresAtUtc,
    DateTimeOffset? RevokedAtUtc,
    bool IsUsable);

public sealed record PlatformClientListItem(
    Guid Id,
    string ClientKey,
    string DisplayName,
    string Status,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset? DisabledAtUtc,
    IReadOnlyCollection<string> Capabilities,
    IReadOnlyCollection<PlatformClientCredentialListItem> Credentials,
    int ActiveCredentialCount);

public interface IPlatformClientQueryService
{
    Task<IReadOnlyCollection<PlatformClientListItem>> ListAsync(CancellationToken cancellationToken = default);
}

internal sealed class PlatformClientQueryService(PlatformClientsDbContext dbContext) : IPlatformClientQueryService
{
    public async Task<IReadOnlyCollection<PlatformClientListItem>> ListAsync(CancellationToken cancellationToken = default)
    {
        var now = DateTimeOffset.UtcNow;
        return await dbContext.Clients.AsNoTracking()
            .OrderBy(x => x.DisplayName).ThenBy(x => x.ClientKey)
            .Select(client => new PlatformClientListItem(
                client.Id,
                client.ClientKey,
                client.DisplayName,
                client.Status.ToString(),
                client.CreatedAtUtc,
                client.DisabledAtUtc,
                client.Capabilities.Select(x => x.Capability).OrderBy(x => x).ToArray(),
                client.Credentials.OrderByDescending(x => x.CreatedAtUtc)
                    .Select(x => new PlatformClientCredentialListItem(
                        x.Id,
                        x.CreatedAtUtc,
                        x.ExpiresAtUtc,
                        x.RevokedAtUtc,
                        x.RevokedAtUtc == null && (x.ExpiresAtUtc == null || x.ExpiresAtUtc > now)))
                    .ToArray(),
                client.Credentials.Count(x => x.RevokedAtUtc == null && (x.ExpiresAtUtc == null || x.ExpiresAtUtc > now))))
            .ToArrayAsync(cancellationToken);
    }
}
