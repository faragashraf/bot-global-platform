using BotGlobal.Contracts.Mobile;
using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Communication.Endpoints;
using BotGlobal.Communication.Application.Delivery;
using BotGlobal.Communication.Application.Abstractions;
using BotGlobal.Communication.Application.Foundation;
using BotGlobal.Communication.Hubs;
using BotGlobal.Communication.Infrastructure.Persistence;
using BotGlobal.Communication.Realtime;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.Communication;

public static class CommunicationModule
{
    public const string ConnectionStringName = "Communication";
    public const string DatabaseSchema = "communication";
    public const string MigrationsHistoryTableName = "__EFMigrationsHistory";

    public static IServiceCollection AddCommunicationModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(configuration);

        var connectionString =
            configuration.GetConnectionString(ConnectionStringName);

        if (string.IsNullOrWhiteSpace(connectionString))
        {
            throw new InvalidOperationException(
                $"Connection string '{ConnectionStringName}' is required for the Communication module.");
        }

        services.AddDbContext<CommunicationDbContext>(
            options =>
                options.UseSqlServer(
                    connectionString,
                    sqlServer =>
                        sqlServer.MigrationsHistoryTable(
                            MigrationsHistoryTableName,
                            DatabaseSchema)));

        services.AddSignalR();

        services.AddScoped<
            ICommunicationDelivery,
            SignalRCommunicationDelivery>();

        services.AddScoped<
            IMobileNotificationService,
            MobileNotificationService>();

        services.AddSingleton<
            IMobileNotificationConnectionRegistry,
            MobileNotificationConnectionRegistry>();

        services.AddScoped<
            IMobileNotificationDelivery,
            SignalRMobileNotificationDelivery>();


        services.AddSingleton<UserConnectionTracker>();

        services.AddScoped<
            ICommunicationAuthorizer,
            FoundationCommunicationAuthorizer>();

        services.AddScoped<
            ICommunicationPreferencesReader,
            FoundationCommunicationPreferencesReader>();

        return services;
    }

    public static IEndpointRouteBuilder MapCommunicationModule(
        this IEndpointRouteBuilder endpoints,
        MobileNotificationMachineAuthorizationOptions notificationAuthorization)
    {
        endpoints.MapHub<CommunicationHub>(
            "/hubs/communications");

        endpoints.MapHub<MobileNotificationsHub>(
            MobileNotificationRealtimeContract.HubPath);

        endpoints.MapCommunicationTestEndpoints();

        endpoints.MapMobileNotificationEndpoints(
            notificationAuthorization);

        return endpoints;
    }
}
