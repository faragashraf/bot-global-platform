using Microsoft.AspNetCore.Builder;
using BotGlobal.Communication.Application.Abstractions;
using BotGlobal.Communication.Application.Foundation;
using BotGlobal.Communication.Hubs;
using BotGlobal.Communication.Realtime;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.Communication;

public static class CommunicationModule
{
    public static IServiceCollection AddCommunicationModule(
        this IServiceCollection services)
    {
        services.AddSignalR();
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
        this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapHub<CommunicationHub>("/hubs/communications");
        return endpoints;
    }
}
