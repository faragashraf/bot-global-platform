using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Notifications.Application.Processing;

internal sealed class NotificationCampaignSummaryService(
    NotificationsDbContext dbContext,
    ILogger<NotificationCampaignSummaryService>? logger = null)
{
    public async Task<bool> RefreshNextDispatchingAsync(
        DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        var campaignId = await dbContext.Campaigns
            .AsNoTracking()
            .Where(campaign =>
                campaign.Status == NotificationCampaignStatus.Dispatching
                && dbContext.Recipients.Any(recipient =>
                    recipient.CampaignId == campaign.Id)
                && !dbContext.Recipients.Any(recipient =>
                    recipient.CampaignId == campaign.Id
                    && (recipient.Status == NotificationRecipientStatus.Pending
                        || recipient.Status == NotificationRecipientStatus.RetryScheduled
                        || recipient.Status == NotificationRecipientStatus.Sending)))
            .OrderBy(campaign => campaign.ProcessingStartedAtUtc)
            .ThenBy(campaign => campaign.Id)
            .Select(campaign => (Guid?)campaign.Id)
            .FirstOrDefaultAsync(cancellationToken);

        if (!campaignId.HasValue)
        {
            return false;
        }

        await RefreshAsync(
            campaignId.Value,
            now,
            cancellationToken);
        return true;
    }

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
            + Get(NotificationRecipientStatus.RetryScheduled)
            + Get(NotificationRecipientStatus.Sending);

        campaign.ApplySummary(
            pending,
            Get(NotificationRecipientStatus.SignalRDispatched),
            Get(NotificationRecipientStatus.FcmAccepted),
            Get(NotificationRecipientStatus.FailedPermanent)
                + Get(NotificationRecipientStatus.Ambiguous),
            Get(NotificationRecipientStatus.SkippedRevoked)
                + Get(NotificationRecipientStatus.Cancelled),
            Get(NotificationRecipientStatus.Expired),
            now);

        await dbContext.SaveChangesAsync(cancellationToken);

        logger?.LogInformation(
            "Notification campaign summary projection refreshed. CampaignId={CampaignId} Pending={PendingCount} SignalRDispatched={SignalRDispatchedCount} FcmAccepted={FcmAcceptedCount} FailedOrAmbiguous={FailedCount} Skipped={SkippedCount} Expired={ExpiredCount} CampaignStatus={CampaignStatus}",
            campaignId,
            pending,
            Get(NotificationRecipientStatus.SignalRDispatched),
            Get(NotificationRecipientStatus.FcmAccepted),
            Get(NotificationRecipientStatus.FailedPermanent)
                + Get(NotificationRecipientStatus.Ambiguous),
            Get(NotificationRecipientStatus.SkippedRevoked)
                + Get(NotificationRecipientStatus.Cancelled),
            Get(NotificationRecipientStatus.Expired),
            campaign.Status);

        int Get(NotificationRecipientStatus status) =>
            counts.GetValueOrDefault(status);
    }
}
