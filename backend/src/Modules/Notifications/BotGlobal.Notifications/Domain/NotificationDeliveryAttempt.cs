namespace BotGlobal.Notifications.Domain;

public sealed class NotificationDeliveryAttempt
{
    private NotificationDeliveryAttempt()
    {
    }

    private NotificationDeliveryAttempt(
        Guid id,
        Guid notificationRecipientId,
        Guid applicationId,
        Guid campaignId,
        Guid mobileDeviceId,
        string deliveryKey,
        int attemptNumber,
        Guid leaseId,
        DateTimeOffset createdAtUtc)
    {
        Id = id;
        NotificationRecipientId = notificationRecipientId;
        ApplicationId = applicationId;
        CampaignId = campaignId;
        MobileDeviceId = mobileDeviceId;
        DeliveryKey = deliveryKey;
        AttemptNumber = attemptNumber;
        LeaseId = leaseId;
        Status = NotificationDeliveryAttemptStatus.Prepared;
        CreatedAtUtc = createdAtUtc;
    }

    public Guid Id { get; private set; }
    public Guid NotificationRecipientId { get; private set; }
    public Guid ApplicationId { get; private set; }
    public Guid CampaignId { get; private set; }
    public Guid MobileDeviceId { get; private set; }
    public string DeliveryKey { get; private set; } = string.Empty;
    public int AttemptNumber { get; private set; }
    public Guid LeaseId { get; private set; }
    public NotificationDeliveryAttemptStatus Status { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset? ProviderInvocationStartedAtUtc { get; private set; }
    public DateTimeOffset? CompletedAtUtc { get; private set; }
    public string? Transport { get; private set; }
    public string? ProviderMessageId { get; private set; }
    public string? SafeErrorCode { get; private set; }
    public byte[] RowVersion { get; private set; } = [];
    public NotificationRecipient Recipient { get; private set; } = null!;

    public static NotificationDeliveryAttempt Create(
        Guid id,
        Guid notificationRecipientId,
        Guid applicationId,
        Guid campaignId,
        Guid mobileDeviceId,
        string deliveryKey,
        int attemptNumber,
        Guid leaseId,
        DateTimeOffset createdAtUtc)
    {
        if (id == Guid.Empty
            || notificationRecipientId == Guid.Empty
            || applicationId == Guid.Empty
            || campaignId == Guid.Empty
            || mobileDeviceId == Guid.Empty
            || leaseId == Guid.Empty)
        {
            throw new ArgumentException(
                "Delivery attempt identity and ownership values are required.");
        }

        ArgumentException.ThrowIfNullOrWhiteSpace(deliveryKey);
        ArgumentOutOfRangeException.ThrowIfLessThan(attemptNumber, 1);

        return new NotificationDeliveryAttempt(
            id,
            notificationRecipientId,
            applicationId,
            campaignId,
            mobileDeviceId,
            deliveryKey,
            attemptNumber,
            leaseId,
            createdAtUtc);
    }

    public void ReassignPreparedLease(Guid leaseId)
    {
        if (Status != NotificationDeliveryAttemptStatus.Prepared)
        {
            throw new InvalidOperationException(
                "Only a prepared delivery attempt can receive a new lease.");
        }

        LeaseId = leaseId;
    }

    public void BeginProviderInvocation(
        Guid leaseId,
        DateTimeOffset now)
    {
        EnsureOwnedPreparedAttempt(leaseId);
        Status = NotificationDeliveryAttemptStatus.ProviderInvocationStarted;
        ProviderInvocationStartedAtUtc = now;
    }

    public void Complete(
        Guid leaseId,
        NotificationDeliveryAttemptStatus status,
        DateTimeOffset now,
        string? transport,
        string? providerMessageId,
        string? safeErrorCode)
    {
        if (Status != NotificationDeliveryAttemptStatus.ProviderInvocationStarted
            || LeaseId != leaseId)
        {
            throw new InvalidOperationException(
                "Only the worker that started the provider invocation can complete it.");
        }

        if (status is NotificationDeliveryAttemptStatus.Prepared
            or NotificationDeliveryAttemptStatus.ProviderInvocationStarted)
        {
            throw new ArgumentOutOfRangeException(
                nameof(status),
                "A completed attempt requires a terminal provider outcome.");
        }

        Status = status;
        CompletedAtUtc = now;
        Transport = transport;
        ProviderMessageId = providerMessageId;
        SafeErrorCode = safeErrorCode;
    }

    public void MarkAmbiguous(DateTimeOffset now, string safeErrorCode)
    {
        if (Status != NotificationDeliveryAttemptStatus.ProviderInvocationStarted)
        {
            throw new InvalidOperationException(
                "Only an unresolved provider invocation can become ambiguous.");
        }

        Status = NotificationDeliveryAttemptStatus.Ambiguous;
        CompletedAtUtc = now;
        SafeErrorCode = safeErrorCode;
    }

    public void ExpirePrepared(DateTimeOffset now)
    {
        if (Status != NotificationDeliveryAttemptStatus.Prepared)
        {
            throw new InvalidOperationException(
                "Only a prepared attempt can expire before provider invocation.");
        }

        Status = NotificationDeliveryAttemptStatus.Expired;
        CompletedAtUtc = now;
        SafeErrorCode = "delivery-expired-before-send";
    }

    public void CancelPrepared(DateTimeOffset now)
    {
        if (Status != NotificationDeliveryAttemptStatus.Prepared)
        {
            throw new InvalidOperationException(
                "Only a prepared attempt can be cancelled before provider invocation.");
        }

        Status = NotificationDeliveryAttemptStatus.Cancelled;
        CompletedAtUtc = now;
        SafeErrorCode = "campaign-cancelled-before-send";
    }

    private void EnsureOwnedPreparedAttempt(Guid leaseId)
    {
        if (Status != NotificationDeliveryAttemptStatus.Prepared
            || LeaseId != leaseId)
        {
            throw new InvalidOperationException(
                "The delivery attempt is not prepared for the current lease.");
        }
    }
}
