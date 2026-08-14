using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace BotGlobal.Pairing.Infrastructure.Persistence;

public sealed class PairingDesignTimeDbContextFactory
    : IDesignTimeDbContextFactory<PairingDbContext>
{
    public PairingDbContext CreateDbContext(string[] args)
    {
        var options = new DbContextOptionsBuilder<PairingDbContext>();

        const string designTimeConnection =
            "Server=127.0.0.1,1433;"
            + "Database=BotGlobalPairing_DesignTime;"
            + "User Id=design_time;"
            + "Password=DesignTimeOnly_NotUsed;"
            + "Encrypt=False;"
            + "TrustServerCertificate=True";

        options.UseSqlServer(
            designTimeConnection,
            sqlServer => sqlServer.MigrationsHistoryTable(
                PairingModule.MigrationsHistoryTableName,
                PairingModule.DatabaseSchema));

        return new PairingDbContext(options.Options);
    }
}
