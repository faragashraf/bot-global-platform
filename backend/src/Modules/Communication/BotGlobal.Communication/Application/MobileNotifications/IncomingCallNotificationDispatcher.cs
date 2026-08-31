using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Notifications;

namespace BotGlobal.Communication.Application.MobileNotifications;

internal sealed class IncomingCallNotificationDispatcher(
    IPlatformClientApplicationResolver applications,
    IMobileNotificationService notifications) : IIncomingCallNotificationDispatcher
{
    public async Task DispatchAsync(IncomingCallNotification notification, CancellationToken cancellationToken)
    {
        var applicationKey = notification.ApplicationKey.Trim();
        var application = await applications.FindByClientKeyAsync(applicationKey, cancellationToken);
        if (application is null ||
            !application.IsActive ||
            !string.Equals(application.ClientKey, applicationKey, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("incoming_call_application_unavailable");

        var type = notification.Kind switch
        {
            IncomingCallNotificationKind.Offered => "incoming_call",
            IncomingCallNotificationKind.Cancelled => "incoming_call_cancelled",
            IncomingCallNotificationKind.AnsweredElsewhere => "incoming_call_answered_elsewhere",
            IncomingCallNotificationKind.Expired => "incoming_call_expired",
            _ => throw new InvalidOperationException("incoming_call_kind_invalid")
        };
        await notifications.SendAsync(
            application.PlatformClientId,
            new SendMobileNotificationRequest(
                notification.RecipientSubjectId,
                application.DisplayName,
                application.DisplayName,
                notification.CallerDisplayName,
                notification.CallerDisplayName,
                type,
                MobileNotificationPriority.High,
                new Dictionary<string, string>
                {
                    ["callId"] = notification.CallId.ToString("D"),
                    ["expiresAtUtc"] = notification.ExpiresAtUtc.ToString("O")
                }),
            cancellationToken);
    }
}
