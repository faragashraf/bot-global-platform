using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Hierarchy;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal static class ConfigurationExtensions
{
    public static PropertyBuilder<DeviceId> HasDeviceIdConversion(this PropertyBuilder<DeviceId> property) =>
        property.HasConversion(id => id.Value, value => new DeviceId(value));

    public static PropertyBuilder<OrganizationId> HasOrganizationIdConversion(
        this PropertyBuilder<OrganizationId> property) =>
        property.HasConversion(id => id.Value, value => new OrganizationId(value));

    public static PropertyBuilder<SiteId> HasSiteIdConversion(this PropertyBuilder<SiteId> property) =>
        property.HasConversion(id => id.Value, value => new SiteId(value));

    public static PropertyBuilder<AreaId> HasAreaIdConversion(this PropertyBuilder<AreaId> property) =>
        property.HasConversion(id => id.Value, value => new AreaId(value));

    public static PropertyBuilder<DeviceGroupId> HasDeviceGroupIdConversion(
        this PropertyBuilder<DeviceGroupId> property) =>
        property.HasConversion(id => id.Value, value => new DeviceGroupId(value));

    public static PropertyBuilder<DeviceGroupId?> HasNullableDeviceGroupIdConversion(
        this PropertyBuilder<DeviceGroupId?> property) =>
        property.HasConversion(new ValueConverter<DeviceGroupId?, Guid?>(
            id => id.HasValue ? id.Value.Value : null,
            value => value.HasValue ? new DeviceGroupId(value.Value) : null));

    public static PropertyBuilder<byte[]> IsRowVersionToken(this PropertyBuilder<byte[]> property) =>
        property.IsRequired().IsRowVersion().IsConcurrencyToken();
}
