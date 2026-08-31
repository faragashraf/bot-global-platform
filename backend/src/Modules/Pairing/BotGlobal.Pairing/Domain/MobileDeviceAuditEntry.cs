namespace BotGlobal.Pairing.Domain;

public static class MobileDeviceAuditKinds
{
    public const string Paired = "paired";
    public const string RePaired = "re-paired";
    public const string EnrolledByApplicationIdentity = "application-identity-enrolled";
    public const string ReEnrolledByApplicationIdentity = "application-identity-re-enrolled";
    public const string PushRegistered = "push-registered";
    public const string PushRefreshed = "push-refreshed";
    public const string PushInvalidated = "push-invalidated";
    public const string UnpairedByDevice = "unpaired-by-device";
    public const string RevokedByAdministrator = "revoked-by-administrator";

    public const string HistoryPurged = "history-purged-by-administrator";
}

public static class MobileDeviceAuditActorTypes
{
    public const string Device = "device";
    public const string Administrator = "administrator";
    public const string System = "system";

    public const int MaxLength = 20;
}

public sealed class MobileDeviceAuditEntry
{
    public const int KindMaxLength = 60;
    public const int DetailMaxLength = 300;
    public const int ActorDisplayNameMaxLength = 200;

    private MobileDeviceAuditEntry()
    {
    }

    public MobileDeviceAuditEntry(
        Guid id,
        Guid mobileDeviceId,
        Guid platformClientId,
        string kind,
        string actorType,
        string? actorDisplayName,
        string? detail,
        DateTimeOffset occurredAtUtc)
    {
        if (id == Guid.Empty)
        {
            throw new ArgumentException(
                "Audit entry id is required.",
                nameof(id));
        }

        if (mobileDeviceId == Guid.Empty)
        {
            throw new ArgumentException(
                "Mobile device id is required.",
                nameof(mobileDeviceId));
        }

        if (platformClientId == Guid.Empty)
        {
            throw new ArgumentException(
                "Platform client id is required.",
                nameof(platformClientId));
        }

        if (string.IsNullOrWhiteSpace(kind))
        {
            throw new ArgumentException(
                "Audit kind is required.",
                nameof(kind));
        }

        if (kind.Trim().Length > KindMaxLength)
        {
            throw new ArgumentException(
                "Audit kind is too long.",
                nameof(kind));
        }

        if (string.IsNullOrWhiteSpace(actorType))
        {
            throw new ArgumentException(
                "Actor type is required.",
                nameof(actorType));
        }

        if (actorType.Trim().Length > MobileDeviceAuditActorTypes.MaxLength)
        {
            throw new ArgumentException(
                "Actor type is too long.",
                nameof(actorType));
        }

        Id = id;
        MobileDeviceId = mobileDeviceId;
        PlatformClientId = platformClientId;
        Kind = kind.Trim();
        ActorType = actorType.Trim();
        ActorDisplayName = NormalizeOptional(
            actorDisplayName,
            ActorDisplayNameMaxLength);
        Detail = NormalizeOptional(
            detail,
            DetailMaxLength);
        OccurredAtUtc = occurredAtUtc;
    }

    public Guid Id { get; private set; }

    public Guid MobileDeviceId { get; private set; }

    public Guid PlatformClientId { get; private set; }

    public string Kind { get; private set; } = null!;

    public string ActorType { get; private set; } = null!;

    public string? ActorDisplayName { get; private set; }

    public string? Detail { get; private set; }

    public DateTimeOffset OccurredAtUtc { get; private set; }

    private static string? NormalizeOptional(
        string? value,
        int maximumLength)
    {
        var normalized = value?.Trim();

        if (string.IsNullOrEmpty(normalized))
        {
            return null;
        }

        return normalized.Length <= maximumLength
            ? normalized
            : normalized[..maximumLength];
    }
}
