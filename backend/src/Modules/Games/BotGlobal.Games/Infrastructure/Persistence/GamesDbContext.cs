using BotGlobal.Games.Domain.Sessions;
using BotGlobal.Games.Domain.Xo;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Games.Infrastructure.Persistence;

public sealed class GamesDbContext(DbContextOptions<GamesDbContext> options) : DbContext(options)
{
    public const string Schema = "games";
    public const string MigrationHistoryTable = "__EFMigrationsHistory";

    public DbSet<GameSession> Sessions => Set<GameSession>();
    public DbSet<GamePlayer> Players => Set<GamePlayer>();
    public DbSet<XoSessionState> XoStates => Set<XoSessionState>();
    public DbSet<XoMove> XoMoves => Set<XoMove>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema(Schema);
        modelBuilder.ApplyConfigurationsFromAssembly(typeof(GamesDbContext).Assembly);
        if (string.Equals(
                Database.ProviderName,
                "Microsoft.EntityFrameworkCore.InMemory",
                StringComparison.Ordinal))
        {
            modelBuilder.Entity<XoSessionState>()
                .Property(x => x.ConcurrencyToken)
                .IsConcurrencyToken(false)
                .ValueGeneratedNever();
        }
        base.OnModelCreating(modelBuilder);
    }
}
