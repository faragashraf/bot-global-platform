using BotGlobal.Contracts.Notifications;

namespace BotGlobal.Notifications.Application;

public sealed record CreateNotificationCampaignCommand(
    Guid PlatformClientId,
    string TitleAr,
    string TitleEn,
    string BodyAr,
    string BodyEn,
    string Type,
    string Priority,
    int? LifetimeDays,
    string AudienceKind,
    string IdempotencyKey,
    Guid CreatedByUserId,
    string CreatedByDisplayName);

public sealed record NotificationAudiencePreviewResponse(
    Guid PlatformClientId,
    string ClientKey,
    string DisplayName,
    DateTimeOffset AudienceAsOfUtc,
    int DistinctExternalSubjectCount,
    int ActiveDeviceCount,
    int PushCapableDeviceCount);

public sealed record NotificationCampaignAcceptedResponse(
    Guid CampaignId,
    string Status,
    DateTimeOffset AudienceAsOfUtc,
    int ExpectedSubjectCount,
    int ExpectedDeviceCount,
    int ActualRecipientCount,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset ExpiresAtUtc);

public sealed record NotificationCampaignListQuery(
    ApplicationAdministrationScope ApplicationScope,
    string? Status,
    DateTimeOffset? FromUtc,
    DateTimeOffset? ToUtc,
    int Page,
    int PageSize);

public sealed record NotificationCampaignSummaryResponse(
    Guid CampaignId,
    Guid PlatformClientId,
    string PlatformClientKey,
    string PlatformClientDisplayName,
    string AudienceKind,
    string Priority,
    string Type,
    string Status,
    DateTimeOffset AudienceAsOfUtc,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset ExpiresAtUtc,
    DateTimeOffset? ProcessingStartedAtUtc,
    DateTimeOffset? CompletedAtUtc,
    string CreatedByDisplayName,
    int AudienceSubjectCount,
    int AudienceDeviceCount,
    int PushCapableDeviceCount,
    int PendingCount,
    int SignalRDispatchedCount,
    int FcmAcceptedCount,
    int FailedCount,
    int SkippedCount,
    int ExpiredCount);

public sealed record NotificationCampaignPageResponse(
    IReadOnlyList<NotificationCampaignSummaryResponse> Items,
    int Page,
    int PageSize,
    int TotalCount,
    int QueuedOrProcessingCount,
    int CompletedCount,
    int CompletedWithFailuresOrExpiredCount);

public interface INotificationCampaignService
{
    Task<NotificationAudiencePreviewResponse> PreviewAudienceAsync(
        Guid platformClientId,
        CancellationToken cancellationToken);

    Task<NotificationCampaignAcceptedResponse> CreateAsync(
        CreateNotificationCampaignCommand command,
        CancellationToken cancellationToken);

    Task<NotificationCampaignPageResponse> ListAsync(
        NotificationCampaignListQuery query,
        CancellationToken cancellationToken);

    Task<NotificationCampaignSummaryResponse?> FindAsync(
        ApplicationAdministrationScope applicationScope,
        Guid campaignId,
        CancellationToken cancellationToken);

    Task<NotificationCampaignSummaryResponse?> CancelAsync(
        ApplicationAdministrationScope applicationScope,
        Guid campaignId,
        CancellationToken cancellationToken);
}

public sealed class NotificationCampaignValidationException(
    IReadOnlyDictionary<string, string[]> errors)
    : Exception("Notification campaign validation failed.")
{
    public IReadOnlyDictionary<string, string[]> Errors { get; } = errors;
}

public sealed class NotificationCampaignConflictException(string message)
    : Exception(message);
