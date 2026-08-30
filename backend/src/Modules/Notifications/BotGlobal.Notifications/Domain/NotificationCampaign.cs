namespace BotGlobal.Notifications.Domain;

public sealed class NotificationCampaign
{
    private NotificationCampaign()
    {
    }

    private NotificationCampaign(
        Guid id,
        Guid platformClientId,
        string platformClientKeySnapshot,
        string platformClientDisplayNameSnapshot,
        NotificationAudienceKind audienceKind,
        DateTimeOffset audienceAsOfUtc,
        string titleAr,
        string titleEn,
        string bodyAr,
        string bodyEn,
        string type,
        NotificationPriority priority,
        string idempotencyKey,
        string requestFingerprint,
        Guid createdByUserId,
        string createdByDisplayNameSnapshot,
        DateTimeOffset createdAtUtc,
        DateTimeOffset expiresAtUtc,
        int audienceSubjectCount,
        int audienceDeviceCount,
        int pushCapableDeviceCount)
    {
        Id = id;
        PlatformClientId = platformClientId;
        PlatformClientKeySnapshot = platformClientKeySnapshot;
        PlatformClientDisplayNameSnapshot = platformClientDisplayNameSnapshot;
        AudienceKind = audienceKind;
        AudienceAsOfUtc = audienceAsOfUtc;
        TitleAr = titleAr;
        TitleEn = titleEn;
        BodyAr = bodyAr;
        BodyEn = bodyEn;
        Type = type;
        Priority = priority;
        Status = NotificationCampaignStatus.Queued;
        IdempotencyKey = idempotencyKey;
        RequestFingerprint = requestFingerprint;
        CreatedByUserId = createdByUserId;
        CreatedByDisplayNameSnapshot = createdByDisplayNameSnapshot;
        CreatedAtUtc = createdAtUtc;
        ExpiresAtUtc = expiresAtUtc;
        AudienceSubjectCount = audienceSubjectCount;
        AudienceDeviceCount = audienceDeviceCount;
        PushCapableDeviceCount = pushCapableDeviceCount;
    }

    public Guid Id { get; private set; }
    public Guid PlatformClientId { get; private set; }
    public string PlatformClientKeySnapshot { get; private set; } = string.Empty;
    public string PlatformClientDisplayNameSnapshot { get; private set; } = string.Empty;
    public NotificationAudienceKind AudienceKind { get; private set; }
    public DateTimeOffset AudienceAsOfUtc { get; private set; }
    public string TitleAr { get; private set; } = string.Empty;
    public string TitleEn { get; private set; } = string.Empty;
    public string BodyAr { get; private set; } = string.Empty;
    public string BodyEn { get; private set; } = string.Empty;
    public string Type { get; private set; } = string.Empty;
    public NotificationPriority Priority { get; private set; }
    public NotificationCampaignStatus Status { get; private set; }
    public string IdempotencyKey { get; private set; } = string.Empty;
    public string RequestFingerprint { get; private set; } = string.Empty;
    public Guid CreatedByUserId { get; private set; }
    public string CreatedByDisplayNameSnapshot { get; private set; } = string.Empty;
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset ExpiresAtUtc { get; private set; }
    public DateTimeOffset? ProcessingStartedAtUtc { get; private set; }
    public DateTimeOffset? CompletedAtUtc { get; private set; }
    public int AudienceSubjectCount { get; private set; }
    public int AudienceDeviceCount { get; private set; }
    public int PushCapableDeviceCount { get; private set; }
    public int PendingCount { get; private set; }
    public int SignalRDispatchedCount { get; private set; }
    public int FcmAcceptedCount { get; private set; }
    public int FailedCount { get; private set; }
    public int SkippedCount { get; private set; }
    public int ExpiredCount { get; private set; }
    public Guid? AudienceExpansionCursor { get; private set; }
    public bool IsAudienceExpansionComplete { get; private set; }
    public Guid? AudienceLeaseId { get; private set; }
    public DateTimeOffset? AudienceLeaseExpiresAtUtc { get; private set; }
    public byte[] RowVersion { get; private set; } = [];

    public static NotificationCampaign Create(
        Guid platformClientId,
        string platformClientKeySnapshot,
        string platformClientDisplayNameSnapshot,
        NotificationAudienceKind audienceKind,
        DateTimeOffset audienceAsOfUtc,
        string titleAr,
        string titleEn,
        string bodyAr,
        string bodyEn,
        string type,
        NotificationPriority priority,
        string idempotencyKey,
        string requestFingerprint,
        Guid createdByUserId,
        string createdByDisplayNameSnapshot,
        DateTimeOffset createdAtUtc,
        DateTimeOffset expiresAtUtc,
        int audienceSubjectCount,
        int audienceDeviceCount,
        int pushCapableDeviceCount)
    {
        return new NotificationCampaign(
            Guid.NewGuid(),
            platformClientId,
            platformClientKeySnapshot,
            platformClientDisplayNameSnapshot,
            audienceKind,
            audienceAsOfUtc,
            titleAr,
            titleEn,
            bodyAr,
            bodyEn,
            type,
            priority,
            idempotencyKey,
            requestFingerprint,
            createdByUserId,
            createdByDisplayNameSnapshot,
            createdAtUtc,
            expiresAtUtc,
            audienceSubjectCount,
            audienceDeviceCount,
            pushCapableDeviceCount);
    }

    public void ClaimAudience(
        Guid leaseId,
        DateTimeOffset leaseExpiresAtUtc,
        DateTimeOffset now)
    {
        AudienceLeaseId = leaseId;
        AudienceLeaseExpiresAtUtc = leaseExpiresAtUtc;
        Status = NotificationCampaignStatus.PreparingAudience;
        ProcessingStartedAtUtc ??= now;
    }

    public void AdvanceAudience(
        Guid? cursor,
        int addedRecipientCount,
        bool isComplete)
    {
        AudienceExpansionCursor = cursor;
        PendingCount += addedRecipientCount;
        IsAudienceExpansionComplete = isComplete;
        AudienceLeaseId = null;
        AudienceLeaseExpiresAtUtc = null;

        if (isComplete)
        {
            Status = NotificationCampaignStatus.Dispatching;
        }
    }

    public void ReleaseAudienceLease()
    {
        AudienceLeaseId = null;
        AudienceLeaseExpiresAtUtc = null;
    }

    public void ExpireBeforeAudienceExpansion(DateTimeOffset now)
    {
        Status = NotificationCampaignStatus.Expired;
        IsAudienceExpansionComplete = true;
        PendingCount = 0;
        ExpiredCount = AudienceDeviceCount;
        CompletedAtUtc = now;
        AudienceLeaseId = null;
        AudienceLeaseExpiresAtUtc = null;
    }

    public bool Cancel(DateTimeOffset now)
    {
        if (Status is NotificationCampaignStatus.Cancelled
            or NotificationCampaignStatus.Expired)
        {
            return false;
        }

        Status = NotificationCampaignStatus.Cancelled;
        IsAudienceExpansionComplete = true;
        CompletedAtUtc ??= now;
        AudienceLeaseId = null;
        AudienceLeaseExpiresAtUtc = null;
        return true;
    }

    public void ApplySummary(
        int pending,
        int signalRDispatched,
        int fcmAccepted,
        int failed,
        int skipped,
        int expired,
        DateTimeOffset now)
    {
        PendingCount = pending;
        SignalRDispatchedCount = signalRDispatched;
        FcmAcceptedCount = fcmAccepted;
        FailedCount = failed;
        SkippedCount = skipped;
        ExpiredCount = expired;

        if (Status == NotificationCampaignStatus.Cancelled)
        {
            return;
        }

        if (!IsAudienceExpansionComplete)
        {
            return;
        }

        if (pending > 0)
        {
            Status = NotificationCampaignStatus.Dispatching;
            CompletedAtUtc = null;
            return;
        }

        Status = expired > 0
            ? NotificationCampaignStatus.Expired
            : failed > 0 || skipped > 0
                ? NotificationCampaignStatus.CompletedWithFailures
                : NotificationCampaignStatus.Completed;

        CompletedAtUtc ??= now;
    }
}
