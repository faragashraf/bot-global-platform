using BotGlobal.Contracts.Notifications;

namespace BotGlobal.Contracts.Mobile;

public sealed record MobilePushDestination(
    Guid DeviceId,
    string Provider,
    string RegistrationToken);

public interface IMobilePushDestinationResolver
{
    Task<MobilePushDestination?> ResolveActiveAsync(
        NotificationApplicationContext application,
        Guid deviceId,
        string provider,
        CancellationToken cancellationToken);
}
