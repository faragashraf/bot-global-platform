using Microsoft.Extensions.Logging;

namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

internal sealed class DisabledFcmPushSender(
    ILogger<DisabledFcmPushSender> logger)
    : IFcmPushSender
{
    public Task<FcmPushSendResult> SendAsync(
        FcmPushMessage message,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(message);

        logger.LogWarning(
            "FCM delivery is disabled; the push message was not sent.");

        return Task.FromResult(
            new FcmPushSendResult(
                Accepted: false,
                MessageId: null));
    }
}
