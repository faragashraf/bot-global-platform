using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.Notifications.Application.Processing;

internal sealed class NotificationDeliveryRecoveryProcessor(
    NotificationsDbContext dbContext,
    IOptions<NotificationCampaignOptions> options,
    ILogger<NotificationDeliveryRecoveryProcessor> logger)
{
    public async Task<IReadOnlySet<Guid>> RecoverBatchAsync(
        DateTimeOffset now,
        int batchSize,
        CancellationToken cancellationToken)
    {
        var recipients = await dbContext.Recipients
            .Where(recipient =>
                recipient.Status == NotificationRecipientStatus.Sending
                && recipient.CurrentAttemptId != null)
            .OrderBy(recipient => recipient.LeaseExpiresAtUtc)
            .ThenBy(recipient => recipient.Id)
            .Take(batchSize)
            .ToArrayAsync(cancellationToken);

        if (recipients.Length == 0)
        {
            return new HashSet<Guid>();
        }

        var attemptIds = recipients
            .Select(recipient => recipient.CurrentAttemptId!.Value)
            .ToArray();
        var attempts = await dbContext.DeliveryAttempts
            .Where(attempt => attemptIds.Contains(attempt.Id))
            .ToDictionaryAsync(
                attempt => attempt.Id,
                cancellationToken);

        var affectedCampaignIds = new HashSet<Guid>();
        foreach (var recipient in recipients)
        {
            var attemptId = recipient.CurrentAttemptId!.Value;
            if (!attempts.TryGetValue(attemptId, out var attempt))
            {
                logger.LogError(
                    "Notification delivery recovery found a missing attempt. DeliveryId={DeliveryId} CampaignId={CampaignId} AttemptId={AttemptId}",
                    recipient.DeliveryKey,
                    recipient.CampaignId,
                    attemptId);
                continue;
            }

            if (attempt.Status
                    == NotificationDeliveryAttemptStatus.ProviderInvocationStarted)
            {
                if (recipient.LeaseExpiresAtUtc > now)
                {
                    continue;
                }

                attempt.MarkAmbiguous(
                    now,
                    "provider-outcome-unknown");
                logger.LogWarning(
                    "Notification delivery became ambiguous after an unresolved provider invocation. DeliveryId={DeliveryId} ApplicationId={ApplicationId} CampaignId={CampaignId} AttemptId={AttemptId} AttemptNumber={AttemptNumber}",
                    attempt.DeliveryKey,
                    attempt.ApplicationId,
                    attempt.CampaignId,
                    attempt.Id,
                    attempt.AttemptNumber);
            }

            if (attempt.Status is NotificationDeliveryAttemptStatus.Prepared
                or NotificationDeliveryAttemptStatus.ProviderInvocationStarted
                or NotificationDeliveryAttemptStatus.Expired)
            {
                continue;
            }

            NotificationDeliveryStateMachine.ProjectRecipient(
                recipient,
                attempt,
                options.Value.Retry);
            affectedCampaignIds.Add(recipient.CampaignId);

            logger.LogInformation(
                "Notification delivery projection repaired. DeliveryId={DeliveryId} ApplicationId={ApplicationId} CampaignId={CampaignId} AttemptId={AttemptId} AttemptNumber={AttemptNumber} AttemptStatus={AttemptStatus}",
                attempt.DeliveryKey,
                attempt.ApplicationId,
                attempt.CampaignId,
                attempt.Id,
                attempt.AttemptNumber,
                attempt.Status);
        }

        if (affectedCampaignIds.Count > 0)
        {
            await SaveLocalTransitionAsync(cancellationToken);
        }

        return affectedCampaignIds;
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
