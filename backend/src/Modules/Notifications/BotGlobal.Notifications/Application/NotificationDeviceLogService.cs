using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Notifications.Application;

internal sealed class NotificationDeviceLogService(
    NotificationsDbContext dbContext)
    : INotificationDeviceLogReader
{
    public async Task<IReadOnlyList<MobileDeviceDeliveryLogEntry>>
        ReadForDeviceAsync(
            Guid mobileDeviceId,
            CancellationToken cancellationToken)
    {
        var entries = await dbContext.Recipients
            .AsNoTracking()
            .Include(candidate => candidate.Campaign)
            .Where(candidate => candidate.MobileDeviceId == mobileDeviceId)
            .OrderByDescending(candidate =>
                candidate.LastAttemptAtUtc
                ?? candidate.DispatchedAtUtc
                ?? candidate.Campaign.CreatedAtUtc)
            .Select(candidate => new MobileDeviceDeliveryLogEntry(
                candidate.CampaignId,
                candidate.Campaign.TitleAr,
                candidate.Campaign.TitleEn,
                candidate.Status.ToString(),
                candidate.LastTransport,
                candidate.LastSafeErrorCode,
                candidate.LastAttemptAtUtc
                    ?? candidate.DispatchedAtUtc))
            .ToListAsync(cancellationToken);

        return entries;
    }

    public async Task<int> PurgeForDeviceAsync(
        Guid mobileDeviceId,
        CancellationToken cancellationToken)
    {
        var purged = await dbContext.Recipients
            .Where(candidate => candidate.MobileDeviceId == mobileDeviceId)
            .ExecuteDeleteAsync(cancellationToken);

        return purged;
    }
}
