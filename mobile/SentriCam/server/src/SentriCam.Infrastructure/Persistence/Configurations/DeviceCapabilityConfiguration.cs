using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class DeviceCapabilityConfiguration : IEntityTypeConfiguration<DeviceCapability>
{
    public void Configure(EntityTypeBuilder<DeviceCapability> builder)
    {
        builder.ToTable("DeviceCapabilities");
        builder.HasKey(capability => capability.Id);
        builder.Property(capability => capability.Id).ValueGeneratedNever();
        builder.Property(capability => capability.DeviceId).HasDeviceIdConversion().IsRequired();
        builder.Property(capability => capability.Name).HasMaxLength(100).IsRequired();
        builder.Property(capability => capability.NormalizedName).HasMaxLength(100).IsRequired();
        builder.Property(capability => capability.Version).HasMaxLength(50).IsRequired();
        builder.Property(capability => capability.IsEnabled).IsRequired();
        builder.Property(capability => capability.AddedAtUtc).HasPrecision(7).IsRequired();
        builder.Property(capability => capability.UpdatedAtUtc).HasPrecision(7).IsRequired();
        builder.HasIndex(capability => new { capability.DeviceId, capability.NormalizedName })
            .IsUnique()
            .HasDatabaseName("UX_DeviceCapabilities_DeviceId_NormalizedName");
    }
}
