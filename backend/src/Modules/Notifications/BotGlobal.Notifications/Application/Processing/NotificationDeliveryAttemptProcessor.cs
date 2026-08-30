using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
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
    TimeProvider timeProvider)
{
    public async Task<NotificationAttemptResult> ProcessAsync(
        ClaimedNotificationWork claim,
        CancellationToken cancellationToken)
    {
        var now = timeProvider.GetUtcNow();
        var recipient = await dbContext.Recipients
            .Include(candidate => candidate.Campaign)
            .SingleOrDefaultAsync(candidate =>
                candidate.Id == claim.Id
                && candidate.LeaseId == claim.LeaseId,
                cancellationToken);

        if (recipient is null)
        {
            return new NotificationAttemptResult(
                Guid.Empty,
                false,
                null);
        }

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
            recipient.Expire();
            await dbContext.SaveChangesAsync(cancellationToken);
            return new NotificationAttemptResult(
                recipient.CampaignId,
                true,
                null);
        }

        var remainingLifetime = recipient.Campaign.ExpiresAtUtc - now;
        var maximumFcmLifetime = TimeSpan.FromDays(28);
        var ttl = remainingLifetime > maximumFcmLifetime
            ? maximumFcmLifetime
            : remainingLifetime;

        var outcome = await transport.DispatchAsync(
            new MobileNotificationTransportRequest(
                recipient.Campaign.Id,
                recipient.Campaign.PlatformClientId,
                recipient.MobileDeviceId,
                recipient.InstallationIdSnapshot,
                recipient.PlatformSnapshot,
                recipient.DeviceNameSnapshot,
                recipient.Campaign.Id.ToString("N"),
                recipient.Campaign.TitleAr,
                recipient.Campaign.TitleEn,
                recipient.Campaign.BodyAr,
                recipient.Campaign.BodyEn,
                recipient.Campaign.Type,
                (int)recipient.Campaign.Priority,
                ttl),
            cancellationToken);

        ApplyOutcome(recipient, outcome, now);
        await dbContext.SaveChangesAsync(cancellationToken);

        return new NotificationAttemptResult(
            recipient.CampaignId,
            true,
            outcome.Kind);
    }

    private void ApplyOutcome(
        NotificationRecipient recipient,
        MobileNotificationTransportOutcome outcome,
        DateTimeOffset now)
    {
        var safeCode = SanitizeSafeCode(outcome.SafeErrorCode);

        switch (outcome.Kind)
        {
            case MobileNotificationTransportOutcomeKind.SignalRDispatched:
                recipient.CompleteAttempt(
                    NotificationRecipientStatus.SignalRDispatched,
                    now,
                    "SignalR",
                    null,
                    null);
                break;

            case MobileNotificationTransportOutcomeKind.FcmAccepted:
                recipient.CompleteAttempt(
                    NotificationRecipientStatus.FcmAccepted,
                    now,
                    "Fcm",
                    null,
                    null);
                break;

            case MobileNotificationTransportOutcomeKind.PermanentFailure:
                recipient.CompleteAttempt(
                    NotificationRecipientStatus.FailedPermanent,
                    now,
                    ResolveTransport(safeCode),
                    safeCode ?? "permanent-transport-failure",
                    null);
                break;

            case MobileNotificationTransportOutcomeKind.DeviceRevoked:
                recipient.CompleteAttempt(
                    NotificationRecipientStatus.SkippedRevoked,
                    now,
                    null,
                    safeCode ?? "device-revoked",
                    null);
                break;

            case MobileNotificationTransportOutcomeKind.NoAvailableRoute:
            case MobileNotificationTransportOutcomeKind.TransientFailure:
                recipient.CompleteAttempt(
                    NotificationRecipientStatus.RetryScheduled,
                    now,
                    ResolveTransport(safeCode),
                    safeCode ?? "route-temporarily-unavailable",
                    now + CalculateRetryDelay(recipient.AttemptCount));
                break;

            default:
                throw new ArgumentOutOfRangeException(nameof(outcome));
        }
    }

    private TimeSpan CalculateRetryDelay(int priorAttemptCount)
    {
        var retry = options.Value.Retry;
        var exponent = Math.Min(priorAttemptCount, 16);
        var delaySeconds = retry.InitialDelaySeconds * Math.Pow(2, exponent);
        var maximumSeconds = TimeSpan
            .FromMinutes(retry.MaximumDelayMinutes)
            .TotalSeconds;

        return TimeSpan.FromSeconds(
            Math.Min(delaySeconds, maximumSeconds));
    }

    private static string? ResolveTransport(string? safeCode)
    {
        return safeCode?.StartsWith(
                "fcm-",
                StringComparison.OrdinalIgnoreCase) == true
            ? "Fcm"
            : null;
    }

    private static string? SanitizeSafeCode(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var normalized = new string(
            value.Trim()
                .ToLowerInvariant()
                .Where(character =>
                    char.IsAsciiLetterOrDigit(character)
                    || character == '-')
                .Take(100)
                .ToArray());

        return normalized.Length == 0 ? null : normalized;
    }
}
