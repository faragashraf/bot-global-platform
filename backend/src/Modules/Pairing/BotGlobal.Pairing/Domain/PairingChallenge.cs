namespace BotGlobal.Pairing.Domain;

public enum PairingChallengeStatus
{
    Pending = 1,
    Completed = 2
}

public sealed class PairingChallenge
{
    public const int TokenHashBytes = 32;
    public const int CorrelationReferenceMaxLength = 200;
    public const int ExternalSubjectIdMaxLength = 200;
    public const int MobilePlatformMaxLength = 20;
    public const int MobileInstallationIdMaxLength = 128;
    public const int MobileDeviceNameMaxLength = 120;
    public const int MobileAppVersionMaxLength = 50;

    private PairingChallenge() { }

    private PairingChallenge(
        Guid id,
        Guid platformClientId,
        byte[] tokenHash,
        string? correlationReference,
        string externalSubjectId,
        DateTimeOffset createdAtUtc,
        DateTimeOffset expiresAtUtc)
    {
        Id = id;
        PlatformClientId = platformClientId;
        TokenHash = tokenHash;
        CorrelationReference = correlationReference;
        ExternalSubjectId = externalSubjectId;
        CreatedAtUtc = createdAtUtc;
        ExpiresAtUtc = expiresAtUtc;
        Status = PairingChallengeStatus.Pending;
        ConcurrencyStamp = Guid.NewGuid();
    }

    public Guid Id { get; private set; }
    public Guid PlatformClientId { get; private set; }
    public byte[] TokenHash { get; private set; } = [];
    public string? CorrelationReference { get; private set; }
    public string? ExternalSubjectId { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset ExpiresAtUtc { get; private set; }
    public DateTimeOffset? CompletedAtUtc { get; private set; }
    public PairingChallengeStatus Status { get; private set; }
    public string? MobilePlatform { get; private set; }
    public string? MobileInstallationId { get; private set; }
    public string? MobileDeviceName { get; private set; }
    public string? MobileAppVersion { get; private set; }
    public Guid ConcurrencyStamp { get; private set; }

    public static PairingChallenge Create(
        Guid platformClientId,
        byte[] tokenHash,
        string? correlationReference,
        string externalSubjectId,
        DateTimeOffset createdAtUtc,
        TimeSpan lifetime)
    {
        if (platformClientId == Guid.Empty)
        {
            throw new ArgumentException(
                "Platform client id is required.",
                nameof(platformClientId));
        }

        if (tokenHash.Length != TokenHashBytes)
        {
            throw new ArgumentException(
                "Pairing token hash must be exactly 32 bytes.",
                nameof(tokenHash));
        }

        if (string.IsNullOrWhiteSpace(externalSubjectId))
        {
            throw new ArgumentException(
                "External subject id is required.",
                nameof(externalSubjectId));
        }

        var normalizedExternalSubjectId = externalSubjectId.Trim();

        if (normalizedExternalSubjectId.Length > ExternalSubjectIdMaxLength)
        {
            throw new ArgumentException(
                "External subject id is too long.",
                nameof(externalSubjectId));
        }

        if (lifetime <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(
                nameof(lifetime),
                "Pairing challenge lifetime must be positive.");
        }

        return new PairingChallenge(
            Guid.NewGuid(),
            platformClientId,
            tokenHash.ToArray(),
            NormalizeCorrelationReference(correlationReference),
            normalizedExternalSubjectId,
            createdAtUtc,
            createdAtUtc.Add(lifetime));
    }

    public bool IsExpired(DateTimeOffset utcNow)
        => Status == PairingChallengeStatus.Pending
           && utcNow >= ExpiresAtUtc;

    public void Complete(
        CompletedMobileDevice device,
        DateTimeOffset completedAtUtc)
    {
        ArgumentNullException.ThrowIfNull(device);

        if (Status != PairingChallengeStatus.Pending)
        {
            throw new InvalidOperationException(
                "Pairing challenge is not pending.");
        }

        if (completedAtUtc >= ExpiresAtUtc)
        {
            throw new InvalidOperationException(
                "Pairing challenge is expired.");
        }

        Status = PairingChallengeStatus.Completed;
        CompletedAtUtc = completedAtUtc;
        MobilePlatform = device.Platform;
        MobileInstallationId = device.InstallationId;
        MobileDeviceName = device.DeviceName;
        MobileAppVersion = device.AppVersion;
        ConcurrencyStamp = Guid.NewGuid();
    }

    private static string? NormalizeCorrelationReference(
        string? correlationReference)
    {
        if (string.IsNullOrWhiteSpace(correlationReference))
        {
            return null;
        }

        var normalized = correlationReference.Trim();

        if (normalized.Length > CorrelationReferenceMaxLength)
        {
            throw new ArgumentException(
                "Correlation reference is too long.",
                nameof(correlationReference));
        }

        if (!normalized.All(IsSafeCorrelationCharacter))
        {
            throw new ArgumentException(
                "Correlation reference contains unsupported characters.",
                nameof(correlationReference));
        }

        return normalized;
    }

    private static bool IsSafeCorrelationCharacter(char value)
        => char.IsAsciiLetterOrDigit(value)
           || value is '-' or '_' or '.' or ':' or '/';
}

public sealed record CompletedMobileDevice(
    string Platform,
    string InstallationId,
    string? DeviceName,
    string? AppVersion);
