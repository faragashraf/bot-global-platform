using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class DeviceSnapshotConfiguration : IEntityTypeConfiguration<DeviceSnapshot>
{
    public void Configure(EntityTypeBuilder<DeviceSnapshot> builder)
    {
        builder.ToTable("DeviceSnapshots");
        builder.HasKey(snapshot => snapshot.Id);
        builder.Property(snapshot => snapshot.Id).ValueGeneratedNever();
        builder.Property(snapshot => snapshot.DeviceId).HasDeviceIdConversion().IsRequired();
        builder.Property(snapshot => snapshot.Status).HasConversion<int>().IsRequired();
        builder.Property(snapshot => snapshot.BatteryPercentage);
        builder.Property(snapshot => snapshot.AvailableStorageBytes);
        builder.Property(snapshot => snapshot.IsMonitoring).IsRequired();
        builder.Property(snapshot => snapshot.IsRecording).IsRequired();
        builder.Property(snapshot => snapshot.MetadataJson).HasColumnType("nvarchar(max)");
        builder.Property(snapshot => snapshot.SnapshotVersion).IsRequired();
        builder.Property(snapshot => snapshot.SchemaVersion).IsRequired();
        builder.Property(snapshot => snapshot.GeneratedAtUtc).HasPrecision(7).IsRequired();
        builder.Property(snapshot => snapshot.CapturedAtUtc).HasPrecision(7).IsRequired();
        builder.HasOne<Device>()
            .WithMany()
            .HasForeignKey(snapshot => snapshot.DeviceId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(snapshot => new { snapshot.DeviceId, snapshot.CapturedAtUtc })
            .IsDescending(false, true)
            .HasDatabaseName("IX_DeviceSnapshots_DeviceId_CapturedAtUtc");
        builder.HasIndex(snapshot => new { snapshot.DeviceId, snapshot.SnapshotVersion })
            .IsUnique()
            .HasDatabaseName("UX_DeviceSnapshots_DeviceId_SnapshotVersion");
    }
}
