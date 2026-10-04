using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;

namespace BotGlobal.Persistence;

public static class BotGlobalDatabaseOptions
{
    public const string ProviderConfigurationKey = "Database:Provider";
    public const string CanaryEnsureCreatedConfigurationKey =
        "Database:CanarySchema:EnsureCreated";

    public static bool IsPostgreSql(IConfiguration configuration) =>
        string.Equals(
            ResolveProvider(configuration),
            "PostgreSql",
            StringComparison.OrdinalIgnoreCase);

    public static bool IsCanaryEnsureCreatedEnabled(
        IConfiguration configuration) =>
        bool.TryParse(
            configuration[CanaryEnsureCreatedConfigurationKey],
            out var enabled)
        && enabled;

    public static DbContextOptionsBuilder UseBotGlobalDatabase(
        this DbContextOptionsBuilder options,
        IConfiguration configuration,
        string connectionStringName,
        string connectionString,
        string schema,
        string migrationsHistoryTable,
        string? migrationsAssembly = null)
    {
        ArgumentNullException.ThrowIfNull(options);
        ArgumentNullException.ThrowIfNull(configuration);

        return ResolveProvider(configuration) switch
        {
            "SqlServer" => UseSqlServer(
                options,
                connectionString,
                schema,
                migrationsHistoryTable,
                migrationsAssembly),
            "PostgreSql" => UsePostgreSql(
                options,
                connectionString,
                schema,
                migrationsHistoryTable,
                migrationsAssembly),
            var provider => throw new InvalidOperationException(
                $"Unsupported database provider '{provider}' for '{connectionStringName}'. Use 'SqlServer' or 'PostgreSql'.")
        };
    }

    private static string ResolveProvider(IConfiguration configuration)
    {
        var provider = configuration[ProviderConfigurationKey];
        return string.IsNullOrWhiteSpace(provider) ? "SqlServer" : provider;
    }

    private static DbContextOptionsBuilder UseSqlServer(
        DbContextOptionsBuilder options,
        string connectionString,
        string schema,
        string migrationsHistoryTable,
        string? migrationsAssembly)
    {
        return options.UseSqlServer(
            connectionString,
            sqlServer =>
            {
                if (!string.IsNullOrWhiteSpace(migrationsAssembly))
                {
                    sqlServer.MigrationsAssembly(migrationsAssembly);
                }

                sqlServer.MigrationsHistoryTable(
                    migrationsHistoryTable,
                    schema);
            });
    }

    private static DbContextOptionsBuilder UsePostgreSql(
        DbContextOptionsBuilder options,
        string connectionString,
        string schema,
        string migrationsHistoryTable,
        string? migrationsAssembly)
    {
        return options.UseNpgsql(
            connectionString,
            npgsql =>
            {
                if (!string.IsNullOrWhiteSpace(migrationsAssembly))
                {
                    npgsql.MigrationsAssembly(migrationsAssembly);
                }

                npgsql.MigrationsHistoryTable(
                    migrationsHistoryTable,
                    schema);
            });
    }
}
