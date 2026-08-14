namespace BotGlobal.PlatformClients.Domain;

public sealed class PlatformClientCredential
{
    public const int SecretHashLength = 32;

    private PlatformClientCredential() { }

    private PlatformClientCredential(
        Guid id,
        Guid clientId,
        byte[] secretHash,
        DateTimeOffset createdAtUtc,
        DateTimeOffset? expiresAtUtc)
    {
        if (id == Guid.Empty) throw new ArgumentException("Credential id is required.", nameof(id));
        if (clientId == Guid.Empty) throw new ArgumentException("Client id is required.", nameof(clientId));

        if (secretHash is null || secretHash.Length != SecretHashLength)
        {
            throw new ArgumentException(
                $"Secret hash must contain exactly {SecretHashLength} bytes.",
                nameof(secretHash));
        }

        if (expiresAtUtc is not null && expiresAtUtc <= createdAtUtc)
        {
            throw new ArgumentOutOfRangeException(nameof(expiresAtUtc));
        }

        Id = id;
        ClientId = clientId;
        SecretHash = secretHash.ToArray();
        CreatedAtUtc = createdAtUtc;
        ExpiresAtUtc = expiresAtUtc;
    }

    public Guid Id { get; private set; }
    public Guid ClientId { get; private set; }
    public byte[] SecretHash { get; private set; } = [];
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset? ExpiresAtUtc { get; private set; }
    public DateTimeOffset? RevokedAtUtc { get; private set; }

    public bool IsRevoked => RevokedAtUtc is not null;

    internal static PlatformClientCredential Create(
        Guid clientId,
        byte[] secretHash,
        DateTimeOffset createdAtUtc,
        DateTimeOffset? expiresAtUtc)
        => new(
            Guid.NewGuid(),
            clientId,
            secretHash,
            createdAtUtc,
            expiresAtUtc);

    public bool IsUsableAt(DateTimeOffset utcNow)
        => !IsRevoked
           && (ExpiresAtUtc is null || utcNow < ExpiresAtUtc.Value);

    public void Revoke(DateTimeOffset revokedAtUtc)
    {
        if (revokedAtUtc < CreatedAtUtc)
        {
            throw new ArgumentOutOfRangeException(nameof(revokedAtUtc));
        }

        RevokedAtUtc ??= revokedAtUtc;
    }
}
