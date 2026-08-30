namespace BotGlobal.Contracts.Notifications;

public sealed record PlatformClientDescriptor(
    Guid PlatformClientId,
    string ClientKey,
    string DisplayName,
    bool IsActive);

public interface IPlatformClientDescriptorReader
{
    Task<PlatformClientDescriptor?> FindAsync(
        Guid platformClientId,
        CancellationToken cancellationToken);
}

public sealed record AdministratorDescriptor(
    Guid UserId,
    string DisplayName,
    bool IsActive);

public interface IAdministratorDescriptorReader
{
    Task<AdministratorDescriptor?> FindAsync(
        Guid userId,
        CancellationToken cancellationToken);
}

public sealed record MobileBroadcastAudiencePreview(
    int DistinctExternalSubjectCount,
    int ActiveDeviceCount,
    int PushCapableDeviceCount);

public sealed record MobileBroadcastAudienceDevice(
    Guid DeviceId,
    string InstallationId,
    string Platform,
    string? DeviceName);

public sealed record MobileBroadcastAudiencePage(
    IReadOnlyList<MobileBroadcastAudienceDevice> Devices,
    bool HasMore);

public sealed record MobileBroadcastDeviceState(
    bool ExistsForPlatformClient,
    bool IsRevoked);

public interface IMobileBroadcastAudienceReader
{
    Task<MobileBroadcastAudiencePreview> PreviewAsync(
        NotificationApplicationContext application,
        DateTimeOffset audienceAsOfUtc,
        CancellationToken cancellationToken);

    Task<MobileBroadcastAudiencePage> ReadPageAsync(
        NotificationApplicationContext application,
        DateTimeOffset audienceAsOfUtc,
        Guid? afterDeviceId,
        int pageSize,
        CancellationToken cancellationToken);

    Task<MobileBroadcastDeviceState> GetCurrentDeviceStateAsync(
        NotificationApplicationContext application,
        Guid deviceId,
        CancellationToken cancellationToken);
}

public enum MobileNotificationTransportOutcomeKind
{
    SignalRDispatched = 1,
    FcmAccepted = 2,
    NoAvailableRoute = 3,
    TransientFailure = 4,
    PermanentFailure = 5,
    DeviceRevoked = 6
}

public sealed record MobileNotificationTransportRequest(
    Guid CampaignId,
    NotificationApplicationContext Application,
    Guid MobileDeviceId,
    string InstallationId,
    string Platform,
    string? DeviceName,
    string NotificationId,
    string TitleAr,
    string TitleEn,
    string BodyAr,
    string BodyEn,
    string Type,
    int Priority,
    TimeSpan TimeToLive);

public sealed record MobileNotificationTransportOutcome(
    MobileNotificationTransportOutcomeKind Kind,
    string? SafeErrorCode = null);

public interface IMobileNotificationTransport
{
    Task<MobileNotificationTransportOutcome> DispatchAsync(
        MobileNotificationTransportRequest request,
        CancellationToken cancellationToken);
}
