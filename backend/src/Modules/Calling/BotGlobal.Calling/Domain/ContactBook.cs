namespace BotGlobal.Calling.Domain;

public sealed class NqrbBlockedAccount
{
    private NqrbBlockedAccount() { }

    public NqrbBlockedAccount(string applicationKey, Guid ownerMembershipId, Guid blockedMembershipId, DateTimeOffset createdAtUtc)
    {
        if (string.IsNullOrWhiteSpace(applicationKey)) throw new ArgumentException("Application key is required.", nameof(applicationKey));
        if (ownerMembershipId == Guid.Empty) throw new ArgumentException("Owner membership id is required.", nameof(ownerMembershipId));
        if (blockedMembershipId == Guid.Empty || blockedMembershipId == ownerMembershipId)
            throw new ArgumentException("Blocked membership id must identify another account.", nameof(blockedMembershipId));
        ApplicationKey = applicationKey.Trim().ToLowerInvariant();
        OwnerMembershipId = ownerMembershipId;
        BlockedMembershipId = blockedMembershipId;
        CreatedAtUtc = createdAtUtc;
    }

    public string ApplicationKey { get; private set; } = string.Empty;
    public Guid OwnerMembershipId { get; private set; }
    public Guid BlockedMembershipId { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
}

public sealed class NqrbContactEdge
{
    private NqrbContactEdge() { }

    public NqrbContactEdge(
        string applicationKey,
        Guid ownerMembershipId,
        Guid contactMembershipId,
        DateTimeOffset createdAtUtc)
    {
        if (ownerMembershipId == Guid.Empty)
            throw new ArgumentException("Owner membership id is required.", nameof(ownerMembershipId));
        if (contactMembershipId == Guid.Empty)
            throw new ArgumentException("Contact membership id is required.", nameof(contactMembershipId));
        if (ownerMembershipId == contactMembershipId)
            throw new ArgumentException("A member cannot save themselves as a contact.", nameof(contactMembershipId));

        ApplicationKey = applicationKey.Trim().ToLowerInvariant();
        OwnerMembershipId = ownerMembershipId;
        ContactMembershipId = contactMembershipId;
        CreatedAtUtc = createdAtUtc;
    }

    public string ApplicationKey { get; private set; } = string.Empty;
    public Guid OwnerMembershipId { get; private set; }
    public Guid ContactMembershipId { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public string? Nickname { get; private set; }

    public bool SetNickname(string? nickname)
    {
        var normalized = string.IsNullOrWhiteSpace(nickname) ? null : nickname.Trim();
        if (normalized is { Length: > 80 } || normalized?.Any(char.IsControl) == true)
            return false;

        Nickname = normalized;
        return true;
    }
}

public sealed class NqrbContactInvite
{
    private NqrbContactInvite() { }

    public NqrbContactInvite(
        string applicationKey,
        string codeHash,
        Guid issuerMembershipId,
        DateTimeOffset createdAtUtc,
        DateTimeOffset expiresAtUtc)
    {
        if (string.IsNullOrWhiteSpace(codeHash))
            throw new ArgumentException("Invite code hash is required.", nameof(codeHash));
        if (issuerMembershipId == Guid.Empty)
            throw new ArgumentException("Issuer membership id is required.", nameof(issuerMembershipId));
        if (expiresAtUtc <= createdAtUtc)
            throw new ArgumentException("Invite expiry must be after creation.", nameof(expiresAtUtc));

        ApplicationKey = applicationKey.Trim().ToLowerInvariant();
        CodeHash = codeHash;
        IssuerMembershipId = issuerMembershipId;
        CreatedAtUtc = createdAtUtc;
        ExpiresAtUtc = expiresAtUtc;
    }

    public string ApplicationKey { get; private set; } = string.Empty;
    public string CodeHash { get; private set; } = string.Empty;
    public Guid IssuerMembershipId { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset ExpiresAtUtc { get; private set; }
    public DateTimeOffset? ClaimedAtUtc { get; private set; }
    public Guid? ClaimedByMembershipId { get; private set; }
}
