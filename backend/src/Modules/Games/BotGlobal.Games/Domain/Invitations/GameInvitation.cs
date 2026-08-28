namespace BotGlobal.Games.Domain.Invitations;

public sealed class GameInvitation
{
    private GameInvitation()
    {
    }

    public GameInvitation(
        Guid id,
        Guid sessionId,
        string applicationKey,
        string tokenHash,
        Guid createdByMembershipId,
        DateTimeOffset createdAtUtc,
        DateTimeOffset expiresAtUtc)
    {
        if (expiresAtUtc <= createdAtUtc)
        {
            throw new ArgumentOutOfRangeException(nameof(expiresAtUtc));
        }

        Id = id;
        SessionId = sessionId;
        ApplicationKey = Require(applicationKey, nameof(applicationKey), 80);
        TokenHash = Require(tokenHash, nameof(tokenHash), 64);
        CreatedByMembershipId = createdByMembershipId;
        CreatedAtUtc = createdAtUtc;
        ExpiresAtUtc = expiresAtUtc;
    }

    public Guid Id { get; private set; }
    public Guid SessionId { get; private set; }
    public string ApplicationKey { get; private set; } = null!;
    public string TokenHash { get; private set; } = null!;
    public Guid CreatedByMembershipId { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset ExpiresAtUtc { get; private set; }
    public DateTimeOffset? RevokedAtUtc { get; private set; }
    public DateTimeOffset? ConsumedAtUtc { get; private set; }

    public bool IsActive(DateTimeOffset now) =>
        RevokedAtUtc is null && ConsumedAtUtc is null && ExpiresAtUtc > now;

    public void Revoke(DateTimeOffset now)
    {
        RevokedAtUtc ??= now;
    }

    public void Consume(DateTimeOffset now)
    {
        if (!IsActive(now))
        {
            throw new InvalidOperationException("The invitation is no longer active.");
        }

        ConsumedAtUtc = now;
    }

    private static string Require(string value, string name, int maxLength)
    {
        var normalized = value?.Trim();
        if (string.IsNullOrWhiteSpace(normalized) || normalized.Length > maxLength)
        {
            throw new ArgumentException($"A valid {name} is required.", name);
        }

        return normalized;
    }
}
