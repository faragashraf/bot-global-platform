using BotGlobal.Contracts.Mobile;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application.Notifications;

public sealed class PairingMobileNotificationRecipientResolver(
    PairingDbContext dbContext)
    : IMobileRecipientResolver
{
    public async Task<IReadOnlyList<MobileRecipientDevice>>
        ResolveActiveDevicesAsync(
            Guid platformClientId,
            string externalSubjectId,
            CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(externalSubjectId);

        var normalizedSubjectId =
            externalSubjectId.Trim();

        return await dbContext.Devices
            .AsNoTracking()
            .Where(device =>
                device.PlatformClientId == platformClientId &&
                device.ExternalSubjectId == normalizedSubjectId &&
                device.RevokedAtUtc == null)
            .Select(device =>
                new MobileRecipientDevice(
                    device.Id,
                    device.InstallationId,
                    device.Platform,
                    device.DeviceName))
            .ToListAsync(cancellationToken);
    }
}
