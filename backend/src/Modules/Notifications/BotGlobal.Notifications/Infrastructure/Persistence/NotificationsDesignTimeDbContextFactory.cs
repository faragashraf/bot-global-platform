using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace BotGlobal.Notifications.Infrastructure.Persistence;

public sealed class NotificationsDesignTimeDbContextFactory
    : IDesignTimeDbContextFactory<NotificationsDbContext>
{
    public NotificationsDbContext CreateDbContext(string[] args)
    {
        // Runtime configuration uses ConnectionStrings:Notifications.
        var connectionString =
            "Server=(localdb)\\mssqllocaldb;"
            + "Database=BotGlobal.Notifications.Design;"
            + "Trusted_Connection=True;TrustServerCertificate=True";

        var options = new DbContextOptionsBuilder<NotificationsDbContext>()
            .UseSqlServer(
                connectionString,
                sqlServer => sqlServer.MigrationsHistoryTable(
                    NotificationsModule.MigrationsHistoryTableName,
                    NotificationsModule.DatabaseSchema))
            .Options;

        return new NotificationsDbContext(options);
    }
}
