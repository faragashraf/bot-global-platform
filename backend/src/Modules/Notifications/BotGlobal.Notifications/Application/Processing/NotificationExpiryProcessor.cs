using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Notifications.Application.Processing;

internal sealed class NotificationExpiryProcessor(
    NotificationsDbContext dbContext)
{
    public async Task<IReadOnlySet<Guid>> ExpireBatchAsync(
        DateTimeOffset now,
        int batchSize,
        CancellationToken cancellationToken)
    {
        var recipients = await dbContext.Recipients
            .Where(recipient =>
                (recipient.Status == NotificationRecipientStatus.Pending
                    || recipient.Status == NotificationRecipientStatus.RetryScheduled)
                && recipient.ExpiresAtUtc <= now)
            .OrderBy(recipient => recipient.ExpiresAtUtc)
            .ThenBy(recipient => recipient.Id)
            .Take(batchSize)
            .ToArrayAsync(cancellationToken);

        foreach (var recipient in recipients)
        {
            recipient.Expire();
        }

        if (recipients.Length > 0)
        {
            await dbContext.SaveChangesAsync(cancellationToken);
        }

        return recipients
            .Select(recipient => recipient.CampaignId)
            .ToHashSet();
    }

    public async Task<bool> ExpireUnexpandedCampaignAsync(
        DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        var campaign = await dbContext.Campaigns
            .Where(candidate =>
                !candidate.IsAudienceExpansionComplete
                && candidate.ExpiresAtUtc <= now)
            .OrderBy(candidate => candidate.ExpiresAtUtc)
            .FirstOrDefaultAsync(cancellationToken);

        if (campaign is null)
        {
            return false;
        }

        campaign.ExpireBeforeAudienceExpansion(now);
        await dbContext.SaveChangesAsync(cancellationToken);
        return true;
    }
}
