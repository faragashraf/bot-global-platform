using SentriCam.Domain.Common;

namespace SentriCam.Domain.Hierarchy;

public sealed class Organization : AggregateRoot<OrganizationId>
{
    private Organization()
    {
    }

    private Organization(OrganizationId id, string name, DateTimeOffset createdAtUtc)
    {
        Id = id;
        Name = name;
        CreatedAtUtc = createdAtUtc;
    }

    public string Name { get; private set; } = string.Empty;

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public static Organization Create(string name, DateTimeOffset now) =>
        new(
            OrganizationId.New(),
            DomainGuard.Required(name, 200, nameof(Name)),
            now);
}
