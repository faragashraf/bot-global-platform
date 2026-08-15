using BotGlobal.Contracts.Mobile;
using BotGlobal.Communication.Contracts.MobileNotifications;

namespace BotGlobal.Communication.Application.MobileNotifications;

public sealed record MobileNotificationDeliveryResult(
    int AttemptedDeviceCount,
    int DeliveredDeviceCount);

public interface IMobileNotificationDelivery
{
    Task<MobileNotificationDeliveryResult> DeliverAsync(
        MobileNotificationEnvelope notification,
        IReadOnlyList<MobileRecipientDevice> devices,
        CancellationToken cancellationToken);
}
