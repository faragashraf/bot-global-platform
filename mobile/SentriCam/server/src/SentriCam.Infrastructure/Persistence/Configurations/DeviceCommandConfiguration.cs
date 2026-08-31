using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Domain.Devices;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class DeviceCommandConfiguration : IEntityTypeConfiguration<DeviceCommand>
{
    public void Configure(EntityTypeBuilder<DeviceCommand> builder)
    {
        builder.ToTable("DeviceCommands");
        builder.HasKey(command => command.Id);
        builder.Property(command => command.Id).ValueGeneratedNever();
        builder.Property(command => command.DeviceId).HasDeviceIdConversion().IsRequired();
        builder.Property(command => command.Kind).HasConversion<int>().IsRequired();
        builder.Property(command => command.CorrelationId).HasMaxLength(100).IsRequired();
        builder.Property(command => command.State).HasConversion<int>().IsRequired();
        builder.Property(command => command.RequestedAtUtc).HasPrecision(7).IsRequired();
        builder.Property(command => command.ExpiresAtUtc).HasPrecision(7);
        builder.Property(command => command.Origin).HasConversion<int>().IsRequired();
        builder.Property(command => command.DispatchedAtUtc).HasPrecision(7);
        builder.Property(command => command.CompletedAtUtc).HasPrecision(7);
        builder.Property(command => command.FailureReason).HasMaxLength(500);
        builder.Property(command => command.ResultCode).HasMaxLength(100);
        builder.Property(command => command.ResultJson).HasColumnType("nvarchar(max)");
        builder.Ignore(command => command.CommandId);
        builder.Ignore(command => command.CreatedAtUtc);
        builder.Property<byte[]>("RowVersion").IsRowVersionToken();
        builder.HasOne<Device>()
            .WithMany()
            .HasForeignKey(command => command.DeviceId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(command => new { command.DeviceId, command.CorrelationId })
            .IsUnique()
            .HasDatabaseName("UX_DeviceCommands_DeviceId_CorrelationId");
        builder.HasIndex(command => new { command.DeviceId, command.State, command.RequestedAtUtc })
            .IsDescending(false, false, true)
            .HasDatabaseName("IX_DeviceCommands_DeviceId_State_RequestedAtUtc");
    }
}
