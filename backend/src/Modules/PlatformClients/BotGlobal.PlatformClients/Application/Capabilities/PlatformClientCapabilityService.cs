using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.PlatformClients.Application.Capabilities;

public sealed record PlatformClientCapabilityState(
    Guid ClientId,
    string ClientKey,
    IReadOnlyList<string> GrantedCapabilities);

public interface IPlatformClientCapabilityService
{
    Task<PlatformClientCapabilityState> GetAsync(
        Guid clientId,
        CancellationToken cancellationToken);

    Task GrantAsync(
        Guid clientId,
        string capability,
        CancellationToken cancellationToken);

    Task RevokeAsync(
        Guid clientId,
        string capability,
        CancellationToken cancellationToken);

    Task<PlatformClientCapabilityState> SetAsync(
        Guid clientId,
        IReadOnlyCollection<string> capabilities,
        CancellationToken cancellationToken);
}

internal sealed class PlatformClientCapabilityService(
    PlatformClientsDbContext dbContext,
    IPlatformCapabilityCatalog catalog,
    TimeProvider timeProvider)
    : IPlatformClientCapabilityService
{
    public async Task<PlatformClientCapabilityState> GetAsync(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        var client =
            await LoadClientAsync(
                clientId,
                cancellationToken);

        return new PlatformClientCapabilityState(
            client.Id,
            client.ClientKey,
            client.Capabilities
                .Select(item => item.Capability)
                .Order(StringComparer.Ordinal)
                .ToArray());
    }

    public async Task GrantAsync(
        Guid clientId,
        string capability,
        CancellationToken cancellationToken)
    {
        var normalized =
            ValidateCapability(capability);

        var client =
            await LoadClientAsync(
                clientId,
                cancellationToken);

        client.GrantCapability(
            normalized,
            timeProvider.GetUtcNow());

        await dbContext.SaveChangesAsync(
            cancellationToken);
    }

    public async Task RevokeAsync(
        Guid clientId,
        string capability,
        CancellationToken cancellationToken)
    {
        var normalized =
            ValidateCapability(capability);

        var client =
            await LoadClientAsync(
                clientId,
                cancellationToken);

        client.RevokeCapability(normalized);

        await dbContext.SaveChangesAsync(
            cancellationToken);
    }

    public async Task<PlatformClientCapabilityState> SetAsync(
        Guid clientId,
        IReadOnlyCollection<string> capabilities,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(capabilities);

        var selected =
            capabilities
                .Select(ValidateCapability)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToHashSet(StringComparer.OrdinalIgnoreCase);

        var client =
            await LoadClientAsync(
                clientId,
                cancellationToken);

        var current =
            client.Capabilities
                .Select(item => item.Capability)
                .ToHashSet(StringComparer.OrdinalIgnoreCase);

        foreach (var capability in selected.Except(
                     current,
                     StringComparer.OrdinalIgnoreCase))
        {
            client.GrantCapability(
                capability,
                timeProvider.GetUtcNow());
        }

        foreach (var capability in current.Except(
                     selected,
                     StringComparer.OrdinalIgnoreCase))
        {
            client.RevokeCapability(capability);
        }

        await dbContext.SaveChangesAsync(
            cancellationToken);

        return new PlatformClientCapabilityState(
            client.Id,
            client.ClientKey,
            client.Capabilities
                .Select(item => item.Capability)
                .Order(StringComparer.Ordinal)
                .ToArray());
    }

    private string ValidateCapability(
        string capability)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(
            capability);

        var normalized = capability.Trim();

        if (!catalog.Contains(normalized))
        {
            throw new ArgumentException(
                $"Unknown platform capability '{normalized}'.",
                nameof(capability));
        }

        return normalized;
    }

    private async Task<Domain.PlatformClient> LoadClientAsync(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        var client =
            await dbContext.Clients
                .Include(item => item.Capabilities)
                .SingleOrDefaultAsync(
                    item => item.Id == clientId,
                    cancellationToken);

        return client
            ?? throw new KeyNotFoundException(
                $"Platform client '{clientId}' was not found.");
    }
}
