using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application;

public sealed class MobileDeviceLifecycleService(
    PairingDbContext dbContext,
    IMobileDeviceCredentialService credentialService,
    MobileDeviceAuditRecorder auditRecorder,
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

        var now =
            timeProvider.GetUtcNow();

        device.Revoke(now);

        auditRecorder.Record(
            device.Id,
            device.PlatformClientId,
            MobileDeviceAuditKinds.UnpairedByDevice,
            MobileDeviceAuditActorTypes.Device,
            null,
            $"platform={device.Platform}",
            now);

        var pushRegistrations =
            await dbContext.PushRegistrations
                .Where(
                    registration =>
                        registration.MobileDeviceId == device.Id
                        && registration.InvalidatedAtUtc == null)
                .ToListAsync(cancellationToken);

        foreach (var registration in pushRegistrations)
        {
            registration.Invalidate(now);

            auditRecorder.Record(
                device.Id,
                device.PlatformClientId,
                MobileDeviceAuditKinds.PushInvalidated,
                MobileDeviceAuditActorTypes.System,
                null,
                $"provider={registration.Provider}",
                now);
        }

        await dbContext.SaveChangesAsync(
            cancellationToken);

        return UnpairMobileDeviceOutcome.Unpaired;
    }
}
