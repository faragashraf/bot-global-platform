using BotGlobal.Pairing.Application.PushRegistrations;
using BotGlobal.Pairing.Application.MobileDevices;
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
using BotGlobal.Contracts.Notifications;
using BotGlobal.Contracts.Calling;
using BotGlobal.Pairing.Application.Calling;
using BotGlobal.Pairing.Application.Profiles;

namespace BotGlobal.Pairing;

public static class PairingModule
{
    public const string ConnectionStringName = "Pairing";
    public const string DatabaseSchema = "pairing";
    public const string MigrationsHistoryTableName = "__EFMigrationsHistory";

    public const string MachineCreateRateLimitPolicy = "pairing-machine-create";
    public const string MachineStatusRateLimitPolicy = "pairing-machine-status";
    public const string MobileClaimRateLimitPolicy = "pairing-mobile-claim";
    public const string MobileEnrollmentRateLimitPolicy = "pairing-mobile-enrollment";
    public const string MachineProfilePublishRateLimitPolicy = "profile-machine-publish";
    public const string MobileProfileReadRateLimitPolicy = "profile-mobile-read";

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

        services.AddScoped<
            IMobileBroadcastAudienceReader,
            PairingMobileBroadcastAudienceReader>();

        services.AddScoped<IMobileDeviceLifecycleService, MobileDeviceLifecycleService>();
        services.AddScoped<IMobileDeviceEnrollmentService, MobileDeviceEnrollmentService>();
        services.AddScoped<IMobileProfileSnapshotService, MobileProfileSnapshotService>();
        services.AddSingleton<IMobileDeviceCredentialService, MobileDeviceCredentialService>();
        services.AddScoped<
            IMobileDeviceAuthenticator,
            MobileDeviceAuthenticator>();

        services.AddScoped<MobileDeviceAuditRecorder>();

        services.AddScoped<
            Application.AdminDevicePairings.IAdminDevicePairingService,
            Application.AdminDevicePairings.AdminDevicePairingService>();

        services.AddScoped<MobilePushRegistrationService>();
        services.AddScoped<IMobilePushRegistrationService>(provider =>
            provider.GetRequiredService<MobilePushRegistrationService>());
        services.AddScoped<IMobilePushDestinationInvalidator>(provider =>
            provider.GetRequiredService<MobilePushRegistrationService>());

        services.AddScoped<
            IMobilePushDestinationResolver,
            MobilePushDestinationResolver>();
        services.AddScoped<ICallingReachabilityResolver, PairingCallingReachabilityResolver>();


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

                options.AddPolicy(
                    MobileEnrollmentRateLimitPolicy,
                    context => RateLimitPartition.GetFixedWindowLimiter(
                        GetApplicationIdentityPartitionKey(context),
                        _ => new FixedWindowRateLimiterOptions
                        {
                            AutoReplenishment = true,
                            PermitLimit = 12,
                            QueueLimit = 0,
                            Window = TimeSpan.FromMinutes(1)
                        }));

                options.AddPolicy(
                    MachineProfilePublishRateLimitPolicy,
                    context => RateLimitPartition.GetFixedWindowLimiter(
                        GetRemotePartitionKey(context, "profile-publish"),
                        _ => new FixedWindowRateLimiterOptions
                        {
                            AutoReplenishment = true,
                            PermitLimit = 120,
                            QueueLimit = 0,
                            Window = TimeSpan.FromMinutes(1)
                        }));

                options.AddPolicy(
                    MobileProfileReadRateLimitPolicy,
                    context => RateLimitPartition.GetFixedWindowLimiter(
                        GetRemotePartitionKey(context, "profile-read"),
                        _ => new FixedWindowRateLimiterOptions
                        {
                            AutoReplenishment = true,
                            PermitLimit = 60,
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
        endpoints.MapMobileProfileEndpoints(machineAuthorization);
        endpoints.MapAdminDevicePairingEndpoints();
        return endpoints;
    }

    private static string GetRemotePartitionKey(
        Microsoft.AspNetCore.Http.HttpContext context,
        string purpose)
        => $"{purpose}:"
           + (context.Connection.RemoteIpAddress?.ToString()
              ?? "unknown");

    private static string GetApplicationIdentityPartitionKey(
        Microsoft.AspNetCore.Http.HttpContext context)
    {
        var application = context.User.FindFirst(
            BotGlobal.Contracts.Mobile.ApplicationIdentityDefaults.ApplicationKeyClaim)?.Value;
        var subject = context.User.FindFirst(
            System.Security.Claims.ClaimTypes.NameIdentifier)?.Value;

        return !string.IsNullOrWhiteSpace(application)
               && !string.IsNullOrWhiteSpace(subject)
            ? $"mobile-enrollment:{application}:{subject}"
            : GetRemotePartitionKey(context, "mobile-enrollment");
    }
}
