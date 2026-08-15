using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application;

public sealed class MobileDeviceLifecycleService(
    PairingDbContext dbContext,
    IMobileDeviceCredentialService credentialService,
    TimeProvider timeProvider)
    : IMobileDeviceLifecycleService
{
    public async Task<UnpairMobileDeviceOutcome> UnpairAsync(
        string credential,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(credential))
        {
            return UnpairMobileDeviceOutcome.InvalidCredential;
        }

        var credentialHash =
            credentialService.Hash(credential);

        var device =
            await dbContext.Devices
                .SingleOrDefaultAsync(
                    item =>
                        item.RevokedAtUtc == null
                        && item.CredentialHash.SequenceEqual(
                            credentialHash),
                    cancellationToken);

        if (device is null)
        {
            return UnpairMobileDeviceOutcome.InvalidCredential;
        }

        device.Revoke(
            timeProvider.GetUtcNow());

        await dbContext.SaveChangesAsync(
            cancellationToken);

        return UnpairMobileDeviceOutcome.Unpaired;
    }
}
