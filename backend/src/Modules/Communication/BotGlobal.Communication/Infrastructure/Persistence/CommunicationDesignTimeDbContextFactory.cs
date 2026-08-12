using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace BotGlobal.Communication.Infrastructure.Persistence;

public sealed class CommunicationDesignTimeDbContextFactory
    : IDesignTimeDbContextFactory<CommunicationDbContext>
{
    public CommunicationDbContext CreateDbContext(string[] args)
    {
        var optionsBuilder =
            new DbContextOptionsBuilder<CommunicationDbContext>();

        // Migration generation does not require connecting to this database.
        // Runtime configuration will use ConnectionStrings:Communication.
        const string designTimeConnection =
            "Server=127.0.0.1,1433;"
            + "Database=BotGlobalCommunication_DesignTime;"
            + "User Id=design_time;"
            + "Password=DesignTimeOnly_NotUsed;"
            + "Encrypt=False;"
            + "TrustServerCertificate=True";

        optionsBuilder.UseSqlServer(
            designTimeConnection,
            sqlServer =>
                sqlServer.MigrationsHistoryTable(
                    "__EFMigrationsHistory",
                    "communication"));

        return new CommunicationDbContext(
            optionsBuilder.Options);
    }
}
