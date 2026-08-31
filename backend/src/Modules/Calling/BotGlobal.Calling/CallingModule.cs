using BotGlobal.Calling.Realtime;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.Calling;

public static class CallingModule
{
    public static IServiceCollection AddCallingModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        services.AddSignalR(options => options.EnableDetailedErrors = false);
        services.AddSingleton<CallSessionRegistry>();
        services.AddOptions<CallingIceOptions>()
            .Bind(configuration.GetSection(CallingIceOptions.SectionName))
            .Validate(
                options => options.CredentialLifetimeMinutes is >= 5 and <= 1440,
                "Calling ICE credential lifetime must be between 5 and 1440 minutes.")
            .ValidateOnStart();
        services.AddSingleton<CallingIceConfigurationProvider>();
        return services;
    }

    public static IEndpointRouteBuilder MapCallingModule(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapHub<CallingHub>("/hubs/calling");
        return endpoints;
    }
}
