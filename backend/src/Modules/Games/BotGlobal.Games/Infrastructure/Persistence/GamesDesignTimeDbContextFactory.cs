using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace BotGlobal.Games.Infrastructure.Persistence;

public sealed class GamesDesignTimeDbContextFactory : IDesignTimeDbContextFactory<GamesDbContext>
{
    public GamesDbContext CreateDbContext(string[] args)
    {
        var options = new DbContextOptionsBuilder<GamesDbContext>()
            .UseSqlServer(
                "Server=localhost;Database=BotGlobal.Games;Trusted_Connection=True;TrustServerCertificate=True",
                sql => sql.MigrationsHistoryTable(
                    GamesDbContext.MigrationHistoryTable,
                    GamesDbContext.Schema))
            .Options;
        return new GamesDbContext(options);
    }
}
