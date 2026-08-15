using FirebaseAdmin.Messaging;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

internal sealed class FirebaseAdminFcmPushSender(
    FirebaseMessaging messaging,
    ILogger<FirebaseAdminFcmPushSender> logger)
    : IFcmPushSender
{
    public async Task<FcmPushSendResult> SendAsync(
        FcmPushMessage message,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(message);

        if (string.IsNullOrWhiteSpace(
                message.RegistrationToken))
        {
            throw new ArgumentException(
                "FCM registration token is required.",
                nameof(message));
        }

        var data =
            message.Data is null
                ? new Dictionary<string, string>()
                : new Dictionary<string, string>(
                    message.Data);

        data["title"] =
            message.Title;

        data["body"] =
            message.Body;

        var firebaseMessage =
            new Message
            {
                Token =
                    message.RegistrationToken,

                Data =
                    data,

                Android =
                    new AndroidConfig
                    {
                        Priority =
                            Priority.High,

                        TimeToLive =
                            TimeSpan.FromDays(3),

                        RestrictedPackageName =
                            "com.enpo.connect"
                    }
            };

        try
        {
            var messageId =
                await messaging.SendAsync(
                    firebaseMessage,
                    cancellationToken);

            logger.LogInformation(
                "FCM accepted message. MessageId={MessageId}",
                messageId);

            return new FcmPushSendResult(
                Accepted: true,
                MessageId: messageId);
        }
        catch (FirebaseMessagingException exception)
        {
            logger.LogError(
                exception,
                "FCM send failed. ErrorCode={ErrorCode}, MessagingErrorCode={MessagingErrorCode}, HttpStatus={HttpStatus}",
                exception.ErrorCode,
                exception.MessagingErrorCode,
                exception.HttpResponse?.StatusCode);

            throw;
        }
    }
}
