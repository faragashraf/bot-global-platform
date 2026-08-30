using BotGlobal.Communication.Application.MobileNotifications.Push;

namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

internal sealed record FcmPushMessage(
    string RegistrationToken,
    string Title,
    string Body,
    IReadOnlyDictionary<string, string>? Data,
    TimeSpan TimeToLive);

internal sealed record FcmPushSendResult(
    bool Accepted,
    string? MessageId,
    string? SafeErrorCode = null,
    bool IsPermanentFailure = false);

internal interface IFcmPushSender
{
    Task<FcmPushSendResult> SendAsync(
        ResolvedApplicationPushProvider configuration,
        FcmPushMessage message,
        CancellationToken cancellationToken);
}
