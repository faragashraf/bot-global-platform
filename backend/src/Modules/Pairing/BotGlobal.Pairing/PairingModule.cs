using BotGlobal.Contracts.Mobile;
using BotGlobal.Pairing.Application.Notifications;
using System.Threading.RateLimiting;
using BotGlobal.Pairing.Application;
using BotGlobal.Pairing.Endpoints;
using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace BotGlobal.Pairing;

public static class PairingModule
{
    public const string ConnectionStringName = "Pairing";
    public const string DatabaseSchema = "pairing";
    public const string MigrationsHistoryTableName = "__EFMigrationsHistory";

    public const string MachineCreateRateLimitPolicy = "pairing-machine-create";
    public const string MachineStatusRateLimitPolicy = "pairing-machine-status";
    public const string MobileClaimRateLimitPolicy = "pairing-mobile-claim";

    public static IServiceCollection AddPairingModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(configuration);

        var connectionString =
            configuration.GetConnectionString(ConnectionStringName);

        if (string.IsNullOrWhiteSpace(connectionString))
        {
            throw new InvalidOperationException(
                $"Connection string '{ConnectionStringName}' is required for the Pairing module.");
        }

        services.AddDbContext<PairingDbContext>(
            options =>
                options.UseSqlServer(
                    connectionString,
                    sqlServer =>
                        sqlServer.MigrationsHistoryTable(
                            MigrationsHistoryTableName,
                            DatabaseSchema)));

        services.TryAddSingleton(TimeProvider.System);
        services.AddSingleton<IPairingTokenService, PairingTokenService>();
        services.AddScoped<IPairingChallengeService, PairingChallengeService>();
        services.AddScoped<
            IMobileRecipientResolver,
            PairingMobileNotificationRecipientResolver>();

        services.AddScoped<IMobileDeviceLifecycleService, MobileDeviceLifecycleService>();
        services.AddSingleton<IMobileDeviceCredentialService, MobileDeviceCredentialService>();
        services.AddScoped<
            IMobileDeviceAuthenticator,
            MobileDeviceAuthenticator>();


        services.AddRateLimiter(
            options =>
            {
                options.AddPolicy(
                    MachineCreateRateLimitPolicy,
                    context => RateLimitPartition.GetFixedWindowLimiter(
                        GetRemotePartitionKey(context, "machine-create"),
                        _ => new FixedWindowRateLimiterOptions
                        {
                            AutoReplenishment = true,
                            PermitLimit = 12,
                            QueueLimit = 0,
                            Window = TimeSpan.FromMinutes(1)
                        }));

                options.AddPolicy(
                    MachineStatusRateLimitPolicy,
                    context => RateLimitPartition.GetFixedWindowLimiter(
                        GetRemotePartitionKey(context, "machine-status"),
                        _ => new FixedWindowRateLimiterOptions
                        {
                            AutoReplenishment = true,
                            PermitLimit = 120,
                            QueueLimit = 0,
                            Window = TimeSpan.FromMinutes(1)
                        }));

                options.AddPolicy(
                    MobileClaimRateLimitPolicy,
                    context => RateLimitPartition.GetFixedWindowLimiter(
                        GetRemotePartitionKey(context, "mobile-claim"),
                        _ => new FixedWindowRateLimiterOptions
                        {
                            AutoReplenishment = true,
                            PermitLimit = 20,
                            QueueLimit = 0,
                            Window = TimeSpan.FromMinutes(1)
                        }));
            });

        return services;
    }

    public static IEndpointRouteBuilder MapPairingModule(
        this IEndpointRouteBuilder endpoints,
        PairingMachineAuthorizationOptions machineAuthorization)
    {
        ArgumentNullException.ThrowIfNull(machineAuthorization);

        endpoints.MapPairingEndpoints(machineAuthorization);
        return endpoints;
    }

    private static string GetRemotePartitionKey(
        Microsoft.AspNetCore.Http.HttpContext context,
        string purpose)
        => $"{purpose}:"
           + (context.Connection.RemoteIpAddress?.ToString()
              ?? "unknown");
}
