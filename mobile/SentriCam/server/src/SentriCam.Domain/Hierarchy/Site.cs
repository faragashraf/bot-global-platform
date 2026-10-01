using SentriCam.Domain.Common;

namespace SentriCam.Domain.Hierarchy;

public sealed class Site : AggregateRoot<SiteId>
{
    private Site()
    {
    }

    private Site(SiteId id, OrganizationId organizationId, string name, DateTimeOffset createdAtUtc)
    {
        Id = id;
        OrganizationId = organizationId;
        Name = name;
        CreatedAtUtc = createdAtUtc;
    }

    public OrganizationId OrganizationId { get; private set; }

    public string Name { get; private set; } = string.Empty;

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public static Site Create(OrganizationId organizationId, string name, DateTimeOffset now)
    {
        global::SentriCam.Domain.Hierarchy.OrganizationId.From(organizationId.Value);
        return new Site(
            SiteId.New(),
            organizationId,
            DomainGuard.Required(name, 200, nameof(Name)),
            now);
    }
}
