using BotGlobal.Pairing.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Pairing.Infrastructure.Persistence.Configurations;

public sealed class PairingChallengeConfiguration
    : IEntityTypeConfiguration<PairingChallenge>
{
    public void Configure(EntityTypeBuilder<PairingChallenge> builder)
    {
        builder.ToTable(
            "PairingChallenges",
            PairingModule.DatabaseSchema,
            table => table.HasCheckConstraint(
                "CK_PairingChallenges_Status",
                "[Status] IN ('Pending','Completed')"));

        builder.HasKey(challenge => challenge.Id);

        builder.Property(challenge => challenge.PlatformClientId)
            .IsRequired();

        builder.Property(challenge => challenge.TokenHash)
            .HasColumnType("binary(32)")
            .IsRequired();

        builder.Property(challenge => challenge.CorrelationReference)
            .HasMaxLength(PairingChallenge.CorrelationReferenceMaxLength);

        builder.Property(challenge => challenge.CreatedAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();

        builder.Property(challenge => challenge.ExpiresAtUtc)
            .HasColumnType("datetimeoffset")
            .IsRequired();

        builder.Property(challenge => challenge.CompletedAtUtc)
            .HasColumnType("datetimeoffset");

        builder.Property(challenge => challenge.Status)
            .HasConversion<string>()
            .HasMaxLength(16)
            .IsUnicode(false)
            .IsRequired();

        builder.Property(challenge => challenge.MobilePlatform)
            .HasMaxLength(PairingChallenge.MobilePlatformMaxLength)
            .IsUnicode(false);

        builder.Property(challenge => challenge.MobileInstallationId)
            .HasMaxLength(PairingChallenge.MobileInstallationIdMaxLength)
            .IsUnicode(false);

        builder.Property(challenge => challenge.MobileDeviceName)
            .HasMaxLength(PairingChallenge.MobileDeviceNameMaxLength);

        builder.Property(challenge => challenge.MobileAppVersion)
            .HasMaxLength(PairingChallenge.MobileAppVersionMaxLength)
            .IsUnicode(false);

        builder.Property(challenge => challenge.ConcurrencyStamp)
            .IsConcurrencyToken()
            .IsRequired();

        builder.HasIndex(challenge => challenge.TokenHash)
            .IsUnique()
            .HasDatabaseName("UX_PairingChallenges_TokenHash");

        builder.HasIndex(
                challenge => new
                {
                    challenge.PlatformClientId,
                    challenge.Status,
                    challenge.ExpiresAtUtc
                })
            .HasDatabaseName(
                "IX_PairingChallenges_PlatformClient_Status_Expires");
    }
}
