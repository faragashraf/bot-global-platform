using BotGlobal.PlatformClients.Application.Security;
using BotGlobal.PlatformClients.Domain;
using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.PlatformClients.Application.Provisioning;

public sealed record CreatePlatformClientCommand(
    string ClientKey,
    string DisplayName,
    IReadOnlyCollection<string> Capabilities);

public sealed record CreatedPlatformClient(
    Guid ClientId,
    string ClientKey,
    string DisplayName,
    IReadOnlyCollection<string> Capabilities,
    string ClientSecret);

public interface IPlatformClientProvisioningService
{
    Task<CreatedPlatformClient> CreateAsync(
        CreatePlatformClientCommand command,
        CancellationToken cancellationToken = default);
}

internal sealed class PlatformClientProvisioningService(
    PlatformClientsDbContext dbContext,
    IPlatformClientSecretService secretService)
    : IPlatformClientProvisioningService
{
    public async Task<CreatedPlatformClient> CreateAsync(
        CreatePlatformClientCommand command,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(command);

        var clientKey =
            PlatformClient.NormalizeClientKey(
                command.ClientKey);

        if (await dbContext.Clients
                .AsNoTracking()
                .AnyAsync(
                    client => client.ClientKey == clientKey,
                    cancellationToken))
        {
            throw new InvalidOperationException(
                $"Platform client '{clientKey}' already exists.");
        }

        var now = DateTimeOffset.UtcNow;

        var client =
            PlatformClient.Create(
                clientKey,
                command.DisplayName,
                now);

        foreach (var capability in
                 command.Capabilities
                     .Where(value =>
                         !string.IsNullOrWhiteSpace(value))
                     .Distinct(
                         StringComparer.OrdinalIgnoreCase))
        {
            client.GrantCapability(
                capability,
                now);
        }

        var generated =
            secretService.Generate();

        client.AddCredential(
            generated.SecretHash,
            now,
            expiresAtUtc: null);

        dbContext.Clients.Add(client);

        await dbContext.SaveChangesAsync(
            cancellationToken);

        return new CreatedPlatformClient(
            client.Id,
            client.ClientKey,
            client.DisplayName,
            client.Capabilities
                .Select(item => item.Capability)
                .Order(StringComparer.Ordinal)
                .ToArray(),
            generated.PlainTextSecret);
    }
}
