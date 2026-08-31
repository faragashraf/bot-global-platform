using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class DeviceRegistrationConfiguration : IEntityTypeConfiguration<DeviceRegistration>
{
    public void Configure(EntityTypeBuilder<DeviceRegistration> builder)
    {
        builder.ToTable("DeviceRegistrations");
        builder.HasKey(registration => registration.Id);
        builder.Property(registration => registration.Id).ValueGeneratedNever();
        builder.Property(registration => registration.DeviceId).HasDeviceIdConversion().IsRequired();
        builder.Property(registration => registration.Kind).HasConversion<int>().IsRequired();
        builder.Property(registration => registration.ClientAppVersion).HasMaxLength(50).IsRequired();
        builder.Property(registration => registration.RegisteredAtUtc).HasPrecision(7).IsRequired();
        builder.HasOne<Device>()
            .WithMany()
            .HasForeignKey(registration => registration.DeviceId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(registration => new { registration.DeviceId, registration.RegisteredAtUtc })
            .IsDescending(false, true)
            .HasDatabaseName("IX_DeviceRegistrations_DeviceId_RegisteredAtUtc");
    }
}
