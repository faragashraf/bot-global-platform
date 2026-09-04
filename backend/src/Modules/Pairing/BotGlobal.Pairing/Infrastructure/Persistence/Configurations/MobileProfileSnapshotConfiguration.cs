using BotGlobal.Pairing.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Pairing.Infrastructure.Persistence.Configurations;

public sealed class MobileProfileSnapshotConfiguration
    : IEntityTypeConfiguration<MobileProfileSnapshot>
{
    public void Configure(
        EntityTypeBuilder<MobileProfileSnapshot> builder)
    {
        builder.ToTable("MobileProfileSnapshots");
        builder.HasKey(snapshot => snapshot.Id);

        builder.Property(snapshot => snapshot.PlatformClientId)
            .IsRequired();
        builder.Property(snapshot => snapshot.ExternalSubjectId)
            .HasMaxLength(PairingChallenge.ExternalSubjectIdMaxLength)
            .IsRequired();
        builder.Property(snapshot => snapshot.DisplayName)
            .HasMaxLength(MobileProfileSnapshot.DisplayNameMaxLength)
            .IsRequired();
        builder.Property(snapshot => snapshot.JobTitle)
            .HasMaxLength(MobileProfileSnapshot.JobTitleMaxLength);
        builder.Property(snapshot => snapshot.OrganizationUnit)
            .HasMaxLength(MobileProfileSnapshot.OrganizationUnitMaxLength);
        builder.Property(snapshot => snapshot.Version)
            .IsRequired();
        builder.Property(snapshot => snapshot.PublishedAtUtc)
            .IsRequired();
        builder.Property(snapshot => snapshot.ReceivedAtUtc)
            .IsRequired();
        builder.Property(snapshot => snapshot.RowVersion)
            .IsRowVersion();

        builder.HasIndex(snapshot => new
            {
                snapshot.PlatformClientId,
                snapshot.ExternalSubjectId
            })
            .IsUnique()
            .HasDatabaseName(
                "UX_MobileProfileSnapshots_PlatformClientId_ExternalSubjectId");
    }
}
