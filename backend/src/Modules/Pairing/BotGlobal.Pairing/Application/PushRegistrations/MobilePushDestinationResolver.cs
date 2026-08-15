using BotGlobal.Contracts.Mobile;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application.PushRegistrations;

internal sealed class MobilePushDestinationResolver(
    PairingDbContext dbContext)
    : IMobilePushDestinationResolver
{
    public async Task<MobilePushDestination?> ResolveActiveAsync(
        Guid deviceId,
        string provider,
        CancellationToken cancellationToken)
    {
        if (deviceId == Guid.Empty ||
            string.IsNullOrWhiteSpace(provider))
        {
            return null;
        }

        var normalizedProvider =
            provider.Trim().ToLowerInvariant();

        return await (
            from registration in dbContext.PushRegistrations.AsNoTracking()
            join device in dbContext.Devices.AsNoTracking()
                on registration.MobileDeviceId equals device.Id
            where
                registration.MobileDeviceId == deviceId
                && registration.Provider == normalizedProvider
                && registration.InvalidatedAtUtc == null
                && device.RevokedAtUtc == null
            select new MobilePushDestination(
                registration.MobileDeviceId,
                registration.Provider,
                registration.RegistrationToken)
        ).SingleOrDefaultAsync(cancellationToken);
    }
}
