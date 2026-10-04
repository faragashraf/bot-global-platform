using BotGlobal.Catalog.Application;
using BotGlobal.Catalog.Application.Admin;
using BotGlobal.Catalog.Infrastructure;
using BotGlobal.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.Catalog;

public static class CatalogModule
{
    public static IServiceCollection AddCatalogModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var connectionString = configuration.GetConnectionString("Catalog");
        if (string.IsNullOrWhiteSpace(connectionString))
        {
            throw new InvalidOperationException(
                "Catalog persistence requires the 'ConnectionStrings:Catalog' configuration value.");
        }

        services.AddDbContext<CatalogDbContext>(options =>
            options.UseBotGlobalDatabase(
                configuration,
                "Catalog",
                connectionString,
                CatalogDbContext.Schema,
                CatalogDbContext.MigrationHistoryTable,
                typeof(CatalogDbContext).Assembly.FullName));
        services.AddScoped<IPublicCatalogQueries, PublicCatalogQueries>();
        services.AddSingleton<IMediaUrlResolver, NullMediaUrlResolver>();

        services.AddScoped<IAdminCatalogQueryService, AdminCatalogQueryService>();
        services.AddScoped<IAdminCatalogCommandService, AdminCatalogCommandService>();

        return services;
    }
}
