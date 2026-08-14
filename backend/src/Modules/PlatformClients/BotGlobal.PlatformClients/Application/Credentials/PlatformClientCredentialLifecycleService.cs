using BotGlobal.PlatformClients.Application.Security;
using BotGlobal.PlatformClients.Domain;
using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;

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
    IPlatformClientSecretService secretService,
    ILogger<PlatformClientCredentialLifecycleService> logger)
    : IPlatformClientCredentialLifecycleService
{
    public async Task<RotatedPlatformClientCredential> RotateAsync(
        Guid clientId,
        CancellationToken cancellationToken = default)
    {
        logger.LogInformation(
            "Platform client credential rotation started. ClientId={ClientId}",
            clientId);

        await using var transaction =
            await dbContext.Database.BeginTransactionAsync(
                cancellationToken);

        logger.LogInformation(
            "Platform client credential rotation transaction started. ClientId={ClientId}",
            clientId);

        try
        {
            var client =
                await dbContext.Clients
                    .Include(x => x.Credentials)
                    .SingleOrDefaultAsync(
                        x => x.Id == clientId,
                        cancellationToken)
                ?? throw new KeyNotFoundException(
                    $"Platform client '{clientId}' was not found.");

            logger.LogInformation(
                "Platform client loaded for credential rotation. ClientId={ClientId} Status={Status} CredentialCount={CredentialCount}",
                client.Id,
                client.Status,
                client.Credentials.Count);

            if (client.Status != PlatformClientStatus.Active)
            {
                throw new InvalidOperationException(
                    "Credentials cannot be rotated for a disabled client.");
            }

            var now = DateTimeOffset.UtcNow;

            var oldUsable =
                client.Credentials
                    .Where(x => x.IsUsableAt(now))
                    .ToArray();

            logger.LogInformation(
                "Usable credentials resolved for rotation. ClientId={ClientId} UsableCredentialCount={UsableCredentialCount}",
                client.Id,
                oldUsable.Length);

            var generated =
                secretService.Generate();

            logger.LogInformation(
                "Replacement secret generated for platform client. ClientId={ClientId}",
                client.Id);

            var created =
                client.AddCredential(
                    generated.SecretHash,
                    now,
                    expiresAtUtc: null);

            // The aggregate creates the credential, while the persistence
            // boundary explicitly registers it as a new database row.
            dbContext.Credentials.Add(created);

            // Persist the replacement first.
            // Old credentials remain usable until this succeeds.
            logger.LogInformation(
                "Persisting replacement credential. ClientId={ClientId} CredentialId={CredentialId}",
                client.Id,
                created.Id);

            await dbContext.SaveChangesAsync(
                cancellationToken);

            logger.LogInformation(
                "Replacement credential persisted. ClientId={ClientId} CredentialId={CredentialId}",
                client.Id,
                created.Id);

            // Defensive verification before revoking anything.
            var replacementExists =
                await dbContext.Credentials
                    .AsNoTracking()
                    .AnyAsync(
                        x =>
                            x.Id == created.Id
                            && x.ClientId == client.Id
                            && x.RevokedAtUtc == null,
                        cancellationToken);

            logger.LogInformation(
                "Replacement credential verification completed. ClientId={ClientId} CredentialId={CredentialId} Exists={ReplacementExists}",
                client.Id,
                created.Id,
                replacementExists);

            if (!replacementExists)
            {
                throw new InvalidOperationException(
                    "The replacement credential could not be verified after persistence.");
            }

            foreach (var credential in oldUsable)
            {
                credential.Revoke(now);
            }

            logger.LogInformation(
                "Old usable credentials marked revoked. ClientId={ClientId} RevokedCount={RevokedCount}",
                client.Id,
                oldUsable.Length);

            await dbContext.SaveChangesAsync(
                cancellationToken);

            logger.LogInformation(
                "Persisted revoked credential state. ClientId={ClientId}",
                client.Id);

            await transaction.CommitAsync(
                cancellationToken);

            logger.LogInformation(
                "Platform client credential rotation committed successfully. ClientId={ClientId} CredentialId={CredentialId}",
                client.Id,
                created.Id);

            return new RotatedPlatformClientCredential(
                client.Id,
                created.Id,
                client.ClientKey,
                generated.PlainTextSecret,
                created.CreatedAtUtc);
        }
        catch (Exception exception)
        {
            logger.LogError(
                exception,
                "Platform client credential rotation failed. ClientId={ClientId}",
                clientId);

            await transaction.RollbackAsync(
                CancellationToken.None);

            logger.LogWarning(
                "Platform client credential rotation transaction rolled back. ClientId={ClientId}",
                clientId);

            throw;
        }
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
