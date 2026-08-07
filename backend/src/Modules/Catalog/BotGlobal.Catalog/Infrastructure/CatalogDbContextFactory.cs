using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace BotGlobal.Catalog.Infrastructure;

public sealed class CatalogDbContextFactory : IDesignTimeDbContextFactory<CatalogDbContext>
{
    public CatalogDbContext CreateDbContext(string[] args)
    {
        var options = new DbContextOptionsBuilder<CatalogDbContext>()
            .UseSqlServer(
                "Server=(localdb)\\mssqllocaldb;Database=BotGlobal.Catalog;Trusted_Connection=True;",
                sqlServer => sqlServer.MigrationsHistoryTable(
                    CatalogDbContext.MigrationHistoryTable,
                    CatalogDbContext.Schema))
            .Options;

        return new CatalogDbContext(options);
    }
}
