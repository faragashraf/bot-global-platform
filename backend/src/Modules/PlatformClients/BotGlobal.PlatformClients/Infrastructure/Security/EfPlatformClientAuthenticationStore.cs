using BotGlobal.PlatformClients.Application.Authentication;
using BotGlobal.PlatformClients.Domain;
using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.PlatformClients.Infrastructure.Security;

internal sealed class EfPlatformClientAuthenticationStore(
    PlatformClientsDbContext dbContext)
    : IPlatformClientAuthenticationStore
{
    public async Task<PlatformClientAuthenticationSnapshot?> FindByClientKeyAsync(
        string normalizedClientKey,
        CancellationToken cancellationToken = default)
    {
        var client = await dbContext.Clients
            .AsNoTracking()
            .Include(item => item.Credentials)
            .Include(item => item.Capabilities)
            .SingleOrDefaultAsync(
                item => item.ClientKey == normalizedClientKey,
                cancellationToken);

        if (client is null)
        {
            return null;
        }

        return new PlatformClientAuthenticationSnapshot(
            client.Id,
            client.ClientKey,
            client.Status == PlatformClientStatus.Active,
            client.Capabilities.Select(item => item.Capability).ToArray(),
            client.Credentials.Select(
                item => new PlatformClientCredentialSnapshot(
                    item.SecretHash.ToArray(),
                    item.ExpiresAtUtc,
                    item.RevokedAtUtc)).ToArray());
    }
}
