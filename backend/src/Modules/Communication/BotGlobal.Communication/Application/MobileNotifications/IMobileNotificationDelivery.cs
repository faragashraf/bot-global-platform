using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Communication.Contracts.MobileNotifications;

namespace BotGlobal.Communication.Application.MobileNotifications;

public sealed record MobileNotificationDeliveryResult(
    int AttemptedDeviceCount,
    int DeliveredDeviceCount,
    int SignalRDeliveredDeviceCount,
    int FcmDeliveredDeviceCount);

public interface IMobileNotificationDelivery
{
    Task<MobileNotificationDeliveryResult> DeliverAsync(
        NotificationApplicationContext application,
        MobileNotificationEnvelope notification,
        IReadOnlyList<MobileRecipientDevice> devices,
        CancellationToken cancellationToken);
}
