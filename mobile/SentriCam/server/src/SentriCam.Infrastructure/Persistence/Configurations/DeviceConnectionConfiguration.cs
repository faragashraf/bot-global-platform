using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class DeviceConnectionConfiguration : IEntityTypeConfiguration<DeviceConnection>
{
    public void Configure(EntityTypeBuilder<DeviceConnection> builder)
    {
        builder.ToTable("DeviceConnections");
        builder.HasKey(connection => connection.Id);
        builder.Property(connection => connection.Id).ValueGeneratedNever();
        builder.Property(connection => connection.DeviceId).HasDeviceIdConversion().IsRequired();
        builder.Property(connection => connection.TransportConnectionId).HasMaxLength(200).IsRequired();
        builder.Property(connection => connection.ConnectedAtUtc).HasPrecision(7).IsRequired();
        builder.Property(connection => connection.LastSeenAtUtc).HasPrecision(7).IsRequired();
        builder.Property(connection => connection.DisconnectedAtUtc).HasPrecision(7);
        builder.Property(connection => connection.IsActive).IsRequired();
        builder.Property<byte[]>("RowVersion").IsRowVersionToken();
        builder.HasOne<Device>()
            .WithMany()
            .HasForeignKey(connection => connection.DeviceId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(connection => connection.TransportConnectionId)
            .IsUnique()
            .HasDatabaseName("UX_DeviceConnections_TransportConnectionId");
        builder.HasIndex(connection => new { connection.DeviceId, connection.IsActive })
            .IsUnique()
            .HasFilter("[IsActive] = CAST(1 AS bit)")
            .HasDatabaseName("UX_DeviceConnections_OneActivePerDevice");
        builder.HasIndex(connection => new { connection.DeviceId, connection.LastSeenAtUtc })
            .IsDescending(false, true)
            .HasDatabaseName("IX_DeviceConnections_DeviceId_LastSeenAtUtc");
    }
}
