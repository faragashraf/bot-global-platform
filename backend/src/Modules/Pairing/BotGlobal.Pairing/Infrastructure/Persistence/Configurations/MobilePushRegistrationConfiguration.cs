using BotGlobal.Pairing.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Pairing.Infrastructure.Persistence.Configurations;

internal sealed class MobilePushRegistrationConfiguration
    : IEntityTypeConfiguration<MobilePushRegistration>
{
    public void Configure(
        EntityTypeBuilder<MobilePushRegistration> builder)
    {
        builder.ToTable(
            "MobilePushRegistrations",
            PairingModule.DatabaseSchema);

        builder.HasKey(x => x.Id);

        builder.Property(x => x.Provider)
            .HasMaxLength(32)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(x => x.RegistrationToken)
            .HasMaxLength(2048)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(x => x.CreatedAtUtc)
            .IsRequired();

        builder.Property(x => x.UpdatedAtUtc)
            .IsRequired();

        builder.HasIndex(
                x => new
                {
                    x.MobileDeviceId,
                    x.Provider
                })
            .IsUnique()
            .HasDatabaseName(
                "UX_MobilePushRegistrations_Device_Provider");

        builder.HasIndex(
                x => new
                {
                    x.Provider,
                    x.InvalidatedAtUtc
                })
            .HasDatabaseName(
                "IX_MobilePushRegistrations_Provider_Invalidated");

        builder.HasOne<MobileDevice>()
            .WithMany()
            .HasForeignKey(x => x.MobileDeviceId)
            .OnDelete(DeleteBehavior.Cascade);
    }
}
