using FirebaseAdmin.Messaging;
using BotGlobal.Communication.Application.MobileNotifications.Push;
using BotGlobal.Communication.Contracts.MobileNotifications;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Communication.Application.MobileNotifications.Fcm;

internal sealed class FirebaseAdminFcmPushSender(
    IFirebaseMessagingResolver messagingResolver,
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

        var resolution = messagingResolver.Resolve(configuration);

        if (resolution.Kind != FirebaseMessagingResolutionKind.Ready)
        {
            logger.LogError(
                "FCM runtime profile could not be resolved for the requested application/provider scope. ApplicationId={ApplicationId}, Resolution={Resolution}",
                configuration.Application.ApplicationId,
                resolution.Kind);

            return new FcmPushSendResult(
                Accepted: false,
                MessageId: null,
                SafeErrorCode: resolution.Kind switch
                {
                    FirebaseMessagingResolutionKind.Disabled =>
                        "fcm-runtime-disabled",
                    FirebaseMessagingResolutionKind.Missing =>
                        "fcm-runtime-missing",
                    _ => "fcm-runtime-scope-mismatch"
                },
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
                            MapAndroidPriority(message.Priority),

                        TimeToLive =
                            timeToLive,

                        RestrictedPackageName =
                            configuration.AndroidPackageName
                    }
            };

        try
        {
            var messageId =
                await resolution.Messaging!.SendAsync(
                    firebaseMessage,
                    cancellationToken);

            logger.LogInformation(
                "FCM accepted message.");

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

    private static Priority MapAndroidPriority(
        MobileNotificationPriority priority) =>
        priority == MobileNotificationPriority.High
            ? Priority.High
            : Priority.Normal;
}
