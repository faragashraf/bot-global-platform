using FirebaseAdmin.Messaging;
using BotGlobal.Communication.Application.MobileNotifications.Push;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

internal sealed class FirebaseAdminFcmPushSender(
    FirebaseMessaging messaging,
    Microsoft.Extensions.Options.IOptions<FcmOptions> options,
    ILogger<FirebaseAdminFcmPushSender> logger)
    : IFcmPushSender
{
    public async Task<FcmPushSendResult> SendAsync(
        ResolvedApplicationPushProvider configuration,
        FcmPushMessage message,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(configuration);
        ArgumentNullException.ThrowIfNull(message);

        if (configuration.Application.ApplicationId
                != options.Value.ApplicationId
            || !string.Equals(
                configuration.ConfigurationReference,
                options.Value.ConfigurationReference,
                StringComparison.Ordinal)
            || !string.Equals(
                configuration.FirebaseProjectId,
                options.Value.ProjectId,
                StringComparison.Ordinal)
            || string.IsNullOrWhiteSpace(
                configuration.AndroidPackageName))
        {
            logger.LogError(
                "FCM runtime configuration does not match the requested application/provider scope. ApplicationId={ApplicationId}",
                configuration.Application.ApplicationId);

            return new FcmPushSendResult(
                Accepted: false,
                MessageId: null,
                SafeErrorCode: "fcm-runtime-scope-mismatch",
                IsPermanentFailure: true);
        }

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

        var timeToLive = message.TimeToLive;

        if (timeToLive <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(
                nameof(message),
                "FCM time-to-live must be positive.");
        }

        var maximumTimeToLive = TimeSpan.FromDays(28);
        timeToLive = timeToLive > maximumTimeToLive
            ? maximumTimeToLive
            : timeToLive;

        var firebaseMessage =
            new Message
            {
                Fid =
                    message.RegistrationToken,

                Data =
                    data,

                Android =
                    new AndroidConfig
                    {
                        Priority =
                            Priority.High,

                        TimeToLive =
                            timeToLive,

                        RestrictedPackageName =
                            configuration.AndroidPackageName
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
                "FCM send failed. ErrorCode={ErrorCode}, MessagingErrorCode={MessagingErrorCode}, HttpStatus={HttpStatus}",
                exception.ErrorCode,
                exception.MessagingErrorCode,
                exception.HttpResponse?.StatusCode);

            throw;
        }
    }
}
