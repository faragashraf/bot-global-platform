using BotGlobal.Contracts.Notifications;
using BotGlobal.PlatformClients.Domain;
using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.PlatformClients.Application.Queries;

internal sealed class PlatformClientDescriptorReader(
    PlatformClientsDbContext dbContext)
    : IPlatformClientDescriptorReader,
      IPlatformClientApplicationResolver
{
    public Task<PlatformClientDescriptor?> FindAsync(
        Guid platformClientId,
        CancellationToken cancellationToken)
    {
        return dbContext.Clients
            .AsNoTracking()
            .Where(client => client.Id == platformClientId)
            .Select(client => new PlatformClientDescriptor(
                client.Id,
                client.ClientKey,
                client.DisplayName,
                client.Status == PlatformClientStatus.Active))
            .SingleOrDefaultAsync(cancellationToken);
    }


    public Task<PlatformClientDescriptor?> FindByClientKeyAsync(
        string clientKey,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(clientKey))
        {
            return Task.FromResult<PlatformClientDescriptor?>(null);
        }

        var normalizedClientKey = clientKey.Trim().ToLowerInvariant();

        return dbContext.Clients
            .AsNoTracking()
            .Where(client => client.ClientKey == normalizedClientKey)
            .Select(client => new PlatformClientDescriptor(
                client.Id,
                client.ClientKey,
                client.DisplayName,
                client.Status == PlatformClientStatus.Active))
            .SingleOrDefaultAsync(cancellationToken);
    }
}
