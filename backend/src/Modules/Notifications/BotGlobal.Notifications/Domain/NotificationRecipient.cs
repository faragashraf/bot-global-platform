namespace BotGlobal.Notifications.Domain;

public sealed class NotificationRecipient
{
    private NotificationRecipient()
    {
    }

    private NotificationRecipient(
        Guid applicationId,
        Guid campaignId,
        Guid mobileDeviceId,
        string installationIdSnapshot,
        string platformSnapshot,
        string? deviceNameSnapshot,
        DateTimeOffset nextAttemptAtUtc,
        DateTimeOffset expiresAtUtc)
    {
        Id = Guid.NewGuid();
        CampaignId = campaignId;
        MobileDeviceId = mobileDeviceId;
        DeliveryKey = CreateDeliveryKey(
            applicationId,
            campaignId,
            mobileDeviceId);
        InstallationIdSnapshot = installationIdSnapshot;
        PlatformSnapshot = platformSnapshot;
        DeviceNameSnapshot = deviceNameSnapshot;
        Status = NotificationRecipientStatus.Pending;
        NextAttemptAtUtc = nextAttemptAtUtc;
        ExpiresAtUtc = expiresAtUtc;
    }

    public Guid Id { get; private set; }
    public Guid CampaignId { get; private set; }
    public Guid MobileDeviceId { get; private set; }
    public string DeliveryKey { get; private set; } = string.Empty;
    public string InstallationIdSnapshot { get; private set; } = string.Empty;
    public string PlatformSnapshot { get; private set; } = string.Empty;
    public string? DeviceNameSnapshot { get; private set; }
    public NotificationRecipientStatus Status { get; private set; }
    public int AttemptCount { get; private set; }
    public DateTimeOffset? NextAttemptAtUtc { get; private set; }
    public DateTimeOffset? LastAttemptAtUtc { get; private set; }
    public string? LastTransport { get; private set; }
    public string? LastSafeErrorCode { get; private set; }
    public DateTimeOffset? DispatchedAtUtc { get; private set; }
    public DateTimeOffset ExpiresAtUtc { get; private set; }
    public Guid? LeaseId { get; private set; }
    public DateTimeOffset? LeaseExpiresAtUtc { get; private set; }
    public Guid? CurrentAttemptId { get; private set; }
    public byte[] RowVersion { get; private set; } = [];
    public NotificationCampaign Campaign { get; private set; } = null!;
    public ICollection<NotificationDeliveryAttempt> DeliveryAttempts { get; private set; }
        = [];

    public static NotificationRecipient Create(
        Guid applicationId,
        Guid campaignId,
        Guid mobileDeviceId,
        string installationIdSnapshot,
        string platformSnapshot,
        string? deviceNameSnapshot,
        DateTimeOffset nextAttemptAtUtc,
        DateTimeOffset expiresAtUtc)
    {
        return new NotificationRecipient(
            applicationId,
            campaignId,
            mobileDeviceId,
            installationIdSnapshot,
            platformSnapshot,
            deviceNameSnapshot,
            nextAttemptAtUtc,
            expiresAtUtc);
    }

    public void Claim(
        Guid leaseId,
        DateTimeOffset leaseExpiresAtUtc,
        Guid attemptId)
    {
        if (Status is not NotificationRecipientStatus.Pending
            and not NotificationRecipientStatus.RetryScheduled)
        {
            throw new InvalidOperationException(
                "Only a pending or retry-scheduled delivery can be claimed.");
        }

        LeaseId = leaseId;
        LeaseExpiresAtUtc = leaseExpiresAtUtc;
        CurrentAttemptId = attemptId;
    }

    public void BeginAttempt(
        Guid leaseId,
        Guid attemptId,
        DateTimeOffset now)
    {
        if (LeaseId != leaseId
            || CurrentAttemptId != attemptId
            || Status is not NotificationRecipientStatus.Pending
                and not NotificationRecipientStatus.RetryScheduled)
        {
            throw new InvalidOperationException(
                "The recipient is not owned by the current delivery attempt.");
        }

        AttemptCount++;
        LastAttemptAtUtc = now;
        Status = NotificationRecipientStatus.Sending;
        NextAttemptAtUtc = null;
    }

    public void ProjectAttempt(
        Guid attemptId,
        NotificationRecipientStatus status,
        DateTimeOffset now,
        string? lastTransport,
        string? safeErrorCode,
        DateTimeOffset? nextAttemptAtUtc)
    {
        if (Status != NotificationRecipientStatus.Sending
            || CurrentAttemptId != attemptId)
        {
            throw new InvalidOperationException(
                "Only the current sending attempt can update recipient delivery state.");
        }

        if (status is NotificationRecipientStatus.Pending
            or NotificationRecipientStatus.Sending)
        {
            throw new ArgumentOutOfRangeException(
                nameof(status),
                "A projected attempt must leave the sending state.");
        }

        LastTransport = lastTransport;
        LastSafeErrorCode = safeErrorCode;
        Status = status;
        NextAttemptAtUtc = nextAttemptAtUtc;
        LeaseId = null;
        LeaseExpiresAtUtc = null;

        if (status is NotificationRecipientStatus.SignalRDispatched
            or NotificationRecipientStatus.FcmAccepted)
        {
            DispatchedAtUtc = now;
        }
    }

    public void Expire()
    {
        Status = NotificationRecipientStatus.Expired;
        NextAttemptAtUtc = null;
        LeaseId = null;
        LeaseExpiresAtUtc = null;
    }

    public static string CreateDeliveryKey(
        Guid applicationId,
        Guid campaignId,
        Guid mobileDeviceId)
    {
        if (applicationId == Guid.Empty
            || campaignId == Guid.Empty
            || mobileDeviceId == Guid.Empty)
        {
            throw new ArgumentException(
                "Application, campaign, and device identity are required.");
        }

        return $"{applicationId:N}:{campaignId:N}:{mobileDeviceId:N}";
    }
}
