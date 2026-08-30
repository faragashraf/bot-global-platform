using Microsoft.Extensions.Logging;
using BotGlobal.Communication.Application.MobileNotifications.Push;

namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

internal sealed class DisabledFcmPushSender(
    ILogger<DisabledFcmPushSender> logger)
    : IFcmPushSender
{
    public Task<FcmPushSendResult> SendAsync(
        ResolvedApplicationPushProvider configuration,
        FcmPushMessage message,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(configuration);
        ArgumentNullException.ThrowIfNull(message);

        logger.LogWarning(
            "FCM runtime is disabled for application {ApplicationId}; the push message was not sent.",
            configuration.Application.ApplicationId);

        return Task.FromResult(
            new FcmPushSendResult(
                Accepted: false,
                MessageId: null,
                SafeErrorCode: "fcm-runtime-disabled"));
    }
}
