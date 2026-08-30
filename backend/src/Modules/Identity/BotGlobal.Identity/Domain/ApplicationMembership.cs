namespace BotGlobal.Identity.Domain;

public sealed class ApplicationMembership
{
    private ApplicationMembership()
    {
    }

    public ApplicationMembership(
        Guid id,
        string applicationKey,
        string subjectId,
        string displayName,
        Guid? globalUserId,
        bool isGuest,
        DateTimeOffset createdAtUtc)
    {
        if (id == Guid.Empty)
        {
            throw new ArgumentException("Membership id is required.", nameof(id));
        }

        Id = id;
        ApplicationKey = Require(applicationKey, nameof(applicationKey), 80);
        SubjectId = Require(subjectId, nameof(subjectId), 160);
        DisplayName = Require(displayName, nameof(displayName), 120);
        GlobalUserId = globalUserId;
        IsGuest = isGuest;
        IsActive = true;
        CreatedAtUtc = createdAtUtc;
    }

    public Guid Id { get; private set; }
    public string ApplicationKey { get; private set; } = null!;
    public string SubjectId { get; private set; } = null!;
    public string DisplayName { get; private set; } = null!;
    public Guid? GlobalUserId { get; private set; }
    public bool IsGuest { get; private set; }
    public bool IsActive { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset? UpgradedAtUtc { get; private set; }

    public void Upgrade(Guid globalUserId, string subjectId, string displayName, DateTimeOffset upgradedAtUtc)
    {
        if (!IsGuest)
        {
            throw new InvalidOperationException("Only a guest membership can be upgraded.");
        }

        GlobalUserId = globalUserId;
        SubjectId = Require(subjectId, nameof(subjectId), 160);
        DisplayName = Require(displayName, nameof(displayName), 120);
        IsGuest = false;
        UpgradedAtUtc = upgradedAtUtc;
    }

    public void Deactivate() => IsActive = false;

    private static string Require(string value, string name, int maxLength)
    {
        var normalized = value?.Trim();
        if (string.IsNullOrWhiteSpace(normalized))
        {
            throw new ArgumentException($"{name} is required.", name);
        }

        if (normalized.Length > maxLength)
        {
            throw new ArgumentException($"{name} exceeds {maxLength} characters.", name);
        }

        return normalized;
    }
}
