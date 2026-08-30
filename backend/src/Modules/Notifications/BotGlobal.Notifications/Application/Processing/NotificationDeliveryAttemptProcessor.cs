using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.Notifications.Application.Processing;

internal sealed record NotificationAttemptResult(
    Guid CampaignId,
    bool Processed,
    MobileNotificationTransportOutcomeKind? Outcome);

internal sealed class NotificationDeliveryAttemptProcessor(
    NotificationsDbContext dbContext,
    IMobileNotificationTransport transport,
    IOptions<NotificationCampaignOptions> options,
    TimeProvider timeProvider,
    ILogger<NotificationDeliveryAttemptProcessor> logger)
{
    public async Task<NotificationAttemptResult> ProcessAsync(
        ClaimedNotificationWork claim,
        CancellationToken cancellationToken)
    {
        if (claim.AttemptId == Guid.Empty)
        {
            return new NotificationAttemptResult(Guid.Empty, false, null);
        }

        var recipient = await dbContext.Recipients
            .Include(candidate => candidate.Campaign)
            .SingleOrDefaultAsync(candidate =>
                candidate.Id == claim.Id
                && candidate.LeaseId == claim.LeaseId
                && candidate.CurrentAttemptId == claim.AttemptId,
                cancellationToken);

        var attempt = await dbContext.DeliveryAttempts
            .SingleOrDefaultAsync(candidate =>
                candidate.Id == claim.AttemptId
                && candidate.NotificationRecipientId == claim.Id
                && candidate.LeaseId == claim.LeaseId,
                cancellationToken);

        if (recipient is null || attempt is null)
        {
            logger.LogWarning(
                "A stale notification delivery claim was rejected. DeliveryId={DeliveryId} AttemptId={AttemptId} LeaseId={LeaseId}",
                claim.DeliveryKey,
                claim.AttemptId,
                claim.LeaseId);
            return new NotificationAttemptResult(Guid.Empty, false, null);
        }

        var now = timeProvider.GetUtcNow();
        if (recipient.LeaseExpiresAtUtc <= now)
        {
            return new NotificationAttemptResult(
                recipient.CampaignId,
                false,
                null);
        }

        if (recipient.ExpiresAtUtc <= now
            || recipient.Campaign.ExpiresAtUtc <= now)
        {
            attempt.ExpirePrepared(now);
            recipient.Expire();
            await SaveLocalTransitionAsync(cancellationToken);

            logger.LogInformation(
                "Notification delivery expired before provider invocation. DeliveryId={DeliveryId} ApplicationId={ApplicationId} CampaignId={CampaignId} AttemptId={AttemptId} AttemptNumber={AttemptNumber}",
                recipient.DeliveryKey,
                recipient.Campaign.PlatformClientId,
                recipient.CampaignId,
                attempt.Id,
                attempt.AttemptNumber);

            return new NotificationAttemptResult(
                recipient.CampaignId,
                true,
                null);
        }

        recipient.BeginAttempt(claim.LeaseId, claim.AttemptId, now);
        attempt.BeginProviderInvocation(claim.LeaseId, now);
        await SaveLocalTransitionAsync(cancellationToken);

        logger.LogInformation(
            "Invoking notification transport. DeliveryId={DeliveryId} ApplicationId={ApplicationId} CampaignId={CampaignId} AttemptId={AttemptId} AttemptNumber={AttemptNumber}",
            recipient.DeliveryKey,
            recipient.Campaign.PlatformClientId,
            recipient.CampaignId,
            attempt.Id,
            attempt.AttemptNumber);

        var remainingLifetime = recipient.Campaign.ExpiresAtUtc - now;
        var maximumFcmLifetime = TimeSpan.FromDays(28);
        var ttl = remainingLifetime > maximumFcmLifetime
            ? maximumFcmLifetime
            : remainingLifetime;

        var outcome = await transport.DispatchAsync(
            new MobileNotificationTransportRequest(
                recipient.Campaign.Id,
                new NotificationApplicationContext(
                    recipient.Campaign.PlatformClientId),
                recipient.MobileDeviceId,
                recipient.InstallationIdSnapshot,
                recipient.PlatformSnapshot,
                recipient.DeviceNameSnapshot,
                recipient.DeliveryKey,
                recipient.Campaign.TitleAr,
                recipient.Campaign.TitleEn,
                recipient.Campaign.BodyAr,
                recipient.Campaign.BodyEn,
                recipient.Campaign.Type,
                (int)recipient.Campaign.Priority,
                ttl,
                attempt.Id),
            cancellationToken);

        await dbContext.Entry(attempt).ReloadAsync(cancellationToken);
        if (attempt.Status
                != NotificationDeliveryAttemptStatus.ProviderInvocationStarted
            || attempt.LeaseId != claim.LeaseId)
        {
            logger.LogWarning(
                "A stale provider result was ignored. DeliveryId={DeliveryId} ApplicationId={ApplicationId} CampaignId={CampaignId} AttemptId={AttemptId} AttemptNumber={AttemptNumber}",
                recipient.DeliveryKey,
                recipient.Campaign.PlatformClientId,
                recipient.CampaignId,
                attempt.Id,
                attempt.AttemptNumber);
            return new NotificationAttemptResult(
                recipient.CampaignId,
                false,
                outcome.Kind);
        }

        var completedAtUtc = timeProvider.GetUtcNow();
        NotificationDeliveryStateMachine.CompleteAttempt(
            attempt,
            claim.LeaseId,
            outcome,
            completedAtUtc);

        // Persist provider truth before recipient/campaign projections. Repair
        // can replay local projections without repeating the external send.
        await SaveLocalTransitionAsync(cancellationToken);

        logger.LogInformation(
            "Notification provider result persisted. DeliveryId={DeliveryId} ApplicationId={ApplicationId} CampaignId={CampaignId} AttemptId={AttemptId} AttemptNumber={AttemptNumber} Outcome={Outcome} ProviderMessageId={ProviderMessageId}",
            recipient.DeliveryKey,
            recipient.Campaign.PlatformClientId,
            recipient.CampaignId,
            attempt.Id,
            attempt.AttemptNumber,
            outcome.Kind,
            attempt.ProviderMessageId);

        await dbContext.Entry(recipient).ReloadAsync(cancellationToken);
        if (recipient.Status != NotificationRecipientStatus.Sending
            || recipient.CurrentAttemptId != attempt.Id
            || recipient.LeaseId != claim.LeaseId)
        {
            logger.LogWarning(
                "A stale recipient projection was rejected after provider completion. DeliveryId={DeliveryId} ApplicationId={ApplicationId} CampaignId={CampaignId} AttemptId={AttemptId} AttemptNumber={AttemptNumber}",
                attempt.DeliveryKey,
                attempt.ApplicationId,
                attempt.CampaignId,
                attempt.Id,
                attempt.AttemptNumber);
            return new NotificationAttemptResult(
                recipient.CampaignId,
                false,
                outcome.Kind);
        }

        NotificationDeliveryStateMachine.ProjectRecipient(
            recipient,
            attempt,
            options.Value.Retry);
        await SaveLocalTransitionAsync(cancellationToken);

        return new NotificationAttemptResult(
            recipient.CampaignId,
            true,
            outcome.Kind);
    }

    private async Task SaveLocalTransitionAsync(
        CancellationToken cancellationToken)
    {
        IDbContextTransaction? transaction = null;
        if (dbContext.Database.IsRelational())
        {
            transaction = await dbContext.Database.BeginTransactionAsync(
                cancellationToken);
        }

        try
        {
            await dbContext.SaveChangesAsync(cancellationToken);
            if (transaction is not null)
            {
                await transaction.CommitAsync(cancellationToken);
            }
        }
        catch
        {
            if (transaction is not null)
            {
                await transaction.RollbackAsync(CancellationToken.None);
            }

            throw;
        }
        finally
        {
            if (transaction is not null)
            {
                await transaction.DisposeAsync();
            }
        }
    }
}
