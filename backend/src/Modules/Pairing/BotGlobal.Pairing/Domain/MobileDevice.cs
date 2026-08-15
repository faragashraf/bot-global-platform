namespace BotGlobal.Pairing.Domain;

public sealed class MobileDevice
{
    private MobileDevice()
    {
    }

    public MobileDevice(
        Guid id,
        Guid platformClientId,
        string externalSubjectId,
        string installationId,
        string platform,
        string? deviceName,
        string? appVersion,
        byte[] credentialHash,
        DateTimeOffset createdAtUtc)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(externalSubjectId);
        ArgumentException.ThrowIfNullOrWhiteSpace(installationId);
        ArgumentException.ThrowIfNullOrWhiteSpace(platform);

        if (credentialHash is null || credentialHash.Length == 0)
        {
            throw new ArgumentException(
                "Credential hash is required.",
                nameof(credentialHash));
        }

        Id = id;
        PlatformClientId = platformClientId;
        ExternalSubjectId = externalSubjectId.Trim();
        InstallationId = installationId;
        Platform = platform;
        DeviceName = deviceName;
        AppVersion = appVersion;
        ExternalSubjectId = externalSubjectId.Trim();
        CredentialHash = credentialHash;
        CreatedAtUtc = createdAtUtc;
        LastPairedAtUtc = createdAtUtc;
    }

    public Guid Id { get; private set; }

    public Guid PlatformClientId { get; private set; }

    // Nullable only for devices paired before subject identity was introduced.
    public string? ExternalSubjectId { get; private set; }

    public string InstallationId { get; private set; } = null!;

    public string Platform { get; private set; } = null!;

    public string? DeviceName { get; private set; }

    public string? AppVersion { get; private set; }

    public byte[] CredentialHash { get; private set; } = null!;

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public DateTimeOffset LastPairedAtUtc { get; private set; }

    public DateTimeOffset? RevokedAtUtc { get; private set; }

    public bool IsActive => RevokedAtUtc is null;

    public void RotateCredential(
        byte[] credentialHash,
        string externalSubjectId,
        string platform,
        string? deviceName,
        string? appVersion,
        DateTimeOffset pairedAtUtc)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(externalSubjectId);
        ArgumentException.ThrowIfNullOrWhiteSpace(platform);

        if (credentialHash is null || credentialHash.Length == 0)
        {
            throw new ArgumentException(
                "Credential hash is required.",
                nameof(credentialHash));
        }

        ExternalSubjectId = externalSubjectId.Trim();
        CredentialHash = credentialHash;
        Platform = platform;
        DeviceName = deviceName;
        AppVersion = appVersion;
        LastPairedAtUtc = pairedAtUtc;
        RevokedAtUtc = null;
    }

    public void Revoke(DateTimeOffset revokedAtUtc)
    {
        RevokedAtUtc ??= revokedAtUtc;
    }
}
