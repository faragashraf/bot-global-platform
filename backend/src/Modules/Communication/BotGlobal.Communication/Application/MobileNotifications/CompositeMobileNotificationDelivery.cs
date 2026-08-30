using BotGlobal.Communication.Application.MobileNotifications.Push;
using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using Microsoft.Extensions.Options;

namespace BotGlobal.Communication.Application.MobileNotifications;

internal sealed class CompositeMobileNotificationDelivery(
    SignalRMobileNotificationDelivery signalR,
    IMobileNotificationConnectionRegistry connections,
    IMobilePushDestinationResolver pushDestinations,
    IApplicationPushNotificationDispatcher push,
    IOptions<ApplicationPushProviderOptions> pushOptions)
    : IMobileNotificationDelivery
{
    public async Task<MobileNotificationDeliveryResult> DeliverAsync(
        NotificationApplicationContext application,
        MobileNotificationEnvelope notification,
        IReadOnlyList<MobileRecipientDevice> devices,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(application);
        ArgumentNullException.ThrowIfNull(notification);
        ArgumentNullException.ThrowIfNull(devices);

        var delivered = 0;
        var signalRDelivered = 0;
        var fcmDelivered = 0;

        foreach (var device in devices)
        {
            if (connections.IsConnected(device.DeviceId))
            {
                var realtimeResult =
                await signalR.DeliverAsync(
                        application,
                        notification,
                        [device],
                        cancellationToken);

                delivered +=
                    realtimeResult.DeliveredDeviceCount;

                signalRDelivered +=
                    realtimeResult.SignalRDeliveredDeviceCount;

                continue;
            }

            var destination =
                await pushDestinations.ResolveActiveAsync(
                    application,
                    device.DeviceId,
                    PushProviderNames.FirebaseCloudMessaging,
                    cancellationToken);

            if (destination is null)
            {
                continue;
            }

            var pushResult =
                await push.DispatchAsync(
                    new ApplicationPushMessage(
                        application,
                        destination.Provider,
                        destination.RegistrationToken,
                        notification.TitleAr,
                        notification.BodyAr,
                        new Dictionary<string, string>
                        {
                            ["notificationId"] =
                                notification.NotificationId,
                            ["type"] =
                                notification.Type,
                            ["titleAr"] =
                                notification.TitleAr,
                            ["titleEn"] =
                                notification.TitleEn,
                            ["bodyAr"] =
                                notification.BodyAr,
                            ["bodyEn"] =
                                notification.BodyEn,
                            ["priority"] =
                                notification.Priority.ToString()
                        },
                        TimeSpan.FromDays(
                            Math.Clamp(
                                pushOptions.Value.DefaultTimeToLiveDays,
                                1,
                                28))),
                    cancellationToken);

            if (pushResult.Kind
                == ApplicationPushDispatchKind.Accepted)
            {
                delivered++;
                fcmDelivered++;
            }
        }

        return new MobileNotificationDeliveryResult(
            AttemptedDeviceCount: devices.Count,
            DeliveredDeviceCount: delivered,
            SignalRDeliveredDeviceCount: signalRDelivered,
            FcmDeliveredDeviceCount: fcmDelivered);
    }
}
