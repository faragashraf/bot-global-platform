namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

public sealed record FcmPushMessage(
    string RegistrationToken,
    string Title,
    string Body,
    IReadOnlyDictionary<string, string>? Data,
    TimeSpan TimeToLive);

public sealed record FcmPushSendResult(
    bool Accepted,
    string? MessageId);

public interface IFcmPushSender
{
    Task<FcmPushSendResult> SendAsync(
        FcmPushMessage message,
        CancellationToken cancellationToken);
}
