namespace BotGlobal.Contracts.Mobile;

public sealed record MobileRecipientDevice(
    Guid DeviceId,
    string InstallationId,
    string Platform,
    string? DeviceName);

public interface IMobileRecipientResolver
{
    Task<IReadOnlyList<MobileRecipientDevice>> ResolveActiveDevicesAsync(
        Guid platformClientId,
        string externalSubjectId,
        CancellationToken cancellationToken);
}
