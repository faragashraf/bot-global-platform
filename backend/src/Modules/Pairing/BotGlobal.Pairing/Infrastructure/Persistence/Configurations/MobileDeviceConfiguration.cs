using BotGlobal.Pairing.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Pairing.Infrastructure.Persistence.Configurations;

public sealed class MobileDeviceConfiguration
    : IEntityTypeConfiguration<MobileDevice>
{
    public void Configure(
        EntityTypeBuilder<MobileDevice> builder)
    {
        builder.ToTable("MobileDevices");

        builder.HasKey(x => x.Id);

        builder.Property(x => x.PlatformClientId)
            .IsRequired();

        builder.Property(x => x.InstallationId)
            .HasMaxLength(200)
            .IsRequired();

        builder.Property(x => x.Platform)
            .HasMaxLength(50)
            .IsRequired();

        builder.Property(x => x.DeviceName)
            .HasMaxLength(250);

        builder.Property(x => x.AppVersion)
            .HasMaxLength(100);

        builder.Property(x => x.CredentialHash)
            .HasMaxLength(32)
            .IsRequired();

        builder.Property(x => x.CreatedAtUtc)
            .IsRequired();

        builder.Property(x => x.LastPairedAtUtc)
            .IsRequired();

        builder.HasIndex(
                x => new
                {
                    x.PlatformClientId,
                    x.InstallationId
                })
            .IsUnique();

        builder.HasIndex(x => x.CredentialHash)
            .IsUnique();
    }
}
