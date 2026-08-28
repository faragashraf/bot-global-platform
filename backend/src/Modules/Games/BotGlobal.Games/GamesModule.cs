using BotGlobal.Games.Application.Entitlements;
using BotGlobal.Games.Application.Invitations;
using BotGlobal.Games.Application.Sessions;
using BotGlobal.Games.Application.Startup;
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
        services.Configure<FamilyGamesVersionPolicyOptions>(
            configuration.GetSection(FamilyGamesVersionPolicyOptions.SectionName));
        services.AddOptions<GameInvitationOptions>()
            .Bind(configuration.GetSection(GameInvitationOptions.SectionName))
            .Validate(
                value => value.LifetimeMinutes is >= 1 and <= 1440,
                "Family Games invitation lifetime must be between 1 and 1440 minutes.")
            .Validate(
                value => Uri.TryCreate(value.DeepLinkBase, UriKind.Absolute, out var link) &&
                    (link.Scheme == "familygames" || link.Scheme == Uri.UriSchemeHttps),
                "Family Games invitation deep links require the familygames or HTTPS scheme.")
            .ValidateOnStart();
        services.AddSingleton<ApplicationVersionPolicyReader>();
        services.AddScoped<IGameSessionService, GameSessionService>();
        services.AddScoped<IGameInvitationService, GameInvitationService>();
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
