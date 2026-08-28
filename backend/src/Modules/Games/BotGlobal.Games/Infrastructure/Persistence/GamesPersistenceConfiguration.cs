using BotGlobal.Games.Domain.Sessions;
using BotGlobal.Games.Domain.Xo;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Games.Infrastructure.Persistence;

internal sealed class GameSessionConfiguration : IEntityTypeConfiguration<GameSession>
{
    public void Configure(EntityTypeBuilder<GameSession> builder)
    {
        builder.ToTable("Sessions");
        builder.HasKey(x => x.Id);
        builder.Property(x => x.ApplicationKey).HasMaxLength(80).IsRequired();
        builder.Property(x => x.JoinCode).HasMaxLength(12).IsRequired();
        builder.Property(x => x.GameType).HasMaxLength(40).IsRequired();
        builder.Property(x => x.RulesetKey).HasMaxLength(80).IsRequired();
        builder.Property(x => x.Status).HasConversion<string>().HasMaxLength(24);
        builder.HasIndex(x => new { x.ApplicationKey, x.JoinCode }).IsUnique();
        builder.HasIndex(x => new { x.ApplicationKey, x.LastActivityAtUtc });
        builder.HasMany(x => x.Players)
            .WithOne()
            .HasForeignKey(x => x.SessionId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.Navigation(x => x.Players).UsePropertyAccessMode(PropertyAccessMode.Field);
    }
}

internal sealed class GamePlayerConfiguration : IEntityTypeConfiguration<GamePlayer>
{
    public void Configure(EntityTypeBuilder<GamePlayer> builder)
    {
        builder.ToTable("Players");
        builder.HasKey(x => x.Id);
        builder.Property(x => x.DisplayName).HasMaxLength(120).IsRequired();
        builder.HasIndex(x => new { x.SessionId, x.MembershipId }).IsUnique();
        builder.HasIndex(x => new { x.SessionId, x.Seat }).IsUnique();
    }
}

internal sealed class XoSessionStateConfiguration : IEntityTypeConfiguration<XoSessionState>
{
    public void Configure(EntityTypeBuilder<XoSessionState> builder)
    {
        builder.ToTable("XoSessionStates");
        builder.HasKey(x => x.SessionId);
        builder.Property(x => x.MatchStatus).HasConversion<string>().HasMaxLength(24);
        builder.Property(x => x.RequiredEntitlement).HasMaxLength(120);
        builder.Property(x => x.ConcurrencyToken).IsRowVersion();
        builder.HasOne<GameSession>()
            .WithOne()
            .HasForeignKey<XoSessionState>(x => x.SessionId)
            .OnDelete(DeleteBehavior.Cascade);
    }
}

internal sealed class XoMoveConfiguration : IEntityTypeConfiguration<XoMove>
{
    public void Configure(EntityTypeBuilder<XoMove> builder)
    {
        builder.ToTable("XoMoves");
        builder.HasKey(x => x.Id);
        builder.Property(x => x.CommandId).HasMaxLength(100).IsRequired();
        builder.HasIndex(x => new { x.SessionId, x.CommandId }).IsUnique();
        builder.HasIndex(x => new { x.SessionId, x.AcceptedVersion }).IsUnique();
        builder.HasOne<GameSession>()
            .WithMany()
            .HasForeignKey(x => x.SessionId)
            .OnDelete(DeleteBehavior.Cascade);
    }
}
