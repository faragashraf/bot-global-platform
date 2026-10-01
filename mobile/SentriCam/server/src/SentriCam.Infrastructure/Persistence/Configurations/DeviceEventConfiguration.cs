using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class DeviceEventConfiguration : IEntityTypeConfiguration<DeviceEvent>
{
    public void Configure(EntityTypeBuilder<DeviceEvent> builder)
    {
        builder.ToTable("DeviceEvents");
        builder.HasKey(deviceEvent => deviceEvent.Id);
        builder.Property(deviceEvent => deviceEvent.Id).ValueGeneratedNever();
        builder.Property(deviceEvent => deviceEvent.DeviceId).HasDeviceIdConversion().IsRequired();
        builder.Property(deviceEvent => deviceEvent.EventType).HasMaxLength(100).IsRequired();
        builder.Property(deviceEvent => deviceEvent.PayloadJson).HasColumnType("nvarchar(max)");
        builder.Property(deviceEvent => deviceEvent.OccurredAtUtc).HasPrecision(7).IsRequired();
        builder.Property(deviceEvent => deviceEvent.ReceivedAtUtc).HasPrecision(7).IsRequired();
        builder.HasOne<Device>()
            .WithMany()
            .HasForeignKey(deviceEvent => deviceEvent.DeviceId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(deviceEvent => new { deviceEvent.DeviceId, deviceEvent.OccurredAtUtc })
            .IsDescending(false, true)
            .HasDatabaseName("IX_DeviceEvents_DeviceId_OccurredAtUtc");
        builder.HasIndex(deviceEvent => new { deviceEvent.EventType, deviceEvent.ReceivedAtUtc })
            .IsDescending(false, true)
            .HasDatabaseName("IX_DeviceEvents_EventType_ReceivedAtUtc");
    }
}
