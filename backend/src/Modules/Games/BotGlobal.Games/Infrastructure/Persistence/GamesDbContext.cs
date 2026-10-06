using BotGlobal.Games.Domain.Invitations;
using BotGlobal.Games.Domain.Sessions;
using BotGlobal.Games.Domain.Autobus;
using BotGlobal.Games.Domain.Xo;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;
using System.Security.Cryptography;

namespace BotGlobal.Games.Infrastructure.Persistence;

public sealed class GamesDbContext(DbContextOptions<GamesDbContext> options) : DbContext(options)
{
    public const string Schema = "games";
    public const string MigrationHistoryTable = "__EFMigrationsHistory";

    public DbSet<GameSession> Sessions => Set<GameSession>();
    public DbSet<GameInvitation> Invitations => Set<GameInvitation>();
    public DbSet<GamePlayer> Players => Set<GamePlayer>();
    public DbSet<AutobusSessionState> AutobusStates => Set<AutobusSessionState>();
    public DbSet<AutobusCommand> AutobusCommands => Set<AutobusCommand>();
    public DbSet<XoSessionState> XoStates => Set<XoSessionState>();
    public DbSet<XoMove> XoMoves => Set<XoMove>();
    internal DbSet<GamesMembershipDeletionFence> MembershipDeletionFences => Set<GamesMembershipDeletionFence>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema(Schema);
        modelBuilder.ApplyConfigurationsFromAssembly(typeof(GamesDbContext).Assembly);
        if (Database.IsNpgsql())
        {
            foreach (var property in new[]
            {
                modelBuilder.Entity<XoSessionState>().Property(x => x.ConcurrencyToken).Metadata,
                modelBuilder.Entity<AutobusSessionState>().Property(x => x.ConcurrencyToken).Metadata,
            })
            {
                property.ValueGenerated = ValueGenerated.Never;
                property.SetBeforeSaveBehavior(PropertySaveBehavior.Save);
                property.SetAfterSaveBehavior(PropertySaveBehavior.Save);
            }
        }
        if (string.Equals(
                Database.ProviderName,
                "Microsoft.EntityFrameworkCore.InMemory",
                StringComparison.Ordinal))
        {
            modelBuilder.Entity<XoSessionState>()
                .Property(x => x.ConcurrencyToken)
                .IsConcurrencyToken(false)
                .ValueGeneratedNever();
            modelBuilder.Entity<AutobusSessionState>()
                .Property(x => x.ConcurrencyToken)
                .IsConcurrencyToken(false)
                .ValueGeneratedNever();
        }
        base.OnModelCreating(modelBuilder);
    }

    public override int SaveChanges(bool acceptAllChangesOnSuccess)
    {
        StampPortableConcurrencyTokens();
        return base.SaveChanges(acceptAllChangesOnSuccess);
    }

    public override Task<int> SaveChangesAsync(
        bool acceptAllChangesOnSuccess,
        CancellationToken cancellationToken = default)
    {
        StampPortableConcurrencyTokens();
        return base.SaveChangesAsync(acceptAllChangesOnSuccess, cancellationToken);
    }

    private void StampPortableConcurrencyTokens()
    {
        if (!Database.IsNpgsql()) return;

        foreach (var entry in ChangeTracker.Entries()
            .Where(entry => entry.State is EntityState.Added or EntityState.Modified &&
                entry.Entity is XoSessionState or AutobusSessionState))
        {
            entry.Property(nameof(XoSessionState.ConcurrencyToken)).CurrentValue =
                RandomNumberGenerator.GetBytes(8);
        }
    }
}
