using BotGlobal.Identity.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Identity.Infrastructure.Persistence;

internal sealed class ApplicationMembershipConfiguration : IEntityTypeConfiguration<ApplicationMembership>
{
    public void Configure(EntityTypeBuilder<ApplicationMembership> builder)
    {
        builder.ToTable("ApplicationMemberships", "identity");
        builder.HasKey(x => x.Id);
        builder.Property(x => x.ApplicationKey).HasMaxLength(80).IsRequired();
        builder.Property(x => x.SubjectId).HasMaxLength(160).IsRequired();
        builder.Property(x => x.DisplayName).HasMaxLength(120).IsRequired();
        builder.HasIndex(x => new { x.ApplicationKey, x.SubjectId }).IsUnique();
        builder.HasIndex(x => new { x.ApplicationKey, x.GlobalUserId }).IsUnique().HasFilter("[GlobalUserId] IS NOT NULL");
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
