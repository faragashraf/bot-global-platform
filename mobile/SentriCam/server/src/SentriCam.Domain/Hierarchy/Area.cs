using SentriCam.Domain.Common;

namespace SentriCam.Domain.Hierarchy;

public sealed class Area : AggregateRoot<AreaId>
{
    private Area()
    {
    }

    private Area(AreaId id, SiteId siteId, string name, DateTimeOffset createdAtUtc)
    {
        Id = id;
        SiteId = siteId;
        Name = name;
        CreatedAtUtc = createdAtUtc;
    }

    public SiteId SiteId { get; private set; }

    public string Name { get; private set; } = string.Empty;

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public static Area Create(SiteId siteId, string name, DateTimeOffset now)
    {
        global::SentriCam.Domain.Hierarchy.SiteId.From(siteId.Value);
        return new Area(
            AreaId.New(),
            siteId,
            DomainGuard.Required(name, 200, nameof(Name)),
            now);
    }
}
