using System.Data;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Notifications.Application.Processing;

internal sealed record ClaimedNotificationWork(
    Guid Id,
    Guid LeaseId);

internal sealed class NotificationWorkClaimer(
    NotificationsDbContext dbContext)
{
    private static readonly SemaphoreSlim NonRelationalClaimLock =
        new(1, 1);

    public async Task<ClaimedNotificationWork?> ClaimAudienceAsync(
        DateTimeOffset now,
        DateTimeOffset leaseExpiresAtUtc,
        CancellationToken cancellationToken)
    {
        if (!dbContext.Database.IsSqlServer())
        {
            await NonRelationalClaimLock.WaitAsync(cancellationToken);
            try
            {
                var campaign = await dbContext.Campaigns
                    .Where(candidate =>
                        (candidate.Status == NotificationCampaignStatus.Queued
                            || candidate.Status == NotificationCampaignStatus.PreparingAudience)
                        && !candidate.IsAudienceExpansionComplete
                        && candidate.ExpiresAtUtc > now
                        && (candidate.AudienceLeaseExpiresAtUtc == null
                            || candidate.AudienceLeaseExpiresAtUtc <= now))
                    .OrderBy(candidate => candidate.CreatedAtUtc)
                    .ThenBy(candidate => candidate.Id)
                    .FirstOrDefaultAsync(cancellationToken);

                if (campaign is null)
                {
                    return null;
                }

                var leaseId = Guid.NewGuid();
                campaign.ClaimAudience(leaseId, leaseExpiresAtUtc, now);
                await dbContext.SaveChangesAsync(cancellationToken);
                return new ClaimedNotificationWork(campaign.Id, leaseId);
            }
            finally
            {
                NonRelationalClaimLock.Release();
            }
        }

        await using var transaction = await dbContext.Database
            .BeginTransactionAsync(
                IsolationLevel.ReadCommitted,
                cancellationToken);

        var claimedCampaign = await dbContext.Campaigns
            .FromSqlInterpolated($"""
                SELECT TOP (1) *
                FROM [notifications].[NotificationCampaigns]
                    WITH (UPDLOCK, READPAST, ROWLOCK)
                WHERE [Status] IN ({NotificationCampaignStatus.Queued}, {NotificationCampaignStatus.PreparingAudience})
                    AND [IsAudienceExpansionComplete] = 0
                    AND [ExpiresAtUtc] > {now}
                    AND ([AudienceLeaseExpiresAtUtc] IS NULL OR [AudienceLeaseExpiresAtUtc] <= {now})
                ORDER BY [CreatedAtUtc], [Id]
                """)
            .FirstOrDefaultAsync(cancellationToken);

        if (claimedCampaign is null)
        {
            await transaction.CommitAsync(cancellationToken);
            return null;
        }

        var claimedLeaseId = Guid.NewGuid();
        claimedCampaign.ClaimAudience(
            claimedLeaseId,
            leaseExpiresAtUtc,
            now);

        await dbContext.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);

        return new ClaimedNotificationWork(
            claimedCampaign.Id,
            claimedLeaseId);
    }

    public async Task<IReadOnlyList<ClaimedNotificationWork>>
        ClaimRecipientsAsync(
            DateTimeOffset now,
            DateTimeOffset leaseExpiresAtUtc,
            int batchSize,
            CancellationToken cancellationToken)
    {
        if (!dbContext.Database.IsSqlServer())
        {
            await NonRelationalClaimLock.WaitAsync(cancellationToken);
            try
            {
                var recipients = await dbContext.Recipients
                    .Where(recipient =>
                        (recipient.Status == NotificationRecipientStatus.Pending
                            || recipient.Status == NotificationRecipientStatus.RetryScheduled)
                        && recipient.NextAttemptAtUtc <= now
                        && recipient.ExpiresAtUtc > now
                        && (recipient.LeaseExpiresAtUtc == null
                            || recipient.LeaseExpiresAtUtc <= now)
                        && recipient.Campaign.Status == NotificationCampaignStatus.Dispatching)
                    .OrderBy(recipient => recipient.NextAttemptAtUtc)
                    .ThenBy(recipient => recipient.Id)
                    .Take(batchSize)
                    .ToArrayAsync(cancellationToken);

                var claims = new List<ClaimedNotificationWork>(recipients.Length);
                foreach (var recipient in recipients)
                {
                    var leaseId = Guid.NewGuid();
                    recipient.Claim(leaseId, leaseExpiresAtUtc);
                    claims.Add(new ClaimedNotificationWork(recipient.Id, leaseId));
                }

                await dbContext.SaveChangesAsync(cancellationToken);
                return claims;
            }
            finally
            {
                NonRelationalClaimLock.Release();
            }
        }

        await using var transaction = await dbContext.Database
            .BeginTransactionAsync(
                IsolationLevel.ReadCommitted,
                cancellationToken);

        var claimedRecipients = await dbContext.Recipients
            .FromSqlInterpolated($"""
                SELECT TOP ({batchSize}) recipient.*
                FROM [notifications].[NotificationRecipients] AS recipient
                    WITH (UPDLOCK, READPAST, ROWLOCK)
                INNER JOIN [notifications].[NotificationCampaigns] AS campaign
                    ON campaign.[Id] = recipient.[CampaignId]
                WHERE recipient.[Status] IN ({NotificationRecipientStatus.Pending}, {NotificationRecipientStatus.RetryScheduled})
                    AND recipient.[NextAttemptAtUtc] <= {now}
                    AND recipient.[ExpiresAtUtc] > {now}
                    AND (recipient.[LeaseExpiresAtUtc] IS NULL OR recipient.[LeaseExpiresAtUtc] <= {now})
                    AND campaign.[Status] = {NotificationCampaignStatus.Dispatching}
                ORDER BY recipient.[NextAttemptAtUtc], recipient.[Id]
                """)
            .ToArrayAsync(cancellationToken);

        var result = new List<ClaimedNotificationWork>(
            claimedRecipients.Length);

        foreach (var recipient in claimedRecipients)
        {
            var leaseId = Guid.NewGuid();
            recipient.Claim(leaseId, leaseExpiresAtUtc);
            result.Add(new ClaimedNotificationWork(recipient.Id, leaseId));
        }

        await dbContext.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return result;
    }
}
