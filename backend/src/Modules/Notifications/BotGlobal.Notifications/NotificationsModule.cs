using System.Security.Claims;
using System.Threading.RateLimiting;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Application.Processing;
using BotGlobal.Notifications.Endpoints;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace BotGlobal.Notifications;

public static class NotificationsModule
{
    public const string ConnectionStringName = "Notifications";
    public const string DatabaseSchema = "notifications";
    public const string MigrationsHistoryTableName = "__EFMigrationsHistory";
    public const string AdminCreateRateLimitPolicy =
        "notification-campaign-admin-create";

    public static IServiceCollection AddNotificationsModule(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(configuration);

        var connectionString = configuration.GetConnectionString(
            ConnectionStringName);

        if (string.IsNullOrWhiteSpace(connectionString))
        {
            throw new InvalidOperationException(
                $"Connection string '{ConnectionStringName}' is required for the Notifications module.");
        }

        services.AddDbContext<NotificationsDbContext>(options =>
            options.UseSqlServer(
                connectionString,
                sqlServer => sqlServer.MigrationsHistoryTable(
                    MigrationsHistoryTableName,
                    DatabaseSchema)));

        services.AddOptions<NotificationCampaignOptions>()
            .Bind(configuration.GetSection(NotificationCampaignOptions.SectionName))
            .Validate(ValidateOptions, "Notifications options are invalid.")
            .ValidateOnStart();

        services.TryAddSingleton(TimeProvider.System);
        services.AddScoped<INotificationCampaignService, NotificationCampaignService>();
        services.AddScoped<
            BotGlobal.Contracts.Notifications.INotificationDeviceLogReader,
            Application.NotificationDeviceLogService>();
        services.AddScoped<NotificationWorkClaimer>();
        services.AddScoped<NotificationAudienceExpander>();
        services.AddScoped<NotificationDeliveryAttemptProcessor>();
        services.AddScoped<NotificationDeliveryRecoveryProcessor>();
        services.AddScoped<NotificationCampaignSummaryService>();
        services.AddScoped<NotificationExpiryProcessor>();
        services.AddHostedService<NotificationCampaignBackgroundService>();

        services.AddRateLimiter(rateLimiter =>
        {
            rateLimiter.AddPolicy(
                AdminCreateRateLimitPolicy,
                context => RateLimitPartition.GetFixedWindowLimiter(
                    GetAdminPartitionKey(context),
                    _ => new FixedWindowRateLimiterOptions
                    {
                        AutoReplenishment = true,
                        PermitLimit = 6,
                        QueueLimit = 0,
                        Window = TimeSpan.FromMinutes(1)
                    }));
        });

        return services;
    }

    public static IEndpointRouteBuilder MapNotificationsModule(
        this IEndpointRouteBuilder endpoints)
    {
        return endpoints.MapNotificationCampaignAdminEndpoints();
    }

    private static bool ValidateOptions(NotificationCampaignOptions options)
    {
        return options.MinimumCampaignLifetimeDays >= 1
            && options.MaximumCampaignLifetimeDays
                >= options.MinimumCampaignLifetimeDays
            && options.MaximumCampaignLifetimeDays <= 28
            && options.DefaultCampaignLifetimeDays
                >= options.MinimumCampaignLifetimeDays
            && options.DefaultCampaignLifetimeDays
                <= options.MaximumCampaignLifetimeDays
            && options.Worker.BatchSize is >= 1 and <= 1000
            && options.Worker.PollIntervalSeconds >= 1
            && options.Worker.LeaseSeconds >= 10
            && options.Worker.MaxParallelDeliveries is >= 1 and <= 64
            && options.Retry.InitialDelaySeconds >= 1
            && options.Retry.MaximumDelayMinutes >= 1;
    }

    private static string GetAdminPartitionKey(HttpContext context)
    {
        return context.User.FindFirstValue(ClaimTypes.NameIdentifier)
            ?? context.Connection.RemoteIpAddress?.ToString()
            ?? "unknown";
    }
}
