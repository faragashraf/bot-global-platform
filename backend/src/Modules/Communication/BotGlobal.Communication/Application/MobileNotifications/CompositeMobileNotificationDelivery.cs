using BotGlobal.Communication.Application.MobileNotifications.Fcm;
using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Contracts.Mobile;

namespace BotGlobal.Communication.Application.MobileNotifications;

internal sealed class CompositeMobileNotificationDelivery(
    SignalRMobileNotificationDelivery signalR,
    IMobileNotificationConnectionRegistry connections,
    IMobilePushDestinationResolver pushDestinations,
    IFcmPushSender fcm)
    : IMobileNotificationDelivery
{
    public async Task<MobileNotificationDeliveryResult> DeliverAsync(
        MobileNotificationEnvelope notification,
        IReadOnlyList<MobileRecipientDevice> devices,
        CancellationToken cancellationToken)
    {
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
                    device.DeviceId,
                    "fcm",
                    cancellationToken);

            if (destination is null)
            {
                continue;
            }

            var pushResult =
                await fcm.SendAsync(
                    new FcmPushMessage(
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
                        }),
                    cancellationToken);

            if (pushResult.Accepted)
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
