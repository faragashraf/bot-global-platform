using BotGlobal.Pairing.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Pairing.Infrastructure.Persistence.Configurations;

internal sealed class MobileDeviceAuditEntryConfiguration
    : IEntityTypeConfiguration<MobileDeviceAuditEntry>
{
    public void Configure(
        EntityTypeBuilder<MobileDeviceAuditEntry> builder)
    {
        builder.ToTable("MobileDeviceAuditEntries");

        builder.HasKey(entry => entry.Id);

        builder.Property(entry => entry.Kind)
            .HasMaxLength(MobileDeviceAuditEntry.KindMaxLength)
            .IsRequired();

        builder.Property(entry => entry.ActorType)
            .HasMaxLength(MobileDeviceAuditActorTypes.MaxLength)
            .IsRequired();

        builder.Property(entry => entry.ActorDisplayName)
            .HasMaxLength(
                MobileDeviceAuditEntry.ActorDisplayNameMaxLength);

        builder.Property(entry => entry.Detail)
            .HasMaxLength(MobileDeviceAuditEntry.DetailMaxLength);

        builder.HasIndex(entry =>
            new { entry.MobileDeviceId, entry.OccurredAtUtc });

        builder.HasOne<MobileDevice>()
            .WithMany()
            .HasForeignKey(entry => entry.MobileDeviceId)
            .OnDelete(DeleteBehavior.Cascade);
    }
}
