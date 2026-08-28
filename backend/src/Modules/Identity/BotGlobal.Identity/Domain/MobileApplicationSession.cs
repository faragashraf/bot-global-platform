namespace BotGlobal.Identity.Domain;

public sealed class MobileApplicationSession
{
    private MobileApplicationSession()
    {
    }

    public MobileApplicationSession(
        Guid id,
        Guid membershipId,
        byte[] accessTokenHash,
        byte[] refreshTokenHash,
        DateTimeOffset accessExpiresAtUtc,
        DateTimeOffset refreshExpiresAtUtc,
        DateTimeOffset createdAtUtc)
    {
        Id = id;
        MembershipId = membershipId;
        AccessTokenHash = RequireHash(accessTokenHash, nameof(accessTokenHash));
        RefreshTokenHash = RequireHash(refreshTokenHash, nameof(refreshTokenHash));
        AccessExpiresAtUtc = accessExpiresAtUtc;
        RefreshExpiresAtUtc = refreshExpiresAtUtc;
        CreatedAtUtc = createdAtUtc;
    }

    public Guid Id { get; private set; }
    public Guid MembershipId { get; private set; }
    public ApplicationMembership Membership { get; private set; } = null!;
    public byte[] AccessTokenHash { get; private set; } = null!;
    public byte[] RefreshTokenHash { get; private set; } = null!;
    public DateTimeOffset AccessExpiresAtUtc { get; private set; }
    public DateTimeOffset RefreshExpiresAtUtc { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset? RevokedAtUtc { get; private set; }

    public bool IsAccessValid(DateTimeOffset now) =>
        RevokedAtUtc is null && AccessExpiresAtUtc > now;

    public bool IsRefreshValid(DateTimeOffset now) =>
        RevokedAtUtc is null && RefreshExpiresAtUtc > now;

    public void Rotate(
        byte[] accessTokenHash,
        byte[] refreshTokenHash,
        DateTimeOffset accessExpiresAtUtc,
        DateTimeOffset refreshExpiresAtUtc)
    {
        AccessTokenHash = RequireHash(accessTokenHash, nameof(accessTokenHash));
        RefreshTokenHash = RequireHash(refreshTokenHash, nameof(refreshTokenHash));
        AccessExpiresAtUtc = accessExpiresAtUtc;
        RefreshExpiresAtUtc = refreshExpiresAtUtc;
    }

    public void Revoke(DateTimeOffset revokedAtUtc) => RevokedAtUtc ??= revokedAtUtc;

    private static byte[] RequireHash(byte[] value, string name) =>
        value is { Length: > 0 }
            ? value
            : throw new ArgumentException("Token hash is required.", name);
}
