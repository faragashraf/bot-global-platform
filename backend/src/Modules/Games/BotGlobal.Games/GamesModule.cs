using BotGlobal.Games.Application.Entitlements;
using BotGlobal.Games.Application.Sessions;
using BotGlobal.Games.Endpoints;
using BotGlobal.Games.Infrastructure.Persistence;
using BotGlobal.Games.Realtime;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.Games;

public static class GamesModule
{
    public static IServiceCollection AddGamesModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var connectionString = configuration.GetConnectionString("Games");
        if (string.IsNullOrWhiteSpace(connectionString))
        {
            throw new InvalidOperationException("Games persistence requires the 'ConnectionStrings:Games' configuration value.");
        }

        services.AddDbContext<GamesDbContext>(options =>
            options.UseSqlServer(
                connectionString,
                sql =>
                {
                    sql.MigrationsAssembly(typeof(GamesDbContext).Assembly.FullName);
                    sql.MigrationsHistoryTable(GamesDbContext.MigrationHistoryTable, GamesDbContext.Schema);
                }));
        services.AddSignalR(options => options.EnableDetailedErrors = false);
        services.AddScoped<IGameSessionService, GameSessionService>();
        services.AddScoped<IGameEntitlementAuthorizer, FreeGameEntitlementAuthorizer>();
        services.AddScoped<IGameNotificationPublisher, DeferredGameNotificationPublisher>();
        services.AddScoped<IGameRealtimeNotifier, GameRealtimeNotifier>();
        services.AddSingleton<GameConnectionRegistry>();
        return services;
    }

    public static IEndpointRouteBuilder MapGamesModule(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGameSessionEndpoints();
        endpoints.MapHub<GamesHub>("/hubs/games");
        return endpoints;
    }
}
