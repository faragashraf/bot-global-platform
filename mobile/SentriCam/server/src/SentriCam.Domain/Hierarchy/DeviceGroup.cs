using SentriCam.Domain.Common;

namespace SentriCam.Domain.Hierarchy;

public sealed class DeviceGroup : AggregateRoot<DeviceGroupId>
{
    private DeviceGroup()
    {
    }

    private DeviceGroup(DeviceGroupId id, AreaId areaId, string name, DateTimeOffset createdAtUtc)
    {
        Id = id;
        AreaId = areaId;
        Name = name;
        CreatedAtUtc = createdAtUtc;
    }

    public AreaId AreaId { get; private set; }

    public string Name { get; private set; } = string.Empty;

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public static DeviceGroup Create(AreaId areaId, string name, DateTimeOffset now)
    {
        global::SentriCam.Domain.Hierarchy.AreaId.From(areaId.Value);
        return new DeviceGroup(
            DeviceGroupId.New(),
            areaId,
            DomainGuard.Required(name, 200, nameof(Name)),
            now);
    }
}
