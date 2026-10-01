using Microsoft.EntityFrameworkCore;
using SentriCam.Contracts.Hub;

namespace SentriCam.Infrastructure.Persistence;

public static class DatabaseProviderConfigurator
{
    public static DbContextOptionsBuilder ConfigureSentriCamProvider(
        this DbContextOptionsBuilder options,
        string provider,
        string connectionString)
    {
        Configure(options, provider, connectionString);
        return options;
    }

    public static DbContextOptionsBuilder<TContext> ConfigureSentriCamProvider<TContext>(
        this DbContextOptionsBuilder<TContext> options,
        string provider,
        string connectionString)
        where TContext : DbContext
    {
        Configure(options, provider, connectionString);
        return options;
    }

    private static void Configure(
        DbContextOptionsBuilder options,
        string provider,
        string connectionString)
    {
        ArgumentNullException.ThrowIfNull(options);
        ArgumentException.ThrowIfNullOrWhiteSpace(connectionString);

        if (provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
        {
            options.UseSqlite(
                connectionString,
                sqlite => sqlite.MigrationsAssembly("SentriCam.Migrations.Sqlite"));
            return;
        }
        if (provider.Equals(HubDatabaseProviders.PostgreSql, StringComparison.OrdinalIgnoreCase))
        {
            options.UseNpgsql(
                connectionString,
                postgres => postgres.MigrationsAssembly("SentriCam.Migrations.PostgreSql"));
            return;
        }
        if (provider.Equals(HubDatabaseProviders.SqlServer, StringComparison.OrdinalIgnoreCase))
        {
            options.UseSqlServer(
                connectionString,
                sql =>
                {
                    sql.MigrationsAssembly(typeof(SentriCamDbContext).Assembly.GetName().Name);
                    sql.EnableRetryOnFailure();
                });
            return;
        }

        throw new InvalidOperationException($"Unsupported database provider '{provider}'.");
    }
}
