using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Security;

public sealed record AuthenticatedMobileDevice(
    Guid DeviceId,
    Guid PlatformClientId,
    string? ExternalSubjectId);

public interface IMobileDeviceAuthenticator
{
    Task<AuthenticatedMobileDevice?> AuthenticateAsync(
        string credential,
        CancellationToken cancellationToken);
}

internal sealed class MobileDeviceAuthenticator(
    PairingDbContext dbContext,
    IMobileDeviceCredentialService credentialService)
    : IMobileDeviceAuthenticator
{
    public async Task<AuthenticatedMobileDevice?> AuthenticateAsync(
        string credential,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(credential))
        {
            return null;
        }

        var credentialHash =
            credentialService.Hash(credential);

        return await dbContext.Devices
            .AsNoTracking()
            .Where(device =>
                device.RevokedAtUtc == null
                && device.CredentialHash.SequenceEqual(
                    credentialHash))
            .Select(device =>
                new AuthenticatedMobileDevice(
                    device.Id,
                    device.PlatformClientId,
                    device.ExternalSubjectId))
            .SingleOrDefaultAsync(cancellationToken);
    }
}
