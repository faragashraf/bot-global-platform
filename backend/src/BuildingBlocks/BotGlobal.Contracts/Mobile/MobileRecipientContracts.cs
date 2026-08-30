using BotGlobal.Contracts.Notifications;

namespace BotGlobal.Contracts.Mobile;

public sealed record MobileRecipientDevice(
    Guid DeviceId,
    string InstallationId,
    string Platform,
    string? DeviceName);

public interface IMobileRecipientResolver
{
    Task<IReadOnlyList<MobileRecipientDevice>> ResolveActiveDevicesAsync(
        NotificationApplicationContext application,
        string externalSubjectId,
        CancellationToken cancellationToken);
}
