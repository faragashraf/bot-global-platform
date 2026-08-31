using SentriCam.Domain.Common;

namespace SentriCam.Domain.Hierarchy;

public readonly record struct OrganizationId(Guid Value)
{
    public static OrganizationId New() => new(Guid.NewGuid());

    public static OrganizationId From(Guid value) => value == Guid.Empty
        ? throw new DomainValidationException("Organization id cannot be empty.")
        : new OrganizationId(value);
}

public readonly record struct SiteId(Guid Value)
{
    public static SiteId New() => new(Guid.NewGuid());

    public static SiteId From(Guid value) => value == Guid.Empty
        ? throw new DomainValidationException("Site id cannot be empty.")
        : new SiteId(value);
}

public readonly record struct AreaId(Guid Value)
{
    public static AreaId New() => new(Guid.NewGuid());

    public static AreaId From(Guid value) => value == Guid.Empty
        ? throw new DomainValidationException("Area id cannot be empty.")
        : new AreaId(value);
}

public readonly record struct DeviceGroupId(Guid Value)
{
    public static DeviceGroupId New() => new(Guid.NewGuid());

    public static DeviceGroupId From(Guid value) => value == Guid.Empty
        ? throw new DomainValidationException("Device group id cannot be empty.")
        : new DeviceGroupId(value);
}
