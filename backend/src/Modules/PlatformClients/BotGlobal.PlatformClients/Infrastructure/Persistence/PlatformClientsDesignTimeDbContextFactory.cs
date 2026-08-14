using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace BotGlobal.PlatformClients.Infrastructure.Persistence;

public sealed class PlatformClientsDesignTimeDbContextFactory
    : IDesignTimeDbContextFactory<PlatformClientsDbContext>
{
    public PlatformClientsDbContext CreateDbContext(string[] args)
    {
        var options = new DbContextOptionsBuilder<PlatformClientsDbContext>();

        const string designTimeConnection =
            "Server=127.0.0.1,1433;"
            + "Database=BotGlobalPlatformClients_DesignTime;"
            + "User Id=design_time;"
            + "Password=DesignTimeOnly_NotUsed;"
            + "Encrypt=False;"
            + "TrustServerCertificate=True";

        options.UseSqlServer(
            designTimeConnection,
            sqlServer => sqlServer.MigrationsHistoryTable(
                PlatformClientsModule.MigrationsHistoryTableName,
                PlatformClientsModule.DatabaseSchema));

        return new PlatformClientsDbContext(options.Options);
    }
}
