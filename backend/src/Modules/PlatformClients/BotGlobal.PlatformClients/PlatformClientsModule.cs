using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.PlatformClients;

public static class PlatformClientsModule
{
    public const string ConnectionStringName = "PlatformClients";
    public const string DatabaseSchema = "platform_clients";
    public const string MigrationsHistoryTableName = "__EFMigrationsHistory";

    public static IServiceCollection AddPlatformClientsModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(configuration);

        var connectionString =
            configuration.GetConnectionString(ConnectionStringName);

        if (string.IsNullOrWhiteSpace(connectionString))
        {
            throw new InvalidOperationException(
                $"Connection string '{ConnectionStringName}' is required for the PlatformClients module.");
        }

        services.AddDbContext<PlatformClientsDbContext>(
            options =>
                options.UseSqlServer(
                    connectionString,
                    sqlServer =>
                        sqlServer.MigrationsHistoryTable(
                            MigrationsHistoryTableName,
                            DatabaseSchema)));

        return services;
    }
}
