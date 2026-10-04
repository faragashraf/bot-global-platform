using BotGlobal.Games.Domain.Invitations;
using BotGlobal.Games.Domain.Sessions;
using BotGlobal.Games.Domain.Autobus;
using BotGlobal.Games.Domain.Xo;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace BotGlobal.Games.Infrastructure.Persistence;

internal sealed class GamesMembershipDeletionFenceConfiguration : IEntityTypeConfiguration<GamesMembershipDeletionFence>
{
    public void Configure(EntityTypeBuilder<GamesMembershipDeletionFence> builder)
    {
        builder.ToTable("MembershipDeletionFences");
        builder.HasKey(x => new { x.ApplicationKey, x.MembershipId });
        builder.Property(x => x.ApplicationKey).HasMaxLength(80).IsRequired();
    }
}

internal sealed class GameInvitationConfiguration : IEntityTypeConfiguration<GameInvitation>
{
    public void Configure(EntityTypeBuilder<GameInvitation> builder)
    {
        builder.ToTable("Invitations");
        builder.HasKey(x => x.Id);
        builder.Property(x => x.ApplicationKey).HasMaxLength(80).IsRequired();
        builder.Property(x => x.TokenHash).HasMaxLength(64).IsRequired();
        builder.HasIndex(x => x.TokenHash).IsUnique();
        builder.HasIndex(x => new { x.ApplicationKey, x.SessionId, x.ExpiresAtUtc });
        builder.HasOne<GameSession>()
            .WithMany()
            .HasForeignKey(x => x.SessionId)
            .OnDelete(DeleteBehavior.Cascade);
    }
}

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
        builder.Property(x => x.RequiredEntitlement).HasMaxLength(120);
        builder.Property(x => x.Status).HasConversion<string>().HasMaxLength(24);
        builder.Property(x => x.AggregateVersion).IsConcurrencyToken();
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

internal sealed class AutobusSessionStateConfiguration : IEntityTypeConfiguration<AutobusSessionState>
{
    public void Configure(EntityTypeBuilder<AutobusSessionState> builder)
    {
        builder.ToTable("AutobusSessionStates");
        builder.HasKey(x => x.SessionId);
        builder.Property(x => x.Difficulty).HasMaxLength(24).IsRequired();
        builder.Property(x => x.CurrentLetter).HasMaxLength(8);
        builder.Property(x => x.Phase).HasConversion<string>().HasMaxLength(24);
        builder.Property(x => x.CategoriesJson).IsRequired();
        builder.Property(x => x.UsedLettersJson).IsRequired();
        builder.Property(x => x.AnswersJson).IsRequired();
        builder.Property(x => x.ScoresJson).IsRequired();
        builder.Property(x => x.VotesJson).IsRequired();
        builder.Property(x => x.RevealCategoryKey).HasMaxLength(80);
        builder.Property(x => x.TieMessageCode).HasMaxLength(80);
        builder.Property(x => x.ConcurrencyToken).IsRowVersion();
        builder.Ignore(x => x.Categories);
        builder.Ignore(x => x.UsedLetters);
        builder.Ignore(x => x.Answers);
        builder.Ignore(x => x.Scores);
        builder.Ignore(x => x.Votes);
        builder.HasOne<GameSession>()
            .WithOne()
            .HasForeignKey<AutobusSessionState>(x => x.SessionId)
            .OnDelete(DeleteBehavior.Cascade);
    }
}

internal sealed class AutobusCommandConfiguration : IEntityTypeConfiguration<AutobusCommand>
{
    public void Configure(EntityTypeBuilder<AutobusCommand> builder)
    {
        builder.ToTable("AutobusCommands");
        builder.HasKey(x => x.Id);
        builder.Property(x => x.CommandId).HasMaxLength(100).IsRequired();
        builder.Property(x => x.Kind).HasMaxLength(40).IsRequired();
        builder.HasIndex(x => new { x.SessionId, x.CommandId }).IsUnique();
        builder.HasIndex(x => new { x.SessionId, x.AcceptedVersion }).IsUnique();
        builder.HasOne<GameSession>()
            .WithMany()
            .HasForeignKey(x => x.SessionId)
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
