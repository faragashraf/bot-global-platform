namespace BotGlobal.Pairing.Domain;

public sealed class MobileProfileSnapshot
{
    public const int DisplayNameMaxLength = 160;
    public const int JobTitleMaxLength = 160;
    public const int OrganizationUnitMaxLength = 200;

    private MobileProfileSnapshot()
    {
    }

    public MobileProfileSnapshot(
        Guid id,
        Guid platformClientId,
        string externalSubjectId,
        string displayName,
        string? jobTitle,
        string? organizationUnit,
        long version,
        DateTimeOffset publishedAtUtc,
        DateTimeOffset receivedAtUtc)
    {
        Id = id;
        PlatformClientId = platformClientId;
        ExternalSubjectId = externalSubjectId;
        Apply(
            displayName,
            jobTitle,
            organizationUnit,
            version,
            publishedAtUtc,
            receivedAtUtc);
    }

    public Guid Id { get; private set; }

    public Guid PlatformClientId { get; private set; }

    public string ExternalSubjectId { get; private set; } = string.Empty;

    public string DisplayName { get; private set; } = string.Empty;

    public string? JobTitle { get; private set; }

    public string? OrganizationUnit { get; private set; }

    public long Version { get; private set; }

    public DateTimeOffset PublishedAtUtc { get; private set; }

    public DateTimeOffset ReceivedAtUtc { get; private set; }

    public byte[] RowVersion { get; private set; } = [];

    public void Apply(
        string displayName,
        string? jobTitle,
        string? organizationUnit,
        long version,
        DateTimeOffset publishedAtUtc,
        DateTimeOffset receivedAtUtc)
    {
        DisplayName = displayName;
        JobTitle = jobTitle;
        OrganizationUnit = organizationUnit;
        Version = version;
        PublishedAtUtc = publishedAtUtc;
        ReceivedAtUtc = receivedAtUtc;
    }

    public bool HasSameContent(
        string displayName,
        string? jobTitle,
        string? organizationUnit,
        DateTimeOffset publishedAtUtc)
        => DisplayName == displayName
           && JobTitle == jobTitle
           && OrganizationUnit == organizationUnit
           && PublishedAtUtc == publishedAtUtc;
}
