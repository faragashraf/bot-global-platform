using BotGlobal.PlatformClients.Application.Security;
using BotGlobal.PlatformClients.Domain;
using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.PlatformClients.Application.Credentials;

public sealed record RotatedPlatformClientCredential(
    Guid ClientId,
    Guid CredentialId,
    string ClientKey,
    string ClientSecret,
    DateTimeOffset CreatedAtUtc);

public interface IPlatformClientCredentialLifecycleService
{
    Task<RotatedPlatformClientCredential> RotateAsync(Guid clientId, CancellationToken cancellationToken = default);
    Task RevokeAsync(Guid clientId, Guid credentialId, CancellationToken cancellationToken = default);
}

internal sealed class PlatformClientCredentialLifecycleService(
    PlatformClientsDbContext dbContext,
    IPlatformClientSecretService secretService)
    : IPlatformClientCredentialLifecycleService
{
    public async Task<RotatedPlatformClientCredential> RotateAsync(Guid clientId, CancellationToken cancellationToken = default)
    {
        var client = await dbContext.Clients
            .Include(x => x.Credentials)
            .SingleOrDefaultAsync(x => x.Id == clientId, cancellationToken)
            ?? throw new KeyNotFoundException($"Platform client '{clientId}' was not found.");

        if (client.Status != PlatformClientStatus.Active)
            throw new InvalidOperationException("Credentials cannot be rotated for a disabled client.");

        var now = DateTimeOffset.UtcNow;
        var oldUsable = client.Credentials.Where(x => x.IsUsableAt(now)).ToArray();
        var generated = secretService.Generate();
        var created = client.AddCredential(generated.SecretHash, now, expiresAtUtc: null);

        foreach (var credential in oldUsable)
            credential.Revoke(now);

        await dbContext.SaveChangesAsync(cancellationToken);

        return new RotatedPlatformClientCredential(
            client.Id,
            created.Id,
            client.ClientKey,
            generated.PlainTextSecret,
            created.CreatedAtUtc);
    }

    public async Task RevokeAsync(Guid clientId, Guid credentialId, CancellationToken cancellationToken = default)
    {
        var client = await dbContext.Clients
            .Include(x => x.Credentials)
            .SingleOrDefaultAsync(x => x.Id == clientId, cancellationToken)
            ?? throw new KeyNotFoundException($"Platform client '{clientId}' was not found.");

        var credential = client.Credentials.SingleOrDefault(x => x.Id == credentialId)
            ?? throw new KeyNotFoundException($"Credential '{credentialId}' was not found for client '{clientId}'.");

        credential.Revoke(DateTimeOffset.UtcNow);
        await dbContext.SaveChangesAsync(cancellationToken);
    }
}
