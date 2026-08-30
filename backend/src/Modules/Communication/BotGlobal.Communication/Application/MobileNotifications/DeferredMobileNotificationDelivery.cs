using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Communication.Contracts.MobileNotifications;

namespace BotGlobal.Communication.Application.MobileNotifications;

internal sealed class DeferredMobileNotificationDelivery
    : IMobileNotificationDelivery
{
    public Task<MobileNotificationDeliveryResult> DeliverAsync(
        NotificationApplicationContext application,
        MobileNotificationEnvelope notification,
        IReadOnlyList<MobileRecipientDevice> devices,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(application);
        ArgumentNullException.ThrowIfNull(notification);
        ArgumentNullException.ThrowIfNull(devices);

        return Task.FromResult(
            new MobileNotificationDeliveryResult(
                AttemptedDeviceCount: devices.Count,
                DeliveredDeviceCount: 0,
                SignalRDeliveredDeviceCount: 0,
                FcmDeliveredDeviceCount: 0));
    }
}
