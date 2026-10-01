using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class DeviceStatusHistoryConfiguration : IEntityTypeConfiguration<DeviceStatusHistory>
{
    public void Configure(EntityTypeBuilder<DeviceStatusHistory> builder)
    {
        builder.ToTable("DeviceStatusHistory");
        builder.HasKey(history => history.Id);
        builder.Property(history => history.Id).ValueGeneratedNever();
        builder.Property(history => history.DeviceId).HasDeviceIdConversion().IsRequired();
        builder.Property(history => history.PreviousStatus).HasConversion<int?>();
        builder.Property(history => history.CurrentStatus).HasConversion<int>().IsRequired();
        builder.Property(history => history.Reason).HasMaxLength(500);
        builder.Property(history => history.ChangedAtUtc).HasPrecision(7).IsRequired();
        builder.HasOne<Device>()
            .WithMany()
            .HasForeignKey(history => history.DeviceId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(history => new { history.DeviceId, history.ChangedAtUtc })
            .IsDescending(false, true)
            .HasDatabaseName("IX_DeviceStatusHistory_DeviceId_ChangedAtUtc");
    }
}
