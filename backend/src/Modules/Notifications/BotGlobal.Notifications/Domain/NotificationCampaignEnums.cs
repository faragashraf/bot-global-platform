namespace BotGlobal.Notifications.Domain;

public enum NotificationAudienceKind
{
    AllCurrentActiveDevices = 1
}

public enum NotificationCampaignStatus
{
    Queued = 1,
    PreparingAudience = 2,
    Dispatching = 3,
    Completed = 4,
    CompletedWithFailures = 5,
    Expired = 6,
    Failed = 7,
    Cancelled = 8
}

public enum NotificationRecipientStatus
{
    Pending = 1,
    RetryScheduled = 2,
    SignalRDispatched = 3,
    FcmAccepted = 4,
    FailedPermanent = 5,
    SkippedRevoked = 6,
    Expired = 7,
    Sending = 8,
    Ambiguous = 9,
    Cancelled = 10
}

public enum NotificationDeliveryAttemptStatus
{
    Prepared = 1,
    ProviderInvocationStarted = 2,
    SignalRDispatched = 3,
    FcmAccepted = 4,
    RetryableFailure = 5,
    PermanentFailure = 6,
    DeviceRevoked = 7,
    Ambiguous = 8,
    Expired = 9,
    Cancelled = 10
}

public enum NotificationPriority
{
    Normal = 1,
    High = 2
}
