using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application.PushRegistrations;

internal sealed class MobilePushDestinationResolver(
    PairingDbContext dbContext)
    : IMobilePushDestinationResolver
{
    public async Task<MobilePushDestination?> ResolveActiveAsync(
        NotificationApplicationContext application,
        Guid deviceId,
        string provider,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(application);

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
                && device.PlatformClientId == application.ApplicationId
                && device.RevokedAtUtc == null
            select new MobilePushDestination(
                registration.MobileDeviceId,
                registration.Provider,
                registration.RegistrationToken)
        ).SingleOrDefaultAsync(cancellationToken);
    }
}
