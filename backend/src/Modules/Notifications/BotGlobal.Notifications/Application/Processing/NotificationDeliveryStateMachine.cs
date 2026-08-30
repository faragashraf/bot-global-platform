using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Domain;

namespace BotGlobal.Notifications.Application.Processing;

internal static class NotificationDeliveryStateMachine
{
    public static void CompleteAttempt(
        NotificationDeliveryAttempt attempt,
        Guid leaseId,
        MobileNotificationTransportOutcome outcome,
        DateTimeOffset now)
    {
        var safeCode = SanitizeSafeCode(outcome.SafeErrorCode);
        var transport = NormalizeTransport(outcome.Transport, safeCode);
        var providerMessageId = outcome.Kind
            == MobileNotificationTransportOutcomeKind.FcmAccepted
            ? SanitizeProviderMessageId(outcome.ProviderMessageId)
            : null;

        var status = outcome.Kind switch
        {
            MobileNotificationTransportOutcomeKind.SignalRDispatched =>
                NotificationDeliveryAttemptStatus.SignalRDispatched,
            MobileNotificationTransportOutcomeKind.FcmAccepted =>
                NotificationDeliveryAttemptStatus.FcmAccepted,
            MobileNotificationTransportOutcomeKind.NoAvailableRoute
                or MobileNotificationTransportOutcomeKind.TransientFailure =>
                NotificationDeliveryAttemptStatus.RetryableFailure,
            MobileNotificationTransportOutcomeKind.PermanentFailure =>
                NotificationDeliveryAttemptStatus.PermanentFailure,
            MobileNotificationTransportOutcomeKind.DeviceRevoked =>
                NotificationDeliveryAttemptStatus.DeviceRevoked,
            MobileNotificationTransportOutcomeKind.Ambiguous =>
                NotificationDeliveryAttemptStatus.Ambiguous,
            _ => throw new ArgumentOutOfRangeException(nameof(outcome))
        };

        attempt.Complete(
            leaseId,
            status,
            now,
            transport,
            providerMessageId,
            safeCode ?? DefaultSafeCode(status));
    }

    public static void ProjectRecipient(
        NotificationRecipient recipient,
        NotificationDeliveryAttempt attempt,
        NotificationRetryOptions retry)
    {
        if (attempt.CompletedAtUtc is not DateTimeOffset completedAtUtc)
        {
            throw new InvalidOperationException(
                "A terminal delivery attempt requires a completion timestamp.");
        }

        var recipientStatus = attempt.Status switch
        {
            NotificationDeliveryAttemptStatus.SignalRDispatched =>
                NotificationRecipientStatus.SignalRDispatched,
            NotificationDeliveryAttemptStatus.FcmAccepted =>
                NotificationRecipientStatus.FcmAccepted,
            NotificationDeliveryAttemptStatus.RetryableFailure =>
                NotificationRecipientStatus.RetryScheduled,
            NotificationDeliveryAttemptStatus.PermanentFailure =>
                NotificationRecipientStatus.FailedPermanent,
            NotificationDeliveryAttemptStatus.DeviceRevoked =>
                NotificationRecipientStatus.SkippedRevoked,
            NotificationDeliveryAttemptStatus.Ambiguous =>
                NotificationRecipientStatus.Ambiguous,
            _ => throw new InvalidOperationException(
                "The delivery attempt is not ready for recipient projection.")
        };

        DateTimeOffset? nextAttemptAtUtc = recipientStatus
            == NotificationRecipientStatus.RetryScheduled
            ? completedAtUtc + CalculateRetryDelay(
                attempt.AttemptNumber,
                retry)
            : null;

        recipient.ProjectAttempt(
            attempt.Id,
            recipientStatus,
            completedAtUtc,
            attempt.Transport,
            attempt.SafeErrorCode,
            nextAttemptAtUtc);
    }

    public static TimeSpan CalculateRetryDelay(
        int attemptNumber,
        NotificationRetryOptions retry)
    {
        var exponent = Math.Min(Math.Max(0, attemptNumber - 1), 16);
        var delaySeconds = retry.InitialDelaySeconds * Math.Pow(2, exponent);
        var maximumSeconds = TimeSpan
            .FromMinutes(retry.MaximumDelayMinutes)
            .TotalSeconds;

        return TimeSpan.FromSeconds(
            Math.Min(delaySeconds, maximumSeconds));
    }

    public static string? SanitizeSafeCode(string? value)
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

    private static string? NormalizeTransport(
        string? transport,
        string? safeCode)
    {
        if (!string.IsNullOrWhiteSpace(transport))
        {
            return transport.Trim() switch
            {
                var value when value.Equals(
                    "fcm",
                    StringComparison.OrdinalIgnoreCase) => "Fcm",
                var value when value.Equals(
                    "signalr",
                    StringComparison.OrdinalIgnoreCase) => "SignalR",
                _ => null
            };
        }

        return safeCode?.StartsWith(
                "fcm-",
                StringComparison.OrdinalIgnoreCase) == true
            ? "Fcm"
            : null;
    }

    private static string? SanitizeProviderMessageId(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var normalized = new string(
            value.Trim()
                .Where(character => !char.IsControl(character))
                .Take(500)
                .ToArray());

        return normalized.Length == 0 ? null : normalized;
    }

    private static string? DefaultSafeCode(
        NotificationDeliveryAttemptStatus status)
    {
        return status switch
        {
            NotificationDeliveryAttemptStatus.RetryableFailure =>
                "route-temporarily-unavailable",
            NotificationDeliveryAttemptStatus.PermanentFailure =>
                "permanent-transport-failure",
            NotificationDeliveryAttemptStatus.DeviceRevoked =>
                "device-revoked",
            NotificationDeliveryAttemptStatus.Ambiguous =>
                "provider-outcome-unknown",
            _ => null
        };
    }
}
