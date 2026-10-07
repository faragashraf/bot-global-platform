using BotGlobal.Identity.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Identity.Infrastructure.Persistence;

internal sealed class ApplicationMembershipConfiguration(
    bool isPostgreSql)
    : IEntityTypeConfiguration<ApplicationMembership>
{
    public void Configure(EntityTypeBuilder<ApplicationMembership> builder)
    {
        builder.ToTable("ApplicationMemberships", "identity");
        builder.HasKey(x => x.Id);
        builder.Property(x => x.ApplicationKey).HasMaxLength(80).IsRequired();
        builder.Property(x => x.SubjectId).HasMaxLength(160).IsRequired();
        builder.Property(x => x.DisplayName).HasMaxLength(120).IsRequired();
        builder.HasIndex(x => new { x.ApplicationKey, x.SubjectId }).IsUnique();
        builder.HasIndex(x => new { x.ApplicationKey, x.GlobalUserId })
            .IsUnique()
            .HasFilter(isPostgreSql
                ? "\"GlobalUserId\" IS NOT NULL"
                : "[GlobalUserId] IS NOT NULL");
        builder.HasOne<ApplicationUser>()
            .WithMany()
            .HasForeignKey(x => x.GlobalUserId)
            .OnDelete(DeleteBehavior.Restrict);
    }
}

internal sealed class MobileApplicationSessionConfiguration : IEntityTypeConfiguration<MobileApplicationSession>
{
    public void Configure(EntityTypeBuilder<MobileApplicationSession> builder)
    {
        builder.ToTable("MobileApplicationSessions", "identity");
        builder.HasKey(x => x.Id);
        builder.Property(x => x.AccessTokenHash).HasMaxLength(32).IsRequired();
        builder.Property(x => x.RefreshTokenHash).HasMaxLength(32).IsRequired();
        builder.HasIndex(x => x.AccessTokenHash).IsUnique();
        builder.HasIndex(x => x.RefreshTokenHash).IsUnique();
        builder.HasOne(x => x.Membership)
            .WithMany()
            .HasForeignKey(x => x.MembershipId)
            .OnDelete(DeleteBehavior.Cascade);
    }
}

internal sealed class ApplicationAccountDeletionRequestConfiguration
    : IEntityTypeConfiguration<ApplicationAccountDeletionRequest>
{
    public void Configure(EntityTypeBuilder<ApplicationAccountDeletionRequest> builder)
    {
        builder.ToTable("ApplicationAccountDeletionRequests", "identity");
        builder.HasKey(x => x.Id);
        builder.Property(x => x.ApplicationKey).HasMaxLength(80).IsUnicode(false).IsRequired();
        builder.Property(x => x.SubjectId).HasMaxLength(160).IsRequired();
        builder.Property(x => x.MobileDeviceIdsJson).IsRequired();
        builder.Property(x => x.LastSafeErrorCode).HasMaxLength(100).IsUnicode(false);
        builder.Property(x => x.RowVersion).IsRowVersion();
        builder.HasIndex(x => x.MembershipId).IsUnique();
        builder.HasIndex(x => x.NextAttemptAtUtc);
    }
}

internal sealed class MobileVersionPolicyConfiguration
    : IEntityTypeConfiguration<MobileVersionPolicy>
{
    public void Configure(EntityTypeBuilder<MobileVersionPolicy> builder)
    {
        builder.ToTable("MobileVersionPolicies", "identity");
        builder.HasKey(x => x.Id);
        builder.Property(x => x.ApplicationKey).HasMaxLength(80).IsUnicode(false).IsRequired();
        builder.Property(x => x.Platform).HasMaxLength(32).IsUnicode(false).IsRequired();
        builder.Property(x => x.LatestVersion).HasMaxLength(32).IsUnicode(false).IsRequired();
        builder.Property(x => x.MinimumSupportedVersion).HasMaxLength(32).IsUnicode(false).IsRequired();
        builder.Property(x => x.Message).HasMaxLength(500);
        builder.Property(x => x.StoreDestination).HasMaxLength(500).IsUnicode(false);
        builder.Property(x => x.UpdatedByDisplayName).HasMaxLength(200);
        builder.HasIndex(x => new { x.ApplicationKey, x.Platform }).IsUnique();
    }
}
