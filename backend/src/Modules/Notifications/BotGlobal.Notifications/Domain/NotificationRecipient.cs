namespace BotGlobal.Notifications.Domain;

public sealed class NotificationRecipient
{
    private NotificationRecipient()
    {
    }

    private NotificationRecipient(
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
    public byte[] RowVersion { get; private set; } = [];
    public NotificationCampaign Campaign { get; private set; } = null!;

    public static NotificationRecipient Create(
        Guid campaignId,
        Guid mobileDeviceId,
        string installationIdSnapshot,
        string platformSnapshot,
        string? deviceNameSnapshot,
        DateTimeOffset nextAttemptAtUtc,
        DateTimeOffset expiresAtUtc)
    {
        return new NotificationRecipient(
            campaignId,
            mobileDeviceId,
            installationIdSnapshot,
            platformSnapshot,
            deviceNameSnapshot,
            nextAttemptAtUtc,
            expiresAtUtc);
    }

    public void Claim(Guid leaseId, DateTimeOffset leaseExpiresAtUtc)
    {
        LeaseId = leaseId;
        LeaseExpiresAtUtc = leaseExpiresAtUtc;
    }

    public void CompleteAttempt(
        NotificationRecipientStatus status,
        DateTimeOffset now,
        string? lastTransport,
        string? safeErrorCode,
        DateTimeOffset? nextAttemptAtUtc)
    {
        AttemptCount++;
        LastAttemptAtUtc = now;
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
}
