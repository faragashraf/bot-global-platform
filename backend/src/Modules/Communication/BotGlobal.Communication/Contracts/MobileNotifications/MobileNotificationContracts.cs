namespace BotGlobal.Communication.Contracts.MobileNotifications;

public enum MobileNotificationPriority
{
    Normal = 1,
    High = 2
}

public sealed record SendMobileNotificationRequest(
    string RecipientExternalSubjectId,
    string TitleAr,
    string TitleEn,
    string BodyAr,
    string BodyEn,
    string Type,
    MobileNotificationPriority Priority);

public sealed record MobileNotificationEnvelope(
    string NotificationId,
    string RecipientExternalSubjectId,
    string TitleAr,
    string TitleEn,
    string BodyAr,
    string BodyEn,
    string Type,
    MobileNotificationPriority Priority,
    DateTimeOffset CreatedAtUtc);

public sealed record SendMobileNotificationResponse(
    string NotificationId,
    string RecipientExternalSubjectId,
    int ActiveDeviceCount,
    string Status);
