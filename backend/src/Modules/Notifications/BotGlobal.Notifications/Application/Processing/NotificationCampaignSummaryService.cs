using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Notifications.Application.Processing;

internal sealed class NotificationCampaignSummaryService(
    NotificationsDbContext dbContext)
{
    public async Task RefreshAsync(
        Guid campaignId,
        DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        var campaign = await dbContext.Campaigns
            .SingleOrDefaultAsync(
                candidate => candidate.Id == campaignId,
                cancellationToken);

        if (campaign is null || !campaign.IsAudienceExpansionComplete)
        {
            return;
        }

        var counts = await dbContext.Recipients
            .AsNoTracking()
            .Where(recipient => recipient.CampaignId == campaignId)
            .GroupBy(recipient => recipient.Status)
            .Select(group => new
            {
                Status = group.Key,
                Count = group.Count()
            })
            .ToDictionaryAsync(
                item => item.Status,
                item => item.Count,
                cancellationToken);

        if (counts.Count == 0
            && campaign.Status == NotificationCampaignStatus.Expired
            && campaign.AudienceDeviceCount > 0)
        {
            return;
        }

        var pending = Get(NotificationRecipientStatus.Pending)
            + Get(NotificationRecipientStatus.RetryScheduled);

        campaign.ApplySummary(
            pending,
            Get(NotificationRecipientStatus.SignalRDispatched),
            Get(NotificationRecipientStatus.FcmAccepted),
            Get(NotificationRecipientStatus.FailedPermanent),
            Get(NotificationRecipientStatus.SkippedRevoked),
            Get(NotificationRecipientStatus.Expired),
            now);

        await dbContext.SaveChangesAsync(cancellationToken);

        int Get(NotificationRecipientStatus status) =>
            counts.GetValueOrDefault(status);
    }
}
