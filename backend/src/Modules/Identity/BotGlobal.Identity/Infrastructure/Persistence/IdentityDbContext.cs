using BotGlobal.Identity.Domain;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Infrastructure.Persistence;

public sealed class IdentityDbContext(
    DbContextOptions<IdentityDbContext> options)
    : IdentityDbContext<
        ApplicationUser,
        IdentityRole<Guid>,
        Guid>(options)
{
    public DbSet<ApplicationMembership> ApplicationMemberships => Set<ApplicationMembership>();

    public DbSet<MobileApplicationSession> MobileApplicationSessions => Set<MobileApplicationSession>();

    protected override void OnModelCreating(
        ModelBuilder builder)
    {
        base.OnModelCreating(builder);

        builder.HasDefaultSchema("identity");

        builder.Entity<ApplicationUser>(
            entity =>
            {
                entity.ToTable(
                    "Users",
                    "identity");

                entity.Property(x => x.DisplayName)
                    .HasMaxLength(200)
                    .IsRequired();

                entity.Property(x => x.IsActive)
                    .IsRequired();

                entity.Property(x => x.CreatedAtUtc)
                    .HasColumnType("datetimeoffset")
                    .IsRequired();
            });

        builder.Entity<IdentityRole<Guid>>()
            .ToTable("Roles", "identity");

        builder.Entity<IdentityUserRole<Guid>>()
            .ToTable("UserRoles", "identity");

        builder.Entity<IdentityUserClaim<Guid>>()
            .ToTable("UserClaims", "identity");

        builder.Entity<IdentityUserLogin<Guid>>()
            .ToTable("UserLogins", "identity");

        builder.Entity<IdentityRoleClaim<Guid>>()
            .ToTable("RoleClaims", "identity");

        builder.Entity<IdentityUserToken<Guid>>()
            .ToTable("UserTokens", "identity");

        builder.ApplyConfiguration(new ApplicationMembershipConfiguration());
        builder.ApplyConfiguration(new MobileApplicationSessionConfiguration());
    }
}
