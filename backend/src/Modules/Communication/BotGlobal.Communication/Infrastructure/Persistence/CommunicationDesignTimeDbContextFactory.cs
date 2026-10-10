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

        // Offline model generation only: no credentials, configuration discovery or connection.
        // Legacy migrations/model fragments still require a separate PostgreSQL review.
        // Chat activation uses the reviewed standalone SQL, never the full legacy chain.
        const string designTimeConnection =
            "Host=127.0.0.1;Database=chat_design_time;Username=design_time";

        optionsBuilder.UseNpgsql(
            designTimeConnection,
            postgres =>
                postgres.MigrationsHistoryTable(
                    "__EFMigrationsHistory",
                    "communication"));

        return new CommunicationDbContext(
            optionsBuilder.Options);
    }
}
