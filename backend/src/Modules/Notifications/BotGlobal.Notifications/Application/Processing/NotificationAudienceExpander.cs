using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Notifications.Application.Processing;

internal sealed class NotificationAudienceExpander(
    NotificationsDbContext dbContext,
    IMobileBroadcastAudienceReader audienceReader)
{
    public async Task<bool> ExpandClaimedPageAsync(
        ClaimedNotificationWork claim,
        int pageSize,
        DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        var campaign = await dbContext.Campaigns
            .SingleOrDefaultAsync(candidate =>
                candidate.Id == claim.Id
                && candidate.AudienceLeaseId == claim.LeaseId,
                cancellationToken);

        if (campaign is null)
        {
            return false;
        }

        if (campaign.ExpiresAtUtc <= now)
        {
            campaign.ExpireBeforeAudienceExpansion(now);
            await dbContext.SaveChangesAsync(cancellationToken);
            return true;
        }

        MobileBroadcastAudiencePage page;
        try
        {
            page = await audienceReader.ReadPageAsync(
                new NotificationApplicationContext(
                    campaign.PlatformClientId),
                campaign.AudienceAsOfUtc,
                campaign.AudienceExpansionCursor,
                pageSize,
                cancellationToken);
        }
        catch
        {
            campaign.ReleaseAudienceLease();
            await dbContext.SaveChangesAsync(cancellationToken);
            throw;
        }

        var deviceIds = page.Devices
            .Select(device => device.DeviceId)
            .ToArray();

        var existingDeviceIds = deviceIds.Length == 0
            ? new HashSet<Guid>()
            : (await dbContext.Recipients
                .AsNoTracking()
                .Where(recipient =>
                    recipient.CampaignId == campaign.Id
                    && deviceIds.Contains(recipient.MobileDeviceId))
                .Select(recipient => recipient.MobileDeviceId)
                .ToArrayAsync(cancellationToken))
                .ToHashSet();

        var added = 0;
        foreach (var device in page.Devices)
        {
            if (!existingDeviceIds.Add(device.DeviceId))
            {
                continue;
            }

            dbContext.Recipients.Add(
                NotificationRecipient.Create(
                    campaign.PlatformClientId,
                    campaign.Id,
                    device.DeviceId,
                    device.InstallationId,
                    device.Platform,
                    device.DeviceName,
                    now,
                    campaign.ExpiresAtUtc));
            added++;
        }

        var cursor = page.Devices.Count > 0
            ? page.Devices[^1].DeviceId
            : campaign.AudienceExpansionCursor;

        campaign.AdvanceAudience(
            cursor,
            added,
            !page.HasMore);

        await dbContext.SaveChangesAsync(cancellationToken);
        return true;
    }
}
