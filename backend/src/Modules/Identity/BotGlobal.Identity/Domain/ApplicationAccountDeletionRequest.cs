using System.Text.Json;

namespace BotGlobal.Identity.Domain;

public sealed class ApplicationAccountDeletionRequest
{
    private ApplicationAccountDeletionRequest()
    {
    }

    public ApplicationAccountDeletionRequest(
        Guid id,
        Guid membershipId,
        Guid globalUserId,
        string applicationKey,
        string subjectId,
        IReadOnlyCollection<Guid> mobileDeviceIds,
        DateTimeOffset requestedAtUtc)
    {
        if (id == Guid.Empty || membershipId == Guid.Empty || globalUserId == Guid.Empty)
        {
            throw new ArgumentException("Deletion, membership, and user identifiers are required.");
        }

        Id = id;
        MembershipId = membershipId;
        GlobalUserId = globalUserId;
        ApplicationKey = Require(applicationKey, nameof(applicationKey), 80);
        SubjectId = Require(subjectId, nameof(subjectId), 160);
        MobileDeviceIdsJson = JsonSerializer.Serialize(
            mobileDeviceIds.Where(value => value != Guid.Empty).Distinct().Order().ToArray());
        RequestedAtUtc = requestedAtUtc;
        NextAttemptAtUtc = requestedAtUtc;
    }

    public Guid Id { get; private set; }
    public Guid MembershipId { get; private set; }
    public Guid GlobalUserId { get; private set; }
    public string ApplicationKey { get; private set; } = string.Empty;
    public string SubjectId { get; private set; } = string.Empty;
    public string MobileDeviceIdsJson { get; private set; } = "[]";
    public DateTimeOffset RequestedAtUtc { get; private set; }
    public DateTimeOffset? AccessRevokedAtUtc { get; private set; }
    public DateTimeOffset NextAttemptAtUtc { get; private set; }
    public DateTimeOffset? LastAttemptAtUtc { get; private set; }
    public int AttemptCount { get; private set; }
    public string? LastSafeErrorCode { get; private set; }
    public Guid? ProcessorLeaseId { get; private set; }
    public DateTimeOffset? ProcessorLeaseExpiresAtUtc { get; private set; }
    public byte[] RowVersion { get; private set; } = [];

    public IReadOnlyList<Guid> MobileDeviceIds() =>
        JsonSerializer.Deserialize<Guid[]>(MobileDeviceIdsJson) ?? [];

    public void IncludeMobileDeviceIds(IEnumerable<Guid> deviceIds)
    {
        MobileDeviceIdsJson = JsonSerializer.Serialize(
            MobileDeviceIds()
                .Concat(deviceIds)
                .Where(value => value != Guid.Empty)
                .Distinct()
                .Order()
                .ToArray());
    }

    public void MarkAccessRevoked(DateTimeOffset now)
    {
        AccessRevokedAtUtc ??= now;
        NextAttemptAtUtc = now;
    }

    public void MarkAttempt(DateTimeOffset now)
    {
        LastAttemptAtUtc = now;
        AttemptCount++;
        LastSafeErrorCode = null;
    }

    public void MarkRetry(DateTimeOffset nextAttemptAtUtc, string safeErrorCode)
    {
        NextAttemptAtUtc = nextAttemptAtUtc;
        LastSafeErrorCode = Require(safeErrorCode, nameof(safeErrorCode), 100);
        ProcessorLeaseId = null;
        ProcessorLeaseExpiresAtUtc = null;
    }

    public bool TryAcquireLease(Guid leaseId, DateTimeOffset now, TimeSpan duration)
    {
        if (leaseId == Guid.Empty || duration <= TimeSpan.Zero)
        {
            throw new ArgumentException("A valid processor lease is required.");
        }
        if (ProcessorLeaseId is not null && ProcessorLeaseExpiresAtUtc > now)
        {
            return false;
        }
        ProcessorLeaseId = leaseId;
        ProcessorLeaseExpiresAtUtc = now.Add(duration);
        return true;
    }

    public void RenewLease(Guid leaseId, DateTimeOffset now, TimeSpan duration)
    {
        if (ProcessorLeaseId != leaseId)
        {
            throw new InvalidOperationException("Account deletion processor lease was lost.");
        }
        ProcessorLeaseExpiresAtUtc = now.Add(duration);
    }

    private static string Require(string value, string name, int maxLength)
    {
        var normalized = value?.Trim();
        if (string.IsNullOrWhiteSpace(normalized) || normalized.Length > maxLength)
        {
            throw new ArgumentException($"{name} is invalid.", name);
        }

        return normalized;
    }
}
