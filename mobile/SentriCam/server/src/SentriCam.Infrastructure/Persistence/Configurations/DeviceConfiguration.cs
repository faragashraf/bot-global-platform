using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Domain.Devices;
using SentriCam.Domain.Hierarchy;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class DeviceConfiguration : IEntityTypeConfiguration<Device>
{
    public void Configure(EntityTypeBuilder<Device> builder)
    {
        builder.ToTable("Devices");
        builder.HasKey(device => device.Id);
        builder.Property(device => device.Id)
            .HasDeviceIdConversion()
            .ValueGeneratedNever();
        builder.Property(device => device.DisplayName).HasMaxLength(200).IsRequired();
        builder.Property(device => device.Status).HasConversion<int>().IsRequired();
        builder.Property(device => device.IsEnabled).IsRequired();
        builder.Property(device => device.RegisteredAtUtc).HasPrecision(7).IsRequired();
        builder.Property(device => device.UpdatedAtUtc).HasPrecision(7).IsRequired();
        builder.Property(device => device.LastSeenAtUtc).HasPrecision(7);
        builder.Property(device => device.DeviceGroupId).HasNullableDeviceGroupIdConversion();
        builder.Property<byte[]>("RowVersion").IsRowVersionToken();

        builder.OwnsOne(device => device.Identity, identity =>
        {
            identity.Property(value => value.InstallationId)
                .HasColumnName("InstallationId")
                .HasMaxLength(128)
                .IsRequired();
            identity.Property(value => value.Manufacturer)
                .HasColumnName("Manufacturer")
                .HasMaxLength(100)
                .IsRequired();
            identity.Property(value => value.Model)
                .HasColumnName("Model")
                .HasMaxLength(100)
                .IsRequired();
            identity.Property(value => value.Platform)
                .HasColumnName("Platform")
                .HasMaxLength(50)
                .IsRequired();
            identity.Property(value => value.OperatingSystemVersion)
                .HasColumnName("OperatingSystemVersion")
                .HasMaxLength(50)
                .IsRequired();
            identity.Property(value => value.AppVersion)
                .HasColumnName("AppVersion")
                .HasMaxLength(50)
                .IsRequired();
            identity.HasIndex(value => value.InstallationId)
                .IsUnique()
                .HasDatabaseName("UX_Devices_InstallationId");
        });

        builder.Navigation(device => device.Identity).IsRequired();
        builder.HasMany(device => device.Capabilities)
            .WithOne()
            .HasForeignKey(capability => capability.DeviceId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.Navigation(device => device.Capabilities)
            .UsePropertyAccessMode(PropertyAccessMode.Field);
        builder.HasOne<DeviceGroup>()
            .WithMany()
            .HasForeignKey(device => device.DeviceGroupId)
            .OnDelete(DeleteBehavior.SetNull);
        builder.HasIndex(device => new { device.Status, device.LastSeenAtUtc })
            .HasDatabaseName("IX_Devices_Status_LastSeenAtUtc");
        builder.HasIndex(device => device.DeviceGroupId)
            .HasDatabaseName("IX_Devices_DeviceGroupId");
    }
}
