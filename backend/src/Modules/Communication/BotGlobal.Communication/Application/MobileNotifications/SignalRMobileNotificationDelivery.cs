using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Communication.Hubs;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using Microsoft.AspNetCore.SignalR;

namespace BotGlobal.Communication.Application.MobileNotifications;

internal sealed class SignalRMobileNotificationDelivery(
    IHubContext<MobileNotificationsHub> hubContext,
    IMobileNotificationConnectionRegistry connections)
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

        var dispatched = 0;

        foreach (var device in devices)
        {
            if (!connections.IsConnected(
                    device.DeviceId))
            {
                continue;
            }

            await hubContext.Clients
                .Group(
                    MobileNotificationRealtimeContract.DeviceGroup(
                        device.DeviceId))
                .SendAsync(
                    MobileNotificationRealtimeContract.ReceiveEvent,
                    notification,
                    cancellationToken);

            dispatched++;
        }

        return new MobileNotificationDeliveryResult(
            AttemptedDeviceCount: devices.Count,
            DeliveredDeviceCount: dispatched,
            SignalRDeliveredDeviceCount: dispatched,
            FcmDeliveredDeviceCount: 0);
    }
}
