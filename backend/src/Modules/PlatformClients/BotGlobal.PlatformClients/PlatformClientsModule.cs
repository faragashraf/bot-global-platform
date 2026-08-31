using BotGlobal.PlatformClients.Application.Capabilities;
using BotGlobal.PlatformClients.Application.Credentials;
using BotGlobal.PlatformClients.Application.Queries;
using BotGlobal.PlatformClients.Application.Provisioning;
using BotGlobal.PlatformClients.Application.Authentication;
using BotGlobal.PlatformClients.Application.Security;
using BotGlobal.PlatformClients.Authentication;
using BotGlobal.PlatformClients.Authorization;
using BotGlobal.PlatformClients.Endpoints;
using BotGlobal.PlatformClients.Infrastructure.Security;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Routing;
using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using BotGlobal.Contracts.Notifications;

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

        services.AddSingleton<
            IPlatformClientSecretService,
            PlatformClientSecretService>();

        services.AddScoped<
            IPlatformClientAuthenticationStore,
            EfPlatformClientAuthenticationStore>();

        services.AddScoped<
            IPlatformClientAuthenticator,
            PlatformClientAuthenticator>();

        services.AddScoped<
            IAuthorizationHandler,
            PlatformClientCapabilityHandler>();

        services.AddAuthentication()
            .AddScheme<
                AuthenticationSchemeOptions,
                PlatformClientAuthenticationHandler>(
                PlatformClientAuthenticationDefaults.Scheme,
                _ => { });


        services.AddSingleton<
            IPlatformCapabilityCatalog,
            PlatformCapabilityCatalog>();

        services.AddScoped<
            IPlatformClientCapabilityService,
            PlatformClientCapabilityService>();

        services.AddScoped<
            IPlatformClientProvisioningService,
            PlatformClientProvisioningService>();


        services.AddScoped<
            IPlatformClientQueryService,
            PlatformClientQueryService>();

        services.AddScoped<
            IPlatformClientDescriptorReader,
            PlatformClientDescriptorReader>();

        services.AddScoped<
            IPlatformClientApplicationResolver,
            PlatformClientDescriptorReader>();


        services.AddScoped<
            IPlatformClientCredentialLifecycleService,
            PlatformClientCredentialLifecycleService>();

        return services;
    }

    public static IEndpointRouteBuilder MapPlatformClientsModule(
        this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapPlatformClientProbeEndpoints();
        endpoints.MapPlatformClientAdminEndpoints();
        return endpoints;
    }

}
