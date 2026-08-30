namespace BotGlobal.Contracts.Notifications;

public sealed record MobileDeviceDeliveryLogEntry(
    Guid CampaignId,
    string CampaignTitleAr,
    string CampaignTitleEn,
    string Status,
    string? Transport,
    string? SafeErrorCode,
    DateTimeOffset? OccurredAtUtc);

public interface INotificationDeviceLogReader
{
    Task<IReadOnlyList<MobileDeviceDeliveryLogEntry>> ReadForDeviceAsync(
        Guid mobileDeviceId,
        CancellationToken cancellationToken);

    Task<int> PurgeForDeviceAsync(
        Guid mobileDeviceId,
        CancellationToken cancellationToken);
}
